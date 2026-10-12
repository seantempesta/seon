---
title: "Chain experiment 1: the first real two-step chain on default"
type: research
status: complete (lane chain-experiment-1, 2026-10-12); Implement never ended green, so no merge request reached root
created: 2026-10-12
---

# Chain experiment 1

**Result.** The first real Design+Prove → Implement chain ran on default (archive 2319152dc, DeepSeek flash) and did
**not** reach a green Implement or a merge request. db<->flow's half worked: a `:running` step got its attempt, branch,
graph and first model call within 0.4-1.3 s, the successor waited on `needs` and forked from its predecessor's end
commit, ends were written once on main, and the problem class `:invalid-task` was written as a task. Every stop was a
step that could not end: two platform defects stop a correct chain outright (a Prove step can never be `:done`; an
Implement step whose proof its predecessor wrote is `:invalid`), one hides why an agent is not done, and my first step
proof was unsatisfiable under the platform's evidence rule. Four paid attempts, 48 model calls, about 383k prompt tokens
(252k cached) and 8.5k completion tokens. Five hand nudges, each recorded below, carried the chain to its next failure.

Authorities: plan §0 item 6, [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) §2-§5,
[runtime-follows-db](../2026-10-11/runtime-follows-db.md) §2-§5, [R1](../2026-10-11/r1-db-flow-design.md),
[hybrid-agent-world](../2026-10-11/hybrid-agent-world.md), [Astra's overnight review](astra-overnight-review.md) (which
predicted the fork-point and branch-proof gaps met here), and `seon.cluster.db-flow-test` (the fixture chain this run is
the real counterpart of).

## Setup

- **Runtime.** Default PID 94708 runs archive **2319152dc** (not HEAD, e3adc851f at launch): it has S3/R1 (`seon.cluster.agent/plan`,
  `plan-facts`, `apply-plan!`, prove-first, predecessor-commit fork) but not P2-P4 (no end copy-back, no branch deletion,
  no inboxes). Every observation below is of that archive. Health was `:degraded` before the run (11 hot-untested warnings,
  1 errored receipt, 1 unowned namespace); cache 503/512 MB; heap 0.95/4 GB.
- **Subject.** `seon.sci.forgive/prose-after-the-last-form-is-dropped`: a real open defect with an owner symbol, a
  reproduction and no proof. Reproduced first (JVM mode, 94.7 ms request): `(seon.sci.forgive/segments "(+ 1 2)\nDone, it
  works.")` answers `{:seon.batch/segments [{:seon.batch/source "(+ 1 2)"}]}`, the prose dropped; prose before a form
  becomes its comment. `segments` is 23 lines, pure, interpretable on a branch. Rejected candidates: `seon.print/a-path-form-reads-any-plain-key`
  and `a-dig-form-through-a-list-index-reads-nil` (no longer reproduce), `seon.render.var/the-offered-check-names-an-unquoted-symbol`
  (still reproduces, but its proof needs a pending-test fixture), `my.fs` and `seon.cluster.agent` tasks (host-bound or claimed).
- **Tree** (root, JVM mode, `seon.task/create!` then one transaction): subject → children
  `seon.sci.forgive/trailing-prose-design-prove` and `seon.sci.forgive/trailing-prose-implement` (`:needs` design-prove),
  must-read `segments`, `comment-of`. Instructions written as `:seon.task/instructions` **and** into each step's intent,
  because at 2319152dc `seon.context/held-task` pulls only id, sym, intent and must-read: neither instructions nor the
  subject reach the agent (card S1, already filed in `seon.task`; pre-launch nudge 0).
- **Step proof** (root SCI, namespace `chain1.proofs` on main): `design-prove-wrote-a-red-behavior-proof` asserted that a
  deftest tagged with the Implement step exists and every such deftest reads `:red` through `seon.context/proofs` on
  `(seon.db/db)`. Implement's proof was to be the one Design+Prove writes.
- **Config** (`seon.config/apply!` on default, restored after): `:seon.config.ai/no-provider false`,
  `:seon.config.run/max-episode-runs 15` (the per-agent call cap; shipped 100). Root holds no task, so it takes no model turn.

