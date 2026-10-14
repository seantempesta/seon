# Boot publication, 2026-10-14

Lane boot-publish. Accepted commit f9a315309 made the widened-publication replay 13.8% faster with 7.5% less calling-thread allocation. The follow-up accepted no further production change: all three candidates failed measured performance acceptance; evidence and two unapplied patches are retained below. The archive hypothesis is refuted. Boot inner-phase instrumentation remains unavailable; actual readiness intervals and instrumented replays are distinguished below.

`seon.cluster/full-source-refresh!` captures changed bytes, calls `seon.fn/analyze-entities`, derives the analyzer identity and file keys, then recursively repeats analysis if other keys are stale. The candidate passes the first call's file artifacts into that existing continuation through `seon.fn/analyzed-files`; the database, projection and captured bytes stay the same. No cache, invalidation rule, or publication selection is added. The discarded parsing, linting and entity construction disappear. Work remains proportional to the union of selected files, plus existing whole-program key comparison and reconciliation.

The smaller alternative is retaining the producer's already computed artifacts, rather than adding a cross-publication cache. `:seon.fn.manifest/artifacts` already names their schema. The continuation keeps the first resolver entries, seeds new analysis with its symbols, then validates the combined artifacts through existing capability and seed checks. Acceptance requires equality with joint analysis, one parse per file, and an end-to-end gain on the same publication probe. This is an EQUIVALENT change only for artifacts from the same publication inputs; arbitrary stale artifacts are not an admitted use.

## Initial evidence

Default PID 96847, installed archive dc47cae3ff490a0d75c44f203f0ce2fc4f1671df. Both `bin/seon status` and MCP `runtime_status` answered: readiness complete, health unknown, one pre-existing `seon.cache/cache?` untested-hot warning, zero reported regressions. No default restart, stop or reset is authorized or performed.

The working-tree-read candidate is **refuted** for this JVM. JVM-mode MCP form, agent root, cluster default:

```clojure
{:source-directory (seon.fs/source-directory)
 :resource (str (clojure.java.io/resource "seon/fs.clj"))
 :cwd (System/getProperty "user.dir")}
```

Result e65b617aac788, evaluation 1 ms: all three resolve under `data/source/dc47cae3ff490a0d75c44f203f0ce2fc4f1671df`. `seon.fs/source-directory` derives from the loaded resource, not the working checkout. `seon.cluster.boot/changed-source-paths` uses that owner; `seon.cluster/refresh-source!` defaults to the same directory. The dig's claim confused a source directory with the working tree.

## Measurements

| Probe | Publication / readiness | Other evidence |
|---|---:|---|
| Exact HEAD 38cca803d29a5cd60c053eca85992b8ecc83079b, run 70766a90086c | ready 8,795 ms | total 36,003 ms; 1 test, 4 assertions green |
| Same archive, run 80520845c8f8 | ready 8,502 ms | total 31,008 ms; 1 test, 4 assertions green |
| Installed boot publication replay from prior source commit 6acf4a47-9e48-5e59-a35d-549d4e580fe8 | 33,983.97 ms | 46 changed inputs; 542 additional stale files; 41,585,766,480 calling-thread allocated bytes |

The exact-HEAD checks use the dc47cae3f template: no working-tree change does **not** mean no publication delta. The operator's `source-ms` measures archive preparation, not the `:seon.source/current` readiness interval.

The replay uses `seon.cluster.store/open-overlay!`, a private resolver cache and the same source archive as default. It retires/rebranches current-src only in that overlay. Result eae53ebf3d15e, 34,559 ms evaluation, 34,662.5 ms entire request. Both resources are released in `finally`. Concurrent default rehearsals were observed in the JVM-wide cells: four kondo calls and two `seon.cluster/rehearse-source!` calls. Therefore those cells cannot be attributed exclusively to this replay.

