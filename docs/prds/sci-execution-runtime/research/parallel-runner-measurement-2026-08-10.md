---
type: research
status: complete
tags: [testing, performance, concurrency, operator]
---

# Parallel runner measurement — 2026-08-10

## Shared-base rerun verdict

The shared-publication implementation removed the dominant repeated setup and
the boot namespace's atomic chain, but the complete stress run still missed
the 5–8 minute ceiling. The frozen-tree command was:

```sh
/usr/bin/time -p bin/test --full 2>&1 \
  | tee tmp/full-gate-parallel-shared-base-2026-08-10.log
```

The source was `b9c0f63fd2c7f2dc36f61098c69688eee1604234`. The runner
selected **1,160 tests**: 73 platform and 1,087 bulk. It aggregated **10,004
assertions, 9 failures, and 12 errors** across 17 failing tests. The command
exited 1 after **40:00.36 wall time** (`real 2400.36`, `user 5762.55`, `sys
1022.93`). The foreign Flow drained-hang did not recur, so no test was excluded
from the aggregation.

The wall time has two materially different parts:

- The nine-worker execution window ran from the first platform task at
  `02:58:30.175091Z` through the last pool result at `03:14:03.112791Z`:
  **15:32.94**. Including one shared-base preparation, namespace loading, and
  selection, the command reached pool drain in about **17:13.85**.
- The required isolated confirmations then serialized 17 failed tests through
  `03:36:49.621964Z`, adding **22:46.51**. This diagnosis cost belongs to a red
  stress run, not the projected green critical path, but both the 15:32.94 pool
  window and 17:13.85 pre-confirmation command time independently miss the
  eight-minute ceiling.

The verdict against the ceiling is therefore **fail**: the pool is 1.94 times
the eight-minute maximum, and the complete red diagnostic command is 5.00
times the maximum.

## What the implementation changed

Commit `e36377a02` prepares one published database base per `bin/test`
invocation, copies it copy-on-write into worker/operator roots, and
reidentifies the copied commit graph. Commit `6bb9cfe8d` makes exact copied
commit records use the destination database identity, so branches can fork
from the shared base honestly.

`seon.cluster.boot-test` now has a per-test fixture over that base instead of
one namespace-wide `:once` fixture. Its 29 tests are ordinary dynamic-queue
tasks. The largest boot member was
`explicit-refork-destroys-the-old-branch-and-forks-current-source` at **205.218
s (3:25.22)**, below the requested four-minute worst-case chain. The old
atomic namespace cost was 918.334 s (15:18.33).

The full run's platform tier completed in **34.413 s**, below the 60-second
requirement. Its cohost test was 34.375 s under full load. This clears the
platform-tier requirement, though it does not reach the design's more
aggressive 15-second cohost target.

## Stress findings and confirmation

The runner distinguished eleven parallel-only findings from six failures that
reproduced alone. The parallel-only class is filed as
[`parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md`](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md).
No test was silently moved to the serial remainder.

Parallel-only confirmations:

- `seon.cluster.boot-test/incremental-source-refresh-preserves-agreement-across-real-edits`;
- `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster`;
- `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store`;
- `seon.dev.fresh-operator-test/forced-reset-clears-an-exact-dead-process-record`;
- `seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`;
- `seon.dev.fresh-operator-test/isolated-cached-boot-reports-refusal-then-reaches-readiness`;
- `seon.dev.fresh-operator-test/live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`;
- `seon.dev.fresh-operator-test/populated-stopped-cluster-reopens-after-full-operator-restart`;
- `seon.dev.fresh-operator-test/source-less-root-reset-republishes-and-reforks-default`;
- `seon.sci.defs-test/agent defs-survives-kill-9-and-explicit-clear`; and
- `seon.sci.eval-test/generated-sources-compose-fork-guard-and-admission`.

Reproducible confirmations:

- `seon.cluster.armed-test/two-clusters-in-one-jvm-own-distinct-live-program-contexts`;
- `seon.cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it`;
- `seon.dev.fresh-operator-test/fresh-process-loads-schema-before-every-operator-instrumentation`;
- `seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`;
- `seon.render-simplification-test/nested-values-render-their-declared-faces`; and
- `seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root`.

## Shared-base worker utilization

Utilization is completed-task elapsed time divided by the 932.938 s pool
window. It includes blocking IO and is not CPU utilization. The serial
fail-closed remainder was one 69-test REPL-parity task; it completed in 73.525
s alongside the pool and did not extend the critical path.

| Worker | Tasks | Busy time | Utilization | Largest task |
| --- | ---: | ---: | ---: | --- |
| pool-1 | 196 | 816.084 s | 87.5% | instrumented agent turn, 229.998 s |
| pool-2 | 100 | 816.164 s | 87.5% | bootstrap drive, 185.670 s |
| pool-3 | 120 | 793.096 s | 85.0% | fresh-operator export, 208.674 s |
| pool-4 | 84 | 909.172 s | 97.5% | operator contracts, 221.475 s |
| pool-5 | 152 | 786.283 s | 84.3% | explicit refork, 205.218 s |
| pool-6 | 144 | 786.761 s | 84.3% | refork with held database, 169.346 s |
| pool-7 | 57 | 811.367 s | 87.0% | web oversized body, 149.422 s |
| pool-8 | 121 | 817.765 s | 87.7% | shell concurrent drain, 150.521 s |
| pool-9 | 117 | 814.897 s | 87.3% | incremental publication, 201.518 s |

The pool averaged **7.89 occupied workers** (87.6%). The remaining ceiling
miss is therefore not worker starvation. It is the sum of 2.5–3.8 minute
resource-heavy tasks under contention plus the fast tail. The leading cuts
remain the 229.998 s instrumented agent turn, 221.475 s operator contract
property, 208.674 s fresh-operator export, 185.670 s bootstrap drive, and
150–161 s shell/web child-JVM tests. Each needs a smaller fixture/property or
less child-JVM setup; accepting the 15:32.94 pool wall time would violate the
owner ceiling.

## Previous measurement verdict

The first coherent-tree full measurement did not meet the 5–8 minute ceiling
and did not produce a terminal suite summary. `bin/test --full` stopped at its
loud 300-second liveness backstop after **24:55.66 wall time** (`real
1495.66`, `user 4093.53`, `sys 838.18`). The result is exit 124, not green.

The measured source was
`e8ba39f45e5c7775b280c4e6c494665c8c02d40a`. The command was:

```sh
/usr/bin/time -p bin/test --full 2>&1 \
  | tee tmp/full-gate-parallel-2026-08-10c.log
```

This is not an ambiguous timeout. The retained virtual-thread-aware dump shows
the last task blocked in `seon.flow/stop-work-launcher!` at `src/seon/flow.clj`
waiting for its `drained` promise. The task is the already filed
[`submission-time-limit-covers-the-pre-start-wait` isolation defect](../../../seon/issues/a-flow-test-hangs-when-run-without-its-namespace-siblings.md).
The test passes only when namespace siblings establish hidden state; var-level
execution reproduces the hang. It is therefore a reproducible test-isolation
finding, not a confirmed parallel-only platform failure.

## Selection and completion totals

The program graph selected **1,158 tests** across 120 namespaces:

- platform: 73;
- bulk: 1,085;
- not reached: 0.

The workers returned task results for **1,157 of 1,158 selected tests** in
1,061 completed tasks. The serial fail-closed remainder was one atomic
`seon.repl-parity-test` task containing 69 tests; it completed in 70.580 s
alongside the pool. The only selected test without a returned result was
`seon.flow-test/submission-time-limit-covers-the-pre-start-wait`.

The coordinator aggregates Clojure assertion/pass/fail/error totals only after
every worker future returns. Because the Flow task did not return, no honest
assertion total exists for this run. No assertion failure output or isolated
confirmation phase was reached before the liveness stop. The confirmed
parallel-only findings list is therefore empty; the one observed finding is
the reproducible isolation defect above.

