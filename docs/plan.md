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

**One tree of data that renders itself, read by two clients.** The model's context goes through the same ratom components as the page: a component transforms its data, filtering it and adding what it needs from the db, into hiccup: the one state, drawn to html directly and read by the model. The order, what's settled and what the research found are on task `seon.context/the-models-context-is-the-walks`. All of it gets finished, and real-task runs follow it.

In flight, each on its own branch and merged when proved:
- **One call per reply, and the MCP tools the same (`jolt/mcp-tools`):** [one tool](model.md#one-tool-eval), [an outside agent](turn.md#an-outside-agent).
- **Test reuse:** a test's digest takes a form's read form, not its text; config keys a `:map` lists are no edges (`jolt/reuse-wip`).
- **Agents repeating themselves** is a bug being root-caused (`jolt/no-repeats`).
- **Cost of an eval and of a root's boot:** the Optimization session.
- **The web pages:** the agent's chat, `@ctx` and raw-wire debug tabs and root's work list are on main. Step buttons are in flight on `jolt/step-buttons` (task `seon.render/an-eval-run-again-replaces-itself`; not merged). Next: going back to any point (`seon.agent/reset-an-agent-to-any-point`), source editing with an explicit disk sync (`seon.render/an-edit-reaches-disk-when-asked`) and a chat that sends only what it places (`seon.render/an-agents-chat-sends-only-what-it-places`).

Open with the owner: erasing the store for the schema step; surfacing a red from the nightly full run.

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
