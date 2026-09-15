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
definition and lazy call are committed in `test/seon/contracts_fixture.clj`;
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

## Item 4 — complete examples and collection/attribute guidance

`my.note/add!` already had the complete executable example at inherited HEAD:

```clojure
(my.note/add! {:my.note/id "observation"
               :my.note/content "Verified the customer total."})
```

No redundant source edit was needed: `my.examples-test` executes that exact
example from the live program doc map. The help owner adds one sentence:
`[:vector X] needs a vector; use vec to convert a lazy seq.` The canonical
`:seon.db/attributes` declaration now explains that the property declares
map entries as database attributes, leaves the map open to extra keys,
and adds no identity constraint. Source grounding:
`src/seon/schema/form.cljc:66` derives stored attributes from map entries.

- Fast path-isolated help/example/grammar run: **8 tests / 270 assertions**,
  0 failures/errors.
- Isolated gate: **8 tests / 274 assertions**, 0 failures/errors
  (`run.swOoip`, removed by the runner).
- The item 3 paid trial included the new vector sentence and scored 12/12.

## Item 2 — refuse function objects before printing contracts

`seon.test.accretion/data-contract!` checks evaluated contract metadata before
`definition-row` prints it. Its refusal is the ordinary evaluation error,
so the existing candidate isolation retains no rejected function or program
row. Its exact message is:

> Function install refused: contracts are data; use a registered predicate schema or a quoted symbol naming an admitted predicate.

The exact captured run-5 definition is in
`test/seon/contracts_install_test.clj`. The regression proves first-install
and replacement refusals, retained-root identity and durable source after
a rejected replacement, and no `#object` in shown text. Quoted symbols pass
this check and reach the existing predicate admission rules; those rules
still require a qualified, documented, generatable, proven-pure predicate.
The shared fixture uses the canonical database, real SCI candidate evaluation,
real declaration installation and schema bridge, with `:panic` contracts.

- Fast run: **2 tests / 46 assertions**, 0 failures/errors, including the
  previous run-4 ordinary-turn install-refusal regression.
- Final isolated gate after sharing the fixture: **10 tests / 318
  assertions**, 0 failures/errors (`run.jCLOPa`, removed by the runner).
- MCP on default evaluated the exact captured function in a disposable
  candidate and returned the refusal with no program row or `#object`.
  After the final wording edit, a live call to the adopted data-contract!
  returned the exact message above. The same live observation found the
  new attribute description and vector help in default's current projection.

## Item 1 — authorized AI rendering seam

The owner authorized `src/seon/error.clj:718` on 2026-09-15. One hunk
prefixes the existing `instrumentation-prose` output with the refusal's
`:seon.error/message` and a newline. All existing suffix bytes and the
HTML pair are unchanged. The regression checks argument position, schema
path, expected vector and actual LazySeq in both the returned error and
`:seon.eval/shown`, and still proves that `vec` succeeds.

Default's adopted Var returned this live projection (final space omitted):

```text
argument 0 (0-based); schema path [0]; expected :vector, got LazySeq
Contract violation in contracts-plan/probe arguments: expected [:vector :map], received []. The call was stopped before the function ran.
```

The initial MCP health probe reported `Read timed out`; ordinary JVM
`(+ 1 1)` returned 2 in 1 ms and the renderer call returned in 2 ms.
This re-observes the existing
[status-probe issue](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md),
not cluster death. No default lifecycle operation was performed.

The initial graph-based reproduction also exposed missing
`:example/order-row` in base installation after the fixture admitted its
schemas. It was replaced with the existing direct SCI/install seams, which
test this assignment without asserting graph propagation health. The first
failure printer expanded a graph into tens of megabytes; later assertions
carry only the relevant evaluation evidence. An extra instrument namespace
run observed two arity/arglist assertions outside this slice failing; the
many-problem bounded-headline regression passed. The final selected gates
above are the verification claims, not that extra namespace run.

Scratch shutdown re-observed the already-filed
[cross-checkout process-census issue](../../../seon/issues/operator-down-misses-a-live-scratch-jvm-from-another-checkout.md):
main's `down` found zero records while PID 89956 was alive. The creating
worktree's exact `stop contracts-plan` stopped it through the PREPL and the
empty JVM exited. No process was killed by a substring match.

## Final verification and custody

Final authorized item 1 gates and new help-trial evidence are recorded below.

Landed slices:

- Item 2: `5bc825c5d` — function-object contract refusal.
- Item 3: `f2277a861` — current-step completing call and 12/12 trial.
- Item 4: `2d3d624c5` — vector and database-attribute guidance.

- Authorized item 1 fast gate: **42 tests / 430 assertions**, 0 failures/errors;
  namespaces `seon.contracts-plan-test`, `seon.error-test`,
  `seon.help-trial-test`, `my.examples-test`, `seon.repl-grammar-test`.
- New unchanged-harness trial: **12/12**, **2,305 prompt tokens**, **391
  completion tokens**, **640 cached tokens**, estimated **$0.00048627**.
  [Complete prompt, reply and score](contracts-and-plan-item1-help-trial-2026-09-15.edn).
  Frozen HEAD `7f6e5b51c` plus the two refusal-owner edits supplied a new
  scratch fork. The already-unavailable OpenRouter model row was removed
  only from that scratch catalog; the unchanged harness selected DeepSeek.
- The creating checkout stopped `contracts-plan-final` through the PREPL;
  empty JVM PID 25914 exited. Default was never stopped, reforked or reseeded.

- Authorized isolated gate: **42 tests / 434 assertions**, 0 failures/errors
  (`run.UTvT25`, removed by runner). Its snapshot overlays only
  `src/seon/instrument.clj`, `src/seon/error.clj`, and
  `test/seon/contracts_plan_test.clj`; foreign in-flight `src/seon/db.clj`
  and CSS edits are excluded. The final whole-file comparison proves
  `error.clj` differs only by the authorized prefix hunk.

- Final path-isolated platform gate: **84 tests / 505 assertions**, 0
  failures/errors (`run.59zGrH`, removed by runner).
- All lane shells exited. The scratch root and checkout were deleted only
  after confirming 0/0 clusters alive and PID 25914 absent; successful test
  roots were removed by their runner. Only this lane's temporary logs were
  deleted. Foreign edits and worktrees remain untouched.

Item 1 lands in this commit with the resolved contract issue, the exact
run-5 regression, and the new 12/12 trial artifact. All four numbered items
are complete.
