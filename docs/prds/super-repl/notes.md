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

## Phase 4: Pool Integration (start-namespace-jvm!) Gotchas

### Agent JVM classpath includes all src/
- The `:agent` alias uses `:replace-paths ["src"]` so all `seon.flow.harness.*` namespaces are available in agent JVMs. No need to send source code via nREPL -- just `(require ...)`.

### Bridge loop is a simple future, not a full flow
- For MVP, the agent JVM runs a `(future (loop [] ...))` that reads requests from a TCP channel, calls `bridge/execute-local`, and writes replies. This is simpler than running a full core.async.flow graph in the agent JVM. The bridge step-fn's `execute-local` does the real work.

### TCP server port 0 for random assignment
- `start-namespace-jvm!` uses `(channel/start-server! {::channel/port 0})` to get a random port, then tells the agent JVM to connect to that port via nREPL eval. This avoids port conflicts.

### nREPL eval for code loading is two-phase
- Phase 1: `require` the channel and bridge namespaces (these are on classpath).
- Phase 2: Define the TCP connection and start the bridge loop. The bridge loop var is `bridge-loop` in the agent JVM's current namespace.
- If either phase fails, `start-namespace-jvm!` cleans up the TCP server and releases the JVM back to the pool.

### Integration tests create their own pool
- `pool_integration_test.clj` creates a 1-JVM pool on port 7950 (to avoid conflict with the main pool on 7900). Tests self-skip if pool creation fails. The pool fixture uses `:once` so JVM startup cost is paid only once.

### Disposing vs releasing after integration tests
- `stop-namespace-jvm!` calls `pool/release!` which resets the namespace and returns the JVM to the idle queue. If the bridge loop left state in the JVM, release may fail and fall back to `dispose!` (kills the process).

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

## Phase 4: Flow Topology (Step 5) Gotchas

### pending-promises is a global atom
- The reply-router step-fn delivers promises registered in a global `topology/pending-promises` atom. This is necessary because `request!` must register the promise BEFORE calling `flow/inject`, and the reply-router process runs in a separate thread. The atom bridges the two.
- `defonce` is used so REPL reloads don't lose in-flight promises.

### flow/inject works for request delivery
- `flow/inject` successfully puts messages onto a process's input channel asynchronously. The coord is `[pid input-id]` where pid is the process keyword in the flow def and input-id matches the `:ins` key.
- `flow/inject` returns a future. We don't need to deref it for request delivery since the promise mechanism handles completion.

### Process naming convention
- Namespace processes use `(keyword "ns" ns-string)` as their pid, e.g. `:ns/seon.test.beta`. This keeps them distinct from infrastructure processes like `:seon.flow/reply-router`.

### Flow lifecycle: create -> start -> resume
- `create-flow` creates the flow but does NOT start it.
- `start` starts processes but they begin PAUSED.
- `resume` actually starts message processing.
- Tests need a small `Thread/sleep` after `resume` for processes to be ready.

### Mock JVM channels for testing
- Tests simulate agent JVMs with go-loops that read from the out-port channel and write replies to the in-port channel. This avoids needing real JVMs or TCP connections.
- The harness's `::harness/in-ports` and `::harness/out-ports` keys inject channels that become `::flow/in-ports` and `::flow/out-ports` in the process state.

## Phase 4b: Transparent Proxy Calls + Real JVM Integration

### Proxy namespaces for transparent remote calls
- `proxy-ns!` creates real Clojure namespaces in agent JVMs with `create-ns` + `intern`. Proxy vars call `bridge/remote-call!` under the hood.
- Agents write `(nutrition/metabolic-rate {::weight 80})` — normal Clojure. The proxy routing is invisible.
- `::proxy?` metadata flag on vars is visible via `(meta #'var)`. Intentional for introspection.
- Partial namespace creation has no rollback — if `proxy-ns!` fails mid-way, you get a partially populated namespace.

