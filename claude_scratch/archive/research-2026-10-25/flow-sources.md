# Flow and agent lifecycle: source list

Read-only. Paths are under /Users/sean/src/seon-next unless absolute. "wt" = /Users/sean/src/seon-next/.worktrees/agent-conversation (branch jolt/agent-conversation, 63 files changed vs main, src diff in turn.clj, agent.clj, context.clj, repl.clj, render/*).

## 1. core.async Flow (Jolt's stdlib, vendored)

- `reference-code/jolt/stdlib/clojure/core/async/flow.clj:189` `process`: "Given a function of four arities (0-3), aka the 'step-fn'": 0 describe `()`, 1 init `(arg-map)`, 2 transition `(state transition)`, 3 transform `(state input msg)`.
- `flow.clj:253-262` (docstring): state may carry `::flow/input-filter`; transition is one of `::flow/resume`, `::flow/pause`, `::flow/stop`.
- `flow.clj:121` start, `:136` stop, `:152` pause, `:156` resume, `:160` ping, `:181` inject.
- `flow.clj:181-187` `inject`: "asynchronously puts the messages on the channel corresponding to the input or output of the process, returning a future"; `[::flow/cast sig]` broadcasts; signals cannot target one proc.
- `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj:417` `proc` (the step-fn becomes a ProcLauncher reify). `:425` `workload` default `:mixed` (`(or workload (:workload desc) :mixed)`). `:428` `state (step args)` runs init. `:433-436` in-ports/out-ports taken from init's state (`::flow/in-ports`, `::flow/out-ports`), control and casts are in-ports.
- `flow/impl.clj:379` `handle-transition`: `:exit -> ::flow/stop`, `:running -> ::flow/resume`, `:paused -> ::flow/pause`; runs only on status change.
- `flow/impl.clj:406-411` paused loop: `alts!! ... :priority true`, control-only while paused.
- `flow/impl.clj:461-475` running loop: read set = ins minus control/casts, filtered by `::flow/input-filter` (`:465`); `:471` `alts!! read-chans :priority true` is the park with no input. A closed in-port yields msg nil once.
- `flow/impl.clj:182`, `:198`, `:306`, `:317`, `:319`, `:333-334`: the commands `::flow/resume`, `::flow/stop`, `::flow/pause` go as `>!!` onto each proc's control chan (`send-control!`).
- `flow/impl.clj:171` `start-proc!`, `:203` `create-flow`, `:356` `finish-outputs!`, `:389` `send-outputs`, `:417` `proc`.
- `reference-code/jolt/stdlib/clojure/core/async/flow/spi.clj:11` `ProcLauncher` protocol (describe :60, start :72); `:88` `Resolver`.
- `reference-code/jolt/stdlib/clojure/core/async/impl/dispatch.clj:65` `executor-for`: `:io` is a fiber; `:mixed` is an OS thread per proc for its life; `:compute` a cached pool with `compute-timeout-ms` 5000 (`flow/impl.clj:417`).
- Online docs: https://clojure.github.io/core.async/flow.html fetched, but the page has no step-fn arity or transition detail (only links to Reference/Walkthrough). The vendored source is the authority here.

## 2. Seon's use of Flow: the agent as a proc

- `src/seon/agent_proc.clj:242` `agent-proc`: "ONE agent's proc in the root graph, added by the api's update! at spawn and removed when the agent has ended." Its docstring lists start (init, nothing opened, fork already requested), pause/resume passed to the agent loop, stop: `{:op :stop}`, await death, SIGKILL past the bound.
- `agent_proc.clj:258` `:workload :io`. `:260-262` ping-map. `:266-268` `::flow/in-ports {:from (:in (:end args))}`, `::flow/out-ports {:to-writer :to-api :events}`.
- `agent_proc.clj:270-272` transitions: `::flow/pause` / `::flow/resume` send `{:op :pause}` / `{:op :resume}` to the process; `:274-281` stop: `{:op :stop}` (unless stop-sent), `await-dead` on `stop-bound-ms` (`:50`), SIGKILL (`lib/c-kill 9`), `hang-up!`.
- `agent_proc.clj:74` `agent-in`: input from the api. `:req` (`:77`) forwards a request into the agent with `:inflight`; `:write` (`:103`) applies a writer answer; `:stall?` (`:107`) is root's stall check; `:forked` / `:failed` / `:drain` / `:kill`.
- `agent_proc.clj:176` `:send` (agent's own message to another agent): `step/out! o :to-api {:op :message ...}`.
- `src/seon/root.clj:221-228` `to-agent`: sends m to agent id's proc as an output to `[id :in]`; `(when (get-in st [:agents id]) ...)`, so a message to an absent agent is dropped here, and the caller answers the gone case.
- `src/seon/root.clj:230-241` `deliver-message`: one delivery for agent sends and owner messages; `:to-agent ... {:op :write :msg {:op :message :from :body :ns}}`, plus `:id` and the run when it is a fact.
- `src/seon/root.clj:366-376` the owner's `:message` api op: if `(get-in st [:agents id])` is nil, the answer is `(gone id "no live agent ...")` (`:371-372`). Otherwise the message is first written to main via the writer (`:tx`, `task/message-tx`) and delivered when the write answers (`:dest {:message ...}`, `:431-433`).
- `src/seon/root.clj:486` `:message (deliver-message st o m)` (agent-send path), `:430-433` message delivered once its fact is written.
- `src/seon/root.clj:930-945` `release!`/`message!` api table; `:992-998` `message!` (owner's body string into the agent's inbox and main).
- `src/seon/root.clj:1000-1004` `release!`.
- `src/seon/fork_server.clj:176` `fork-server`, `:242` `fork-server-proc`, `:221` `promote` (standby promoted when the server dies), `:50` `server-step`, `:125` `main`. Its workload is `:io` (`:56`, `:253`).
- `resources/seon/system.edn` lines 1-8 describe the procs as data: "Agents are not here: the reconciler starts them from tasks." `:reconciler` `seon.reconcile/reconciler-proc`, `:fork-server` `seon.fork-server/fork-server-proc`, `:api` `seon.root/api-proc`. `:conns` at the end of the file. `:fx` declares the effect kinds.

## 3. Agent process: where it reads its socket and inbox

- `src/seon/agent.clj:35` send to root (one frame, `:dead` when root is gone), `:40` next message from root.
- `agent.clj:44-54` `next-msg!`: pending messages first; when dirty and no frame waiting, `commit!` then `recv-raw!`. "Idle with applied writes (no message waiting): they are committed first."
- `agent.clj:71-78` the delivery: a `{:op :message}` read anywhere the agent reads its socket lands in the inbox "at once" (`handle!`).
- `agent.clj:80-93` `inbox`: messages delivered and not yet taken; "left in the inbox: root's requests ... other agents' sends and the owner's". Taken by `messages` (`:95-100`), dropped by `agent.clj:103-106` (the first n leave it).
- `agent.clj:659-662` the `:message` op in `handle!`: `(swap! state update :inbox (fnil conj []) ...)` and answers `{:op :received ...}`.
- `agent.clj:757`, `:777-784`, `:795-800`: the fresh-fork path. `resumed` branches load program diff and take their held task from main with `task/onto-tx` (`:798`).
- `agent.clj:301` `write-end!` (end written on the branch). `:321` process death writes `:outcome :error`.
- `src/seon/repl.clj:28` nREPL-style message model; `repl.clj:443` ":pause, :resume, :stop and :ping are taken by the loop."
- `step.clj:7-9` pause/resume semantics as used by step-fn runners; `step.clj:25` only :pause :resume :ping :stop are control.

## 4. The turn loop (what a run is, and how it ends)

Main tree (`src/seon/turn.clj`):
- `turn.clj:17-23` ns doc: "The turn loop's own facts live in the agent's one state map, under :turn".
- `turn.clj:28-32` `current-task`; `:34-44` `begin!` (run begun once per run, plan-before); `:56-60` `request-stop!` (`:waiting` = `my.turn/wait`, `:failed` = `my.task/fail!`).
- `turn.clj:75` `observe` (proofs, contract gaps, unmerged, problems, done rule over them).
- `turn.clj:109` `end!`: stage the runtime, `agent/write-end!`, returns the end; `:125` `usage`.
- `turn.clj:146` `basis!`: messages taken here: facts written on the branch first (`task/message-tx`), then `agent/taken!`; a read that is not the run's turn leaves the inbox (`:160`).
- `turn.clj:313` `work!`: loop ends on `(empty? reasons)` -> `:green`, `stop` -> outcome, `hairy` -> `:merge-failed`, `(>= turn max-turns)` -> `:cap`, repeated calls -> `:no-progress`.
- `docs/agent.md:86` "`(my.turn/wait)` ends your turn and waits for root." `docs/turn.md:9` "a function asked to stop: `my.turn/wait` (`:waiting`) or `my.task/fail!` (`:failed`...)".

Worktree (`wt/src/seon/turn.clj`), newer:
- `turn.clj:246` a reply with prose and no call: `(request-stop! :waiting (:text c))` ("the run waits for the user (the design's tool_choice auto)").
- `turn.clj:146` `basis!` now also used with `:agent (:key @agent/state)`; `turn.clj` records `:seon.turn/sent` (what the model was sent, committed with the turn record).

## 5. Run ends, judge, reconcile (what starts an agent)

- `src/seon/reconcile.clj:130-137` `idx-ready?`: task `:seon.task/execution :running`, not done, every `:seon.task/needs` done, and no live run and not judged on its runs. This is the only start predicate.
- `reconcile.clj:313-` `actions`: "reap, kill, judge, then start ready tasks up to max-running, merges up to max-merging"; `:spawn` op at `:534-` `start`.
- `reconcile.clj:346-363` judge: run `:done`/`:ready-to-merge` when `done-explain` is empty; if `rs` nonempty -> `(failed aid id ...)` -> `{:op :hold}`, so the task is held for root. So an agent whose end is `:waiting` with unfinished proofs is held, not restarted automatically.
- `reconcile.clj:443-457` `hold-tx`: compare-and-swap `:running -> :held` on the task, `:seon.task/failure`, and `:seon.run/...` closed.
- `reconcile.clj:506-` `pull-end`; `:569-` `judge-tx`: writes run `:ended` with outcome, results, reason; `:seon.run/error`; `:615-623` `dead-tx` (`:running -> :dead`).
- `reconcile.clj:547` `:reap` writes `:lost` with reason "root restarted"; `:548` `:close`; `:549` `:accept`; `:551-553` `:kill`; `:554-` `:hold`.
- Docs: `docs/root.md:41` run status flow `:running -> :ended (or :dead, :killed, :lost) -> :done, :merged, :behind, :bailed, :closed`; outcomes `:green :red :waiting :cap :failed :merge-failed :error :no-end`.
- `docs/tasks.md#a-refused-run-is-held-for-root` (cited): refused runs are not retried on their own.

## 6. Messaging today, end to end

1. Owner: `src/seon/root.clj:992-998` `message!` -> `request!` `'message!` -> api `root.clj:371` `:message`: no live agent -> `gone`; live -> writer `:tx` (`task/message-tx`) -> on answer `deliver-message` (`root.clj:230-241`) -> `to-agent` (`root.clj:221`) -> output `[id :in]` on the root graph -> agent proc's `:in` -> `agent_proc.clj:74 agent-in` `:write` -> `to-proc! st (:msg m)` -> socket frame `{:op :message}` -> `agent.clj:71-78` inbox -> read by `turn.clj:146 basis!` (`agent/taken!`) at the next turn (a fact: written on the branch).
2. Agent to agent: `agent_proc.clj:176` `:send` -> `{:op :message}` to api -> `root.clj:486` `deliver-message`.
3. Root to agent request: `agent_proc.clj:77` `:req` with `:rid`; `:kind` and `:inflight`.
4. A message to an agent that is not live is refused at the api (`root.clj:371-372`); there is no inbox for a stopped agent and no restart on message, in main. The agent's last run's inbox is in its process only.

## 7. Context rebuilt from the db at any t

- `src/seon/context.clj:397-401` `turn-record`: pull of `:seon.turn/{id,n,origin,basis,reply,calls,truncated,reasoning}`.
- `context.clj:403-410` `model-turns`: the run's model turns before turn n, oldest first.
- `context.clj:413-419` `remade`: "the request turn entity e on db read, made again from its basis on the commit it read": `(render (d/commit-as-db db (:commit b)) b)`. The basis's `:commit` is the commit it read (`d/commit-as-db`, Datahike's as-of by commit id).
- `context.clj:421-425` `turn-text`; `:432-459` `request-text` / reader text, same `commit-as-db`.
- `context.clj:462-485` `messages` from a basis; `:493` finds the last turn with a basis for a run (`d/q` over `:seon.turn/run`).
- `turn.clj:146-170` `basis!`: records `:commit (d/commit-id (agent/db))` (the commit it reads), task, run, turn, origin, observed, `:message-ids` (facts), `:messages` (not facts), `:last-evals`.
- `turn.clj:218` and `:261` `:seon.turn/basis basis` written with the model turn and the outside (`:outside`) turn.
- `src/seon/db.clj:362` `d/commit-as-db conn (d/merge-base conn branch main)`; `db.clj:367` main's commits are read from the store, not replayed.
- `src/seon/fork_server.clj:79` `d/commit-as-db (:store st) (:commit m)`; `writer.clj:19`, `:48` the same.
- Worktree: `context.clj` has 554 changed lines; `context.clj` now carries `:seon.turn/sent` (the sent text) and `:similar` as a measured option (per commit c79a6394f and 0e4c9cec2 messages; not re-read).

## 8. Fork and restart facts (for "start it again")

- `docs/processes.md` (lead): "Every agent is its own OS process, forked from one single-threaded fork server that holds main's db value."
- `src/seon/fork_server.clj:50` `server-step` handles `:tx` and `:fork` from one FIFO pipe; an agent forked after commit k sees exactly k.
- `src/seon/agent.clj:734` `init!` (its args at `:757`) takes `resumed` and `commit` and `t`; `:795-800` a resumed branch takes its task from main.
- Measured start cost is from the owner's message (0.08 s warm), not re-measured here.
- `src/seon/root.clj:472-478` fork-server restart (`:restarting`, restarts counter).
- `src/seon/fork_server.clj:237` promotes a standby (`:restarts` incremented).

## 9. Decisions and wiki pages

- `docs/decisions.md` (commit 3984b642d, 2026-10-25): "## 2026-10-25 rulings" with three entries: "Root is main; everything else is the same system with different config"; "Agents restart from their record; the lifecycle is Flow's" (owner: "Can we just have the flow step function and the lifecycle handle the forking and killing?"; "A message wakes or restarts an agent; ... being researched ... Stated in: not yet"); "The screen is sent as diffs between turns" (`(seon.screen/show {:since t})`).
- `docs/flow.md` lead: "Every Seon component is a Flow proc, and Jolt's Flow is upstream's API over Jolt's own `reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj`; this page states the proc contract ..."
- `docs/processes.md` lead: "Every agent is its own OS process, forked from one single-threaded fork server that holds main's db value, so it starts in milliseconds ..."
- `docs/agent.md` lead: "You are a Seon agent: you work one task at a Clojure REPL on your own branch, and every change you make is a form you evaluate."
- `docs/turn.md` lead: "An agent works its task in a loop inside its own process: observe the branch, end if the done rule refuses nothing, otherwise send the context to the model and evaluate its reply, then observe again."
- `docs/core-async.md` lead: "core.async on Jolt is one waiter protocol over Chez mutexes, condition variables and fibers, with no go thread pool and no cap on pending ops ..."
- `docs/restart.md` lead: "How a fresh root picks up the work when every Claude session, memory and transcript is gone ..." (root restart, not agent restart).
- Other pages found but not read in full: `docs/root.md`, `docs/tasks.md`, `docs/context.md` (does not exist yet; decisions say owed), `docs/vector.md`, `docs/failure-points.md`, `docs/clocks.md`.

## Notes on what was not checked

- No REPL, tests or builds were run (read-only).
- The `:workload :io` on agent_proc is read from source, not measured.
- The agent-conversation worktree's `seon.agent` and `seon.turn` changes were read only at the lines cited; the full wiki sections for the worktree (docs/agent.md, docs/turn.md on that branch) were not diffed.
