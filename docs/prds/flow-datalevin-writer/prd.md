# PRD: Flow-Based Datalevin Writer

## Current State

### What Exists

#### Flow Infrastructure (src/seon/flow/)

| File | Purpose | Tested? | Status |
|------|---------|---------|--------|
| `msg.clj` | Message envelope schemas (request/reply/event) | Yes (msg_test.clj) - schema gen, EDN round-trip, validation | **Working** |
| `harness.clj` | Orchestrator-side namespace process step-fn | Yes (harness_test.clj) - describe, init, transform, overload, events | **Working** (unit-level) |
| `harness/channel.clj` | TCP <-> core.async channel adapter | Yes (channel_test.clj) | **Working** |
| `harness/bridge.clj` | Agent JVM bridge step-fn + remote-call! | Yes (bridge_test.clj) | **Working** (unit-level) |
| `harness/proxy.clj` | Transparent cross-namespace proxy generation | Not tested standalone | **Untested** |
| `topology.clj` | Reply router, request!, build-topology!, cycle detection | Yes (topology_test.clj) - happy path, timeout, error, overload, cycles | **Working** |
| `status.clj` | Runtime status collection, throughput, error drain | Yes (status_test.clj) - shape, all-flows, throughput | **Working** |
| `trace.clj` | Flow event persistence to Datalevin | Yes (trace_test.clj) - persist, query, round-trip | **Working** (requires running system) |
| `pool.clj` | Pre-warmed JVM pool with health checks | Yes (pool_test.clj, pool_integration_test.clj) | **Working** |
| `agent_runner.clj` | Agent JVM entry point (-main) | Not directly tested | **Working** (used in production) |

#### Writer Code (src/seon/db/datalevin/writer.clj)

- `db-writer-step`: 4-arity flow step-fn that calls `d/transact!`
- `create-writer-flow`: Creates single-process flow with the writer step
- `inject-tx!`: Fire-and-forget inject into writer flow

**Critical Bug Found (REPL evidence):**

```
;; inject-tx! triggers "can't resolve channel with coord" error
;; because db-writer-step declares :out/result and :out/error outputs,
;; but create-writer-flow uses {:conns []} so no channels exist for those outputs.
;; The transaction IS written (d/transact! succeeds), but the flow process
;; throws when trying to send the result, losing the state update.
;;
;; Error from flow's error-chan:
;; :cause "can't resolve channel with coord"
;; :data {:coord :out/result}
;;
;; Evidence: ping shows count: 0, total-writes: 0 even after inject.
;; But max-eid incremented (data was written before the output error).
```

When outputs are removed (nil return from transform), the writer works correctly:
```
;; With patched step returning nil outputs:
;; {:count 1, :writes 1, :errors 0}
```

#### Transaction Wrapper (src/seon/db.clj)

- `transact!`: Wraps `d/transact!` in a `future` with 10s timeout. On timeout, calls `future-cancel` and throws.
- `ensure-writer!`: Lazily creates a writer flow per connection for backup coordination only.
- `pause-writes!`/`resume-writes!`: Coordinate with writer flow for backups.
- `shutdown-writers!`: Uses pause -> ping -> stop pattern.
- **Current writes bypass the flow entirely** -- `transact!` calls `d/transact!` directly.
- Only 2 production callers: `seon.ctx` and `seon.flow.trace`.

#### Connection Manager (src/seon/db/datalevin/conn.clj)

- `get-master-conn!`, `get-namespace-conn!`, `get-runtime-conn!`: Lazy get-or-create with caching.
- `reconnect!`: Close old + create fresh connection.
- `close-datalevin-conn`: May block if connection is deadlocked (uses `d/close` which calls `send-only`).
- TTL-based cleanup scheduler.
- Integrant component with suspend/resume.

### What Works (REPL Evidence)

1. **core.async.flow basics**: Creating flows, injecting messages, pinging state -- all work correctly.
   ```
   ;; Simple counter step: inject 3 messages -> count: 3, state: {:count 3}
   ```

2. **topology/request! pattern**: Promise-based request/response over flow works end-to-end with echo topology (tested in topology_test.clj and confirmed in REPL).

3. **Flow lifecycle**: start/pause/resume/stop all work. `ping` returns accurate state snapshots.

