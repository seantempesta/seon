---
type: prd
status: draft
tags: [prd, database, flow, architecture]
---
# Seon Unified Flow Architecture

This document explains how Seon's components fit together, from physical
JVM processes up through the core.async.flow dataflow graph that routes
all cross-boundary calls.

---

## 1. Process Map: What Runs Where

Every box below is a separate OS process (JVM or native binary).
Arrows show network connections with their protocols.

```
 Host Machine
 ============================================================================

 +---------------------------+          +---------------------------+
 |   Datalevin Server JVM    |          |      Caddy (native)       |
 |   port 8898               |          |      port 3030            |
 |                           |          |                           |
 |  LMDB storage engine      |<----+    |  HTTPS reverse proxy      |
 |  Datalog query engine     |     |    |  TLS termination          |
 |  Per-DB write locks       |     |    |  HTTP/2 to clients        |
 |                           |     |    |                           |
 |  data/datalevin/           |     |    +-------------+-------------+
 +---------------------------+     |                  |
         ^  ^  ^                   |                  | HTTP :8080
         |  |  |                   |                  v
         |  |  |  TCP              |    +---------------------------+
         |  |  |  (Datalevin       |    |   Orchestrator JVM        |
         |  |  |   client proto)   |    |   nREPL :7888             |
         |  |  |                   |    |   HTTP  :8080             |
         |  |  +-------------------+----+                           |
         |  |                      |    |  Integrant system         |
         |  |                      |    |  Flow topology (future)   |
         |  |                      |    |  Connection manager       |
         |  |                      |    |  Code scanner             |
         |  |                      |    |  Web UI + SSE             |
         |  |                      |    |  JVM Pool manager         |
         |  |                      |    |                           |
         |  |                      |    +------+------+------+------+
         |  |                      |           |      |      |
         |  |                      |           | TCP  | TCP  | TCP
         |  |                      |           | bridge bridge bridge
         |  |                      |           |      |      |
         |  +----------------------+-----------+      |      |
         |                         |                  |      |
         +-------------------------+------------------+      |
                                   |                         |
 +---------------------------+     |    +--------------------+------+
 |   Agent JVM #1            |     |    |   Agent JVM #2            |
 |   nREPL :7900             |-----+    |   nREPL :7901             |
 |                           |          |                           |
 |  Bridge loop              |          |  Bridge loop              |
 |  Local fn execution       |          |  Local fn execution       |
 |  Proxy namespaces         |          |  Proxy namespaces         |
 |  Own Datalevin client     |          |  Own Datalevin client     |
 +---------------------------+          +---------------------------+

```

**Key facts:**

- Datalevin survives orchestrator restarts. Killing Seon does NOT kill the DB.
- Each agent JVM has its own Datalevin client connection (direct to :8898).
- The TCP bridge between orchestrator and agent is for flow messages only (function calls/replies).
- Agent JVMs are pre-warmed in a pool and assigned on demand.

---

## 2. Layer Diagram: What Crosses Each Boundary

Four distinct layers, each with a clear contract at its boundary.

```
 +=========================================================================+
 |                        APPLICATION LAYER                                |
 |                                                                         |
 |  Agent code, domain logic, REPL eval, web handlers                      |
 |  Calls functions by name. Doesn't know about flows or TCP.              |
 |                                                                         |
 |  Interface:  (my-fn arg1 arg2)  -- normal Clojure function calls        |
 |              Proxy fns make remote calls look local                      |
 +============================+============================================+
                               |
                    Proxy functions / request!
                    Map-in envelopes (::msg/request, ::msg/reply)
                               |
 +============================v============================================+
 |                         FLOW GRAPH LAYER                                |
 |                                                                         |
 |  core.async.flow topology: processes + connections                       |
 |  Routes requests to the right namespace process                         |
 |  Backpressure via queue-cap per namespace                               |
 |  Reply routing via promise correlation                                  |
 |                                                                         |
 |  Interface:  flow/inject to send, promises to receive                   |
 |              Envelope maps with ::msg/* keys                            |
 +============================+============================================+
                               |
                    Length-prefixed EDN over TCP
                    (seon.flow.harness.channel)
                               |
 +============================v============================================+
 |                     CONNECTION MANAGER LAYER                            |
 |                                                                         |
 |  seon.db.datalevin.conn -- caches connections per DB name               |
 |  Lazy creation, TTL cleanup, staleness detection, auto-reconnect        |
 |  seon.flow.harness.channel -- TCP socket <-> core.async adapter         |
 |                                                                         |
 |  Interface:  get-conn! / close-conn! / reconnect!                       |
 |              start-server! / connect! (TCP channels)                    |
 +============================+============================================+
                               |
                    Datalevin client protocol (TCP :8898)
                    Raw socket I/O (bridge TCP)
                               |
 +============================v============================================+
 |                      DATALEVIN SERVER LAYER                             |
 |                                                                         |
 |  External JVM process. LMDB storage. Datalog queries.                   |
 |  One write lock per database. Multiple readers.                         |
 |  Databases: seon.runtime, seon.ai, seon.flow, seon.{namespace}          |
 |                                                                         |
 |  Interface:  Datalevin wire protocol over TCP                           |
 |              d/transact!, d/q, d/pull on client side                    |
 +=========================================================================+

```

