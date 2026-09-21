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
