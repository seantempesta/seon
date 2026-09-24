---
type: research
status: complete
lane: census-red-baseline
created: 2026-09-24
---

# Default's red baseline, grouped by root cause (2026-09-24)

Read-only census of default's recorded test evidence. No test ran and no JVM started. Default pid 99265
(start 18:13:37Z). Read through MCP `eval_clj` in JVM mode against `(seon.cluster.boot/connection "default")`.

The read: `seon.test.runner/latest-results` over every `:seon.test.member/symbol` found 1,411 symbols and
1,355 results in 240 ms. Reds are results with fail or error counts, plus the typed unknowns: 625 in total.
Each red's text comes from `seon.test/failure-message` (test.clj:53), clipped to 1,500 characters. Grouping
used an uncommitted scratch classifier (tmp-only): an ordered regex list over that text, then a hand read of every
assertion-type red. Run timestamps are UTC; commit times were converted from -06:00.

## Totals

| | count |
|---|---:|
| recorded member symbols / latest results | 1,411 / 1,355 |
| red (`problems` reports 624 + 1 unknown) | 625 |
| red for a test that no longer exists (no `:seon.test/sym` row; S12 and turn-suite deletions) | 34 |
| **red for a current test** | **591** |
| of those, reaching `seon.fn` / `seon.sci.eval` / `seon.cluster` (whole-namespace `seon.test/reaching`, 130–260 ms) | 538 / 532 / 271 |

Almost every red reaches `fn.clj` and `sci/eval.clj`, so any change to those files is blocked by the whole
baseline. Only a fix to a class, not to a namespace, unblocks adoption.

## Ranked classes (591 current reds)

| # | class | count | owner | smallest fix |
|---|---|---:|---|---|
| 1 | a stopped request records members it never ran as errors | 195 | test.clj:1820–1835 | record no counts (≈10 lines); name the stop cause |
| 2 | SCI interprets an affected caller it cannot run | 115 | fn.clj:827, sci/eval.clj:908/1101 | 76 are stale (rerun); 24 need a resolve fix |
| 3 | fixture writes hand-built rows that the writer refuses | ≈100 | harness S5 H1 | S5; one residue fixable now |
| 4 | over its time bound, or waiting on an event under load | ≈33 | open-items class 4 (cost) | rerun on a quiet JVM; then per-test |
| 5 | test calls src with a retired shape | ≈29 | tests (update) | update each caller |
| 6 | the test branch inherits default's live faults and messages | ≈21 | harness S5 H1 | S5 member world |
| 7 | refusal payload member or key missing | ≈13 | suspected error-constructor conversion | one probe, then one fix |
| 8 | cache or read counters differ | ≈8 | harness H6 | S10/S11 |
| 9 | render selection ignores fixture renderers | ≈9 | seon.render selection | probe |
| — | no class (candidate real regressions) | ≈68 | see the list below | — |

Outside the 591: the 34 deleted-test reds come from `problems/failed-tests`, which reads member symbols
rather than current test rows (problems.clj:385). The fix is to query `[_ :seon.test/sym ?symbol]` instead,
one line. These 34 inflate readiness but do not block the gate.

## 1. A stopped request's unstarted members are recorded as errors — 195

`release-not-started!` (test.clj:1820–1835) commits each member a batch never started with
`:seon.test/error-count 1` and the message "Not started: request … stopped before this member."
`latest-results` then shows that as the test's red and hides its real prior evidence. Of the 195, the last
real execution was green for 60 and red for 52; the other 83 never began in any recorded run.

Examples, with the requests that stopped:
- `seon.adoption-diagnostic-test/row-refusal-describes-the-cause-without-copying-the-projection`
  (test/seon/adoption_diagnostic_test.clj:10), request 821fbe1f0b44
- `seon.ai-test/an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal`
  (test/seon/ai_test.clj:768)
- `seon.adoption-contract-freshness-test/adopted-contracts-govern-both-sci-and-host-calls`
  (test/seon/adoption_contract_freshness_test.clj:106)

Biggest stoppers: 3ad44b32bccf (4 started, all red; 56 not started), ad134b197ab1 (20/44), bd39bf08236c (4/60),
756878b0ffed (8/56), 4c4f85c15ca0 (8 green / 53, so a deadline or platform stop).

