---
type: issue
status: superseded
severity: friction
tags: [issue, agent, flow]
---
# Agent Pool Health-Check SIGKILLs Idle JVMs

## Summary

`seon.flow.pool` runs a health-checker every 30s that evals `:ok` against each idle agent JVM via nREPL. JVMs that fail the check are killed and replaced. Manual testing confirms the eval itself succeeds (returns `":ok"` as expected). However, the JVMs die externally with exit code 137 (SIGKILL) every ~90s during normal idle, and the pool keeps respawning them.

The pool is **disabled** (`:seon.flow/pool {:enabled? false}` in `resources/system.edn`) until Phase 3 step 5 refactors the pool's atom-based registry to flow state and addresses the SIGKILL-cycle root cause.

## Symptoms

- Log pattern: `Agent JVM ready {:port 7900, :pid 9476}` followed ~90s later by `Agent JVM process dead {:port 7900, :exit-code 137}`, then `Spawning agent JVM {:port 7903}`, repeating indefinitely
- Pool burns through port range 7900-7999 over hours
- No application-level kill calls produce SIGKILL — `kill-process!` (line ~141) uses `.destroy` which is SIGTERM (143)
- `cleanup-stale-agents!` uses `kill-pid! → kill -9` but only runs at startup
- Source of SIGKILL is external: macOS memory pressure or a parent-process kill we haven't traced

## Workaround (Phase 3 Demo)

`seon.session/launch!` (commit `baa2b41`) bypasses the pool entirely. It calls `#'seon.flow.pool/spawn-agent-jvm!` directly to spawn an agent JVM on demand, picks a port outside the pool range (7980+), and manages lifecycle without the health-checker. Verified end-to-end via the Phase 3 demo target.

## Real Fix (Phase 3 Step 5)

`docs/prds/datahike-migration/phase-3-harness-migration.md` §"State migration" lists the pool as a touch-time atoms-to-flow-state target:

| Today | Phase 3 |
|---|---|
| `all-jvms` atom | Registry process holding `{:jvms {port -> entry}}` in flow state. Acquisitions/releases also persist to `:seon.session`. |

The refactor is the natural place to also fix the SIGKILL-cycle root cause: investigate macOS memory pressure on the agent JVM heap (currently `-Xms256m -Xmx512m`), check whether something downstream of `nrepl-eval!` retains classloaders, or whether the JVMs are accumulating native memory from parent-process tracking. Until that work happens, the pool stays off.

## Related

- `src/seon/flow/pool.clj` — current implementation (~930 LOC)
- `docs/prds/datahike-migration/phase-3-harness-migration.md` §"State migration"
- `src/seon/session.clj` — Phase 3 demo's pool-bypass path

## Superseded (2026-06-28 audit)

flow/pool.clj is a disabled JVM nREPL pool; active-pod agents are CLJS runtimes, not nREPL sessions.
