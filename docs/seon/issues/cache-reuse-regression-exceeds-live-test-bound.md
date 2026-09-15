---
type: issue
status: open
severity: friction
tags: [issue, test, wave/test-fixture]
---

# Cache reuse regression exceeds the live test bound

On 2026-09-15, the exact in-process call
`(seon.test/run #'seon.test-runner-test/consecutive-cache-invocations-reuse-the-published-base (seon.operator/connection "default"))`
returned 0 pass / 0 fail / 1 error, run entity 64675. The test never completed
within `:seon.test-support/event-backstop-seconds` (20,000 ms).

Without the gate's `seon.test.published-base` property, this regression first
populates a real file-backed base. The result identifies the outer completion
bound, not a failed cache-reuse assertion; the exact internal delay is unverified.
Its scratch `tmp/base-reuse-*` directory was absent after the run.

The orchestrator should verify the regression with its prepared base and
determine how direct in-process invocation should acquire that same input.
Do not treat the timeout as green or increase the bound without measuring the
missing phase. Full evidence is in the test-suite cost plan's batch-6 follow-up.
