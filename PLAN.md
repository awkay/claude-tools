# Clojure Code Intelligence Toolset

A small babashka-native toolset that gives an AI coding agent on-demand access to a per-function semantic index of a Clojure / ClojureScript codebase. The agent answers questions like "do I already have a function that does X?" by searching the index rather than re-discovering by name in source.

## Status

- **Phase 1 (foundation + validation):** done
- **Phase 2 (production indexer + CLIs):** done
- **Phase 3 (distribution via bbin + tests + agent help):** done
- **MCP server:** deferred — separate feature, unblocked.
- **Embeddings:** deferred — FTS5 keyword search over AI-generated descriptions is sufficient on real queries (see Phase 1 findings below)

## Install

```bash
bbin install io.github.awkay/claude-tools
code-search --help
```

Requires `clj-kondo` and `claude` (the Claude Code CLI) on PATH; the binary's
`code-search doctor` subcommand checks and offers `brew install` for anything missing.

## Using it

The installed binary is `code-search` with subcommands. Bare `code-search QUERY...`
is shorthand for `code-search search QUERY...`.

```bash
cd ~/my-clojure-project

# One-time per project: build the index
code-search index src/

# Day-to-day: search by intent (natural language works)
code-search 'validate an email'
code-search 'fetch profile from cdn'

# Inspect a candidate before using it
code-search show com.acme.util.date/->display-str

# Drop instructions into your project's CLAUDE.md so AI agents
# learn how to use the tool
code-search llm-help >> CLAUDE.md
```

The DB lives at `.code-intelligence/code-index.db` in the project root (override
with `CODE_INDEX_DB`). Re-running `code-search index` after changes is cheap:
unchanged files are skipped via SHA cache.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│              Claude Code Agent                          │
│         (uses Bash tool to call CLIs)                   │
└────────────┬────────────────────────────┬───────────────┘
             │                            │
       code-search                  code-show
             │                            │
             ▼                            ▼
         ┌───────────────────────────────────┐
         │   code-index.db (SQLite + FTS5)   │
         │  per-function rows; BM25 search   │
         └────────────┬──────────────────────┘
                      │
                  code-index
                      │
        ┌─────────────┴────────────┐
        │       clj-kondo          │   ← deterministic facts
        │   (file analysis, EDN)   │
        └─────────────┬────────────┘
                      │
                  claude -p          ← LLM judgment (description, tags, etc.)
                  (Max plan)
```

The pipeline is intentionally lopsided: **everything mechanical comes from clj-kondo + source slicing**; **only soft-judgment fields come from the LLM**. The LLM never invents filenames, line numbers, arities, callers, purity, or types.

### Engine choice

We use `claude -p --model haiku` under the user's Claude Max subscription.

- Compared to local Ollama qwen-coder:3b: roughly equivalent per-call latency, **but real parallelism** (no shared GPU). 25 sample fns at parallel=20 finish in ~54s vs ~140s for Ollama.
- Compared to qwen-coder:1.5b: qwen-1.5b made 4 major errors (wrong return types on HOFs/middleware/promise-returning fns); qwen-3b and Haiku had 0. We chose Haiku for both speed and accuracy. See `comparison_report.md` from the validation pass for the full audit.

The Anthropic-API-direct path (using an API key, not Max) would be ~20× faster per call (no CLI startup), at the cost of separate billing.

## Repository layout

```
claude-tools/
├── PLAN.md                          ← this file
├── bb.edn                           ← bb tasks + pod deps + :bbin/bin entry
├── .gitignore                       ← WAL sidecars, .clj-kondo cache
├── .code-intelligence/
│   └── code-index.db                ← SQLite + FTS5 index (checked in as warm cache)
├── schema/
│   └── code_index.sql               ← functions + files + functions_fts + triggers
│                                     (loaded via classpath so it works after bbin install)
├── bin/                             ← Local convenience wrappers (not the distribution path)
│   ├── code-index                   ← bb index
│   ├── code-search                  ← bb search
│   └── code-show                    ← bb show
├── scripts/
│   ├── main.clj                     ← `code-search` entry; dispatches subcommands
│   ├── index.clj                    ← `code-search index`
│   ├── search.clj                   ← `code-search search` and `show`
│   ├── llm_help.clj                 ← `code-search llm-help` — CLAUDE.md instructions
│   ├── validate.clj                 ← bb validate (fixed 25-fn regression check)
│   ├── doctor.clj                   ← env check (clj-kondo, claude, bb)
│   └── lib/
│       ├── analysis.clj             ← deterministic enrichment (purity, arglists, types)
│       ├── claude_cli.clj           ← `claude -p` wrapper + prompt builder
│       ├── db.clj                   ← SQLite helpers, FTS query builder, file-SHA cache
│       ├── kondo.clj                ← clj-kondo wrapper, body extraction, caller mining, CLJC merge
│       └── sha.clj                  ← content normalize + sha256 + composite signature (port from fulcro-spec)
└── test/
    ├── fixtures/sample.cljc         ← clj-kondo fixture for kondo-test
    └── scripts/lib/
        ├── sha_test.clj             ← signature port from fulcro-spec
        ├── analysis_test.clj        ← purity, arglists, guardrails, docstring args
        ├── kondo_test.clj           ← analyze, body extraction, caller mining, CLJC merge
        └── db_test.clj              ← schema, upsert, file-sha cache, FTS5 query builder
