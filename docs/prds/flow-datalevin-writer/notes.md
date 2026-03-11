---
type: reference
status: draft
tags: [prd, reference, database, flow]
---
# Flow Datalevin Writer — Research Notes

This file contains all REPL-verified research findings from the design phase.
The main PRD references these by section number (e.g., "see Notes Q1").

---

## Q1: Stuck Flow Recovery — Can We Kill It?

**Answer (REVISED): The writer MUST run in a separate JVM.** Reasons:

1. **Complete isolation:** If the writer deadlocks, kill the process. No leaked threads, no leaked monitors, no accumulated resource waste.
2. **Same pattern as agent isolation:** `seon.flow.pool` already solves cross-JVM process management. Using it for the writer means one pattern for all isolated work.
3. **Overhead is negligible:** We're already doing TCP to the Datalevin server. Adding one more TCP hop (orchestrator -> writer JVM -> Datalevin server) adds ~0.1ms per write.
4. **Enables pause -> serialize -> kill -> restore -> resume:** With in-process flows, a blocked thread can never be cleaned up without killing the whole JVM. With a separate process, we can kill just the writer.
5. **No leaked threads:** The in-process approach leaks one thread + socket per deadlock. Over time this accumulates. With process isolation, `Process.destroyForcibly()` cleans up everything.

The pool infrastructure (`seon.flow.pool`) spawns separate JVMs with nREPL and communicates via TCP. The bridge (`seon.flow.harness.bridge`) runs step-fns inside agent JVMs and relays messages over TCP channels. This same machinery works for the writer.

**Original in-process approach (kept as fallback):** When the writer flow thread hangs, abandon old flow + conn, create fresh ones. The leaked thread holds monitors on the *old* conn atom. New writer is unaffected. This can serve as a fallback when the pool is unavailable.

**Enhancement for fallback path:** Close the old connection's socket to force `AsynchronousCloseException` on the blocked thread, releasing monitors faster.

---

## Q2: Single Writer Guarantee

**Answer: Datalevin enforces single-writer at the server level per database using a `Semaphore(1)`.**

Evidence from `reference-code/datalevin/src/datalevin/server.clj`:

```clojure
;; Line 1947-1954: Per-database lock is a Semaphore with 1 permit
(defn- get-lock [^Server server db-name]
  (let [dbs (.-dbs server)]
    (locking dbs
      (or (get-in dbs [db-name :lock])
          (let [lock (Semaphore. 1)]
            (update-db server db-name #(assoc % :lock lock))
            lock)))))

```

The `open-transact` and `open-transact-kv` server handlers both acquire this semaphore before allowing writes (lines 1971, 2014). The semaphore is released on `close-transact`/`close-transact-kv` (lines 1994, 2048).

**Additionally**, on the client side, `datalevin.conn/with-transaction` uses `(locking orig-conn ...)` — a Java `synchronized` monitor on the conn atom. This serializes writes per-connection on the client.

**Three levels of write serialization exist:**

1. **Client-side:** `(locking conn-atom ...)` in `with-transaction` — prevents two threads using the same connection from writing concurrently
2. **Client-side:** `(locking write-txn ...)` nested inside — prevents concurrent write transactions on the same store
3. **Server-side:** `Semaphore(1)` per database — prevents two clients writing to the same database simultaneously

For simple `d/transact!` calls (not `with-transaction` blocks), the call goes through `conn/-transact!` which invokes `with-transaction` internally, so levels 1 and 2 always apply. But the server-side semaphore is only acquired for `open-transact`/`open-transact-kv` operations (explicit write transactions). Regular `tx-data` calls on the server (line 1695) do NOT acquire the semaphore — they rely on LMDB's own write serialization.

**LMDB itself is strictly single-writer.** From LMDB documentation: "Only a single write transaction may be live at a time." LMDB enforces this at the C level — `mdb_txn_begin` with `MDB_RDONLY` omitted will block until the current write transaction completes.

**Implication for our design:** One writer flow per connection is correct. The flow serializes writes on our side, matching the per-connection `locking` in Datalevin. We don't need additional coordination beyond what Datalevin already provides.

---

## Q3: Cross-Database Write Serialization

**Answer: No cross-database serialization needed. Each database has its own memory-mapped files, its own semaphore, and its own LMDB environment.**

