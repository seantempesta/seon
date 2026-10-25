# Design: the agent loop: sessions that park, wake on a message, and are loaded from any commit (2026-10-25, revision 2: the owner's rulings of 2026-10-25 folded in)

THE PLAN steps it serves: 1 (an agent's context is right, so it finishes in few turns) and E (turns to green): an agent stops losing its conversation at every stop, and a user's reply costs one wake (7 to 35 ms) instead of a fork, a resumed branch and a blank conversation. It builds on `jolt/agent-conversation` (design `notes/design-agent-view-2026-10-24.md`, revision 3) once that is merged to main. Nothing here is built. It carries out the 2026-10-25 rulings in `docs/decisions.md`: every agent parks; resume replays pure forms and asks before replaying effects; a session's context is generated from the db and continues across runs; the screen is sent as diffs; the lifecycle is Flow's; correctness and performance come before lines.

Evidence: root's scratchpad (`flow-sources.md`, `flow-lab.md` with lab code on `jolt/flow-lab`, `jolt-park-resume.md`, `messaging.md`), and the memory measurements in section 2, made for this note on this worktree's root (ports 8140/8141, no model calls).

## 0. What is wrong today, in one paragraph
A **run** is five things at once: the process's lifetime (spawn to SIGKILL), the unit the judge judges, the turn budget, the scope of the conversation (`seon.context/records` filters evals by `(:run record)` and messages by `:seon.message/to` the run, context.clj:391-405), and the address of a message. So every stop is a death. `:waiting` is judged, held as a *failure* (`hold-tx`, reconcile.clj:443) and killed (`:diffed`, reconcile.clj:693). A message to a stopped task is refused (`gone`, root.clj:371) or, from an agent, silently dropped (`to-agent`, root.clj:226). `my.root/run!` starts a new process with a blank conversation (flow-lab section 3: turns of the earlier run are on the branch, unread). Turn ids restart at 0 and eval handles restart at `e0` in each process (eval-n!, repl.clj:310-318), so a second run collides with the first.

## 1. Session, run, turn

| Word | What it is | Its record | Bounded by |
|---|---|---|---|
| **agent** | one session's identity: the agent of a task's session (a root's included: a root is a task on main), or the user | `:seon.agent/key`, generated at the session's first load like any agent's and stable from then on, no longer per boot | nothing: it lives as long as its session |
| **session** | the agent's conversation: every record of the agent on its branch, in tx order | none of its own: a query at a commit | the history budget (`:seon.config.render/history-budget`), never a turn count |
| **run** | one agentic loop: a wake to a park (or to green). The owner's word (2026-10-23) | `:seon.run/*` on main, written by the reconciler, as today | `:max-turns` per run: a budget per wake |
| **turn** | one model call and its evals | `:seon.turn/*` on the branch | |
| **process** | the forked OS process holding the session live | root's view (`:seon.agent/status`, `pid`, `mem-mb`) | memory admission (section 2) |

- **A run stays, with one job:** one loop between a wake and a park. It is still what the reconciler starts and what records the loop's spend (turns, tokens, outcome). It stops being the process lifetime (the process outlives runs), the judge's unit for a stop (only a green end is judged), the conversation's scope and a message's address. The conversation is the session's.
- **Turn budget decoupled:** `:max-turns` bounds one run. A run that reaches it parks with outcome `:cap`, and the next message starts a run with a fresh budget, on the same conversation. What bounds a session's cost is the history budget (the context's size) and the owner's money budget (`my.fx`'s `:budget`), never a turn count.
- **Ids are session-wide.** Turn `<agent-key>/<n>` and eval handle `result/e<n>`, n counting on from the session's last record on the branch. A process reads the counters once at init: one reverse seek on `:seon.eval/agent` and `:seon.turn/agent`, O(log n). The handle's identity is `<agent-key>/e<n>`, so ids from two processes of one session never collide.
- **The conversation at commit C** is `(seon.context/request agent {:db (d/commit-as-db conn C)})`: the agent's records on the branch at C, printed by their `:seon.render/ai` renders, under the history budget. It is immutable except for the screen evals (section 5) and the eval running now. `records` drops the run filter and queries by agent (`[?e :seon.eval/agent ?a]`, `[?m :seon.message/to ?a]`), and adds the run ends as records (`:seon.end/*`, already on the branch), so the model reads "run 3 parked: out of turns (30)" where it happened.
- **The task story across runs:** the story the session first sent stays the cached prefix. A change to the task since then is a screen section (section 5), sent as a diff, so the prefix never breaks.
- **What ends a run, and what follows:**

| Outcome | Today | New |
|---|---|---|
| `:green` (the done rule refuses nothing) | judge; accept, or hold as a failure; kill | judge as today (the agent's tests, the branch diff, `judge-tx`). Accepted: the stage is done; the next stage's message wakes the agent (section 1a); after the last stage its process ends (the only kill). Refused: the task is held (DECISIONS: no automatic retries) and the agent **parks**; the reason goes to the user's timeline |
| `:waiting` (a prose-only reply, `my.turn/wait`) | judged, held as a failure, killed | **park**: no judge, no hold, no kill; the task stays `:running` |
| `:cap`, `:no-progress`, `:error` (a provider error) | held as a failure, killed | **park**, its reason on the run; the next message wakes it with a fresh budget |
| `:failed` (`my.task/fail!`), `:merge-failed` | held, killed | the failure carried and the task held, as today (`judge-tx`); the agent **parks** |
| the process died | run `:dead`, held, killed | run `:dead`; the session has no process; the next message loads it at its head (section 4) |
| root restarted mid-run | `:lost`, re-run blank | `:lost`; the session is loaded at its head and its next run's first record says what was interrupted (root's `last-op`) |