```

Run with `bb test` — currently 19 deftests, 153 assertions, all green.

## What's stored per function

Per-function row in `functions`:

**Identity (deterministic):**
`ns`, `name`, `qualified_name`, `lang` (clj / cljs / cljc — `cljc` collapses the two `:lang` halves when their normalized bodies match), `sha` (composite fingerprint of self + sorted callee sigs)

**Source location (deterministic, refreshed on file-SHA change without re-LLM):**
`filename`, `line_start`, `line_end`, `col_start`, `col_end`

**Shape (deterministic):**
`arglists_edn` (e.g. `([x] [x y])` via rewrite-clj), `arities_json`, `defined_by`, `private`, `docstring` (verbatim)

**Purity (deterministic heuristic):**
`pure_heuristic` (0/1), `pure_heuristic_reasons` (regex-based scan of body for `swap!`, `set!`, `io/`, JS interop, etc.)

**Types (deterministic when present):**
`types_edn` (extracted from `>defn` guardrails specs)

**Callers/callees (deterministic from clj-kondo):**
`caller_count`, `caller_namespaces`, `callee_namespaces`, `example_callers` (JSON: up to 5 real call-site snippets, ~3 lines each, mined from `var-usages`)

**Soft judgment (LLM only):**
`description_llm` (one sentence), `arg_descriptions_llm`, `return_description_llm`, `tags_llm`, `domain_signals_llm`, `general_purpose_score` (0..1), `confidence` (0..1), `analyzed_by_model`

## CLI

After `bbin install`, users run a single `code-search` binary with subcommands.
During local development on this repo, the same actions are exposed as `bb` tasks.

### `code-search index SOURCE-DIR` (or `bb index ...`)

Indexes (or re-indexes) a source tree. Two cache tiers:

1. **File-SHA gate**: if the file content sha matches the stored value, the file is skipped entirely — no per-fn work, no LLM.
2. **Function-SHA gate**: within a changed file, fns whose composite SHA matches the stored row are skipped (positions can be refreshed without LLM via `db/update-positions!`, though the current path just leaves them alone since clj-kondo positions come from the same fresh lint).

Options: `--db PATH`, `--model NAME` (default `haiku`), `--parallel N` (default 20), `--force` (ignore caches).

clj-kondo always runs over the **full** tree, so cross-file `caller_namespaces` / `example_callers` stay accurate even when most fns are skip-cached.

### `code-search [search] QUERY...` (or `bb search ...`)

BM25 ranked FTS5 search over `(qualified_name, description_llm, docstring, tags_llm, domain_signals_llm)`. Default mode `OR` (natural-language queries degrade gracefully). FTS5 tokenizer splits on Clojure punctuation (`. / - _ ! ? :`) so `simplymeet.web.scroll-monitor/create-observer!` is searchable as the constituent tokens. Stop-words filtered at query-build time.

Bare `code-search QUERY...` (no subcommand) is shorthand for `code-search search QUERY...`.

Options: `--db PATH`, `--limit N`, `--mode or|and`, `--format plain|edn|json`.

### `code-search show QUALIFIED-NAME` (or `bb show ...`)

Dumps the full record for one function as a compact "function card" suitable for an LLM agent to read: file/lines, args, purity reasoning, description, return description, per-arg descriptions, docstring, tags, callees, callers, and up to 5 real call-site snippets.

### `code-search llm-help` (or `bb llm-help`)

Prints CLAUDE.md-ready instructions teaching an AI agent when and how to use the tool.
Pipe to your project's agent config:

```bash
code-search llm-help >> CLAUDE.md
```

### `code-search doctor` (or `bb doctor`)

Checks `clj-kondo`, `claude`, `bb` are on PATH. Offers `brew install` for what's missing.

### `bb validate` (dev-only)

Fixed-sample regression test: runs the full pipeline on 25 hand-picked functions from `../simplymeet` and prints a results table + latency stats + FTS sanity checks. Used during pipeline development; not part of the normal user flow.

## Engineering details worth knowing

### Cross-process concurrency on SQLite

The babashka go-sqlite3 pod is a single subprocess and **doesn't internally serialize concurrent calls** — under 20-way concurrent writes it SIGSEGV'd. The indexer pipeline therefore does:

1. Serial cache-check (read `functions` rows)
2. Parallel LLM analysis (futures, no DB I/O)
3. Serial DB writes

This keeps the pod single-threaded and gives us real LLM parallelism.

### CLJC handling

clj-kondo emits each var-def in a `.cljc` file twice (once with `:lang :clj`, once `:cljs`). After grouping by `[ns name filename]`:

- Both halves identical (same body sha) → one row with `lang = "cljc"`
- Bodies diverge (reader-conditional changes implementation) → two rows with `lang = "clj"` and `lang = "cljs"`

Caller-snippet sampling dedupes by `[filename row col]` so the same call-site doesn't appear twice in `example_callers`.

### SHA fingerprinting

Ported from `fulcro-spec/src/main/fulcro_spec/signature.clj` (the same algorithm the fulcro-spec coverage system uses). Strips docstrings, collapses whitespace, sha256s the result. Two-field composite for non-leaf fns: `"selfhex,calleeshex"` where `calleeshex` is sha of sorted callee signatures, so changing a callee's contract triggers caller re-analysis.

Tests are direct ports from `fulcro-spec/src/test/fulcro_spec/signature_spec.clj` — 52 assertions in `clojure.test`, runnable via `bb test`.

### Prompt shape

The LLM receives only what it actually needs to make judgment calls:

```
Namespace: <ns>
Function: <name> [(private)]
Arglists: ([arg ...])
[Guardrails spec: [spec ... => spec]]
Purity (heuristic): appears pure | side-effecting [— signals: ...]

