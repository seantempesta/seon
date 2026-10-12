---
title: "Hybrid: step semantics and the step lifecycle"
type: research
status: hybrid of plan-step-semantics-opus.md (63c54b9e0) and plan-step-semantics-sol.md (29a576595) over dig-step-semantics.md (fe9f5d295), chain-experiment-1.md (19bfdc087, F1–F5, its proofs patch) and verify-astra.md #6/#8/#9, aligned with hybrid-push.md (root's db<->flow is the one pusher and reactor) under AGENTS.md 2026-10-12 (agents are compartments; agents transact facts, root reacts; no guards or fences); design only, phases filed as tasks
basis: HEAD 29a576595 read by symbol (`seon.test.runner/latest-results`, `seon.test/change-reds`, `seon.turn/reached-red`, `record-reached-red!`, `test-step`, `task-end`, `end-task!`, `next-agent-work`, `first-proof-run-t`; `seon.context/task-done?`, `proofs`, `all-green?`; `seon.cluster.agent/plan`, `plan-facts`, `apply-plan!`, `write-attempt!`, `fork-attempt!`, `follow!`, `write-problem!`, `land-ends!`, `agents-to-start`, `db<->flow`; `seon.cluster.host/start!`; `seon.task/frontier`, `task-edges`); probes on default (archive 2319152dc, pid 94708) below
---

# Step semantics and the step lifecycle

## 1. The design in one paragraph

A step is a task; what kind of step it is, and therefore what makes it done, is one fact on the task. One owner selects
a step's obligations from that fact, and one pure rule judges them against the step's **fork point** (its predecessor's
end commit, recorded on the attempt as `:seon.task.attempt/fork`), never against main. The agent judges itself inside
its own compartment (its branch, its SCI fork, a fork of its environment for tests) and writes the verdict as facts on
its branch: the tests it broke, its end. Root's `db<->flow`, woken by the branch's listen route, does everything that
touches main or another agent: pushes those facts (hybrid-push.md), stops the graph, starts the successor from the
recorded commit, opens root's merge task or its repair problem, and relaunches only by a fact root writes while
working that problem. Nothing retries, rewrites an end or starts a graph except `plan` over attempts of `:running`
tasks. A new red test is a Prove step's product, never breakage; "broken" is a test that existed at the fork point,
was not red there and is red at the head. Both plans arrived at this shape independently; the differences below are
settled by the smaller mechanism that the existing data already supports.

## 2. Point by point

