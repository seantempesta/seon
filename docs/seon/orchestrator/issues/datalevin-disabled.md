---
type: issue
status: active
tags: [issue, database]
---
# Datalevin Disabled in Dev

## Summary

The bundled Datalevin server (reference-code/datalevin submodule) crash-loops on startup: `datalevin.server/event-loop` throws on every iteration, the catch swallows the exception without logging, and `init` is re-submitted to the dispatcher — producing hundreds of thousands of "Datalevin server started on port 8898" log lines, 100% CPU, and hanging Seon's Phase 2 boot indefinitely.

The Datalevin path is being replaced by Datahike (see `docs/prds/datahike-migration/`). Phase 5 deletes the source. Until then, Datalevin is **disabled** in `resources/system.edn` — its four Integrant keys (`:seon.db.datalevin/server`, `/connections`, `:seon/runtime-db`, `:seon.graph/scanner`) are absent from the config.

## Symptoms (Pre-Disable)

- `bin/run` hangs at `Phase 2: Starting database and dependent services...` → `Datalevin server ready` → `Starting infrastructure flow...` (never returns)
- `lsof -ti :8898` shows the Datalevin process burning CPU (state `R`, `%CPU` > 100)
- `wc -l logs/datalevin.log` reports tens of thousands of "server started on port 8898" lines within seconds
- Boot timestamp deltas show no progress for minutes

## Resolution (Until Phase 5)

`resources/system.edn` omits the four datalevin Integrant keys. `seon.db.datalevin.*` source files still exist (Phase 5 deletes them). `seon.flow.topology/build-infrastructure!` builds the writer/reader processes only when a connection-manager is supplied — when absent, the infrastructure flow boots with just REPL eval, reply-router, and sinks.

## Boot Reality (Post-Disable)

`./bin/run` boots cleanly in ~3s:

- Phase 1 complete (nREPL :7888, HTTP :8080)
- Phase 2 logs `Phase 2 failed — system is degraded. REPL and HTTP still available.` — **expected**, not an error to fix
- Datahike flow up with five conn-processes: `:seon.flow :seon.orchestrator :seon.phase2.demo :seon.repl :seon.session`
- Test baseline: 4054 pass / 0 fail / 2 errors

## Side Effects

- `seon.health` reports `:status :degraded` because `:datalevin`, `:datalevin-query`, and `:flow-responsive` health checks probe components that no longer exist. Cosmetic; cleaned up in Phase 3 step 12.
- `seon.flow.trace` writes route through datahike now (`:seon.flow` is in `:seon.db/flow :namespaces`). Old log warnings about "Failed to persist flow event" are gone.
- The agent JVM pool is also disabled (separate issue: see [[agent-pool-sigkill-cycle]]).

## Reactivation (Don't)

Don't try to wake Datalevin up. Phase 4 migrates the remaining domain namespaces (`seon.health.*`, `seon.trading.*`, `seon.ai.*`) onto datahike; Phase 5 deletes the source. The whole subsystem disappears.

## Related

- `docs/prds/datahike-migration/prd.md` — overall migration scope
- `docs/prds/datahike-migration/phase-3-harness-migration.md` — current in-flight work
- Commit `d453ae5` — the disable change
