---
title: "Audit of the night of 2026-10-12/13: 41 commits, runs 16–23"
type: research
status: complete; independent audit (Fable), judged from evidence only; wrote none of the work
basis: "git log f209820ab..HEAD (41 commits, 05:13–08:41 +0700); run-16.md … run-23.md; runs-16-19.md; prove-products.md; interpreted-reds.md; completer-input.md; turn-reds-triage.md; with-redefs-inventory.md; f27-check-cost.md; hybrid-chains.md; plan.md §0. Live reads on default in JVM mode at basis t 536871330: 11 branches, 831 tasks, 6 with a step, 0 test runs on main, relaunch dial 2, 0 open faults. Lanes f66-satisfied and f67-unrequired-ns were editing; their files were not touched."
---

# Audit of the night: did the goal move, and what got worse

## Headline

**The goal moved, then stopped moving.** Of eight real DeepSeek chains, two were clean (Run 19, `seon.render.block/surface-id`,
21 s, 4+2 calls; Run 20, `seon.repl/token-boundary?`, 25 s, 5+2 calls): one `:running` fact, Prove → merge → Implement →
merge, both products correct, both branches deleted, 0 hand acts. Four stalled (18, 21, 22, 23), each on a **different**
step-lifecycle defect, and Run 23's defect (F66) is the composition of two of the night's own fixes (`4cfa03eab` a relaunch
forks the end commit; the `:satisfied` rule reads the fork point as main). Chain 5 has now been run four times (Runs 21–23
plus the lanes fixing it) and merged nothing. The fix-one-stall-per-run pattern is **not converging**: the lifecycle has
accreted a hand set of "fixable" refusals that grew by one member in three separate commits, a `:satisfied` verdict with its
own deletion rule, a done rule that says "the merge would accept" but checks only part of what the merge checks, and a relaunch
path that carries the rest of the refusal to a model that, in Run 23, never got to read it. One design question would make
all four of those impossible (§3). The night's best commits deleted proxies and read the fact (`0c684db75`, `2354cd1ad`);
its weakest added members to lists.

Cost of the night: 41 commits, 8 restarts (one per run), source +542/−341, tests +938/−563, resources +129/−34. Model
calls paid: 14+10, 0+13, 4, 4+2, 5+2, **180**, 8, 6 = 248, of which 180 were Run 21's three capped attempts on a step that
could not end.

## 1. Runs 16–23

| Run | HEAD | Result | Calls | Stall | Fix (commit) |
|---|---|---|---|---|---|
| 16 | 8a46d0810 | Prove merged (table only); Implement correct body, no contract; merge refused root → root | 14+10 | F40 `[:fn]` refused at REPL; F45 refusal is a dead end; F46 Prove ends on first deftest | 2354cd1ad, ea800d326, 4cfa03eab, a150c4346 |
| 17 | 4a1fc2e7e | done in 54 s; correct body under `[:cat :any :any]`; no law | 0+13 | F46b `:satisfied` on partial product; F48 `:any` accepted; F51 satisfied branch kept | ec19ecdfb, a45f6a96b, b182e514c, be25d0dfb |
| 18 | 487388073 | Prove wrote table, then spun one core 23 min | 4 | F53 proof-run read a selection fact, not what ran | 0c684db75 |
| 19 | 6299bcda4 | **clean**, 21 s, correct | 4+2 | F56 `pr-str` in an `is` message merged; HEAD lint-red for 25 min | 1c68e9f5d, 770733432 |
| 20 | 606110768 | **clean**, 25 s, correct, specified `[:fn]` contract admitted | 5+2 | F57 merged function runs stale compiled body on a fresh branch until reload | open |
| 21 | 1d538ec20 | 3 × 60 calls, all `:capped`; nothing merged | 180 | F60 error-red is not designed red; F61 unmet clause invisible; F62 relaunch with unchanged verdict | d8b470c57 (F62: nothing, by decision) |
| 22 | d8b470c57 | Prove `:done` in 8 calls; write-back refused (scratch `rv`); dead end | 8 | F64 scratch helper merges; F65 `:not-analyzable` is a dead end | 6e8b157ed |
| 23 | 6e8b157ed | write-back refused (unrequired ns); relaunch `:satisfied` with 0 calls; product lost | 6+0 | F66 fork point read as main; F67 REPL admits what the file refuses; F68 `ready` without proofs | lanes f66-satisfied, f67-unrequired-ns (in flight) |

