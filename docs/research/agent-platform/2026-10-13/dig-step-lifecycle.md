---
title: "Dig: the step lifecycle, done = the merge accepts"
type: research
status: complete; research only, wrote no source, test or database state
basis: "rg and git log over src, test, script and docs at HEAD 62eae1983 (2026-10-13)"
---

# Dig: the step lifecycle, "done = the merge accepts"

Date: 2026-10-13. Research only. Read of source, git history and docs. No source, test, branch or database write. No JVM request was made: every fact below is from `rg`, `git log`/`git show`, `sed` or a doc quote, and each quote is labelled.

Labels: **[V]** verified, the quoted output is below it. **[I]** inferred from quoted output; not run.

Counts marked "occurrences" are `rg --count-matches` over `src`, `test`, `script`. They include docstrings and comments, so they are not call counts. Call sites are listed by line.

---

## 0. The five most important facts

1. **The verdict is a second, partial judge of the merge.** `seon.context/unmet` (`src/seon/context.clj:175`) judges a step by six clauses: `:proofs`, `:product`, `:product-absent`, `:broken`, `:new-red`, `:uncontracted`. `seon.cluster.source/accept-merge!` and `prepare-merge!` also refuse on the root file proof, clj-kondo lint, dangling task references, replaced functions with no green reaching test, an empty delta, and the test run's identity. The verdict has none of the file proof, lint, dangling or uncovered checks. **[V]** `rg -n 'lint-errors' src` returns only `src/seon/cluster/source.clj:834` (declare), `:941` (call inside `prepare-merge!`) and `:1742` (defn). `context.clj` never calls it. The F66 commit says so in its own message: "the relaunch after a write-back :not-analyzable refusal is judged :done at its first verdict, because the verdict does not observe the file proof" (`git show -s 941fb2814`).

2. **Acceptance is not cheap, and the static half is much cheaper than the test half.** `prepare-merge!` builds a fresh merge branch `S` and runs a candidate gate on it, which runs real tests. **[V]** `source.clj` (inside `prepare-merge!`): `(let [s (keyword (str "merge-" (id/id)))` then `gate ((requiring-resolve 'seon.cluster/candidate-gate!) source held-store s write!)`. Measured: `prepare-merge!` with one proof is 1.7 s warm, 2.8 s with two reaching tests (`docs/seon/specs/merge.md:279-280`); one test delta is 4.06 s (`docs/research/agent-platform/landing/inv-test-selection-cost-opus-2026-09-26.md:248`). The static parts: lint is 14 to 132 ms per file (commit `770733432` message); the write-back file proof is 421 ms (`docs/research/agent-platform/landing/fix-merge-basis-2026-09-26.md:17`); one `step-verdict` with three proofs is 2.6 to 3.3 ms on a fixture with no recorded runs (`docs/research/agent-platform/2026-10-12/cost-gb.md:42-50`). **[I]** The one judge must split: a static half (contracts, file proof, lint, dangling, uncovered; all cheap) and a test half that the step already runs as its proofs. Running `prepare-merge!` itself per verdict is ruled out by its cost.

3. **The done rule grew one clause per commit, all on 2026-10-13.** `fixable-refusals` (`src/seon/cluster/agent.clj:2181`) has three members from three commits (`ea800d326`, `770733432`, `6e8b157ed`). The step judge gained its clauses in `d8b470c57` (`unmet`), `ea800d326` (uncontracted for Implement) and `6e8b157ed` (uncontracted for every step). The fork-point proxy for `:satisfied` was changed in `941fb2814`. Full timeline in section 5. **[V]** `git log -S` per symbol, section 5.

4. **F66 is already landed, so the plan's "lane f66-satisfied in flight" is stale, and F66's fix is not the one the plan asked for.** `docs/seon/plan.md:59` says "lane f66-satisfied (read main's head, not the fork point)". `git show --stat 941fb2814` lists `src/seon/context.clj` and `test/seon/cluster/db_flow_test.clj`. The landed fix reads the fork point from the merge base: "The fork point's presence is read from main at the merge base, not from the agent's own record." **[V]** The verdict is therefore judged against the merge base, while `prepare-merge!` and `accept-merge!` judge against main's head (`source.clj`, `head (db/db (:seon.db/connection source))` in both). **[I]** That mismatch is the design round's question (1) in concrete form: a step can read `:satisfied` against an old base while the merge would be judged against a newer head.

