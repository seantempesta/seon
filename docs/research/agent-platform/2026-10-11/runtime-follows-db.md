---
title: "The runtime follows the database: task tree, five intents, one reconciler, heartbeat, root as scheduler, t-resume"
type: research
status: round 2 of an owner iteration (2026-10-11); not agreed
basis: HEAD be72c64c1, read by symbol; old plan model at cea904e92^; one read-only JVM probe on default (11 ms, 2.8 MB)
---

# Runtime follows DB

**Thesis (owner, 2026-10-11): there is ONE path, an agent wakes and runs a task, and everything uses it**: step tasks,
root's scheduling task, root's merge requests, maintenance, the heartbeat where it can. Governing principle: Flow
manages the literal process and computation; channels carry only ephemeral data; everything of consequence is a
database fact, so a crash loses nothing and a restore to any `t` is a derivation. One path means one thing to make
fast and stable, measured at its five seams (§0), and one reconciler that hosts it.

Probe (default): one cluster graph (`seon.agent/armer`, `seon.render.web/render`, both `:running`), one armed agent
graph (`root`), one child roster for the hosted branch agent `item3-interp`, 0 assignments, 705 tasks; listened
`:seon.message/to`, `:seon.effect/to`, `:seon.schedule.fire/agent`; arming `:seon.agent/id`, `:seon.schedule.task/owner`.

## 0. The one path, and everything not on it

The path: a listened datom wakes the agent (`seon.cluster.wake/route!`) → its turn proc pins one database value and
derives the work (`seon.turn/next-agent-work`: a held assignment, an unanswered wake) → the task is loaded and
validated and its context rendered (`seon.context/current-state`) → its proofs run on its branch (`test-step`) → green
ends it (`end-task-call`, one transaction) or red opens one model call → the recording transaction → the next wake.

| Today or proposed | On the path? | Disposition |
|---|---|---|
| step tasks (Design, Prove, Implement) | yes, once launched | the path |
| root's merge request (plan §0 item 8) | yes: a task assigned to root; `my.root/merge!` is a form in its pass | the path |
| root's scheduling (§D) | yes: root's `:work` task; nominal wakes answered with no call | the path |
| maintenance `:seon.schedule.task/*` + per-agent `seon.schedule/schedule-step` (own timer, own `d/listen`, calls a Var directly) | **no**: a second "task" with a second proc and listener | becomes a task: a schedule entity's firing **creates a task** (template, position, intent `:running`) whose proof is the Var's postcondition; a cheap firing with no model is a task whose first pass goes green; one timer proc stays in the cluster graph to write the fire fact (a clock is not a task) |
| `seon.test-support/start-run!`, `own-branch-agent!`, `outside-wake-on-branch!` ("Work your task.") | no: hand launch plus a manufactured wake | deleted; the bench writes tasks; a held unanswered assignment is work |
| `seon.task/assign!`'s proof run before the row, on the caller's thread | no: proofs outside the agent's pass | the first pass runs them |
| `eval_clj` hosting an ad-hoc agent | half: it hosts, then runs the form as that agent's run | root's act at the entrant boundary through the same `host!`; the run is on the path (E6) |
| the armer / reconciler (§A3) | **no, by design**: it hosts the path; it writes no task facts | stays: the one thing that is not a task, because a task cannot start itself |
| the heartbeat (§A4) | partly: a timer that pings and writes facts | a proc, not a task: its work is a clock and a `ping` and its only writes are observations; making it a task would wake an agent every 30 s for nothing. Its *consequences* (stale → anomaly) reach root's task on the path |
| the render proc, the fault committer | no | views and the entrant boundary; they run no agent work |
| `seon.turn/end-task!`'s proposal and landing (`land-end!`, dial off) | half: inside the pass, but a separate landing path | the landing is root's merge-request task; `end-task!` only records the end |

