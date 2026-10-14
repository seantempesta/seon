---
title: "S5 run 1: the Design -> Prove -> Implement chain for the unresolved-alias nearest defect stalled at its first task with 0 model calls. Design's template proof was already green on main (recorded by root's own §4 readiness round, with no task input), so the chain agent derived no :prove pass, no model turn and no end. 0 calls, 0 tokens, no merge; config restored"
type: research
status: complete (lane s5-run acting as root, 2026-10-14 10:03-10:20 UTC); stopped at Design, chain left in place for root, config restored and verified
created: 2026-10-14
---

# S5 run 1

Script: `docs/research/agent-platform/2026-10-14/s5-run-plan.md`. Default pid 7558, booted 10:03:31 UTC from
`data/source/76c4bed09…` (HEAD), `:seon.boot/missing-layers []`.

**Broken first.**

1. **The chain stalls at Design, with nothing to show for it: no model call, no attempt end, no fault.** The chain
   agent `attempt/35a165b24683` (attempt `35a165b24683`) started on `:chain-e80fbb64f0e0` at 10:07:12. Ten minutes
   later it had no turn, no `:seon.ai.attempt`, no test run and no end. Root sees only an open attempt.
   `(seon.turn/next-agent-work head {:seon.agent/id "attempt/35a165b24683"})` answers `nil`. On the branch:

   | Read | Value |
   |---|---|
   | `seon.turn/step-proved?` | `true` |
   | `seon.context/task-done?` | `true` |
   | `seon.turn/attempt-running?` | `true` |
   | `seon.turn/proved-unanswered?` | `false` |
   | `seon.context/current-results` for `seon.step.design-test/handoff` | green, 8 pass, run `100ebcd52ded` at 10:05:42, before the chain existed |

   Because the step is already "proved", `next-agent-work` skips the `:prove` arm. Because the task is "done",
   `proved-unanswered?` is false. Nothing else proposes `:done`, so the agent sits idle forever. That run was mine:
   §4's row "proof table present / handoffs green on main", which says to use "root's central round after the
   restart". `current-results` on main was `[]` until then.
   - The plan was the untouched Design template (no `## What exists`, no `## Approach`), so the handoff on this
     task would have been red.
   - **Root cause (D1): a template proof's result recorded with no task input proves every later task that holds
     that proof.** The result's `:seon.test.member/reach-digest` (`b10dad84…`) equals the branch's current reach
     digest for the test. Its `:seon.test.run/input-digest` (`f3a42e81…`, the input-less run) is not part of what
     `seon.context/current-results` matches, and so not part of what `seon.turn/step-proved?` and
     `seon.context/task-done?` match either.
   - Every template task (Design, Prove) is exposed. The first central round, or any test step on main that reaches
     `seon.step.design-test/handoff`, makes every later chain's Design "done at its start". Owner:
     `seon.context/current-results`, or the reach digest of a test that reads `seon.test/task` (S2a said the input
     is keyed by content). Proof shape: after an input-less green run of `seon.step.design-test/handoff` on main, a
     fresh `chain!` Design attempt opens `:prove` and its first model call.
   - **D2: a task "done" by reused results never ends.** TASK-CHAIN §2.3 says "all green at the start ends the task
     with no model call". Here the green came from reuse, not from this attempt's `prove-turn`, so no `:done` end
     was written. The attempt is open with no end, and `:seon.config.agent/max-running` counts it. The S3a-2 test
     (`seon.cluster.db-flow-test/a-chain-agent-makes-its-first-model-call-with-no-wake`) covers green-at-start only
     when the attempt's own prove pass produced the green.
   - **The S5 run plan made this happen.** §4 asks root to make the template proofs current and green on main
     before launch. It should say the opposite: no template-proof run on main before a chain run, until D1 is
     fixed.
