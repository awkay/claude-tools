(ns scripts.lib.db
  "Thin SQLite helpers over pod-babashka-go-sqlite3.
   Schema lives in schema/code_index.sql."
  (:require
   [babashka.pods :as pods]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.9")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn load-resource-sql
  "Read a SQL file by resource path. Prefers the classpath (so it works when
   shipped via bbin/jar) and falls back to cwd-relative (so it works during
   local dev when :paths includes the project root). Returns the raw SQL
   string."
  [resource-path]
  (or (some-> (io/resource resource-path) slurp)
      (slurp resource-path)))

(defn- load-schema-sql []
  (load-resource-sql "schema/code_index.sql"))

(defn- split-statements
  "Split SQL on `;` but only when we are at paren-depth 0 AND outside a
   CREATE TRIGGER ... BEGIN ... END block. Returns trimmed non-blank statements."
  [sql]
  (let [n (count sql)]
    (loop [i 0 start 0 depth 0 in-trigger? false acc []]
      (cond
        (>= i n)
        (let [tail (str/trim (subs sql start))]
          (if (str/blank? tail) acc (conj acc tail)))

        :else
        (let [c (.charAt sql i)]
          (cond
            (= c \()  (recur (inc i) start (inc depth) in-trigger? acc)
            (= c \))  (recur (inc i) start (dec depth) in-trigger? acc)
            (and (not in-trigger?)
                 (zero? depth)
                 ;; case-insensitive look-ahead for "BEGIN"
                 (let [tail (subs sql i (min n (+ i 5)))]
                   (= "BEGIN" (str/upper-case tail))))
            (recur (+ i 5) start depth true acc)

            (and in-trigger?
                 (let [tail (subs sql i (min n (+ i 4)))]
                   (= "END;" (str/upper-case tail))))
            (let [stmt (str/trim (subs sql start (+ i 4)))]
              (recur (+ i 4) (+ i 4) depth false
                     (if (str/blank? stmt) acc (conj acc stmt))))

            (and (not in-trigger?) (zero? depth) (= c \;))
            (let [stmt (str/trim (subs sql start (inc i)))]
              (recur (inc i) (inc i) depth in-trigger?
                     (if (str/blank? stmt) acc (conj acc stmt))))

            :else
            (recur (inc i) start depth in-trigger? acc)))))))

(defn- comment-line? [^String l] (str/starts-with? (str/trim l) "--"))

(defn apply-schema! [db schema-sql]
  (doseq [stmt (->> (split-statements schema-sql)
                    (remove (fn [s] (every? comment-line? (str/split-lines s)))))]
    (sqlite/execute! db [stmt])))

