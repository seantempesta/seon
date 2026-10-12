---
title: "Step semantics and the step lifecycle (Opus plan, design round 5)"
type: plan
status: proposed (lane plan-steps-opus, 2026-10-12, HEAD 206e733a2); independent of Sol's plan, for Fable's hybrid
created: 2026-10-12
---

# Step semantics and the step lifecycle

Authorities: AGENTS.md (no guards or fences; agents are compartments and transact facts, root reacts; derive work and
completion), [dig](dig-step-semantics.md), [chain experiment 1](chain-experiment-1.md) F1-F5, [verify-astra](verify-astra.md)
#6/#8/#9, [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) §3b and §8.1-8.2,
[runtime-follows-db](../2026-10-11/runtime-follows-db.md) §3/§5, [R1](../2026-10-11/r1-db-flow-design.md),
[hybrid-agent-world](../2026-10-11/hybrid-agent-world.md), [plan-push-opus](plan-push-opus.md) (no `hybrid-push.md` exists yet).

## 1. Design

**What exists.** A step is a task; its agent's pass (`seon.turn/step`) runs `test-step` (proofs, then reaching tests,
then `record-reached-red!`, which runs and records tests **on main** through `seon.test/change-reds`), then `end-task!`
writes the end on its branch; `db<->flow` on main plans attempts (`plan-facts` + `plan`), lands ends (`land-ends!`) and
starts graphs, beside two other starters (`agents-to-start`, `seon.cluster.host/start!`). Completion is one predicate,
`seon.context/task-done?` = own proofs green and `:seon.agent/reached-red` empty; validity is `task-edges` on main.

