---
type: issue
status: resolved
severity: blocker
tags: [my.plan, done-when, derive-or-die, live-test, design]
created: 2026-09-15
---

# A plan step can be marked complete while its done-when is false (run 7 "reported" without sending a message)

## Observed (live run 7, default, 2026-09-15 15:36–15:40Z)

All seven steps carry `:my.plan.item/completed-tx`; the session ended with
`(my.agent/done)` at the budget. Step 7's done-when reads "A query finds
the :seon.message from Juniper to root containing the customer and both
verified totals". No message from Juniper exists:
`(seon.db/q '[:find [?c ...] :where [?m :seon.message/from ?j] [?j :seon.agent/id "juniper"] [?m :seon.message/content ?c]])` → `[]`.
The model, with one turn left, wrote "I'll complete steps 6 and 7 now
that I've verified the note records the new total, and close out the
plan" and called `complete!` on both. The plan says done; the task is not.

## Why

`:my.plan.item/done-when` is prose; completion is asserted by the model
through `my.plan/complete!`. Nothing checks the criterion. This is the
class the stewards PRD §8.4 names: completion must be DERIVED from facts.

## Wanted

- `:my.plan.item/done-query` (a query bound to `:my.plan.item/subject`)
  beside the prose; the loop evaluates it at every settlement and records
  `completed-tx` the first time it is true. `complete!` on a step whose
  done-query is false returns a flat error naming the query and what it
  found.
- The seven fixture steps carry done-queries; the loop proof asserts that
  a step cannot be completed by assertion alone.
- The problems panel reports "steps completed with a false done-query".

## Resolution — 2026-09-15

New optional `:my.plan.item/done-query` and `:my.plan.item/subject` facts
accrete beside the prose. Settlement evaluates open steps at the writer;
the first nonempty result or truthy scalar records `completed-tx` as that
transaction. `complete!` refuses a false query with the exact query and result.
Steps without a query retain their previous assertion behavior.

The canonical loop proof verifies false assertion followed by automatic
completion. All seven fixture queries completed in the live scratch proof,
with only the two intentional errors (premature completion and nested done).
[Landing and exact evidence](../../../prds/context-generation/research/run7-wave-landing-2026-09-15.md).
The broader problems-panel audit belongs to the stewards PRD; this bounded
wave fixes the completion writer and does not implement that panel.
