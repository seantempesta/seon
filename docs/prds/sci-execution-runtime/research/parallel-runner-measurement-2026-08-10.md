---
type: research
status: complete
tags: [testing, performance, concurrency, operator]
---

# Parallel runner measurement — 2026-08-10

## Verdict

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
