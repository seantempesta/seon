# PRD: Unified Runtime Architecture

**Status:** Draft (research complete, ready for implementation)
**Priority:** High (next major architecture evolution)
**Author:** Deep research -- no code changes

---

## The Insight

Every namespace is a namespace instance. The main system is just another set of namespace instances. Agents connect to namespace instances. External processes are namespace instances too.

**Turtles all the way down.**

This is not a new abstraction to add on top of what exists. The pieces already exist:
- `seon.ctx` creates per-namespace atoms backed by Datalevin
- `seon.flow.pool` manages external JVM processes with nREPL
- `seon.flow.topology` wires namespace step-fns into data-processing flows
- `seon.flow.harness` routes cross-namespace calls via TCP bridges
- `seon.graph.ingest` tracks what code exists in Datalevin
- Integrant manages component lifecycle

The gap: these are separate systems with separate state management. A namespace instance should be one thing, whether it runs in-process or in an external JVM.

---

## The Unified Model

### What is a Namespace Instance?

A namespace instance is the runtime representation of a Clojure namespace. It has:

1. **Identity** -- a namespace string (e.g. `"seon.trading.signals"`)
2. **State** -- a ctx atom (from `seon.ctx`), optionally persisted to Datalevin
3. **Code** -- the functions defined in the namespace (tracked in the code graph)
4. **Location** -- in-process (main JVM) or external (pool JVM)
5. **Connections** -- how other namespace instances reach this one (direct call, flow channel, or TCP bridge)

The key realization: **we already have all of this**. The `seon.ctx` registry maps instance-id to `{:atom, :conn, :namespace, ...}`. The flow topology maps process-ids to namespace step-fns. The pool maps session-ids to JVM handles.

What's missing is the unifying layer that treats them uniformly.

### System Components as Namespace Instances

Today's Integrant components map naturally:

| Component | Namespace Instance | Location | State |
|-----------|-------------------|----------|-------|
| Datalevin server | `seon.db.datalevin.server` | in-process | port, root, connections |
| HTTP server | `seon.web.server` | in-process | port, routes, middleware |
| nREPL server | `seon.nrepl` | in-process | port, handler, sessions |
| Agent pool | `seon.flow.pool` | in-process | idle-queue, active JVMs |
| Code scanner | `seon.graph.scanner` | in-process | graph conn, paths |
| Agent session | `seon.trading.signals` (example) | external JVM | ctx atom, nREPL port |

The system components are namespace instances that happen to run in the main JVM. Agent sessions are namespace instances that happen to run in pool JVMs. The only difference is location.

### What Changes

**Not much.** The existing systems stay. We add a thin registry layer and Datalevin persistence.

1. **`seon.runtime` namespace** (new) -- the unified registry
   - Every namespace instance registers here on creation
   - Queryable: "what's running?" "what state does X have?" "where is X?"
   - Persists to Datalevin for crash recovery

2. **Integrant init-key writes to runtime registry** -- when a component starts, it registers as a namespace instance
3. **Pool claim writes to runtime registry** -- when an agent session starts, it registers
4. **ctx watches write state changes to Datalevin** -- already happens via `seon.ctx` debounced persistence

### What Does NOT Change

- core.async.flow topology and step-fns stay exactly as they are
- Pool JVM lifecycle stays exactly as it is
- TCP bridge stays exactly as it is
- Integrant component lifecycle stays exactly as it is
- ctx atom API stays exactly as it is

---

## How Flows Map to Namespace Instances

A flow is data moving through namespace instances. The topology is defined by:
1. **The function call graph** (static, from `seon.graph.ingest`) -- which namespaces depend on which
2. **Runtime connections** (dynamic, from `seon.flow.topology`) -- which namespace steps are wired together

Today, `build-topology!` takes a map of namespace-string to config and creates flow processes. Each process runs a `namespace-step` that routes requests to an agent JVM via TCP.

In the unified model, a flow process IS a namespace instance. The harness (`namespace-step`) is the bridge between the flow infrastructure and the namespace instance's location:

