---
title: "The runtime follows the database: task FSM, one reconciler, Flow pause/resume, boot and t-basis resume"
type: research
status: Fable pass for owner discussion; not agreed
created: 2026-10-11
basis: HEAD a8c2c170a, read by symbol; one read-only JVM probe on default (11 ms, 2.8 MB) for the roster and ping
---

# Runtime follows DB

The owner's brief (2026-10-11): one task per step, linked by ref; a database listener that detects state changes and
allocates or deallocates Flow graphs (start, pause, resume, stop); after a crash, see what is running, start what is
not, and let every other state follow the database as the source of truth; a resume option at restart; scheduling
elsewhere so tasks run independently; paused by default so a debugging session can query everything without firing
missiles; resume to any `t`. This note states that model precisely, checks it against the Opus proposal
([task-graph-design](task-graph-design.md)), [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md), plan §0 and the
code, and proposes the smallest composition that keeps process control in Flow.

What the probe showed (default, 2026-10-11): one cluster graph (`seon.agent/armer` and `seon.render.web/render`, both
`:running` under `flow/ping`), one armed agent graph (`root`), one child roster for the hosted branch agent
`item3-interp` on `:agent-9027404abfc7` (its graph lives in the child roster, not the cluster's `:seon.agent/armed`),
0 assignments, 705 tasks; listened attributes `:seon.message/to`, `:seon.effect/to`, `:seon.schedule.fire/agent`;
arming attributes `:seon.agent/id`, `:seon.schedule.task/owner`.

## A. The owner's model, stated precisely

Two different things are called "state", and the design hinges on keeping them apart:

| | Progress of a task | Execution of the runtime |
|---|---|---|
| What it answers | where this work is: ready, assigned, ended how | whether anything may run right now |
| Nature | a consequence of facts written once by their producers | an **intent** nobody can derive (the owner wants it paused) |
| Stored? | **derived**, never a field (AGENTS.md "Derive work and completion"; `seon.plan/derived-frontier` reused) | **stored**, one fact per scope, written by whoever controls execution |
| Actual counterpart | none: the facts are the truth | the roster of live graphs and `flow/ping` (`:paused`/`:running`) |

### A1. The task FSM (derived from facts written once)

| State | Derivation over main (all comparable by `t`) | Who writes the fact that moves it | Observed or stored |
|---|---|---|---|
| created | the task exists; `:seon.task/after` (card-many ref, asserted at creation) names its predecessors; `:seon.task/of` its subject | the creator: root, an agent's `my.task/refine!`/`create!`, a mined query, a schedule firing's template | stored facts, immutable |
| blocked | some task in `after` is not done | nobody: follows from the predecessor | derived |
| ready | not done, no assignment with open admission, every `after` done (no `after` = ready at creation) | nobody | derived |
| assigned | an assignment row exists (`seon.task/assign!`, identity `seon.id/id [handle agent branch]`) and its agent's `:seon.agent/admission-closed-tx` is absent | the reconciler's launch (C) | stored once |
| running / paused | assigned **and** the execution intent of its scope (A2) | the operator or root | intent stored; actual observed |
| ended `:done` `:given-back` `:capped` `:stalled` `:sent-back` | `seon.turn/task-end` derived by the agent's own pass, recorded by `end-task-call` (end message + admission closed, one transaction); `:sent-back` carries `:seon.task/target` | the agent's pass | stored once per assignment |
| done(T) | some assignment of T ended `:done` and no later `:sent-back` targets T | nobody | derived |
| closed | the subject's last step done, or a merge request closed by `seon.task/close!` | root | derived / stored once |

No field moves. A send-back makes a done task not-done by a later fact, so its successors stop being ready and it
becomes ready again for a fresh attempt. There is no `:seon.task/stage` that changes, no "launched" flag, no running
flag written by the runtime.

### A2. The execution intent, the reconciler, the roster

- **Intent**: `:seon.cluster/execution` ∈ `#{:running :paused}` on the cluster entity, and an optional per-agent
  override `:seon.agent/execution`. Paused wins. Both are `:seon.wake/arms true`, so a change wakes the reconciler.
- **Actual**: the roster. Process scope `seon.operator.runtime/running-instances`; execution scope the one atom
  `seon.cluster.agent/routing` (`:seon.agent/armed`, `:seon.agent/channels`, `:seon.agent/contexts`,
  `:seon.agent/children` per hosted branch, `:seon.flow/graph`). Each armed entry holds its graph and executor, so
  `flow/ping` answers `::flow/status` and the proc's `ping-map-fn` state per agent. Today status must walk children to
  see branch agents; the graph view (C) is one function over the roster that does.
- **Reconciler** (the owner's React): one derivation `desired(db) = {agent → :running | :paused}` over assignments
  with open admission and the intents, one observation `actual = roster + ping`, and the diff applied with Flow's own
  verbs: absent → `arm!` (`flow/start`, which leaves every proc `:paused` by construction, then `flow/resume` only when
  desired is `:running`); `:running` desired, `:paused` actual → `flow/resume`; the reverse → `flow/pause`;
  admission closed → `disarm!` (`flow/stop`, interrupt, join). Idempotent by identity: running it twice changes
  nothing. It is woken by the datom router on arming attributes and primes itself at its own `::flow/resume`, which is
  exactly what `seon.cluster.agent/armer-step` already does for "unarmed agents with work".

### A3. Boot modes and t-basis resume

| Mode | What happens | Guarantee | Limit |
|---|---|---|---|
| `start` (resume) | `recover-runs!` closes interrupted turns (facts only), then the reconciler's prime arms every agent with an open assignment and resumes those whose intent is `:running`; a hosted branch agent is re-hosted from its assignment's branch | the work stays due; nothing is replayed | an effect a crashed pass began but did not record repeats unless its owner is idempotent (AGENTS.md "Recover facts, not execution") |
| `start --paused` (debug) | the same prime, but the cluster intent is set `:paused` first: graphs exist and are pingable, no transform runs, no proof runs, no model call | every fact queryable, nothing fires | the schedule procs pause too (whole graph), so maintenance waits |
| resume to `t` | fork a branch from the commit at `t` (`seon.cluster.registry/fork!` from a commit id; `d/as-of` alone is read-only), set its intent `:paused`, point a cluster handle at it and let the reconciler arm against it | the facts at `t`: tasks, assignments, messages, program entities on that branch | live results (`seon.cache`), SCI private state, provider replies, git commits, written files are gone or not undone; the JVM's compiled Vars are HEAD's, so only what the branch interprets from source is at `t` |

"If it says running and it is not running, switch it to paused" is unnecessary once nothing writes actual state to the
database: after a crash desired still says `:running`, actual is empty, and the diff arms and resumes (or arms and
leaves paused under `--paused`). A stored "running" would be a mirror that drifts (AGENTS.md "Decide at the authority").

## B. Where the design and the code differ from this model; ambiguities

| Where | Says | Differs from A | Needs |
|---|---|---|---|
| TASK-CHAIN §2, plan §0 item 6 | one evolving task; `:seon.task/stage` moved by the transition; `:seon.task/basis-commit` on the task | the owner chose one task per step; a mutable stage is the state field A1 avoids; `refine!` of the held task breaks its own assignment (`:revised-since-assignment`) | revise §2, §6 items 2–5 and plan item 6 in place to `:seon.task/after`, `:seon.task/of`, stage type set once |
| task-graph-design §3c | the armer launches: fork, host, write the assignment | agrees with A2; but "launch calls `assign!`" runs the proofs on the armer's thread for up to the step dial, blocking every other arm | owner answer E3; recommendation: first pass |
| `seon.task/assign!` | runs the proofs in the agent's world **before** writing the row; refuses `:proofs-green` | the owner wants proofs to run "in the agent's SCI env on the branch they were given … if valid and not paused"; under `--paused` nothing may run, so assignment cannot depend on a run | split: the row is written by the launch; the red-first run is the agent's first pass (`seon.turn/step`'s `test-step`), green → `:done` with zero calls |
| `seon.turn/next-agent-work` | an `:open` turn needs a held assignment **and** an unanswered wake; a fresh assignment has none, so `seon.test-support/start-run!` sends "Work your task." | a never-answered assignment is work | one clause: a held assignment with no answering turn since its `t` is work |
| `seon.cluster.agent/armer-step` | arms agents whose `:seon.agent/branch` is its own execution's branch; branch agents are hosted on demand by `seon.cluster.host/host!` from `eval_clj` | after a restart, an assigned branch agent is not re-hosted until someone evaluates as it; boot is not "resume" for them | the reconciler hosts from assignments (branch on the row), not from who last evaluated |
| `seon.cluster/arm-agents!`, boot | always `flow/resume`; no paused boot | A3 needs a cluster intent read before the prime and a `start --paused` | one dial/flag and one `when` around `flow/resume` in `seon.flow/start-graph!` |
| `seon.turn/end-task!` | proposal → land (dial) → `outcome-tx` → `end-task-call`: four writes, two connections | Astra §8.2 already noted it is not one transaction; A1 needs only that the **end fact** is one write (it is) | leave; record `:sent-back` and its target in `end-task-call`'s transaction |
| `seon.task/resolution` | closed-by faults, signal, merge outcome | no done(T) clause for work tasks | the fourth clause from task-graph-design §3b |
| vocabulary | "running/paused/stopped" words undeclared; `:seon.schedule.task/*` is a second "task" | Flow's words are `:paused`, `:running`, `:exit` | record execution intent words; rename schedule tasks (decision 4 of the Opus note) |

Ambiguities only the owner can settle: the scope of pause (cluster, agent, task); the default boot mode; whether a
stored "actual" is wanted anyway as an audit (recommended no); whether t-resume means a read-only view or a forked
branch one can resume (recommended fork); whether maintenance schedules pause with the cluster.

## C. The simplest design that keeps process control in Flow

What Flow gives for free, verified in `clojure.core.async.flow.impl`: procs start `:paused`; `pause`/`resume` and
`pause-proc`/`resume-proc` are control-channel commands with priority over inputs; `ping` returns `::flow/status`,
`::flow/count` and the step's `ping-map-fn` state; `inject` puts on any `[pid cid]`; the transition arity receives
`::flow/resume`, `::flow/pause`, `::flow/stop` (there is no `::flow/start` call despite the guide); `create-flow` is
pure data; `stop` is a command, not a join (Seon joins the executor, `seon.flow/join-graph!`); report and error
channels are `sliding-buffer 100`, so nothing durable may ride them; a graph's proc set is fixed at creation, so
adding an agent to a shared graph means stopping and rebuilding it.

| Option | Shape | Cost | Guarantee | Sacrifice |
|---|---|---|---|---|
| **1. The armer becomes the reconciler** (recommended) | the cluster graph's armer proc, woken by the router on arming attributes (`:seon.agent/id`, `:seon.schedule.task/owner`, plus `:seon.task/after`, the assignment's `:seon.task/end`, the two intents) and primed at `::flow/resume`; one transform: desired − actual → `arm!`/`host!`/`flow/pause`/`flow/resume`/`disarm!`, then launch ready tasks (fork, host, assignment row) | one query per wake proportional to the ended task's successors and the open assignments; launch = one fork (a pointer) + one host (today's acquisition) + one row | boot, crash recovery and a live change are the same pass; idempotent by identity; paused means no transform runs anywhere | the armer transform does hosting (seconds today) on the cluster graph's io thread; keep proofs out of it |
| 2. One graph per cluster, one proc per agent | every agent a proc in the cluster graph; `pause-proc`/`resume-proc` per agent | rebuild the whole graph on every launch and end (fixed proc set): stop and join every agent to add one | one `ping` shows everything | an in-flight turn interrupted by a neighbour's launch; no per-agent executor to join; contradicts "topology changes rebuild graphs" cheaply |
| 3. A separate reconciler proc or namespace beside the armer | a new proc that pauses/resumes, the armer still arms | a second derivation of the same facts | none beyond 1 | two procs deciding one roster: the dual-writer problem the roster was built to avoid |

