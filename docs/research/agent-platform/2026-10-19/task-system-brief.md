---
title: "Task system brief for root: what runs today, what the designed task graph needs, and how to run live run 13"
type: research
status: "lane brief-root (Opus), 2026-10-19, read-only on default at 1e975f291 (JVM-mode reads; no SCI request as root; one `bin/seon check --at HEAD` of 16 tests). Every claim is marked verified (with the form or test that showed it) or unverified."
---

# Task system brief for root (2026-10-19)

Root ran live runs 8 to 12 as single-task chains from `seon.step.implement/template`. The designed flow is
design → prove → implement → merge, with every agent improving the plan as it learns. This brief is the operating
manual for that flow:
- what is installed and what is target;
- the exact forms to use;
- what is verified;
- what blocks run 13.

"Installed" means code on HEAD 1e975f291 that default runs (`bin/seon status`: source root
`data/source/1e975f291…`). "Target" means the task model (`docs/research/agent-platform/2026-10-17/task-model.md`).
In the target, there is no chain entity: tasks link only by `:seon.task/needs` Datahike refs (owner, 2026-10-19).

## Broken things first

1. **Every live Prove step is blocked on HEAD: its own proof is red before the agent does anything.** The Prove
   template's proof is `seon.step.prove-test/handoff`. That deftest always runs its fixture table, and only then
   checks the supplied task (TASK-CHAIN §4 (a)/(b)). One table row is red on HEAD: "a proof never run"
   (`test/seon/step/prove_test.clj:64`). Its expected `:unreached` is `[tq.prv/middle tq.prv/outer]`. The actual is
   `[tq.prv/middle tq.prv/other tq.prv/outer tq.prv/producer]`: a never-run proof now reaches nothing through
   `seon.test.runner/tests-reaching`.
   - **Verified** by `bin/seon check --at HEAD` run `359dd87f7965`: 16 tests executed, 15 green, this one red, in
     21 s.
   - Consequence: `seon.cluster.source/done-refusals` refuses a red proof, so no Prove step can be done until the
     row or the runner is fixed. That holds whatever the agent writes.
   - The row's code last changed in 65f4c292e (10-14). The runner's reach code changed since then (795e44a83,
     9e902642f, 51c745cde).
   - **Unfiled; root should file it.** This lane changes no tasks. The owner is `seon.test.runner/tests-reaching`
     or the row: classify by the commit that changed the behavior.
2. **`seon.task/from-filed` composed with `seon.task/chain!` is broken since T1 (c9f2a98a7).** It was the start
   form of S5 run 4.
   - `from-filed` now answers four maps: design, prove, implement and merge. They link by `:seon.task/needs` lookup
     refs to `<name>-prove` and `<name>-implement`, and the merge map carries `:seon.task/target`.
   - `chain!` renames tasks 2 to 4 as `<name>-2`, `<name>-3` and `<name>-4` (`seon.task/chain-entities`). It keeps
     their `:needs`, so the lookup refs name tasks that do not exist.
   - **Verified** in JVM mode by `datahike.api/with` over `seon.task/adopt-tasks-tx` of those entities. It throws
     "Nothing found for entity id [:seon.task/sym …-prove]". `chain-refusal` returns nil first, so `chain!` forks
     the branch before the throw.
   - The merge map would also become a fourth chain task with no proofs. Its template's instructions say "Root's
     final merge of your chain refused", and no done rule can pass it.
   - The working form is in §2.1: the first three maps, without `:needs` and `:target`.
3. **The help every agent reads misdescribes the installed chain.** `resources/seon/help.md` "Who you are" says:
   "When your step is done, everything on your branch merges onto main by itself … Then the step after you starts
   from main." "Your task" says the same about `:seon.context/merged`.
   - Installed: the steps share one chain branch, the next step continues on that branch, and main gets one merge
     when the last task is done (TASK-CHAIN §2.5–2.6).
   - The text dates from the per-step-merge model. A Design or Prove agent reads a false promise about where its
     work goes.
   - Fix at the producer (help.md). Unfiled.
4. **Nothing on the `needs` path can start an agent today** (§0).
5. **No full design → prove → implement chain has ever merged live.** The last multi-step live run was S5 run 4
   (2026-10-14):
   - Design was done in 18 calls and Prove in 28.
   - Implement capped repeatedly in runs 4 to 8, and the final merge was refused (`:file-absent`).
   - Runs 2 to 12 since then were all single-step Implement chains (`live-run-*.md` 10-16 to 10-19).
   - Since 10-14, P4b, P5, P6, P10, T1, T2, T3a and the runtime work have all landed.
6. **No test drives the real Design or Prove template through a chain.** `rg` finds the `seon.step.*/template`
   symbols only in `seon.task/from-filed`, docstrings and `seon.cluster.agent/merge-step`. The multi-task
   db<->flow tests use synthetic proofs (`seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act`
   uses `(is (= 1 1))`).
7. **The grade does not know a task's step.** `my.root/turns-report` pools Design, Prove and Implement attempts into
   one turns-to-green median. A 0-call Prove that is done at its start counts as turns-to-green 0, which reads as an
   improvement (§5).
