---
title: "Cost hand-off: committed projection reuse and changed-attribute squash"
type: research
status: evidence
created: 2026-10-12
---

# Cost hand-off: committed projection reuse and changed-attribute squash

`seon.db/transact-call` prepares one projection, validates the speculative report, then hands the committed report to `seon.db/hold-committed-projection!`. The hand-off now associates the already held declaration-revision cell with the new commit, including ordinary writes. Reopening that commit reuses the cell instead of copying every declaration and finding a replacement base.

## Current result and boundary

Root extended ownership to `seon.program`. Both assigned cost bounds passed at the previous landing; subsequent C3 checks show the timing failures recorded below. The follow-on C3 cost provenance and authorized reconcile consumer are implemented: all seven convergence tests and both cost bounds pass together in run `5d7bc1f9d9ab`. The index result now selects its program population at the producer. Earlier timing failures and their comparisons are recorded below. Sol's released proof was adopted with root authorization; no other lane was contacted or edited.

The live JVM remained PID 51669, started 2026-10-11T17:57:28.024Z, running `e0bd37443`. It was never stopped, reset, restarted or loaded with this uncommitted host source. Its initial status was degraded by an overlay cache discipline warning and an unowned `user` namespace. Check JVMs were launched and retired by `bin/seon check`.

## Measured producer and change

The projection-in-transaction-data hypothesis was falsified on default with `seon.profile/measure`:

| Held-input operation | ms | allocated bytes |
|---|---:|---:|
| `seon.db/stamp-transaction` | 0.03 | 3,520 |
| `seon.db/prepared-write` | 0.06 | 16,096 |
| `seon.db/cost-upserts`, empty sink | 0.12 | 61,728 |

`seon.db/jdk-integers->long` already preserves transaction-function arguments. Datahike's `datahike.db.transaction` dispatch applies the supplied function to its mid-transaction database and the original arguments. No projection copy was found on this path. `astra-cost-handoff/preparation` preserves the exact probes.

The cold-fork profile instead contained one `seon.db/declaration-content-key`, one `seon.schema/load-projection`, and two `seon.schema/range-differences`. `seon.db/hold-committed-projection!` previously ran only when the report wrote declaration datoms. An ordinary commit therefore had no commit alias until a later read derived it, while reopening creates a different connection revision key.

The existing cache's exact committed revision key now selects its existing cell first. The existing speculative-cell path remains for changed declarations. Both associations reuse that cell; no new cache, state, projection derivation, writer-thread work, or cost-accounting behavior was introduced. The unchanged path is proportional to the fixed declaration-attribute key, not to the declaration population. It replaces the later whole-population content key and derivation. Precomputing cost upserts was rejected: they must still see same-transaction definitions and the writer's actual values.

The strengthened `seon.db-test/a-reopened-commit-carries-its-content-revisions` invokes the public projection entrance after a real fork's connection closes and reopens. It asserts zero calls to the content-key copier and projection loader. It failed before the fix (run `9a956ecf41c9`, one loader call) and passed after it. The existing contract on the hand-off still returns exactly `:seon.db/transaction-report`.

## Before and after

Each revision below ran the two original cost tests in its own archive/check JVM. These measurements refute the central-round inference that squash became red after `cd59c9fa3`; the earlier bulk green was not a cold standalone baseline.

| Archive | squash ms / MB | cold fork ms / MB | cold SCI ms / MB |
|---|---:|---:|---:|
| `cd59c9fa3` | 32.99 / 36.77 | 13.07 / 4.46 | 934.42 / 1,797.37 |
| `e1c424db5` | 30.05 / 40.15 | 18.04 / 4.49 | 924.58 / 1,793.94 |
| `c7dc2c3b6` | 21.51 / 39.97 | green, raw sample not retained | green |
| `e0bd37443` | 42.17 / 38.94 | 17.81 / 8.44 | 1,386.25 / 3,078.78 |
| hand-off fix, first check | 23.09 / 38.07 | 7.75 / 0.74 | 843.85 / 1,801.15 |

Allocation is the calling platform thread's counter, not retained heap or all-thread allocation. The committed flow samples also carry the JVM-window counters, explicitly including unrelated work. Peak live heap was not measured. Cold SCI construction deliberately rebuilds the full interpreter and remained within its existing 1,300 ms / 2.1 GB bounds after the fix; it is not an ordinary warm operation.

## Squash attribution

