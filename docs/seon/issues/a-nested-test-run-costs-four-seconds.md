---
type: issue
status: open
severity: friction
tags: [issue, test, performance]
---

# A nested test run costs about four seconds

Observed 2026-09-25 on `default` (run f83b8c3052fe, the
`seon.test-failure-facts-test` rows in
`tmp/orchestrator/verify-6ab6bdc1-results.txt.d/005.txt`). Fixtures that drive
a real `seon.test/run` inside a member (`run-probe`) pay about 4 s per nested
run. Members with two nested runs measured 8.0 s and 8.8 s, and one with three
measured 10.5 s. Each is over its 5,000 ms declared bound, so the harness
reports a bound failure. That happens even when every assertion passes.

A lane REPL probe (2026-09-25, lane fix-test-failure-facts-opus) took 4.9 s
for one `seon.test/run` of one host test. Most of the time went to member
acquisition (`seon.cluster.agent/acquire-context!`) and
`seon.schema/call-with-projection`. Selecting and running a single host test
should be sub-second. The owner should profile one nested `:named` run of one
member and remove the whole-program work from it. Raising the fixtures' bounds
is the wrong fix.
