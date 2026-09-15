# The namespace steward platform — program entry (opened 2026-09-15)

**Mandate (owner, 2026-09-15):** stop guessing at tasks; make it easy to
generate real tasks that improve our own system, complete by definition:
the data model first, then the functions that generate and store the data,
then the context rendered for a task, then the function (tests) that
decides success — and, when an agent succeeds, persist the result to disk
as program files. The system builds itself, in batches, across every
namespace with a steward.

**Status: ideas only. Nothing in this folder is approved or a final
design.** The owner will form the plan of attack from these pieces.

## Reading order

1. [ideas/stewards-self-improving-2026-09-15.md](ideas/stewards-self-improving-2026-09-15.md)
   — the running draft from the 2026-09-15 design dialogue: render inputs
   (§0), the namespace picture (§1), metrics as queries (§2), plans from
   state (§3), the batch (§4), the inside-out data questions (§8), the
   withdrawn problem family (§9), the synthesis from the audits (§10), and
   the task as stored data with deftests as success (§11).
2. [research/data-audit-a-2026-09-15.md](research/data-audit-a-2026-09-15.md)
   — program side: tests, faults, contracts, missing functions, examples,
   dead code; test-run provenance; what exists for definitions → files.
3. [research/data-audit-b-2026-09-15.md](research/data-audit-b-2026-09-15.md)
   — session side: messages, plan done-whens, stalls, elisions, render
   cost; what pairs actually receive; the call ledger question; the MVP.
4. [plan/unsettled.md](plan/unsettled.md) — the working edge once a plan exists.

## Inherited facts (from the context-generation program, closing)

- The turn loop, the additive context, the debug ledger and problems
  panel, the render pairs, the stop dial, derived plan completion
  (`:my.plan.item/done-query`), direct message sends, and the contract
  guards all landed there; runs 2–8 and the model's own accounts are in
  `docs/prds/context-generation/research/` (explain_probe_run*.edn).
- In flight at hand-off: test-provenance (`:seon.test/run`, results on
  `:current-src`, `seon.test/verified?`), schema-audit (permissive
  contracts, one refusal grammar), page-speed-and-estimate.

## Vocabulary already settled

steward (`:seon.ns/steward`), plan item (`my.plan.item`), render pair,
detector = a contracted query function, success = deftests read through
`seon.test/verified?`.
