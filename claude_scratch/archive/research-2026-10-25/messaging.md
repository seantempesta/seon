# Messaging: map and proposal (read-only research; main a2f2578ee, branch jolt/agent-conversation)

## 1. Every path a message travels today

| Path | Where | What it does |
|---|---|---|
| owner/page/MCP -> agent | web.clj:85-88 (`>` prefix in the submit box) and server.clj:123-125 (`:message`, MCP seon_message) -> `seon.root/message!` root.clj:991-997 -> request table root.clj:943 `{:op :message :from :owner}` | root.clj:371-377: refused `gone` when no live agent (root.clj:372). Else writes a fact on MAIN (`task/message-tx`, task.clj:176-185: id from to(run ref) body) and, only after the writer answers (root.clj:432-433), calls deliver-message |
| agent -> agent | `my.agent/send-message` (my/agent.clj:29) -> `seon.agent/send-message` agent.clj:107-110 `{:op :send}` -> agent_proc.clj:176 `:to-api {:op :message}` -> root.clj:486 `deliver-message` directly | NO fact, no refusal: `to-agent` (root.clj:225-228) is a no-op for a gone agent, so a send to a dead agent vanishes silently |
| deliver-message | root.clj:230-240 | sends `{:op :write :msg {:op :message ...}}` to the agent proc; includes `:id`/`:to` only for the owner's (fact) messages |
| agent inbox | agent.clj:661-663 (`:message` -> `:inbox` atom, replies `:received` for a latency stat); `inbox` agent.clj:80-93 drains the socket first; `messages` agent.clj:95-100; `taken!` 103-106 | also fed by `:fx-reply` (agent.clj:649-652: async effect outcomes as inbox messages) and, per the docstring, root's requests |
| the turn takes it | `seon.turn/basis!` turn.clj:147-174: splits inbox into facts (have `:id`) and others; writes facts on the BRANCH (same content id as main's), commits, `taken!`; basis `:observed :message-ids` / `:messages` | non-fact sends live only in the basis |
| facts | seon.facts.edn:346-354 `:seon.message/{id from to body reply-to}`; `to` is a ref to the RUN; `reply-to` is declared and never written | |
| in the context | main: `context/taken-messages` context.clj:232-241 -> current-state `:messages` (a section of the block). Branch: `context/records` (context.clj:290-303) puts each message the run took in tx order as a USER message (`message-messages` :261) | |
| UI | page's `>` (above); `:seon.message/_to` is a unit of the run page (facts.edn:246); ui.clj:477 renders a message row | |
| refusals | only the owner path: `gone` (root.clj:40-59, 372). Agent sends and fx replies are never refused | |

## 2. What happens to a message when...

- Mid-turn: socket frame read by `agent/inbox`/`await-op!` (agent.clj:69-78, 107) lands in `:inbox`; taken at the next turn's `basis!`. Not lost. (Mid-eval: read at next `inbox` call.)
- Between turns: same, taken at the next turn. Run ended with it unread: lost to the model (the fact exists on main, nothing reads it).
- Run ended `:waiting` / `:cap` / `:error` / `:red`: the run is judged (reconcile.clj:327-354); a non-empty done-rule reason is `{:op :hold}` (reconcile.clj:330-332, 350-354): task `:running -> :held` + `:seon.task/failure` + the agent killed (:diffed -> :kill :ended, reconcile.clj:~697). A message now is REFUSED (owner path) or DROPPED (agent path). Green end (`:waiting` with empty reasons cannot happen: `empty? reasons` ends `:green` first) -> `:accept` -> done -> the task is finished forever (`idx-done?` reconcile.clj:127-128; `idx-ready?` :130-140).
- Process gone: same as ended: refused / dropped. Root restarted: root's agents map is empty, so every message is refused; an unread fact on main is not looked at.
- Release: `my.root/run!` = cas `:held -> :running` (my/root.clj:39-56): the reconciler (idx-ready?, reconcile.clj:130-153) spawns the next run, a NEW process, run id n+1, same branch. `my.turn/wait` (my/turn.clj:12-14, `request-stop! :waiting`) ends the run; the branch's prose-only reply does the same (turn.clj:247-250 branch). The docs say "root holds the task": waiting IS held-for-release today, and the hold is written as a FAILURE (`hold-tx` reconcile.clj:453-458) so quiescence reports it as a root cause.
- "The user is an agent": on the branch yes (`task/boot-tx` task.clj:164-173: `(agent-tx boot :user {})`, per boot, no process; `:seon.eval/by` names it). On main no. Messages' `from` is the string "owner" (root.clj:943 `:from :owner`), not the user entity. Nothing addresses "user" yet; no agent can send to it (root.clj:372 would refuse: no live agent).
- Branch gap: `context/records` is RUN-scoped (evals `(= run (:run record))`, messages `to` the run), so a second run on the same branch starts a blank conversation. Messaging after a stop needs this scoped by branch/task.

## 3. How many mechanisms (count 7) and where they duplicate

1. owner request `:message` (fact on main, refused if gone) 2. agent `:send` (no fact, dropped if gone) 3. `deliver-message` (shared tail, but 1 and 2 enter it at different points) 4. the `:inbox` atom (3 feeders: sends, owner, fx-replies) 5. basis!'s two classes (facts -> `:message-ids` vs others -> `:messages`: two readers in `taken-messages`) 6. the fact written twice (main by root, branch by the turn) 7. `my.turn/wait`/held/release as the only wake. Duplicated: 1 vs 2 (same job, different recording and refusal), 5 (two representations of one thing), 6 (two writes of one fact), the page `>` special-case (web.clj:85) beside `seon.root/message!`, and `from` strings "owner"/run id/`:fx`.

## 4. Turn loop and the reconciler today

- seon.turn/work! loop (turn.clj branch ~262-300): observe; empty reasons -> `:green`; `stop` -> end with `:waiting|:failed`; cap -> `:cap`; no-progress -> `:no-progress`; else act!. A prose-only reply (branch) calls `request-stop! :waiting`. The process never parks: `end!` writes the end, the reconciler judges and kills it.
- Runnable = `idx-ready?`: task execution `:running`, not done, needs done, no live or judged run (reconcile.clj:130-140); ready set refreshed per dirty task (:146-153), started up to max-running (:378-389). `:held` tasks are never ready; `release!` (cas held->running) is the only way back. Nothing makes an unread message a reason.

## 5. Proposal (simplest, reusing what exists)

**One function.** `seon.root/send!` (root.clj, replacing `message!` and the `:message` branch at 371-377) and one agent-facing `my.agent/send` (replaces `send-message`; same wire op `:send`, now going through the same request): `(send! from to body)` where `to` is an agent id, a task id, or `:user`. It does ONE thing: transact the `:seon.message/*` fact on main (id, from, to, body, optional reply-to), and when the writer answers, deliver:
 1. recipient is a live agent -> its inbox via the existing `deliver-message` (mid-turn or between turns; read at the next turn);
 2. recipient is a task with no live agent (waiting, capped, errored, root restarted, process dead) -> in the SAME transaction, the existing release: `[:db/cas task :execution :held :running]` (`task/execution-tx`, my/root.clj:54). The reconciler's existing ready set starts the next run (new process, same branch), whose first turn reads the fact. Reactive, no poll, no new reconciler rule, no new status;
 3. recipient `:user` -> the fact only (the user agent has no process; the page's timeline shows it; the page's input box and MCP seon_message are `send!` from `:user`).
 Refusal becomes typed data only when nothing can wake it: task finished/merged (`idx-done?`), cancelled, or unknown id.

**The loop.** Keep ending the run on `:waiting` (do NOT park a process: a parked fork per waiting task costs memory on the 16 GB target; a stop plus fork-on-message is cheap, COW). Change `judge`: outcome `:waiting` holds the task WITHOUT `:seon.task/failure` (waiting is not a failure; quiescence stops reporting it as a root cause). `:cap`/`:error`/`:no-progress` still hold with failure, and a message wakes those too (release is the same cas), so "out of turns, then the user writes" restarts it with a fresh turn budget. A NEW RUN (not the same run extended): runs are the unit of judging, killing, cost and turn cap; extending one would mean a process that outlives its judge. A new run is already what release does.

**Continuity.** With the branch's conversation, `context/records` must read the branch's conversation, not the run's (drop `(= run ...)`, select messages by the agent/branch). Same branch => same conversation; the task story is the run message again.

**Replies.** A prose-only reply = `(send! me <sender of the last message taken, else :user> text)` with `reply-to` set (the declared, unused attr). `:seon.turn/reply` stays the model's output for replay; the message fact is the addressee's. Agents also call `(my.agent/send :user "...")` or `(send "a3"/"ns/task" "...")` anywhere in the REPL. An agent that asked another agent a question and ended `:waiting` is released by the answer, the same way as the user.

**Deletes (estimates).** `message!` + `:message` request branch + `deliver-message`'s fact/no-fact split (~35 root.clj); agent-side `send-message`+`:send` special path (~10); `basis!` facts/others split and `:messages` basis key + `taken-messages` second branch (~15); the page's `>` special-case in web.clj (~6, the box sends `send!` for a leading `>` still, or always prose to the agent when no form); fx replies keep the inbox but as `from :fx` facts is not needed (leave). Adds: `send!`+resolution to task/agent/user (~35), the `hold-tx` waiting variant (~8), records scoping (~6), the MCP/page rewire (~10), help (~8). Net about -20 src lines and one concept where seven were.

**Needs an owner ruling.**
1. `:seon.message/to` target. Today a run. A restarted run id is new, so the stable address is the task (or the agent's branch). Recommend: `to` = task entity (ref), or the user agent entity for `:user`; replaces the run ref (the run page's `:seon.message/_to` unit becomes the task's). Schema change, store erased (DECISIONS).
2. Read marker: wake needs no marker (release is a cas, a spent release is a no-op), so NO `:seon.message/taken` is required. The branch's own fact (written when the turn takes it) is the read record. Recommend not adding it.
3. A waiting hold without `:seon.task/failure` (no new status; `:held` with an `:waiting` run outcome). New status is NOT proposed.
4. Does a message reopen a finished/merged task? Recommend NO (refused: file a new task); done is derived from runs and permanent.
5. `from` identity: replace the "owner" string by the user agent entity ref (it exists on the branch, `boot-tx`), so owner and agents are one kind of sender. The user entity is per boot; messages across boots would name different users. Owner call: one stable user key.
6. Whether a prose-only reply is also a message fact (duplicates the turn's `:seon.turn/reply` text) or only the turn record (then agent-to-agent replies need the fact anyway). Recommend always.

## 6. Help section draft (docs/agent.md, "Messaging")

```
## Messaging
- `(my.agent/send to body)` sends a message to an agent id, a task id, or `:user`; the user is an agent too. It is a fact (`:seon.message/*`) and answers nil, or an error map when nothing can receive it (a finished task).
- Messages can arrive any time. A running agent reads them at its next turn, in the conversation as user messages `{:from :body}`; `:from` is who sent it (`"user"`, an agent id, a task).
- A reply with prose and no eval call is a message back to whoever wrote you last (the user if none), and stops your run. Finishing is still `(my.task/done!)`.
- A stopped run (waiting, out of turns, failed) is started again by the next message to its task: same branch, same conversation, a fresh turn budget.
- To ask another agent and wait: `(send "ns/task" "...")` then answer with prose; its reply starts you again.
```
