---
type: research
status: active
tags: [research, turn, test, render]
---

# Backstop and misc landing — 2026-09-15

## Job 1 — admitted turn parts

Commit: `d4a85dbdc29425d29d5dbdab215653ec97bc9bb9`.

Read AGENTS.md end to end, the roadmap README and working edge, the named
backstop issue, the turn proc and graph completion await. Applied the
Clojure, REPL, Flow, provider, and canonical-testing skills.

The existing completion observer now receives the work admitted at the provider
and evaluation seams. It uses the resolved attempt timeouts and already chosen
finite retry delays; backup targets contribute their own timeout. Each admitted
form starts its own evaluation allowance. The existing lifecycle allowance
covers permit/stop/settlement progress and is added to admitted work. Neither
arming nor permit acquisition caps this at the evaluation limit. Disarm joins
the same active observer. A fault names provider response, evaluation completion,
turn permit, or proc stop acknowledgement.

Dependency ledger: core.async Flow's transform/stop protocol is at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168`;
SCI's evaluation interrupt seam is `reference-code/sci/doc/interrupt.md`.
First-party admission owners are `seon.turn/provider-targets`, `call-turn`, and
`evaluate-sources`; `seon.ai/delays` already produces the bounded retry schedule.
No second retry calculation or graph lifecycle mechanism was introduced.

### Verification

- MCP JVM observation on default before edits: evaluation 10,000 ms; provider
  timeout 180,000 ms; maximum retries 2; maximum total retry delay 3,000 ms;
  lifecycle completion allowance 600,000 ms. The resolved provider schedule
  was empty because a backup was configured.
- Canonical armed fast loop: `seon.loop-proof-test seon.turn-continue-test`,
  **5 tests / 392 assertions / 0 failures / 0 errors**. The extra continuation
  scenario spends 20 seconds in the simulated external provider with a 10-second
  evaluation limit, then completes its real SCI turn with one provider attempt,
  no fault, and two turns remaining.
- Canonical armed part/disarm regression: **1 test / 9 assertions / 0 failures /
  0 errors**. Two retries yield 90,300 ms; primary plus backup yields 70,000 ms.
  Missing provider and evaluation parts each report their own name at a 200 ms
  bound through the graph's completion await.
- Isolated `bin/test --paths` gate at `dabd311d0` plus the six owned code/test
  paths: **6 tests / 403 assertions / 0 failures / 0 errors**, coordinator 206 s.
- Default's reloaded JVM Vars return provider work **360,000 ms** (primary plus
  backup) and exactly `Agent "probe" run "probe-turn" did not publish provider
  response within 200 ms.`. This is a hot-reloaded-Var proof. Concurrent source
  edits prevented adoption from recording convergence; no full-adoption claim
  is made at this checkpoint. Final publication is checked after job 2.

### Existing verification boundary

`seon.cluster.agent-test` remains red at its existing consumer fixture boundary.
The initial expanded run reproduced parallel counting and the routing fixture's
missing process `8111-1700000000000`; it was terminated after recording that
known boundary. A HEAD-only canonical probe at `dabd311d0` independently reported
**1 test / 3 assertions / 1 failure / 0 errors**, with
`{:settled? true :answered-once? true :ledger-equals-runs? false
  :receipts-unique? true :fences-quiet? true :per-agent-serial? false}`.
No edits to that old suite were retained. Evidence belongs to the existing
[consumer issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md).
The reproducible probe is
[backstop_misc_baseline_2026_09_15.clj](backstop_misc_baseline_2026_09_15.clj).

Another lane held uncommitted install-gate changes in `src/seon/turn.clj` at
entry. Work was prepared at detached HEAD in `tmp/backstop-and-misc-wt`, with
`reference-code` linked; the own-path patch applied cleanly after that lane
landed `747995bf4`. The isolated gate names its earlier HEAD-plus-owned-paths
boundary. Default was never stopped, reforked, or reseeded.

### Exact source/test diff

| Path | Added | Removed |
|---|---:|---:|
| `src/seon/turn.clj` | 55 | 21 |
| `src/seon/cluster/agent.clj` | 1 | 2 |
| `resources/seon/schemas/seon.config.agent.edn` | 1 | 1 |
| `test/seon/loop_proof_test.clj` | 3 | 3 |
| `test/seon/turn_continue_test.clj` | 13 | 2 |
| `test/seon/turn_backstop_test.clj` | 56 | 0 |

## Job 2 — audit-1 A05, A10, A11, A12

Commit subject: `Retire audit misc mechanisms and derive settings and API checks`.

Read the named audit and all four linked issue notes end to end. The four
findings are resolved and archived. The associated incremental-planner fixture
issue is also resolved; its input omitted required admission provenance.

### Changes and dependency ledger

- **A05:** deleted the retired private-bounder census, shortest-path helper used
  only there, and report schema fields. The general declared sink/projection
  check remains with positive subject assertions and projected, bypass,
  unresolved, and codec fixtures. Whole-value elision retains count and requery
  evidence. The same namespace's synthetic function/test rows now supply their
  declared admission provenance; bypass edges enter in the initial transaction.
  Dependencies precede lookup refs in the transaction; setup refusal is asserted.
  Removed the stale two-constructor roster; current authored settlement remains
  covered. Fixed its existing planner expectation to retain the complete row.
- **A10:** removed 140 lines of obsolete plan rules from input.css and consolidated
  live selectors in blocks.css. Existing specificity had retained several older
  effective values; these are now explicit in the single rule for each concern.
  Tailwind's existing `bin/css` build regenerated output.css, which is excluded
  from this commit.