The unchanged test with a temporary diagnostic produced 37,797,592 allocated bytes, 23.52 ms, 12,751 armed calls. Its leading owners were `seon.db/undecoded-datoms` (70 calls, 14 self-ms), `seon.schema.internal/entity-maps` (3,631 calls), and `seon.program/derived-cluster-attributes` (one cold derivation over 3,628 schemas). The diagnostic edit was removed.

A finite default-JVM probe over the same immutable ten-evaluation helper measured squash at 20.35 MB / 9.50 ms, then 3.14 MB / 2.23 ms when repeated. Reading historical attributes that had **no changed results** still allocated: task must-read 653 KB, task id 576 KB, task name 482 KB. JFR allocation samples named Datahike's temporal sorted merge, datom resolution and temporal transaction filtering. `datahike.db.utils/filter-txInstant` resolves transaction entities for the attribute history before filtering it. This work is independent of `seon.db/cost-upserts`.

A direct Datahike query filtering numeric transactions was also tried, without redefining any Var: equal empty result, 10.03 MB / 8.37 ms versus 14.21 MB / 6.99 ms for the existing historical scan. It is not a sufficient fix and was not installed. A malformed trailing delimiter in that probe was my input error, delivered as an additional reply item; the measurement form itself completed.

The producer slice now selects attributes with `seon.db/changed-attributes`, comparing Datahike's committed or speculative attribute revisions. An absent revision means that attribute still needs its historical read; numeric bases and temporal views retain the full selection. `:db/txInstant` is always read because Datahike excludes it from revision tracking. No derived state or cache is added. `seon.db/entities-changed-after` reads the selected native historical index and compares its datoms' transaction numbers directly to the held basis, avoiding `datahike.db.utils/filter-txInstant`'s transaction-entity lookups. Retractions remain in the history and returned coordinates.

`seon.program/derived-cluster-attributes` uses the projection's already derived database-entity catalog (290 entries), rather than rediscovering entities from all 3,628 schemas. Plain maps use `malli.core/properties`; aliases and conjunctions retain `seon.schema.internal/entity-properties` and inherited rules. The existing central cache still owns the derived result. Cold work is proportional to entity declarations plus the historical index of changed attributes, not every schema and every cluster attribute's history.

Incremental cold measurements: revision selection alone 9.16 MB / 11.01 ms; entity catalog 5.43 MB / 11.01 ms; numeric historical basis 4.95 MB / 7.68 ms; direct plain-map properties **4.85 MB / 7.53 ms** (standalone run `80320c5d828c`, 6 assertions, no failures/errors). The bound remains 5 MB / 10 ms. The earlier full squash suite, cluster-attribute proof and flow cost check passed 13 tests / 133 assertions (`91b9b0ad1164`); the final lane gate repeats them against the complete slice. The existing retraction proof now includes native, numeric-basis and temporal-view cases. The cost proof writes its measured data through the value printer for durable evidence. See `astra-cost-handoff-evidence/squash-cold-after.edn` and `astra-cost-handoff-evidence/squash-check-after.txt`.

## Verification and evidence

The squash slice landed as `9cc893681`. Its final lane gate passed 15 named tests / 143 assertions, no reuse, failures or errors, run `96f894e106fb` (11.017 s tests, 39.141 s overall). This includes all eleven squash Vars, the cluster-attribute proof, both declaration `changed-identities` proofs, and the flow bound. Full-tree lint before landing was 0 errors / 1,541 existing warnings, 6.64 s; candidate lint also had 0 errors. Source delta was +37/-9 lines and test delta +10/-0. Default remained PID 51669 on archive `e0bd37443`.

Raw check outputs and measured flow values are in [astra-cost-handoff-evidence](astra-cost-handoff-evidence/). The standalone projection checks ran green: `seon.db-test/a-definition-write-keys-its-projection-by-its-own-datoms` and `seon.db-test/speculative-declarations-never-enter-the-committed-projection-cache`; the only red in that check was the pre-existing squash bound. The reopening regression and `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` passed together, 47 assertions, run `3eca53dada4e`.

The raw-store/accounting check passed all 18 named tests and 149 assertions, no reuse, failures or errors (run `1e8b8164620a`). It took 135.24 s in test execution and 160.96 s overall; the shell and two publication proofs build complete canonical stores under their existing declared long bounds. Publication rehearsal took 3.25 s, adoption 0.66 s, and candidate boot 15.31 s including 8.40 s to readiness. These are the complete-publication/check operations requested for this platform slice, not the warm fork measurement. A mid-check status sampled 2.07 GB heap used and 0.91 GB live; these are observations, not peak measurements.

