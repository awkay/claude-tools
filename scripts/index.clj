(ns scripts.index
  "Production indexer over an arbitrary source tree.

   Re-runs are cheap:
     - File-SHA gate: unchanged files are skipped entirely (no per-fn work).
     - Function-SHA gate: within a changed file, unchanged fns are skipped too.
     - Trivial-shortcut: small pure fns with a docstring don't need the LLM.

   Cross-file caller info is preserved on every run: clj-kondo always analyzes
   the whole tree (it's fast), so caller_namespaces / example_callers stay
   accurate even when most fns are skip-cached."
  (:require
   [clojure.string :as str]
   [scripts.doctor :as doctor]
   [scripts.lib.claude-cli :as claude-cli]
   [scripts.lib.db :as db]
   [scripts.lib.kondo :as kondo]))

(def default-db-path
  (or (System/getenv "CODE_INDEX_DB")
      ".code-intelligence/code-index.db"))

(def default-model
  (or (System/getenv "CODE_INDEX_CLAUDE_MODEL") "haiku"))

(def default-parallel 20)

;; -----------------------------------------------------------------------------
;; Pipeline (lifted/cleaned from validate.clj)
;; -----------------------------------------------------------------------------

(defn- run-once
  [{:keys [model]} fn-rec]
  (let [t0 (System/currentTimeMillis)]
    {:result (claude-cli/analyze-fn {:model model :fn-record fn-rec})
     :ms     (- (System/currentTimeMillis) t0)}))

(defn- run-batch
  "One LLM call per file. Returns {:results {qname -> result-map} :ms long}."
  [{:keys [model]} filename fn-records]
  (let [t0     (System/currentTimeMillis)
        src    (try (slurp filename) (catch Exception _ ""))
        results (claude-cli/analyze-file {:model model
                                          :file-source src
                                          :fn-records fn-records})]
    {:results results
     :ms      (- (System/currentTimeMillis) t0)}))

(defn- store!
  [db {:keys [model]}
   fn-rec
   {:keys [description arg_descriptions return_description
           tags domain_signals general_purpose_score confidence
           analyzed-by-model]
    :or {analyzed-by-model model}}]
  (db/upsert-function!
   db (-> fn-rec
          (assoc :description-llm description
                 :arg-descriptions-llm arg_descriptions
                 :return-description-llm return_description
                 :tags-llm tags
                 :domain-signals-llm domain_signals
                 :general-purpose-score general_purpose_score
                 :confidence confidence
                 :analyzed-by-model analyzed-by-model
                 :callee-namespaces (->> (:callees fn-rec) (keep :to-ns) distinct vec))
          (select-keys
           [:ns :name :qualified-name :lang :sha
            :filename :line-start :line-end :col-start :col-end
            :arglists-edn :arities :defined-by :private? :docstring
            :pure-heuristic? :pure-heuristic-reasons :types-edn
            :caller-count :caller-namespaces :callee-namespaces :example-callers
            :description-llm :arg-descriptions-llm :return-description-llm
            :tags-llm :domain-signals-llm :general-purpose-score :confidence
            :analyzed-by-model]))))

(defn- parallel-map [n f coll]
  (->> coll
       (partition-all n)
       (mapcat (fn [batch]
                 (->> batch
                      (mapv #(future (f %)))
                      (mapv deref))))))

(defn- fn-already-indexed? [db {:keys [qualified-name lang sha]}]
  (= sha (:sha (db/fn-row-by-qname db qualified-name lang))))

(defn- pre-delete-orphans!
  "For each filename in `fn-records`, remove DB rows whose (qname,lang) is
   no longer in the current kondo output. Returns total rows deleted."
  [db fn-records]
  (let [by-file (group-by :filename fn-records)]
    (reduce
     (fn [n [filename recs]]
       (let [keep-set (->> recs
                           (map (juxt :qualified-name :lang))
                           (set))
             deleted  (db/delete-orphans-in-file! db filename keep-set)]
         (when (pos? deleted)
           (binding [*out* *err*]
             (println (format "  [PRUNE] %s: removed %d stale fn(s)" filename deleted))))
         (+ n deleted)))
     0
     by-file)))

(defn run-pipeline
  "Per-file batch pipeline: pre-delete orphans → group by file → one LLM call
   per file (parallel across files) → serial DB writes.
   Returns a seq of {:qname :ms :source}."
  [db opts fn-records]
  (pre-delete-orphans! db fn-records)
  (let [{cached true to-do false} (group-by #(fn-already-indexed? db %) fn-records)
        _ (binding [*out* *err*]
            (doseq [fn-rec cached]
              (println (format "  [SKIP] %s (sha cached)" (:qualified-name fn-rec)))))
        by-file  (group-by :filename to-do)
        batches
        (parallel-map
         (:parallel opts)
         (fn [[filename recs]]
           (let [{:keys [results ms]} (run-batch opts filename recs)]
             (binding [*out* *err*]
               (println (format "  [LLM] %-58s %4d ms (%d fn)"
                                filename ms (count recs))))
             {:filename filename :recs recs :results results :ms ms}))
         by-file)]
    (doseq [{:keys [recs results]} batches
            fn-rec recs]
      (store! db opts fn-rec (get results (:qualified-name fn-rec))))
    (concat
     (for [fn-rec cached]
       {:qname (:qualified-name fn-rec) :ms 0 :source :cached})
     (for [{:keys [recs ms]} batches
           fn-rec recs]
       ;; amortize batch ms across its fns for percentile reporting
       {:qname (:qualified-name fn-rec) :ms (quot ms (max 1 (count recs))) :source :llm}))))

;; -----------------------------------------------------------------------------
;; File-SHA gating
;; -----------------------------------------------------------------------------

(defn- partition-by-file-sha
  "Classify every fn-record by whether its FILE has changed since last index.
   Returns {:to-process [...] :skip-untouched [...] :file-shas {file -> sha}}."
  [db fn-records]
  (let [files     (distinct (map :filename fn-records))
        file-shas (into {} (map (fn [f] [f (kondo/file-sha f)]) files))
        changed?  (fn [f]
                    (not= (file-shas f)
                          (db/file-sha-stored db f)))
        {:keys [to-process skip-untouched]}
        (reduce
         (fn [acc fn-rec]
           (if (changed? (:filename fn-rec))
             (update acc :to-process conj fn-rec)
             (update acc :skip-untouched conj fn-rec)))
         {:to-process [] :skip-untouched []}
         fn-records)]
    {:to-process     to-process
     :skip-untouched skip-untouched
     :file-shas      file-shas}))

;; -----------------------------------------------------------------------------
;; CLI
;; -----------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [[a & rst] args opts {:db default-db-path :model default-model
                              :parallel default-parallel}
         positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")       (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--model")    (recur (rest rst) (assoc opts :model (first rst)) positional)
      (= a "--parallel") (recur (rest rst) (assoc opts :parallel (Integer/parseInt (first rst))) positional)
      (= a "--force")    (recur rst (assoc opts :force? true) positional)
      :else              (recur rst opts (conj positional a)))))

