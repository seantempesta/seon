# Sessions: how a Claude session works

How the Claude sessions that build Seon share the work. The rules are [AGENTS.md](../AGENTS.md) sections 2 and 3, and this page is their detail. How a branch lands is [merging](merging.md#a-branch-lands-with-binland), and the machine's rules are in [machine](machine.md).

## Sessions own their areas

Each named session owns one area and knows all of it:

| Session | Area |
|---|---|
| context | what an agent reads: its context, the turn and its request, the rounds and the `seon-driver` agents |
| web | what a person sees, the server's ops and the MCP relay, and the standing system on 7888 and 7777 |
| runtime | the running system's cost and correctness: effects, forks, loading, the update owner and admission, and the reactive core (`seon.ratom`) |
| tests | the test system: selection, digests, verdicts in the store, `:prove` and its clients |
| deps | the libraries and native code: Jolt, Chez, Datahike and konserve, their pins, and the binary's identity |
| data | schemas, pull patterns and keys, and the one merge |
| flow | Jolt's Flow and Seon's use of it: procs, their start, drain and stop, and the list of state and waits outside Flow (`seon.problems/nothing-inside-seon-crashes-or-hangs`); the merge gate inside the system (`seon.fx/proving-runs-as-flow`); the switch-over design (`seon.system/an-update-loses-nothing`); messaging (`seon_inbox`, `seon_message`, attach of `session/<area>`); and the review of every design that waits |

Each works on branch `jolt/<session>` in `.worktrees/<session>`, except two older paths: runtime's worktree is `.worktrees/optimization` and tests' is `.worktrees/tests-reads`. Root works in `.worktrees/root`. `.worktrees/merges` (branch `jolt/merges`) is no session's: it is the write-back's tree, where Seon commits main's changed files (`docs/source.md#commit-the-merges-branch`).

- A fix that adds, moves or reads an attribute, ref or pattern clause goes to data first, with the pull form wanted.
- A session reads the code a helper changes, takes the measurements that decide a design itself, and never answers for code it has not read.
- Taking over an area starts with a list of what exists: each piece with its file and line, and whether to keep, extend or delete it.
- A session lands its own change and tells root the sha. What a session takes is on the schedule, so nothing is done twice.
- A time a session states is read from `date`, never from its own sense of the hour (2026-11-14: sessions ran 30 minutes ahead, and a waiter filtered `--since` a time still in the future). A wait on another session's work keys on git ancestry or a message (`git log <sha>..main --grep=...`), never on a time.

## The managing session

One session, root, manages the others and owns no code.

- **It keeps the schedule:** what is on main, each session's next items in order, the rulings it made and the decisions waiting on the owner. Every recommendation becomes an item with an owner the same turn.
- **It reads each landed diff** within the hour and files what it finds; each session lands its own branch.
- **It asks what the work should cost before it accepts a number.** Every plan carries an estimate worked from what must be done ([AGENTS.md §3](../AGENTS.md)). An absurd number is the finding, and a measurement 10 times over the estimate stops the work.
- **It asks whether a process is dumb before following it.** A step that reruns work, waits on a person for no decision, or exists to work around another step is changed at once, and the old text is deleted.
- **It rules what is reversible and asks the owner the rest,** one question at a time. While he is away it decides, and lists each ruling for him to reverse.
- **It verifies before it repeats.** A claim is checked by one command before it reaches the owner, and what was not checked is called inferred; a delegated agent's hypothesis stays a hypothesis in what root tells the owner. A question it cannot answer from verified facts gets "not yet" and the name of what is gathering the answer (owner, 2026-11-15: "Always defer and wait for data than answer without knowing"). The owner's meaning is written in correct terms, never quoted (asked first when unclear), and the code is read before it becomes work.
- **It writes to the owner in Clojure's and the libraries' words,** briefly, with the number and the one decision.
- **It checks identity and content before it acts on anything it cannot undo:** a branch's commit by what the commit holds, a process by its command and working directory, a store by a query. A branch moved to the wrong commit on 2026-11-14 because a commit id was taken from an unordered set.
- **It stays out of the merge path.** It runs no `bin/update` by hand while another land updates, checks any gate it scripts immediately before the act, runs every command it hands to all sessions on the live machine first, and, while admission is missing, sets the order of ready lands by what each fixes. On 2026-11-15 one hand update beside a pin's update left root unrestarted, the restart that followed cut another land's update and reaped the owner's session, and a gate count that also counted waiting shells would have blocked every land.
- **It holds a land that restarts root or loads code into it while a watched `seon_done` runs,** and tells the session that owns the land.
- **It keeps every session working.** A session idle with work queued gets its next item at once, and a session that waits on something sets a background wake instead of ending its turn idle. On 2026-11-14 nothing landed from 02:52 to 06:00 because three sessions sat waiting for messages root could not send.
- **It sends few messages.** The app pauses a session's sends after 20 since the owner last typed, so root batches its rulings, sends no acknowledgements, writes what it cannot send into its ledger, and never routes around the pause.
- **After a long run on its own, it has the work reviewed by a fresh agent** that has not seen root's conclusions, from git, the land logs and the store, and it checks that review's claims before passing them on.

### What it reads itself, and what it hands off

It hands off what has one right answer and a long output: a long read, a sweep of files, a model of costs, a check of many landed messages, to a read-only agent (Sonnet) briefed with [principles](principles.md) first, which answers in a few hundred words with each claim marked measured or read; root judges the answer and checks the line it rests on (owner, 2026-11-15: "Use more sonnet agents for modeling ideas, processing data and giving you an answer or evidence ... switch to an expert bullshit detector and project manager"). It reads itself what a ruling rests on: each landed diff, a design note, a message that asks for a ruling. Nobody bisects history to find a bug: the live failure is read and probed. A ruling that rests on what the code holds waits for the session that owns that code to answer.

## The way improves

The standard is the best way known, written down, until a better one replaces it. When the way hurts:
1. name the cost with its number;
2. find what allowed it;
3. change the script, the check or the wiki section so the better thing is the default, deleting the old way in the same change;
4. tell every session what to stop doing.

Claude Code refuses a shell command whose text it cannot check for a dangerous removal (an `rm` inside a heredoc or a Makefile recipe written through the shell), and only a person may approve it: write such a file with the editor instead.

A lesson kept only in a session's memory is lost at its next compaction. Before compacting, a session reports to its parent what cost it time and which script or check would have caught it. It then makes each fact it learned true where that fact lives.

## Live agent runs find the system's problems

A session runs Seon agents on real tasks to find what in the system cost the agents turns, never to make one task go green. For each stretch where the agent lost turns, it names what allowed it and mails the owner of the cause. A fix is never for the run's task alone. What the agent refined reaches disk by review ([source](source.md#a-merged-task-reaches-mains-files)).

## A session works through its Seon agent

A Claude session does what a Seon agent does, through the same calls; each line is the agent action that replaces the session's own machinery once [Stage 1](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority) is safe to start.

| A session's need | The agent action | Today |
|---|---|---|
| Change code, a doc, a task or a `bin/` script | `seon_attach` its area's agent, then `seon_eval` with ```seon.repl/edit!``` or a definition | built |
| Change a file it edited on disk | its import, as a transaction on the agent's branch | not built (`seon.system/a-sessions-files-are-a-transaction-on-its-agents-branch`) |
| Run its tests | `(my.agent/prove)`, by what the change reaches | built; its process tests wait on the gate (`seon.reconcile/the-gate-proves-the-candidate-branch`) |
| Merge main in | `(my.branch/merge-main!)` | built; costs 8.2B ticks a merge (`seon.agent/a-merge-of-main-applies-every-change-main-made`) |
| Land | `seon_done`: the judge, the check, Seon's merge into main | built; merged one agent task (pilot run 15, 2026-11-16); sessions move onto it at the checklist's step 7 |
| Git follows | the write-back of main's changed files | commits to a side branch (`seon.source/a-merge-moves-gits-main-to-the-stores-files`) |
| Learn that what it waits on merged | a message to its agent from the merge | built (```seon.task/landed-messages```) |

Until then a session lands through its worktree and `bin/land`, and only the tasks that make this path work do.

## Messages

Work that blocks another area is a task in that area's namespace, with its evidence, named in the blocked task's `:seon.task/needs` ([the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority)). A session messages the owning session directly, through the app's session messages, only to block, unblock or agree an interface: 6 lines or fewer, the evidence and one ask, no acknowledgements ([decisions](decisions.md#blockers-are-tasks-sessions-message-the-owning-session-directly)). A message is the home of no fact: what it teaches goes into the wiki section or task entry that owns it.

## No fan-out

A session starts no other session unless the owner asks. It uses as many helpers as its work needs: each is briefed from a plan the session understands and owns its own files. Every session and helper runs on Opus or Sonnet, never Fable without the owner's yes.

## Asking the owner

The owner makes the rulings, and a session brings him one decision at a time with its evidence.
- Ask with the question tool, in one round, each question with its background and your pick first.
- A well-scoped task or a clear defect needs no asking.
- A report separates what was verified from what was inferred.

## Helpers

A helper is hands for a well-specified piece. It works in the session's one worktree, on files nobody else is editing, commits there on the session's branch and stops: it never lands, never pushes and never works in the main tree. A helper that edits only docs runs no `bin/` command. A brief states:
1. the question, and which step of THE PLAN it serves;
2. what is known, as paths;
3. the scope: what it may run and which files it owns;
4. the estimate of what the work should cost;
5. what done looks like, and where the result goes.

## The session reviews every line

The session reads every line a helper writes and lands it itself; review is never delegated.
- It reads the whole diff against the plan and the laws, what the diff deletes first.
- It sees each proof go red without the change.
- When a helper finds a gap in the design, the session settles it; a helper never works around one.

## Stable links

A link the owner is given must still open after a restart: link an agent by its branch (the dashboard's `/agent/main/task-<task-name>`) and name the commit the system was built from.
