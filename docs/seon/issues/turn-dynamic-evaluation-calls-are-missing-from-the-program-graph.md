---
type: issue
status: open
severity: friction
tags: [issue, test, program-graph, turn, wave/verification-audit]
---

# Dynamically resolved turn evaluation calls are absent from the program graph

## Problem

The graph cannot report two evaluation paths that the turn owner executes.

## Evidence — 2026-09-15

The backstop-and-misc canonical gate at `d4a85dbdc` plus its owned paths
finds the evaluator/preview census still red. `seon.turn/evaluate-sources`
calls `seon.sci.eval/evaluate-for-install` via `requiring-resolve`
(`src/seon/turn.clj:4351`); `system-turn` similarly resolves
`preview-sources` (`src/seon/turn.clj:2089`). The graph does not contain
either caller edge. Source existence does not prove indexed reachability.

The previous test additionally expected the older direct `evaluate` target.
It now names the current `evaluate-for-install` target while preserving the
positive edge assertions. No turn, SCI, renderer, or analyzer repair is included
in job 2. The selected-path snapshot excludes concurrent renderer edits.

## Owner

The turn/indexing integration owns these missing edges. The backstop lane's
job 2 owns census retirement and its fixtures, not the concurrent evaluation
integration. Its gate keeps this boundary visible.

## Acceptance

Record these calls at the existing indexing/declaration owner so the graph
answers the same reachability questions as execution. Keep the canonical
`seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path`
assertions; do not replace the missing facts with a test-only caller list or
treat an absent caller as health.

Exact gates and counts belong to the
[landing note](../../prds/context-generation/research/backstop-and-misc-landing-2026-09-15.md).