4. **Writer step-fn logic**: The transform logic in `db-writer-step` is correct -- it calls `d/transact!` and tracks stats. The bug is in how the flow is wired, not in the step function itself.

### What's Broken/Stale

1. **`create-writer-flow` has an output wiring bug**: Declares `:out/result` and `:out/error` in `describe` but creates no connections for them. Every successful write triggers "can't resolve channel with coord" from the flow runtime. The data IS written but state is lost.

2. **`seon.db/transact!` bypasses the flow**: The writer flow exists only for backup coordination (pause/resume). Actual writes go through `future` + `d/transact!` directly. This means the flow-based writer is architecturally present but not actually used for writes.

3. **`inject-tx!` is fire-and-forget**: No way for the caller to know if the write succeeded or failed. The existing design has no promise/request-id pattern for synchronous writes through the flow.

## Problem Statement

### The Deadlock

Full analysis in `docs/prds/graph-cleanup/datalevin-deadlock-research.md`.

Summary: `d/transact!` acquires 3 nested Java monitors (conn atom, write-txn, ByteBuffer) and blocks on socket I/O. If the Datalevin server is slow/hung, the thread holds all monitors indefinitely. `Thread.interrupt()` does NOT release Java monitors. All subsequent `d/transact!` on that connection deadlock permanently. Only recovery: kill the JVM.

### Current Mitigation

`seon.db/transact!` wraps `d/transact!` in a `future` with 10s timeout. On timeout:
- Calls `future-cancel` (which only sets interrupt flag -- monitors stay held)
- Throws to the caller
- The future's thread remains blocked, holding all monitors
- All subsequent writes to that connection deadlock

### Why Flow Solves This

With a single-writer-thread-per-connection flow:
1. Calling threads never touch `d/transact!` or hold monitors
2. If the writer thread hangs, callers timeout cleanly (promise not delivered)
3. Recovery: abandon old writer+connection, create fresh ones
4. Blast radius: one leaked thread instead of system-wide deadlock

## Proposed Architecture

```
Caller thread                     Writer flow (single :io thread per conn)
     |                                      |
     |-- register promise ---------.        |
     |-- flow/inject tx-msg -------|------> |
     |                             |        |-- d/transact!(conn, tx-data)
     |                             |        |   [holds monitors on this thread only]
     |                             |        |-- deliver(promise, result)
     |<-- deref(promise, timeout) -'        |
     |                                      |
     v                                      |
  (result or timeout exception)             |
```

The writer step-fn delivers results directly to promises (side-effect in transform), similar to `topology/reply-router-step`. No flow outputs needed for the result path.

### Key Design Decision: Promise Delivery in Transform

The existing `topology.clj` proves this pattern works:
- `pending-promises` atom: `{request-id -> promise}`
- `reply-router-step` delivers to promises via side-effect in transform
- `request!` registers promise before inject, derefs with timeout

We reuse this exact pattern for the writer.

### Deadlock Recovery

When `d/transact!` hangs inside the writer flow:
1. **Detection**: Caller's `deref` times out. Promise never delivered. Writer flow thread stuck.
2. **Recovery**: Abandon old writer flow + connection. Create fresh connection via `conn/reconnect!` (close old in background future -- may block). Create new writer flow on new connection.
3. **What leaks**: One thread + one socket. Socket eventually times out or GC'd.

## Gap Analysis

### Can Reuse As-Is
- `seon.flow.msg` -- envelope schemas (if we adopt them for writer messages)
- `seon.flow.status` -- status collection, error drain
- `seon.runtime` -- flow registry (register-flow!, get-flow, etc.)
- `seon.db.datalevin.conn` -- connection manager, reconnect!
- Promise pattern from `topology.clj` (conceptual reuse, not code reuse)

### Needs Fixing
- **`db-writer-step`**: Remove `:out/result` and `:out/error` from describe. Add promise delivery in transform. Add `::request-id` to tx message format.
- **`create-writer-flow`**: Already correct (empty conns is fine once outputs are removed from describe).
- **`inject-tx!`**: Replace with `transact-via-flow!` that registers promise and returns result.

