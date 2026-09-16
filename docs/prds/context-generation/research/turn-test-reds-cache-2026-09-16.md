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

The completed batch was incorporated before rewriting the gate request.
No `bin/test`, `bin/test-fast`, or test JVM was launched.

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

## Recovery fixture slice

`turn-intent-is-the-complete-crash-falsifier` now tests prefixes of zero and two
real in-memory evaluations after the complete intent commits, deliberately
omitting settlement before recovery. Throwing inside a total phase is a caught
host failure and no longer simulates a process cut. The test checks the complete
intent and trigger, exact prefix results, all unfinished ordinals, recovery's
commit and closure, no re-execution, and new work rather than resumption of the
interrupted turn. An interrupted turn does not answer its wake.

Candidate **44765 27/0/0**; source-reloaded fresh-base **44774 27/0/0**.
The existing recovery writer and direct evaluation owner are used unchanged.


## Final batch-23 member verdicts

Dated census: **44 cluster-turn members: 29 green unchanged, 11 repaired
(92 passing assertions), four unresolved**. These are focused in-process
proofs, not a claim that the namespace or platform gate is green. Each test
used a fresh canonical database branch and fresh SCI context; each probe batch
rebuilt its canonical base from the source manifest. The isolated source basis
was `c1d7d4695` plus only this lane's test edits. Later foreign production edits
are excluded from these results. Proof after edits used explicit file reload;
default adoption convergence is not claimed.

All [94 complete saved result values](turn-test-reds-batch23-evidence-2026-09-16.edn)
are retained (181941 bytes), including failures and seed refusals. The
[probe forms](turn-test-reds-batch23-probes-2026-09-16.clj) preserve the actual
MCP forms as data, including both unlanded candidates. Counts below are
pass/fail/error; numeric run identities belong to the isolated branch unless
explicitly described as default in the earlier record. A green unchanged
replay dissolves that cold observation at this basis; it does not assign a
cause to every prior failure or prove unrelated interleavings impossible.

