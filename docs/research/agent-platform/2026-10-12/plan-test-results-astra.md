---
title: Test results an agent can explain from stored runs
type: research
status: proposed; owner decisions Q1–Q3 below; no implementation
agent: plan-tr-astra
date: 2026-10-12
---

# Stored results, one comparison

The runner already admits and completes `:seon.test.run/run` values; `seon.test.runner/result-at-reach` finds their results. Keep those facts, preserve root's completed work on main, and derive one comparison for the agent, the merge rule and outside tools. Delete test execution from the act of asking what a change broke.

Read: AGENTS.md and the testing, Datahike, data-modeling, data-oriented Clojure and REPL skills; [dig](dig-test-results.md), [done-and-merge hybrid](hybrid-done-and-merge.md), [M1 ruling](m1-notes.md). This plan does not redo the dig or change M1's prohibition on extra base runs. Proposed names below are explicitly new. Implementation status: **all phases pending**.

## 1. Two corrections that determine the design

**A current result and a before/after comparison ask different questions.** Read each result at exactly the reach digest of the program it describes. Do not require the *two programs* to have equal digests to identify a transition. On my SCI branch, `f` returning 1 gave its test a green result at digest `3864e0…`; changing it to 2 gave a red at `d3bcc9…`. Looking for `d3bcc9…` in the earlier database answered `{}`. Looking at each database's own digest answered green → red. These are observed results, not a causal experiment: identical program/input/evaluator with different outcomes is inconsistent evidence, not evidence that an unchanged function caused a regression. Q2 makes the wording decision explicit.

**The current gate and the target gate differ.** `seon.cluster.branch-evidence/test-evidence` calls `red-at-current-reach?` on main at main's own digest; it does not require cross-branch digest equality and does not compare failure identities. `verdict` then requires `:seon.source/accepted-red-before`. The hybrid M7 replaces the merge rule and recognizes designed reds. Today `seon.context/designed-red?` even accepts a plain `:fail`, without an open-task argument (live probe returned true). Do not describe the stricter hybrid rule as installed. Extend M7's existing task/proof, rather than introducing another gate in this project.

## 2. The data and how main gets it

A run remains one admitted selection and its observed completion. Reuse `:seon.test.run/id`, `/at`, `/basis-t`, `/program-digest`, `/input-digest`, `/loaded-commit-id`, `/git-sha`, `/branch`, `/tested-branch`, `/members`, `/covered-by`, `/exclusions`, `/stopped`, `/terminated?`; each selected test keeps `:seon.test.member/symbol`, `/reach-digest`, `/evaluator`, completion/exit facts, counts and report refs. Keep symbols as values that outlive deleted declarations. Keep owned components and peer reports under their existing schemas. No baseline entity, file, per-test status attribute or copied agent verdict.

**Recommended Q1: runs remain `:seon.data`; root records its observations.** The source branch's execution history is not program to merge. A root gate's run on the merge result is root's own observation: root's accepted merge transaction records that completed selection with the merged program. Its stored destination is main; `/tested-branch` and the original tested basis describe execution. Preserve the run id and original time; destination transaction ids describe admission/completion at the destination, never pretend a source transaction id is a main transaction. Reuse `seon.test/admit-run` and `seon.test.runner/record-tx`, composing their transaction data with the program transaction at the writer. Reused coverage must resolve to already retained main runs or include its complete owning run/report facts in that same recording; never leave refs into a deleted branch.

This needs an explicit change at admission, not a permissive caller: today `admit-run` rejects native cross-branch recording (`m1-notes.md`). Separate tested provenance from recording destination in the existing recording request and validate against the prospective merged program the writer holds. M7 already compares the tested definition digests inside the writer; consume that decision. Do not add a second comparison, lock or retry. Ordinary agent runs still record only on the agent's branch. No proposed admission relaxation allows an agent to write main.