| Replay phase | Offset ms | Work proportional to |
|---|---:|---|
| First analysis and file-key comparison complete | 5,111.58 | Selected sources; all stored derivation inputs for key comparison |
| Second analysis complete / publication begins | 25,472.39 | Selected sources again plus 542 stale files |
| Schema population complete | 25,484.65 | Schema population delta |
| Contract projection begins | 27,819.35 | Reconciliation preparation over selected program entities |
| Contract facts complete | 28,520.80 | 3,797 function entities |
| Reconciliation transaction begins | 29,082.78 | Changed entity attributes and validation |
| Program entities complete | 34,030.48 | Writer validation and transaction |
| Publication complete | 34,420.27 | Source identity and branch head |

Offsets include approximately 436 ms setup outside `seon.profile/measure`. The first discarded interval is an opportunity, not a promised saving.

## Regression proof

The first regression on the unchanged producer is red: run a686a4dcc382, 3,462 ms, one pass and one failure. Joint analysis and continuation return equal entity sets, but `seon.profile/calls-of` for `seon.fn.analyzer/parsed-facts` is 2, expected 1. Diagnostic operator-check-20261014T170026.955.edn.

## Dependency seams

HEAD Datahike pin 6d49110d336f8e21bf828d006e3a885ac1c12a34: `datahike.versioning/commit-as-db` materializes one immutable commit; `datahike.versioning/release-materialized-db` releases its native resources. `seon.cluster.store/open-overlay!` composes the existing Datahike overlay backend, whose writes remain in memory. Malli pin dd13ff81139159778edb0a67b491eccc0baa991d: `malli.core/-instrument` compiles and applies the declared argument/result schemas. No validation exemption is added.

## Controlled replay and cache audit

The reusable probe is `seon.boot-publish-probe-test/publication-widening-measurement`. It clones a fixed input archive, prepends one comment to the same eight large source files, publishes each attempt into a new Datahike overlay, and records phase callbacks and `seon.profile/measure`. Its loaded-files argument is explicitly empty on both sides, so both must rederive the program under an unknown loaded analyzer rather than accidentally measuring an unchanged key. This is the widening path, not a claim about an ordinary one-file body edit.

The input is an owned copy of archive 76c4bed093241fcf2464b64f98998ac3175433f6, retained outside the operator's collectible `data/source` directory. An earlier attempt using a collectible archive failed during cloning after root's external restart; that run (cee3d9b5b449) is not performance evidence. Default changed externally from PID 96847 to 7558 at 10:03:31 UTC; this lane did not restart it.

Initial parent run e2b96fa12ebc: 27,579.33 ms and 35,505,436,280 calling-thread allocated bytes. Candidate ed075a2924b9: 19,053.62 ms and 24,354,077,256 bytes; six continuation assertions plus the publication assertion passed. These first numbers are **not acceptance evidence**: the parent's `seon.fn.analyzer/parse-facts` ran 568 times and the candidate's eight times, because candidate boot had warmed content-keyed parsed facts. The subsequent paired probe runs three publications per JVM and compares equal warm states separately from first-use cost. The parent was also rerun with both added test files, equalizing the widened inventory (570 selected inputs, including two absent probe files).

The exact-HEAD and one-file-check observations now have a real readiness interval: the first one-file probe (cee3d9b5b449, before its fixture-copy failure) reached source/current at 6,086 ms after store at 394 ms: **5,692 ms**. The successful one-file parent e2b96fa12ebc reached source/current at 5,777 ms and readiness at 8,838 ms. These are boot observations, separate from the instrumented publication replay. Boot publishes before `seon.cluster/arm-host-program!`; inner publication cells do not exist for that original boot. The existing task `seon.cluster/boot-publication-phases-are-invisible` owns that observability gap. Later lifetime cells must not be mislabeled as its profile.

Current `seon.fn/tests-reaching` over each of the two changed functions names 53 tests, retained in boot-publish-reaching.txt. The broader `seon.fn/gating-tests` walk names 2,800; the central broad tier belongs to root. The lane verifies the 53 reaching tests and the added regression/probe, classifying baseline reds by var rather than weakening tests.

## Reaching-test baseline

Parent archive 7ed5ac91ee3c218627e9e5c08d9d6e132b62eba4, run d0154ac05135: all 53 requested tests executed, 296 passing assertions, 34 failures and nine errors across 14 red tests, 279,379 ms. Diagnostic operator-check-20261014T172038.044.edn. These are pre-existing reds; no test was changed or relaxed.