| Test in `seon.cluster.turn-test` | Verdict | Recorded run; counts |
|---|---|---|
| `a-batched-turn-commits-only-queryable-definition-facts` | dissolved at fresh-base replay; unchanged | 44604; 7/0/0 |
| `a-combined-evaluation-projects-every-terminal-receipt-datom` | dissolved at fresh-base replay; unchanged | 44605; 10/0/0 |
| `a-lost-model-call-leaves-a-durable-readable-reason` | unresolved; original assertion retained | 44784; 3/1/0 |
| `a-prose-prefixed-contracted-defn-settles-and-doc-answers` | dissolved at fresh-base replay; unchanged | 44611; 4/0/0 |
| `a-real-evaluation-that-runs-away-is-stopped-and-recorded` | dissolved at fresh-base replay; unchanged | 44612; 2/0/0 |
| `a-refused-contract-commits-a-receipt-and-no-row` | dissolved at fresh-base replay; unchanged | 44613; 2/0/0 |
| `a-refused-definition-stays-in-its-agents-defs` | repaired; class and commit below | 44739; 4/0/0 |
| `a-refused-delivery-becomes-a-durable-error-fact` | repaired; class and commit below | 44729; 6/0/0 |
| `a-run-prompts-from-its-opening-database-value` | repaired; class and commit below | 44677; 11/0/0 |
| `a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` | dissolved at fresh-base replay; unchanged | 44616; 9/0/0 |
| `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end` | dissolved at fresh-base replay; unchanged | 44621; 14/0/0 |
| `a-whole-turn-runs-from-trigger-to-closed-run` | dissolved at fresh-base replay; unchanged | 44622; 6/0/0 |
| `absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx` | dissolved at fresh-base replay; unchanged | 44623; 4/0/0 |
| `agent-code-with-defn-and-println-folds-green-without-in-ns` | dissolved at fresh-base replay; unchanged | 44624; 6/0/0 |
| `an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence` | dissolved at fresh-base replay; unchanged | 44625; 28/0/0 |
| `another-agent-calls-the-live-cluster-definition-without-reinstall` | dissolved at fresh-base replay; unchanged | 44630; 1/0/0 |
| `another-agent-sees-a-flat-contract-violation-after-live-install` | dissolved at fresh-base replay; unchanged | 44631; 2/0/0 |
| `concurrent-streams-share-one-conn-test` | repaired; class and commit below | 44678; 11/0/0 |
| `contracted-redefinition-exactly-replaces-the-row` | dissolved at fresh-base replay; unchanged | 44632; 5/0/0 |
| `delimiter-repair-is-span-local-and-precedes-intent` | unresolved; original assertion retained | 44708; 14/1/0 |
| `evaluation-follows-the-readers-parse-time-namespace` | dissolved at fresh-base replay; unchanged | 44638; 3/0/0 |
| `function-install-reads-the-case-count-from-cluster-facts` | dissolved at fresh-base replay; unchanged | 44639; 4/0/0 |
| `generated-fixed-point-closes-the-run` | repaired; class and commit below | 44692; 4/0/0 |
| `generated-membership-failure-never-advances-the-run-to-call` | repaired; class and commit below | 44693; 4/0/0 |
| `generated-model-attempt-traces-preserve-presence-and-episode-laws` | blocked by incomplete identity upsert | 44594; seed refused |
| `generated-phase-failures-converge-through-one-terminal-exit` | repaired; class and commit below | 44764; 5/0/0 |
| `import-addition-is-ordinary-data-and-reacquires-exactly` | dissolved at fresh-base replay; unchanged | 44643; 2/0/0 |
| `import-only-ns-unmap-installs-exactly-after-its-context-commit` | dissolved at fresh-base replay; unchanged | 44644; 4/0/0 |
| `incompatible-clusters-alternate-runtime-schema-validation-without-bleed` | dissolved at fresh-base replay; unchanged | 44649; 9/0/0 |
| `mixed-plan-publishes-only-the-contracted-function` | dissolved at fresh-base replay; unchanged | 44650; 4/0/0 |
| `ns-unmap-retracts-the-owned-function-after-the-terminal-commit` | dissolved at fresh-base replay; unchanged | 44651; 7/0/0 |
| `qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context` | dissolved at fresh-base replay; unchanged | 44652; 2/0/0 |
| `reasoning-starvation-persists-usage-finish-and-the-named-error` | dissolved at fresh-base replay; unchanged | 44653; 11/0/0 |
| `refused-import-only-ns-unmap-leaves-the-run-sci-ctx-unchanged` | dissolved at fresh-base replay; unchanged | 44658; 3/0/0 |
| `refused-runtime-schema-registration-mutates-neither-row-nor-projection` | dissolved at fresh-base replay; unchanged | 44659; 5/0/0 |
| `refused-terminal-program-transactions-settle-and-do-not-refire` | blocked by incomplete identity upsert | 44594; seed refused |
| `reply-reading-follows-evaluated-alias-and-dynamic-require-state` | dissolved at fresh-base replay; unchanged | 44660; 2/0/0 |
| `runtime-schema-key-changes-pass-the-one-usage-guarded-decision` | repaired; class and commit below | 44593; 6/0/0 |
| `runtime-schema-registration-commits-the-evaluated-form-and-attribute` | dissolved at fresh-base replay; unchanged | 44661; 10/0/0 |
| `runtime-schema-unregister-removes-one-unused-global-schema` | repaired; class and commit below | 44592; 4/0/0 |
| `runtime-tests-install-run-redefine-and-delete-exactly` | dissolved at fresh-base replay; unchanged | 44662; 6/0/0 |
| `streaming-writes-zero-datoms-test` | repaired; class and commit below | 44707; 10/0/0 |
| `successful-call-persists-the-providers-open-usage-document` | dissolved at fresh-base replay; unchanged | 44668; 11/0/0 |
| `turn-intent-is-the-complete-crash-falsifier` | repaired; class and commit below | 44774; 27/0/0 |

## Class disposition and committed regression

