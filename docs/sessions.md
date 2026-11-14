# Sessions: how a Claude session works

How the Claude sessions that build Seon share the work. The rules are [AGENTS.md](../AGENTS.md) sections 2 and 3, and this page is their detail. How a branch lands is [merging](merging.md#a-branch-lands-with-binland), and the machine's rules are in [machine](machine.md).

## Sessions own their areas

Each named session owns one area and knows all of it:

| Session | Area |
|---|---|
| Context | what an agent reads |
| Web | what a person sees, and the servers and demos (the standing system on 7888 and 7777) |
| Optimization | the running system's cost and correctness in Seon's code: the turn, boot, fork and store |
| Tests | the test system: selection, digests, the verdict cache, the runners and slow tests |
| Deep fixes | the dependencies: Jolt, Chez, Datahike and konserve |
| Data model | every change to schemas and pull patterns; Web and Context are its consumers |

- A fix that adds, moves or reads an attribute, ref or pattern clause goes to Data model first, with the pull form wanted.
- A session reads the code a helper changes, takes the measurements that decide a design itself, and never answers for code it has not read.
- Taking over an area starts with a list of what exists: each piece with its file and line, and whether to keep, extend or delete it.
- A session lands its own change and tells root the sha. What a session takes is on the schedule, so nothing is done twice.

## The managing session

One session, root, manages the others and owns no code.

- **It keeps the schedule:** what is on main, each session's next items in order, the rulings it made and the decisions waiting on the owner. Every recommendation becomes an item with an owner the same turn.
- **It reads each landed diff** within the hour and files what it finds; each session lands its own branch.
- **It asks what the work should cost before it accepts a number.** Every plan carries an estimate worked from what must be done ([AGENTS.md §3](../AGENTS.md)). An absurd number is the finding, and a measurement 10 times over the estimate stops the work.
- **It asks whether a process is dumb before following it.** A step that reruns work, waits on a person for no decision, or exists to work around another step is changed at once, and the old text is deleted.
- **It rules what is reversible and asks the owner the rest,** one question at a time. While he is away it decides, and lists each ruling for him to reverse.
- **It verifies before it repeats.** A claim is checked by one command before it reaches the owner, and what was not checked is called inferred. The owner's sentence is quoted, never paraphrased, and the code is read before his sentence becomes work.
- **It writes to the owner in Clojure's and the libraries' words,** briefly, with the number and the one decision.

### What it reads itself, and what it hands off

It hands off what has one right answer and a long output: a long read, a sweep of files. It reads itself what a ruling rests on: each landed diff, a design note, a message that asks for a ruling. Nobody bisects history to find a bug: the live failure is read and probed. A ruling that rests on what the code holds waits for the session that owns that code to answer.

## The way improves

The standard is the best way known, written down, until a better one replaces it. When the way hurts:
1. name the cost with its number;
2. find what allowed it;
3. change the script, the check or the wiki section so the better thing is the default, deleting the old way in the same change;
4. tell every session what to stop doing.

A lesson kept only in a session's memory is lost at its next compaction. Before compacting, a session reports to its parent what cost it time and which script or check would have caught it. It then makes each fact it learned true where that fact lives.

## Live agent runs find the system's problems

A session runs Seon agents on real tasks to find what in the system cost the agents turns, never to make one task go green. For each stretch where the agent lost turns, it names what allowed it and mails the owner of the cause. A fix is never for the run's task alone. What the agent refined reaches disk by review ([source](source.md#a-merged-task-reaches-mains-files)).

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
