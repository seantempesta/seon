---
type: issue
status: open
severity: defect
tags: [issue, database, datahike]
created: 2026-09-24
---

# A `not` predicate loses a variable bound at a pattern's transaction position

## Problem

Consider a `not` whose predicate compares a variable that the outer query bound at a
datom pattern's transaction position (`[?o :attr _ ?tx]`). Datahike treats that
predicate as unsatisfied, so the negation removes nothing. `not-join` with the same
variables named explicitly removes the rows correctly.

This is probably the same class as
[bound-transaction-input-selects-an-older-turn](bound-transaction-input-selects-an-older-turn.md):
a transaction-position variable does not constrain the clauses that follow it. That
note's root cause is not yet localized.

## Evidence

Setup:
- Datahike gitlink `620721d6`.
- Snapshot root `tmp/errors-on-their-branch/snap`, 2026-09-24.
- One signature, resolved through `d/db-with` (no write).
- Its resolution tx is `536870961`; its occurrence's `last-at` datom tx is `536870947`.

```clojure
base '[:find ?e ?tx :where [?o :seon.error.occurrence/last-at _ ?tx]
                           [?e :seon.error/occurrences ?o]]
(not      [?e :seon.error/resolved-tx ?r] [(>= ?r ?tx)])   ; => #{[42329 536870947]}  (wrong)
(not-join [?e ?tx] [?e :seon.error/resolved-tx ?r] [(>= ?r ?tx)]) ; => #{}  (right)
(not [?e :seon.error/resolved-tx ?r] [(>= ?r 0)])          ; constant: excludes correctly
```

`reference-code/datahike/src/datahike/query.cljc:2243-2256` joins every relation into
the negation context. The loss is therefore upstream of that step, probably in planning
or in how the relation carrying `?tx` is formed. This has not been localized.

## Workaround in use

`seon.error/occurred-since` (`src/seon/error.clj`) uses `not-join [?error ?tx]`, with a
comment.

## Fix

Localize the cause in the Datahike fork. Add a Datahike regression for this form. Then
the explicit `not-join` can be removed.