## Timeline (main transaction instants, UTC)

| Time | Event (fact) | Measured |
|---|---|---|
| 00:21:18.915 | both steps `:seon.task/execution :running` (t 536871164) | |
| 00:21:18.948 | attempt `08d6fe652434` of design-prove, agent and branch `:attempt-08d6fe652434` written by db<->flow (t+1) | intent → attempt 33 ms |
| 00:21:19.550 | its first turn on its branch (prove-first ran the red step proof, then the model) | attempt → first turn 0.60 s |
| 00:21:55.764 | end `:capped` on main with its commit; root gets the end message | 15 calls in 33 s |
| 00:23:24.016 | **nudge 2**: new step `trailing-prose-design-prove-2` with a satisfiable step proof; needs and children rewired; step 1 `:cancelled` | |
| 00:23:24.058 | attempt `e95d4e90b70f` (forks main: no done needs) | 42 ms; first turn +0.39 s |
| ~00:23:41 | its step proof `design-prove-tagged-a-behavior-proof` green (call 5); `:seon.agent/reached-red` = its own new red test | |
| 00:23:55.903 | end `:capped` (calls 6-15 said "done") | 15 calls in 30 s |
| 00:24:50.821 | **nudge 3**: its end rewritten `:capped` → `:done` | |
| same pass | implement on the frontier, judged `:invalid` (no proof on main); problem task `seon.task/invalid-task-ba199582ec50` written, `:held`, unassigned | |
| 00:25:22.695 | **nudge 4**: root defines Design+Prove's deftest on main (copied from its branch) | define: 2.05 s, test step 1.9 GB |
| 00:25:22.900 | attempt `b83a488f2b98` of implement, forked from design-prove-2's end commit (it holds design-prove-2's agent and test) | first turn +1.28 s |
| ~00:25:31 | its proof green at call 3; two existing `seon.sci.forgive-test` tests reached red | |
| 00:25:54.684 | end `:capped` (calls 4-15 repeated `(my.task/current)` and "the task is :done") | 15 calls in 29 s |
| 00:27:08.890 | **nudge 5**: its end rewritten `:capped` → `:sent-back`, intent refined with the two reached reds and the send-back option | |
| 00:27:08.960 | attempt 2 `096a91fbbffd` (number 2), forked from design-prove-2's commit | 70 ms; first turn +1.19 s |
| 00:27:16.115 | end `:stalled` after 3 calls: calls 2 and 3 were DeepSeek DSML tool-call markup, no form | 7.2 s |

No merge request was recorded (no end ran through a green `seon.turn/end-task!`); root received 6 end messages and no
repair problem for any `:capped` or `:stalled` end. Default's frontier is empty; all four attempt branches remain (no P2 at
2319152dc). Residue on default's main, disposable: the four step tasks, the problem task, `chain1.proofs`, and the
deftest `seon.sci.forgive-test/prose-after-the-last-form-is-held-as-its-comment` (root-defined, not in any file).

## Per step

| Attempt | Step | End | Calls | Prompt / cached / completion tokens | Wall | Agent errors seen in replies | DSML replies |
|---|---|---|---|---|---|---|---|
| 08d6fe652434 | design-prove (1) | `:capped` | 15 | 145,899 / 102,656 / 3,578 | 36.8 s | `:seon.test/identities` vector refused (contract wants a set); guessed `seon.problem/at` | 1 |
| e95d4e90b70f | design-prove-2 | `:capped` (rewritten `:done`) | 15 | 85,222 / 48,640 / 2,282 | 31.8 s | same identities refusal; one platform fault (below) | 1 |
| b83a488f2b98 | implement (1) | `:capped` (rewritten `:sent-back`) | 15 | 138,233 / 96,896 / 2,081 | 31.8 s | none recorded | 1 |
| 096a91fbbffd | implement (2) | `:stalled` | 3 | 14,093 / 3,968 / 572 | 7.2 s | replies without a form | 2 |

Productive work took 5 calls (design-prove-2: read, design, write the test, run it) and 3 calls (implement: read, change,
green). The other 40 calls were spent on a step that could not end. Send-backs: 0 by an agent (the one `:sent-back` end is
nudge 5's rewrite). Seams: intent → attempt 33-70 ms in one pass; attempt → first model turn 0.4-1.3 s (fork, acquire,
graph start, prove-first); about 2.1 s per model call end to end.

