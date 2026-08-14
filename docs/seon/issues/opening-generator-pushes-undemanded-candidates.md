---
type: issue
status: open
severity: friction
tags: [issue, agent, render, wave/prefix-drift-bootstrap]
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

## Scheduling note — 2026-08-12

Skipped by the evolving-session defect-clear wave because
`src/seon/bootstrap.clj` is held by the prefix-drift lane. Its demand-first
selection contract must be implemented and falsified at that one owner after
the lane releases the file; this wave will not add filtering at a downstream
render owner.

## Acceptance

- An acquisition member unrelated to every action form adds no generated entry.
- Every discovery entry has a downstream demanded-symbol path to an action.
- The ordered episode remains deterministic when irrelevant facts accrete.

## Evidence — 2026-08-13 live-pull attribution

The isolated full-publication probe in
[the dated attribution report](../../prds/context-generation/research/live-pull-attribution-2026-08-13.md)
expanded 37 direct and 37 listing members into 78 candidates. Cold direct and
listing expansion consumed 88 ms and 39 ms respectively, while the enclosing
database acquisition consumed 3,188 ms and Datahike `pull-spec` alone consumed
3,045 ms.

Demand-first selection remains the correct owner-level fix for undemanded
opening content, but it is not the dominant live-pull performance fix: the
schema-wide database pull has already paid its cost before
`seon.bootstrap/pull-result` filters candidates. The issue remains open for
the demanded-symbol closure contract; performance claims should distinguish
that downstream candidate reduction from Datahike pull execution.
