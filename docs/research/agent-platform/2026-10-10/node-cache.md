---
type: research
status: restart-needed
date: 2026-10-10
lane: astra-node-cache
---

# Byte-bounded node cache (astra-node-cache, 2026-10-10)

Existing flow: Datahike connections share `datahike.index.interface/make-node-cache`; `CachedStorage` stores/restores immutable nodes through it. Konserve's generic API does not use `konserve.cache`. PSS separately retains restored children through soft references. The change uses Caffeine's weighted eviction with decoded-content estimates and PSS's existing weak-reference setting, including both deserialization handlers. Seon declares a 512 MB budget. The legacy node-count option remains available to Datahike consumers.

The principal requesting owner is `seon.sci.eval/function-digests`: one `get-else` lookup per function causes random EAVT node reads. Two attribute scans and an entity-id join preserve missing-digest handling and make node access proportional to the two attribute ranges instead of the whole entities. This replaces the per-function lookups; no extra derived cache.

Default: c5ca9fd93, PID 68306, start 2026-10-10T04:27:24.552Z. Initial health degraded: three existing errored requests and unowned user namespace. Fork pins: Datahike cf8157304ef3799afab54e3ca93e87421ee8aab4, Konserve e4334756413917305968333bf305e658c177765d, PSS 0.4.137. PSS `read-handlers` and `root-read-handler` already accept `:ref-type`; no PSS fork needed.

## Baseline

Full replies and exact forms are in node-cache-replies.edn. Agent astra-node-cache-probe, namespace node.cache.repro, work called forty times and one reaching test. Definition: 2239.5 MB step / 1147.5 ms CPU. Redefinitions: 1883.4 / 850.9, 1576.4 / 631.8, 1577.7 / 600.4. Warm test-step 706.6–752.4 ms; custody 0.4 ms; reads 378; five transactions. Final warm writer 245.74 MB / 116.98 ms CPU, commit 89.83 MB / 46.58 ms CPU. This is a stronger workload than forty calls with no reaching test.

Forty calls without a test, agent astra-node-cache-calls: first definition 471.0 MB step / 217.2 ms CPU, entrance 81.8 MB / 54.5 ms CPU, custody 113.2 ms, 207 reads. Warm redefinition 264.2 MB / 132.6 ms CPU, entrance 3.7 MB / 4.9 ms CPU, custody 0.3 ms, test-step 46.8 ms, 181 reads, one transaction; writer 14.28 MB / 12.61 ms CPU, commit 19.34 MB / 16.0 ms CPU. First requests were on an already warm shared store and do not reproduce the historical cold-store entrance figure.

`jcmd 68306 JFR.configure stackdepth=256` succeeded. Recording before.jfr covered the definition and three redefinitions with the reaching test; 8449 MB sampled allocation. Node restoration's first meaningful Seon callers: `seon.sci.eval/function-digests` 3697 MB; `seon.fn/declared-callers` 386 MB; `seon.db/declaration-content-key` 286 MB; `seon.test.runner/reach-attribute` 237 MB; `seon.sci.eval/installation-covers-program-change?` 139 MB; `seon.test/changed-definition-symbols` 77 MB. Samples are approximate and include all threads; 4819.7 MB restoration was on this agent's step thread. The default storage cache was full at 8192 nodes; default storage stats alone were 13973 disk reads / 404748 accesses, and exclude branch storage counters.

## Proof and landing

**RESTART NEEDED.** Landed caller fix `75bd6a23a`; Datahike fork `cb3bce514507ecc96d36b6957ad10f6e892f51cf`, pushed to seantempesta/datahike's seon/identity-join-first; Seon gitlink `f6ed22b16`. Default was not restarted. The dial and request regression are a tested working-tree change, also preserved in node-cache-dial.patch. They cannot pass the live publication gate until root restarts default with the new pin. A Seon-first attempt cannot reopen the old store because old Datahike treats the unknown byte-budget key as a mismatching creation setting; bump-first is necessary here.

The new dial is optional on stored configuration entities (an accretive addition), with 512000000 supplied in shipped defaults and at store opening. This lets adoption admit existing configuration facts before reconciliation. The byte count has its own name; the old node-count meaning is not reused. Datahike retains its legacy node-count option for other callers. The JVM's existing SoftRef policy flag is left in place; byte-cached trees themselves use weak references.

- Compiled baseline `a0e4c829aebc`: the new request proof is red, **70 node loads > 48**. An earlier independent baseline `1c291f608b16` had the same count. The budget is old and new paths through six indexes at up to four levels in the fixture. It is not a wall-clock assertion.
- Query-only trials `1305f8e1eae6` and `4b1cd31117a1` passed. A later trial `6955e2f3e787` was red again at 70 loads: the old cache/soft-reference retention makes the disk-read result unstable. The bound was kept. The query change therefore landed separately with its existing digest equality/commit-reuse proof; the byte-cache proof remains with the activation change.
- Direct live comparison on default, result `result/ee1f99db3fb7c`: the existing digest reader allocated **36,788,504 bytes / 15.98 ms**, the two attribute scans **6,858,008 bytes / 4.28 ms**, and the returned maps were equal. This was JVM mode with an explicit default connection and no source reload.
- Fork: node-cache, connector-release and cache tests, **16 tests / 59 assertions / zero failures or errors**. Tests cover weighted admission of differently sized nodes, branch sharing, file reopen and weak reference settings of both restored roots and cached nodes. Fork touched-file lint: zero errors and warnings.
- Complete candidate `464d203a3dcf02ad4f3784fc81e8e9e7b03aae69`, run `4d86b0baa755`: **4 tests / 19 assertions green**, including the request budget, configured dial, store configuration and digest identity/reuse. Its throwaway JVM booted from the candidate and adopted it onto a clone of the existing store template. The live rehearsal correctly refused the changed dependency fingerprint. Total 69.7 s, including 26.9 s dependency class fill, 27.4 s boot and 4.3 s tests; these are operator operations, not request timings.

