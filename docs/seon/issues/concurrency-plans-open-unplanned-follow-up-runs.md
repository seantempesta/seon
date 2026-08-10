---
type: issue
status: open
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
