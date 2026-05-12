(ns scripts.instructions.search
  "Search + show for the instruction index.

   `search` returns three grouped sections:
     matches:        direct FTS hits
     you may also need:  1-hop `prereq` edges from the matches
     see also:       1-hop `companion` and `extends` edges from the matches"
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [scripts.instructions.db :as idb]))

(def default-db-path
  (or (System/getenv "INSTRUCTIONS_DB")
      ".code-intelligence/instructions.db"))

(defn- short-str [s n]
  (when s
    (let [s (str/trim s)]
      (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s))))

(defn- format-match-row [{:keys [path title summary] :as _row}]
  (str "  " path
       (when title (str "  — " title))
       (when summary (str "\n      " (short-str summary 200)))))

(defn- format-related-row [{:keys [path title summary kind from confidence]}]
  (str "  " path
       (when title (str "  — " title))
       (when summary (str "\n      " (short-str summary 160)))
       "\n      " (name kind) " of " from
       (when confidence (format " (conf %.2f)" (double confidence)))))

(defn- neighbors-for
  "Given a match path, return [{:path :kind :confidence :from path} ...] of
   1-hop outgoing edges, optionally filtered by `kinds`."
  [db match-path kinds]
  (let [edges (idb/outgoing-edges db match-path)]
    (->> edges
         (filter #(contains? (set (map name kinds)) (:kind %)))
         (map (fn [{:keys [dst_path kind confidence]}]
                {:path dst_path :kind kind :confidence confidence :from match-path})))))

(defn- hydrate-doc [db path]
  (when path
    (let [d (idb/get-doc db path)]
      {:path    path
       :title   (:title d)
       :summary (:summary d)})))

(defn- dedupe-related
  "Collapse multiple inbound mentions of the same path to one entry, keeping
   the highest confidence. Preserves first-seen ordering."
  [rows]
  (->> rows
       (reduce (fn [{:keys [seen out]} row]
                 (let [k [(:path row) (:kind row)]]
                   (if-let [idx (get seen k)]
                     (let [existing (nth out idx)
                           winner   (if (> (or (:confidence row) 0)
                                           (or (:confidence existing) 0))
                                      row existing)]
                       {:seen seen :out (assoc out idx winner)})
                     {:seen (assoc seen k (count out)) :out (conj out row)})))
               {:seen {} :out []})
       :out))

(defn search!
  ([q] (search! q {}))
  ([q {:keys [db limit mode] fmt :format
       :or   {db default-db-path limit 5 mode :or fmt :plain}}]
   (idb/open db)
   (let [matches (idb/fts-search db q {:limit limit :mode mode})
         match-paths (set (map :path matches))
         prereqs  (->> matches
                       (mapcat #(neighbors-for db (:path %) [:prereq]))
                       (remove #(match-paths (:path %)))
                       dedupe-related
                       (map #(merge (hydrate-doc db (:path %)) %)))
         see-also (->> matches
                       (mapcat #(neighbors-for db (:path %) [:companion :extends]))
                       (remove #(match-paths (:path %)))
                       (remove #(some (fn [p] (= (:path %) (:path p))) prereqs))
                       dedupe-related
                       (map #(merge (hydrate-doc db (:path %)) %)))]
     (case fmt
       :edn  (pp/pprint {:matches matches :prereqs prereqs :see-also see-also})
       :json (println (json/generate-string
                       {:matches matches :prereqs prereqs :see-also see-also}
                       {:pretty true}))
       (do
         (println "matches:")
         (if (empty? matches)
           (println "  (no matches)")
           (doseq [m matches] (println (format-match-row m))))
         (when (seq prereqs)
           (println)
           (println "you may also need (prereqs):")
           (doseq [r prereqs] (println (format-related-row r))))
         (when (seq see-also)
           (println)
           (println "see also (companion / extends):")
           (doseq [r see-also] (println (format-related-row r)))))))))

(defn show!
  ([path] (show! path {}))
  ([path {:keys [db] fmt :format :or {db default-db-path fmt :plain}}]
   (idb/open db)
   (let [r        (idb/get-doc db path)
         out-eds  (idb/outgoing-edges db path)
         in-eds   (idb/incoming-edges db path)
         explains (idb/parse-explains r)
         tags     (idb/parse-tags r)]
     (cond
       (nil? r)         (println "Not found:" path)
       (= fmt :edn)     (pp/pprint (assoc r :outgoing out-eds :incoming in-eds))
       (= fmt :json)    (println (json/generate-string
                                  (assoc r :outgoing out-eds :incoming in-eds)
                                  {:pretty true}))
       :else
       (do
         (println (:path r))
         (when (:title r) (println "  title:   " (:title r)))
         (when (:summary r) (println "  summary: " (:summary r)))
         (when (seq tags) (println "  tags:    " (str/join ", " tags)))
         (when (seq explains)
           (println "  explains:")
           (doseq [e explains] (println "    *" e)))
         (println "  by:      " (:analyzed_by_model r))
         (let [grouped (group-by :kind out-eds)]
           (doseq [k ["prereq" "companion" "extends"]]
             (when-let [es (seq (get grouped k))]
               (println (str "\n  " k ":"))
               (doseq [{:keys [dst_path confidence]} es]
                 (println (format "    %s  (conf %.2f)"
                                  dst_path (double (or confidence 0.0))))))))
         (when (seq in-eds)
           (println "\n  listed as prereq/companion/extends BY:")
           (doseq [{:keys [src_path kind confidence]} in-eds]
             (println (format "    %s  [%s, conf %.2f]"
                              src_path kind (double (or confidence 0.0))))))
         (when (:body r)
           (println "\n  body (first 800 chars):")
           (doseq [l (->> (short-str (:body r) 800) str/split-lines)]
             (println "    " l))))))))

(defn- parse-args [args]
  (loop [[a & rst] args opts {} positional []]
    (cond
      (nil? a) [opts positional]
      (= a "--db")     (recur (rest rst) (assoc opts :db (first rst)) positional)
      (= a "--limit")  (recur (rest rst) (assoc opts :limit (Integer/parseInt (first rst))) positional)
      (= a "--mode")   (recur (rest rst) (assoc opts :mode (keyword (first rst))) positional)
      (= a "--format") (recur (rest rst) (assoc opts :format (keyword (first rst))) positional)
      :else            (recur rst opts (conj positional a)))))

(defn -search-main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: instructions [search] [--db PATH] [--limit N] [--mode or|and] [--format plain|edn|json] QUERY...")
          (System/exit 1))
      (search! (str/join " " pos) opts))))

(defn -show-main [& args]
  (let [[opts pos] (parse-args args)]
    (if (empty? pos)
      (do (println "usage: instructions show [--db PATH] [--format plain|edn|json] PATH")
          (System/exit 1))
      (show! (first pos) opts))))
