---
type: research
status: active
tags: [testing, reliability, flow]
---

# Flaky gate investigation — 2026-08-02

## Result

The confirmed Flow failure was a real control-observation race. The test sent
an asynchronous `pause` command and immediately submitted work. A delayed-proc
interleaving reproduced work starting before pause was observed in 53 of 100
trials without machine load. The repaired test uses Flow's ordered `ping-proc`
reply to establish that the launcher proc reports `:paused` before submission.

The sweep also removed one dead sleep branch; replaced both 10 ms child-file
polls with filesystem/pipe and process-exit events; removed the agent pause
test's 300 ms negative sleep and related polls; replaced a negative fanout-stop
clock with an observed channel take; restored leaked process-global schema
and instrumentation state; made a blocked source-publication fixture release
in `finally`; and replaced three wall-duration assertions with exact work or
completion evidence.

## Dependency ledger

| Boundary | Selected source | First-party owner and proof |
|---|---|---|
| `core.async.flow` lifecycle | core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`v1.10.874-alpha3`); `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108-155`, `flow/impl.clj:71-86,174-189,199-217,271-305` | `src/seon/flow.clj:285-360,381-440,479-521`; `test/seon/flow_test.clj` |
| Child-JVM readiness | JDK `Process.onExit`, `WatchService`, and `ENTRY_CREATE` | `test/seon/flow/kill_child.clj:15-43`; `test/seon/flow_test.clj` child-death falsifier |
| Recurring test gate | `clojure.test` through `bin/test:84-135` | focused namespace selections plus final bare `bin/test` |