Correctness of what shipped: 4 products merged (f54082cf6, 5f7fc3f7b/44ce825d8, 0505527a4/228a6b026), all checked correct
by the run lanes on real inputs (568 characters; all 65,536 chars). Nothing wrong reached HEAD this night. Goal 3 (tasks
refined as work proceeds) was exercised once, on a branch that never merged (Run 21). Goal 4 (agents mining Seon) is a Haiku
by hand (`chain-candidates-3.md`: one weak candidate in the pool it was given).

## 2. Regressions and wrong turns, per commit

Verified = checked by `git show`, `rg`, `ls` or a JVM-mode read; inferred = from the reports and the diff alone.

| Commit | What | Evidence | Severity | Recommendation |
|---|---|---|---|---|
| ea800d326 + 770733432 + 6e8b157ed | `seon.cluster.agent/fixable-refusals`, a hand set `#{uncontracted-error lint-error [write-back-error :not-analyzable]}`, grew one member per commit; `failed-pass!` matches the type or the type+reason pair | verified: the set and its two match forms at `agent.clj:2181`, `:2229`. 6e8b157ed's own message: the pair also matches a `:not-analyzable` refusal for a checkout file the agent did not write, which "would spend the relaunch dial" | **high**: this is the edge-case pile CLAUDE.md says to stop on. A classification of "what the agent can fix" lives in a list beside the mechanism, and is already wrong for a case it names | **FIX FORWARD by design round (§3)**: either every refusal of an agent's own branch is delivered to it (no class), or `step-status` judges by the merge's own check so a `:done` step's merge cannot be refused. Delete the set in that commit |
| be25d0dfb, ec19ecdfb's `:satisfied` branch, 4cfa03eab | The Prove `:satisfied` verdict reads the fork point as "main holds the product"; 4cfa03eab made a relaunch fork its predecessor's end commit, so the proxy became false (F66, Run 23: product lost, Implement "ready" with no proofs) | verified: `step-status` satisfied clause `(every? #(true? (get fork-point %)) product)` in `context.clj`; Run 23 attempts on default: `2ad107668183 :error`, `ea899f6589e2 :satisfied` | **high** (a chain stops with nothing to run) | FIX FORWARD (lane f66-satisfied in flight): read main's head, the merge target, never the fork point. Then ask whether `:satisfied` is needed at all: with `unmet` as written, a Prove fork whose product main already holds red is already `:done` (no clause is unmet) and merges an empty diff; `:satisfied` exists only to skip that merge and needed its own branch-deletion rule (be25d0dfb). Candidate deletion for the design round |
| d8b470c57 | `designed-red?` widened: any red with a failing report, assertion or error, is designed red (was: fail or `:seon.error/unimplemented`) | verified diff. The rule is the simplest; the stated backstop is Implement's green requirement | **medium** risk, not a regression yet: a product test red by the *agent's own* runtime mistake (wrong arity in the test body, a helper that throws) now ends Prove `:done` and merges; Implement inherits an unfixable proof and must send back (Run 15b showed it bends the code instead). The commit says the unresolved-symbol case was not probed | KEEP; add the regression the first time a self-inflicted error-red merges (not before: a stack-frame check would be a fence). Record the risk in the Prove help, which already says to send back a contradicting proof |
| b182e514c | `seon.turn.batch/judged-database`: a `db/with` of the last change's facts (`form-facts` recomputed) per eval request that defines a function, so `contract-gaps` can judge it; `turn.clj` gained `:seon.eval/contract-gaps` in two hand attribute lists (`select-keys` at ~166 and the vector at ~1127) beside the `:seon.eval` schema | verified diff; cost not measured in the commit (no `seon.profile/measure`) | **low–medium**: per-defn work that duplicates what the batch already computed for the same change; two hand lists that CLAUDE.md says to derive from the schema | FIX FORWARD: measure `uncontracted-measures` on a defn reply on default (`:seon.repl/ms` before/after); have the batch hand its last change's facts to the judge instead of recomputing; derive the eval attribute vector from the `:seon.eval` entity schema (one source, the two lists go) |
| 874377c5f | Lazy interpreted source acquisition: 236 → 97 ms, 369 → 180 MB on a quiet throwaway JVM; regression test bound 200 ms / 220 MB; retired "the gigabyte-cost task" | verified bound in `test_test.clj:355-356`. Run 22 measured 212 ms / 128 MB **warm on default** through `my.test/check` (a different probe: MCP entrance + reply included). Cold on default the one-to-five-test check still costs 2,341–2,371 ms / 4.4–4.5 GB (Runs 22, 23) | **low** for the code (a real measured win); **medium** for the bookkeeping: the cold cost the retired task named is unchanged, and the 200 ms bound sits 6 % under a live warm measurement of the neighbouring probe, so it will flake on a loaded JVM | KEEP the code. FIX FORWARD: a task with the cold probe as its red proof (first check after boot: 2.3 s / 4.5 GB, Run 23) so the cost keeps an owner; re-measure the regression probe on default and set its bound at 2× the measured value with the reason beside it (CLAUDE.md "make bounds honest") |
| c05059e06 | Deleted `seon.dev.source-instrumentation-test` (111 lines, 2 tests) | verified: `seon.operator/init-form` has no def in `src`, `script` or `test`; the file could not run. Both tests were the only ones asserting that publication restores a merged function's instrumentation. F57 (Run 20) is exactly that gap, live: a fresh branch after merge ran the stale compiled `token-boundary?` with `:malli/schema` nil | **medium**: the deletion is right (a test of a deleted lifecycle), the behavior class is now unproven while a live observation says it is broken | KEEP the deletion. FIX FORWARD: file F57 with a red proof: on a branch forked after a merge that changed a function, before reload, the call answers the merged body or the reply names the stale binding as data |
| 2354cd1ad | Deleted the predicate-purity rule (`:seon.schema/unproved-predicate-purity`, `pure-predicate-symbols`); contracts kept as data, not printed and re-read; 22 files | verified: the rule refused every agent `[:fn]` contract because nothing ever produced the set it checked; Run 17 confirmed the 5 `seon.fs.jvm` malformed contracts gone; Run 20 admitted the specified `[:fn … char?]` contract live | none: this was a check beside a mechanism that nothing could satisfy, i.e. a fence; its deletion is the law applied | KEEP. The one thing it protected against (an impure predicate running under instrumentation) has never been observed; add its regression when it is |
| 7f96b6cba | The model call is the handle's **required** member `:seon.turn.loop/complete`; 48 `with-redefs` of `ai/complete` deleted; 17 files, +315/−279 | verified: Run 23's 6 calls went through `#'seon.ai/complete` on the handle; one hand-built handle lacked the member and was found before commit. The 31 "pre-existing reds" it names were measured by `turn-reds-triage.md` as 32 red vars on 7f96b6cba, none caused by it | none as a regression; a correct conversion of a Var seam into a value. "Required" is right: one producer (`start-flows!`) sets the Var, test-support defaults it; an optional member with a `get` default would be a second default beside the producer (the decision doc rejects it for that reason) | KEEP |
| a05ba6655 | Filed the 32 measured reds as 9 tasks and tagged their deftests as proofs | verified diff: tasks filed, 3 of the 32 fixed in df21007f0 (class B → F) | none; but the red count did not fall, ownership rose. Five `armed-test` reds are tagged to one boot-cost task (39–44 s boots against a 5 s bound) | KEEP. Note the trend: unowned reds on HEAD ~12 (runs-16-19) → 32 named → 0 unowned, 29 owned red |
| 5f7fc3f7b (a chain's merge) | Merged a test whose `is` message prints a value with `pr-str`; HEAD lint-red, every lane commit refused 06:54 → 07:18 | verified: 1c68e9f5d removed it; 770733432 added the lane lint bar to the merge | **medium**, root's: the merge gate had a lower bar than `bin/lane commit`; fixed the same night | KEEP the fix |
| 1d538ec20 → Run 21 | Chain 5 launched with a subject whose gap is a throw, on a done rule that counted only assertion failures | verified Run 21: 180 calls, two automatic relaunches of an attempt whose verdict was unchanged | **medium**, cost: 120 of the 180 calls were the relaunch dial continuing with no new information (F62). Not adding a progress check was the right call (it would be a fence); the cost was real | KEEP the decision; the design round (§3) removes the cause: a step that cannot end is a step whose done rule is not the merge's rule |

Not regressions, checked: `0c684db75` (F53) replaced a selection fact with the recorded members and deleted the busy loop
without a counter; `1c68e9f5d` fixed three interpreted-only reds in the tests, not the runner, and found that four "interpreted
reds" were plain reds nobody ran compiled (main held 0 test runs: verified, still 0); `a45f6a96b` made one rule
(`seon.cluster.source/contract-gaps`) serve merge, step status and the REPL warning; `ec19ecdfb` gave a Prove step its product
as data (`:seon.task/product`), with the known gap that `seon.task/create!` and `my.task/refine!` still hand-list keys.

## 3. Is the lifecycle converging? No. The design question.

Count the special cases added to the step lifecycle this night, each for one run:

1. `fixable-refusals`: three members, three commits, matched two ways (ea800d326, 770733432, 6e8b157ed).
2. `:satisfied` (Prove) with its own deletion rule (be25d0dfb) and its own proxy for main (the fork point), now wrong for relaunches.
3. `unmet` clauses: `:proofs :product :product-absent :broken :new-red :uncontracted` (d8b470c57, 6e8b157ed), with lint
   and file analysis explicitly left out ("cost must be measured first") and delivered through the relaunch instead.
4. The relaunch (H5) now serves three unrelated situations: a capped attempt with work, a stalled one, and a refused merge.
5. A relaunch forks the end commit but keeps the merge base (4cfa03eab), so "what the branch adds" and "what the fork point
   holds" are no longer the same question; `step-status` asks the second where it means the first.

Every one of these is an answer to the same unmodeled fact: **the step's done rule is not the merge's acceptance rule, and
neither is read against main's head.** `ea800d326` and `6e8b157ed` both state the intended law ("done means the merge would
accept") and both implement a subset of the merge's checks, then route the remainder to the agent as a refusal class after
the fact. CLAUDE.md: "When a failure is possible, find the design that makes it impossible, at its owner."

**Design round question** (dig → Opus + Astra independent → Fable hybrid → narrow check): *Make `seon.context/step-status`
judge a step by exactly the check `seon.cluster.source/prepare-merge!` would apply to the branch's candidate against main's
head (contract gaps, lint, file analysis, designed reds, broken reaching tests), so that `:done` ⇔ the merge accepts, by
construction.* What disappears: `fixable-refusals` and `refused-merge-relaunch` (a `:done` step's merge cannot be refused for
an agent-fixable reason), the `:satisfied` verdict and its branch rule (a product main already holds is a `:done` with an
empty diff, or the subject is retired), and the fork-point proxy. What must be measured first (count before designing): the
cost of the file proof and lint per verdict (770733432 measured lint at 14–132 ms per file; the file proof is unmeasured),
and how often a verdict runs per eval request. The alternative the round must weigh: deliver *every* merge refusal of an
agent's own branch to that agent with its reason (no class at all), and let root's problem task appear only when the dial is
spent. Both delete the list. The second is smaller; the first makes Run 21's 180-call step impossible as well.

Until that round lands, the two in-flight lanes (f66-satisfied: read main's head; f67-unrequired-ns: surface
`:unresolved-namespace` at the defn) are the right narrow fixes and should land first.

## 4. Process

- **Lane sizes.** 41 commits in 3 h 28 min; 23 are code, 8 run reports, 6 chain merges, 3 chain filings, 1 summary. Largest
  code commits: 7f96b6cba (17 files, 594 lines, scripted sweep), 2354cd1ad (22 files, 380 lines, net −8), ea800d326 (8
  files, 296), 874377c5f (9 files, 284), 6e8b157ed (7 files, 131). Each of the large ones is one behavior and most are
  net-negative or near zero in source; sizes are acceptable. Durations cannot be read from commits alone; the f27 lane ran
  from its first default probe (00:08 UTC, seen by the Run 20 lane) to its commit at 00:29 UTC after an unreported earlier
  stretch, and the completer sweep (7f96b6cba) wrote its decision doc and converted 8 files in one lane. Nothing here
  looks like a spiral; the spiral was in the runs (chain 5, four launches).
- **Model mix.** Co-authors: Opus 29 commits, Sonnet 11, Astra 1 (874377c5f), Haiku 0 (three reports). Within the roles.
- **Haiku accuracy.** `with-redefs-inventory.md` claimed 15 test files require `seon.operator.runtime` "which has no file"
  (verified false: `resources/seon/operator/runtime.clj`; c05059e06 checked all 414 test namespaces load), that `hook_test`'s
  `load-config` cannot resolve (false: loaded from `bin/seon-hook` by `load-file`, 3 tests pass), and counted 185 sites where
  the census counts 167 (c05059e06); the tree now holds 141 `with-redefs`/`alter-var-root` lines in 56 test files (rg).
  `turn-reds-triage.md`'s first version had 28 inferred unknowns; it was replaced by one measured check run
  (637bbb669483, 9 min wall) before anything acted on it — the right correction. `chain-candidates-3.md` was honest (one
  weak candidate, four rejected). Rule to add to the Haiku brief: a claim that something does not exist quotes the
  `ls`/`rg`/`require` output that shows it; a count is produced by the census the tree already has, not a second parser.
- **Restarts.** 8 boots of default in 3 h 39 min (one per run; pids 7800 … 92190), each a restart + reset; 6 commits marked
  RESTART NEEDED. Consistent with "one restart per batch" only because each run was its own batch; the F66/F67 fixes are
  the next batch.
- **Reds.** Unowned reds on HEAD at 06:57: about 12 (runs-16-19.md §5). Measured at 7f96b6cba: 32 red vars in 11 namespaces.
  After a05ba6655 and df21007f0: 29 red, all owned by a task. HEAD was lint-red for 25 min from a chain's merge. Main held 0
  test runs all night (verified), so "red on HEAD" was discovered by gates, not by a central round; none ran.
- **Root's own mistakes, in order of cost.** (1) Run 21: a subject whose gap is a throw was filed against a done rule that
  could not accept it; 180 paid calls. (2) Run 17: launched a rerun whose Prove step was predictably `:satisfied` on Run 16's
  partial product (F46 was already filed); it shipped a `[:cat :any :any]` contract. (3) The merge gate below the lane lint
  bar; 25 min of refused lane commits. (4) Run 23 launched 2 min after 6e8b157ed with the refusal → relaunch → `:satisfied`
  composition untested ("Not proven" in the commit). (5) plan.md §0 says "every commit that moves a phase updates this
  table" and was not touched all night: it still reads "no phase started" at f2422dcd8 while H4 and H5 ran live twice.
  (6) Residue on default: 11 branches, 4 of them attempt/base evidence from Runs 22–23 and two probe agents with bases.
  (7) `seon.config/apply!` answered `converged? false` in all 8 runs (C3) and is still unfiled as a fixed task.
- **Decisions taken in the owner's absence that look right:** deleting the purity fence; refusing F54 (a park after N
  no-progress passes) and fixing F53 at the reader; refusing F62's progress check; taking the F27 win at 220 MB.
