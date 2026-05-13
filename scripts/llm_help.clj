(ns scripts.llm-help
  "Prints CLAUDE.md-ready instructions teaching an AI coding agent when and how
   to use the `code-search` tool. Pipe to your project's CLAUDE.md (or whatever
   your agent reads): `code-search llm-help >> CLAUDE.md`")

(def help-text
  "## Code intelligence (code-search)

This project has a per-function semantic index of its Clojure / ClojureScript
source. Use it to discover existing functions by intent before writing new ones,
and to inspect a candidate's shape and call-sites.

### Commands

```bash
# Search by intent (natural language is fine).
code-search 'validate an email address'

# Use the explicit `search` subcommand when passing options — otherwise the
# flags will be parsed as part of the query. Example: narrow to a sub-tree
# and ask for more results.
code-search search --ns-filter com.acme.billing --limit 20 'apply a discount'

# Inspect a candidate — full function card: args, purity, description,
# docstring, call-site examples, callers, callees.
code-search show com.acme.util.date/->display-str
```

Other flags exist (`code-search --help`); the most useful for narrowing noisy
results are `--ns-filter`, `--ns-exclude`, `--limit`, and `-v` (verbose).

### How to read search results

Default output is two lines per hit:

```
ns/name
  one-sentence description
```

Pass `-v` for the verbose form, which adds:
- `file:line_start-line_end` — exact source location.
- `gp` — general-purpose score (0..1). High = pure utility; low = domain-specific.
- `conf` — confidence (0..1) of the description.
- `callers` — number of call-sites in the indexed tree.
- `tags` — semantic keywords.
- `score` — final ranking score (more negative = better match). Composed of
  BM25 + a namespace boost − a function of caller_count, so library-ish
  namespaces and well-used functions tend to rank higher.

CLJC files emit one row per language; if the same `ns/name` appears for both
clj and cljs with different bodies you'll see two hits.

Descriptions are LLM-generated and can be subtly wrong. The qualified-name,
location, args, and callers are deterministic and trustworthy; read the actual
source before relying on any specific behavior.
")

(defn -main [& _]
  (println help-text))
