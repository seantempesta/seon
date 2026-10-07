# Publication cost, second pass

Publication captures changed bytes, derives entities, compares per-file derivation keys, reconciles the source branch and adopts selected identities. This slice reuses immutable schema/task derivations, seeks selected identity values, and passes prior declarations between the existing owners.

## Status and boundaries

Task: `seon.cluster/publication-derivation-checks-read-the-whole-program`, returned handle **253286d36d52**. The targets are **not met**. Final code commits are **9e0f1cba8** and **bb4446294**. The same second append measures **205.425 ms / 224,923,280 allocated bytes**, versus parent **354.772 ms / 646,197,480**; the next append measures **171.123 ms / 156,284,328 bytes**. Final cold readiness is **22,019 ms**, versus parent **23,171 ms**. The publication task remains open; this is not a completed ten-second boot or sub-100-MB publication fix.

Default was faulted on entry: readiness 23,931 ms, three open fault signatures, 17 errored receipts and three regressions. Its faults named `seon.turn/show-row` (nil shown failure), `seon.turn.world/record-fault` (branch custody mismatch, task 3d2fb1b41dc8), and `seon.cluster.store/refuse!` (absent agent branch). The lane did not stop, reset, reload or install prototype code into default. The check tool's normal live-store branch publication/adoption is the only candidate interaction with that store.

All prototype execution used the owned root `scratchpad/publish-cost2/boot-root`; the source archive's `src/my/background.clj` received comment appends. A copied default store could not be opened at another canonical path: the store owner correctly refused its different store identity. That failed attempt never modified default. Instead the installed operator's `prepared-source!` and `launch-child!` created a store at the owned path from a9d1c4d33. A clone of that stopped store was retained, and each cold run restores it at the same path. This is one boot/publication path, not a second implementation.

Other lanes' dirty files and sessions were not touched. `tmp/test-data-assertions/proofs.edn`, the HTTP-kit submodule and untracked directories are excluded from candidate inputs. No worktree, push or PR. The file ledger names this lane's source/test files and this note directory.

## Cold baseline: what the batch actually invalidates

Baseline: HEAD 7f1b865948385dd6898bb2c0bc0bc0cb99bb2e6bbb5 over the store seeded at a9d1c4d33, source commit 6ac656fc-7c3d-504c-b7cd-f1355867baf3. There are 20 changed source/test/resource files between these revisions, but only **17 Clojure byte digests changed while all 475 Clojure derivation keys changed**. The analyzer's reached definitions changed. The existing key law therefore requests whole-program reanalysis; this is not evidence of a half-second cost per changed file. `changed-counts.clj` records the comparison.

Baseline phases: store 207 ms; source current 20,994 ms; SCI context 22,574 ms; graph 23,162 ms; ready **23,171 ms**. Operator total was 32,188 ms. Seed installation took 40,609 ms; that is a fresh store, not the incremental cold comparison.

JFR was attached after prepl advertisement with stack depth 256. Baseline main-thread samples rank as follows. Bytes are **weighted JFR samples**, not exact ThreadMXBean allocation counters; sample counts are CPU evidence, not wall durations. Owners are the nearest Seon frames, excluding instrumentation/declaration binding. Inclusive parent timings must not be added to these rows.

| Owner | Execution samples | Weighted allocated bytes | Proportional to |
|---|---:|---:|---|
| analyzer/invoke-kondo | 344 | 7,994,231,048 | selected source text; this boot selects all 475 files |
| db/pull-many-plan-with-evidence | 153 | 5,642,107,840 | pulled entities, selectors, refs and decoding |
| db/vector-query-call | 93 | 4,129,761,512 | query inputs/results and declaration decoding |
| analyzer/parsed-facts | 55 | 2,872,362,696 | parsed nodes and repeated fact selection |
| analyzer/ensure-dependency-analysis! | 49 | 749,675,936 | dependency analysis acquisition |
| schema/direct-references | 47 | 2,133,201,488 | schema forms and their references |
| analyzer/contains-position? | 39 | 1,585,037,648 | usage/span comparisons |
| schema.edn/read-schema-resource | 36 | 1,228,050,832 | schema resource text |
| fn/declared-function-targets | 29 | 1,924,700,608 | literal forms searched for declared invokers |
| fn.signature/read-source | 29 | 327,071,536 | function contract/source forms |

The baseline recording has 1,395 main-thread execution samples and 42,070,991,008 weighted main-thread allocation bytes. The compact ranks are in `cold-profile.json`; original JFR remains in the owned scratch directory; generated full stack JSON was removed after compact ranks were saved.

