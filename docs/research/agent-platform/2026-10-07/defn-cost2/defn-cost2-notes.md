# defn-cost2: definition-pass allocation

The SCI definition is evaluated, analyzed and admitted, then the writer derives program facts and schema projections before settlement. This lane profiles the existing `seon.pass-probe` entrance and removes whole-program derivations at their producer.

## Initial health and boundaries

Parent: `957199da3` (program after `84a3c5e4d`, only dependency removal and documentation later). Default runs `a4d438dc6`, pid 89251, start 2026-10-07T22:55:10.633Z; both operator and MCP status reported faulted. Existing custody task `3d2fb1b41dc8`, absent-branch fault, transaction max 5291 ms, schema call max 5302 ms, arm 1115 ms. No prototypes or profiling run there.

Throwaway root: `tmp/defn-cost2/root`, initially pid 95601, start 2026-10-07T23:10:44.130Z. First start was still publishing when I prematurely attempted a second start and init: store-lock refusal and competing-publication refusal were operator mistakes, not independent defects. Initial probe before readiness threw in `agent-world`; repeated after readiness. Fresh boot 66101 ms (outside pass windows). Preserved measurements and final reproducible probe accompany the landing.

Foreign boundary: s3-01 owns `src/seon/turn.clj` and `src/seon/turn/batch.clj`; s1-01-02 owns agent-loop deletion. Candidate checks must use HEAD plus this lane's files. The REPL skill contains stale descriptions (repair, per-turn forks, missing-contract refusal); current `agent-loop.md` and source govern, not those claims.

## First measured profile

`(seon.pass-probe/run-mcp "probe" "sci" 1 #{1} true)`: ordinary 61.79 ms / 30.33 MB / 10132 calls; definition 399.70 ms / 785.99 MB / 75995 calls; after-definition 82.45 ms / 84.04 MB / 95243 calls. Definition thread bytes: writer 632.72 MB, agent 151.56 MB. One durable commit. Five later definition passes, JFR enabled: 217–340 ms / 272–369 MB. Cold and warm are distinct, not interchangeable proof.

JFR `jdk.ObjectAllocationSample`, throttle 20000/s, recording and JSON export depth 256: 8544 samples, 1496.2 MB weighted, 5420 truncated stacks. Top exclusive Seon owners across five passes: vector-query-call 183.1 MB, declaration-content-key 130.0 MB, storage-q-in 95.9 MB, compiled-storage 73.9 MB, arity-facts 65.2 MB. A deeper rerun is needed for complete phase attribution; truncated stacks are not evidence of absence.

## Producer fixes

EQUIVALENT: `call-preparation/contract-arities` currently derives every function's argument descriptors before either consumer selects a supplier or a target. Replace it with a per-symbol derivation held by the same projection cache. Snapshot acquisition asks only for the suppliers its entity query returned; arming asks only for its target. No new cache, state or fallback. Cost changes from all program contracts to the requested contracts' arities and arguments. The existing incoherent-supplier, target-plan, redefinition, sibling-world and armed-call tests prove the same data and custody semantics.

Dependency: pinned Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, `registry.cljc:24–28` retains schemas by key; `core.cljc` `-function-schema-arities`, `-function-info`, `children` and `deref-all` supply the existing descriptors. Inputs: compiled registry and symbol; recomputation: once per requested symbol per projection. First-party consumers: `derive-snapshot` and `planned-arities` only. Smallest alternative is deletion of the unrequested derivation, rather than a cross-projection cache.

The depth-1024 rerun (export depth at least 256) has 8791 samples, no truncation, deepest stack 811. It exposes a cold authored-shapes build (446 MB inclusive) and writer arity queries (479 MB inclusive across five passes). These are distinct from SCI evaluation. Warm pass costs remain about 280–318 MB. The first post-restart definition also pays cold authored-shape construction and query plans: 1518 MB / 1576 ms under JFR.