5. **"Step kinds as templates" contradicts the docs, and the kind is already data.** `docs/seon/schedule/cards/TASK-CHAIN.md:14` says "stage types as data"; line 51 says "no template file, no stage-type render"; line 191 says "never a template or a stage type"; `docs/seon/plan.md:196` says "no templates, no stage types". TASK-CHAIN line 166 (§6, "What must be built") still lists "the stage type entity, the rendered template". **[V]** The step kind is `:seon.task/step`, and `seon.task/obligations` (`src/seon/task.clj:508`) already selects proofs by it. But each kind's done rule is a hard-coded `case` in `step-status` (`src/seon/context.clj:211-216`). So a template today means a new `case` branch, a help group and a schema (hybrid D2, `docs/research/agent-platform/2026-10-12/hybrid-chains.md:329`).

---

## 1. The current judge, as the code says it

### 1.1 The done rule (`src/seon/context.clj`)

**[V]** `unmet` docstring, lines 175-182: "Each clause of the step's done rule that `observation` does not meet ... `:uncontracted`, the functions its merge would refuse, for every step: done means the merge would accept."

**[V]** the clauses, `context.clj:186-195`:

```
(let [{broken :seon.context/broken new-red :seon.context/new-red} (reds head fork-point)
      [owed-clause owed] (if (= :prove step) [:product product] [:proofs proofs])
      clauses (cond-> {:proofs (into #{} (remove #(= :green (get-in head [% :seon.context/proof-status]))) proofs)
                       :broken broken
                       :new-red (into #{} (remove owed) new-red)}
                (= :prove step) (assoc :product ... :product-absent ...))
      clauses (assoc clauses :uncontracted (set uncontracted))]
```

**[V]** `step-status` satisfied clause, `context.clj:211-216`:

```
satisfied? (case step
             :implement (and (seq proofs) (green? proofs) (= (count green-before) (count proofs)))
             :prove (and (seq product) (green? proofs) (empty? broken) (empty? new-red)
                         (every? #(true? (get fork-point %)) product) (every? (comp designed-red? head) product)))
```

Status: `(cond satisfied? :satisfied (empty? (unmet observation)) :done :else :working)`, `context.clj:219`.

### 1.2 Where the verdict is computed (per pass, not per eval request)

The verdict is computed at four sites. **[V]** each is quoted:

| Site | Quote | When it runs |
|---|---|---|
| `src/seon/turn.clj:5771` | `(end-task! cluster agent-id))`, under `(when (and (nil? submission) (= :closed (:seon.turn.loop/outcome report)))` | A pass whose model turn closed. |
| `src/seon/turn.clj:2160` (inside `task-end`, reached from `end-task!` at `:2213`) | `(let [status (:seon.task/status (@context-step-verdict database agent-id))]` | Same closing pass. Full `step-verdict`: observation with `contract-gaps` and `base-results`. |
| `src/seon/turn.clj:5459-5464` (inside `record-broken!`, called from `test-step` at `:5507`) | `(@context-step-observation database agent-id sym {...})`, then `commit (when (seq reds) (fork-commit database))`, then `(@test-fork-point-red execution commit reds limit-ms)` | A pass where `step-due?` holds. `test-fork-point-red` **runs tests** on the fork commit when there are reds. |
| `src/seon/context.clj:644` (in `task-sections`, reached from `current-state` and `model-context` at `:755`) | `verdict (step-verdict database agent-id)` | Every model call, because the context block is rebuilt each time. Also `my.task/current` at `src/my/task.clj:35`. |

**[V]** `step-due?`, `src/seon/turn.clj:5191-5196`: "Whether the pass `report` evaluated a form and the agent's tests may see it: its recording transaction wrote program rows, or a proof of the task the agent holds has no result at its current reach."

