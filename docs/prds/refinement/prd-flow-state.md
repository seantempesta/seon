# PRD: Datalevin as the System Model

**Status:** Draft
**Priority:** Medium (after refinement PRD completes)
**Author:** Research -- no code changes

---

## Problem Statement

Seon has **27 atoms** scattered across the codebase, each holding a piece of system state. Some are ephemeral caches (fine), but many represent durable system state that is lost on restart, invisible to queries, and disconnected from the code graph. The system cannot answer basic questions about itself.

### The Atom Inventory

**System state that MUST be in Datalevin (lost on restart, needed for recovery):**

| Atom | File | What It Holds | Serializable? | Changes How? |
|------|------|--------------|---------------|-------------|
| `*registry` | `seon.flow.registry` | flow-id -> {flow, chans, label, started-at} | Partially (flow/chans are opaque) | On flow start/stop |
| `*prev-counts` | `seon.flow.status` | {flow-id {pid {count, time}}} for throughput deltas | Yes | Every status poll |
| `*errors` | `seon.flow.status` | {flow-id [error-maps]} sliding window | Yes | On flow errors |
| `*error-drains` | `seon.flow.status` | {flow-id go-channel} | No (runtime) | On flow start/stop |
| `session-registry` | `seon.orchestrator.session` | session-id -> session info map | Partially (contains atoms, pool refs) | On session start/stop/eval |
| `agent-pool` | `seon.orchestrator.session` | Reference to pool component | No (runtime) | On init! |
| `dl-mgr` | `seon.orchestrator.session` | Datalevin connection manager ref | No (runtime) | On init! |
| `agent-registry` | `seon.ai.agent` | session-id -> agent handle (process, channels, atoms) | Partially (process/channels are opaque) | On agent launch/complete |
| `registry` | `seon.ctx` | instance-id -> {atom, conn, namespace, scheduler, ...} | Partially (atoms, schedulers are opaque) | On ctx create/destroy |
| `write-stats` | `seon.db` | {total-writes, last-write-at, by-caller} | Yes | Every write |
| `writers` | `seon.db` | conn-identity -> {flow, conn} writer flows | No (runtime) | Lazily on first write per conn |

**Ephemeral state that stays as atoms (runtime-only, reconstructable):**

| Atom | File | Why It Stays |
|------|------|-------------|
| `pending-remote-promises` | `seon.flow.harness.bridge` | In-flight request correlation, reconstructed per session |
| `pending-promises` | `seon.flow.topology` | In-flight request correlation, reconstructed per session |
| `ui-state` | `seon.web.agents` | UI toggle state, trivial |
| `refresh-ch_` | `seon.web.sse` | SSE broadcast channel, runtime |
| `edit-events`, `review-events`, `todo-events` | `seon.dev.context` | Dev hook event buffers, ephemeral by design |
| `tx-counter` | `seon.dev.context` | Transaction counter, resets on reload |
| `enabled?` | `seon.ai.datalevin` | Feature flag, set from config |
| `stats-atom` | `seon.ai.datalevin` | Message persistence stats, reconstructable |
| `*conn` | `seon.render` | Cached conn reference, runtime |
| `resolution-cache` | `seon.render` | Renderer resolution cache, warm on demand |
| `flow-state` | `seon.web.sse.flow` | SSE flow state, runtime |
| `*schemas` | `seon.schema` | Schema registry, populated at load time |
| `dl-mgr`, `session-ids` | `seon.primer.ctx` | Primer session tracking, ephemeral |
| `stats-cache` | `seon.web.stats` | Cached stats with TTL, reconstructable |
| `counter` | `seon.ns.example` | Example counter, trivial |
| `renderers` | `seon.primer.render` | Render function registry, populated at load time |
| `namespace-handlers` | `seon.ns.routes` | Route handler cache, populated at load time |
| `pending-evals` | `seon.web.browser` | In-flight browser evals, ephemeral |

### What Cannot Be Answered Today

1. "What was the state of the trading flow 5 minutes ago?" -- atoms have no history
2. "Which namespaces have running processes right now?" -- requires walking multiple atoms
3. "Show me all agents that ran today with their costs and namespaces" -- partially in Datalevin (`seon.ai`), partially in atoms (`agent-registry`)
4. "What functions does the currently running trading flow manage?" -- flow registry has no link to code graph
5. "After a crash, what was running?" -- all atom state is gone

