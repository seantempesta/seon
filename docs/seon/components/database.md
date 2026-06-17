---
type: component
status: active
tags: [component, database]
---
# Database

> Sole database API for the paused JVM main-app track — all reads and writes route through `seon.db`. Backed by embedded Datahike (in-process LMDB), with per-namespace stores owned by a `core.async.flow`.

`[JVM track — paused]` This doc describes the JVM main-app database layer (embedded in-process Datahike, core.async flow routing), which is paused but not deleted. The ACTIVE track is the CLJS pod: it does NOT embed datahike — it forwards writes over a Unix socket to the central `wire-server` writer (file-backed datahike at `data/clusters/default/store`) and reads local lazy db values. So "embedded / no separate database process" below is true ONLY of this paused JVM track, not of Seon as currently run.

## Status (2026-05-20)

Datahike is the core. `seon.db` dispatches per db-name through dedicated flow processes (`seon.db.datahike.conn-process`), one per database. Migration narrative lives in `docs/prds/datahike-migration/`.

- **`seon.db` public API**: `(transact! :seon.weather ...)`, `(query :seon.weather ...)`, etc. Stable at the call site.
- **Internally**: db-names declared under `:seon.db/flow` in `resources/system.edn` route through the datahike flow.
- **Auto-stamp**: every datahike-routed `transact!` adds `{:seon.db/namespace <db-name>}` to entity maps (Decision 7).

## Purpose