What to measure on the path, per task and per round, recorded as profile evidence (never a target invented; 10× over
the work's proportion is wrong work): **wake → work derived** (`route!` to `next-agent-work`, ms; today sub-ms routing);
**work → task loaded and validated** (assignment read, task pull, reference resolution; proportional to must-read
count); **task → context rendered** (`current-state`, ms and MB; today hundreds of MB, item 12); **context → proofs
run** (`test-step`, ms per proof, reuse by reach digest); **proofs → first call or end** (zero calls on green);
**end recorded → successor's graph resumed** (the reconciler's pass: fork, host, assignment; today host is
240–409 ms warm plus acquisition); **recording transaction** (under 100 ms, AGENTS.md); and per pass `:seon.repl/ms`,
`:seon.repl/alloc-mb`, calls. Stability: count passes that ended in a fault, an interrupted turn, a stale heartbeat,
a repeated effect after a crash; each is a Seon defect with a task.

## A. The model

Two kinds of state, kept apart: a task's **progress** is derived from facts written once by their producers (never a
field that moves); the **execution intent** is a stored decision nobody can derive. The runtime's **actual** state is
the roster and `flow/ping`, written back only by the heartbeat as an audit (A4), never read as truth by the reconciler.

### A1. Links: the `seon.plan` tree with plan items replaced by tasks

What `my.plan` stored (cea904e92^) and what each becomes:

| `my.plan` (deleted) | Meaning then | On tasks now | Verdict |
|---|---|---|---|
| `:my.plan/steps`, `:my.plan.item/steps` (component sets) | ownership: a step owns its sub-steps; retracting the parent retracts them; the parent is the reverse component edge | `:seon.task/steps` component ref set; a child's parent is `:seon.task/_steps`; **the parent task is the handoff medium** (`refine!` edits it; children read it in context) | reuse; replaces round 1's `:seon.task/of` |
| `:my.plan.item/position` (int among siblings) | authored sibling order, a stored fact because card-many is a set | `:seon.task/position`; **equal positions run concurrently, lower positions run first** (A1 readiness) | reuse, with the sequencing rule the old frontier lacked (it ran siblings concurrently and sequenced only through the current-step pointer) |
| `:my.plan.item/needs` (set of step ids, cross-plan, missing = open) | explicit prerequisite, dependency is not ownership | `:seon.task/needs` ref set to any task, any tree; a missing or retracted target stays open until the author removes the need | reuse; replaces round 1's `:seon.task/after` for joins and cross-links; sequence inside a tree is position, not needs |
| `:my.plan.item/completed-tx` | completion as a fact naming its transaction | the assignment's end fact (`seon.turn/end-task-call`), `:done` or `:sent-back` with target; a parent completes when its children do | reuse the shape; written by the end, not by hand |
| `:my.plan/current-step` | a mutable "where am I" | none: ready is derived | drop (the state machine to avoid) |
| `:my.plan.item/done-query`, `:done-when`, `:subject` | arbitrary completion queries settled every turn | none: a task completes by its proofs (AGENTS.md "Derive work and completion") | drop |
| `:my.plan.item/about` (symbol tokens) | what the step concerns | `:seon.task/must-read` and the typed intent | drop (already exists) |
| `:my.plan.item/agent`, `:my.plan/agent`, `:my.plan/objective` | an agent's private plan | the assignment row is the agent edge; the root task's intent is the objective | drop: trees are shared, not per agent |
| `derived-frontier` (ready/blocked in one pull) | blocked while any need is open; ready when incomplete, unblocked, leaf or all children complete | the same derivation plus position order, over main | reuse |

**Readiness** (derived, one pull of the tree plus the foreign needs, as `derived-frontier` did): a task is ready when
it is not done, has no assignment with open admission, every `:seon.task/needs` target is done, every sibling with a
lower `:seon.task/position` is done, and it is a leaf or all its children are done. **Done**: an attempt ended
`:done` and no later `:sent-back` targets it; a parent with children is done when they all are (and its own proofs,
if any, are green). A send-back makes a done task not-done by a later fact, so its successors stop being ready and it
is ready again for a fresh attempt. The chain Design(0) → Prove(1) → Implement(2) → merge request(3) → retrospective(4)
is five children of one subject task; a fan-out is children sharing a position; a join is a task that `needs` several.

### A2. The five intents, each with one reconciler action

Intent lives on the **task** (`:seon.task/execution`), since what runs is an attempt at a task; absent means
`:running`. The cluster carries `:seon.cluster/execution` ∈ `#{:running :paused}`; paused anywhere wins. Both are
`:seon.wake/arms true`. The reconciler's precondition is readiness; the intent is permission and shape.