- `my.program-test/an-agent-writes-back-a-file-whose-analysis-runs-a-hook`
- `my.program-test/write-branch!-writes-my-defn-into-its-file`
- `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change`
- `seon.cluster.merge-test/an-accepted-merge-is-published-by-the-next-pass`
- `seon.cluster.publication-delta-test/selected-entities-reconcile-without-a-manifest`
- `seon.cluster.publication-host-test/live-init-keeps-the-host-process-and-launches-no-jvm`
- `seon.cluster.publication-inputs-test/an-unchanged-explicit-path-is-captured-once-and-keeps-the-head`
- `seon.cluster.publication-reuse-test/recorded-result-resolves-after-one-file-publication`
- `seon.fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes`
- `seon.fn.publication-test/incremental-indexing-reconciles-only-selected-file-entities`
- `seon.incremental-publication-test/non-program-inputs-have-their-own-publication-owner`
- `seon.turn.batch-test/a-repl-deftest-in-a-marked-namespace-is-written-back-and-reindexes-equal`
- `seon.turn.batch-test/a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal`
- `seon.turn.batch-test/an-unchanged-committed-nilable-contract-is-admitted-and-written-back`

The continuation also carries `:seon.source/published`, the original immutable publication basis. It must not reacquire a newer current-src while retaining artifacts derived from the earlier database. The existing writer's expected-commit comparison remains the authority. Held artifacts supply their own file digests, and capture reads only the additional files, so the snapshot and retained entities describe the same bytes. These changes remove repeated reads; they add no lock, retry, cache, or competing acceptance check.

## Accepted measurement and remaining cost

The final code preserves the original publication basis, retained file digests and legacy ordering when no artifacts are held. Parent run `0fadbdb0129b` (archive `7ed5ac91ee3c218627e9e5c08d9d6e132b62eba4`) and final candidate run `9c9126785675` (archive `1bacf54477b54586da56264e04317b4d9a6a4df2`) ran the same three-publication probe during this session in separate disposable JVMs, with the same owned input copy and private resolver caches. The first sample is excluded from the warm comparison because boot warms different parsed-fact contents. Both compared samples on each side have zero parse-facts cache misses. This is an end-to-end publication measurement, not a sum of cells or a total-boot speedup claim.

| Same widened publication | Parent | Final change | Difference |
|---|---:|---:|---:|
| Warm sample 1 | 17,732.16 ms | 15,354.75 ms | -13.4% |
| Warm sample 2 | 17,800.93 ms | 15,274.52 ms | -14.2% |
| Warm mean | 17,766.55 ms | 15,314.64 ms | **-13.8%** |
| Mean calling-thread allocated bytes | 21,676,851,216 | 20,040,959,468 | **-7.5%** |
| Parsed-facts visits per publication | 576 | 568 | Eight repeated file visits removed |
| Kondo calls per publication | 2 | 2 | Unchanged |

Raw phases and cells are retained in [parent evidence](boot-publish-parent.edn) and [change evidence](boot-publish-change.edn). Allocation is cumulative thread allocation, not retained heap. JVM-window allocation includes compiler/worker activity and is separately labeled in the evidence.

The final exact-HEAD check, run `37e312fd276a`, booted archive `2b5829f40ab4a836ee5d4ab00cba6cd070647cf0` without working-tree paths. Store was ready at 474 ms, source/current at 9,588 ms: **9,114 ms publication interval**, ready at 11,261 ms; the operator test passed four assertions. Its store seed was 76c4bed, so this is an exact-HEAD check, not a zero-publication-delta proof. The final candidate boot's store-to-source/current interval was 23,666 ms, ready at 26,424 ms; changing the analyzer itself invalidates the program. These different boot deltas cannot be compared as a performance A/B.

