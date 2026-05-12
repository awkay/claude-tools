(ns scripts.instructions.db
  "SQLite ops for the instruction-retrieval index.

   Layout:
     docs   (path PK, sha, candidates_sha, summary, explains_json, tags_json, body, ...)
     edges  (src_path, dst_path, kind, confidence) — kind ∈ #{prereq companion extends}
     docs_fts — FTS5 mirror; triggers keep it in sync.

   See schema/instructions.sql."
  (:require
   [babashka.pods :as pods]
   [cheshire.core :as json]
   [clojure.string :as str]
   [scripts.lib.db :as basedb]))

;; Pod already loaded by scripts.lib.db; alias the same ns here.
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def schema-resource "schema/instructions.sql")
(def probe-table "docs")

(defn open
  "Open (or create) the instructions DB. Applies schema if absent."
  [path]
  (basedb/open-with-schema path probe-table schema-resource))

(defn rebuild-fts!
  "Drop and recreate docs_fts + triggers (pick up tokenizer changes) and
   reindex from docs."
  [db]
  (doseq [stmt ["DROP TRIGGER IF EXISTS docs_ai"
                "DROP TRIGGER IF EXISTS docs_ad"
                "DROP TRIGGER IF EXISTS docs_au"
                "DROP TABLE IF EXISTS docs_fts"]]
    (sqlite/execute! db [stmt]))
  (basedb/apply-schema! db (basedb/load-resource-sql schema-resource))
  (sqlite/execute! db ["INSERT INTO docs_fts(docs_fts) VALUES('rebuild')"]))

(defn- ->json [x] (when x (json/generate-string x)))
(defn- <-json [s] (when s (try (json/parse-string s true) (catch Exception _ nil))))

;; -----------------------------------------------------------------------------
;; docs table
;; -----------------------------------------------------------------------------

(defn doc-row-by-path [db path]
  (first (basedb/query db "SELECT id, sha, candidates_sha FROM docs WHERE path = ?" path)))

(defn upsert-doc!
  "Insert or update a doc row. Returns :inserted | :updated | :unchanged.
   :unchanged when stored sha matches `sha` AND we're not forcing."
  [db {:keys [path sha root-dir title summary explains tags body model]} & {:keys [force?]}]
  (let [now      (str (java.time.Instant/now))
        existing (doc-row-by-path db path)]
    (cond
      (and existing (= (:sha existing) sha) (not force?))
      :unchanged

      existing
      (do (basedb/execute! db
                           "UPDATE docs SET
                              sha=?, root_dir=?, title=?, summary=?,
                              explains_json=?, tags_json=?, body=?,
                              analyzed_by_model=?, indexed_at=?
                            WHERE id=?"
                           sha root-dir title summary
                           (->json explains) (->json tags) body
                           model now (:id existing))
          :updated)

      :else
      (do (basedb/execute! db
                           "INSERT INTO docs
                              (path, sha, root_dir, title, summary,
                               explains_json, tags_json, body,
                               analyzed_by_model, indexed_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?)"
                           path sha root-dir title summary
                           (->json explains) (->json tags) body
                           model now)
          :inserted))))

(defn set-candidates-sha! [db path candidates-sha]
  (basedb/execute! db "UPDATE docs SET candidates_sha=? WHERE path=?" candidates-sha path))

(defn delete-doc! [db path]
  (basedb/execute! db "DELETE FROM edges WHERE src_path=? OR dst_path=?" path path)
  (basedb/execute! db "DELETE FROM docs WHERE path=?" path))

(defn all-paths [db]
  (map :path (basedb/query db "SELECT path FROM docs ORDER BY path")))

(defn get-doc [db path]
  (first (basedb/query db "SELECT * FROM docs WHERE path=?" path)))

;; -----------------------------------------------------------------------------
;; edges table
;; -----------------------------------------------------------------------------

(defn replace-edges!
  "Transactionally replace all outgoing edges from `src-path`.
   `edges` is a seq of {:dst :kind :confidence}."
  [db src-path edges]
  (basedb/execute! db "DELETE FROM edges WHERE src_path=?" src-path)
  (doseq [{:keys [dst kind confidence]} edges
          :when (and dst kind (not= dst src-path))]
    (basedb/execute! db
                     "INSERT OR IGNORE INTO edges (src_path, dst_path, kind, confidence)
                      VALUES (?,?,?,?)"
                     src-path dst kind (or confidence 0.0))))

