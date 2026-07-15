---
type: issue
status: resolved
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

The agent-runtime portion is cut over to the one admission state. Message
allocation/write, public spawn/delegate/resume, wake, run-loop continuation,
re-drive, ticker, and schedule fire refuse with typed unavailable data before
their owning effect. Repeated refusals record no fault. Drain and diagnosis
controls remain callable. Focused admission, lifecycle, loop, and message proof
passes 51 tests/279 assertions. Cold boot's non-agent-facing `create!` and
`mint!` remain the only pre-publication birth callers.

The owning publication transition, web/call gates, hot-reload ownership, and
application readiness landed in `8f5936ae`. The combined publication boundary
gate passes 57 tests/361 assertions with zero failures. A config-free
`bin/seon restart` rebuilt the writer, client, bootstrap, and CSS; the pod and
writer reopened the same complete coordinate
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a570614-19ab-5c2b-855e-231d990ed4fc`/`536870983`. Admission generation and
the active projection fingerprint both equal `-833049123`, with 1,575 schemas
and 819 function contracts.

Live process proof then closed admission through the real MCP boundary. The
readiness route changed from 200 to 503 while state was `:publishing`; complete
committed reconstruction verified 814 wrappers with no coverage gaps, reopened
the same fingerprint, and readiness returned 200. Root and `/data` served 200,
the root gzip SSE feed emitted a Datastar patch, and the browser shims reported
no console warnings or errors.

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
