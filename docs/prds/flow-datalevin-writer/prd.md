---
type: prd
status: completed
tags: [prd, database, flow]
---
# PRD: Flow-Based Datalevin Writer

## Vision

Route all Datalevin writes through a single-writer flow running in a **separate JVM**. Callers use topology (correlation IDs, timeouts, error routing) to communicate with the writer. If the writer deadlocks, kill the process and start a new one. No leaked threads, no leaked monitors, no system-wide deadlock.

**Secondary goal:** Add `seon.db/query` and `seon.db/pull` read wrappers that hide connection details, handle staleness, and enable logging. These are not flow-based — just a clean API boundary so agents never deal with Datalevin connection internals.

## Problem

`d/transact!` acquires 3 nested Java monitors and blocks on socket I/O. If the Datalevin server hangs, the calling thread holds all monitors forever. `Thread.interrupt()` does NOT release Java monitors. All subsequent writes to that connection deadlock permanently. The current `future` + `future-cancel` mitigation is ineffective (see Notes Q5).

## Architecture

```
Caller thread                Orchestrator (topology)           Writer JVM (pool)
     |                            |                                 |
     |-- topology/request! -----> |                                 |
     |   (correlation-id, promise)|                                 |
     |                            |-- TCP send {cid, tx-data} ---> |
     |                            |                                 |-- d/transact!(conn, tx-data)
     |                            |                                 |   [holds monitors HERE only]
     |                            |<-- TCP reply {cid, result} --- |
     |                            |-- deliver promise               |
     |<-- deref (timeout) --------|                                 |
     |                                                              |
  (result or timeout)                                               |

```

### Design Decisions

1. **Topology is the caller-facing API.** Topology already handles correlation IDs, timeouts, error routing. Promises are an implementation detail inside topology. Callers use `topology/request!` or equivalent — not raw promises.

2. **Read wrappers hide connection details.** `seon.db/query` and `seon.db/pull` take a db-name keyword (`:seon`, `:seon.runtime`), resolve the connection internally, handle staleness. Agents shouldn't know or care about Datalevin connection objects. NOT flow-based — just a clean function boundary.

3. **Malformed transaction errors propagate fully.** When Datalevin rejects a transaction (validation error, schema violation), the full error with descriptive message flows back through topology to the caller. No swallowing.

4. **State separation is explicit.** Step-fn state has two categories:
   - **Serializable** (EDN-safe): counters, config, buffered tx-data. Survives restart.
   - **Runtime** (not serializable): conn, TCP channels, promises. Rebuilt on startup via `init`.
   The step-fn's `init` builds runtime state; `transition :pause` saves serializable state.

5. **Flow lifecycle handles reconnection.** On startup (fresh or restore): connect to Datalevin, create TCP channels, restore pending buffer from disk, resume. Both paths go through `init`.

6. **At-least-once is sufficient.** Datalevin upserts are idempotent. No need for exactly-once. Callers should use lookup refs for inserts.

7. **Separate JVM via pool.** `seon.flow.pool` already manages cross-JVM processes. The writer uses the same pattern as agent isolation. On deadlock: `Process.destroyForcibly()` cleans everything. On failure: request a new process from the pool and rebuild the writer — no in-process fallback.

8. **This is a proof of concept for the whole flow system.** The writer is the first real flow, but the architecture (process isolation, kill/recover, topology communication, state serialization) is being designed for ALL flows — including agent processes, rendering pipelines, and any future stateful processing. Do not cut corners or remove infrastructure "because the writer doesn't need it." Build the foundation right.

## Current State

### What Exists

| Component | Location | Status |
|-----------|----------|--------|
| Flow infrastructure | `src/seon/flow/` | Working (msg, topology, harness, pool, status, trace) |
| Writer step-fn | `src/seon/db/datalevin/writer.clj` | **Buggy** — output wiring error (see below) |
| Transaction wrapper | `src/seon/db.clj` | Working but bypasses flow (future + d/transact!) |
| Connection manager | `src/seon/db/datalevin/conn.clj` | Working (lazy get-or-create, reconnect, TTL cleanup) |

### Writer Bug

`db-writer-step` declares `:out/result` and `:out/error` in describe but `create-writer-flow` uses `{:conns []}`. Every write triggers "can't resolve channel with coord". The data IS written but state is lost. When outputs are removed (nil return), it works correctly. The transform logic is sound; the wiring is broken.

### What's Used in Production

- `seon.db/transact!` wraps `d/transact!` in a future with 10s timeout. Bypasses the flow entirely.
- Only 2 production callers: `seon.ctx` and `seon.flow.trace`.
- `ensure-writer!` exists for backup coordination (pause/resume) but doesn't handle actual writes.