| Final warm sample 2 cell | Self ms | Calls | Proportional to / next action |
|---|---:|---:|---|
| `seon.fn.analyzer/invoke-kondo` | 4,886 | 2 | Two selected-source lint invocations and resolver synchronization; largest remaining cell. Inspect clj-kondo cache synchronization and repeated resolver work before changing it. |
| `seon.db/transact-call` | 1,655 | 3 | Program reconciliation, checked transaction and publication facts. |
| `seon.fn.analyzer/analyze` | 1,397 | 2 | Selected source analysis processing, beyond kondo. |
| `seon.db/pull-logical` | 870 | 1,658 | Pulled declaration/namespace values. |
| `seon.fn/analyzed-artifacts` | 630 | 4 | Selected-file artifacts; now each file is retained across widening. |
| `seon.fn/analysis-entities-by-file` | 574 | 2 | Conversion of selected analyzed entities. |
| `seon.program/with-contract-facts` | 332 | 4,192 | Contract derivation across the widened program. |

Whole-program work still exists when the analyzer identity changes: derivation-key comparison, the required widened analysis, contract facts and reconciliation. This slice removes duplicate work over already selected files, not the whole-program invalidation rule. Ordinary publication is still above one second; the supplied assignment authorizes these long boot/publication probes. The remaining work and observability gap stay explicit rather than being called cold or expected.

## Follow-up: Kondo, projection and declaration costs

Root accepted f9a315309 and requested the next measured slice. The continuation begins with default still on 76c4bed, ready 12,918 ms, health faulted (two open faults, four agent-error signatures, 56 errored receipts), no reported regressions. These are pre-existing observations. No change is loaded into default by this lane.

The replay now records selected profile cells separately and can optionally record JFR with `SEON_BOOT_PUBLISH_JFR`. The same unchanged 76c4bed input copy is used. Initial parent run `0f3299412105` passed all three publication assertions; its warm samples were 15,697.78 and 15,014.78 ms. This is diagnostic evidence; the sort pair below uses the same parent commit after another lane's 2dba90a5b changes landed.

| Owner, final warm diagnostic sample | Calls | Self ms | Inclusive ms | Proportional to |
|---|---:|---:|---:|---|
| `seon.schema/call-with-projection-state` | 0 | 0 | 0 | Absent from this publication replay. Its default lifetime total does not identify publication work. |
| `seon.db/with-declarations` | 7,510 | 23 | 1,351 | One entrance per database read, plus the read callback's work; not 1.351 s of declaration derivation. |
| `seon.db/read-declarations` | 7,510 | 3 | 6 | Constant installed-schema lookup and delayed projection per read. |
| `seon.db/carried-projection` | 67,197 | 40 | 492 | Projection acquisition on read paths, using the existing content/revision cache. |
| `seon.schema/build-projection` | 1 | 0 | 10 | Existing cached projection for the held schema population. |
| `seon.cluster/derivation-keys` | 2 | 9 | 22 | File-key comparison over the program, with shared schema derivation cached by content. |

Kondo's pin is `eb245d8260736ca463cda85219b6fc3ae73699c3`. `clj-kondo.core/run!` calls source processing on every `:lint` input, then `clj-kondo.impl.cache/sync-cache*` loads missing used namespaces and combines them with this run's declarations. This cache is a resolver cache, not a stored complete analysis result. `clj-kondo.impl.cache/update-defs` avoids resolving types of disk/built-in declarations again. It does not scan every cached namespace. In the replay the publisher selects eight changed texts and then 560 additional extant files because the probe deliberately supplies an unknown loaded analyzer (`:seon.cluster/loaded-files {}`). Kondo receives that selected program, not the entire dependency classpath. The file-key owner treats changed deriving code as an invalidation even when source text stayed the same.

JFR across the three parent replay samples recorded 3,632 execution samples: 1,190 contained Kondo frames, 889 its expression analyzer, 124 final var-usage lint, and only 12 cache synchronization. These categories overlap and are sampled stacks, not additive exact milliseconds. This refutes cache synchronization as the dominant cost in this run. Plain source-content caching of complete analysis would be incorrect: findings and resolution also read dependency declarations, macros, configuration and hooks. A durable raw-analysis reuse design would need those exact dependencies; the existing Kondo cache exposes no complete-analysis reuse seam. No new cache or inference that unchanged text means unchanged analysis is introduced.