Second producer fix, EQUIVALENT: the arity gate already holds the exact changed caller/callee sets, but Datalog joins can scan all call edges/arity rows even when a family or a core callee has no stored entity. Replace these bounded reads with the existing `db/pull-many` entrance: one batch of caller identities, one batch of target identities; vector decoding remains owned by the existing reader. Preserve bounds, comparison coverage, missing minimum behavior and caller-family union. No whole-program query and no new reader/cache. Regression is the existing changed-callers table plus empty/absent callees. Library seam: Datahike `pull-many` resolves each supplied identity and returns aligned entity values; Seon's existing bridge decodes vectors.

Third producer fix, EQUIVALENT: `program/authored-shapes` needs only the compiled authored declarations, but asks `build-projection` to admit a fresh whole program. Use the existing content-cached `schema/declaration-projection` of exactly the same packaged forms. This keeps authored-resource authority and deletion/redefinition behavior, removes duplicate admission and shares the already-derived declaration generation. Existing shape equality and authored-population regression prove its data.

Candidate check `d74cf4a6d2525896cd9218b3d7d49f3d5fc6a056`, run `9f9ec2286737`: 42 executed, 210 assertions passed, zero failed assertions, two errors, 13320 ms. Own defect: supplier-map construction retained an absent supplier as nil; changed to `keep`, preserving the old absence behavior and the coherent-supplier input contract. The authored-population test's synthetic attribute lacks a registered schema; its parent proof is recorded below.

Fourth producer fix, EQUIVALENT: `schema.datahike/compiled-storage` folds the same immutable Malli root on every storage read, including across successive projections retaining that root. Use Malli's existing `-cached` (`reference-code/malli/src/malli/core.cljc:353–361`, pin above), keyed by this producer. Its input and lifetime are the compiled schema, its miss folds that schema's nodes, and a hit reads one map. No independent Seon cache or invalidation is added. The existing retained-root, composition, changed-property and writer tests cover it.

The `entity-entries` caching edit was refused before writing by the edit hook's `uncontained-throw` rule. Existing task `seon.contracts-compile-test/edit-hook-refuses-a-lawful-throw-of-a-registered-error` owns that mismatch with current success-only throwing contracts. This lane does not bypass the hook; internal.cljc is unchanged. Only `compiled-storage` uses Malli's schema cache.

Prototype limitation caught and corrected: `instrument/apply!` compiles stored projection contracts, so rearming a function whose signature changed against the old projection is not valid proof. A prototype definition disappeared on the next pass under that mismatched wrapper. The rerun supplied a projection updated through `projection-with-function-contract` for each edited Var; then all definitions installed and the next SCI pass answered 42. Final proof remains candidate publication/adoption and boot, not this host replacement.

Fifth producer fix, EQUIVALENT: `declaration-content-key` sorted each native DB's already-ordered AEVT ranges. Pinned Datahike `e5453d967b749794944dbdfe64b751519983ba59`, `db.cljc` `contextual-datoms` delegates native ranges to the ordered index; temporal wrappers retain the old explicit sort. On the throwaway root's real declarations, old and new keys are equal. Five unarmed same-thread measurements: old 2.48–2.97 ms / 6.97–7.03 MB per key; direct range 1.10–2.85 ms / 2.34–2.35 MB. Removing the comparator pass saves about 4.65 MB each time; it does not pretend the remaining whole-program range scan is incremental. Existing native/speculative/as-of content-equality tests cover the shared key.

Parent proof of the shape-fixture red: `330ce6ecf16c`, 1 executed, 2 assertions passed, error 1, 2310 ms; same absent `:seon.program-test/declared-after` schema. The test uses `with-redefs` and supplies an inline entry without its attribute declaration. Listed as a pre-existing red and excluded by var from this lane's acceptance set; other shape and definition tests remain required.

## Final paired evidence and limits

**The <30 MB target is NOT MET.** The final A/B parent is `f50ef6059`, installed in the same isolated committed-archive JVM. The original cold observation used `957199da3`. Do not compare that cold 786 MB directly to the warm change as an identical-workload saving.