So the answer to "how often" is: per model call (context block), per closing pass (end), and per pass that changed program or left a proof stale (test step, which may run tests). An eval request on its own does not compute the verdict unless it wrote program rows or left a proof stale. **[I]**

---

## 2. Acceptance vs the step judge

Sources: `src/seon/cluster/source.clj` `prepare-merge!` (defn at line 888), `accept-merge!` (line 1069), `contract-gaps` (1037), `lint-errors` (1742). Step judge: `context.clj` `unmet` (175), `step-status` (197), `step-observation` (357).

| Check | Merge refuses with (quoted) | Step judge | Gap |
|---|---|---|---|
| Root file proof (namespace not required, unanalyzable file) | `prepare-merge!` with `:root`: "a declaration naming a namespace its file does not require refuses before any test runs" (docstring) | none | **missing** (F66 "not fixed") |
| clj-kondo error in published file | `prepare-merge!`: `(lint (when (vector? proof) (lint-errors root proof)))`, `(seq lint)` refuses `:seon.source/lint-error` | none | **missing** |
| Contract gaps (missing or unjustified `:any`/`:some`) | `accept-merge!`: `gaps (contract-gaps tested delta)`, `uncontracted (gap-symbols gaps)` | `step-observation`: `refused (contract-gaps database agent-id)`, changed identities since the merge base | **present, different identity set** (merge: candidate delta; step: branch vs merge base) |
| Proofs green (named run) | `accept-merge!`: `(seq red-tests)` refuses, `red-test-refusal` | `:proofs` clause (head status green) | present, different evidence (fresh run on `S` vs head entries) |
| Product red by design | `designed` from `seon.test.runner/designed-red?` on `latest-results` | `designed-red?` (context.clj:158, private, on head entries) | present, **two copies of the predicate** (section 3.2) |
| New red not reached by proofs | `red` via `red-test-verdicts` with `fork-point-red` on main | `:new-red` | present |
| Replaced function with no green reaching test | `uncovered` in `accept-merge!` | none | **missing** |
| Dangling task reference | `dangling ((requiring-resolve 'seon.task/candidate-dangling) ...)` | none | **missing** |
| Empty delta / conflict / selection / reach | `prepare-merge!` `(empty? delta)`, `three`, `unselected-reach` | not applicable to an agent's whole branch | n/a **[I]** |
| Program written since the run | `program-written-since?` in `accept-merge!` | none (the writer checks it) | expected; writer owns it |

**[V]** the commit message of `770733432` confirms the lint placement: "`seon.cluster.source/prepare-merge!` (with :root, as db<->flow merges) refuses before any test runs with the new variant :seon.source/lint-error".