Evidence:

- Server stores databases in a `dbs` map keyed by `db-name` (line 598-604)
- Each database entry has its own `:lock` (Semaphore), `:store`, `:kv-store`, `:dt-db`
- The `get-lock` function creates a separate `Semaphore(1)` per database name
- LMDB environments are per-directory — each database maps to a separate directory with its own memory-mapped file

**This means:**

- Writes to `seon` database and writes to `seon.runtime` database can happen concurrently with no interference
- Each database's writer flow is fully independent
- No global write lock needed

---

## Q4: Read Consistency During Writes

**Answer: LMDB provides full MVCC. Readers never block writers and writers never block readers. `d/db` (i.e., `@conn`) returns an immutable snapshot.**

Evidence from Datalevin source:

1. `datalevin.conn/db` (line 176-179) simply does `@conn` — deref of the conn atom returns the current `DB` value
2. `datalevin.conn/-transact!` does `(assoc report :db-after @conn)` — after the transaction, it reads the updated atom
3. The conn is a Clojure atom wrapping a `DB` record. `with-transaction` does `(reset! orig-conn new-db)` after the write completes

LMDB's MVCC model:

- Read transactions see a consistent snapshot from the moment they start
- The `MDB_RDONLY` flag creates a read-only transaction that does not conflict with writes
- Datalevin's `binding/cpp.clj` line 1004 shows explicit read/write transaction handling via `mdb_txn_begin`
- Read transactions can proceed concurrently with a write transaction

**For remote (client/server) Datalevin:**

- `@conn` returns the locally cached `DB` value (updated after each successful transact)
- Queries go through the server which uses its own read transactions
- Reads are never blocked by writes at the LMDB level

**Implication:** Our flow-based writer does not affect read performance. Callers can freely do `d/q`, `d/pull`, `d/entity` on `(d/db conn)` while writes are flowing through the writer. The only contention is between writers, which is serialized by our single-writer flow.

---

## Q5: The 10-Second Timeout — Is It Right?

**Answer: The timeout is appropriate but `future-cancel` is ineffective. The flow replaces this entirely.**

Current code in `seon.db/transact!` (lines 88-114):

```clojure
(let [fut (future (d/transact! conn tx-data))
      result (deref fut default-timeout-ms ::timeout)]
  (when (= result ::timeout)
    (future-cancel fut)  ;; Sets interrupt flag only — monitors stay held
    ...))

```

**What happens on timeout:**

1. `deref` returns `::timeout` after 10s
2. `future-cancel` calls `Thread.interrupt()` on the future's thread
3. The thread is inside `(locking conn ...)` doing a blocking socket read
4. `Thread.interrupt()` does NOT release Java monitors — it only sets the interrupt flag
5. If the thread is in NIO `SocketChannel.read()`, the interrupt MAY cause `ClosedByInterruptException` (NIO channels respond to interrupts by closing the channel). This would unwind the `locking` blocks and release monitors.
6. But this is unreliable — the thread might be in a non-interruptible section when the interrupt arrives

**The flow replaces this mechanism entirely:**

- Caller threads never touch `d/transact!` or hold monitors
- The writer flow thread does the blocking I/O
- If it hangs, the caller's promise times out cleanly
- Recovery creates a new connection + flow
- No need for `future-cancel` at all

**Should the 10s timeout change?** No. Normal remote writes take 1-10ms. A 10s timeout is generous for slow server conditions but short enough to detect actual deadlocks. The flow should use the same 10s timeout for promise deref.

---

## Q6: Datalevin's Three Locks — Why?

**Lock 1: `(locking orig-conn ...)` — Connection-level mutex**

- Protects the conn atom from concurrent mutation
- Ensures only one thread can read-modify-write the DB value in the atom
- The atom's `reset!` must happen inside this lock to prevent lost updates

**Lock 2: `(locking (l/write-txn s#) ...)` — Store/LMDB write transaction mutex**

- Protects the write transaction state on the store
- For local LMDB: ensures only one write transaction is open at a time (LMDB requirement)
- For remote DatalogStore: the `write-txn` is a volatile holding `:remote-dl-mutex` — used as a coordination point for the `open-transact`/`close-transact` lifecycle
- Nested inside lock 1 because you need the conn locked before you can safely access its store's write-txn

