---
type: issue
status: open
severity: high
tags: [issue, agent, bootstrap, context, render]
---

# Opening generator pushes undemanded candidates

## Problem

The live-pull opening generator emits every candidate in the distance-bounded
pull that becomes dependency-ready. It does not first select the episode's
action forms and restrict discovery to their explained-set closure. A richer
neighborhood can therefore lengthen the survey prefix even when the added
candidate is irrelevant to the action the episode teaches.

## Evidence

`src/seon/bootstrap.clj:135-166` renders direct candidates for the complete
acquisition order. Lines 168-190 render listing candidates for the complete
neighborhood, and lines 192-208 concatenate both collections before
`seon.render.walk/ordered-episode` establishes dependency order. Ordering
dependency-ready supply does not establish that a downstream action demanded
each supplied candidate.

The independent
[evolving-session review](../../prds/sci-execution-runtime/research/evolving-session-fable-review-2026-08-12.md)
connects this direction to the measured ablation result: retained action
demonstrations produced work, while discovery-heavy contexts produced repeated
surveys.

## Owner

`seon.bootstrap/pull-result` owns candidate selection. The episode's action arc
must define the demanded symbols before the fixed-point explanation walk.

## Acceptance

- An acquisition member unrelated to every action form adds no generated entry.
- Every discovery entry has a downstream demanded-symbol path to an action.
- The ordered episode remains deterministic when irrelevant facts accrete.