## Dependencies

- `clojure.core.async.flow` — working, tested
- `datalevin.core` — the underlying transact! we're wrapping
- `seon.db.datalevin.conn` — connection manager for recovery
- `seon.flow.topology` — correlation ID / request-reply pattern
- `seon.flow.pool` — separate JVM management
- `seon.flow.harness.channel` — TCP channel adapter (length-prefixed EDN)
- `seon.runtime` — flow registry for status/observability

No new external dependencies needed.

---

## Phases

### Phase 0: Verify Assumptions in REPL (30 min)

**Goal:** Confirm that the critical building blocks work as expected before writing production code.

**Input:** Running Seon system, REPL connected.

**Steps:**

1. Create a minimal flow with a step-fn that does `d/transact!` and returns nil (no outputs). Verify: inject works, state updates, no "can't resolve channel" error.
2. Test promise delivery as side-effect in transform: create an atom of promises, deliver from transform, verify caller can deref.
3. Test `seon.flow.harness.channel`: send an EDN map over TCP, receive it on the other end. Verify round-trip for tx-data shapes (maps with `:db/id`, keywords, vectors).
4. Test pool JVM acquisition: `pool/acquire!`, verify nREPL connection, release.
5. Verify topology `request!` pattern: send request with correlation ID, get reply, promise delivered.

**Output:** REPL session log confirming all 5 checks pass. No files created.

**Verification:** Each check either returns expected data or throws with a clear error explaining what's different from expected.

**Status: COMPLETE (2026-02-28) -- independently verified**

All 5 assumptions verified in REPL (original agent + verification agent):

1. **Nil outputs flow** - CONFIRMED. Step-fn with `{:outs {}}` and `nil` output from transform works. No "can't resolve channel" error. State updates correctly (`write-count` incremented to 1 after one inject), data written to DB (verified with `d/q` returning `#{["verify-1"]}`), no errors on error-chan.

2. **Promise delivery as side-effect** - CONFIRMED. Atom of promises + deliver from transform + caller deref works under flow's threading model (not just a regular function call). Promise delivered with `{:status :ok, :value 42}` via `flow/inject`, no timeout.

3. **TCP channel round-trip** - CONFIRMED. `seon.flow.harness.channel` compiles and works. Maps with `:db/id`, keywords, vectors all round-trip. Bidirectional (client->server and server->client) both work.

4. **Pool JVM acquisition** - CONFIRMED. `pool/acquire!` with 30s timeout returns agent handle in ~6ms. nREPL eval of `(+ 1 2)` returned `"3"`. `pool/release!` returns JVM to pool cleanly.

5. **Topology request! pattern** - CONFIRMED (end-to-end). The previous agent only tested reply-router in isolation and reported `build-topology!` was blocked by a harness compile error. **Verification found this is no longer true.** `seon.flow.harness` compiles cleanly, and the full path works: `build-topology!` creates flow with namespace-step + reply-router, `request!` injects a request, namespace-step forwards it to the external out-port channel, a simulated agent echoes back, reply-router delivers the promise. Result: `{:echo [1 2 3]}`. No blockers for Phase 3.

**Surprises/Notes:**

- The harness compile error (`runtime/get-flow` not found) reported by the original agent appears to have been fixed. `(require 'seon.flow.harness :reload)` succeeds.
- Agent JVMs (from `create_session`) don't have native Datalevin on their classpath. All Datalevin testing must happen in the orchestrator REPL.
- Existing writer tests all pass: 7 tests, 31 assertions, 0 failures.

---

### Phase 1: Fix Writer Step-fn (30 min)

**Goal:** Make `db-writer-step` work correctly: no output wiring errors, promise delivery for synchronous callers, clean state separation.

**Input:**

- `src/seon/db/datalevin/writer.clj`
- `test/seon/db/datalevin/writer_test.clj`

**Steps:**