**The design.** One owner selects a step's obligations and one pure function judges them; both read **the step's fork
point** (its predecessor's end commit, recorded on the attempt), never main; the agent judges itself inside its own
compartment and writes the verdict as facts on its branch; root's `db<->flow`, woken by the branch's listen route,
does everything that touches main or another agent. Concretely:

1. **The step kind is data**: `:seon.task/stage` ∈ `#{:prove :implement}` on the task (new enum attribute, accretion;
   recorded in vocabulary first). An ordinary task is `:implement`; the creator writes `:prove` on a Design+Prove step.
   Not `:seon.task/kind` (that name is retiring and means something else).
2. **One obligation selector**, `seon.task/obligations` (database, task) → `{:stage k :proofs #{own tagged deftests}
   :product #{deftests tagged with the tasks that :needs this one}}`. It is the only reader of proofs for a step:
   `assign!`'s refusals, `test-step`'s run set, `current-state`, `task-end` and `plan-facts`' validity all call it
   (Astra §8.2: "select once in the task owner and share"). `seon.turn/task-proofs` goes.
3. **One done rule per kind**, `seon.context/step-status` (pure over an observation): `broken` = tests that exist at
   the fork point, were not red there, and are red at the head; `new-red` = red at the head and absent at the fork
   point. A new test is never breakage: that removes F2 by definition, with no exemption for "the successor's proof".
4. **The baseline is the fork point, in the agent's compartment.** Evidence is keyed by reach digest, so a test whose
   reach digest is unchanged since the fork point cannot have been broken by the change; only tests reaching the
   change need a fork-point result, read from the evidence the branch inherited at that commit, else run once in that
   commit's program context (hybrid-immutable-env's per-commit value) and recorded **on the agent's branch** under the
   fork-point digest. `change-reds`' run on main and `root-execution` in the test step go: the agent no longer writes
   main (today it does, in `change-reds`' `call-with-custody` on main).
5. **F1: evidence is content-valid across branches.** `seon.test.runner/latest-results` filters runs by
   `:seon.test.run/branch` = the reading database's branch. Measured on default: the same branch db value reads
   `[[green-one 0 1] [red-one 1 0]]`, and with only `[:config :branch]` renamed (a member forked from it) reads `[]`.
   The branch name is a stamp beside a key that already decides validity (`proof-status` compares the reach digest),
   so the filter is deleted; a db value only holds its own lineage's runs, so "latest in this value" is correct.
   A test member forked from the agent's branch then reads exactly what the agent reads.
6. **The context shows what decides the end**: the proofs, the `broken` tests and (for `:prove`) the product, each with
   status and failure data from the same observation `step-status` judged; `:seon.task/status` comes from
   `step-status`, not `all-green?` (today `current-state` says `:done` while `task-done?` is false: F4's loop).
7. **Ends are the attempt's own facts; root reacts.** Every end, the error end included, is a fact on the attempt.
   The agent writes `:done :sent-back :given-back :capped :stalled :error` on its branch (its pass, or its graph's
   entrant boundary); root writes only ends of its own acts (`:cancelled`, a failed start's `:error`) on main.
   The push carries branch ends to main; `plan` derives every reaction from main + roster. Relaunch is an explicit
   fact root writes; nothing retries by itself.
8. **One starter**: `plan` over attempts of `:running` tasks. `:held` means no attempt and no model turn, ever.

| Step kind | Done when (`step-status`) | Where checked | Merge request |
|---|---|---|---|
| `:implement` | `:proofs` nonempty and all green at the head; `broken` = ∅; every `new-red` is an own proof (none left red) | the agent's test step on its branch, baseline at the attempt's fork point; verdict written on its branch (`:seon.agent/broken`, the end) | when no task `:needs` it (the chain's last step); root works it |
| `:prove` | own `:proofs` (optional) all green; `:product` nonempty; `new-red` = `:product` (each product test red with `:fail` reports or the declared stub error); `broken` = ∅ | same place and baseline | never (it has dependents; its program reaches them by fork) |
| both | a `:sent-back` end needs a target and counterexample (`my.task/send-back!`) and is never green | the agent's pass | none |
| validity (plan) | `obligations` nonempty **at the fork point** (`:proofs` for `:implement`; dependents for `:prove`) | root's `plan-facts`, reading the basis commit's value (`:seon.flow.plan/basis`), cached by commit id | — |

Cost: `obligations` is two indexed reads (tags of the task and of its dependents); `step-status` is set arithmetic over
results the step already read (prototype: six table rows in 5 ms); the fork-point baseline is one evidence read per
reaching test, plus a run only for a reaching test with no fork-point result. Today the same judgment runs tests on
main inside every pass (measured on default: a two-test check took 1082 ms, of which the test step was 660 ms and
650 MB; a finding under existing task `my.test/a-two-test-check-allocates-a-gigabyte-and-a-half`). Same probe: defining
a brand-new red test reported it in `:seon.turn.loop/reached-red`, the F2 class reproduced on a fresh store.

## 2. The lifecycle: one intent table, one starter (`db<->flow`)

Intent `:seon.task/execution` on the task, cluster `:seon.cluster/execution` (R3; paused anywhere wins). Every row is
derived from main (after push) + roster in `plan`, idempotent by identity; each action and its failure fact are one unit.

| Facts on main | Roster | `db<->flow` action (root's world) | Who wrote the triggering fact |
|---|---|---|---|
| task `:held` | — | nothing; no attempt, no model turn even with wakes | root / creator |
| `:running`, ready (needs done), valid at fork point, no open attempt, no blocking end | — | `:attempt` (fork point = done needs' end commits, else main's head, recorded) then `:start-flow`; a failure writes that attempt's `:error` end with its reason, so the next pass plans the rest (#6) | root |
| open attempt, `:running` | no graph | `:start-flow` (restart = launch) | — |
| `:paused` / cluster `:paused` | graph | `flow/pause`, acknowledged by `flow/ping-proc` under the bound | root |
| `:stopped` | graph | stop **after the current pass** (`:seon.agent/finish? true`), join, release the SCI context; attempt stays open, resumable by `:running` | root |
| `:cancelled` | graph | interrupt, write end `:cancelled` on main, delete the branch; no problem task | root |
| end `:done` | graph | stop (derived); successors become ready; merge request (agent-written) becomes root's merge task; branch deleted once pushed, no successor waits, no open request | agent |
| end `:sent-back` → target T | graph | stop; T is not done (`leaf-done`'s existing negation), so T gets attempt n+1 with the counterexample in context; the sender waits on T again | agent |
| end `:capped` `:stalled` `:given-back` `:error` | graph | stop; repair problem task by identity `[class attempt]`, assigned to root; branch kept; the task leaves the frontier | agent (or root for a start `:error`) |
| that attempt carries `:seon.task.attempt/relaunch` | — | the task is ready again: attempt n+1 from **the same fork point** | **root**, working the repair problem (`my.root/relaunch!` attempt reason; may `refine!` first) |
| send-back to the same target twice | — | arbitrate problem (runtime-follows-db §5) | — |

Stop finishes the pass; cancel interrupts. No end is rewritten; a relaunch never edits an end, it answers it. The
repair problem's proof is "its attempt carries a relaunch, or its task is `:held`/`:cancelled`", so working it closes it.

## 3. Deletions, by symbol

- `seon.turn/task-proofs`, `seon.turn/reached-red`, `seon.turn/record-reached-red!` (→ `record-broken!` over
  `step-status`), `seon.turn/root-execution`'s use in the test step; `seon.test/change-reds`' main run and recording
  in main's custody (its comparison moves to the fork-point baseline); `seon.context/all-green?` as a status source.
- `seon.test.runner/latest-results`' `:seon.test.run/branch` clause (F1).
- `seon.cluster.agent/agents-to-start` and `db<->flow`'s derive-all start loop (after schedules become recurring
  tasks, runtime-follows-db §6); `seon.cluster.host/start!`'s `start-branch-flow!` call; the held-task arm of
  `seon.turn/next-agent-work` ("a :held task root assigned by hand waits for its wakes") and `attempt-running?` as a
  branch condition (every model turn is an attempt's).
- The four catches: `apply-plan!`'s per-action catch, `db<->flow`'s start catch and `unlanded-ends` catch,
  `land-ends!`'s per-end catch (#6), the last two with the push plan's replacement of `land-ends!`.
- `follow!`'s `:cancel` writing an end that `land-ends!` then treats as an error end (#9); `write-problem!`'s hardcoded
  "names no proof" intent (the failed check becomes data on the problem).
- `seon.task/frontier`'s permanent exclusion `#{:given-back :capped :stalled :cancelled}` becomes "an ended attempt
  of that class without a relaunch" (one `not` in the rule), and gains `:error`.

## 4. Phases (about fifteen minutes each, red proof first; reuse `chain-experiment-1-proofs.patch`)

| # | Change (owner files) | Red proof |
|---|---|---|
| P1 | F1: delete the branch clause in `seon.test.runner/latest-results` (runner.clj) | new `seon.test.runner-test/a-forked-branch-reads-the-results-it-inherited` (the probe in §1.5 as a test) and `seon.context/a-step-proof-cannot-observe-its-agents-test-evidence` tagged on it |
| P2 | `:seon.task/stage` schema + vocabulary row; `seon.task/obligations`; `assign!`, `test-step`, `current-state`, `task-end` call it (task.clj, turn.clj, context.clj, seon.task.edn) | `seon.task-test/obligations-select-by-stage` (table: implement, prove with dependents, prove with own proofs) |
| P3 | `seon.context/step-status` + `record-broken!` with the fork-point baseline in the agent's compartment; delete the main run (turn.clj, test.clj, context.clj) | `seon.context-test/each-stage-is-done-by-its-one-rule` (the six prototype rows); `seon.turn-test/a-new-red-test-is-not-broken`; `seon.turn-test/the-test-step-writes-nothing-on-main`; the patch's `a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red` with p declared `:prove` (chain-world takes a stage) |
| P4 | F4: `current-state` shows proofs, `broken` and product with failure data; status from `step-status` (context.clj) | `seon.context/reached-reds-are-invisible-to-the-agent` tagged on new `seon.context-test/broken-tests-show-with-their-failures` (status and `task-end` agree) |
| P5 | F3: `plan-facts` reads validity through `obligations` on the basis commit's value (agent.clj) | the patch's `a-successor-whose-proof-its-predecessor-wrote-starts` |
| P6 | merge request only from a step no task needs (turn.clj `end-task!`) | row in the P3 patch test: p's `:done` end records no merge request; i's does |
| P7 | #6: action failure = the attempt's `:error` end; `:attempt` + `:start-flow` one unit; delete the catches (agent.clj) | `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` |
| P8 | #9: stop finishes, cancel interrupts + `:cancelled` end + branch delete, pause acknowledged (agent.clj) | `seon.cluster.agent-test/each-intent-names-one-roster-state` (R2 table) |
| P9 | F5: repair problem per class by identity; `:seon.task.attempt/relaunch` + `my.root/relaunch!`; frontier rule (task.clj, agent.clj, my/root.clj) | `seon.cluster.agent/a-capped-attempt-has-no-relaunch` tagged on new `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` (capped → problem, no attempt 2; relaunch → attempt 2 forked from attempt 1's fork point) |
| P10 | #8: one starter; delete `agents-to-start`, `host/start!`'s graph start, the held arm (agent.clj, host.clj, turn.clj; fixtures converted) | `seon.turn-test/a-held-task-opens-no-model-turn` + P8's table; after runtime-follows-db §6 for schedule owners |
| P11 | chain experiment 2: same subject, same instructions plus Prove's "run the tests reaching the subject" rule, zero nudges | merge request in root's queue; expected about 5 + 4 calls |

Order: P1 → P2 → P3/P4 → P5/P6 (needs F's recorded fork point, lane p4-branch-writes) → P7/P8 (with the push plan's
replacement of `land-ends!`) → P9 → P10 → P11. P1-P6 alone unblock the chain (F1-F4); P7-P10 remove the nudges' cause.

## 5. Risks

- **Fork-point baseline cost.** A reaching test never run at the fork point costs one run there; for a join (octopus
  fork) the baseline is the merged fork value. Measure P3 on the chain subject; the bound is the step dial (10 s).
- **Deleting the branch filter** admits evidence inherited at fork. That is the intent (reach digest decides); a test
  whose result depends on data outside its reach (non-deterministic or reading unreached facts) would be reused
  wrongly: that test is the defect, found by its repeat.
- **`:prove`'s "red for the stated reason"** is only as strong as the failure data; a `(is false)` product passes the
  rule. TASK-CHAIN §8.2's catalogue says no extra verdict tier fixes that; Implement's send-back and root's review do.
- **Outside (MCP) agents** lose `host!`'s graph in P10; until decided they keep evaluating through the one path, which
  already runs the test step in the eval request (observed: `:seon.turn.loop/tests` in an MCP reply).
- File collisions: turn.clj, agent.clj, db_flow_test.clj are held by p4-branch-writes / hybrid-env; phases go as patches.

## 6. Unsure (recommended answer first)

1. **Outside agents' graph**: an outside agent's step is an ordinary attempt whose graph has no model proc (MCP drives
   its passes; the mailbox proc still delivers). Alternative: no graph, messages read at the next eval request.
2. **Relaunch fork point**: the same fork point (fresh start; tracked refinements already pushed). Alternative: the
   failed attempt's end commit, keeping its partial program (risks carrying the reason it failed).
3. **Stub error type** for "red for the stated reason": the error type Design's contracted stubs `fail!` with; none is
   declared today, so P3 accepts `:fail` reports and records the gap as a task.
4. **Design as its own stage**: not now; Design's checks are own proofs on a `:prove` step. Add `:design` only when a
   pipeline splits them (accretion: a new enum value with its own rule).
5. Should `:given-back` need root at all, or route like a send-back to the assigner? Recommended: repair problem,
   because a given-back step names no target.