### 1a. A request's stages are runs of one session (owner's ruling, 2026-10-25)
DECISIONS: "A request's stages are separate steps in one session". Design, implement and merge stay three tasks, each with its own instructions and its own judge checks, and they are worked as runs of **one session**: the next stage continues the same conversation, on the same branch, in the same process when it is parked.
- **What exists:** `seon.template/request` already writes the three linked tasks in one transaction: `<name>` (`:design`), `<name>-implement` needing it, and `<name>-merge` needing that, all on the design task's branch. `seon.task/accumulate` already carries the proofs along `:needs`, and `judge-tx` already judges by `:kind` (`:design` is checked for a plan and red proofs, reconcile.clj:47-49). None of this changes.
- **How a stage joins the session:** through `:needs` and the record, with no new attribute (owner, 2026-10-25). The session is a task's **same-branch `:needs` chain**: for a request, its three stages; for a plain work task, the task and its merge; for a task alone, the task itself. `merge-keys` already walks this chain (reconcile.clj:302-311). The session's agent is the `:seon.run/agent` of the chain's runs, an existing ref. The first load of a chain with no run generates the key, and every later run of any stage in the chain takes the same agent. Several roots on main are separate sessions, since no `:needs` links their tasks.
- **The session's current stage** is the first task of the chain that is not done and whose `:needs` are done. A run records it as `:seon.run/task` (an existing attribute), so every run belongs to exactly one stage.
- **How a stage's instructions arrive:** a stage is accepted when its green end is judged and accepted. The reconciler's judge transaction then also writes one message, a fact on main from the next stage's task id to the session's agent, carrying the next stage's `:seon.task/instructions` and its plan as rendered by its `:seon.render/ai`. That message is the stage's start. The ready rule of section 2 (messages > `taken`) wakes the parked process with no new path: one starter, one wake. The model reads the stage change where it happened: the earlier stages' records stay in the cached prefix, and the screen's task section (section 5) shows the new stage as a diff.
- **The process ends only when the session has no stage left:** when the last stage is accepted, or the request is cancelled. The first row of the outcome table reads "accepted: the stage is done; the next stage's message wakes the agent; after the last stage the process ends".
- **How the judge checks each stage:** the run's task is the stage, so `judge-tx` takes the run's `:seon.run/task` kind as today:
  - **design:** a plan and red proofs;
  - **implement:** the accumulated proofs are green, and the branch diff is caused by this session;
  - **merge:** merged, with main's checks green.

  A stage that is refused holds that stage's task alone (DECISIONS: no automatic retries). The agent parks, and the reason goes to the user's timeline. The user's reply releases the stage and wakes the same session in the same stage. Earlier stages stay done.
- **Hazards:**
  - **A stage message written twice:** it can't be. It is part of the one judge transaction for that run, and a run is judged once.
  - **Two stages running:** they can't, because the next stage's `:needs` hold it until the current one is done, and section 2 starts no run while a run of the session is `:running`.
  - **A merge run in root's gate checkout:** the merge stage's git effects already go through `:fx :git` on the session's branch (reconcile.clj:745). What the agent does in the merge stage, resolving conflicts and running proofs, happens in its own process as in the other stages.
  - **The judge's proofs for implement:** they are design's red proofs, read through `accumulate` as today.
- **Work done:** at a stage change, one more message fact in the judge's transaction, and a wake (7 to 35 ms measured) instead of a fork plus a blank conversation. The turns implement no longer spends re-reading what design learned are estimated, not measured: that is the pilot's first number.
- **Proofs:**
  - a scripted request runs design → implement → merge in one process: one agent key, turn ids continuing, implement's first request carrying design's records as its prefix;
  - each stage's run carries its own stage as its task, and is judged by that stage's kind;
  - a refused implement stage holds only implement, and a reply from the user wakes the same session.
- **The pilot** (owner, 2026-10-25): **"customizable errors"** is the first request run through the pipeline once parts 2-4 of section 7 are built: v1 let errors be customized, and next does not yet. It is small, so it exercises all three stages and their judges on real work. It is a paid run, so it needs root's go. Its numbers: turns to green per stage, and implement's turns against an estimate for a blank-context implement.

## 2. Parking and waking on Flow

**Where an agent parks.** Its process's loop is `seon.step/run-loop` over `seon.repl/agent-step` (repl.clj:439-472). `seon.turn/work!` is one `:eval` request in that loop. When work! returns, the loop is back in `recv!` (seon.sock/recv!: read(2) on the socket): measured 1 thread and 0.0% CPU. In root, the agent's proc is an `:io` fiber parked in Flow's running loop (`alts!!` over `:in` and `:from`, flow/impl.clj:471): 0 OS threads, no step running. **So parking needs no new mechanism: it is work! returning.** Flow's `::flow/pause` is not parking (a paused proc takes control messages only and would push a message back on its sender); parking is Flow's idle running state.

