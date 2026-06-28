---
type: issue
status: resolved
severity: cleanup
tags: [issue, database, archive]
---
# Datahike Migration History (Archived)

## Summary

Historical breadcrumb for anyone reading old PRDs or log archives.

The legacy database was Datalevin, run as an external JVM on port :8898. That server crash-looped on startup (the event loop swallowed exceptions and re-submitted `init` in a tight cycle), producing tens of thousands of "server started" lines and hanging Phase 2 boot indefinitely.

We migrated to embedded Datahike (in-process LMDB). The bridge, flow topology, and per-namespace conn-processes live under `src/seon/db/datahike/` (`conn_process.clj`, `flow.clj`, `schema.clj`, `system.clj`, `tx_bus.clj`).

## Current State

- Datahike is the database. Embedded, in-process, no separate JVM, no port.
- Five conn-processes run under `:seon.db/flow`: `:seon.flow :seon.orchestrator :seon.phase2.demo :seon.repl :seon.session`.
- `./bin/run` boots cleanly in ~3s.
- Phase 2 still logs `Phase 2 failed — system is degraded`. This is expected until the legacy `:seon/runtime-db` Integrant key is retired in Phase 5.

## Related

- `docs/prds/datahike-migration/prd.md` — full migration scope
- `docs/prds/datahike-migration/phase-3-harness-migration.md` — current in-flight work
- [[orchestrator/active]] — phase status
