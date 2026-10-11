---
title: "Cost hand-off: committed projection reuse and changed-attribute squash"
type: research
status: evidence
created: 2026-10-12
---

# Cost hand-off: committed projection reuse and changed-attribute squash

`seon.db/transact-call` prepares one projection, validates the speculative report, then hands the committed report to `seon.db/hold-committed-projection!`. The hand-off now associates the already held declaration-revision cell with the new commit, including ordinary writes. Reopening that commit reuses the cell instead of copying every declaration and finding a replacement base.

## Current result and boundary

Root extended ownership to `seon.program`. Both assigned cost bounds now pass, including squash in a standalone cold check. The follow-on C3 configuration-convergence item is separate: its cost entities need explicit provenance and the reconciler currently consumes only transaction provenance. A narrow consumer ownership extension was requested; no other lane was contacted or edited.

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

Raw check outputs and measured flow values are in [astra-cost-handoff-evidence](astra-cost-handoff-evidence/). The standalone projection checks ran green: `seon.db-test/a-definition-write-keys-its-projection-by-its-own-datoms` and `seon.db-test/speculative-declarations-never-enter-the-committed-projection-cache`; the only red in that check was the pre-existing squash bound. The reopening regression and `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` passed together, 47 assertions, run `3eca53dada4e`.

The raw-store/accounting check passed all 18 named tests and 149 assertions, no reuse, failures or errors (run `1e8b8164620a`). It took 135.24 s in test execution and 160.96 s overall; the shell and two publication proofs build complete canonical stores under their existing declared long bounds. Publication rehearsal took 3.25 s, adoption 0.66 s, and candidate boot 15.31 s including 8.40 s to readiness. These are the complete-publication/check operations requested for this platform slice, not the warm fork measurement. A mid-check status sampled 2.07 GB heap used and 0.91 GB live; these are observations, not peak measurements.

Full-tree `clj-kondo --lint src script test --cache false` finished in 6.87 s with **0 errors**, 1,541 pre-existing warnings. `git diff --check` was clean. Production source is +15/-15 lines; the strengthened existing test is +8/-2; the reusable measurement script is 39 lines. The original `e0bd37443` commit message names 14 tests only by category; [tests.edn](astra-cost-handoff-evidence/tests.edn) names the concrete 14 raw-store/accounting regressions run here, alongside the three projection proofs and flow bound. This does not claim recovery of an unavailable exact manifest from another agent's session.

Foreign working-tree edits were excluded from the candidate: `test/seon/reconcile_test.clj`, its landing note, the pre-existing scratch/proof files, and the dirty http-kit submodule. In the squash continuation, foreign runner/SCI test edits were likewise excluded; a concurrently landed runner fix entered HEAD normally. The original edit hook's SCI path reported pending-test selection and acquisition errors; the continuation reports the lane's previously retired admission as closed. No uncommitted source was loaded into default. Host-bound proof is the archived publication/adoption and disposable-JVM check, not the unavailable lane SCI proof. Those diagnostics are not counted as green.

The first lane commit gate stopped before tests in `clojure -Spath -M:dev:test`: Maven's `DefaultModelValidator` threw the known `HashMap$Node`/`HashMap$TreeNode` race. The full diagnostic is retained in `astra-cost-handoff-evidence/classpath-race.edn`; the existing issue is [dependency-resolution-can-race-maven-model-validation](../../../seon/issues/dependency-resolution-can-race-maven-model-validation.md). No source workaround or dependency change was made; the same commit gate was invoked again.

Dependency seams were inspected at the actual pins: Datahike `61bca1edd6bcef079f5c47e4f5f75df30549934d`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`. `datahike.db/advance-cache-context` and `datahike.db/speculative-cache-context` own attribute revisions; `datahike.db/committed-value-identity` identifies the committed value; the existing Malli function contract owns input/output validation.