A directly repeated cost isolated in Seon's analyzer was source-order key construction (87 sampled stacks). Clojure's `clojure.core/sort-by` calls its key function on both operands at every comparison. `seon.fn.analyzer/normalized-entries` and findings sorting therefore repeatedly build the same seven-member key and symbol strings. The rejected candidate `seon.fn.analyzer/ordered-entries` paired each entry with its key once, uses the same stable comparison on those keys, and returns the entries. Key construction changes from comparison-count work to one per analyzed entry; comparison work remains O(n log n), and temporary key storage is O(n). No cross-call cache or state is added. This is EQUIVALENT: the comparison keys and stable tie ordering are unchanged. Existing analyzer parity/findings/identity tests prove the behavior; the full publication replay decides performance acceptance.

The first sort candidate run `a95ae8584338` passed 14 tests / 73 assertions, with warm publication 14,508.98 and 14,270.26 ms. The reduced analyzer self cell was partly attribution moving into the newly instrumented helper, not an equivalent elapsed-time gain. Also, 2dba90a5b landed between those first parent/candidate archives, increasing reconciliation from 4,192 to 4,282 entities. The exact paired archives 3c4441715203f472450e203259f079a32beb5d6c and ca0e8878d029c61b328fa623fdbb1c1a6584a66f differ only in analyzer.clj. Candidate warm samples were 13,932.41 / 13,911.17 ms. A repeated parent answered 14,052.58 / 13,683.12 ms: parent mean **13,867.85**, candidate **13,921.79**, a **0.4% regression**. The sort change was removed and is not committed. This is why isolated self cells and the first comparison were not used as acceptance.

Both full runs requested the same 40 reaching tests plus the replay. They had the same four red vars: `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`, `seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`, `seon.public-contract-test/public-contract-census-refuses-an-absent-source-root`, and `seon.reset-edges-test/source-publication-refuses-an-outside-caller-and-keeps-its-head`. They respectively concern namespace source identity, existing uncontracted public functions, missing-root error data, and missing fixture dependency analysis. No assertion was weakened.

### JVM dialect selection

Seven production .cljc files contain 4,708 lines on 4dd6fab76. The library's `clj-kondo.impl.config/cljc-features` defaults to `[:clj :cljs]`, and `clj-kondo.impl.analyzer/analyze-input` analyzes each selected dialect. `clj-kondo.core/run!` supports `{:cljc {:features [:clj]}}`. This is upstream configuration, not a new cache, parser, filter or fork bump. Publication currently builds both dialects and then `seon.fn.analyzer/jvm-entry?` discards the ClojureScript entries in most result vectors. Protocol implementation entries have no language field in `clj-kondo.impl.analysis/reg-protocol-impl!`, so the old output actually leaks ClojureScript implementations.

Live JVM probe efef417a33e8a, 19 ms: captured .cljc text declares P/p and extends String in :clj and string in :cljs. `seon.fn.analyzer/analyze` returns both `clojure.core/extend-type` and `cljs.core/extend-type` in `:seon.fn.analyzer/protocol-impls`. The existing `seon.fn.analyzer-test/jvm-projection-excludes-cljs-and-keeps-generated-constructors` is extended with this case. The candidate selects the JVM dialect at the Kondo input and contracts that request. Shared dependency-cache population continues analyzing both dialects. Root externally restarted default onto 4dd6fab76 during this follow-up; the lane did not do so. The paired replay uses that new baseline on both sides. Parent red run `27700c52247c` had seven passing assertions and the expected protocol-implementation failure. Candidate run `a492bbb9270b` passed all analyzer tests plus publication: 14 tests, 74 assertions. The first matched warm replay did not improve wall time: parent 15,623.80 / 15,516.97 ms; candidate 16,476.54 / 15,884.39 ms. Allocation fell only about 0.9%. A later six-sample candidate cohort overlapped an automatic edit-hook SCI evaluation (47,949 ms, 171 forms / five changes), so it is not quiet acceptance evidence. The production and test edits were removed; [the unapplied correctness patch](boot-publish-jvm-dialect.patch) retains the reproduced defect and fix for a separately justified slice. No performance acceptance is claimed.