---

## 3. Flow Graph Internals

The topology is a core.async.flow graph. Each box is a **process** (a thread
running a step-fn loop). Lines are **connections** (channel wires between
process outputs and inputs).

### Current State (standalone writer, topology only in tests)

```
                          flow/inject
                              |
                              v
 +---------------------------[request]---------------------------+
 |                    :ns/seon.health.lifting                    |
 |                    (namespace-step)                           |
 |                                                               |
 |  ins:  :seon.flow.in/request   (from flow/inject)            |
 |  outs: :seon.flow.out/reply    -> reply-router                |
 |        :seon.flow.out/error    -> error-sink                  |
 |        :seon.flow.out/event    -> event-sink                  |
 |  in-ports:  :seon.flow.in/jvm-reply     (from TCP)           |
 |  out-ports: :seon.flow.out/jvm-request  (to TCP)             |
 +---+------------------+------------------+---------------------+
     |                  |                  |
     | :reply           | :error           | :event
     v                  v                  v
 +---+------+    +------+-------+    +-----+--------+
 | reply-   |    | error-sink   |    | event-sink   |
 | router   |    |              |    |              |
 |          |    | Stores last  |    | Stores last  |
 | Delivers |    | 100 errors   |    | 100 events   |
 | promises |    | for debug    |    | for observe  |
 +----------+    +--------------+    +--------------+

 (Repeated for each namespace: :ns/seon.trading.signals, etc.)

```

### Proposed State: Infrastructure Flow + Per-Namespace Flows

Two kinds of flow, started independently:

```
 INFRASTRUCTURE FLOW (started at boot, rarely rebuilt)
 =====================================================
 Processes wired by connections (inside one flow graph):

 +--[request]-----+
 |  :seon/writer   |     +----------+     +----------+     +----------+
 | (writer-step)   |---->| reply-   |     | error-   |     | event-   |
 | d/transact! to  |     | router   |     | sink     |     | sink     |
 | any Datalevin DB|     |          |     |          |     |          |
 +-----------------+     | delivers |     | last 100 |     | last 100 |
                         | promises |     | errors   |     | events   |
                         +----------+     +----------+     +----------+
                              ^                ^                ^
                              |                |                |
                         flow/inject      flow/inject      flow/inject
                         (from namespace flows — crosses flow boundary)

 NAMESPACE FLOWS (created lazily when agents claim namespaces)
 =============================================================
 Each is its own flow graph. Created/destroyed independently.

   Namespace flow: seon.health.lifting
   +--[request]-------------------+
   | :ns/seon.health.lifting      |
   | (namespace-step)             |
   |                              |
   | in:  flow/inject from        |     On reply/error/event output:
   |      topology/request!       |     flow/inject into infrastructure
   | out: replies, errors, events +---> flow's reply-router / sinks
   |                              |
   | in-ports:  TCP from agent JVM|
   | out-ports: TCP to agent JVM  |
   +------------------------------+

   Namespace flow: seon.health.nutrition    (separate flow, separate JVM)
   +------------------------------+
   |  (same pattern)              +---> same infrastructure sinks
   +------------------------------+

   Namespace flow: seon.repl               (REPL eval, same pattern)
   +------------------------------+
   |  (same pattern)              +---> same infrastructure sinks
   +------------------------------+

```

