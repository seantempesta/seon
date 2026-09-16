---
type: issue
status: open
severity: friction
tags: [issue, datahike, query, dependency]
---

# The query planner rejects a negation whose variable a recursive rule binds

Found 2026-09-16 while fixing
[`plan-derivation-refuses-with-an-unbound-rule-variable`](plan-derivation-refuses-with-an-unbound-rule-variable.md)
(evidence, with the reproducing data state and `d/explain` output:
[plan-derivation-refusal-2026-09-16](../../prds/steward-platform/research/plan-derivation-refusal-2026-09-16.md)).

A well-formed Datalog rule is refused by the IR planner once the database
grows, while the relational engine answers it at every size. The shape:

```clojure
[(open-work ?step)
 (descendant ?step ?leaf)          ; a RECURSIVE rule call binds ?leaf
 [?leaf :my.plan.item/id]
 (not-join [?leaf] [?leaf :my.plan.item/completed-tx _])
 (leaf ?leaf)]
```

Two mechanisms meet:

1. [`datahike.query.lower/lower` step 7](../../../reference-code/datahike/src/datahike/query/lower.cljc:1092)
   validates that a `:not` / `:not-join` op has at least one var bound by a
   prior op. Its per-op contribution set credits only `:entity-group`,
   `:pattern-scan` and `:function`; a `:recursive-rule` op credits
   **nothing**, although it binds its call args at run time.
2. [`datahike.query.plan/order-plan-ops`](../../../reference-code/datahike/src/datahike/query/plan.cljc:1524)
   orders ops by estimated cost and, when no op is runnable, selects the
   cheapest anyway (`(if (seq executable) executable scored)`).

So when the recursive rule is estimated cheaper than the scan that also
binds the variable, the rule is ordered first, credits no binding, and the
following negation is rejected:

```
Insufficient bindings: none of #{?leaf__auto__r4312084} is bound in …
```

Measured threshold in one JVM: the same query answers with 7
`:my.plan.item/id` datoms and refuses with 405. Wrapping the branch in
`or-join` moves the threshold (correct at 405, refusing at 1005); it does
not remove it.

`seon.plan` no longer has a rule of this shape — its frontier is derived in
Clojure — so nothing first-party depends on the refusal today. Any future
rule that negates a recursion-bound variable will hit it.

Acceptance: a `:recursive-rule` op credits its bound call args in the step-7
tracker (or the orderer refuses to select an op whose required vars are
unbound while a runnable op remains), with a regression in the fork covering
a recursive rule followed by a `not-join` over the variable it binds, at a
database size where the rule is the cheaper op.