```
Caller namespace instance
  -> flow channel (core.async)
    -> namespace-step (harness)
      -> TCP bridge (if external JVM)
        -> agent JVM executes function
      -> or direct call (if in-process)
        -> resolve and invoke function
```

The key insight: **the harness already abstracts location**. The caller doesn't know whether the target is in-process or external. We just need to make in-process namespace instances available to the same routing, which is trivial -- the harness can resolve locally instead of routing over TCP.

### In-Process Namespace Step (new, simple)

For system components that run in the main JVM, the namespace step becomes trivial:

```clojure
(defn in-process-step
  "Step-fn for namespaces that run in the main JVM.
   Resolves and calls functions directly instead of routing via TCP."
  ([] {:ins {:seon.flow.in/request "Cross-namespace call requests"}
       :outs {:seon.flow.out/reply "Responses"
              :seon.flow.out/event "Observability"}
       :workload :compute})
  ([{::keys [namespace]}]
   {::namespace namespace})
  ([state transition] state)
  ([state input-id msg]
   (case input-id
     :seon.flow.in/request
     (let [ns-str (::namespace state)
           fn-sym (symbol ns-str (::msg/fn msg))
           f (resolve fn-sym)
           result (apply f (::msg/args msg))]
       [state {:seon.flow.out/reply [{::msg/id (::msg/id msg)
                                       ::msg/status :ok
                                       ::msg/value result}]}])
     [state nil])))
```

This means the orchestrator can route calls to any namespace instance -- in-process or external -- through the same flow topology. An agent calling `(seon.db/q ...)` routes through the flow to the in-process DB namespace instance. An agent calling `(seon.trading.signals/ema ...)` routes to an external JVM.

---

## Startup and Restoration

### Two Modes

**Fresh start** (empty or new Datalevin):
1. Load Integrant config from `system.edn`
2. Start components in dependency order (Integrant handles this)
3. Each component registers as namespace instance in runtime registry
4. Code scanner populates graph
5. System is live

**Resume** (existing Datalevin with state):
1. Load Integrant config from `system.edn`
2. Start components in dependency order
3. Each component registers as namespace instance
4. **Hydrate**: read previous runtime state from Datalevin
   - Mark any instances that were `:running` as `:crashed` (dirty shutdown)
   - Restore ctx atom values from persisted data
   - Log what was running before crash for operator awareness
5. Code scanner updates graph (code may have changed)
6. System is live with recovered state

### How the Function Call Graph Determines Restoration Order

The code graph (`seon.graph.query/dependencies-of`) gives us a topological sort of namespace dependencies. When restoring:

1. Query all namespace instances from Datalevin
2. Build dependency graph from `seon.ns.dep/from-ns` / `seon.ns.dep/to-ns`
3. Topological sort: restore leaf namespaces first, dependents after
4. External processes can start in parallel if they have no dependencies on each other

This is not needed for Phase 1 (Integrant already handles dependency order for components). It becomes important when we want to auto-restart external agent sessions after a crash.

---

## What Gets Persisted and When

**Major events only.** Not intermediate flow data. Not every atom swap.

| Event | When | How |
|-------|------|-----|
| Namespace instance created | Component init or pool claim | Immediate upsert |
| Namespace instance destroyed | Component halt or session release | Immediate upsert (status -> :stopped) |
| Ctx state change | Atom watch fires | Debounced (100ms default, existing) |
| Flow snapshot | Graceful shutdown, backup, manual | Explicit: pause -> ping -> serialize -> write |
| Crash detection | On startup, find `:running` instances | Immediate update to `:crashed` |
| Write stats | Periodic timer | Every 30 seconds |

### The Serialization Boundary

**Can be serialized to Datalevin** (and round-trips through EDN):
- Ctx atom values (maps of keyword -> primitive/collection)
- Status keywords, timestamps, counts
- Namespace strings, session IDs
- Flow process names and message counts