Root's restart batch runs the already-authorized affected/compiled tiers and records each completed batch durably on main. A full initial round is an owner scheduling decision, not a prerequisite to reading results: untouched tests remain unknown. For tests needing an isolated JVM, preserve that isolation and use `seon.test.platform/record!`'s existing runner admission/completion path; generalize its result transport to retain tested digests, evaluator and exit evidence. Main receiving a result does not imply it describes main's current program: the exact reach/input read decides that. A rejected candidate's results stay with its proposal and do not become main's current results.

Root records each central batch before its disposable root can be removed. `seon.central-round/run-round!` already receives a recording function; replace the `-main` function's authoritative `OUT` text with runner-owned recording and run ids, and retain ordinary diagnostic output only as presentation. Do not import the old 2,832-result text as trusted evidence: the dig's historic counts describe another commit, and the original store is gone.

**If Q1 instead makes runs program:** change `/run`'s partition and the merge composition in the same phase; merge complete admitted run values, their owned selections, report dependencies and reused coverage, not arbitrary component datoms. Remove destination-local transaction refs from the portable form by deriving them on destination admission. Every reader still tests content and evaluator. This costs a further portability phase; simply changing the partition keyword is incorrect. No new run identity or status format is needed under either choice.

## 3. One query and what it can honestly say

Proposed public owner: `seon.test/compare-results`, contract
`[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.test/identities] :seon.test/comparison]`.
Inputs are held immutable **before**, **after**, and the selected tests. The new result schema lives in the existing `seon.test.edn`; record its vocabulary before code. `my.test/changes` is a thin agent entrance acquiring the agent's merge base and branch; it offers an explicit identities argument for a requery. Root calls the same owner with main B and the tested merge result. `bin/seon check` supplies the candidate's actual parent and candidate, not whatever main happens to contain later.

For each test, derive each side's reach once, batch the reads, and return:

- the two stored result references and their observed outcome, reach, input digest and actual member evaluator;
- the exact-match result on before at after's reach (unknown when absent), so the requested same-reach question is answered literally;
- the before/after transition, including report identities added and removed; unknown with its missing evidence when comparison is unavailable;
- whether the current red is designed, plus its open task references; declaration absence is distinct from missing execution.

The minimum validity key is test symbol + reach digest + input digest + actual evaluator. Use the runner's existing source/fixture and loaded-program evidence rules. `result-at-reach` currently filters only reach; add a separately named runner read with the fuller request, sharing `newest-results` and `recorded-member-results`, rather than silently changing M1's existing contract. Convert current-result readers to that read in their owning phases. Selection and `verified?` must use the same validity predicate; do not repeat it in the renderer or shell.

| Evidence | Answer |
|---|---|
| After has no current completed result, missing digest or necessary provenance | Current outcome unknown, reason and available run refs; never borrow older green |
| Before has no comparable result | Red now, attribution unknown; zero test execution to answer |
| Before green, after red, reach changed, inputs comparable and evaluator equal | Observed green → red in this change; proposed `:introduced` transition (Q2) |
| Both red | Compare stored failing report identities; same set = unchanged failure; added identities = additional failure; counts alone decide nothing |
| Before red, after green | Fixed in the observed comparison |
| Test absent before / absent after | Added / removed declaration, not missing execution; an added ordinary red is not automatically a designed red |
| Same full validity key has contradictory completed observations | Latest raw outcome remains visible; comparison/usable proof is unknown, with both run refs; no rerun loop |
| Body completes beyond its declared bound | Ordinary red with the runner's duration failure, not a transport unknown |
| Bound fires without observed exit, or no member completion | Unknown; the execution owner retains its resources until exit |
| Every current failure is typed `:seon.error/unimplemented` and the test tags an open task | Designed red under M7; retain its error counts and task refs; never rename it green |

Failure reports already have identities (`report-identity`, `report-entity`, `/failures`). Read those facts; do not hash newly printed messages. Different ids are conservatively different observations. Duration failures currently include observed milliseconds in printed report identity, so two overruns need not compare equal. Show both observations and unknown attribution when they conflict at one validity key; do not parse their strings or claim repeated timing measurements prove a new code failure. Changing that producer's error shape belongs to the existing report-data task, not an extra normalization layer here.