### The Existing Code Graph

Datalevin already stores the "what exists" layer via `seon.graph.ingest`:

```clojure
;; Namespace entities
:seon.ns/name       {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.ns/doc        {:db/valueType :db.type/string}
:seon.ns/file       {:db/valueType :db.type/string}
:seon.ns/target     {:db/valueType :db.type/keyword}

;; Function entities
:seon.fn/qualified-name {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.fn/namespace      {:db/valueType :db.type/string}
:seon.fn/name           {:db/valueType :db.type/string}
:seon.fn/doc            {:db/valueType :db.type/string}
:seon.fn/arglists       {:db/valueType :db.type/string}
:seon.fn/row            {:db/valueType :db.type/long}
:seon.fn/private        {:db/valueType :db.type/boolean}
:seon.fn/updated-at     {:db/valueType :db.type/instant}

;; Call graph
:seon.call/from-fn  {:db/valueType :db.type/ref}
:seon.call/to-fn    {:db/valueType :db.type/ref}

;; NS dependencies
:seon.ns.dep/from-ns {:db/valueType :db.type/string}
:seon.ns.dep/to-ns   {:db/valueType :db.type/string}

;; Specs
:seon.spec/key           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
:seon.spec/namespace     {:db/valueType :db.type/string}
:seon.spec/definition    {:db/valueType :db.type/string}
```

The runtime layer (flows, sessions, agents) needs to connect to this.

### The Existing Ctx Persistence (Two Competing Schemas)

Two separate persistence paths exist and must be unified:

```clojure
;; seon.ctx/datalevin-schema (used by ctx/do-persist!)
:seon.ctx/instance-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
:seon.ctx/namespace   {:db/valueType :db.type/string}
:seon.ctx/data        {:db/valueType :db.type/string}  ;; EDN blob
:seon.ctx/updated-at  {:db/valueType :db.type/instant}

;; seon.flow.harness/persist-ctx! (different namespace, different identity key)
:ctx/namespace  {:db/valueType :db.type/string}  ;; NOT unique identity!
:ctx/data       {:db/valueType :db.type/string}
:ctx/updated-at {:db/valueType :db.type/instant}
```

The harness schema does not even have `:db/unique` on `:ctx/namespace`, so multiple entries for the same namespace can accumulate without upsert.

### The Existing Session Persistence

`seon.orchestrator.session` already persists to Datalevin:

```clojure
;; dl-schema in seon.orchestrator.session
:orch.session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
:orch.session/namespace  {:db/valueType :db.type/string}
:orch.session/nrepl-port {:db/valueType :db.type/long}
:orch.session/status     {:db/valueType :db.type/string}
:orch.session/started-at {:db/valueType :db.type/instant}
:orch.session/stopped-at {:db/valueType :db.type/instant}
:orch.session/db-name    {:db/valueType :db.type/string}
```

But it stores to its own namespace database (`seon.orchestrator`), disconnected from the master graph. And the in-memory `session-registry` atom is the real authority -- Datalevin is fire-and-forget backup.

---

## Design Principle: Datalevin is the System Model

**The atoms are caches. Datalevin is truth.**

When the system starts, it reads state from Datalevin. When state changes, it writes to Datalevin. The atoms provide fast in-process access. If an atom and Datalevin disagree, Datalevin wins.

This is not "persist everything in real-time." Datalevin writes are batched/debounced. But the flow is:

```
State change -> Update atom -> Debounced write to Datalevin
System startup -> Read from Datalevin -> Populate atoms
```

Not:

```
State change -> Update atom -> Maybe persist if we remember
System startup -> Start fresh -> Hope nothing was important
```

---

## Unified Datalevin Schema

All runtime state goes into the **master database** alongside the code graph. This is one schema, one connection, one queryable graph.

### Why the Master Database?

The code graph (`seon.ns/*`, `seon.fn/*`) already lives in the master database. Runtime entities (flows, sessions, agents) need to join against the code graph via namespace strings. Putting them in separate databases would make cross-entity queries impossible -- Datalevin does not support cross-database joins.

### The Schema

