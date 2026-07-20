---
type: issue
status: open
severity: minor
tags: [issue, cljs, pod]
---

# Receipt test's stubbed rejection trips the core-fault gate report

## Problem

A full `bin/test-cljs` run (2026-07-20, 1284 tests, 5817 assertions, 0
failures) still prints a CORE-FAULT GATE block:
`SEON-CORE-FAULT {:seon.error/message "program row rejected"}` from
`[:seon.eval/record-eval] tx FAILED: program row rejected — source: (+ 1 2)`.
The fault originates in `test/seon/eval/receipt_test.cljs`, which
deliberately stubs a rejected program row to prove receipt behavior; the
recorded fault then surfaces in the suite-level gate report as if it were
a real core defect (exit code stays 0, so it is advisory noise).

## Owner

`test/seon/eval/receipt_test.cljs` (the stub should not record a
suite-visible core fault) or the core-fault gate accounting in the test
runner.

## Acceptance

A green full `bin/test-cljs` run prints no CORE-FAULT GATE block from
deliberately injected test faults.
