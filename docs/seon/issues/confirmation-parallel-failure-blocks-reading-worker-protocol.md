---
type: issue
status: open
severity: friction
tags: [issue, test, runtime, class/p2, wave/flow-protocol]
---

# Confirmation parallel-failure blocks reading worker protocol

## Problem

A focused `bin/test` run wedged in the red-task confirmation path: after a
task was classified `confirmation parallel-only`, the coordinator waited in
`confirm-task-results!` (`src/seon/test/runner.clj:1436`) while a worker
blocked reading the protocol inside `confirm-parallel-failure!`
(`src/seon/test/runner.clj:1394`) — no reporter progress for 300 s, exit
124. This is a NEW wedge shape after the 2026-08-13 confirmation repairs
(worker identity at launch, `-Scp` classpath, total tally): the
parallel-only classification branch appears to hold a protocol read that
its counterpart never satisfies. The events-with-loud-backstops law
requires this read to be bounded and its starvation to name the peer.

## Evidence

2026-08-13, retained root `tmp/test-runs/run.UDOZSa`: selection
`seon.cluster.boot-test seon.dev.fresh-operator-test`, 61 tests completed,
last boundary
`populated-stopped-cluster-reopens-after-full-operator-restart`, then the
liveness backstop fired with coordinator and worker dumps retained.

The source-wide await census on the same date found the protocol read at
`src/seon/test/runner.clj:1098-1107,1165-1169` and its confirmation future join
at `src/seon/test/runner.clj:1427-1431`. The 300-second suite silence watchdog
eventually kills the JVM, but neither seam has a local bound naming the worker
whose response or confirmation never arrived. Task and worker-launch future
joins at `src/seon/test/runner.clj:1321-1322,1564` have the same local shape.

## Owner

`src/seon/test/runner.clj` confirmation phase — the
`confirm-parallel-failure!` protocol exchange. Diagnosis starts from the
retained worker dumps (which side never writes/reads).

## Acceptance

- The parallel-only confirmation exchange is event-complete: both sides'
  reads are satisfied by writes the other side always produces, bounded by
  the declared backstop naming the peer on starvation.
- One regression covering the parallel-only classification path end to end.