```clojure
(def system-state-schema
  {;; ===================================================================
   ;; FLOW INSTANCES
   ;; A flow is a core.async.flow topology -- a set of connected processes.
   ;; The flow entity records that it existed, when, and its last known state.
   ;; ===================================================================

   :seon.flow/id
   {:db/valueType :db.type/keyword
    :db/unique    :db.unique/identity}
   ;; e.g. :seon/main-topology, :seon/trading-pipeline
   ;; Identity key enables upsert: re-registering same flow-id updates it.

   :seon.flow/label
   {:db/valueType :db.type/string}
   ;; Human-readable name, e.g. "Main namespace topology"

   :seon.flow/status
   {:db/valueType :db.type/keyword}
   ;; :running, :paused, :stopped, :crashed
   ;; Indexed as keyword for efficient filtering.

   :seon.flow/started-at
   {:db/valueType :db.type/instant}

   :seon.flow/stopped-at
   {:db/valueType :db.type/instant}

   ;; ===================================================================
   ;; FLOW PROCESSES
   ;; Each step-fn in a flow becomes a process entity.
   ;; The key relationship: a process can manage a namespace, which links
   ;; it to the code graph (seon.ns/name).
   ;; ===================================================================

   :seon.flow.proc/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; Composite string: "flow-keyword/process-keyword"
   ;; e.g. "seon-main-topology/ns-seon.trading.signals"
   ;; String (not keyword) because keyword namespaces cannot contain dots.

   :seon.flow.proc/flow
   {:db/valueType :db.type/ref}
   ;; Ref to the parent :seon.flow/id entity.
   ;; Enables: from a process, navigate to its flow.
   ;; Enables: from a flow, pull all its processes.

   :seon.flow.proc/name
   {:db/valueType :db.type/keyword}
   ;; The process keyword within the flow, e.g. :ns/seon.trading.signals

   :seon.flow.proc/ns
   {:db/valueType :db.type/string}
   ;; The namespace this process manages (if it is a namespace-step).
   ;; String to join with :seon.ns/name in the code graph.
   ;; Nil/absent for non-namespace processes (reply-router, event-sink).

   :seon.flow.proc/status
   {:db/valueType :db.type/keyword}
   ;; :running, :paused -- mirrors flow/ping status.

   :seon.flow.proc/msg-count
   {:db/valueType :db.type/long}
   ;; Last known message count from flow/ping.

   ;; ===================================================================
   ;; FLOW SNAPSHOTS
   ;; Point-in-time capture of all process states in a flow.
   ;; Created on demand, on shutdown, and on backup.
   ;; ===================================================================

   :seon.flow.snap/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; Composite: "flow-keyword/ISO-timestamp"

   :seon.flow.snap/flow
   {:db/valueType :db.type/ref}
   ;; Ref to :seon.flow/id entity.

   :seon.flow.snap/created-at
   {:db/valueType :db.type/instant}

   :seon.flow.snap/reason
   {:db/valueType :db.type/keyword}
   ;; :manual, :shutdown, :backup, :error

   :seon.flow.snap/data
   {:db/valueType :db.type/string}
   ;; EDN blob: serialized map of {process-keyword -> process-state-map}
   ;; from flow/ping. Stored as string because process state is arbitrary.

   ;; ===================================================================
   ;; AGENT SESSIONS
   ;; Replaces orch.session/* with seon.session/* in the master database.
   ;; An agent session ties together: a namespace, an nREPL port,
   ;; a ctx instance, and optionally a pool JVM.
   ;; ===================================================================

   :seon.session/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; 4-char hex session ID, e.g. "a1b2"

   :seon.session/ns
   {:db/valueType :db.type/string}
   ;; Namespace string -- joins to :seon.ns/name in code graph.

   :seon.session/status
   {:db/valueType :db.type/keyword}
   ;; :running, :stopped, :error

   :seon.session/nrepl-port
   {:db/valueType :db.type/long}

   :seon.session/started-at
   {:db/valueType :db.type/instant}

   :seon.session/stopped-at
   {:db/valueType :db.type/instant}

   :seon.session/eval-count
   {:db/valueType :db.type/long}

   :seon.session/last-activity-at
   {:db/valueType :db.type/instant}

   ;; ===================================================================
   ;; AI AGENT RUNS
   ;; An AI agent run is one invocation of Claude (or another provider)
   ;; working within a session. Links to the session entity.
   ;; Note: seon.ai already has its own persistence for messages.
   ;; This entity captures the run-level summary for the system graph.
   ;; ===================================================================

   :seon.agent/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; 4-char hex session ID (same as seon.session/id for Seon agents)

   :seon.agent/session
   {:db/valueType :db.type/ref}
   ;; Ref to :seon.session/id entity.

   :seon.agent/ns
   {:db/valueType :db.type/string}
   ;; Namespace string -- joins to :seon.ns/name.

   :seon.agent/provider
   {:db/valueType :db.type/keyword}
   ;; :claude, :gemini, etc.

   :seon.agent/status
   {:db/valueType :db.type/keyword}
   ;; :running, :completed, :failed, :terminated, :interrupted

   :seon.agent/ai-session-id
   {:db/valueType :db.type/string}
   ;; The AI conversation session ID (for linking to message persistence)

   :seon.agent/started-at
   {:db/valueType :db.type/instant}

   :seon.agent/stopped-at
   {:db/valueType :db.type/instant}

   :seon.agent/cost-usd
   {:db/valueType :db.type/double}

   :seon.agent/num-turns
   {:db/valueType :db.type/long}

   :seon.agent/duration-ms
   {:db/valueType :db.type/long}

   :seon.agent/result-text
   {:db/valueType :db.type/string}
   ;; Final summary text from the agent. Stored as string, can be large.

   ;; ===================================================================
   ;; CTX PERSISTENCE (unified)
   ;; Replaces both seon.ctx/datalevin-schema and harness ctx/* attrs.
   ;; A ctx is a serialized state blob associated with a namespace
   ;; and optionally with a flow process or agent session.
   ;; ===================================================================

   :seon.ctx/id
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}
   ;; For agent sessions: the 4-char hex session ID.
   ;; For flow processes: "flow-keyword/process-keyword".
   ;; For standalone ctx: a generated ID.

   :seon.ctx/ns
   {:db/valueType :db.type/string}
   ;; Namespace string -- joins to :seon.ns/name.

   :seon.ctx/flow-proc
   {:db/valueType :db.type/ref}
   ;; Optional ref to :seon.flow.proc/id entity.
   ;; Present when ctx belongs to a flow process.

   :seon.ctx/session
   {:db/valueType :db.type/ref}
   ;; Optional ref to :seon.session/id entity.
   ;; Present when ctx belongs to an agent session.

   :seon.ctx/data
   {:db/valueType :db.type/string}
   ;; EDN blob of the serializable portion of the ctx value.

   :seon.ctx/updated-at
   {:db/valueType :db.type/instant}

   ;; ===================================================================
   ;; WRITE STATS
   ;; Captures database write statistics for observability.
   ;; Single entity, upserted on each persist cycle.
   ;; ===================================================================

   :seon.write-stats/id
   {:db/valueType :db.type/keyword
    :db/unique    :db.unique/identity}
   ;; Always :seon/write-stats (singleton)

   :seon.write-stats/total-writes
   {:db/valueType :db.type/long}

   :seon.write-stats/last-write-at
   {:db/valueType :db.type/instant}

   :seon.write-stats/by-caller
   {:db/valueType :db.type/string}
   ;; EDN blob: {caller-ns-string count}
   })
```

