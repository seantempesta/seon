---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, flow]
---

# Link behavioral-test evidence to the eval that caused it

## Problem

The self-hosted test runner updated only the latest pass or failure timestamp
on each `:seon.test` entity. `seon.eval/eval-form-entry!` then discarded the
runner result. A generation plan could not prove that a particular eval passed
its behavioral tests: a newer or older global timestamp was not evidence for
that eval, turn, run, or assignment message.

## Owner

`seon.test.runner` owns the `cljs.test` event capture, selected tests, summary,
and durable surfaced projection. `seon.eval` supplies the already-committed
eval ID when a newly defined test triggers that runner.

## Resolution

The runner now writes its native `test`, `pass`, `fail`, and `error` summary
counters plus refs to the selected `:seon.test` entities directly onto the
causing `:seon.eval` entity. The eval is already a component of its turn, and
the turn already connects to its run, so callers can follow the existing
turn/run/message/plan connections. No test-run entity, timestamp join, or
generation-specific result schema was added.

## Verification

- `seon.test.runner-test` proves one transaction contains the exact eval ID,
  selected test ref, and native `cljs.test` summary.
- `seon.eval` passes the admitted eval ID to the automatic runner only after
  program publication succeeds.
- The affected changed-test gate passed 610 tests and 2,791 assertions with no
  failures or errors.