Full-tree `clj-kondo --lint src script test --cache false` finished in 6.87 s with **0 errors**, 1,541 pre-existing warnings. `git diff --check` was clean. Production source is +15/-15 lines; the strengthened existing test is +8/-2; the reusable measurement script is 39 lines. The original `e0bd37443` commit message names 14 tests only by category; [tests.edn](astra-cost-handoff-evidence/tests.edn) names the concrete 14 raw-store/accounting regressions run here, alongside the three projection proofs and flow bound. This does not claim recovery of an unavailable exact manifest from another agent's session.

Foreign working-tree edits were excluded from the candidate: `test/seon/reconcile_test.clj`, its landing note, the pre-existing scratch/proof files, and the dirty http-kit submodule. In the squash continuation, foreign runner/SCI test edits were likewise excluded; a concurrently landed runner fix entered HEAD normally. The original edit hook's SCI path reported pending-test selection and acquisition errors; the continuation reports the lane's previously retired admission as closed. No uncommitted source was loaded into default. Host-bound proof is the archived publication/adoption and disposable-JVM check, not the unavailable lane SCI proof. Those diagnostics are not counted as green.

The first lane commit gate stopped before tests in `clojure -Spath -M:dev:test`: Maven's `DefaultModelValidator` threw the known `HashMap$Node`/`HashMap$TreeNode` race. The full diagnostic is retained in `astra-cost-handoff-evidence/classpath-race.edn`; the existing issue is [dependency-resolution-can-race-maven-model-validation](../../../seon/issues/dependency-resolution-can-race-maven-model-validation.md). No source workaround or dependency change was made; the same commit gate was invoked again.

