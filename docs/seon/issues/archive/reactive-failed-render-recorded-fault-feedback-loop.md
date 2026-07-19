---
type: issue
status: closed
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
provenance. The first persisted instance may also carry Datahike schema
declarations for those attributes. The report permits schema datoms only for
same-report entities whose `:db/ident` belongs to the closed persisted-error
attribute set; an ordinary attribute declaration is not error evidence.
Actual render-error results identify the reactive registration as failed.
While it remains failed, the registration suppresses only that derived
error-projection transaction shape; it continues to accept every mixed or
ordinary matching transaction. The upgraded test uses real five-field
protocol datoms, covers a fault report racing failure completion and one
arriving afterward, and proves an ordinary schema declaration plus domain
datom still repairs and narrows. No classifier attribute or extra transaction
metadata is stored.

Focused proof passes 7 reactive tests / 49 assertions and 16 Datastar tests /
75 assertions.

The rebuilt live proof at Seon `aa9970c1` used unique marker
`reactive-fault-proof-2f3cc07d-0b8c-458c-92d4-9ef0a0554784`. One fault entity
was stored and its error value delivered once. Through a 1.5-second quiet
interval, evaluation count remained one and
`:seon.reactive/failure-evidence-events-suppressed` reached one. A relevant
ordinary transaction at basis transaction 536870928 caused exactly the second
evaluation, delivered the repaired value, and installed exact evidence for
`:seon.agent/id` and `:seon.agent/purpose` at that same basis transaction.
Active and pending high-water marks remained one. Final unobserve returned
registration, consumer, active, pending, and timer counts to zero. The writer
committed-report readiness queue was zero with only the normal default source;
watcher, writer, and pod remained ready. The harness retracted both its root
purpose marker and fault entity, leaving root purpose absent and no matching
fault entity.
