# Phase 5: Unified Connection API — Keyword Identity

**Status:** Not started
**Depends on:** Phase 4 (DB Consolidation — done)
**Branch:** `feature/refinement`

---

## Goal

Replace three hardcoded connection functions with one unified `get-conn!` that uses keyword identity. Maximize database separation for fault isolation.

## Background

Phase 4 consolidated the orchestrator's dual-write and renamed `seon-graph` → `seon.runtime`. But the connection API still has three separate functions (`get-master-conn!`, `get-runtime-conn!`, `get-namespace-conn!`) that are thin wrappers over the same internal `get-or-create-connection!` with hardcoded string db-names scattered across callers.

**Key discovery:** Datalevin's `lisp-case` normalizes all db-names — dots become hyphens on the wire. `"seon.runtime"` → `"seon-runtime"`. This is transparent to callers.

## Database Architecture (4 tiers)

Maximally separated. Each tier has independent LMDB locks — a deadlocked write to one cannot block another.

| Keyword | Datalevin name | Contents | Volatility |
|---------|---------------|----------|------------|
| `:seon.ai` | `seon-ai` | AI sessions + messages | High (append-mostly) |
| `:seon.flow` | `seon-flow` | Flow traces + flow snapshots | Medium |
| `:seon.runtime` | `seon-runtime` | Code graph + instance registry + agent runs | Medium (graph stable, registry dynamic) |
| `:seon.{ns}` | `seon-{ns}` | Per-namespace agent context | Per-agent |

**Why split AI + flow from runtime?**
- Zero cross-entity joins between AI sessions and flow traces (confirmed by audit)
- Zero cross-entity joins between AI/flow and the code graph
- Separate LMDB locks = independent deadlock domains
- Agent runs reference runtime instances via Datalevin ref → must stay in `:seon.runtime`

**Why not merge everything?**
- Different schemas, different write patterns, different volatility
- Fault isolation: scanner bulk-writes shouldn't risk blocking AI session persistence
- The deadlock history (documented in MEMORY.md) makes lock isolation valuable

## API Design

### Single function: `get-conn!`

```clojure
(conn/get-conn! {::conn/manager mgr
                 ::conn/db :seon.runtime
                 ::conn/schema merged-schema})

;; For namespace DBs — namespace symbol → keyword:
(conn/get-conn! {::conn/manager mgr
                 ::conn/db (keyword (str 'seon.trading))})
```

Internally: `(name :seon.runtime)` → `"seon.runtime"` → Datalevin normalizes to `"seon-runtime"`. Cache key = the keyword itself.

### Supporting functions

```clojure
(conn/close-conn!  {::conn/manager mgr ::conn/db :seon.ai})
(conn/reconnect!   {::conn/manager mgr ::conn/db :seon.runtime ::conn/schema ...})
(conn/connection-stats {::conn/manager mgr})  ; returns keyword keys
```

### Config in system.edn

```edn
:seon/runtime-db
{:connection-manager #ig/ref :seon.db.datalevin/connections
 :db :seon.runtime}
```

Components receive `:db` keyword from Integrant config.

## Implementation Steps

### Step 1: Unify `conn.clj` API

- Delete `get-master-conn!`, `get-runtime-conn!`, `get-namespace-conn!`
- Add `get-conn!` taking `::db` (keyword) + optional `::schema`
- Rename `close-namespace-conn!` → `close-conn!` taking `::db`
- Simplify `reconnect!` — just takes `::db`, no `case` dispatch
- Update `connection-stats` — keyword cache keys
- Update schemas: `::db` is `[:keyword]`
- Delete `master-db-name`, `runtime-db-name` constants, `namespace->db-name`

### Step 2: Rename master DB `:seon` → `:seon.ai`

- `src/seon/ai/datalevin.clj` — `::conn/db :seon.ai`
- `test/seon/flow/trace_test.clj` — `::conn/db :seon.ai` (or `:seon.flow`, see step 3)

### Step 3: Split flow traces to `:seon.flow`

- `src/seon/flow/trace.clj` — `::conn/db :seon.flow`
- Move flow snapshot writes from `:seon.runtime` to `:seon.flow` (if no ref joins — verify)

### Step 4: Update all callers

| File | Current | New |
|------|---------|-----|
| `src/seon/system.clj` | `get-runtime-conn!` | `get-conn!` + `::conn/db :seon.runtime` |
| `src/seon/runtime.clj` | `get-runtime-conn!` | `get-conn!` + `::conn/db :seon.runtime` |
| `src/seon/ai/datalevin.clj` | `get-master-conn!` | `get-conn!` + `::conn/db :seon.ai` |
| `src/seon/flow/trace.clj` | `get-master-conn!` | `get-conn!` + `::conn/db :seon.flow` |
| `src/seon/render.clj` | `get-runtime-conn!` | `get-conn!` + `::conn/db :seon.runtime` |
| `src/seon/ns/routes.clj` | `get-runtime-conn!` | `get-conn!` + `::conn/db :seon.runtime` |
| `src/seon/db.clj` | case dispatch | keyword-based |
| `src/seon/orchestrator/session.clj` | `get-namespace-conn!` | `get-conn!` + `::conn/db (keyword ...)` |
| `env/dev/clj/user.clj` | `get-master-conn!` | `get-conn!` + `::conn/db :seon.ai` |
| `src/seon/dev/test.clj` | `get-master-conn!` | `get-conn!` + `::conn/db :seon.ai` |
| `test/seon/flow/trace_test.clj` | `get-master-conn!` | `get-conn!` + `::conn/db :seon.flow` |

### Step 5: Update system.edn + config validation

- Add `:db :seon.runtime` to `:seon/runtime-db` config
- Update `system/config.clj` schema

### Step 6: Verify + clean up

- `(user/reset)` — system starts
- `(user/status)` — all healthy
- Full test suite passes
- Old data directories can be cleaned: `data/datalevin/` hex dirs for old "seon" DB

## Migration Note

Old `seon` database directory (hex-encoded `73656F6E`) becomes stale after rename. Users should:
```bash
# After verifying new DBs work:
# rm -rf data/datalevin/73656F6E/  (old "seon" master)
```

## Open Question

**Flow snapshots**: Currently in `:seon.runtime` (via `seon.runtime/snapshot-topology!`). Should they move to `:seon.flow`? Depends on whether any query joins snapshots with runtime instances. Verify during step 3.
