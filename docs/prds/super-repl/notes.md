# Super REPL - Agent Notes

## Phase 1b: Production Hardening Gotchas

### Non-blocking pool startup
- `create-pool!` returns immediately. Integration tests MUST call `(pool/await-warm pool 30000)` before asserting pool counts or acquiring JVMs.
- The `remaining-warmup` atom counts down as each spawn future completes (success or failure). When it hits zero, `::warming?` is set to false. This means warming "completes" even if some JVMs failed to spawn -- the pool just has fewer JVMs than requested.
- `replenish-pool!` does NOT use `remaining-warmup` -- it's only for the initial warmup phase. Replenishment futures don't affect the warming flag.

### Blocking acquire
- `acquire!` with `::timeout-ms` uses `.poll(timeout, TimeUnit/MILLISECONDS)` on the `LinkedBlockingQueue`. This is safe to call from any thread.
- `acquire!!` uses `.take` which blocks FOREVER. If the pool is shut down while a thread is blocked on `.take`, that thread will be stuck. Use `acquire!` with `::timeout-ms` in production.
- Both `acquire!` and `acquire!!` call `activate-jvm!` which does `setup-namespace!` (nREPL eval). If the JVM died between being enqueued and being acquired, `activate-jvm!` will throw. Callers should handle this.

### Stale process cleanup
- `cleanup-stale-agents!` uses `lsof -ti :PORT` to find PIDs. On macOS, `lsof` is always available. On Linux, the `lsof` package may need to be installed.
- The safety range is hardcoded to 7900-7999. If agent ports are configured outside this range, cleanup will skip them and log a warning.
- Cleanup runs synchronously at the start of `create-pool!`. If many stale processes exist, this adds a small delay (~500ms per stale process for TCP timeout + lsof + kill).
- The TCP connect test uses a 500ms timeout. This is short because we're connecting to localhost.

### Unit testing mock pools
- Unit tests construct pool structures manually with `(atom {...})` and `(LinkedBlockingQueue.)`. These tests can't test `acquire!`/`acquire!!` end-to-end because `activate-jvm!` calls `setup-namespace!` which needs a real nREPL.
- For testing timeout behavior of `.poll(timeout)`, test the `LinkedBlockingQueue` directly rather than going through `acquire!`.
- The `acquire-blocking-test` catches the exception from `setup-namespace!` and only validates the timing behavior.

### Port allocation
- `allocate-port!` increments `::next-port` atomically via `swap-vals!`. Ports are never recycled within a pool lifetime. If you create and dispose many JVMs, ports will eventually exceed 7999 and violate the `::port` schema. In practice, this would require 100 JVM lifecycles which doesn't happen in normal operation.

## Phase 2: Knowledge Graph Foundation Gotchas

### Datalevin dependency placement
- Datalevin is a dev-only dependency (in `:dev` alias). It was NOT in the `:test` alias initially, causing `FileNotFoundException: Could not locate datalevin/core__init.class`. Added `datalevin/datalevin {:local/root "reference-code/datalevin"}` to the `:test` alias in `deps.edn`.

### REPL comment blocks and namespace aliases
- `::alias/keyword` syntax in `(comment ...)` blocks causes read-time failures if the alias is not declared in the `ns` form. Dynamic `(require '[... :as alias])` inside comment blocks does NOT make `::alias/keyword` resolve -- the reader needs the alias at read time, not runtime. Use fully-qualified keywords like `:seon.db.datalevin.conn/manager` instead.

### Datalevin local connections for tests
- Tests use `(d/get-conn dir)` with a temp directory instead of a Datalevin server connection. This gives each test an isolated database without needing the server running. Clean up with `(d/close conn)` and `(delete-dir dir)` in a fixture.

### Datalevin get-else for optional attributes
- `call-graph` and `callers-of` use `[(get-else $ ?e :graph/line -1) ?line]` to handle entities without `:graph/line`. Datalevin's Datalog requires binding all variables, so optional attributes need `get-else` with a sentinel value.

### Bulk ingestion strategy
- `ingest-analysis!` does a full retract-by-type then re-insert. This is simple and correct but means the graph is briefly empty during re-ingestion. For Phase 3+ consider making this atomic.
- `ingest-incremental!` is smarter: only retracts entities for the affected namespace(s), then inserts new ones. This preserves the rest of the graph during incremental updates.

### analyze-form implementation
- The PRD suggested using `seon.dev.lint/lint-source` for per-form analysis. However, `lint-source` uses a different clj-kondo config (focused on error detection, not analysis extraction). Instead, `analyze-form` uses `clj-kondo/run!` directly with the same `project-kondo-config` that `analyze-project!` uses. This ensures consistent analysis output for both modes.

### Entity deduplication
- Namespace dependency entities (`ns-dependency`) are deduplicated with `(distinct)` during extraction because clj-kondo can report multiple usages of the same required namespace.
- Function entities are NOT deduplicated during extraction because each function definition is unique by namespace + name.
