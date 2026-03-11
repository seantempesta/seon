---
type: component
status: production
---
# Database

> Sole database API for Seon — all reads and writes route through `seon.db`, serialized by core.async flow processes.

## Purpose

Seon uses Datalevin (embedded Datalog on LMDB) as its only database, running as a separate JVM process. The database component exists to provide a single API surface (`seon.db`) that enforces Malli validation on every write, auto-derives Datalevin schemas from the Malli registry, and serializes all access through infrastructure flow processes (see [[components/flow-topology]]). This prevents concurrent LMDB corruption, provides observability (every request is a traceable flow message), and allows transparent retry on connection failure.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.db` | `src/seon/db.clj` | Public API: `transact!`, `query`, `pull-by-name`, flow routing |
| `seon.db.schema` | `src/seon/db/schema.clj` | Malli-to-Datalevin schema bridge (see [[components/schema-system]]) |
| `seon.db.tx` | `src/seon/db/tx.clj` | Transaction metadata: timestamps, caller, source on every write |
| `seon.db.datalevin.conn` | `src/seon/db/datalevin/conn.clj` | Connection manager: per-DB locking, caching, Integrant component |
| `seon.db.datalevin.server` | `src/seon/db/datalevin/server.clj` | External Datalevin JVM process: start, adopt, health, suspend/resume |
| `seon.db.datalevin.writer` | `src/seon/db/datalevin/writer.clj` | Infrastructure flow writer step-fn |
| `seon.db.datalevin.reader` | `src/seon/db/datalevin/reader.clj` | Infrastructure flow reader step-fn |
| `seon.db.datalevin.backup` | `src/seon/db/datalevin/backup.clj` | Backup: pause writer, copy LMDB dir, resume, prune |

## Public API Surface

### Write Path (`seon.db/transact!`)

`(transact! db-name tx-data)` or `(transact! db-name tx-data opts)`

1. Extracts all attribute keywords from tx-data (map entities and vector tuples).
2. **Validates attributes** — every non-`:db/*` attribute must be registered in `seon.schema`. Throws if unregistered.
3. **Validates values** — each attribute value is checked against its Malli schema. Throws on first failure with the attribute, expected schema, actual value, and Malli explanation.
4. **Ensures Datalevin schema** — for any attribute missing from the live Datalevin schema, derives the type from Malli and calls `d/update-schema`.
5. Routes through the infrastructure flow writer (or direct `d/transact!` in test mode).

### Read Path

- **`query [db-name datalog-query & inputs]`** — Datalog query. Routes through flow reader, or direct with retry.
- **`pull-by-name [db-name selector eid]`** — Pull entity by selector and eid.
- **`pull-many-by-name [db-name selector eids]`** — Pull multiple entities.
- **`entity-by-name [db-name eid]`** — Get entity by eid.

All read functions accept a db-name keyword (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`, or any namespace keyword) and resolve to a connection via the connection manager.

### Connection Resolution

- **`resolve-conn [db-name]`** — Resolve db-name to a raw Datalevin connection. Used by `ensure-schema!` and callers needing raw access.

### Flow Coordination

- **`pause-writer! []`** — Pause infrastructure flow writer (for backups).
- **`resume-writer! []`** — Resume after backup.

### Direct Mode

`*direct-mode*` is a dynamic var (default `false`). When bound to `true`, reads and writes bypass the infrastructure flow and use Datalevin directly. Used in two contexts:

- **Test fixtures** that don't have a running infrastructure flow.
- **Integrant init** during bootstrap before the flow is started.

## Dependencies

- **Uses**: [[components/schema-system]] (validation, schema derivation), `seon.flow.topology` (via requiring-resolve to break circular dep), `seon.flow.msg` (message envelope), `seon.db.datalevin.conn` (connection manager), `datalevin.core`
- **Used by**: Every domain namespace, `seon.ctx` (state persistence), `seon.graph.ingest` (knowledge graph writes), `seon.ai.datalevin` (session persistence), `seon.flow.trace` (event persistence), `seon.db.datalevin.backup` (pause/resume)

## How Data Flows

### Write Path (Production)

```
Caller
  -> db/transact! (validate attrs, validate values, ensure-schema!)
    -> flow-request! (create promise, inject into flow)
      -> infrastructure flow writer step-fn
        -> conn/get-conn! (from connection manager)
        -> d/transact! (with timeout, retry on connection error)
        -> reply envelope -> reply-router -> deliver promise
    -> caller receives result
```

### Write Path (Test / Direct Mode)

```
Caller
  -> db/transact! (validate attrs, validate values, ensure-schema!)
    -> resolve-conn (from connection manager)
    -> d/transact! (direct)
```

### Read Path (Production)

```
Caller
  -> db/query (or pull-by-name, etc.)
    -> flow-request! -> infrastructure flow reader step-fn
      -> execute-query (dispatches on :q/:pull/:pull-many/:entity)
      -> reply envelope -> reply-router -> deliver promise
```

### Read Path (Direct Mode)

```
Caller
  -> db/query
    -> with-retry (resolve-db, execute, reconnect on error)
```

## Multi-Database Strategy

Datalevin runs as a single server process managing multiple databases. Each database is identified by a keyword:

| Database | Contents | Typical Access Pattern |
|----------|----------|----------------------|
| `:seon` | Core domain data | Domain namespaces read/write |
| `:seon.runtime` | Code graph, namespace instances, flow registry | Graph ingest, lifecycle, introspection |
| `:seon.ai` | AI sessions, conversation messages | Agent lifecycle, observatory |
| `:seon.flow` | Flow traces, snapshots | Tracing, debugging |
| `:seon.{ns}` | Per-namespace agent context | Agent sessions (dynamic, on-demand) |

The connection manager (`seon.db.datalevin.conn`) caches connections per database keyword. Core databases (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`) survive `(user/reset)` via Integrant suspend/resume. Non-core connections are closed on suspend.

## Connection Manager Design

The connection manager solves a critical concurrency problem: when two threads simultaneously request a database that hasn't been opened yet on the Datalevin server, `open-kv` races and corrupts LMDB state.

**Solution**: Per-DB locking via `ConcurrentHashMap`. Each database name gets its own lock object. The fast path (cached, valid connection) requires no locking. The slow path (first connection or reconnection) holds the per-DB lock during `d/get-conn`.

**Key operations**:

- `get-conn!` — Get or create connection (fast path: no lock; slow path: per-DB lock)
- `reconnect!` — Force close + fresh connection
- `sweep-stale-connections!` — Remove dead connections from cache
- `close-non-core-connections!` — Used during suspend
- `health` — TCP probe + connection count

## Design Decisions

**Separate JVM for Datalevin.** The database runs as an external process started via `bin/run-datalevin`. This means killing Seon does NOT kill the database. Data is safe across Seon restarts. Integrant adopts an existing server if the port is already open.

**Positional arguments on `seon.db`.** This is the one namespace where map-in/map-out does not apply. The API mirrors `datalevin.core` for drop-in familiarity: `(query :seon '[:find ?e ...])`.

**Requiring-resolve for circular deps.** `seon.db` needs `seon.flow.topology` (to inject into the flow) and `seon.runtime` (to find the flow), but those namespaces depend on `seon.db`. The circular dep is broken with `requiring-resolve` at call time, not load time.

**Writer owns connections.** The writer and reader step-fns maintain their own `::owned-conns` map. On first use of a database, they acquire a connection from the connection manager and cache it in their flow state. This avoids per-request connection lookups.

**Retry on connection error.** Both the writer step-fn and the direct-mode read path detect connection errors (refused, reset, broken pipe, timeout) and retry once with a fresh connection. The connection manager evicts stale entries on reconnect.

**Transaction metadata via `seon.db.tx`.** Every write can include `:db/current-tx` metadata with timestamp, caller namespace, source (`:agent`, `:system`, `:user`, `:repl`, `:migration`), and optional session/operation info. Datalevin does not auto-add timestamps like Datomic, so this is the auditability mechanism.

## Refactoring Opportunities

- **Duplicate `connection-error?`** — Defined in both `seon.db` and `seon.db.datalevin.conn`. The `seon.db` version is private and identical. Should use `conn/connection-error?` directly.
- **Writer and reader step-fns are ~220 lines each of very similar code** — The retry-with-reconnect pattern is duplicated. Could extract a shared `with-flow-retry` utility.
- **`transact!` schema uses `:any`** — `[:sequential :any]` for tx-data and `:any` for return. These are the correct dynamic types for Datalevin tx-data, but the `:malli/schema` metadata is aspirational rather than enforced.
- **`*conn-manager*` dynamic var** — Exists for test override but is separate from `*direct-mode*`. The two testing mechanisms could potentially be unified.
- **`seon.agent.helpers`** — References SQL helpers that predate the Datalevin migration. Marked deprecated in the namespace inventory.
