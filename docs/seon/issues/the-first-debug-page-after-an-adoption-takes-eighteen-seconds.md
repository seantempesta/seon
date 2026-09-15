---
type: issue
status: open
severity: friction
tags: [issue, render, performance]
created: 2026-09-15
---

# The first debug page after an adoption takes eighteen seconds

## Problem

Default, 2026-09-15 22:05Z, right after the hook adopted `c395610db`:
three consecutive `GET /agent/juniper/debug` took **18.43 s, 0.195 s,
0.175 s**. The warm floor is now under 200 ms (debug-page-cost kills 1–2),
so the cold request is the whole cost: an adoption invalidates every
retained render and the first page re-renders the complete history (45
turns, 88 evaluations) through the render pair, plus re-acquires the
schema projection. The page-speed landing measured cold 2.4–3.3 s this
morning on a smaller population; it scales with history.

## Wanted

A cold page after adoption costs what the CHANGED renders cost, not the
whole history: retained render identity keyed on the evaluation's shown
text and the render pair's program identity survives an adoption that did
not change them (law 2.1: the retained value carries what it derives from).
Regression: after an adoption that changes an unrelated namespace, the
debug page re-renders zero evaluations. Owner: debug-page-cost lane after
kill 3.
