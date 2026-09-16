---
type: research
status: active
tags: [research, test, runtime, database, class/p3]
---

# Turn test reds — transaction cache and batch 23

Continuation of [the batch-19 record](turn-test-reds-batch19-2026-09-16.md).
The owner selected option 1: repair the maintained Datahike fork, then derive
schema deletion's projection at the writer. Both the gitlink and dependency
checkout were verified at `cdcb5792db8bd599487f099437265d18a31164a5`
before editing. Read the cache issue, unregister issue, prior landing records,
fork-maintenance reference, and batch-23 report; the earlier record lists the
named design authorities read end to end. Applied data-oriented-clojure,
datahike, repl and clojure-testing skills.

## Class and structural repair

**Guarantee:** transaction functions receive a speculative database without
committed query-cache identity, and declaration deletion derives its projection
from that transaction's actual facts.

`datahike.core/with` now calls the existing `datahike.db/clear-cache-context`
when constructing `:db-after`, before `transact-tx-data` can execute a
transaction function. The superseded post-transaction clearing was removed.
The immutable entering value remains unchanged. Committed-read caching and
writer cache generations remain owned by the existing dependency machinery.
The direct bulk-entity loader does not execute transaction functions and is
unchanged.

`seon.turn/row-tx` deletion now uses `schema/projection-from-database` with
the transaction database and its carried projection, matching declaration
admission. No cache-disable binding, new registry, or fallback was introduced.

Dependency ledger: `reference-code/datahike/src/datahike/core.cljc:126`,
`reference-code/datahike/src/datahike/db.cljc:387`,
`reference-code/datahike/src/datahike/db/transaction.cljc:1152`, and
`src/seon/turn.clj:1209`. Fork commit: `49ea5933`.

## In-process proof

Default PID 7595 answered MCP health and explicit
`(seon.operator/connection "default")` custody probes. It was not restarted,
stopped, or reforked. Test dependency paths were added to a scoped classloader;
`clojure -Spath -A:test` computed paths without launching test JVMs.

The fork regression
`datahike.test.query-cache-test/transaction-functions-query-the-speculative-value`
runs through `kaocha.repl/run`, using the fork's `tests.edn` and its
`:clj-pss` suite, inside that existing JVM. It exercises immutable `with` and
the real connection writer: prime a query, declare an attribute, query it
inside a transaction function, then delete it in the same transaction.
It also verifies uncached evidence, final absence, and unchanged entering
committed identity. Final candidate and source-reloaded runs both report
**1 test / 17 passes / 0 failures / 0 errors**. An initial assertion incorrectly
assumed the asynchronous writer's returned `:db-before` retains connection
attachment; the corrected obligation checks the retained input value and
the immutable `with` report's exact input identity.

The canonical regression
`seon.datahike-fork-test/schema-deletion-reads-earlier-declarations-in-one-transaction`
uses the real declaration writer and canonical fixture. It verifies both the
mid-transaction stored form and derived projection, exclusion from committed
cache identity, surviving program tombstone, removed native attribute, and
removed projected form. The projection map itself must exist.

Exact `seon.test/run` results, pass/fail/error:

| Run | Definition exercised | Result |
|---|---|---|
| 74059 | Fork candidate, original deletion writer; initial test string spelling | 4/2/0: canonical EDN spelling and retained attribute |
| 74064 | Evaluated writer candidate and canonical spelling | 6/0/0 |
| 74075 | Strengthened final eight-assertion candidate | 8/0/0 |
| 74080 | Freshly rebuilt source manifest, canonical base and SCI context | 8/0/0 |
| 74085 | File definitions reloaded in default; new canonical base and SCI context | 8/0/0 |

Each probe creates and releases a canonical base through the existing
`create-base`/`close-base!` owners; individual tests use isolated branches.
Fixture delays are scoped to the serial probe and restored. The committed
regression itself uses the ordinary canonical fixture and owns no global state.
The first attempted fresh-store fixture was refused because it had no justified
store-global observation; it was replaced by the ordinary branch fixture.

## Batch-23 boundary

The orchestrator's completed gate at `f817acfd0` reports **148 tests / 867
assertions / 141 failures / 38 errors**, with 44 distinct red turn-consumer
tests and two red `seon.turn-test` tests. `seon.sci.eval-test` has no listed
failure. Persistent result recording separately timed out after 30000 ms.
These are the orchestrator's results, not a gate run by this lane.

The report includes the already identified renderer-ref boundary, retired
result/private-definition observations, generated-phase fixtures, fake
evaluation completion, and changed evaluation call-edge datom counts.
Seed refusals and downstream failures require separate live probes; the
larger tally is not attributed wholesale to any one class.

The gate request remains unchanged until the current replays incorporate
these reds. No `bin/test`, `bin/test-fast`, or test JVM was launched.
