# Flow-Based Datalevin Writer: Research and Design

## What We Already Have

### core.async flow Primitives (reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj)

Flow is a library for building concurrent data processing graphs from communication-free functions. Key primitives:

- **`flow/process`** — wraps a step-fn (4-arity fn: describe/init/transition/transform) into a process launcher
- **`flow/create-flow`** — builds a flow from `{:procs {:pid {:proc ... :args ...}} :conns [...]}`
- **`flow/start`** / **`flow/stop`** / **`flow/pause`** / **`flow/resume`** — lifecycle
- **`flow/inject`** — async put messages onto a process's input channel. Returns a future.
- **`flow/ping`** — sync barrier. Returns map of `pid -> {:state ... :count ... :status ...}`
- **Workloads**: `:io` (thread per process), `:compute` (submitted to executor per transform), `:mixed`
- **Error handling**: All exceptions on any flow thread go to `:error-chan`. Process continues.
- **Supervision**: Flow catches exceptions in transform and reports them. The process does NOT die — it logs the error and continues processing the next message. There is no automatic restart of a hung thread.

**Critical limitation**: flow does NOT kill or restart threads. If a transform blocks forever (e.g., stuck `d/transact!`), the flow process is permanently stuck. `flow/stop` sends a `:stop` transition but the process only receives it on its next loop iteration — which never happens if transform is blocked.

### seon.db.datalevin.writer (existing)

Already exists at `src/seon/db/datalevin/writer.clj`. It's a flow step-fn (`db-writer-step`) that:

- Receives tx messages on `:in/transact`
- Calls `d/transact!` directly in the transform
- Tracks write count, errors, timing
- On pause/stop, flushes via `d/transact! conn []`
- `create-writer-flow` creates a single-process flow
- `inject-tx!` puts a tx message into the flow

**Current usage in seon.db**: Writer flows are created lazily per-connection for backup coordination (pause writes before backup). But `seon.db/transact!` does NOT route writes through the flow — it calls `d/transact!` directly via a future with timeout. The writer flow exists only for pause/resume lifecycle.

### seon.flow.topology — Request/Response Pattern

`topology.clj` solves the request/response-over-flow problem with promises:

1. **`pending-promises`** — global atom of `{request-id -> promise}`
2. **`request!`** — registers a promise, injects into flow, derefs with timeout
3. **`reply-router-step`** — flow step that delivers replies to promises by matching `::msg/id`

This is exactly the pattern needed for a synchronous `transact!` that routes through a flow.

### seon.flow.harness — The Full Architecture

The harness system shows a complete single-writer architecture:
- Orchestrator-side `namespace-step` receives requests, forwards to agent JVM
- Agent JVM `bridge-step` executes locally, returns reply
- Cross-namespace calls go through `remote-call!` (promise + inject)
- Backpressure via pending count + queue cap + overload replies

## The Deadlock Problem (Recap)

From `datalevin-deadlock-research.md`:

1. `d/transact!` acquires 3 nested Java monitors: conn atom, write-txn, ByteBuffer
2. If the Datalevin server is slow/hung, the thread blocks on socket read holding all monitors
3. `future-cancel` / `Thread.interrupt()` does NOT release Java monitors
4. All subsequent `d/transact!` on that conn deadlock at `(locking conn ...)`
5. Only fix today: `pkill -9`

## Proposed Architecture

### Single Writer Flow Per Connection

```
Caller thread                   Writer flow (single :io thread)
     |                                    |
     |-- promise + inject tx msg -------> |
     |                                    |-- d/transact!(conn, tx-data)
     |                                    |   [holds monitors on writer thread only]
     |                                    |-- reply with result or error
     |<--- deliver promise --------------|
     |
     v (deref with timeout)
```

The key insight: calling threads never touch `d/transact!` or hold Datalevin monitors. Only the single writer thread does.

### Step Function: `db-writer-step` (enhanced)

Modify the existing `seon.db.datalevin.writer/db-writer-step` to support request/response:

