# Ctx Sync Primitives Design

**Status:** Research Complete
**Date:** 2024-12-24
**Goal:** Simple, powerful API for ctx management with atom-first runtime + XTDB persistence

---

## Research Findings

### Existing Patterns in Seon

**1. `seon.db.node` - Query Wrapper Patterns**

The DB layer provides clean abstractions:
- `xtql-query` / `sql-query` for raw queries
- `query` as unified entry point (routes based on type)
- `entity` for single-entity lookup by ID
- `entity-history` for temporal queries with `FOR ALL VALID_TIME`
- `execute-tx!` for synchronous transactions

Key insight: Queries accept `{:current-time inst :snapshot-time inst}` opts for temporal access.

**2. `seon.db.queries` - Domain Query Patterns**

Higher-level query builders showing:
- Use of `xt/template` macro for dynamic value injection
- Temporal queries via `:current-time` option
- Clean function signatures: `(fn [node ticker opts])`

**3. `seon.trading.core` - Domain API Pattern**

The canonical pattern for domain APIs:
- First param is always `db` (the XTDB node)
- Domains don't manage their own DB - they receive it
- `capabilities` function for LLM agent discovery
- Clean delegation to internal modules

**4. `seon.primer.state` - Current Ctx Implementation**

Current state:

```clojure
(defonce ctx (atom {}))

(defn update-ctx! [f & args]
  (let [new-ctx (apply swap! ctx f args)]
    (when-not (valid-ctx? new-ctx)
      (throw ...))
    new-ctx))

;; Watch triggers SSE on change
(add-watch ctx :sse-auto-refresh ...)

```

Strengths:
- Validation on every update (Malli)
- Auto SSE refresh via watch
- Simple global atom

Weaknesses:
- Single global atom - no multi-session support
- No persistence layer
- No temporal capabilities
- No session isolation

**5. Integrant System Pattern**

The primer XTDB node is already configured:

```clojure
:seon.primer/xtdb-node
{:storage #profile {:dev {:type :local :path "data/primer"}
                    :test :in-memory
                    :prod {:type :local :path "data/primer"}}}

```

Access pattern from user.clj shows how to get components:

```clojure
(defn current-system []
  (or system (deref (deref (resolve 'seon.runner/system)))))

(defn xtdb-node []
  (:seon/xtdb-node (current-system)))

```

---

## Design Goals

1. **Simple primitives** - Agent can call a few functions to do everything
2. **Atom-first** - Use atom for all reads/writes during runtime (60fps capable)
3. **Multi-session** - Multiple children/sessions in one atom
4. **Periodic sync** - Checkpoint to XTDB on demand or periodically
5. **Recovery** - Load from XTDB to atom on startup
6. **Temporal** - Query past states from XTDB when needed
7. **REPL-friendly** - Everything inspectable and testable

---

## Proposed API

### Data Model

```clojure
;; The atom holds all sessions keyed by session-id
@sessions
;; =>
{"session-123"
 {:session/id "session-123"
  :session/child-id "child-456"
  :session/started-at #inst "2024-12-24T08:00:00Z"
  :session/last-checkpoint #inst "2024-12-24T08:05:00Z"

  :primer/current-scene {:scene/id "forest-crossroads" ...}
  :primer/child-profile {:interests #{:dinosaurs} ...}
  :primer/story-facts #{:met-owl :has-lantern}}

 "session-789" {...}}

```

### Core Operations (Fast, Atom-Based)

```clojure
(ns seon.primer.ctx
  "Ctx management - atom + XTDB sync.

   Runtime: atom operations are instant (60fps capable).
   Persistence: checkpoint to XTDB on demand.
   Recovery: load from XTDB on startup.")

;; === Read Operations ===

(ctx/get "session-123")
;; => {:session/id "session-123" :primer/current-scene {...} ...}

(ctx/get-in "session-123" [:primer/current-scene :scene/id])
;; => "forest-crossroads"

(ctx/exists? "session-123")
;; => true

(ctx/list-sessions)
;; => ["session-123" "session-789"]

;; === Write Operations ===

(ctx/update! "session-123" assoc :primer/current-scene {...})
;; => updated ctx (also triggers SSE refresh)

(ctx/update-in! "session-123" [:primer/story-facts] conj :solved-riddle)
;; => updated ctx

(ctx/merge! "session-123" {:primer/child-profile {...}})
;; => updated ctx

```

### Session Lifecycle

