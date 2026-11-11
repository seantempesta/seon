# Sessions: how a Claude session works

How the Claude sessions that build Seon share the work: who owns what, how the owner is asked, what a helper is given and how its work is reviewed. The hard rules are [AGENTS.md §2](../AGENTS.md); how a branch lands is [merging](merging.md#a-branch-lands-with-binland), and the machine's rules are [machine](machine.md).

## Sessions own their areas

A named session owns one area of Seon and knows all of it: Context (what an agent reads), Web (what a person sees, and the servers), Optimization (the running system's cost and correctness in Seon's own code: an agent's turn, boot, fork and the store, the data model, merges and write-back; it reads the cost line of other sessions' plans), Tests (the test system: selection, digests, the verdict cache, the runners, the full run, slow tests), Deep fixes (performance and correctness inside the dependencies: Jolt, Datahike, konserve; the edge with Optimization is where a cost bottoms out), and one managing session that keeps the landing order and reads each landing's audit ([merging](merging.md#a-landing-message-carries-its-audit)). What each has in flight is in [plan](plan.md).

- **A session holds its area's design, code and state in its own head** (owner, 2026-11-08: "We are moving away from monolithic orchestrators who don't know anything and implementation agents who don't know the big picture"). It reads the code a helper changes, takes the measurements that decide a design itself, and never answers for code it has not read.
- **No fixed ceremony** (owner, 2026-11-06: "I don't want the work done twice. If you do it well merge it. If optimization does it well merge it. If you hand something off to optimization just do it. No more ceremony and mandated fixed policies."). Whoever proves a change lands it. Each session says by message what it is taking, and hands another session only work that session can do. Agents doing the wrong thing is a bug for whoever owns the cause. Coordination files and wake pipes are not used.
- **The Web session owns servers and demos** (owner, 2026-11-08: "Okay just take over all responsibility for servers and demos for me"): the standing root (server 7888, dashboard 7777), demo roots and what is left of them. Other sessions ask Web for server and demo work, and send the standing root only read-only evals unless the owner himself says yes to a write; another session's relayed ask is not his yes. The owner tests at http://127.0.0.1:7777/, so after any landing `bin/root status` is read and 7777 is seen to answer.
- **Taking over an area starts with a list of what exists** (owner, 2026-10-27: "a new agent already has a plan? that can't be well thought out"): each piece with its file and line, its job, and keep, extend or delete, before any design.
- **A session and the owner spend their time on plans** (owner, 2026-10-28: "It would be good to have you mostly be working on the details of plans with me so we have less ambiguity too"): what to change, where, what it replaces, the proofs and what done means, written into the task entry ([tasks](tasks.md)).

## The managing session

One session manages the others; it owns no area of the code (owner, 2026-11-10: it runs point for the area sessions, agrees the strategy with him, distributes the work, audits and lands).

- **It keeps the schedule:** one page, rewritten whole when it drifts, holding what is on main, the next batch's members, each session's items in order, the rulings it made while the owner was away and the decisions waiting on him. Every recommendation becomes an item with an owner the same turn (owner, 2026-11-10: "Don't just make recommendations to me and then let them slide away").
- **It lands in batches** ([merging](merging.md#the-managing-session-lands-a-batch)), and each landing note says what was taken out and why.
- **It rules what is its to rule and asks the owner the rest,** one question at a time with the question tool, its pick first: a design rule, a dependency choice, a cost bound, the standing store. While he is away it decides and lists each ruling for him to reverse.
- **It verifies before it repeats.** A session's claim is checked by one command before it reaches the owner, and what was not checked is called a report or an inference. The owner's sentence is quoted, never paraphrased into a design, and the code is read before his sentence becomes a request to build: on 2026-11-10 "tracked reads" was sent out as work when both mechanisms existed ([render](render.md#reactive-renders)).
- **An absurd number is the finding.** It asks what the thing should cost before it chooses among options framed inside the number (owner, 2026-11-10: "Please ask wtf more often and don't let crazy shit slide"), and it asks the same of every plan: n, the big-O, the number expected.
- **It gives the simple version first.** When the owner says a design is too much, the data model is usually what is wrong, not his understanding (2026-11-10: a place declared on each join, against "render functions that recognize a top level render").
- **It watches the clock on what it started:** a prove's expected end is written down when it starts, a run far past it is a hang to capture, and waits run in the background while other work goes on.
- **A review goes to the session that owns the area** (owner, 2026-11-10: "reviewed by a competent agent in their speciality"): a task a Seon agent finished, a helper's diff in another session's namespace, a claim about another session's code.

**A change that moves every verdict is run whole once** (owner, 2026-11-10: "just run it once so the other runs don't duplicate all the tests"). Before landing a dependency pin or a change to what root runs or to the runner, the managing session tells every session to hold its proves, lands it, and says go when that landing's greens are stored; two such changes never prove side by side.

## The way improves

The standard is the best way known, written down, until a better one replaces it ([AGENTS.md §1](../AGENTS.md)). What turned a pain into a part of the way on 2026-11-10, as the pattern to follow:

- every session proving each small change alone (a machine flooded, 25-minute proves) became one batch proved once ([merging](merging.md#the-managing-session-lands-a-batch));
- "erase your store after an edit from outside" became a root that catches up with its checkout at start ([source](source.md#a-boot-catches-up-with-its-checkout));
- agents' finished work lost at each erase became an `:unsynced` problem and a reviewed sync ([source](source.md#a-merged-task-reaches-mains-files));
- a root that forked no agent after every landing became one definition of a tree's id ([merging](merging.md#a-branch-lands-with-binland));
- three branches red on a law they had not run became the pre-tip checks, and those are becoming one script (task `seon.fx/process-tests-run-from-inside` and the plan it belongs to).

How to do it: name the cost with its number; find what allowed it; change the script, the check or the wiki section so the better thing is the default; delete the old way in the same change; tell every session what to stop doing. A lesson kept only in a session's memory is lost at its next compaction.

A compaction is where a session's own lessons are most easily lost, so it is where they are handed up: before it compacts a session reflects on its stretch of work and reports to its parent what would have saved it time; the parent turns each suggestion into an owned item of the plan or a small change made at once, and the session writes its findings into its state note and its post-compaction prompt (owner, 2026-11-11: "lots of base hits rather than swinging for homeruns"). The managing session does the same toward the owner. A Seon agent's form of this is its task: what it learned goes into the task's plan, so its evals can be dropped and its context made again from the task and its entity's data.

**A session leaves the system up to date where each fact lives** (owner, 2026-11-11: "grep around and find the parts that might be out of date and not just edit an append log.md ... Over time I want to stop drift"). Before it concludes or compacts, a session greps the wiki, the task files, the docstrings and the schema descriptions for every place that states what its work changed, makes each true where it is, removes the task entries it completed and rewrites the plans it changed. One new page that restates the rest is drift.

## No fan-out

A session does its work itself, in one conversation, and starts no other session unless the owner asks (owner, 2026-10-28: "This system of launching 10+ agents just burned my tokens and was not productive"; and "let's stick with just you and me and any subtasks you do are one use agents that are sonnet and haiku and mostly just to find info for you to read and you can preprocess and run longer tests on well spec'ed ideas (especially repl interactive building) with sonnet").

The night of 2026-10-27 ran sixteen sessions and a merge session, with wake pipes and orders files, each re-deriving context and reworking the others' branches. About a fifth of a week's tokens went on churn (109 merges, 6 Jolt pin bumps, 591 GB of worktrees), and the machine reached a memory limit at load 35. The tooling built to serve that parallel work is the second lesson of the night ([decisions](decisions.md#build-the-product-not-infrastructure-owner-2026-10-28)).

A session uses as many helpers as its work needs (owner, 2026-11-10: "What I never said one helper? Yeah allocate whatever resources we need"): each is briefed from a plan the session understands, owns its own files so no two write the same ones, and has every line reviewed by the session. What the night above got wrong was sessions nobody steered reworking each other, not the number of hands; the machine still bounds it ([machine](machine.md#processes)).

**No agent on the costliest model without the owner's yes** (owner, 2026-11-10: "Do not launch any fable agents without my approval"). Say which model a helper or a session would run on before starting it, and give a helper the cheapest model that fits its piece.

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

A brief is complete, or the subagent spends its turns rediscovering. Every brief states: A brief for anything in a shared path (the store's locks, a write, a render every page uses) names the caller's path the helper must run before it reports, not only its own test: a helper's "it works" on its own test has been wrong where the system's heavier path was not run (the first lock layer failed only under two processes with threads). A change to a lock or to durability is read whole by its session before it is called reviewed.
1. **the question**, and which step of THE PLAN it serves;
2. **what is known**, as paths: the task entry, wiki sections, the exact failing command and its output;
3. **the scope**: read-only, or what it may run and which files it owns; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and its evidence (a reproduction, a file and line, measured numbers);
5. **where the result goes**: a short reply, or one file.

Check what a subagent returns (a reproduction, a file and line, a number) before acting on it: its report is a hypothesis until measured ([machine](machine.md#reading-a-number)).

## Stable links

A link the owner is given must still open after a restart (owner, 2026-10-29: "I can't see the changes. Always give me links or give me a stable way to find links on a stable server"). Agent ids start again at each boot, so link an agent by its branch (the dashboard's ``/agent/main/task-<task-name>`` opens the newest agent that worked the task), name the commit the root was built from, and bring the root he watches to that commit before saying a change is visible.
