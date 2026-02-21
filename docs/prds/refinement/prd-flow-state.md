# PRD: Unified Flow State in Datalevin

**Status:** Draft
**Priority:** Medium (after refinement PRD completes)
**Author:** Research — no code changes

---

## Problem Statement

Seon has four independent state-tracking systems that don't talk to each other:

1. **Code graph** (`seon.graph.*`) — Static analysis in Datalevin. Knows namespaces, functions, schemas, dependencies. Updated at startup and incrementally by the dev hook. This is the "what exists" layer.

2. **Flow registry** (`seon.flow.registry`) — In-memory atom (`*registry`) mapping flow-id to `{flow, chans, label, started-at}`. Knows which flows are running. No persistence. Lost on restart.

3. **Flow status** (`seon.flow.status`) — Collects live metrics via `flow/ping` on demand. Throughput rates, error counts, process states. Ephemeral — computed fresh each time, previous counts tracked in atoms for delta calculation.

4. **Ctx instances** (`seon.ctx`) — Per-instance atoms with optional debounced persistence to Datalevin. Each instance lives in an in-memory registry. The Datalevin persistence stores serialized EDN blobs keyed by instance-id.

The flows page (`seon.web.flows`) queries the registry and status systems to render. The agents page queries Datalevin (for messages/sessions) and the agent registry (for running state). There is no unified model connecting "this namespace has these functions" (graph) to "this namespace has a running flow process" (registry) to "this is the current state of that process" (ctx/status).

**Consequences:**
- Cannot answer "what was the state of namespace X at time T?" — ctx atoms are ephemeral, persistence is fire-and-forget blobs
- Cannot answer "which running flows use functions from namespace Y?" — registry has no link to code graph
- Restart loses all runtime state — registry atoms reset, status counters vanish
- Cannot snapshot the whole system, inspect it, then restore — the pieces don't compose
- The flows page shows live ping data but has no historical view

---

## Proposed Solution

Make Datalevin the canonical store for flow runtime state, connecting it to the existing code graph. The in-memory atoms remain the hot path for real-time operations, but Datalevin becomes the durable layer that enables snapshot, restore, and query.

### Key Design Decisions

**NOT real-time streaming.** Every ctx update does not go to Datalevin. That would be too expensive and would fight LMDB's write model. Instead:

- **Pause-and-snapshot**: Pause a flow, write its full state to Datalevin, resume. This reuses the existing `flow/pause` + `flow/ping` pattern from `seon.db.datalevin.writer`.
- **On-demand persist**: Explicit `snapshot!` call writes current state. The debounced ctx persistence already works this way.
- **Restore from snapshot**: On startup or on demand, load the last snapshot and hydrate in-memory atoms.

**Connected to code graph.** Flow instances link to `seon.ns/name` entities already in Datalevin. A flow process managing namespace "seon.trading.signals" references the same `:seon.ns/name "seon.trading.signals"` entity that the code scanner created. This means you can query: "show me all functions in this namespace AND its current runtime state."

---

## What Exists Today (Inventory)

### Code Graph (Datalevin — already works)

Schema from `seon.graph.ingest/datalevin-schema`:
```clojure
:seon.ns/name       {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.ns/doc        {:db/valueType :db.type/string}
:seon.ns/file       {:db/valueType :db.type/string}
:seon.fn/qualified-name {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.fn/namespace      {:db/valueType :db.type/string}
:seon.fn/name           {:db/valueType :db.type/string}
;; ... plus call graph, ns deps, specs
```

Populated by `seon.graph.analyzer` + `seon.graph.ingest` at startup, updated incrementally by the dev hook.

### Flow Registry (in-memory atom — lost on restart)

```clojure
;; seon.flow.registry/*registry
;; {flow-id -> {::id, ::flow, ::chans, ::label, ::started-at}}
```

Registered by `seon.flow.topology/build-topology!`. No persistence.

### Flow Status (computed on demand — ephemeral)

```clojure
;; seon.flow.status collects via flow/ping:
;; Per process: status (:running/:paused), count, msgs-per-sec, state-summary
;; Per flow: uptime-ms, errors (sliding window of 100)
```

`*prev-counts` and `*errors` atoms track deltas and error history. Lost on restart.

### Ctx System (atoms + optional Datalevin blobs)

```clojure
;; seon.ctx/registry (in-memory)
;; instance-id -> {:atom, :conn, :namespace, :persist?, ...}

;; Datalevin schema for ctx persistence:
:seon.ctx/instance-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.ctx/namespace   {:db/valueType :db.type/string}
:seon.ctx/data        {:db/valueType :db.type/string}  ;; EDN blob
:seon.ctx/updated-at  {:db/valueType :db.type/instant}
```

### Harness Ctx Persistence (separate from seon.ctx)

