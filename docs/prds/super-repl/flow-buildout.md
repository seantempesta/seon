---
type: prd
status: draft
tags: [prd, database, flow]
---
# Plan: Namespace Harness — Flow-Routed Agent Isolation

> **Status: Executed and completed.** The harness, bridge, proxy, and channel code described here is built and tested. The next step — wiring it into production as the routing backbone — is tracked in [`docs/prds/unified-flow/implementation-plan.md`](../unified-flow/implementation-plan.md).

## Context

Seon agents own namespaces and write well-spec'd functions. We need process isolation where no namespace can crash the system, with transparent cross-namespace communication via core.async.flow.

**This plan is the source of truth** and supersedes prior flow plans in the PRD.

Key principles:

- **`::flow/in-ports` and `::flow/out-ports`** bridge flow to the outside world (`flow/impl.clj:261-263`)
- **Flow state ≠ `*ctx*`** — flow process state is runtime state managed by the harness. `*ctx*` is a separate agent-facing atom injected into the namespace. They are distinct.
- **`::conn` is namespace-local** — Datalevin connection lives in `*ctx*` as `::conn` (auto-resolved to agent's namespace). Connection manager patterns used, no ad-hoc connections.
- **nREPL is for loading code only** — all runtime data flows through TCP socket channels wired as flow in-ports/out-ports
- **Agents feel like they call functions directly** — internal flow routing is invisible to them
- **Orchestrator observes everything** — cross-ns calls, errors, timeouts, queue pressure
- **No unnamespaced keys** in channels or messages, ever
- **No magic numbers** — all defaults in `system.edn`
- **Strict policy**: all keys fully namespaced under `seon.flow.*`

## MVP Scope (This Implementation)

Build the smallest testable slice first:

**In scope:**

1. TCP channel adapter + message envelope schemas
2. One orchestrator namespace-step + one agent bridge-step
3. Blocking request/reply only (no fire-and-forget yet)
4. Queue cap (32 dev default) + overload error path
5. Basic observability events (start, ok, error, overload, timeout)
6. Minimal `*ctx*` persistence: save/load serializable data, warn on non-serializable
7. End-to-end tests: happy path + overload + timeout

**Deferred:**

- Pure-function local execution optimization
- Hierarchical escalation / investigation agents
- Dynamic proxy/wrapper generation
- Fire-and-forget semantics
- Batching, advanced queue strategies, retries/idempotency

**Locked in now (to avoid churn):**

- Single source-of-truth schema namespace for wire messages (`seon.flow.msg`)
- Version key in envelope (`:seon.flow.msg/version`) from day one
- Strict: no unnamespaced keys in channels/messages

## Architecture

Dual-flow model: orchestrator flow + namespace JVM mini-flow. Connected by TCP socket channels as flow in-ports/out-ports.

```
ORCHESTRATOR JVM (flow topology)
├── :ns/seon.test.alpha (namespace-step process)
│   ├── :seon.flow.in/request   ← flow input (cross-ns calls)
│   ├── :seon.flow.out/reply    → flow output (responses to callers)
│   ├── :seon.flow.out/error    → flow output (to error monitor)
│   ├── :seon.flow.out/event    → flow output (observability events)
│   ├── ::flow/in-ports
│   │   └── :seon.flow.in/jvm-reply  ← TCP ← agent JVM responses
│   └── ::flow/out-ports
│       └── :seon.flow.out/jvm-request → TCP → agent JVM requests
│
├── :ns/seon.test.beta (same pattern, own JVM)
├── :seon.flow/reply-router (delivers promises for blocking callers)
└── :seon.flow/error-monitor (tracks rates, emits events)

AGENT JVM (186MB, own mini-flow)
├── nREPL (code loading only)
├── Mini flow:
│   └── :seon.flow/bridge (bridge-step)
│       ├── ::flow/in-ports  {:seon.flow.in/request ← TCP ← orchestrator}
│       ├── ::flow/out-ports {:seon.flow.out/reply  → TCP → orchestrator}
│       └── transform: deserialize → call local fn → serialize response
├── *ctx* atom (agent-facing, contains ::conn + agent data)
│   └── Persisted on stop (serializable keys only, warn on non-serializable)
└── Loaded namespace code

```

### `*ctx*` Model (Distinct from Flow State)

**Flow process state**: managed by harness, contains runtime bookkeeping (JVM handle, error counts, channel refs). Not visible to agents.

**`*ctx*` atom**: agent-facing, injected into the namespace. Contains:

```clojure
@*ctx*
;; => {::conn <datalevin-connection>   ; auto-injected, namespace-local key
;;     ::my-counter 42                 ; agent's own data
;;     ::ui-state {...}}               ; whatever the agent wants

```

Two storage tiers for agents:

```clojure
;; Tier 1: Simple state (atom, auto-persisted across restarts)
(swap! *ctx* assoc ::my-counter 42)

;; Tier 2: Structured storage (Datalevin, cross-session, queryable)
(d/transact! (::conn @*ctx*) [{:trading/signal {...}}])
(d/q '[:find ?e :where [?e :trading/type :signal]] @(::conn @*ctx*))

```

On persist: strip non-serializable keys, warn (don't fail), write rest to Datalevin.
On resume: load persisted data, re-inject `::conn` and other runtime handles.

### Channel and Port Naming Contract

**Input channel IDs** (flow `:ins`):

- `:seon.flow.in/request` — incoming cross-ns function call requests
- `:seon.flow.in/control` — lifecycle commands

**Output channel IDs** (flow `:outs`):

- `:seon.flow.out/reply` — responses to callers
- `:seon.flow.out/error` — error reports
- `:seon.flow.out/health` — health pings
- `:seon.flow.out/event` — observability events (start, ok, error, overload, timeout)

**No transport-oriented names** like `:to-jvm` / `:from-jvm` in the contract. In-ports/out-ports use the same semantic names.

### How `::flow/in-ports` and `::flow/out-ports` Work

From `flow/impl.clj:261-263`, after init returns state:

```clojure
ins  (into (or ins {}) (::flow/in-ports state))   ; merge in-ports into inputs
outs (into (or outs {}) (::flow/out-ports state))  ; merge out-ports into outputs

```

- **In-ports**: external channels merged into process input set. `alts!!` reads them alongside normal flow inputs.
- **Out-ports**: external channels merged into output set. Transform emits to them in output maps.
- **Not visible/resolvable** by other flow processes — they're the boundary.

## Message Envelope (`seon.flow.msg`)

Single source-of-truth schema namespace. Version key from day one.

```clojure
(ns seon.flow.msg
  (:require [seon.schema :as schema]))

;; Envelope keys
(schema/register! ::id [:uuid])
(schema/register! ::version [:= 1])  ; wire protocol version, from day one
(schema/register! ::type [:enum :request :reply :error :event])
(schema/register! ::from-ns [:string {:min 1}])
(schema/register! ::to-ns [:string {:min 1}])
(schema/register! ::fn [:string {:min 1 :description "Fully qualified function name"}])
(schema/register! ::args [:vector :any])
(schema/register! ::timeout-ms [:int {:min 1}])
(schema/register! ::reply-required? [:boolean])
(schema/register! ::trace-id [:uuid {:description "Distributed trace correlation"}])
(schema/register! ::created-at [:fn {:description "Timestamp"} inst?])
(schema/register! ::payload [:map {:description "Accretive extension map"}])

(schema/register! ::request
  [:map
   [::id ::id]
   [::version ::version]
   [::type [:= :request]]
   [::from-ns ::from-ns]
   [::to-ns ::to-ns]
   [::fn ::fn]
   [::args ::args]
   [::reply-required? {:optional true} ::reply-required?]  ; default true
   [::timeout-ms {:optional true} ::timeout-ms]
   [::trace-id {:optional true} ::trace-id]
   [::created-at ::created-at]
   [::payload {:optional true} ::payload]])

;; Response status
(schema/register! ::status [:enum :ok :error :timeout :overload])

;; Error taxonomy
(schema/register! ::error-type [:enum :execution :timeout :overload :serialization :not-found])
(schema/register! ::error-class [:string {:description "Exception class name"}])
(schema/register! ::error-message [:string])
(schema/register! ::error-data [:maybe :map])

(schema/register! ::reply
  [:map
   [::id ::id]           ; matches request ::id
   [::version ::version]
   [::type [:= :reply]]
   [::status ::status]
   [::from-ns ::from-ns]
   [::value {:optional true} :any]
   [::error-type {:optional true} ::error-type]
   [::error-class {:optional true} ::error-class]
   [::error-message {:optional true} ::error-message]
   [::error-data {:optional true} ::error-data]
   [::duration-ms [:int {:min 0}]]
   [::trace-id {:optional true} ::trace-id]])

;; Observability events
(schema/register! ::event-kind [:enum :start :ok :error :overload :timeout :pause :resume :stop])
(schema/register! ::event
  [:map
   [::id ::id]
   [::version ::version]
   [::type [:= :event]]
   [::event-kind ::event-kind]
   [::from-ns ::from-ns]
   [::created-at ::created-at]
   [::payload {:optional true} ::payload]])

```

## Backpressure and Queue Policy

**Dev default**: queue cap 32 (in `system.edn`)
**On full queue**: short enqueue wait → typed `:overload` error returned quickly → overload event emitted. No indefinite stalls.

Per-namespace overrides in namespace code (not system.edn — per user preference).

## Config (`system.edn`)

```clojure
{:seon/flow-defaults
 {:queue-cap 32
  :enqueue-wait-ms 75
  :call-timeout-ms 10000
  :overflow {:strategy :error}}}

```

Per-namespace overrides specified in namespace code, not global config.

## Namespace Structure

```
src/seon/flow/
├── msg.clj                  ; Message envelope schemas (source of truth)
├── harness.clj              ; Namespace-step, ctx persistence, lifecycle
├── harness/
│   ├── bridge.clj           ; Agent-JVM-side bridge step-fn
│   ├── channel.clj          ; TCP socket ↔ core.async channel adapter
│   └── detect.clj           ; Schema-driven pure vs contextual classification
├── topology.clj             ; Flow graph wiring + reply router
test/seon/flow/
├── msg_test.clj             ; Schema validation + EDN round-trip
├── harness_test.clj         ; Integration: full cross-ns lifecycle
├── harness/
│   ├── bridge_test.clj
│   ├── channel_test.clj
│   └── detect_test.clj
└── topology_test.clj

```

## Implementation Steps (MVP, Each Testable)

### Step 0: Context Recovery (READ FIRST)

1. **Flow API** — `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` (lines 163-284)
2. **Flow impl** — `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj` (lines 241-320, in-ports/out-ports merge at 261-263)
3. **Flow guide** — `reference-code/core.async/doc/flow-guide.md` (full file)
4. **Existing flow step-fns** — `src/seon/web/sse/flow.clj`
5. **JVM pool** — `src/seon/flow/pool.clj`
6. **Agent runner** — `src/seon/flow/agent_runner.clj`
7. **puredanger example** — `puredanger/flow-example` `src/stats.clj`
8. **PRD** — `docs/prds/super-repl/prd.md` (Phase 4)
9. **Conventions** — `CONVENTIONS.md`

### Step 1: Message Envelope Schemas

**File**: `src/seon/flow/msg.clj`

Define all envelope schemas as shown above. Malli-registered, fully namespaced under `seon.flow.msg`.

**Test** (`test/seon/flow/msg_test.clj`): Generate samples with `mg/sample`, validate schemas, EDN round-trip serialization.

### Step 2: TCP Channel Adapter

**File**: `src/seon/flow/harness/channel.clj`

Bidirectional TCP ↔ core.async adapter. Length-prefixed EDN. Keys under `seon.flow.harness.channel`.

```clojure
(defn start-server!
  "Start TCP server. Returns {::server ::in-ch ::out-ch ::close! ::port}."
  [{::keys [port]}] ...)

(defn connect!
  "Connect to TCP server. Returns {::in-ch ::out-ch ::close!}."
  [{::keys [host port]}] ...)

```

**Test** (`test/seon/flow/harness/channel_test.clj`): Server on random port, client connects, send/receive EDN both directions. Test close cleanup. Test reconnect behavior.

### Step 3: Bridge Step Function (Agent JVM Side)

**File**: `src/seon/flow/harness/bridge.clj`

Agent JVM's mini-flow process. Uses semantic channel names.

```clojure
(defn bridge-step
  ([] {:params {::harness/namespace "Namespace to serve"
                ::harness/bridge-port "TCP port"}
       :workload :io})

  ([args]
   (let [tcp (channel/connect! {::channel/port (::harness/bridge-port args)})
         ns-sym (::harness/namespace args)
         conn (conn/get-namespace-conn! ...)]  ; via connection manager
     {::harness/namespace ns-sym
      ::harness/functions {}
      ::harness/ctx (atom {(keyword (str ns-sym) "conn") conn})
      ::flow/in-ports {:seon.flow.in/request (::channel/in-ch tcp)}
      ::flow/out-ports {:seon.flow.out/reply (::channel/out-ch tcp)}}))

  ([state transition]
   (case transition
     ::flow/resume (assoc state ::harness/functions (discover-fns state))
     ::flow/stop (do (persist-ctx! state) state)
     state))

  ([state input-id msg]
   (case input-id
     :seon.flow.in/request
     (let [reply (execute-local state msg)]
       [state {:seon.flow.out/reply [reply]}])
     [state nil])))

```

`execute-local`: look up fn var, call with args, catch exceptions, return `::msg/reply` envelope with error taxonomy (`:execution`, `:not-found`, etc).

**Test**: Mock channels, send request, verify reply. Test exception → typed error reply.

### Step 4: Namespace Step Function (Orchestrator Side) ✅

**File**: `src/seon/flow/harness.clj`

```clojure
(defn namespace-step
  ([] {:ins {:seon.flow.in/request "Cross-ns call requests"}
       :outs {:seon.flow.out/reply "Responses"
              :seon.flow.out/error "Errors"
              :seon.flow.out/event "Observability events"}
       :params {::namespace "Namespace symbol" ::pool "JVM pool"}
       :signal-select #{::reload}
       :workload :io})

  ([{::keys [namespace pool] :as args}]
   ;; Acquire JVM, start TCP bridge, load bridge code via nREPL
   (let [jvm (pool/acquire!! pool {::pool/namespace namespace})
         bridge-port (allocate-bridge-port!)
         tcp-server (channel/start-server! {::channel/port bridge-port})
         queue-cap (get-config :queue-cap 32)]  ; from system.edn
     ;; One-time: load bridge into agent JVM via nREPL
     (pool/nrepl-eval! (::pool/port jvm) ...)
     ;; Emit start event
     {:seon.flow.harness/namespace namespace
      :seon.flow.harness/jvm jvm
      :seon.flow.harness/pool pool
      :seon.flow.harness/queue-cap queue-cap
      :seon.flow.harness/pending 0
      :seon.flow.harness/error-count 0
      ::flow/in-ports {:seon.flow.in/jvm-reply (::channel/out-ch tcp-server)}
      ::flow/out-ports {:seon.flow.out/jvm-request (::channel/in-ch tcp-server)}}))

  ([state transition]
   (case transition
     ::flow/stop (do (pool/release! (:seon.flow.harness/pool state)
                                     (:seon.flow.harness/jvm state))
                     state)
     state))

  ([state input-id msg]
   (case input-id
     :seon.flow.in/request
     (if (>= (:seon.flow.harness/pending state) (:seon.flow.harness/queue-cap state))
       ;; Overload: return error immediately, emit event
       (let [overload-reply {::msg/id (::msg/id msg)
                             ::msg/version 1
                             ::msg/type :reply
                             ::msg/status :overload
                             ::msg/error-type :overload
                             ::msg/error-message "Namespace queue at capacity"
                             ::msg/from-ns (str (:seon.flow.harness/namespace state))
                             ::msg/duration-ms 0}]
         [(update state :seon.flow.harness/pending identity)
          {:seon.flow.out/reply [overload-reply]
           :seon.flow.out/event [{::msg/type :event
                                  ::msg/event-kind :overload
                                  ::msg/from-ns (str (:seon.flow.harness/namespace state))
                                  ::msg/created-at (java.time.Instant/now)}]}])
       ;; Forward to agent JVM
       [(update state :seon.flow.harness/pending inc)
        {:seon.flow.out/jvm-request [msg]}])

     :seon.flow.in/jvm-reply
     (let [error? (not= (::msg/status msg) :ok)
           state (-> state
                     (update :seon.flow.harness/pending dec)
                     (cond-> error? (update :seon.flow.harness/error-count inc)))]
       [state {:seon.flow.out/reply [msg]
               :seon.flow.out/error (when error? [msg])
               :seon.flow.out/event [{::msg/type :event
                                      ::msg/event-kind (if error? :error :ok)
                                      ::msg/from-ns (str (:seon.flow.harness/namespace state))
                                      ::msg/created-at (java.time.Instant/now)}]}])

     [state nil])))

```

**Test**: Full round-trip with real pool JVM. Test overload path (send 33 requests with cap 32).

### Step 5: Reply Router + Topology ✅

**File**: `src/seon/flow/topology.clj`

Reply router delivers promises. `request!` is the blocking call entry point.

```clojure
(defn request!
  "Blocking cross-ns call through flow. Returns value or throws."
  [{::keys [flow request]}]
  ...)

```

`build-topology!` wires namespace processes + reply router. Reads queue-cap from config.

**Test**: Wire alpha + beta. `request!` happy path. Timeout test. Overload test.

### Step 6: `*ctx*` Persistence

In `seon.flow.harness`:

```clojure
(defn persist-ctx!
  "Save serializable *ctx* data to Datalevin. Warns on non-serializable values."
  [{::keys [ctx conn namespace]}] ...)

(defn load-ctx!
  "Load *ctx* from Datalevin, re-inject runtime handles (::conn etc)."
  [{::keys [conn namespace]}] ...)

```

**Test**: Set data in ctx, persist, reload, verify. Include a non-serializable value, verify warning + clean recovery.

## Proof of Concept Test Namespaces

```clojure
;; seon.test.alpha — calls beta
(ns seon.test.alpha (:require [seon.test.beta :as beta] [seon.schema :as schema]))
(schema/register! ::name [:string {:min 1}])
(schema/register! ::greeting [:string])
(schema/register! ::greet-request [:map [::name ::name]])
(schema/register! ::greet-response [:map [::greeting ::greeting]])
(defn greet
  {:malli/schema [:=> [:cat ::greet-request] ::greet-response]}
  [{::keys [name]}]
  {::greeting (str "Hello, " (::beta/formatted (beta/format-name {::beta/raw-name name})) "!")})

;; seon.test.beta — target namespace
(ns seon.test.beta (:require [seon.schema :as schema]))
(schema/register! ::raw-name [:string {:min 1}])
(schema/register! ::formatted [:string])
(schema/register! ::format-request [:map [::raw-name ::raw-name]])
(schema/register! ::format-response [:map [::formatted ::formatted]])
(defn format-name
  {:malli/schema [:=> [:cat ::format-request] ::format-response]}
  [{::keys [raw-name]}]
  {::formatted (clojure.string/upper-case raw-name)})

```

## MVP Test Scenarios

1. **Channel adapter** — TCP round-trip with EDN messages
2. **Message schemas** — generate, validate, serialize round-trip
3. **Bridge step-fn** — mock channels, request → fn → reply
4. **Namespace step-fn** — request → TCP → bridge → fn → reply
5. **Happy path end-to-end** — alpha→beta through topology, blocking, correct result
6. **Overload** — queue cap 32, send 33rd request → typed `:overload` error returned quickly
7. **Timeout** — slow function exceeds timeout → typed `:timeout` error
8. **Error propagation** — beta throws → alpha gets exception + error event emitted
9. **`*ctx*` persistence** — set, persist, restart, verify. Non-serializable → warn + recover
10. **Pause/resume/stop** — correctness on both orchestrator and agent JVM sides

## Files to Create

| File | Purpose |
|------|---------|
| `src/seon/flow/msg.clj` | Message envelope schemas (source of truth) |
| `src/seon/flow/harness.clj` | Namespace-step, ctx persist/load, lifecycle |
| `src/seon/flow/harness/bridge.clj` | Agent JVM bridge step-fn |
| `src/seon/flow/harness/channel.clj` | TCP ↔ core.async adapter |
| `src/seon/flow/topology.clj` | Flow graph wiring + reply router |
| Tests mirror src structure | One test file per source file |

## Existing Code to Reuse

| Code | File |
|------|------|
| `pool/acquire!!`, `release!`, `nrepl-eval!` | `src/seon/flow/pool.clj` |
| `conn/get-namespace-conn!` | `src/seon/db/datalevin/conn.clj` |
| `query/dependencies-of` | `src/seon/graph/query.clj` |
| SSE flow step-fn patterns | `src/seon/web/sse/flow.clj` |
| `flow/create-flow`, `process`, `inject` | core.async.flow (`reference-code/`) |

## Verification

1. `./bin/run` — start server
2. REPL: test channel adapter directly
3. REPL: `build-topology!` with test namespaces
4. REPL: `request!` — cross-ns call, verify result
5. REPL: test overload by flooding requests
6. Tests: `clojure -M:test -m kaocha.runner --focus seon.flow.harness-test`
