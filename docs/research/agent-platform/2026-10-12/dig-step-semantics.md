---
title: "Step semantics: where completion is decided and what each path reads"
type: research
status: complete (lane dig-steps, 2026-10-12)
created: 2026-10-12
---

# Step semantics: every decision point

## Task-end decision: seon.turn/task-end (line 2163)

Reads `:seon.agent/id` on `database` (agent's branch, frozen state after no turn open).

```
:done          ← task-done? reads: proofs ✓ green AND no reached-red
:sent-back     ← sent-back-to reads: latest :seon.turn/disposition :completed with :seon.turn/sent-back-to
:given-back    ← db/q finds any :seon.turn/disposition :completed (turn exists)
:capped        ← turns-left = 0
:stalled       ← stalled? reads: latest turn closed with no disposition AND no continuing reply AND no unanswered wakes
nil            ← admission closed, no task held, or turn open
```

**Key observation: :done is impossible for Prove if its red proof is on successor (F2).** `task-done?` (line 164) checks `(empty? (:seon.agent/reached-red ...))` where reached-red is set by `record-reached-red!` (line 5470) reading the test step's new reds. Prove writes the successor's proof on its branch, so it lands in reached-red and blocks :done until its step is done.

## Validity check: seon.cluster.agent/plan-facts (line 1702)

Reads on main only. A leaf is `:valid` iff `(seq (:seon.task/proofs edges))` where `edges = seon.task/task-edges` (line 1729).

**Defect F3:** `task-edges` reads `seon.task/tasks` on main's definition, not the fork point. When Implement starts, its proof exists on Design's branch (recorded on Prove's end commit), not on main. Plan classifies it `:invalid` and writes a problem task. **Fix:** Check validity at the fork point (the predecessor's end commit), read via `seon.cluster.source/fork-attempt!`.

## Reached-red recording: seon.turn/record-reached-red! (line 5470)

Reads `database` (branch), `(root-execution cluster)` (main), and `agent-id`. **Defect F1:** Within `my.test/member`'s run on the branch, `seon.test.runner/latest-results` filters by branch, so the member reads `:pending` while the agent reads `:red` on main. **Fix:** Pass the branch and commit as explicit test inputs.

`change-reds` (line 3047) reads: (a) reds on branch via `runner/latest-results`, (b) same tests on main under same evaluator at current reach, (c) new tests red on branch that are not red on main = reached-red.

## Proofs visibility: seon.context/current-state

Reads: `(seon.context/proofs database task-sym)` on agent's database (branch). **Defect F4:** Proofs render, but `reached-red` does not. Agent says "task is :done" with no mention of red tests it broke. **Fix:** Render reached-red in the proofs block.

## Agent starters: db<->flow plan actions

**Legitimate paths:**
1. `plan-facts` + `apply-plan!` (line 2297): starts `:startable` attempts via `:start-flow` → `seed-attempt!` → `write-attempt!` + `start-branch-flow!`.
2. Fork point (line 722): `fork-attempt!` sorts commits and picks first uuid (lexical). **Fallback bug (F5):** if predecessor's branch is deleted (keep-ended-branches? false), fork main instead. **Fix:** Record fork commit on attempt, tag it, octopus-merge for joins, delete fallback.

**Legacy path (outside plan):** `seon.cluster.host/start!` calls `start-branch-flow!` directly. Docstring says db<->flow is "the only other starter" but it coexists. **Defect #8:** design unresolved (hosted agents, held-task exemption).

## Held-task exemption: seon.turn/next-agent-work

A task held (assigned by hand) with `:execution :held` (not `:running`) still opens model turns on wakes if `(not ((:seon.agent/finish? …)))`. **Defect #8:** This exemption and `agents-to-start` (old derive-all) conflict with plan's `:running`-only launch. **Fix:** Every graph starts from plan, or held agents become attempts with explicit `:running` intent.

## Stop/cancel semantics: seon.cluster.agent/follow! (runtime-follows-db D3/R2)

**Intended (D3):**
- `:stop` → `:stopped`: finish? true, release SCI context
- `:cancel` → `:cancelled`: interrupt, end on branch, delete branch, no problem task

**Actual (HEAD 9e7958e30):** Both interrupt. `:cancelled` writes end on main, skips branch delete, and next `land-ends!` treats it as error (keeps branch, files problem task contradicting "terminal").

**Pause:** Sets flag, no acknowledgement via `flow/ping-proc`.

## Call cap and stall: no relaunch

When `:capped` (turns-left = 0) or `:stalled` (loop stopped): `seon.task/frontier` excludes it forever. Root gets the end message but no repair problem (runtime-follows-db §5 missing). **Defect F5:** No verb to relaunch. Both nudges 2 and 5 were hand edits (new step task, end rewritten). **Fix:** File a repair problem (existing `seon.task/each-problem-class-is-detected-from-its-facts`) or `my.task/relaunch!`.

## History

**Deleted cea904e92 (2026-09-28):** `seon.plan`, `my.plan`, task-steps entity, `settle-call`, agent's plan component. Reason: tasks carry the plan (children + needs); no separate entity.

**Before:** Steps lived in plan (My, Design, Prove, Implement, Land); proof selection was stage-aware. **Now:** Steps are task children; proofs are ordinary deftests. Pipeline A is a tree shape (TASK-CHAIN §3).

## Open questions

1. **Prove's red proof for Implement:** Is the proof "the successor's test, written by me" or "Implement's proof, written by me"? Chain-experiment-1 proved "written by me" (design-prove-2's deftest, define-rooted) and reached Implement. But TASK-CHAIN §3b lists Prove's proof as "required cases = observed cases" and Implement's as "required proofs ✓ all green", both on the branch. **Semantics unresolved** (F2's fix pending).

2. **Validity window:** At what commit is `:invalid-task` detected? Proposed: at fork point (R1 new E6), read validity there. **Issue:** plan-facts reads only main, plan actions fork from basis; they are different moments. **Design needed** (F3's fix, ~1 h).

3. **Relaunch**: After `:capped` or `:stalled`, what writes the retry? `seon.task/frontier` and the reconciler do nothing. **Missing affordance** (F5, existing skeleton `each-problem-class-is-detected`).

4. **Held-task intent table (R2):** Are `:held` agents legacy or an official part? If official, their `:running` status is unbuilt (task R3). **Design round pending** (#8, TASK-CHAIN §6b).