8. **A merged chain does not delete its filed entry** (TASK-CHAIN S3f-1 second half, open). Run 12's merge
   bd8bd428a wrote only `src/seon/render/var.clj` and its schema. `seon.render.var/a-malformed-contract-renders-as-missing`
   is still in `resources/seon/tasks/seon.render.var.edn:9`. **Verified** by `git show --stat` and `grep`.

## 0. Installed vs target: what runs live today

**Installed (HEAD 1e975f291): live runs still go through `seon.task/chain!`, a chain entity and `:seon.task/next`.**
- `chain!` forks `chain-<handle>`.
- It writes the tasks on that branch, linked by `:seon.task/next`. The first is `:running` and the rest `:held`.
- It writes one chain fact on main: `:seon.chain/branch`, `:seon.chain/first` and the optional `:seon.chain/merge?`.
- Root's listener `seon.cluster.agent/db<->flow` → `run-pass!` reads every chain fact
  (`chain-facts`/`chain-read`), starts attempts, advances (`advance-chains!` → `seon.task/advance-tx`), bails
  (`bail-chains!`), merges (`merge-chains!`) and publishes (`finish-chains!`).
- Main held 11 chain facts at the time of reading (verified: `[:find ?b :where [?c :seon.chain/branch ?b]]`).

**Target (owner, verbatim 2026-10-19: "there is no 'chain' entity: we are doing this just through `:needs` Datahike
refs").** Tasks are desired state on main, linked by `:seon.task/needs`. One pure reconciler,
`seon.cluster.reconcile/reconcile`, closes the gap between desired and observed facts. A merge is an ordinary task
with `:seon.task/target` main.

**Is anything on the `needs` path executable today? No agent can be started through it.**

| Piece | State on HEAD | Evidence |
|---|---|---|
| `seon.task/create-all!` (T1) | **built, not launched.** Writes a vector of task maps on main in one transaction, every task `:held`, branches resolved (`written-branches`: a linear run shares one branch, a targeted task gets `task-<handle>`), `:target`/`:config` stored | green: `seon.task-test/a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task` (check 359dd87f7965); `a-task-write-resolves-every-tasks-branch` not in my run |
| `seon.task/from-filed` (T1) | **built**: four maps linked by needs, merge task targeting main's branch (`:cluster-default`) | verified in JVM mode on `seon.render.ns/the-namespace-form-face-quotes-the-name-dir-reads` |
| `:seon.agent/graph-process` (T2) | **built**: asserted at `start-flow!`, retracted at observed exit | commit cb8274c69 (not re-run here) |
| `seon.cluster.reconcile` (T3a, T3a-agent) | **built, not wired**: `transitions` (21 task rows), `branch-transitions`, `agent-transitions`, `admit`, `reconcile`; nothing in `src` outside the namespace calls it | verified: `rg "seon.cluster.reconcile"` finds no caller; `seon.config` uses a different `reconcile` (`seon.reconcile`, config) |
| `my.root/run!` | **chain-only**: `seon.task/run-held!` finds the task's chain (`chain-branch`) and otherwise refuses "Task … is on no chain." | read: `seon.task/run-held!` |
| the old per-assignment launch on main | **exists, wrong for this**: `launch-facts` → `seon.task/ready` starts a `:running` main task whose every need is `:merged` on main, on its own `attempt-<key>` branch forked from main; it knows no `:target`, and a merge task with no proofs is classed invalid (`basis-valid?`) | read; its three end-to-end tests were never green (task-model §13) and T3b deletes it |

So a `create-all!` graph sits `:held` on main, and the one release verb refuses it. A hand write of `:running` would
send the design task down the per-assignment path. That path merges each step separately and cannot run the merge
task. **Do not do that in a live run.**

### What T3b and later need before a live run starts tasks through `create-all!` and needs

The order is from task-model §11. The status is from the phase-status lines of `task-model.md` and `merge-hybrid.md`
(read 2026-10-19).

| # | Phase | Status | What it supplies | Red proof (named in the plan) |
|---|---|---|---|---|
| 1 | P7 one file proof, consumed thrice | **pending** | `prepare-merge!`'s file proof built once; T3b moves its build | merge-hybrid §P7 |
| 2 | **T3b one path** (Opus, restart batch) | **pending** | `run-pass!` = observe, `reconcile`, apply; `chain!` → `create-all!`; every `next` reader → `needs`; the build rule (continue a need's branch, or fork the target and carry each need's change); the done step (`:done` on the task's branch, or P4a's accept onto main for a targeted task); held semantics; the `max-running` cap through `admit`; `my.root/run!` on main task identity; the bail commits `:held` on main first; `:sent-back` bails; the chain and per-assignment functions deleted (§9) | `seon.cluster.db-flow-test/a-merge-task-with-no-conflict-is-done-at-start-with-no-model-call`, `…/a-conflicting-merge-task-starts-an-agent-on-its-merge-branch`, `…/a-task-with-no-target-is-done-on-green-proofs-and-writes-nothing-to-main`, `…/a-held-task-starts-nothing-until-root-releases-it`, `…/the-scheduler-never-starts-more-than-max-running`, `…/two-independent-tasks-start-in-one-pass`, `…/a-task-needing-two-parallel-tasks-starts-after-both-and-sees-their-work`, `…/a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act`, `…/a-bail-commits-held-on-main-before-closing-the-branch`, `…/a-sent-back-attempt-bails-to-root-and-never-merges`, `…/a-stale-branch-with-an-ended-attempt-merges-main-forward-and-keeps-its-work`; `a-three-task-chain-ends-merged-with-no-hand-act` rewritten over needs with a merge task |
| 3 | T3c views, claims, schema, miner (same restart batch) | pending | task-graph page; `cluster-claims` through `:seon.task/branch`; `seon.chain.edn` and `:seon.task/next` deleted; `:paused`/`:stopped` folded into `:held`; `seon.mine` reads task branches | `seon.cluster.registry-test/a-task-branch-survives-retirement`, `seon.mine-test/kept-branches-lists-a-held-tasks-branch-with-no-chain-fact` |
| 4 | T4 environment restart | pending | no agent starts with an incomplete environment; an environment change restarts the agent | `…/an-environment-change-on-a-running-task-restarts-its-agent-on-the-new-environment`, `…/a-task-with-an-incomplete-environment-starts-no-agent-and-reports-the-gap` |

Already built for T3b: P4a/P4b (one accept transaction), P5 (red is red), P6 (one test request per step, also the
start step), P10 (conflicts section), T1, T2, T3a and T3a-agent. T3a-agent's commit 6033cebad lists 8 gaps against
`seon.turn/step` that T3b decides. They are unverified here.

The T3b design also has to answer one more thing.

### The owner's auto-run ruling against today's table

Owner, verbatim, 2026-10-19: "If we have things stable they can auto run. I just don't want you to turn it on and
have thousands of things running because we don't understand what's going on yet." Read as rules:
- within released work, tasks auto-run when their needs are done;
- the global on/off stays the owner's;
- `:seon.config.agent/max-running` stays small (4; 0 means nothing new starts);
- watching comes before switching on.

**What the T3a table does today for a task added to a released graph.**
- `seon.cluster.reconcile/task-state` sets `wanted?` to `(and (= :running execution) (not= :pending needs))`.
- `create-all!` writes every task `:held`, including one added later beside released work.
- Such a task matches row N1, the steady row "not released, or waiting on needs", and **never runs until root
  releases that task itself**.
- The same holds within one graph: releasing the Design task does not release Prove, Implement or Merge. Each needs
  its own `:running`, and then waits in N1 on `needs :pending`.
- Installed chains get the effect by `seon.task/advance-tx` writing `:execution :running` on the next task. The
  target deletes `advance-tx`.
- **Verified** by reading `transitions` and `task-state`. No test covers "added to a released graph".

**Does T3b keep the owner's rules? As planned, partly.**
- The global switch exists: `max-running` 0 admits nothing new (owner decision 8, `admit`), and Flow's pause of
  `db<->flow` is the instant off.
- The cap holds: `admit` starts oldest first, one per branch, while open attempts are fewer than `max-running`.
  Today's value is 4 (verified).
- **The auto-run inside released work is missing.** T3b must pick one rule and prove it, for example:
  - `my.root/run!` releases a task and every task that transitively needs it (as abandon cancels them);
  - or `create-all!` writes `:running` for a task whose needs are all in released, uncancelled work.
- The new proof would be a db-flow row: a task written by `create-all!` needing a released, running task starts
  when that need is done, with no second `run!`, and never beyond `max-running`. Neither T3b proof list nor
  TASK-CHAIN §2 has it today. `a-held-task-starts-nothing-until-root-releases-it` covers only the root release.
- Under the TASK-CHAIN rule "no new lifecycle clause without amending TASK-CHAIN and a red deftest", the ruling must
  be written into TASK-CHAIN §2 first.

**Observability that is missing before anything is switched on.**
- `bin/seon status` and `runtime_status` show only `:open-turns`, heap and faults (verified).
- `my.root/turns-report` grades only ended attempts.
- Nothing answers in one call:
  - held, running and done counts per task;
  - what each waiting task waits on (its pending needs);
  - the open attempts against `max-running`;
  - the cost so far per running agent: calls, tokens and evaluation ms/MB.
- Installed partial views:
  - the chains web page (`seon.task/chain-views`, `http://127.0.0.1:7994`);
  - `my.root/merge-steps`;
  - `my.root/ended`, which reads end messages;
  - the hand form in §3.3.
- T3c's task-graph page is planned, but its plan names no counts or cost per running agent. The natural home is one
  read of the observed value `seon.cluster.agent/observe` builds in T3b. That function does not exist yet.

### Can run 13 use design → prove → implement → merge today?

- **Through `from-filed`'s four tasks and `create-all!`: no.** That waits for T3b (§0 table).
- **Through the installed chain path: yes for design → prove → implement**, with the root's final merge as the
  merge step. It needs `from-filed`'s first three maps with `:needs`/`:target` removed (§2.1), and **only after
  Broken 1 is fixed**. Until then every Prove step's proof is red, so no chain passes Prove.
- The installed merge is root's `merge-chains!`. It runs automatically when the last task is done, and a refused
  merge appends the held merge step (MERGE-STEP).

**Recommendation (the honest path):**
1. Fix Broken 1 first. Classify the row against `tests-reaching`, fix the owner, then re-run
   `seon.step.prove-test/handoff`.
2. Fix Broken 3 (help) in the same window.
3. Run 13 as installed three-task chains from `from-filed` (§6).
4. Call it what it is: the installed chain model, not the target task graph. Its measurements (Design and Prove turns,
   plan updates) carry over to the target, because the templates, done rule, block and turn loop are the same code
   T3b keeps.
5. Do not hand-release `create-all!` tasks.

## 1. The installed chain, step by step

### 1.1 The data

- A task is one entity (TASK-CHAIN §1). Members: `:seon.task/ns`, `:seon.task/name`, `:seon.task/plan` (flowing),
  `:seon.task/instructions`, `:seon.task/proofs` and `:seon.task/model` (owned), `:seon.task/next` (link) and
  `:seon.task/execution` (control).
- **Verified** in JVM mode with `#'seon.task/owned-members` and `#'seon.task/flowing-members` on main's projection:
  - owned: `[:seon.task/instructions :seon.task/proofs :seon.task/model]`;
  - flowing: `[:seon.task/step :seon.task/plan :seon.task/product]`.
  - `step` and `product` are legacy members (TASK-CHAIN §6 deletes them). Templates do not set them, so in practice
    only the plan flows.
- `seon.task/template` answers a template's owned members.
- **Verified:** the design and prove templates hold `instructions` and `proofs`; implement and merge hold
  `instructions` only. No template sets `:seon.task/model`: absent means the cluster's `:seon.config.ai/model`
  (`"deepseek-flash"` on main).

### 1.2 Each step

| Step | Template proof | What its agent must do (template `## Work`) | Its done |
|---|---|---|---|
| Design (`seon.step.design/template`) | `seon.step.design-test/handoff` (green on HEAD, check 359dd87f7965) | keep `## Problem` unchanged; find the producer, callers, schemas and covering tests; write `## What exists` and `## Approach` into the plan with `my.task/update!` … `my.edit/text`; register new schemas on the branch; define new functions as contracted stubs throwing `:seon.error/unimplemented`; say how each Problem example becomes true | `seon.step.design/handoff` empty: `:sections`, `:problem-changed` (Problem against the plan at the creating transaction, `created-problem`), `:references`, `:producer-not-cited` (What exists cites a first-party fn and a deftest, Approach a fn), `:data-not-modeled` (a schema key changed on the branch or resolved in those sections); then the done rule |
| Prove (`seon.step.prove/template`) | `seon.step.prove-test/handoff` (**red on HEAD**, Broken 1) | write table-row deftests that are red for the right reason; add them to the next task's proofs with `(my.task/update! (get-in (my.task/current) [:seon.context/chain :seon.context/after 0 :seon.task/sym]) :seon.task/proofs (fnil into #{}) '[…])`; unmap scratch; never touch its own proofs | `seon.step.prove/handoff` empty: `:no-proofs` (next task's, through `:seon.task/next`), `:not-a-deftest`, `:not-designed-red` (`seon.test.runner/designed-red?`), `:unreached` (changed or Approach-cited fns outside the proofs' tested closure); then the done rule, where the next task's red proofs are exempt |
| Implement (`seon.step.implement/template`) | none on the template; from `from-filed` it carries the filed entry's proofs, and Prove adds more | change the producer the Approach names and convert its callers; unmap what it does not keep; send back a proof that contradicts the plan | the done rule alone: proofs green, no other red member, no contract gap, the file check, and on the last task no uncovered merged function |
| Merge (`seon.step.merge/template`) | none; its proofs are set at the append | installed: only after a refused final merge, appended `:held` on the kept merge branch by `merge-chains!` (with `## Merge refusal`), run by `my.root/run!` | the done rule on the merge branch; then root's pass merges that branch again |

