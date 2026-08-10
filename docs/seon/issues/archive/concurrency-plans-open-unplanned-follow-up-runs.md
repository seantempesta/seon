---
type: issue
status: resolved
severity: friction
tags: [issue, testing, concurrency, runtime]
---

# Keep concurrency plans from opening provider-backed follow-up runs

## Problem

The long concurrency harness sends completion messages after its six-form
system plans. Those messages remain ordinary triggers, open follow-up runs
with no prepared plan, and reach the provider stub. Transcript and unanswered-
trigger assertions then count traffic the harness itself caused.

## Evidence

At clean commit `48eb25ab7`, this one class produced 52 failures:

- six unanswered completion triggers at line 395;
- 45 transcript message-set mismatches at line 440, each containing additional
  ordinal-5 completion messages; and
- one nonempty `model-calls` assertion at line 524, whose prompt names a
  completion message as the current instruction.

The complete handoff records the same known harness defect as “auto-reply
messages on completion caused stubbed follow-up episodes,” but no issue note
owned it. Evidence: `tmp/full-gate-2026-08-10b.log:1965-2306`.

## Owner

Suspected owner: the system-plan/concurrency test harness and the declared
trigger fact for system-authored plans. Do not suppress ordinary production
messages merely to satisfy the test.

## Acceptance

- Every scenario has an explicit terminal boundary that cannot open an
  unplanned provider-backed follow-up run.
- Transcript assertions derive their expected message set from the scenario's
  declared traffic.
- The provider stub receives zero calls in all six scenarios.

## Resolution

Resolved 2026-08-10 in the concurrency-independence harness. Production was
correct: a completed run answers the agent that sent its trigger. The harness
now creates each scenario agent, pauses its mailbox through Flow's own
pause-and-ping control protocol, opens triggerless caller-planned runs, and
starts their production folds together behind one latch. The plan's declared
ring messages remain durable unanswered work in the paused mailboxes; no
completion reply or provider-backed follow-up run can be constructed.

`bin/test seon.concurrency-independence-test` passed 2 tests and 2,942
assertions with zero failures and zero errors. The provider stub received zero
calls in every scenario, every transcript contained exactly its declared
incoming/outgoing ring pair, and the long test completed in 187.65 seconds
from its runner begin/end timestamps (the clean retained gate took 195.32
seconds at the same boundary).