### Matched runner measurements

These use the exact forty-call forms in `seon.node-cache-test/a-warm-redefinition-loads-only-changed-index-paths`, with `SEON_NODE_CACHE_EVIDENCE` naming the output file. The helper uses the value printer for the compact data. Baseline and candidate each ran in a fresh throwaway JVM seeded from the same c5ca9fd93 template. The harness agent's custody is already prepared; its fixture uses an overlay, so these commit numbers and entrance costs are **not** default's physical-file commit cost or the new-agent acquisition cost. Full data is in node-cache-replies.edn.

| Request part | Baseline | Byte-cache candidate |
|---|---:|---:|
| Warm step allocation | 225.6 MB | 164.9 MB |
| Warm step CPU | 148.2 ms | 110.8 ms |
| Warm entrance allocation / CPU | 3.9 MB / 4.9 ms | 3.6 MB / 4.2 ms |
| Warm writer allocation / CPU | 12.8974 MB / 14.53 ms | 10.7273 MB / 11.43 ms |
| Warm commit allocation / CPU | 0.2837 MB / 0.40 ms | 0.5898 MB / 0.65 ms |
| Warm custody | 0.7 ms | 0.2 ms |
| Warm test-step | 61.6 ms | 45.3 ms |
| Logical reads / node loads | 179 / 70 | 179 / 0 |
| First fixture request step allocation / CPU | 758.8 MB / 426.0 ms | 1557.1 MB / 684.7 ms |
| First fixture entrance allocation / custody | 60.7 MB / 1.6 ms | 31.8 MB / 1.9 ms |
| Node cache after requests | 5488 nodes, bytes unknown | 5468 nodes, estimated 141,587,146 bytes / 512,000,000 |

Warm allocation improves 26.9% and CPU 25.2%. **The first fixture request regresses** in this comparison, and the commit adds about 0.31 MB / 0.25 ms, including cache weighing. The cold increase also occurred with the query change alone (1601.4 MB in an earlier trial); its cause is not established. Do not call the cold request fixed. Actual retained heap was not measured by a heap dump: Caffeine's value is the decoded-content estimate, which counts shared payloads per node and excludes child references. A new agent on default after activation, the first-request regression, and heap calibration remain open measurements. The earlier 2.4 GB entrance cost was not reproduced even before this change on the already warm default store.

### Reproduction and remaining work

JFR: `jcmd 68306 JFR.configure stackdepth=256`, then `JFR.start name=astra-node-cache settings=profile`, execute the recorded forms, and `JFR.stop name=astra-node-cache filename=<owned-path>`. Convert with `jfr print --json --stack-depth 256 --events jdk.ObjectAllocationSample`, then run node-cache-jfr.py on that JSON. Allocation samples are estimates; the honest reply is the request's counter evidence.

Root must restart default from the new pin, then commit the six dial files and test/seon/node_cache_test.clj through bin/lane with the named tests above plus store reopen and branch sharing. The files are already present in the working tree; the patch is an archival copy, not a second edit to apply on top. Repeat the live default probes and profile the first-request increase before claiming the complete number fixed. This lane did not touch other lanes' files, restart default, enable a provider, or push Seon.

Verification of landed pin `f6ed22b16`, `bin/seon check --at HEAD`, run `db4f9647da3e`: **4 tests / 20 assertions / zero failures or errors**, 5035 ms test run. Named proofs: `seon.cluster.store-test/open-write-release-reopen-preserves-data`, `seon.cluster.store-test/create-settings-apply-only-to-fresh-stores`, `seon.cluster.store-test/a-branch-reads-the-index-nodes-its-store-already-holds`, and `seon.sci.branch-execution-test/a-loaded-programs-digests-are-read-once-per-commit`. Total 34.1 s, boot 21.3 s, readiness 14.4 s. Caller-fix landing run `82d82dd43b50`: one test, five assertions green; total 23.5 s. These long operator checks are the explicitly requested isolated proof, not ordinary request latency.

Final `clj-kondo --lint src script test --cache false`: **zero errors**, 1465 existing warnings, 6371 ms. No broad-tier result is claimed. There were no pre-existing reds in the named proofs; the new node-load proof was deliberately red on the old cache. The foreign boundary is default's loaded dependency fingerprint, not another lane's changes.

Sizes: landed Seon caller change +11/-11 source lines; fork +188/-26 across source, tests, dependency and documentation; pending activation six files +24/-30 plus the 45-line request test. The existing task `seon.sci.eval/acquisition-rebuilds-the-whole-program` owns the acquisition cost already observed before this lane; the first-fixture regression here still needs attribution. The node-cache task remains open until activation and live measurements pass.

All four owned disposable agent branches requested retirement successfully, with no diagnostics (106–168 ms requests). Their source/evidence needed for continuation is preserved here and in the patch. No owned test JVM or JFR recording remains active. The shared Git index is empty; activation files remain unstaged for root's continuation.
