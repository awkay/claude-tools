(ns scripts.embed
  "`code-search embed-index` — build/refresh per-function embeddings via a local
   Ollama model. Incremental: skips any function whose (model, content) SHA is
   unchanged.

   Re-running after the LLM-described fields change (e.g. after `index`) is
   cheap — only changed functions get re-embedded."
  (:require
   [clojure.string :as str]
   [scripts.lib.db :as db]
   [scripts.lib.embeddings :as emb]))

(def default-db-path
  (or (System/getenv "CODE_INDEX_DB")
      ".code-intelligence/code-index.db"))

(def default-parallel 2)

;; -----------------------------------------------------------------------------
;; Progress bar
;; -----------------------------------------------------------------------------

(def ^:private bar-lock (Object.))
(def ^:private bar-width 30)

(defn- tty? []
  ;; System/console returns nil when stderr/stdout is redirected. Good enough.
  (some? (System/console)))

(defn- fmt-eta [secs]
  (let [s (long secs)]
    (cond
      (< s 60)   (format "%ds" s)
      (< s 3600) (format "%dm%02ds" (quot s 60) (mod s 60))
      :else      (format "%dh%02dm" (quot s 3600) (mod (quot s 60) 60)))))

(defn- render-bar [done total errs elapsed-ms]
  (let [pct      (if (pos? total) (/ done (double total)) 1.0)
        filled   (long (* bar-width pct))
        bar      (str (apply str (repeat filled \#))
                      (apply str (repeat (- bar-width filled) \-)))
        rate     (if (pos? elapsed-ms) (/ done (/ elapsed-ms 1000.0)) 0.0)
        remain   (- total done)
        eta-s    (if (pos? rate) (/ remain rate) 0)]
    (format "  [%s] %d/%d (%3d%%)  %.1f/s  ETA %s  err %d"
            bar done total (long (* 100 pct)) rate (fmt-eta eta-s) errs)))

(defn- print-progress! [done total errs elapsed-ms final?]
  (locking bar-lock
    (binding [*out* *err*]
      (if (tty?)
        (do (print "\r") (print (render-bar done total errs elapsed-ms))
            (when final? (println))
            (flush))
        (when (or final? (zero? (mod done 50)))
          (println (render-bar done total errs elapsed-ms)))))))

(def help-text
  (str/trim "
usage: code-search embed-index [OPTIONS]

Build/refresh embeddings for every function in the index using a local Ollama
embedding model. Incremental — only changed (or missing) rows are recomputed.

Options:
  --db PATH         SQLite path (default $CODE_INDEX_DB or .code-intelligence/code-index.db)
  --model NAME      Ollama embedding model (default 'nomic-embed-text')
  --host URL        Ollama base URL (default http://localhost:11434)
  --force           Recompute every embedding even if SHA matches
  --rebuild         Drop all rows for the chosen model before embedding
  --help            Show this message
"))

(defn- parse-args [args]
  ;; Embedding concurrency is fixed at 2: Ollama is single-process so more
  ;; in-flight requests yield diminishing returns and increase the rate of
  ;; "Stream closed" connection collisions. The LLM `index` command's own
  ;; `--parallel` is unrelated and unaffected.
  (loop [[a & rst] args opts {:db default-db-path
                              :model emb/default-model
                              :host emb/default-host
                              :parallel default-parallel}]
    (cond
      (nil? a) opts
      (= a "--db")       (recur (rest rst) (assoc opts :db (first rst)))
      (= a "--model")    (recur (rest rst) (assoc opts :model (first rst)))
      (= a "--host")     (recur (rest rst) (assoc opts :host (first rst)))
      (= a "--force")    (recur rst (assoc opts :force? true))
      (= a "--rebuild")  (recur rst (assoc opts :rebuild? true))
      (or (= a "--help") (= a "-h")) (recur rst (assoc opts :help? true))
      :else (recur rst opts))))

(defn- candidate-rows
  "All function rows with their text materialized. Returns a vec of
   {:id :text :content-sha}."
  [db model]
  (->> (db/query db
                 "SELECT id, qualified_name, arglists_edn, docstring, description_llm,
                         return_description_llm, tags_llm, domain_signals_llm,
                         arg_descriptions_llm
                    FROM functions")
       (mapv (fn [r]
               (let [text (emb/fn-row->embed-text r)]
                 {:id          (:id r)
                  :qname       (:qualified_name r)
                  :text        text
                  :content-sha (emb/content-sha model text)})))))

(defn- needs-embed?
  [db {:keys [id content-sha]} model force?]
  (or force?
      (let [cur (emb/current-row db id)]
        (or (nil? cur)
            (not= (:model cur) model)
            (not= (:content_sha cur) content-sha)))))

(defn- parallel-map [n f coll]
  (->> coll
       (partition-all n)
       (mapcat (fn [batch]
                 (->> batch (mapv #(future (f %))) (mapv deref))))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help? opts)
      (println help-text) (System/exit 0))
    (let [{:keys [db model host parallel force? rebuild?]} opts]
      (binding [*out* *err*]
        (println "embed-index: model =" model " host =" host " parallel =" parallel))
      (when-not (emb/ollama-reachable? host)
        (binding [*out* *err*]
          (println "error: Ollama not reachable at" host)
          (println "       start it with `ollama serve` (or pass --host).")
          (System/exit 1)))
      (let [db (db/open db)
            _  (db/ensure-embeddings-schema! db)
            _  (when rebuild?
                 (db/execute! db "DELETE FROM embeddings WHERE model = ?" model)
                 (binding [*out* *err*] (println "  [REBUILD] cleared rows for model")))
            all   (candidate-rows db model)
            todo  (filterv #(needs-embed? db % model force?) all)
            t0    (System/currentTimeMillis)
            done  (atom 0)
            errs  (atom 0)]
        (binding [*out* *err*]
          (println "  functions:" (count all)
                   " to-embed:" (count todo)
                   " (skip cached:" (- (count all) (count todo)) ")"))
        (when (pos? (count todo))
          (print-progress! 0 (count todo) 0 1 false))
        (dorun
         (parallel-map
          parallel
          (fn [{:keys [id qname text content-sha]}]
            (let [ok? (try
                        (let [v    (emb/embed text {:model model :host host})
                              blob (emb/vec->blob-str v)]
                          (emb/upsert! db {:function-id id
                                           :model       model
                                           :dim         (count v)
                                           :content-sha content-sha
                                           :vec-str     blob})
                          true)
                        (catch Exception e
                          (swap! errs inc)
                          (locking bar-lock
                            (binding [*out* *err*]
                              (when (tty?) (print "\r\033[K"))
                              (println (format "  [ERR] %s — %s" qname (.getMessage e)))))
                          false))]
              (when ok? (swap! done inc))
              (print-progress! @done (count todo) @errs
                               (- (System/currentTimeMillis) t0) false)))
          todo))
        (when (pos? (count todo))
          (print-progress! @done (count todo) @errs
                           (- (System/currentTimeMillis) t0) true))
        ;; Rebuild the packed binary corpus so subsequent searches are fast.
        ;; Only worth doing if at least one row succeeded (or we rebuilt).
        (when (or rebuild? (pos? @done))
          (let [pp (emb/pack-path (:db opts) model)
                t1 (System/currentTimeMillis)]
            (binding [*out* *err*]
              (print "  [pack] writing packed corpus..."))
            (let [n (emb/write-pack! db model pp)]
              (binding [*out* *err*]
                (println (format " %d vectors, %d ms" n
                                 (- (System/currentTimeMillis) t1)))))))
        (let [wall (- (System/currentTimeMillis) t0)]
          (println)
          (println "Done.")
          (printf  "  wall-clock:    %d ms\n" wall)
          (printf  "  embedded:      %d\n" @done)
          (printf  "  errors:        %d\n" @errs)
          (printf  "  rows in table: %d\n" (emb/count-for-model db model)))))))
