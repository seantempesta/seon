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

## Serial-worker exchange follow-up

Cold platform gates 8 and 9 exposed a consequence of the host-Var split in
`ea676d0af`. The split correctly stopped arming the canonical SCI ctx around a
host test body, but that arm had also been the host task's only aggregate
deadline. `serve-worker-commands!` called `run-task!` synchronously and could
write `:task-complete` only after it returned. A host task that outlived the
coordinator's exchange bound was therefore killed with no terminal worker
event.

The retained journals identify the first missing event, rather than the later
victims. In gate 9,
`tmp/test-runs/run.SMT0SG/workers/serial/logs/worker-dispatch.edn` ends with the
request for
`seon.cluster.source-test/existing-clusters-remain-on-their-chosen-source-commit`
at `2026-09-17T22:06:33.331267Z` and has no reply. In gate 8 the same test did
return after 268,407 ms; the following
`failed-and-stale-builds-preserve-the-published-head` exhausted its 290 s
exchange. The empty `elapsed-ms` lines for selection and store tests were then
written in under a millisecond by the coordinator from `:worker-retired`
results. They are the cascade after retirement, not malformed replies from
those tests.

The worker command loop now owns a single execution thread for its whole
lifetime. Every host or SCI task is submitted to that same thread, preserving
pooled-thread `ThreadLocal` carriage so another arm leak remains observable.
The command loop supervises it under the task's declared body bound, which is
inside the coordinator exchange bound. On expiry it cancels the execution and
publishes an attributed `:task-complete` result with numeric elapsed time; the
coordinator remains the process-level backstop for code that ignores
interruption. Normal host calls also pass the complete request to `run-vars!`,
preserving the ctx fixture input formerly conveyed by `eval/run-tests` without
arming that ctx.

The regression drives two real host tasks through
`serve-worker-commands!` as worker `serial`, proves both correlated
`:task-complete` events carry numeric elapsed time, proves both bodies ran on
the same execution thread, and then drives an over-bound task to the same
terminal event. Thus the class invariant is at the exchange boundary: every
worker tier completes the exchange, including when its body bound fires.

The requested `--paths` run refused in the shared tree because held caller
files were outside this lane's overlay. A HEAD-plus-lane scratch worktree also
had no published graph, so the authorized plain fast form was used there. Its
full two-namespace run reached 74 tests and 423 assertions; this lane's serial
exchange regression passed, while the run reported 15 pre-existing errors:
the HEAD platform manifest classifies two destructive fresh-operator tests as
platform members, and the remaining cache/launcher tests fail in
`dev_cache/test-inputs` while comparing mixed string and keyword keys. Those
Stage 1/cache bytes are the named foreign verification boundary. The
orchestrator still owns the cold platform proof of the serial-worker repair.

## Serial-worker exchange: verification and the facet declaration

This section completes the previous one from the same working tree after its
Codex lane stopped on an external usage limit. Its uncommitted runner bytes
were read and continued, never discarded; the Stage 1 selection hunks that
share `src/seon/test/runner.clj` were left uncommitted for their own lane.

### The first missing event, re-derived from the retained roots

The `END worker=serial elapsed-ms=` lines with an empty measurement are the
cascade, not the defect. `run-task!` (`src/seon/test/runner.clj:1588`) already
catches `Throwable` and always assocs `::task-elapsed-ms`, so a worker reply
cannot carry an empty one. The empty lines are the coordinator formatting
`:seon.test.runner/worker-retired` results for tasks it never dispatched.

The first failure in gate 9 is one exchange bound, not a malformed reply:

```text
post-reset-platform-9.log:1314 BEGIN worker=serial
  task=seon.cluster.source-test/existing-clusters-remain-on-their-chosen-source-commit
  bound=306s
post-reset-platform-9.log:1315 END   worker=serial elapsed-ms=  (22:11:39, 306 s later)
post-reset-platform-9.log:1488 :seon.error/kind :seon.test.runner/worker-exchange-bound
```

