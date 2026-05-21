---
type: issue
status: archived
severity: cleanup
milestone: M1
tags: [issue, database, archive]
---
# Raw Datalevin Connection in agent_runner.clj (Resolved)

## Resolution

Resolved by the datahike migration. `src/seon/flow/agent_runner.clj` no longer touches a datalevin connection — `datalevin.core/get-conn` is gone from the namespace. Agent JVMs reach the database via `seon.db` and the cross-JVM relay (`seon.db.relay`), which routes through the orchestrator's `:seon.db/flow`.

The general principle still holds: only `src/seon/db/` and `src/seon/db/datahike/` may touch a raw connection. Everything else uses `seon.db/transact!`, `seon.db/query`, `seon.db/pull-by-name`, etc.

## Related

- [[components/database]]
- [[components/flow-topology]]