**Prove can end at its start with zero model calls.** `from-filed` gives Implement the filed entry's proofs. If they
are already red for the right reason and reach every function the Approach cites, Prove's handoff is satisfied before
any work (TASK-CHAIN §2.1: "A stage with nothing left to do is green at its start"). Inferred from the code; not
observed live since 10-14.

### 1.3 The done rule (one function for every step)

`seon.cluster.source/done-refusals [branch judged]` judges every step. The judged input comes from
`seon.context/judged-input`, read through `step-observation` and `step-verdict`. Its next proofs are the proofs of
the task named by `:seon.task/next`. It refuses:
- unresolved unmerged entries;
- a proof or member with no executed result, and a task with neither proofs nor next proofs;
- a red proof, or any red member that is not one of the next proofs ("red is red", P5);
- a contract gap;
- the file refusal (`seon.cluster.source/publication`);
- on the last task, uncovered merged functions.

Done-rule table test `seon.cluster.source-test/done-refusals-judges-every-task-alike`: **green**.

The block shows the step section (`seon.context/step-section`): the status, each refusal with operation forms, other
red tests, and product tests.

### 1.4 What ends a step

`seon.turn/task-end` decides the end:
- `:done`: `step-verdict` status `:done`, checked after every eval request's test step and at the attempt's start
  (P6's one `seon.test/run`).
- `:sent-back`: `my.task/send-back!`.
- `:given-back`: a completed turn with no send-back.
- `:capped`: `seon.turn/turns-left` is 0. The cap is `:seon.config.run/max-episode-runs` on the chain branch's
  config. Main holds 100 (verified); runs 8 to 12 set 40 by `:seon.chain/config`. The cap counts per attempt: each
  step's agent is new and gets the full cap.
- `:stalled`: Seon's loop stops calling.

Then root's pass handles it:
- **done:** `advance-chains!` re-judges `done-refusals` at the attempt's end commit (`:seon.task.attempt/commit`).
  - Empty: one transaction `[:db.fn/call seon.task/advance-tx key]` writes outcome `:done`, gives the next task the
    finished task's flowing members (the finished value wins) and sets it `:running`. The next pass starts a new
    agent on the same branch.
  - Refused: `bail-chain!`.
- **last task done:** `merge-chains!`.
  - Accepted: `finish-chains!` publishes a git commit and ends the branch, which is kept, since
    `:seon.config.agent/keep-ended-branches?` is true.
  - Refused: bail, and a held merge step is appended.
  - `seon.merge/main-moved?`: retried on a later wake. This is run 12's unverified 8-minute delay.
- **any other end:** `bail-chain!` writes a problem task on main (`:unfinished-task`, carrying the last verdict),
  then on the branch the attempt `:closed` with its reason and the task `:held`. Nothing reruns until
  `my.root/run!`.

### 1.5 What flows to the next step and what does not

- **Flows:** the plan. Prove's proofs written onto Implement's task also stay: they are written on that task
  directly, so they are not flowed.
- **Does not flow:** instructions, proofs and model (owned).
- The code also stays: every step shares one branch, so everything the previous step defined is already there.
  - The next agent sees it under `:seon.context/changes`: every declaration changed since the merge base, by family.
  - It also sees the tasks before it under `:seon.context/chain :seon.context/before`. Mid-chain those are only
    symbols, since `step-before` shows `:merged` only after a main merge.

## 2. How to start a real task the designed way

### 2.1 Installed (use this for run 13, after Broken 1)

The root act is one SCI request as root on default, as in runs 8 to 12 (`tmp/run12/start.clj`). Call preparation
fills `chain!`'s `{}` context, with connection and store, from root's bindings. Form, built on the verified
three-map vector (`datahike.api/with` probe: three tasks linked by `next`, executions `[:running :held :held]`,
proofs `[#{design handoff} #{prove handoff} #{filed proofs}]`):

```clojure
(let [db (seon.db/db)
      start (fn [sym]
              (let [filed (into {} (remove (comp nil? val))
                                (seon.db/pull db [:seon.task/ns :seon.task/name :seon.task/plan :seon.task/proofs :seon.task/model]
                                              [:seon.task/sym sym]))
                    ;; a new identity per run: chain! answers an existing chain for a first task main already chains
                    filed (update filed :seon.task/name str "-run-13")
                    three (mapv #(dissoc % :seon.task/needs :seon.task/target)
                                (subvec (seon.task/from-filed db filed) 0 3))]
                [sym (seon.task/chain! {} three
                                       {:seon.chain/config {:seon.config.ai/no-provider :seon.config/absent
                                                            :seon.config.run/max-episode-runs 40}})]))]
  (mapv start '[ns/task-a ns/task-b]))
```

- **Names.** Only the first map's name counts. The tasks become `<name>`, `<name>-2` and `<name>-3`. The branch is
  `chain-<handle>`, and the answer carries the first attempt's `:seon.agent/id` (`attempt/<key>`).
- **Model.** Absent means the cluster's (`deepseek-flash`). A per-task value is `(assoc m :seon.task/model "…")` on
  each map. `:outside` starts the agent with no model turns, for a Claude Code judge entering by `eval_clj` as that
  agent id.
- **Config.** `:seon.chain/config` decides dials on the chain branch only, in the same transaction
  (`seon.task/config-tx`). Every task's runnable view must validate first (`chain-refusal`), or nothing is written.
- **max-running.** Main's `:seon.config.agent/max-running` is 4 (verified). It counts open attempts across all
  chains. A chain has one open attempt at a time, so 4 chains run at once and the rest wait.
- **Keeping the filed identity** (S5 run 4's form): the first task revises the filed entry on the chain branch, and
  the merge carries that revision to main. Nothing deletes the entry from its task file (Broken 8). The `-run-13`
  suffix avoids touching the entry. Root then retires the filed entry by hand in the commit that records the merge.
- **To run from a kept branch's work:** pass `{:seon.chain/from :chain-…}` (the merge base stays main).
- **A plan with no `## Problem` section** is wrapped as one by `from-filed`. The Design check `:problem-changed`
  compares against that wrapped text.

### 2.2 `run!`, held and abandon: installed vs target

| | Installed | Target (T3b) |
|---|---|---|
| Write | `chain!`: first task `:running`, the rest `:held` on the chain branch; the chain fact on main | `create-all!`: every task `:held` on main; no chain entity, only `:seon.task/needs` refs |
| Release | `chain!` itself starts the first task; `advance-tx` releases the next at each done; `my.root/run! {:seon.task/assigned handle}` re-runs a **held** chain task (after a bail) by one branch transaction `:held` → `:running` (`seon.task/run-tx`, refused `:unrunnable` when the runnable view fails) | `my.root/run!` is the only release (start and resume), one write on main; within released work, auto-run per the owner's ruling (§0, not yet designed into T3b) |
| Held | a bailed task, or a later chain task not yet advanced to | every written task until released; `:paused` and `:stopped` fold into `:held` |
| Abandon | `my.root/abandon! {:seon.chain/branch b :seon.task/reason r}`: every open attempt `:closed`, every task `:cancelled`, the branch ended (kept, by the dial) | cancels the named tasks and every task that needs them |
| Cap | `:seon.config.agent/max-running` in `launch-actions` | the same dial in `seon.cluster.reconcile/admit`; 0 admits nothing new |

## 3. How agents are meant to update the plan

### 3.1 The verbs

- `my.task/update!` is `(my.task/update! sym k f & args)`, with `clojure.core/update` semantics.
  - `k` is one of `:seon.task/plan`, `:seon.task/instructions` and `:seon.task/proofs`.
  - It writes on the caller's branch through `seon.task/create!`, with the declaration it read as its basis. A
    changed basis is refused.
  - It answers the handle and `:seon.task/changed?`, plus `:my.task/problems` for unresolved references.
  - Test: `my.task-test/update-applies-a-function-to-a-member`, **green**.
- `my.edit/text` is `(my.edit/text s old new)`: one exact replacement, or a no-match or ambiguous-match error.

### 3.2 Who tells agents to do it

- **Help:** `resources/seon/help.md` "Write what you learn" teaches both verbs, binding `task` and `next-task`. It
  says "Update as soon as you decide something the next step would otherwise rediscover." "When you cannot" says to
  record a decision with `update!`. `seon.bootstrap-test/every-reference-in-help-resolves`: **green**.
- **Design template:** required. Its whole product is the plan's `## What exists` and `## Approach`, and its handoff
  checks them.
- **Prove template:** writes the next task's `:seon.task/proofs`. It says nothing about updating the plan.
- **Implement template:** says nothing about updating the plan.
- **Merge template:** says nothing about updating the plan.

So a plan update is enforced only in Design. Prove's proofs write is enforced by its handoff (`:no-proofs`).

### 3.3 What shows plan changes downstream

- The next task receives the finished plan whole at advance (`advance-tx`), and its block shows the current plan
  under `:seon.context/task`.
- **Nothing shows what changed or who changed it.** There is no diff or history section in the block, and the help
  names `editscript.core/diff` only in TASK-CHAIN's description of help.
- Root can read the history. Verified on run 12's kept branches:

```clojure
(seon.db/read-at main :chain-13cdf64c0222
  (fn [head] (seon.db/q '[:find ?tx ?plan :in $ ?id :where [?e :seon.task/id ?id] [?e :seon.task/plan ?plan ?tx true]]
                        (seon.db/history head) "13cdf64c0222")))
```

Run 12's nsform chain has 2 plan versions (one update). Unres, program and typed have 1 each, the creation only.

**Root's watch form** (verified on run 12's kept branches; JVM mode, read-only). Per chain, it gives each task's
execution and proofs, attempts with end and outcome, plan versions, and model calls per attempt:

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      chains (seon.db/q '[:find ?b ?h :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]] main)]
  (into (sorted-map)
    (for [[b h] chains]
      [b (seon.db/read-at main b
           (fn [head]
             (mapv (fn [t]
                     (let [task (seon.db/pull head [:seon.task/name :seon.task/execution :seon.task/proofs] [:seon.task/id t])
                           attempts (seon.db/q '[:find ?k ?end ?out :in $ ?h :where [?x :seon.task/id ?h] [?a :seon.task/attempt-of ?x]
                                                 [?a :seon.task/assignment ?k]
                                                 [(get-else $ ?a :seon.task.attempt/end :open) ?end]
                                                 [(get-else $ ?a :seon.task/outcome :none) ?out]] head t)
                           plans (count (seon.db/q '[:find ?tx :in $ ?h :where [?e :seon.task/id ?h] [?e :seon.task/plan _ ?tx true]]
                                                   (seon.db/history head) t))]
                       (assoc (dissoc task :db/id) :handle t :attempts (vec (sort attempts)) :plan-versions plans
                              :calls (mapv #(:seon.competency/model-calls (seon.mine/grade head (first %))) attempts))))
                   (seon.task/chain-handles head h))))])))
```

Restrict `chains` to run 13's branches. It cost 180 ms and 153 MB for two chains: poll it every few minutes, not in a
tight loop.

## 4. What works end to end vs untested or broken (design and prove steps)

The check is `bin/seon check --at HEAD --include-long`, run `359dd87f7965`: 16 tests, 15 green, 1 red, 21 s
(2026-10-19, this lane).

| Test | Result | What it proves |
|---|---|---|
| `seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act` | green | chain!, start, advance ×2, one merge, publication, branch kept or deleted, with synthetic always-green proofs (no model turn) |
| `…/a-two-task-chain-reaches-its-second-model-call` | green | advance and the second task's first scripted model call; plan flows |
| `…/an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl` | green | `:outside` tasks through MCP |
| `…/a-capped-chain-attempt-bails-to-root-once-and-root-runs-it-again` | green | bail and `my.root/run!` |
| `…/a-held-chain-with-no-graph-runs-from-run-alone` | green | run! wakes the pass |
| `…/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step` | green | the merge-step append |
| `…/a-bailed-attempt-says-why-as-data` | green | `:seon.agent/end-refusals` on a bail |
| `…/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red`, `…/a-prove-step-admitted-by-its-successor-opens-a-model-turn` | green | the **old** per-assignment `:step :prove`/`:product` path (`launch-facts`), not chains; T3b deletes it |
| `seon.step.design-test/handoff` | green | the Design predicate's table |
| `seon.step.prove-test/handoff` | **red** | Broken 1: every live Prove step's own proof is red |
| `seon.task-test/a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task` | green | the T1 shape only (`create-all!`), not a launch |
| `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch` | green | `chain!` |
| `my.task-test/update-applies-a-function-to-a-member` | green | `update!` |
| `seon.bootstrap-test/every-reference-in-help-resolves` | green | help references resolve (not that help is true) |
| `seon.cluster.source-test/done-refusals-judges-every-task-alike` | green | the done rule table |

**Untested anywhere** (no deftest found):
- a chain built from the real Design and Prove templates;
- Prove writing the next task's proofs through `update!` in a db-flow chain;
- Design's `:problem-changed` against a chain's creating transaction on a live branch;
- the `from-filed` → `chain!` composition, which is broken (Broken 2);
- a Prove step done at its start with filed proofs.

**Untested live since this week's changes:**
- every Design and Prove behavior since 10-14;
- P5 ("red is red") on a mid-chain task, where Prove's own red rows must be exactly the next proofs;
- P6's one test request on a Prove step;
- P10's conflicts section on a merge step;
- the runtime work (SCI pin 33039c6e and the content-addressed Var space) under a Design agent's stub `defn`s and
  schema registrations.

The last item is an inference from `1e975f291` "Branch runtime note: the interop result … what remains"; it is not
measured.

## 5. What the grader and `turns-report` measure

The grader is `seon.mine/grade-entity`. `db<->flow`'s `grade-ended!` writes it once per ended attempt, on main
(`seon.mine/ungraded` → `grade-tx`). Per attempt it records:
- end;
- turns (calls), and turns to green when the end is `:done`;
- wasted turns: calls with no `:read`, `:probe`, `:progress` or `:wait` form (`seon.mine/classify`);
- form classes, and waste by rule and by producer (`steps`, `rank`);
- prompt, cached and completion tokens, and cached share;
- wall time;
- git sha and provider-reported models;
- the task handle (`:seon.grade/task`).

`my.root/turns-report` (`seon.mine/report`) gives:
- attempts, green, and the median and p90 of turns to green;
- the same per commit-and-model window;
- the top 10 waste rules and producers.

**Per step:** nothing. A grade names its task, and nothing names its step kind. `:seon.task/step` is legacy, and the
templates do not set it. Root can derive the kind from the task's proofs on its branch:
`seon.step.design-test/handoff` means Design and `seon.step.prove-test/handoff` means Prove.

**Per chain:** nothing. Grades are per attempt. Chain wall time, total calls and "merged or not" are hand-joined
today (run 12's tables).

**Needed to measure plan updates and turns before first write** (proposals, unbuilt):
- `:seon.grade/plan-updates`: the count of the attempt's evaluations whose form head is `my.task/update!` with `k`
  `:seon.task/plan`. Or, better, the count of plan datoms its agent asserted in branch history, as in §3.3.
  `classify` lumps `update!` into `:progress` with `defn`.
- `:seon.grade/first-write-turn`: the index of the first call holding a `:progress` form. Runs 7 to 12 report "first
  write at form N" by hand.
- `:seon.grade/step`: derived as above, so `report` can window by step kind. Otherwise a 0-call Prove (turns to green
  0) or a short Design lowers the pooled median and reads as progress.
- A chain or graph grouping: the attempts of one branch (installed) or one `needs` component (target), summed.

## 6. Recommended procedure for live run 13

Every step marked **UNTESTED** has no deftest and no live run since this week's changes.

### 6.0 Preconditions (root, before intake)

1. **Fix Broken 1.** `seon.step.prove-test/handoff` must be green. Re-run it with
   `bin/seon check --at HEAD --test seon.step.prove-test/handoff`.
2. **Fix Broken 3.** help.md "Who you are" and "Your task" must describe one chain branch and one merge at the end.
3. Restart default onto that HEAD (root's batch).
4. Run `bin/seon status`, and confirm `:seon.config.agent/max-running` 4 on main (JVM read
   `(seon.config/agent-cluster-dial db "root" :seon.config.agent/max-running)`).

### 6.1 Task intake and screening

1. **Pick 3 or 4 filed tasks** with a plan that has a `## Problem`, and preferably an existing red proof.
   - At most 4, so that every chain starts at once under `max-running` 4.
   - Mix them: one where Design must find the producer, one with filed red proofs (Prove may be done at its start),
     one a new behavior with no proof.
2. **Screen in one check JVM**: `bin/seon check --at HEAD --test …`. Name:
   - the proofs;
   - every test the plan names (run 12's Broken 1 missed one);
   - the test namespaces of the owner symbols;
   - `seon.step.design-test/handoff` and `seon.step.prove-test/handoff`.

   Accept a task only when:
   - its proofs are red for the right reason;
   - every other test in its reach is green, or is listed in its plan as part of the work;
   - its producer is not host-bound (PLATFORM-STEP: a branch cannot prove a host-bound change).
3. **Check references.** `(seon.ref/references db {:seon.ref/text plan})` should give no `:seon.ref/problem-kind`.
   Design's `:references` check refuses unresolved names, so an unresolved name in the Problem is a trap the agent
   cannot fix, because it must keep the Problem unchanged. Fix it in the filed plan first; S5 run 4 did this by hand.
4. Commit the intake proofs and plan fixes before starting, so the chains fork from them.

### 6.2 Starting full chains

- **One SCI request as root** with the form in §2.1. Record the answers: branch, first handle and agent id.
- **UNTESTED:** this exact composition. The three-map vector was verified with `d/with`; `chain!` itself was not run
  with it.
- **Do not** call `chain!` on `from-filed`'s full output (Broken 2).
- **Do not** use `create-all!` (§0).

### 6.3 What root watches (JVM-mode reads only; no SCI as root during the run)

1. The watch form in §3.3 every few minutes: task execution, attempts with end and outcome, plan versions, calls.
2. Advances: a Design attempt `:done` should be followed by Prove `:running` and a new attempt on the same branch
   within one pass. **UNTESTED** with real templates; the mechanism is tested with synthetic proofs.
3. Prove's write: Implement's `:seon.task/proofs` grows on the branch (`seon.db/pull head [:seon.task/proofs]
   [:seon.task/id implement-handle]`). **UNTESTED** in any deftest.
4. Bails: problem tasks on main, named `seon.task/unfinished-task-<attempt key>` by
   `seon.cluster.agent/write-problem!`, each with `:seon.task/verdict`. The query is verified on main:
   `(seon.db/q '[:find ?n ?v :where [?t :seon.task/ns seon.task] [?t :seon.task/name ?n] [(clojure.string/starts-with? ?n "unfinished-task-")] [(get-else $ ?t :seon.task/verdict "") ?v]] main)`.
5. Merges: a new git commit "Merge <ns>/<name>-run-13-3 …" from `seon.merge/publish!`. `chain-finishes` should drop
   the chain. A done chain that does not merge within a pass or two is run 12's unverified `main-moved?` delay:
   record the time.
6. Health: `runtime_status` before and after; heap via `seon.cluster.status`. Any error recorded during the run is a
   finding.
7. Do not intervene. A bail goes to root; decide after the run.

### 6.4 Post-run law review of merges

For each merge commit:
1. Read the published diff (`git show <sha>`).
2. Check it against the laws: no guard or fence, contracts at the producer, no printing to compare, no hand list,
   errors as declared variants.
   - Run 12's var merge shipped a `{}` fallback over a declared variant, and an undeclared status key.
   - Its pairs merge compared `(str emitted)`.
3. File each violation as a task with its exact form, or fix it within 15 minutes.
4. Retire the filed entry the chain fixed (Broken 8): delete it from `resources/seon/tasks/<ns>.edn` in the run's
   commit.
5. Dual review (CLAUDE.md "Every review is dual"): a fresh Opus agent and Astra, each finding reproduced before it
   is fixed.

### 6.5 How failures feed back

- **Per bail**, read the problem task's verdict and the agent's forms (the watch form plus `seon.mine/grade` and
  `seon.mine/steps`), then classify:
  - **the task was wrong** (bad proof, missing red in reach): fix the plan or proofs on the branch with
    `my.task/update!` as root's agent, or file it again; then `(my.root/run! {:seon.task/assigned h})`;
  - **context was wrong or missing** (block, help, render): fix it at the producer, or file a task on the producer
    with the exact form and reply;
  - **model behavior**: record it in the run's evidence; no fix.
- **Per Design** that passes its handoff with a wrong Approach (S5 run 4's finding 3): tighten
  `seon.step.design/handoff` with a new table row in `seon.step.design-test/handoff`. Never add a guard in the turn
  loop.
- **Metrics:** run `(my.root/turns-report)` and split it by step by hand, by deriving each grade's step from its
  task's proofs (§5). Report plan versions per task and the first write per attempt.
- **Write the run's evidence** to `docs/research/agent-platform/2026-10-2x/live-run-13.md` in runs 10 to 12's
  format, with Design and Prove columns added. Commit the filings in the same commit.

## 7. Findings outside this lane (none filed: this lane changes no tasks)

1. `seon.step.prove-test/handoff` is red on HEAD in row "a proof never run" (Broken 1). This is the blocker for any
   Prove step. Owner: `seon.test.runner/tests-reaching` or the row.
2. `seon.task/from-filed` and `seon.task/chain!` no longer compose (Broken 2). Either `chain!` drops `:needs`/`:target`
   and stops at a merge map, or `from-filed`'s docstring names the three-map use until T3b. T3b deletes `chain!`, so
   the cheapest fix is the three-map form in §2.1, or a 15-minute `from-filed` docstring note.
3. help.md promises per-step merges (Broken 3).
4. The T3b design lacks the owner's auto-run rule for tasks added to released work, and a red proof for it (§0).
5. The grade has no step kind, no plan-update count and no first-write turn (§5).
6. `my.root/instructions`' laws still say "A merge request is an assignment … `(my.root/merge! {:seon.task/assignment
   key})`" and "An agent's end reaches you as a message". The installed chain merges by itself in `merge-chains!`.
   The laws are stale against TASK-CHAIN §2.6. Unverified whether any reader still depends on them.