Source:
<full body, including docstring in its natural position>

Example call sites (real code):
  in <caller-ns>/<caller-var>:
    <3 lines around the call site>
  ...

Return ONE JSON object with: description, arg_descriptions,
return_description, tags, domain_signals, general_purpose_score,
confidence.
```

Deterministic fields (`Calls:`, `Used by:`, etc.) are *not* in the prompt — they'd be model-readable noise about facts we already know. Example call-sites carry the same info in a form the model can actually use to infer argument semantics.

## Phase 1 validation findings

- **Hybrid quality verdict** (Haiku-comparison report): qwen-3b had 0 major errors but missed nuance on a few caveats. Haiku had 0 errors of any kind. We use Haiku.
- **FTS5 sufficiency:** every query in the validation set that returned 0 hits on the first version of the index returned the right function after two tokenizer tweaks (custom separators + OR-mode). Embeddings deferred.
- **Throughput:** 25 fns in ~54s at parallel=20. ~2.5s amortized per fn. Index of 454 fns ran in ~9.5 min.

## Not yet built

### MCP server

Optional thin HTTP wrapper around the same CLIs. Useful for non-CLI MCP clients. Not needed for Claude Code agents (they call the binaries via Bash).

### Embeddings as fallback retrieval

`nomic-embed-text` via Ollama + a `desc_embedding BLOB` column + cosine-rerank in process. Implement only if real-world queries start missing in ways FTS5 + the current tokenizer can't fix.

### Deletion handling

Currently if a function is removed from the source tree, its row persists in the index. Phase 3 cleanup: after a full index pass, delete rows whose `qualified_name` isn't in the current clj-kondo output.
