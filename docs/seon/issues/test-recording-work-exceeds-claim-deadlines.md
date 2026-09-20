---
type: issue
status: open
severity: friction
tags: [issue, bounded-execution, test-fixture]
---

# Test recording work exceeds claim deadlines

Observed in armed fast run `9925b3f8cf7c`, 2026-09-23 assignment:
`seon.test.runner-test/no-double-execution` and
`platform-claims-and-original-bounds-govern-bulk` reach a claim after their
declared 20 s deadline. The latter carries deadline
`2026-09-20T21:42:12.073Z` and claimed-at `2026-09-20T21:42:13.046Z`;
`seon.test.runner/claim-member` correctly refuses
`:seon.test.runner/worker-exchange-bound` and `transacted!` reports it.
Raising the deadline is not a repair. Measure the admission, recording and
database transaction work before attributing the cost to file storage or
validation. The test-system fixture change does not establish that cause.

The same run recorded 7 failures / 3 errors. The retained observation
identifies the timing target, error-map propagation, stale refusal assertion
and these two deadline failures; the remaining failure/error attribution
must be obtained from that run's recorded members before claiming complete
classification. The run is red, never accepted as green.

Evidence and fixture measurements:
[test-system landing note](../../prds/steward-platform/research/test-system-fork-2026-09-23.md).