Recommended: **option 1**, per-agent graphs as today (`seon.cluster.agent/graph-definition`: mailbox → turn, schedule),
the cluster graph (armer, render) as the reconciler's host, the datom router as its sensor. Pause and resume are
`flow/pause`/`flow/resume` on each agent's graph, so a paused agent's schedule proc pauses too and its `d/listen` is
removed by its own `::flow/pause` transition (`seon.schedule/schedule-step` already does this).

How agents communicate, and what is durable:

| Channel | Durable | Use |
|---|---|---|
| database messages (`seon.cluster.message/send!` on the recipient's branch) + listened attributes + `route!` wakes | yes: the message is a fact; the wake is "look"; answeredness is derived from `t` (`seon.turn/unanswered-wakes`) | all agent-to-agent and root communication, the task handoff (`:seon.task/of`), the end message |
| Flow connections (`mailbox → turn` sliding-1), `inject`, `::flow/cast` | no: sliding buffers, lost at stop | inside one agent's graph only; an operator's poke is `inject` of a payload-free wake |
| Flow `report`/`error` chans | no (sliding 100) | observation; Seon's fault committer tags and records errors |

What disappears or collapses into the reconciler:

| Today | Becomes |
|---|---|
| `seon.test-support/start-run!`, `own-branch-agent!`'s setup eval, `outside-wake-on-branch!`'s "Work your task." | the bench writes tasks with `after`; the reconciler launches; a held unanswered assignment is work |
| `my.root/launch!` (planned, plan §0 item 5) | `my.task/create!` with `after`; nothing launches by hand |
| hosting on demand from `eval_clj` as the only path to a branch agent after boot | the reconciler hosts from assignments; `eval_clj` keeps hosting for ad-hoc agents |
| `seon.task/assign!`'s proof run before the row | the first pass's `test-step`; `assign!` shrinks to the row |
| TASK-CHAIN's `advance-end!`, `:seon.config.chain/advance?`, mutable stage, `my.root/launch!` basis handling | not built: readiness derived; the basis is the predecessor assignment's recorded commit |
| boot's "arm and resume" | the same prime under the intent; `--paused` is one fact written before it |

Stays: the per-agent graph and executor (needed to interrupt and join one agent), the wake router, the schedule proc,
`end-task!` (adds `:sent-back`), the turn-completion bound (a bound, not a fence: "Bound admission and await actual
exit"). Cost of a pass: successors of the ended task × their `after` count (reverse `:avet`), plus open assignments,
never all 705 tasks.

### C1. The governing principle, applied to every channel in the code

Owner (2026-10-11, during this pass): Flow manages the literal process and computation; channels carry only
ephemeral data we do not care about; anything of consequence is a database fact, so nothing is lost and restore is a
derivation. Every channel use today, classified:

| Channel (creator) | Carries | Buffer | Verdict |
|---|---|---|---|
| armer wake `:seon.cluster.wake/channel` (`seon.cluster/arm-agents!`, `host/start!`) | payload-free "look" | sliding 1 | conforms: rederived from facts at the next pass or prime |
| mailbox in-port + `mailbox → turn` episode (`graph-definition`) | payload-free wake | sliding 1 | conforms: the message is the fact (`:seon.message/to`); answeredness derived from `t` |
| schedule kick `:seon.schedule/channel` (`arm-graph!`) | payload-free | sliding 1 | conforms: due work derived from `:seon.schedule.fire/*` facts and the clock |
| render wake, runtime-eval, pages, per-tab taps (`arm-agents!`, `seon.render.web`) | "re-render", page deltas | sliding 1 | conforms: a page is derived from the database; a lost delta is the next one |
| agent request port `:seon.agent/requests` + reply promise (`submit-source!`) | **a source submission's text** and its reply | 1 / promise | borderline: the text is the caller's value, awaited under a bound; a stop closes the reply (`:seon.await/closed-error`) and the caller retries. Conforms as long as the run is recorded by the pass's one transaction before the reply; the channel is transport, not the record |
| completion permit `:seon.turn.loop/completion`, stream channel, quiesce, `graph-ref`, `feeds-exited` | synchronization tokens | 1 / promise | conforms: process control, no data |
| Flow `::flow/error` → `seon.flow/fault-port` | a proc's Throwable report | **synchronous**: `>!!` runs the recorder on the reporting thread, no buffer | conforms by replacement: Seon already refused Flow's sliding error channel for consequential data; the fault becomes a stored fact before the put returns |
| Flow `::flow/report`, `ping` replies | pass reports, status | sliding 100 / per call | conforms: observation only; nothing reads reports as truth |
| work launcher `::completion` (`start-work-launcher!`) | io work completions | `io-parallelism` | conforms: the work's result is recorded by its owner; the channel sequences threads |

Nothing consequential rides a channel today except the submission text, and that is a request awaiting its recorded
answer, not state. The additions in A and C keep the rule: intent, assignment, `after`, end and `:sent-back` are
facts; the reconciler's wake is payload-free; a pause or resume is a control command whose truth is the intent fact,
re-derived at the next prime.

## D. Where scheduling lives

Scheduling is writing facts the reconciler reads: a task with `after` (when), an assignment (who, where), an intent
(whether). Nobody calls start.

| Option | Who writes | Cost | Guarantee | Sacrifice |
|---|---|---|---|---|
| **1. Creators write `after`; the reconciler launches every ready task under one admission dial** (recommended) | root and agents (`my.task/create!`, `refine!`), mined queries, a schedule firing that instantiates a template | one dial `:seon.config.agent/max-running` read at each pass; ready tasks sorted by creation `t` | tasks run independently; the only policy is a bound every execution surface declares anyway | no priorities beyond order of creation |
| 2. A scheduler agent | a root-side agent writes assignments (or a `:seon.task/priority`) from its own task | a model call or query per decision | arbitrary policy as data | a model in the loop of every launch; a second owner of assignments |
| 3. Flow topology as the schedule | successors wired as procs/conns | rebuilds per chain | none beyond 1 | not durable; contradicts C |

Later, a `:seon.task/priority` or `:seon.task/not-before` fact extends option 1 without a new owner. Pause is the
owner's hand on the whole thing: `:seon.cluster/execution :paused` stops every launch and transform without touching a
task.

## E. Owner questions (recommended option first)

1. **Progress derived, intent stored?** Recommended: yes, as A1/A2: no `:seon.task/stage` or running flag; one
   `:seon.cluster/execution` intent plus an optional `:seon.agent/execution`. Alternative: a stored task run-state the
   runtime writes, accepting drift and a reconcile rule.
2. **Scope of pause.** Recommended: cluster and agent (Flow pauses a whole agent graph). Alternative: per task (would
   mean pausing the agent anyway).
3. **Where the red-first proofs run.** Recommended: the agent's first pass on its branch, inside its graph, so pause
   holds them and a green task ends with zero calls; `assign!` only writes the row. Alternative: keep `assign!`'s
   pre-run on the reconciler's thread.
4. **Default boot mode.** Recommended: `start` resumes (ordinary operation, "the flows just continue");
   `start --paused` and every t-resume boot paused. Alternative: paused by default always, resume by hand.
5. **t-basis resume.** Recommended: fork a branch from the commit at `t`, paused, and reconcile it (facts recoverable;
   live values and external effects stated unrecoverable). Alternative: `d/as-of` read-only, no runtime.
6. **Store actual state at all?** Recommended: no; actual is the roster and `flow/ping`, shown by one status function
   that walks child rosters. Alternative: a heartbeat fact per agent for audit.
7. **Do maintenance schedules pause with the cluster?** Recommended: yes (whole-graph pause); a maintenance firing is
   not agent work (Opus decision 4) but it still fires missiles.
8. **Admission bound.** Recommended: one dial `:seon.config.agent/max-running`, ready tasks by creation order.
   Alternative: launch everything ready.
9. **Revise TASK-CHAIN §2/§6 and plan §0 item 6 in place now** to `after`/`of`/stage-type-set-once, and record the
   execution words in the vocabulary? Recommended: yes, before item 6's lane starts.

Not verified (probes before building): `host!` from the armer's proc thread for a new branch; the cost of the ready
query on the live store; `fork!` from an arbitrary historical commit id for t-resume.
