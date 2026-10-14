---
title: "TASK-CHAIN: second independent review, Astra"
type: review
date: 2026-10-14
basis: 2b5b9ccf6f0952ccbd42293669f797babb52dc1a
status: complete
---

# TASK-CHAIN: second independent review, Astra

Review target: `2b5b9ccf6f0952ccbd42293669f797babb52dc1a`, 2026-10-14. **Mostly sound; refine the execution contracts below before assigning their slices.** Keep the single branch, ordinary tasks, explicit proofs, new agent per task, and one final merge. The remaining problems need owner-level corrections, not additional lifecycle machinery.

Read: CLAUDE.md, TASK-CHAIN end to end, both first-round reviews, and all eleven requested evidence notes. Code findings below are observed at the target; failure scenarios are inferred unless marked **live**. First-round decisions already incorporated—branch discovery, retention claims, cardinality-one next, source-side merge reads, and separate intermediate/final judgments—are not reopened here. Proposed symbols are proposals, not installed APIs.

## 1. S3 readiness contradicts completion and explicit resumption

**Evidence/scenario.** §2.2 starts a running task with no open attempt, defining open as no outcome. Advance closes A with `:done` but leaves A running. That rule makes A eligible again alongside B. Conversely, §2.7 leaves a bailed attempt without an outcome; setting its task running cannot create a new attempt. `seon.task/ready` also excludes historical blocking ends, independently of the proposed open-attempt rule. `seon.cluster.agent/plan-facts` currently queries attempts without an **end**, so changing only that query is insufficient.

**Replace §2.2's eligibility sentence:** “Start a task on this chain's next path only when its execution is running, its latest attempt is neither done nor merged, and no attempt lacks an outcome. A processed unsuccessful attempt is closed with its reason; its task stays held until root explicitly runs it. Historical closed failures do not prevent the next numbered attempt.”

**Add to §2.7:** “Persist the deterministic problem task on main first; then write the failed attempt's closed outcome/reason and the task's held execution together on the chain branch. Repeating either write adds no second problem. Root's run operation closes any still-undecided ended attempt and changes held to running in one branch transaction. Cancelled attempts close and retire through the existing cleanup path without a problem task.” Two databases cannot participate in the claimed single branch transaction; this order makes both stopping points derivable without an outbox or retry service.

**Red assertions:** after A advances, repeated passes never start A again; after a capped A is processed and explicitly run, exactly one new agent/attempt starts on the same branch; repeated wakes before that run start none. Assert preserved end/reason, one problem, increasing attempt number, and no successor start. These belong to S3a/S3c, not a later live experiment.

## 2. “Touch the chain fact” does not specify a wake

**Live evidence.** Reasserting an existing held execution through `datahike.api/with` produced zero execution datoms and only the transaction-instant datom. `seon.cluster.wake/route!` offers a starting wake only for an **added** datom of a starting attribute. Its current route therefore also contradicts §1's claim that declaring starts makes retraction wake root. The two chain-fact values do not change when root resumes a failed task.

**Replace the run/wake text:** “`my.root/run!` durably changes execution on the chain branch, then offers a payload-free wake to the existing root plan channel. Boot's normal full derivation sees a committed run even if the process stopped before the offer. No main-side touch is required.” This composes the channel already supplied to `seon.cluster.wake/route!`, without retaining an otherwise unused branch connection, inventing a timestamp, or faking remove/add. `seon.cluster.agent/start-branch-flow!` and `seon.cluster.agent/apply-lifecycle!` establish why the stopped branch's listener alone cannot suffice. If the owner instead wants chain-lifetime listeners, explicitly move that existing route's lifetime to the chain connection owner; “touch” alone is not implementation text.

**Red:** stop/join the failed agent, leave the chain held, resume it with no unrelated main write, and observe its next model call. Specify whether chain-fact retraction needs a pass; if it does, convert the route's addition-only condition with a focused retraction case, otherwise delete that claim.

## 3. S2a needs a content identity, snapshot boundary, and exact-result lookup

**Evidence.** `seon.test.runner/task-reach-digests` hashes its task input. §4 now puts two complete database values in that input; literally hashing that value makes recording a result change the next result's identity and defeats S2a's unchanged-request assertion. Meanwhile `seon.context/current-results` calls `seon.test.runner/latest-results`, which chooses the newest result per test **before** filtering by reach. A later execution of the shared template proof for B can hide A's valid result. The existing `seon.test.runner/result-at-reach` already solves the latter problem.

