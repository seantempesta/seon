# Phase 4: DB Consolidation

**Status:** Done (partial — steps 1, 2, 4 complete; step 3 deferred)
**Depends on:** Phases 1-3 (done)
**Branch:** `feature/refinement`
**Commit:** `94cbfc9`

---

## Goal

Eliminate redundant databases and dual-writes.

## What Was Done

### 1. Renamed graph DB: `seon-graph` → `seon.runtime` ✓

`src/seon/system.clj` — `build-graph-uri` changed to use `"seon.runtime"`.

### 2. Killed orchestrator dual-write ✓

`src/seon/orchestrator/session.clj` — deleted `dl-schema`, `dl-mgr`, `get-dl-conn`, `store-session!`, `update-session-status!`, `load-session-from-db`, `load-active-sessions-from-db`. Session lifecycle now uses only `runtime/register!` / `runtime/unregister!`. The `session-registry` atom stays (holds in-memory process handles).

### 3. Added `running-sessions` to runtime ✓

`src/seon/runtime.clj` — queries all external running instances from Datalevin.

### 4. Tests updated ✓

- `test/seon/orchestrator/session_test.clj` — removed Datalevin-dependent tests
- `test/seon/runtime_test.clj` — added `running-sessions` tests
- **529 tests, 0 failures**

## What Was Deferred

### AI session migration (`seon.ai.datalevin`)

Moving AI sessions/messages from master `seon` DB to per-namespace DBs was deferred. Rationale:
- It's a separate subsystem (message history for replay/learning)
- Not blocking any current work
- Would touch `seon.ai`, `seon.ai.claude`, and their tests
- Can be a standalone task when there's a concrete benefit

The master `seon` DB still exists for AI data. The `seon.orchestrator` DB is no longer written to.

## Known Issues

- Test warnings: "Failed to persist runtime instance ... Calling close-transact-kv without opening" — expected in tests without a real graph DB. Not failures.

## Migration Note

Users should clean old data:
```bash
rm -rf data/datalevin/seon-graph/
```
