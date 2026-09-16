---
type: research
status: measured in the default JVM (pid 95853); the three regressions await the cold gate
created: 2026-09-16
tags: [research, steward, plan, datahike, query, render, measurement]
---

# The plan derivation refused because the query planner outran its binder (2026-09-16)

Lane: `plan-derivation`. Subject: the blocker
[`plan-derivation-refuses-with-an-unbound-rule-variable`](../../../seon/issues/plan-derivation-refuses-with-an-unbound-rule-variable.md),
filed by the issue-context trials lane (evidence:
[issue-context-trials-2026-09-16](issue-context-trials-2026-09-16.md)).

## 1. The reproducing data state, exactly

The refusal is neither cluster-specific nor plan-shape-specific: it is a
**size** threshold on `:my.plan.item/id`. Reproduced in the default JVM
against an in-memory Datahike database carrying only the plan attributes:

| plan steps in the database (`:my.plan.item/id` datoms) | `ready-ids-query` under the old rules |
|---|---|
| 7 (default's own facts, both agents) | answers |
| 405 (one agent with 200 parents × 1 child, plus the small plans) | `Insufficient bindings: none of #{?leaf__auto__r4312084} is bound …` |
| 1005 (adding 150 four-deep chains) | refuses for every agent, `d/explain` itself refuses |

Verbatim refusal at 405 steps:

```clojure
{:error :query/where
 :form (not-join [?leaf__auto__r4312084]
                 [?leaf__auto__r4312084 :my.plan.item/completed-tx _])}
```

That matches the trials cluster's line byte for byte apart from the gensym
suffix, and explains why `default` (7 steps) answered `:ok` while a freshly
forked cluster carrying a real issue-family plan did not. Nothing about
`root`'s plan was special; the estimate is global to the attribute.

## 2. Why the engine refuses — the planner, not the query

The query is well formed and the relational engine answers it. The refusal
comes from the **IR planner's NOT-binding validation**, after its
**cost-based ordering**:

- [`datahike.query.lower/lower` step 7](../../../../reference-code/datahike/src/datahike/query/lower.cljc:1092)
  walks the ordered ops and raises when a `:not` / `:not-join` op has none
  of its vars in `vars-so-far`. Its per-op contribution set credits only
  `:entity-group`, `:pattern-scan` and `:function` — a `:recursive-rule` op
  contributes **nothing**, even though it binds its call args at run time.
- [`datahike.query.plan/order-plan-ops`](../../../../reference-code/datahike/src/datahike/query/plan.cljc:1524)
  orders ops by estimated cost, and when nothing is runnable it picks the
  cheapest anyway (`(if (seq executable) executable scored)`).

`d/explain` at 7 steps shows the benign order for `open-work` branch 2 —
`SCAN [?leaf :my.plan.item/id] (est. 7 rows)`, then the two `NOT-JOIN`s,
then `RECURSIVE-RULE descendant` last. Once the `:my.plan.item/id` scan
estimate grows past the recursive rule's, the rule is ordered first, credits
no vars, and the `NOT-JOIN #{?leaf}` that follows is rejected.

So any rule body that negates a variable bound **only** by a recursive rule
call is at the mercy of the database's size. The old `seon.plan/rules` had
exactly that shape:

```clojure
[(open-work ?step)
 (descendant ?step ?leaf)          ; the only binder of ?leaf: a recursive rule
 [?leaf :my.plan.item/id]
 (not-join [?leaf] [?leaf :my.plan.item/completed-tx _])
 (leaf ?leaf)]
```

Two reformulations were measured and **both refuted**:

1. recursing on `open-work` itself through a pattern-bound child — never
   refuses, but returns nothing when called from inside `(not …)` /
   `blocked`, so `blocked` went empty and parents became ready;
2. wrapping branch 2 in `(or-join [?step] (and …))` — correct at 405 steps,
   and refusing again at 1005. It moves the threshold, it does not remove it.

## 3. The fix: derive the frontier where the tree already is

`seon.plan/plan` already pulls the agent's whole component tree for its
renderers. Readiness, blockage and open work are now derived in Clojure from
that same pulled value (`tree-nodes`, `open-work?`, `derived-frontier` in
`src/seon/plan.clj`), and the `leaf` / `open-work` / `blocked` / `ready`
rules are deleted. `rules` keeps only `descendant` and `owned`, which have
no negation over a recursion-bound variable. Two Datalog reads per plan
became zero; a dependency that lives outside the agent's tree (needs is an
ordinary ref) is pulled with the same selector.