The final installer loads all changed definitions before arming any of them, then supplies the authored, quoted contract forms in each owner's namespace to `projection-with-function-contract`. Both final installation envelopes have `isError false`. Earlier prototype samples with a rearming error are discarded: evaluating metadata resolved predicate symbols into function objects, and rearming before all callers changed briefly mixed old/new arities. Final stage evidence includes all changed armed owners, not just their children. Candidate adoption/boot is still the installation proof; host replacement supplies paired timing only.

Three repetitions of the identical canonical `seon.pass-probe/run-mcp` ordinary/definition/after-definition rows, fresh agent per repetition. Medians, decimal MB, platform-thread allocation counters (writer and agent included). All final rows completed with no error and one own durable commit. Full per-thread maps are in the adjacent EDN.

| row | parent ms | change ms | parent MB | change MB | parent armed calls | change armed calls |
|---|---:|---:|---:|---:|---:|---:|
| ordinary | 60.42 | 50.25 | 40.88 | 42.63 | 8,190 | 8,596 |
| defn | 302.41 | 206.54 | 254.81 | 194.06 | 74,834 | 64,511 |
| after-defn | 130.94 | 91.86 | 91.43 | 74.15 | 95,262 | 95,261 |

The definition saves **60.75 MB (23.8%)**, **95.87 ms** and **10,323 armed calls**. Definition ranges: parent 271.45–378.71 ms / 236.31–281.06 MB; change 200.81–254.87 ms / 181.77–223.72 MB. The ordinary row remains within the 20%/50 ms regression threshold. Timing is susceptible to other work on this shared machine; allocation is the more stable signal. No other lane was controlled or interrupted.

Five consecutive definitions under deep JFR, parent: median 577.73 ms / 293.45 MB / 149,672 armed calls. Five consecutive definitions under deep JFR, change: median 228.55 ms / 218.40 MB / 137,268 armed calls. These rows also pay acquisition after the preceding definition. On change SCI evaluate runs once at 1–3 ms, kondo once at 6–8 ms, declaration-content-key four times at 7–8 ms total, and arity-facts four times rather than the parent's 2,922–2,926. The JFR parent was slower than the non-recording parent; it is not a clean estimate of wall-time improvement.

JFR used 20,000 allocation samples/s and stack depth 1024 after depth 256 truncated 5,420/8,544 samples. Final parent: 39,275 samples, 1,474.49 MB weighted, maximum depth 804. Change: 4,872 samples, 1,118.57 MB, maximum depth 772. Both have **zero truncated stacks**. Weighted allocation is an estimate, includes measurement overhead, and is not the exact platform-thread counter. The classifier's explicit precedence is in `Rank.java`; bins are disjoint, inclusive ranks are also retained.

| ranked owner | parent sampled MB/pass | change sampled MB/pass | proportional to |
|---|---:|---:|---|
| native Datahike/konserve work | 67.31 | 62.23 | written index paths, serialized nodes and queried relations; not demonstrated form-only |
| projection acquisition/content key | 54.16 | 41.64 | whole program declarations and function contracts |
| fault-window/error observation | 44.91 | 32.26 | error schema/reference population even with no new error |
| program/fact writer validation | 27.38 | 30.00 | written owning values plus declaration-derived tables |
| rendered Var test-reach selection | 16.45 | 16.77 | declared-edge/identity population plus reached closure |
| other Seon/runtime | 10.51 | 13.68 | mixed, not further attributed |
| SCI context acquisition | 8.98 | 7.30 | whole program base and agent context |
| settlement preparation | 7.14 | 6.00 | pass observations and run bookkeeping |
| kondo/runtime analysis | 5.72 | 5.28 | form plus namespace resolver input |
| contract installation/descriptors | 23.54 | 2.96 | requested contracts after fix; formerly all contracts |
| probe/report overhead | 2.87 | 2.71 | measured pass |
| recording preparation | 1.09 | 1.96 | pass observations |
| final call-arity gate | 24.33 | 0.66 | changed callers/callees and their edges after fix |
| run reads excluding scopes above | 0.22 | 0.28 | run record |
| print/render excluding test-reach | 0.21 | 0.00 | shown value |
| SCI evaluation body | 0.06 | 0.00 | form |

