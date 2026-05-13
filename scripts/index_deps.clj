(ns scripts.index-deps
  "Interactive dep picker: reads deps.edn, lets the user choose which
   maven deps to index, extracts the jars from ~/.m2, and (in --fast mode)
   writes one row per public+docstringed function into the code index."
  (:require
   [babashka.terminal :as term]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [scripts.doctor :as doctor]
   [scripts.lib.claude-cli :as claude-cli]
   [scripts.lib.db :as db]
   [scripts.lib.kondo :as kondo])
  (:import
   (java.util.zip ZipFile)
   (org.jline.terminal TerminalBuilder)))

;; -----------------------------------------------------------------------------
;; deps.edn parsing
;; -----------------------------------------------------------------------------

(defn- mvn-entry? [[_ coord]]
  (and (map? coord) (contains? coord :mvn/version)))

(defn- collect-from
  "Returns [{:lib sym :version v :source label} ...] for one deps map."
  [deps source]
  (for [[lib coord] deps
        :when       (mvn-entry? [lib coord])]
    {:lib lib :version (:mvn/version coord) :source source}))

(defn read-candidates
  "Parse deps.edn at `path`, return de-duped candidates ordered by lib name.
   Each candidate has :lib, :version, :sources (set of source labels)."
  [path]
  (let [{:keys [deps aliases]} (edn/read-string (slurp path))
        top                    (collect-from deps :deps)
        alias-entries          (mapcat
                                (fn [[alias-key {:keys [extra-deps]}]]
                                  (collect-from extra-deps
                                                (keyword "alias" (name alias-key))))
                                aliases)
        merged                 (reduce
                                (fn [acc {:keys [lib version source]}]
                                  (update acc [lib version]
                                          (fn [prev]
                                            {:lib     lib
                                             :version version
                                             :sources (conj (or (:sources prev) #{}) source)})))
                                {}
                                (concat top alias-entries))]
    (->> merged vals (sort-by (juxt (comp str :lib) :version)) vec)))

;; -----------------------------------------------------------------------------
;; ANSI / terminal helpers
;; -----------------------------------------------------------------------------

(def ^:private CSI "[")
(defn- ansi [s] (str CSI s))
(defn- clear-screen [] (print (ansi "2J")) (print (ansi "H")))
(defn- home          [] (print (ansi "H")))
(defn- clear-eol     [] (print (ansi "K")))
(defn- clear-eos     [] (print (ansi "J")))
(defn- move-to     [row col] (print (ansi (str row ";" col "H"))))
(defn- hide-cursor [] (print (ansi "?25l")))
(defn- show-cursor [] (print (ansi "?25h")))
(defn- reverse-on  [] (print (ansi "7m")))
(defn- reverse-off [] (print (ansi "0m")))

(defn- write-line [s] (print s) (clear-eol) (print "\r\n"))

;; -----------------------------------------------------------------------------
;; TUI render
;; -----------------------------------------------------------------------------

(defn- fmt-row
  [{:keys [lib version sources]}]
  (let [src-str (->> sources
                     (map (fn [s]
                            (if (= s :deps) ":deps" (str ":aliases/" (name s)))))
                     sort
                     (str/join ", "))]
    (format "%-50s %-15s %s" (str lib) version src-str)))

(defn- clamp-scroll
  "Returns scroll offset so cursor is within [scroll, scroll+viewport-h)."
  [cursor scroll viewport-h total]
  (let [scroll (max 0 (min scroll (max 0 (- total viewport-h))))]
    (cond
      (< cursor scroll)                       cursor
      (>= cursor (+ scroll viewport-h))       (- cursor viewport-h -1)
      :else                                   scroll)))

(defn- render
  [{:keys [candidates checked cursor scroll term-h term-w]}]
  (home)
  (let [total      (count candidates)
        checked-n  (count checked)
        ;; reserve: 1 header + 1 separator + 1 footer
        viewport-h (max 1 (- term-h 3))
        scroll     (clamp-scroll cursor scroll viewport-h total)
        end        (min total (+ scroll viewport-h))
        sep-w      (max 20 (min 120 (or term-w 100)))
        more-above (pos? scroll)
        more-below (< end total)]
    (write-line (format "Pick deps to index   [%d/%d checked]   showing %d-%d %s%s"
                        checked-n total (inc scroll) end
                        (if more-above "▲ " "")
                        (if more-below "▼" "")))
    (write-line (apply str (repeat sep-w \-)))
    (doseq [i (range scroll end)]
      (let [c       (nth candidates i)
            mark    (if (checked i) "[x]" "[ ]")
            arrow   (if (= i cursor) "▶ " "  ")
            line    (str arrow mark "  " (fmt-row c))]
        (if (= i cursor)
          (do (reverse-on) (print line) (reverse-off) (print "\r\n"))
          (write-line line))))
    ;; pad remaining viewport rows so footer position is stable
    (dotimes [_ (- viewport-h (- end scroll))]
      (write-line ""))
    (write-line "j/k or ↑/↓ move · space/x toggle · a toggle-all · i index · q quit")
    (clear-eos)
    (flush)
    scroll))

;; -----------------------------------------------------------------------------
;; Key reading
;; -----------------------------------------------------------------------------

(defn- read-key
  "Reads one logical key from the JLine terminal reader. Returns one of:
     :up :down :left :right :space :enter :a :i :q :x :other"
  [^java.io.Reader rdr]
  (let [c (.read rdr)]
    (cond
      (= c 27) ;; ESC — could be arrow
      (let [b1 (.read rdr) b2 (.read rdr)]
        (if (and (= b1 91))
          (case (int b2)
            65 :up
            66 :down
            67 :right
            68 :left
            :other)
          :other))
      (= c 32) :space
      (or (= c 10) (= c 13)) :enter
      (= c (int \a)) :a
      (= c (int \i)) :i
      (= c (int \q)) :q
      (= c (int \x)) :x
      (= c (int \j)) :down
      (= c (int \k)) :up
      (= c (int \g)) :home
      (= c (int \G)) :end
      (= c 3)        :q ;; ctrl-c
      :else          :other)))

;; -----------------------------------------------------------------------------
;; TUI loop
;; -----------------------------------------------------------------------------

(defn- run-tui
  "Returns the action map: {:action :index :checked #{idx ...}} or
   {:action :quit}."
  [candidates]
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))
        rdr      (.reader terminal)]
    (.enterRawMode terminal)
    (hide-cursor)
    (clear-screen)
    (try
      (loop [state {:candidates candidates
                    :checked    #{}
                    :cursor     0
                    :scroll     0
                    :term-h     (max 5 (.getHeight terminal))
                    :term-w     (max 40 (.getWidth terminal))}]
        (let [new-scroll (render state)
              state      (assoc state :scroll new-scroll)
              k          (read-key rdr)
              {:keys [cursor checked]} state
              total      (count candidates)]
          (case k
            :up    (recur (assoc state
                                 :cursor (mod (dec cursor) total)
                                 :term-h (max 5 (.getHeight terminal))
                                 :term-w (max 40 (.getWidth terminal))))
            :down  (recur (assoc state
                                 :cursor (mod (inc cursor) total)
                                 :term-h (max 5 (.getHeight terminal))
                                 :term-w (max 40 (.getWidth terminal))))
            :home  (recur (assoc state :cursor 0))
            :end   (recur (assoc state :cursor (dec total)))
            (:space :x)
            (recur (update state :checked
                           (fn [s] (if (s cursor) (disj s cursor) (conj s cursor)))))
            :a (recur (assoc state :checked
                             (if (= (count checked) total) #{} (set (range total)))))
            :i {:action :index :checked checked}
            :q {:action :quit}
            (recur state))))
      (finally
        (show-cursor)
        (print (ansi "0m"))
        (flush)
        (.close terminal)))))

;; -----------------------------------------------------------------------------
;; Non-TTY fallback
;; -----------------------------------------------------------------------------

(defn- non-tty-run
  [candidates {:keys [all? selectors]}]
  (cond
    all?
    {:action :index :checked (set (range (count candidates)))}

    (seq selectors)
    (let [match? (fn [{:keys [lib]} sel]
                   (or (= (str lib) sel)
                       (= (name lib) sel)))
          idxs   (set (for [[i c] (map-indexed vector candidates)
                            sel   selectors
                            :when (match? c sel)]
                        i))]
      {:action :index :checked idxs})

    :else
    (do
      (println "deps.edn candidates:")
      (doseq [c candidates] (println " " (fmt-row c)))
      (println)
      (println "Re-run with --all or LIB-NAMES to select. Not a TTY — no picker.")
      {:action :quit})))

;; -----------------------------------------------------------------------------
;; m2 lookup + jar extraction
;; -----------------------------------------------------------------------------

(defn- lib->group+artifact
  "Returns [group artifact] for a clojure lib symbol. Unqualified syms have
   group = artifact (e.g. `nrepl/nrepl` → `nrepl/nrepl`; `clojure` →
   `clojure/clojure`)."
  [lib]
  (let [n (name lib)
        g (or (namespace lib) n)]
    [g n]))

(defn- m2-jar-file
  "Build the canonical ~/.m2 path for a coord. Returns a File whether or not
   it exists."
  [{:keys [lib version]}]
  (let [[group artifact] (lib->group+artifact lib)
        group-path       (str/replace group "." "/")]
    (io/file (System/getProperty "user.home")
             ".m2" "repository" group-path artifact version
             (str artifact "-" version ".jar"))))

(defn- coord->extract-dir
  "Sub-dir under `root` where this coord's sources are extracted."
  [{:keys [lib version]} root]
  (let [[group artifact] (lib->group+artifact lib)]
    (io/file root group (str artifact "-" version))))

(defn- coord->path-prefix
  "Logical path stored in the DB. e.g. `deps/clojure/data.json-2.5.0`."
  [{:keys [lib version]}]
  (let [[group artifact] (lib->group+artifact lib)]
    (str "deps/" group "/" artifact "-" version)))

(def ^:private clj-source-exts #{".clj" ".cljs" ".cljc"})

(defn- clj-source-entry? [^java.util.zip.ZipEntry e]
  (and (not (.isDirectory e))
       (let [n (.getName e)
             i (.lastIndexOf n ".")]
         (and (pos? i) (clj-source-exts (.substring n i))))))

(defn- extract-jar!
  "Extract every .clj/.cljs/.cljc entry from `jar` into `dest-dir`,
   preserving the in-jar path. Returns count of files written."
  [^java.io.File jar ^java.io.File dest-dir]
  (.mkdirs dest-dir)
  (with-open [zf (ZipFile. jar)]
    (reduce
     (fn [n e]
       (if (clj-source-entry? e)
         (let [out (io/file dest-dir (.getName e))]
           (.mkdirs (.getParentFile out))
           (with-open [in (.getInputStream zf e)]
             (io/copy in out))
           (inc n))
         n))
     0
     (enumeration-seq (.entries zf)))))

(defn- delete-recursively! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (delete-recursively! c)))
    (.delete f)))

;; -----------------------------------------------------------------------------
;; Fast indexing (no LLM)
;; -----------------------------------------------------------------------------

(defn- trim-docstring
  "Collapse internal whitespace to single spaces, trim, and cap at 200 chars
   (with `…` suffix if truncated). Returns nil on nil/blank input."
  [s]
  (when (and s (not (str/blank? s)))
    (let [collapsed (-> s (str/replace #"\s+" " ") str/trim)]
      (if (> (count collapsed) 200)
        (str (subs collapsed 0 199) "…")
        collapsed))))

(defn- fast-record?
  "Public def with a non-blank docstring."
  [fn-rec]
  (and (not (:private? fn-rec))
       (not (str/blank? (:docstring fn-rec)))))

(defn- rewrite-filename
  "Replace the absolute temp-extract path with the stable `deps/...` prefix."
  [{:keys [^String temp-prefix ^String logical-prefix]} fn-rec]
  (update fn-rec :filename
          (fn [fname]
            (if (and fname (str/starts-with? fname temp-prefix))
              (str logical-prefix "/" (subs fname (count temp-prefix)))
              fname))))

(defn- fast-store!
  "Write one minimal row per public+docstringed fn. The trimmed docstring
   is stored in description_llm so the FTS index picks it up alongside
   the raw docstring."
  [db fn-rec]
  (let [trimmed (trim-docstring (:docstring fn-rec))]
    (db/upsert-function!
     db (-> fn-rec
            (assoc :description-llm trimmed
                   :arg-descriptions-llm nil
                   :return-description-llm nil
                   :tags-llm nil
                   :domain-signals-llm nil
                   :general-purpose-score nil
                   :confidence nil
                   :analyzed-by-model "fast"
                   :callee-namespaces (->> (:callees fn-rec)
                                           (keep :to-ns)
                                           distinct vec))
            (select-keys
             [:ns :name :qualified-name :lang :sha
              :filename :line-start :line-end :col-start :col-end
              :arglists-edn :arities :defined-by :private? :docstring
              :pure-heuristic? :pure-heuristic-reasons :types-edn
              :caller-count :caller-namespaces :callee-namespaces :example-callers
              :description-llm :arg-descriptions-llm :return-description-llm
              :tags-llm :domain-signals-llm :general-purpose-score :confidence
              :analyzed-by-model])))))

(defn- progress!
  "Emit a single overwritable status line to stderr. Pass nil/empty to
   clear the line (terminal width assumed ≤ 200)."
  [msg]
  (binding [*out* *err*]
    (print (str "\r" msg "[K"))
    (flush)))

(defn- progress-newline! []
  (binding [*out* *err*] (println) (flush)))

(defn- index-coord-fast!
  "Extract one coord's jar, run kondo on the result, rewrite paths, store
   public+docstringed fns. Emits progress to stderr."
  [db tmp-root {:keys [i n]} {:keys [lib version] :as coord}]
  (let [head (format "[%d/%d] %s %s" i n lib version)
        jar  (m2-jar-file coord)]
    (cond
      (not (.exists jar))
      (do (progress! (str head " — SKIP (jar not in ~/.m2)"))
          (progress-newline!)
          {:lib lib :version version :status :missing})

      :else
      (let [extract-dir    (coord->extract-dir coord tmp-root)
            temp-prefix    (str (.getCanonicalPath extract-dir) "/")
            logical-prefix (coord->path-prefix coord)
            _              (progress! (str head " — extracting jar…"))
            _              (delete-recursively! extract-dir)
            t-ex0          (System/currentTimeMillis)
            file-count     (extract-jar! jar extract-dir)
            t-ex           (- (System/currentTimeMillis) t-ex0)]
        (if (zero? file-count)
          (do (progress! (str head " — SKIP (no .clj/.cljs/.cljc in jar)"))
              (progress-newline!)
              {:lib lib :version version :status :no-sources})
          (let [_        (progress! (format "%s — linting %d source file(s) (extract %d ms)…"
                                            head file-count t-ex))
                t-k0     (System/currentTimeMillis)
                all-recs (kondo/analyze (.getCanonicalPath extract-dir))
                t-k      (- (System/currentTimeMillis) t-k0)
                fast     (filter fast-record? all-recs)
                rewriter (partial rewrite-filename
                                  {:temp-prefix    temp-prefix
                                   :logical-prefix logical-prefix})
                total    (count fast)]
            (loop [[r & more] fast wrote 0]
              (when r
                (when (or (zero? (mod wrote 25)) (nil? more))
                  (progress! (format "%s — writing rows %d/%d (lint %d ms)…"
                                     head wrote total t-k)))
                (fast-store! db (rewriter r))
                (recur more (inc wrote))))
            (progress! (format "%s — indexed %d/%d public+docstring fns (lint %d ms)"
                               head total (count all-recs) t-k))
            (progress-newline!)
            {:lib lib :version version :status :ok
             :indexed total :total (count all-recs)}))))))

;; -----------------------------------------------------------------------------
;; LLM indexing (--no-fast)
;; -----------------------------------------------------------------------------

(def ^:private default-model
  (or (System/getenv "CODE_INDEX_CLAUDE_MODEL") "haiku"))

(def ^:private default-parallel 20)

(defn- public-record?
  "Public def (with or without docstring)."
  [fn-rec]
  (not (:private? fn-rec)))

(defn- llm-store!
  "Store one fn-rec + LLM result. Filename has already been rewritten to the
   logical `deps/...` path."
  [db model fn-rec
   {:keys [description arg_descriptions return_description
           tags domain_signals general_purpose_score confidence
           analyzed-by-model]
    :or   {analyzed-by-model model}}]
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
                 :callee-namespaces (->> (:callees fn-rec)
                                         (keep :to-ns) distinct vec))
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
                 (->> batch (mapv #(future (f %))) (mapv deref))))))

(defn- index-coord-llm!
  "Extract one coord's jar, run kondo, batch-LLM-analyze each source file, and
   store public fns with LLM results. Filenames are rewritten to logical
   `deps/...` paths before DB write."
  [db {:keys [model parallel]} tmp-root {:keys [i n]} {:keys [lib version] :as coord}]
  (let [head (format "[%d/%d] %s %s" i n lib version)
        jar  (m2-jar-file coord)]
    (cond
      (not (.exists jar))
      (do (progress! (str head " — SKIP (jar not in ~/.m2)"))
          (progress-newline!)
          {:lib lib :version version :status :missing})

      :else
      (let [extract-dir    (coord->extract-dir coord tmp-root)
            temp-prefix    (str (.getCanonicalPath extract-dir) "/")
            logical-prefix (coord->path-prefix coord)
            _              (progress! (str head " — extracting jar…"))
            _              (delete-recursively! extract-dir)
            t-ex0          (System/currentTimeMillis)
            file-count     (extract-jar! jar extract-dir)
            t-ex           (- (System/currentTimeMillis) t-ex0)]
        (if (zero? file-count)
          (do (progress! (str head " — SKIP (no .clj/.cljs/.cljc in jar)"))
              (progress-newline!)
              {:lib lib :version version :status :no-sources})
          (let [_        (progress! (format "%s — linting %d source file(s) (extract %d ms)…"
                                            head file-count t-ex))
                t-k0     (System/currentTimeMillis)
                all-recs (kondo/analyze (.getCanonicalPath extract-dir))
                t-k      (- (System/currentTimeMillis) t-k0)
                pubs     (filter public-record? all-recs)
                by-file  (group-by :filename pubs)
                rewriter (partial rewrite-filename
                                  {:temp-prefix    temp-prefix
                                   :logical-prefix logical-prefix})
                total    (count pubs)
                done     (atom 0)
                batches
                (parallel-map
                 (max 1 (or parallel default-parallel))
                 (fn [[filename recs]]
                   (let [t0  (System/currentTimeMillis)
                         src (try (slurp filename) (catch Exception _ ""))
                         res (claude-cli/analyze-file
                              {:model       model
                               :file-source src
                               :fn-records  recs})
                         ms  (- (System/currentTimeMillis) t0)]
                     (swap! done + (count recs))
                     (progress! (format "%s — LLM %d/%d (lint %d ms, last file %d ms)"
                                        head @done total t-k ms))
                     {:recs recs :results res}))
                 by-file)]
            (doseq [{:keys [recs results]} batches
                    fn-rec recs]
              (llm-store! db model (rewriter fn-rec)
                          (get results (:qualified-name fn-rec))))
            (progress! (format "%s — indexed %d/%d public fns via LLM (lint %d ms)"
                               head total (count all-recs) t-k))
            (progress-newline!)
            {:lib lib :version version :status :ok
             :indexed total :total (count all-recs)}))))))

;; -----------------------------------------------------------------------------
;; CLI
;; -----------------------------------------------------------------------------

(def ^:private default-db-path
  (or (System/getenv "CODE_INDEX_DB")
      ".code-intelligence/code-index.db"))

(def ^:private default-tmp-root
  (str (System/getProperty "java.io.tmpdir") "/claude-tools-deps"))

(defn- parse-args [args]
  (loop [opts {:deps-file "deps.edn"
               :all?      false
               :fast?     true
               :selectors []
               :db        default-db-path
               :model     default-model
               :parallel  default-parallel
               :tmp-root  default-tmp-root}
         args args]
    (if-let [[a & rst] (seq args)]
      (cond
        (= a "--all")        (recur (assoc opts :all? true) rst)
        (= a "--fast")       (recur (assoc opts :fast? true) rst)
        (= a "--no-fast")    (recur (assoc opts :fast? false) rst)
        (= a "--db")         (recur (assoc opts :db (first rst)) (next rst))
        (= a "--deps-file")  (recur (assoc opts :deps-file (first rst)) (next rst))
        (= a "--tmp-root")   (recur (assoc opts :tmp-root (first rst)) (next rst))
        (= a "--model")      (recur (assoc opts :model (first rst)) (next rst))
        (= a "--parallel")   (recur (assoc opts :parallel (Integer/parseInt (first rst))) (next rst))
        (or (= a "-h") (= a "--help"))
        (recur (assoc opts :help? true) rst)
        :else                (recur (update opts :selectors conj a) rst))
      opts)))

(defn- print-help []
  (println (str/trim "
code-search index-deps — pick maven deps from deps.edn and index them.

Usage:
  code-search index-deps                         interactive TUI (--fast by default)
  code-search index-deps --all                   non-TTY: select everything
  code-search index-deps org.clojure/data.json   non-TTY: by lib name
  code-search index-deps --deps-file PATH        alternate deps.edn
  code-search index-deps --db PATH               SQLite path
  code-search index-deps --tmp-root DIR          temp extract root (default /tmp/claude-tools-deps)
  code-search index-deps --no-fast               full LLM analysis (slow; uses ~/.claude CLI)
  code-search index-deps --model NAME            LLM model (default: $CODE_INDEX_CLAUDE_MODEL or haiku)
  code-search index-deps --parallel N            concurrent LLM file-batches (default 20)

Fast mode (default):
  • No LLM calls.
  • Indexes ONLY public defs with a docstring.
  • Docstring is whitespace-collapsed and truncated to 200 chars.
  • Jars missing from ~/.m2 are skipped with a note.

--no-fast mode:
  • Indexes ALL public defs (with or without docstring).
  • One LLM call per source file, parallelized across files.
  • Slower and costs API tokens; output includes LLM descriptions, tags, etc.")))

(defn- run-indexing! [{:keys [db tmp-root fast? model parallel] :as opts} picked]
  (when-not fast?
    (binding [*out* *err*]
      (when-not (doctor/ensure!) (System/exit 1))))
  (let [db-conn (db/open db)
        root    (io/file tmp-root)]
    (.mkdirs root)
    (binding [*out* *err*]
      (if fast?
        (println (format "index-deps: db=%s tmp-root=%s fast=true" db (.getPath root)))
        (println (format "index-deps: db=%s tmp-root=%s fast=false model=%s parallel=%d"
                         db (.getPath root) model parallel))))
    (try
      (let [n       (count picked)
            results (doall
                     (map-indexed
                      (fn [idx c]
                        (if fast?
                          (index-coord-fast! db-conn root
                                             {:i (inc idx) :n n} c)
                          (index-coord-llm! db-conn opts root
                                            {:i (inc idx) :n n} c)))
                      picked))
            ok      (filter #(= :ok (:status %)) results)
            missing (filter #(= :missing (:status %)) results)
            empties (filter #(= :no-sources (:status %)) results)
            indexed (reduce + 0 (map #(or (:indexed %) 0) ok))]
        (println)
        (println "Done.")
        (printf "  deps requested: %d\n" (count results))
        (printf "  deps indexed:   %d\n" (count ok))
        (when (seq missing)
          (printf "  jar missing:    %d (see [SKIP] above)\n" (count missing)))
        (when (seq empties)
          (printf "  no clj sources: %d\n" (count empties)))
        (printf "  fns indexed:    %d (public + docstring)\n" indexed))
      (finally
        (delete-recursively! root)))))

(defn -main [& args]
  (let [{:keys [help? deps-file] :as opts} (parse-args args)]
    (cond
      help?
      (print-help)

      (not (.exists (io/file deps-file)))
      (do (binding [*out* *err*]
            (println (str "deps.edn not found: " deps-file)))
          (System/exit 2))

      :else
      (let [candidates (read-candidates deps-file)]
        (if (empty? candidates)
          (println (str "No :mvn/version deps found in " deps-file))
          (let [tty?   (and (term/tty? :stdin) (term/tty? :stdout))
                result (if tty?
                         (run-tui candidates)
                         (non-tty-run candidates opts))]
            (case (:action result)
              :quit  (println "Cancelled.")
              :index (let [picked (map candidates (sort (:checked result)))]
                       (println)
                       (println (format "Selected %d dep(s):" (count picked)))
                       (doseq [c picked]
                         (println "  •" (fmt-row c)))
                       (println)
                       (run-indexing! opts picked))))))))
  nil)