The Flow SPI also requires control-channel priority at every `alts!!`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:32-34`).
The custom work launcher currently omits it (`src/seon/flow.clj:314-319`), an
already-filed production defect in
`docs/seon/issues/work-launcher-control-alts-lacks-priority.md`. The test must
still acknowledge pause because public `flow/pause` is asynchronous even after
that separate defect is repaired.

## Method and load bound

The primary proof was a deterministic channel interleaving, not machine load.
A one-thread `:io` executor held the launcher proc until `resume`, `pause`, and
a submission were queued. Releasing the proc then exposed the ready-channel
choice directly.

An initial stress run mistakenly used all 18 CPUs. It was stopped after the
owner corrected the bound; its 1 failure in 10 repetitions is retained only as
historical evidence and was not repeated. Every subsequent load check used one
test JVM and at most `stress-ng --cpu 6` for less than 60 seconds.

## Findings and fixes

### Pause was sent but not observed before submission

**Failure.**
`seon.flow-test/submission-time-limit-covers-the-pre-start-wait` expected
`:seon.flow/time-limit` and intermittently received `:seon.flow/completed`.

**Root cause.** `flow/pause` only puts `::flow/pause` on the control channel
and returns (`flow/impl.clj:71-75,184`). A proc changes status only after its
next channel selection and transition (`flow/impl.clj:199-217,271-305`). The
test submitted immediately after that unacknowledged put. Flow's `ping-proc`
is the existing synchronous observation: it queues a later control message and
returns the proc's live status (`flow/impl.clj:76-86,189,272-279`). Per-channel
ordering means a `:paused` ping reply acknowledges the earlier pause.

The custom launcher makes the window especially sharp: its
`alts!! [control completion submission]` lacks the SPI's required
`:priority true`, so when pause and submission are both ready it chooses
randomly. The controlled delayed-proc probe started work before observing pause
in 53/100 trials.

**Fix.** Commit `e5be92764` adds the pause-status acknowledgment before the
submission. It does not increase the 30 ms limit or weaken the outcome
assertion.

**Measured rate.** Before: 1/10 failures under the accidentally unbounded
stress run; controlled no-load interleaving: 53/100 early starts. After:
0/100 failures quiet (400 assertions), 0/100 with six-CPU pressure (400
assertions), and focused `seon.flow-test` 19 tests / 118 assertions / 0
failures / 0 errors.

### Child readiness was polled despite two observable events

**Risk.** The Flow child-death falsifier sampled `File.exists` and
`Process.isAlive` every 10 ms. The 20-second limit was a legitimate backstop
around a foreign JVM, but the polling cadence was load-bearing test machinery
for events the JDK publishes.

**Fix.** Commit `8259098f2` registers the parent directory with `WatchService`
before starting the child, completes one readiness future on either
`ENTRY_CREATE` or `Process.onExit`, and retains the 20-second loud backstop.
The same commit deletes an unused `work-message` arity whose dormant branch
contained the other `Thread/sleep` in `flow_test.clj`.

**Measured rate.** No intermittent failure was reproduced for the old poll;
this was a source-proven risky pattern, not a claimed observed flake. After:
the child-death test passed 10/10 repetitions (50 assertions), and the focused
Flow namespace passed 19 tests / 118 assertions.

### Agent pause mixed clocks with unobserved state

**Risk.** The agent pause falsifier polled provider entry, plan settlement,
pause status, and resumed quiescence; asserted that `pause` returned within a
wall duration; then slept 300 ms to infer that a paused mailbox consumed no
work. It passed its one quiet pre-change run, so this is classified as a
source-proven intermittent risk rather than an observed failure.

**Fix.** Commit `53996daa3` uses latches for provider entry/release, observes
pause-call completion through a future, acknowledges mailbox pause with the
same ordered pause-then-ping protocol, and uses Datahike transaction events for
plan and quiescence settlement. The later ping compares delivery counts only
after pause has been acknowledged, so no negative sleep remains.

**Measured rate.** Before: one focused run, 12 tests / 92 assertions / 0
failures. After: exact test 50/50, and focused namespace 12 tests / 93
assertions / 0 failures / 0 errors.

### Store flock readiness polled a child-created file

**Risk.** `the-flock-fences-across-processes` polled a ready file and child
liveness every 10 ms against a 30-second clock. The child and parent already
own exact output and exit events.

**Fix.** Commits `e5d0e4544` and `1e320cf41` make the child publish `held`
only after opening the store and writing readiness; the parent completes one
future from that pipe event or `Process.onExit`. Pipe failures complete the
future exceptionally instead of falling through to a timeout. Killed/refused
children are joined before fixture deletion, including cleanup paths.

**Measured rate.** Before: 0/20 failures. After: 0/20 failures; focused
namespace 14 tests / 46 assertions / 0 failures / 0 errors. This was a risky
polling construction, not a reproduced flake.

The two remaining test-tree sleeps are `Thread/sleep Long/MAX_VALUE` in the
store and Flow child JVMs. They are intentional: after publishing readiness,
the child must remain alive until the parent sends SIGKILL. A shorter timer
would replace the crash proof with a clock race.

### Fanout-stop dependency used elapsed noncompletion as evidence

**Risk.** `stopping-the-fanout-awaits-an-active-fault-commit` dereferenced the
stop future for one second and treated the timeout as proof that stop retained
its database dependency. That could pass merely because the future had not
started.

**Fix.** Commit `780c2e0ea` wraps the existing completion channel in a
test-local `ReadPort` that publishes when `stop-error-fanout!` actually enters
its completion take. Only after that event does the test assert the future is
unrealized; releasing the commit then leads to observed future completion.
There is no negative clock.

**Measured rate.** After: exact test 100/100 (200 assertions); focused Flow
namespace 19 tests / 118 assertions / 0 failures / 0 errors.

### Process-global schema registration leaked across test order

**Failure class.** `register!-flows-through-the-same-gate` registered
`:seon.schema.edn.gate/agent-honest` and never restored the registry. A direct
before/after probe deterministically found unequal state and the key still
present, so later tests depended on namespace order.

**Fix.** Commit `538164b27` snapshots exact entering schema state and restores
it in `finally` around both refusal and success assertions.

**Measured rate.** Before state equality: false; leaked key present: true.
After: exact test 20/20, state equality true, leaked key absent; focused
namespace 9 tests / 29 assertions / 0 failures / 0 errors.

### Blocked source publication could leak its future during teardown

**Risk.** The competing source-publication test released its blocked future
only after a successful second publication, and then used unbounded deref. An
exception in the second publication left the future parked and could wedge
fixture teardown.

**Fix.** Commit `45b1c6d97` releases the latch in `finally` and observes stale
publication completion through the shared named event backstop.

**Measured rate.** After: exact test 20/20 (140 assertions); focused namespace
8 tests / 47 assertions / 0 failures / 0 errors.

### Session-image installation asserted a machine-speed threshold

**Observed failure.** The session-image installation test required 200 SCI
forms to install in less than 50 ms. It failed 1/5 quiet repetitions at
50.609625 ms even though installation completed correctly.

**Root cause and fix.** Wall duration was being used as a proxy for bounded
installation work. Commit `5f5d4cba8` instead proves the exact dependency:
the same SCI context is returned, the unrestorable set is empty, exactly 200
forms are evaluated, exactly 200 vars are interned, and the endpoint values
are installed. No elapsed threshold remains.

**Measured rate.** Before: 1/5 failures quiet. After: focused namespace 8
tests / 44 assertions / 0 failures / 0 errors.

### Streaming offers asserted per-call wall duration

**Risk.** The turn streaming test required every buffered offer to finish in
less than 100 ms. The intended contract is nonblocking offer plus sliding-1
retention, both directly observable; scheduler delay could violate the clock
without violating either contract.

**Fix.** Commit `6559e7a15` observes producer completion, proves every offer
returned true, counts the emitted prefix exactly, and retains the existing
sliding-1 value assertion.

**Measured rate.** Before: 0/5 quiet failures, so this is source-proven risk.
After: focused namespace 46 tests / 262 assertions / 0 failures / 0 errors.

### Evaluation completion duplicated its event proof with elapsed time

**Risk.** The infinite-evaluation test already awaited a future and asserted
the exact `:seon.eval/time-limit` value, then also required the entire call to
finish in less than five seconds. The duration assertion added a scheduler and
GC oracle without strengthening the semantic proof.

**Fix.** Commit `e5a699624` retains the 15-second loud event backstop, the cut
instant, the `:time` disposition, and the flat time-limit value, and removes
only the redundant elapsed comparison.

**Measured rate.** After: exact test 20/20 with no failures.

### Instrumentation tests leaked process-global mutation

**Failure class.** Instrumentation tests mutate Malli's global function-schema
registry and instrument Var roots. A direct probe observed 446 instrumented
Vars before cleanup and zero afterward, proving deterministic cross-test state
loss rather than a hypothetical ordering concern.

**Fix.** Commit `5678cf7ce` restores exact entering instrumentation around the
evaluation acquisition test. Commit `edb6130fd` adds a per-test fixture that
snapshots and restores both the private Malli registry map and exact Var roots,
including exceptional exits; the total-removal test also has local `finally`
cleanup.

**Measured rate.** Before: 446 entering instrumented Vars, 0 after the probe.
After: 446/446 identical roots and registry after normal and injected-throw
paths; exact repetition 20/20; focused namespace 12 tests / 55 assertions / 0
failures / 0 errors.

### Concurrent-evaluation proof still calibrates work to a clock

**Risk left open.** The concurrent-evaluation test uses a fixed 100-million
iteration SCI spin to make one evaluation exceed 200 ms while expecting its
peer to finish within 10 seconds. It passed 5/5 quiet repetitions, but CPU
speed and contention determine the interleaving; neither evaluation publishes
the event that its `:interrupt-fn` has been installed.

**Disposition.** This needs a minimal production readiness event after arm
installation and task creation, followed by event-controlled test bodies. It
was not replaced with a longer spin or timeout. Issue
`docs/seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md`
records source evidence and acceptance criteria.

### Remaining Flow-test clocks

- The 30 ms synthetic interrupt at `test/seon/flow_test.clj` is the subject of
  an infinite-spin containment test; completion is observed from the report
  channel. It is a semantic time limit, not a readiness guess.
- The 100 ms Flow ping deliberately proves that busy procs cannot answer while
  a separate responsive observer can. The dependency exposes only a
  timeout-bounded partial ping, so the clock is the behavior under test.
- `Future.get`, latch waits, executor termination, WebSocket delivery, and
  process exit all use `test-support/event-backstop-seconds` after the test has
  named the event publisher. Those clocks fail loudly and do not establish
  success by elapsed time.
- Boot tests retain three explicit performance rulings: start-to-REPL under 10
  seconds, sibling fork under 2 seconds, and boot recovery under 10 seconds.
  These are performance assertions, not readiness inference. They are
  intentionally load-sensitive and must be evaluated on the requested quiet
  tree; they were not changed into semantic assertions or given larger bounds.

## Literal timeout and time-limit census

The requested regex finds exactly 44 literal sites in `test/`. They divide by
meaning rather than by numeric size:

| Class | Sites | Disposition |
|---|---:|---|
| AI/HTTP or drive transport limits | 12 | Legitimate bounds around remote or deliberately unreachable network state; several tests assert the resulting timeout/transport value. |
| SCI evaluation time limits | 15 | Product semantics under the one `:interrupt-fn`; infinite/long forms make timeout the subject, while finite forms use the value as a loud ceiling. |
| Flow compute, submission, or ping limits | 13 | Twelve are semantic containment/backstop tests; the one flaky pre-start assertion was repaired by acknowledging pause before its unchanged 30 ms limit. |
| Pure config/schema data | 4 | Numbers are compared or projected as data; no test waits on them. |

No literal was increased. The short infinite-spin SCI limits are behavior under
test; finite-form limits are loud backstops. AI literals are request/config
data or genuine remote/unreachable-network bounds.

## Shared state, cleanup, and order audit

- The installed work launcher is process-global, but audited fixtures stop it
  in `finally`. Agent graph tests disarm in `finally`; hot-reload Var mutation
  restores the original root before disarm.
- The schema registration leak was deterministic and is fixed in `538164b27`.
- The source fixture's blocked future cleanup was incomplete and is fixed in
  `45b1c6d97`.
- Datahike listeners introduced or touched by this wave are unregistered in
  `finally`; child processes are killed/joined before fixture deletion.
- Malli instrumentation state is now restored exactly even across exceptional
  exits. The remaining calibrated concurrent-evaluation case is filed rather
  than hidden behind a larger workload.

## Issue disposition

`docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md` remains
open with narrower evidence. Commit `53996daa3` removed the pause-related
polls, but `agent_test.clj` still has 12 `await-until` call sites. The hard core
observes idle-pass counts and armer/routing settlement. `arm!` currently
discards Flow's report channel and the closed armed-handle schema cannot carry
it. Closing the issue therefore needs coordinated production-handle plus schema
ownership; a hidden test channel is not an acceptable fix.

Two additional source-proven risks have dedicated notes:

- `docs/seon/issues/flow-monitor-test-resources-outlive-their-cleanup-scope.md`
  records a Flow monitor test that acquires and activates resources before its
  cleanup `try`, and whose `finally` can skip later cleanup after an earlier
  failure.
- `docs/seon/issues/concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md`
  records the missing post-arm readiness event described above.

## Verification

| Gate | Result |
|---|---|
| Controlled delayed-launcher interleaving, before | 53/100 submissions started before pause observation |
| Suspect test, after, quiet | 100 tests / 400 assertions / 0 failures / 0 errors |
| Suspect test, after, six-CPU bounded pressure | 100 tests / 400 assertions / 0 failures / 0 errors |
| Child-death test, after | 10 tests / 50 assertions / 0 failures / 0 errors |
| `bin/test seon.flow-test` | 19 tests / 118 assertions / 0 failures / 0 errors |
| Agent pause test, after | 50/50 tests / 0 failures / 0 errors |
| `bin/test seon.cluster.agent-test` | 12 tests / 93 assertions / 0 failures / 0 errors |
| Store flock test, after | 20/20 tests / 0 failures / 0 errors |
| `bin/test seon.cluster.store-test` | 14 tests / 46 assertions / 0 failures / 0 errors |
| Schema registration test, after | 20/20 tests; exact state restored |
| `bin/test seon.schema.edn-test` | 9 tests / 29 assertions / 0 failures / 0 errors |
| Source publication test, after | 20/20 tests / 0 failures / 0 errors |
| `bin/test seon.cluster.source-test` | 8 tests / 47 assertions / 0 failures / 0 errors |
| Session-image focused namespace | 8 tests / 44 assertions / 0 failures / 0 errors |
| Turn focused namespace | 46 tests / 262 assertions / 0 failures / 0 errors |
| Infinite-evaluation exact repetition | 20/20 tests / 0 failures / 0 errors |
| Instrumentation focused namespace | 12 tests / 55 assertions / 0 failures / 0 errors |
| Integrated turn/eval/instrumentation gate | 98 tests / 484 assertions / 0 failures / 0 errors |
| `bin/issues-index --check` | Pending |
| `bin/test` on quiet tree | Pending |