An empty sample bin is not proof of zero allocation. The native bin includes queries as well as storage. Its largest changed-run dependency stacks across five passes: LZ4 56.23 MB, CachedStorage 48.68 MB, persistent-set implementation 43.69 MB. The full dependency breakdown is in the rank output; do not call the whole bin serialization.

Definition/reach digest construction overlaps writer work: write-digest-error 10.85 MB inclusive over five passes (2.17 MB/pass), builder-digest 7.64 MB, stored-call-reach 4.08 MB. These are not added to the table. Rendered test-reach is a read, not scheduling or execution. No generated-input auto-check or test scheduling appeared in the complete stacks or requested armed stage counts for this simple defn; current turn.batch invokes accretion for schema definitions. Oversized generated inputs are not the demonstrated cause here.

## Fix-specific evidence

Same final JFR workload, inclusive producer totals per pass; these overlap and must not be summed:

| producer | parent MB/pass | change MB/pass | mechanism |
|---|---:|---:|---|
| report-arity-comparison | 24.33 | 0.66 | two identity batches replace Datalog scans |
| compiled-storage | 16.03 | 0.05 | Malli root retains its storage facts |
| declaration-content-key | 23.26 | 9.15 | native AEVT is already ordered; temporal views still sort |
| contract-install/descriptors | 23.54 | 2.96 | arity-facts 2,922–2,926 → 4 |

The original cold authored-shapes trace was 446 MB inclusive once. The separately repeated microprobe clearing **only authored-shapes**, keeping schema derivations warm, gives parent median **3.70 ms / 6.07 MB**, change **0.255 ms / 0.037 MB**; both return six shapes, equal in the earlier real-population comparison. This conservative probe does not claim every pass saves 446 MB. `shapes.clj` and results preserve it. `key.clj`, run with parent installed, preserves the key-equality/allocation probe described above.

Post-GC isolated heap: **338.89 MB used / 671.09 MB committed**. This is a retained-heap snapshot, not a before/after retention proof. Allocation measures churn. No default heap was manipulated.

## Remaining work and S4–S6

S5 explicitly deletes the fault-window and run-observation rereads: **32.26 MB/pass** in the measured error-window scope. current-run is 32.50 MB/pass inclusive and substantially overlaps error/projection work; it is not another additive saving. S6 removes run machinery, but still needs settlement: the remaining 6.00 MB settlement preparation is an upper bound on potential removal, not a promise. S4's prompt refresh is not exercised by this MCP workload: claim no measured S4 saving.

Even deleting the measured error-window and all settlement preparation leaves far more than 30 MB. S4–S6 do not delete whole-program projection acquisition or rendered test-reach. The existing contracts-out-of-projection design is a separate coordinated cut, not an installed API. Existing tasks own the residual classes:

- `seon.schema/a-function-contract-changes-the-schema-projection` and `seon.schema/a-declaration-write-reloads-the-whole-projection` (projection ~41.64 MB/pass).
- `seon.sci.eval/acquisition-rebuilds-the-whole-program` (context ~7.30 MB/pass).
- `seon.db/the-query-wrapper-costs-36x-datahike` and `seon.db/every-commit-validates-its-write-report-three-ways` (reads and validation).
- `seon.program/a-defn-scans-every-projection-form-per-call` (authored shapes reduced here).

Native index/serialization/query work (~62.23 MB/pass) remains a separate performance investigation; this profile does not justify calling it inevitable. This lane lands bounded producer fixes with the allocation target unmet, not proof that 30 MB is impossible. Foreign turn.clj/turn/batch.clj ownership was s3-01 throughout implementation; it landed `2bf3e10db` before this lane's final commit gate. No foreign edits enter this slice.

## Verification and reproduction