Dependency seams were inspected at the actual pins: Datahike `61bca1edd6bcef079f5c47e4f5f75df30549934d`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`. `datahike.db/advance-cache-context` and `datahike.db/speculative-cache-context` own attribute revisions; `datahike.db/committed-value-identity` identifies the committed value; the existing Malli function contract owns input/output validation.

## C3 follow-on: accounting provenance and convergence

The C3 producer change makes each `seon.db/cost-upserts` entity carry `:seon.db/process`, pointing to the accounting process. That process carries its own provenance too, so its identity does not belong to the surrounding configuration transaction. Datahike's nested entity/upsert mechanism writes both with the cost sample in the same transaction. `seon.db/stamp-transaction` installs the two provenance attributes when absent; it still passes the prepared projection to the writer. The producer's output contract requires the named process entity in transaction data. No extra transaction, derivation, cache, retained-identity list or exception in configuration is introduced.

A finite `seon.db/with` probe returned the cost's accounting owner and the process's self-reference correctly (4 ms evaluated form, 84.9 ms whole reply). The producer regression, writer-cost regression and both cost bounds pass: run `71f3c6047ae0`, 4 tests / 56 assertions, 10.812 s tests, 40.652 s overall. An initial draft incorrectly contracted its nested transaction entity as the installed read-reference schema and was refused at the producer; the corrected contract names `:seon.db.process/process`.

The producer-only baseline was `a6c142d3a994`: 26 executed, 189 assertion passes / 19 failures / 0 errors, 142.333 s test execution. All 18 original raw-store/accounting/projection/flow tests and squash passed; exactly the seven C3 Vars were red. [c3-reaching.txt](astra-cost-handoff-evidence/c3-reaching.txt) preserves that baseline.

Root subsequently released Sol's claim and authorized the reconcile consumer. The adopted diff in `test/seon/reconcile_test.clj` is exactly Sol's eight-line proof that a reapply must not retract cost entities. `seon.reconcile/plan-transaction-data` now gives explicit entity provenance priority over the identity's first transaction. Its existing query becomes `seon.reconcile/process-by-entity`; no second ownership registry is introduced.

With that consumer, `7c47bf0d749d` ran all seven convergence tests and both cost bounds: 95 assertions pass / 4 fail / 0 errors, 9.029 s. Six convergence Vars and both bounds are green. Only `seon.index-reconcile-test/indexing-an-existing-population-uses-one-writer` remains red, counting 11 and then 3 changes despite an unchanged published population. [c3-convergence.txt](astra-cost-handoff-evidence/c3-convergence.txt) preserves the result.

Root authorized the remaining index producer. `seon.fn/report-identities` now accepts explicit identity attributes; its existing generic arities still report every identity. `seon.fn/index!` supplies the keys of its held `seon.program/shapes-in` value when it produces reconciliation results. Cost and accounting-process identities therefore never enter the publication result; no downstream exclusion list or taxonomy is added. The strengthened index proof checks the returned identities as well as zero operations. All seven C3 proofs, both cost bounds, the generic report proof and logical vector-owner proof are green: `5d7bc1f9d9ab`, 11 executed / 117 assertions / 0 failures / 0 errors, 9.756 s tests, 38.742 s total. See [c3-complete.txt](astra-cost-handoff-evidence/c3-complete.txt). The lane gate names the reaching Vars in [c3-tests.txt](astra-cost-handoff-evidence/c3-tests.txt).

The edit hook reports the existing `seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error` defect: it demands an error return union for the throwing plan function. The function instead has the required success-only contract. No hook or foreign source was modified; the authorized edit is verified through the archived candidate. The old lane SCI admission remains retired, and default has not loaded uncommitted source. Foreign runner/SCI tests and the dirty dependency checkout are excluded from these candidates. The seven required proofs are now green; the lane gate independently checks the final slice.

The wider consumer check `0b29e7e1a2f8` runs 31 named Vars: 29 green, 217 assertion passes / 2 failures / 0 errors, 150.159 s test execution. All original 18 regressions and the six independent C3 proofs pass. Two timing failures remain visible: squash 12.66 ms against 10 ms (4,951,200 allocated bytes against 5,000,000), and the existing 60-trial generated convergence proof 11,300.49 ms against 5,000 ms. Its semantic property passes. Neither bound was loosened. The squash time is a regression relative to the earlier standalone green and needs its separate cold check; the property timeout is not classified as pre-existing without a parent measurement. See [c3-independent.txt](astra-cost-handoff-evidence/c3-independent.txt). Full `src script test` lint is 0 errors / 1,541 warnings, 6.417 s; the owned diff has no whitespace errors.

The separate cold cost check `690104c954d8` ran two Vars: 45 assertion passes / 1 failure / 0 errors, 6.879 s tests. Squash is green again at 7.54 ms / 4,966,608 bytes. The flow test instead fails its cold graph-construction bound, 9.86 ms against 5 ms; its branch allocation bound passes. This is not an all-green result and neither failure is waived. See [cost check](astra-cost-handoff-evidence/c3-cost-alone.txt) and [squash measurement](astra-cost-handoff-evidence/c3-squash.edn). At that checkpoint neither continuation had committed. The disposable checks exited normally and default's lifecycle was untouched.

## Timing attribution and remaining reds

The generated convergence timing failure is **pre-existing**: `869e2f99b0b4` on `cd59c9fa3` takes 17,586.00 ms; `b3f62fc0c364` on `e0bd37443` takes 18,433.18 ms, both against 5,000 ms. Both also fail the semantic property before C3. C3 fixes its semantics and lowers elapsed time to 11,300.49 ms, but does not make this cost proof green. Filed and tagged as `seon.reconcile/generated-convergence-fits-its-work-bound`; the unchanged 60 trials and bound remain. That Var is excluded from the landing gate as a reproduced pre-existing timeout, not counted green.

The cold graph failure is **newly observed but not source-attributed**: 9.86 ms in `690104c954d8`, versus 1.97 ms on `cd59c9fa3`, 1.61 ms on `e0bd37443`, and 2.10 ms on the same C3 producer in `5d7bc1f9d9ab`, against 5 ms. Allocation remains about 633–635 KB and calls about 292–312. The comparison does not establish a regression caused by this lane, and the same source passes again without a graph change. Filed and tagged as `seon.flow/cold-paused-graph-construction-keeps-its-cost-bound`; no bound or code is changed to hide it. Its Var remains included in the lane gate. The full flow samples and historical logs are in this evidence directory.

`seon.cluster.publication-delta-test/transaction-report-identities-select-only-changed-program-entities` also errors on both historical revisions before reaching the report function: its fixture changes a function doc without a matching definition digest. This foreign fixture boundary is excluded from the landing gate; the generic report and vector-owner tests are named separately; the sibling fixture failure is classified below. The source publication and other original 18 regressions remain named. No foreign file or session was modified. Source/runner edits that other lanes committed entered the candidate through HEAD normally; foreign scratch files and the dirty http-kit checkout remain excluded.

The first expanded C3 gate `f7ba678a0bc4` ran 45 Vars: 282 assertion passes / 1 failure / 5 errors, 159.355 s. All seven C3 convergence proofs pass. The five errors reproduce unchanged on `e0bd37443` in `6c97ed8cd2f1` (3.422 s): `seon.cluster.publication-delta-test/selected-entities-reconcile-without-a-manifest` derives a nonexistent test path under its scratch publication root; the four `seon.fn.incremental-deletion-test` Vars named below fail with Malli invalid-schema before indexing. These pre-existing foreign fixture failures are excluded from the final gate, whose other direct index and generic report proofs remain named:

- `seon.cluster.publication-delta-test/selected-entities-reconcile-without-a-manifest`
- `seon.fn.incremental-deletion-test/a-deletion-recomputes-its-unchanged-callers-edges`
- `seon.fn.incremental-deletion-test/a-replacement-under-a-declared-invocation-keeps-its-new-edge`
- `seon.fn.incremental-deletion-test/a-declaration-adding-a-target-reanalyzes-its-invokers`
- `seon.fn.incremental-deletion-test/a-supplied-file-entity-never-widens-the-reconciliation`

The squash failure in that larger gate is 12.95 ms / 4.58 MB. A cold native-search experiment (`4768a718b5c1`) remained red at 7.71 ms / 5,348,472 bytes; its earlier three-test green had warmed the schema catalog and was not cold evidence. Dereferencing catalog aliases alone still leaves 5.21 MB and was superseded. `seon.schema/shape-entity-in` already computes inherited entity properties; its catalog entry now carries the existing `:seon.program/partition` fact. `seon.program/derived-cluster-attributes` selects the three cluster schemas from that catalog and derives conflict rules only for those selected schemas. Its producer contract declares the retained partition; the existing cluster-attribute proof checks its presence. This removes the 214 conjunction walks from cold squash, without a second cache or partition model.

The remaining producer cost is merging every old historical datom before selecting transactions after the held basis. `seon.db/entities-changed-after` now composes `datahike.db.interface/batch-search`: its callback selects the native attribute range and filters each current/temporal index by transaction before Datahike merges the retained datoms and applies the view context. Datahike still owns index selection, historical deduplication, and temporal views. No cache, guard, or transaction-time entity lookups are added. A finite default-JVM probe returns equal entities at 251,208 bytes / 0.32 ms before and 83,048 bytes / 0.31 ms after (6 ms form / 47 ms request). The final gate includes every squash Var, including native, numeric and temporal retraction cases. The first exploratory schema comparison accidentally used an empty current projection and is not evidence; the actual database catalog has 290 entries (76 maps, 214 conjunctions).

The final standalone cold squash check `ec2f6e10a41d` passes all six assertions (2.130 s test, 44.261 s total); its measured result is retained in [c3-native-search.txt](astra-cost-handoff-evidence/c3-native-search.txt). Full lint is 0 errors / 1,541 warnings, 6.853 s. The lane gate below repeats both cost bounds and all seven convergence proofs with the named reaching tests.

Expanded gate `4365c9e57a15`: 56 Vars, 856 assertion passes / 4 failures / 0 errors, 160.361 s. All seven C3 proofs, both bounds, and the inherited/catalog producer tests pass. The only red is `seon.schema.program-test/database-shape-render-declarations-resolve`, whose obsolete assertion that function and schema families have no renderers fails identically on `e0bd37443` (raw parent result in c3-foreign-parent.txt). This foreign renderer expectation is excluded from the final 55-Var gate, not changed or counted green. Final cold squash is 7.21 ms / 4,143,352 bytes / 94 armed calls.

C3 landed as `d02a78686`: final gate `6cc4fb6b098a` passes 55 named Vars / 383 assertions, no reuse, failures or errors (161.583 s tests, 199.480 s total). Both cost bounds and all seven convergence proofs pass. Full lint before landing: 0 errors / 1,541 existing warnings. Source delta is +69/-29 lines; test delta +24/-4. The original 18 regressions are included. [Exact gate output](astra-cost-handoff-evidence/c3-gate.txt) records publication, adoption and boot timings. Default remains on its original archive.