### Design Decisions Explained

**Why string joins instead of refs for namespace links?**

The code graph uses `:seon.ns/name` (string, unique identity) and `:seon.fn/namespace` (string, not ref). Using refs would require resolving entity IDs at write time, which means the code graph must exist before runtime entities can be created. String joins are simpler: write the namespace string, query with a join clause. Datalevin's AVE index on strings makes this efficient.

```clojure
;; String join pattern (simple, always works)
[?proc :seon.flow.proc/ns ?ns-name]
[?ns   :seon.ns/name ?ns-name]

;; Ref join pattern (would require entity ID resolution at write time)
;; NOT used -- too much coupling
```

**Why refs for parent-child relationships (flow->process, session->agent)?**

Within the runtime layer, entities are created together (a flow and its processes in one transaction). Refs enable pull patterns like `{:seon.flow.proc/_flow [:seon.flow.proc/name :seon.flow.proc/status]}` to get all processes for a flow in one pull. This is the natural Datalevin pattern for owned sub-entities.

**Why keyword for `:seon.flow/id` but string for `:seon.flow.proc/id`?**

Flow IDs are short symbolic identifiers (`:seon/main-topology`) that work naturally as keywords. Process IDs need to contain dots (namespace names) which cannot appear in keyword namespaces without escaping. Strings are the pragmatic choice for composite IDs.