## Frictions, each with its task

| # | Friction (observed fact) | Producer | Task |
|---|---|---|---|
| F1 | My first step proof read `seon.context/proofs` on `(seon.db/db)` inside the test member: the member runs on its own branch and `seon.test.runner/latest-results` keeps only runs recorded for that branch, so the behavior proof read `:pending` there while the agent's REPL read `[:red]`. 15 calls, `:capped`. Partly my proof's defect; the platform offers a step proof no way to observe its agent's program. | `seon.test.runner/latest-results` (branch filter), TASK-CHAIN §3b inputs | `seon.context/a-step-proof-cannot-observe-its-agents-test-evidence` (decision: supply the held step's branch and commit as the member's explicit input) |
| F2 | A Prove step can never end `:done`: its new red test (tagged with the successor) is red on its branch and absent on main, so it lands in `:seon.agent/reached-red` and `seon.context/task-done?` is false with green step proofs. The agent then loops until capped. | `seon.test/change-reds` → turn's reached-red → `seon.context/task-done?` | `seon.context/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red`, proof `seon.cluster.db-flow-test/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red` (patch) |
| F3 | The successor is `:invalid` when it becomes ready: `seon.cluster.agent/plan-facts` checks `seon.task/task-edges` on main, but its proof is on the predecessor's branch, which is exactly where `fork-attempt!` will fork it. A problem task is written instead of an attempt; its intent says "names no proof", which is false at the fork point. | `seon.cluster.agent/plan-facts` | `seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts`, proof of the same name (patch; task file held by lane hybrid-env) |
| F4 | Reached reds are invisible: with green proofs and two reached-red tests, `my.task/current` and the block show only the proofs, so the Implement agent said "done" for 12 calls and never weighed the send-back the reds called for. | `my.task/current`, `seon.context/current-state` | `seon.context/reached-reds-are-invisible-to-the-agent` |
| F5 | A capped, stalled or given-back step has no relaunch: the frontier excludes it for good and root gets only a message, no repair problem. Both relaunches were hand edits (a new step task; an end rewritten to `:sent-back`). | `seon.task/frontier`, the missing repair problem (runtime-follows-db §5) | `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (patch; held file); repair problems: existing `seon.task/each-problem-class-is-detected-from-its-facts` |
| F6 | One platform fault during design-prove-2: `seon.turn/show-row` passed `(or source "")` to `seon.sci.eval/shown-failure`, whose request requires a non-empty source (signature `f7755aef…5fd166`). Reproduction not isolated. | `seon.turn/show-row` | `seon.turn/a-form-without-source-is-shown-as-a-fault` (patch; held file; no proof yet) |
| F7 | DeepSeek answered 5 of 48 calls with DSML tool-call markup; twice in a row ended implement-2 `:stalled`. | `seon.ai`, `seon.turn/continuing-reply?` | existing `seon.ai/a-dsml-tool-call-reply-reads-as-prose` (no proof yet; this run is fresh evidence) |
| F8 | Step context lacks instructions and the subject (`seon.context/held-task` pulls four fields). Worked around before launch by writing both into each step's intent. | `seon.context/held-task` | existing card S1 task in `seon.task` (instructions on the step) |
| F9 | The `:invalid-task` problem stays open after the leaf became valid and started; it was created `:held` and unassigned. | `seon.cluster.agent/write-problem!` | existing `seon.task/each-problem-class-is-detected-from-its-facts`, `seon.task/a-repeated-problem-writes-nothing` |
| F10 | Cost: defining one deftest at root's SCI REPL took 1.19-2.05 s with a 1.5-1.9 GB test step (the test itself 18-43 ms); `seon.context/proofs` for two one-proof tasks took 489 ms / 729 MB. | root's test step, `seon.context/proofs` | existing `my.test/a-two-test-check-allocates-a-gigabyte-and-a-half`, `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte` |
| F11 | Agents passed `:seon.test/identities` as a vector twice (refused: the contract wants a set) and hallucinated completion verbs (`seon.task/done`, `my.task/done!!`). | `my.test/check` contract; missing affordance | TASK-CHAIN §6b (guessed names); existing `my.program/a-bare-subject-is-a-valid-read-request` (take what agents pass) |

Pipeline learning, not a code defect: Design+Prove chose a trailing segment with an empty `:seon.batch/source`, and its
table asserted only `segments`. Two existing tests (`the-reader-reads-a-reply-by-its-line-rules`,
`recorded-replies-read-into-the-same-segments`) assert that prose-only text yields no segment, so the shape breaks them;
F6's fault is the same hazard (an empty source reaching the printer). The Prove instructions must require running the
tests already reaching the subject function against the designed shape, and recording which ones the shape changes (the
card's "every existing test asserting the old behavior updated, or the decision that none exist"); Implement's first
check found it only because nudge 5 told it.

## What worked

- **db<->flow drives a tree from facts.** Setting `:running` produced exactly one attempt for the ready leaf and none for
  the leaf that `needs` it; a second pass wrote nothing. Ends were recorded once, with their commit, on main.
- **The successor forked from its predecessor's end commit**: implement's branch held design-prove-2's agent and its
  deftest, so the red test reached Implement without any copy (the R1 fork-point rule works as designed).
- **Prove-first**: every attempt's first pass ran its proofs before the model; the red result was the model's first context.
- **Small agents do the real work fast once the step can end**: design and red proof in 5 calls; implementation to green
  in 3 calls (it then broke two reached tests, which is the reason the pipeline has a check).
- **The problem-as-task path** wrote `seon.task/invalid-task-ba199582ec50` by identity in the same pass.

## The red proofs

`chain-experiment-1-proofs.patch` (applies to HEAD 569bddafd) holds two deftests appended to
`test/seon/cluster/db_flow_test.clj` (`chain-world` accepts a nil proof body for a step whose predecessor writes its
proof) and the task entries for the two task files lane hybrid-env holds (`seon.cluster.agent.edn`, `seon.turn.edn`).
The test file and those task files are claimed by lanes p4-branch-writes and hybrid-env, so they go to their owners as a
patch; the `seon.context` tasks are committed here. Both proofs were run on a throwaway JVM (`bin/seon check --at` a
detached commit of 590618c2e, which already has P4 slice 2 and P7, plus the patch; nothing else touched):

| Proof | Result | Red for the stated reason |
|---|---|---|
| `a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red` | red (run ed3a7c05400b): p's step proof observed green on its branch (passes), then no `:done` end within the 20 s event bound and a second model call (fixture provider, 2 calls) | yes: the step proof is green and the attempt continues, as F2 |
| `a-successor-whose-proof-its-predecessor-wrote-starts` | red (run e6d47affd824): 0 attempts of i, an `:invalid-task` problem written | yes: F3 |
| control `a-green-first-step-ends-itself-and-its-end-starts-the-next-from-its-commit` | green in the same run | the fixture works |

F3 is about validity, not the fork point: hybrid-env's new phase E6
(`seon.cluster.agent/a-successor-starts-from-its-predecessors-program-context`) persists and forks from the predecessor's
end commit, and F3's fix should read validity at that same fork point.

## Nudges (every hand act after launch)

0. (pre-launch) Instructions and the subject's intent copied into each step's intent (F8).
1. Config: provider on, cap 15; restored to `no-provider true`, cap 100 after the run.
2. Design+Prove relaunched as a new step task with a satisfiable step proof; needs and children rewired; step 1 cancelled (F1, F5).
3. design-prove-2's end rewritten `:capped` → `:done` (its step proof was green; F2).
4. Root defined design-prove-2's deftest on main so implement was not `:invalid` (F3).
5. implement's end rewritten `:capped` → `:sent-back` to get attempt 2; its intent refined with the reached reds (F4, F5).

## Next run

Land F2, F3 and F4 (with F1's decision and F5's relaunch verb), restart default on that HEAD, and rerun the same subject
with the same two instructions plus the Prove change above. Expected cost if nothing else breaks: about 5 + 4 calls,
one send-back at most, and a merge request in root's queue.
