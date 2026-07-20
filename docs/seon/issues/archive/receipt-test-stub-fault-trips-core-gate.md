---
type: issue
status: resolved
severity: cleanup
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

## Resolution

Closed in commit `b109266e` (seon.log routing unit, 2026-07-20):
`failed-program-publication-does-not-commit-a-transcript` now wraps its
provoked `record-eval!` call in `seon.error/expecting-core-fault!` — the
designed test-side bracket that writes the fault datom but marks it
EXPECTED so the gate does not count it. Proof: full `bin/test-cljs`
2026-07-20 10:20 — `Ran 1284 tests containing 5817 assertions. 0
failures, 0 errors. PASS` with no CORE-FAULT GATE block
(`tmp/test-cljs-20260720-102037-1035.log`).
