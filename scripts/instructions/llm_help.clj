(ns scripts.instructions.llm-help
  "Print CLAUDE.md-ready instructions teaching an AI agent how to use
   `instructions` for project instruction retrieval.")

(def help-text
  "## instructions — project instruction retrieval

You have access to `instructions`, a CLI that searches a project-local index
of markdown instruction files (skills, references, how-tos) and surfaces
both direct matches and the prerequisite / companion docs they imply.

**When to use it:** before starting any non-trivial task, run
`instructions '<what you intend to do>'`. The index is built from the
project's own instruction tree, so a match means someone has already written
guidance for the exact thing you're about to attempt.

**Commands:**

  instructions 'implement a fulcro mutation'
      Search by natural-language intent. Returns three sections:
        matches             — direct FTS5 hits, BM25-ranked
        you may also need   — 1-hop prereq edges from the matches
        see also            — 1-hop companion / extends edges

  instructions show docs/instructions/some-doc.md
      Dump the full record for one doc: summary, capability list, typed
      outgoing/incoming edges, and a body excerpt.

  instructions index docs/instructions/
      Build or refresh the index. Cheap to re-run — SHA-gated.

The DB lives at `.code-intelligence/instructions.db` (project-local; checked
in as a warm cache).

**How to read results:**

Each matched doc has an `explains` list of concrete capabilities it teaches.
Prereq edges mean 'read this doc before the matched one to follow it'.
Companion / extends edges mean 'related context worth knowing'.

Read the docs that look relevant via `instructions show PATH` (or just Read
the file directly — the `path` is a real project path). Don't assume a doc
exists for a topic without searching first; orphan topics show up in
`instructions gaps`.
")

(defn -main [& _]
  (println help-text))
