---
type: research
status: step 1 verified; platform admission refused; step 2 requires owner review
created: 2026-09-16
tags: [testing, performance, datahike]
---

# Test preparation costs

Read AGENTS.md §0–§5, the
[measured execution note](test-execution-model-2026-09-16.md) end to end,
and [stage-0 design §3](test-system-stage0-design-2026-09-17.md#3-one-classpath-derivation-and-identity-resolution)
in full. The assignment authorizes serial gates with
`SEON_TEST_ORCHESTRATOR=1` and requires stopping before choosing between
honest step-2 designs. Selection, tiers, claims, and bounds are unchanged.

## Step 1: fixture preparation belongs to worker readiness

`seon.test.runner/worker-command-loop!` acquires the packaged projection,
realizes the fixture owner's canonical `database-base`, refuses a failed
construction, and only then emits `:ready`. The readiness event carries
`:seon.test.runner/fixture-preparation-ms`; the coordinator prints that exact
typed event as `WORKER READY`. Its existing bounded readiness exchange still
owns the wait. The same projection is passed explicitly to contract
initialization, avoiding a second packaged projection acquisition.

The canonical base's own immutable published-path key makes subsequent fixture
acquisitions reuse it. Every selected test still executes under the existing
contract arming. Priming precedes arming, as it does in `seon.test/check`;
no test clock or bound was increased. The new regression
`seon.test-preparation-test/worker-readiness-holds-a-usable-canonical-base`
uses the real worker loop, the actual canonical base and a real database branch.

The first attempted after-run correctly refused readiness: construction lacked
the handed schema projection. That failed attempt executed **zero tests** and
is not a performance result. The correction carries one projection through
preparation and arming. Its diagnostic was
`:seon.test-support/database-base-unavailable`, caused by
`:seon.schema/missing-projection` at `seon.program`.

## Measurements

All commands ran one at a time, with output redirected under
`tmp/test-preparation-costs/`. Baseline snapshot HEAD:
`5ae8dcdafed230128aa20bc2ccee73a6faf1d8cf`.

```sh
SEON_TEST_ORCHESTRATOR=1 bin/test --paths src/seon/schedule.clj -- seon.db-test
SEON_TEST_ORCHESTRATOR=1 bin/test --paths src/seon/schedule.clj src/seon/test/runner.clj -- seon.db-test
SEON_TEST_ORCHESTRATOR=1 bin/test-fast --paths src/seon/test/runner.clj src/seon/test/arm.clj test/seon/test_preparation_test.clj -- seon.test-preparation-test
SEON_TEST_ORCHESTRATOR=1 bin/test --paths src/seon/schedule.clj src/seon/test/runner.clj src/seon/test/arm.clj test/seon/test_preparation_test.clj -- seon.db-test
```

The second command is the failed intermediate attempt, not the final proof.
Raw logs: `before.log`, `after-priming.log`, `fast.log`, `after-final.log`.
Final after-run snapshot HEAD: `8d29822964216886f3a40cbf72ae0af0a7f8525a`.
Concurrent committed source changes between those HEADs were confined to
`src/seon/issue.clj` and `test/seon/turn_loop_test.clj`; database-test bytes were
unchanged. These are HEAD-plus-owned-path measurements, not an assertion that
the rest of the checkout stood still.
The fast regression passed **1 test / 7 assertions**, exit 0; its emitted
PHASE lines were `snapshot elapsed-seconds=3` and `test-slot elapsed-seconds=0`.
The [evidence script](test-preparation-costs-2026-09-16.py) extracts every phase,
readiness event and each worker's first completed task without reconstructing
timings from unrelated intervals.

Every emitted PHASE line, seconds:

| Phase | Before | Intermediate refusal | Final after |
|---|---:|---:|---:|
| snapshot | 3 | 6 | 3 |
| test-slot | 0 | 0 | 0 |
| dependency-cache-and-classpath | 3 | 3 | 3 |
| worker-checkouts | 3 | 2 | 3 |
| published-base | 0 | 126 | 131 |
| coordinator-and-tests | 84 | 28 | 95 |

Baseline: **48 tests, 357 assertions, 0 failures, 0 errors; exit 0**.
The exact cached base hit took **5 ms work / 5 ms elapsed**. The intermediate
cold publication took **125,771 ms work / elapsed**, then failed readiness.
The final cold publication took **130,698 ms work / elapsed**. The final run
passed **48 tests / 357 assertions / 0 failures / 0 errors**, exit 0.
These are different cache conditions; do not subtract total wall times to
claim a priming speedup. The historical cold reference is **112,098 ms** in
`tmp/orchestrator/gate-results/batch-107.log`.

| First task | Before worker / elapsed-ms | After worker / elapsed-ms | After preparation-ms |
|---|---|---|---:|
| a-write-naming-another-clusters-branch-is-refused-naming-both | pool-1 / 16,574 | pool-1 / 908 | 19,760 |
| all-transaction-grammars-validate-the-resulting-entity | pool-2 / 17,063 | pool-3 / 1,448 | 19,549 |
| an-agent-namespace-is-shared-and-is-not-an-identity | pool-3 / 16,395 | pool-2 / 776 | 19,686 |

Worker assignment remains dynamically claimed, so the table matches **test
identity**, not an assumed stable worker assignment. All three readiness events
preceded the first `BEGIN`; the largest final per-test duration was **5,094 ms**.
The preparation cost moved outside test clocks; total gate wall time did not
improve in this observation. `coordinator-and-tests` increased **84 → 95 s**
under different cache conditions and concurrent system load. Priming guarantees
honest attribution, not removal of canonical construction work.

## Step 2: design decision, before implementation

The cache fingerprint and source digest are different authorities.
`dev_cache.clj:459` derives `test-digest` from gate input digests, its own bytes,
and dependency-cache digest. `seon.cluster/source-roots` includes program roots,
default config and issue notes; `source-snapshot` also includes schema forms.
String equality between these unlike digests cannot authorize reuse.

Within one store, `source/current` returns an exact commit and
`source/database` reads it with Datahike `commit-as-db`. New clusters fork that
commit via `registry/ensure-cluster!`; they do not re-index. A gate uses a
different process and store, so it needs an independent store export/clone,
not a branch pointer into the development JVM's locked store.

The existing incremental owner is suitable after a compatible immutable cache
is cloned: `refresh-source!` with changed paths reads `build/current-src.edn`,
verifies its source digest against the published branch, derives missed changes
from per-file digests, and performs safe upserts or falls back to full analysis
for structural changes. The candidate's manifest must be rebased and the store
reidentified using the existing export owner. Its final provenance must be
captured from the newly published database. `--prepare-base` currently gets
`manifest.edn` from the full-build-only in-memory `source-analysis-cache`;
an incremental implementation must instead consume the completed source
artifact, which the publisher already writes atomically.

Measured sizes during this slice: immutable completed test stores **102–104
MiB**, development store **2.4 GiB** (`du -sh`). These are allocated sizes,
not export time estimates. No incremental/export benchmark was run and no
speedup for either option is claimed.

| Option | Guarantee | Measured cost, work and tradeoff |
|---|---|---|
| **1. Clone a compatible immutable test base, then use the existing incremental publisher — recommended** | Exact final snapshot remains authoritative; structural or incompatible changes take the existing full rebuild path. No development JVM dependency. | Reuses a 102–104 MiB store. Clone/reidentification and incremental publication costs remain unmeasured. Extend the cache owner to compare declared inputs and pin the selected base under its existing lock, then feed the existing refresh owner. Gives up reuse when no compatible retained base exists. |
| 2. Export the development publication on exact source/dependency compatibility | Reuses one exact published commit and its matching manifest through the store's owning process; verify identity on the exported value. | Current export owner copies **every branch** of the 2.4 GiB development store and reidentifies reachable history. Export time is unmeasured. Requires coherent artifact/commit capture and live operator availability; gives up standalone gate preparation for this reuse path. |
| 3. Keep exact-key cache reuse and full misses | Smallest invariant: every miss builds from the immutable snapshot. | Measured hit **5 ms**, cold **125,771 ms** here (**112,098 ms** historical). Gives up incremental reuse. Removing the proven idle publication-exit delay below is a separate small correction, not a substitute for a reuse design. |

This is the requested review boundary, not a foreign failure. No step-2
production code has been changed.

## Additional cold-publication finding

After its completed-base message, publication PID 64773 was alive at **0.0%
CPU**. `jcmd 64773 Thread.print` recorded:

```text
"clojure-agent-send-off-pool-0" ... elapsed=50.03s ... TIMED_WAITING
  java.util.concurrent.SynchronousQueue.poll
  java.util.concurrent.ThreadPoolExecutor.getTask
"clojure-agent-send-off-pool-1" ... elapsed=50.03s ... TIMED_WAITING
  java.util.concurrent.SynchronousQueue.poll
  java.util.concurrent.ThreadPoolExecutor.getTask
"DestroyJavaVM" ... elapsed=39.81s
```

These were non-daemon threads; other listed daemon threads do not keep the JVM
alive. Clojure's `Agent.java:54` constructs the send-off cached executor and
`:72` shuts it down; `core.clj:2268` exposes `shutdown-agents`.
The publication branch of runner `-main` returns without that shutdown.
At least **39.81 seconds** of this observation is exit waiting, not analysis.
Filed as [publication idle threads](../../../seon/issues/archive/test-base-publication-waits-for-idle-agent-threads.md).

## Step 3: the premise is already dissolved at this HEAD

Git archaeology found commit `45e5c6c5605b4c42223b9970a0a2256abf6954a9`
(2026-09-08): the coordinator already reads the exact publication's
`manifest.edn` with `cache/manifest` when `seon.test.published-base` is supplied.
`bin/test` supplies it. Only the non-published fallback calls `build-manifest`.
Publication obtains that manifest from the same analysis that populated facts.
Replacing this with another database pull would add work, not remove analysis.

The label `SELECT building the program graph` is stale. Between that line and
`TIER platform`, the coordinator also joins every worker's initialization
future. Baseline measured this enclosing span at **23.018 seconds**
(21:44:24.409274Z → 21:44:47.427398Z). It is not a measured analysis duration.
One read-only JVM probe on default read the **339-artifact** immutable manifest
in **404 ms** (406 ms full MCP form). This is a live host measurement, not a
coordinator timing or an inferred 18-second saving. Exact form:

```clojure
(let [started (System/nanoTime)
      manifest (seon.test.cache/manifest
                "/Users/sean/src/seon/target/test-published-bases/9c65e213bd44d95574491851fba202849bab97c1835598ab615cc5b32e53f4a2/base")]
  {:manifest-read-ms (quot (- (System/nanoTime) started) 1000000)
   :artifacts (count (:seon.fn.manifest/artifacts manifest))})
```

Filed as [misattributed coordinator progress](../../../seon/issues/test-coordinator-label-attributes-worker-startup-to-analysis.md).
Correcting/splitting that reporting is deferred until after step-2 review,
preserving the requested sequence of separate commits.

## Dependency ledger and verification boundary

Read the worker loop, `run!`, `worker-exchange!`, initialization and publication
paths in full. Relevant mechanisms:

- `test/seon/test_support.clj:379,475,609,974`: canonical base creation,
  retrying realization, immutable publication key and branch lease.
- `src/seon/test/arm.clj:238`: the shared contract initialization owner.
- `reference-code/datahike/src/datahike/versioning.cljc:212,323,469`:
  branch pointers, expected-head publication and immutable commit reads.
- `src/seon/cluster/source.clj:153,165,576,691`: source head, database value,
  full publication and safe upserts; `src/seon/cluster.clj:2000,2371`:
  incremental analysis and refresh entry.
- `src/seon/cluster/export.clj:293`: independent export and reidentification.
- `dev_cache.clj:466`: tools.build `create-basis` with `:test` and ordered
  classpath roots; this slice does not alter classpath semantics.

Session start: `bin/seon status` found default PID 41413 alive; MCP
`runtime_status` received all three plumbing replies. One read-only JVM probe
returned basis **536871845**, runner loaded and fixture namespace not loaded.
That is a host observation, not a fixture proof. The live proof for this change
is the real newly launched worker JVM, not default's hot-reloaded runner.
No default stop/restart/refork, foreign session operation or schema edit.

Unrelated dirty schema, turn, message, rendering, test and issue files were
preserved and excluded from the named-path snapshots. Hooks report existing
repository Markdown dependency-pin errors in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`; no claim
of repository-wide Markdown lint success is made.

The owned Markdown files pass `seon.dev.markdown/validate-file` with zero
violations; `git diff --check` is clean. The failed intermediate root was
removed only after checking its recorded launcher/runner had exited and no
live Java/Clojure process held it. Its diagnostic is retained as
`tmp/test-preparation-costs/intermediate-refusal.edn`. Successful gates clean
their own roots. No scratch cluster or worktree was created.

Required platform command (log `platform.log`), snapshot HEAD
`1304a404b32b407fb74caaadc4c3d71faaaffbb2`:

```sh
SEON_TEST_ORCHESTRATOR=1 bin/test --paths src/seon/test/runner.clj src/seon/test/arm.clj test/seon/test_preparation_test.clj --platform
```

Platform **exit 1 before test execution**, after selecting 96 tests. Refusal
kind `:seon.test.runner/destructive-platform-test`; exact path:

```text
seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload
→ seon.cluster.agent/graph-definition
→ seon.schedule/schedule-step
→ seon.schedule/fire-due!
→ seon.operator/reap-dead-roots!
→ seon.operator/cleanup-root-under-lock!
```

None of that path's four source/test files changed between baseline and platform
HEAD, verified by path-restricted `git diff`. The selection checker is unchanged
by this slice. This is the exact outside-scope boundary; no platform-green claim
is made and no tier or selection change was attempted. Filed as
[platform census admission](../../../seon/issues/platform-flow-census-reaches-root-cleanup-through-scheduler.md).

All platform PHASE lines, seconds: **snapshot 5; test-slot 0;
dependency-cache-and-classpath 5; worker-checkouts 7; published-base 162;
coordinator-and-tests 54**. Publication work **162,176 ms**, elapsed
**162,177 ms**. All three workers published readiness first, with preparation
**pool-1 18,430; pool-2 18,362; pool-3 18,218 ms**. There are no platform
first-task timings because no task was admitted. The standalone readiness
regression's fast proof remains **1/7/0/0**; database gate **48/357/0/0**.

The refusal is copied to `tmp/test-preparation-costs/platform-refusal.edn`.
The completed refused run's root is removed after confirming its launcher,
coordinator, and workers have exited. No foreign root was cleaned.

Owned files: `src/seon/test/runner.clj`, `src/seon/test/arm.clj`,
`test/seon/test_preparation_test.clj`, this landing note, its `.py` evidence
reader, and the three linked issue notes (publication idle exit, coordinator
label, platform admission). Stop here for the assignment's step-2 design review.

## Approved continuation: publication exit

Owner approved step 1 and accepted step 3 as dissolved, then chose step-2
option 1. Before reuse work, the publication branch now calls
`shutdown-agents` after its completed-base message. This is the requested
one-line code change; it stops idle agent executors without altering a bound.

Real command: `bin/test --paths src/seon/schedule.clj src/seon/test/runner.clj -- seon.db-test`.
Snapshot HEAD **5d3a3bda585a420117f884e0c33e8521d3d96bf3**; log
`tmp/test-preparation-costs/exit-fix.log`. Normal `bin/_test-slot` admission,
no slot bypass or concurrency override. Publication **91,575 ms work / elapsed**,
versus **130,698 ms** in the preceding measured cold run. The ready message
was followed by child exit and coordinator launch, with no idle-expiry wait.
The difference includes load variation and is not an isolated CPU speedup.
Database tests: **48 / 357 assertions / 0 failures / 0 errors**.
The successful publication was opened by all three canonical worker fixtures.
PHASE seconds: snapshot **3**; test-slot **0**; dependency-cache-and-classpath
**2**; worker-checkouts **3**; published-base **91**; coordinator-and-tests
**131**. Launcher exit **0**.
