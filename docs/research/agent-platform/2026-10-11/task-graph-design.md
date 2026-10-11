---
title: "Task graph: every unit of agent work is a task, linked, advanced by derivation (proposal for owner discussion)"
type: research
status: superseded by runtime-follows-db.md (owner rounds 1–4, 2026-10-11): one task per step and the armer as launcher were taken; `:seon.task/after`/`:of` became `:seon.task/needs`/`:seon.task/steps`; kept as history
created: 2026-10-11
basis: HEAD f43646710; seams verified by symbol in source at that commit, no JVM probe
---

# Task graph

Owner (2026-10-11): everything an agent does should be a task; simple ways to chain them, branch them and pass them
along; "all in the database explicitly linked up, and when we detect updates we advance chains." What exists: tasks are
entities at main, an assignment row binds one to an agent's branch (`seon.task/assign!`), a task's end is derived
(`seon.turn/task-end`) and recorded in one transaction (`seon.turn/end-task-call`), and one Datahike listener routes
declared attributes to agent mailboxes (`seon.cluster.wake/route!`). Nothing links one task to the next: the chain is
launched by hand (`seon.competency/start-run!`, test code). The proposal: one link attribute, readiness derived at
the writer and in the armer, and launch as an idempotent effect keyed by the assignment's identity.

## 1. The quarry: `seon.plan` (deleted in cea904e92, 2026-09-28)

`src/seon/plan.clj` 1,432 lines (83 functions), `src/my/plan.clj` 186, schemas `my.plan`, `my.plan.item`, `seon.plan`
427 lines, tests about 960. Read at `cea904e92^`.