**Lock 3: `(locking bf ...)` in `client/send-n-receive` — ByteBuffer mutex**

- Protects the shared ByteBuffer used for socket I/O
- Each `Connection` has one buffer — concurrent sends would corrupt it
- This is per-connection, not per-database

**Why they can't be consolidated:**

- Locks 1 and 2 protect different things (conn atom vs. LMDB write state). You could theoretically use one lock, but the `with-transaction` macro needs to support both local and remote stores — the remote case does network I/O under lock 2 that shouldn't hold lock 1 any longer than necessary.
- Lock 3 is in a different module (`client.clj`) protecting a different resource (the wire buffer). Consolidating it with locks 1-2 would couple the connection layer to the transaction layer.

**For our flow-based writer, this doesn't matter:** We never hold any of these locks from caller threads. The single writer thread acquires all three sequentially, and if it gets stuck, we abandon it entirely.

---

## Q7: Database State After Forced Disconnection

**Answer: The database is safe. LMDB transactions are atomic at the storage level, and the server handles client disconnection gracefully.**

**Server-side disconnect handling:**

From `server.clj` line 646-655:

```clojure
(defn- disconnect-client* [^Server server client-id]
  (remove-client server client-id)
  (let [^Selector selector (.-selector server)]
    (when (.isOpen selector)
      (doseq [^SelectionKey k (.keys selector)
              :when state]
        (when (= client-id (@state :client-id))
          (close-conn k))))))

```

The server removes the client from its tracking and closes the selection key. Additionally:

- `remove-idle-sessions` (line 2574) periodically removes clients that haven't been active within the idle timeout
- When a client socket closes unexpectedly (network error), the NIO selector detects it on the next select cycle and the server cleans up

**Transaction atomicity:**

LMDB guarantees that write transactions are atomic:

- If a write transaction is committed (`mdb_txn_commit`), all changes are durable
- If not committed (process dies, socket closes), the transaction is automatically rolled back
- LMDB uses copy-on-write B+ trees — the old data pages remain valid until a new root is committed

For Datalevin's `tx-data` handler (the normal transact path, line 1695-1722):

- The server receives the transaction data, processes it in `transact*`, and writes the result back
- If the client socket closes mid-transaction, the server-side write either completes (data committed) or the exception propagates and the server-side state is unchanged
- The server does NOT hold a long-lived write transaction for regular `tx-data` — it opens and commits within a single handler call

For `open-transact`/`close-transact` (explicit write transactions):

- The server acquires a `Semaphore(1)` on `open-transact`
- If the client dies without calling `close-transact`, the semaphore is NOT released
- The `remove-idle-sessions` periodic cleanup eventually removes the dead client and the semaphore can be cleaned up
- **Risk:** If the semaphore isn't released, no other client can open a write transaction on that database until the server detects the dead session via idle timeout

**Implication for kill-recovery:**

- Killing our writer process (closing the socket) will NOT corrupt the database
- If we were mid-`d/transact!`, the transaction either committed fully or didn't happen
- The Datalevin server may hold the semaphore briefly until it detects the dead connection, but regular `tx-data` calls don't use the semaphore — only explicit `open-transact` does
- Since our code uses `d/transact!` (which goes through `tx-data` on the server, NOT `open-transact`), there's no semaphore concern

---

## Q8: Topology + Promise Dual Approach

**Answer: Yes, flow outputs and promises can coexist. They serve different consumers.**

Looking at `seon.flow.topology/reply-router-step` (line 121-164): it delivers to promises via side-effect in the transform function, returning `nil` for outputs. This is exactly the pattern the writer should use.

The writer step-fn can:

1. Call `d/transact!` in the transform
2. Deliver the result to a promise (for imperative callers using `transact-via-flow!`)
3. Return `nil` for outputs (no flow output channels needed)

If we later want to add flow outputs (e.g., for downstream processing of write results), we can add `:out/result` to describe AND wire connections in the flow config. The promise delivery and the flow output would both happen in the same transform call — no conflict.

**Migration path:**

- Phase 1-3: Promise-only (no flow outputs, `nil` return from transform)
- Future: Add flow outputs if we want event-driven downstream processing (e.g., write event -> SSE refresh)

---

## Q9: Load Testing Plan

