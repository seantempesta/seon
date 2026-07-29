---
type: issue
status: resolved
severity: blocker
tags: [issue, agent-runtime, database]
---

# Form and receipt identities collide for problem assignment

## Problem

Frozen forms and their eval receipts both derive their identity string as
`(pr-str [run-id ordinal])`, under different unique identity attributes.
E3 resolves an agent-held `about` string across every installed identity
attribute and correctly refuses when it names multiple entities. Routing a red
form by either the form or receipt identity was therefore ambiguous on the live
path.

## Resolution

Every receipt now also carries a distinct `:seon.problems/id`, derived as
`"problem-"` plus the same run/ordinal tuple. This is an identity on the
receipt, not stored red/routed/settled state. E2-PRIME uses that string for the
E3 assignment, while redness and the seven settlement states remain pure
derivations over receipt, assignment, and declination facts.

The production turn regression evaluates a red form, resolves its problem
identity to the receipt, commits the assignment in the terminal transaction,
and proves the sibling form still runs.
