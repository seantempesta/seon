# Sessions: how a Claude session works

How the Claude sessions that build Seon share the work: who owns what, how the owner is asked, what a helper is given and how its work is reviewed. The hard rules are [AGENTS.md §2](../AGENTS.md); how a branch lands is [merging](merging.md#a-branch-lands-with-binland), and the machine's rules are [machine](machine.md).

## Sessions own their areas

A named session owns one area of Seon and knows all of it: Context (what an agent reads), Web (what a person sees, and the servers), Optimization (cost, storage and the proofs; it reads the cost line of other sessions' plans), and one managing session that keeps the landing order and reads each landing's audit ([merging](merging.md#a-landing-message-carries-its-audit)). What each has in flight is in [plan](plan.md).

- **A session holds its area's design, code and state in its own head** (owner, 2026-11-08: "We are moving away from monolithic orchestrators who don't know anything and implementation agents who don't know the big picture"). It reads the code a helper changes, takes the measurements that decide a design itself, and never answers for code it has not read.
- **No fixed ceremony** (owner, 2026-11-06: "I don't want the work done twice. If you do it well merge it. If optimization does it well merge it. If you hand something off to optimization just do it. No more ceremony and mandated fixed policies."). Whoever proves a change lands it. Each session says by message what it is taking, and hands another session only work that session can do. Agents doing the wrong thing is a bug for whoever owns the cause. Coordination files and wake pipes are not used.
- **The Web session owns servers and demos** (owner, 2026-11-08: "Okay just take over all responsibility for servers and demos for me"): the standing root (server 7888, dashboard 7777), demo roots and what is left of them. Other sessions ask Web for server and demo work, and send the standing root only read-only evals unless the owner himself says yes to a write; another session's relayed ask is not his yes. The owner tests at http://127.0.0.1:7777/, so after any landing `bin/root status` is read and 7777 is seen to answer.
- **Taking over an area starts with a list of what exists** (owner, 2026-10-27: "a new agent already has a plan? that can't be well thought out"): each piece with its file and line, its job, and keep, extend or delete, before any design.
- **A session and the owner spend their time on plans** (owner, 2026-10-28: "It would be good to have you mostly be working on the details of plans with me so we have less ambiguity too"): what to change, where, what it replaces, the proofs and what done means, written into the task entry ([tasks](tasks.md)).

## No fan-out

A session does its work itself, in one conversation, and starts no other session unless the owner asks (owner, 2026-10-28: "This system of launching 10+ agents just burned my tokens and was not productive"; and "let's stick with just you and me and any subtasks you do are one use agents that are sonnet and haiku and mostly just to find info for you to read and you can preprocess and run longer tests on well spec'ed ideas (especially repl interactive building) with sonnet").

The night of 2026-10-27 ran sixteen sessions and a merge session, with wake pipes and orders files, each re-deriving context and reworking the others' branches. About a fifth of a week's tokens went on churn (109 merges, 6 Jolt pin bumps, 591 GB of worktrees), and the machine reached a memory limit at load 35. The tooling built to serve that parallel work is the second lesson of the night ([decisions](decisions.md#build-the-product-not-infrastructure-owner-2026-10-28)).

One helper at a time on a step, briefed from a plan the session understands; steer it with a message before launching another.

## Asking the owner

The owner makes the rulings, and a session brings him one decision at a time with its evidence.

- **Ask with the question tool,** in one round, each question with its background and your pick first (owner, 2026-11-09: "ask questions you are awaiting answers for with your questions tool"; 2026-11-06: "lets do a round of using your questions tool and give me background"). A big decision carries priced options, the simplest viable one first.
- **A well-scoped task, or a clear defect, needs no asking** (owner, 2026-11-06: "If you have a well scoped task please do it without asking").
- **Explain before changing** when he asks to understand.
- **A report tells what was verified from what is inferred,** and says what was not measured.

## Helpers by the shape of the work

A helper is chosen by the shape of the work, and is hands for a well-specified piece, never the one who understands it.

- **Long, well-planned work goes to a fresh Sonnet agent** with the written plan: it reads the task entry and the sections it cites, does the work, runs the targeted tests, commits and returns a summary. It costs only the plan plus its own work.
- **A fork of the session** (the same model, the whole conversation) takes short work that needs the conversation's context but would flood the session's own: reading many logs, sorting reds to their causes, a batch of small fixes. Measured 2026-10-28: a fork fixed five reds across four merged branches in 5 minutes, about 690k tokens in 46 steps, with no corrections; a fork given the heap fixes, several measured changes, ran long and paid for the whole conversation on every step.
- **Haiku finds and reads:** it searches the code, the wiki and the web, and returns paths, quotes and facts.
- **A harder piece may go to an Opus helper** when Sonnet has failed at it or would.

Note what each helper cost and returned, and refine this list from it.

## A helper works in its own worktree

A helper works in a worktree of its own, branched from the session's branch (``bin/worktree NAME jolt/<session-branch>``), commits there and stops: it never runs `bin/land`, never pushes, and never works in the main tree. One writer per tree: before handing anything off, the session decides what the helper does, which files it owns and what in flight it must leave alone. A helper that needs a root uses its own worktree's ([server](server.md#working-a-task-from-a-worktree)), never the session's.

## The session reviews every line

The session reads every line a helper writes and lands it itself; review is never delegated (owner, 2026-11-08: "Well spec'ed tasks can be handed to sonnet, but the opus agents are responsible for reviewing the code"; "I want you to review all core code").

- Read the whole diff against the plan and the laws, what it deletes first.
- Check each claim the helper reports: rerun a proof, read a number.
- **See each proof go red without the change.** A proof that passes without its fix proves nothing: a helper's writer threw on `char[]` (every model eval's `*out*`) behind a proof that passed either way.
- Only then merge the helper's branch into the session's and land it.
- A helper's change to core code that landed unread is reviewed as soon as it is found. On 2026-11-08 such a review found `:var/meta` storing instrumentation keys, so 491 of 2,431 contracted vars' facts differed by which process imported them.
- When a helper finds a gap in the design, stop and settle it with the owner; a helper never invents a counter or a fact to work around it.

## Briefing a subagent

A brief is complete, or the subagent spends its turns rediscovering. Every brief states:
1. **the question**, and which step of THE PLAN it serves;
2. **what is known**, as paths: the task entry, wiki sections, the exact failing command and its output;
3. **the scope**: read-only, or what it may run and which files it owns; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and its evidence (a reproduction, a file and line, measured numbers);
5. **where the result goes**: a short reply, or one file.

Check what a subagent returns (a reproduction, a file and line, a number) before acting on it: its report is a hypothesis until measured ([machine](machine.md#reading-a-number)).

## Stable links

A link the owner is given must still open after a restart (owner, 2026-10-29: "I can't see the changes. Always give me links or give me a stable way to find links on a stable server"). Agent ids start again at each boot, so link an agent by its branch (the dashboard's ``/agent/main/task-<task-name>`` opens the newest agent that worked the task), name the commit the root was built from, and bring the root he watches to that commit before saying a change is visible.
