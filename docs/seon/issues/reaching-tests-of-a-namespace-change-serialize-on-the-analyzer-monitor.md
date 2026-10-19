---
type: issue
status: open
severity: performance
tags: [issue, test, analyzer, cost]
---

# The reaching tests of a namespace declaration change run one at a time on the analyzer's monitor

Observed 2026-10-19 (lane runtime-fix, thread dumps `tmp/runtime-fix/td-both-*.txt`, gitignored, of a check JVM at
candidate 3c354097d of 011edf42f's wire): an agent's `(in-ns 'seon.cluster.agent) (defn rr-probe ...)` made the
namespace row of `seon.cluster.agent` differ (an own `deftype` class written as an import; fixed by
`seon.sci.eval/own-class-imports`). Every closed member of the namespace then counted as changed, and the turn ran
the tests reaching them. Those test bodies call `seon.fn.analyzer/analyze` (clj-kondo) through interpreted lazy
members; one was RUNNABLE in it for 87 s while every other test body was BLOCKED on the same monitor, so the request
did not answer within the check's 120 s.

The trigger is fixed. The cost class stays: a declaration change of a hub namespace selects hundreds of reaching
tests, and each one that analyses waits for the one before it. Owner: `seon.fn.analyzer/analyze` (a monitor cannot be
timed or interrupted, per the lint) and the selection of tests for a namespace change. Proof to write: a namespace
declaration change of `seon.cluster.agent` on a branch answers its eval request within its bound.