### Test Setup

Use a dedicated test database (`test-load`) to avoid interfering with production data.

### 1. High Write Load Generation

```clojure
(defn generate-load!
  "Fire N concurrent write requests through the writer flow.
   Returns vector of promises."
  [writer-fn n]
  (let [promises (atom [])]
    (dotimes [i n]
      (let [p (promise)]
        (swap! promises conj p)
        (future
          (try
            (deliver p {:ok (writer-fn [{:db/id -1
                                         :test/counter i
                                         :test/payload (str "load-" i)}])})
            (catch Exception e
              (deliver p {:error (.getMessage e)}))))))
    @promises))

```

Ramp from 10 to 100 to 1000 concurrent writes. Measure:

- **Throughput:** writes/second at each concurrency level
- **Latency:** p50, p95, p99 per write
- **Error rate:** what percentage fail?
- **Queue depth:** how full does the flow channel get?

### 2. Stuck Writer Simulation

Inject an artificial delay into the writer step-fn to simulate a hung `d/transact!`:

```clojure
;; Test-only step-fn that hangs after N writes
(defn hanging-writer-step
  [state input-id tx-msg]
  (when (> (::total-writes state) 5)
    (Thread/sleep 30000))  ;; Simulate hung server
  ;; ... normal write logic
  )

```

Verify:

- Callers' promises time out after 10s
- The flow is detectable as stuck (via `flow/ping` timeout)
- Other callers are blocked at `flow/inject` (channel buffer full)

### 3. Kill + Recovery Test

```clojure
(defn test-kill-recovery! []
  ;; 1. Start writer flow
  ;; 2. Fire 5 successful writes (verify data)
  ;; 3. Inject hanging write (simulate deadlock)
  ;; 4. Wait for timeout detection
  ;; 5. Trigger recovery: abandon old flow+conn, create new
  ;; 6. Fire 5 more writes through new flow
  ;; 7. Verify all 10 writes are in the database
  ;; 8. Verify old flow thread count (should be 1 leaked)
  )

```

### 4. Data Integrity Verification

After each test:

```clojure
(defn verify-integrity! [conn expected-count]
  (let [actual (d/q '[:find (count ?e) .
                       :where [?e :test/counter _]]
                    (d/db conn))]
    (assert (= actual expected-count)
            (str "Expected " expected-count " got " actual))))

```

Also verify:

- No duplicate entities (each `:test/counter` value appears exactly once)
- No partial writes (each entity has all expected attributes)
- Read consistency during writes (query while writes are flowing)

### 5. Metrics to Collect

| Metric | How | Target |
|--------|-----|--------|
| Write throughput | count / elapsed | >100 writes/s for small txns |
| Write latency p50 | sorted promises | <5ms |
| Write latency p99 | sorted promises | <50ms |
| Recovery time | timeout detection to first successful write on new flow | <12s (10s timeout + 2s recovery) |
| Leaked threads | Thread.getAllStackTraces count before/after | +1 per deadlock event |
| Channel buffer depth | flow/ping state | <10 (buffer size) under normal load |

---

## Flow Error Behavior (REPL-Verified)

**When a step-fn throws an exception:**

```clojure
;; REPL evidence:
{:s1-count 1, :s1-status :running,     ;; before error
 :s2-count 1, :s2-status :running,     ;; after error (count didn't change — error msg not counted)
 :s3-count 2, :s3-status :running,     ;; after next msg: FLOW CONTINUES RUNNING
 :error? true,
 :error-ex-msg "test error",
 :error-state {:count 1}}              ;; state at time of error

```

**Key findings:**

1. The flow catches `Throwable` (not just `Exception`) in the process loop
2. The exception is put on `error-chan` with context: `{::flow/pid, ::flow/status, ::flow/state, ::flow/count, ::flow/cid, ::flow/msg, ::flow/op :step, ::flow/ex}`
3. The process **continues running** after the error — it does NOT die
4. The state is NOT rolled back — it remains at whatever it was before the failing transform
5. The error-chan has a sliding-buffer of 100, so errors won't block the process

**When a step-fn blocks indefinitely:**

```clojure
;; REPL evidence:
{:s1 {:count 1, :status :running},
 :s2-during-block nil,    ;; ping timed out (500ms)
 :s3-during-block nil}    ;; still blocked
;; inject returns a Future — non-blocking, buffers in channel (up to 10)

```

