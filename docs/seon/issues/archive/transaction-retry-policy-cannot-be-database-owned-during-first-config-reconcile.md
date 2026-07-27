---
type: issue
status: superseded
severity: friction
tags: [issue, runtime, database, architecture]
---

# Make transaction retry policy available during first config reconcile

## Problem

The stale-database retry counts cannot yet be unified as database-owned config
facts because `seon.runtime.state/reconcile!` commits the config singleton
itself. On a fresh apply, that first transaction cannot read its retry policy
from a fact that does not exist until the transaction succeeds.

The candidate consumers also do not represent one failure class:
lifecycle/spawn retries handle stale database values, CAS loss is a semantic
outcome, and generated-identity collisions are probabilistic allocation
failures.

## Evidence

- `src/seon/runtime/state.cljs` retries initial config reconciliation three
  times without an acquired config singleton.
- `src/seon/agent/lifecycle.cljc` and `src/seon/agent.cljs` retry stale database
  values up to 32 times.
- `src/seon/db/id.cljc` and `src/seon/host/context.clj` retry generated identity
  collisions up to 16 times.
- `docs/prds/sci-execution-runtime/research/timeout-census-2026-07-24.md`
  ranks their unification as R50 lane 7, but the first-apply dependency makes
  a direct fact lift non-mechanical.

## Owner

`seon.runtime.state` owns the first-apply boundary. It must expose a settled
source for pre-configuration transaction-conflict policy before ordinary
runtime consumers can share database-owned policy facts. Identity-collision
policy remains with `seon.db.id` and should be projected to host receipt
allocation only after that separate failure class is named.

## Acceptance

- Fresh config apply obtains its bounded conflict policy without reading an
  absent database fact or introducing an environment/inline fallback.
- Stale database conflicts, CAS semantic loss, and identity collisions remain
  distinguishable in returned evidence.
- Lifecycle, spawn, runtime reconcile, database identity allocation, and host
  receipt allocation consume the appropriate named policy facts.
- Focused tests prove first apply, concurrent stale-value convergence, CAS
  loss, and forced identity collision without per-consumer literal defaults.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
