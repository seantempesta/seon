---
type: issue
status: open
severity: blocker
tags: [issue, web, database, flow]
---

# Failed render fault evidence retriggered the same reactive failure

## Evidence

A missing namespace schema caused one deterministic Datastar render failure.
The writer then logged the identical Datahike error every three to five seconds
for more than two minutes. The failed computation correctly widened its
dependency plan to `:all`, but `seon.error/record!` persisted the failure as a
new transaction. That transaction matched `:all`, scheduled the same failed
render, and recorded another fault indefinitely.

This violates bounded failure behavior even though widening correctly permits
an unrelated schema repair to reach the computation.

## Owner

The existing `seon.error` persistence transaction and `seon.reactive` failed
registration transition. Datastar remains a consumer; it does not gain a retry
queue or deduplication cache.

## Acceptance

- The first render failure remains visible and persists once.
- Its exact error-evidence transaction cannot schedule the failed computation.
- An unrelated committed transaction still schedules one repair computation.
- A successful repair replaces `:all` with exact Datahike read evidence.
- One active plus newest pending bounds and final owner cleanup remain intact.

## Candidate correction

`seon.error/persist!` already submits only bounded error projections. Every
such transaction contains `:seon.error/fault`, and its other datoms are exactly
the declared error/frame projection attributes plus ordinary transaction
provenance. Actual render-error results identify the reactive registration as
failed. While it remains failed, the registration suppresses only that derived
error-projection transaction shape; it continues to accept every mixed or
ordinary matching transaction. The upgraded failure-to-repair test proves the
fault commit does not recompute and a disjoint ordinary commit still repairs
and narrows. No classifier attribute or extra transaction metadata is stored.

Focused source proof is green. Resolution still requires a rebuilt live
deterministic failure showing one stored/visible fault, no repeating writer
errors, successful unrelated repair, and zero retained owners.