**Key findings:**

1. A blocked step-fn makes the entire process unresponsive
2. `flow/ping` times out (returns nil for that process)
3. `flow/inject` succeeds (returns Future) because it writes to the channel buffer
4. But messages accumulate in the buffer — once buffer is full (10), inject's underlying `>!!` will block
5. There is NO way to interrupt a blocked step-fn from outside — `flow/stop` sends a command on the control channel, but the process loop only reads control between transform calls
6. **This is exactly why we need process isolation** — the only way to unstick a deadlocked `d/transact!` is to kill the process

---

## Error Propagation Table

| Error | Source | Propagation |
|-------|--------|-------------|
| `d/transact!` throws | Bad tx-data, schema violation | Caught in transform, delivered to promise as exception |
| `d/transact!` blocks | Server deadlock, network hang | Process stuck, promise times out, kill process |
| TCP connection dies | Network error, process killed | `in-ch` closes, reader detects, promise times out |
| Serialization fails | Non-EDN data in tx-data | Caught before send, delivered to promise as exception |

---

## Read Path Analysis

Reads go directly through the Datalevin client connection — they do NOT go through any flow.

**`seon.graph.query`**: All query functions take a `::conn` parameter and call `d/q` or `d/pull` on `@conn` (dereferenced snapshot).

**Should reads go through a flow?** No. Direct client access is correct because:

1. **LMDB MVCC**: Readers never block writers and writers never block readers.
2. **No contention**: `deref` of the conn atom is lock-free.
3. **No serialization overhead**: Reads return Clojure data structures directly.

**Read consistency with separate-process writer:**

- After `transact-via-flow!` returns success, the orchestrator's `@conn` may still show the old snapshot until the next query triggers a server round-trip
- This is fine: most reads happen on subsequent requests where the snapshot is already fresh

---

## Serialization Details

### What State Would Need Serialization

| State | Type | EDN-Safe? | Notes |
|-------|------|-----------|-------|
| `total-writes` | long | Yes | Counter |
| `total-errors` | long | Yes | Counter |
| `last-write-at` | Instant | Yes (tagged literal) | `#time/instant "..."` already supported by `channel.clj` |
| Pending write buffer | map of uuid -> map | Mostly | Promise values are NOT serializable and must be excluded |

### Constraints

1. **All flow step-fn state must be EDN-serializable.** No opaque Java objects, no atoms, no channels in state. The writer step-fn (`db-writer-step`) currently stores `::conn` in state (line 78) which is NOT serializable. This must be passed via init args and kept separate from serializable state.

2. **The pending write buffer contains promises** which are not serializable. The buffer must be split: `{:serializable {:tx-data [...] :sent-at Instant :retries 0} :runtime {:promise p}}`. Only the serializable part goes to disk.

3. **Transaction data must be pure EDN.** No Java objects, no lazy sequences, no functions.

---

## Single Writer Across Process Boundary

### The Double-Writer Problem

When killing a hung writer JVM and starting a new one:

1. Old JVM may still be alive briefly (process kill is async)
2. Both JVMs could have connections to the same Datalevin database
3. Both could attempt `d/transact!` simultaneously

### Protection Layers

**Layer 1: Process kill is reliable.** `Process.destroyForcibly()` sends SIGKILL. The OS terminates the process and closes all sockets.

**Layer 2: LMDB single-writer guarantee.** Even if two connections attempt concurrent writes, LMDB's C-level write lock serializes them.

**Layer 3: Server-side write serialization.** For `tx-data` operations, the server processes writes sequentially per its event loop.

### Do We Need a Lock File?

**No.** The process kill + LMDB guarantees are sufficient.

---

## Promise Bridging Across JVM Boundaries

Clojure promises are JVM-local objects. The cross-JVM mapping uses correlation IDs over TCP, matching the pattern from `seon.flow.topology/request!`.

### Implementation Sketch