(defn outgoing-edges
  "All outgoing edges from `src-path`. Optional kind filter."
  ([db src-path] (outgoing-edges db src-path nil))
  ([db src-path kind]
   (if kind
     (basedb/query db
                   "SELECT dst_path, kind, confidence FROM edges
                    WHERE src_path=? AND kind=? ORDER BY confidence DESC"
                   src-path (name kind))
     (basedb/query db
                   "SELECT dst_path, kind, confidence FROM edges
                    WHERE src_path=? ORDER BY kind, confidence DESC"
                   src-path))))

(defn incoming-edges
  ([db dst-path] (incoming-edges db dst-path nil))
  ([db dst-path kind]
   (if kind
     (basedb/query db
                   "SELECT src_path, kind, confidence FROM edges
                    WHERE dst_path=? AND kind=? ORDER BY confidence DESC"
                   dst-path (name kind))
     (basedb/query db
                   "SELECT src_path, kind, confidence FROM edges
                    WHERE dst_path=? ORDER BY kind, confidence DESC"
                   dst-path))))

;; -----------------------------------------------------------------------------
;; FTS query
;; -----------------------------------------------------------------------------

(def ^:private fts-stop-words
  #{"the" "a" "an" "and" "or" "of" "to" "for" "in" "on" "with" "by" "is" "are"
    "be" "do" "does" "how" "what" "which" "this" "that" "from" "as" "at" "it"
    "i" "we" "you" "my" "our" "into" "any"})

(defn query->fts5
  "Turn a natural-language query into an FTS5 expression. Strips metasyntax,
   drops stop-words, joins tokens with `mode` (:or | :and). Returns nil when
   nothing survives."
  [q mode]
  (let [stripped (str/replace q #"[\"\^\$\(\)\[\]\{\}:\-]" " ")
        tokens   (->> (str/split stripped #"\s+")
                      (map str/trim)
                      (remove str/blank?)
                      (map str/lower-case)
                      (remove fts-stop-words)
                      distinct)]
    (when (seq tokens)
      (case mode
        :or  (str/join " OR " tokens)
        :and (str/join " " tokens)))))

(defn fts-search
  "BM25 over docs_fts. Returns ranked rows joined with docs."
  ([db q] (fts-search db q {}))
  ([db q {:keys [limit mode] :or {limit 10 mode :or}}]
   (if-let [q5 (query->fts5 q mode)]
     (basedb/query db
                   ;; docs_fts columns: path, title, summary, explains, tags, body
                   ;; Weight title/summary/explains (LLM-curated capability text) over body.
                   "SELECT d.path, d.title, d.summary, d.tags_json, d.explains_json,
                           bm25(docs_fts, 2.0, 10.0, 6.0, 6.0, 3.0, 1.0) AS rank
                      FROM docs_fts
                      JOIN docs d ON d.id = docs_fts.rowid
                      WHERE docs_fts MATCH ?
                      ORDER BY rank ASC
                      LIMIT ?"
                   q5 limit)
     [])))

(defn fts-candidates
  "Find candidate neighbor docs for pass-2 analysis of `src-path`. Searches
   the doc's own (explains + summary + body excerpt) against the FTS index,
   excluding the doc itself. Returns up to `k` rows of
   {:path :title :summary :explains_json :sha}."
  [db src-path query-text k]
  (let [q5 (query->fts5 (or query-text "") :or)]
    (if-not q5
      []
      (basedb/query db
                    "SELECT d.path, d.title, d.summary, d.explains_json, d.sha,
                            bm25(docs_fts) AS rank
                       FROM docs_fts
                       JOIN docs d ON d.id = docs_fts.rowid
                       WHERE docs_fts MATCH ? AND d.path != ?
                       ORDER BY rank ASC
                       LIMIT ?"
                    q5 src-path k))))

(defn parse-explains [row] (<-json (:explains_json row)))
(defn parse-tags [row] (<-json (:tags_json row)))
