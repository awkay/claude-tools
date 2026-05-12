(ns scripts.main
  "Single entry point for the `code-search` CLI distributed via bbin.

   Subcommands:
     index      Build/refresh the index for a source directory.
     search     Search the index (default when no subcommand given).
     show       Dump the full record for one function.
     doctor     Check environment (clj-kondo, claude, bb).
     llm-help   Print CLAUDE.md-ready instructions for AI agents.

   Examples:
     code-search index src/main
     code-search 'serialize a profile'        # search is the default
     code-search search 'jwt middleware'
     code-search show com.acme.util/format-date
     code-search llm-help >> CLAUDE.md"
  (:require
   [clojure.string :as str]))

(defn- print-usage []
  (println (str/trim "
code-search — Clojure code intelligence index

Usage:
  code-search [QUERY ...]              search (default)
  code-search index   SOURCE-DIR [opts]
  code-search search  QUERY...   [opts]
  code-search show    QUALIFIED-NAME [opts]
  code-search doctor
  code-search llm-help

Common options:
  --db PATH          SQLite path (default .code-intelligence/code-index.db
                     or $CODE_INDEX_DB)
  --format plain|edn|json   (search/show)
  --limit N          (search)
  --mode or|and      (search; default or)
  --model NAME       claude model alias (index; default haiku)
  --parallel N       concurrent LLM calls (index; default 20)
  --force            ignore caches (index)

`code-search --help` prints this; `code-search llm-help` prints instructions
intended for AI coding agents.")))

(defn -main [& args]
  (let [[sub & rst] args]
    (cond
      (or (nil? sub) (#{"-h" "--help" "help"} sub))
      (print-usage)

      (= sub "index")
      (do (require 'scripts.index) (apply (resolve 'scripts.index/-main) rst))

      (= sub "search")
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-search-main) rst))

      (= sub "show")
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-show-main) rst))

      (= sub "doctor")
      (do (require 'scripts.doctor) ((resolve 'scripts.doctor/-main)))

      (= sub "llm-help")
      (do (require 'scripts.llm-help) ((resolve 'scripts.llm-help/-main)))

      ;; No subcommand match: treat all args as a search query.
      :else
      (do (require 'scripts.search) (apply (resolve 'scripts.search/-search-main) args)))))