The spec also says the requesting database is captured “at the request's commit” while requiring handoff proofs to read results produced later in that request. Ordering the tests alone does not update an immutable value.

**Add to S2a/§4:** “The execution binding carries the task and held branch/main values; database objects and commit ids are not themselves proof-input content. Task declaration content, successor proof content, and captured reads determine reuse for task-reading tests. After selected dependent tests have recorded, capture the branch value supplied to the current task's own proof. Lookup recomputes the same input identity and uses `seon.test.runner/result-at-reach`, including when another task ran the same deftest more recently. Root re-judges the recorded proof against the held task/end basis; attempt counters, end recording, and unrelated evaluations do not invalidate it. Next-task proofs run under the next task's input; a nested invocation inherits its caller's input unless it explicitly selects another.” Keep read-evidence ownership in the runner, not a second stage cache.

**Extend the S2a red:** A-red, B-green, then read A and B without rerunning either; both results remain available. Recording an unrelated evaluation preserves reuse; changing A's plan or a result its handoff read invalidates A only. In one request, record a previously missing successor result and then prove that the handoff sees it. The input/identity/read-evidence schema must be written before the body; the present sentence “one value flows” is not enough for this slice.

## 4. The table's done rule is still the superseded rule

**Evidence.** S2b explicitly accepts “proof green with a new red test outside the proofs.” §2.4 now refuses it unless that red belongs to the **next** task's proofs. `done-rule-table.md` row 4 tested the earlier permissive rule; it did not test the final stray-red rule. Its eight green rows do not prove the current combined judge.

**Replace that S2b row:** “Own proof green + branch-new red outside next proofs → stray-red refusal; put the same red in next proofs → no stray-red refusal; terminal task + branch-new red → refusal. Empty, missing, stale, assertionless, or red own proofs never finish. Main-green/current-branch-red refuses using main's own reach; absent main result is unknown, not regression.” Preserve the existing contract-gap and contracted-uncovered-stub rows. Reuse existing refusal variants where their meanings match; name/register any genuinely new variant in `:seon.source/refusals` before implementing.

**Additional selection trap:** `seon.test/changes` is not an empty-set query: it returns `:seon.test/policy-unscoped` when nothing changed. Calling it after every ordinary evaluation would turn an untouched Design task into a request error. **Add:** “Extract/reuse the existing pure branch-delta derivation beneath `seon.test/changes`; zero delta is an empty selection to which explicit task proofs are added. Keep the public unnamed-check refusal for callers that requested nothing.” Assert a plan-only update still runs its proof and produces no selection error.

## 5. S1c still deletes readers before their replacements

**Evidence.** S1c deletes `seon.task/effective` and `seon.task/goal-owner`; the current `seon.test.runner/task-input` calls effective, `seon.task/obligations` calls both, and `my.task/refine!` calls goal-owner. Their replacements are assigned to S2a/S2c and S1d. “With callers” hides several later slices inside S1c. Deleting children also affects execution/readiness and merge task-basis derivation, not just chain construction (`seon.task/execution`, `seon.cluster.source/tested-task-basis`).

**Replace S1c's deletion clause:** “S1c changes the constructor and transaction producer. Delete effective with the conversion of its last task-input/context caller; delete goal-owner with refine/obligations' last caller; delete children/needs and their admission/readiness/merge readers in the corresponding conversion commit. No compatibility copy is introduced. The pre-brief assigns each deletion and every remaining caller to one named commit.” Thus S1c can be proved before S2 without pretending the full cut fits that lane. Publish retirement of attributes only with those consumers converted.

## 6. S4's unbound test and S5's Prove experiment cannot both work as written

**Evidence.** S2a says a task-reading test without a task returns missing input; §4 says the same shared handoff deftest runs its table unbound and merely skips its bound assertion. `seon.test/task` currently returns nil when unbound. Separately, `seon.step/prove-handoff` requires a next task, so S5's **one-task Prove chain** always fails no-proofs regardless of its work.

**Exact edits:** “`seon.test/task` is an optional reader: without an explicitly supplied task it returns nil and never resolves tags. A template deftest always runs its table; when the reader returns a task input, it additionally asserts that task's predicate. A request explicitly naming a nonexistent task reports missing input before execution.” This needs no test exemption or second reader.

“The isolated Prove trial uses Prove followed by a held Implement task to receive its proofs. Until S3b is installed, inspect the real handoff and stop that disposable trial before terminal acceptance; never weaken the predicate for a missing successor. A standalone Design trial can be graded at its end even when final acceptance refuses its uncovered stub.” Keep the production mechanism unchanged; stage trials are experiments with explicit observation/stop boundaries.