### Reverse channel for agent-to-agent calls
- Bridge has `pending-remote-promises` atom (same pattern as topology's `pending-promises`).
- `remote-call!` sends request on reverse channel, blocks on promise, returns value.
- Cross-ns relay in topology: go-loop per namespace reads reverse requests, calls `topology/request!`, sends reply back. Uses `async/thread` per request — no deadlock on A→B→A calls.
- **Livelock risk**: deep circular call chains with low queue-cap could exhaust capacity. Queue-cap > depth of call chains avoids this.

### Real pool JVM integration
- `start-namespace-jvm!` acquires JVM, starts TCP server on port 0, loads bridge code in two phases via nREPL (require namespaces, then start TCP client + loop).
- Simple `future` loop in agent JVM (not a full flow) — reads request, calls `execute-local`, writes reply. Simpler than running flow in the agent JVM.
- Pool integration tests use port range 7950-7969 to avoid conflicts with dev pool.
- Domain tests use port range 7960-7969.
- Tests self-skip if pool creation fails (no hard dependency on running server).

### Cycle detection
- DFS-based cycle detection runs at `build-topology!` time. Checks `::proxy-targets` in namespace configs.
- Throws `ex-info` with `:cycle-detected true` and human-readable cycle descriptions.

### Event/error sink processes
- Flow throws "can't resolve channel with coord" if a process emits to an unwired output.
- Fixed by adding `event-sink-step` and `error-sink-step` processes to every topology. They store recent events/errors in ring buffers (max 100) for observability.

### Flow observability
- `flow/ping` returns per-process status, message count, and state. Cheap (<1ms).
- `datafy` returns static topology (connections).
- `error-chan` streams all errors with full context.
- No public API for channel buffer depth — skip in v1.
- `user/flow-status` returns structured map of all registered flows with process states, throughput, errors, alerts.

### Performance on real JVMs
- Fast calls (log-meal, log-workout): ~1-2ms round-trip through TCP
- Simulated DB lookup (20-70ms sleep): ~35ms total
- Simulated computation (50-150ms sleep): ~134ms total
- TCP + flow overhead: ~1-2ms on top of function execution time
- Timeout recovery works: namespace stays responsive after timeout
- Burst load (10 concurrent): all succeed

## Phase 3: Super REPL Form Router Gotchas

### Datalevin requires java.util.Date, not java.time.Instant
- `:db.type/instant` in Datalevin expects `java.util.Date`, not `java.time.Instant`
- Use `(Date.)` not `(Instant/now)` for `:form/created-at`

### Datalevin cannot store nil values
- If `:form/name` is nil (for expressions), omit the key entirely from the entity map
- Use `cond->` to conditionally assoc nullable fields

### Datalevin aggregate queries (max, min) return SpillableSet
- `(d/q '[:find ?name (max ?v) ...])` returns results where the aggregate value is a SpillableSet, not a plain value
- Workaround: query raw tuples `[:find ?e ?name ?v ...]` and compute max in Clojure with `group-by` + `max-key`

### Test fixture isolation
- Each test uses `:each` fixture with a fresh temp Datalevin conn
- But within a single test, multiple `testing` blocks share the same DB state
- Version numbers accumulate across `testing` blocks in the same `deftest`

## Phase 3: Agent Environment (seon.agent.env)

### Datalevin uses java.util.Date, not java.time.Instant
- Datalevin schema with `:db.type/instant` expects `java.util.Date`, NOT `java.time.Instant`
- Using `Instant/now` in transact data causes `ClassCastException`
- Use `(java.util.Date.)` instead

### d/q with `(pull ...)` returns tuples
- `(d/q '[:find (pull ?e [...]) ...] ...)` returns vectors of `[pulled-map]` tuples
- Use `ffirst` to get the actual map, not `first`
- This differs from `(d/pull @conn [...] eid)` which returns the map directly

### ctx-schema must be merged with graph schema
- `env/ctx-schema` defines `:seon.ctx/*` attributes for context persistence
- When creating a Datalevin conn for tests, merge: `(merge ingest/datalevin-schema env/ctx-schema)`
- In production, the connection manager should include both schemas

## Phase E: Uniform Namespace Treatment (Refinement)

### Lazy connection via connection manager
- Primer ctx and orchestrator sessions now store a connection manager ref (not a raw conn)
- Connections are obtained lazily on first use via `conn/get-namespace-conn!`
- This eliminates the race condition where system.clj tried to resolve connections during init
- Both use Integrant keys (`:seon/primer-ctx`, `:seon/orchestrator-sessions`) with `#ig/ref :seon/connection-manager`

### `get-namespace-conn!` accepts optional `::schema`
- Callers that need schema pass `::conn/schema dl-schema` when getting their connection
- Others don't change — schema defaults to nil (no schema applied)
- This lets each namespace define its own Datalevin schema without a central compiler

### Port conflict test fix
- `start-namespace-nrepl!` now retries up to 3 times on BindException (TOCTOU race between port check and bind)
- Explicit port requests don't retry (port-conflict-test still validates error handling)
- Test fixture sleep increased from 20ms to 100ms for OS socket release

### Removed `*test-dl-conn*` dynamic var
- Orchestrator session tests use `with-redefs-fn` on the private `get-dl-conn` function
- Eliminates coupling between test infrastructure and production code

## Render Pipeline WP4-6: Client Tracking + Migration

### seon.ctx client tracking
- `::track-clients?` defaults to false. Existing ctx instances (flow, primer) are unaffected.
- Client push uses `requiring-resolve` for http-kit and chassis to avoid hard deps from ctx.clj.
- `render-and-push!` uses `seon.web.reactive.transform/transform-hiccup` -- that module survives deletion.
- `::render-fn` on ctx is a temporary bridge. Later replaced by Datalevin resolution via `find-renderer`.

### Migration from reactive.instance
- `instances-for-namespace` returns registry entries with `::ctx/instance-id` added (not `::instance/id`).
- routes.clj destructures with `{::ctx/keys [instance-id] :keys [created-at]}` for instance rows.
- ID generation moved to `ctx/generate-id` (public fn), called by routes before `ctx/create!`.

### Deleted files
- `src/seon/web/reactive/instance.clj` - replaced by seon.ctx with `::track-clients? true`
- `src/seon/web/reactive/ctx.clj` - replaced by `ctx/clients-for-namespace` and `ctx/client-count-for-namespace`
- `seon.ai.agent/xtdb-node` atom and `init!` fn removed (set but never read)
- `seon.web.server` no longer requires `seon.ai.agent`

### Surviving reactive modules
- `seon.web.reactive.transform` - pure hiccup transformation, used by ctx.clj and routes.clj
- `seon.web.reactive.actions` - action resolution and signal extraction, used by routes.clj
- `seon.primer.render` - has active callers in primer/html.clj