**Why separate flows?**

- Adding a namespace = create a new flow. No disruption to existing.
- Updating code = redefine the var. No flow restart at all.
- Full rebuild only needed for infrastructure changes (rare).
- Each namespace can be paused/resumed/pinged independently.

---

## 4. Cross-JVM Call Sequence

What happens when Agent A (in JVM1, namespace `seon.health.lifting`) calls
a function in namespace `seon.health.nutrition` (running in JVM2).

```
 Agent JVM1                  Orchestrator JVM                Agent JVM2
 (seon.health.lifting)       (flow topology)                 (seon.health.nutrition)
 ======================      ====================            ======================

 1. Agent code calls:
    (nutrition/metabolic-rate 75)
         |
         | This is a PROXY function
         | created by proxy-ns!
         v
 2. proxy-fn sends
    remote-call! via
    reverse channel
         |
         | ::msg/request envelope
         | {::to-ns "seon.health.nutrition"
         |  ::fn "seon.health.nutrition/metabolic-rate"
         |  ::args [75]}
         v
 3. TCP out-ch ------------>  4. cross-ns-relay go-loop
    (length-prefixed EDN)        reads from reverse-request-ch
                                      |
                                      v
                              5. relay calls request!
                                 {::flow topology
                                  ::target-ns "seon.health.nutrition"
                                  ::fn "...metabolic-rate"
                                  ::args [75]}
                                      |
                                      | Registers promise
                                      | in pending-promises atom
                                      v
                              6. flow/inject into
                                 [:ns/seon.health.nutrition
                                  :seon.flow.in/request]
                                      |
                                      v
                              7. namespace-step transform:
                                 - Check queue-cap (backpressure)
                                 - Forward to out-port
                                   :seon.flow.out/jvm-request
                                      |
                                      | TCP bridge
                                      v
                                                              8. TCP in-ch delivers
                                                                 request to bridge loop
                                                                      |
                                                                      v
                                                              9. bridge/execute-local
                                                                 - resolve-fn
                                                                 - (apply var args)
                                                                 - Build reply envelope
                                                                      |
                                                                      v
                                                             10. TCP out-ch sends reply
                                      |
                                      | TCP bridge
                                      v
                             11. namespace-step transform:
                                 input-id = :seon.flow.in/jvm-reply
                                 - Decrement pending count
                                 - Emit on :seon.flow.out/reply
                                      |
                                      v
                             12. reply-router-step transform:
                                 - Look up promise by ::msg/id
                                 - deliver promise with reply
                                      |
                                      v
                             13. request! deref unblocks
                                 - Returns ::msg/value
                                      |
                                      | Reply back through
                                      | reverse-reply-ch
                                      v
 14. TCP in-ch <------------  relay sends reply on
     delivers reply              reverse-reply-ch
         |
         v
 15. remote-call! deref
     unblocks, returns value
         |
         v
 16. proxy-fn returns 1850
     to calling code

```

**Total hops: 16 steps, 4 TCP boundary crossings (2 each way).**

---

## 5. Writer Call Sequence

### Current: Standalone Writer Flow (per-connection)

```
 Application code                   Writer flow (standalone)       Datalevin
 ================                   =======================        =========

 (seon.db/transact! conn tx-data)
      |
      v
 1. validate-attrs!
    ensure-schema!
    ensure-writer! (lazy create)
      |
      v
 2. Direct d/transact! in a future
    with 10s timeout
      |                                                                 |
      +---------------------------------------------------------------->
      |                                                                 |
      |                              d/transact! conn tx-data           |
      |                                                                 |
      <-----------------------------------------------------------------+
      |
 3. track-write! (stats atom)
      |
      v
 4. Return tx-report

 Writer flow exists for COORDINATION only:
 - pause-writes! -> flow/pause -> transition hook flushes
 - resume-writes! -> flow/resume
 - writer-status -> flow/ping (observe state)
 Actual writes DO NOT go through the flow channel.

```

### Proposed: Writer Process in Main Topology