## 7. Three remaining contracts need precise choices

**Constructor identities.** §2.1 allows later explicit names but claims positional next links make cycles impossible. Two maps with the same ns/name have the same `seon.task/task-handle`; Datahike identity upsert collapses them. A first name `x` followed by explicit `x` produces a self-link. **Edit:** “Only the first map supplies task identity. The constructor derives every later ns/name from that first identity and its 1-based vector index (`x-1`, `x-2`); supplied id/sym/next never determine identity or links.” This removes the ambiguity at construction rather than adding a cycle detector. If caller-selected later names must remain, their uniqueness needs an explicit input contract instead of the current impossibility claim.

**Updates of identity.** §3a permits any declared member except execution/next, therefore ns/name too. `seon.task/declaration` contains both; changing either derives a different handle while next still refers to the old task. **Edit:** “Update operates on declared content members; identity, link and control are constructor/root inputs. Its key schema is derived from the same member kinds used by advance.” This is the declared API, not a check pasted beside the writer. Add a contract case for ns/name and preserve the member-level CAS case where an unrelated proof edit does not reject a plan edit. Also specify template's explicit database arity, with omitted database filled from the caller's declared binding.

**Fork target.** `seon.cluster.registry/fork!` defaults target to parent. For `:seon.chain/from old-chain`, that makes old-chain the merge target/base unless supplied otherwise. **Edit:** “Always pass the cluster main branch as `:seon.branch/target`; `from` selects the starting head only. Reuse `seon.cluster.registry/branch-fork-entity`'s existing inherited base toward main.” Red: a replacement chain sees and finally merges an earlier definition made only on the abandoned chain, even after the old branch is retired. No new lineage owner.

## 8. Correct the remaining factual/measurement claims

| Claim | Evidence and exact replacement |
|---|---|
| File names are authored symbols and normalized | At the target, reading all task EDN with `clojure.edn/read-string` found **869 string names**. `seon.task/file-declarations` validates before its name check and does not normalize symbols. Replace with “task files and runtime task maps use string names; namespace is a symbol.” S1a's 856-entry census is stale; rerun the census for its script. |
| A complete contract here necessarily has named output schemas; bare map refuses | `seon.cluster.source/contract-gaps` filters findings to missing-spec, any, some; bare-map is reported by `seon.fn/contract-findings` but not refused by this merge rule. Replace with “the shared done rule uses the installed merge completeness rule; Design instructions request named schemas.” If named outputs must be mechanically required, change that one contract owner and give S2b an explicit bare-map row; do not add a Design-only validator. |
| A first-sentence rule fixes the 150-token summary | `seon.ref/docstring` and `seon.ref/brief` already use the first sentence. `doc-view-measure.md` identifies a long **sentence**, not a long first line. Replace with “shorten the producer's summary sentence where needed; retain the existing summary rule.” No new character cap. |
| 20-chain read “cold”; all context units already have their mechanism | `root-mechanics.md` calls it the first read with a warm store; a restart-cold store was not measured. At the target `seon.context/must-read-ai` uses render-call, but `seon.context/exchanges` still walks/emits all historical evaluations, and `seon.context/model-context` rebuilds the block. Replace with “must-read render reuse is installed; section/history assembly remains to be measured and converted.” The older 382 ms/898 MB sample is a historical baseline, not a measurement of target HEAD. |

## 9. Slice briefs and feasible boundaries

The table below is proposed text to add to §7. Paths name ownership; symbols name code. Each proposed split is a serial, loadable commit unless its files are disjoint. The lifecycle and runner cuts remain experienced-owner work until their red proofs and contracts exist; relabeling them Sonnet does not make them a 15–30 minute assignment.