**Why `:db.type/keyword` for status fields?**

Keywords are compact, self-documenting, and efficient for equality checks in queries. Datalevin indexes keywords, so `[?e :seon.flow/status :running]` is a direct index lookup, not a scan.

**Why EDN blobs for state data?**

Process state, ctx data, and write-stats-by-caller are arbitrary maps that vary in shape. Defining schema attributes for every possible key would be impractical and brittle. EDN blobs store the full state, and the structured attributes (status, timestamps, counts) provide the queryable surface. You query the structured attributes, then deserialize the blob when you need the full picture.

**Why NOT `:db/isComponent true` on flow->process refs?**

Component refs cascade delete: retracting the flow entity would automatically retract all process entities. This is desirable for cleanup but dangerous if done accidentally. We prefer explicit retraction so process history survives flow restarts.

---

## The Fully Connected Graph

With this schema, every entity connects to the code graph via namespace strings:

```
                    CODE GRAPH (static)
                    ==================
                    :seon.ns/name ----+---- :seon.fn/qualified-name
                         |           |           |
                    :seon.ns/doc     |      :seon.fn/namespace (string join)
                    :seon.ns/file    |      :seon.fn/arglists
                                     |      :seon.fn/doc
                                     |
                    RUNTIME LAYER (dynamic)
                    =======================
                         |
         +---------------+---------------+
         |               |               |
   :seon.flow.proc/ns  :seon.session/ns  :seon.agent/ns
         |               |               |
   :seon.flow.proc/id  :seon.session/id  :seon.agent/id
         |               |               |
   :seon.flow.proc/flow :seon.ctx/session :seon.agent/session
         |               |               |
   :seon.flow/id    :seon.ctx/id    (same session entity)
         |
   :seon.flow.snap/flow
         |
   :seon.flow.snap/id
```

### Example Queries

**"What namespaces have running flow processes, and how many functions does each have?"**

```clojure
(d/q '[:find ?ns-name (count ?fn)
        :where
        [?flow :seon.flow/status :running]
        [?proc :seon.flow.proc/flow ?flow]
        [?proc :seon.flow.proc/ns ?ns-name]
        [?fn   :seon.fn/namespace ?ns-name]]
      @conn)
;; => #{["seon.trading.signals" 12] ["seon.health.workout" 8]}
```

**"Show me all agents that ran on a namespace, with cost and status"**

```clojure
(d/q '[:find ?agent-id ?ns ?status ?cost
        :in $ ?target-ns
        :where
        [?a :seon.agent/ns ?ns]
        [?a :seon.agent/id ?agent-id]
        [?a :seon.agent/status ?status]
        [?a :seon.agent/cost-usd ?cost]
        [(= ?ns ?target-ns)]]
      @conn "seon.trading")
;; => #{["a1b2" "seon.trading" :completed 0.23]
;;      ["c3d4" "seon.trading" :failed 0.05]}
```

**"For a running flow, show processes with their namespace function counts and specs"**

```clojure
(d/q '[:find ?proc-name ?ns ?fn-count ?spec-count ?proc-status
        :in $ ?flow-id
        :where
        [?flow :seon.flow/id ?flow-id]
        [?proc :seon.flow.proc/flow ?flow]
        [?proc :seon.flow.proc/name ?proc-name]
        [?proc :seon.flow.proc/ns ?ns]
        [?proc :seon.flow.proc/status ?proc-status]
        ;; Subquery: count functions
        [(d/q [:find (count ?fn) .
               :in $ ?ns-name
               :where [?fn :seon.fn/namespace ?ns-name]]
              $ ?ns) ?fn-count]
        ;; Subquery: count specs
        [(d/q [:find (count ?sp) .
               :in $ ?ns-name
               :where [?sp :seon.spec/namespace ?ns-name]]
              $ ?ns) ?spec-count]]
      @conn :seon/main-topology)
```

Note: Datalevin does not support nested `d/q` calls inside query clauses. The above is conceptual. In practice, do two queries and join in Clojure:

