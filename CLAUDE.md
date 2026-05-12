# Project conventions for AI agents

## bb.edn and deps.edn stay in sync

This repo ships two manifest files at the root:

- **`bb.edn`** — used by `bb` for local development (tasks, pods, paths).
- **`deps.edn`** — used by `tools.deps` so that `bbin install io.github.awkay/claude-tools`
  can resolve the project as a git library. (bbin shells out to clojure CLI to
  build a classpath, which requires `deps.edn`.)

Both files declare `:bbin/bin` and `:paths`. **If you change one, change the
other.** In particular:

- Both binaries are declared:
  - `code-search → scripts.main`
  - `instructions → scripts.instructions.main`
- The classpath `:paths` (for resource resolution, especially
  `schema/code_index.sql` and `schema/instructions.sql` via `io/resource`)
  must match.

If you forget to update `deps.edn`, local `bb` commands still work, but
`bbin install` from a git coordinate breaks. If you forget to update `bb.edn`,
local `bb` development breaks but a published install still works.

## Tests

`bb test` runs the full clojure.test suite. Keep it green.

## Don't commit

- `.code-intelligence/` — local per-project SQLite index. The directory is
  gitignored. Users build their own when they install.
- `.claude/` — local Claude Code working dir. Gitignored.
