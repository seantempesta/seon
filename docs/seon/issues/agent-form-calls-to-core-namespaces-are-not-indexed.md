---
type: issue
status: open
severity: blocker
tags: [issue, program-graph, index, eval, agent]
---

# Agent run forms record no call edge to a core function they call

## Problem

A run form's `:seon.fn/calls` edges are recorded for functions in the agent's
own namespace and for `my.*` toolkit functions, but NOT for a fully qualified
core function the form plainly calls. `(seon.db/q '[...])` inside an agent form
produces no edge to the `seon.db/q` entity, even though that entity exists in
the same program graph.

Every "which forms used this function?" question is therefore silently wrong for
core functions — the exact class the "everything is queryable" principle exists
to prevent. It is not a missing fact (the entity is there); it is a missing EDGE,
which is worse, because the query returns a confident empty answer.

## Evidence

Measured on the minimum-context ablation FULL drive root
`tmp/ablation/drive-roots/full-02/clusters`, branch
`:cluster-minimum-context-full` (probe committed at
`tmp/ablation/grade_probe.clj` and `tmp/ablation/grade_probe2.clj`):

```text
seon.db/q entity: 12765
forms calling seon.db/q: nil
run forms with any call edge: 7
```

The agent's own form, ordinal 2 of run `b488ab0b-…`, is

```clojure
(let [c (cluster-agent-count)
      contract (seon.db/q '[:find ?spec . :in $ ?sym
                            :where [?f :seon.fn/sym ?sym]
                                   [?f :seon.fn/spec ?spec]]
                          "my.agents.w1-history-proof-5/cluster-agent-count")]
  (my.run/complete (pr-str {...})))
```

and its recorded facts are

```clojure
#:seon.fn{:keywords [:seon.fn/spec :seon.fn/sym]
          :calls [#:seon.fn{:sym my.run/complete}
                  #:seon.fn{:sym my.agents.w1-history-proof-5/cluster-agent-count}]}
```

`my.run/complete` and the agent's own function are edged; `seon.db/q` is not.
The same gap appears on the shipped bootstrap forms, which evaluate
`(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])` at ordinals 5, 6
and 12 and also carry no `seon.db/q` edge.

## Consequence observed

The minimum-context ablation's third success criterion is
"another clean receipt belongs to a form calling `seon.db/q` and carrying
literal keyword `:seon.fn/spec`"
([plan](../../prds/sci-execution-runtime/research/minimum-context-ablation-plan-2026-08-11.md)).
Because the edge never exists, that criterion is UNSATISFIABLE, and all four
variants were graded `:minimum-context.grade/success? false` regardless of what
the agent actually did — FULL and HALF both completed the work correctly.

## Acceptance

- A run form that calls a resolvable program-graph function records a
  `:seon.fn/calls` edge to it, whatever namespace it lives in.
- One regression proves the class dead: evaluate a form calling a core function
  and a `my.*` function in one agent run and assert both edges, so a
  namespace-dependent edge gap cannot reappear.
- Re-derive any conclusion drawn from a `:seon.fn/calls` query over run forms
  since this gap existed.

## Owner

Program-graph indexing owner (`src/seon/fn.clj` reply/form analysis path).