Single database API surface (`seon.db`) that:
- Enforces Malli validation on every write
- Auto-derives Datahike schemas from the Malli registry (`seon.db.datahike.schema`)
- Routes per db-name through dedicated flow processes (`seon.db.datahike.conn-process`), one per database
- Auto-stamps `:seon.db/namespace` so pulled entities are self-describing without consulting a schema (Decision 7)
- Provides a single inspection point for future security filters and policy gates (Decision 9: state lives in flow state, not atoms)

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.db` | `src/seon/db.clj` | Public API: `transact!`, `query`, `pull-by-name`, flow routing |
| `seon.db.schema` | `src/seon/db/schema.clj` | Malli-to-Datahike schema bridge (see [[components/schema-system]]) |
| `seon.db.tx` | `src/seon/db/tx.clj` | Transaction metadata: timestamps, caller, source on every write |
| `seon.db.datahike.conn-process` | `src/seon/db/datahike/conn_process.clj` | Per-db flow process: owns the embedded Datahike conn, serializes reads/writes |
| `seon.db.datahike.schema` | `src/seon/db/datahike/schema.clj` | Malli-derived schema installation against an embedded Datahike store |
| `seon.db.datahike.flow` | `src/seon/db/datahike/flow.clj` | Flow wiring: spawn/teardown per-db conn-processes |
| `seon.db.datahike.system` | `src/seon/db/datahike/system.clj` | Integrant component: starts the datahike flow, registers db-names |
| `seon.db.datahike.tx-bus` | `src/seon/db/datahike/tx_bus.clj` | Post-commit tx broadcast for subscribers |

## Public API Surface

### Write Path (`seon.db/transact!`)

`(transact! db-name tx-data)` or `(transact! db-name tx-data opts)`

1. Extracts all attribute keywords from tx-data (map entities and vector tuples).
2. **Validates attributes** — every non-`:db/*` attribute must be registered in `seon.schema`. Throws if unregistered.
3. **Validates values** — each attribute value is checked against its Malli schema. Throws on first failure with the attribute, expected schema, actual value, and Malli explanation.
4. **Ensures Datahike schema** — for any attribute missing from the live Datahike schema, derives the type from Malli and installs it via the schema bridge.
5. Routes through the per-db conn-process flow (or direct `d/transact` in test mode).

### Read Path

- **`query [db-name datalog-query & inputs]`** — Datalog query. Routes through the per-db conn-process, or direct with retry.
- **`pull-by-name [db-name selector eid]`** — Pull entity by selector and eid.
- **`pull-many-by-name [db-name selector eids]`** — Pull multiple entities.
- **`entity-by-name [db-name eid]`** — Get entity by eid.

All read functions accept a db-name keyword (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`, or any namespace keyword) and resolve to a connection via the conn-process.

### Connection Resolution

- **`resolve-conn [db-name]`** — Resolve db-name to a raw Datahike connection. Used by `ensure-schema!` and callers needing raw access.

### Flow Coordination

- **`pause-writer! []`** — Pause the per-db conn-process (for backups); calls `flow/pause` then `flow/ping` to confirm quiescence.
- **`resume-writer! []`** — Resume the conn-process after backup.

### Direct Mode

`*direct-mode*` is a dynamic var (default `false`). When bound to `true`, reads and writes bypass the flow and call Datahike directly. Used in two contexts:

- **Test fixtures** that don't have a running datahike flow.
- **Integrant init** during bootstrap before the flow is started.

## Dependencies

- **Uses**: [[components/schema-system]] (validation, schema derivation), `seon.flow.topology` (via requiring-resolve to break circular dep), `seon.flow.msg` (message envelope), `seon.db.datahike.conn-process` (per-db flow owner), `datahike.api`
- **Used by**: Every domain namespace, `seon.ctx` (state persistence), `seon.graph.ingest` (knowledge graph writes), `seon.ai.session` (session persistence), `seon.flow.trace` (event persistence)

## How Data Flows

### Write Path (Production)

```
Caller
  -> db/transact! (validate attrs, validate values, ensure-schema!)
    -> flow-request! (create promise, inject into flow)
      -> per-db conn-process step-fn
        -> d/transact (against the embedded conn it owns)
        -> reply envelope -> reply-router -> deliver promise
    -> caller receives result

```

### Write Path (Test / Direct Mode)

```
Caller
  -> db/transact! (validate attrs, validate values, ensure-schema!)
    -> resolve-conn (from the conn-process registry)
    -> d/transact (direct)

```

### Read Path (Production)

```
Caller
  -> db/query (or pull-by-name, etc.)
    -> flow-request! -> per-db conn-process step-fn
      -> execute-query (dispatches on :q/:pull/:pull-many/:entity)
      -> reply envelope -> reply-router -> deliver promise

```

### Read Path (Direct Mode)

```
Caller
  -> db/query
    -> with-retry (resolve-db, execute)

```

## Multi-Database Strategy

Datahike runs **in-process**. Each db-name has a dedicated conn-process that owns a single embedded Datahike connection over its own LMDB directory.

| Database | Contents | Typical Access Pattern |
|----------|----------|----------------------|
| `:seon` | Core domain data | Domain namespaces read/write |
| `:seon.runtime` | Code graph, namespace instances, flow registry | Graph ingest, lifecycle, introspection |
| `:seon.ai` | AI sessions, conversation messages | Agent lifecycle, observatory |
| `:seon.flow` | Flow traces, snapshots | Tracing, debugging |
| `:seon.{ns}` | Per-namespace agent context | Agent sessions (dynamic, on-demand) |

Core db-names (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`) survive `(user/reset)` via Integrant suspend/resume — their conn-processes hold open conns across restarts. Non-core conn-processes are torn down on suspend.

## Conn-Process Design

Each db-name has one conn-process that owns the embedded Datahike connection and serializes all reads and writes through its inbox. This removes the concurrent-open race that plagued the old external-server design: there is exactly one opener per db-name, and it lives inside the JVM.

**Key operations**:

- The conn-process holds `::conn`, `::db-name`, and `::schema-cache` in its flow state.
- Writes apply Malli-derived schema deltas before `d/transact`.
- Post-commit tx-data is broadcast on the `tx-bus` for subscribers.
- Suspend closes the conn cleanly; resume reopens lazily on next request.

## Design Decisions

**Embedded Datahike.** `[JVM track — paused]` On this paused track the database lives in the Seon JVM — no separate database process and no database TCP port for it; LMDB files live under `data/datahike/<db-name>/`, and bringing the JVM down brings the database down with it. (This is NOT universally true of Seon: the active CLJS pod forwards writes to the separate `wire-server` central writer over a Unix socket.)

**Positional arguments on `seon.db`.** Under the 2026-06-08 rule, positional public fns are a sanctioned first-class shape (named, specced `:catn` slots), so `seon.db` is no longer an *exception* — it is the canonical example of the positional shape. The API mirrors `datahike.api` for drop-in familiarity: `(query :seon '[:find ?e ...])`. Each positional slot still gets its own fully-namespaced spec (see `docs/prds/agent-runtime/research/positional-db-ops-spec-2026-06-08.md`).

**Requiring-resolve for circular deps.** `seon.db` needs `seon.flow.topology` (to inject into the flow) and `seon.runtime` (to find the flow), but those namespaces depend on `seon.db`. The circular dep is broken with `requiring-resolve` at call time, not load time.

**Conn-process owns the conn.** Each per-db conn-process owns its Datahike conn for its entire lifetime. There is no shared connection cache; the flow process IS the cache.

**Retry on transient error.** The direct-mode read path detects transient errors and retries once. The conn-process is single-threaded per db-name, so flow-routed writes never race.

**Transaction metadata via `seon.db.tx`.** Every write can include `:db/current-tx` metadata with timestamp, caller namespace, source (`:agent`, `:system`, `:user`, `:repl`, `:migration`), and optional session/operation info. Datahike does not auto-add timestamps the way Datomic peer libs do, so this is the auditability mechanism.

## Refactoring Opportunities

- **`transact!` schema uses `:any`** — `[:sequential :any]` for tx-data and `:any` for return. These are the correct dynamic types for Datahike tx-data, but the `:malli/schema` metadata is aspirational rather than enforced.
- **`*conn-manager*` dynamic var** — Exists for test override but is separate from `*direct-mode*`. The two testing mechanisms could potentially be unified.
- **`seon.agent.helpers`** — References SQL helpers that predate the migration. Marked deprecated in the namespace inventory.
