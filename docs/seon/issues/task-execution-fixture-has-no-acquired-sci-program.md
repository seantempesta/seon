---
type: issue
status: open
severity: friction
tags: [issue, test, sci]
---

# Task-execution fixture has no acquired SCI program

Run `e63e0d5af33e`, reproduced in `75801c3ccaed`, 2026-09-23:
`seon.test.runner-test/default-red-does-not-launch-confirmation` expects one
assertion failure and the deliberate fixture's message. Instead its task
returns `The SCI context did not acquire the tested program` from
`seon.test.runner/run-task!`. The failure count is zero and the expected
message is absent. The task never exercised the deliberately failing body.

This is outside the six assigned duration bodies. Acceptance: acquire the
fixture's actual program through its existing SCI owner, then verify the
failure is captured exactly once and no confirmation task executes. Do not
replace the acquired-program check or treat the preliminary refusal as the
intended failing assertion.

## Combined gate diagnosis — 2026-09-21

The gate on `33842ead4` recorded 162 errors before any test body:
[retained gate log](../../prds/agent-platform/landing/fresh-start-combined-gate.log)
(line 407 onward), with snapshot/worker evidence in
`tmp/test-runs/run.rcJWRo`. Every task reached the same resolution refusal.

The canonical fixture's `:seon.test-support/sci-context` delay constructs
`seon.sci.eval/cluster-ctx`, which now intentionally defers acquisition.
`worker-command-loop!` dereferenced that delay and advertised readiness;
its context had no acquired database. `resolve-test` compares the digest
of its explicit execution database with the acquired database digest.
Without acquisition the latter is nil, so its refusal is correct. Worker
resolution carries that same fixture's connection, immutable database,
projection, and class loader; no separate recording database is substituted.

The task boundary now calls the existing `seon.sci.eval/acquire!` for an
unacquired context before resolving its test Vars, using the resolution's
explicit database and the existing `seon.test/with-test-loader` seam with
the resolution's class loader. The host path's `prepare-tests!` had the same lazy
construction assumption and now acquires first use under its existing test
loader. Neither boundary reacquires an already acquired context. The
`resolve-test` digest comparison and acquisition-refusal check are unchanged.

The existing `default-red-does-not-launch-confirmation` regression now starts
with a deliberately lazy context, requires matching acquired program identity,
one executed test and zero infrastructure errors, and retains its deliberate
single assertion failure and no-confirmation assertions. The existing
`resolution-follows-admitted-source-and-acquisition` regression explicitly
acquires its older program before forking a stale context, then requires
both direct resolution and worker execution to refuse the newer request
without replacing that context's acquired database.

Verification pending: only `git diff --check` ran in this bounded lane.
The owner will include `seon.test.runner-test` and `seon.test-test` in the
one rerun of the combined checkpoint. No JVM, tests, operator commands, or commits ran here.

### Worker readiness must include shared acquisition

The repaired combined checkpoint on `98d1a8ede` reached test bodies, but its
first task reported SCI namespace additions as worker-global drift:
[log](../../prds/agent-platform/landing/fresh-start-combined-gate-repaired.log),
line 96, at 20:15:39. The examples were `my.agent absent->4` and
`my.background absent->4`. This is the first acquisition of the worker's shared
context, not a mutation originating in the selected test body:
`sci-base-namespace-sizes` observes a realized context delay, while
`serve-worker-commands!` captures its initial sizes before `run-task!` acquires
the previously empty program.

Worker initialization now acquires that shared program through the existing
SCI and test-loader owners before readiness and therefore before any task's
drift snapshot. Readiness's fixture-preparation measurement includes the work. Acquisition
row refusals and refusal-recording errors throw before readiness is emitted.
The existing `a-cold-worker-does-not-arm-its-base-around-host-test-bodies`
regression now performs the same real acquisition before its snapshot and
checks that the host body leaves those SCI namespace sizes unchanged.
Standalone task execution keeps its first-use acquisition. The snapshot and
drift comparison are unchanged, so subsequent namespace additions/removals
remain observable. No test/JVM was launched alongside the owner's checkpoint;
static diff checking passed. The next owner source-load check and checkpoint
must verify this initialization placement.

### Readiness frame joined to partial diagnostic — 2026-09-21

The next checkpoint (`tmp/test-runs/run.Ns3SqO`, launcher 23490,
coordinator 24053, worker 24145) acquired its program but could not exchange
readiness. The owner captured thread dumps under
`docs/prds/agent-platform/landing/runtime-*-readiness-threads.json`: the worker
was reading commands and the coordinator waiting for its readiness event.
The final retained worker stderr line contained:

```text
    seon.test.runner$worker_main_BANG_.invokeStatic (runner.cljSEON_TEST_WORKER_EDN #:seon.test.runner{:worker-event :ready, ...}
```

The coordinator's `read-exchange-reply!` recognizes protocol lines only at
the start of a line. It therefore attributed this entire joined line as
nonprotocol output, then waited for an event the worker had already sent.
The exact earlier diagnostic writer is unproven; `worker-main!` redirects
`System/out`, but code can retain an earlier writer. No stream-ownership
redesign is required to repair the observed framing defect.

`write-protocol!` now begins each complete frame with a newline. The strict
reader and its identity/exchange validation are unchanged. The real canonical
worker-readiness regression first writes an unterminated startup diagnostic,
then runs worker initialization and passes the resulting output through the
actual coordinator reader. It requires a ready terminal and separately
preserved diagnostic output. It also feeds an unmatched frame followed by a
normal frame, requiring the former to be attributed and the latter parsed
unchanged. Existing protocol fixture parsers skip blank frame boundaries.

`git diff --check` passed; no JVM or test ran in this lane. Root terminated
the captured gate through its owned trap and owns source-load and canonical
verification of these final edits.
