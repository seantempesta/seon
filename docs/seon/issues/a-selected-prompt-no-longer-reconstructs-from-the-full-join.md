---
type: issue
status: open
severity: friction
tags: [issue, render, prompt, turn, capture]
created: 2026-09-17
---

# A selected prompt no longer reconstructs from the acquired full join

## Problem

`seon.render/captured-history` (`src/seon/render.clj:1548-1560`) checks that a
turn's saved `:seon.context.capture/prompt` reconstructs from re-acquired
evaluations by comparing it with `(:seon.cluster.prompt/text acquired)` — the
plain join of EVERY acquired history unit.

Since S11 the prompt is no longer that join: `seon.cluster.prompt/select`
chooses the newest whole units the prompt's own
`:seon.config.ai/prompt-token-budget` admits and `compose` joins those, with
one elision value for the rest
(`src/seon/cluster/prompt.clj`,
[S11 landing note](../../prds/steward-platform/research/composable-history-s11-2026-09-17.md)).

So whenever selection actually drops a unit, a turn-scoped re-acquisition
compares a SELECTED capture against an UNSELECTED join and reports
`:seon.render/capture-mismatch` — a false alarm about saved evaluations that
reconstruct perfectly well.

## Why it is inert today

The shipped budget is 1,000,000 tokens (`config/default.edn:387`) and
Juniper's whole history on `default` is 10,612 characters ≈ 3,317 tokens, so
nothing is ever dropped and the two strings are identical (measured
2026-09-17). The defect arms itself the first time the budget is lowered to a
value a real history can exceed — which is exactly what Decision 7 intends.

## Wanted

The reconstruction check compares the capture against the text the same budget
would compose, not against the full join: `captured-history` takes (or derives)
the selection the prompt made, or the check moves to the one caller that knows
the budget. Regression: a turn captured under a budget that dropped units
re-acquires without a mismatch, and a genuinely divergent capture still
refuses.

`src/seon/render.clj` was outside the S11 lane's owned paths, so the lane
recorded the boundary rather than changing the check.
