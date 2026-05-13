(ns scripts.search
  "CLI for retrieving function records from the code-index. Two operations:
     - search: BM25 FTS5 search over (qualified_name, description_llm, docstring,
       tags_llm, domain_signals_llm) with optional ns filtering and ranking boosts.
     - show: dump the full record for a single qualified-name.

   Output formats: plain (default, line-oriented), edn, json.

   Project-local defaults: if a .code-search.edn file exists in cwd or any
   ancestor directory (up to the filesystem root), its contents are merged as
   defaults under CLI-supplied flags. See `bb search --help`."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [scripts.lib.db :as db]))

(def default-db-path
  (or (System/getenv "CODE_INDEX_DB")
      ".code-intelligence/code-index.db"))

(def default-boost-ns ["lib" "core"])

(def help-text
  (str
   "usage: bb search [OPTIONS] QUERY...\n"
   "       bb show   [OPTIONS] QUALIFIED-NAME\n"
   "\n"
   "Options:\n"
   "  --db PATH           Path to code-index SQLite db.\n"
   "                      Default: $CODE_INDEX_DB or .code-intelligence/code-index.db\n"
   "  --limit N           Max results (default 5 for search).\n"
   "  --mode or|and       FTS5 token combinator (default 'or').\n"
   "  --format plain|edn|json   Output format (default plain).\n"
   "  --ns-filter LIST    Comma-separated substrings. HARD FILTER: only return\n"
   "                      rows whose namespace contains any listed substring.\n"
   "                      Case-insensitive. Disables the default lib/core boost.\n"
   "  --ns-exclude LIST   Comma-separated substrings. HARD EXCLUDE: drop rows\n"
   "                      whose namespace contains any listed substring.\n"
   "  --ns-boost LIST     Comma-separated substrings. SOFT BOOST: rows whose ns\n"
   "                      contains any listed substring rank higher (-10.0 to score).\n"
   "                      Default when neither --ns-filter nor --ns-boost supplied: \"lib,core\".\n"
   "  --no-caller-boost   Disable the caller_count ranking term.\n"
   "  -v, --verbose       Verbose per-result output (file, gp, conf, tags, score).\n"
   "                      Default: two-line compact (qualified-name + description).\n"
   "  --no-config         Ignore .code-search.edn.\n"
   "  --help              Show this message.\n"
   "\n"
   "Project config (.code-search.edn):\n"
   "  An optional EDN file holding project-local defaults. Discovery: starting\n"
   "  at the current working directory, walk upward through parents until a\n"
   "  .code-search.edn is found (first hit wins). Use --no-config to skip it.\n"
   "\n"
   "  Resolution order for every option (highest wins):\n"
   "    1. CLI flag\n"
   "    2. Value in .code-search.edn\n"
   "    3. Built-in default\n"
   "\n"
   "  All keys are optional. Supported keys (with types):\n"
   "    :db             string   — path to the SQLite index\n"
   "    :limit          integer  — max results (overrides built-in 5)\n"
   "    :mode           :or|:and — FTS5 token combinator\n"
   "    :format         :plain|:edn|:json\n"
   "    :ns-filter      [\"sub\" ...] — hard include filter\n"
   "    :ns-exclude     [\"sub\" ...] — hard exclude filter\n"
   "    :ns-boost       [\"sub\" ...] — soft rank boost (overrides built-in [\"lib\" \"core\"])\n"
   "    :caller-boost?  true|false  — enable/disable the log(1+caller_count) term\n"
   "\n"
   "  Example .code-search.edn:\n"
   "    {:limit 20\n"
   "     :ns-boost   [\"lib\" \"core\" \"util\"]\n"
   "     :ns-exclude [\"test\" \"spec\"]\n"
   "     :caller-boost? true}\n"
   "\n"
   "Ranking:\n"
   "  score = bm25 + ns_boost(-10.0 on match) - ln(1 + caller_count)\n"
   "  Lower score = better match.\n"))

(defn- parse-json [s] (when s (try (json/parse-string s true) (catch Exception _ nil))))

(defn- short-snippet [s n]
  (when s
    (let [s (str/trim s)]
      (if (> (count s) n) (str (subs s 0 n) "…") s))))

(defn- format-search-row-compact [{:keys [qualified_name description_llm]}]
  (str qualified_name "\n"
       "  " (or description_llm "(no description)")))

(defn- format-search-row-verbose [{:keys [qualified_name description_llm filename
                                          line_start line_end general_purpose_score
                                          confidence tags_llm caller_count score rank]}]
  (str qualified_name "\n"
       "  file: " filename ":" line_start "-" line_end "\n"
       "  desc: " (or description_llm "(no description)") "\n"
       "  gp: " (format "%.2f" (double (or general_purpose_score 0.0)))
       "  conf: " (format "%.2f" (double (or confidence 0.0)))
       "  callers: " (or caller_count 0)
       (when-let [tags (parse-json tags_llm)] (str "  tags: " (str/join ", " tags)))
       "  score: " (format "%.4f" (double (or score rank 0.0)))))

(defn- format-show-record [r]
  (let [arg-descs (parse-json (:arg_descriptions_llm r))
        callers   (parse-json (:example_callers r))
        callees   (parse-json (:callee_namespaces r))
        callerns  (parse-json (:caller_namespaces r))
        tags      (parse-json (:tags_llm r))
        signals   (parse-json (:domain_signals_llm r))
        reasons   (parse-json (:pure_heuristic_reasons r))]
    (str
     (:qualified_name r) "\n"
     "  file:    " (:filename r) ":" (:line_start r) "-" (:line_end r) "\n"
     "  lang:    " (:lang r) "\n"
     "  args:    " (or (:arglists_edn r) "?") "\n"
     "  pure:    " (if (= 1 (:pure_heuristic r)) "yes" "no")
     (when (seq reasons) (str " — " (str/join ", " reasons))) "\n"
     (when (:types_edn r) (str "  types:   " (:types_edn r) "\n"))
     "  by:      " (:analyzed_by_model r) "\n"
     "\n"
     "  Description:  " (or (:description_llm r) "(none)") "\n"
     (when (:return_description_llm r)
       (str "  Returns:      " (:return_description_llm r) "\n"))
     (when (seq arg-descs)
       (str "\n  Arguments:\n"
            (->> arg-descs
                 (map (fn [{:keys [name desc]}]
                        (str "    " name ": " desc)))
                 (str/join "\n"))
            "\n"))
     (when (:docstring r)
       (str "\n  Docstring: " (pr-str (:docstring r)) "\n"))
     (when (seq tags) (str "\n  Tags:    " (str/join ", " tags) "\n"))
     (when (seq signals) (str "  Signals: " (str/join ", " signals) "\n"))
     "\n  Calls (namespaces):  " (str/join ", " (or callees ["(none)"])) "\n"
     "  Callers:             " (:caller_count r) " sites across "
     (count (or callerns [])) " ns: "
     (str/join ", " (or callerns ["(none)"])) "\n"
     (when (seq callers)
       (str "\n  Example call-sites:\n"
            (->> callers
                 (map (fn [{:keys [in snippet]}]
                        (str "    in " in ":\n"
                             (->> (str/split-lines snippet)
                                  (map #(str "      " %))
                                  (str/join "\n")))))
                 (str/join "\n\n"))
            "\n")))))

(defn- find-config-file
  "Walk upward from cwd looking for .code-search.edn. Returns java.io.File or nil."
  []
  (loop [dir (.getCanonicalFile (io/file "."))]
    (when dir
      (let [f (io/file dir ".code-search.edn")]
        (cond
          (.isFile f) f
          (.getParentFile dir) (recur (.getParentFile dir))
          :else nil)))))

(defn load-config
  "Load project-local defaults from .code-search.edn (searched upward from cwd).
   Returns {:path File :opts map} or nil."
  []
  (when-let [f (find-config-file)]
    (try
      {:path f :opts (edn/read-string (slurp f))}
      (catch Exception e
        (binding [*out* *err*]
          (println "warn: failed to read" (.getPath f) "—" (.getMessage e)))
        nil))))

(defn search!
  ([query] (search! query {}))
  ([query {:keys [db limit mode format ns-filter ns-exclude ns-boost caller-boost? verbose?]
           :or {db default-db-path limit 5 mode :or format :plain caller-boost? true}}]
   (let [ns-boost* (cond
                     (seq ns-boost)   ns-boost
                     (seq ns-filter)  nil          ; explicit filter replaces default boost
                     :else            default-boost-ns)
         hits (db/fts-search db query {:limit limit
                                       :mode mode
                                       :ns-filter ns-filter
                                       :ns-exclude ns-exclude
                                       :ns-boost ns-boost*
                                       :caller-boost? caller-boost?})
         row-fn (if verbose? format-search-row-verbose format-search-row-compact)]
     (case format
       :edn   (pp/pprint hits)
       :json  (println (json/generate-string hits {:pretty true}))
       (doseq [h hits]
         (println (row-fn h)))))))

(defn show!
  ([qname] (show! qname {}))
  ([qname {:keys [db format] :or {db default-db-path format :plain}}]
   (let [r (first (db/query db "SELECT * FROM functions WHERE qualified_name = ? LIMIT 1" qname))]
     (cond
       (nil? r) (println "Not found:" qname)
       (= format :edn)  (pp/pprint r)
       (= format :json) (println (json/generate-string r {:pretty true}))
       :else (println (format-show-record r))))))

(defn- parse-csv [s]
  (when s
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- parse-args [args]
  (loop [[a & rst] args opts {} positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")           (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--limit")        (recur (rest rst) (assoc opts :limit (Integer/parseInt (first rst))) positional)
      (= a "--mode")         (recur (rest rst) (assoc opts :mode (keyword (first rst))) positional)
      (= a "--format")       (recur (rest rst) (assoc opts :format (keyword (first rst))) positional)
      (= a "--ns-filter")    (recur (rest rst) (assoc opts :ns-filter  (parse-csv (first rst))) positional)
      (= a "--ns-exclude")   (recur (rest rst) (assoc opts :ns-exclude (parse-csv (first rst))) positional)
      (= a "--ns-boost")     (recur (rest rst) (assoc opts :ns-boost   (parse-csv (first rst))) positional)
      (= a "--no-caller-boost") (recur rst (assoc opts :caller-boost? false) positional)
      (or (= a "-v") (= a "--verbose")) (recur rst (assoc opts :verbose? true) positional)
      (= a "--no-config")    (recur rst (assoc opts :no-config? true) positional)
      (or (= a "--help") (= a "-h")) (recur rst (assoc opts :help? true) positional)
      :else                  (recur rst opts (conj positional a)))))

(defn- merged-opts
  "Merge CLI opts on top of .code-search.edn opts. CLI wins for any key the user
   actually supplied."
  [cli-opts]
  (if (:no-config? cli-opts)
    cli-opts
    (if-let [{:keys [opts]} (load-config)]
      (merge opts cli-opts)
      cli-opts)))

(defn -search-main [& args]
  (let [[cli pos] (parse-args args)]
    (cond
      (:help? cli)
      (println help-text)

      (empty? pos)
      (do (println help-text) (System/exit 1))

      :else
      (search! (str/join " " pos) (merged-opts cli)))))

(defn -show-main [& args]
  (let [[cli pos] (parse-args args)]
    (cond
      (:help? cli)
      (println help-text)

      (empty? pos)
      (do (println help-text) (System/exit 1))

      :else
      (show! (first pos) (merged-opts cli)))))
