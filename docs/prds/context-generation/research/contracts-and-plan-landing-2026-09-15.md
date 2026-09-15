---
type: research
status: active
tags: [contracts, plan, help, live-test]
created: 2026-09-15
---

# Contracts and plan — 2026-09-15

## Grounding and boundary

Read AGENTS.md, both assigned issues, the run-5 explain probe (including
`:text`), the roadmap README and working edge end to end. Read the refusal
and doc-map seams, candidate installation, compact plan render, my.note and
my.plan docstrings, and the executable docstring-example test. Archaeology:
`747995bf4` supplies candidate isolation and refusal results; `8df86358b`
supplies the compact plan pair.

Inherited HEAD: `60e4e13bb`. Default PID 23729 answered operator status and
MCP health. Existing edits in turn.clj, CSS and unrelated untracked files
are outside this lane. Default is never stopped, reforked or reseeded.

## Dependency ledger

- Malli `reference-code/malli/src/malli/core.cljc:1469,2643,3027`:
  collection predicates and `explain` already supply `:in`, `:path`,
  `:schema` and `:value`; vector validation calls `vector?`.
  `src/seon/instrument.clj` owns refusal construction and interpreted wrapping.
- SCI `reference-code/sci/src/sci/core.cljc`: contexts, Vars and candidate
  forks; `src/seon/sci/eval.clj` derives declarations from evaluated Var
  metadata before admission. `src/seon/test/accretion.clj` owns install
  refusal teaching.
- Canonical fixture: `test/seon/context_blocks_fixture.clj` supplies the
  order schemas. The regression evaluates its forms in real SCI, commits
  canonical program rows, and derives Datahike attributes through the
  existing schema bridge over the complete projection. Contracts are armed.

## Item 1 — refusal coordinates

Live MCP JVM probe before editing:

```clojure
(let [problem (first (:errors
                      (malli.core/explain [:cat [:vector :map]]
                                          [(map identity [{:example/amount 60}])])))]
  {:in (:in problem) :path (:path problem)
   :expected (malli.core/type (:schema problem))
   :got (.getSimpleName (class (:value problem)))})
;; => {:in [0], :path [0], :expected :vector, :got "LazySeq"}
```

The existing message now carries the first failing argument (explicitly
zero-based), Malli schema path, expected schema type and actual JVM type.
Complete existing diagnostic evidence is retained. The exact run-5 accepted
definition and lazy call are committed in `test/seon/contracts_plan_test.clj`;
the regression installs through candidate SCI evaluation, checks the
evaluation's shown text and doc map, then proves `vec` returns Ada/115.

Verification results are appended with each numbered slice.

## Item 3 — teach the selected step's completing call

The plan AI source now says, for the fixture's selected step:

```clojure
;; My plan is my instructions. After seeing the result and verifying the criterion, complete this step with (my.plan/complete! {:my.plan.item/id "juniper/read"}). my.plan/current! selects; completing clears the selection.
(seon.plan/plan {})
```

The id comes from the current selection, including a database ref. An
unselected plan says `No step is selected.` and invents no completing id.
my.plan's namespace and current! docstrings teach the same distinction.
The HTML function's source bytes are unchanged. Only the plan entry in the
AI golden fixture changes; the help-trial harness and scorer are unchanged.

Validation:

- Fast path-isolated run: 38 tests, 543 assertions, 0 failures/errors.
- Isolated gate after the ref-resolution refinement: 38 tests, 547
  assertions, 0 failures/errors (`run.TFln6g`, removed by the runner).
- Live scratch fork of HEAD `2795a3f3b` plus this lane's selected files:
  the unchanged help-trial `run!` scored **12/12**, with 2,304 prompt tokens,
  370 completion tokens, 256 cached tokens, estimated cost **$0.000529968**.
  The 7,533-byte prompt contains the exact completing call, selection clause,
  and item 4's vector sentence. Complete evidence:
  [DeepSeek trial](contracts-and-plan-help-trial-deepseek-2026-09-15.edn).

The first priced candidate, OpenRouter, returned HTTP 402 (insufficient
credits); that unavailable observation is preserved in the
[first attempt](contracts-and-plan-help-trial-2026-09-15.edn). Its model
row was removed only from the disposable scratch catalog, allowing the
unchanged harness to select the next cheapest configured credential.
The scratch cluster has providers disabled for ordinary turns. The
fixture and trial run consecutively to satisfy the harness's initial-state
preflight; failed preflights made no model call.

The first scratch publication saw source changes during analysis and refused.
A detached `tmp/contracts-plan-wt` at HEAD plus only this lane's files,
with `reference-code` linked, supplied the stable publication. No foreign
session or file was operated. Default was never stopped, reforked or reseeded.
