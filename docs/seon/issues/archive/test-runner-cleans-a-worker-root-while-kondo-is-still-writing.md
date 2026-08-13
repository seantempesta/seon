---
type: issue
status: resolved
severity: blocker
tags: [issue, testing, concurrency, operator]
---

# Await every worker writer before deleting its root

## Problem

The changed-files gate cannot settle because
`seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root`
tries to delete a worker root while its clj-kondo cache directory is not empty.
The same test fails in the runner's fresh isolated confirmation, so reducing
worker parallelism or classifying it as parallel-only would hide the failure.

## Evidence

The 2026-08-11 changed-files run for commit `2a19869c7` completed 1,109 tests
and 8,968 assertions with one error. Both the pool attempt and isolated
confirmation ended in:

```text
java.nio.file.DirectoryNotEmptyException:
.../workers/pool-8/.clj-kondo/.cache/v1/clj
  at seon.fs/delete-recursively! (fs.clj:69)
```

The runner retained the failed isolated root at
`tmp/test-runs/run.9lLkUI`. This note records only the observed boundary;
whether a process, thread, or cache writer outlived its published completion
still needs a direct probe.

The W1 integration gate reproduced
`interrupted-launcher-awaits-its-runner-before-retaining-the-root` as its one
parallel-only error: the fake runner never published readiness
(`tmp/orchestrator/w1-integration-stdout.log:413122-413161`). A subsequent
focused `bin/test seon.gen.loop-test` attempt also failed before its selected
tests while deleting a worker's `test/seon/schema_edn_fixtures/valid` path.
Both observations remain outside W1's changed owners and reinforce the same
completion-before-cleanup boundary without attributing the still-unproven
writer lifetime.

## Owner

The test-runner interruption/completion boundary and the worker-root cleanup
that consumes it. `seon.fs/delete-recursively!` is the visible refusal site,
not yet an attributed cause.

## Root cause

`seon.test.runner/stop-process-tree!` signaled the worker and its captured
descendants but awaited only the worker `Process`. A descendant could therefore
survive the worker's `Process.waitFor` completion and keep writing beneath the
worker root after the coordinator had published its own completion to
`bin/test`. The launcher correctly keyed successful-root deletion to that
coordinator completion; the coordinator's value was incomplete because it did
not include its worker child's exit publication.

`script/seon/dev/changed_test.clj` does not share this defect or the worker-root
cleanup owner. It already awaits the exact launched `bin/test` process through
`ProcessHandle.onExit`, and `bin/test` owns the successful-root deletion.

## Resolution

Commit `b2b3aa185` makes the worker ownership value carry the root process,
every captured descendant, their individual `ProcessHandle.onExit` futures,
and one `CompletableFuture.allOf` completion. Shutdown captures those futures
before signaling, signals descendants before the worker root, and returns only
after every captured process publishes exit. The ten-second wait is retained
only as a loud backstop: it reports the still-live process ids, forces them,
and refuses if their recorded exit completion still does not arrive
(`src/seon/test/runner.clj:1156-1218`).

The regression uses filesystem creation events and a FIFO rather than sleeps.
A child publishes that it still owns the root, blocks, then writes into that
root and publishes completion. The test proves cleanup has not begun while the
child is blocked and fires exactly once after the recorded completion. Its
fixture teardown continues through the existing no-follow recursive deletion
owner; the production delete path was not changed
(`test/seon/test_runner_test.clj:470-563`).

Dependency grounding: OpenJDK 26.0.1's `java.lang.ProcessHandle#onExit`
returns a `CompletableFuture` completed on process termination; the installed
OpenJDK source explicitly documents `onExit().get()` as the event-driven wait.
The first-party precedent is commit `7eeff3e70`, which changed the launcher to
await its exact runner before retaining the root. This repair applies the same
law to the runner's worker children.

## Resolution evidence

- Before the fix, the bounded regression reproduced the class with 2 failures
  and 3 errors: cleanup ran once and removed the worker root while the child
  was still blocked and able to perform its late write.
- After `b2b3aa185`, `bin/test seon.test-runner-test` ran 13 tests containing
  132 assertions with 0 failures and 0 errors, then removed its successful
  isolated operator root.
- `git diff --check` passed for the production owner, regression, and this
  issue note.

