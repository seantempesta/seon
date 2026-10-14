---
type: research
status: measured; candidate patch stopped at file ownership
date: 2026-10-14
---

# Boot source publication: measured duplicate analysis

Status: **ownership stop; no performance fix committed**. `perf-analyzer` holds `src/seon/fn.clj`, where retaining the first analysis belongs. The unapplied [patch](boot-source-current.patch) is a candidate, not a proven change. No default restart, reset, reload, provider call, or live branch mutation was performed. **RESTART NEEDED after the owner proves and commits the platform fix**, not for these research files.

`seon.cluster/full-source-refresh!` analyzes changed files, uses their entities to derive the loaded analyzer's identity, and compares per-file derivation keys. If additional keys differ, it discards the first analysis and recursively analyzes both sets. The proposed composition carries the existing file artifacts into that continuation and uses the existing artifact producer and whole-population validation. It removes repeated parsing, linting and entity derivation; it adds no cache or second analyzer.

## Finding and boundary

The actual stored source delta is **207 inputs**, not the 247 paths in the Git diff between launches. The first pass analyzes **88 Clojure files**, producing **3,837 entities**. It finds **471 additional files** with stale derivation keys. The second pass analyzes **559 files**, including the same 88 again. The replay counts **647 calls to `seon.fn.analyzer/parsed-facts`** and two clj-kondo invocations: 88 + 559, rather than 559.

The discarded first pass, independently measured on captured bytes and the same prior source commit, costs **6,321.01 ms and 7,165,687,680 allocated bytes**. This is repeated work, not an estimate based on a per-file average. The full publication replay costs **42,237.13 ms and 43,868,492,808 calling-thread allocated bytes**, with the installed instrumentation active. Those allocations are cumulative allocation, not retained heap.

The current analyzer's identity really changed. This investigation does **not** establish that the 471 stale derivations can be skipped. The proven avoidable work is deriving the first 88 files twice under the same analyzer, schema population and previous database.

Existing task: `seon.cluster/publication-tests-cost-35-seconds` already names this recursion and the requirement that changed files be analyzed once. It remains open. Related tasks: `seon.cluster/boot-publication-phases-are-invisible`, `seon.cluster/adoption-readopts-what-the-jvm-loaded`, and `seon.cluster/adoption-write-costs-sixteen-seconds`. No duplicate task or issue-index edit was made.

The assignment explicitly says to leave a patch and stop when the fix needs a held file. The proposed artifact continuation needs `seon.fn/analyzed-files`; `bin/lane status perf-analyzer` confirms that lane holds its source. I neither edited nor messaged that lane. Production source/test delta from this lane is zero. No red-to-green run, parent/change speedup, or production commit is claimed.

## Basis and reproduction

- Default PID: 88215, started 2026-10-14T06:09:10.548Z. Installed archive: `243683f1d8794948bdd6c07ca773bab72362b37f`.
- Stored evaluation timestamps confirm the replay at **06:34:57.490Z** (`691b3846a334`, 43,077 ms including setup) and the independent analysis at **06:38:36.969Z** (`14a5554a9c5a`, 7,452 ms including setup). Both precede the external 06:40:26.939Z restart.
- Prior source database: `6aceeff1-de8d-53e8-9327-daf993687522`, from `:seon.operator/captured-heads` in `data/clusters/default/logs/operator-start-20261014T131012.590.edn`; it still materializes and has 961 file entities.
- Current published source: `6acf1cac-3600-5692-a03d-956d1696ec52`. The current archive inventory has 968 inputs, including 579 Clojure texts; 559 fall within the indexer's Clojure roots used for derivation keys.
- Patch generated against shared HEAD `723bce2e1fc40044790b1b9087f4c449cd4bcb4f`. The installed code is older. The relevant cluster analysis recursion is present in both.
- All probes use MCP `eval_clj`, explicit `agent=root`, `cluster=default`, `mode=jvm`. Read-only probes pass an explicit database. The publication replay writes only an in-memory `seon.cluster.store/open-overlay!` and a private temporary resolver cache. MCP itself records its ordinary evaluation transaction, as designed.
- The overlay's `current-src` is replaced with the prior commit through `seon.cluster.registry/retire-branch!` and `seon.cluster.registry/branch!`. `seon.cluster.boot/changed-source-paths` then selects the real 207-input delta. `seon.cluster/full-source-refresh!` publishes onto `:astra-boot-source-replay` inside that overlay. The overlay and private cache are released in `finally`.
- The replay passes `seon.cluster/launched-source-digests`, matching boot before adoption, and binds the existing `seon.cluster/*source-progress!*` callback to collect timestamps. No Var is redefined or uncommitted source loaded.