- **A11:** uses `seon.cluster.instruction/toolkit-namespaces`, program `dir` data, and
  `seon.bootstrap/help-value` rather than directory listings and rendered prose.
  The canonical launcher receives `support/effective-config` intact. Plan, edit,
  and fs population checks query public program rows. A contracted function in
  `my.examples.nested-fixture` is discovered, documented, resolved, and its example
  evaluated through real SCI without changing discovery logic.
- **A12:** `schema/declaration-population` and
  `schema.form/attr-form-properties` read one `:seon.config/display` property on
  the settings schema. Its declared label/divisor/unit entries format values;
  other attributes retain their raw name and magnitude. The registry contained
  no existing semantic unit declaration. The canonical regression varies the
  declaration with `schema/call-with-forms`, gives differently spelled attributes
  identical units, and leaves an undeclared `-ms` value unchanged.

Dependencies reused: Datahike Datalog through `seon.db/q`
(`reference-code/datahike/src/datahike/api.cljc`); Malli schema properties
(`reference-code/malli/src/malli/core.cljc`) through Seon's existing schema-form
owner; real SCI documentation/evaluation (`reference-code/sci/src/sci/core.cljc`)
through the existing canonical fixture. No alternate graph, API discovery, or
settings conversion mechanism was added.

### Browser and live observations

`bin/css` succeeded. The reproducible
[Chromium comparison](backstop_misc_css_2026_09_15.cjs) loads the canonical HTML
exported by `seon.html-views-test` under both baseline and consolidated CSS.
At **1440 and 700 pixels**, all **64 elements** have identical inspected computed
styles, with **zero differences** and **no horizontal overflow**. Both resulting
screenshots were inspected; ready, blocked, current, and completed plan entries
remain readable. Browser contexts were closed.

MCP on default observed `Evaluation deadline` → `10 s` from the declaration and
only `:seon.fn.output/totals` / `:seon.fn.output/paths` in the census response.
This establishes loaded behavior. Adoption and publication IDs were compared
explicitly. Final observation converged: adopted and published IDs both read
`6aa8e2c3-c1f9-5f23-bbe8-b1673576257b`. Earlier publication failures occurred
while concurrent source edits were incomplete; they did not require a restart.
Default was never stopped, reforked, or reseeded.

### Exact job 2 source/test diff

Counts are added/removed lines relative to the parent of this job's commit;
the CSS browser reproduction is additional research evidence.

| Finding | Path | Added | Removed |
|---|---|---:|---:|
| A05 | `src/seon/fn.clj` | 1 | 53 |
| A05 | `resources/seon/schemas/seon.fn.output.edn` | 1 | 19 |
| A05 + fixture repair | `test/seon/fn_test.clj` | 29 | 53 |
| A05 | `test/seon/print_test.clj` | 3 | 7 |
| A10 | `resources/public/css/input.css` | 0 | 140 |
| A10 | `resources/public/css/blocks.css` | 24 | 26 |
| A11 | `test/my/examples_test.clj` | 11 | 21 |
| A11 | `test/my/examples/nested_fixture.clj` | 11 | 0 |
| A11 | `test/my/plan_api_test.clj` | 8 | 2 |
| A11 | `test/my/edit_test.clj` | 20 | 7 |
| A11 | `test/my/fs_test.clj` | 20 | 10 |
| A12 | `src/seon/agent.clj` | 26 | 16 |
| A12 | `resources/seon/schemas/seon.config.edn` | 20 | 1 |
| A12 | `test/seon/html_views_test.clj` | 21 | 1 |

### Final verification and boundary

- Final `bin/test-fast --paths` on `seon.fn-test`: **29 tests / 167 assertions /
  2 failures / 0 errors**. The bypass, unresolved-subject, canonical census,
  reachability-fixture, and planner checks pass. Both remaining failures are
  the two missing dynamic-call edges in one existing evaluator-path census.
- Final `bin/test --paths` at **`25bce52a0760394a8b14fe4e1fb23a2511708495`**,
  overlaying the twelve Clojure/schema/test paths above, on
  `seon.fn-test seon.print-test my.examples-test my.plan-api-test my.edit-test
  my.fs-test seon.html-views-test`: **66 tests / 716 assertions / 2 failures /
  0 errors**, coordinator **109 seconds**. All **65 other tests / 713 assertions**
  pass. The sole failing test is
  `seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path`.
- Final platform verification, `bin/test --platform`: **84 tests / 505 assertions /
  0 failures / 0 errors**, coordinator **82 seconds**, exit **0**. Its snapshot
  is `175db5840` plus the then-current working tree; subsequent own changes
  only repair and assert the census fixture's transaction order. Production
  code and schema bytes are identical to that passing platform checkpoint.

The [missing-edge issue](../../../seon/issues/archive/turn-dynamic-evaluation-calls-are-missing-from-the-program-graph.md)
names the integration boundary: turn code dynamically resolves
`evaluate-for-install` and `preview-sources`, but the graph omits those edges.
Job 2 changed no turn, SCI, analyzer, or render-owner implementation. The test
now names the current evaluator and still refuses an absent call edge; its
expectations were not relaxed. The broader consumer fixture's unasserted writer
refusal is separately recorded in the existing
[consumer issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md).

Code/schema/CSS/tests total **195 added / 356 removed (net −161)**. Research
adds the **46-line** reproducible browser comparison and this landing evidence;
issue notes record all remaining limitations. The scratch worktree and own
probe/gate artifacts were removed after their runners exited. No foreign session
was operated or messaged, and generated output.css remains uncommitted.