No flaky flag, registry, quarantine or exemption list. Contradictory retained observations are data. Scan only matching executions of selected tests, with early termination once a contradiction is established. A flaky name with no observed contradiction is not magically detectable.

An agent compares against its held merge base using results inherited on its branch, including its existing prove-first run at the base digest (M1). It does not acquire main or invoke another agent. Root compares against current main when testing the merge result. Both use the same query with different explicit before values; label those bases in the result. An outside branch comparison is informative, while root's merge-result comparison is the acceptance evidence.

**Acceptance is separate from attribution.** Keep raw `:seon.test/passed?` meaning all requested tests passed. The hybrid's `seon.context/step-status` remains the sole task/merge decision over the comparison. A test being red before is not by itself a green proof or coverage of a changed function. The recommended Q3 rule allows only green or M7-designed reaching evidence; ordinary inherited reds remain visible and prevent acceptance in that selected set. Unrelated suite reds are outside the selected set, without any exclusion list. M1's `:satisfied` behavior and no-extra-base-run ruling remain.

## 4. Agent first, then the outside entrances

`seon.turn/test-step` performs the tests it already owes, then reads the comparison once. Its reply summary and `seon.context/current-state` derive from that same value; neither writes `:seon.agent/broken` or `/new-red`. On a later request, derive again from content-valid results. Reuse existing `seon.cache` and read evidence if profiling warrants it; never use the whole database commit as a reason to recompute all test reaches.

Proposed compact render (keys owned by the new comparison schema; illustrative counts, not a live result):

```clojure
{:seon.test/selected 12
 :seon.test/outcomes {:green 8 :red 2 :error 1 :unknown 1}
 :seon.test/transitions {:introduced 1 :unchanged 1 :designed 1 :unknown 1}
 :seon.render/form (my.test/changes)}
```

Approximately 70–100 tokens for counts and the requery; a shown changed test with its symbol, run refs and reason costs approximately 40–80 more. These are estimates to measure with the final printer, not another truncation budget. Show compact counts first; details and full reports through `my.test/changes` with selected identities and existing run/test render forms. Use `seon.render.test`'s data face and the one value printer. No shell diff of names, counts or printed failures.

“Which tests are red on main?” is the same read with the explicitly requested main test population. It costs the population's reached graph and matching executions. Offer its requery in the block; do **not** perform a whole-suite current census at every eval request. A central round can show its stored batch outcomes with their tested program; label these historical until current reach is checked. This avoids a stored total that silently becomes stale or a whole-suite refresh hidden in rendering.

`seon.cluster.branch-evidence/branch-evidence` retains file equality, coverage and platform provenance responsibilities; its tests and decision come from this comparison and the same `step-status` rule. `bin/lane` consumes that answer, deletes `--accept-red-before` and its trailer, and prints through the existing value printer. No separate shell decision about reds.

`seon.operator/check!` still proves publication and boots a candidate JVM for host-bound changes. `bin/test-check` returns structured runner evidence, including unknown/unfinished execution, through the existing transport. Root records it on the candidate proposal before disposal; then the same comparison and acceptance rule answer the check. A nonzero all-green exit is no longer the operator's independent classification. Boot/publication errors still answer as errors. MCP `eval_clj` already enters the same turn/reply path: it needs no new red classifier or MCP endpoint.

## 5. Phases, proofs, ownership and deletions

Each phase is a roughly fifteen-minute card; split a card before launch if its stated files cannot fit. New tests below are **proposed**, not tests claimed to have run: the column states the failing HEAD behavior/counterexample. Use existing fixtures and extend an existing table for the same behavior. Before assignment, install and run the named red proof through the one runner. Platform/recording and script cards use the isolated commit path; ordinary readers use branch evidence. All time limits below are **acceptance targets**, not measured gains; measure the complete named entrance with `seon.profile/measure` on parent and candidate, including first acquisition and allocation. Ordinary assertions stay within the runner's five-second bound; existing declared-long integration fixtures retain their stated reasons.

