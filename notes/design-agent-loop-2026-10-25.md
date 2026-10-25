# Design: the agent loop: sessions that park, wake on a message, and resume from any commit (2026-10-25, revision 1, for root's review and the owner's gate)

THE PLAN steps it serves: 1 (an agent's context is right, so it finishes in few turns) and E (turns to green): an agent stops losing its conversation at every stop, and a user's reply costs one wake (7 to 35 ms) instead of a fork, a resumed branch and a blank conversation. It builds on `jolt/agent-conversation` (design `notes/design-agent-view-2026-10-24.md`, revision 3) once that is merged to main. Nothing here is built. It carries out the 2026-10-25 rulings in `docs/decisions.md`: every agent parks; resume replays pure forms and asks before replaying effects; a session's context is generated from the db and continues across runs; the screen is sent as diffs; the lifecycle is Flow's; correctness and performance come before lines.

Evidence: root's scratchpad (`flow-sources.md`, `flow-lab.md` with lab code on `jolt/flow-lab`, `jolt-park-resume.md`, `messaging.md`), and the memory measurements in section 2, made for this note on this worktree's root (ports 8140/8141, no model calls).

## 0. What is wrong today, in one paragraph
A **run** is five things at once: the process's lifetime (spawn to SIGKILL), the unit the judge judges, the turn budget, the scope of the conversation (`seon.context/records` filters evals by `(:run record)` and messages by `:seon.message/to` the run, context.clj:376-381), and the address of a message. So every stop is a death. `:waiting` is judged, held as a *failure* (`hold-tx`, reconcile.clj:443) and killed (`:diffed`, reconcile.clj:693). A message to a stopped task is refused (`gone`, root.clj:371) or, from an agent, silently dropped (`to-agent`, root.clj:225). `my.root/run!` starts a new process with a blank conversation (flow-lab section 3: turns of the earlier run are on the branch, unread). Turn ids restart at 0 and eval handles restart at `e0` in each process (eval-n!, repl.clj), so a second run collides with the first.

## 1. Session, run, turn

| Word | What it is | Its record | Bounded by |
|---|---|---|---|
| **agent** | one session's identity: a task's agent (one per task, on the task's branch), `"user"`, `"root"` | `:seon.agent/key` (stable, no longer per boot) | nothing: it lives as long as its task |
| **session** | the agent's conversation: every record of the agent (and the agents it continues, section 4) on its branch, in tx order | none of its own: a query at a commit | the history budget (`:seon.config.render/history-budget`), never a turn count |
| **run** | one agentic loop: a wake to a park (or to green). The owner's word (2026-10-23) | `:seon.run/*` on main, written by the reconciler, as today | `:max-turns` per run: a budget per wake |
| **turn** | one model call and its evals | `:seon.turn/*` on the branch | |
| **process** | the forked OS process holding the session live | root's view (`:seon.agent/status`, `pid`, `mem-mb`) | memory admission (section 2) |

- **A run stays, with one job:** one loop between a wake and a park. It is still what the reconciler starts and what records the loop's spend (turns, tokens, outcome). It stops being the process lifetime (the process outlives runs), the judge's unit for a stop (only a green end is judged), the conversation's scope and a message's address. The conversation is the session's.
- **Turn budget decoupled:** `:max-turns` bounds one run. A run that reaches it parks with outcome `:cap`, and the next message starts a run with a fresh budget, on the same conversation. What bounds a session's cost is the history budget (the context's size) and the owner's money budget (`my.fx`'s `:budget`), never a turn count.
- **Ids are session-wide.** Turn `<agent-key>/<n>` and eval handle `result/e<n>`, n counting on from the session's last record on the branch. A process reads the counters once at init: one reverse seek on `:seon.eval/agent` and `:seon.turn/agent`, O(log n). The handle's identity is `<agent-key>/e<n>`, so two branches forked from one session never share an eval id once both merge.
- **The conversation at commit C** is `(seon.context/request agent {:db (d/commit-as-db conn C)})`: the records of the agent chain on the branch at C, printed by their `:seon.render/ai` renders, under the history budget. It is immutable except for the screen evals (section 5) and the eval running now. `records` drops the run filter and queries by agent (`[?e :seon.eval/agent ?a]` for ?a in the chain, `[?m :seon.message/to ?a]`), and adds the run ends as records (`:seon.end/*`, already on the branch), so the model reads "run 3 parked: out of turns (30)" where it happened.
- **The task story across runs:** the story the session first sent stays the cached prefix. A change to the task since then is a screen section (section 5), sent as a diff, so the prefix never breaks.
- **What ends a run, and what follows:**