```
 Application code               Main flow topology              Datalevin
 ================               ==================              =========

 (seon.db/transact! conn tx-data)
      |
      v
 1. topology/request!
    (same pattern as namespace calls)
    - Register promise by ::msg/id
    - flow/inject into
      [:seon/writer :seon.flow.in/request]
      |
      +------>  2. writer-step transform:
      |            - Extract db-name + tx-data from msg
      |            - d/transact! on the right conn  ---------->  d/transact!
      |            - Build reply envelope             <----------  tx-report
      |            - Emit on :seon.flow.out/reply
      |                    |
      |                    v
      |         3. reply-router (SAME one used by namespaces)
      |            - Look up promise by ::msg/id
      |            - deliver promise with reply
      |
      v
 4. request! deref unblocks
    Returns tx-report

 Benefits:
 - SAME request!/reply-router pattern as namespace calls
 - pause/resume pauses ALL writes (backup coordination)
 - Writer state visible via flow/ping (metrics, health)
 - Topology snapshot captures writer state
 - No separate standalone flow, no separate promise atoms

```

---

## 6. Dynamic Topology: Three Operations

Flow graphs are static after `create-flow` — you can't add processes to a
running flow. But we don't need to. The architecture uses SEPARATE flows:
one stable infrastructure flow + one flow per namespace.

### Three operations, from cheapest to most expensive

### 6a. Update existing namespace code (FREE — no flow changes)

Step-fns are passed as vars (`#'namespace-step`). Redefining the var
changes behavior immediately in the running flow. This is how REPL-driven
development works.

```
 Agent redefines a function in seon.health.lifting
    |
    v
 (defn calculate-1rm ...)   ;; new implementation
    |
    v
 DONE. Next call to calculate-1rm uses the new code.
 No flow restart. No disruption. Zero cost.

```

### 6b. Add a new namespace (CHEAP — new flow, no disruption to existing)

Each namespace gets its own mini-flow. Adding one doesn't touch others.

```
 Agent claims "seon.health.delta"
    |
    v
 1. Acquire agent JVM from pool
    pool/acquire! -> JVM with nREPL port
    |
    v
 2. Start TCP bridge
    channel/start-server! -> bridge channels
    Load bridge code into agent JVM
    |
    v
 3. Create NEW flow for this namespace only
    flow/create-flow
      {:delta-step (flow/process #'namespace-step
                     {:args {::namespace "seon.health.delta"
                             ::in-ports ...
                             ::out-ports ...}})}
    |
    v
 4. flow/start + flow/resume
    |
    v
 5. Register in topology registry
    Callers can now request! this namespace

 EXISTING FLOWS UNTOUCHED. Alpha, beta, gamma keep running.

 Infrastructure flow        Namespace flows (independent)
 =====================      ==============================
 | reply-router     |       | :ns/alpha flow |  (running)
 | event-sink       |       | :ns/beta  flow |  (running)
 | error-sink       |       | :ns/gamma flow |  (running)
 | writer           |       | :ns/delta flow |  (NEW!)
 =====================      ==============================

```

Namespace flows send replies to the infrastructure flow's reply-router
via `flow/inject`. This crosses flow boundaries but uses the same
message envelope format.

### 6c. Full rebuild from zero (RARE — only for infrastructure changes)

Only needed if writer, reply-router, or sink processes change.
Tested but rarely executed in production.

```
 1. Pause infrastructure flow
 2. Ping (sync barrier)
 3. Snapshot all process state via ping
 4. Stop infrastructure flow
 5. create-flow with new config
 6. Start + restore state (procs support :restore param)
 7. Resume

```

