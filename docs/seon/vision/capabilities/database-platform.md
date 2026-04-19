---
type: capability
status: complete
tags: [vision, database]
---
# Reliable Database Platform

Datalevin runs as a separate JVM process, providing ACID transactions on LMDB with Datomic-compatible Datalog queries. The database survives application restarts, supports multiple logical databases for isolation, and handles concurrent access safely through a connection manager with per-DB locking.

## What Exists

- Datalevin as separate JVM process (survives Seon restarts)
- Connection manager with per-DB locking (ConcurrentHashMap, double-checked)
- Multiple databases for logical isolation (`:seon`, `:seon.runtime`, `:seon.ai`, `:seon.flow`)
- Two-phase startup adopts existing Datalevin or starts fresh
- XTDB fully removed from deps and source

## Gaps

None.

## Related

- Components: [[components/database]]
- PRDs: [[prds/datalevin-migration/prd]], [[prds/flow-datalevin-writer/prd]]
