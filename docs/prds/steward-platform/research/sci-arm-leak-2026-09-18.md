---
type: research
status: landed
created: 2026-09-18
tags: [sci, kernel, arm, bounded-execution]
---

# SCI arm leak

## Result

The leak was the instrumented return boundary of `seon.sci.kernel/arm`, not
SCI's `interrupt!`, a virtual-thread inheritance, or the deadline task.
`own-arm` installed the arm in the process guard's `ThreadLocal` before
returning (`src/seon/sci/kernel.clj`, previous lines 242-262). The instrumented
Var could then fail while compiling or checking the returned map. In that
case the caller never received `:seon.sci.kernel/stop!`, and the caller's
`finally` could not release the already-installed arm.

The evaluation entrance made the gap explicit: it called the instrumented
`kernel/arm` and only then stored the returned value in `arm-state`
(`src/seon/sci/eval.clj`, previous line 2700). Its `finally` could therefore
observe `nil` after a post-return contract failure even though the thread was
armed.

## Evidence

The serial-worker order in
`tmp/orchestrator/gate-results/predicate-revert-gate-2.log` is:

- `my.plan-test/a-second-agent-renders-through-the-same-defaults` begins at
  line 859. It performs four SCI evaluations at
  `test/my/plan_test.clj:461-466`; its empty plan values and map in place of
  rendered text are reported at log lines 1022-1033.
- The next direct evaluations,
  `seon.cluster.message-test/inbox-has-one-request-map-arity-and-supplied-defaults`
  and `message-ai-source-evaluates-through-the-agent-database`, begin at log
  lines 919 and 923. Both report `already-armed`; the latter carries the
  unarmed diagnostic record (`allocated-bytes -1`, `duration-ms 0`,
  `fn-entries 0`) at lines 1204-1214, proving refusal before evaluation work.
- The same worker later reaches the independent deadline regression at line
  957 and refuses its new ctx at `kernel.clj:310` (lines 1216-1225).

This order falsifies source publication as a necessary cause. The earlier
`post-reset-platform-6-wt.log` shows the same persistence: the last source
test ends at line 1306, unrelated store and declaration tests run next, and
the first direct arm in `seon.env-test` refuses at lines 1347-1348 and
1866-1888. The common mechanism is an instrumented `arm` returning across a
fallible output boundary.

Dependency grounding:

- `reference-code/sci/doc/interrupt.md:5-39` defines `:interrupt-fn` and the
  wall-clock `time-limit` pattern.
- `reference-code/sci/src/sci/interrupt.cljc:31-42` defines `interrupt!` as
  the private-marker exception interpreted code cannot catch.
- SCI supplies the interrupt event; Seon's kernel owns the arm's dynamic
  extent and must release it on every host exit path.

The Seon MCP runtime tools were unavailable in this lane. `bin/seon status`
showed `default` alive; the lane did not issue an operator command against it.

## Fix

`seon.sci.kernel/with-arm` now owns acquisition, callback execution, and
release in one `try`/`finally` (`src/seon/sci/kernel.clj:350-373`). Its
instrumented input checks happen before acquisition and its output checks
happen only after release, so no wrapper exit can strand thread state.

All production `kernel/arm` callers moved to that owner:

- form evaluation and selected-test execution in
  `src/seon/sci/eval.clj:2696-2896,3005-3008`;
- named invocation in `src/seon/sci/kernel.clj:612-687`;
- issue-test execution in `src/seon/plan.clj:664-668`.

The low-level `arm` remains for explicit arm-value probes. A foreign-arm
refusal now reports the existing arm id, owner thread, context and interpreter
ids, bounded arming stack, and requested context ids
(`src/seon/sci/kernel.clj:211-321`).

`test/seon/sci/kernel_arm_carriage_test.clj` now proves ordinary failure,
explicit `interrupt!`, and `time-limit` exits all permit a different ctx to
arm immediately on the same worker thread. It also proves the diagnostic
contains the existing arm and arming site. The existing instrumented deadline
regression remains in `test/seon/instrument_test.clj` and was not edited
because that path was held by another lane.

## Verification

The requested `--paths` run refused its incomplete overlay and named held
caller files, including `src/seon/test.clj` and
`test/seon/instrument_test.clj`. Per the assignment, verification used one
plain fast worker over all four namespaces:

```text
bin/test-fast seon.sci.kernel-arm-carriage-test seon.cluster.message-test seon.instrument-test seon.env-test
Ran 61 tests containing 320 assertions.
0 failures, 0 errors.
```

The focused first pass was also green: 6 tests, 27 assertions, 0 failures,
0 errors. The orchestrator still owns the cold gate and platform proof.

Implementation delta before this note: 156 insertions and 50 deletions across
`src/seon/sci/kernel.clj`, `src/seon/sci/eval.clj`, `src/seon/plan.clj`, and
`test/seon/sci/kernel_arm_carriage_test.clj`.

## Cold-worker follow-up

The cold gate over `0a58c769d` found a second seam that the fast runner does
not exercise: `seon.test.runner/run-task!` sent every resolved test Var through
`seon.sci.eval/run-tests`. That function correctly scoped a canonical-base arm
with `kernel/with-arm`, but core tests resolve to JVM Vars. Consequently the
arm's dynamic extent deliberately covered the whole host test body. Each task
created a fresh arm for the same canonical interpreter, and any test evaluating
an independent ctx met that foreign arm before doing work.

The retained worker evidence is
`tmp/test-runs/run.2V4cJZ/workers/pool-1/logs/worker-stderr.log`: the worker
armed contracts before canonical-base construction and again after loading the
four requested namespaces. The gate then reported nine distinct existing arm
ids, all for interpreter `846133226`. Distinct ids falsify one arm surviving
between tasks; the shared interpreter identifies the canonical fixture base,
and `run-task!`'s unconditional call to `seon.sci.eval/run-tests` explains why
that base was armed anew around each host body. The original diagnostic logged
only its first bounded frame, `new_armed`; the refusal now renders the complete
bounded arming stack while retaining the structured stack in ex-data.

`run-resolved-tests!` now runs an all-host task directly through `run-vars!`
and reserves the SCI arm for an all-SCI task. A mixed task refuses because one
namespace fixture cannot honestly have two arm boundaries. The regression
performs the worker's contract initialization, uses its canonical base, runs a
host test body that arms an independent ctx, and proves another independent arm
succeeds on the same pooled thread after the body.

The requested `--paths` verification refused because the shared tree's held
Stage 1 work also changes `src/seon/test.clj` and
`test/seon/cluster/source_test.clj`, both callers the overlay graph required.
The authorized plain fast form ran the final bytes in one JVM:

```text
bin/test-fast seon.test.runner-test seon.sci.kernel-arm-carriage-test seon.cluster.message-test
Ran 49 tests containing 259 assertions.
0 failures, 0 errors.
```

The foreign verification boundary is that held Stage 1 selection work in
`src/seon/test/runner.clj` remained uncommitted alongside this lane's two
localized runner hunks; it was neither edited nor included in this landing.
