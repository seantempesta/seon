---
type: capability
status: complete
tags: [vision, database]
---
# Reliable Database Platform

`[JVM track — paused]` On the paused JVM main-app track, Datahike runs embedded in the Seon JVM, providing ACID transactions on LMDB with Datomic-compatible Datalog queries and bitemporal history. The on-disk store survives JVM restarts, supports multiple logical databases for isolation, and handles concurrent access safely through a connection manager with per-DB locking. (The active CLJS pod track does not embed datahike — it forwards writes over a Unix socket to the `wire-server` central writer at `data/clusters/default/store`; reads are local lazy db values.)

## What Exists

- Datahike embedded (in-process LMDB) `[JVM track — paused]` — the on-disk store survives JVM restarts
- Connection manager with per-DB locking (ConcurrentHashMap, double-checked)
- Multiple databases for logical isolation (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`)
- Two-phase startup opens / initializes each connection deterministically
- Bitemporal history via `:keep-history? true`
- Prior database backends (XTDB, Datalevin) fully removed from deps and source

## Gaps

None.

## Related

- Components: [[components/database]]
- PRDs: [[prds/datahike-migration/prd]] (historical: [[prds/datalevin-migration/prd]], [[prds/flow-datalevin-writer/prd]])
