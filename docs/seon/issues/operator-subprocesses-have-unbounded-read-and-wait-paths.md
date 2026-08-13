---
type: issue
status: open
severity: friction
tags: [issue, operator, test, wave/operator-child-lifecycle]
---

# Bound operator subprocess reads and waits

## Problem

Several foreign subprocess paths read all output and wait without a backstop.
A child that keeps stdout open or never exits can wedge the operator; two
direct dev tests repeat the same unbounded wait and can wedge the suite.

## Evidence

- `script/seon/fresh_operator.clj:746-756` uses unbounded `slurp` then
  `.waitFor` for offline roster discovery.
- `script/seon/fresh_operator.clj:1454-1470` launches the detached cluster path
  without a bounded child handoff.
- `script/seon/fresh_operator.clj:1846-1853` uses unbounded `slurp` and wait for
  initialization.
- `script/seon/fresh_operator.clj:2046-2063,2419-2439` also waits without a
  backstop for browser/tail helpers.
- `test/seon/dev/changed_test_test.clj:5-13` and
  `test/seon/dev/edit_feedback_test.clj:11-21` are the two direct test waits
  without explicit time bounds; the other subprocess tests use backstops.

## Owner

One operator child-process runner that owns concurrent output draining,
process completion, exact-process termination, and a loud foreign-process
deadline.

## Acceptance

Every spawned foreign process has one observable completion path and one loud
deadline backstop. Output cannot deadlock the wait, timeout terminates only the
captured process identity, and the two tests fail boundedly when their child
wedges.
