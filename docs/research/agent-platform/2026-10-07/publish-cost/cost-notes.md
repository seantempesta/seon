# Publication cost

Publication captures changed files, analyzes them, checks derivation identity, reconciles rows and optionally adopts them. This lane removes whole-program reads from the derivation checks using selected index reads and the existing content cache where the measured immutable inputs justify it.

## Status and evidence

Default was faulted on entry: readiness 12,114 ms, 4 open fault signatures, 17 errored receipts, 9 failed tests. Health named publication task 9665c2ed25f0, adoption 5dbb7de36bdb, index tasks 12037fb1e077, 84fdc131a599 and 8b62adf64442; projection tasks d27179bab996/c4ece4f4eec9. No stop/reset/reload of default.

Disposable root: `scratchpad/publish-cost/root`. Fresh launch a9d1c4d33: source current 39,179 ms, ready 41,953 ms. It exited after its non-PTY launcher completed; cause not established, no heap dump or terminal error in its log. Relaunched with a held PTY on 13b850d30 (HEAD advanced during preparation), same store. All baseline performance below is that second JVM, on platform prepl threads. The source archive, never the shared checkout, receives probe edits to `src/my/background.clj`.

| Parent probe | wall ms | total thread bytes | calling platform thread bytes |
|---|---:|---:|---:|
| first comment append | 1,031.708 | 2,273,029,864 | 2,254,342,856 |
| second comment append | 766.935 | 1,827,779,952 | 1,779,436,208 |
| defn body: keyword invocation to `get` in poll | 565.097 | 1,172,371,168 | 1,014,686,168 |

First sample: 111,347 armed calls; capability check 314 ms, analyzer digest 187 ms, schema keys 158 ms, publish 236 ms, index 82 ms. Warm body sample: 80,611 armed calls; analyzer digest 181 ms, schema keys 81 ms, publication 210 ms, index 96 ms. Inclusive timings overlap.

Isolated held-value measurements after those publications:

| step | ms | platform bytes | armed calls | proportional to |
|---|---:|---:|---:|---|
| selected-analyzer-digest | 195.777 | 446,502,496 | 22,578 | every function and its calls, although only analyzer reach contributes |
| stored-derivation-schema-keys | 73.070 | 186,429,560 | 143 | every keyword use and every contract in program |
| clj-kondo adapter | 10.448 | 6,670,232 | 10 | selected file and cached required namespaces |
| unchanged capability validation | 0.694 | 556,272 | 128 | selected declarations; still submits an empty reverse query |

No tests launched (owner reserves central tests). Foreign boundary: pass-cost owns db, config, SCI kernel, turn and batch; printer-web owns web/CSS. Candidate archives exclude their working changes.

## Design checks

EQUIVALENT: analyzer identity needs only rows reached from the two analyzer roots, excluding the already-declared plumbing namespaces. Fetch those frontiers with `db/pull-many`, apply exactly the existing selected-file and loaded-byte rules, and hash the same sorted contributions. Remove the whole-program function query and the separate graph-map construction. Cost becomes the analyzer's reached definitions and edges, independent of unrelated program size. Dependency guarantee: Datahike pull-many preserves input alignment; unique symbol lookups seek AVET (`reference-code/datahike/src/datahike/pull_api.cljc`, `db/search.cljc`). No new runtime state.

EQUIVALENT: an empty reverse-reach frontier has no parents. Do not send it to the query planner. Keep all unchanged declaration comparisons and all nonempty capability checks.

Schema dependency optimization remains under investigation; no correctness shortcut that skips stale derivations is authorized.

## Same-probe changes

No new cache or stored fact. Analyzer identity now reads reached frontiers with `pull-many`, preserves the same authored/removed/loaded-file overlay, and hashes the same contributions. Its whole-program function query and graph-map construction disappear. Isolated armed result: **195.777 → 14.256 ms**, **446,502,496 → 28,118,400 bytes**, **22,578 → 2,477 armed calls**. Seven old/new comparisons on one held database all matched: unchanged, same selected declarations, changed unpublished bytes, removed unpublished file, changed loaded producer, deleted declarations, mismatched loaded bytes. This is a probe, not a test-run claim.

The capability validator avoids an empty reverse query. Armed unchanged comparison: **0.694 → 0.246 ms**, **556,272 → 292,040 bytes**, **128 → 46 calls**. The first uncached empty query cost 314 ms; subsequent parent calls merely warmed the dependency cache. The existing regression now also requires zero queries for unchanged declarations.

| full refresh without adoption | parent ms / total bytes | change ms / total bytes |
|---|---|---|
| comment append | 1,031.708 / 2,273,029,864; 766.935 / 1,827,779,952 | 392.286 / 569,986,360; 347.338 / 577,802,088; 393.890 / 577,533,960 |
| defn body (`poll` keyword invocation ↔ `get`) | 565.097 / 1,172,371,168 | 506.571 / 682,623,032; 385.998 / 596,922,120; 395.299 / 595,591,920 |

All ran on platform prepl threads in the same isolated JVM and store. Allocation uses total ThreadMXBean counters; evidence also contains exact calling-thread bytes. First changed comment: 58,335 armed calls versus first parent's 111,347. Two final publications advanced current-src twice, called transact! twice each, and grew filesystem allocated store space from 83,896 to 84,280 KiB (+384 KiB); this is not a retained logical-size/GC claim. JFR allocation samples over those calls total 1,132,780,528 weighted bytes, ~1,116 MB on their two prepl threads. Default stack depth retained no Seon owner for most deep library allocations: that recording cannot rank the remaining producers.