### Needs Building
1. **Per-writer `pending-promises` atom** -- analogous to `topology/pending-promises` but scoped per writer (not global)
2. **`transact-via-flow!`** -- blocking call: register promise, inject, deref with timeout
3. **Deadlock recovery logic** -- on timeout, abandon writer+conn, create fresh, retry
4. **Replace `seon.db/transact!` internals** -- route through flow instead of future+d/transact!
5. **Tests for the new writer** -- unit tests for step-fn, integration test with real Datalevin

### Not Needed
- The full topology/harness/bridge/proxy infrastructure is for cross-namespace agent isolation. The writer flow is much simpler -- single process, no routing, no TCP.

## Dependencies

- `clojure.core.async.flow` -- working, tested
- `datalevin.core` -- the underlying transact! we're wrapping
- `seon.db.datalevin.conn` -- connection manager for recovery
- `seon.runtime` -- flow registry for status/observability

No new dependencies needed.

## Risks

1. **flow/inject backpressure**: If the writer thread is slow and inject fills the channel buffer (default 10), inject will block the caller. This is actually desirable -- natural backpressure. But if the buffer fills because the writer is deadlocked, callers will block on inject before their promise timeout fires. **Mitigation**: Use `flow/inject` which returns a future -- deref that future with timeout too.

2. **Leaked threads accumulate**: Each deadlock recovery leaks one thread + socket. If Datalevin server is flaky, this could accumulate. **Mitigation**: Log leaked threads, add a counter, alert if > 3 leaked threads. In practice, server hangs are rare.

3. **Connection close blocks**: `conn/reconnect!` calls `close-datalevin-conn` which may block if the old connection is deadlocked. **Mitigation**: Close old connection in a background future with timeout. If it doesn't close, abandon it (leak).

4. **Performance overhead**: Adding a channel hop + promise allocation per write. **Expected**: Negligible. The channel put/take is ~100ns. Promise allocate+deliver is ~200ns. `d/transact!` to remote server is ~1-10ms. The overhead is <1%.

## Phases

### Phase 1: Fix the Writer Step (30 min)
- Remove `:out/result` and `:out/error` from `db-writer-step` describe
- Add `::request-id` to tx message format
- Add per-writer `pending-promises` atom
- Deliver results to promises in transform (side-effect)
- Unit test the fixed step-fn

### Phase 2: Synchronous Write API (1 hour)
- Implement `transact-via-flow!` -- register promise, inject, deref with timeout
- Handle inject failure (channel full/closed)
- Handle timeout (clean up promise, throw)
- Unit test with mock flow

### Phase 3: Replace seon.db/transact! (1 hour)
- Route `seon.db/transact!` through writer flow instead of future+d/transact!
- Keep `ensure-writer!` but have it create the new promise-aware writer
- Keep `pause-writes!`/`resume-writes!` (they already work)
- Update `shutdown-writers!` if needed
- Integration test with real Datalevin

### Phase 4: Deadlock Recovery (2 hours)
- Detect deadlock (promise timeout = writer stuck)
- Abandon old writer flow + connection
- Create fresh connection via conn/reconnect! (close old in background)
- Create new writer flow
- Retry the failed transaction on the new writer
- Integration test: simulate hung transact, verify recovery

### Phase 5: Observability (30 min)
- Register writer flows in runtime flow registry
- Status collection via `flow/ping` (write count, error count, last-write-at)
- Log leaked threads with counter
- Wire into health check endpoint

## Open Questions

1. **Should each Datalevin connection get its own writer flow, or share one?** Current design: one per connection (matches current `seon.db/writers` atom structure). This is correct because `d/transact!` locks per-connection, so serializing per-connection is the right granularity.

2. **Should `transact-via-flow!` retry on timeout?** Probably not in the core function. Let callers decide. But the deadlock recovery (Phase 4) should create a fresh writer and retry once.

3. **What timeout for writes?** Current `seon.db/transact!` uses 10s. Keep that. Normal writes are 1-10ms. 10s is generous enough for slow server, short enough to detect hangs.

4. **Should we keep the `future-cancel` approach as fallback?** No. The whole point is to stop calling `d/transact!` from caller threads. If we route through flow, there's no future to cancel.

5. **How to handle `pause-writes!` with the new architecture?** `flow/pause` already works -- it sends a `:pause` transition. The existing transition handler flushes via `d/transact! conn []`. Callers will block on their promise until pause completes and the writer resumes. This is correct behavior for backup coordination.
