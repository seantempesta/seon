---
type: research
status: incomplete; no accepted performance change
---

# Cost-gb evidence, 2026-10-12

## Design and scope

`seon.test.runner/reach-digests` reads a closure through `seon.db/pull`, then hashes its strongly connected components. Replace one pull per name with `seon.db/pull-many` for each frontier, preserving the same graph and digest. Work remains proportional to the reached declarations, edges and schemas. No additional cache or dependency change. The smaller alternative is to reuse the existing cached graph; that does not address the first computation after a changed declaration.

Dependency seam: Datahike c9e57c6fb65365d5ce92b59854fb02ea59054fe7, `datahike.pull-api/pull-many`, eager input-aligned results including nil for missing references. Malli dd13ff81139159778edb0a67b491eccc0baa991d, `malli.core/validator` and `malli.core/-instrument`: existing named contracts remain instrumented.

## Default baseline

Default archive c792b6aaf; no open evaluations at first observation. Existing health reports SCI acquisition failures and unresolved `profile/measure`; existing tasks are attached to those occurrences. Branch-repl owns `seon.sci.eval` and its tests.

Item 1: MCP SCI as fresh `cost-gb-1`, `(seon.context/current-state) (in-ns 'probe.gb) (defn twice [x] (* 2 x))`: request 439.3 ms, test step 67.9 ms, step allocation 266.2 MB. Second `(defn twice [x] (+ x x))`: request 180.4 ms, test step 37.1 ms, step allocation 153.3 MB. A second fresh agent `cost-gb-2`, measured through `seon.profile/measure` around `seon.mcp/eval-clj`: 361.93 ms / 386.68 MB JVM allocation window. Its second definition: 151.52 ms / 220.31 MB; internal request 147.5 ms, test step 39.8 ms. Neither request called `seon.test.runner/reach-digests`, `reached-graph` or `tests-reaching`. The historical 0.6–1.9 GB test-step cost did not reproduce. No performance change or gain is claimed for item 1.

Item 2: JVM mode as root, explicit default database, `seon.profile/measure` around `(seon.test.runner/reach-digests database ['seon.request-cost-test/a-request-counts-its-own-calls-reads-and-transactions])`: 805.17 ms, 1,762,131,416 calling-thread allocation bytes, JVM window 1,763.09 MB. Supporting counts: 7,711 pulls, 545 source parses for behavior digests, 2,418 behavior-digest calls. Existing behavior cache was partly populated; this is first graph computation, not a claim of an empty cache.

Red proof: `seon.test.reach-test/a-changed-programs-first-reach-digest-is-subsecond` measures `reach-digests` after a real declaration transaction, with 100 ms and 128 MB bounds for rebuilding the reached graph while unchanged behavior digests can be reused. Parent and candidate results follow below.

## Reach candidate: not accepted

The isolated parent (ad3009bc0e6a2424c8fee458fe63c521e65a0c23) measured 605.38 ms / 1,522.80 MB. Batched-pull candidate 5ece5d14a49cfa55be5dc1ac2dc5e391f1f5ac23 measured 481.20 ms / 1,245.99 MB. Repeated candidate measurements were 488.51 ms / 1,247.48 MB and 469.25 ms / 1,254.21 MB. The test remained red. The candidate is saved in cost-gb-reach.patch; it is not installed or approved for application. Its production and test edits were removed from the working files.

The branch edit hook could not publish a changed contract containing `[:fn clojure.core/ifn?]`: `seon.program/read-edn` reported `No reader function for tag object`. Existing task: `seon.instrument/a-contract-with-an-fn-ifn-cannot-arm-on-a-sci-branch`. This is a separate verification limit, not the reason the cost proof failed.

Allocation sampling of the isolated check identifies the remaining owner: `konserve.compressor/Lz4Compressor` reading Datahike index nodes. Of approximately 1.30 GB sampled on `seon-test-body`, approximately 745 MB came from `LZ4FrameInputStream.readHeader`; about 930 MB included `datahike.index.persistent-set/CachedStorage.restore`. Weights overlap. The summary and reproducer are cost-gb-jfr.json and cost-gb-jfr.py. Recorded check: 779cac3e2e66e1cabb135e80675717d56a410f05, run 74002b5066e3. That profiled measurement (496.41 ms / 1,251.28 MB) is diagnostic, not performance proof.

Reproducer: run the isolated check with `JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=settings=profile,filename=/absolute/output/directory/,dumponexit=true'`; use `jfr print --json --events jdk.ObjectAllocationSample,jdk.ExecutionSample --stack-depth 100` on its test JVM recording, then pass that JSON to cost-gb-jfr.py. Do not attribute recordings from other check processes. An attempted attachment to an exited check PID produced no recording and is excluded.