`tmp/test-runs/run.SMT0SG/workers/serial/logs/worker-dispatch.edn` ends with
that same exchange id `e2f31412-…` dispatched at `22:06:33.331267Z` and has no
reply. Every later `Worker exchange failed:` line in that log carries
`:seon.test.runner/worker-retired`, all dispatched within 20 ms of
`22:11:39.68`, through `seon.cluster.source-test` and on into
`seon.cluster.store-test`.

### The seam

`ea676d0af` was right to stop arming the canonical SCI ctx around a host test
body, but that arm had been the host task's ONLY aggregate deadline. After the
split, `serve-worker-commands!` called `run-task!` synchronously with no bound
of its own, so a slow host body was killed by the coordinator's exchange bound
with no terminal worker event — the bounded-execution law's half-dropped case
(AGENTS §2.3): removing the bound left the event, and the wait had no bound.

The repair puts the bound at the seam that admits the work. The worker command
loop owns one long-lived execution thread for its whole lifetime
(`worker-command-loop!`), so pooled-thread `ThreadLocal` carriage — the SCI arm
above all — stays exactly as observable as when `run-task!` ran in place, and
`bounded-worker-task!` supervises each submission under the task's declared body
bound. The bounds nest: `bounds/exchange-seconds long-ms 0` (270 s ordinary)
inside the coordinator's `task-exchange-bound-seconds`, which adds the measured
fixture priming (290 s, observed 306 s), inside the suite silence horizon. On
expiry the worker cancels the execution and publishes an attributed
`:task-complete` with numeric elapsed time and one error result per test symbol;
the coordinator remains the process-level backstop for code that ignores
interruption. A task that throws still reaches the worker's own handler: the
`ExecutionException` is unwrapped to its cause.

### The regression

`test/seon/test/runner_test.clj:24` now drives real host tasks through
`serve-worker-commands!` as worker `serial` and asserts the class invariant —
a task completes the exchange on every worker tier:

- two correlated `:task-complete` events, worker id `serial`, each with a
  `nat-int?` `::task-elapsed-ms` and a zero error count;
- both bodies on one execution thread (thread ids compared);
- an over-bound task reaching the same terminal event with
  `::worker-task-bound true` and a positive elapsed time;
- the worker's own bound strictly below `task-exchange-bound-seconds`, for an
  ordinary task and for a declared-long one — the arithmetic that decides
  whether the terminal event can be published at all.

### `seon.sci.kernel/failure-value` output facets

The wrapper derives its result permissions from the declared output union
(`src/seon/instrument.clj:583-616`, `:738-761`): `::declared` is the facet keys
reachable in the output form and `::base?` is whether `:seon.error/base` is
among them. `failure-value` declared only `:seon.error/value`, which is an
ordinary map, so `::base?` was false and EVERY base-shaped return was rejected
as `:seon.instrument/undeclared-error` — the reported arity/contract facet
errors in `an-instrumented-multi-arity-miss-reads-like-clojure` and
`agent-contracts-apply-on-acquire-and-cold-recovery`.

Per program-facts PRD §1q the output now enumerates its facets explicitly. This
boundary preserves the facet of a refusal it did not raise, so it is a generic
pass-through and lists the whole facet population: `:seon.error/value`,
`:seon.error/base`, and the 63 members of `seon.error/facet-keys` over the
packaged declarations, sorted, derived on 2026-09-18 — the same population the
db lane declared as `:seon.db/error-result` (`1695b43b2`). Keeping
`:seon.error/value` in the union is accretion: values that carry only kind and
message still validate.

### Verification and the foreign boundary

Fast iteration on the shared tree, plain form (the `--paths` overlay refused,
naming held caller files `src/seon/test.clj` and
`test/seon/cluster/source_test.clj`, which this lane does not own):