Exact forms and MCP replies for prior-commit verification, the publication replay and the captured-input analysis are in [boot-source-current-probes.edn](boot-source-current-probes.edn). The replies include result handles, timing, allocation, profile rankings and slow findings. The full replay's large identity set was elided by the ordinary value printer; it is not needed for the timing conclusion.

Library seams inspected: `datahike.versioning/commit-as-db`, `datahike.versioning/release-materialized-db`, and Seon's `seon.cluster.store/open-overlay!`. The running Datahike pin is `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`; clj-kondo is `57252e07975710aa579b24f0d1b2b1e04195caa2`; Malli is `dd13ff81139159778edb0a67b491eccc0baa991d`. A commit database is an immutable input; an overlay reads the held backend and keeps its writes in memory. Malli's `malli.core/-instrument` supplies argument/result validation; no instrumentation exclusion is proposed.

## What the boot layer does and what its cost follows

The label `:seon.source/current` is the readiness milestone in `seon.cluster.boot/stand-boot-layers!`, not a function named `seon.source/current`. Its span begins after store acquisition and includes dependency-analysis readiness, changed-input detection and source publication. The recorded `at-ms` is cumulative since boot: source current at 30,666 minus store ready at 148 means **30,518 ms** in that span.

| Step, in execution order | Measured cost | Work is proportional to |
|---|---:|---|
| `seon.fn.analyzer/ensure-dependency-analysis!` | Not separately recorded in the original boot; not rerun against the shared resolver cache | Dependency classpath on an absent/incomplete cache; readiness check otherwise |
| `seon.cluster.source/discover-paths` | 49.32 ms, 122.9 MB allocation in a standalone probe | Whole exported inventory and declared roots: 12,200 `input-path?` calls for 968 accepted inputs |
| `seon.cluster.source/capture-paths` | 205.49 ms in an overlapping sample; 43 ms self in the quieter comparison below | Bytes of every discovered input plus recorded gitlink pins; captures Clojure text while hashing |
| `seon.cluster.boot/changed-source-paths`, complete unchanged check | **86.69 ms**, 195.4 MB allocation, result `[]` | Inventory discovery + all input bytes + stored file-digest entities + sorting their path union |
| `refresh-source!` bootstrap/store acquisition and declaration projection | Included in boot; replay starts at its `full-source-refresh!` and explicitly includes declaration projection | Configuration, held store acquisition and schema population; no separate original-boot timing exists |
| `full-source-refresh!` capture/compare/classify/test-input digest | Included in the replay's first interval below | Requested input bytes and paths, stored file digests; a whole inventory when no selection was supplied |
| First `seon.fn/analyze-entities` | **6,321.01 ms**, 7.17 GB allocation in the independent captured-input probe | 88 selected Clojure files, their text/AST/use counts, relevant prior declarations and capability reach |
| `seon.cluster/selected-analyzer-digest` after first analysis | **23.69 ms**, 23.0 MB | Reachable analyzer declarations; 11 `pull-many` rounds, overlaid with new entities and launch digests |
| Merge stored/authored derivation schema inputs | **22.46 ms**, 35.9 MB warm | File identities/keys across the program, missing cached file schemas, plus the newly analyzed rows |
| `seon.cluster/derivation-keys` | **15.52 ms**, 9.0 MB warm | Every derivation input's key; schema closure and hashing on content misses |
| Compare stored derivation keys; recurse if stale | 471 extra files; first interval **10,480 ms**, second interval **18,467 ms** in replay | First pass 88 files, second pass all 559: repeats the selected-file work and some capture/key work |
| `seon.cluster.source/publish!` and `populate-source!` | **13,290 ms** from publication-start to complete | Schema changes, selected entities, validation/reconciliation, task/config initialization and source identity |

The 6.32-second direct analysis and the 10.48-second first replay interval are different measurements. The latter includes preparation, projection/key work and first-use costs in that run. They must not be subtracted as if measured in one window. Similarly, the standalone 205 ms capture sample overlapped another lane's test work: its JVM-wide rankings include that unrelated activity. The 86.69 ms complete unchanged check was a separate quieter window.

