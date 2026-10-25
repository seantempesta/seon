# flow-lab: lifecycle, parked agents, resume (measured 2026-10-25)

Worktree /Users/sean/src/seon-next/.worktrees/flow-lab, branch jolt/flow-lab, its own root (ports 8120/8121, store runs/store), scripted provider (seon.lab/reply, test/seon/lab.clj), no paid calls. Lab code is marked "LAB (flow-lab)": src/seon/agent_proc.clj (park/wake), src/seon/turn.clj (work! re-entry), notes/flow-lab/resume.clj (resume prototype). Machine load ~3-4 throughout.

Method note: query root with `printf '{:op :eval ...}' | bin/ask 8120` (nc -w waits past the reply). Source edits are NOT followed by the watcher for test/ files nor by a restarted root (it runs main's stored program): redefine with (load-file ...) in root or in the agent.

## 1. Today's lifecycle
- Start: my.root/run! releases a :held task; reconciler :spawn -> writer :spawn -> fork server forks. Events (ms after release):
  - warm fork: spawned 27-60, ready 35-60 (fork server already holds main's reach graph).
  - cold (first fork after root boot): spawned 938-1513, ready +10 (fork server builds main's reach graph on the first fork, fork-server.clj:87).
  - resumed branch (kept branch, release! of a held task): spawned 60, ready 447 (+390 ms: program/load-diff!, onto-tx, d/connect on the branch).
  - a whole 3-turn scripted run, release -> :ended: 570-690 ms warm; 31-turn run 2.35 s (~75 ms/turn, scripted).
- Idle agent process: 1 thread, 0.0% CPU, RSS 48-60 MB (footprint 47-55 MB, ps and footprint(1)); forks share the fork server's COW pages. After it has run turns: 116 MB RSS (outside agent after work), footprint 134-139 MB for resumed agents, 306-316 MB for one that ran 5 turn loops (System/gc does not shrink it: Chez keeps the heap).
- Idle agent proc in root: :workload :io = a fiber parked on alts! over its :in/:from; no step runs. Root with 7 agent procs: 51 threads, 0.3% CPU (top). Root RSS 1.1-1.9 GB, fork server 290-330 MB (mostly shared).
- Removal order (it is the reconciler that kills, once it has judged): work! returns -> reconcile :worked -> :tested (tests in the agent) -> :diffed (branch read) -> `{:op :kill :id agent :ended true}` (reconcile.clj, step :diffed) -> root api :kill (root.clj:392) sets view status :ended and sends {:op :kill} to the agent proc -> agent-proc `:kill` does `lib/c-kill pid 9` (SIGKILL; no {:op :stop} grace) -> socket EOF -> `:from` nil -> `:dead` frame -> api `:ended` -> end-agent (root.clj:~297) removes `[:agents id]` and `[:agent-procs id]`, `::update` -> flow/update! stops the proc (already dead). ~40-50 ms from :tests to :ended. judge-tx writes the run :ended/outcome first, then :accept or :hold (waiting with unfinished proofs: hold, task :held, run :closed).
- Gotcha found: attach (seon.server) sets the task's env :model "outside" in main for good; a later go! of the same task runs no turn loop. Use fresh tasks for scripted runs. max-running is 4; killed/lost "running" runs are re-run by the reconciler at root boot (outside agents idle, occupying slots).

## 2. Messages to a live agent (seon.root/message!)
- Path: api :message -> writer tx of the :seon.message fact (answered in ~100 ms: 98, 108 ms) -> deliver-message -> agent proc :in :write -> socket frame -> agent inbox.
- During a long eval (outside agent, `(do (Thread/sleep 4000) (count (seon.agent/inbox)))` with message! sent 1 s in): result `{:inbox-during 1}`: the frame lands in the inbox DURING the eval (the agent's socket signals SIGIO; sock/signal-at-input!). It is read by the turn loop at the next turn's basis! (agent/inbox, taken!), so a run in a model turn sees it at the start of its next turn, after the long eval ends.
- Run with an 8 s eval in turn 1 and a message at +3 s: message acked at +108 ms; the run ended on its script at 8.8 s (a wait script; the message would be read by a turn 3 if the script had gone on).
- Between runs / after the end: message! answers `no live agent :aN` (root.clj:371): nothing stores it (messages are written to main only for a live agent).
- Today (no park): a message after :waiting is refused, never lost silently but never read.

## 3. Restart today
- release! (my.root/run! sys [id]) = one cas :held -> :running on main; the reconciler :spawn's a new agent on the kept branch: release -> ready 447 ms, work! started 475 ms, first turn commit ~670 ms (t6#2).
- New run does NOT see the old conversation: t6#1 and t6#2 are both :waiting with 3 turns and the scripted model (keyed on the assistant messages it is sent) started from none both times. Cause: context/render builds the scrollback from `model-turns db run n` = turns of the same :seon.run/id only (context.clj:403), and the run sections come from the first turn of this run. Branch, evals, defs-on-branch are kept; turns of the earlier run are on the branch but unread.
- For a restart to carry the conversation: model-turns would read the branch's earlier runs too (same task), and turn numbering continue; the process and its atoms are new anyway (see 6B).

## 4/5(A). Parked live agents via Flow (prototype in the worktree)
What ends the process on :waiting: nothing in the agent (work! returns, the REPL serves on); the reconciler's :diffed step kills it for every end. Smallest change to park: do not send :kill when the end's outcome is :waiting, and keep run/task state :running-or-waiting instead of judged/held (judge-tx currently writes :ended -> :hold). In the lab I attached an outside agent for t8 (alive, as a parked one is) and changed only:
- agent-proc.clj (about 15 lines): an op `:park {:forms F}` stores the wake form; a message `:write` to a proc holding it emits one `{:op :request :req {:op :eval :id id :forms F} :route [id :in] :tag :wake}` to the api (the existing request/route pattern prove uses), flag :waking, counters :pending/:woken-at; the answer (tag :wake) clears :waking and re-wakes at once if more messages arrived meanwhile.
- turn.clj work!: clear the stale `[:turn :stop]` and start the turn counter at the run's recorded turn count.
Measured (scripted model, so no model latency):
- message! -> work! eval :started: 7-35 ms; first turn commit ~200 ms (the writer's tx 100 ms is overlapped, the proofs' test fork 10 ms, context); a 3-turn wake ~730 ms total.
- Back to back (3 messages): first wake at +7 ms, second wake at +408 ms (4 ms after the first answered), both loops took all three messages: inbox [], 6 message facts on the branch, turn ids continue 0..4.
- Idle cost of a parked process with no step running: 1 thread, 0.0% CPU (top), footprint 47-55 MB if fresh, 135 MB resumed, ~300 MB after turns (does not shrink). Root side: a fiber, 0 threads.
- Capacity: :seon.config.machine/memory-mb = 4096 (a quarter of 16 GB). Root ~1.3 GB + fork server 0.3 GB leaves ~2.5 GB: ~50 fresh idle agents, ~18 resumed (135 MB), ~8 after real work (300 MB). Memory per parked agent after a run is the binding number, not threads or CPU.
Hazards found in the lab (the first two reproduced before the fixes above):
1. Stale `:stop`: the second wake ended at once with no turn and the messages stayed in the inbox (inbox ["HELLO again", "HELLO third..."], not taken).
2. Turn numbering restarts at 0 on a re-entered work!, colliding with recorded turns (fixed in the lab by counting).
3. Lost wake: a message arriving while the loop runs (waking) has no new wake; handled by the pending/woken counters on the answer.
4. Two wakes in flight would run two work! forms in one single-threaded process: the :waiting flag admits one.
5. The reconciler judges at the end and kills: parking needs the run to stay open (or each wake a new run on the same branch with the conversation carried); the :judge/hold for :waiting is the part of the design not prototyped.
6. A message to a parked proc whose process died: today root answers `no live agent` only when the proc is gone; the proc sees :dead and the api ends it as today.

## 6B. Resume from any commit point (notes/flow-lab/resume.clj)
- A branch at an old commit: `(datahike.api/branch! (:conn sys) commit-uuid branch)` 1.4-3.7 ms (a pointer). A fork on it (seon.root/spawn! with that existing branch; the writer treats it as resumed): 334-344 ms; its db is that commit exactly (max-tx 536871807 and commit-id equal to the turn's basis commit, checked).
- Commit points come free: every model turn records its basis commit (`:seon.turn/basis :commit`), the db the model read.
- Conversation: `(seon.context/render (d/commit-as-db conn commit) basis)` = the exact request the model got at that turn, 26-37 ms (11-13 k chars; the history budget caps it), 63 messages at turn 30.
- Result handles: restorable as data from the store: only what each eval record keeps, `:seon.cluster.eval/record {:tag :form :shown ...}` (shown = the printed text) and the turn's `:evals` text. For turn 20 of the 31-turn run: 20 evals: 10 print as EDN data, 5 are non-data (every `(def x (atom ..))` prints `#'agent.aN/x`), 5 are send-message effects. The live value of a handle (:h0..) and the atoms' current state are not in the store: they live in the dead process.
- The model evals have no :fns (reach facts) recorded, only root's outside evals do, so effects are classified by symbols in the form against a list (seon.agent/send-message, my.fx/call, my.fx/send, spit, sh, my.task/fail!, ...): a heuristic; `bin/laws`-style compiler facts should replace it.
- Replay (replace dead handles by re-evaluating forms in order, skipping effects): 
  | resume at turn | evals | replayed | effects skipped | fork | context | plan | replay (wall) |
  | 2  (short) | 2  | 2  | 0 | 336 ms | 37 ms | 2 ms  | 9 ms |
  | 12 | 12 | 9  | 3 | 334 ms | 26 ms | 12 ms | 14 ms |
  | 20 | 20 | 15 | 5 | 344 ms | 28 ms | 2 ms  | 15 ms (agent time 1.8 ms) |
  | 30 (long) | 30 | 23 | 7 | 336 ms | 28 ms | 3 ms | 26 ms |
  Total 0.38-0.40 s whatever the length; the fork dominates (load-diff and branch open), replay is ~1 ms/form here (scripted forms are trivial: real forms with test runs, fetches or long computation cost what they cost). Check after replay at turn 20: `@lab-h` = 5 (five swap! forms replayed), lab-h5/lab-h17 restored, 18 handles.
- Not restored by the db: defs made at the REPL in the agent's scratch ns (`agent.a2/lab-h`): the new agent's ns `agent.a3` has no lab-h until the replay; a form that names the old namespace explicitly (`agent.a2/x`) would fail on replay (a rewrite or a ns alias is needed).
- Side-effecting forms in the 30-turn run: seon.agent/send-message (7). The user's approval needed for those: list of forms from `plan :effects`.
- Hazards: forms whose effect is not visible by symbol (`(my.fx/call ...)` through a helper); replays of forms that read a changed world (the time, files, main moved on: branch is at an old main); dead handles referencing resources (sockets, forks); the branch at commit c has records after c ignored (eval records after c are not in that db, so a replay after c must not exist).
