(ns scripts.instructions.index
  "Two-pass indexer for the instruction-retrieval DB.

   Pass 1: per-doc LLM emits summary/explains/tags, sha-gated.
   Pass 2: per-doc LLM picks typed edges from FTS-narrowed candidates,
           gated by a composite sha of (doc-sha + candidate shas)."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [scripts.instructions.db :as idb]
   [scripts.instructions.llm :as llm]
   [scripts.lib.sha :as sha]))

(def default-db-path
  (or (System/getenv "INSTRUCTIONS_DB")
      ".code-intelligence/instructions.db"))

(def default-candidate-k 25)
(def default-confidence-floor 0.5)

;; -----------------------------------------------------------------------------
;; FS walk + content extraction
;; -----------------------------------------------------------------------------

(defn- md-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (str/lower-case (.getName f)) ".md")))

(defn- walk-md [^String dir]
  (let [root (io/file dir)]
    (when-not (.exists root)
      (throw (ex-info (str "Directory does not exist: " dir) {:dir dir})))
    (->> (file-seq root)
         (filter md-file?)
         (sort-by #(.getPath ^java.io.File %)))))

(defn- project-relative
  "Return `path` relative to `project-root`, normalized with forward slashes.
   Falls back to the absolute path if it doesn't sit under project-root."
  [project-root ^java.io.File f]
  (let [root (.toPath (.getCanonicalFile (io/file project-root)))
        p    (.toPath (.getCanonicalFile f))]
    (if (.startsWith p root)
      (str (.relativize root p))
      (str p))))

(defn- extract-title
  "First H1 (# ...) in body, else nil."
  [body]
  (some->> (str/split-lines body)
           (some (fn [l]
                   (when-let [m (re-matches #"\s*#\s+(.+?)\s*" l)]
                     (str/trim (nth m 1)))))))

(defn- read-doc
  "Read one file and produce the canonical doc map (no LLM fields yet)."
  [project-root ^java.io.File f]
  (let [body (slurp f)
        sha  (sha/sha256 body)]
    {:path     (project-relative project-root f)
     :sha      sha
     :title    (extract-title body)
     :body     body
     :abs-path (.getCanonicalPath f)}))

;; -----------------------------------------------------------------------------
;; Pass 1
;;
;; CRITICAL: the go-sqlite3 pod is a single subprocess and is NOT safe for
;; concurrent calls — under contention it SIGSEGVs. The pipeline therefore
;; does all DB I/O SERIALLY:
;;   1. Serial pre-pass: read stored shas, decide LLM-vs-cached per doc.
;;   2. Parallel LLM: futures touch ONLY in-memory data, no DB.
;;   3. Serial post-pass: upsert all rows.
;; -----------------------------------------------------------------------------

(defn- pass1-plan
  "Serial DB read: classify each doc as :cached (reuse stored LLM fields) or
   :needs-llm. Returns a vector of maps with :doc, :stored (when cached),
   and :status."
  [db docs force?]
  (mapv (fn [{:keys [path sha] :as doc}]
          (let [stored (when-not force? (idb/get-doc db path))]
            (if (and stored (= (:sha stored) sha))
              {:doc    doc
               :status :cached
               :stored stored}
              {:doc doc :status :needs-llm})))
        docs))

(defn- pass1-enrich-cached [{:keys [doc stored]}]
  (assoc doc
         :summary  (:summary stored)
         :explains (idb/parse-explains stored)
         :tags     (idb/parse-tags stored)
         :model    (:analyzed_by_model stored)
         :reused?  true))

(defn- pass1-enrich-llm [model {:keys [doc]}]
  ;; pure: only shells out to claude -p; no DB.
  (let [r (llm/pass1 {:model model :doc doc})]
    (assoc doc
           :summary  (:summary r)
           :explains (:explains r)
           :tags     (:tags r)
           :model    model
           :reused?  false)))

;; -----------------------------------------------------------------------------
;; Pass 2
;; -----------------------------------------------------------------------------

(defn- candidate-query-text
  "Text used to find candidate neighbors via FTS: explains + summary."
  [{:keys [summary explains]}]
  (str/join " " (cons (or summary "") (or explains []))))

(defn- candidates-composite-sha
  "Composite sha for cache-gating pass 2: sha(doc-sha + sorted candidate shas)."
  [doc-sha candidate-shas]
  (sha/short-sig
   (sha/sha256 (str doc-sha "|" (str/join "," (sort candidate-shas))))))

(defn- pass2-plan
  "Serial DB pre-pass for pass 2. For each doc, FTS-narrow candidates and
   decide whether the LLM needs to run. Returns a vector of maps with all
   the data needed for the (DB-free) parallel LLM step."
  [db p1-results k force?]
  (mapv (fn [{:keys [path sha] :as doc-with-summary}]
          (let [qtext     (candidate-query-text doc-with-summary)
                cands-raw (idb/fts-candidates db path qtext k)
                cands     (mapv (fn [c]
                                  {:path     (:path c)
                                   :title    (:title c)
                                   :summary  (:summary c)
                                   :explains (idb/parse-explains c)
                                   :sha      (:sha c)})
                                cands-raw)
                comp-sha  (candidates-composite-sha sha (map :sha cands))
                stored-cs (some-> (idb/doc-row-by-path db path) :candidates_sha)
                skip?     (and (not force?)
                               stored-cs
                               (= stored-cs comp-sha))]
            {:doc        doc-with-summary
             :path       path
             :candidates cands
             :comp-sha   comp-sha
             :status     (cond
                           skip?           :cached
                           (empty? cands)  :no-candidates
                           :else           :needs-llm)}))
        p1-results))

(defn- pass2-enrich-llm
  "Pure (no DB) — runs the LLM on one plan entry whose status is :needs-llm."
  [model confidence-floor {:keys [doc candidates] :as entry}]
  (let [cand-paths (mapv :path candidates)
        result     (llm/pass2 {:model model :doc doc :candidates candidates})
        edges      (llm/edges-from-pass2 result cand-paths
                                         {:confidence-floor confidence-floor})]
    (assoc entry :edges edges)))

;; -----------------------------------------------------------------------------
;; Orchestration
;; -----------------------------------------------------------------------------

(defn- parallel-map
  "Bounded-parallel map: process `xs` in batches of `n` futures, preserving order."
  [n f xs]
  (->> xs
       (partition-all n)
       (mapcat (fn [batch] (->> batch (mapv #(future (f %))) (mapv deref))))
       vec))

(defn index!
  "Build/refresh the instruction index.

   opts:
     :db          DB path (default $INSTRUCTIONS_DB or .code-intelligence/instructions.db)
     :project-root  project root (default current dir)
     :model       Claude model alias (default haiku)
     :parallel    concurrent LLM calls (default 20)
     :k           candidate count per doc (default 25)
     :confidence-floor  min confidence for an edge to be stored (default 0.5)
     :force?      ignore caches"
  [dir {:keys [db project-root model parallel k confidence-floor force?]
        :or   {db               default-db-path
               project-root     (System/getProperty "user.dir")
               model            llm/default-model
               parallel         20
               k                default-candidate-k
               confidence-floor default-confidence-floor}}]
  (idb/open db)
  (let [files     (walk-md dir)
        _         (println (format "Found %d markdown files under %s" (count files) dir))
        docs      (mapv (partial read-doc project-root) files)
        root-rel  (project-relative project-root (io/file dir))

        ;; ---------- Pass 1 ----------
        _          (println "Pass 1: per-doc summary/explains/tags …")
        ;; (1) Serial pre-pass: classify each doc as :cached or :needs-llm.
        plan1      (pass1-plan db docs force?)
        cached1    (filterv #(= :cached (:status %))    plan1)
        needs1     (filterv #(= :needs-llm (:status %)) plan1)
        _          (println (format "  %d cached, %d to analyze" (count cached1) (count needs1)))
        ;; (2) Parallel LLM (no DB access in futures).
        llm1       (parallel-map parallel (partial pass1-enrich-llm model) needs1)
        cached-out (mapv pass1-enrich-cached cached1)
        p1-results (vec (concat cached-out llm1))
        ;; (3) Serial DB upsert.
        _          (doseq [d p1-results]
                     (idb/upsert-doc! db (assoc d :root-dir root-rel) :force? force?))
        analyzed   (count llm1)
        reused     (count cached-out)
        _          (println (format "Pass 1 done: %d analyzed, %d cached" analyzed reused))

        ;; ---------- Pass 2 ----------
        _           (println "Pass 2: typed-edge matching (FTS-narrowed) …")
        ;; (1) Serial pre-pass: FTS-narrow candidates, decide LLM-vs-skip.
        plan2       (pass2-plan db p1-results k force?)
        needs2      (filterv #(= :needs-llm (:status %)) plan2)
        nocands2    (filterv #(= :no-candidates (:status %)) plan2)
        cached2     (filterv #(= :cached (:status %)) plan2)
        _           (println (format "  %d cached, %d no-candidates, %d to analyze"
                                     (count cached2) (count nocands2) (count needs2)))
        ;; (2) Parallel LLM (no DB access).
        llm2        (parallel-map parallel
                                  (partial pass2-enrich-llm model confidence-floor)
                                  needs2)
        ;; (3) Serial DB write: edges + composite shas.
        _           (doseq [{:keys [path edges comp-sha]} llm2]
                      (idb/replace-edges! db path edges)
                      (idb/set-candidates-sha! db path comp-sha))
        _           (doseq [{:keys [path comp-sha]} nocands2]
                      (idb/replace-edges! db path [])
                      (idb/set-candidates-sha! db path comp-sha))
        ;; cached2 entries already have correct edges + comp-sha stored
        edged       (count llm2)
        skipped     (+ (count cached2) (count nocands2))
        total-edges (->> llm2 (mapcat :edges) count)]
    (println (format "Pass 2 done: %d analyzed, %d cached, %d edges written"
                     edged skipped total-edges))
    {:files (count files)
     :pass1 {:analyzed analyzed :reused reused}
     :pass2 {:analyzed edged :skipped skipped :edges total-edges}}))

;; -----------------------------------------------------------------------------
;; CLI entry
;; -----------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [[a & rst] args opts {} positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")       (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--model")    (recur (rest rst) (assoc opts :model (first rst)) positional)
      (= a "--parallel") (recur (rest rst) (assoc opts :parallel (Integer/parseInt (first rst))) positional)
      (= a "--k")        (recur (rest rst) (assoc opts :k (Integer/parseInt (first rst))) positional)
      (= a "--confidence-floor") (recur (rest rst) (assoc opts :confidence-floor (Double/parseDouble (first rst))) positional)
      (= a "--project-root") (recur (rest rst) (assoc opts :project-root (first rst)) positional)
      (= a "--force")    (recur rst (assoc opts :force? true) positional)
      :else              (recur rst opts (conj positional a)))))

(defn -main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: instructions index [--db PATH] [--model NAME] [--parallel N]")
          (println "                        [--k N] [--confidence-floor F]")
          (println "                        [--project-root PATH] [--force] DIR")
          (System/exit 1))
      (let [{:keys [files pass1 pass2]} (index! (first pos) opts)]
        (println (format "Indexed %d files. Pass1: %s. Pass2: %s."
                         files pass1 pass2))))))
