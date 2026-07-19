---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, milestone]
---

# Score database work from database facts

## Problem

The live pod returns an ordinary database value, ordered eval rows, and typed
final database queries. The Inspect database milestone instead required a
scorer-only database wrapper plus captured request/result trees that production
does not emit. A correct agent could therefore fail before its actual database
facts were examined.

## Dependency ledger

- `src/seon/web/serve.cljs` owns `/agents/run` and the loopback-only typed
  `/_seon/operator/product-evidence` query.
- `src-inspect-ai/src/seon_inspect/solver.py` retains the runtime database value
  as `pod_database_value`.
- `src-inspect-ai/src/seon_inspect/product_scenarios.py` owns the typed query
  client.
- `src-inspect-ai/src/seon_inspect/milestone.py` owns database-memory scoring.

## Acceptance

- Ordered successful eval rows prove the schema, transaction, later query,
  computation, and completion sequence.
- Every retained eval transaction is at or before the final database value's
  basis transaction.
- One typed final query returns the actual identity/measure pairs, and the
  scorer verifies both the records and thresholded answer.
- Missing, stale, incomplete, or incorrect final facts fail closed.
- Fixed and seeded live database workflows pass without any synthetic
  operation evidence.

## Current evidence

The milestone implementation and focused tests now use the real database value
and typed final query. The synthetic operation tree and eleven fixture-only
mutations were deleted; 70 focused milestone/solver tests pass and the diff is
28 net lines smaller. The issue remains open until a fixed live database run
passes and a generated row proves the dynamic attribute query.

The fixed live row now passes in 48 seconds with accuracy 1.0 and zero
fabrication. The first generated row stored and read back every correct fact but
exposed a narrower scorer mismatch: its contract permits querying records and
computing from the returned `result/...` value in a later eval, while the
scorer required the predicate inside Datalog. The scorer now accepts both
valid evaluation shapes and keeps the typed final read mandatory; 71 focused
tests pass. The generated rerun remains the archival gate.

A second generated sample then stored, queried, computed, and read back every
requested fact correctly but failed only because the scorer required both
`message/user` and `complete`. Since `complete` is already the delivered human
reply, that duplicate side effect was removed from this database-specific
contract. The frozen generated artifact and hashes were intentionally updated;
the combined focused gate passes 156 tests. A fresh generated run remains the
archival gate.