Konserve e4334756413917305968333bf305e658c177765d already writes 64 KiB LZ4 frames. The dependency's frame reader allocates its buffers for each restored node. The public frame API has no reset/buffer-reuse seam (cost-record.md, Dependency seams and limits). No encoding change, private-field manipulation, buffer cache, dependency edit or fork bump was made. The remaining cold storage cost needs dependency-owner work in the scheduled restart batch; no speculative compression patch is presented as a fix.

The installed Datahike cache is active: a later read of default reported 15,243 nodes, 511,978,040 weighted bytes against a 512,000,000-byte maximum, 12,783,791 hits, 519,002 misses and 81,864 evictions. These are cumulative shared counters, not attribution to the measured closure. `datahike.index.persistent-set/CachedStorage` checks the shared cache before restoring a node. The recording does not establish whether all restored addresses were distinct; that remains an unanswered question, and the LZ4 attribution alone does not justify an encoding change.

## Repeated base candidate: not accepted

`seon.context/base-results` opens the immutable fork commit and computes its proof digests on every `seon.context/step-verdict`. The candidate caches just those digests in `seon.cache/lookup`, keyed by fork commit and proof set. It continues to read results from the current database, since new runs can change the answer without changing the fork commit. It neither retains a database value nor caches the current result under the fork's identity.

The proof constructs three real proof declarations before creating an agent branch and assigning its task. After acquisition, it measures three complete `step-verdict` calls with `seon.profile/measure`, compares their returned values and asserts a 2 ms cost bound. The zero repeated `seon.db/commit-database` count is supporting evidence only. This fixture has no recorded proof runs; it does not prove the cost of the full model turn or of reading existing runs.

Parent candidate 6c75eb511c6df3af7123b9c4a415d0b91d64a297, run b7f1869be04a: 3.30 ms and 2,772,672 allocation bytes. Candidate d9fa73fb4367a384a6cac23305182becffb67f85, run 5f904356229a: 2.62 ms and 2,459,264 allocation bytes. The repeated commit reads fell from three to zero, but the end-to-end cost proof remained red. The provisional bound was not relaxed to accept the candidate. The candidate and proof are preserved in cost-gb-base.patch; their production and test edits were removed.

## Remaining work and boundaries

1. Reproduce the reported GB-class test step with the original agent's task and reached tests. Fresh unassigned agents did not reproduce it; no fix is claimed.
2. Attribute cold index restores by address, then fix the responsible dependency or read pattern. The batched Seon candidate alone is insufficient. Any dependency change remains a patch for a scheduled restart batch; default was never stopped or reset.
3. Measure a complete request that repeats the base observation with actual recorded proof runs. The attempted cache reduced one immutable derivation, but did not satisfy its cost proof and does not pass one observation through the whole request.

Foreign boundaries: branch-repl held the SCI evaluation files during the initial probes. The context task resource is held by chain-2 and was not edited. The SCI publication error described above limits branch proof; isolated checks supplied the candidate measurements. No foreign source or session was changed. These are evidence and rejected candidates, not completed fixes.

Verification after removing both candidates: `clj-kondo --lint src script test --cache false` reported zero errors and 1,584 warnings in 7,606 ms. The evidence script parses as Python and its output parses as JSON. Root replaced default during this work; the final operator observation names archive f8565bffa12ec493ac2a62984d1baef92ff5dbac and process 15687, with no open turns and existing acquisition errors. No claim is made that the late cache counters or cleanup observations share the initial default's basis.

## Cleanup exposed a GB-class test step

The own-branch cleanup form `(my.agent/retire-branch! {:seon.store/branch (:seon.agent/branch (my.agent/branch))})` returned `:my.agent/retiring? true` for `cost-gb-1`, `cost-gb-2` and the edit-hook agent `cost-gb`. Retirement is requested; these answers do not prove branch deletion.

On the edit-hook agent, whose branch still held the experimental definitions, the same form selected `seon.context-test/repeated-step-verdicts-reuse-the-base-proof-digests`, `seon.test-failure-facts-test/an-unimplemented-stub-is-a-prove-steps-designed-red` and `seon.test.reach-test/a-changed-programs-first-reach-digest-is-subsecond`, all reported as errored. Reply handle `result/e954b0fa6aff4`: request 6,173.2 ms; test step 5,391.3 ms; step allocation 6,403.1 MB. The test cost reported 1,048.8 ms / 1,495.2092 MB / count 1; writer cost 1,660.8428 MB over 11 transactions. The shared JVM allocation window was 15,376.49 MB and includes unrelated work, so it is not attributed to this request. SCI acquisition also returned the existing recorded error signature `500ddeeeca094b0ccd26f74c3bd90c64c2775fa762f11344b7f8b9de5ec9aec1`.

This reproduces a GB-class test step with changed definitions and reached tests, not the fresh unassigned two-defn case. It is evidence for the original finding and the existing cold-reach task, not a passing comparison or a completed fix. The incomplete test runs and acquisition failure must be separated from successful test execution in the next probe.
