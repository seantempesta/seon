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
- **Agents merging real tasks, the first priority.** The tree, the one-call tool, edits and checked plans are on main. Live runs on main (deepseek-flash, thinking off, default temperature): 2 real merges in 24 attempts on 8 tasks; 120 turns brought no more than 40; thinking on and deepseek-v4-pro finished nothing on 3 hard tasks at 2.7x and 6x the cost. Two Claude agents working only through the REPL logged what's wrong; `jolt/audit-fixes` fixes it: a red on a merged tree is the agent's to clear (removing `:main-red`), a sync to disk by hand and verified, plain prompt facts on the checkout, shell and files, a create op, a task with no proofs told to write one. Then rerun. An agent's real task now merges end to end through the REPL (the printer's breadth task): fix right in 3 replies, then blocked for hours by stray reds nothing of its own caused: agents ran old tests on a first boot after a merge, a restart reverted merged work, reused greens hid reds on main, and parallel test roots shared a merges branch. Those are fixed. A real model's first run on them (flash, 7 tasks, $0.14): 2 merged and on main, 1 finished but blocked, 4 at the 40-turn cap. Not comparable yet with the 2 in 24: small, and the same tasks merged before. What ended the rest: one agent's file edit reaching others through the checkout (`seon.source/seon-files-move-by-branch`), an edit breaking the agent's own REPL, and test setup using up the turns.
- **One test system, and time from a proved change to main:** the Optimization session. A root's and a checkout's digests match on one tree, process tests too (0 of 1,428 differ), and a process test green at its digest asks no gate; how often that saves a real agent's gate is not yet measured. `agent-tests`' own cache and the several selectors are what else is outside it. A long gate shards by its work; a prove stops on a red `laws` or `health`; a merge of the wiki, tasks or tests skips the steps that run the binary ([merging](merging.md#a-merge-runs-what-the-change-reaches)). A turn that writes no form does not wait on the embedding model, and an outside agent's reply merges main as a model's turn does. Open flakes: `seon.agent/a-proof-child-that-died-says-why`, `seon.check/a-check-step-sits-idle-until-its-limit`.
- **Users, worlds within worlds:** the Web session; a design tried at the REPL, not built ([users](users.md), with its steps and who owns what). First: what survives the plan, then the data model and task entries.
- **The web pages:** the agent's chat, `@ctx` and raw-wire debug tabs and root's work list are on main. An opened step has Run, To here, Copy, Edit ([run again](turn.md#an-eval-can-be-run-again)) and Back to here ([going back](turn.md#an-agent-goes-back-to-an-eval)). Source editing is paused until agents merge at all: its design is on `seon.render/an-edit-reaches-disk-when-asked`. A handle names its value, so the same answer has the same handle. A failed run's page says why it failed and how its proof went ([the page](render.md#the-page)). Next: source editing with an explicit disk sync (`seon.render/an-edit-reaches-disk-when-asked`).

- **Seon's own files move by branch:** an agent's `edit!` works on any path, and scripts come into the db by an allow-list at import; a plan on task `seon.source/seon-files-move-by-branch`.

Open with the owner: starting agents in a scratch REPL so a try-out `def` isn't program; whether a task's gate runs only what it names; a second `:tests` worker's memory; a problem class for bare names and `path:line` in plans, and for tasks whose proofs are all green already; the print budget; `seon.reader/forgive`; `:seon.error/fix` hints; what happens when a chat render breaks; `seon.repl/type-findings-are-an-evals-warnings`' go; surfacing a red from the nightly full run. From the web session's audit of unmerged work, held for the owner: the staged kinds (`docs/decisions.md` still says they go, and they are on main; only the old branch was deleted); the demo root on port 12871; worktrees `bench-b` and `ctx-ui-review` (uncommitted scripts) and `meas-step6` (1.8 GB of runs).

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