**[I]** The round's question (1) therefore needs more than the five checks named in the request. It needs the file proof, lint, dangling and uncovered checks too, and the base they are judged against must be the merge's base (main's head), not the fork point.

---

## 3. A. Callers and tests

### 3.1 Occurrence counts (`rg --count-matches`, src / test / script)

| Symbol | src | test | script | Note |
|---|---|---|---|---|
| `step-status` | 11 | 5 | 0 | includes docstrings |
| `step-observation` | 9 | 0 | 0 | |
| `unmet` | 8 | 10 | 0 | the word also appears in prose |
| `designed-red?` | 6 | 1 | 0 | two definitions, see 3.2 |
| `fixable-refusals` | 5 | 0 | 0 | defn at `agent.clj:2181`, used at `:2229` |
| `failed-pass!` | 3 | 0 | 0 | defn `agent.clj:2208`, caught at `:2457` |
| `relaunch-reason` | 4 | 0 | 0 | defn `agent.clj:2157`; used at `:2203` and `:2273` |
| `prepare-merge!` | 9 | 28 | 0 | |
| `accept-merge!` | 13 | 18 | 0 | |
| `contract-gaps` | 23 | 3 | 0 | `context.clj:339` (private) and `source.clj:1037` |
| `uncontracted` | 48 | 53 | 0 | word-heavy; `source/uncontracted` is `source.clj:1061` |
| `lint-errors` | 3 | 0 | 0 | only inside `prepare-merge!` |
| `task/effective` | 5 | 2 | 0 | defn `task.clj:967` |
| `obligations` | 21 | 23 | 0 | word-heavy; defn `task.clj:508` |
| `task/ready` | 7 | 12 | 1 | defn `task.clj:1003` |
| `step-tests` | 5 | 0 | 0 | private, `turn.clj:5217` |
| `proof-run-recorded?` | 3 | 4 | 0 | `turn.clj:5271` |

**[V]** Source of counts: `rg -F --count-matches -- '<symbol>' src | awk ...` per symbol, run in this session.

### 3.2 The two `designed-red?` predicates

**[V]** `src/seon/context.clj:158-161`:

```
(defn- designed-red?
  "True when the head entry `entry` is red by at least one failing report: a failing assertion or an error its body raised. ..."
  ...
  (boolean (and (= :red (:seon.context/proof-status entry)) (seq (:seon.context/proof-failures entry)))))
```

**[V]** `src/seon/test/runner.clj:2482-2488`:

```
(defn designed-red?
  "Whether `result` is a Prove step's designed red: an executed red with at least one failing report ..."
  [result]
  (boolean (and (red-result? result) (seq (:seon.test.failure/reports result)))))
```

Same rule over two shapes, one on the head entry the verdict reads, one on the recorded result the merge reads. **[I]** The one judge needs one predicate on the recorded result, and the head entry should carry it, not a second copy.

### 3.3 Test names that pin the lifecycle

**[V]** `test/seon/context_test.clj`:
- `:428` deftest `^{:seon/tasks #{'seon.turn/the-test-step-writes-nothing-on-main}}`, with the step-status calls at `:438`
- `:472` `a-step-section-shows-what-its-rule-counted` (calls at `:477`)
- `:482` `a-working-steps-section-names-each-unmet-clause` (calls at `:490`, cases `:503-504`)
- `:623-625` the helper keeps a step working as its unmet clause (`:uncontracted`)

**[V]** `test/seon/test_failure_facts_test.clj`:
- `:172` `an-unimplemented-stub-is-a-prove-steps-designed-red` (`:185` step-status; `:200` `runner/designed-red?`)
- `:188` `an-error-its-subject-throws-is-a-prove-steps-designed-red` (`:202`)

**[V]** `test/seon/cluster/db_flow_test.clj` (relaunch and satisfied):
- `:1340` `a-capped-attempt-with-changes-relaunches-from-its-commit`
- `:379` `a-prove-step-whose-product-main-holds-closes-satisfied-and-its-successor-forks-main`
- `:1187` one chain, refused gate relaunch
- `:1252` merge refused at its file proof, relaunch over three passes
- `:1292` a Prove attempt's merge refused at its file proof, relaunch over three passes

**[V]** `test/seon/cluster/merge_test.clj`: `:183` `a-red-reaching-test-refuses-acceptance`, `:563` `a-requested-green-end-merges-and-publishes-with-no-hand-act`. `test/seon/cluster/write_back_test.clj:314` is the lint-and-file-proof test (its name is in the `770733432` message). `test/seon/cluster/source_test.clj:415` `an-undefined-slot-is-not-a-complete-contract`; `:430` asserts `source/uncontracted`.

### 3.4 Callers that are not in the lifecycle

- `relaunch-reason` is also used by `close!` for `:capped`/`:stalled` (`agent.clj:2273`). Deleting the refused-merge relaunch does not delete `relaunch-reason`. **[V]**
- `:satisfied` is not only a step verdict. It is an attempt end: `close!` (`agent.clj`, `(= :satisfied end)`), `task-end` (`turn.clj:2162`, `#{:done :satisfied}`), and `leaf-done` in the ready rules (`src/seon/task.clj:949-951`: `[?a :seon.task.attempt/end :satisfied ?tx]`). Deleting `:satisfied` touches the ready rules as well. **[V]**

---

## 4. B. Per-pass cost and measured numbers

### 4.1 Measured (from docs; none measured by this dig)

| Operation | Measured | Source |
|---|---|---|
| `prepare-merge!`, one proof | 1.7 s warm, 175k calls; 8.7 s first call | `docs/seon/specs/merge.md:279` |
| `prepare-merge!`, one proof + 2 reaching | 2.8 s, 602k calls | `merge.md:280` |
| `prepare-merge!`, one-test delta | 4.06 s (`candidate-gate!` 3.24 s, `test/run` 2.85 s) | `docs/research/agent-platform/landing/inv-test-selection-cost-opus-2026-09-26.md:248` |
| `accept-merge!` accepted, 3 identities | 1.78 s, 6.2k calls | `merge.md:282` |
| `accept-merge!` refused (red proof) | 135 ms | `merge.md:281` |
| lint of published Clojure, warm | 14 ms (54 lines), 51 ms (551 lines), 132 ms (2,075 lines) | commit `770733432` message |
| write-back file proof, one file | 421 ms | `docs/research/agent-platform/landing/fix-merge-basis-2026-09-26.md:17` |
| `step-verdict`, 3 proofs | 2.62 to 3.30 ms, 2.5 to 2.8 MB; fixture has no recorded runs | `docs/research/agent-platform/2026-10-12/cost-gb.md:42-50` |
| `step-status` alone, 2000 calls | 0.0013 ms per call | `docs/research/agent-platform/2026-10-12/narrow-check-done-and-merge.md:34` |

The run reports give whole-chain times only (for example "merged and published in 26 s", `run-16.md`; "in 54 s", `run-17.md`). No run report isolates a merge check. **[V]** (`rg` over `run-1*.md` for prepare/merge timings found none.)

### 4.2 Gap in the measurements

**[I]** Nothing measures the file proof or lint inside a step verdict, nor a verdict with recorded runs, nor `base-results` against a real fork commit. `cost-gb.md:42` says so explicitly: "This fixture has no recorded proof runs; it does not prove the cost of the full model turn or of reading existing runs." The design round must measure the static half on a real chain (Run 23's branch) before it chooses between "verdict runs the file proof each time" and "verdict caches the file proof by branch head".

### 4.3 Cost sites that already run tests

**[V]** `turn.clj:5462-5464`: when reds exist the verdict calls `@test-fork-point-red execution commit reds limit-ms`, which runs tests at the fork commit. So the verdict is not free when it has reds, and it is free when it has none. A judge built on `unmet` will inherit this cost.

---

## 5. C. History: the accretion timeline (since 2026-10-12)

**[V]** `git log --oneline -S '<symbol>' --since=2026-10-01 -- src` for each symbol, and `git log -1 --format='%h %ad %s' --date=short <sha>`. Dates are commit dates (2026-10-13 unless noted).

| Commit | Date | What it added | Clause or refusal |
|---|---|---|---|
| `346645d56` | 2026-10-12 | Proofs judged at the fork point; `:satisfied` opens no model turn | `:satisfied` introduced |
| `7ddbb2c1e` | 2026-10-12 | P3: one rule judges a step against its fork point | fork-point proxy |
| `bae97ee71` | 10-13 | H4: a Prove step's designed reds merge as its product; `needed?` deleted | `designed-red?` (runner) |
| `d7ccec1b6` | 10-13 | F22/F23: Prove step whose product main already holds is `:satisfied` and closes without a merge | `:satisfied` for product |
| `ec19ecdfb` | 10-13 | F46/F46b: Prove step declares its deftests as data, `:seon.task/product` | product as data |
| `3d6418255` | 10-13 | H5: capped or stalled attempt with work relaunches from its end commit, dial default 2 | relaunch |
| `be25d0dfb` | 10-13 | F51: a `:satisfied` end deletes its branch once main holds the end | `:satisfied` branch rule |
| `4cfa03eab` | 10-13 | Relaunch forks the predecessor's end commit, keeps merge base | base changes |
| `ea800d326` | 10-13 | F45/F41 done-means-merge: Implement done only when its merge would accept; refused merge relaunches | `fixable-refusals` created (uncontracted-error, lint-error) |
| `a45f6a96b` | 10-13 | F48: `:any`/`:some` slots refused exactly as missing contracts | contract gaps |
| `d8b470c57` | 10-13 | F60/F61: error-red product is designed red; working step names each unmet clause | `unmet` created |
| `770733432` | 10-13 | Merge holds the lane lint bar; `:seon.source/lint-error` added to `fixable-refusals` | lint |
| `b182e514c` | 10-13 | Contract gaps reach the step section and the defn warning | step section |
| `6e8b157ed` | 10-13 | F64/F65: `:uncontracted` clause for every step; `:not-analyzable` pair added to `fixable-refusals` | `unmet` clause, pair |
| `68fd72410` | 10-13 | F67: a definition naming a namespace it does not require requires it in the agent's SCI namespace | ns requirement |
| `941fb2814` | 10-13 | F66: relaunched Prove step's fork point is read from main at the merge base | fork-point proxy changed |

**[V]** The `fixable-refusals` set is three commits: `ea800d326` created it, `770733432` added the lint member, `6e8b157ed` added `[write-back-error :not-analyzable]` (`git log -S fixable-refusals`).

**[V]** The `:satisfied` proxy: `ea800d326` and `be25d0dfb` are the rule's history; `941fb2814` changed what the fork point means. The audit's point that the proxy "became false" for relaunches (`docs/research/agent-platform/2026-10-13/audit-night.md`, §2 row `be25d0dfb`, `ec19ecdfb`, `4cfa03eab`) is confirmed by the F66 commit message.

---

## 6. D. Templates and stage types: the text and the spec

### 6.1 The docs disagree

**[V]** quotes:
- `docs/seon/schedule/cards/TASK-CHAIN.md:14`: "one general mechanism (a task value, stage types as data, one context function, one stage-aware proof selection)"
- `TASK-CHAIN.md:51`: "no template file, no stage-type render"
- `TASK-CHAIN.md:166` (in §6 "What must be built"): "the stage type entity, the rendered template, `:seon.task/stage`, `:seon.task/basis-commit`"
- `TASK-CHAIN.md:191` (§7): "instructions are data on the step, never a template or a stage type"
- `docs/seon/plan.md:196`: "Instructions are data on the task (steps and problem tasks alike); no templates, no stage types."

**[I]** Owner's ruling is in `plan.md:196` and `TASK-CHAIN.md:191`. Line 14 and line 166 are stale.

### 6.2 What TASK-CHAIN §3 and §3b specify

**[V]** §3 names six stages: Mine, Design, Prove, Implement, Land, Retrospective (`TASK-CHAIN.md:79-113`). Each has a decision: Prove "`:changed #{old tests}` or `:unchanged`"; Implement "`:changed #{syms}` or `:send-back`"; Design "`:added`/`:changed`/`:removed` with keys and samples, or `:unchanged`".

**[V]** §3b table, the proof each step asserts (`TASK-CHAIN.md:115-135`):
- Mine: "expected input = observed input; ... a nonempty case set"
- Design: "no unresolved reference; ... no consumer unaccounted; a decision present"
- Prove: "required cases = observed cases; expected failures = observed baseline failures ...; a decision present"
- Implement: "required proofs nonempty and all green on the branch; no newly broken reaching test; no remaining replacement; ... a decision present"
- Land: "reviewed program = merged program = written program; the recorded git commit; the proofs green on main"
- Retrospective: "every observed friction has a follow-up, an instructions change or a reasoned `:none`"

**[V]** No §3b row asserts that the task is ready for the next step. Land's proof is "the proofs green on main". Successor readiness is derived elsewhere: `leaf-done` in `ready-rules` (`src/seon/task.clj:942-951`).

### 6.3 What is already template-like

**[V]** `docs/research/agent-platform/2026-10-12/hybrid-chains.md` §4.1, table:
- `:seon.task/intent`: "inherited down `:children`; a child's own value overwrites"
- `:seon.task/instructions`: "the step's own key; a parent declares none, so nothing is inherited"
- `:seon.task/references`: "derived by `seon.task/task-delta` from intent **and** instructions"
- "`seon.task/effective` ... the content maps (`select-keys` of intent and instructions) from the root subject down `:children` to the step, `(apply merge …)`, the step last."

So the current model already does the round's question (2) in its simplest form: intent accumulates down the tree, instructions overwrite at the step. **[I]** Not verified: whether references accumulate across steps or are re-derived per step. `task-delta` is at `src/seon/task.clj:268`; its body was not read past its first lines in this dig.

**[V]** `hybrid-chains.md:329` (D2): "No `:design` step; Prove's product is red tests, contracted stubs, registered schemas" with the reason "0 tasks carry a step; the target is two steps; a kind needs a done rule, help group and schema".

### 6.4 The stage-type entity design

**[V]** `TASK-CHAIN.md:237` (§8.1.2) and `:260-263` (§8.1.3): "owned by a stage type entity", "The task carries its stage (`:seon.task/stage`, a ref to the stage type entity)", "The stage type entity holds `:seon.task.stage/proofs`, `:seon.task.stage/next`, `:seon.task.stage/template`". These are in the review sections, not the agreed §3, which is the source of the conflict.

---

## 7. E. The rulings that constrain the design

**[V]** quotes from `/Users/sean/src/seon/CLAUDE.md` (line numbers from `rg -n`):
- `:97` **Fix at the producer.** "When a fix starts to look like a workaround, exemption, fallback or patch elsewhere, return to the data flow and find the owner."
- `:114` **NO GUARDS OR FENCES.** "A guard or fence is always the wrong solution ... When a failure is possible, find the design that makes it impossible, at its owner, and change that."
- `:295` **Merge is a root task.** "An agent writes no merge request: a done branch is merged by root's `db<->flow`, whose gate takes the proofs from the assignment's task."
- `:321` **Agents are compartments; root acts cluster-wide.** "A non-root agent holds its own branch and its own SCI fork, and nothing else."
- `:329` **Agents transact facts; root reacts.** "An agent never performs an effect that reaches main or another agent."
- `:352` **Decide at the authority.** "Derive at the writer or pass its decision; validate a pre-read's basis there if it could change."
- `:482` **Derive work and completion.** "A task names its success tests ... before it is assigned or launched."

**[I]** Consequences for the round:
- `:352` means a `:done` verdict is a pre-read that can go stale when main moves. The one judge must either re-validate at the writer (`accept-merge!` already checks `program-written-since?`), or accept that `:done` means "the merge would accept at the basis I read". The F66 gap shows the basis matters.
- `:114` rules out a "verdict refuses to be done when lint is red" fence beside the merge. The judge must be the merge's own check, not a copy with a flag.
- `:295` and `:329` keep the merge where it is: the verdict only reads, it does not merge.

**[V]** Hybrid rulings (`docs/research/agent-platform/2026-10-12/hybrid-chains.md`, §7):
- D2 (`:329`): no `:design` step.
- D3 (`:330`): "Content merges down `:children` only; `refine!` writes `:seon.task/intent` on the subject and `:seon.task/instructions` on the held step; no cross-task write".
- D5 (`:332`): "Relaunch is derived, bounded by `:seon.config.run/relaunches` (default 2), forks the ended commit, retains the merge base; unchanged ends close with a root task".
- D6 (`:333`): "A successor forks main as soon as its need is `:merged`".
- D7 (`:334`): "Nobody answers an unattended question".

**[V]** `docs/seon/plan.md:57-59` (H4-H5 rows): H5 "done; exercised, one defect open"; "Run 23 relaunched into `:satisfied` (F66)"; next "lane f66-satisfied (read main's head, not the fork point); then the design round below". Stale: F66 landed as `941fb2814` (section 0, fact 4).

---

## 8. Implications for the three questions

### Question 1: one judge, the merge's acceptance

**Deletable if the judge is the merge's own check** (with counts from section 3):
- `fixable-refusals`, `agent.clj:2181` (3 members, 3 commits). Its only use is `failed-pass!` (`:2229`).
- `refused-merge-relaunch`, `agent.clj:2187`. Its only use is `failed-pass!`.
- `failed-pass!`'s relaunch branch (`fixable?` at `agent.clj:2229`). The catch itself stays.
- The `unmet` clauses `:uncontracted` and `:proofs`, `:new-red`, `:broken` become one call (the merge's check), **[I]**, and `context.clj:186-195` shrinks to a call.
- The fork-point proxy in `step-status`, `context.clj:213-214` (`every? … fork-point … product`) is replaced by the merge's own base read.
- `designed-red?` at `context.clj:158` becomes the runner's predicate on the recorded result (section 3.2).

**Not deletable with it** (section 3.4):
- `relaunch-reason`, shared with `close!` for capped and stalled ends.
- `:satisfied` as an attempt end: used by `close!`, `task-end` and `leaf-done`. The rule that makes `:satisfied` a step verdict can go; the end value may stay until its ready rule is replaced.
- The relaunch dial (`:seon.config.run/relaunches`), which H5 uses for capped and stalled ends.

**The cost question** (section 4): the static half is cheap in the measurements (lint 14 to 132 ms per file, file proof 421 ms, contracts already measured as part of `step-observation`). The test half is already run as the step's proofs in `test-step` and may run `fork-point-red`. The verdict cannot run `prepare-merge!`, which takes 1.7 to 4.06 s and writes a branch. **[I]** The round should measure the file proof and lint on the Run 23 branch, and say whether they are cached by branch head.

**Simplest alternative the round must weigh** (from the audit, `docs/research/agent-platform/2026-10-13/audit-night.md` §3): deliver every merge refusal of an agent's own branch to that agent with its reason, and let root's problem task appear only when the dial is spent. This deletes the class list but not the verdict gap: a `:done` step would still be merged and refused. **[I]** The round should compare them on Run 21's 180-call step: the judge makes the refusal impossible, the delivery makes it cheap.

### Question 2: step kinds as templates

**[V]** Today: kind is `:seon.task/step`; `seon.task/obligations` selects the proofs; `step-status` has one `case` for the done rule; the help has one group per kind. A template as data would add a kind entity with its done rule. That is a new concept with a schema and a help group (D2), so the TASK-CHAIN §3 vocabulary (Mine, Design, Prove, Implement, Land, Retrospective) is the table of kinds, not stage entities.

**[I]** The owner's rule (`plan.md:196`) says no stage types. A template is a stage type under another name. The round needs the owner's ruling before it designs kinds: either the done rule is per kind in code (today), or the kind is a data entity (TASK-CHAIN §6, line 166). Both are consistent with "instructions overwrite while intent and references accumulate", which is what `seon.task/effective` already does.

### Question 3: each step's proof shows the task is ready for the next step

**[V]** Readiness is `leaf-done` in `ready-rules` (`src/seon/task.clj:942-951`): an attempt merged on main, or ended `:satisfied` with main holding its product, and no later `:sent-back` targets it. `seon.task/ready` is a Datalog query over this. The test `ready-answers-in-under-10-ms-for-fifty-leaves` (`test/seon/task_test.clj:233`) pins its cost.

**[I]** A step's proof can assert "the successor is in `seon.task/ready`" by calling `ready` after the step's merge. This is cheap (the test pins under 10 ms for fifty leaves). It needs the merge first, so it is a proof of Land, not of the verdict. It can't be part of the verdict unless the verdict predicts the merge's outcome, which is question 1.

---

## 9. Open items and limits

- Not verified: whether `:seon.task/references` accumulates across steps (section 6.3). `task-delta` (`src/seon/task.clj:268`) was not read to its end.
- Not measured: the file proof, lint or `base-results` inside a live verdict (section 4.2). The `step-verdict` number is from a fixture with no runs.
- Not run: no JVM request. The question "does `unmet` equal the merge's refusals on a live branch" is open. It needs a read-only JVM check of Run 23's branch, with the reply quoted.
- The plan's "lane f66-satisfied" is stale (section 0, fact 4). `docs/seon/plan.md:59` and `audit-night.md` §2 still say "in flight" and should be updated in the design round's commit.
- Counts in section 3.1 are token occurrences, not calls.