```clojure
;; Create new session (in atom only - not persisted until checkpoint)
(ctx/create! "session-123" {:session/child-id "child-456"})
;; => {:session/id "session-123" :session/child-id "child-456"
;;     :session/started-at #inst "..." ...}

;; Destroy session (removes from atom)
(ctx/destroy! "session-123")
;; => nil

;; Destroy with XTDB cleanup (marks as ended in DB)
(ctx/destroy! "session-123" {:persist? true})
;; => nil

```

### Persistence Operations (XTDB Sync)

```clojure
;; Save current atom state to XTDB
(ctx/checkpoint! "session-123")
;; => {:tx-id 12345 :system-time #inst "..."}

;; Save all sessions
(ctx/checkpoint-all!)
;; => [{:session-id "session-123" :tx-id 12345} ...]

;; Convenience: update and immediately checkpoint
(ctx/update+checkpoint! "session-123" assoc :primer/current-scene {...})
;; => updated ctx (also writes to XTDB)

```

### Recovery Operations (XTDB -> Atom)

```clojure
;; Load single session from XTDB into atom
(ctx/load! "session-123")
;; => ctx map (now in atom)

;; Load historical state (for debugging/replay)
(ctx/load-at! "session-123" #inst "2024-12-24T08:00:00Z")
;; => historical ctx (in atom)

;; Load all active sessions on startup
(ctx/load-active-sessions!)
;; => ["session-123" "session-789"]

```

### Temporal Queries (Read-Only, XTDB)

```clojure
;; List checkpoints for a session
(ctx/history "session-123")
;; => [{:checkpoint/time #inst "..." :checkpoint/tx-id 123} ...]

;; Get ctx at specific point in time (doesn't affect atom)
(ctx/at "session-123" #inst "2024-12-24T08:00:00Z")
;; => historical ctx map

;; Query specific path at time
(ctx/get-at "session-123" [:primer/current-scene] #inst "...")
;; => historical value

;; Diff between two points
(ctx/diff "session-123" #inst "2024-12-24T08:00" #inst "2024-12-24T09:00")
;; => {:added {...} :removed {...} :changed {...}}

```

---

## Implementation Design

### Module Structure

```
src/seon/primer/ctx.clj    ; Main API

```

### Core State

```clojure
(ns seon.primer.ctx
  "Ctx management - atom + XTDB sync."
  (:require [seon.primer.schema :as schema]
            [seon.db.node :as db]
            [malli.core :as m]
            [xtdb.api :as xt]))

;; All sessions in memory
(defonce sessions (atom {}))

;; Get primer XTDB node from running system
(defn primer-node []
  (let [sys-var (resolve 'seon.runner/system)]
    (when sys-var
      (:seon.primer/xtdb-node @(deref sys-var)))))

```

### Read Operations

```clojure
(defn get
  "Get session ctx from atom. Returns nil if not found."
  [session-id]
  (clojure.core/get @sessions session-id))

(defn get-in
  "Get nested value from session ctx."
  [session-id path]
  (clojure.core/get-in @sessions (cons session-id path)))

(defn exists?
  "Check if session exists in atom."
  [session-id]
  (contains? @sessions session-id))

(defn list-sessions
  "List all session IDs currently in memory."
  []
  (keys @sessions))

```

### Write Operations

```clojure
(defn- validate-session!
  "Validate session ctx against schema. Throws on invalid."
  [session-id ctx]
  (when-not (m/validate schema/SessionCtx ctx {:registry @schema/registry})
    (throw (ex-info "Invalid session ctx after update"
                    {:session-id session-id
                     :errors (m/explain schema/SessionCtx ctx)})))
  ctx)

(defn update!
  "Update session ctx. Validates and triggers SSE refresh."
  [session-id f & args]
  (let [result (apply swap! sessions update session-id f args)
        ctx (clojure.core/get result session-id)]
    (validate-session! session-id ctx)
    ;; SSE refresh happens via watch (already set up in state.clj pattern)
    ctx))

(defn update-in!
  "Update nested path in session ctx."
  [session-id path f & args]
  (apply update! session-id update-in path f args))

(defn merge!
  "Merge data into session ctx."
  [session-id data]
  (update! session-id merge data))

```

### Session Lifecycle