**Cannot be serialized** (reconstructed on startup):
- `flow` objects (core.async.flow handles)
- core.async channels
- `java.lang.Process` handles
- `ScheduledExecutorService` instances
- Datalevin connections
- nREPL client/server objects
- Atoms (the container, not the value)

The pattern: persist the *value*, reconstruct the *container*.

---

## Agent Session Model

An agent connects to a namespace instance. The API:

```clojure
;; Start a session -- creates a namespace instance
(runtime/start-session!
  {::runtime/namespace 'seon.trading.signals
   ::runtime/session-id "a1b2"        ;; or auto-generated
   ::runtime/location :external       ;; or :in-process
   ::runtime/initial-ctx {...}})
;; => {::runtime/session-id "a1b2"
;;     ::runtime/nrepl-port 7901
;;     ::runtime/namespace "seon.trading.signals"
;;     ::runtime/status :running}

;; Eval code in the session (MCP does this)
(runtime/eval! {::runtime/session-id "a1b2"
                ::runtime/code "(+ 1 2)"})
;; => "3"

;; Get session state
(runtime/instance {::runtime/namespace "seon.trading.signals"})
;; => {::runtime/status :running
;;     ::runtime/location :external
;;     ::runtime/nrepl-port 7901
;;     ::runtime/ctx {...}
;;     ::runtime/started-at #inst "..."}

;; List all instances
(runtime/instances)
;; => [{::runtime/namespace "seon.db.datalevin.server"
;;      ::runtime/location :in-process
;;      ::runtime/status :running}
;;     {::runtime/namespace "seon.trading.signals"
;;      ::runtime/location :external
;;      ::runtime/session-id "a1b2"
;;      ::runtime/status :running}
;;     ...]
```

Whether the instance is in-process or external is transparent to the caller. The session-id is the key for external sessions. The namespace string is the universal key for any instance.

---

## Datalevin Schema

This extends the existing code graph schema with runtime state. All in the master database (`seon-graph`).

