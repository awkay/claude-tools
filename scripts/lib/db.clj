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

(defn qnames-in-file
  "Return seq of {:qualified_name :lang} for every row stored under `filename`."
  [db filename]
  (query db "SELECT qualified_name, lang FROM functions WHERE filename = ?" filename))

(defn delete-orphans-in-file!
  "Delete rows for `filename` whose [qualified-name lang] is not in `keep-set`
   (a set of [qname-string lang-string]). Returns count deleted."
  [db filename keep-set]
  (let [existing (qnames-in-file db filename)
        to-del   (remove (fn [{:keys [qualified_name lang]}]
                           (keep-set [qualified_name lang]))
                         existing)]
    (doseq [{:keys [qualified_name lang]} to-del]
      (execute! db "DELETE FROM functions WHERE qualified_name = ? AND lang = ?"
                qualified_name lang))
    (count to-del)))

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

(defn- ns-matches-any?
  "True if (lower-case ns) contains any of the substrings (also lower-cased)."
  [ns-str substrs]
  (when (and ns-str (seq substrs))
    (let [lns (str/lower-case ns-str)]
      (boolean (some #(str/includes? lns (str/lower-case %)) substrs)))))

(defn- like-clause
  "Returns [sql-fragment params] for an OR-of-LIKE filter against `ns`.
   `op` is \"LIKE\" (inclusion) or \"NOT LIKE\" (exclusion). For NOT LIKE the
   terms are joined with AND so all exclusions must hold.
   Returns nil if substrs is empty."
  ([substrs] (like-clause substrs "LIKE"))
  ([substrs op]
   (when (seq substrs)
     (let [terms (mapv #(str "%" (str/lower-case %) "%") substrs)
           joiner (if (= op "NOT LIKE") " AND " " OR ")
           pred   (str "lower(f.ns) " op " ?")
           frag   (str "(" (str/join joiner (repeat (count terms) pred)) ")")]
       [frag terms]))))

(defn fts-search
  "BM25 over functions_fts with optional ns filter, ns boost, and caller_count boost.
   Returns rows ordered by composite `score` (lower = better).

   Options:
     :limit         max rows to return (default 10)
     :mode          :or | :and (default :or)
     :ns-filter     seq of substrings; hard-filter rows whose ns contains ANY (case-insens)
     :ns-exclude    seq of substrings; drop rows whose ns contains ANY (case-insens)
     :ns-boost      seq of substrings; soft rank boost on match.
     :boost-amount  positive magnitude of the ns boost (default 10.0). Internally
                    applied as a negative offset to the score. Defaults to [\"lib\" \"core\"]
                    when neither :ns-filter nor :ns-boost is supplied (caller passes :default? true
                    to request that default). If :ns-filter is supplied and :ns-boost is empty,
                    no ns boost is applied.
     :caller-boost? when true (default), subtract ln(1+caller_count) from score."
  ([db q] (fts-search db q {}))
  ([db q {:keys [limit mode ns-filter ns-exclude ns-boost caller-boost? boost-amount]
          :or {limit 10 mode :or caller-boost? true boost-amount 10.0}}]
   (if-let [q5 (query->fts5 q mode)]
     (let [boost-mag (Math/abs (double boost-amount))
           [filter-frag  filter-params]  (like-clause ns-filter)
           [exclude-frag exclude-params] (like-clause ns-exclude "NOT LIKE")
           where (str "WHERE functions_fts MATCH ?"
                      (when filter-frag  (str " AND " filter-frag))
                      (when exclude-frag (str " AND " exclude-frag)))
           ;; Fetch a wider pool so post-rerank can reorder before truncation.
           pool-size (max (* limit 10) 200)
           sql (str "SELECT f.qualified_name, f.description_llm, f.general_purpose_score,
                            f.confidence, f.tags_llm, f.filename, f.line_start, f.line_end,
                            f.ns, f.caller_count,
                            bm25(functions_fts) AS rank
                       FROM functions_fts
                       JOIN functions f ON f.id = functions_fts.rowid
                       " where "
                       ORDER BY rank ASC
                       LIMIT ?")
           rows (apply query db sql q5
                       (concat (or filter-params [])
                               (or exclude-params [])
                               [pool-size]))
           score (fn [{:keys [rank ns caller_count]}]
                   (let [boost (if (ns-matches-any? ns ns-boost) (- boost-mag) 0.0)
                         clog  (if caller-boost?
                                 (- (Math/log (+ 1.0 (double (or caller_count 0)))))
                                 0.0)]
                     (+ (double (or rank 0.0)) boost clog)))]
       (->> rows
            (map #(assoc % :score (score %)))
            (sort-by :score)
            (take limit)
            vec))
     [])))