Nothing durable names why a request stopped. `::stopped` (test.clj:1781–1803) has four causes: the deadline
passed, a member did not finish, recording was refused, or a red platform test.

Fix, about 10 lines, in two parts:
- (a) Write not-started members with `began? false` and no counts. `recorded-member-results`
  (runner.clj:1595) already turns that into `:seon.test/result-unknown`. An unknown still blocks the gate,
  but it no longer reads as a failure.
- (b) Record the stop cause on the run as a named terminal event (law "Bound admission": name the missing
  terminal event).

On its own, (a) turns no red green. The real unblock is removing the stop causes, which are classes 4 and 2.
No issue records this defect; this file is its durable record.

## 2. SCI interprets an affected caller it cannot run — 115

Branch members interpret program rows that differ from the compiled Vars. Default's own rows differ too:
lanes publish test edits that were never adopted (see the harness-s0 landing).

- **2a. JVM macro in a helper — 76. Stale.** The message is "Cannot interpret seon.render.web-test/with-server:
  … Symbol cannot be cast to … Compiler$LocalBinding" (43 reds), and the same for
  `seon.cluster.agent-test/with-connection` (33). Both helpers call `async/go-loop`. Commit 55af9e04b (18:57Z)
  made such callers host-bound. Every one of these reds ran between 18:28 and 18:37Z, and the live rows now
  carry `:seon.fn/host-bound? true`.
  - Examples: `seon.render.web-test/a-taken-port-serves-anyway-and-says-so` (test/seon/render/web_test.clj:2220),
    `seon.db-test/equal-declaration-content-shares-one-projection-across-values` (test/seon/db_test.clj:2244),
    `seon.db-test/every-database-value-reader-answers-for-all-four-view-shapes` (db_test.clj:971).
  - Fix: none; rerun them.