An earlier current-database probe, before these derivations were warm, measured stored schema-key extraction at 162.81 ms / 278.0 MB, analyzer identity at 16.55 ms / 22.5 MB and derivation keys at 242.36 ms / 225.7 MB. These are whole-program costs, but they are far smaller than analysis and reconciliation in this replay.

## Publication phase timestamps

Offsets below are from replay setup, not JVM start. `seon.profile/measure` begins approximately 710 ms after setup starts; setup includes the private cache copy and overlay branch selection.

| Callback | Offset ms | Interval interpretation |
|---|---:|---|
| Re-deriving 471 inputs | 11,190 | Initial preparation, first analysis and derivation-key comparison |
| Findings; publication started, 678 input paths | 29,658 | Another 18,467 ms after discarding the first pass; 678 includes resources/deletions, not 678 Clojure files |
| Schema population started | 29,876 | 218 ms publication setup |
| Schema population complete, 854 datoms dropped | 30,235 | 360 ms schema population |
| Program entities started | 30,236 | |
| Contract projection started/complete | 33,362 | 3,127 ms prior index preparation; projection itself negligible at callback resolution |
| Contract entities complete, 4,777 | 34,291 | 929 ms contract-fact processing |
| Reconciliation transaction starts | 34,738 | 447 ms additional preparation |
| Program entities complete | 41,922 | 7,184 ms reconciliation and return |
| Initialization entities | 42,417 | 495 ms after program completion |
| Publication source identity | 42,939 | 522 ms initialization |
| Publication branch head / complete | 42,946 / 42,947 | Final source recording and head publication |

Largest `seon.profile/measure` self-time entries: `seon.fn.analyzer/invoke-kondo` 10,872 ms (2 calls); `seon.db/transact-call` 7,962 ms (6 calls, includes writer waiting); `seon.db/pull-logical` 3,250 ms (1,829 calls); `seon.fn.analyzer/parsed-facts` 2,551 ms (647 calls); `seon.cache/settle!` 2,322 ms; `seon.fn.analyzer/analyze` 2,092 ms excluding profiled callees; `seon.schema/contract-references` 1,104 ms. The two measured analysis calls are publication passes, not 207 independent whole-program analyses.

## What is reused, what is repeated, and the proposed fix

The published database already stores file byte digests, derivation digests, program entities and analyzed-source digests. Discovery hashes file bytes before deciding what changed. Unchanged file/schema derivations can reuse `seon.cache` entries keyed by content. The writer reconciles derived entities against stored entities rather than unconditionally replacing everything.

However, the first analysis only returns flattened program rows to `full-source-refresh!`. When its subsequent key comparison widens the input set, recursion loses the file artifacts and starts their analysis again. This does not depend on another boot having retained an in-process cache: it duplicates work within one publication.

The patch makes the existing `seon.fn/analyzed-files` producer public and allows its request to carry file artifacts from this same publication. It excludes their paths from the new analysis, retains their resolver namespace entries, seeds the additional analysis with their first-party symbols, and validates the combined artifact collection with the existing capability and seed checks. `full-source-refresh!` retains those artifacts across its existing one continuation. `analyze-entities` and `build-manifest` retain their existing results.

This is deliberately a local continuation, not cross-boot artifact caching. The precondition is the same captured input bytes, previous database, declaration projection and loaded analyzer. Broader cache reuse needs those dependencies represented in its key and is a separate change. The patch has not yet proved that all batch-dependent lint/reference facts preserve equality; the owner must check that, especially removed definitions, new first-party symbols and implicit dependent paths.

The patch includes a proposed proof through the existing `seon.fn/analyze-entities` entrance: analyze alpha, then extend with beta that calls alpha; compare the complete rows with a joint analysis and assert only beta is parsed in the continuation. It remains tagged to the existing task. This narrow proof is necessary but not sufficient: the actual boot-layer comparison and integration checks below are required before any performance commit.

## Required proof before applying/committing the candidate