```clojure
;; Practical approach: separate queries, Clojure join
(let [procs (d/q '[:find ?proc-name ?ns ?proc-status
                    :in $ ?flow-id
                    :where
                    [?flow :seon.flow/id ?flow-id]
                    [?proc :seon.flow.proc/flow ?flow]
                    [?proc :seon.flow.proc/name ?proc-name]
                    [?proc :seon.flow.proc/ns ?ns]
                    [?proc :seon.flow.proc/status ?proc-status]]
                  @conn :seon/main-topology)
      ns-names (set (map second procs))
      fn-counts (into {}
                  (d/q '[:find ?ns (count ?fn)
                          :in $ [?ns ...]
                          :where [?fn :seon.fn/namespace ?ns]]
                        @conn (vec ns-names)))
      spec-counts (into {}
                    (d/q '[:find ?ns (count ?sp)
                            :in $ [?ns ...]
                            :where [?sp :seon.spec/namespace ?ns]]
                          @conn (vec ns-names)))]
  (mapv (fn [[proc-name ns status]]
          {:process proc-name
           :namespace ns
           :status status
           :fn-count (get fn-counts ns 0)
           :spec-count (get spec-counts ns 0)})
        procs))
```

**"What was the last known state of the trading flow before it stopped?"**

```clojure
(d/q '[:find (pull ?snap [:seon.flow.snap/created-at
                           :seon.flow.snap/reason
                           :seon.flow.snap/data]) .
        :in $ ?flow-id
        :where
        [?flow :seon.flow/id ?flow-id]
        [?snap :seon.flow.snap/flow ?flow]
        [?snap :seon.flow.snap/created-at ?t]
        :order-by [?t :desc]
        :limit 1]
      @conn :seon/trading-pipeline)
;; => {:seon.flow.snap/created-at #inst "2026-02-21T..."
;;     :seon.flow.snap/reason :shutdown
;;     :seon.flow.snap/data "{:ns/seon.trading.signals {:pending 0 ...}}"}
```

**"Which sessions are currently running, with their agent status?"**

```clojure
(d/q '[:find (pull ?s [:seon.session/id :seon.session/ns
                        :seon.session/nrepl-port :seon.session/started-at])
              (pull ?a [:seon.agent/status :seon.agent/provider
                        :seon.agent/cost-usd])
        :where
        [?s :seon.session/status :running]
        [?a :seon.agent/session ?s]]
      @conn)
```

**"Navigate from a function to its runtime context"**

```clojure
;; Given function "seon.trading.signals/ema", find if its namespace
;; has a running flow process and what ctx state it has
(let [ns-name (d/q '[:find ?ns .
                      :in $ ?fn
                      :where [?e :seon.fn/qualified-name ?fn]
                             [?e :seon.fn/namespace ?ns]]
                    @conn "seon.trading.signals/ema")
      proc (d/q '[:find (pull ?p [*]) .
                   :in $ ?ns
                   :where
                   [?p :seon.flow.proc/ns ?ns]
                   [?f :seon.flow/status :running]
                   [?p :seon.flow.proc/flow ?f]]
                 @conn ns-name)
      ctx (d/q '[:find ?data .
                  :in $ ?ns
                  :where
                  [?c :seon.ctx/ns ?ns]
                  [?c :seon.ctx/data ?data]]
                @conn ns-name)]
  {:function "seon.trading.signals/ema"
   :namespace ns-name
   :process proc
   :ctx-data (when ctx (clojure.edn/read-string ctx))})
```

---

## Serialization Mechanism

### What Gets Persisted and When

| State | Trigger | Debounce | Pattern |
|-------|---------|----------|---------|
| Flow registration | `build-topology!` completes | None (immediate) | Upsert flow + processes in one transaction |
| Flow unregistration | `stop-topology!` starts | None (immediate) | Update status to :stopped, set stopped-at |
| Flow snapshot | Manual, shutdown, backup | None (explicit) | Pause -> ping -> serialize states -> write -> resume |
| Session start | `start-agent-session!` | None (immediate) | Upsert session entity |
| Session stop | `stop-agent-session!` | None (immediate) | Update status, set stopped-at |
| Session activity | `record-eval-complete!` | 5 seconds | Debounced update of eval-count, last-activity-at |
| Agent start | `launch-agent!` | None (immediate) | Upsert agent entity |
| Agent completion | Result message received | None (immediate) | Update status, cost, duration, result-text |
| Ctx updates | Atom watch fires | 100ms (existing) | Debounced write of serializable ctx data |
| Write stats | Every N writes or timer | 30 seconds | Periodic upsert of stats singleton |

