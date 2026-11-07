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
- **Agents merging real tasks, the first priority (this session).** The tree, the one-call tool, edits and checked plans are on main. Live runs on main (deepseek-flash, thinking off, default temperature, one run each): at 40 turns, 1 real merge of 16 attempts on 8 tasks; at 120 turns, 1 real of 8, so more turns alone didn't bring more; messages on 98% of replies, calls to the model about 100 ms apart, cost past turn 40 stays sane at 98-100% cache hits. Thinking on and deepseek-v4-pro finished nothing on 3 hard tasks and cost 2.7x and 6x. An earlier merge's proof passed with the bug present, so a merge counts only if its proofs fail without the change (task `seon.proofs/a-proof-green-without-the-change-proves-nothing`). Next (`jolt/run6-fixes`): a model call that never answers ends in a typed error; a gate that can't analyze a namespace says so to the agent, and a replaced `ns` form loses no alias. Then rerun.
- **Time from a proved change to main, and a turn's cost:** the Optimization session. A prove stops on a red `laws` or `health`; a merge of the wiki, tasks or tests skips the steps that run the binary ([merging](merging.md#a-merge-runs-what-the-change-reaches)); a turn that writes no form does not wait on the embedding model ([vector](vector.md#the-model)). Open with the owner: Jolt pin to `jolt/poller-wake` (task `jolt.backend/the-threadeddeadlock-gate-hangs`), and keeping the embedding helper up while agents work (task `seon.vector/the-helper-stays-while-agents-work`).
- **The web pages:** the agent's chat, `@ctx` and raw-wire debug tabs and root's work list are on main. An opened step has Run, To here, Copy, Edit ([run again](turn.md#an-eval-can-be-run-again)) and Back to here ([going back](turn.md#an-agent-goes-back-to-an-eval)). Source editing is paused until agents merge at all: its design is on `seon.render/an-edit-reaches-disk-when-asked`. A handle names its value, so the same answer has the same handle. A failed run's page says why it failed and how its proof went ([the page](render.md#the-page)). Next: source editing with an explicit disk sync (`seon.render/an-edit-reaches-disk-when-asked`).

- **Seon's own files move by branch:** an agent's `edit!` works on any path, and scripts come into the db by an allow-list at import; a plan on task `seon.source/seon-files-move-by-branch`.

Open with the owner: starting agents in a scratch REPL so a try-out `def` isn't program; whether a proof that passes without the change holds a merge; whether a task's gate runs only what it names; a second `:tests` worker's memory; the Jolt pins (`jolt/poller-wake`, a Date's milliseconds); a problem class for bare names and `path:line` in plans, and for tasks whose proofs are all green already; the print budget; `seon.reader/forgive`; `:seon.error/fix` hints; what happens when a chat render breaks; `jolt.host/a-date-prints-its-milliseconds`'s pin bump; `seon.repl/type-findings-are-an-evals-warnings`' go; surfacing a red from the nightly full run. From the web session's audit of unmerged work, held for the owner: the staged kinds (`docs/decisions.md` still says they go, and they are on main; only the old branch was retired); the demo root on port 12871; worktrees `bench-b` and `ctx-ui-review` (uncommitted scripts) and `meas-step6` (1.8 GB of runs).

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