The first candidate's recording attached too late to capture its analyzer phase. Its 492 main-thread samples and 15.2 GB weighted allocation **cannot** be compared to the complete parent profile. They do identify later publication reads: `reached-entities` 3.060 GB, `file-identities` 1.886 GB, and `report-identities` 1.009 GB within pull-many. Those observations led to the additional identity/read-reuse fixes below. Its actual readiness, 24,403 ms, is valid and misses the target.

## Design checks and implemented fixes

All changes below are **EQUIVALENT**, with the stated preconditions. No derivation-currentness, writer conflict, task admission or deletion check is skipped.

1. **Stored file schema inputs.** Read file identities/digests, then pull keyword/contract facts only for cache misses. The cache key is the source producer's file derivation digest plus path, and the cache retains raw keyword candidates. Filtering those candidates against the current schema world happens later, so adding a formerly absent schema still changes the affected key. A source publication's admitted file derivation digest identifies its analyzed declarations. Legacy rows without a digest are read without caching. This replaces whole-program keyword/spec joins with file metadata reads plus changed-file facts.
2. **Schema hashing and invocation targets.** Cache the immutable declaration world's derivation forms/digests and population invocation targets in `seon.cache`. Keys contain the complete forms/dependencies/shapes or complete forms/attribute inputs. No commit stamp or mutable world is cached. Existing per-file digest results remain identical; only changed inputs miss. Weights include retained declaration forms using the schema owner's calibration. A file without a declared invoker no longer receives a second source parse to discover invocation targets.
3. **Selected analyzer reads.** Prior definition names use reverse refs from selected files, and an empty removed-definition set issues no reverse query. A separate attempt to replace analyzer-reach's frontier pulls with tuple joins increased warm allocation and was removed. The prior lane's reached-frontier algorithm remains intact.
4. **Task reconciliation.** Cache declaration member selection on the immutable projection; cache parsed task files by grammar content, path and text; cache basis/delta derivations by full declaration forms, current task attribute datoms, files and supplied basis. The writer recomputes that content key in its own database, so competing edits still refuse. Native task datom equality suffices because these results do not observe transaction time. A changed task field, schema, file or caller basis misses. Errors/throws are not retained. Existing files are read once, with empty declarations only for removed paths. The content read remains proportional to task facts: this does not claim zero whole-population input checking.
5. **Identity values and prior declarations.** Once selected entity IDs are known, query identity attribute/value tuples directly instead of constructing pull maps with every identity selector for every entity. `report-identities` still reads both sides of the transaction; empty IDs produce the empty set. `incremental-projection` now consumes the old declarations that `desired-entities` already holds, removing a duplicate full export from the same immutable database. The contract names that additional held input.
6. **Span comparisons.** Compare scalar row/column bounds directly. The former implementation allocated three vectors, three `juxt` closures and a concatenated sequence for each comparison. Filename equality, integer checks, inclusive start and exclusive end remain the same. A complete total predicate contract is added, and the analyzer regression checks neighboring quoted and called Vars.

These serve the agent's publication/adoption step and remove repeated derivations and reads inside its existing path. No additional evaluator, registry, writer, scheduler or store is introduced. The smallest alternatives were direct selected reads and passing held values; content caches are limited to measured pure derivations. A global file-byte seal for the analyzer graph was rejected: schema invocation targets can change graph edges without changing those file bytes.

Dependency guarantees used: Datahike pull-many aligns results with requested refs (`reference-code/datahike/src/datahike/pull_api.cljc`); index seeks and tuple queries operate on immutable database values; `datahike.datom` equality omits transaction time, which is not observed by task declaration results. Malli `m/children`/`m/deref` and registry `mr/schema` supply the task members from their owning schemas. Cache failures are not retained (`seon.cache/lookup`). Detailed pinned source locations are recorded with final validation below.

## Measurements

Warm probe: append a comment to the same isolated archive file and call `seon.cluster/refresh-source!` without development adoption. Each record includes exact calling-platform-thread bytes, total ThreadMXBean allocated bytes, timing, armed call count and the returned publication value. Total bytes include concurrent worker allocation; they are not retained heap measurements.

| Same probe | ms | Total bytes | Calling-thread bytes | Armed calls |
|---|---:|---:|---:|---:|
| Parent, stable second append | 354.772 | 646,197,480 | 627,046,400 | 58,627 |
| Schema/task/selected-read prototype | 158.056 | 152,592,352 | 132,567,632 | see `empty2.edn` |
| First exact candidate, stable second append | 177.667 | 143,805,352 | 123,317,536 | see `candidate-warm2.edn` |

Additional same-held-value comparisons:

| Probe | Before | After | Equality / scope |
|---|---|---|---|
| 1,379 selected entities' identities | 153.0085 ms / 418,619,368 bytes | 1.86775 ms / 4,118,832 bytes | identical identity sets, same held DB |
| 100,000 span comparisons | 31.769708 ms / 129,263,184 bytes | 22.35025 ms / 33,995,008 bytes | 50,000 matches on both; after is armed |

