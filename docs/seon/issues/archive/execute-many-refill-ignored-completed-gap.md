---
type: issue
status: archived
severity: friction
tags: [issue, database, flow]
---

# Execute-many refill ignored completed position gap

## Problem

The grouped-read refill window counted only executor jobs. When an early member
was slow, later members could complete and leave the job set, opening capacity
for more work even though their out-of-order results remained retained behind
the gap. A 64-member request could therefore admit and retain all later results
instead of honoring the intended per-database window.

## Resolution

Refill now counts every admitted position that has not yet been accepted in
vector order. That includes running jobs, yielded query callers, and completed
out-of-order values without adding another registry. Advancing the contiguous
result position releases capacity; a slow earlier position keeps the retained
suffix bounded by the existing read window.

## Evidence

The deterministic aggregate result-bound proof blocks position zero, completes
an oversized later position first, and verifies that positions beyond the
eight-member per-database window are never admitted before the limit decision.
