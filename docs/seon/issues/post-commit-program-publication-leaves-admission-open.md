---
type: issue
status: open
severity: blocker
tags: [issue, pod, schema]
---

# Post-commit program publication leaves runtime admission open

## Problem

Schema and function declaration candidates are validated before their database
transaction, but runtime wrapper and instrumentation publication happens after
that commit. If post-commit publication fails, Seon records a core error while
continuing to admit work against the older process-local projection. Durable
program facts and the executing runtime can therefore describe different
generations.

## Evidence

The exact call paths and dependency behavior are recorded in
[[../../prds/database-lifecycle-recovery/research/database-lifecycle-source-audit-2026-07-14]]
and
[[../../prds/database-lifecycle-recovery/research/malli-runtime-schema-authority-audit-2026-07-13]].
Candidate construction is complete and pre-commit validation rejects invalid
dependent contracts. The remaining post-commit catch path records the fault but
does not close agent, eval, schedule, or web-command admission and does not
reconstruct the committed generation from database facts.

## Owner

The one runtime admission and schema/program publication path spanning
`seon.eval`, `seon.schema`, instrumentation, `seon.client`, and agent-loop entry
points. The fix must strengthen that mechanism in place, not add a second
registry or a rollback transaction.

## Acceptance

- One fail-closed runtime admission state covers every autonomous work entry.
- A deterministic post-commit publication failure records one bounded owning
  fault and rejects later work with a typed unavailable value.
- The runtime reconstructs and publishes the committed program generation, or
  remains unavailable and fails readiness.
- No database history is undone and no old process-local projection continues
  serving work after the failure.
- Focused eval/schema/instrumentation tests and config-free live restart prove
  recovery from committed facts.
