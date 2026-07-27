---
type: issue
status: superseded
severity: blocker
tags: [issue, runtime, database]
---

# JVM result surface does not implement R32 result-symbol handles

## Evidence (containment audit 2026-07-23, verified)

Direct run-holding process batches discard their retained-value map; `result/<id>`
is never bound in SCI on the JVM tier — R32's process-identity-backed
handles (registry, wipe-on-restart, steering to re-derive) are
unimplemented there. Citations:
research/sci-containment-surface-audit-2026-07-23.md (High #5).

## Direction

Fold into the P6 invoke-family implementation (R32 registry is already
its deliverable); until then agents on the JVM tier cannot address
prior results, a real capability regression vs the pod.

## Acceptance

- P6's R32 registry lands; `result/<id>` resolves on the run-holding process, dies
  loudly on that platform's restart, steers to re-derivation.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
