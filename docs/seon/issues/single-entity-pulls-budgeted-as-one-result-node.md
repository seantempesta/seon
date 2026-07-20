---
type: issue
status: open
tags: [database, issue, agent]
severity: friction
---

# Single-entity pulls budgeted as one result node

## Problem

`my.plan` assigned `:datahike.resource/max-results 1` to a pull of one plan
entity. Datahike counts every pulled attribute and ref node, not entities, so
ordinary multi-attribute plan rows always exceeded the budget. The status
function then returned its proper error value through an output schema that
accepted only success, obscuring the database evidence behind a Malli error.

## Evidence

After a canonical pod restart, agent `plain-chefs-do` called
`(my.plan/status {:my.plan/id "yy9b6iocki7j"})`. The first member of
`acquire-status` failed with `datahike query-results budget exceeded`; the other
three bounded queries succeeded. A direct narrow pull returned the step as
`:done`.

Datahike's `pull_api.cljc` calls `charge-result-node!` for pulled attributes,
defaults, recursion nodes, and entity IDs. `resource.cljc` compares that node
counter to `max-results`. The value therefore cannot mean “number of requested
entities.” Similar literal-one pull budgets in database restore and web brand
acquisition need their own focused verification before this issue is archived.

## Owner

`my.plan/pull-member` owns the bounded plan-row pull. It permits realistic
attribute/ref-node work while retaining the existing work and result-weight
ceilings. `:my.plan/status-response` owns both the derived success value and
the existing `my.plan.internal/fail` value.

## Acceptance

- Focused plan proof validates the node budget and both status response rails.
- The real post-restart agent reads step `yy9b6iocki7j` successfully.
- Literal-one single-entity pull budgets in database restore and web brand
  acquisition are verified and corrected where they carry the same semantics.
