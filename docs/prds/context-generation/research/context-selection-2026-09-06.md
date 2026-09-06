---
type: research
status: active
tags: [context, evaluation]
---

# Ordered selection of existing evaluations

The existing contribution identity, position, and evaluation refs now also
represent an agent's selected context. `:seon.context.contribution/agent`
connects those contributions to their owner. Capture-only block name, hash,
and token fields are optional on the stored contribution; the prompt capture
input and pricing contracts keep their previous requirements.

`seon.context/selection` returns contribution maps ordered by position, with
refs to original evaluation entities. Evaluation ordinals already own form
order. No source text, result, digest, or token estimate is copied into a
selected contribution.

`seon.context/append-tx` is invoked as an ordinary Datahike transaction
function. It receives the mid-transaction database, verifies agent/run
ownership and terminal evaluations, then derives the next position. A
repeated identical contribution identity is idempotent; a conflicting use
refuses. Closed runs with error or interrupted evaluations can be selected;
open runs, absent evaluations, and unfinished evaluations cannot. It does
not require every authored form to have executed: a completed/wait
disposition legitimately leaves later forms unstarted.

## Dependency ledger and proof

- Datahike's `:db.fn/call` applies the function to its mid-transaction
  database in `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
- Existing Seon run transitions use the same mechanism in
  `src/seon/cluster/run.clj`; `run/terminal?` owns the terminal-evaluation
  predicate reused here. `seon.db/transact!` preserves the thrown typed
  refusal while the writer atomically aborts the transaction.
- An immutable live dependency probe on the owned Juniper database applied
  two transaction functions in one Datahike `with`: the second observed the
  first's pending write and produced value `2`; the live database retained
  no probe entity. No shared state was written.
- `test/seon/context_selection_test.clj` is the recurring authority proof:
  two selected runs receive positions 0 and 1 within one transaction,
  repetitions retain identity/position, agents remain isolated, original
  evaluation refs and count remain unchanged, and each refusal rolls back
  an earlier otherwise-valid append in the same transaction.
- `test/seon/context_capture_test.clj` retains the capture compatibility
  proof. The focused gate is the two namespaces together.

Focused gate `run.zAWF7a` passed on 2026-09-06: two tests, 115 assertions,
zero failures or errors. The final live selection/control proof remains
with integration; the earlier immutable dependency probe does not claim
that the newly declared selection schema was installed in the live cluster.

Web controls and rendering the selected evaluations are separate integration
work owned by the parent task. No second execution path or cache is added.