| Slice | Missing exact brief / split |
|---|---|
| **S1a** | “Own one syntax sweep in `script/seon/`, task/schema resources, all live intent readers and authored laws/skills. Rename by syntax, including namespaced maps and fixture text; correct the schema description. Record the actual census and prove old/new declarations differ only by the renamed key. Existing reaching tests; root restart batch.” Broad mechanical sweep, not a 15-minute edit; separate inventory/script preparation from its single atomic conversion commit. |
| **S1b** | “Own task/chain schemas, `seon.task/task-delta`, `seon.task/held-tx`, and registry claims with task/registry tests. Next is a non-component peer ref; only content members can flow. Use `malli.core/decode` and `malli.transform/default-value-transformer` with optional defaults enabled. Supplied running stays running; editing a running task does not reset it held.” Split defaults/name validation from chain schema/retention. Each publishes with consumers; registry proof retains branch **and base**, including held chains. |
| **S1c** | “Own task producer/constructor and `test/seon/task_test.clj`; define `:seon.task/task-map`, chain-options and chain result before the body, including optional later identity rules from finding 7. Template returns only owned content from an explicit database.” Split producer/tempid/reference replacement from branch creation/discovery/idempotence. Assert one tx for all task identity datoms, no chain tasks on main, repeat preserves an edited plan and commits nothing. Apply finding 5's deletion order. |
| **S1d** | Three lanes: pure string replacement in `seon.edit`/`my.edit` and tests; member update in `seon.task`/`my.task` and tests; Markdown help in `seon.bootstrap`, help resource and tests. Add error-result propagation/no write, absent set + into, same-value no transaction, unrelated-member CAS success, and zero/one/multiple text matches. Help doc-view expansion waits for S3d's render contract; parsing/help sections can land earlier. |
| **S2a** | Own runner/test/context input schemas and test-input test file; all selection, execution, recording and lookup callers convert together. Split the world-construction-error producer fix from task input/reuse. The former asserts request error plus no completed designed-red member evidence; the latter uses finding 3's table and finding 6's optional reader. No blanket database hash. |
| **S2b** | Own `seon.cluster.source/done-refusals`, source refusal/judged schemas, `seon.context/step-observation`, `seon.turn/test-step`/initial proving and their tests. Split whole-branch selection/ordered recording from the pure done judge. Implement finding 4's exact table, including zero changes; initial proving must use the same ordering before deciding zero model calls. |
| **S2c** | “After S2a/S2b readers use fields, own the syntax sweep, fn/analyzer/tag schemas, generated-task consumers and affected fixtures. Assert old tagged proofs **union existing explicit proofs** equals each retained task's new field; never replace explicit proofs with tagged-only sets.” Split script/pre-brief from the atomic consumer/data retirement commit. Retire old stage fixtures/templates with their last readers, not later by assumption. |
| **S3a** | Split (1) held chain observation/action schemas + branch-local attempt/connection/route ownership, then (2) readiness/advance and turn authority. Owners: cluster agent/registry/wake, task, turn, context, `my.root`, db-flow tests. Define proposed `seon.task/advance-tx` as database + assignment → transaction-data; derive next in the writer. Assert finding 1's no restart of A, distinct agents sharing one branch, exact reference replacement, and a repeated advance yields no task datoms. The composite observes exactly two distinct agents for two tasks; replace “three … would appear” with a separate actual three-task row. |
| **S3b** | Split source-task basis/candidate acceptance from publication/retirement. Own merge/source, `my.root`, merge tests. State where accepted-merge publication identity is durable: `seon.merge/merge-onto` currently records merge-commit in its later answer transaction. Derive publication from acceptance provenance or record its identifying facts there; restart between acceptance and answer must publish exactly once. Add source-born attempts, whole delta, all outcomes closed, no runtime task-file write-back, conflict retention and publication-wait/refusal cases. |
| **S3c** | Bail/resume/abandon and restart are separate lanes, sharing cluster owners serially; restart is not routine Sonnet work. Use findings 1–2. Name existing `seon.cluster/recover-runs!`/`seon.turn/recover-call` as owners, then give a row per §2.8 stopping point asserting attempt identity, model-call count, outcome, retained branches and pending publication. Run only after S3b for accepted-merge recovery. |
| **S3d** | Split render tiers from context reuse/performance. First own ns/test/schema/doc AI renders and focused data tests; assert distance 1 has doc/contract/schema descriptions/read form without source, distance 2 retains source. Then own context section/history assembly and its before/after probe. Depend on the errors cut's deterministic refusals, not just S3a. Use existing `seon.render/render-call`; measure target HEAD before setting a cost assertion. Cache hits alone do not prove end-to-end gain. |
| **S4** | Shared helpers/contracts land once, then use the three disjoint stage lanes below. Design rows: missing/blank sections, unresolved ref, no modeled data, existing-schema success, new-schema/stub success. Prove rows: missing successor/proofs/deftest/current result, green result, infrastructure error, unrelated red, unreached changed/cited function, assertion-red and body-error success. Assert refusal **sets**, not merely nonempty. Both tables run unbound; bound cases use requesting branch values after dependent evidence records. Implement has no extra predicate. |

## 10. Parallel stage development after S3a

The proposed single `seon.step.clj`, single `step_test.clj`, and single template EDN prevent three lanes from owning their files. Task file namespace is derived from its filename by `seon.task/file-namespace`; splitting EDN silently while retaining `seon.step/*` identities will not work.

