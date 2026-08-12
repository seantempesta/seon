---
type: research
status: active
tags: [research, render, database, flow]
---

# W2 change-flow acceptance evidence

## Boundary and authorities

W2 is implemented by `bc3dfe3fd`, `bdb7b8efc`, `b0a3713d3`, and
`291681222`, on Datahike fork `407e9328851`. Before design and implementation,
the complete [sealed PRD](../plan/self-generating-context-prd-2026-08-11.md),
the complete [incremental invalidation report](incremental-invalidation-design-2026-08-11.md),
and the complete `seon.cluster.wake` docstring law were read end to end.

The implementation has no eviction or compaction. Histories remain append-only
and unbounded under the overnight ruling.

## Method

Every behavioral verdict is event-driven. Context requests complete through
the render proc's promise channel. Listener routing is ordered by the returned
Datahike transaction report and absence is then observed with nonparking
`poll!`. No elapsed time decides correctness.

The unchanged counter wraps the five public `seon.db` read doors: `q`, `pull`,
`pull-many`, `entity`, and `datoms`. The semantic-equality trial counts the
root `pull` replay and compares the actual result of `append-history` with its
prior entry count. The new-message trial counts the same actual append delta.

Cold latency was measured after fixture and SCI request construction, with the
clock surrounding only `walk/root-acquisition`. It is informational; semantic
assertions, not time, are the test verdict.

## Outcomes

| Cost class | Observed result | Recurring proof |
|---|---:|---|
| Unchanged context acquisition | **0** `seon.db` reads | `unchanged-acquisition-performs-zero-database-door-reads` |
| Irrelevant commit | **0** render wakes | `render-wake-intersects-the-current-published-interest` |
| Relevant, semantically equal root read | **1** pull replay, **0** appended entries; consumed revision then selects no candidate | `relevant-semantically-equal-root-read-replays-once-and-advances` |
| One new message | **1** actually appended entry; prior bytes retained | `one-new-message-appends-exactly-one-entry` |
| Cold root pull | **1,795.387292 ms** versus **46.0 ms**, **39.0301585× slower** | `cold-root-pull-records-an-informational-latency-sample` plus isolated measurement |

The five behavioral proofs passed as focused direct `clojure.test/test-vars`
executions at `291681222`; the six preceding root-pull regressions also passed
in the repository runner before the final proof refinement. The repository
runner remains extremely slow for the prompt namespace. A virtual-thread-aware
dump taken during that run showed no `join-error-fanout!`, `stop!`, render, or
flow frame; the suspected foreign flow wedge was not observed.

## Interpretation

Correctness and incremental cost after the first acquisition meet W2's stated
classes. Cold acquisition does not beat the measured four-query floor: the
one pull is about 39× slower in this fixture. That performance defect remains
open in [cold root pull is slower than the four-query floor](../../../seon/issues/cold-root-pull-is-slower-than-the-four-query-floor.md).