## Wall-time anatomy

- Namespace load plus program-graph selection: 38.9 s from suite start to the
  platform tier.
- Platform tier: 72.150 s, so the bare-tier under-one-minute target also
  missed under this load.
- Bulk tier began at `02:01:24.402700Z`.
- Every non-hung test had returned by `02:19:17.469359Z`, 17:53 after bulk
  began and 19:44 after suite start.
- The last ordinary progress was followed by the deliberate 300 s liveness
  backstop, producing the 24:55.66 terminal wall time.

The target was already structurally impossible before the hang. The atomic
29-test `seon.cluster.boot-test` namespace occupied pool-8 for 918.334 s
(15:18). The next largest completed members were fresh-operator export at
363.848 s, instrumented agent turn at 358.941 s, the operator public-contract
property at 251.232 s, and the n-agent property at 235.120 s. Nine private
operator roots removed suite-context contention between workers, but each
worker still paid publication/base preparation and concurrent machine load
inflated shell and operator tests sharply.

## Worker utilization

Utilization below is completed-task elapsed time divided by the 1,445.214 s
window from the first platform task to the liveness backstop. It measures task
occupancy, including blocking IO, not CPU. Pool-4's second figure includes the
576.137 s spent inside the hung Flow task before the backstop.

| Worker | Completed tasks | Completed tests | Busy time | Utilization | Largest completed task |
| --- | ---: | ---: | ---: | ---: | --- |
| pool-1 | 119 | 119 | 1,087.109 s | 75.2% | operator contracts, 251.232 s |
| pool-2 | 79 | 79 | 977.529 s | 67.6% | bootstrap drive, 198.378 s |
| pool-3 | 177 | 177 | 984.359 s | 68.1% | fresh-operator export, 363.848 s |
| pool-4 | 81 | 81 | 802.715 s | 55.5%; 95.4% including hang | armed boot message, 192.859 s |
| pool-5 | 188 | 188 | 977.877 s | 67.7% | n-agents, 235.120 s |
| pool-6 | 79 | 79 | 960.498 s | 66.5% | armed boot seeds, 192.263 s |
| pool-7 | 199 | 199 | 967.094 s | 66.9% | two-cluster boot, 198.588 s |
| pool-8 | 35 | 63 | 1,095.511 s | 75.8% | atomic boot namespace, 918.334 s |
| pool-9 | 103 | 103 | 973.637 s | 67.4% | instrumented agent turn, 358.941 s |
| serial | 1 | 69 | 70.580 s | 4.9% | REPL parity atomic task, 70.580 s |

Across the nine pool workers, completed-task occupancy averaged 6.11 workers.
The late tail then collapsed to pool-4 alone. Peak resident memory was not
captured by this `/usr/bin/time -p` run, so this measurement makes no measured
RSS claim.

## Required cuts before another ceiling claim

The implementation proves that a dynamic isolated-worker pool works, but nine
JVMs alone cannot meet the ceiling. The next measurement is not credible until
all of these classes are addressed:

1. Prepare one immutable published test base once and fork it into each worker
   root. Per-worker publication is the co-equal dominant cost.
2. Split the `seon.cluster.boot-test` namespace fixture so its 29 tests are not
   one 15-minute serial chain. Preserve fixture truth while sharing a published
   base and give each destructive case its own cheap branch/root fixture.
3. Fix the Flow test's hidden sibling precondition at the existing open issue;
   never move it quietly to the serial remainder.
4. Cut or split the measured largest members: fresh-operator export (shared
   published base), instrumented agent turn (smaller fixture), operator
   contracts (split the property), and n-agents (smaller agent count/property
   sample with the same invariant).
5. Re-measure worker count after shared-base preparation. The 6.11-worker
   occupancy and heavy shell-test inflation show that nine runnable JVMs do
   not translate into nine CPUs of useful throughput on this machine.

Until those cuts land, the measured verdict against the owner ceiling is
**fail: more than 3.1× the eight-minute maximum, with no terminal suite
summary**.