| Intent | Reconciler action (Flow verbs) | Facts | Entered by |
|---|---|---|---|
| `:held` | nothing, even when ready; no agent, no branch | the intent | root (scheduler) or the creator |
| `:running` | if no graph: fork from the predecessor's recorded commit, `host!`, write the assignment, `flow/start` then `flow/resume`; if paused: `flow/resume`; if hosted elsewhere after a restart: re-host from the assignment row | assignment row (once), branch | root; the default |
| `:paused` | if no graph: launch as above but **no** `flow/resume` (Flow starts paused; context acquired, inspectable, no transform, no proof, no call); if running: `flow/pause` (takes effect after the current transform) | the intent | root, `start --paused`, t-resume |
| `:stopped` | `flow/pause`, join the pass, `flow/stop`, join the executor (`disarm!` without the interrupt), release the SCI context; agent, branch and assignment stay; `:running` re-hosts from the row | the intent | root (memory, long waits) |
| `:cancelled` | interrupt (`disarm!` as today), close the assignment with the reason, delete the branch; terminal | end fact `:cancelled` + reason, `:seon.agent/admission-closed-tx` | root |

Complete and minimal, checked against what the roster can be in: no graph and not launchable (`:held`), no graph
but launchable (`:running` before launch), graph paused (`:paused`), graph running (`:running`), graph gone but
resumable (`:stopped`), gone for good (`:cancelled`); done is derived, not an intent. `:stopped` is distinct from
`:paused` because a hosted context costs hundreds of MB (`seon.context/one-agents-current-state-costs-hundreds-of-megabytes`),
and from `:cancelled` because its facts stay due. "Finish the current turn then stop" is Flow's own semantics: the
control channel is read with priority **between** transforms (`flow/impl.clj` `proc`), so `pause` + join ends after
the current pass; only `:cancelled` interrupts.

### A3. The reconciler: the armer, generalized

`seon.cluster.agent/armer-step` already is "make the roster match the facts": woken by the router on arming
attributes, primed at its own `::flow/resume`, deriving unarmed agents with work. It becomes the one reconciler: for
every ready task and every assignment with open admission, desired = its intent; actual = roster + `flow/ping`; apply
the table above, under one admission dial `:seon.config.agent/max-running` (ready tasks in creation order). Idempotent
by identity: branch names are derived, `fork!` answers `created? false`, the assignment upserts by
`seon.id/id [handle agent branch]`, `arm!` leaves an armed agent alone. Boot, crash recovery, a live change and
`--paused` are the same pass.

**Only the reconciler starts graphs.** An agent that wants work done writes a task (`my.task/create!` with its
position, needs, intent); it never calls `host!`, `arm!` or `assign!`. The owner's worry is right: a graph started
from inside another agent's transform would run on that agent's executor and die with it, and two starters of one
roster is the dual-writer problem. Today's one exception is the MCP entrance (`eval_clj` hosts an ad-hoc agent on
demand through `acquire-context!`/`host!`); it is root's own act at the entrant boundary and goes through the same
`host!` the reconciler calls (owner question E6).

### A4. Heartbeat: observed state written only when it changes

A proc in the cluster graph (`:seon.cluster/heartbeat`, `:io`, its own timer) pings every graph in the roster and its
child rosters every `:seon.config.cluster/heartbeat-ms`, and writes `:seon.agent/observed` (`:running`, `:paused`,
`:absent`, with the last transform count) **only when it differs from the stored value**, plus one
`:seon.cluster/heartbeat-t` on the cluster entity per period. Health reads: observed state older than two periods, or a
heartbeat older than two periods, is **unknown** (AGENTS.md "Health is a positive observation"); the reconciler never
reads it. History is the audit. Cost: one ping per agent per period (about 1 ms each); one datom per period for the
heartbeat (at 30 s, 2,880 datoms a day, reclaimed by retention GC) and one per actual state change. The heartbeat is
the only periodic writer in the system; its period is a dial, never under 10 s.

### A5. Boot modes and t-resume