```clojure
(def runtime-schema
  {;; =================================================================
   ;; NAMESPACE INSTANCE
   ;; The central entity. One per running namespace.
   ;; Joins to code graph via :seon.ns/name string match.
   ;; =================================================================

   :seon.runtime/namespace
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; e.g. "seon.trading.signals", "seon.db.datalevin.server"
   ;; Unique identity enables upsert.

   :seon.runtime/status
   {:db/valueType :db.type/keyword}
   ;; :running, :stopped, :crashed, :paused

   :seon.runtime/location
   {:db/valueType :db.type/keyword}
   ;; :in-process, :external

   :seon.runtime/session-id
   {:db/valueType :db.type/string}
   ;; 4-char hex, only for external sessions. Nil for in-process.

   :seon.runtime/nrepl-port
   {:db/valueType :db.type/long}
   ;; nREPL port (external JVMs only)

   :seon.runtime/started-at
   {:db/valueType :db.type/instant}

   :seon.runtime/stopped-at
   {:db/valueType :db.type/instant}

   :seon.runtime/component-key
   {:db/valueType :db.type/keyword}
   ;; Integrant component key, e.g. :seon/datalevin-server
   ;; Only for in-process instances created by Integrant.

   ;; =================================================================
   ;; CTX PERSISTENCE (unified, replaces both seon.ctx and harness ctx)
   ;; =================================================================

   ;; seon.ctx/datalevin-schema already defines these:
   ;; :seon.ctx/instance-id  (string, unique identity)
   ;; :seon.ctx/namespace    (string)
   ;; :seon.ctx/data         (string, EDN blob)
   ;; :seon.ctx/updated-at   (instant)
   ;;
   ;; We add a link to the runtime instance:

   :seon.ctx/runtime
   {:db/valueType :db.type/ref}
   ;; Ref to :seon.runtime/namespace entity.
   ;; Links a ctx snapshot to the namespace instance that owns it.

   ;; =================================================================
   ;; FLOW TOPOLOGY SNAPSHOTS
   ;; Point-in-time capture of all process states.
   ;; =================================================================

   :seon.flow.snap/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; Composite: "flow-label/ISO-timestamp"

   :seon.flow.snap/label
   {:db/valueType :db.type/string}
   ;; Which topology this snapshot belongs to

   :seon.flow.snap/created-at
   {:db/valueType :db.type/instant}

   :seon.flow.snap/reason
   {:db/valueType :db.type/keyword}
   ;; :shutdown, :backup, :manual, :error

   :seon.flow.snap/data
   {:db/valueType :db.type/string}
   ;; EDN blob: {process-keyword -> process-state-map}

   ;; =================================================================
   ;; AGENT RUNS (extends existing seon.ai persistence)
   ;; Links an AI agent invocation to a namespace instance.
   ;; =================================================================

   :seon.agent.run/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; Same as session-id for Seon agents

   :seon.agent.run/runtime
   {:db/valueType :db.type/ref}
   ;; Ref to :seon.runtime/namespace entity

   :seon.agent.run/provider
   {:db/valueType :db.type/keyword}
   ;; :claude, :gemini

   :seon.agent.run/status
   {:db/valueType :db.type/keyword}
   ;; :running, :completed, :failed, :interrupted

   :seon.agent.run/started-at
   {:db/valueType :db.type/instant}

   :seon.agent.run/stopped-at
   {:db/valueType :db.type/instant}

   :seon.agent.run/cost-usd
   {:db/valueType :db.type/double}

   :seon.agent.run/num-turns
   {:db/valueType :db.type/long}

   :seon.agent.run/duration-ms
   {:db/valueType :db.type/long}

   ;; =================================================================
   ;; WRITE STATS (singleton)
   ;; =================================================================

   :seon.write-stats/id
   {:db/valueType :db.type/keyword
    :db/unique    :db.unique/identity}
   ;; Always :seon/write-stats

   :seon.write-stats/total-writes
   {:db/valueType :db.type/long}

   :seon.write-stats/last-write-at
   {:db/valueType :db.type/instant}

   :seon.write-stats/by-caller
   {:db/valueType :db.type/string}
   ;; EDN blob: {caller-ns-string count}
   })
```

### Design Decisions

**Why `:seon.runtime/namespace` as identity instead of a separate `:seon.runtime/id`?**

The namespace string IS the identity. There's exactly one runtime instance per namespace. Using the namespace as identity means:
- Upsert is natural (re-registering same namespace updates it)
- Joins to code graph are direct (same string)
- No need for a synthetic ID

**Why NOT separate flow/session/agent entity types?**

The original PRD draft had `:seon.flow/id`, `:seon.session/id`, `:seon.agent/id` as separate entities. But these are all aspects of the same thing -- a namespace instance. A session IS a namespace instance with location `:external`. A flow process IS a namespace instance participating in a topology. Collapsing them into one entity type is the "turtles all the way down" insight.