(defn open-with-schema
  "Open (or create) a SQLite db at `path`, enable WAL, and apply the schema
   loaded from `schema-resource` iff `probe-table` is absent."
  [path probe-table schema-resource]
  (io/make-parents path)
  (sqlite/execute! path ["PRAGMA journal_mode=WAL;"])
  (let [exists? (->> (sqlite/query path
                                   ["SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                                    probe-table])
                     seq)]
    (when-not exists?
      (apply-schema! path (load-resource-sql schema-resource))))
  path)

(defn open
  "Open (or create) the SQLite db, enable WAL, apply schema if functions table absent."
  [path]
  (open-with-schema path "functions" "schema/code_index.sql"))

(defn rebuild-fts!
  "Drop and recreate functions_fts + triggers (picks up tokenizer changes) and
   reindex from the functions table. Safe one-shot migration."
  [db]
  (doseq [stmt ["DROP TRIGGER IF EXISTS functions_ai"
                "DROP TRIGGER IF EXISTS functions_ad"
                "DROP TRIGGER IF EXISTS functions_au"
                "DROP TABLE IF EXISTS functions_fts"]]
    (sqlite/execute! db [stmt]))
  ;; Re-apply schema; CREATE TABLE/INDEX statements are IF NOT EXISTS so they
  ;; no-op, while the just-dropped FTS table + triggers get recreated.
  (apply-schema! db (load-schema-sql))
  ;; FTS5 special command: rebuild the index from the linked content table.
  (sqlite/execute! db ["INSERT INTO functions_fts(functions_fts) VALUES('rebuild')"]))

(defn query [db sql & params]
  (sqlite/query db (into [sql] params)))

(defn execute! [db sql & params]
  (sqlite/execute! db (into [sql] params)))

(defn- ->json [x] (when x (json/generate-string x)))

;; -----------------------------------------------------------------------------
;; files table (file-SHA cache)
;; -----------------------------------------------------------------------------

(defn file-sha-stored
  "Return the stored file_sha for `filename`, or nil if unknown."
  [db filename]
  (some-> (query db "SELECT file_sha FROM files WHERE filename = ?" filename)
          first :file_sha))

(defn record-file-sha!
  "Upsert the (filename, file_sha) pair."
  [db filename file-sha]
  (execute! db
            "INSERT INTO files (filename, file_sha, analyzed_at)
             VALUES (?, ?, ?)
             ON CONFLICT(filename) DO UPDATE SET
               file_sha = excluded.file_sha,
               analyzed_at = excluded.analyzed_at"
            filename file-sha (str (java.time.Instant/now))))

(defn file-unchanged?
  "True iff stored file_sha matches `current-sha`."
  [db filename current-sha]
  (= current-sha (file-sha-stored db filename)))

;; -----------------------------------------------------------------------------
;; functions table
;;
;; A function record carries deterministic fields (from kondo + analysis) plus
;; LLM-produced fields. Callers pass them all in one map.
;; -----------------------------------------------------------------------------

(defn fn-row-by-qname [db qname lang]
  (first (query db "SELECT id, sha FROM functions WHERE qualified_name = ? AND lang = ?" qname lang)))

(defn update-positions!
  "When a file changed but the function's body didn't (SHA stable), refresh
   only the line/col positions — no LLM call."
  [db {:keys [qualified-name lang filename line-start line-end col-start col-end]}]
  (execute! db
            "UPDATE functions SET filename=?, line_start=?, line_end=?, col_start=?, col_end=?
             WHERE qualified_name=? AND lang=?"
            filename line-start line-end col-start col-end qualified-name lang))

(defn upsert-function!
  "Insert or update a function row. Returns :inserted / :updated / :unchanged."
  [db {:keys [ns name qualified-name lang sha
              filename line-start line-end col-start col-end
              arglists-edn arities defined-by private? docstring
              pure-heuristic? pure-heuristic-reasons types-edn
              caller-count caller-namespaces callee-namespaces example-callers
              description-llm arg-descriptions-llm return-description-llm
              tags-llm domain-signals-llm general-purpose-score confidence
              analyzed-by-model]}]
  (let [now      (str (java.time.Instant/now))
        existing (fn-row-by-qname db qualified-name lang)]
    (cond
      (and existing (= (:sha existing) sha))
      :unchanged

      existing
      (do (execute! db
                    "UPDATE functions SET
                        sha=?, filename=?, line_start=?, line_end=?, col_start=?, col_end=?,
                        arglists_edn=?, arities_json=?, defined_by=?, private=?, docstring=?,
                        pure_heuristic=?, pure_heuristic_reasons=?, types_edn=?,
                        caller_count=?, caller_namespaces=?, callee_namespaces=?, example_callers=?,
                        description_llm=?, arg_descriptions_llm=?, return_description_llm=?,
                        tags_llm=?, domain_signals_llm=?,
                        general_purpose_score=?, confidence=?, analyzed_by_model=?, indexed_at=?
                      WHERE id=?"
                    sha filename line-start line-end col-start col-end
                    arglists-edn (->json arities) (str defined-by) (if private? 1 0) docstring
                    (if pure-heuristic? 1 0) (->json pure-heuristic-reasons) types-edn
                    caller-count (->json caller-namespaces) (->json callee-namespaces) (->json example-callers)
                    description-llm (->json arg-descriptions-llm) return-description-llm
                    (->json tags-llm) (->json domain-signals-llm)
                    general-purpose-score confidence analyzed-by-model now
                    (:id existing))
          :updated)

      :else
      (do (execute! db
                    "INSERT INTO functions
                       (ns, name, qualified_name, lang, sha, filename, line_start, line_end, col_start, col_end,
                        arglists_edn, arities_json, defined_by, private, docstring,
                        pure_heuristic, pure_heuristic_reasons, types_edn,
                        caller_count, caller_namespaces, callee_namespaces, example_callers,
                        description_llm, arg_descriptions_llm, return_description_llm,
                        tags_llm, domain_signals_llm,
                        general_purpose_score, confidence, analyzed_by_model, indexed_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    (str ns) (str name) qualified-name (or lang "clj") sha
                    filename line-start line-end col-start col-end
                    arglists-edn (->json arities) (str defined-by) (if private? 1 0) docstring
                    (if pure-heuristic? 1 0) (->json pure-heuristic-reasons) types-edn
                    caller-count (->json caller-namespaces) (->json callee-namespaces) (->json example-callers)
                    description-llm (->json arg-descriptions-llm) return-description-llm
                    (->json tags-llm) (->json domain-signals-llm)
                    general-purpose-score confidence analyzed-by-model now)
          :inserted))))

(def ^:private fts-stop-words
  #{"the" "a" "an" "and" "or" "of" "to" "for" "in" "on" "with" "by" "is" "are"
    "be" "do" "does" "how" "what" "which" "this" "that" "from" "as" "at" "it"
    "i" "we" "you" "my" "our" "into" "any"})

(defn- query->fts5
  "Turn a natural-language query into an FTS5 expression.
   Strips FTS5 metasyntax, drops stop-words, joins tokens with the given op.
   Returns nil if no tokens survived."
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
  "BM25 over functions_fts. Default mode is :or so natural-language queries
   degrade gracefully — BM25 ranks the matches regardless of how many tokens hit.
   Use :and for strict all-tokens-required."
  ([db q] (fts-search db q {}))
  ([db q {:keys [limit mode] :or {limit 10 mode :or}}]
   (if-let [q5 (query->fts5 q mode)]
     (query db
            "SELECT f.qualified_name, f.description_llm, f.general_purpose_score, f.confidence,
                    f.tags_llm, f.filename, f.line_start, f.line_end,
                    bm25(functions_fts) AS rank
               FROM functions_fts
               JOIN functions f ON f.id = functions_fts.rowid
               WHERE functions_fts MATCH ?
               ORDER BY rank ASC
               LIMIT ?"
            q5 limit)
     [])))
