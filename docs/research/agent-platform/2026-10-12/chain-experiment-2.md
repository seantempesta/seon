---
title: "Chain experiment 2: Design+Prove → Implement on c792b6aaf reaches a merge request"
type: research
status: complete (lane chain-2, 2026-10-12); merge request b210caab20ba waits for root; root must run the printer tests compiled before merging
created: 2026-10-12
---

# Chain experiment 2

**Result.** The second Design+Prove → Implement chain ran on default (archive **c792b6aaf**, DeepSeek flash, the shipped
`:seon.config.ai/model`) and **reached a merge request**: `b210caab20ba` (merge task `6d400a0b8f15`, branch
`:attempt-cb2ea4c5e3c0`, commit `6acc819d-f280-5511-b52b-adf45b8e07ed`), proof
`seon.render.value-test/a-count-prints-as-an-entity-reference` green there. Three of experiment 1's platform stops are
gone: a Prove step ended `:done` by itself twice (F2), the Implement step started from its predecessor's end commit and
found its proof there with no copy to main (F3), and a step proof reading `(seon.db/db)` observed the agent's branch (F1).
The Implement agent also did the pipeline's job: it found the first proof could never turn green and tried to send it
back. The cost was higher than experiment 1 (7 attempts, 73 model calls, 838k prompt tokens of which 611k cached, 22.8k
completion) and **eight hand acts** carried the chain, each recorded below: a Prove step with no proof of its own never
got a turn, the send-back verb refused the agent's call shape, the send-back's counterexample had no way to reach the
target, a capped attempt had no relaunch, the successor forked its predecessor's superseded end and could not start, and
its end was not copied to main until root ran db<->flow by hand. Root did not merge; `my.root/merge!` was not called.