### The Atom-Datalevin Sync Pattern

Each stateful module follows this pattern:

```clojure
;; 1. Module-private atom for fast in-process reads
(defonce ^:private *state (atom {}))

;; 2. On system startup, hydrate from Datalevin
(defn init! [conn]
  (let [persisted (load-from-datalevin conn)]
    (reset! *state persisted)))

;; 3. On state change, update atom AND schedule Datalevin write
(defn update-state! [conn change]
  (swap! *state apply-change change)
  (schedule-persist! conn @*state))  ;; debounced

;; 4. For reads, use the atom (fast, no I/O)
(defn get-state [] @*state)

;; 5. On shutdown, flush pending writes
(defn shutdown! [conn]
  (flush-pending-persist! conn @*state))
```

### What Stays in Atoms Only

Opaque runtime objects that cannot be serialized:

- `flow` objects (core.async.flow handles)
- `chans` (core.async channels)
- `process` (java.lang.Process handles)
- `scheduler` (ScheduledExecutorService)
- `conn` (Datalevin connections)
- `pool` (JVM pool component)
- `close!` functions
- `status-atom` (atoms themselves are not serializable, their values are)

These are reconstructed on startup (flows restarted, sessions reclaimed, etc.) or marked as :stopped/:crashed if recovery is not possible.

---

## Migration Path

### Phase 1: Schema + Flow Registration (1 agent session)

**Goal:** Flows persist their existence in Datalevin. After restart, we know what was running.

1. Add `system-state-schema` to the master database schema (merge with `seon.graph.ingest/datalevin-schema` at connection creation time).

2. Create `seon.flow.state` namespace:
   - `register-flow!` -- transacts flow + process entities after `flow/start`
   - `unregister-flow!` -- updates flow status to :stopped after `flow/stop`

3. Wire into `seon.flow.topology`:
   - `build-topology!` calls `state/register-flow!` after successful start
   - `stop-topology!` calls `state/unregister-flow!` before stop

4. On startup, mark any flows still showing `:running` in Datalevin as `:crashed` (they survived a dirty shutdown).

**Test:** Start system, verify flow entities exist in Datalevin. Stop, restart, verify flows marked as stopped/crashed.

**Files changed:** `seon.flow.state` (new), `seon.flow.topology` (add calls), `seon.graph.ingest` or system startup (merge schema).

### Phase 2: Session + Agent Migration (1 agent session)

**Goal:** Move session/agent persistence from `seon.orchestrator` namespace DB to master DB using new schema.

1. Replace `orch.session/*` attributes with `seon.session/*` attributes in master DB.
2. Replace `agent-registry` atom persistence with `seon.agent/*` attributes in master DB.
3. Update `seon.orchestrator.session`:
   - `store-session!` writes to master DB with new schema
   - `update-session-status!` uses new schema
   - `load-session-from-db` queries new schema
   - `recover-sessions!` marks stale :running sessions as :crashed
4. Update `seon.ai.claude/launch-agent!`:
   - Write `seon.agent/*` entity on launch
   - Update on completion (status, cost, duration, result-text)

**Test:** Start agent session, verify session + agent entities in master DB. Complete agent, verify cost/status updated.

**Files changed:** `seon.orchestrator.session`, `seon.ai.claude`, system startup.

### Phase 3: Flow Snapshots (1 agent session)

**Goal:** Snapshot flow state on demand, on shutdown, and on backup.

1. Implement `snapshot!` in `seon.flow.state`:
   - Pause flow (`flow/pause`)
   - Sync barrier (`flow/ping`)
   - Collect all process states from ping result
   - Serialize to EDN, write `seon.flow.snap/*` entity
   - Resume flow (`flow/resume`)

2. Implement `restore!` and `list-snapshots`.

3. Add automatic snapshot on graceful shutdown:
   - `stop-topology!` calls `snapshot!` with reason `:shutdown` before `flow/stop`

4. Add snapshot on backup:
   - `seon.db.datalevin.backup` calls `snapshot!` with reason `:backup` before backup