**Proposed §4/§7 replacement:** adopt these names and convert their references in one preparatory commit:

| Lane | Predicate / tests | Template file and identity |
|---|---|---|
| Design | `src/seon/step/design.clj`: `seon.step.design/handoff`; `test/seon/step/design_test.clj`: `seon.step.design-test/handoff` | `resources/seon/tasks/seon.step.design.edn`, `seon.step.design/template` |
| Prove | `src/seon/step/prove.clj`: `seon.step.prove/handoff`; `test/seon/step/prove_test.clj`: `seon.step.prove-test/handoff` | `resources/seon/tasks/seon.step.prove.edn`, `seon.step.prove/template` |
| Implement | No predicate namespace; `test/seon/step/implement_test.clj` for its scripted/live entrance | `resources/seon/tasks/seon.step.implement.edn`, `seon.step.implement/template` |

Before launching: S1 data/writer/verbs, S2 input/selection/done/tag conversion, S3a's real start/advance, and one shared-helper/fixture commit must be installed and proven. Keep common helpers in `src/seon/step.clj`, common fixture additions in `seon.test-support`, owned by that preparatory lane only. Both predicates keep the §4 database/task → refusals contract. Reuse branch-change/result/reference owners; do not install the evidence's repeated full digest-map scans or a stage-specific cache. Helper contracts must name their maps/sets and release any materialized fork value in its owner.

Give each stage its own research file for live observations; the coordinator owns TASK-CHAIN status edits, applying each lane's supplied status text serially. Delete the retired `seon.step.edn` and old `step_test.clj` definitions in the preparatory conversion with their last readers; do not leave duplicate templates. Design and Prove can be graded after S3a at the boundaries in finding 6. Full Implement acceptance/publication trials additionally require S3b; failure/restart trials require S3c. Those dependencies do not prevent the three stage predicates/instructions/tests from being developed concurrently.

**Avoid extra machinery:** keep payload-free coalesced wakes. The measured 20-chain uncached read is 9–14 ms; head-key caching is optional until expected load justifies it, and must retain derived data rather than released database objects. Do not add an Implement checker, a second changed-program walker, summary truncation, or a separate help render cache. Amend the existing owners and delete replaced paths with their consumers.

## Verification and landing

Default reported degraded health (8 untested-hot warnings, 1 agent-error signature, 4 errored receipts, 3 unowned namespaces), with source archive `de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6`, older than the review target. No reset, provider call, disposable branch, source edit, or authored main transaction. MCP's normal JVM evaluation records are the only probe writes. An initial probe mistakenly used `first` instead of `ffirst` on the eid query and returned a lookup-ref syntax error; this was reviewer error, corrected below, not a product finding.

Exact successful JVM/root/default probe:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection)
      held (ffirst (seon.db/q '[:find ?e :where [?e :seon.task/execution :held]] database))
      report (datahike.api/with database [[:db/add held :seon.task/execution :held]])]
  {:branch (get-in database [:config :branch])
   :reasserted-existing-execution-datoms
   (count (filter #(= :seon.task/execution (:a %)) (:tx-data report)))
   :total-tx-datoms (count (:tx-data report))
   :next-installed? (contains? (:schema database) :seon.task/next)
   :exact-reach-owner? (boolean (ns-resolve 'seon.test.runner 'result-at-reach))
   :name-validity (mapv seon.task/symbol-name? ["a" "a-1" "bad name"])})
```

Answer: branch `:cluster-default`, execution datoms **0**, total datoms **1**, next installed **false**, exact-reach owner **true**, validity `[true true false]`. Evaluation 3 ms; request 40.4 ms, 0.7 MB allocated, 1,249 calls, 3 reads, one evaluation-recording transaction. Result handle `result/e6a81f8cc97af`. This proves the no-op write mechanism, not the unimplemented chain resume path.

Dependency pins: Datahike `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, SCI `eb3290d5c5ed72133ae1947492ecd324413868ed`. Checked the default transformer and the Datahike add path; no dependency edit. Foreign boundary: pre-existing modified Datahike/Konserve gitlinks, dirty HTTP-kit, scratch artifacts and other untracked research files; none included or modified. No current test reds were measured; historical reds are not current evidence.

`clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,664 warnings, 6,429 ms**. No test reaches this Markdown-only addition. Use the installed `bin/lane` docs-only path (no `--test`, no publication or throwaway JVM); no runtime acceptance claim. Source/test delta: zero. Only this review file is claimed and committed, then its claim released.