The complete `bin/test --all` integration gate was deliberately not run in
this lane; the disjoint wedge-properties lane owns that one full gate after its
agent-test repair. Its result remains the integrated confirmation of the final
acceptance bullet below, not evidence attributed to this focused run.

## 2026-08-13 bounded-wait follow-up

The retained evidence from the later `204372af7` gate refutes the hypothesis
that this issue's `ProcessHandle.onExit` aggregation caused that gate's
300-second silence. In
`tmp/test-runs/run.BGxKo6/tmp/test-liveness/98503-1786591213826-threads.json`,
the coordinator was waiting for active worker RPC futures in
`run-task-pool!`; it was not in `stop-process-tree!`,
`await-process-tree-exit`, or a child-exit `CompletableFuture.get`.

The review did find two ways an exit failure remained incorrectly
representable. Worker shutdown first made an unbounded `worker-rpc! :stop`
round trip before it captured process-tree ownership, and the existing
ten-second exit backstop continued silently when forced termination succeeded.
Commit `0ef2750ef` captures every exact child completion before sending the
stop command, sends the command without awaiting a protocol response, and
awaits the captured `CompletableFuture.allOf`. If the ten-second backstop
fires, it now names every still-live process, force-reaps the tree, and throws
the typed `:seon.test.runner/process-tree-exit-backstop` refusal even when the
forced exits arrive. Cleanup therefore remains keyed to child completion while
the last-resort clock is always a failing bug report
(`src/seon/test/runner.clj:1182-1251`).

The same commit fixes the diagnostic blind spot. The suite liveness backstop
captures the coordinator and every descendant first, runs bounded `jcmd
Thread.dump_to_file -format=json` operations concurrently, and records every
dump path before stopping the suite (`src/seon/test/runner.clj:261-395`). The
regression starts a real worker JVM with a named virtual thread and proves both
the coordinator and worker dumps retain their virtual threads
(`test/seon/test_runner_test.clj:351-440`). A second regression makes a child
ignore graceful termination and proves the backstop names it, force-reaps it,
and fails (`test/seon/test_runner_test.clj:544-603`).

The 123-second clj-kondo-cache test was not spending that time awaiting child
exit. A virtual-thread-aware dump of focused worker PID 2833 retained at
`tmp/test-liveness/focused-worker-2833-threads.json` showed the worker blocked
reading a nested `bin/test` while that nested launcher copied ten complete
worker checkouts. The two structural nested-runner fixtures now install a fake
`getconf` reporting two logical processors, so each probe constructs the one
worker root its assertion needs. Production pool sizing is unchanged.

Follow-up verification:

- `bin/test seon.test-runner-test` ran 14 tests containing 144 assertions with
  0 failures and 0 errors. The cache regression completed in 39.5 seconds,
  down from 123.3 seconds in the retained gate.
- The one authorized `bin/test --all` at `0ef2750ef` did not produce a tally:
  it exited 124 at its declared 300-second silence backstop and retained
  `tmp/test-runs/run.rME4Ll`. This issue's cache regression completed in 44.4
  seconds, later tests continued, and the worker-root cleanup regressions were
  green before the distinct wedge.
- The revised backstop retained eleven virtual-thread-aware dumps: coordinator
  PID 7724 and all ten worker JVMs, plus diagnostic log
  `tmp/test-runs/run.rME4Ll/tmp/test-liveness/7724-1786593605865.log`.
  Six worker dumps identify the foreign boundary: six
  `seon.render.web-test` tests were blocked in `HttpClient.send` through
  `fetch` at `test/seon/render/web_test.clj:290` (`the-html-page-keeps-the-transcript-outside-the-agent-profile`,
  `the-feed-opener-is-a-sibling-of-the-morph-targets`,
  `an-agent-page-is-the-same-mechanism-as-root`,
  `an-unknown-route-is-an-honest-404`,
  `two-tabs-each-get-their-own-complete-paint`, and
  `each-agent-has-an-isolated-debug-route`). That protected render owner is the
  exact remaining integration boundary; this lane did not edit or rerun it.

## Acceptance

- The interrupted launcher publishes completion only after every writer under
  its worker root has stopped using that root.
- The existing regression passes in the worker pool and in a fresh isolated
  confirmation without retrying directory deletion on a clock.
- The schema-sensitive changed-files gate completes without retaining a failed
  operator root.