**Test:** Create snapshot, verify EDN data in Datalevin. Restart, list snapshots, verify data integrity.

**Files changed:** `seon.flow.state`, `seon.flow.topology`, `seon.db.datalevin.backup`.

### Phase 4: Ctx Unification (1 agent session)

**Goal:** One ctx persistence schema, connected to flows and sessions.

1. Update `seon.ctx/datalevin-schema` to use unified `seon.ctx/*` attributes from system-state-schema.
2. Add `:seon.ctx/flow-proc` (ref) and `:seon.ctx/session` (ref) to link ctx to owner.
3. Update `seon.ctx/do-persist!` to write to master DB.
4. Migrate `seon.flow.harness/persist-ctx!` to use `seon.ctx/*` schema instead of `ctx/*`.
5. Delete `ctx/*` attributes from harness.

**Test:** Ctx persistence round-trips through unified schema. Both ctx/persist! and harness/persist-ctx! use same schema. Old `ctx/*` attributes no longer used.

**Files changed:** `seon.ctx`, `seon.flow.harness`, `seon.graph.ingest` (remove old ctx schema if present).

### Phase 5: Graph Queries + Flows Page (1-2 agent sessions)

**Goal:** The flows page shows runtime state joined with code graph data.

1. Add query functions to `seon.flow.state`:
   - `flow-with-graph` -- processes enriched with fn/spec counts
   - `flow-history` -- all flows (running + stopped) with snapshots
   - `namespace-runtime` -- from namespace name, get process + ctx + agent info

2. Enhance `seon.web.flows`:
   - Show historical flows from Datalevin, not just registry
   - Show function/spec counts per namespace process
   - Show snapshot list with "inspect" capability
   - Show associated agent runs per namespace

**Test:** Flows page renders with graph-enriched data. Stopped flows appear in history.

**Files changed:** `seon.flow.state`, `seon.web.flows`.

### Phase 6: Write Stats + Startup Hydration (1 agent session)

**Goal:** Write stats persist across restarts. All atoms hydrate from Datalevin on startup.

1. Persist `seon.db/write-stats` periodically (every 30s) to `seon.write-stats/*`.
2. On startup, hydrate:
   - `seon.flow.status/*prev-counts` and `*errors` from last snapshot data
   - `seon.db/write-stats` from Datalevin
   - `seon.orchestrator.session/session-registry` from Datalevin (running -> crashed)
3. Add `seon.system.hydrate` namespace that orchestrates startup hydration.

**Test:** Stop system, restart, verify write stats survived. Verify sessions marked as crashed.

**Files changed:** `seon.db`, `seon.flow.status`, `seon.orchestrator.session`, `seon.system.hydrate` (new).

---

## Non-Goals

- **Real-time write-through**: Every atom swap does NOT go to Datalevin. The debounced/periodic model stays. LMDB writes are serialized; flooding it with every state change would create contention.
- **Replacing core.async.flow**: We build on top of flow's existing pause/resume/ping.
- **Full event sourcing**: Snapshots are point-in-time, not logs of every change. The trace system handles event-level detail.
- **Cross-flow transactions**: Each flow snapshots independently.
- **Automatic restart from snapshot**: Snapshots enable manual recovery. Auto-restart is a future enhancement.
- **Migrating the schema registry atom**: `seon.schema/*schemas` is populated at load time from `schema/register!` calls in source code. It is a code-level concern, not a data concern.

---

## Success Criteria

1. After `pkill -9`, restart the system. Datalevin shows which flows were running (marked :crashed), which sessions were active (marked :crashed), and the last snapshot of each flow's state.

2. From the REPL, navigate from a function name to its runtime context:
   ```clojure
   (state/namespace-runtime {::state/conn conn ::state/ns "seon.trading.signals"})
   ;; => {:functions 12, :specs 8, :process {:status :running, :msg-count 4521},
   ;;     :ctx {:last-signal {...}}, :agents [{:id "a1b2" :status :completed :cost 0.23}]}
   ```

3. The flows page shows historical data (not just live), with function counts and agent history per namespace.

4. Only one ctx persistence schema exists (`seon.ctx/*`). The old `ctx/*` and `orch.session/*` attributes are removed.

5. All 514 existing tests pass. This is additive, not a rewrite.

6. Write stats survive restart.