Committed **`97faccb88`** through `bin/lane commit`. Final candidate `bb09c7bbfc679226d2bd0f71630c3c3870d8933b`, stored run `c3df867455c3`: **54 tests / 292 assertions / zero failures / zero errors**, 42,527 ms. Candidate lint: **0 errors / 1,455 warnings**, 17,768 ms. Publication 21,912 ms, adoption 4,510 ms, 898 identities; boot 63,349 ms (ready 57,638 ms), slot wait 73,268 ms, total check 217,369 ms. These slow gate operations remain visible; green correctness does not make them sub-second. `acceptance.txt` preserves the exact summary. This gate includes the concurrently landed S3 conversion and every reaching var selected above except the demonstrated parent fixture red.

The isolated root was stopped through `bin/seon --root tmp/defn-cost2/root down`, verifying pid 97997 / start 2026-10-07T23:15:11.237Z and actual process exit. Its store and redundant multi-GB JSON export were removed; raw JFR retained. No owned JVM or shell remains. Shared default pid 89251 was never restarted or reset. Remaining cross-owner files are now held by s1-03 (`fn.clj`) and s1-04 (`sci/eval.clj`); none was edited or contacted by this lane.

First full 54-test commit attempt: candidate `ae7c81c7fb347e163596c05e55fd62caa77a51fd`, run `ce320fb7e269`, 292 assertions passed, zero errors, one duration failure: call-preparation-test/a-redefined-contract-replaces-its-held-plan took 7,116.60 ms against 5,000 ms. Total tests 28,448 ms; slot wait 173,214 ms; boot 68,994 ms; total check 299,879 ms. The earlier candidate passed that test; this result is not a pass and the same full gate is repeated without changing the bound.

Earlier candidate `88e7a9a7695269d093742e8fbf101da975d27d85`, stored run `9c00dc9d5c67`: **47 tests, 245 assertions, zero failures/errors**, 14,772 ms. Publication 4,401 ms, adoption 1,854 ms, 535 identities adopted on live-store branches. Candidate boot ready 37,693 ms, full boot 44,335 ms, full check 72,118 ms. These are over-budget gate operations, explicitly authorized by this assignment's throwaway boot/check requirement, not ordinary pass timings. Shared default never restarted/reset or ran prototypes.

Final working-tree lint: `clj-kondo --lint src script test --cache false --parallel`, **0 errors / 1,455 warnings**, 5,146 ms. The lane commit repeats candidate lint and named tests. Source +44/−74 (net −30); tests +6/−5 (net +1); total net −29. Measurement scripts/evidence are separate.

`tests.txt` names the complete acceptance selection. `reaching.edn` is the live owner's selection for all seven changed producers, plus explicit content/native/as-of regressions. The newly discovered reaching vars join the commit gate. Selected task tags already have authored task EDN. The one excluded relevant pre-existing red is `seon.program-test/a-declaration-added-after-the-first-call-is-a-cache-miss`, reproduced on parent above.

Scripts target only `tmp/defn-cost2/root/data/clusters/default/prepl.edn`: that cluster name belongs to the isolated root. Start a committed archive there with `JAVA_TOOL_OPTIONS=-XX:FlightRecorderOptions:stackdepth=1024 bin/seon --root tmp/defn-cost2/root start`, keeping its shell alive. Copy scripts to `tmp/defn-cost2/`. Run `install.py f50ef6059 tmp/defn-cost2/install-parent.clj` / `install.py change tmp/defn-cost2/install-final.clj`, execute through `bb .../repl.clj <file>`, and require an error-free installation envelope before probing. `paired.clj` supplies the non-recording rows; `final-parent.clj` / `final-change.clj` record five definitions. `java Rank.java <recording>` reads JFR directly without multi-GB JSON export. Candidate publication/adoption/boot proves installation; host replacement only measures the paired slice.

Raw JFR remains under tmp/defn-cost2; adjacent committed EDN/ranks/scripts preserve the findings and reproduction. The isolated root is retired; shared default remains for root's coordinated landing.