| Capability | How | Verdict now |
|---|---|---|
| Ordered step tree per agent | `:seon.agent/plan` component → `:my.plan/steps` → nested `:my.plan.item/steps`; `:my.plan.item/position` orders siblings | Not reused: private to one agent, so nothing could be handed to another agent, which is the whole point now |
| Dependencies, including across agents | `:my.plan.item/needs` (set of step ids, may name another agent's step; a missing one counts as open) | **Reuse the idea**: a link by identity to another unit of work, missing = not done |
| Ready / blocked | `derived-frontier`: blocked while any need carries open work; ready when incomplete, unblocked, leaf or all children complete. Derived in Clojure from one pull, never stored | **Reuse**: ready is a derivation over completion facts, never a status field |
| Completion | presence of `:my.plan.item/completed-tx` (the completing transaction; its instant is the time) | **Reuse the shape**: completion is a fact written once by its producer |
| Cycle refusal | `refuse-dependency-cycle!` in `plan!` | Reuse at the writer (AGENTS.md: validate final owning values, refuse cycles) |
| Auto-completion | `:my.plan.item/done-query` evaluated by `settle-call` inside every turn's transaction, under a deadline | Not reused: re-evaluating arbitrary queries every turn is polling; no task ever authored one |
| Current step | `:my.plan/current-step`, a mutable ref | Not reused: a stored "where am I" is the state machine to avoid |
| Whole-tree reconcile, objective, about/subject tokens | `plan!`, `objective!`, `:my.plan.item/about` | Not reused: no callers |

Why it was deleted (spec `docs/seon/specs/task-steps.md`, cb2b770fd, reviewed 0643d3ad2): "keep as flat ordered steps;
the tree is not used"; `needs`/ready/blocked "dead: delete" (`ready-subjects` had no caller); done-query "dead in
practice"; `plan!` dead; verdict "YES, with steps on the task", about −3,200/+300 lines. Root rulings R1–R4: steps live
on the task at the task home; an agent with no task makes a self `:work` task; no migration. c135d9993 landed the steps.
**What still holds:** no agent-side task attribute (the edge is the assignment, `seon.task/agent-task`); done-query
stays deleted (completion is proofs, AGENTS.md "Derive work and completion"); the rejection of steps on the agent.
**What no longer holds:** "needs is dead" was true of callers, not of the need: dependencies had no user because work
was one agent per task. The steps themselves are already scheduled for deletion (plan §0 item 5). The owner's
2026-10-10 ruling ("nail a single task; children and planning later", plan §6 deferral of "child tasks, task graphs and
fan-out") is what this note reopens; §6 asks whether to build now or after the first experiment.

## 2. The current mechanisms, verified

| Seam | What it does | Consequence for a task graph |
|---|---|---|
| `seon.cluster.wake/route!`, `wake-attributes` | one `d/listen` per connection; attributes with `:seon.wake/listen true` route a payload-free wake to the agent the datom's value names; `:seon.wake/opens-turn?` decides whether it may cause a model call | listened today: `:seon.message/to`, `:seon.effect/to` (open turns), `:seon.schedule.fire/agent` (does not). A wake is "look"; work is derived |
| `seon.cluster.wake/arming-attributes`, `seon.cluster.agent/armer-step` | `:seon.wake/arms true` (on `:seon.agent/id`, `:seon.schedule.task/owner`) wakes the cluster's armer, which derives unarmed agents with work (`seon.turn/more-agent-work?`) and arms them; it primes itself at `::flow/resume`, so boot is the same derivation | the existing "make the live roster match the facts" proc, effects included, recovery by construction |
| `seon.cluster.host/host!` | hosts a branch agent: acquires its branch, writes the agent on the cluster and branch, runs an armer owning that branch's router | a branch agent is woken only by datoms on **its** branch (`seon.cluster.message/send!` writes on the recipient's home branch for this reason) |
| `seon.cluster.registry/fork!` → `branch!` | answers `:seon.cluster/created? false` for an existing name | forking by a derived name is idempotent |
| `seon.task/assign!` | runs the proofs in the agent's world (effect), refuses `:proofs-green`, `:proofs-unobserved`, `:proof-run-failed`, then writes the assignment row; identity `seon.id/id [handle agent branch]` | the assignment is the unit of an attempt, with a deterministic identity |
| `seon.task/assignment` | refuses `:revised-since-assignment` when the task's declaration digest changed | **a `refine!` of intent or must-read on the task an agent holds breaks its own assignment** under TASK-CHAIN §2's one-evolving-task model |
| `seon.turn/end-task!` | proposal → `land-end!` (dial) → `seon.context/outcome-tx` → `end-task-call` (end message to root + `:seon.agent/admission-closed-tx`, one transaction at the home) | the end transaction is the natural place to record the attempt's end; no launch exists |
| `seon.turn/next-agent-work` | an `:open` turn needs a held assignment plus an unanswered wake or a continuing reply | a freshly assigned agent with no wake does nothing: why `start-run!` sends "Work your task." through `seon.test-support/outside-wake-on-branch!` |
| `seon.schedule/schedule-step`, `fire-call` | per-agent proc, its own `d/listen` and timer; each due instant is a `:seon.schedule.fire/fire` fact that calls the declared Var directly | `:seon.schedule.task/*` is a second, different "task" (owner + function + cron), no model, no proofs |
| `seon.task/resolution` | closed-by faults, signal or merge outcome; work tasks never close (catalogue §2.4) | completion of a work task has no fact at main today |

## 3. Proposal

### 3a. One task per step, linked to a shared subject (recommended over one evolving task)

| | One evolving task with `:seon.task/stage` (TASK-CHAIN §2) | One task per step, `:seon.task/of` its subject |
|---|---|---|
| Where the handoff lives | the task's fields | the **subject** task's fields (intent, must-read, evidence, decisions); the step tasks are small |
| Proofs per step | needs one stage-aware selection `seon.task/proofs` shared by four callers, keyed by a mutable stage | each step's obligations are its own: its stage type's proofs, plus the subject's tagged proofs for Implement; fixed at creation |
| Revision conflict | `refine!` revises the held task → `:revised-since-assignment` | the agent holds the step task; refining the subject never revises it |
| Fan-out, join | impossible (one stage at a time) | ordinary: several steps after one; one step after several |
| Send-back | rewrite the stage field | an end naming its target step (§3b); history is the assignments |
| Cost, measurement | per-stage numbers must be sliced out of one task | per step: one assignment, one agent, one end record |
| Entities | 1 per piece of work | 1 subject + N steps (N = 2 in the first experiment) |
| Mutable state | `:seon.task/stage` moves | none: links are asserted once |

Recommended: **one task per step**. Every unit of agent work (Design, Prove, Implement, root's merge request, root's
retrospective) is a task with its own assignment, proofs and end; the subject task is the handoff medium `refine!`
edits. Stage types stay data (template, proofs) as TASK-CHAIN §2 says; a step names its stage type once.

### 3b. Data: three attributes and one end fact

| Attribute | Shape | Meaning |
|---|---|---|
| `:seon.task/after` | card-many ref to tasks, indexed, `:seon.wake/arms true` | this task is ready when every task it is after is done; asserted at creation, never moved |
| `:seon.task/of` | ref to the subject task | the handoff the step reads and refines; context renders it (`seon.context/current-state`) |
| `:seon.task/stage` | ref to a stage type, set once at creation | template and stage proofs; replaces TASK-CHAIN's mutable "stage now working" |
| `:seon.task/end` on the **assignment** row, `:seon.wake/arms true` | the existing enum plus `:sent-back`, with `:seon.task/target` (ref to a step) and the decision | written by `end-task-call` in the transaction it already makes; asserted once per assignment |

Derived, never stored (the `seon.plan/derived-frontier` idea, over facts at main, all comparable by `t`):

- **done(T)**: some assignment of T ended `:done` and no later `:sent-back` end targets T.
- **ready(T)**: T is not done, has no assignment with open admission, and every task in `:seon.task/after` is done.
  A task with no `after` is ready when created (root's first step).
- **open subject**: a subject is closed when its last step is done (a fourth `seon.task/resolution` clause,
  `:closed-by :seon.task/after`), or by TASK-CHAIN §6.6's green-on-main clause; both derived.

Shapes the owner asked for, all the same data:

| Shape | Facts |
|---|---|
| Chain | Design; Prove `after #{Design}`; Implement `after #{Prove}`; merge request `after #{Implement}`; retrospective `after #{merge}` |
| Fan-out | three Implement steps `after #{Prove}` (e.g. one per caller family) |
| Join | Integrate `after #{merge-a merge-b}`: joins go through main, so the joined step's basis is main (§3c) |
| Send-back | Implement's end `:sent-back`, target Prove: Prove is no longer done → ready → a fresh agent; Implement waits on it again. No new tasks, no rewiring, no cap (TASK-CHAIN §3) |
| Merge request | the `:merge` task `seon.task/propose!` already writes, plus `after #{Implement}`, assigned to root; landing ends it |
| Scheduled maintenance | unchanged: a firing is a Var call, not agent work (§4, decision 4) |

### 3c. Who launches, and how (an effect, idempotent by identity)

Launch = for each ready task: derive the agent id and branch from the task and its attempt number, `fork!` the branch
from its basis (the single predecessor's branch at its end, recorded on that assignment; main for a root step or a
join), `host!` the agent, write the assignment. Each part is keyed by a derived identity: `branch!` answers
`created? false`, the agent upserts by `:seon.agent/id`, the assignment upserts by `seon.id/id [handle agent branch]`.
Running the derivation twice writes nothing new; once the assignment exists the task is no longer ready. No launched
flag, no lock.

| Option | Who derives and launches | Recovery | Verdict |
|---|---|---|---|
| A. **The armer** | `seon.cluster.agent/armer-step`, already woken by arming attributes: add `:seon.task/after` and the assignment's `:seon.task/end`. It already derives "unarmed agents with work" and arms them; it additionally derives ready tasks and launches them | its resume prime: the same derivation at boot | **recommended**: the existing roster-from-facts proc; nothing new runs |
| B. The predecessor's `end-task!` | after `end-task-call` commits, launch the successors it made ready (TASK-CHAIN §8.1.3 `advance-end!`) | none: the predecessor's admission is closed, so a crash between commit and launch strands the successor until something else derives it, which is option A anyway | rejected: two paths for one effect |
| C. Root's model turn | the end message to root (`:seon.message/to`, opens a turn) | root re-reads | rejected: a model call per advance |

The successor's first wake: a hosted agent's armer primes once at resume (`seon.cluster.host/host!`), and
`seon.turn/next-agent-work` should treat **a held assignment the agent has never answered** as work. That derivation
replaces `seon.test-support/outside-wake-on-branch!`'s "Work your task." message. (Writing the assignment as a listened
attribute does not work: assignments live at main, and a branch agent's router listens on its branch.)

The red-first proof run: `seon.task/assign!` runs proofs before writing (seconds, up to the 10 s step dial). Either the
armer calls `assign!` (launch blocks other arming for that long) or the successor's first pass runs its proofs before
its first model call and an already-green step ends with zero model calls (decision 3).

### 3d. What is reused from `seon.plan`

Reused: links by identity, missing = not done; ready and blocked derived from completion facts in one read; completion
as a once-written fact naming its transaction; cycle refusal at the writer. Not reused: agent-owned component trees,
positions, a current-step pointer, done-queries settled each turn, whole-document reconcile.

### 3e. What is deleted or simpler

| Item | Change |
|---|---|
| `:seon.task/steps`, `:seon.step/*`, `my.task/plan!`, `step!`, `done!`, `steps`, `seon.task/steps!`, `steps-call`, `steps-view`, the held-view's steps | deleted (already plan §0 item 5): a step is a task |
| TASK-CHAIN's mutable `:seon.task/stage`, `advance-end!`, `:seon.config.chain/advance?`, `:seon.task/basis-commit` on the task | not built: stage is set once; the basis lives on the predecessor's assignment; advance is derived |
| TASK-CHAIN §6.3 stage-aware `seon.task/proofs` keyed by current stage | shrinks to a function of the step task's fixed stage type |
| `seon.competency/start-run!` launch half, `seon.test-support/own-branch-agent!`'s setup eval, `outside-wake-on-branch!` | the bench writes subject + steps; the armer launches; settings become facts on the agent row |
| `my.root/launch!` (planned, not built) | becomes "create tasks"; launching is never called by hand |
| `my.task/open`, `claim!` | deleted (already item 5): work is assigned by readiness, not claimed |

### 3f. Cost per advance

| Operation | Proportional to | Notes |
|---|---|---|
| end recorded | 1 assignment | the `end-task-call` transaction it already writes; one more datom |
| ready derivation in the armer | successors of the ended task × their `after` count (reverse `:avet` on `:seon.task/after`), or on a prime the unlaunched linked tasks | never all tasks; no scan of history |
| launch | 1 fork (a pointer) + 1 host + 1 assignment | host's acquisition is the existing cost, today hundreds of MB (`seon.context/one-agents-current-state-costs-hundreds-of-megabytes`) |
| red-first proofs | the step's proofs | up to the 10 s step dial, wherever decision 3 puts it |
| a wake that changes nothing | 1 query | coalesced by the armer's sliding buffer |

## 4. Owner decisions

1. **One task per step vs one evolving task.** Recommended: one task per step with `:seon.task/of` its subject (§3a);
   it removes the revision conflict, the mutable stage and the stage-aware selection, and fan-out/join come free.
   Alternative: TASK-CHAIN as written (smaller schema, no graph).
2. **Who launches.** Recommended: the armer derives ready tasks and launches them (A); boot is its existing prime.
   Alternatives: `end-task!` launches (B, needs A anyway for recovery); root launches by hand until the experiment ends.
3. **Where the red-first proof run goes.** Recommended: the successor's first pass, before its first model call (an
   already-green step ends `:done` with no call; launch stays pure). Alternative: the armer calls `assign!` as today and
   blocks arming for up to the step dial.
4. **Scheduled work.** Recommended: maintenance firings stay Var calls (no model, no proofs) and `:seon.schedule.task/*`
   is renamed to free the word task (vocabulary entry first); a recurring *agent* task is a schedule whose firing creates
   a task from a template, added when one is wanted. Alternative: every firing is a task now.
5. **When.** Recommended: decide the model now and put `:seon.task/after` / `:of` into plan §0 item 6 in place of
   `:seon.task/stage`; run the first experiment (item 7) by hand with two linked step tasks; move launch into the armer
   after it. Alternative: keep the 2026-10-10 deferral and run item 7 on TASK-CHAIN's model unchanged.

Not verified: that `host!` is callable from the main armer's proc for a new branch (it is called from root's REPL
today); the exact listener connection for `:seon.task/end` arming wakes at main; the cost of the ready query on the live
store. Each is a probe before building.