During rebuild, in-flight requests to the infrastructure flow get
timeout errors. Namespace flows are unaffected (they're separate).
Callers retry.

---

## 7. Boot Sequence: Integrant Component Dependencies

Components start in dependency order. Arrows mean "depends on" (started after).

```
 :seon.schema/registry          (no deps -- first to start)
       |
       v
 :seon.db.datalevin/server      (start/adopt external Datalevin JVM)
       |
       v
 :seon.db.datalevin/connections (connection manager, needs server)
       |
       +------------------+------------------+
       |                  |                  |
       v                  v                  v
 :seon/runtime-db    :seon.flow/pool    :seon.dev/nrepl
 (code graph conn,   (pre-warm 3 JVMs,  (port 7888,
  mark crashed,       each gets its      REPL access)
  hydrate cache)      own nREPL port)
       |                  |
       v                  v
 :seon.graph/scanner :seon.orchestrator/sessions
 (background scan,   (session storage,
  populates graph)    wires pool ref)
       |
       v
 :seon.web.server/http-server   (port 8080, routes, SSE)
       |
       v
 :seon.web/caddy                (HTTPS proxy :3030 -> :8080)
       |
       v
 :seon.ai.claude/sdk            (Claude CLI path config)

```

**Startup timeline (approximate):**

```
 t=0s    Schema registry loads
 t=0.1s  Datalevin server adopted (or started fresh)
 t=0.2s  Connection manager ready
 t=0.3s  runtime-db connected, crashed instances marked
 t=0.5s  nREPL server listening on :7888
 t=0.5s  Pool starts warming 3 agent JVMs (background)
 t=1.0s  HTTP server listening on :8080
 t=1.0s  Code scanner starts (background future)
 t=1.5s  Caddy proxy ready on :3030
 t=3.0s  Pool JVMs warm (background)
 t=4.0s  Code scan complete (background)

```

---

## 8. Message Envelope Schema

Every message crossing the flow graph uses the same envelope format.
Defined in `seon.flow.msg`.

### Request

```clojure
{::msg/id         #uuid "..."        ; Correlation ID
 ::msg/version    1                   ; Protocol version
 ::msg/type       :request
 ::msg/from-ns    "seon.health.lifting"
 ::msg/to-ns      "seon.health.nutrition"
 ::msg/fn         "seon.health.nutrition/metabolic-rate"
 ::msg/args       [75 180]
 ::msg/created-at #time/instant "2026-03-03T..."
 ;; optional:
 ::msg/trace-id   #uuid "..."        ; Distributed trace
 ::msg/timeout-ms 10000}

```

### Reply (success)

```clojure
{::msg/id         #uuid "..."        ; Echoes request ID
 ::msg/version    1
 ::msg/type       :reply
 ::msg/status     :ok
 ::msg/value      1850                ; Return value (must be EDN-serializable)
 ::msg/from-ns    "seon.health.nutrition"
 ::msg/duration-ms 42}

```

### Reply (error)

```clojure
{::msg/id            #uuid "..."
 ::msg/version       1
 ::msg/type          :reply
 ::msg/status        :error           ; or :timeout, :overload
 ::msg/error-type    :execution       ; or :not-found, :serialization, :timeout, :overload
 ::msg/error-class   "clojure.lang.ExceptionInfo"
 ::msg/error-message "Division by zero"
 ::msg/from-ns       "seon.health.nutrition"
 ::msg/duration-ms   1}

```

---

## 9. TCP Bridge Protocol

Communication between orchestrator and agent JVMs uses length-prefixed EDN
over a TCP socket. Implemented in `seon.flow.harness.channel`.

```
 Wire format:
 +-------------------+------------------------------------------+
 | 4 bytes           | N bytes                                  |
 | (big-endian int)  | (UTF-8 encoded EDN string)               |
 | = N               | = pr-str of message envelope             |
 +-------------------+------------------------------------------+

 Bidirectional:
 - Forward channel:  orchestrator -> agent (requests)
 - Reverse channel:  agent -> orchestrator (cross-ns requests)

 Each direction has its own reader thread and writer thread.
 Channels buffer up to 32 messages (core.async chan size).

```

### Connection Setup Sequence

```
 Orchestrator JVM                          Agent JVM
 ================                          =========

 1. channel/start-server!
    {::port 0}  ; random port
    ServerSocket listening
    Accept thread waiting
         |
         |  pool/nrepl-eval! loads bridge code:
         |  (require 'seon.flow.harness.bridge)
         |  (require 'seon.flow.harness.channel)
         |
         |  pool/nrepl-eval! connects back:
         |  (channel/connect! {::host "localhost"
         |                     ::port <bridge-port>})
         |
         |                              2. Socket.connect()
         |<---------------------------------+
         |                                  |
 3. Accept thread wires                4. connect! wires
    reader + writer threads               reader + writer threads
         |                                  |
         v                                  v
    {::in-ch  (from agent)            {::in-ch  (from orch)
     ::out-ch (to agent)               ::out-ch (to orch)
     ::close! fn}                      ::close! fn}

```

---

## 10. Proxy Namespace Transparency

Agent code calls remote functions with normal Clojure syntax. The proxy
system makes this transparent.

```
 Agent JVM (seon.health.lifting)
 ================================

 ;; At JVM setup time, orchestrator sends via nREPL:
 (proxy/proxy-ns!
   {::proxy/target-ns  "seon.health.nutrition"
    ::proxy/functions   {"metabolic-rate" {::proxy/arglists '([weight height])}
                         "daily-calories" {::proxy/arglists '([activity-level])}}
    ::proxy/request-ch  reverse-out-ch
    ::proxy/from-ns     "seon.health.lifting"})

 ;; This creates namespace seon.health.nutrition with proxy vars.
 ;; Agent code can then:

 (require '[seon.health.nutrition :as nutrition])
 (nutrition/metabolic-rate 75 180)
 ;; => 1850  (transparently routed through flow)

 ;; Under the hood, the proxy var calls:
 ;;   bridge/remote-call! -> reverse channel -> orchestrator
 ;;   -> flow/inject -> namespace-step -> TCP -> target JVM
 ;;   -> execute-local -> reply back through the same path

```

---

## 11. Backpressure and Error Handling

### Backpressure (per-namespace queue cap)

```
 request! called        namespace-step
 ================       ==============

 inject request ------> Check: pending >= queue-cap?
                              |
                         +----+----+
                         |         |
                         v         v
                        NO        YES
                         |         |
                    Forward to   Return immediately:
                    agent JVM    {::status :overload
                    pending++     ::error-type :overload}
                         |
                    (on reply)
                    pending--

```

Default `queue-cap` is 32 per namespace. Overload replies are instant
(no waiting). The caller gets an ex-info with `:overload` status.

### Error Propagation Path

```
 Agent JVM throws           Flow graph                    Caller
 ===================        ==========                    ======

 (throw (ex-info ...))
      |
      v
 execute-local catches
 Builds error reply:
 {::status :error
  ::error-type :execution
  ::error-message "..."}
      |
      | TCP
      v
 namespace-step:
   pending--
   error-count++
   emit on :seon.flow.out/reply  -----> reply-router
   emit on :seon.flow.out/error  -----> error-sink (logging)
                                              |
                                        deliver promise
                                              |
                                              v
                                        request! checks status
                                        throws ex-info with
                                        error details from reply

```

---

## 12. Current vs. Proposed: Gap Summary

| Aspect | Current | Proposed |
|--------|---------|----------|
| Flow topology | Tests only, never started in prod | Integrant component, always running |
| Writer | Standalone per-conn flow | Process in main topology |
| REPL eval | Direct nrepl-eval! | Routed through topology |
| Adding namespace | N/A (no running topology) | Create new per-namespace flow (no disruption) |
| Flow state | Not persisted | Snapshot to Datalevin on pause/stop |
| `transact!` | `d/transact!` in future with timeout | `flow/inject` -> writer process -> promise |
| Super-REPL | `pool/nrepl-eval!` directly | Inject into `:seon/repl` process |

The core infrastructure (message envelopes, TCP bridges, proxy namespaces,
reply routing, backpressure) is **built and tested**. The gap is wiring it
into production as the single routing backbone.

---

## 13. Research: Flow Graph Mutability

### Question

Can core.async.flow graphs be updated live? Can we add/remove processes
or change connections on a running flow?

### Answer: No. Flow graphs are structurally immutable after `create-flow`

The flow object is a `reify` that closes over its process descriptions (`pdescs`),
connection map (`conn-map`), channel option maps (`inopts`, `outopts`), and a
`chans` atom (which holds live channels only while running). There is no API
to mutate any of these.

### What the Graph protocol exposes

Verified in the REPL -- the `Graph` interface has exactly these methods:

```
start, stop, pause, resume, ping, inject,
pause_proc, resume_proc, command_proc, ping_proc

```

No `add-proc`, `remove-proc`, `update-conns`, or anything similar.

### Detailed findings

| Question | Answer | Evidence |
|----------|--------|----------|
| Add processes to running flow? | **No** | `pdescs` is a local val in `create-flow`, closed over by the reify. No setter. |
| Modify connections on running flow? | **No** | `conn-map` is a local val, channels created at `start` time based on it. |
| Change config between pause/resume? | **No** | `pause`/`resume` only send command messages to the control channel. No reconfiguration. |
| Hot-swap step-fns? | **Yes, via var indirection** | If you pass `#'my-fn` (the var) to `flow/process`, the process loop calls through the var on every transform. Redefining the var changes behavior immediately. This is documented in the flow guide under "Reloading". |
| State survives pause/resume? | **Yes** | The process loop keeps its `state` local across pause/resume transitions. Verified: ping after pause shows same state. |
| State survives stop/start? | **No** | `stop` closes all channels and resets the `chans` atom to nil. `start` creates fresh channels and calls `init` again. All process state is lost. |
| `datafy` exposes mutable internals? | **No** | `datafy` returns a `postwalk`-ed snapshot: `{:procs :conns :execs :chans}`. The `:chans` value contains datafied channel maps (buffer stats), not raw channel objects. Safe for observability. |
| Same flow object reusable after stop? | **Yes** | `stop` then `start` works. Fresh channels, fresh init. The flow config (procs/conns) is still there. |

### REPL session (evidence)

```clojure
;; Graph protocol methods
(->> clojure.core.async.flow.impl.graph.Graph
     .getDeclaredMethods (mapv #(.getName %)))
;; => ["start" "stop" "resume" "ping" "inject" "pause"
;;     "pause_proc" "resume_proc" "command_proc" "ping_proc"]

;; Pause preserves state
(flow/pause f)
(flow/ping f :timeout-ms 2000)
;; => {:p1 {::flow/status :paused, ::flow/state {:count 0}, ...}}
(flow/resume f)
(flow/ping f :timeout-ms 2000)
;; => {:p1 {::flow/status :running, ::flow/state {:count 0}, ...}}

;; Stop -> start resets state (init called again)
(flow/stop f)
(flow/start f)  ;; fresh channels, fresh init
(flow/resume f)
(flow/ping f :timeout-ms 2000)
;; => {:p1 {::flow/status :running, ::flow/state {:count 0}, ...}}
;; count reset to 0 because init was called again

;; datafy returns snapshot, not live refs
(clojure.datafy/datafy f)
;; => {:procs {:p1 {:proc {...}}}, :conns [], :execs {...},
;;     :chans {:ins {[:p1 :in] {:buffer {...}}}, :outs {[:p1 :out] nil}, ...}}

```

### Cleanest workaround for dynamic topology

Since flow graphs cannot be modified in place, the **stop -> rebuild -> start**
approach in Section 6 is confirmed as the correct strategy. However, based on
the source code analysis, here are refinements:

**Key insight: `stop`/`start` on the SAME flow object works, but you cannot
change its process set.** To add a process, you MUST call `create-flow` with
a new config map. The old flow object is dead.

**Refined rebuild sequence:**

```
1. flow/pause old-flow          (async - sends command)
2. flow/ping old-flow           (sync barrier - all procs paused)
3. Snapshot state via ping       (capture each proc's state map)
4. flow/stop old-flow            (closes all channels, procs exit)
5. create-flow NEW-config        (new flow object with added/removed procs)
6. flow/start new-flow           (fresh channels, init called)
7. Restore state via inject      (inject saved state into procs that support it)
8. flow/resume new-flow          (all procs running)
9. Swap atom: reset! topology-atom new-flow

```

**State restoration detail:** Since `init` is called fresh on `start`, processes
that need to survive rebuilds should support a `:restore` parameter in their
`:params` describe. The init arity checks for `(:restore args)` and uses it
as initial state instead of the default. This way, step 7 becomes: pass the
pinged state as `:restore` in the proc-def `:args`.

**Alternative: separate flow per concern.** Instead of one mega-flow, run:

- Flow A: infrastructure (writer, reply-router, error-sink, event-sink) -- rarely changes
- Flow B: namespace processes -- rebuilt when namespaces change

Namespace processes emit to Flow A's reply-router via `inject`. This avoids
rebuilding the stable infrastructure when only namespace membership changes.
Downside: two flows to manage, cross-flow inject instead of connections.

**Recommendation:** Start with single-flow rebuild (simpler). If rebuild
frequency becomes a problem, split into two flows later. The message envelope
format is the same either way -- the split is purely a topology concern.