Agent runs are still separate entities because one namespace instance can have many agent runs over its lifetime (it's a one-to-many relationship).

**Why keep flow snapshots as separate entities?**

Snapshots are temporal -- multiple snapshots for the same topology over time. They can't be collapsed into the namespace instance entity. They're a history log.

**Why string join to code graph instead of ref?**

Same reasoning as original PRD: the code graph must exist before runtime entities for refs to work. String joins decouple write ordering.

---

## Example Queries

**"What's currently running?"**

```clojure
(d/q '[:find ?ns ?status ?location
        :where
        [?e :seon.runtime/namespace ?ns]
        [?e :seon.runtime/status :running]
        [?e :seon.runtime/location ?location]]
      @conn)
;; => #{["seon.db.datalevin.server" :running :in-process]
;;      ["seon.web.server" :running :in-process]
;;      ["seon.trading.signals" :running :external]}
```

**"What was running before the crash?"**

```clojure
(d/q '[:find ?ns ?location ?started
        :where
        [?e :seon.runtime/namespace ?ns]
        [?e :seon.runtime/status :crashed]
        [?e :seon.runtime/location ?location]
        [?e :seon.runtime/started-at ?started]]
      @conn)
```

**"Navigate from function to its runtime context"**

```clojure
(let [ns-name (d/q '[:find ?ns .
                      :in $ ?fn
                      :where
                      [?e :seon.fn/qualified-name ?fn]
                      [?e :seon.fn/namespace ?ns]]
                    @conn "seon.trading.signals/ema")
      runtime (d/q '[:find (pull ?r [*]) .
                       :in $ ?ns
                       :where [?r :seon.runtime/namespace ?ns]]
                     @conn ns-name)
      ctx-data (d/q '[:find ?data .
                       :in $ ?ns
                       :where
                       [?c :seon.ctx/namespace ?ns]
                       [?c :seon.ctx/data ?data]]
                     @conn ns-name)]
  {:function "seon.trading.signals/ema"
   :runtime runtime
   :ctx (when ctx-data (edn/read-string ctx-data))})
```

**"Show agent runs for a namespace with costs"**

```clojure
(d/q '[:find ?run-id ?status ?cost ?started
        :in $ ?ns
        :where
        [?r :seon.runtime/namespace ?ns]
        [?a :seon.agent.run/runtime ?r]
        [?a :seon.agent.run/id ?run-id]
        [?a :seon.agent.run/status ?status]
        [?a :seon.agent.run/cost-usd ?cost]
        [?a :seon.agent.run/started-at ?started]]
      @conn "seon.trading.signals")
```

---

## The Atom Inventory (Revised)

The original PRD identified 27 atoms. Here's how they map to the unified model:

### Atoms that become Datalevin-backed (persist via runtime registry)

| Atom | Current File | Unified As |
|------|-------------|------------|
| `*registry` | `seon.flow.registry` | `:seon.runtime/*` entities (flow-id becomes label on instances) |
| `session-registry` | `seon.orchestrator.session` | `:seon.runtime/*` entities with `:external` location |
| `agent-registry` | `seon.ai.agent` | `:seon.agent.run/*` entities |
| `registry` | `seon.ctx` | Already persists via `:seon.ctx/*` schema |
| `write-stats` | `seon.db` | `:seon.write-stats/*` entity |

### Atoms that stay as atoms (ephemeral, reconstructable)

All other atoms stay as-is. They're caches, buffers, or runtime handles.

### Net effect: 5 fewer global atoms, all runtime state queryable in Datalevin

---

## Migration: What Gets Replaced

### `seon.flow.registry` -- absorbed into runtime registry

The flow registry maps flow-id to `{flow, chans, label, started-at}`. The runtime registry captures the same info:
- `label` -> on each namespace instance in the topology
- `started-at` -> `:seon.runtime/started-at`
- `flow` and `chans` -> stay in-memory (opaque handles, not serializable)

The registry becomes a query over runtime instances rather than a separate atom.

### `seon.orchestrator.session` -- simplified

Today: maintains `session-registry` atom AND writes to its own Datalevin namespace DB.
After: writes to master DB via runtime registry. The atom becomes a cache.

### `seon.ai.agent` -- agent runs become entities

Today: `agent-registry` atom tracks running agents.
After: `:seon.agent.run/*` entities in Datalevin. The atom becomes a cache.

---

## Phased Implementation Plan

### Phase 1: Runtime Registry + Schema (1 agent, ~5 files)

**Goal:** Create `seon.runtime` namespace with the unified registry. Register existing Integrant components as namespace instances.

1. Create `src/seon/runtime.clj`:
   - `runtime-schema` (the Datalevin schema above)
   - `register!` -- upsert a namespace instance in Datalevin + in-memory cache
   - `unregister!` -- update status to `:stopped`
   - `instance` -- query a single instance by namespace
   - `instances` -- list all instances
   - `mark-crashed!` -- on startup, find `:running` instances and mark `:crashed`

2. Merge `runtime-schema` into master DB schema at connection creation time (alongside `seon.graph.ingest/datalevin-schema`).

3. Add `register!` calls to Integrant `init-key` methods:
   - `:seon/datalevin-server` registers `"seon.db.datalevin.server"`
   - `:seon.web.server/http-server` registers `"seon.web.server"`
   - etc.

4. On startup, call `mark-crashed!` before registering new instances.

5. Tests: Start system, verify instances in Datalevin. Stop, restart, verify crashed marking.

**Files:** `src/seon/runtime.clj` (new), `src/seon/system.clj` (add register calls), `src/seon/graph/ingest.clj` (merge schema).

### Phase 2: Session Integration (1 agent, ~4 files)

**Goal:** Agent sessions write to the runtime registry instead of separate persistence.

1. `seon.orchestrator.session/start-agent-session!` calls `runtime/register!` with `:external` location.
2. `seon.orchestrator.session/stop-agent-session!` calls `runtime/unregister!`.
3. Remove `orch.session/*` schema and separate DB writes.
4. The `session-registry` atom becomes a cache over runtime instances.

**Files:** `src/seon/orchestrator/session.clj`, `src/seon/runtime.clj` (add session helpers).

### Phase 3: Agent Run Entities (1 agent, ~3 files)

**Goal:** Agent runs persist as `:seon.agent.run/*` entities linked to runtime instances.

1. `seon.ai.claude/launch-agent!` writes `:seon.agent.run/*` entity on launch.
2. Agent completion handler updates status, cost, duration.
3. The `agent-registry` atom becomes a cache.

**Files:** `src/seon/ai/claude.clj`, `src/seon/ai/agent.clj`, `src/seon/runtime.clj`.

### Phase 4: Flow Snapshots (1 agent, ~3 files)

**Goal:** Snapshot flow process states on shutdown and backup.

1. `seon.runtime/snapshot!`:
   - Find all namespace instances in a topology
   - Pause flow, ping (sync barrier), serialize states
   - Write `:seon.flow.snap/*` entity
   - Resume flow

2. Wire into `seon.flow.topology/stop-topology!` -- snapshot before stop.
3. Wire into `seon.db.datalevin.backup` -- snapshot before backup.

**Files:** `src/seon/runtime.clj`, `src/seon/flow/topology.clj`, `src/seon/db/datalevin/backup.clj`.

### Phase 5: Ctx Unification (1 agent, ~3 files)

**Goal:** One ctx persistence path, linked to runtime instances.

1. Add `:seon.ctx/runtime` ref to ctx persistence (links ctx to its namespace instance).
2. Migrate `seon.flow.harness/persist-ctx!` to use `seon.ctx/*` schema.
3. Delete `ctx/*` attributes from harness.
4. Verify both in-process and external ctx round-trip through unified schema.

**Files:** `src/seon/ctx.clj`, `src/seon/flow/harness.clj`, `src/seon/runtime.clj`.

### Phase 6: Startup Hydration (1 agent, ~3 files)

**Goal:** On startup, hydrate atom caches from Datalevin.

1. `seon.runtime/hydrate!`:
   - Read all runtime instances from Datalevin
   - Mark `:running` -> `:crashed`
   - Populate in-memory caches (session-registry, agent-registry)
   - Restore ctx atom values

2. Wire into `seon.core/start-app` -- call before Integrant go.

**Files:** `src/seon/runtime.clj`, `src/seon/core.clj`.

### Phase 7: Flow Registry Absorption (1 agent, ~4 files)

**Goal:** Replace `seon.flow.registry` with queries over runtime instances.

1. `seon.flow.registry/register!` -> `seon.runtime/register!` with additional flow metadata.
2. `seon.flow.registry/list-flows` -> `seon.runtime/instances` filtered by topology participation.
3. Update `seon.flow.topology`, `seon.flow.status` to use runtime queries.
4. Delete `seon.flow.registry` namespace.

**Files:** `src/seon/flow/topology.clj`, `src/seon/flow/status.clj`, `src/seon/flow/registry.clj` (delete), `src/seon/runtime.clj`.

### Phase 8: Observatory + Flows Page (1-2 agents, ~3 files)

**Goal:** UI shows runtime instances from Datalevin, with code graph enrichment.

1. Flows page shows all runtime instances (running + historical).
2. Each instance shows function count, spec count, ctx state, agent runs.
3. Historical instances (stopped/crashed) appear with timestamps.

**Files:** `src/seon/web/flows.clj`, `src/seon/web/agents.clj`, `src/seon/runtime.clj` (add query helpers).

---

## Key Design Questions Answered

### Can we run flows in-process AND have agents connect to them the same way?

Yes. The `namespace-step` in the harness abstracts location. For in-process namespaces, the step resolves and calls functions directly (no TCP). For external namespaces, it routes via TCP bridge. The caller uses `topology/request!` regardless.

### What's the minimal change to make Integrant components visible as namespace instances?

Add a `runtime/register!` call to each `ig/init-key` method. That's literally it -- one line per component. The runtime registry handles persistence.

### How does the function call graph determine restoration order?

`seon.graph.query/dependencies-of` gives us the dependency chain. Topological sort of `seon.ns.dep/*` edges gives restoration order. Leaf namespaces (no dependencies) can restore in parallel.

### What's the serialization boundary?

Values are serializable (maps, keywords, strings, numbers, timestamps). Containers and handles are not (atoms, channels, processes, connections). The pattern: persist the value, reconstruct the container.

### How do we handle the transition?

Incrementally. Each phase is independently buildable and testable. The existing systems keep working throughout. Phase 1 adds the registry without changing anything else. Subsequent phases migrate consumers one at a time.

---

## Non-Goals

- **NOT tracking intermediate flow data** -- too expensive. Major events only.
- **NOT replacing core.async.flow** -- we build on top of it.
- **NOT automatic restart from crash** -- snapshots enable manual recovery. Auto-restart is future work.
- **NOT full event sourcing** -- point-in-time snapshots, not a log of every change.
- **NOT migrating the schema registry atom** -- `seon.schema/*schemas` is populated from source code at load time, not runtime state.
- **NOT running flows inside the system runtime differently** -- flows ARE namespace instances. The system harness IS the flow infrastructure.

---

## Success Criteria

1. After `pkill -9`, restart. Datalevin shows which namespace instances were running (marked `:crashed`), which agent sessions were active, and the last snapshot of flow state.

2. From the REPL:
   ```clojure
   (runtime/instances)
   ;; => [{:seon.runtime/namespace "seon.db.datalevin.server"
   ;;      :seon.runtime/status :running
   ;;      :seon.runtime/location :in-process} ...]
   ```

3. Navigate from function to runtime:
   ```clojure
   (runtime/instance {::runtime/namespace "seon.trading.signals"})
   ;; => {:status :running, :location :external, :nrepl-port 7901,
   ;;     :ctx {:last-signal {...}},
   ;;     :agent-runs [{:id "a1b2" :status :completed :cost 0.23}],
   ;;     :fn-count 12, :spec-count 8}
   ```

4. Only one ctx persistence schema exists (`seon.ctx/*`). The old `ctx/*` attributes are gone.

5. All existing tests pass. This is additive, not a rewrite.

6. The flows page shows historical data with code graph enrichment.

---

## Instance Identification and Inter-Instance Messaging

### Current State (Research, 2026-02-21)

#### Four ID Systems Exist

| System | Generator | Format | Storage | Example |
|--------|-----------|--------|---------|---------|
| `seon.ctx/generate-id` | 2 random bytes, hex | 4-char hex string | In-memory atom + Datalevin | `"a13b"` |
| `seon.orchestrator.session/generate-session-id` | Identical to above | 4-char hex string | In-memory atom + Datalevin | `"d4e5"` |
| `seon.ai/start-session!` | `"ses-" + UUID` | UUID with prefix | Datalevin | `"ses-550e8400-..."` |
| Claude SDK | Internal | UUID | Process stdout | `"sess_..."` |

The ctx generator and session generator are **duplicated code** -- identical algorithms in two files.

#### ID Flow Through Agent Launch

```
launch-agent!
  -> session/start-agent-session!
       generates 4-char hex "a1b2"          <-- PRIMARY ID
       -> ctx/create! (instance-id = "a1b2")
       -> pool/claim! (session-id = "a1b2")
  -> ai/start-session!
       generates "ses-UUID..."              <-- SEPARATE AI ID
       stores ::ai/agent-session-id "a1b2"  <-- links back
  -> agent-registry stores both IDs
  -> Claude SDK generates its own session UUID
```

A single agent has **three IDs**: the 4-char hex (infrastructure), the AI session UUID (conversation persistence), and Claude's own session UUID (SDK internal).

#### Current Communication

| Channel | Mechanism | Addressing | Reply Routing |
|---------|-----------|------------|---------------|
| Agent -> System | MCP tool calls via `bin/mcp-server` | `SEON_SESSION_ID` env var -> nREPL port lookup | Synchronous (request/response) |
| Cross-namespace (flow) | `seon.flow.msg` envelopes via TCP bridge | `::msg/from-ns` / `::msg/to-ns` (namespace strings) | `::msg/id` UUID -> promise map |
| Observation | `agent/tail` returns messages channel | `::agent/session-id` (4-char hex) | N/A (read-only) |
| Agent -> Agent | **Does not exist** | -- | -- |

#### Collision Risk

4-char hex = 65,536 values. No collision check exists. With 3-5 concurrent agents, birthday paradox probability is ~0.01% -- negligible but unguarded.

### Recommendations

#### 1. Unified ID: Keep 4-char hex, consolidate generators

The 4-char hex works well. It's human-readable, compact, and already used consistently across ctx, sessions, pool, and agent registry.

Changes:
- **Single generator** in `seon.runtime/generate-id` (delete duplicates in `seon.ctx` and `seon.orchestrator.session`)
- **Collision check** against runtime registry before assignment
- **Drop AI session UUID prefix** -- use the same 4-char hex as AI session primary key (current 1:1 mapping makes the separate UUID unnecessary)

#### 2. Instance-Addressed Messaging

Extend `seon.flow.msg` envelope with instance-ID addressing:

```clojure
;; New fields (optional, alongside existing ::msg/from-ns / ::msg/to-ns)
::msg/from-id  ;; 4-char hex instance ID of sender
::msg/to-id    ;; 4-char hex instance ID of target
```

This enables "send to instance a1b2" in addition to "send to namespace seon.trading".

#### 3. Message Router in `seon.runtime`

Generalize the bridge's promise-based reply pattern:

```clojure
(runtime/send! {::runtime/to-id "a1b2"
                ::msg/fn "seon.trading.signals/ema"
                ::msg/args [[1.0 2.0 3.0]]})
;; => promise that delivers the reply

;; Or by namespace (routes to the running instance of that namespace):
(runtime/send! {::msg/to-ns "seon.trading.signals"
                ::msg/fn "seon.trading.signals/ema"
                ::msg/args [[1.0 2.0 3.0]]})
```

The router:
1. Looks up target in runtime registry (by ID or namespace)
2. In-process target: resolve and call directly
3. External target: route via TCP bridge (existing infrastructure)
4. Returns promise/channel for reply (using `::msg/id` correlation)

#### 4. What Can Be Reused

- **`seon.flow.msg` envelope schema** -- already has request/reply/event types, trace-id, error handling
- **`seon.flow.harness.bridge/pending-remote-promises`** -- the promise-per-request-id pattern is exactly right
- **`seon.flow.harness.bridge/execute-local`** -- function resolution and execution with error handling
- **TCP bridge** -- for external JVM communication, already works

What's new is the **routing layer** that sits between callers and the bridge/direct-call decision.