| Cause | Tests | Disposition | Regression / commit |
|---|---|---|---|
| Committed cache identity on speculative values; stale deletion projection | schema declaration/deletion | structural repair at transaction entry and existing writer | fork `49ea5933`, main `c1d7d4695`; fork 17 and canonical 8 assertions |
| Retired serialized-result observation | unregister, schema key changes | exact saved shown text | both member observables; `b3266e25b` |
| Fake evaluation envelope | opening snapshot, concurrent streams | real SCI and current generated forms | both independent snapshot/channel observables; `2c1ecba79` |
| Retired generated-entry injection | fixed point, membership failure | inject at current declared-source seam | terminal and refusal observables; `f1b47d219` |
| Transient streaming observation after provider completion | streaming datoms | observe partial inside provider, exact terminal completion | existing streaming regression; `261ac0ca5` |
| Diagnostic kind confused with human text | refused delivery | assert kind and exact shown message separately | delivery regression; `b8050b94f` |
| Missing persistent SCI context; retired private storage | refused definition | same real agent context across turns | isolation and next-turn call regression; `559241e6b` |
| Invalid occurrence attribute and incomplete evaluation seed | phase property | declared occurrence ref and full source evidence | unchanged 24 trials / seed 2026080601; `94da1b2ce` |
| Caught phase exception mistaken for process interruption | crash recovery | cut after intent / before settlement | zero/two-prefix parameterized recovery regression; `c4c3ca475` |
| Sparse config identity maps | two seed-blocked members above | skip downstream per owner; complete seed refusals retained | run 44594; [residual](../../../seon/issues/identity-upserts-still-require-complete-entity-maps.md), `656c68276` |
| Bookkeeping exceeds declared bound | delimiter repair | unlanded corrected candidate; retain 300 ms assertion | run 44708: 7198.085124 ms; [residual](../../../seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md) |
| Provider diagnostic absent from subsequent prompt | lost model call | unlanded real-fixture candidate; retain visibility assertion | run 44784; [residual](../../../seon/issues/provider-failure-diagnostic-is-absent-from-next-prompt.md) |

## Remaining visibility decision: three priced options

The provider-failure candidate verifies closure, durable diagnostic, and a new
trigger; the next real prompt still omits the reason. No automatic retry
behavior is inferred. Resolving this through context/error owners exceeds this
bounded lane. These are estimates, not implementation commitments:

1. **Recommended smallest scope, subject to an explicit owner ruling:** make
   durable operator-visible diagnostics the contract and retire automatic
   prompt visibility. About 30–60 minutes for ruling, docs and regression;
   guarantees durable inspectability, gives up automatic agent visibility.
2. Include the existing attempt/error diagnostic through the existing generated
   context renderer. About 2–4 hours across context/error owners; guarantees
   the next prompt includes the reason, costs prompt space and owner work.
3. Surface an explicit diagnostic read through existing context/help. About
   1–2 hours; guarantees on-demand access, gives up automatic inclusion.

No option is silently selected and no new production path was added.

## Foreign verification boundaries and gate request

The two batch-23 `seon.turn-test` reds were also replayed without edits:
`settlement-mints-rows-for-unindexed-call-targets`, run 44670, **9/1/0**, expects
no call edge but sees `seon.bootstrap/help`; and
`virtual-turns-use-the-proc-and-compaction-is-agent-scoped`, run 44671,
**87/3/1**, has old datom counts and a renderer-ref contract expecting an integer
but receiving a map. These files/owners are held by other lanes. Their full
results are in the evidence artifact; no production attribution beyond those
observations is claimed.

The gate request now contains only existing test namespaces:
`seon.cluster.turn-test`, `seon.sci.eval-test`, `seon.turn-test`, and
`seon.datahike-fork-test`. The orchestrator owns the next cold gate and platform
proof. No test JVM was launched by this continuation.

Final default MCP runtime status returned health/Flow unknown after a 30-second
read timeout, PID 53378 / start 04:10:35Z / PREPL 61867. The
[existing issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md)
records it. No default lifecycle operation occurred. Markdown hooks report 29
unrelated historical gitlink citations in `agents-md-audit-2026-09-15.md`;
those foreign audit bytes were preserved.