Source delta: +53/-72 (-19 net); tests +18. Measurement programs and exact shown values are beside this note. Prototype measurement is not a landed-installation claim.

## Remaining work; design check

**Not budget-complete.** Publication still allocates 570–683 MB, far above 10 MB. `stored-derivation-schema-keys` still reads every keyword use and contract (~74–105 ms / 185–186 MB). `derivation-keys` hashes the declaration world's forms once per publication (~22 ms); task reconciliation visits all task inputs (~47–61 ms inclusive). Scratch/publication/writer work is ~210–224 ms inclusive. The small-file kondo adapter is ~10–14 ms / 6.67 MB: it does not reanalyze the program for this probe. The measured refresh reused its projection (no build-projection call). Source inspection shows adoption reads the reported identities, not every program entity.

The frontier design held in the seven equivalence cases and removes unrelated reads without a new mechanism. It is still not sub-10-MB. A shortcut using a source-level analyzer seal was rejected before implementation: `e87f8a28f` explicitly retired that seal in favor of per-file derivation keys. Restoring it could hide stale files and would undo the owner's design. A commit-keyed schema cache misses every publication. A revision-keyed cache cannot be assumed valid: the actual materialized published value has no attribute revisions and carries the commit as its conservative revision. Datahike `query.cljc:3043` compares revisions only within one connection generation. Further schema-check reduction needs a reviewed content/invalidation seam; no check was skipped.

An adoption probe on the private store returned in 1,915.908 ms / 3,505,671,184 bytes, including catch-up adoption of many prior identities: **not** a single-file before/after proof. Adoption re-armed the prototype helper against its old stored one-argument contract, so the next probe refused its new four-argument call. This is my prototype-installation gap, not a demonstrated production defect. Re-arming with the candidate contract repaired it. The retry toggled back to already-published bytes and correctly returned unchanged (6.477 ms / 6,416,024 bytes). These observations do not prove candidate adoption. Exact publication/adoption on a live-store branch remains required before landing.

## Verification, findings and handback

Candidate: `git archive` plus only this lane's four source/test files, excluding foreign edits. Archive-wide `clj-kondo --lint src script test --cache false --parallel`: 0 errors, 1,456 warnings, 4,673 ms before the final assertion refinement; repeated result below.

Changed files' contracts compile: **0 findings**, 36.014 ms / 31,619,976 bytes / 1,795 armed calls. Baseline-aware checker additionally reports one `:uncontained-throw` on the capability validator's 11 existing throwing branches (203.348 ms / 281,192,736 bytes). This change adds no throw and preserves the required success-only contract. Existing task: `seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error`. Its recommendation to add catch/error unions conflicts with the current laws; this is not a contract-compilation failure.

Full archived src/script/test corpus: **six contract-compile findings**, all in `script/seon/dev/{docstring,markdown}.clj`, 645.122 ms / 736,265,040 bytes, 487 files. Existing tasks: `seon.contracts-compile-test/script-contract-vars-do-not-compile-against-packaged-projection`, and `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`. No whole-corpus green claim.

The shared guard briefly refused shell tools for `test/seon/config_test.clj:818:65`, unmatched `)` (pass-cost-owned). Independent work continued from the archive; that file was not edited. The source/compiler boundary remained the committed archive throughout.

Created through the default task writer: `seon.cluster/publication-derivation-checks-read-the-whole-program`, returned handle **253286d36d52**, result `result/e6bc93f7e9ac0`, 68 ms. Default task reads were 3 and 6 ms, without diagnostics. Existing `seon.cluster/adoption-readopts-what-the-jvm-loaded` covers catch-up adoption. Task ids were returned by the owner, not invented. No issue-index edit.

Root's exact focused list; no lane test runs were started:

1. `seon.cluster-test/analyzer-identity-reads-only-reached-declarations`
2. `seon.cluster-test/the-analyzer-digest-follows-the-analyzing-declarations-only`
3. `seon.cluster-test/publication-derives-only-the-changed-files-key`
4. `seon.cluster.program-change-test/a-launch-on-analyzer-bytes-with-unchanged-definitions-publishes-selected`
5. `seon.fn-test/capability-comparison-batches-prior-rows`
6. `seon.fn.publication-test/incremental-indexing-reconciles-only-selected-file-entities`
7. `seon.fn.publication-test/adoption-distinguishes-declared-attributes-from-malli-constructors`
8. `seon.adoption-entities-test/adoption-record-size-does-not-grow-with-reload-identities`

**No code commit.** The owner reserves test runs to root; `bin/lane commit --test` invokes `bin/seon check` and starts a test JVM. Remaining allocation and checker findings also fail the landing requirements. The diff is ready for inspection, not declared complete. Default needs no reset and was not restarted; installation after an accepted landing needs root's usual restart.

Final archive lint after the test assertion refinement: **0 errors**, 1,456 warnings, 4,233 ms.

Cleanup: `bin/seon --root scratchpad/publish-cost/root down` confirmed exit of exactly pid 56871, start instant 2026-10-07T13:53:17.381Z. The original process was 55233, 2026-10-07T13:49:08.532Z, already exited. Both are owned disposable processes. Default was untouched. Measurement forms assume a disposable cluster named `probe`; comment/body forms obtain the root from the JVM property. The equivalence form requires `seon.publish-cost.parent-root` to name the archived parent.

Evidence-only commit is permitted by bin/lane’s documentation path branch, which starts no test JVM. `candidate.patch` preserves the unlanded four-file change alongside the measurements. Source/test claims remain held pending root proof and disposition; this is not a code landing.
