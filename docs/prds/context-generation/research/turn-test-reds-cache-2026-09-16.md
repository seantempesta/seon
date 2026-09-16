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

## Schema consumer slice

The production cache repair is main commit `c1d7d4695`. The remaining schema
consumers used the retired serialized-result codec. They now query ordered,
agent-authored evaluations through the existing `agent-evaluations` helper and
assert exact saved shown text. No error-data codec or schema policy changed.

| Cause | Tests | Disposition | Regression |
|---|---|---|---|
| Speculative transaction retained committed cache identity | `runtime-schema-unregister-removes-one-unused-global-schema` | Resolved by fork/writer repair and current observation | Canonical composed declaration/deletion plus real turn |
| Retired result observation | `runtime-schema-key-changes-pass-the-one-usage-guarded-decision` | Current exact shown text, same schema/error obligations | Existing real refinement turn |

Candidate unregister run 44563: **4/0/0**; refinement run 44582: **6/0/0**.
After file reload, fresh canonical base and fresh contexts: runs **44592 4/0/0**
and **44593 6/0/0**. Refinement's first candidate incorrectly retained the old
delivery annotation expectation (44564, 5/1/0); the actual `seon.run/complete`
return is the completion value alone, and that exact value is asserted.

These later probes use this lane's isolated development JVM at snapshot
`c1d7d4695`, with only this lane's test overlay. The main default probe overlapped
foreign instrumentation changes (`seon.cluster/populate-source!` and
`seon.test-support/effective-config`); the isolated JVM removes that interference.
Its source publication was `6aaa1645-156e-5b57-aefc-a6c14d4b814e`. These are
explicit file-reloaded Var proofs, not a claim that default's queued adoption
completed. Main-tree publication reported a bounded timeout.

## Seed recheck after write-validation-class

Run 44594 executes both original partial config seeds on a fresh canonical
base. Both return complete `:seon.db/invalid-write` diagnostics for missing
`:seon.config/applied-manifest-digest`, at paths `[1 ...]` and `[0 ...]`.
Thus the assumption that these two members were unblocked is falsified.
`20d30a0bd` fixes optional identity classification and reverse refs; its own
settlement explicitly preserves the
[incomplete identity-upsert residual](../../../seon/issues/identity-upserts-still-require-complete-entity-maps.md).
The terminal-program and generated-attempt property remain seed-blocked;
their downstream assertions are not used to attribute a production defect.

The default generated-property attempt before isolation returned 0/1/1
(run 75350), including instrumentation drift during foreign publication.
It was **not a test timeout**. Closing its temporary base briefly found an
active fixture connection; after that connection released, this lane deleted
only its own memory store `8eee9698-2a32-4db0-bb5a-77617907a1a4`.
No default or foreign connection was stopped or released by this lane.

## Real evaluation fixture slice

`a-run-prompts-from-its-opening-database-value` and
`concurrent-streams-share-one-conn-test` no longer inject the constant-result
evaluator. The former asserts the current reverse-inbox and runtime-trigger
read forms while retaining message-A/message-B snapshot separation. The latter
queries only agent-authored evaluations and asserts each exact completion value
and source, alongside all channel-loss and nonblocking-offer obligations.

Candidate runs **44565 11/0/0** and **44583 11/0/0**; after source-file reload,
fresh canonical base/context runs **44677 11/0/0** and **44678 11/0/0**.
The fake evaluator was the stale fixture seam; no production turn semantics
changed and no assertion was loosened.

## Generated-source fixture slice

`generated-fixed-point-closes-the-run` and
`generated-membership-failure-never-advances-the-run-to-call` retained injections
into `bootstrap/next-entry` after `6aca09cce` moved the production arm to the
shared `declared-sources` generator. Their terminal seed also wrote the retired
result field. The tests now inject exhaustion/refusal at that existing generator,
seed saved shown text, use real SCI, and read a closure transaction's actual
`:db/txInstant`. The unused bootstrap require is removed.

The exhaustion test still requires closure, no further work for that turn, and
system authorship. The refusal test still requires error outcome, no provider
call phase, closure, and the exact durable occurrence message. Candidate runs
**44679 4/0/0**, **44680 4/0/0**; source-reloaded fresh-base runs
**44692 4/0/0**, **44693 4/0/0**. No generated-source production path changed.

## Streaming observation slice

`streaming-writes-zero-datoms-test` now executes the real completion and waits
for the render proc to observe its partial **before the provider returns**.
The later wait observes terminal facts clearing that already-observed partial.
This removes the race that asked to observe an in-flight state after settlement.
The zero-stream-attribute census remains intact; saved completion and provider
reply are now exact-value assertions instead of retired result storage and a
nil-or-string check. Candidate **44682 10/0/0**, file-reloaded fresh-base
**44707 10/0/0**. No render owner changed.

The delimiter observation candidate still cannot land. Its original uncontracted
`defn` is refused under the ruled contract requirement. A contracted fixture
restores all semantic assertions, but run **44708 is 14/1/0**: measured
six-form bookkeeping **7198.085124 ms**, against the unchanged **300 ms** bound.
The candidate and bound are not weakened or committed. This is a named
performance residual, not a green test or a parser correctness attribution.

## Delivery diagnostic observation slice

`a-refused-delivery-becomes-a-durable-error-fact` separately asserts the live
typed kind `:seon.message/unknown-recipient` and the exact shown diagnostic
`There is no agent named "missing-agent".` The old assertion expected the
kind token to be repeated in the human-facing text. All other delivery,
evaluation-count and idle obligations remain. Candidate **44681 6/0/0**;
source-reloaded fresh-base run **44729 6/0/0**.

## Private-context fixture slice

`a-refused-definition-stays-in-its-agents-defs` previously omitted the persistent
agent context, then queried retired `:seon.def/*` storage and tried to restore
the definition on another turn. It now acquires the real context once through
`fork-for-turn` and supplies `:seon.sci.eval/agent-ctx`, as the agent owner does.
Each explicit drive covers one turn. The assertions require no shared source
definition, a callable private object, absence from the shared base, and exact
completion from calling that same object on the next turn. Program identity
may survive as a tombstone; it is not mistaken for an installed definition.
Candidate **44719 4/0/0**; source-reloaded fresh-base **44739 4/0/0**.
No private serialization, restoration path, or production context owner changed.

## Phase-property fixture slice

The original property queried `:seon.error.occurrences` instead of the installed
`:seon.error/occurrences`. A direct read returns `:seon.db/invalid-read`, not a
count. The observed transactions already closed their turns and recorded one
occurrence. Its evaluation case also seeded no source, causing the settlement
analyzer to refuse nil. The fixture now supplies source, namespace and author,
uses the installed occurrence ref, and removes the unnecessary fake evaluator.

All **24 trials**, seed **2026080601**, and the original five assertions remain.
Candidate **44755 5/0/0**; source-reloaded fresh-base **44764 5/0/0**. The
per-turn closure/evaluation/error counts and bounded supervisor escalation all
pass. The early failed property never accumulated enough recurring failures
to exercise escalation; its empty notification table was downstream evidence,
not a production notification defect.