```clojure
;; New: seon.db.datalevin.writer-client (orchestrator side)
(defonce pending-writes (atom {}))

(defn transact-via-writer!
  "Send tx-data to remote writer, return result or throw."
  [{::keys [out-ch in-ch]} tx-data timeout-ms]
  (let [cid (random-uuid)
        p (promise)]
    (swap! pending-writes assoc cid {:promise p :tx-data tx-data :sent-at (Instant/now)})
    (a/>!! out-ch {:correlation-id cid :tx-data tx-data})
    (let [reply (deref p timeout-ms ::timed-out)]
      (if (= reply ::timed-out)
        (do (swap! pending-writes dissoc cid)
            (throw (ex-info "Writer timeout" {:cid cid :timeout-ms timeout-ms})))
        (if (= :ok (:status reply))
          (:result reply)
          (throw (ex-info (:error-message reply) reply)))))))

```

ACK reader loop:

```clojure
(defn start-ack-reader! [in-ch]
  (async/thread
    (loop []
      (when-let [msg (a/<!! in-ch)]
        (when-let [{:keys [promise]} (get @pending-writes (:correlation-id msg))]
          (swap! pending-writes dissoc (:correlation-id msg))
          (deliver promise msg))
        (recur)))))

```

---

## Failure Scenarios

| Scenario | What Happens | Data Safe? | Caller Experience |
|----------|-------------|------------|-------------------|
| **Writer JVM dies mid-transaction** | LMDB rolls back uncommitted write. Server detects closed socket. | Yes | Promise times out. With replay: orchestrator replays unacknowledged writes (~12s). Without: caller gets timeout exception. |
| **Writer dies after TCP read, before transact** | Message in process memory, never reached Datalevin. | Yes | Replay re-sends. Idempotent writes safe. Tempid inserts could duplicate. |
| **Writer dies after transact, before ACK** | Transaction committed. ACK never sent. Orchestrator doesn't know. | Yes | Replay re-sends. Upserts are no-ops. Tempids could duplicate. Key scenario for idempotency. |
| **TCP drops but writer alive** | Writer healthy, can't send ACK. | Yes | Orchestrator kills writer (simpler than reconnect), replays. |
| **Orchestrator dies** | All promises lost. Writer continues briefly. | Yes | No caller to experience anything. Fresh writer on restart. |
| **Datalevin server dies** | Writer's transact throws IOException. | Yes (WAL) | Caller gets error (not timeout). Can retry after server restart. |

### Key Insight: Post-Commit Pre-ACK Window

The most dangerous scenario is "writer dies after transact but before ACK". The **only** protection is idempotent transaction data. Recommendation: require idempotent writes (upserts with lookup refs). At-least-once is sufficient.

---

## Risks Summary

1. **flow/inject backpressure**: If writer hangs and buffer fills, callers block on inject. Mitigation: deref inject's returned future with timeout.
2. **Leaked threads accumulate** (in-process fallback only): Each deadlock leaks one thread + socket. Mitigation: log, count, alert if > 3.
3. **Connection close blocks**: `close-datalevin-conn` may block. Mitigation: close in background future.
4. **Performance overhead**: ~300ns per write for channel hop + promise. Negligible vs 1-10ms network round-trip.
5. **Server-side semaphore not released**: Only applies to `open-transact` (we use `tx-data`). Theoretical only.
6. **Multiple conn atoms per database**: Brief overlap during recovery. Safe — LMDB single-writer prevents corruption.

---

## Resolved Open Questions

1. **One writer per connection or shared?** One per connection. Matches Datalevin's per-connection locking.
2. **Should `transact-via-flow!` retry on timeout?** No. Deadlock recovery creates fresh writer and retries once.
3. **What timeout for writes?** 10s (same as current). Normal writes 1-10ms.
4. **Keep `future-cancel` as fallback?** No. Flow replaces it entirely.
5. **How to handle `pause-writes!`?** `flow/pause` works. Callers block on promise until resume.
6. **Close old socket on recovery?** Optional optimization. Not required for correctness.
7. **Automatic or caller-driven retry?** Automatic: new conn+flow, retry once, throw on second failure.
8. **Read-after-write with separate process?** Fine for remote Datalevin. Server always returns latest committed data.
9. **TCP channel buffer sizing?** Default 32 is plenty. Writer serializes, so typically one outstanding write.
10. **Startup ordering?** Pool depends on Datalevin server. Writer JVM connects on startup, health-checks before accepting writes.
11. **Fallback if pool unavailable?** Yes, fall back to in-process writes during pool warmup.