2. **`seon.task/chain!` cannot take a filed task's identity.** The §2 form as written answered
   `:seon.db.write/validation-refusal` `:seon.task/refused :revision-conflict` ("seon.error is refused:
   revision-conflict."):
   - `:seon.task/current` is the ingested file entry `f1f6dc8e6652`, with its original plan;
     `:seon.task/proposed` is the chain's map.
   - `chain!` calls `adopt-tasks-tx` with basis `{}`, so any first map that reuses a filed `ns`/`name` with
     different content conflicts.
   - TASK-CHAIN §2.1 "From a filed task" needs exactly this. Owner: `seon.task/chain!` (S3f-1 must pass the
     filed declaration as the basis).
   - The fork happened before the refusal, leaving branch `:chain-f1f6dc8e6652` with no chain fact. Root retired it
     (`my.agent/retire-branch!`).
   - Decision (recorded): relaunch under a distinct first name, `seon.error/s5-alias-name-nearest-core-name`. This
     gives up only the S3f-1 entry deletion, which was a hand act anyway.
3. **No way to enter a chain agent from outside.** MCP `eval_clj` with `:agent "attempt/35a165b24683"` did not
   find the agent: it lives only on the chain branch. MCP minted a new agent of that id on a new branch
   `:agent-0e7bebc71e2b` (retired at once). Its `(seon.context/current-state)` was
   `{:seon.context/turns {:my.agent/turns-left 40}}` and `(my.task/current)` was `{}`. TASK-CHAIN §2.2's `:outside`
   tasks ("a Claude Code agent through MCP `eval_clj`, as that agent id on the chain branch") cannot work until MCP
   resolves agents on chain branches. Owner: the MCP agent resolution (`seon.cluster.agent/acquire-context!` by
   agent id).
4. **`bin/seon logs default` shows nothing from this JVM.** Its newest dated line is 2026-10-14T06:49Z. The JVM
   that booted at 10:03Z logs elsewhere, or nowhere. The watch half "watch `bin/seon logs default` for `turn proc is
   parked`" could not run.

## Readiness checklist (§4), ticked 10:04-10:06 UTC

| Row | Answer |
|---|---|
| boot | source-root `76c4bed09…` = HEAD, missing-layers `[]` |
| S1c-2, S1d-2, S2b-2, S3a-1, S2a, S3a-3, S4b-design, S4b-prove resolve | all `true` (`chain!`, `update!`, `done-refusals`, `chain-facts`, `result-at-reach`, `advance-tx`, `design/handoff`, `prove/handoff`) |
| S4a-1 templates | `(#{seon.step.design-test/handoff} #{seon.step.prove-test/handoff} nil)` |
| tests present | all 8 named tests have entities |
| green on main | `current-results` `[]` (nothing run since boot), so root ran the 8 by `my.test/check` `:seon.test/identities`: `passed? true`, 17.8 s, 1.09 GB. **This run caused Broken 1.** |
| subject still red | `:installed-nearest nil` |
| files free | `src/seon/error.clj` and `test/seon/error_test.clj` held by no lane (boot-publish, red-values, s3a-2b, s3b listed) |
| S3b-2 | not landed (known) |
| optional | `seon.task/from-filed` nil, `my.root/run!` nil |
| config | after apply: model `"deepseek-flash"`, no-provider absent, 40, relaunches 0, max-running 4 (`converged? false`, C3) |

Every row passed. Gap: the check proves the template proofs green with no task input, and that very result breaks
the run.

## Run

- 10:06:42: §2 form, refused (Broken 2).
- 10:07:04: retired `:chain-f1f6dc8e6652`.
- 10:07:12: `chain!` with first name `s5-alias-name-nearest-core-name` answered
  `{:seon.chain/branch :chain-e80fbb64f0e0 :seon.chain/first "e80fbb64f0e0" :seon.task/handles ["e80fbb64f0e0" "328e2aec8eca" "5c640ebf1a1f"]}`
  in 172 ms. That is the last setup act.
- 10:07:31: root's pass had started the attempt: Design `:running` with attempt `35a165b24683`, agent entity
  946210; Prove and Implement `:held`. S3a-1 worked.
- 10:07:55, 10:09:07, 10:10:17, 10:12:09: unchanged. No turn, no AI attempt, no test run, open faults 2 (both from
  before the run).
- 10:12: stopped, config restored (verified below). The chain fact, branch and open attempt are **left in place for
  root**: they hold the D1/D2 evidence.

The §3 watch form's `loop` pulls `[:seon.task/id nil]` at the end of the chain: "seon.db/pull refused argument 2
`entity-id` … got nil". The working watch pulled the three handles directly.

## Grades

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End | Done-refusals seen |
|---|---|---|---|---|---|---|---|
| Design `e80fbb64f0e0` | 0 | 0 | 0 | open 5 min at stop, no end | 0 | none (idle, `next-agent-work` nil) | none shown (no block was ever rendered for a turn) |
| Prove `328e2aec8eca` | not started | | | | | `:held` | |
| Implement `5c640ebf1a1f` | not started | | | | | `:held` | |
| **Total** | **0** | **0** | **0** | | | | |

**Compared with:**

| Measure | Rough run 1 | Design prototype 1 | S5 run 1 |
|---|---|---|---|
| Model calls | 38 | 12 | 0 |
| Prompt tokens | 570k (310k cached) | 217k | 0 |
| Hand acts after launch | 6 | 0 | 0 that moved the chain (below) |
| Merge | Prove auto-merged, Implement refused (conflict) | n/a | none: S5 did not pass |

**Hand acts after `chain!`:** none moved the chain. Root's acts, each named with the mechanism that should have
made it unneeded:

1. A probe: MCP SCI eval as the chain agent's id. It created a stray agent and branch, retired at once (Broken 3).
   Mechanism: MCP resolving chain-branch agents (§2.2 `:outside`).
2. An attempt to run the Design proof on the chain branch with the task input from JVM mode
   (`seon.cluster.agent/acquire-context!` on the operator instance, then `seon.turn/run-proofs`). It failed at
   argument preparation and had no effect. Mechanism: the attempt-start `prove-turn` (§2.3), blocked by D1.
3. Config restore (planned, §3 step 7).

Before launch: retiring the orphan `:chain-f1f6dc8e6652` (Broken 2), and unmapping two defs root made by accident
in its leftover SCI namespace (below).

**S5 pass criteria:** none met (0 hand acts holds only trivially).

## Context defects (exact excerpts)

No chain agent rendered a block or a reply, so these come from root's own path and the one stray probe.

- **A chain agent's block is empty when read from outside**: `(seon.context/current-state)` gave
  `{:seon.context/turns {:my.agent/turns-left 40}}`. This was the stray same-id agent, but it is what any MCP
  entrant sees today (Broken 3). The S3a-2b context half (task, next, done refusals) is not landed.
- **Root's SCI namespace was left at `seon.prove-prototype-test`** from rough run 1. A root `(def s5-check …)`
  therefore became a program declaration on main, `seon.prove-prototype-test/s5-check`, with an `:auto-required`
  finding listing 8 test namespaces. Unmapping it was refused while `s5-members` referred to it: "Cannot perform
  my.program/ns-unmap! on seon.prove-prototype-test/s5-check; repair the named referrers first." Both are unmapped.
  The block never says which namespace root is in before a `def`.
- **Every root SCI eval reply on main carries the same unrelated test-step error**, 7 times in this run:
  `:seon.turn.loop/test-error … :seon.error/offending seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion … :seon.test/selection-refusal :seon.test/identity-unresolved, "The requested test is not eligible."`.
  It shows on `retire-branch!` and `in-ns` too. That is noise beside every value, and a stale test reference on
  main.
- **A returned refusal travels as a value into the next call.**
  - `seon.cluster.agent/acquire-context!` with the operator instance answered an error map
    ("Cannot call seon.cluster.agent/acquire-context!: :seon.agent/context-source is unavailable").
  - That map went to `seon.turn/run-proofs` as `execution`, which refused it with 9 problems, each repeating the
    whole offending map: ~15k characters, about 4k tokens, for one missing key.
- **An unknown pull attribute prints whole stack frames.** `seon.db/pull` with `:seon.eval/outcome` (no such
  attribute) answered each of 10 entities with a 46-frame chain, about 6k tokens. One refusal naming the
  attribute would do.
- **The plan's grading form reads `:seon.turn/agent` and `:seon.ai.usage/prompt-tokens` on `:seon.turn/attempts`.**
  The branch holds no `:seon.ai.usage/prompt-tokens` datom at all (`[?e :seon.ai.usage/prompt-tokens _]` is empty
  over the inherited history), so the form cannot measure a run as written. Check it against a run that made calls
  before S5 run 2.
- **`seon.turn/next-agent-work` took 1.44 s and 2.5 GB** on the chain branch (one JVM-mode call). This is the
  per-agent derivation every turn pass runs: over the one-second rule.
- **`my.test/check` of 8 named tests took 17.8 s and 1.09 GB**, with JVM-window allocation of 35 GB.

## Errors by signature

| Signature / type | Where | Count |
|---|---|---|
| `:seon.task/refused :revision-conflict` (`seon.task/tasks-tx`) | first `chain!` | 1 |
| `:seon.test/identity-unresolved` on `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion` | every root SCI test step on main | 7 |
| `:seon.instrument/contract-error` `seon.db/pull` nil entity | §3 watch form | 1 |
| `:seon.call-preparation/unavailable` `:seon.agent/context-source` | root's `acquire-context!` | 1 |
| open faults (both before the run: `seon.sci.kernel/acquire-arm` memory pressure 08:10, `seon.turn/write-refusal-error` 08:37) | main | 2 |

## Final merge outcome

None. The chain never left Design.

## Residue on default (root decides)

- Chain fact `{:seon.chain/first "e80fbb64f0e0" :seon.chain/branch :chain-e80fbb64f0e0}` on main.
  - Branch kept; Design `:running` with attempt `35a165b24683` open (no end); Prove and Implement `:held`.
  - The agent graph is idle and takes no turn (`next-agent-work` nil).
  - To abandon (§3 step 6): `my.root/close!` the attempt with its reason, `my.agent/retire-branch!` on
    `:chain-e80fbb64f0e0`, retract the chain fact.
  - To rerun after D1 is fixed: the same `chain!` form answers this chain unchanged (idempotent), so retire it first
    or start a new first name.
- Root's main program: `seon.prove-prototype-test/s5-check` and `s5-members` were defined and then unmapped
  (history holds them). On main, root's recorded test run `100ebcd52ded` holds the input-less greens of
  `seon.step.design-test/handoff` and `seon.step.prove-test/handoff`. Any new chain inherits them until D1 is fixed.
- Branches `:chain-f1f6dc8e6652` and `:agent-0e7bebc71e2b` retired.
- JVM `user` namespace: `s5-watch`, `s5-agent`, `s5-evals`, `s5-trunc` (host helpers, not program).
- Config: restored to no-provider `true`, `max-episode-runs` 100, `relaunches` 2, verified with `seon.config/effective`.

## To file (this lane commits docs only)

- `seon.context/a-template-proof-green-without-task-input-proves-every-chain-task` (D1, owner
  `seon.context/current-results` / the reach digest of task-reading tests).
- `seon.turn/a-task-done-by-reused-results-never-ends` (D2, owner `seon.turn/next-agent-work`).
- `seon.task/chain-refuses-a-filed-tasks-identity` (Broken 2, owner `seon.task/chain!`, S3f-1).
- MCP: an agent id that lives only on a chain branch is minted anew on a fresh branch (Broken 3).
- The test-step identity-unresolved noise on every root eval, `seon.db/pull` stack-frame verbosity, the returned
  refusal passed as a value, and the `next-agent-work` cost.
- S5 plan §4: drop the template-proof green-on-main row, or run it on a disposable branch, until D1 is fixed.
