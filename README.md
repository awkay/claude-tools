# code-search

A babashka-native, per-function semantic index for a Clojure / ClojureScript
codebase. Designed for AI coding agents that need to answer **"is there
already a function that does X?"** without re-discovering it by reading the
whole tree.

`clj-kondo` produces deterministic facts (filename, line range, arglists,
callers, callees, purity, types). `claude -p` produces the soft-judgment
fields (one-sentence description, per-arg meaning, return description, tags).
Everything lives in a single SQLite + FTS5 database in the project root.

## Install

This repo ships **two** binaries — `code-search` (per-function code index)
and `instructions` (project-instruction index). bbin 0.2.x only installs the
first `:bbin/bin` entry per `bbin install` call, so installing both takes
two commands:

```bash
# 1. code-search (default entry)
bbin install io.github.awkay/claude-tools

# 2. instructions (must be installed explicitly because bbin installs one
#    binary per call). Run this once per machine.
bbin install io.github.awkay/claude-tools \
     --as instructions \
     --main-opts '["-m" "scripts.instructions.main"]'

code-search doctor    # checks clj-kondo, claude, bb
```

If you only want one, install just the one you need. After installing
`code-search`, its `--help` reminds you of the `instructions` install line.

Prerequisites (auto-detected by `doctor`):

- `bb` (babashka)
- `clj-kondo`
- `claude` (the Claude Code CLI, signed in under a Max subscription)

## Usage

```bash
cd ~/my-clojure-project

# 1. Build the index. Re-runs are SHA-incremental — only changed files cost
#    LLM time.
code-search index src/

# 2. Search by intent. Natural-language queries work; stop-words filtered;
#    FTS5 OR-mode degrades gracefully.
code-search 'validate an email'
code-search 'fetch profile from CDN'
code-search 'jwt middleware'

# 3. Inspect a candidate. Prints a compact "function card": file/lines,
#    args, purity reasoning, description, return type, per-arg meaning,
#    docstring, tags, callers, and up to 5 real call-site snippets.
code-search show com.acme.util.date/->display-str

# 4. Tell your agent how to use this tool.
code-search llm-help >> CLAUDE.md
```

The DB lives at `.code-intelligence/code-index.db` in the project root
(override with `CODE_INDEX_DB`). The WAL sidecar files are gitignored; the
`.db` itself is meant to be checked in as a warm cache for teammates.

## How it works

```
┌─────────────────────────────────────────────────────────┐
│   AI coding agent (uses Bash tool to call code-search)  │
└────────────────┬────────────────────────────────────────┘
                 │
            code-search index / search / show
                 │
                 ▼
        ┌──────────────────────────────────┐
        │  code-index.db (SQLite + FTS5)   │
        │  per-function rows; BM25 search  │
        └────────────┬─────────────────────┘
                     │ built by
                     ▼
    ┌──────────────┐         ┌──────────────────┐
    │  clj-kondo   │   +     │   claude -p      │
    │ (file facts) │         │ (descriptions)   │
    └──────────────┘         └──────────────────┘
```

### What's stored per function

Deterministic fields (no LLM):

- Identity: `ns`, `name`, `qualified_name`, `lang` (clj / cljs / cljc)
- Position: `filename`, `line_start`/`end`, `col_start`/`end`
- Shape: `arglists_edn`, `arities_json`, `defined_by`, `private`, `docstring`
- Purity: `pure_heuristic` + scan-based `pure_heuristic_reasons`
- Types: `types_edn` (from `>defn` guardrails specs, when present)
- Graph: `caller_count`, `caller_namespaces`, `callee_namespaces`,
  `example_callers` (up to 5 real 3-line call-site snippets)
- Fingerprint: `sha` (composite `self + sorted callee sigs` à la fulcro-spec)

LLM-produced fields:

- `description_llm`, `return_description_llm`, `arg_descriptions_llm`,
  `tags_llm`, `domain_signals_llm`, `general_purpose_score`, `confidence`,
  `analyzed_by_model`

### Why this split

The LLM never gets to invent mechanical facts. clj-kondo decides what's in
the file; the LLM only decides what those facts *mean*. This makes the index
small, accurate, and cheap to regenerate.

### Search

FTS5 with BM25 ranking over `(qualified_name, description_llm, docstring,
tags_llm, domain_signals_llm)`. Custom tokenizer splits on Clojure-y
punctuation (`. / - _ ! ? :`) so `simplymeet.web.scroll-monitor/create-observer!`
is searchable as its constituent terms. Default query mode is `OR` —
natural-language queries with stop-words still return ranked hits.

### Incremental indexing

Two cache tiers:

1. **File-SHA gate** — if a file hasn't changed, every function in it is
   skipped (no clj-kondo work beyond the always-on full lint, no LLM).
2. **Function-SHA gate** — within a changed file, fns whose
   `self+callee-arglist` composite SHA matches their stored row are skipped.

clj-kondo always runs over the whole tree (it's fast) so caller information
stays accurate even when most rows are skip-cached.

## Repo layout

```
claude-tools/
├── bb.edn                      bb tasks + pod deps + :bbin/bin entry
├── schema/code_index.sql       SQLite schema (loaded via classpath)
├── scripts/
│   ├── main.clj                code-search dispatcher
│   ├── index.clj               code-search index
│   ├── search.clj              code-search search / show
│   ├── llm_help.clj            code-search llm-help
│   ├── doctor.clj              code-search doctor
│   ├── validate.clj            bb validate (dev-only regression check)
│   └── lib/
│       ├── analysis.clj        purity heuristic, arglist extraction, guardrails types
│       ├── claude_cli.clj      `claude -p` wrapper + prompt builder
│       ├── db.clj              SQLite helpers, FTS query builder, file-SHA cache
│       ├── kondo.clj           clj-kondo wrapper, body slicing, caller mining, CLJC merge
│       └── sha.clj             content normalize + sha256 (port from fulcro-spec)
├── bin/                        Local convenience wrappers (bbin is the real distribution)
└── test/                       19 deftests, 153 assertions
    ├── fixtures/sample.cljc
    └── scripts/lib/{sha,analysis,kondo,db}_test.clj
```

## Hacking on it

```bash
bb test            # full clojure.test suite
bb nrepl           # bb nREPL on :1667
bb index PATH      # build/refresh index against any source tree
bb search QUERY    # search the index
bb show QNAME      # show one fn
bb doctor          # env check
bb llm-help        # the CLAUDE.md content
bb validate        # fixed-sample regression run against ../simplymeet
bb rebuild-fts     # drop & recreate functions_fts (pick up tokenizer changes)
```

See `PLAN.md` for the design rationale, Phase 1 validation findings (FTS5
sufficiency, model comparison against qwen-coder), engineering notes
(SQLite pod concurrency, CLJC merge, prompt shape), and what's deliberately
deferred (instruction retrieval DB, MCP server, embeddings).