```text
bin/test-fast seon.test.runner-test seon.sci.kernel-arm-carriage-test
Ran 29 tests containing 161 assertions.
0 failures, 8 errors.
```

`a-cold-worker-does-not-arm-its-base-around-host-test-bodies`, carrying the new
serial-exchange assertions, PASSED. Every one of the eight errors is the same
foreign refusal, at `test/seon/test_support.clj:291`:

```text
Fixture setup was refused by :seon.test-support/database-base-unavailable:
Canonical fixture base construction failed: Initialization lookup refs do not resolve.
```

That refusal is `seon.cluster.clj:1285` (`transact-initialization!`): no pending
initialization row's lookup refs resolve. It hits every `with-database` test in
the namespace and is unrelated to this lane's bytes. The same eight names run
clean at HEAD plus only this lane's three owned diffs, in a throwaway worktree
(`git worktree add tmp/fable-wt HEAD`, `reference-code` linked): the first of
them, `a-committed-retraction-survives-the-restore-and-is-named-a-committed-change`,
errors in the shared tree and passes there. The shared tree's Stage 1 selection,
cache and source bytes are the named foreign verification boundary; the
canonical fixture base is currently broken for concurrent lanes in it.

`seon.test-runner-test` could not be included at all: `test/seon/test_runner_test.clj:48`
still refers to `dev-cache/digest-file!`, which a concurrent edit to
`dev_cache.clj` removed, so the namespace does not compile. That is the same
dependency the wrapper lane reported.

`clj-kondo` over the three owned files reports 0 errors and 6 pre-existing
warnings. `git diff --check` is clean.

The Stage 1 selection hunks in `src/seon/test/runner.clj` (`bulk-selection`
through `seon.test/select`, the `::unchanged?` platform suppression) were left
uncommitted for their own lane: this commit stages only the import, the
`run-vars!` request carriage, `bounded-worker-task!`, and the two command-loop
hunks.

### Cold gate: the serial worker completes every exchange

`SEON_TEST_ORCHESTRATOR=1 bin/test -- seon.cluster.store-test seon.test.selection-test`
at `f77fa320f`, run root `tmp/test-runs/run.8vac2X`. It selected `TIER platform
0 tests` and `TIER bulk 23 tests` and ran for eleven minutes before an external
TERM killed the launcher (not a gate refusal, not a worker fault). In every
serial exchange it completed:

```text
BEGIN worker=serial 4 tasks, bound=296s
END   worker=serial elapsed-ms=8677 task=…/an-in-process-refusal-never-drops-the-os-fence
END   worker=serial elapsed-ms=6545 task=…/the-flock-fences-across-processes
END   worker=serial elapsed-ms=923  task=…/a-failed-release-never-drops-the-fence
```

- `worker-retired`: **0** occurrences in the whole log.
- `worker-exchange-bound`: **0**.
- Task `END` lines with an EMPTY `elapsed-ms=`: **0**. (The single
  `elapsed-ms= ` match in the log is the `PREPARED cached base` line, a
  different message.)

That is the class invariant the repair is for: on the serial tier, every host
task published a terminal `:task-complete` carrying numeric elapsed time.
Compare gates 8 and 9, where the first exceeded exchange retired the serial
worker and 168 and 170 later tasks were reported failed unrun.

Two further cold attempts did not reach their workers, both before any worker
exists and both foreign:

1. `run.aEQ8ZR` refused at `dependency-cache-and-classpath`: `seon.test.cache`
   requires `seon.fs`, which another lane was committing at that moment
   (`fbb4a205b`), so the HEAD-plus-tracked-changes snapshot had the modified
   `src/seon/test/cache.clj` without its new dependency.
2. `run.5wvQ4Z` refused at `published-base`: "Test input preparation exceeded
   its execution bound" at its declared 320 s, with the machine under a
   concurrent republish from the reset investigation.

The orchestrator still owns the complete cold platform proof. It should be
taken on a quiet machine; nothing in this lane's bytes is implicated in either
refusal.