1. Remove `:out/result` and `:out/error` from `db-writer-step`'s `describe` return. Transform returns `nil` (no flow outputs).
2. Add `pending-promises` atom as init arg (not in step-fn state — it's runtime, not serializable).
3. Add `::correlation-id` to the tx message format. Transform looks up promise by correlation-id, delivers result or error.
4. Separate state into serializable (`:total-writes`, `:total-errors`, `:last-write-at`) and runtime (`:conn`, passed via init, not in state map).
5. Update tests: verify transform delivers to promise, verify state updates, verify error delivery on bad tx-data.

**Output:** Modified `writer.clj` and `writer_test.clj`.

**Verification:**

- `(user/run-tests 'seon.db.datalevin.writer-test)` — all pass
- No "can't resolve channel" errors in REPL when injecting messages

**Dependencies:** Phase 0 (assumptions verified)

---

### Phase 2: Read Wrappers (30 min) ✅ COMPLETE

**Goal:** Add `seon.db/query` and `seon.db/pull` that hide connection details from callers.

**Input:**

- `src/seon/db.clj`
- `src/seon/db/datalevin/conn.clj`

**Steps:**

1. Add `seon.db/query` — takes db-name keyword (`:seon`, `:seon.runtime`, or a namespace string) + Datalog query + inputs. Resolves connection via conn manager, calls `d/q` on `@conn`.
2. Add `seon.db/pull` — same pattern, calls `d/pull`.
3. Add `seon.db/pull-many` — same pattern.
4. Handle staleness: if query throws connection error, call `conn/reconnect!` and retry once.
5. Add Malli schemas for all three functions.
6. Write tests with a test database.

**Output:** Modified `src/seon/db.clj`, new or modified test file.

**Verification:**

- Tests pass for happy path and connection-error-retry path
- `(user/run-tests 'seon.db-test)` — all pass

**Dependencies:** None (independent of writer work)

---

### Phase 3: Synchronous Write API via Topology (1 hour)

**Goal:** Implement `transact-via-flow!` using topology's correlation-ID pattern. This is the in-process version — callers get a blocking write with promise-based timeout.

**Input:**

- `src/seon/db/datalevin/writer.clj` (from Phase 1)
- `src/seon/flow/topology.clj` (reference for request! pattern)

**Steps:**

1. Implement `transact-via-flow!` — register promise in `pending-promises`, inject tx message with correlation-id into writer flow, deref promise with timeout.
2. Handle inject failure (channel full or closed) — throw immediately, don't wait for timeout.
3. Handle timeout — clean up promise from `pending-promises`, throw with descriptive error including tx-data count and timeout duration.
4. Handle Datalevin errors — when `d/transact!` throws in the step-fn, the error is delivered to the promise. Caller gets the original exception re-thrown with full error info.
5. Write unit tests with a mock flow (no real Datalevin needed).

**Output:** Updated `writer.clj` with `transact-via-flow!`, updated tests.

**Verification:**

- Unit tests cover: successful write, timeout, inject failure, Datalevin error propagation
- `(user/run-tests 'seon.db.datalevin.writer-test)` — all pass

**Dependencies:** Phase 1

---

### Phase 4: Wire seon.db/transact! to Writer Flow (1 hour)

**Goal:** Replace the `future` + `d/transact!` internals of `seon.db/transact!` with the flow-based writer from Phase 3.

**Input:**

- `src/seon/db.clj`
- `src/seon/db/datalevin/writer.clj` (from Phase 3)

**Steps:**

1. Modify `seon.db/transact!` to route through `transact-via-flow!` instead of `future` + `d/transact!`.
2. Keep `ensure-writer!` but have it create the new promise-aware writer flow.
3. Keep `pause-writes!` / `resume-writes!` unchanged (they use `flow/pause` / `flow/resume` which still work).
4. Update `shutdown-writers!` if needed.
5. Integration test with real Datalevin: write data through `seon.db/transact!`, verify it's in the database.
6. Test both callers (`seon.ctx` and `seon.flow.trace`) still work.

**Output:** Modified `src/seon/db.clj`, integration test.

**Verification:**

- `(user/run-tests 'seon.db-test)` — all pass
- REPL: `(seon.db/transact! conn [{:db/id -1 :test/x 1}])` returns tx-report
- Existing callers (`seon.ctx`, `seon.flow.trace`) still work end-to-end

**Dependencies:** Phase 3

---

### Phase 5: Separate-Process Writer (2 hours)

**Goal:** Move the writer flow into a pool JVM. Communication over TCP via `seon.flow.harness.channel`. This is the key isolation improvement — deadlocked writes can be killed.

**Input:**

- `src/seon/db/datalevin/writer.clj` (from Phase 4)
- `src/seon/flow/pool.clj`
- `src/seon/flow/harness/channel.clj`

**Steps:**

1. Create `src/seon/db/datalevin/writer_remote.clj` — orchestrator-side client that sends tx-data over TCP and reads ACKs.
2. Writer JVM side: receives tx-data from TCP, calls `d/transact!`, sends result/error back with correlation-id.
3. Pending write buffer on orchestrator: `{correlation-id -> {:tx-data [...] :promise p :sent-at Instant}}`.
4. ACK reader loop: reads from `in-ch`, matches correlation-id, delivers promise, removes from buffer.
5. Modify `seon.db/transact!` to route through remote writer. If the pool JVM is unavailable, request a new one from the pool — do NOT fall back to in-process writes.
6. Integration test: write through remote writer, verify data in Datalevin.

**Output:** New `writer_remote.clj`, modified `db.clj`, tests.

**Verification:**

- Integration test: 10 writes through remote writer, all data present
- `(user/run-tests 'seon.db.datalevin.writer-remote-test)` — all pass

**Dependencies:** Phase 4

---

### Phase 6: Kill and Recover (2 hours)

**Goal:** When the writer JVM deadlocks, detect it, kill the process, start a new one, replay unacknowledged writes.

**Input:**

- `src/seon/db/datalevin/writer_remote.clj` (from Phase 5)
- `src/seon/flow/pool.clj`

**Steps:**

1. Detect deadlock: promise timeout means writer is stuck. Also detect TCP `in-ch` close (writer process died).
2. Kill: `Process.destroyForcibly()` on the pool JVM process.
3. Acquire new pool JVM, establish fresh TCP connection, start new ACK reader.
4. Replay: iterate pending write buffer (sorted by `sent-at`), re-send each to new writer. Promises from original callers get delivered when replayed writes complete.
5. If replay also times out, throw to original callers.
6. Integration test: simulate hung `d/transact!` (Thread/sleep in step-fn), verify recovery and replay.
7. Integration test: verify no data duplication with lookup-ref-based tx-data.

**Output:** Updated `writer_remote.clj`, integration tests.

**Verification:**

- Test: writer hangs -> detected in <12s -> new writer -> replayed write succeeds -> original caller's promise delivered
- Test: kill recovery with 5 pending writes, all 5 eventually succeed
- `(user/run-tests 'seon.db.datalevin.writer-remote-test)` — all pass

**Dependencies:** Phase 5

---

### Phase 7: Observability (30 min)

**Goal:** Writer flows are visible in health checks, status endpoints, and the observatory.

**Input:**

- `src/seon/db/datalevin/writer_remote.clj` (from Phase 6)
- `src/seon/flow/status.clj`
- `src/seon/runtime.clj`

**Steps:**

1. Register writer flows in runtime flow registry (`runtime/register-flow!`).
2. Status collection: TCP ping to writer JVM returns write count, error count, last-write-at.
3. Log recovery events: "Writer JVM killed, acquiring new one", "Replaying N pending writes".
4. Wire into health check endpoint: writer status appears in `/api/health`.
5. Add leaked-thread counter (for in-process fallback path).

**Output:** Updated files, health check shows writer status.

**Verification:**

- `curl <http://localhost:8080/api/health`> includes writer status
- Writer metrics visible after a few test writes

**Dependencies:** Phase 6

---

### Phase 8: Load Testing (1 hour)

**Goal:** Verify the writer handles concurrent writes, backpressure, and recovery under load.

**Input:** Working writer from Phase 7, load test plan from Notes Q9.

**Steps:**

1. Implement load test harness (see Notes Q9 for code sketches).
2. Run: 10, 100, 1000 concurrent writes. Measure throughput, latency p50/p95/p99, error rate.
3. Run: stuck writer simulation. Verify timeout detection, recovery, replay.
4. Run: data integrity check after all tests. No duplicates, no partial writes.
5. Document results in this PRD under "Load Test Results".

**Output:** Load test code (in `test/`), results documented.

**Verification:** Throughput >100 writes/s for small txns, recovery <12s, zero data corruption.

**Dependencies:** Phase 7

---

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Verify Assumptions in REPL | Complete (verified) |
| 1 | Fix Writer Step-fn | Pending |
| 2 | Read Wrappers | Pending |
| 3 | Synchronous Write API via Topology | Pending |
| 4 | Wire seon.db/transact! to Writer Flow | Pending |
| 5 | Separate-Process Writer | Pending |
| 6 | Kill and Recover | Pending |
| 7 | Observability | Pending |
| 8 | Load Testing | Pending |

## Mandatory: Read Source Code When Stuck

**This is a hard rule for all agents on this PRD.**

When you encounter unexpected behavior, errors, or confusion about how a library works:

1. **Read the source code** in `reference-code/`. We have: `datalevin/`, `core.async/`, `integrant/`, `malli/`, `clj-kondo/`, and others.
2. **Test in the REPL first.** Don't assume — verify. Create a minimal reproduction.
3. **Search with context.** Use `(user/search "query" :files ["relevant/file.clj"])` — include the actual code that's confusing you.
4. **Never guess at library behavior.** If you're not sure how `flow/inject` handles a closed channel, read the source. If you're not sure how Datalevin handles a killed client, read the server source. Hallucinated assumptions have caused every major bug this session.

The reference code directories exist specifically so agents can look up exactly how things work. Use them.

## Reference

All research findings, REPL evidence, failure scenarios, and implementation sketches are in `notes.md` in this directory.