Equivalence measured against the old rules, in the same JVM, on the same
database values:

| database | agent | old rules (ready / blocked) | derived (ready / blocked) |
|---|---|---|---|
| `default` | `juniper` | `[juniper/read]` / 6 ids | identical |
| `default` | `root` | `[]` / `[]` | identical |
| memory, deps | `deps` | `[d1 d2a]` / `[d2]` | identical |
| memory, 1005 steps | `deep` | refuses | 150 ready / 0 blocked, 600 nodes in **2.6 ms** |

## 4. The refusal never reaches the opening as a bare line

A refusal is data, so the projections now read it. `refusal-line` gives all
three AI formatters (`format-plan-ai`, `format-item-ai`,
`format-ready-items-ai`) one typed sentence, and `plan` names the agent in
`:seon.error/data` so the line can say whose plan it is:

```
Plan unavailable for "root" — :seon.db/invalid-read: Insufficient bindings: …
```

`render-plan-ai` derives the plan once; when it refuses, the emitted source
is `(seon.plan/format-plan-ai (seon.plan/plan {}))` under a comment saying
the plan is not shown — so the agent reads a typed line through the plan's
own pair instead of a bare exception message where its instructions belong.
The refusal itself stays a flat `:seon.error` value returned by `plan`, so
the error surfaces still see it.

Live, in the default JVM:

```clojure
(seon.plan/render-plan-ai {:seon.db/db db :seon.agent/id "no-such-agent"})
;; => ";; My plan could not be derived from current facts, so it is not shown;
;;     the refusal below names it.\n(seon.plan/format-plan-ai (seon.plan/plan {}))"
(seon.plan/render-plan-ai {:seon.db/db db :seon.agent/id "juniper"})
;; => unchanged: ";; My plan is my instructions. … \n(seon.plan/plan {})"
```

## 5. In-process test runs (default, pid 95853)

Base realized before the runs; each `seon.test/run` on the live connection
with `:seon.test/remaining-ms 100000`, after reloading `my.plan-test`
through `seon.test`'s own loader.

| test | pass / fail / error |
|---|---|
| `terminal-formatters-say-a-refusal-in-their-own-words` | 12 / 0 / 0 |
| `a-refused-derivation-renders-a-typed-line-where-instructions-belong` | 3 / 0 / 0 |
| `the-derivation-answers-for-a-plan-with-no-steps-and-for-parents` | 8 / 0 / 0 |
| `completing-a-dependency-unblocks-the-dependent-step` | 5 / 0 / 0 |
| `the-whole-plan-renders-once-in-both-projections` | 8 / 0 / 0 |
| whole `my.plan-test` namespace | see §7 |

`terminal-formatters-preserve-database-errors` was the stale expectation: it
asserted the pass-through that produced the bare line. It is replaced, not
deleted.

## 6. Still open, out of this lane's scope

The Datahike planner defect stands: a well-formed query is rejected because
`:recursive-rule` ops credit no bindings in `lower.cljc`'s step-7 tracker,
and `order-plan-ops` will select an unrunnable op when nothing is runnable.
Any first-party rule with that shape is one database-growth away from the
same refusal. Filed as
[`the-query-planner-rejects-a-negation-bound-by-a-recursive-rule`](../../../seon/issues/the-query-planner-rejects-a-negation-bound-by-a-recursive-rule.md).

## 7. Verification boundary

Measured: the reproduction and its threshold, the old-versus-derived
equivalence on three database values, the render pair in both branches, and
the in-process runs above — all in the default JVM at this HEAD. Not run by
this lane: `bin/test` (no test JVM launched), the platform tier, and any
observation of a freshly forked cluster's stored opening; the gate request
is `tmp/orchestrator/gate-requests/plan-derivation.txt`.