**Where it wakes. One starter, the reconciler.** A run is started only by the reconciler, which also writes it on main, so no second path starts loops (the lab's agent-proc wake request was a second starter: hazards 3 and 4 there). A task's session is **ready for a run** when:
```
task :running, not done, its :needs done, no run of it :running, and one of:
  - it has had no run;
  - (messages to its agent on main) > (:seon.run/taken of its last run);
  - its last run is :lost.
```
`:seon.run/taken` is how many message facts the session had taken when the run parked, which the agent reports with its end (the count of `:seon.message/to` it on its branch). It is the one new attribute: the reconciler's index holds main, where the messages are, and the taken copies are on the branch, which it reads only at a run's end, so recording the count then keeps the ready rule a comparison on main. Both sides are counts of immutable facts, so the comparison closes the lost wake without timing: a message written before the park record and delivered to a process that had already decided to park is in the count and not in `taken`, so the reconciler wakes the session again at once. The reconciler's index keeps the per-agent message count from each transaction's message datoms: O(datoms in the tx).

The action for a ready session:
- process live (parked) on the task's branch: `:wake`, which is today's `:started` step: `{:op :eval :forms (seon.turn/work! ...)}`, with no fork;
- no process, or a process on a branch the task no longer names (a rewind): `:load` of the session at the task's branch head (section 4: the one loader, for new tasks too), then the same `:started` step.

Admission: `:seon.config.agent/max-running` (4) counts **runs running**, not processes alive. Parked processes are bounded by memory (below).

**Messages, back to back and during an eval.**
- A message is a fact on main first, then delivered to the live process's input (`deliver-message`, root.clj:229), and lands in the inbox wherever the agent reads its socket, even mid-eval (SIGIO, measured: `{:inbox-during 1}` during a 4 s eval).
- The running turn's next `basis!` takes every message in the inbox at once (one write of their facts on the branch, turn.clj:157).
- Before work! returns to park, it reads the inbox once more (`agent/inbox` drains the socket without waiting); if anything is there, the loop runs another turn instead of parking, budget permitting. This is the agent's own state, read in its one thread, not a guard; the count above covers what arrives after this read.
- No double turns: one process is one thread; the reconciler starts no run for a task with a run `:running`; a wake is an eval request in the process's FIFO.
- Measured on the lab prototype: wake 7 to 35 ms from the message's write to work!'s start; first turn committed about 200 ms later (scripted model); three back-to-back messages gave two wakes, no message lost or taken twice, turn ids continuing.

**Memory on park: measured for this note.** Same agent work in each case: 400k rows built, 40k kept live (`notes/agent-loop/mem-measure.clj`, this worktree's root, ports 8140/8141):

| | fresh agent | after the work | then one full collect (`heap-reserve-ratio` 0) | collect time |
|---|---|---|---|---|
| today's fork server | 76 MB | 333 MB | 280 MB (275 MB with the live data dropped) | 62-75 ms |
| a full collect on a fresh agent (a2) | 161 MB | | **259 MB, a rise of 98 MB** | 84 ms |
| fork server made static first (`lib/static!`, 77 ms, once) | 70 MB | **106 MB** | 107 MB | **2.8 ms** |

What this shows:
- **Release by collecting on park does not work.** At fork, 163 MB of the fork server's heap sits in generations 3 and 4, not in the static generation. An agent's collection of generation 4 copies those shared objects into private pages: the copy-on-write sharing is lost. One collect gave back only 45-58 MB of a 257 MB growth, and on a fresh agent it raised the footprint by 98 MB. (The lab's "~300 MB after a run" is this.)
- **The fix is at its cause, in the fork server**: make its heap static before forking (`lib/static!` exists, fork_server.clj:101, called today only at boot, root.clj:804). Then a child's collections never trace the inherited heap: the same work leaves 106 MB (3.1x less), and a full collect costs 2.8 ms instead of 75.
- So the design is: the fork server runs `static!` at the first fork after main's heap has grown since its last static (an event: an `:applied` that loaded or transacted, never a timer), and an agent runs one full collect when it parks (cheap now: ~3 ms, and it no longer un-shares).
- **Measured capacity, 12 agents** (the same work in each, the fork server made static once after the first fork, 31 ms; then each agent's work and one full collect):
  - each agent was 54-60 MB fresh and 101-102 MB parked afterwards;
  - `top` showed every parked agent at 0.0% CPU and 1 thread;
  - root was 1,016 MB, the fork server 159 MB (unchanged by a second static, 6 ms), its standby 28 MB;
  - so (4096 - 1016 - 159 - 28) / 102 is about **28 parked worked agents** within `memory-mb`.
  - Not measured: a real turn loop's live data per agent (this work keeps about 11 MB live), and the fork server's growth over many statics with main moving.
- Earlier estimate, for comparison: in `:seon.config.machine/memory-mb` (4096): about 2.5 GB is left after root and the fork server, which holds about 8 parked agents at today's 300 MB and about 24 at 106 MB.
- Hazard: static objects are never freed, so the fork server keeps every main db value it held at a static. Its growth per static is unmeasured (build part 1). The bound is the existing standby fork server (`promote`, fork_server.clj:221): when the fork server's footprint passes its share, the standby takes over and the old one exits. That may need a config key (owner's gate, section 6).
- Found in passing, filed rather than fixed: Chez reports `collect-trip-bytes` 172,842,656 in an agent, while `:seon.config.agent/trip-bytes` is 4,194,304 (agent.clj:772 sets it; Jolt's policy rescales it, it seems). A follow-up for a `jolt/` branch.

**Eviction: off by default, one config key** (the owner did not choose, 2026-10-25). The default is strictly always park: every agent stays parked until its session has no stage left, and admission refuses a load that memory cannot hold (about 28 worked agents fit in `memory-mb` with the static fork server, measured above). `:seon.config.agent/evict?` (default false) switches on eviction by load. When a load would put the sum of the agents' reported `mem-mb` past their share of `memory-mb`, the reconciler stops the parked process woken least recently, judged by wake order (a counter, not a time). That is Flow's existing stop transition (agent_proc.clj:274: `{:op :stop}`, await death, SIGKILL past the bound). The session loses nothing: its next message loads it at its branch head through section 4's one loader, in about 0.4 s instead of 7-35 ms.

**The lifecycle is Flow's.** A proc is present exactly while the session has a process. The api's `flow/update!` adds it at spawn and removes it at the end, as today. Stop is the kill, as today (and eviction, when switched on, is a stop). The fork itself stays a request to the fork server's proc: Flow's init arity returns state and has no outputs, and the fork server is the only single-threaded process that can fork (lib.clj:149), so forking inside init would mean a step blocking on another proc (AGENTS §5). Nothing about the lifecycle needs Flow changes.

**Deleted:** the `:diffed` kill for every end (kept only for an accepted end); `hold-tx` for `:waiting`, `:cap` and `:no-progress` ("waiting is a failure" goes, so quiescence stops naming waiting tasks as root causes); `my.root/run!` and `my.root/release!` as the only way back (a message is the release, and the one loader starts, sections 3 and 4).

## 3. Messaging: one function, `my.agent/message!` (owner, 2026-10-25)

**One function.** Any agent, at its REPL, calls `(my.agent/message! to body)`, which answers `{:id mid}` or a typed error. This includes each root, since a root is an agent. Underneath is `seon.root`'s one api op, `:message`, and every sender goes through it:
- the user, through the page's input box and MCP `seon_message`;
- an agent, as one request frame answered exactly once;
- an effect's owner, for an async effect's outcome.

It does exactly two things, in order:
1. **Transact the fact on main:** `:seon.message/{id from to body}`. `to` is a ref to the recipient's agent entity: an existing attribute that refs a run today. `from` stays the sender's address, a string. The write is answered when committed in memory (group commit, AGENTS §5).
2. **Deliver:** if the recipient has a live process, parked or busy, the existing `deliver-message` puts the message on its proc's input, then in its inbox. If not, nothing more happens: the fact is in the reconciler's count, which loads the session (section 2).

**Addresses:**
- a task's id names its session's agent;
- an agent's key names that agent;
- `:user` names the user.

The one error is an address nothing resolves. A message to a finished task's agent is recorded and answered like any other, and nothing wakes, because the task is done (DECISIONS: done is permanent).

**No restrictions** (owner, 2026-10-25). A message from anyone to a held task's agent releases the task in the same transaction (`[:db/cas task :execution :held :running]`, today's `task/execution-tx`). That covers a hold from a refused judgment or from `my.task/fail!`, whether the message comes from the user, a root or another agent. The reply is the release, so the old question 3 (only the user or root releases) is gone.

**Replies use the same function.** A prose reply with no eval call is a `message!` to whoever sent the last message the run took (the user if no one did), and the run parks `:waiting`. The turn keeps `:seon.turn/reply`, the model's output, which reasoning replay needs. A prose-only turn points to its message instead of storing the body twice. `:seon.message/reply-to` stays declared and unwritten: nothing yet needs a thread link (the drop asked for in ruling 2).

**Every agent is an agent, root included** (owner, 2026-10-25: "All agents can be root. Root is just a name").
- **A root** is a task on main whose instructions say "you are root, running on main", worked by an agent whose key is generated like any other agent's. Several may run, each with its own task and its own session.
- **The user** is one agent entity, made with the store (not once per boot, as `task/boot-tx` makes it today). It has no task and no process, its inbox is a query (`:seon.message/_to`), and the page shows its timeline.
- **Gone:** the hardcoded `"root"` agent and every root special case (the one-system ruling, 2026-10-25).

**The seven mechanisms (messaging.md section 3), and what goes:**

| Today | Goes or stays |
|---|---|
| 1. owner `:message` (fact on main, refused when gone) | goes: `message!`; nothing is refused but an unknown address |
| 2. agent `:send` (no fact, dropped when gone) | goes: `message!`, recorded and answered |
| 3. `deliver-message`'s fact and no-fact entries | one entry, always a fact |
| 4. the inbox's three feeders (sends, owner, effect replies) | one feeder: message facts. An async effect's outcome (`fx-send!`, `:fx-reply`, agent.clj:204, 678) is a message from its kind's owner. The synchronous `fx!` answer is unchanged |
| 5. `basis!`'s two classes (`:message-ids` and `:messages`) and `taken-messages`' second branch | one class: ids |
| 6. the fact written twice (on main by root, on the branch by the turn) | stays: one content id, one entity once merged. The branch's copy is the "taken" record the count needs |
| 7. `my.turn/wait`, hold, and `release!` as the only wake | `wait` stays (it parks with no message); hold for waiting goes; `release!` goes (a message releases) |
| also | `seon.root/message!`, `seon.agent/send-message` and `my.agent/send-message` all become `my.agent/message!`; the web page's `>` special case (web.clj:89) goes |

**Cost:** one transaction (one fact, about 5 datoms), one frame per delivery, and one count update in the reconciler's index. A message from an agent waits for the in-memory commit, which should take milliseconds but is unmeasured on this path. The lab's 100 ms was the owner's path waiting for the writer's flush; build part 4 measures it.

**The help page section** (docs/agent.md, "Messages"):
```
## Messages
- `(my.agent/message! to body)` messages anyone: a task id, an agent's key, or :user. It answers
  {:id ..} once the message is recorded; the one error is an address nothing names.
- Messages reach you any time, as user messages {:from :body :id} in your conversation, at your
  next turn. Reply with `(my.agent/message! from text)`, or with prose and no eval: that goes to
  whoever wrote you last (the user if no one) and ends your run.
- Ending a run is not ending your work: you park, and the next message wakes you with your whole
  conversation and a fresh turn budget. Finishing is `(my.task/done!)`; giving up is `(my.task/fail! why)`.
  A message to a held task releases it, whoever sends it.
- To ask another agent and wait: `(my.agent/message! "ns/task" "...")`, then reply with prose; its answer wakes you.
- The owner follows your work live on the page. Show what you are trying there, and when the page
  does not show something you or the owner need, improve it (seon.render.ui): it is your interface too.
```
The last item carries out the ruling "instruct agents to think about and use the UI". The same sentence goes into the system page (`seon.context/system-page`) so that every agent reads it.

## 4. Loading a session at a point: starting and resuming are one mechanism (owner, 2026-10-25)

**One loader.** Starting an agent is loading its session at its branch's head. Resuming after a death, a lost root, an eviction or a rewind is loading the session at a commit. They are the same path, the one that exists today (`agent/init!`'s resumed path, agent.clj:763-793), made the only path:
- the reconciler's `:spawn` becomes `:load`;
- `my.root/run!`'s fresh fork goes, and the separate `my.root/resume!` is not built.

The reconciler stays the one starter: a user, a root or any agent changes the task's data, and the reconciler loads what the data asks for.

**A session at commit C** is the session's agent loaded on a branch whose head is C. Every way a session comes back is a case of that:

| Case | Branch and commit | What is loaded |
|---|---|---|
| a new task | its branch's head (main's image plus nothing) | no records, so nothing to replay |
| a dead or lost process; an eviction (if switched on) | its branch's head | its records to the head |
| a rewind | `(my.task/rewind! task C)` makes a branch at C (`d/branch!`, 1.4-3.7 ms, a pointer) and points the task's branch to it; the old branch stays as a record. The task's process is on the old branch, so the reconciler stops it and loads | its records to C |

Running two live copies of one session (the old design's `:fork`) is left out: rewinding covers loading any session from any point, and the old branch can itself be loaded again by rewinding to one of its commits.

**The steps of a load**, the same in every case:
1. **Fork, then load the program diff.** `program/load-diff!` from main's image to the branch at C. It is measured at 334-344 ms and dominates the cost. Its pending messages are those to the agent on main that are not yet on its branch: O(messages to it).
2. **Rebind the data results.** For each eval record up to C whose response value is data, `result/e<n>` is bound to the stored value. Any other value reads its record: its printed text, saying that it is not the live value. On main the record keeps `:value` when the value is plain data (`seon.db/plain?`), else `{:printed :type}`. The vars are made at load, before the first form is read, because the compiler resolves `result/eN` when it reads a form. The agent-view session measured 0.74M ticks and 5.3 MB for 1,033 records in memory. Values of 1 KB or more are stored out of line on LMDB and cost more to fetch; that is unmeasured, and build part 5 measures it with a cost proof that the rebind grows linearly in records.
3. **Replay, through one filter.** The session's evals up to C, in order, leaving out the screen's (`:seon.eval/by "screen"`) and the definitions `load-diff!` already loaded. Each eval is classed:
   - **pure:** it asked no effect (no `:seon.fx/*` record joined its recording transaction; this is exact, since the owners record every effect, agent.clj:163-172), sent no message, and called no edge function, judged by what it called (step 5) and by what it can reach in the compiler's reach graph (`program/reach-graph`; never regex over code). A pure eval is replayed;
   - **effectful:** any of those. It is never replayed by default (owner, 2026-10-25: "We don't autoplay the things we know are effects"). The agent and the user see it listed with its recorded ticks, and either can evaluate it again by hand;
   - **durable already:** its only effect was a write on its own branch. That effect is in the db at C, so the eval is never replayed, since a second transact would apply twice.

   The filter is one config key, `:seon.config.agent/replay`: `:pure` (the default) or `:all` (replay effects too; owner, 2026-10-25: "it's just a filter of the forms right?"). Under `:all`, durable-already evals are still skipped, because their effect is already in the db.
4. **Every replayed eval is marked as replayed.** A replay is an ordinary eval recorded with `:seon.eval/by "replay"`, an existing attribute and value space. The load itself is one eval, `:by "load"`, whose value is the plan: the commit, the evals replayed, those skipped as effects and those skipped as durable. The conversation renders the load as one record, for example "loaded at turn 20 (commit C): 15 forms replayed, 5 effects not replayed: e12 e14 ...". It renders each replay as one line beside its original eval, showing the new value only when it differs from the recorded one. So at every eval the agent sees whether it ran in this process or was replayed. This replaces the old `:seon.resume/*` fact: the load's eval record is that fact.
5. **Every eval records the functions it called.** `seon.profile/touched` already collects the contracted vars an eval touched, and today `profile/top` keeps only the costliest as the record's `:fns` (`:seon.config.agent/form-top-fns`, repl.clj:182-187). The record keeps the full set under `:fns`, and the top N becomes the view. That is a key inside the record's data map, not a new attribute (the old `:seon.eval/calls` is dropped). Its size per eval and its bytes in the store are unmeasured; build part 5 measures them first, and if the set is large the record keeps only the edge-function calls.
6. **Everything survives** (owner, 2026-10-25). The agent's namespace is named from its stable key (`make-ns!`, agent.clj:629, takes the key instead of the process id). Each replayed form runs in the namespace its record names. The program diff restores every definition, including the agent's own redefinition of `seon.screen/forms`. The process id (`:a3`) stays only as root's handle on a live process. No namespace attribute is needed (the old `:seon.agent/ns` is dropped).

Measured on the lab prototype: planning the replay took 2-12 ms, and replay took 9-26 ms at 2-30 evals (scripted forms; real forms cost what they cost). A whole load is 0.38-0.40 s whatever the session's length. A new task's load is today's spawn plus an empty diff.

**Hazards and how each closes:**
- **An effect hidden behind a helper:** it is classed by what the eval actually called and asked for (recorded), plus what it can reach (the compiler), never by the symbols in its text.
- **Host I/O that bypasses `my.fx`** (`spit`, `sh`, interop): the reach graph must name Jolt's host I/O and interop sites as edge functions. Unknown means effectful, never assumed pure.
- **A pure form reading a world that moved:** the branch db at C is the world it read. Time, files and main's head are read through edge functions, so forms that read them are effectful and not replayed.
- **An eval running when the process died:** no record was written. Root's `last-op` names it, and the load's plan lists it as interrupted.
- **A rewind while the process is busy:** the reconciler stops it (Flow's stop, as today) before loading, so two processes never hold one session.

## 5. The screen as diffs

**What main has (merged 2026-10-25, `jolt/agent-conversation`):** the screen is `seon.screen/forms`, three forms (`(seon.screen/show)`, `(my.task/status)`, `(my.branch/changes)`) evaluated in the agent's REPL before each request, recorded `:seon.eval/by "screen"`, sent as the request's last user messages and replaced by the next request's (context.clj:464-470), so they never repeat and sit after the cached prefix. The agent changes its screen by redefining `seon.screen/forms`. This part keeps all of that and changes one thing: what each form sends is its diff since the previous screen, appended rather than replaced. The tail's tokens per turn fall from the whole screen to what changed, and the conversation becomes append-only, so the cached prefix grows with it.

`(seon.screen/show {:since t})`, where t is the db t of the session's previous screen eval (recorded with it):
- **Answers** `{:changed {section value} :unchanged [section ...]}`: the sections whose inputs changed in (t, now], whole, and the names of the rest. The first screen of a session or after a load is whole.
- **Screen evals become ordinary records, appended,** no longer replaced each request (a diff must have its base in the conversation). So the conversation is append-only, and the provider's prefix cache holds across turns.
- **A section whose last whole value the history budget is about to cut** (`kept` would drop it at its next step) is sent whole again (the ruling).
- **Every input is a db fact.** The page's components are from root's render cache on main at a recorded t (design revision 3, section 3). The task's status and the branch's changes are read from the branch at its commit. The screen eval records `:since`, main's t and the branch's commit, so `request` at any commit makes the same text again.
- **Cost:**
  - a component changed since t iff it was rendered after t: the render cache already knows, by reads touched (`d/reads-touched?`), so O(components on the screen), never a structural diff;
  - the agent's own state forms (`(my.task/status)`, `(my.branch/changes)`) are compared with the previous screen's values by equality: O(their size);
  - the screen's tokens per turn fall from the whole page to what changed.
- **Agents edit their own screen forms** (owner, 2026-10-25): `seon.screen/forms` is a var in the agent's own process, so redefining it is an eval like any other, recorded and restored by the load (section 4). The diff is taken per form by equality with that form's previous value, so a form the agent adds is diffed with no knowledge of what it shows. The help page says so: "add what you want to see each turn to `seon.screen/forms`".
- **Proofs:** an unchanged screen sends only the names; a screen remade from its basis is the same bytes; the section about to be cut is sent whole.

## 6. Per part: work, hazards, reuse, proofs, and what needs the owner's gate

| Part | Work done | Reuses | Deletes | Proofs (cost proofs where input grows) |
|---|---|---|---|---|
| 1 session/run/turn | request at commit: O(new records) hot (exists), O(records sent) cold. `records` walks newest to oldest and stops when the budget is full, not O(all records) | `request`, `printed-run`, `kept`, `:seon.end/*` | the run filter in `records`; per-process eval-n!; run-scoped turn ids | a second run's first request carries the first run's records; ids never collide across processes; cost: a cold request is O(records sent) |
| 2 park/wake | a park: work! returns plus one ~3 ms collect; a wake: one eval request, no fork; reconciler: O(datoms) per tx for counts | run-loop, the agent proc, the reconciler's `:started` chain, Flow stop | kill on every end; hold for waiting; the lab's wake path | no message lost: n messages at random points (before basis, mid-eval, after the last inbox read, after the park record), each taken exactly once, and one run at a time; the static fork server's worked-agent footprint ≤ fresh + live (measured: 70 to 106 MB) |
| 3 messaging | one tx (about 5 datoms) and one frame per message | writer, `deliver-message`, `execution-tx` | seven mechanisms to one (table above); the `"root"` special cases | each sender kind (user, a root, an agent, an effect's owner) reaches a parked, a live and a processless agent; a message from any of them releases a held task; an unknown address is the one error |
| 4 load | 0.4 s: fork plus load-diff 0.34 s, plan O(evals ≤ C), replay = the forms' recorded ticks; a new task's load is a spawn plus an empty diff | `d/branch!`, `load-diff!`, `init!`'s resumed path (now the only path), `:seon.fx/*` records, `:fns`, reach graph, `:seon.eval/by` | `my.root/run!`'s fresh fork as a second start path; the lab's symbol-list heuristic | a load at turn k of a scripted session gives the same request as the original turn k+1 would, plus the load's record and each replay marked; an eval that sent a message is not replayed under `:pure` and is replayed under `:all`; a durable-already eval is never replayed; a redefined `seon.screen/forms` survives a load; cost: plan O(evals ≤ C) |
| 5 screen diffs | O(components on screen) per turn | render cache reads, the printer | the whole screen each request | unchanged screen sends only names; remade from basis equals sent |

**Schema and config changes, each re-justified** (owner, 2026-10-25: "What are these attributes doing?"; the standing store is erased, never migrated: DECISIONS):
1. **Kept, changed meaning:** `:seon.agent/key` is generated at a session's first load and stable from then on, no longer per boot. It is what messages address and what names the agent's namespace.
2. **Kept, retargeted:** `:seon.message/to` refs the recipient's agent instead of a run, because a run no longer addresses anything. `:from` stays a string; `:reply-to` stays declared and unwritten.
3. **New, the only one:** `:seon.run/taken` (int), the count that closes the lost wake on main (section 2).
4. **Moved into the record, no attribute:** the full set of called functions, in the eval record's existing `:fns` key (section 4, step 5).
5. **Dropped:** `:seon.agent/continues` (no fork; a rewind keeps the agent, and the branch's parent commit is Datahike's own record), `:seon.agent/ns` (named from the key), `:parked` (a parked run is an ended run, outcome `:waiting`, `:cap` and the rest; whether a process is live is root's view), `:seon.eval/calls` (item 4), and `:seon.resume/*` (the load's own eval record, `:by "load"`, holds the plan).
6. **Config keys:** `:seon.config.agent/replay` (`:pure` or `:all`, default `:pure`) and `:seon.config.agent/evict?` (default false), each with its unit, provenance and what happens at the bound. Possibly the fork server's static garbage bound, only if build part 1 measures growth that needs it.
Ids: eval and turn ids become session-scoped (`<key>/e<n>`, `<key>/<n>`), a change of value, not of schema.
New functions the help page teaches: `my.agent/message!` and `my.task/rewind!`. Gone: `seon.root/message!`, `send-message` (both), `my.root/release!`, `my.root/run!`'s fresh start.

**Owner's questions, after the 2026-10-25 rulings:** questions 1, 3 and 4 are ruled (a stable key with sessions from `:needs`; no restrictions; `my.agent/message!`), and question 6 is now a config key that defaults to always park. Two stay open, with my recommendation first:
1. A refused green end parks and holds (as DECISIONS asks), rather than going to the agent as a message that wakes it, which would be an automatic retry. *Recommended: hold*, with the reason in the user's timeline. Any message to the agent is the release.
2. Async effect outcomes become messages from the effect's owner, making one inbox feeder. *Recommended.*

## 7. Build order (phase 2, after the owner's rulings and root's go; `jolt/agent-conversation` is on main)
Each part is red then green, measured, reported before the next; a page change is checked in a browser.
1. **Fork server static before fork; collect on park.** Smallest, measured, unblocks capacity. Proof: worked-agent footprint; fork server growth per static measured.
2. **Session-scoped records and ids** (part 1): the conversation across runs.
3. **Park and wake** (part 2): park outcomes, `taken`, the reconciler's ready rule and `:wake`, `evict?` (off); the session's agent from the `:needs` chain's runs and the stage message in the judge's transaction (section 1a).
4. **`my.agent/message!`** (part 3): the help page and system page (with the UI instruction), the page's box and MCP rewired, the seven mechanisms' deletions, and the `"root"` special cases removed (a root is a task on main).
5. **The one loader** (part 4): `:spawn` becomes `:load`, `my.task/rewind!`, the replay filter and `replay` key, replays marked; the full `:fns` set measured first.
6. **Screen diffs** (part 5).
After part 4: **the pilot, "customizable errors"** (section 1a), a paid run with root's go.
No other paid model runs: the scripted provider (`seon.lab/reply`, `jolt/flow-lab`) drives every proof; a paid measurement of turns to green comes after part 4, with root's go.

## 8. Flow across processes (owner, 2026-10-25: "messages are supposed to go through flows ... shared across all the processes")

**Today.** There is one Flow graph, in root. An agent and the fork server run the same step-fn contract on their own thread (`seon.step/run-loop`) and receive Flow's lifecycle as data over a socket. Each agent appears in root's graph as a bridge proc (`seon.agent-proc`). A message from agent A to agent B travels: A's socket, then A's proc in root, then the writer (the fact on main), then `deliver-message`, then B's proc's `:in`, then B's socket. Root is the hub. The reason given is "physics" (docs/failure-points.md, "Physics"): an agent forks, for its test children and `clone!`, so it must stay single-threaded.

**Measured for this note** (this worktree's root, one agent, `notes/agent-loop/fork-threads.clj`, no model calls):

| In the agent when it forks | Forks | Children |
|---|---|---|
| a parked go block, an OS thread blocked in `<!!`, the sweeper pool fixed at 0 (`gc-sweepers!` reads 0 throughout) | 50 of 50 went through | **50 of 50 exited 0**, about 40 ms each with a full collect and a go block in the child |
| 8 go blocks busy on one channel (a flow under message traffic) | 199 of 200 refused (`-2`), then 50 forced through in 863 tries | **50 of 50 deadlocked**: the child's main thread waits forever in `S_mutex_acquire` (sampled), on a Scheme mutex held by a thread that does not exist in the child |

What this shows, with the research subagent's source reading (Chez `c/gc.c`, `c/thread.c`, Jolt's `fibers.ss`, `fibers-async.ss`, `jolt/async/remote.clj`):
- **The sweeper hang is gone.** Fixing the pool at 0 (patch `0008-fixed-sweeper-pool`, `seon.main/-main`) is inherited by fork, and no collection starts a sweeper (`gc.c:444`, `2999-3020`).
- **A second hazard remains, and it is the real one.** `proto-fork-alone` checks that one Chez thread is *active*. But a thread deactivates on its way into `S_condition_wait` while it still holds the channel's mutex, and on its way out it re-takes that mutex before it reactivates (`thread.c:709-720`). Inside those windows the count reads 1, the fork goes ahead, and the child inherits a locked mutex whose owner does not exist in the child. A busy flow lives in those windows: 100% of the forks that passed the check deadlocked.
- So **a process running a busy Flow cannot fork**, and pausing its procs does not close the windows reliably. The field agrees: POSIX allows only async-signal-safe calls in a threaded process's child, `pthread_atfork` is considered insufficient, and the standard answer is a process that forks while single-threaded (Android's zygote, Python's `forkserver`), or `posix_spawn`.
- **Jolt's fibers are bound to their carrier thread for life** (a continuation resumed on another thread raises "stale foreign context", `fibers.ss` header), and `sa-fiber-run-all` already drains carrier 0's queue on the calling thread (`fibers.ss:1617`).
- **`jolt.async.remote`** carries back-pressure end to end through bounded channels and the kernel's socket buffers. Its pumps are threads or poller fibers, so today it adds threads. `remote/flow.clj` already makes a remote end a Flow proc.

**Options, priced, simplest first:**
1. **Keep root as the hub (today) and close its gaps.** This is what sections 3 and 4 do: every message is a fact before delivery, there are no drops, and the reconciler wakes or loads. It costs nothing new, but agents still speak a hand-rolled lifecycle instead of Flow, and the bridge proc stays.
2. **Agents never fork; the fork server forks their children.** Agents could then run threads, Flow procs and remote ends with pumps. But a test child or clone would have to be forked from the fork server's image of main and load the agent's program diff: about 0.34 s per child (measured for a load), instead of about 40 ms copy-on-write from the agent. It also moves the agent's live state out of reach of its children.
3. **Recommended: Flow on the main thread in agents (a Jolt change).** An agent's fibers run on its one thread, as ClojureScript's core.async runs on its one event loop:
   - carrier 0 is the calling thread, drained wherever it would otherwise wait: in `recv!`, in `<!!`, and between forms;
   - the remote end's pump becomes a fiber woken by the socket's readiness, from the SIGIO handler the agent already uses (`seon.sock/signal-at-input!`), so it needs no poller thread;
   - an agent's procs are `:io` only, since `:mixed` and `:compute` need threads.

   The process stays one OS thread, so it forks as safely as today. The agent's procs then join root's graph through remote channels with back-pressure. That deletes `run-loop`'s own lifecycle protocol, the bridge logic of `agent-proc` and the separate frame protocol, and a message becomes a channel put from the sender's proc to the recipient's, with the fact still written by the one writer. The cost is a Jolt branch (`jolt/main-carrier`): a carrier mode where carrier 0 is the calling thread, plus a SIGIO-driven pump mode for `jolt.async.remote`. A long eval blocks the agent's fibers while it runs, which is the agent's behaviour today; messages still land in the inbox through SIGIO.
   - **To prove first, at the REPL:** a fiber parked on carrier 0 resumes when the main thread blocks in `<!!`; a remote end pumped from SIGIO keeps its back-pressure; the fork table above shows 50 of 50 exit 0 with the flow under traffic.
   - **To measure:** fork success with the agent's flow busy (it must be 100%), message latency agent to agent against today's 7-35 ms wake, and threads per agent (must stay 1).
4. **Agent to agent without root in between** (Erlang-style direct links). Every message must still be committed by the one writer, which is in root, before it is delivered, so a direct link saves only the second hop, and it adds a peer mesh. Not worth it while the writer is in root.

**Where the facts' homes change:** docs/failure-points.md "Physics" still says the sweepers are the reason agents stay single-threaded. The measured reason is the condition-wait windows (the table above). That correction is owed to the page whichever option is chosen.

### 8a. Option 3 prototyped: core.async and Flow on the agent's one thread (owner, 2026-10-25: "the option 3")

**What was built, at an agent's REPL with no Jolt rebuild** (`notes/agent-loop/main-carrier.clj`, about 70 lines; the runs are in `notes/agent-loop/main-carrier-runs.clj`):
- `mode!` gives Jolt's fiber pool one carrier and marks it started, so no carrier thread is forked. It also sets up `jolt.io-poller`'s kqueue and wake pipe without the poller thread.
- `run-until` is the agent's loop:
  - drain carrier 0's run queue on this thread;
  - when the queue is empty, run one poller round on this thread (block in kevent until a registered fd is ready, then resume its waiters);
  - when nothing is queued and nothing is awaited, answer `{:stuck n}`: no fiber can ever wake, so that is reported, not waited out.
- `take!!` is `<!!` for the main thread: it runs the loop until the channel has a value.

**Measured** (this worktree's root, fresh agents, no model calls):

| Run | Result | OS threads |
|---|---|---|
| a go block, its value taken on the main thread | 42, as put | agent: **1** (`ps -M`) |
| 20,000 values to a forked child and back over a `jolt.async.remote` end (`:pump :fiber`), the child echoing `(inc v)` | all back, sum exact; 52 ms, **382,000 per second** each way, pipelined; 16 poller rounds | agent 1, child 1 |
| 100,000 values over that channel, with **50 forks made in the middle of the traffic** (each child runs a go block and a full collect) | **50 of 50 forks went through, 50 of 50 children exited 0**, sum exact | 1 |
| ...the same forks beside a threaded busy flow, for comparison (section 8) | 199 of 200 refused; 50 of 50 forced through deadlocked | not counted (carrier threads) |
| a three-proc Flow graph (`create-flow`, `:io` procs), 20,000 messages injected | all through, sum exact; 148 ms, **135,000 per second** | 1 |
| back-pressure: the peer process is busy in a 1 s eval (its fibers don't run) while 100,000 values are sent | the sender put **1,707** (its buffer, the kernel's socket buffers and the peer's) and then parked; once the peer ran, all 100,000 arrived, sum exact | 1 each |

**What it shows:**
- Each process stays one OS thread, so it forks as safely as today.
- Its go blocks, Flow procs and remote channels work as they do anywhere.
- Parallelism is between processes: two single-threaded processes ran at full speed against each other.
- Back-pressure crosses the process boundary. A blocked process pauses its senders without dropping anything, which answers the owner's "if flow is blocked they pause?".

**What the Jolt change (`jolt/main-carrier`) must add, found by the prototype:**
1. **The mode as a runtime option.** Carrier 0 is the calling thread and the poller runs in its rounds. It is set at startup and set again by `jolt-after-fork-child!` in a forked child. The prototype reaches private vars (`jolt.io-poller/poller-round`, `process-events!`); the change exposes one `run-until`.
2. **Blocking calls made on the main thread outside a fiber run the loop instead of waiting.** These are `<!!`, `>!!`, `alts!!`, `FutureTask.get` and `deref` of a promise. Without this, two prototype runs hung: a direct `flow/stop` (it calls `.get` on proc futures) and a wait on `flow/inject`'s FutureTask. Called from a fiber, the same calls park correctly (measured).
3. **Timers on the loop.** `a/timeout`, Flow's ping and stop bounds and `alts!!` with a timeout need the kevent timeout (`EVFILT_TIMER`) instead of Jolt's timer thread. Not built yet.
4. **`{:stuck}` becomes an error fact**, never a hang: an agent whose loop has nothing queued and nothing awaited reports it (AGENTS §9: "never came" is a recorded fact).

**How it joins the agent loop:**
- `seon.step/run-loop` over the socket becomes `run-until` with the agent's socket registered in the poller, and an eval runs on the same thread between drains.
- While an eval runs, the agent's fibers wait, as its process does today. Senders feel that as back-pressure, measured above.
- A form that blocks in `<!!` keeps the agent's flow running (change 2).
- The agent's procs join root's graph through remote ends, so Flow's own pause, resume and ping reach them as channel messages. A paused proc's input fills, and its senders park.
- This deletes `run-loop`'s own lifecycle protocol, `agent-proc`'s bridge logic and the hand-written frames. The one writer still records each message on main before delivery (section 3).

**Not yet measured:**
- agent to root latency through a remote end, against today's 7-35 ms wake;
- the loop with timers (change 3);
- a full agent turn (evals, model calls through `my.fx`) inside `run-until`.