| Mode | Pass | Guarantee |
|---|---|---|
| `start` | `recover-runs!` closes interrupted turns, then the reconciler's prime applies every intent: re-hosts assigned branch agents from their rows, resumes `:running`, leaves `:paused` paused, rebuilds nothing for `:stopped`/`:held` | the flows just continue; work stays due, nothing replays |
| `start --paused` | writes `:seon.cluster/execution :paused` before the prime | every graph exists and answers `ping`; no transform, proof or call runs; schedules pause too |
| resume to `t` | fork a branch from the commit at `t` (`seon.cluster.registry/fork!` from a commit id), intent `:paused`, point a cluster handle at it, prime | the facts at `t` and the agents as they were, inspectable, resumable one at a time |

The owner's claim holds: a branch commit id plus a reconstructible SCI context is enough, because context is rendered
live from facts (`seon.context/current-state`) and the SCI context is acquired from the branch's program entities
(`acquire-context!`; `seon.cache` may still hold it). **Unrecoverable, exactly:** live result values (`seon.cache`
LRU; a handle answers "gone"); the contents of SCI atoms and other places that are not program entities; an in-flight
provider call or evaluation at the crash (the work is due again and repeats unless its owner is idempotent); effects
already made outside the store (provider spend, git commits, written files, messages read by outsiders); and the
compiled JVM program, which is HEAD's, not `t`'s (only what the branch interprets from source is at `t`).

## B. Differences from the written designs and the code