- **Decisions to review:** `designed-red?` widened to any error (d8b470c57) — right by simplicity, it moves wrong-proof cost
  onto Implement; `:seon.task/product` as an authored set of test names (ec19ecdfb) — a new authored attribute where the
  law says proofs are derived from `:seon/tasks` tags; justified in prove-products.md, but `create!`/`refine!` cannot write it.

## 5. Plan currency

plan.md §0 was stale by one night: "Chains 5–9 ended `:capped`", "no phase started", "808 tasks, none with a step". Corrected
in place in this commit: H0–H5 done and exercised live (Runs 10–23), H6 partly (prompt shrank; no measurement filed), H7 met
twice on mined subjects (Runs 19, 20) without a task refinement written back. hybrid-chains.md is research and keeps its
date; its §5 table has no status column, so the status lives in plan.md only.

## 6. The five next actions, ranked

1. **Land f66 and f67, restart once, rerun chain 5 once (Run 24).** If it merges, chain 5 is done; if not, stop rerunning
   chain 5 and open the design round. Either way no further per-run patch to `step-status`, `fixable-refusals` or the relaunch.
2. **Open the design round of §3** ("done ⇔ the merge accepts, judged against main's head"), Haiku dig first: count verdicts
   per eval request on Runs 19–23, measure the file proof and lint per verdict, list every caller of `fixable-refusals`,
   `:satisfied` and the fork point. Two independent plans, hybrid, narrow check, then phases with red proofs.
3. **Re-own the costs the night let go:** a task with the cold one-test check as its red proof (2.3 s / 4.5 GB first check
   after boot, Run 23); re-measure 874377c5f's probe on default and set its bound honestly; measure `uncontracted-measures`
   per defn reply (b182e514c) and remove the recomputation; derive the two `:seon.eval` attribute lists in `turn.clj` from the
   schema.
4. **File F57 with a red proof** (merged function, fresh branch, stale compiled body, `:malli/schema` nil) in place of the
   deleted publication-instrumentation test; it is a correctness hazard for any chain whose successor calls a merged
   function before a reload.
5. **Hygiene and the brief:** delete the 4 evidence branches and 2 probe agents on default once f66/f67's proofs exist; fix
   C3; add the "quote the output" rule to Haiku briefs; make the committing lane update plan.md §0's phase table (the rule is
   written there and was not followed once tonight). Then run two new mined subjects concurrently (the first untested
   dimension of goal 1).