```clojure
;; seon.flow.harness has its own persist-ctx!/load-ctx! using:
:ctx/namespace  {:db/valueType :db.type/string}
:ctx/data       {:db/valueType :db.type/string}
:ctx/updated-at {:db/valueType :db.type/instant}
```

Note: Two different ctx persistence schemas exist (`seon.ctx/datalevin-schema` and harness's `ctx/*` attrs). These should be unified.

---

## Design

### Datalevin Schema for Flow State

New entity types, added to the master database alongside the existing graph schema:

```clojure
(def flow-state-schema
  {;; Flow instance — a running (or previously running) flow
   :seon.flow/id          {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :seon.flow/label       {:db/valueType :db.type/string}
   :seon.flow/status      {:db/valueType :db.type/keyword}  ;; :running, :paused, :stopped
   :seon.flow/started-at  {:db/valueType :db.type/instant}
   :seon.flow/stopped-at  {:db/valueType :db.type/instant}

   ;; Flow process — one per step-fn in a flow
   :seon.flow.proc/id     {:db/valueType :db.type/string :db/unique :db.unique/identity}
   ;; Composite: "flow-id/process-name"
   :seon.flow.proc/flow   {:db/valueType :db.type/ref}     ;; ref to :seon.flow/id entity
   :seon.flow.proc/name   {:db/valueType :db.type/keyword}  ;; e.g. :ns/seon.trading.signals
   :seon.flow.proc/ns     {:db/valueType :db.type/string}   ;; namespace managed (if namespace-step)
   :seon.flow.proc/status {:db/valueType :db.type/keyword}  ;; :running, :paused

   ;; Snapshot — point-in-time capture of a flow's full state
   :seon.flow.snap/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   ;; Composite: "flow-id/timestamp"
   :seon.flow.snap/flow       {:db/valueType :db.type/ref}
   :seon.flow.snap/created-at {:db/valueType :db.type/instant}
   :seon.flow.snap/reason     {:db/valueType :db.type/keyword}  ;; :manual, :shutdown, :backup
   :seon.flow.snap/data       {:db/valueType :db.type/string}   ;; EDN blob of all process states

   ;; Ctx state (unified — replaces both seon.ctx and harness ctx persistence)
   :seon.ctx/id           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.ctx/namespace    {:db/valueType :db.type/string}
   :seon.ctx/flow-proc    {:db/valueType :db.type/ref}     ;; optional ref to flow process
   :seon.ctx/data         {:db/valueType :db.type/string}   ;; EDN blob
   :seon.ctx/updated-at   {:db/valueType :db.type/instant}})
```

### Connecting to Code Graph

The key join is `:seon.flow.proc/ns` to `:seon.ns/name`. Both are strings. Query example:

```clojure
;; "Show me all running flow processes with their namespace's function count"
(d/q '[:find ?proc-name ?ns-name ?fn-count ?proc-status
        :where
        [?flow :seon.flow/status :running]
        [?proc :seon.flow.proc/flow ?flow]
        [?proc :seon.flow.proc/name ?proc-name]
        [?proc :seon.flow.proc/ns ?ns-name]
        [?proc :seon.flow.proc/status ?proc-status]
        ;; Join to code graph
        [(count-fns-in-ns $ ?ns-name) ?fn-count]]
      @conn)

;; Simpler: "Which namespaces have running processes?"
(d/q '[:find ?ns-name ?proc-status
        :where
        [?flow :seon.flow/status :running]
        [?proc :seon.flow.proc/flow ?flow]
        [?proc :seon.flow.proc/ns ?ns-name]
        [?proc :seon.flow.proc/status ?proc-status]]
      @conn)
```

### API Surface

New namespace: `seon.flow.state` (or extend `seon.flow.status`).

```clojure
(ns seon.flow.state
  "Flow state management — snapshot, restore, and query via Datalevin.")

(defn register-flow!
  "Record a flow in Datalevin when it starts.
   Called from topology/build-topology! after flow/start.

   Request keys:
     ::flow-id    - Keyword identifier
     ::label      - Human-readable label
     ::processes  - Map of pid -> {:ns namespace-string} for namespace processes"
  [{::keys [flow-id label processes]}]
  ...)

(defn unregister-flow!
  "Mark a flow as stopped in Datalevin.
   Called from topology/stop-topology!.
   Sets :seon.flow/stopped-at and :seon.flow/status :stopped."
  [{::keys [flow-id]}]
  ...)

(defn snapshot!
  "Pause flow, capture all process states via ping, write to Datalevin, resume.
   Uses the pause-ping-stop pattern from MEMORY.md.

   Request keys:
     ::flow-id - Flow to snapshot
     ::reason  - Why (:manual, :shutdown, :backup)

   Returns the snapshot entity map."
  [{::keys [flow-id reason]}]
  ...)

(defn restore!
  "Load a snapshot and hydrate flow state.
   Designed for startup recovery or debugging.

   Request keys:
     ::snap-id - Snapshot ID to restore, or nil for latest
     ::flow-id - Flow to restore (used if snap-id is nil)

   Returns the deserialized state map."
  [{::keys [snap-id flow-id]}]
  ...)

(defn list-snapshots
  "Query snapshots for a flow.

   Request keys:
     ::flow-id - Flow identifier
     ::limit   - Max results (default 10)

   Returns vector of snapshot summaries."
  [{::keys [flow-id limit]}]
  ...)

(defn flow-with-graph
  "Query a flow's runtime state joined with its code graph data.
   Returns processes enriched with function counts, spec counts, dependencies.

   Request keys:
     ::flow-id - Flow identifier

   Returns map of process data joined with graph data."
  [{::keys [flow-id]}]
  ...)
```

### Integration Points

**`seon.flow.topology/build-topology!`** — After `flow/start` and `flow/resume`, call `state/register-flow!` to persist the flow's existence and its process list to Datalevin.

**`seon.flow.topology/stop-topology!`** — Before `flow/stop`, call `state/snapshot!` with reason `:shutdown` to capture final state, then `state/unregister-flow!`.

**`seon.web.flows`** — Enhance the flows page to:
- Show historical flows (not just currently registered ones)
- Show snapshot history for each flow
- Show code graph data (function count, spec count) alongside flow status
- Enable "inspect snapshot" to see state at a point in time

**`seon.flow.registry`** — Becomes a thin wrapper. The in-memory atom stays for fast lookup of live flow objects (needed for `flow/ping`, `flow/inject`), but Datalevin is the durable record.

### Ctx Unification

Currently two separate persistence paths exist:
- `seon.ctx/do-persist!` uses `seon.ctx/*` attributes
- `seon.flow.harness/persist-ctx!` uses `ctx/*` attributes

Unify to a single schema (`seon.ctx/*`) with an optional ref to the flow process that owns the ctx. The `seon.ctx/id` replaces both `seon.ctx/instance-id` and `ctx/namespace` as the identity key.

---

## Phases

### Phase 1: Schema + Registration (estimted: 1 agent session)

1. Add `flow-state-schema` to `seon.graph.ingest/datalevin-schema` (merged at connection creation time)
2. Create `seon.flow.state` namespace with `register-flow!` and `unregister-flow!`
3. Wire into `build-topology!` and `stop-topology!`
4. Verify: start system, check Datalevin has flow entities, stop, check status updated
5. Test: flow registration round-trips through Datalevin

### Phase 2: Snapshot + Restore (estimated: 1-2 agent sessions)

1. Implement `snapshot!` using pause-ping-resume pattern
2. Implement `restore!` and `list-snapshots`
3. Add snapshot on shutdown (graceful stop persists state)
4. Test: snapshot, kill, restart, verify snapshot exists in Datalevin
5. Test: restore loads correct state data

### Phase 3: Graph Join + Flows Page (estimated: 1-2 agent sessions)

1. Implement `flow-with-graph` query joining flow processes to code graph
2. Enhance `seon.web.flows` to show:
   - Historical flows (from Datalevin, not just registry)
   - Function/spec counts per namespace process
   - Snapshot list with "inspect" capability
3. Test: flows page renders with graph-enriched data

### Phase 4: Ctx Unification (estimated: 1 agent session)

1. Migrate `seon.flow.harness/persist-ctx!` to use unified `seon.ctx/*` schema
2. Add optional `:seon.ctx/flow-proc` ref linking ctx to flow process
3. Remove `ctx/*` attributes from harness schema
4. Update `seon.ctx/datalevin-schema` with new fields
5. Test: ctx persistence works through unified path

---

## Non-Goals

- **Real-time write-through**: Every ctx swap does NOT go to Datalevin. The debounced model stays.
- **Replacing core.async.flow**: We build on top of flow's existing pause/resume/ping. We don't reimplement scheduling.
- **Full event sourcing**: Snapshots capture point-in-time state, not a log of every state change. The trace system (`seon.flow.trace`) handles event-level detail.
- **Cross-flow transactions**: Each flow snapshots independently. No distributed snapshot protocol.
- **Automatic restart/recovery**: Phase 2 enables manual restore. Automatic restart-from-snapshot is a future enhancement.
- **Migrating all 23 d/transact! callsites**: That's Track 4 of the refinement PRD. This PRD is specifically about flow state visibility.

---

## Success Criteria

1. After restarting the system, Datalevin still knows which flows were running and their last known state
2. The flows page can show "this namespace process manages 12 functions and 8 specs" by joining to the code graph
3. An operator can snapshot a running system, inspect the snapshot in the REPL, and see all process states
4. Only one ctx persistence schema exists (no more `ctx/*` vs `seon.ctx/*` duplication)
5. All existing tests continue to pass (this is additive, not a rewrite)