| Point | Opus | Sol | Resolution |
|---|---|---|---|
| Step kind as data | `:seon.task/stage` ∈ `#{:prove :implement}`; ordinary task = `:implement` | `:seon.task/step-kind` ∈ `#{:work :design-prove :implement :merge}`, existing tasks acquire `:work` | **Opus's two values, named `:seon.task/step`.** vocabulary.md retires "stage (of one task)" for "step", so the attribute is the step's kind: `:seon.task/step` ∈ `#{:prove :implement}`, absent = `:implement` (accretion: no migration, one reader). Sol's `:merge` is root's merge task (`:seon.task/kind :merge`, not judged by this rule); `:work` and `:implement` share one rule, so they are one value. `:design` only when a pipeline splits Design from Prove (new enum value, own rule). |
| One obligation selector | `seon.task/obligations` (db, task) → `{:step k :proofs own tagged deftests :product deftests tagged with the tasks that need this one}` | `seon.task/step-obligations` extending `task-edges`, plus a declared proof-product specification (cases, expected failures, digests) as components | **Opus.** The product set is already data: the deftests tagged with a task whose `:seon.task/needs` names this step, on the step's own branch (`task-edges` reads tags by symbol, so a branch holding no task row still reads them). Sol's proof-product entity adds a second declaration of what `:seon/tasks` and `needs` already say; TASK-CHAIN §8.2 ruled no extra verdict tier fixes proof relevance (Implement's send-back and root's review do). `seon.turn/task-proofs` and the duplicate selection in `seon.context/task-done?` go. |
| Done rule per kind | `seon.context/step-status`, pure over one observation: `broken`, `new-red`; `:prove` done when `new-red` = `:product`, red with `:fail` reports or the declared stub error | same classification "at the test producer"; expected failures must equal declared cases; `(is false)` cannot complete | **Opus's rule, with Sol's one sharpening that costs nothing:** a product test red by an `:error` whose type is not the stub's (`:seon.error/unimplemented`, declared in P2; today no stub error type exists) is not done: it names a compile or reference failure, not a designed red. Exact case equality needs the proof-product entity and is not adopted. |
| Baseline for "broken" | fork point, in the agent's compartment; inherited evidence by reach digest, else one run in the fork commit's program context recorded on the agent's branch | fork program, local test fork; "never let an agent run tests on main" | **Agreed**, with the cost cut both imply: only the reaching tests red at the head need a baseline (a green test broke nothing), so the fork-point read or run is proportional to the reds, not the reach. `seon.test/change-reds`' run on main in main's custody (`db/call-with-custody` on `(root-execution cluster)`, verified at `seon.turn/record-reached-red!`) is the compartment violation; it goes. |
| F1 | delete the `:seon.test.run/branch` clause of `latest-results`; a db value holds only its lineage's runs; reach digest decides validity | keep "branch-native admission"; pass the origin as explicit input and read it | **Opus, verified.** Reproduced on default (scratch agent `hybrid-steps-probe`): after one check, `latest-results` on its branch answers `[[map-and-set-order-never-changes-an-identity 1 0]]` and, with only `[:config :branch]` renamed on the same value, `[]`. Runs record `:seon.test.run/branch` = the tested value's branch (`seon.test.runner` run row), `seon.test.run` is `:seon.data` (never pushed), and a Datahike branch value holds exactly its lineage's commits, so "latest in this value" is correct without the clause and `proof-status` already compares the reach digest. Sol's explicit origin input is the same read with a second parameter; not needed. **Safety found in the probe:** `seon.turn/first-proof-run-t` and `proof-run-recorded?` also filter by branch and mean "this attempt proved first"; with inherited runs readable they would count a predecessor's run and skip prove-first, so P1 keys them by the attempt (runs after the attempt row's `t` on the branch), not by branch. `seon.test/changed-since-green`'s clause is the same class and goes with P1. |
| Validity (F3) | `plan-facts` reads `obligations` on the basis commit's value, cached by commit id | resolve the exact predecessor end commits, read via `commit-as-db`, reuse the held value for assembly | **Agreed.** The basis is `:seon.flow.plan/basis` (done needs' end commits); joins are the octopus fork (hybrid-immutable-env E6, `write-attempt!` still takes `(first (sort fork-point))`: E6's deletion). The problem task's intent becomes the failed check as data (`write-problem!`'s "names no proof" string goes). |
| Context (F4) | proofs, `broken`, product with failure data; status from `step-status` | kind, fork basis, obligations, expected reds, broken tests, one projection | **Both say the same.** `current-state` and `my.task/current` show the obligations and the broken tests from the one observation `step-status` judged, each with status and failure data (`proof-failures` already exists), and `:seon.task/status` comes from `step-status`; `all-green?` as a status source goes. |
| Ends and relaunch | ends are the attempt's own facts; `:error` end; repair problem by identity `[class attempt]`; relaunch = `:seon.task.attempt/relaunch` root writes, attempt n+1 from the same fork point | same; relaunch as a separate request entity (task, previous attempt, reason, chosen fork commit, revision); root may keep the failed attempt's program | **Opus's fact on the ended attempt** (one indexed fact, idempotent: the row exists) carrying the reason; attempt n+1 forks the same fork point. Sol's "repair may keep the failed attempt's program" is owner question 2. |
| Lifecycle table and starter | one table; `plan` over attempts of `:running` tasks is the one starter; `:held` = no attempt, no model turn | same; roster is an observation, ended attempts outrank intents | **Agreed (§4).** Stop finishes the pass (landed at HEAD: `follow!` passes `:seon.agent/finish? true`); cancel interrupts. |
| Catches | delete the four; action failure = the attempt's `:error` end | same, fold into one assembly boundary | **Agreed; split by owner.** hybrid-push U3 deletes `land-ends!`, its catch, `unlanded-ends`' and `detached!`; this design deletes `apply-plan!`'s per-action catch and `db<->flow`'s start catch with `agents-to-start` (P7, P10). |
| Outside (MCP) agents | an ordinary attempt whose graph has no model proc | hosting builds an execution, not a background model graph; a graph only from root's plan | **Agreed on the law** (`seon.cluster.host/start!` stops starting graphs); owner question 1 decides what an MCP-held attempt's graph is. |

## 3. Data, selection and the one rule

- **`:seon.task/step`** `[:enum :prove :implement]` on `:seon.task/task` (schema `seon.task.edn`, vocabulary row under
  "subject, step"); the creator writes `:prove` on a Design+Prove step. **`:seon.task.attempt/end`** gains `:error`.
  **`:seon.task.attempt/relaunch`** `[:string {:min 1}]`: root's reason, written on an ended attempt. **`:seon.error/unimplemented`**:
  the variant Design's contracted stubs fail with (TASK-CHAIN §3 item 1 `(fail! …)`), so a product test's `:error` names a designed red.
- **`seon.task/obligations`** (database, task-sym) → `{:seon.task/step k :seon.task/proofs #{…} :seon.task/product #{…}}`:
  two indexed reads over `:seon.task/tasks` (own tags; tags of the tasks whose `needs` name this one). Its callers:
  `assign!`'s refusal, the test step's run set, `current-state`, `task-end`, `plan-facts`' validity.
- **`seon.context/step-status`** (observation) → status, pure: given `{:step :proofs :product :head results :fork-point results}`
  (results = latest admitted result per test keyed by reach digest), `broken` = present at the fork point, not red there,
  red at the head; `new-red` = red at the head, absent at the fork point.

| Step | Done (`step-status` = `:done`) | Where | Merge request |
|---|---|---|---|
| `:implement` | `:proofs` nonempty, all green at the head; `broken` = ∅; `new-red` ⊆ `:proofs` (so none) | the agent's test step on its branch; verdict written on its branch (`:seon.agent/broken`, the end) | when no task `needs` it: root's merge task |
| `:prove` | `:proofs` (optional) all green; `:product` nonempty; `new-red` = `:product`, each red by `:fail` or an `:error` of type `:seon.error/unimplemented`; `broken` = ∅ | same | never: its program reaches its successors by fork |
| both | `:sent-back` carries a target and counterexample (`my.task/send-back!`) and is never green | the agent's pass | none |
| validity | `obligations` nonempty at the fork point (`:proofs` for `:implement`, `:product` for `:prove`) | root's `plan-facts` on the basis commit's value | — |

**Cost.** `obligations`: two AVET reads. `step-status`: set arithmetic over results the step already read (Opus: six rows
in 5 ms). Baseline: one inherited-evidence read per reaching test red at the head, and a run at the fork point only for
one with no evidence there. Today's judgment runs tests on main inside every pass (Opus: a two-test check 1082 ms, 650 MB
in the step; this probe's one-test check 845 ms, 1.8 GB JVM window: `my.test/a-two-test-check-allocates-a-gigabyte-and-a-half`).

## 4. The lifecycle: one intent table, one starter

Intent `:seon.task/execution` on the task, cluster `:seon.cluster/execution` (R3; paused anywhere wins). Every row is
derived by `plan` from main (after push) + roster, idempotent by identity; an action and its failure fact are one unit.

| Facts on main | Roster | `db<->flow` (root's world) | Triggering fact written by |
|---|---|---|---|
| task `:held` | — | nothing: no attempt, no model turn even with wakes | root or the creator |
| `:running`, needs done, valid at the fork point, no open attempt, no ended attempt of a terminal class without a relaunch | — | `:attempt` (fork point recorded: the done needs' end commits, else main's head) and `:start-flow` as one unit; a failure writes that attempt's `:error` end with its reason | root |
| open attempt, `:running` | no graph | `:start-flow` (restart = launch) | — |
| `:paused`, or cluster `:paused` | graph | `flow/pause`, acknowledged by `flow/ping-proc` under the bound | root |
| `:stopped` | graph | stop after the current pass (`:seon.agent/finish? true`), join, release the SCI context; attempt open, resumable by `:running` | root |
| `:cancelled` | graph | interrupt; end `:cancelled` on main (root's own act); `:delete-branch` derived; no problem task | root |
| end `:done` | graph | `:stop` derived; successors ready; the agent-written merge request becomes root's merge task; branch deleted once pushed, no successor waits, no open request (hybrid-push §4) | agent |
| end `:sent-back` → T | graph | `:stop`; T is not done (`leaf-done`), so T gets attempt n+1 with the counterexample in its context; the sender waits on T again | agent |
| end `:capped` `:stalled` `:given-back` `:error` | graph | `:stop`; repair problem task by identity `[class attempt]` assigned to root, carrying the end and reason; branch kept; the task leaves `ready` | agent (root for a start `:error`) |
| that attempt carries `:seon.task.attempt/relaunch` | — | the task is ready again: attempt n+1 from the same fork point | root, working the repair problem (`my.root/relaunch!`; may `refine!` first) |
| `:sent-back` to the same target twice | — | arbitrate problem (runtime-follows-db §5) | — |

No end is rewritten; a relaunch answers it. The repair problem's proof: its attempt carries a relaunch or its task is
`:held` or `:cancelled`. `seon.task/frontier`'s exclusion `#{:given-back :capped :stalled :cancelled}` becomes "an ended
attempt of a terminal class with no relaunch" (one `not`) and gains `:error`.

**Deleted, by symbol.** `seon.turn/task-proofs`, `reached-red`, `record-reached-red!` (→ `record-broken!` over
`step-status`), `root-execution`'s use in the test step, the branch filters in `first-proof-run-t`/`proof-run-recorded?`;
`seon.test/change-reds`' main acquisition, run and recording; `seon.test/changed-since-green`'s branch clause;
`seon.test.runner/latest-results`' `:seon.test.run/branch` clause; `seon.context/all-green?` as a status source and
`task-done?`'s own selection; `seon.cluster.agent/agents-to-start` and `db<->flow`'s start loop and catch (after
runtime-follows-db §6 moves schedules to recurring tasks); `apply-plan!`'s per-action catch; `seon.cluster.host/start!`'s
`start-branch-flow!` call; the held arm of `seon.turn/next-agent-work` and `attempt-running?` as a branch condition;
`write-problem!`'s hardcoded intent; `write-attempt!`'s `(first (sort fork-point))` (E6's octopus). `land-ends!`,
`unlanded-ends`, `detached!` and their catches are hybrid-push U3's.

## 5. Phases (about fifteen minutes each; red proof first; filed as tasks; `chain-experiment-1-proofs.patch` reused)

| # | Slice (owned files) | Red proof | Task |
|---|---|---|---|
| P1 | F1: delete the branch clause in `latest-results` and `changed-since-green`; key `first-proof-run-t`/`proof-run-recorded?` by the attempt (`src/seon/test/runner.clj`, `src/seon/test.clj`, `src/seon/turn.clj`, `test/seon/test/runner_test.clj`, `test/seon/turn_test.clj`) | `seon.test.runner-test/a-forked-value-reads-the-results-it-inherited` (this probe as a test); `seon.turn-test/a-predecessors-proof-run-does-not-count-as-the-attempts` | `seon.test.runner/a-forked-value-reads-the-results-it-inherited`; tags `seon.context/a-step-proof-cannot-observe-its-agents-test-evidence` |
| P2 | schema and words: `:seon.task/step`, `:error` end, `:seon.task.attempt/relaunch`, `:seon.error/unimplemented`, vocabulary rows; `seon.task/obligations` and its callers (`resources/seon/schemas/{seon.task,seon.task.attempt,seon.error}.edn`, `docs/seon/architecture/vocabulary.md`, `src/seon/task.clj`, `test/seon/task_test.clj`); platform `--test` | `seon.task-test/obligations-select-by-step` (implement; prove with dependents; prove with own proofs; absent step = implement) | `seon.task/a-step-selects-its-obligations-by-its-kind` |
| P3 | `step-status`, `record-broken!` with the fork-point baseline in the agent's compartment; delete the main run (`src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/test.clj`, `test/seon/context_test.clj`, `test/seon/turn_test.clj`, `test/seon/cluster/db_flow_test.clj`) | `seon.context-test/each-step-kind-is-done-by-its-one-rule` (table); `seon.turn-test/the-test-step-writes-nothing-on-main`; the patch's `a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red` with p declared `:prove` | `seon.context/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red`, `seon.turn/the-test-step-writes-nothing-on-main` |
| P4 | F4: context shows obligations and broken tests with failures; status from `step-status` (`src/seon/context.clj`, `src/my/task.clj`, `test/seon/context_test.clj`) | `seon.context-test/broken-tests-show-with-their-failures` (status and `task-end` agree) | `seon.context/reached-reds-are-invisible-to-the-agent` |
| P5 | F3: `plan-facts` validity through `obligations` on the basis commit's value; problem intent as data (`src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj`) | the patch's `a-successor-whose-proof-its-predecessor-wrote-starts` | `seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts` |
| P6 | merge request only from a step no task needs (`src/seon/turn.clj` `end-task!`, `test/seon/cluster/db_flow_test.clj`) | rows in P3's test: p's `:done` records no merge request; i's does | `seon.turn/a-merge-request-comes-only-from-a-step-no-task-needs` |
| P7 | #6: a failed `:attempt`/`:start-flow` (one unit) writes the attempt's `:error` end; delete `apply-plan!`'s catch (`src/seon/cluster/agent.clj`, `test/seon/cluster/db_flow_test.clj`) | `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` | `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` |
| P8 | #9: cancel interrupts, ends `:cancelled`, deletes the branch, no problem task; pause acknowledged (`src/seon/cluster/agent.clj` with hybrid-push U3's derived lifecycle) | R2 table `seon.cluster.agent-test/each-intent-names-one-roster-state` | existing `seon.cluster.agent/each-intent-names-one-roster-state` |
| P9 | F5: repair problem per class by identity; `:seon.task.attempt/relaunch` + `my.root/relaunch!`; the `ready` rule (`src/seon/task.clj`, `src/seon/cluster/agent.clj`, `src/my/root.clj`, `test/seon/cluster/db_flow_test.clj`) | `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` (capped → problem, no attempt 2; relaunch → attempt 2 from attempt 1's fork point) | `seon.cluster.agent/a-capped-attempt-has-no-relaunch`; rows of `seon.task/each-problem-class-is-detected-from-its-facts` |
| P10 | #8: one starter; delete `agents-to-start`, the start catch, `host/start!`'s graph start, the held arm; fixtures converted (`src/seon/cluster/agent.clj`, `src/seon/cluster/host.clj`, `src/seon/turn.clj`, tests); after owner question 1 and runtime-follows-db §6 | `seon.turn-test/a-held-task-opens-no-model-turn`; P8's table | `seon.turn/a-held-task-opens-no-model-turn` |
| P-final | chain experiment 2: same subject and instructions plus Prove's "run the tests reaching the subject against the designed shape"; zero nudges; restart default on that HEAD first | the chain test extended to Design+Prove (expected red product) → Implement → one root merge request, no hand end edits; then the paid run (about 5 + 4 calls) with a merge request in root's queue | `seon.cluster.agent/the-real-chain-runs-with-no-nudges` |

**Order.** P1 → P2 → P3 → P4 (P3/P4 share `context.clj`: one lane) → P5, P6 → P7, P8 (with hybrid-push U3) → P9 →
P10 → P-final. P1–P6 alone unblock the chain (F1–F4); P7–P10 remove the nudges' cause. P1 and P3 are Opus lanes (the test
step is on the one path); P2 is a platform slice; P4–P9 Sonnet on this spec. Collisions: `src/seon/cluster/agent.clj`,
`src/seon/task.clj` and `test/seon/cluster/db_flow_test.clj` are also hybrid-push U2/U3/U5/U6's; root sequences them.
The patch's deftests land with P3 and P5 (its task entries land here; `seon.turn/a-form-without-source-is-shown-as-a-fault` too).

## 6. Owner questions (the recommended answer is used unless overruled)

1. **An outside (MCP) agent's graph.** Recommended: an MCP-held task is an ordinary attempt (root assigns it) whose graph
   has the mailbox and test procs and no model proc (`graph-definition` by the agent's record, `:seon.agent/settings`
   naming no model); the eval request is its pass. Alternative: no graph; messages wait as data on main (hybrid-push U5)
   and are read at the next eval request's context. P10 waits for this.
2. **Relaunch fork point.** Recommended: the same fork point (a fresh start; tracked refinements are already pushed).
   Alternative (Sol): root names the failed attempt's end commit to keep its partial program, risking its failure's cause.
3. **Stub error type.** Recommended: declare `:seon.error/unimplemented` in P2 and make Design's stubs `fail!` with it;
   until then P3 accepts `:fail` reports only.
4. **Design as its own step kind.** Recommended: not now; Design's checks are own proofs on a `:prove` step.
5. **`:given-back` routing.** Recommended: a repair problem for root (a given-back names no target); a send-back with a
   target routes to that target.

## 7. Probes (default, archive 2319152dc; scratch agent `hybrid-steps-probe`, disposable)

- main (`:cluster-default`) holds 0 test runs today, so F1 was reproduced on the scratch branch: `my.test/check` of one
  `seon.id-test` deftest 845 ms (the F10 class); then `latest-results` on the branch `[[sym 1 0]]`, on the same value with
  `[:config :branch]` renamed `[]`; reach digests equal on both. Request 959 ms, 181 MB.
- Read by symbol: `follow!` already passes `:seon.agent/finish? true` for `:stop` (b7cc27520) and still writes the
  `:cancelled` end on main with no branch deletion; `fork-attempt!` has no main fallback (E6 landed) but `write-attempt!`
  still takes the first sorted commit of a join; `change-reds` runs and records on main in main's custody from the
  agent's test step; `write-problem!` hardcodes "names no proof"; `agents-to-start` and `host/start!`'s graph start exist.
