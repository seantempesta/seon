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
