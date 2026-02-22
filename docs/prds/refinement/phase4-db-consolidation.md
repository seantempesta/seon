# Phase 4: DB Consolidation

**Status:** Ready for implementation
**Depends on:** Phases 1-3 (done)
**Branch:** `feature/refinement`

---

## Goal

Eliminate redundant databases and dual-writes. After this phase:
- The graph DB is named `seon.runtime` (was `seon-graph`)
- `seon.orchestrator` DB is killed (session persistence removed, only runtime registry used)
- AI sessions/messages move from master `seon` DB to per-namespace DBs
- No more dual-writes anywhere

## The Two-DB Model

| DB Name | Contents | Size Profile |
|---|---|---|
| `seon.runtime` | Code graph, runtime instances, agent run metadata | Small — metadata only |
| `seon.{namespace}` (per-agent) | AI sessions, messages, ctx state, domain data | Grows — conversation content |

**Deleted DBs:**
- `seon-graph` → renamed to `seon.runtime`
- `seon.orchestrator` → killed
- `seon` (master) → AI data moves to namespace DBs

## Changes

### 1. Rename graph DB: `seon-graph` → `seon.runtime`

**File: `src/seon/system.clj`** — `build-graph-uri` function
- Change `"seon-graph"` to `"seon.runtime"` in the URI builder
- Update log messages

**Note:** Users must delete `data/datalevin/seon-graph/` on next restart (auto-recreates as `seon.runtime`).

### 2. Kill orchestrator dual-write: `src/seon/orchestrator/session.clj`

**Delete these functions** (they write to the now-deleted `seon.orchestrator` DB):
- `store-session!`
- `update-session-status!`
- `load-session-from-db`
- `load-active-sessions-from-db`
- `get-dl-conn`
- The `dl-schema` def
- The `dl-mgr` atom

**Keep:**
- `session-registry` atom (in-memory process handles — NOT serializable)
- All public API functions

**Modify `start-agent-session!`:**
- Remove `(store-session! nil session-info)` call
- Remove the `try` wrapper around `runtime/register!` — it's now the ONLY write, so let errors propagate
- Remove dual-write comment

**Modify `stop-agent-session!`:**
- Remove `(update-session-status! nil id :stopped stopped-at)` call
- Remove the `try` wrapper around `runtime/unregister!`

**Modify `recover-sessions!`:**
- Remove `(load-active-sessions-from-db nil)` — use `runtime/instances` filtered by `:external` instead
- Remove `(update-session-status! nil ...)` — use `runtime/unregister!` instead

**Modify `get-agent-session`:**
- Remove the Datalevin fallback (`load-session-from-db`) — only check in-memory registry
- Historical sessions can be queried from runtime registry

**Modify `init!`:**
- Remove `(reset! dl-mgr mgr)` — no more DL connection manager needed
- Keep pool initialization

### 3. Move AI sessions to namespace DBs: `src/seon/ai/datalevin.clj`

Currently `get-conn` returns the master `seon` DB connection. Change to use per-namespace connections.

**Add namespace parameter to `get-conn`:**
- Accept optional namespace parameter
- When namespace provided, use `conn/get-namespace-conn!` instead of `conn/get-master-conn!`
- When nil, fall back to master (for backward compat during migration)

**Thread namespace through writes:**
- `save-session!` — extract namespace from session entity (`::ai/namespace`)
- `save-message!` — needs session's namespace (look up from session entity or thread through)
- `update-session!` — same

**Key insight:** The AI session entity already has `::ai/namespace` on it. Use that to determine which DB to write to.

### 4. Add `running-sessions` query: `src/seon/runtime.clj`

```clojure
(defn running-sessions
  "All external running instances from Datalevin."
  [_request]
  (when-let [c @conn]
    (mapv first
      (d/q '[:find (pull ?e [*])
             :where
             [?e :seon.runtime/namespace _]
             [?e :seon.runtime/status :running]
             [?e :seon.runtime/location :external]]
           @c))))
```

### 5. Update tests

- `test/seon/orchestrator/session_test.clj` — remove any tests that depend on `seon.orchestrator` DB reads
- `test/seon/runtime_test.clj` — add test for `running-sessions`
- `test/seon/ai/datalevin_test.clj` — verify writes go to namespace DB

## Verification

1. Launch agent → runtime instance in `seon.runtime` DB, messages in namespace DB
2. No writes to `seon.orchestrator` or `seon` master DB
3. `(runtime/running-sessions {})` returns the agent
4. Full test suite passes (529+ tests, 0 failures)

## Migration Note

This is a breaking change for existing data:
- Old sessions in `seon.orchestrator` DB are abandoned (ephemeral anyway)
- Old AI messages in `seon` master DB are abandoned (can be migrated later if needed)
- Graph data in `seon-graph` is abandoned (rescanned on startup)

Users should:
```bash
rm -rf data/datalevin/seon-graph/ data/datalevin/seon-orchestrator/ data/datalevin/seon/
```