## Cleanup

The last owned test future reported `:completed`. The owned operator root
`tmp/turn-test-cache-wt/tmp/turn-test-cache-root` completed `down`; PID 52846
exited, the store flock was free, and its three-branch roster was readable.
All 94 saved EDN values were verified against the committed evidence artifact
before scratch deletion. The worktree's `reference-code` symlink was unlinked
without following it; the owned worktree/root and scratch directory were removed.
Shared dependency files, foreign worktrees, foreign processes and dirty source
paths were preserved. No background command or test future remains owned by
this continuation.


## Batch-28 continuation: evaluation call facts

Read the replacement AGENTS.md, batch-28 report, and exact loader/fixture-base
rules end to end; read commits `20d30a0bd`, `76774d044` and `3596cfb96`.
Initial HEAD was `bb3f19370`; default PID 53378. Test namespaces were reloaded
through `seon.test/with-test-loader`, and test Vars resolved with
`seon.test/resolve-test`. All runs use explicit default connection custody,
serial daemon futures and canonical fixture branches. No fixture base was
replaced and no test JVM was launched.

The cold fork and SCI namespaces passed batch 28 at `258150603`. The six
remaining members are now in scope. First re-baseline: settlement 50462
**9/1/0**; virtual-turn 50463 hit the 20000 ms runner bound. Its subsequent
120000 ms diagnostic run 50468 returned **125/3/1**: the three old datom-count
expectations remain, and the second fixture run missed its required closed
event. The older renderer-ref error did not recur. The event bound was not
relaxed. A result-inspection MCP timeout was recorded before a smaller probe
confirmed completion and the complete result was saved.

`settlement-mints-rows-for-unindexed-call-targets` now requires the exact
resolved `seon.bootstrap/help` edge, as introduced by `76774d044`; unresolved
mentions must still produce no dangling edge. Candidate run **52264 10/0/0**;
after file edit and explicit loader reload, **52265 10/0/0**. This is a
ruled expectation repair, with no production change. Hook publication queued;
this proof claims explicit test-file reload, not complete default adoption.


### Batch-28 seed probe and live fixture boundary

The original sparse config identity seed returns `:seon.db/invalid-write`,
missing `:seon.config/applied-manifest-digest`. An explicit `:db/add` update
commits and reads back max-episode-runs 2. The generated-scenario candidate
uses a lookup-ref entity update, checks the transaction result, uses real SCI,
and selects the provider turn through its attempt ref. Before any file edit,
the representative call stops at `seon.test/stale` arity validation.

A direct comparison verifies the cause: default's program row supports one
and two arguments; the canonical fixture branch still carries the old
one-argument contract. The loaded plan caller at `src/seon/plan.clj:570`
supplies two. This is the [successful old-base boundary](../../../seon/issues/successful-fixture-base-retains-pre-adoption-contracts.md),
not a failed property verdict. No seed/property candidate was written to the
test file. The wave's explicit no-base-rebuild rule requires orchestrator
direction; a refresh was requested before dependent work continues.

Remaining tests retain their assertions. The virtual-turn candidate would
account for the one `:seon.fn/calls` datom per evaluation (16/6/2 and 30/16/2),
but its live event failure must be resolved before that change can land.
The delimiter performance and provider prompt visibility candidates remain
unlanded from the prior record. No production seam in this continuation was
edited; protected source and unrelated working-tree edits remain untouched.


Complete [batch-28 returned values](turn-test-reds-batch28-evidence-2026-09-16.edn)
and [MCP forms](turn-test-reds-batch28-probes-2026-09-16.clj) are retained.
All six owned futures reported realized; the checked-in cluster-turn namespace
was reloaded to remove the unlanded candidate. No scratch JVM, worktree or
background shell was created. The four existing namespace names remain in
`tmp/orchestrator/gate-requests/turn-test-reds.txt`; they were re-verified under
`test/`. Final cold proof remains the orchestrator's responsibility.