1. Run `seon.boot-source-cost-test/publication-analysis-retains-its-first-file-results` red on the unchanged producer, recording the run id and failing work-count assertion. No red run has been done in this lane because implementation stopped at the held file.
2. Publish/reload the patched schema and producer in the isolated candidate path. Run that proof green, `seon.publication-cost-test/publication-cost-follows-changed-inputs`, the reaching tests in `seon.fn-test`, `seon.fn.publication-test`, `seon.fn.publication-cache-test`, and the cluster publication/derivation-key tests selected by the actual reach. Some existing publication tests still redefine Vars; no result or green status for them is claimed here. Select vars individually where the owner's baseline is red.
3. Repeat the saved 207-input boot-layer replay on parent and candidate with the same prior commit and archive bytes. Compare resulting program identities/digests, schema/task facts and findings as data. Require a measured wall-time gain under `seon.profile/measure`; 647 → 559 parsed-file calls is supporting evidence only. Do not substitute a warm same-commit no-op.
4. Measure in a quiet window and report allocation and instrumentation state. Do not claim that subtracting 6.32 seconds from this replay is a proven gain.
5. Commit through the owning lane's gate, then root batches the necessary restart. This lane's documentation commit requires no restart.

## Other work and owners

| Remaining cost/finding | Owner and disposition |
|---|---|
| 471 stale files after a changed analyzer identity | `seon.cluster` derivation selection. Existing task; do not narrow analyzer reach without proving what rows depend on |
| Repeated AST parsing, var-quote scans and duplicate clean-analysis checks | `perf-analyzer`, `seon.fn.analyzer` / `seon.fn`; already assigned work |
| Contract facts and schema population/cache work | `perf-request`, `seon.program` / `seon.schema`; already assigned work |
| Reconciliation/write validation and database reads | `db-caches` for `seon.db`; `perf-analyzer` for `seon.fn/reconcile-tx-in` |
| Whole-inventory discovery and repeated captures | `lane-lifecycle-branch` holds `seon.cluster.source`; measured below one second here, so not the first fix |
| Boot phase reporting before instrumentation | Existing `seon.cluster/boot-publication-phases-are-invisible`; current callbacks can measure a replay but are not persisted by boot |

The Gemini research is useful history, not current executable specification. Its schema-change-wide `selected` example is absent from the installed `full-source-refresh!`; this replay widens through derivation keys. Its example call to `rehearse-source!` has wrong argument meanings: the installed contract takes root, directory and cluster-name, not a database as its third argument. Its claim that per-file derivation keys fully cover cross-file inputs contradicts `seon.cluster/derivation-keys`, which explicitly delegates those inputs to dependent/caller selection.

## Verification and limits

- `bin/seon status` and MCP `runtime_status` both answered before probing. Default was already `:faulted`, with one open fault, 14 errored receipts and three malformed contracts; these are not introduced or resolved by this lane.
- Full `clj-kondo --lint src script test --cache false`: **0 errors, 1,667 warnings**, 8,101 ms. Exit 2 is the warning status. No warning cleanup or other lane's file was edited.
- The proposed test file alone parses/lints with **0 errors, 0 warnings**. `git apply --check` accepts the patch against the current working files. These are static checks, not runtime proof of the patch.
- No before/after candidate execution, no red-to-green test id, no regression-suite result, no production change and no measured speedup. The precise foreign boundary is the file claim on `src/seon/fn.clj`, not a claim that another lane's code is broken.
- The original boot ran before host instrumentation; the replay runs instrumented, warm, on a shared JVM and an in-memory overlay. Original source span 30,518 ms and replay 42,237 ms are not an A/B comparison. The replay's JVM-wide CPU/allocation include unrelated work; calling-thread allocation excludes worker-thread allocation. Reported replay GC: 69 collections / 598 ms.
- The source boot span and publication are over ten seconds; this assignment explicitly authorizes measuring them and the existing publication-cost task records the defect. The MCP slow findings are preserved in the evidence.
- All owned overlays and private resolver caches were released by the probes' `finally` clauses. During final verification default had changed externally to PID **17529**, source commit **6acf23f0-6ec8-5a86-bf38-85bf9a65f9bb**. This lane issued no restart command. The replay branch is absent from the live store, but an unchanged-head check against the measured JVM is consequently unavailable; no such check is claimed. All performance figures above belong to PID 88215, before this boundary.
- The first cleanup-verification form failed to compile because I omitted `java.lang.` from `ProcessHandle`; the corrected form passed. Both replies are retained. This was a probe-author error, not a publication failure.
