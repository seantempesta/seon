---
type: issue
status: resolved
severity: blocker
tags: [issue, plan, datahike, query]
---

# `seon.plan/plan` refuses with an unbound rule variable, and the refusal reaches the agent's opening

Observed 2026-09-16 on the isolated `trials` cluster (checkout
`tmp/issue-trials-wt` at `db65dc542`) while capturing worker openings for the
issue-context trials.

Every agent's opening carries a plan block. On that cluster it renders this,
verbatim, where the agent's instructions belong:

```
my.agents.d4e6bed2793e=> ;; My plan is my instructions. No step is selected. my.plan/current! selects; completing clears the selection.
(seon.plan/plan {})
Insufficient bindings: none of #{?leaf__auto__r25056} is bound in clojure.lang.LazySeq@98b41825
```

Called directly with the projection handed in, the value is a typed refusal
whose data names the offending clause:

```clojure
(seon.plan/plan {:seon.db/db db :seon.agent/id "root"})
;; => {:seon.db/invalid-read true
;;     :seon.error/kind :seon.db/invalid-read
;;     :seon.error/message "Insufficient bindings: none of #{?leaf__auto__r25608} is bound in ..."
;;     :seon.error/data {:seon.db/operation :seon.db/q
;;                       :seon.db/dependency-data
;;                       {:error :query/where
;;                        :form (not-join [?leaf__auto__r25547]
;;                                        [?leaf__auto__r25547 :my.plan.item/completed-tx _])}}}
```

The clause is the second `open-work` rule at `src/seon/plan.clj:53-57`, where
`(descendant ?step ?leaf)` is expected to bind `?leaf` before the `not-join`.
It reproduces for BOTH agents on that cluster, including `root`, so it is not
caused by the issue-family's step shape. The same call on `default` answers
`:ok` for both of its agents, so the failure is data- or
planner-state-dependent rather than universal — that difference is the first
thing to bisect.

Two defects, not one:

1. the derivation refuses at all;
2. the refusal reaches the agent as a BARE exception message, not as a
   `#:seon.repl{...}` response with `:error`. An agent that loses its plan is
   told nothing it can act on, and the turn carries on looking healthy.

Acceptance: the plan derivation answers for a nested plan on a freshly forked
cluster, with a regression covering a plan whose steps have children and no
`completed-tx`; and a refused generated read renders as a typed REPL error
response rather than a raw message.

## Resolution (2026-09-16, lane `plan-derivation`)

Reproduced and fixed. The refusal was neither cluster- nor plan-shape
specific: it is a SIZE threshold. Datahike's IR planner orders ops by
estimated cost and credits no bindings for a `:recursive-rule` op, so once
`:my.plan.item/id` grows past the recursive `descendant` rule's estimate,
`(not-join [?leaf] …)` is ordered before its binder and rejected. Measured
in one JVM: answers at 7 plan steps, refuses at 405, refuses everywhere at
1005. Reformulating the rule only moves the threshold.

Both defects are closed at the owner:

1. `seon.plan` derives readiness, blockage and open work in Clojure from the
   component tree `plan` already pulls (`tree-nodes`, `open-work?`,
   `derived-frontier`); the `leaf` / `open-work` / `blocked` / `ready` rules
   are deleted and `rules` keeps only `descendant` and `owned`. Two Datalog
   reads per plan became zero, and the derivation cannot refuse for this
   cause in any data state.
2. A refusal is now said, not handed on: `refusal-line` gives every plan AI
   formatter one typed sentence naming the plan, its agent, the refusal kind
   and its message, and `render-plan-ai` emits
   `(seon.plan/format-plan-ai (seon.plan/plan {}))` under an explaining
   comment when the derivation refuses — never a bare exception line where
   the agent's instructions belong.

Regressions: `my.plan-test/the-derivation-answers-for-a-plan-with-no-steps-and-for-parents`,
`.../a-refused-derivation-renders-a-typed-line-where-instructions-belong`,
`.../terminal-formatters-say-a-refusal-in-their-own-words`.

The dependency defect underneath it is filed separately:
[the-query-planner-rejects-a-negation-bound-by-a-recursive-rule](the-query-planner-rejects-a-negation-bound-by-a-recursive-rule.md).
Evidence:
[plan-derivation-refusal-2026-09-16](../../prds/steward-platform/research/plan-derivation-refusal-2026-09-16.md).
