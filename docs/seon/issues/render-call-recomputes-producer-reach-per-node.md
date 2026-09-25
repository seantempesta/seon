---
type: issue
status: open
severity: performance
created: 2026-09-25
tags: [issue, render, one-render-path]
---

# render-call recomputes the producer's call reach per node

`render-call`'s wrapper costs 3.5× the render itself: it recomputes the producer's call reach (~4 ms) for every node. Reach is a function of the producer's definition digest: memoize by it.

Evidence: docs/research/agent-platform/render-graph-research-2026-09-25.md §8 (research-render-graph-opus, default pid 59566).
