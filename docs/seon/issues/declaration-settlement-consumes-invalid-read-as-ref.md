---
type: issue
status: open
severity: friction
tags: [issue, database, test, agent]
---

# Declaration settlement consumes an invalid-read value as a ref

On default at 2026-09-16T02:35:24Z,
`seon.turn-test/batch-settlement-preserves-declaration-order` recorded
1 pass, 2 failures, 0 errors through `seon.test/run` with explicit default
custody and armed contracts.

The real fixture at `test/seon/turn_test.clj:551` settles a test declaration
and its function declaration in one batch. The transaction refused with
`:lookup-ref/unique`, entity-id `[:seon.db/invalid-read true]`; the test's
subject ref consequently remained absent. The complete small failure value
was read. This proves an invalid-read value reached lookup-ref handling;
it does not identify the earlier failed query.

Probe the program declaration settlement owner and that query on the
current publication. Propagate a typed refusal rather than interpreting
its map entries as entity refs, and verify that a valid same-batch subject
resolves. The error-graph lane did not change the protected program or
declaration settlement owners. This is not evidence against the passing
error recording/refusal notification regression.