### Per-file declaration lookup

`seon.fn/analysis-entities-by-file` reduces over every source context; inside that loop it scans the full `:seon.fn.analyzer/var-definitions` vector to choose declarations matching that filename, even when no invocation attributes exist. For F selected files and D selected declarations this is O(F × D) filename tests. The candidate groups the same definition vector by filename once, then maps only the file's group in the existing loop: O(D + F) selection work, O(D) temporary references. `clojure.core/group-by` owns the grouping. Inputs, per-file order, duplicate handling, context selection, and the resulting holder maps stay identical. This EQUIVALENT slice removes the whole selected-program scan from each file and adds no cache or persistent state. The 20 existing reaching tests are named in [the grouping reaching set](boot-publish-group-reaching.txt); they are not claimed green by this experiment. The end-to-end replay decides performance acceptance.

The exact parent `7bcdbdd4e84346fb6f7eb74758189e0b6819aecc` (run `ebde15294866`) and candidate `a63386a92de31667ee47b851e532751864f2ef62` (run `a28738936d8b`) differ only in `src/seon/fn.clj`. A detached worktree froze the parent after other lanes advanced HEAD; both checks used the same 4dd6fab76 template and fixed 76c4bed inputs. Each passed six publication assertions. The first sample is retained but excluded from warm comparison because boot warmed different source-content cache entries.

| Grouping replay, warm samples 1–5 | Parent | Candidate | Difference |
|---|---:|---:|---:|
| Publication mean | 14,246.35 ms | 14,984.99 ms | **+5.2%** |
| Calling-thread CPU mean | 12,256.56 ms | 12,842.16 ms | +4.8% |
| Calling-thread allocated bytes mean | 20,100,051,445 | 20,029,655,762 | −0.35% |
| Final publication sample | 14,155.45 ms | 14,382.33 ms | +1.6% |

The O(F × D) scan exists, but this candidate did not produce an end-to-end gain. The code was removed and [its unapplied patch](boot-publish-group.patch) retained. This is a rejected experiment, not a claim that grouping intrinsically slows the algorithm. The two JVMs can differ in JIT/GC state; that uncertainty is a reason to reject acceptance, not to select a favorable sample. [Raw follow-up parent evidence](boot-publish-followup-parent.edn) and [candidate evidence](boot-publish-followup-change.edn) also retain the sort and JVM-dialect comparisons.

The final parent still spent 4,448 ms self in Kondo (three invocations), 1,763 ms in the checked transaction owner, 1,360 ms in analyzer result processing, 841 ms in logical pulls (1,692 calls), and 503 ms in entity conversion. Kondo and result processing follow selected source/analysis size, widened to the program when deriving inputs change. Reconciliation and contract facts follow the selected program population (4,359 contract calls in this replay), then the changed transaction data. These remain the next costs; schema projection construction was only 12 ms inclusive and derivation-key comparison 20 ms inclusive. A cache of complete analysis would need the actual resolver/config/hook dependency content, not merely a file-text digest. No such cache is proposed or installed by this slice.

### Isolated start --head comparison

The owned worktree/operator root started committed f9a315309, then ran an unchanged `bin/seon start --head`, then changed its detached HEAD to operator-generated candidate 4d6adb563. `git diff` proves that candidate changes only `test/seon/boot_publish_probe_test.clj` (26 added, two removed lines). Both replacements retained the same store and caches. Dependency pins and target classes used the existing shared archive caches; source publication still read committed archives. Default was never stopped or restarted. After the measurements, `bin/seon down` found no remaining process; exact-PID inspection and root status confirmed no live JVM. The owned worktree was then removed.

| Real start --head | Ready ms | Store-to-source/current ms | Operator total ms |
|---|---:|---:|---:|
| Unchanged f9a315309 | **3,556** | 184 (135 → 319) | 9,353 |
| One test file changed, 4d6adb563 | **4,836** | 3,173 (126 → 3,299) | 14,396 |