The initial one-file call allocated 2.091 GB / 824.607 ms before warming. It is recorded rather than silently discarded. Warm schema dependency reads formerly cost ~186 MB; cached task reconciliation reduced its measured publication phase from ~61.6 MB to ~20.7 MB. Individual stage timings are observations of held values, not a substitute for the final same-probe whole publication and cold boot.

## Verification so far

First candidate 7552850bc920781922381e145d90ff5714cb2ce0 passed publication/adoption on a live-store branch and 11 focused tests: **116 assertions, 0 failures, 0 errors**, 5,049 ms. The check total was 64,188 ms, publication 7,060 ms and adoption 1,716 ms. Its fresh-store check JVM ready time was 36,710 ms; that is not the older-store cold comparison. The subsequent identity-read/reuse and span changes require the later gate result.

Full checkout lint after those changes: **0 errors, 1,457 warnings**, 9,778 ms. Existing warnings are not presented as zero findings. Prototype installer mistakes (a stale helper arity, an initial overly narrow row contract and scratch syntax mistakes) were corrected on the isolated root; none was installed into default. A replaced private Var visible only in the prototype JVM is a probe artifact, not proof of publication/adoption. Exact candidate tests are the installation evidence.

The corrected second candidate e6406feac173e3e04fe5afaf9aecbc120e159e30 passed **16 tests / 127 assertions / 0 failures / 0 errors**, 5,200 ms. Check total 56,903 ms; publication 6,039 ms; adoption 1,572 ms; fresh-store ready 30,610 ms. The preceding check's two reds were introduced by this lane: `vals` of an empty prior map returned nil rather than the new contract's sequence. The producer now passes a vector; the existing fresh-file publication tests reproduce and prove the fix. No pre-existing red is excluded from the focused list.

On the same restored older store, that candidate reached source current at **19,558 ms** and ready at **21,681 ms** (parent 20,994 / 23,171 ms). Automatic JFR attachment at the next prepl advertisement captured the complete main-thread work: **1,056 execution samples / 30,771,701,952 weighted allocation bytes**, versus 1,395 / 42,070,991,008 for parent. Remaining ranks: kondo 343 samples / 7.881 GB; pull-many 79 / 3.163 GB; queries 68 / 2.738 GB; direct references 48 / 2.300 GB; parsed facts 46 / 2.868 GB. Those are samples, not exact total allocation. Final warm append on that candidate: **158.887 ms / 146,545,224 total bytes / 124,194,496 calling-thread bytes**. Both requested targets remain unmet.

One further EQUIVALENT fix caches the canonical-key set in `seon.schema/direct-references` by complete immutable forms through `seon.cache`, replacing a set rebuild proportional to all schemas for every inspected form. It still asks Malli for the actual scoped references. Calibration is 3,000 bytes per form, covering the retained forms key plus key set. The same 100-form probe returned the identical structural digest: **29.642 ms / 85,100,888 bytes / 723 armed calls → 8.060 ms / 5,385,144 bytes / 1,223 armed calls**. Calls increase because cache helpers are armed, while time/allocation decrease. This last change requires the final gate/cold result below.

Dependency pins and seams: Datahike e5453d967b749794944dbdfe64b751519983ba59, `pull_api.cljc:541,579`, `datom.cljc:106`; Malli 43c6dca8d2d8df3162b13725939954807be4a4ed, `core.cljc:2641,2864`, `registry.cljc:97`; clj-kondo 57252e07975710aa579b24f0d1b2b1e04195caa2. Each cache recomputes on changed content or eviction. Identity queries are proportional to selected IDs and installed identity attributes, and exports remain proportional to reached declarations.

Code landed as **9e0f1cba89077ec7164fd8f3a525625576382936**, through `bin/lane commit --test`: **19 tests / 154 assertions / 0 failures / 0 errors**, 7,048 ms; archived lint **0 errors / 1,458 warnings**, 4,634 ms. Gate total 60,785 ms, publication 8,174 ms, adoption 2,021 ms, 604 adopted identities. These gate clock rows have different changed identities and are not same-probe performance comparisons. Broad tiers remain root's work. The commit has **+65 net source / +50 net test lines**; its remaining lines are measurement programs, raw replies and this dated evidence.