```clojure
;; transform
([state :in/transact tx-msg]
  (let [conn     (::conn state)
        tx-data  (::tx-data tx-msg)
        reply-to (::reply-to tx-msg)  ;; [pid in-id] coord for reply
        request-id (::request-id tx-msg)
        t0       (System/nanoTime)]
    (try
      (let [result (d/transact! conn tx-data)
            elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
        [(update state ::total-writes inc)
         {reply-to [{::request-id request-id
                     ::status :ok
                     ::result result
                     ::elapsed-ms elapsed-ms}]}])
      (catch Exception e
        [(update state ::total-errors inc)
         {reply-to [{::request-id request-id
                     ::status :error
                     ::error (.getMessage e)}]}]))))
```

**Problem**: flow outputs must be declared in `describe`. The `reply-to` coord pattern doesn't work because outputs are static. Instead, use the promise pattern from topology.clj.

### Revised Design: Promise-Based (like topology/request!)

```clojure
;; Global atom per writer: {request-id -> promise}
(defonce ^:private *pending (atom {}))

(defn transact-via-flow!
  "Submit a transaction through the writer flow. Blocks until complete or timeout."
  [writer-flow conn tx-data timeout-ms]
  (let [request-id (random-uuid)
        p (promise)
        msg {::tx-data tx-data
             ::request-id request-id}]
    (swap! *pending assoc request-id p)
    (try
      (flow/inject writer-flow [:writer :in/transact] [msg])
      (let [result (deref p timeout-ms ::timeout)]
        (swap! *pending dissoc request-id)
        (if (= result ::timeout)
          (throw (ex-info "Transaction timed out" {:timeout-ms timeout-ms}))
          (if (::error result)
            (throw (ex-info (::error result) result))
            (::result result))))
      (catch Exception e
        (swap! *pending dissoc request-id)
        (throw e)))))
```

The writer step delivers to promises directly (side-effect in transform):

```clojure
([state :in/transact tx-msg]
  (let [request-id (::request-id tx-msg)
        result (try
                 {::status :ok ::result (d/transact! (::conn state) (::tx-data tx-msg))}
                 (catch Exception e
                   {::status :error ::error (.getMessage e)}))]
    (when-let [p (get @*pending request-id)]
      (swap! *pending dissoc request-id)
      (deliver p result))
    [(cond-> state
       (= :ok (::status result)) (update ::total-writes inc)
       (= :error (::status result)) (update ::total-errors inc))
     nil]))
```

### Deadlock Recovery

This is the hard part. When `d/transact!` hangs inside the writer flow:

1. **Detection**: `transact-via-flow!` times out (deref returns `::timeout`). The promise is never delivered. The writer flow thread is stuck.
2. **The flow process is dead**: It will never process another message because transform is blocked.
3. **We cannot kill the thread**: Java monitors prevent it.

**Recovery strategy — Replace the connection AND the writer flow:**

```clojure
(defn recover-deadlocked-writer!
  "When a writer flow is stuck, abandon it and create a fresh one."
  [conn-manager ns-key]
  ;; 1. Abandon the old writer flow (stop is async, may not complete)
  ;;    The old thread + conn are leaked. This is acceptable.
  (let [old-writer (get @writers (conn-id old-conn))]
    (swap! writers dissoc (conn-id old-conn)))

  ;; 2. Create fresh connection (new atom, new socket, no monitors held)
  (let [new-conn (conn/reconnect! {...})
        ;; 3. Create new writer flow on new connection
        new-flow (create-writer-flow {::conn new-conn ::db-name ...})]
    (swap! writers assoc (conn-id new-conn) {:flow new-flow :conn new-conn})
    new-conn))
```

**What gets leaked**: The old thread (blocked in `d/transact!`), the old conn atom (monitor held), the old socket. The socket will eventually timeout or be GC'd. The thread will die when the socket closes.

**What gets recovered**: A completely fresh connection + writer flow. New transactions proceed immediately.

