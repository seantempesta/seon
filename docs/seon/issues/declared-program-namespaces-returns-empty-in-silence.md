---
type: defect
status: open
severity: friction
tags: [testing, instrument, class/absence-as-health]
---

# `declared-program-namespaces` returns `[]` in silence

The test runner's worker loads the program before arming contracts
(`ccfaeb14d`) and refuses when it armed nothing. `verify-p1-p6-and-backlog`
(finding 9) found the input to that check unchecked: when the relative root
does not resolve, `declared-program-namespaces` returns `[]` silently, and
the zero-instrumented refusal cannot see it because the worker's own test
vars keep the count positive. Fix: an unresolvable root is a typed refusal;
the arming assertion compares against the program graph's namespace count,
not against zero.