The exact commit booted the same restored older store using `bin/seon start --head`: **22,781 ms ready / 33,621 ms operator total**, versus parent 23,171 / 32,188. No meaningful ten-second success is claimed; the small readiness difference is within the observed variation. Its full JFR main-thread recording has 1,144 execution samples and 31,307,712,792 weighted allocation bytes. Final warm appends were **193.080 ms / 224,324,040 bytes / 20,443 armed calls** and **169.116 ms / 157,030,728 bytes / 20,454 calls**. The first append was 721.855 ms / 1,806,201,608 bytes because the new stored file derivation keys had not populated this JVM's per-file schema-input cache. The second append's remaining allocation burst is attributed by the profile to `seon.db/declaration-content-key`; the third still allocates 157 MB. None meets 100 MB.

Three appends advanced the published source three times and called the transaction owner twice each. Filesystem allocated store space rose from **98,556 to 99,184 KiB (+628 KiB)**. This is filesystem allocation, not retained logical size. Before those appends, exact-commit status reported heap used **879,919,864**, heap live **535,986,936**, max **10,997,465,088** bytes; central cache weight **98,474,664 / 512,000,000** bytes; zero stale Vars, zero regressions and no open turns. Health was degraded only for the 3,084 ms catch-up adoption, existing task **5dbb7de36bdb**. Root, not this lane, owns default's restart to install the landed changes. No reset is requested.

The cold verification exposed a flaw in the final schema cache's key: comparing complete, equal-but-distinct forms populations moved about 2.3 GB into cache lookup. The result is only the set of names. A follow-up keys it by the vector of names (order differences can cause a harmless miss) and weighs the names vector plus set at 96 bytes per member. Malli still compiles in each caller's own registry. A probe that supplies 100 equal-but-distinct forms maps returns the identical result digest: **97.736 ms / 176,230,408 bytes → 38.924 ms / 102,008,008 bytes**, 1,223 armed calls both ways. The map-copy cost in that probe is included on both sides. The follow-up landed as **bb4446294798cd502b25bd99235a29e80a239d61**, through `bin/lane commit --test`: **7 tests / 68 assertions / 0 failures / 0 errors**, 3,698 ms; archived lint **0 errors / 1,458 warnings**, 4,530 ms. Its gate total is 57,036 ms, publication 7,124 ms, adoption 1,783 ms. The two code commits together add **66 net source / 50 net test lines**. The first is 36 files (+1,195/-108 including evidence), the second 11 files (+281/-2 including evidence).

Final exact-commit cold comparison on the restored older store: source current **20,123 ms**, ready **22,019 ms**, operator total **33,181 ms**. Parent: 20,994 / 23,171 / 32,188 ms. Main-thread JFR totals: **1,061 execution samples / 30,768,310,024 weighted allocation bytes**. CPU-ranked remaining owners: kondo **320 samples / 8.018 GB**; pull-many **71 / 3.907 GB**; queries **65 / 2.514 GB**; parsed-facts **50 / 2.788 GB**; dependency analysis **31 / 0.624 GB**. Direct references remain **24 / 1.609 GB**. Cache claim dropped out of the top allocation rows. This sampling evidence supports the producer change, not a precise retained-memory claim.

Final warm second/third appends: **205.425 ms / 224,923,280 total / 203,806,672 calling-thread bytes / 20,443 armed calls**; **171.123 ms / 156,284,328 total / 134,743,640 calling-thread bytes / 20,454 calls**. The second still derives declaration content; the later sample remains above 100 MB. Final status: heap used **620,569,064**, live **469,574,120**, max **10,997,465,088** bytes; cache weight **109,536,746 / 512,000,000**. No stale Vars, regressions or open turns. Catch-up adoption remains 3,190 ms, linked to task **5dbb7de36bdb**.

## Remaining work and cleanup

The warm cost still includes declaration-content acquisition, reached analyzer rows, task-fact checking and branch/writer/index work. The first publication after boot reads all newly stamped file schema inputs into the per-file cache; it does not yet reuse the just-produced analyzed rows for those new keys. That is a measured remaining producer opportunity, requiring equality between analyzed and canonical stored schema inputs before seeding them. Cold boot still reanalyzes all 475 inputs after analyzer identity changes. Changing that invalidation rule would need a separate correctness design; this slice preserves it. No skipped validation, stale derivation or green-test claim hides either target miss. All performance evidence stays linked to task **253286d36d52**.

`bin/seon --root scratchpad/publish-cost2/boot-root down` confirmed exit of exactly **pid 92950**, start instant **2026-10-07T16:11:49.404Z**. Earlier owned JVMs 69259, 82239, 87033 and 90187 were likewise stopped before restoring their store. The owned `root`, `boot-root` and `older-store` directories were removed without following symlinks; shared caches were retained. Large generated JSON was removed; raw JFR remains under `scratchpad/publish-cost2/`, and the committed streaming rank script regenerates compact evidence with pinned `ijson` through `uv run`. Default was never stopped or reset. Root's ordinary restart is still needed to install the code there; no reset is needed.