| Phase | Files owned; change | Red proof, assertion and why red on HEAD | Cost target |
|---|---|---|---|
| T1 | `src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.edn`, `test/seon/test/runner_test.clj`: full-provenance result read sharing existing queries; additive API; retain `result-at-reach` for M1 | New `seon.test.runner-test/current-results-separate-evaluators-and-expose-conflicting-runs`: runner-admitted same-reach runs with distinct input/evaluator do not certify each other; contradictory matching completions answer unknown with both run refs. HEAD's reach-only newest read cannot answer this. | Warm selected-three read <20 ms; no all-suite scan; allocated bytes recorded |
| T2 | `src/seon/test.clj`, `resources/seon/schemas/seon.test.edn`, `test/seon/test_test.clj`, `docs/seon/architecture/vocabulary.md`: `compare-results`, shared evidence validity with `verified?`; no caller removed | New `seon.test-test/stored-results-distinguish-a-new-failure-from-an-unknown-base`: table built through the runner, asserts the transitions in §3, including green at R0 → red at R1, exact-match unknown, same counts/different failure, missing before, pending and removed test. HEAD's boolean classifiers cannot represent these; §6 reproduces R0/R1. | Compare three stored tests <25 ms warm, <1 s acquisition; zero test-body executions |
| T3 | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/test/platform.clj`, applicable existing `seon.test.run.edn` request schema, `test/seon/test/platform_test.clj`: separate tested basis from root recording destination in existing admission/completion composition | New `seon.test.platform-test/root-records-the-tested-result-without-running-it-again`: canonical run of one isolated fixture, admit its root observation at the matching prospective program, pull counts/reach/evaluator and reports after source branch deletion; assert unchanged execution count. HEAD native admission rejects tested branch (M1 probe); do not fabricate terminal facts. | One small completion transaction <100 ms; zero extra body executions |
| T4 | Coordinate with hybrid M6/M7 owner: `src/seon/merge.clj` (target file; today merge orchestration is `src/seon/cluster/source.clj`), `test/seon/cluster/merge_test.clj`: accepted program and gate observation recorded together through T3 | New `seon.cluster.merge-test/main-keeps-the-merge-results-after-the-branch-is-deleted`: after merge/delete, main reads exactly the tested result and successor inherits it; current data partition does not carry the run. | Recording increment <100 ms, full one-change merge <1 s excluding due body work |
| T5 | `script/seon/central_round.clj`, `test/seon/central_round_test.clj`: record each completed root batch through T3 before disposal; delete mandatory `OUT` authority in `-main`, retain diagnostic presentation | New `seon.central-round-test/a-completed-batch-is-readable-after-its-root-is-removed`: a two-test real batch leaves main result refs and counts without reading a file; HEAD leaves them only in disposable store/OUT. | Same test-body cost, recording <100 ms per batch; no compulsory full round |
| T6 | `src/seon/turn.clj`, `src/seon/context.clj`, `src/my/test.clj`, `src/seon/render/test.clj`, schemas `seon.agent.edn`, `seon.context.edn`, `seon.turn.loop.edn`, `my.test.edn`; `test/seon/{context,turn,repl}_test.clj`, `test/my/test_test.clj`: agent comparison and render, remove `record-broken!`, `test-fork-point-red`, `base-results` after converting their callers; retract old agent judgment facts and remove their schema entries together | Extend `seon.context-test/each-step-kind-is-done-by-its-one-rule` with explicit unknown-before and observed unchanged failure; new `seon.repl-test/reply-and-current-state-show-the-same-test-comparison` asserts equal counts/run refs from both entrances and zero new test runs on a read. Convert `seon.turn-test/the-test-step-writes-nothing-on-main`'s stored-broken assertion to derived data. HEAD stores judgments and shows no common comparison. | Summary/query <25 ms warm; full read-only eval <1 s; no test run for base evidence |
| T7 | In hybrid M7, `src/seon/cluster/source.clj`, `src/seon/merge.clj`, `src/seon/context.clj`, `src/seon/test.clj`, `test/seon/{test,cluster/merge}_test.clj`: consume comparison in the one step rule, strict designed-red rule; delete `change-reds` and now-unreferenced `fork-point-red`; convert schema descriptions and task references listed below | Extend M7 `seon.cluster.merge-test/a-prove-steps-designed-reds-pass-the-gate`: typed stub/open task accepted, plain assertion or closed task not designed; same-count additional failure not inherited. Live plain-assertion probe is true on HEAD. Retire obsolete `change-reds` test with its subject; table in T2 preserves its surviving question. | Stored gate comparison <100 ms; zero base runs |
| T8 | `src/seon/cluster/branch_evidence.clj`, `bin/lane`, `resources/seon/schemas/seon.source.edn`, `test/seon/cluster/branch_evidence_test.clj`; current RF-2 docs and obsolete task intents: shared acceptance answer, delete accepted-red flag/attribute/trailer in the same commit | Extend `seon.cluster.branch-evidence-test/a-branch-proves-its-files-only-with-current-green-evidence`: designed red gives the same answer as M7 with no accepted set; arbitrary inherited red follows Q3. HEAD requires an accepted set and recognizes no designed-red proof. | Selected-three evidence query <1 s end to end, no JVM boot |
| T9 | `script/seon/operator.clj`, `bin/test-check`, `src/seon/test/platform.clj`, `test/seon/operator_test.clj`, `test/seon/test/platform_test.clj`: preserve structured candidate results before deletion and use T2/T7; delete exit/text-based red classification in `run-candidate-tests!`/`check!` | New `seon.operator-test/check-and-branch-evidence-agree-on-stored-test-results`: same candidate/base and stored green, designed, ordinary red, missing evidence cases produce equal acceptance data; candidate failure remains accessible after child exit. HEAD derives tests-passed from process exit. | Comparison/recording overhead <100 ms; separately measure existing boot/body cost, no extra base JVM |

T1 → T2; T3 → T4/T5; T2 → T6 → T7 → T8/T9. T4/T7 join the existing M6/M7 cards, not competing implementations. Run files have one owner at a time. All additions compile with their consumers before activation; old callers keep their contract until their converting phase. No namespace or dependency rename is needed.

**Deletion completeness, searched across source, tests, scripts, schemas and task references:**

- T6 removes all `record-broken!`/`test-fork-point-red` uses in `seon.turn`, agent judgment reads in `seon.context`, their `seon.agent.edn` entries and the `seon.turn.loop.edn` description; converts the assertion in `test/seon/turn_test.clj` and the P3 intent in `resources/seon/tasks/seon.turn.edn`. T6 owns that task file as well. `base-results`'s M1 behavior survives through the shared query; its proof stays.
- T7 removes `change-reds`'s call in `seon.cluster.source/accept-merge!`, its `red-test-verdicts` description, the one `test/seon/test_test.clj` regression and comments in `test/seon/cluster/merge_test.clj`; `fork-point-red`'s final caller is `change-reds`. Convert references in schemas `seon.test.edn`, `seon.test.member.edn`, tasks `seon.cluster.squash.edn` (or `seon.merge.edn` after M6), `seon.turn.edn`, `seon.context.edn`, and `docs/seon/plan.md`. Leave the independently useful interpreted-evaluator request option: its description is not a reason to delete the option. Dated research remains history.
- T8 deletes the flag's help/parser/transport/trailer uses in `bin/lane`, the request attribute and verdict argument in source/branch evidence, and the test fixture's accepted argument; revise `docs/seon/schedule/cards/RF-2.md` and `docs/seon/schedule/agent-loop-ledger.md`. Retire/convert the obsolete flag-extension intent in `resources/seon/tasks/seon.operator.edn` and flag reference in `seon.task.edn` in T8. These are additional owned files of T8. Search quoted symbols, schema keys and task intents again at execution time.
- T5 replaces future dependence on central `tmp` output; it does not delete someone else's existing evidence files. Manual comparisons disappear as a workflow, not a fictitious source-line deletion. The transient platform result transport may remain; it is not a baseline and is no longer the only surviving evidence.

Measured declaration spans now: `fork-point-red` 34 lines, `change-reds` 15, `record-broken!` 41, `base-results` 16 (106 removed); `test-evidence` 33 and `verdict` 22 are rewritten, not counted as wholly deleted. Estimated source/script delta: remove 150–190, add 220–300, **net +30–150**; schemas net +35–65; tests net +140–240. These are planning estimates, not measured implementation sizes. This buys one comparison and durable observations while retiring duplicate judgments; do not add another result index, state machine or scheduler to meet the estimate.

**Performance prerequisite:** reuse the existing `seon.test.runner/tests-reaching-rebuilds-the-whole-program-reach-graph-per-change` and `verified-recomputes-reach-digests-for-the-whole-program` tasks. The new comparison accepts the selection the test step already holds; it must not call `tests-reaching` once per changed function or derive every test for a headline. T8 must convert `test-evidence`'s per-function reach loop to the owner's selected set. A claim of cheaper evaluation needs the existing end-to-end eval probe measured before/after; a query-call count alone is insufficient. No new performance architecture is proposed here.

## 6. Reproduction and limits

Read-only JVM probes used explicit root/default through MCP `eval_clj`; definitions/tests used only SCI agent `plan-tr-astra`. Tool discovery exposed `mcp__seon__eval_clj` directly (no ToolSearch tool was offered). `bin/seon status` reported loaded archive `c792b6aaf36d3927bae68b8d71115bbbe7ad0a88`; checkout HEAD was `7fea1f2e7` while reading. Default was available but faulted: two open-error signatures, SCI acquisition observations including task handles `9d357e01233e`, `95142c18c8d3`, `c0b0ec672675`, `7dff9f6d4f5b`, `7d16300fe6b5`, `8cd307285a10`. No stop/reset/reload or uncommitted JVM source load. Foreign working edits in `src/seon/test/runner.clj` and `test/seon/test/reach_test.clj` were left alone; measured claims below are the installed system, not those edits.

**P1, live facts.** With `d = (seon.db/db (seon.cluster.boot/connection "default"))`:

```clojure
{:runs (count (seon.db/q '[:find [?e ...] :where [?e :seon.test.run/id]] d))
 :members (count (seon.db/q '[:find [?e ...] :where [?e :seon.test.member/symbol]] d))}
```

Answered `{:runs 0 :members 0}`; 2,872 stored test declarations. The earlier aggregate `count` query returned nil on the empty set, so the count-of-vector form above is the reproduced zero. `result-at-reach` on main for a selected test answered `{}`. A `seon.profile/measure` around the three counts plus absent-result read: 3.93 ms, 5,674,320 allocated bytes, four reads; full MCP request 56.6 ms. The installed Malli registry's `/run` form has `:seon.program/partition :seon.data`, verified by:

```clojure
(let [p (seon.schema/projection-from-database d)]
  [:installed-run-properties
   (second (malli.core/form
            (malli.registry/schema (:seon.schema.projection/registry p)
                                   :seon.test.run/run)))])
```

**P2, a real changed reach.** First SCI form was `(seon.context/current-state)` (190.7 ms whole request). Then these requests, in order:

```clojure
(in-ns 'plan-tr-astra.probe)
(require '[clojure.test :refer [deftest is]])
(defn f {:malli/schema [:=> [:cat] :int]} [] 1)
(deftest f-is-one (is (= 1 (f))))
;; Next request, after the automatic green run:
(def base-db (seon.db/db))
(def base-reach (seon.test.runner/reach-digests base-db ['plan-tr-astra.probe/f-is-one]))
(defn f {:malli/schema [:=> [:cat] :int]} [] 2)
;; Next request, after the automatic red run:
(let [d (seon.db/db)
      r (seon.test.runner/reach-digests d ['plan-tr-astra.probe/f-is-one])]
  [(seon.test.runner/result-at-reach base-db r)
   (seon.test.runner/result-at-reach base-db base-reach)
   (seon.test.runner/result-at-reach d r)
   (seon.test.runner/result-at-reach d base-reach)])
```

Answers: absent, pass/fail `[1 0]`, `[0 1]`, `[1 0]`; both actual evaluators `:interpreted`; both input digests `bce5343293a60d12a430e418c803efbb64c7556eb5fefe6716ad5a1ee3d57855`. Requests defining green/red took 518.5/966.6 ms; test-step allocations 412.2/842.8 MB, versus body allocations 6.74/7.22 MB. This supports the existing reach/test-step performance tasks, not a new benchmark claim. The follow-up read was 148.3 ms whole request. A measured paired result read:

```clojure
(seon.profile/measure
 (fn [] {:before (seon.test.runner/result-at-reach base-db base-reach)
         :after (seon.test.runner/result-at-reach
                 (seon.db/db)
                 (seon.test.runner/reach-digests (seon.db/db)
                   ['plan-tr-astra.probe/f-is-one]))}))
```

3.69 ms, 5,172,040 bytes, 21 reads; whole request 110.5 ms. The executed measurement held the after database once in its outer `let` (same immutable value for both calls).

**P3, fresh reach calculation.** JVM root/default, hold `d` once; call this twice, selecting measured ms, allocations, reads and hot functions from the result:

```clojure
(seon.profile/measure
 #(seon.test.runner/reach-digests d
    ['seon.context-test/each-step-kind-is-done-by-its-one-rule
     'seon.test.runner-test/a-result-at-the-base-reach-digest-survives-later-runs
     'seon.cluster.branch-evidence-test/a-branch-proves-its-files-only-with-current-green-evidence]))
```

All three names first confirmed by Datalog. First: **410.72 ms, 683,172,888 bytes, 7,657 reads**; repeat: **1.92 ms, 1,538,696 bytes, zero reads**. Whole request 486.1 ms. First calculation called `reach-index` once and `reach-component-digests` twice; largest self costs: `seon.db/pull-logical` 99 ms, `carried-projection` 39 ms, `structural-digest` 32 ms. This is a cold *requested graph/index* on a running JVM with partly warm dependency/node caches, not a boot-cold JVM. Earlier exploratory selection had two real tests and one absent guessed name: 302.36 ms/557,664,064 bytes; it is **not** a three-real-test benchmark. No shared caches were cleared. Therefore the dig's 2.1/3.1/5.9-second fully cold figures are not reconciled as equal experiments; root's next restart must measure this exact form before any reach acquisition to establish boot-cold cost. Do not budget repeated reads as though 1.92 ms paid first acquisition.

**P4, designed red.** JVM read-only:

```clojure
(#'seon.context/designed-red?
 {:seon.context/proof-status :red
  :seon.context/proof-failures [{:type :fail}]})
```

Returned `true`; T7's stricter data assertion is red on today's body. This finding belongs to the already-filed M7 task `seon.cluster.squash/a-merge-whose-reached-definition-moved-writes-nothing`, whose intent also covers designed reds.

Dependency seams read: Datahike gitlink `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, `datahike.versioning/branch!` shares stored roots; `commit-as-db` opens a held commit, preserving attached cache identity. Inputs: connection/value and commit; recomputation: a new selected commit, never a new result format. Seon's `seon.db/commit-database`, `with`, `prepared-write` retain its writer validation. Malli gitlink `dd13ff81139159778edb0a67b491eccc0baa991d`, `malli.core/validator` and `explainer` cache on the compiled schema; compile the new named comparison once per supplied registry and validate at the producer. No dependency addition; no claim that Datahike supplies Seon's semantic merge or that an immutable held value alone pins GC history.

Verification limits: no full round, no gate-result transfer or new query implemented, no timing of extra base execution (the recommended design removes it). New phase proofs have not been run or mislabeled green. `clj-kondo --lint src script test --cache false --config '{:output {:summary true}}'` completed in 6,952 ms with **0 errors, 1,585 warnings** in the shared tree; these warnings are foreign to this doc-only slice. No source/test change or test run is required for the document commit. Cleanup uses `(my.agent/retire-branch! {:seon.store/branch (get-in (seon.db/db) [:config :branch])})`; an initial zero-argument call returned the declared arity error (caller mistake, 108.1 ms request), without changing the branch.

Cleanup observation: that call closed admission and returned `:my.agent/retiring? true` for `:agent-3a5960b1bfdd` (143.3 ms). A subsequent root read still found the branch: `seon.cluster.agent/release-context!` has not released its owning handle. The default routing's child for this branch holds only keys `[branch nil]` and `[branch "plan-tr-astra"]`. The owning root must release that closed probe's execution before actual deletion; I did not mutate root routing under this assignment's read-only JVM restriction. This is the existing lifecycle issue named on `my.agent/retire-branch!`, `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion`. One initial roster read passed a request map where `seon.cluster.registry/branches` takes the store itself; it returned its input contract error (52.7 ms), then the corrected read above succeeded. No other lane's context was touched.

## 7. Decisions for the owner

Prices are implementation estimates in fifteen-minute cards, plus separately stated execution time. The recommendations do not authorize new broad test runs.

| Question | Options, guarantee, cost and sacrifice |
|---|---|
| **Q1: are runs data or program, and what does main retain?** | **A — data, root observations (recommended):** T3–T5, roughly 3 cards after the common reader work. Retain the already-required merge-gate and restart-batch runs; no extra base bodies. Ordinary source histories stay private; untouched coverage remains unknown. **B — program evidence:** A's destination-admission work plus 1–2 portability cards; all completed program-linked runs travel through the semantic merge, including reports/coverage. More history and provenance work, no stronger evidence at an untested digest. Both choices use the same runs and query. |
| **Q2: what does “my change caused this red” promise?** | **A — literal cross-branch same digest only:** smallest, no transition logic beyond T1; changed-closure reds usually remain unknown. Equal-key disagreement cannot honestly establish causation. **B — before/after at each side's exact digest (recommended):** T2, one card; shows the observed introduced/additional/fixed failure and both run refs, with the exact-match lookup also present. No claim of experimental causality or automatic reruns. Missing/evaluator-incomparable evidence stays unknown. The P2 counterexample rules out calling A a complete regression classifier. |
| **Q3: what accepts an ordinary unchanged red?** | **A — green or designed evidence only (recommended):** aligns the hybrid M7 coverage requirement and AGENTS.md's positive-proof rule; T7/T8. A reached ordinary red stops acceptance even when attribution says unchanged. **B — also accept an identical ordinary pre-existing failure:** same query, approximately one additional table case/rule edit; more commits can proceed, but explicitly weakens the positive-proof/green-coverage rule and must be ruled into M7 and AGENTS.md. Neither choice retains `--accept-red-before` or lets unknown become green. |
| **Q4: initialize untouched main coverage now?** | **A — accumulate from planned merge/restart work (recommended initially):** no extra execution, unknown stays visible. **B — one explicitly scheduled full central round:** complete observation for that tested program, historical dig cost 61.2 minutes for 2,832 tests (not a prediction for today's 2,872); run once, then incremental affected work. Repeating the full suite at every restart is not necessary for this design. |

The hybrid can choose A/B on Q1 without changing the query, choose the comparison promise explicitly on Q2, and apply the one owner-approved acceptance rule on Q3. No phase guesses those rulings from a flag or a missing run.