| Where | Says | Change |
|---|---|---|
| TASK-CHAIN §2, §6 items 2–5; plan §0 item 6 | one evolving task, mutable `:seon.task/stage`, `:seon.task/basis-commit` on the task, `advance-end!`, advance dial | one task per step as a child; stage type set once; basis on the predecessor's assignment; readiness derived; no advance code |
| task-graph-design §3b | `:seon.task/after`, `:seon.task/of` | `:seon.task/needs`, `:seon.task/steps` + `:seon.task/position` (A1); launch stays the armer's (§3c option A) |
| `seon.task/assign!` | runs proofs before writing the row | the row is the launch's write; the red-first run is the agent's first pass (`seon.turn/step`'s `test-step`), so `:paused` holds it and a green task ends with zero calls |
| `seon.turn/next-agent-work` | needs a held assignment **and** an unanswered wake ("Work your task." is sent by `seon.test-support/start-run!`) | a held assignment never answered is work |
| `seon.cluster.agent/armer-step` | arms only its own branch's agents; branch agents are hosted on demand from `eval_clj` | hosts from assignment rows, so a restart re-hosts (verified gap: today an assigned branch agent is not re-hosted) |
| `seon.cluster/arm-agents!`, `seon.flow/start-graph!` | always `flow/resume` | resume only under the intent |
| `seon.cluster.agent/disarm!` | always interrupts | `:stopped` pauses and joins first; only `:cancelled` interrupts |
| `seon.task/resolution` | closed-by faults, signal, merge outcome | adds the derived done clause (children done, proofs green); work tasks (root's) never close (catalogue §2.4) |
| vocabulary | no execution words | record `:held :running :paused :stopped :cancelled` (Flow's `:paused`/`:running` kept) and rename `:seon.schedule.task/*` |

## C. Process control in Flow, and every channel classified

Flow gives for free (`clojure.core.async.flow.impl`): procs start `:paused`; `pause`/`resume`/`pause-proc`/
`resume-proc` are control commands with priority between transforms; `ping` returns status, count and `ping-map-fn`
state; `inject` puts on any `[pid cid]`; the transition arity sees `::flow/resume`/`::flow/pause`/`::flow/stop`;
`create-flow` is pure data; `stop` is a command, not a join (Seon joins the executor); report/error channels are
`sliding-buffer 100`; a graph's proc set is fixed at creation. Hence one graph per agent (interrupt and join one agent;
a shared graph would stop everyone to add one proc), the cluster graph for armer/reconciler, render and heartbeat.

| Channel (creator) | Carries | Buffer | Verdict under the principle |
|---|---|---|---|
| armer wake (`arm-agents!`, `host/start!`) | payload-free "look" | sliding 1 | conforms: rederived at the next pass or prime |
| mailbox in-port, `mailbox → turn` episode | payload-free | sliding 1 | conforms: the message is the fact; answeredness derived from `t` |
| schedule kick | payload-free | sliding 1 | conforms: due work derived from facts and the clock |
| render wake, runtime-eval, pages, per-tab taps | "re-render", page deltas | sliding 1 | conforms: pages derive from the database |
| request port + reply promise (`submit-source!`) | a submission's text, its reply | 1 / promise | transport for an awaited request; the run is recorded by the pass's transaction before the reply; a stop closes the reply and the caller retries |
| completion permit, stream, quiesce, `graph-ref`, `feeds-exited` | synchronization | 1 / promise | conforms: process control |
| `::flow/error` → `seon.flow/fault-port` | a proc's Throwable | synchronous | conforms by replacement: the fault is a fact before the put returns |
| `::flow/report`, `ping` replies | observation | sliding 100 | conforms; the heartbeat turns ping into facts on change |
| work launcher completion | io completions | n | conforms: results recorded by their owners |

Additions keep the rule: intents, steps, position, needs, assignments, ends, observed state and heartbeat are facts;
every wake is payload-free; a Flow command's truth is the intent fact it was derived from.

## D. Who schedules: root's standing task

The deterministic part needs no scheduler: the reconciler launches ready tasks under the dial. What remains is
judgment: holding, ordering, cancelling, repairing. That is **root's `:work` task** (the self task an agent without a
task has, R2; work tasks never close), with intent `:running`, as the owner wants: mostly automatic, root already
there when something breaks.

| Part | Mechanism | Model call? |
|---|---|---|
| sensing | root's wakes are what already reaches it: end messages (`:seon.message/to "root"`), recorded errors, plus the heartbeat's `:seon.agent/observed` changes and a stale `:seon.cluster/heartbeat-t` | no |
| nominal pass | an end `:done` whose successor is ready, or an observed change matching intent: the wake is answered by a pass that derives "nothing to do" (`:seon.wake/opens-turn? false` on those attributes, as a schedule firing today) | no |
| anomaly pass | an end `:given-back`/`:capped`/`:stalled`/`:cancelled`, a `:sent-back` loop (the same target twice), a recorded error, a stale heartbeat, an attempt past its budget: the wake opens root's turn with the anomaly in its context | yes, only then |
| acting | root writes facts: `my.task/create!` (steps, position, needs), an intent (`:held`, `:paused`, `:stopped`, `:cancelled`, back to `:running`), `my.task/refine!` on the subject, `my.root/merge!`/`close!`; the reconciler does the rest | — |
| proofs | the task's proofs are the invariants ("no assignment stalled longer than the dial", "every non-done end has a successor or a close", "heartbeat fresh"); red exactly when something is wrong, which is when root works | — |

Root never starts a graph itself; it changes intents and the reconciler acts. The anomaly predicate is one derivation
over facts, so "model only when something is wrong" is a declaration (`opens-turn?` plus the predicate), not code in
the loop.

## E. Owner questions for round 3 (recommended first)

1. **Position sequences siblings; equal positions run concurrently; `needs` only crosses trees.** Alternative:
   siblings always concurrent (the old frontier) and every sequence an explicit `needs`.
2. **Intent on the task (`:seon.task/execution`), cluster `:seon.cluster/execution` only `:running`/`:paused`.**
   Alternative: intent on the agent as well.
3. **`:stopped` releases the SCI context and keeps agent, branch, assignment**; `:running` re-hosts from the row.
   Alternative: fold `:stopped` into `:paused` and accept the heap.
4. **The scheduler is root's `:work` task with invariant proofs; model turns only on the anomaly predicate.**
   Alternative: a separate long-lived scheduler agent.
5. **Heartbeat at 30 s, one datom per period plus changes, unknown after two periods.** Alternative: in-memory
   heartbeat, only changes written.
6. **`eval_clj` keeps hosting ad-hoc agents as root's act through the same `host!`** (recorded as an agent with a
   `:running` work task). Alternative: `eval_clj` writes the task and awaits the reconciler's hosting.
7. **Maintenance firings become tasks on the one path (a timer proc only writes the fire fact); the heartbeat stays a
   proc.** Alternative: keep `seon.schedule/schedule-step` per agent as the second path.

Not verified (probes before building): `host!` from the armer's proc thread; the ready derivation's cost on the live
store with 705 tasks; `fork!` from an arbitrary historical commit for t-resume; `flow/pause` then join as "finish the
current pass" against the turn-completion bound.