| Outcome | Today | New |
|---|---|---|
| `:green` (the done rule refuses nothing) | judge; accept, or hold as a failure; kill | judge as today (the agent's tests, the branch diff, `judge-tx`). Accepted: the task is done, and its agent's process ends (the only kill). Refused: the task is held (DECISIONS: no automatic retries) and the agent **parks**; the reason goes to the user's timeline |
| `:waiting` (a prose-only reply, `my.turn/wait`) | judged, held as a failure, killed | **park**: no judge, no hold, no kill; the task stays `:running` |
| `:cap`, `:no-progress`, `:error` (a provider error) | held as a failure, killed | **park**, its reason on the run; the next message wakes it with a fresh budget |
| `:failed` (`my.task/fail!`), `:merge-failed` | held, killed | the failure carried and the task held, as today (`judge-tx`); the agent **parks** |
| the process died | run `:dead`, held, killed | run `:dead`; the session has no process; the next message resumes it at its head (section 4) |
| root restarted mid-run | `:lost`, re-run blank | `:lost`; the session resumes at its head and its next run's first record says what was interrupted (root's `last-op`) |

## 2. Parking and waking on Flow

**Where an agent parks.** Its process's loop is `seon.step/run-loop` over `seon.repl/agent-step` (repl.clj:440-472). `seon.turn/work!` is one `:eval` request in that loop. When work! returns, the loop is back in `recv!` (seon.sock/recv!: read(2) on the socket): measured 1 thread and 0.0% CPU. In root, the agent's proc is an `:io` fiber parked in Flow's running loop (`alts!!` over `:in` and `:from`, flow/impl.clj:471): 0 OS threads, no step running. **So parking needs no new mechanism: it is work! returning.** Flow's `::flow/pause` is not parking (a paused proc takes control messages only and would push a message back on its sender); parking is Flow's idle running state.

