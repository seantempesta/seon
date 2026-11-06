# The plan

What Seon is for, where it stands, the one step being worked now, and the steps after it. [AGENTS.md](../AGENTS.md) is how a session works; the [map](README.md) is how the system works; [decisions](decisions.md) are the owner's rulings; git is the history.

## The goal

**A user asks for something, and a Seon agent does it, end to end, while the user watches.** In full:

1. **A request becomes a task**: its plan (what to change and why) and its proofs (the tests that say it's done).
2. **An agent works the task at a real REPL**, in its own forked process on its own branch, in few turns:
   - it sees its world as data (`@ctx`, pulled from the db), with the task, the code its plan cites and what it last did;
   - every eval answers small: its value or an error, plus one line of what changed;
   - main's difference from its branch is in that line every time, so nothing at the end is a surprise;
   - the tests its change reaches run by themselves, by digest; a failure comes back as data it can fix.
3. **Done means green and merged**: the done rule checks the proofs at the branch's commit; the branch merges into main; main runs the result next.
4. **The user watches it live**: the agent's page is a chat (the task, the agent's reply, each eval with a one-line outcome), and the agent sees what the user sees.

**Measured as** turns to green and tasks merged. **Held to:** nothing crashes or hangs (every failure is data to the agent); everything shown is derived from the db at t; one mechanism per job; every change earns its place; a v1 capability is present or dropped on purpose ([parity](parity.md)).

## Now: one step

**One tree of data that renders itself, read by two clients.** The model's context goes through the same ratom components as the page: a component transforms its data, filtering it and adding what it needs from the db, into hiccup: the one state, drawn to html directly and read by the model. The order, what's settled and what the research found are on task `seon.context/the-models-context-is-the-walks`.

In flight, each on its own branch and merged when proved:
- **Agents merging real tasks, the first priority (this session).** The tree, the one-call tool, edits and true plans are on main. DeepSeek on main (deepseek-flash, thinking off, default temperature, 40 turns, 5 tasks, one run each, $0.0875): 0 green, 0 merged; a message on 98% of replies, an intent on 97% of evals, 1 repeat in 144, first own proof median turn 10, 0 whole-page re-reads. Order: (1) `jolt/deferred-proofs`: an agent's deferred process tests get a verdict while it can act, since 2 of 5 closed at merge with no turn left; (2) rerun the same 5 tasks and read them; (3) stray `def` probes that become program, about 9 turns across agents; (4) measure thinking and a stronger model on the 3 tasks that hit the cap with their proofs red, each arm's cost stated (owner: "Yes, measure both"); (5) keep checking plans at a REPL: 67 of 302 done, about 217 left, the commonest fault is code that moved under a task.
- **Time from a proved change to main:** the Optimization session. Agents' merges on a swept store are proved ([collection](storage.md#collection)); a merge of the wiki, tasks or tests skips the steps that run the binary ([merging](merging.md#a-merge-runs-what-the-change-reaches)); next, what a first status costs when main's content moves.
- **The web pages:** the agent's chat, `@ctx` and raw-wire debug tabs and root's work list are on main. An opened step has Run, To here, Copy, Edit ([run again](turn.md#an-eval-can-be-run-again)) and Back to here ([going back](turn.md#an-agent-goes-back-to-an-eval)). A handle names its value, so the same answer has the same handle. Next: source editing with an explicit disk sync (`seon.render/an-edit-reaches-disk-when-asked`) and a chat that sends only what it places (`seon.render/an-agents-chat-sends-only-what-it-places`).

Open with the owner: a problem class for bare names and `path:line` in plans, and for tasks whose proofs are all green already; the print budget; `seon.reader/forgive`; `:seon.error/fix` hints; what happens when a chat render breaks; `jolt.host/a-date-prints-its-milliseconds`'s pin bump; `seon.repl/type-findings-are-an-evals-warnings`' go; surfacing a red from the nightly full run.

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
