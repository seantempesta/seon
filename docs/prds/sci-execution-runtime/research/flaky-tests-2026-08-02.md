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

The sweep also removed one dead sleep branch and replaced one 10 ms child-file
poll with filesystem-create and process-exit events. Remaining findings and the
final full-gate result are recorded below.

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
- `stopping-the-fanout-awaits-an-active-fault-commit` still uses a one-second
  negative `deref` to claim stop has not returned. Its completion assertion is
  valid, but the negative premise can pass when the stop future has not begun.
  Disposition is recorded in the issue section below.

## Literal timeout and time-limit census

The requested regex finds exactly 44 literal sites in `test/`. They divide by
meaning rather than by numeric size:

| Class | Sites | Disposition |
|---|---:|---|
| AI/HTTP or drive transport limits | 12 | Legitimate bounds around remote or deliberately unreachable network state; several tests assert the resulting timeout/transport value. |
| SCI evaluation time limits | 15 | Product semantics under the one `:interrupt-fn`; infinite/long forms make timeout the subject, while finite forms use the value as a loud ceiling. |
| Flow compute, submission, or ping limits | 13 | Twelve are semantic containment/backstop tests; the one flaky pre-start assertion was repaired by acknowledging pause before its unchanged 30 ms limit. |
| Pure config/schema data | 4 | Numbers are compared or projected as data; no test waits on them. |

No literal was increased. Pending lane evidence will expand this table with
the exact non-Flow sites that require a fix or issue.

## Shared state, cleanup, and order audit

Pending final integration of the agent, store, and cross-suite audits.

## Issue disposition

Pending final disposition of
`docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md` and any
new issue notes found by the cleanup/shared-state sweep.

## Verification

| Gate | Result |
|---|---|
| Controlled delayed-launcher interleaving, before | 53/100 submissions started before pause observation |
| Suspect test, after, quiet | 100 tests / 400 assertions / 0 failures / 0 errors |
| Suspect test, after, six-CPU bounded pressure | 100 tests / 400 assertions / 0 failures / 0 errors |
| Child-death test, after | 10 tests / 50 assertions / 0 failures / 0 errors |
| `bin/test seon.flow-test` | 19 tests / 118 assertions / 0 failures / 0 errors |
| `bin/issues-index --check` | Pending |
| `bin/test` on quiet tree | Pending |