**Where it wakes. One starter, the reconciler.** A run is started only by the reconciler, which also writes it on main, so no second path starts loops (the lab's agent-proc wake request was a second starter: hazards 3 and 4 there). A task's session is **ready for a run** when:
```
task :running, not done, its :needs done, no run of it :running, and one of:
  - it has had no run;
  - (messages to its agent on main) > (:seon.run/taken of its last run);
  - its last run is :lost.
```
`:seon.run/taken` is how many message facts the session had taken when the run parked, which the agent reports with its end (the count of `:seon.message/to` it on its branch). Both sides are counts of immutable facts, so the comparison closes the lost wake without timing: a message written before the park record and delivered to a process that had already decided to park is in the count and not in `taken`, so the reconciler wakes the session again at once. The reconciler's index keeps the per-agent message count from each transaction's message datoms: O(datoms in the tx).

The action for a ready session:
- process live (parked): `:wake`, which is today's `:started` step: `{:op :eval :forms (seon.turn/work! ...)}`, with no fork;
- no process: `:spawn` of the branch's head as a resumed branch (section 4), then the same `:started` step.

Admission: `:seon.config.agent/max-running` (4) counts **runs running**, not processes alive. Parked processes are bounded by memory (below).

**Messages, back to back and during an eval.**
- A message is a fact on main first, then delivered to the live process's input (`deliver-message`, root.clj:230), and lands in the inbox wherever the agent reads its socket, even mid-eval (SIGIO, measured: `{:inbox-during 1}` during a 4 s eval).
- The running turn's next `basis!` takes every message in the inbox at once (one write of their facts on the branch, turn.clj:146).
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
- **The fix is at its cause, in the fork server**: make its heap static before forking (`lib/static!` exists, fork_server.clj:101, called today only at boot, root.clj:805). Then a child's collections never trace the inherited heap: the same work leaves 106 MB (3.1x less), and a full collect costs 2.8 ms instead of 75.
- So the design is: the fork server runs `static!` at the first fork after main's heap has grown since its last static (an event: an `:applied` that loaded or transacted, never a timer), and an agent runs one full collect when it parks (cheap now: ~3 ms, and it no longer un-shares).
- Measured capacity in `:seon.config.machine/memory-mb` (4096): about 2.5 GB is left after root and the fork server, which holds about 8 parked agents at today's 300 MB and about 24 at 106 MB.
- Hazard: static objects are never freed, so the fork server keeps every main db value it held at a static. Its growth per static is unmeasured (build part 1). The bound is the existing standby fork server (`promote`, fork_server.clj:221): when the fork server's footprint passes its share, the standby takes over and the old one exits. That may need a config key (owner's gate, section 6).
- Found in passing, filed rather than fixed: Chez reports `collect-trip-bytes` 172,842,656 in an agent, while `:seon.config.agent/trip-bytes` is 4,194,304 (agent.clj:743 sets it; Jolt's policy rescales it, it seems). A follow-up for a `jolt/` branch.

**Eviction, by measured workload.** When an admission (a spawn) would put the sum of the agents' reported `mem-mb` past their share of `memory-mb`, the reconciler stops the parked process woken least recently (by wake order, a counter, not a time). That is Flow's stop transition, which exists (agent_proc.clj:274: `{:op :stop}`, await death, SIGKILL past the bound). The session loses nothing: its next message resumes it from its branch head (about 0.4 s instead of 7-35 ms).

**The lifecycle is Flow's.** A proc is present exactly while the session has a process. The api's `flow/update!` adds it at spawn and removes it at the end, as today. Stop is the kill, as today; eviction is a stop. The fork itself stays a request to the fork server's proc: Flow's init arity returns state and has no outputs, and the fork server is the only single-threaded process that can fork (lib.clj:149), so forking inside init would mean a step blocking on another proc (AGENTS §5). Nothing about the lifecycle needs Flow changes.

**Deleted:** the `:diffed` kill for every end (kept only for an accepted end); `hold-tx` for `:waiting`, `:cap` and `:no-progress` ("waiting is a failure" goes, so quiescence stops naming waiting tasks as root causes); `my.root/run!`'s release as the only way back (a message is the release, section 3).

## 3. Messaging: one send

**One function.** In the agent's REPL: `(send to body)` or `(send to body {:reply-to mid})`, which answers `{:id mid}` or a typed error. Underneath is `seon.root`'s one api op `:send`, which every sender goes through: the user (the page's input box, MCP `seon_message`), an agent (one request frame, answered: exactly one answer), root, and an effect's owner (an async effect's outcome). It does exactly two things, in order:
1. Transact the fact on main: `:seon.message/{id from to body reply-to}`, with `from` and `to` refs to agent entities. Answered when committed in memory (group commit, AGENTS §5).
2. Deliver: if the recipient has a live process (parked or busy), the existing `deliver-message` to its proc's input, then its inbox. If not, nothing: the fact is in the reconciler's count, which wakes or resumes it (section 2).
- **Resolution of `to`:** an agent key; a task id (its session's agent); `"user"`; `"root"`.
- **Refused only when nothing can ever read it:** an unknown key, or a task done, merged or cancelled (DECISIONS: done is permanent; file a new task). The refusal is a typed error to the sender.
- **A held task** (refused judgment, `my.task/fail!`): a message from the user or root releases it in the same transaction (`[:db/cas task :execution :held :running]`, today's `task/execution-tx`), so the owner's reply *is* the release. A message from another agent waits in the count until a release (owner's question 3).

**Replies are the same send.** A reply with prose and no eval call is `send` to whoever sent the last message the run took (else `"user"`), with `reply-to` set, and the run parks `:waiting`. `:seon.message/reply-to` is declared today and never written; it becomes a ref. The turn keeps `:seon.turn/reply` (the model's output, which reasoning replay needs); a prose-only turn refers to its message rather than storing the body twice.

**The user is a stable agent entity:** `{:seon.agent/key "user"}` on main, written once when the store is made, not per boot (`task/boot-tx` makes one per boot today). Its inbox is a query (`:seon.message/_to`). It has no process and never wakes; the page shows its timeline. Root is `{:seon.agent/key "root"}`, the agent on main (one-system ruling, 2026-10-25).

**The seven mechanisms (messaging.md section 3), and what goes:**

| Today | Goes or stays |
|---|---|
| 1. owner `:message` (fact on main, refused when gone) | goes: `:send` |
| 2. agent `:send` (no fact, dropped when gone) | goes: `:send`, recorded, answered |
| 3. `deliver-message`'s fact and no-fact entries | one entry, always a fact |
| 4. the inbox's three feeders (sends, owner, effect replies) | one: message facts. An async effect's outcome (`fx-send!`, `:fx-reply`, agent.clj:649) is a message from its kind's owner. The synchronous `fx!` answer is unchanged |
| 5. `basis!`'s two classes (`:message-ids` and `:messages`) and `taken-messages`' second branch | one class: ids |
| 6. the fact written twice (main by root, the branch by the turn) | stays: one content id, one entity once merged; the branch's copy is the "taken" record the count needs |
| 7. `my.turn/wait`, hold, and `release!` as the only wake | `wait` stays (it parks with no message); hold for waiting goes; `release!` becomes a send |
| also | `seon.root/message!`, `seon.agent/send-message`, `my.agent/send-message`, the web page's `>` special case (web.clj:85): one send |

**Cost:** one transaction (one fact, about 5 datoms), one frame per delivery, and one count update in the reconciler's index. A send from an agent waits for the in-memory commit (milliseconds: unmeasured on this path; the lab's 100 ms was the owner's path waiting for the writer's flush, to be measured in build part 3).

**The help page section** (docs/agent.md, "Messages"):
```
## Messages
- `(send to body)` messages anyone: an agent's key, a task id, "user" or "root". It answers
  {:id ..} once the message is recorded, or an error when nothing can ever read it (a finished task).
- Messages reach you any time, as user messages {:from :body :id} in your conversation, at your
  next turn. To answer one, `(send from text {:reply-to id})`, or reply with prose and no eval:
  that goes to whoever wrote you last (the user if no one) and ends your run.
- Ending a run is not ending your work: you park, and the next message wakes you with your whole
  conversation and a fresh turn budget. Finishing is `(my.task/done!)`; giving up is `(my.task/fail! why)`.
- To ask another agent and wait: `(send "ns/task" "...")`, then reply with prose; its answer wakes you.
```
`send` shadows `clojure.core/send` (Clojure's agents) in the agent's namespace: owner's question 4.

## 4. Resume from any commit

`(my.root/resume! agent-key commit {:as :rewind | :fork})`, a user's or root's function. Each step, with its measured cost:
1. **A branch at C.** `d/branch!` from the session's branch at commit C (1.4-3.7 ms, a pointer). `:rewind` points the task's branch to it (the old branch is kept as a record); `:fork` gives it a new exploration task.
2. **A new agent that continues the old.** `{:seon.agent/key <new> :seon.agent/continues {:agent <old> :commit C} :seon.agent/ns <old's ns>}`. Its conversation is the records of both on its branch. At C that branch holds exactly the old agent's records up to C, so no filter by commit is needed, and messages to the old agent stay with the old branch.
3. **Fork onto it** through the resumed path that exists (`agent/init!`, agent.clj:748-764): `program/load-diff!` from main's image to the branch at C. Measured 334-344 ms, which dominates. Its pending messages are those to it on main (the fork's db0) not yet on its branch: O(messages to it).
4. **Rebind data results.** For each eval record up to C whose response value is data (readable EDN, its type recorded), `result/e<n>` is bound to the stored value. Any other value reads its record: its printed text, saying that it is not the live value.
5. **Replay.** The evals of the session up to C, in order, minus the screen's (`:seon.eval/by "screen"`) and the definitions `load-diff!` already loaded, each classed:
   - **pure:** it asked no effect (no `:seon.fx/*` record joined its recording tx: exact, the owners record every effect today, agent.clj:175), sent nothing, and its forms reach no edge function by the compiler's reach graph (`program/reach-graph`, the reach facts the tests already use; never regex over code). It is replayed automatically.
   - **effectful:** any of those. It is listed for the user's approval, with its recorded ticks as the price of replaying it.
   - **durable already:** a form whose only effect was a write on its own branch. Its effect is in the db at C, so it is never replayed (a second transact would apply twice). It is listed as such.
   Replay runs in the new process as evals by `"resume"`, and one fact records the resume: `{:seon.resume/agent :from :commit :replayed :skipped :asked}`. The conversation renders it as one record ("resumed at turn 20: 15 forms replayed, 5 sends await your approval: e12 e14 ..."), so the model knows what holds.
   Measured on the lab prototype: plan 2-12 ms, replay 9-26 ms at 2-30 evals (scripted forms; real forms cost what they cost). The whole resume is 0.38-0.40 s whatever the session's length.
6. **Every eval records the functions it called.** `:seon.eval/calls`: the contracted vars it touched, as refs to their var entities. `seon.profile/touched` already collects all of them; today `profile/top` keeps only the costliest (`:seon.config.agent/form-top-fns`) as `:fns`. The full set is the record; the top-N stays the view. Not measured: the set's size per eval and its bytes in the store (build part 4 measures it, and if it is large, keeps the effect calls only).
7. **A stable namespace per session.** `agent.<slug of the chain's first agent key>` (`make-ns!`, agent.clj:619, takes the session's key instead of the process id), so a replayed form that names `agent.x/foo` resolves and no form names a process id. The process id (`:a3`) stays only as root's handle on a live process.

**Hazards and how each closes:**
- An effect hidden behind a helper: classed by what the eval actually called and asked (recorded), plus what it can reach (compiler), never by the symbols in its text.
- Host I/O that bypasses `my.fx` (`spit`, `sh`, interop): the reach graph must name Jolt's host I/O and interop sites as edge functions. Unknown means effectful: it is asked, never assumed pure.
- A pure form reading a world that moved: the branch db at C is the world it read. Time, files and main's head are reads through edge functions, so those forms are effectful and asked.
- An eval running when the process died: no record was written. Root's `last-op` names it, and the resume fact lists it as interrupted.

## 5. The screen as diffs

`(seon.screen/show {:since t})`, where t is the db t of the session's previous screen eval (recorded with it):
- **Answers** `{:changed {section value} :unchanged [section ...]}`: the sections whose inputs changed in (t, now], whole, and the names of the rest. The first screen of a session or of a resume is whole.
- **Screen evals become ordinary records, appended,** no longer replaced each request (a diff must have its base in the conversation). So the conversation is append-only, and the provider's prefix cache holds across turns.
- **A section whose last whole value the history budget is about to cut** (`kept` would drop it at its next step) is sent whole again (the ruling).
- **Every input is a db fact.** The page's components are from root's render cache on main at a recorded t (design revision 3, section 3). The task's status and the branch's changes are read from the branch at its commit. The screen eval records `:since`, main's t and the branch's commit, so `request` at any commit makes the same text again.
- **Cost:**
  - a component changed since t iff it was rendered after t: the render cache already knows, by reads touched (`d/reads-touched?`), so O(components on the screen), never a structural diff;
  - the agent's own state forms (`(my.task/status)`, `(my.branch/changes)`) are compared with the previous screen's values by equality: O(their size);
  - the screen's tokens per turn fall from the whole page to what changed.
- **Proofs:** an unchanged screen sends only the names; a screen remade from its basis is the same bytes; the section about to be cut is sent whole.

## 6. Per part: work, hazards, reuse, proofs, and what needs the owner's gate

| Part | Work done | Reuses | Deletes | Proofs (cost proofs where input grows) |
|---|---|---|---|---|
| 1 session/run/turn | request at commit: O(new records) hot (exists), O(records sent) cold. `records` walks newest to oldest and stops when the budget is full, not O(all records) | `request`, `printed-run`, `kept`, `:seon.end/*` | the run filter in `records`; per-process eval-n!; run-scoped turn ids | a second run's first request carries the first run's records; ids never collide across processes; cost: a cold request is O(records sent) |
| 2 park/wake | a park: work! returns plus one ~3 ms collect; a wake: one eval request, no fork; reconciler: O(datoms) per tx for counts | run-loop, the agent proc, the reconciler's `:started` chain, Flow stop | kill on every end; hold for waiting; the lab's wake path | no message lost: n messages at random points (before basis, mid-eval, after the last inbox read, after the park record), each taken exactly once, and one run at a time; the static fork server's worked-agent footprint ≤ fresh + live (measured: 70 to 106 MB) |
| 3 messaging | one tx (about 5 datoms) and one frame per send | writer, `deliver-message`, `execution-tx` | seven mechanisms to one (table above) | each sender kind reaches a parked, a live and a processless agent; a send to a finished task is refused, typed; a reply sets `reply-to` |
| 4 resume | 0.4 s: fork 0.34 s (load-diff), plan O(evals ≤ C), replay = the forms' recorded ticks | `d/branch!`, `load-diff!`, `init!`'s resumed path, `:seon.fx/*` records, reach graph | the symbol-list heuristic of the lab | resume at turn k of a scripted session gives the same request as the original turn k+1 would (minus the resume record); a form that sent a message is asked, not replayed; cost: plan O(evals ≤ C) |
| 5 screen diffs | O(components on screen) per turn | render cache reads, the printer | the whole screen each request | unchanged screen sends only names; remade from basis equals sent |

**Schema and config changes for the owner's gate** (the standing store is erased, never migrated: DECISIONS):
1. `:seon.agent/key` becomes the session's stable key (a task's agent, `"user"`, `"root"`), no longer per boot. New: `:seon.agent/continues` (old agent and commit), `:seon.agent/ns`.
2. `:seon.message/to` becomes a ref to an agent (was a run), `:from` a ref to an agent (was a string), `:reply-to` a ref to a message (declared, unwritten).
3. `:seon.run/status` gains `:parked`; new `:seon.run/taken` (int).
4. `:seon.eval/calls` (refs to vars); eval and turn ids are session-scoped (`<key>/e<n>`, `<key>/<n>`).
5. `:seon.resume/*`: one fact per resume.
6. Possibly one config key: the fork server's static garbage bound (when the standby takes over), with unit and provenance, only if build part 1 measures growth that needs it.
New functions the help page teaches: `send`, `my.root/resume!`. Gone: `message!`, `send-message`, `release!`.

**Owner's questions** (each needs a ruling before its part is built; my recommendation first):
1. Agent key = the task's session (one per task) rather than the branch: *recommended*, since a rewind moves the task to a new branch and its agent continues there.
2. A refused green end parks and holds (as DECISIONS asks) rather than sending the refusal to the agent as a message that wakes it (an automatic retry). *Recommended: hold*, with the reason in the user's timeline; the user's reply is the release.
3. Only the user's or root's message releases a held task; another agent's waits. *Recommended.*
4. `send` shadows `clojure.core/send` in agent namespaces. *Recommended: `send`*, as the owner named it, with the shadowing stated on the help page; the alternative is `tell`.
5. Async effect outcomes become messages from the effect's owner (one inbox feeder). *Recommended.*

## 7. Build order (phase 2, after `jolt/agent-conversation` merges and root's go)
Each part is red then green, measured, reported before the next; a page change is checked in a browser.
1. **Fork server static before fork; collect on park.** Smallest, measured, unblocks capacity. Proof: worked-agent footprint; fork server growth per static measured.
2. **Session-scoped records and ids** (part 1): the conversation across runs.
3. **Park and wake** (part 2): park outcomes, `:parked`/`taken`, the reconciler's ready rule and `:wake`, eviction.
4. **One send** (part 3), the help page, the page's box and MCP rewired; the seven mechanisms' deletions.
5. **Resume** (part 4), with `:seon.eval/calls` measured first.
6. **Screen diffs** (part 5).
No paid model runs: the scripted provider (`seon.lab/reply`, `jolt/flow-lab`) drives every proof; a paid measurement of turns to green comes after part 4, with root's go.