### Integration with seon.db/transact!

```clojure
(defn transact!
  [conn tx-data]
  (let [writer (ensure-writer! conn)
        flow (:flow writer)]
    (try
      (transact-via-flow! flow conn tx-data default-timeout-ms)
      (catch Exception e
        (when (timeout? e)
          ;; Deadlock detected — recover
          (log/error "Writer flow deadlocked, recovering" {...})
          (recover-deadlocked-writer! ...))
        (throw e)))))
```

## What Needs to Be Built vs. What Exists

### Already Exists
- `seon.db.datalevin.writer/db-writer-step` — writer flow step-fn (needs enhancement)
- `seon.db.datalevin.writer/create-writer-flow` — creates single-process writer flow
- `seon.db.datalevin.writer/inject-tx!` — injects into flow (fire-and-forget)
- `seon.db.datalevin.conn/reconnect!` — closes old conn, creates fresh one
- `seon.flow.topology/request!` — promise-based request/response over flow (reference pattern)
- `seon.db/transact!` — wrapper with future + timeout (replace this)
- Writer flow registry in `seon.db/writers` atom

### Needs to Be Built
1. **Promise delivery in `db-writer-step` transform** — add request-id + deliver pattern
2. **`transact-via-flow!`** — blocking call that registers promise, injects, derefs with timeout
3. **Deadlock recovery** — on timeout, abandon old writer+conn, create fresh ones
4. **Integration with conn manager** — `reconnect!` needs to work in background (old close may block)
5. **Cleanup of old `seon.db/transact!`** — remove future-based wrapper, route through flow

### Scope Estimate
- Enhance writer step-fn: ~30 min
- `transact-via-flow!` with promise pattern: ~1 hour
- Deadlock recovery + conn manager integration: ~2 hours
- Replace `seon.db/transact!` to route through flow: ~1 hour
- Tests: ~2 hours
- **Total: ~1 day**

## Honest Assessment: Does Flow Solve the Problem?

### What flow solves
1. **Serialized writes** — single `:io` thread per connection. No concurrent monitor acquisition.
2. **Caller isolation** — calling threads never hold Datalevin monitors. Timeout is clean (just abandon the promise).
3. **Backpressure** — flow channel buffers provide natural backpressure.
4. **Observability** — write stats, error counts, ping for health.
5. **Backup coordination** — pause/resume already works via writer flow.

### What flow does NOT solve
1. **Killing a stuck thread** — if `d/transact!` blocks forever, the writer flow thread is permanently dead. Flow has no mechanism to kill or restart a blocked transform. This is a fundamental Java limitation (monitors cannot be interrupted).
2. **Automatic restart** — flow reports errors but does NOT restart processes. A stuck transform means the process loop never iterates again.
3. **The leaked thread** — recovery creates a new conn + flow, but the old thread + conn are leaked. In theory this could accumulate over many deadlocks.

### The gap
Flow makes deadlock recovery **manageable** (replace conn + flow, leak old thread) instead of **catastrophic** (whole system deadlocked, need `pkill -9`). But it does not eliminate the underlying problem — a hung `d/transact!` still kills its thread.

The real fix would be either:
- Datalevin using `ReentrantLock.tryLock(timeout)` instead of `synchronized`
- Socket-level timeout on the Datalevin client connection
- Or: the socket-close approach from Option 1 in the deadlock research (close the socket to force `AsynchronousCloseException`, unwinding the monitors)

### Verdict
Flow is the right architecture. It reduces the blast radius from "entire system deadlocked" to "one leaked thread, auto-recovered." That's a massive improvement. The leaked thread is an acceptable tradeoff — in practice, Datalevin server hangs are rare, and each leak is one thread + one socket that will eventually be GC'd.

The socket-close approach (Option 1 from deadlock research) could be added later as an optimization to actually recover the thread instead of leaking it. But the flow-based writer is the prerequisite architecture for both approaches.