Authorities: AGENTS.md "The goal", [plan](../../../seon/plan.md) §0 item 2 and §3,
[TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) §2-§3b, [chain experiment 1](chain-experiment-1.md) (method,
frictions F1-F11), [hybrid-done-and-merge](hybrid-done-and-merge.md) (M5, M10, M12 own three of today's stops).

## What root reviews: merge request b210caab20ba

| | |
|---|---|
| Merge request | `b210caab20ba`, merge task `6d400a0b8f15`, `(my.root/merge-requests)` lists it with the operations `(my.root/merge! {:seon.task/merge "6d400a0b8f15"})` and `my.root/close!` |
| Branch, commit | `:attempt-cb2ea4c5e3c0` at `6acc819d-f280-5511-b52b-adf45b8e07ed` (forked from Design+Prove attempt 4's end `6acc8121-6c54-5c0b-8231-5234812136d0`) |
| Functions changed | `seon.render.value/classify` only: the map branch's `:entity?` becomes `(boolean (and lookup? (find x :db/id)))` (the identity-attribute disjunct `(some #(find x %) identity-attributes)` is gone) and its comment says "A map is an entity only when it carries `:db/id`". `identity-attributes` is still used by `held-order`. `seon.render.value/reference-value` is unchanged; its docstring still holds. |
| Tests added | `seon.render.value-test/a-count-prints-as-an-entity-reference`, tagged `seon.render.value/count-reference-implement`: in `support/with-database` with a seeded agent `count-ref-agent`, `{:seon.turn/agent eid :seon.agent/id "a"}` and `{:seon.turn/agent eid :seon.source/digest 7}` print `eid` as a number, `{:db/id 7 :seon.turn/agent eid}` prints `[:seon.agent/id "count-ref-agent"]`. Red at its fork point, green on the branch. |
| Not covered | the subject's "a bare `{:db/id n}` under a ref attribute stays a reference" case (already asserted by `a-reference-prints-as-its-referent-s-identity`) |
| **Reached tests: unknown** | The Implement step's test step ran only its proof: `my.program/tests-reaching` answers **0** tests for `classify` (47 for `prepare`; finding G9). On a branch where `classify` is redefined, all 61 `seon.render.value-test` tests **error** in SCI (`No implementation of method: :setThreadBound of protocol: #'sci.impl.vars/IVar found for class: clojure.lang.Var`), while on an unchanged branch all 61 are green (20.1 s, 2.06 GB). The printer is platform code: it is only provable compiled. **Before merging, root runs `seon.render.value-test` on a check JVM of HEAD plus this diff** (`bin/seon check`); the interpreted result proves nothing either way. |
| Reproduction on main | `[{:seon.turn/agent 61 :seon.agent/id "a"} {:seon.turn/agent 61 :seon.source/digest 7} {:seon.turn/agent 61 :x 3}]` in JVM mode prints 61 as `[:db/ident :seon.agent/failure]` in the first two maps (40.9 ms request). |

## Setup

- **Runtime.** Default PID 96331 runs archive c792b6aaf (E4 and M1 in; P1, P4-P6 in). Health `:faulted` before the run
  (an old `seon.sci.eval/acquisition-refusal` fault, 22 errored receipts), cache 318/512 MB, heap 1.6/4 GB. Never stopped
  or reset.
- **Subject** `seon.render.value/a-count-prints-as-an-entity-reference` (filed today, owner `seon.render.value/classify`,
  `:seon.fn/host-bound? false`). Picked by the brief's suggestion and by reach: a one-line producer change with an
  existing table test. Reproduced first (above). Its weakness, found during the run: the printer is platform code.
- **Tree** (root, JVM mode): `seon.task/create!` of `seon.render.value/count-reference-design-prove` (`:step :prove`,
  no proof of its own: P5 admits a Prove step by its successor's declaration) and `seon.render.value/count-reference-implement`
  (`:needs` the first), then one transaction adding both as the subject's `:seon.task/children` and their
  `:seon.task/instructions`. The instructions were also written into each step's intent (`seon.context/held-task` still
  pulls four fields; nudge 0, F8 again).
- **Config** `seon.config/apply!`: `:seon.config.ai/no-provider false`, `:seon.config.run/max-episode-runs 15`, as
  experiment 1. Restored to `true` and `100` after the run (verified).

## Timeline (main transaction instants, UTC)

| Time | Event | Measured |
|---|---|---|
| 06:31:31.954 | both steps `:running` | |
| 06:31:31.970 | attempt `214c0023a606` of design-prove written by db<->flow; graph started | intent → attempt 16 ms |
| 06:31-06:34 | **no turn**: `seon.turn/next-agent-work` answers nil (G1) | 0 calls in 180 s |
| 06:34:31.3 | **nudge 2**: step proof `chain2.proofs/design-prove-wrote-the-implement-proof` defined on main by root's SCI (529.8 ms, test step 451 MB); new step `count-reference-design-prove-2`; needs rewired; step 1 `:cancelled` | |
| 06:34:31.347 | attempt `6d2c806b579a`; prove-first ran the red step proof; first form +0.47 s | |
| 06:35:23.245 | **end `:done` by itself** (product red by assertions, nothing broken) | 12 calls, 51.9 s |
| 06:35:23.304 | Implement attempt `53e02239f052`, forked from `6acc7fab…` (design-prove-2's end) | end → successor 59 ms; first form +2.0 s |
| ~06:35:46 | Implement fixed `classify` and diagnosed the proof: its third case's `eid` is `(:seon.db/eid (db/q '[:find (pull ?e [:db/id]) . …]))`, always nil, so the case can never pass | |
| 06:36:07 | `(my.task/send-back! 'seon.render.value/count-reference-design-prove "…")` refused: arity error (G3) | |
| 06:36:23.017 | end `:capped`; problem task `seon.task/attempt-ended-53e02239f052` written (`:held`, unassigned) | 15 calls, 59.7 s |
| 06:37:40.714 | **nudge 3**: Implement's end rewritten `:capped` → `:sent-back`, target design-prove-2, reason the agent's own text; the same text appended to design-prove-2's intent (G4) | |
| 06:37:40.796 | attempt 2 `54b843674b8e` of design-prove-2 (forks main) | 82 ms |
| 06:38:25.9 | end `:capped`: 15 calls guessing the test fixture (`seon.test.support`, `my.program/aliases`, `my.program/project`), 7 errors, no deftest | 45.1 s |
| 06:39:14.567 | **nudge 4**: end rewritten `:capped` → `:sent-back`; intent refined with the fixture attempt 1 had found | |
| 06:39:14.611 | attempt 3 `82e6d1f654b1` | |
| 06:39:59.9 | end `:capped` on call 15: a correct deftest whose last line read `)).)))`, so `Unable to resolve symbol: .` and it never defined | 45.3 s |
| 06:40:44.980 | **nudge 5**: end rewritten `:capped` → `:sent-back`, nothing else | |
| 06:40:45.020 | attempt 4 `6015ca2624e7` | |
| 06:41:37.617 | **end `:done` by itself**: a good proof (below) | 14 calls, 52.6 s |
| 06:41:37.651 | Implement attempt 2 `cb2ea4c5e3c0` written with fork `6acc7fab…` (design-prove-2's **first**, refuted end) | 34 ms |
| 06:41:37.691 | `seon.cluster.agent/fork-attempt!` throws "The attempt's predecessor branch is gone": the attempt stays open with no graph, no end (G7) | |
| 06:43:13 | **nudge 6**: fork rewritten to `6acc8121…` (attempt 4's end); **nudge 7**: one `seon.cluster.agent/db<->flow` pass run by hand (the fork fact wakes nothing) | pass 574 ms, 962 MB |
| 06:43:26.8-06:43:29 | Implement 2 reads `my.task/current`, the sources, redefines `classify`; proof green; step `:done` on its branch; merge request recorded there | 2 calls, ~4 s |
| 06:43:29-06:44:30 | the `:done` end sits on the branch: `seon.cluster.agent/unlanded-ends` lists it, nothing copies it to main (G8) | |
| 06:44:30 | **nudge 8**: a second hand db<->flow pass copies the end and the merge request on main | 77 ms |

## Per attempt

| Attempt | Step | End | Calls | Prompt / cached / completion | Wall | Evaluation errors |
|---|---|---|---|---|---|---|
| 214c0023a606 | design-prove | `:cancelled` (nudge 2) | 0 | 0 | 180 s idle | |
| 6d2c806b579a | design-prove-2 #1 | `:done` | 12 | 181,037 / 137,600 / 2,970 | 51.9 s | 1 |
| 53e02239f052 | implement #1 | `:capped` (rewritten `:sent-back`) | 15 | 141,827 / 99,072 / 7,609 | 59.7 s | 3 (send-back arity, `support` alias, `db` alias) |
| 54b843674b8e | design-prove-2 #2 | `:capped` (rewritten) | 15 | 188,818 / 142,720 / 2,741 | 45.1 s | 7 (guessed names) |
| 82e6d1f654b1 | design-prove-2 #3 | `:capped` (rewritten) | 15 | 127,189 / 87,936 / 3,144 | 45.3 s | 3 (stray `.`) |
| 6015ca2624e7 | design-prove-2 #4 | `:done` | 14 | 190,250 / 142,848 / 3,850 | 52.6 s | 5 |
| cb2ea4c5e3c0 | implement #2 | `:done`, merge request | 2 | 8,509 / 640 / 2,518 | ~4 s working (107 s blocked on the fork, 61 s unlanded) | 0 |
| **total** | | | **73** | **837,630 / 610,816 (73 %) / 22,832** | | 19 |

Productive work: Design+Prove 12 calls (attempt 1, a broken proof) and 14 calls (attempt 4, a good one); Implement 2
calls once its proof was good. Send-backs: 1 by an agent (refused by the verb's contract; root carried it), 0 accepted.
Seams: intent → attempt 16-82 ms; attempt → first form 0.47-2.0 s; end → successor attempt 34-59 ms; about 3.5-4 s per
model call. Successor needing root: yes, both times (nudges 3-8).

## Against experiment 1

| | Experiment 1 (2319152dc) | Experiment 2 (c792b6aaf) |
|---|---|---|
| Attempts / model calls | 4 / 48 | 7 / 73 |
| Prompt / cached / completion tokens | ~383k / 252k / 8.5k | 838k / 611k / 22.8k |
| Merge request | none | **b210caab20ba** |
| A Prove step ended `:done` by itself | never (F2) | twice |
| Successor started from the predecessor's commit with its proof there | only after root copied the proof to main (F3) | yes, with no copy |
| Implement found its proof wrong | no (reds invisible, F4) | yes, with the exact counterexample |
| Agent send-backs | 0 | 1 attempted, refused by `my.task/send-back!`'s contract |
| Hand acts after launch | 5 | 8 |
| Problem task for a capped end | none (F5) | written (`seon.task/attempt-ended-…`), `:held` and unassigned (F9 again) |
| Design+Prove → Implement calls when everything works | 5 + 3 | 14 + 2 |

The ideal of experiment 1's "next run" (about 5 + 4 calls, one send-back at most, a merge request) was met in shape
(14 + 2 calls on the good path, one send-back, a merge request) but not in cost: three Design+Prove attempts were lost to
a bad proof, a fixture the agent could not find, and a one-character typo on the last call.

## Frictions, each with its task

| # | Friction (observed fact, form and reply) | Producer | Task and proof |
|---|---|---|---|
| G1 | A `:prove` step admitted only by its successor's declaration gets an attempt and a graph but no turn: `(seon.turn/next-agent-work db {:seon.agent/id "attempt/214c0023a606" …})` → `nil` for 180 s (`proof-run-recorded?` is true for a task naming no proof, `proved-unanswered?` false, no wake). P5 admits exactly this shape. | `seon.turn/next-agent-work` | **new** `seon.turn/a-prove-step-admitted-by-its-successor-opens-a-model-turn`, proof `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` (red: q's attempt exists, no `:done` end within the 20 s event bound, 0 model calls) |
| G2 | A Prove step ends in the eval request that first makes its product red by assertions, before the agent can check the red is the stated one. Design+Prove #1's third case was red forever (`:seon.db/eid` of a pull is nil); it was judged `:done`. Implement caught it, as the card intends. | `seon.context/step-status` (`:prove`), the Prove instructions | first occurrence: instructions change for the next run (below), no platform task |
| G3 | `(my.task/send-back! 'seon.render.value/count-reference-design-prove "Assertion 3 can never pass …")` → `:seon.instrument/arity-error`, fix "Call one of the declared arglists.", arglists `([{reason :my.task/reason, :as request}])`: neither the request keys nor the docstring Example. The agent's last calls were spent; the send-back never happened. Met twice with F11 (identities vector). | the arity refusal (`seon.instrument/compiled-wrapper`) | **new** `seon.instrument/an-arity-refusal-offers-the-functions-example`, proof `my.task-test/a-send-back-with-positional-arguments-is-answered-with-its-example` (red: the arity error passes, `:seon.instrument/examples` is absent) |
| G4 | A send-back's reason reaches only root's end message: the attempt records `:seon.task.attempt/target`, not the reason, and the target's next context shows nothing. Root copied the counterexample into the target's intent (nudge 3). | `seon.turn/end-task-call`, `seon.context/current-state` | **new** `seon.context/a-send-back-reason-reaches-the-targets-next-attempt`, proof `seon.cluster.db-flow-test/a-send-back-reason-reaches-the-targets-next-attempt` (red: a's reply sends it back, attempt 2 starts, `:seon.context/send-backs` is nil) |
| G5 | Step instructions never reach the agent (F8, met twice): written into each intent by hand. | `seon.context/held-task` | **new** `seon.context/a-steps-instructions-reach-its-agent`, proof `my.task-test/current-answers-the-held-steps-instructions` (red: `(not (= "Write the red proof first." nil))`) |
| G6 | A capped attempt has no relaunch (F5, met twice): three relaunches were `:capped` → `:sent-back` rewrites. | `seon.task/ready` | existing M12 `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (evidence added) |
| G7 | Implement attempt 2's fork was `(first (sort fork-point))` over **both** of design-prove-2's `:done` ends, so it took the first, refuted one, whose branch had been deleted; `fork-attempt!` threw and the attempt stayed open with no graph and no end (fault `seon.cluster.agent/fork-attempt!` ×1). Rewriting the fork wakes nothing; a hand db<->flow pass started it. | `seon.cluster.agent/write-attempt!` (fork pick), `seon.cluster.agent/apply-plan!` (a failed start leaves the attempt open) | existing M10 `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` (deletes the pick) and M5 `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` (evidence added to both) |
| G8 | Implement 2's `:done` end and merge request sat on its branch for 61 s, listed by `seon.cluster.agent/unlanded-ends`, until a hand db<->flow pass copded them. The attempt had been started by nudge 7's hand pass from root's JVM REPL thread, so the missing wake may be that start's, not the platform's. | the branch's listen route | cause not reproduced: no task until it is seen on an attempt db<->flow started itself |
| G9 | `(my.program/tests-reaching {:seon.program/subject 'seon.render.value/classify})` → 0 tests, gap `:outside-graph`; `prepare` → 47. The Implement step's test step ran only its proof and its verdict saw nothing broken. Running the 61 printer tests on a branch that redefines `classify` errors all 61 (SCI `IVar` `setThreadBound` on a `clojure.lang.Var`); unchanged, all 61 are green. | `seon.test.runner/tests-reaching` (reach through `make-node`/`tuple-node` and a self-call) | **new** `my.program/a-function-called-through-its-callers-is-reached-by-their-tests`, proof `my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests` (red: the `prepare` assertion passes, the `classify` assertion fails). The interpreted printer erroring is AGENTS.md's rule that a branch never changes the printer; the merge needs a compiled check. |
| G10 | Guessed names in Design+Prove #2-#3: `seon.test.support`, `my.program/aliases`, `my.program/project`, `seon.db/with-connection`; `(my.program/source 'support/with-database)` refuses an alias the test namespace holds; `(dir seon.render.value-test)` → `[]`; twice `(:seon.task/intent (seon.context/current-state))` → nil. | TASK-CHAIN §6b; `my.program/source` alias resolution | §6b's existing plan (answer at the failure); counts here are its evidence |
| G11 | Costs on the way: defining one deftest from root's SCI 529.8 ms with a 451 MB test step; the edit hook's test step after a one-test edit 0.85-2.2 GB; one hand db<->flow pass that starts an attempt 962 MB; the 61 printer tests 20.1 s and 2.06 GB (41 GB in the JVM window). | test step, db<->flow start | existing F10 tasks and lane cost-gb's queue |
| G12 | `my.test/check` answering a reused red result shows `:seon.test/passed? false` with no counts and no failures in its summary keys; the data is only under `:seon.test/results`. A `my.root`/JVM reply's cost diff lists `seon.render.value/project` and its neighbours as `:interpreted :new` on root's main, from other agents' runs. | `my.test/check` summary, the cost diff | not filed today (context noise; next lane) |

Friction met twice and changing an entrance rule or proof: F8 → G5 (proof filed), F11 → G3 (proof filed), F5 → G6
(existing task, evidence), F9 (problem task `:held` and unassigned) → existing `seon.task/each-problem-class-is-detected-from-its-facts`.

The five new proofs were run on lane chain-2's own branch in default (the edit hook and `my.test/check`), each red for
its stated reason as quoted above. The fork-point proof first written for G7 was deleted before commit: M10 makes every
assignment fork main, so a proof of "forks the latest end" would assert a rule the agreed design removes.

## The two proofs Design+Prove wrote

Attempt 1 (rejected by Implement): cases with a literal 61 and a third case `(:seon.db/eid (db/q '[:find (pull ?e [:db/id]) . :limit 1] …))`,
nil, so the expected text `"{:seon.turn/agent [:seon.agent/id nil], :db/id }"` can never print. Attempt 4 (the merge
request's): the seeded referent `count-ref-agent`, its `eid` by query, three cases (identity attribute without `:db/id`,
source digest without `:db/id`, `:db/id` present), red at the fork point by assertion. It followed the fixture hint
nudge 4 wrote (`(in-ns 'seon.render.value-test)`, the namespace's `support`, `db`, `value` aliases and `render-request`).

## Nudges (every hand act after launch)

0. (pre-launch) Instructions copied into each step's intent (F8 / G5).
1. Config: provider on, cap 15; restored after.
2. A step proof for Design+Prove defined on main; the step recreated as `-2`; needs and children rewired; step 1 cancelled (G1).
3. Implement #1's end `:capped` → `:sent-back` with its target and its own reason; the reason appended to the target's intent (G3, G4).
4. Design+Prove #2's end `:capped` → `:sent-back`; the intent refined with the fixture attempt 1 had found (G6, G10).
5. Design+Prove #3's end `:capped` → `:sent-back`, nothing else (G6).
6. Implement #2's `:seon.task.attempt/fork` rewritten to attempt 4's end (G7).
7. One db<->flow pass by hand to start it (G7: the fork fact wakes nothing).
8. One db<->flow pass by hand to copy its end and merge request (G8).

## Next run

Merge G1, G3, G4, G5 and G9 (each is a small producer change with its red proof) and M5, M10, M12 from
hybrid-done-and-merge; then rerun a subject that is not platform code (the printer cannot be proven on a branch).
Instructions changes for the Prove step: give the test fixture by name (the namespace to `in-ns`, its aliases and helper),
and require a REPL dry run of each case's expected value before the deftest, since the step ends the moment its product
is red. Expected cost if nothing else breaks: about 12 + 3 calls, one send-back at most, zero hand acts, and the merge
request carrying its reached tests' results.