```clojure
(defn create!
  "Create a new session in memory."
  [session-id initial-data]
  (let [ctx (merge {:session/id session-id
                    :session/started-at (java.time.Instant/now)}
                   initial-data)]
    (validate-session! session-id ctx)
    (swap! sessions assoc session-id ctx)
    ctx))

(defn destroy!
  "Remove session from memory. Optionally persist end state."
  [session-id & {:keys [persist?]}]
  (when persist?
    (when-let [node (primer-node)]
      (let [ctx (get session-id)]
        (db/execute-tx! node
          [[:put-docs :primer/sessions
            (assoc ctx
              :xt/id session-id
              :session/ended-at (java.time.Instant/now))]]))))
  (swap! sessions dissoc session-id)
  nil)

```

### Persistence

```clojure
(defn checkpoint!
  "Save session to XTDB. Returns tx result."
  [session-id]
  (when-let [node (primer-node)]
    (let [ctx (get session-id)
          now (java.time.Instant/now)]
      (db/execute-tx! node
        [[:put-docs :primer/sessions
          (assoc ctx
            :xt/id session-id
            :session/last-checkpoint now)]])
      ;; Update atom with checkpoint time
      (swap! sessions assoc-in [session-id :session/last-checkpoint] now))))

(defn checkpoint-all!
  "Save all sessions to XTDB."
  []
  (doall (map checkpoint! (list-sessions))))

(defn update+checkpoint!
  "Update session and immediately checkpoint."
  [session-id f & args]
  (let [ctx (apply update! session-id f args)]
    (checkpoint! session-id)
    ctx))

```

### Recovery

```clojure
(defn load!
  "Load session from XTDB into atom."
  [session-id]
  (when-let [node (primer-node)]
    (let [ctx (first (db/query node
                       (xt/template
                         (from :primer/sessions
                           [{:xt/id ~session-id}
                            *]))))]
      (when ctx
        (swap! sessions assoc session-id ctx)
        ctx))))

(defn load-at!
  "Load historical session state into atom."
  [session-id inst]
  (when-let [node (primer-node)]
    (let [ctx (first (db/query node
                       (xt/template
                         (from :primer/sessions
                           [{:xt/id ~session-id} *]))
                       {:current-time inst}))]
      (when ctx
        (swap! sessions assoc session-id ctx)
        ctx))))

(defn load-active-sessions!
  "Load all sessions that haven't ended."
  []
  (when-let [node (primer-node)]
    (let [sessions-data (db/query node
                          '(-> (from :primer/sessions [xt/id session/ended-at *])
                               (where (nil? session/ended-at))))]
      (doseq [ctx sessions-data]
        (swap! sessions assoc (:xt/id ctx) ctx))
      (map :xt/id sessions-data))))

```

### Temporal Queries

```clojure
(defn history
  "Get checkpoint history for session."
  [session-id]
  (when-let [node (primer-node)]
    (db/entity-history node :primer/sessions session-id)))

(defn at
  "Get session ctx at point in time (read-only, doesn't affect atom)."
  [session-id inst]
  (when-let [node (primer-node)]
    (first (db/query node
             (xt/template
               (from :primer/sessions
                 [{:xt/id ~session-id} *]))
             {:current-time inst}))))

(defn get-at
  "Get specific path at point in time."
  [session-id path inst]
  (get-in (at session-id inst) path))

```

### SSE Integration

```clojure
;; Add watch that triggers SSE refresh on any session change
(defonce _sessions-watch
  (add-watch sessions :sse-auto-refresh
    (fn [_ _ old-val new-val]
      (when (not= old-val new-val)
        (require 'seon.web.sse)
        ((resolve 'seon.web.sse/refresh-all!))))))

```

---

## Schema Updates

Extend `seon.primer.schema` with session-aware schemas:

```clojure
(def SessionCtx
  "Full session context - everything needed for one session."
  [:map
   ;; Required session identity
   [:session/id :string]

   ;; Optional session metadata
   [:session/child-id {:optional true} :string]
   [:session/device-id {:optional true} :string]
   [:session/started-at {:optional true} :time/instant]
   [:session/last-checkpoint {:optional true} :time/instant]
   [:session/ended-at {:optional true} :time/instant]

   ;; Primer state (existing schemas)
   [:primer/current-scene {:optional true} Scene]
   [:primer/child-profile {:optional true} ChildProfile]
   [:primer/story-facts {:optional true} [:set :keyword]]
   [:primer/behaviors {:optional true} [:map-of :keyword Behavior]]])

```

---

## REPL Workflow Example

Agent can inspect and manipulate state easily:

```clojure
;; === Agent Debugging Session ===

;; See all active sessions
(ctx/list-sessions)
;; => ["session-123"]

;; Inspect current state
(ctx/get "session-123")
;; => {:session/id "session-123"
;;     :primer/current-scene {:scene/id "forest" ...}
;;     ...}

;; Check specific value
(ctx/get-in "session-123" [:primer/current-scene :scene/id])
;; => "forest"

;; Update scene (triggers UI refresh via SSE)
(ctx/update! "session-123" assoc :primer/current-scene
  {:scene/id "owl-dialogue"
   :scene/template :dialogue/exchange
   :scene/params {:character :owl
                  :greeting "Who goes there?"}
   :scene/actions [{:action/id :ask-name
                    :action/label "What's your name?"
                    :action/handler 'seon.primer.actions/ask-name}]})

;; Save progress
(ctx/checkpoint! "session-123")

;; === Time Travel (for debugging) ===

;; What did the scene look like 5 minutes ago?
(ctx/at "session-123"
  (.minus (java.time.Instant/now)
          (java.time.Duration/ofMinutes 5)))

;; See checkpoint history
(ctx/history "session-123")

;; Restore to earlier state
(ctx/load-at! "session-123" #inst "2024-12-24T08:00:00Z")

;; === Session Lifecycle ===

;; Create new session
(ctx/create! "new-session"
  {:session/child-id "emma"
   :primer/current-scene {:scene/id "intro" ...}})

;; End session (save to DB)
(ctx/destroy! "new-session" {:persist? true})

```

---

## Auto-Checkpoint Strategy

For reliability without performance impact:

```clojure
;; Option 1: Checkpoint on significant transitions
;; In action handler:
(defn handle-scene-transition [session-id next-scene]
  (ctx/update! session-id assoc :primer/current-scene next-scene)
  (ctx/checkpoint! session-id))  ; Significant moment - save it

;; Option 2: Periodic checkpoint (background)
(defn start-checkpoint-scheduler! []
  (future
    (loop []
      (Thread/sleep 60000)  ; Every minute
      (ctx/checkpoint-all!)
      (recur))))

;; Option 3: Checkpoint-on-idle
;; After N seconds of no activity, checkpoint

```

Recommendation: **Checkpoint on significant transitions** (scene changes, story progress). This captures meaningful states without overhead of every keystroke.

---

## Migration Path

Current `seon.primer.state` can be preserved while adding new capabilities:

1. **Stage 1**: Create `ctx.clj` alongside existing `state.clj`
2. **Stage 2**: Migrate handlers to use `ctx/update!` instead of `state/update-ctx!`
3. **Stage 3**: Add persistence calls at key points
4. **Stage 4**: Remove old `state.clj`

The global `ctx` atom in `state.clj` becomes `sessions` atom in `ctx.clj` with session-id keys.

---

## Implementation Considerations

1. **Atom vs Sessions Map**: Using `{session-id -> ctx}` map allows:
   - Multiple concurrent sessions
   - Session isolation
   - Easy session enumeration

2. **Schema Validation**: Keep validation on every write for safety. Schema errors during development are better than corrupt state in production.

3. **SSE Refresh Scope**: Current SSE refresh is global (`refresh-all!`). For multi-session, may need to track which session changed and only refresh relevant clients. Can defer this - single-session use case works with current approach.

4. **Node Access Pattern**: Using `primer-node` function that resolves from running system avoids needing to pass node through every function while still being testable (can mock the resolution).

5. **XTDB Table**: Single `:primer/sessions` table with session-id as `:xt/id`. XTDB's temporal features handle history automatically.

---

## Summary

The ctx sync primitives provide:

| Operation | Speed | Storage | Use Case |
|-----------|-------|---------|----------|
| `get`, `get-in` | Instant | Atom | Runtime reads |
| `update!` | Instant | Atom | Runtime writes |
| `checkpoint!` | ~10ms | XTDB | Save progress |
| `load!` | ~10ms | XTDB->Atom | Session resume |
| `at`, `history` | ~10ms | XTDB (read) | Debugging, replay |

The API is designed for:
- **Runtime performance**: All reads/writes hit atom (in-memory)
- **Durability**: Explicit checkpoints to XTDB
- **Debuggability**: Full temporal query support
- **REPL-friendliness**: Simple functions, inspectable state

This design follows Seon's patterns:
- DB node passed implicitly via system lookup
- Malli validation on writes
- Clean separation of concerns
- Temporal capabilities via XTDB