- **2b. Unresolved symbol — 24. Live.**
  - `seon.cluster/publication-base!` cannot resolve `spit` (13). The reds ran at 18:26Z. SCI omits
    `clojure.core/spit`, and the row has `:seon.fn/host-bound? false`.
  - `seon.test.interrupted-request-test/admitted-run!` cannot resolve `finished` (7, at 20:26Z).
    `finished` is a same-namespace `def ^:private`, which is not a program row.
  - `seon.flow-test/install-test-work-launcher!` cannot resolve `test-environment` (4).
  - Examples: `seon.cluster.wake-test/a-declared-attribute-wakes-with-no-code-change`
    (test/seon/cluster/wake_test.clj:357), `seon.turn-test/run-derives-its-opening-database-and-starting-namespace`
    (test/seon/turn_test.clj:1117; a turn test reaches another test namespace's helper).
  - Fix at fn.clj:827: a call to a `clojure.core` Var that SCI's clojure.core lacks makes its caller host-bound.
  - Fix in sci/eval.clj: an interpreted caller resolves an unchanged same-namespace non-row Var to its compiled
    Var, extending b855a8a17. About 20 lines. Related issue:
    docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md (resolved; this is residue).
- **2c. A changed host-bound declaration refuses on the branch — 12.** "Host-bound declaration seon.fn/analyze-forms
  must change through the loaded source files" (sci/eval.clj:1101). The refusal is correct.
  - Examples: `seon.reset-edges-test/analysis-is-a-required-positive-fact` (test/seon/reset_edges_test.clj:74),
    `seon.problems-test/an-errored-receipt-is-a-problem-without-being-a-fault` (problems_test.clj:397).
  - Whenever a lane changes fn.clj, the gate must adopt and then test (cluster `adopt-then-test!`) rather than
    interpret. Check which path produced these 18:49Z runs.
- **2d. "The SCI context did not acquire the tested program" — 3** (test.clj:1304).
  - Example: `seon.db-test/nested-native-reports-admit-bounded-reference-identities` (db_test.clj:1087).

## 3. Fixtures write hand-built rows the writer refuses — about 100 (harness S5 H1; do not hand-repair)

- **Missing `:seon.program/definition-digest` — 38** (22 `:seon.ns/name`, 7 `:seon.test/sym`, 3 `:seon.fn/sym`,
  5 `:seon.schema/key`). Examples: `my.plan-test/a-changed-title-is-an-ordinary-fact-update` (test/my/plan_test.clj:218),
  `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself`,
  `seon.render.web-debug-test/blocks-use-the-values-schema-documentation`.
  **Residue fixable now:** `my.plan-test/with-plan` (test/my/plan_test.clj:28) still hand-writes
  `{:db/id "plan-ns" :seon.ns/name 'fixture.plan}` (21 reds at 20:39Z); the ns sweep 53ae022b2 missed it;
  `support/namespace-row` (test_support.clj:697) replaces it, 3 lines.
- **Agent row without `:seon.agent/branch` — 27.** `my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair`
  (test/my/agent_test.clj:11), `seon.agent-situation-test/situation-is-the-live-derived-control-surface`
  (agent_situation_test.clj:27), `seon.ai-test/agent-overlay-reads-only-derived-per-agent-attributes` (ai_test.clj:602).
- **Component validation — 11.** `unowned-entity` (bare `:seon.config.eval.result`, `:seon.schema-usage-guard/*`) and
  `missing-component-schema` (test-local component attrs); e.g. `seon.blob-test/binary-content-round-trips-…` (blob_test.clj:82).
- **Other — 19.** "The failure fixture transaction was refused" (8, test_failure_facts_test.clj:99); "Auto-check fixture
  admission failed" (4, accretion_test); message without `:seon.message/to` (4, test/my/note_test.clj:15); refused
  transacts inside `is` (my.plan-api-test, run6-stall-test, sci.documentation-test/bare-test-macros-…).

## 4. Over its time bound or waiting on an event under load — about 33

24 "exceeded its declared duration"/"did not exit within", 7 "test channel did not publish its required event" /
"Continuation fixture did not publish", 2 micro-bounds (db_test:2306 52.05 vs 50 ms; fault_test:80 1,172 vs 1,000 ms).
Examples: `seon.cluster.agent-test/failed-turn-transform-keeps-a-live-backstop-after-releasing-its-permit`
(test/seon/cluster/agent_test.clj:1013, 20.5 s vs 5 s), `seon.loop-proof-test/running-fixture-settles-its-seeded-wake`
(loop_proof_test.clj:64, 34.4 s), `seon.cluster.agent-arming-test/an-agent-created-while-…` (25.7 s). They coincide with
274% CPU concurrent publications (open-items class 4; profile `call-with-projection` 8,240 s over 460k calls). No test
is proven slow alone: rerun on a quiet JVM before per-test work. Most likely also class 1's `::stopped` deadline.

## 5. Test calls src with a retired shape — about 29 (update the test)

- **Contract refusals raised from a test caller — 19:** `seon.turn/record-attempt!` without `:seon.ai/max-tokens` (5;
  `seon.data-shapes-test/attempt-usage-is-queryable` data_shapes_test.clj:245,
  `seon.turn-test/attempt-model-identity-survives-descriptor-retraction` turn_test.clj:41);
  `seon.render.web/candidate-call-ids` with 2 args (root_pull_test:368/383); `seon.id/valid?` with 0 args;
  `seon.error/recording`/`commit-tx` without `:seon.error/declared-schema` (5); `seon.context/capture-tx`, `seon.error/normalize`.
- **Symbols expected as strings — 4** (sci/reader_test.clj:470, 609, 675, 710); existing issue
  docs/seon/issues/reader-test-expects-declaration-symbols-as-strings.md.
- **Fixture entity schemas without a partition — 6** (schema.clj:1528, rule from f4c56ad12), e.g.
  `seon.error-test/complete-error-children-validate-through-the-writer` (error_test.clj:973): add `:seon.program/partition`.

## 6. Test branch inherits default's live faults and messages — about 21 (harness S5 H1)

error-test 9 (e.g. `recurrence-counting-does-not-require-a-notification-threshold`, error_test.clj:797: 1 message
expected, 6 got), problems-test 7 (`a-healthy-cluster-derives-an-empty-value`, problems_test.clj:185: `{}` expected),
plus schedule_test.clj:350, turn-test/recovery-closes-a-turn-with-no-evaluations, db-test/a-write-naming-another-clusters-
branch-…, config-test/apply-replaces-an-inherited-config-identity-…, flow-test/core-fault-signatures-…. Cause:
`support/with-database` (test_support.clj:654) forks default's live branch. Existing issue
docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md.

## 7. Refusal payload member or key missing — about 13 (probe first)

Expected `:seon.error/data :seon.error/member`, `:seon.config/key`, `:seon.error/offending` or `:seon.instrument/arglists`;
got nil. `seon.config-test/shipped-initialization-admission-is-registry-derived` (config_test.clj:162, assert :213),
`seon.db-test/an-unknown-read-operation-refuses-naming-the-attribute` (db_test.clj:2075),
`seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms` (schema_test.clj:1462); also
db_test:184, instrument_test:1470, problems_test:509, schema_test:446/959, shell/jvm_test:175, bootstrap_test:396,
render/call_test:28, config_test:477. Looks like one producer. Suspects, unproven: error-constructor conversion
bab838388 and checkpoint ab7e4a643 (UNREVIEWED). One REPL probe of `config/default-population` refusal data decides
regression versus retired expectation.

## 8. Cache or read counters — about 8 (harness H6, S10/S11)

root_pull_test:204/235/340 (6 compiles vs 2; 112 reads vs 0), render_simplification_test:785, fn_test:2651/699/2847,
`seon.db-test/uncarried-read-…`.

## 9. Render selection ignores fixture renderers — about 9 (candidate regression)

render_simplification_test :552, :694, :136, :611, :1024, :937, :914, :277, :660: expected the fixture renderer, got the
attribute-map printer or "one applicable renderer". One owner (seon.render selection over the handed projection);
likely the fixture namespaces' render rows are absent from the acquired context (then class 3). Probe once.

## Unclassified — candidate real regressions (about 68)

With a src frame first: config-application-test/every-config-entry-has-an-honest-application-contract (refusal from
fn.clj:603); turn-loop-test/a-refused-phase-escalates-… (turn.clj:3674) and refused-generated-opening-… (turn.clj:4498);
db-test/a-refused-declarations-read-refuses-decoding (db_test.clj:2027, NPE `getNamespace`); render-simplification-test/
nested-values-render-their-declared-faces (NPE); instrument-test/the-caller-frame-is-part-of-the-refusal-sentence (NPE in
`render-ai`); fn-test/publication-writes-one-definition-digest-per-declaration-family (`:malli.core/invalid-schema`);
schema.datahike-test/canonical-population-native-parity (`:seon.flow/callback-result` register);
flow-test/turn-evaluation-completion-is-a-flat-diagnostic-value (config requires a cluster row);
config-test/two-clusters-on-one-jvm-have-no-config-bleed (config_test.clj:659, foreign-branch write);
test-provenance-test/runs-are-distinct-… (test_provenance_test.clj:21) and test-runner-test/result-recording-is-total-…
("no surviving test definition"); cluster.store-test/an-error-inside-one-store-write-… (store_test.clj:127: store already
open in pid 99265, destructive test not isolated); adoption-diagnostic-test/operator-reports-… (2,596 vs 1,024 bytes; open
item); worker-global drift 3 (runner.clj:389; contracts-compile-test Vars loaded mid-run); about 40 single assertions
across instrument, config, mcp, mcp-bridge, turn-loop, schema and documentation tests.

## Which fixes unblock the most adoptions

Every class reaches fn.clj and sci/eval.clj in about the same proportion: 538 of the 591 reds reach `seon.fn`.
So the ranking by count is also the ranking by adoptions unblocked.

1. Rerun the stale 2a reds: 76, no code. At the same time, remove the class 4 load and make not-started members
   unknowns (class 1): up to 195 + 33 become real outcomes. Of the 112 not-started members that ran before,
   60 were last green.
2. The class 2b resolve fix: 24 reds, about 20 lines. The `my.plan-test` ns-row residue: 21 reds, 3 lines.
3. The class 5 test updates: about 29, mechanical, one script per refusal shape.
4. Harness S5 H1 (classes 3 and 6): about 120. This is the largest block that needs design work.
5. Probe classes 7 and 9 (about 22) before assigning them.

Limits: regex plus hand reading of 1,500-character clipped text; "about" counts are ±3; class 1 stop causes are
inferred; no red was re-executed. Timings: latest-results 240 ms; each reaching call 137–261 ms.
