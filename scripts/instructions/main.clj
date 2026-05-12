(ns scripts.instructions.main
  "Single entry point for the `instructions` CLI distributed via bbin.

   Subcommands:
     index      Build/refresh the instruction index for a directory.
     search     Search the index (default when no subcommand given).
     show       Dump the full record (+ edges) for one doc path.
     gaps       List orphans and foundation docs.
     llm-help   Print CLAUDE.md-ready instructions for AI agents."
  (:require
   [clojure.string :as str]))

(defn- print-usage []
  (println (str/trim "
instructions — Project instruction retrieval (markdown + FTS5 + typed edges)

Usage:
  instructions [QUERY ...]                  search (default)
  instructions index  DIR     [opts]
  instructions search QUERY...[opts]
  instructions show   PATH    [opts]
  instructions gaps           [opts]
  instructions llm-help

Common options:
  --db PATH               SQLite path (default .code-intelligence/instructions.db
                          or $INSTRUCTIONS_DB)
  --format plain|edn|json (search/show)
  --limit N               (search; default 5)
  --mode or|and           (search; default or)
  --model NAME            claude model alias (index; default haiku)
  --parallel N            concurrent LLM calls (index; default 20)
  --k N                   candidate count for pass-2 (index; default 25)
  --confidence-floor F    minimum confidence to store an edge (index; default 0.5)
  --project-root PATH     project root for path normalization (index)
  --force                 ignore caches (index)

`instructions --help` prints this; `instructions llm-help` prints instructions
intended for AI coding agents.")))

(defn -main [& args]
  (let [[sub & rst] args]
    (cond
      (or (nil? sub) (#{"-h" "--help" "help"} sub))
      (print-usage)

      (= sub "index")
      (do (require 'scripts.instructions.index)
          (apply (resolve 'scripts.instructions.index/-main) rst))

      (= sub "search")
      (do (require 'scripts.instructions.search)
          (apply (resolve 'scripts.instructions.search/-search-main) rst))

      (= sub "show")
      (do (require 'scripts.instructions.search)
          (apply (resolve 'scripts.instructions.search/-show-main) rst))

      (= sub "gaps")
      (do (require 'scripts.instructions.gaps)
          (apply (resolve 'scripts.instructions.gaps/-main) rst))

      (= sub "llm-help")
      (do (require 'scripts.instructions.llm-help)
          ((resolve 'scripts.instructions.llm-help/-main)))

      :else
      (do (require 'scripts.instructions.search)
          (apply (resolve 'scripts.instructions.search/-search-main) args)))))
