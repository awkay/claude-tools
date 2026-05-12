(ns scripts.lib.claude-cli
  "Analyze a function by shelling `claude -p` under the user's Max subscription.
   Trades 'fully local' for higher quality + real parallelism (no GPU contention)."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-model "haiku")

(def ^:private system-prompt
  "You are a Clojure code analyst. Given a function plus deterministic facts and example call-sites, return ONE JSON object with the requested fields. No prose, no markdown fences — JSON only.")

(defn- truncate [s n]
  (if (and s (> (count s) n)) (str (subs s 0 n) "\n;; [truncated]") s))

(defn- format-callers [example-callers]
  (when (seq example-callers)
    (->> example-callers
         (map (fn [{:keys [in snippet]}]
                (str "  in " in ":\n"
                     (->> (str/split-lines (truncate snippet 400))
                          (map #(str "    " %)) (str/join "\n")))))
         (str/join "\n\n"))))

(defn- build-prompt
  [{:keys [ns name arglists-edn arities private? body
           pure-heuristic? pure-heuristic-reasons types-edn example-callers]}]
  (str
   system-prompt "\n\n"
   "Namespace: " ns "\n"
   "Function: " name (when private? " (private)") "\n"
   "Arglists: " (or arglists-edn (str "(" arities ")")) "\n"
   (when types-edn (str "Guardrails spec: " types-edn "\n"))
   "Purity (heuristic): " (if pure-heuristic? "appears pure" "side-effecting")
   (when (seq pure-heuristic-reasons)
     (str " — signals: " (str/join ", " pure-heuristic-reasons)))
   "\n"
   "\nSource:\n" (truncate body 6000) "\n"
   (when-let [callers (format-callers example-callers)]
     (str "\nExample call sites (real code):\n" callers "\n"))
   "\nReturn ONE JSON object with exactly these keys:\n"
   "{\n"
   "  \"description\": <one sentence: what it does and what it returns>,\n"
   "  \"arg_descriptions\": [{\"name\": <arg-name>, \"desc\": <brief>}, ...],\n"
   "  \"return_description\": <kind of value + meaning>,\n"
   "  \"tags\": [<2-6 lowercase keywords>],\n"
   "  \"domain_signals\": [<project-specific terms>],\n"
   "  \"general_purpose_score\": <0..1>,\n"
   "  \"confidence\": <0..1>\n"
   "}\n"
   "JSON ONLY. No markdown fences. No commentary."))

(defn- strip-fences
  "Claude often wraps JSON in ```json fences despite instructions — strip them."
  [s]
  (let [s (str/trim s)
        s (str/replace s #"(?s)\A```(?:json)?\s*" "")
        s (str/replace s #"(?s)\s*```\s*\z" "")]
    (str/trim s)))

(defn- extract-json
  "Best-effort extraction: try whole, then strip fences, then locate first { and
   try parse from there. Returns nil if all fail."
  [s]
  (or (try (json/parse-string s true) (catch Exception _ nil))
      (try (json/parse-string (strip-fences s) true) (catch Exception _ nil))
      (when-let [start (str/index-of s "{")]
        (try (json/parse-string (subs s start) true) (catch Exception _ nil)))))

(defn- run-claude
  [model prompt]
  (let [{:keys [out exit]} (p/sh ["claude" "-p"
                                  "--model" model
                                  "--no-session-persistence"
                                  "--output-format" "text"
                                  prompt]
                                 {:out :string :err :string :timeout 120000})]
    (when (zero? exit) out)))

(defn analyze-fn
  "Run one analysis call. Returns the parsed JSON map; never throws.
   One retry on bad JSON; final fallback returns a confidence=0 stub."
  [{:keys [model fn-record] :or {model default-model}}]
  (let [prompt (build-prompt fn-record)
        attempt (fn []
                  (some-> (run-claude model prompt) extract-json))]
    (or (attempt)
        (attempt)
        {:description           "<analysis failed>"
         :arg_descriptions      []
         :return_description    ""
         :tags                  []
         :domain_signals        []
         :general_purpose_score 0.0
         :confidence            0.0})))

;; -----------------------------------------------------------------------------
;; Batch analysis: one Haiku call per file, EDN output keyed by qualified-name.
;; -----------------------------------------------------------------------------

(def ^:private batch-system-prompt
  "You are a Clojure code analyst. Given the full source of a file and a list of functions to summarize, return ONE EDN map keyed by qualified-name string. No prose, no markdown fences — EDN only.")

(defn- format-fn-spec [{:keys [ns name qualified-name arglists-edn arities private?
                               pure-heuristic? pure-heuristic-reasons types-edn]}]
  (str "- " qualified-name (when private? " (private)")
       "  arglists=" (or arglists-edn (str "(" arities ")"))
       (when types-edn (str "  guardrails=" types-edn))
       (str "  purity=" (if pure-heuristic? "pure" "side-effecting"))
       (when (seq pure-heuristic-reasons)
         (str " [" (str/join "," pure-heuristic-reasons) "]"))))

(defn- build-batch-prompt [file-source fn-records]
  (str
   batch-system-prompt "\n\n"
   "File source:\n```clojure\n" file-source "\n```\n\n"
   "Analyze these functions (defined in the file above):\n"
   (str/join "\n" (map format-fn-spec fn-records))
   "\n\n"
   "Return ONE EDN map. Top-level keys are qualified-name STRINGS (e.g. \"my.ns/foo\"). "
   "Each value is a map with EXACTLY these keys (use these exact keywords):\n"
   "  :description            <one sentence: what it does and what it returns>\n"
   "  :arg_descriptions       [{:name \"<arg>\" :desc \"<brief>\"} ...]\n"
   "  :return_description     <kind of value + meaning>\n"
   "  :tags                   [<2-6 lowercase keyword-like strings>]\n"
   "  :domain_signals         [<project-specific terms>]\n"
   "  :general_purpose_score  <0..1 number>\n"
   "  :confidence             <0..1 number>\n"
   "Include an entry for EVERY function listed above. EDN ONLY. No markdown fences. No commentary."))

(defn- strip-edn-fences [s]
  (let [s (str/trim s)
        s (str/replace s #"(?s)\A```(?:edn|clojure)?\s*" "")
        s (str/replace s #"(?s)\s*```\s*\z" "")]
    (str/trim s)))

(defn- extract-edn-map [s]
  (let [try-parse (fn [x] (try (let [r (edn/read-string x)]
                                 (when (map? r) r))
                               (catch Exception _ nil)))]
    (or (try-parse s)
        (try-parse (strip-edn-fences s))
        (when-let [start (str/index-of s "{")]
          (try-parse (subs s start))))))

(defn- run-claude-json
  "Run `claude -p` with JSON output. Returns {:text result-text :session-id sid}
   or nil on failure."
  [model prompt & {:keys [resume-id]}]
  (let [argv (cond-> ["claude" "-p"
                      "--model" model
                      "--no-session-persistence"
                      "--output-format" "json"]
               resume-id (into ["--resume" resume-id])
               true      (conj prompt))
        {:keys [out exit]} (p/sh argv {:out :string :err :string :timeout 600000})]
    (when (zero? exit)
      (try (let [j (json/parse-string out true)]
             {:text (:result j) :session-id (:session_id j)})
           (catch Exception _ nil)))))

(defn- build-resume-prompt [missing-qnames]
  (str "You missed some functions in your previous EDN response. "
       "Return ONE EDN map keyed by qualified-name STRING containing entries for ONLY these: "
       (pr-str (vec missing-qnames))
       "\nSame value shape as before (:description, :arg_descriptions, :return_description, "
       ":tags, :domain_signals, :general_purpose_score, :confidence). "
       "EDN ONLY. No markdown fences. No commentary."))

(defn analyze-file
  "Batch-analyze every fn-record in `fn-records` (all from the same file).
   `file-source` is the full text of that file. Returns a map of
   qualified-name -> result map (snake_case keys, same shape as analyze-fn).

   Strategy: one batched call; if any names are missing from the response,
   resume the session and ask for just the missing ones. Stragglers after
   resume get a confidence=0 stub."
  [{:keys [model file-source fn-records] :or {model default-model}}]
  (let [prompt   (build-batch-prompt file-source fn-records)
        wanted   (set (map :qualified-name fn-records))
        stub     {:description           "<analysis failed>"
                  :arg_descriptions      []
                  :return_description    ""
                  :tags                  []
                  :domain_signals        []
                  :general_purpose_score 0.0
                  :confidence            0.0}
        first-rsp (run-claude-json model prompt)
        parsed1   (or (some-> first-rsp :text extract-edn-map) {})
        missing   (vec (remove parsed1 wanted))
        parsed2   (if (and (seq missing) (:session-id first-rsp))
                    (let [resumed (run-claude-json model
                                                   (build-resume-prompt missing)
                                                   :resume-id (:session-id first-rsp))]
                      (or (some-> resumed :text extract-edn-map) {}))
                    {})
        merged    (merge parsed1 parsed2)]
    (into {}
          (for [qname wanted]
            [qname (or (get merged qname) stub)]))))