(defn- pct [xs p]
  (when (seq xs)
    (let [s (vec (sort xs)) idx (min (dec (count s)) (int (* p (count s))))]
      (nth s idx))))

(defn -main [& args]
  (let [[opts pos] (parse-args args)
        source-dir (first pos)]
    (when (or (nil? source-dir) (str/blank? source-dir))
      (println "usage: bb index [--db PATH] [--model NAME] [--parallel N] [--force] SOURCE-DIR")
      (System/exit 1))
    (binding [*out* *err*]
      (println "index: model =" (:model opts)
               "parallel =" (:parallel opts)
               "source =" source-dir)
      (when-not (doctor/ensure!) (System/exit 1)))
    (let [db        (db/open (:db opts))
          _         (binding [*out* *err*] (println "index: linting" source-dir))
          all-recs  (kondo/analyze source-dir)
          _         (binding [*out* *err*]
                      (println "  total fns:" (count all-recs)))
          {:keys [to-process skip-untouched file-shas]}
          (if (:force? opts)
            {:to-process all-recs :skip-untouched [] :file-shas {}}
            (partition-by-file-sha db all-recs))
          _         (binding [*out* *err*]
                      (println "  file-SHA cache: skip" (count skip-untouched) "fns;"
                               "process" (count to-process) "fns"))
          t0        (System/currentTimeMillis)
          rows      (doall (run-pipeline db opts to-process))
          wall-ms   (- (System/currentTimeMillis) t0)
          llm-rows  (filter #(= :llm (:source %)) rows)
          ms        (map :ms llm-rows)]
      ;; Update file-SHA records for ALL files we saw (even unchanged — keeps cache fresh)
      (doseq [[file sha] file-shas]
        (when sha (db/record-file-sha! db file sha)))
      (println)
      (println "Done.")
      (printf "  wall-clock:    %d ms\n" wall-ms)
      (printf "  fns linted:    %d\n" (count all-recs))
      (printf "  file-skipped:  %d (file SHA unchanged)\n" (count skip-untouched))
      (printf "  fn-skipped:    %d (function SHA unchanged)\n"
              (count (filter #(= :cached (:source %)) rows)))
      (printf "  LLM:           %d" (count llm-rows))
      (when (seq ms)
        (printf "  (p50=%d  p95=%d  max=%d ms)" (pct ms 0.5) (pct ms 0.95) (apply max ms)))
      (println)
      (println "  DB rows total:"
               (-> (db/query (:db opts) "SELECT COUNT(*) AS n FROM functions")
                   first :n)))))
