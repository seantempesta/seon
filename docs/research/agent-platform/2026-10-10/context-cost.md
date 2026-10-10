---
title: Context cost
type: research
date: 2026-10-10
status: partial
---

# Context cost

The block reads a held task, its references and proofs; end summaries read branch outcomes. Reference rendering computes a complete caller closure before selecting two levels. Stop that walk after the two levels the consumer requests, resolve only their declared targets, and derive the selected tests once. Complete reverse closure retains its original behavior.

Lane: astra-context-cost. Plan §0 item 8, essential 5. Publication and boot derivation are outside this slice.

## Opening evidence

Default: archive `56d2efa61b76743d0127793f3489f9136f3086f1`, PID 55365, started 2026-10-10 13:39:32.837 UTC. Health faulted: ten open faults, one failed test, no completed collection receipt. These existing classes remain with their owners.

MCP JVM, explicit root/default, read-only:

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      items (vec (filter :seon.task/end (seon.cluster.message/inbox d "root")))]
  (seon.profile/measure #(mapv (partial seon.context/ended d) items)))
```

Eleven summaries: first 1,804.44 ms / 2,324.47 MB caller allocation / 2,326.49 MB JVM window / 202,807 armed calls / 315 reads (`result/e7741f97f108f`). Warm: 267.07 ms / 255.51 MB caller / 256.61 MB window / 22,840 calls / 194 reads (`result/e8ab589eb782d`). Warm self time: `seon.db/branch-database` 30 ms (22 calls), `seon.schema/contract-references` 28 ms (338), `seon.schema/range-differences` 28 ms (18), `seon.schema/retained-population` 23 ms (45), `seon.db/declaration-content-key` 19 ms (11).

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      agent "r3-ref-section"
      branch (:seon.agent/branch (seon.db/pull d [:seon.agent/branch] [:seon.agent/id agent]))
      database (seon.db/branch-database d branch)]
  (seon.profile/measure #(seon.context/current-state database agent)))
```

Actual agent branch `:agent-97a679cf8780`: 143.90 ms / 146.28 MB caller / 195.50 MB window / 19,765 calls / 393 reads; repeat 61.32 ms / 77.08 MB caller / 160.18 MB window / 15,330 calls / 394 reads (`result/e1ba6b856036f`). Concurrent activity accounts for the larger window; it is not attributed to this call. Separate component measurement (`result/e33406b7f7b1f`): references 48.62 ms / 60.55 MB caller / 61.57 MB window / 10,204 calls / 358 reads; proofs 4.45 ms / 2.40 MB / 3.43 MB / 993 calls / 14 reads; branch cost 11.98 ms / 21.43 MB / 22.45 MB / 2,891 calls / 6 reads.

`seon.profile/measure` includes all-thread allocation under `:seon.cost/jvm-window`, including unrelated activity. Caller allocation is reported separately, never called whole-run allocation. These functions are synchronous; Datahike materialization uses workers. The window is the conservative whole-run upper bound.

## Dependency seams and proof

Datahike pin `cb3bce514507ecc96d36b6957ad10f6e892f51cf`: `datahike.db/committed-value-identity`, `datahike.db/advance-cache-context`, `datahike.versioning/commit-as-db`. `seon.db/content-revisions` resolves declaration attribute revisions across branch ancestry; unrelated operational writes keep them. Malli pin `dd13ff81139159778edb0a67b491eccc0baa991d`: `malli.core/validator` caches on a compiled schema, and `malli.core/-instrument` validates contracted calls. Seon's central Caffeine cache runs one derivation per key, weighs retention and shares its configured byte bound.

The new proof measures a seven-reference, one-proof current-state block through `seon.profile/measure`, checks its value and uses the existing task's 50 ms / 50 MB budget. The work is seven reference descriptions and one proof, not the transitive program. Allocation is the all-thread JVM window, not the caller-only counter. The measured call is the second block acquisition: first acquisition remains separately reported, never hidden in the budget claim. The test tags both `seon.context/one-agents-current-state-costs-hundreds-of-megabytes` and `seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels` as quoted symbols.

Red before implementation: candidate `19689227c187`, run `7f1cea8f8250`, three passes and two failures: 90.36 ms and 147.609008 MB. The isolated runner's diagnostic is `data/clusters/default/logs/operator-check-20261010T211013.324.edn`. Subsequent candidates established that bounding the walk alone was insufficient; removing complete declared-target acquisition and duplicate selected-test derivation reduced allocation to 52.05 MB. No budget was relaxed.

The final walk does no identity query for an affected-function result that bounded selection never returns, and does not resolve the next frontier after reaching its last requested level. This is proportional to the selected call levels and their declared edges. Declared-edge attributes and test subjects still have attribute-wide reads; this slice does not claim every read is output-proportional.

## Paired live measurements

Root restarted default externally during this lane. The second archive is `2d19dafb037d8ed10bea91f4e246e023bc219dec`, PID 93456, started 14:25:30.641 UTC. A fresh disposable agent `astra-context-cost-probe` held the same immutable database values and eleven inbox entries throughout the following pair. Before used compiled HEAD; after used the three changed functions in SCI, with their affected callers. This is branch evidence, not an installed default JVM result.

| Call, warm | Before ms | After ms | Before JVM MB | After JVM MB | Before armed calls | After armed calls | Before reads | After reads |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `seon.context/current-state`, r3-ref-section | 55.78 | 26.02 | 79.93 | 29.14 | 15,139 | 11,404 | 390 | 292 |
| Eleven `seon.context/ended` | 235.93 | 191.83 | 240.80 | 246.54 | 23,518 | 23,529 | 211 | 211 |

Caller allocation: current-state 79.05 → 28.15 MB; ended 239.89 → 245.50 MB. Handles: before `result/e61fb9e15e0e4`, `result/ec7027282f366`; final after `result/e054a26efbbd2`, `result/e826c9bd396fb`. Ended's time difference is not a demonstrated improvement: reads are identical and allocation increased by 5.74 MB; concurrent JVM activity and cache/JIT history differ. Before the final deletion of the unused frontier query, current-state was 22.40 ms / 29.85 MB / 11,768 calls, and ended 224.17 ms / 241.20 MB / 23,518 calls (`result/e8466f05bc477`, `result/e5c045afea9dc`).

First acquisitions in that pair: current-state before 508.56 ms / 742.35 MB / 37,850 calls, after 144.75 ms / 240.52 MB / 24,940 calls; ended before 1,489.62 ms / 2,206.43 MB / 188,562 calls, after 392.76 ms / 473.80 MB / 31,417 calls. Those first acquisitions have different cache histories and cannot establish a cold speedup. Warm current-state's remaining largest measured self time was `seon.db/query-index-patterns`, 2 ms over 49 calls; the other listed readers were at most 1 ms each.

## Rejected experiments

Moving committed projection retention into `seon.cache`, keyed by immutable declaration revisions, retained about 328 MB and caused 71 evictions. Eleven end summaries stayed expensive: the interpreted repeat was 1,699.81 ms / 2,632.66 MB JVM allocation / 215,720 calls / 315 reads. Retaining whole projections displaced reach data. The experiment was removed from source and its cache entries evicted. There is no database-owner change in this slice and no new cache. Existing content-valid database and runner caches remain in use.

Replacing subject and declared-edge attribute scans with Datalog queries bound to the current targets increased the isolated allocation from 52.05 MB to 55.02 MB and 75.59 MB respectively. Those experiments were also removed. Adding a new cache or index to obscure that cost was not justified by these observations.

## Held-file boundary: end summaries

`root-stage2` holds dirty `src/seon/context.clj` and `test/seon/context_test.clj`. No changes were made to those files. The precise follow-up composition is:

1. `seon.context/ended` already obtains `branch-db`. Pass that immutable value to the error reader instead of making `seon.problems/agent-errors-by-signature` call `seon.db/branch-database` again through `seon.problems/branch-agent-errors`. Convert the latter's callers together; the all-agent health reader should acquire each branch once too. The measured eleven summaries currently acquire 22 branch databases.
2. In the per-agent path, replace `seon.problems/head-evaluation-ids`'s full head scan with indexed membership reads for just the error evaluation ids actually found on that branch. Preserve inherited-error exclusion and unknown/refused reads. The all-agent aggregation may still share one already-held set if measurement favors it.
3. Recorded outcomes must remain valid after branch retirement. Do not skip live error reading merely because an outcome was recorded: the current outcome facts do not include the errors that `ended` displays. If root chooses to record that bounded historical error summary at `seon.context/outcome-tx`, specify its data and tests with the outcome owner before changing the reader. Cache only the small derived summary under all its actual content inputs, not a branch name or a retained projection.
4. Extend the existing end-summary tests to prove live versus retired outcomes and inherited errors, then measure the exact eleven-entry call above with an all-thread budget. The first step alone removes eleven redundant acquisitions but does not promise the entire 241 MB disappears.

This lane does not close the whole context-cost task: end summaries and first acquisition still require work. Publication and boot derivation were not edited.

## Verification and limits

The branch edit path exposed render-time arity diagnostics between the new three-argument `seon.fn/closure-walk` and its old callers while the forms were being admitted. After all three definitions, the measured calls answered. The earlier scratch branch also carried restored database definitions from the rejected experiment and is not suitable for performance comparisons; the clean probe branch above isolates the final source owner.

Full working-tree lint: `clj-kondo --lint src script test --cache false`, 7,913 ms, zero errors, 1,498 existing warnings. Candidate checks independently boot HEAD plus only this lane's paths. Shared dirty context, turn, schema and merge files are outside those candidates. Automatic SCI reaching tests reported failures in fixture/interpreter paths; the isolated candidate results below, not those replies, decide this commit. Root owns broad integration and any default reload.

Candidate `4b4cc9b815289b9dc48faf42f190b2433fa42c39`, run `22e8bb6677bd`: five tests, 22 assertions, zero failures/errors; 4,790 ms test total. The new measured-cost proof passes both 50 ms and 50 MB limits. The other four tests are `seon.fn-test/gate-selection-preserves-cycles-absent-seeds-and-held-values`, `seon.fn-test/tests-reaching-follows-calls-and-explicit-subjects`, `seon.test.accretion-test/one-gate-set-query-includes-edges-subjects-and-pending-tests`, and `seon.test-reaching-test/selection-reads-each-declared-edge-attribute-once`. Check total 35,007 ms: publication 2,961 ms, adoption 812 ms, throwaway boot 17,895 ms. Those operator costs remain outside the context hot path and the deferred publication assignment.

The first lane commit named all seventeen test vars returned by the installed `seon.fn/gate-sets` for the three changed definitions, plus the cost proof and the reference/current-state consumer proofs. Candidate `98552bd0428b885b7520c38bb742a6a0aa61d014`, run `3933a5cc6b67`: twenty tests, 146 passing assertions, zero assertion failures, two errors in one pre-existing red. The commit was refused; nothing was committed from that attempt.

That red is `seon.sci.branch-execution-test/branch-definitions-and-unchanged-callers-share-one-context`: `sci/resolve` returns nil for its unchanged caller, and evaluation cannot resolve `seon.sci.eval-test/cut?`. A standalone check of committed `2d19dafb037d8ed10bea91f4e246e023bc219dec` reproduces both errors, run `54fe779af7e3`, 4,935 ms. Diagnostics: `data/clusters/default/logs/operator-check-20261010T213100.189.edn` and `data/clusters/default/logs/operator-check-20261010T213154.000.edn`. The candidate's parent `17138ccad2bdec907d3df92d2cbba37618bd226a` differs from that baseline only in the merge owner's write-and-commit lock change. This interpreter boundary is outside this slice. The final commit names the other nineteen vars individually, as authorized for namespaces with pre-existing reds.

Cleanup retired both owned SCI branches: `:agent-45ff919a8cac` (clean measurement probe) and `:agent-f1c3991d9467` (original editing lane). Self-retirement first returned `:my.agent/retiring? true`; the root's subsequent retirement returned `:my.agent/retired? true` for both (`result/e226ed484efa7`). The original branch's automatic test step reported a schema mismatch for `:seon.test.run/evaluator` after the external JVM replacement. No foreign session, branch, file or process was changed.

Committed through `bin/lane commit`: `d3d59342b`. Final candidate `1cac9b045a53807adde57add0018b18bd6638f71`, run `a67a5e3b2528`: nineteen named tests, 143 passing assertions, zero failures/errors, 9,347 ms. Candidate lint: zero errors, 1,497 warnings, 6,538 ms. Check total 44,680 ms includes 7,693 ms queue wait, 3,773 ms publication, 1,088 ms adoption, 14,642 ms throwaway boot and 9,432 ms test phase. The measured-cost assertion remained green in this complete selected run.

Size: source +23/−22 (net +1), test +33 (net source/test +34), landing evidence initially +92 lines. Only the three named paths landed. The whole context-cost task remains open at the held-file boundary above; root still needs to install and remeasure the compiled change on default.