Initial empty-store boot was 47,391 ms and is excluded from this comparison. These are one observation each, not a median or a performance-fix claim. [Exact operator start observations](boot-publish-restarts.edn) retain readiness, process identities and phase facts.

## Original slice validation and handoff

Candidate run `59f5b6ec1151` executed the 53 reaching tests plus two added tests: 305 passing assertions, exactly the same 34 failures and nine errors in the same 14 baseline-red vars listed above; no newly red var. This ran before the final basis/digest retention refinements. Final exact-code run `9c9126785675` passed both new tests, nine assertions, zero failures/errors. The commit gate names the 39 green reaching vars individually plus both new tests; the pre-existing 14 reds are excluded by var as authorized. [The complete reaching set](boot-publish-reaching.txt) remains reviewable.

`clj-kondo --lint src script test --cache false`: 8,743 ms, zero errors, 1,696 existing warnings. `git diff --check` passed. Production changes: 38 added / 17 removed lines, net +21; two new test files: 101 lines. The only production paths are `src/seon/cluster.clj`, `src/seon/fn.clj` and `resources/seon/schemas/seon.fn.edn`. No critical-path file was edited. Foreign dirty context/schema/test files and the http-kit working tree are excluded from the candidate archive. The baseline-red publication/merge/write-back tests are the foreign verification boundary, not a green claim.

Reproduce with `seon.boot-publish-probe-test/publication-widening-measurement`, setting `SEON_BOOT_PUBLISH_INPUT` to an owned retained copy of the 76c4bed archive and `SEON_BOOT_PUBLISH_OUTPUT` to an evidence path. Run parent and candidate with both new test files present; compare samples 1 and 2, retaining sample 0 as first-use evidence. `boot-publish-check.py OUTPUT --at HEAD --test seon.operator-test/check-names-the-tests-its-throwaway-jvm-runs` retains a real check's readiness before its owned JVM exits. Its postboot lifetime cells are not original boot cells.

**RESTART NEEDED** after root's scheduled batch to install this host-bound change on default. This lane has not stopped, reset or restarted default. No claim is made about browser paint or retained heap. The next largest measured cost is kondo (4.886 s self in the final warm sample), followed by the checked writer (1.655 s).


## Follow-up validation and handoff

The follow-up changes only the existing measurement test and dated evidence. No production path or critical-path file remains changed. The test records six overlay publications, selected cumulative-cell deltas, calling-thread CPU, and optional JFR. CPU includes the small phase-output/cell-observation overhead around the measured publication; the existing profile duration remains the end-to-end comparison. No timing threshold or cache is added. The only test reaching the changed diagnostic body is `seon.boot-publish-probe-test/publication-widening-measurement`, explicitly named at the commit gate.

Foreign boundary: task/namespace changes advanced HEAD during investigation; comparisons that crossed those changes were discarded, and exact archive diffs established the final pairs. The four existing analyzer-reaching red vars above remain outside this slice; no test was relaxed and no foreign source was edited. Default was restarted externally by root onto 4dd6fab76 during the session. This lane performed only the isolated restart pair described above. It does not request another default restart for these diagnostic-only changes.

Reproduce the six-sample probe with `SEON_BOOT_PUBLISH_INPUT` pointing to an owned copy of archive 76c4bed093241fcf2464b64f98998ac3175433f6, `SEON_BOOT_PUBLISH_OUTPUT` naming a writable evidence file, and `bin/seon check --at REV --test seon.boot-publish-probe-test/publication-widening-measurement`. Set `SEON_BOOT_PUBLISH_JFR` optionally; inspect JFR with `jfr print --json --events jdk.ExecutionSample --stack-depth 128`. Compare identical test/input inventories and store templates, and verify the archives differ only by the intended candidate. There is no accepted improvement beyond f9a315309.

Final full-tree `clj-kondo --lint src script test --cache false`: 7,428 ms, **zero errors**, 1,702 existing warnings. `git diff --check` passed. Diagnostic test change: 33 added / four removed lines, net +29; production source delta: zero. The commit gate runs the diagnostic var on HEAD plus exactly these claimed paths.
