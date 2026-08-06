---
type: prd
status: active
tags: [prd, evaluation, bootstrap]
---

# The grader-repair wave — spec (2026-08-05)

One bounded lane repairing the three experiment-measurement defects so
the bootstrap A/B matrix can be trusted, then one rerun. Owner-ruled:
parked until the current implementation wave's gate work quiets, then
dispatched. Read whole before implementing:
[o4-delegation-diagnosis-2026-08-05.md](../research/o4-delegation-diagnosis-2026-08-05.md),
the P9 section of
[state-of-the-program-2026-08-05.md](state-of-the-program-2026-08-05.md),
and [bootstrap-baseline-2026-08-04.md](../research/bootstrap-baseline-2026-08-04.md).

## Unit 1 — O4 grades the causal episode, not one run

`seon.eval.drive/terminal-state` scopes grading to the initiating run,
which `my.run/wait` correctly CLOSES while the peer works — so correct
delegation graded 0/10. Fix per the diagnosis report's ruled shape: the
terminal condition is the CAUSAL EPISODE — the transitive closure of
runs caused by the trigger message (tx-meta caused-by walks already
exist for chain bounds), settled when no caused run remains open and
the initiating agent's continuation run has closed. Grade P4c/P4d over
that closure. The issue
[bootstrap-o4-stops-before-causal-delegation-settles.md](../../../seon/issues/bootstrap-o4-stops-before-causal-delegation-settles.md)
carries acceptance criteria. Regression: the diagnosis lane's live
five-hop scenario (send → peer wake → durable fn → reply → continuation
→ result) replayed as a fixture must grade success.

## Unit 2 — O5's predicate targets a deleted refusal

O5 still asserts `:seon.schema/open-argument-map` refusal — deleted by
the open-maps ruling (#48), so 0/10 is meaningless. Repoint the
predicate at the SURVIVING refusal class the bootstrap now teaches (the
`:any` rule per the #48 enforcement addendum). Verify by one manual
episode transcript before trusting the matrix.

## Unit 3 — O1 inversion replication

O1 scored 8/10 taught vs 10/10 untaught — the weakly-held-priors
prediction (teaching hurts where the model is competent), unreplicated.
Replicate exactly: same arms, 20 runs, current tree, digests keying the
grades. Report the replication verdict as data in the research doc —
no bootstrap redesign rides this lane.

## Unit 4 — one matrix rerun

With 1–3 landed: rerun the full O1–O5 matrix once (~$0.50, 50/arm),
against the current published bootstrap. Deliverable: the new table
beside the old in a dated research doc, with the grader fixes named so
the before/after is honest. The O1 replication result plus this matrix
are the inputs to the owner's next bootstrap-design conversation —
that conversation is explicitly NOT this lane's scope.

Owned paths: `src/seon/eval/drive.clj`, the grader/predicate files,
`evals/` harness entries, fixtures/tests, the research doc. The
experiment spend is pre-authorized at these sizes.
