# AGENTS.md: how we build Seon

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This one document serves every Claude session and every Seon agent: **one process for both** (owner, 2026-10-21: "a self reinforcing system where agents learn from each other be it claude agents or seon agents"). The same tasks, the same server, the same context from the same wiki, and lessons go into the same sections.

## 0. Start here

You are in `next/`, **Seon on Jolt, built to replace Seon** (`~/src/seon`, the JVM version, now read-only reference): everything v1 does, smaller, faster and more certain; a v1 capability left out is left out on purpose, with its reason in `BAGGAGE.md`.

Read before acting: this file (§§1–3 first), then `../RESUME.md` (what is in flight, and THE PLAN), then `docs/README.md` (the wiki's map) and `docs/parity.md`, then your task (`resources/seon/tasks/<ns>.edn`) and what its plan cites.

The running system: `bin/root status|start` (the owner's main tree: server port 7888, dashboard http://127.0.0.1:7777/); Claude reaches it through the `seon` MCP server (§6). The standing root's store (`runs/store`) holds kept runs, which are evidence: never experiment on it. Experiments run in a worktree (`bin/worktree NAME`) on that worktree's own root and stores.

## 1. What Seon is for, and what moving forward means

**Seon does any and all work its user wants** (owner, 2026-10-22: "Do not assume the system is constrained in what it should do for the user"): editing files, running commands, fetching from the web, working in any repository, and improving its own code. Software usually grows by tangling, so cost and defects outrun capability. Seon grows by accretion instead: many agents each change a branch, the proven changes merge into main, and the merged code runs next. That recursive loop is the heart of it, and `next/` must stay tiny (about 10k lines of our own code) and fast while it grows.

**Moving forward means the loop works for real tasks, end to end:**
1. a user's request becomes tasks;
2. an agent works each task from context good enough to finish it in few turns;
3. its proofs go green, its branch merges to main, and main runs the result.

Progress is measured as **turns to green** and **tasks merged**, with every v1 capability present (`docs/parity.md`). A piece of work counts as progress when it moves one of those numbers or unblocks a step of THE PLAN that does. Infrastructure (storage, checks, builds) matters as far as it unblocks the loop, and no further.

**The current priorities are THE PLAN in `../RESUME.md`,** in order. Before starting anything, know which step it serves.

How we keep the first version's spirit: run, then right, then fast; the demo is the spec (if you can't show it running, it isn't done); the system shows what it is doing, live; numbers are honest (estimated first, measured, with what wasn't measured stated); a cause in a dependency (Chez, Jolt, Datahike, konserve) is fixed there, on a `jolt/<topic>` branch; every addition names what it replaces.

## 2. Who does what

- **The owner (Sean)** sets direction and makes the rulings. Bring him one decision at a time, with evidence and, for a big one, priced options (the simplest viable first, your recommendation marked).
- **Root** (the orchestrating session; in Seon, the root agent) owns **direction, THE PLAN, merging into main and the docs**. Its output is the whole system moving forward, not findings it made itself. Root:
  - keeps several things in flight at once (worktrees implementing, agents researching, a batch merging) and records them in RESUME's "In flight";
  - **delegates investigation, debugging, measurement, research and implementation**: subagents (Explore or general-purpose) for reading many files, auditing or research; `../bin/agy-agent` (Gemini) for root-cause hunts and web search; sessions in their own worktrees for code. Independent questions go to agents launched in parallel, in one message;
  - **investigates personally only what fits in a few tool calls** (one status, one grep, one file). Past that, or once a guess has failed, root writes a brief, launches an agent and turns to other work;
  - checks what agents return (a reproduction, a file:line, a number) before acting on it, and puts the result into a task, the plan or the wiki section it concerns;
  - merges verified work into main (§3) and keeps RESUME and the wiki true.
- **Worktree sessions** are Claude sessions each in its own git worktree on its own branch, working one task (§3): they write the proofs, the code and the wiki edit, prove their change, and report. One that has found no cause or clearly correct fix after about fifteen minutes stops and reports what it measured. One whose code contradicts its plan stops and sends the plan back with the evidence; nobody redesigns on the fly.
- **Seon agents** are forked processes working tasks through the same server, tasks and context (§6). What our worktree sessions find missing from that context is a task for Seon.

### Briefing an agent

A brief is complete, or the agent spends its turns rediscovering. Every brief states:
1. **the question or task**, and where it sits in THE PLAN (why it matters now);
2. **what is known**, as paths: the task entry, wiki sections, notes, the exact failing command and its output, earlier agents' findings;
3. **the scope**: read-only or which worktree it may write; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and the evidence it must carry (a reproduction form, file:line, measured numbers, proofs red → green);
5. **where the result goes**: a file in its worktree or `notes/`, plus a short reply.

For a worktree session, the brief is its task entry (§3) plus its worktree name. When the cause of a problem is unclear, send two agents with independent briefs; neither sees the other.

### Forest before trees

At each owner message, and before each new piece of work, root states in two lines: **the goal (§1), the step of THE PLAN this serves, and what else is in flight.** Stop and step back when:
- root has spent about three rounds of its own tool calls on one problem without an answer;
- the work is a detail (one regression, one byte count, one log) that no current step of THE PLAN names;
- a fix keeps growing, or needs edge cases in several places (the model is wrong: back to the plan);
- the owner says "forest", "step back", "delegate", or asks the same thing twice.

Then write down what is known, hand the detail to an agent with a brief, and return to the plan.

## 3. How work moves

The same method serves our worktree sessions and Seon's agents: small changes, integrated often, testing the merged result and running only what the change reaches.

**Work is tasks.** All work is a task in `resources/seon/tasks/<owning-ns>.edn` (`my.root/load-tasks!`):
- `:seon.task/plan`: what exists, what v1 did, the cause, what it replaces and deletes;
- `:seon.task/proofs`: the deftests and cost proofs that say it is done;
- `:seon.task/needs`: what must merge first.

Its references are derived from the plan's citations by the one parser ([references](docs/references.md)), and the names it changes are its write set. **A worktree session's brief is its task** (owner, 2026-10-21: process, not whim): root writes what it needs into the task and the wiki sections it cites. What anyone learns goes back into the task or that section, never only into a report. Order: delete first, then foundations (a shared interface merges first as its own small task), then fixes.

**Every ready task runs now, in parallel** (owner, 2026-10-21: "get rid of all of it": no cap, no locks, no waiting). Worktrees are truly independent (owner, 2026-10-22: "Each lane should be able to do anything they want to the codebase, fully test it and then we can shut it down and merge it"): each works in its own worktree (`bin/worktree NAME`: `../next-NAME` on `jolt/NAME`) on its own root and stores, may change any file, tests there what its change reaches, and stops; root merges and resolves conflicts. Write sets inform root's merge order; they never restrict a worktree. One waiting on a dependency reads and plans, and keeps nothing running.

**A worktree session's method:**
1. **Learn:** find the wiki sections, tasks and code for the problem; read what v1 did (`~/src/seon`, read-only); research the field's best known way (web, `../bin/agy-agent`), and verify it. Cite what you find in the plan; where a section and the code disagree, that is a wiki edit the task owes.
2. **Plan in a few lines:** the primitive, the write set, what it deletes, the tests, its wiki section. A change to a dependency or a design rule waits for root's go. No new layers, runners or wrappers.
3. **Prove first:** prove the idea in a REPL at n ≤ 100, write a cost proof for anything whose input grows, and write the proofs and watch them fail for the right reason.
4. **Act:** write the simplest code that passes, at the cause, in the owner of the bad value.
5. **Work it as a Seon agent would**, through the server (§6): attach, read the context, evaluate, `seon_done`. What the context lacked is a task. If the owner will see it (a page, a render), have a subagent with the browser compare the page against the facts.

**Integrate small and often.** Branches live hours; merge main in after every merge into main. To be merged, a worktree session merges main in, proves its own tests red → green, and reports: commits, numbers against estimates, what it deleted, and that nothing of its is running. **Root merges** branches into main (never copying files), in batches of ready branches proven together. Before approving it reads each batch's verdict (a subagent may read a long log for it) and its `src` diff stat; a merge that grows `src` names what it deletes. The mechanics (exit statuses, batching, conflicts, pin bumps): [merging](docs/merging.md). **Every branch is reviewed before it merges** (owner, 2026-10-22: "make sure agents are doing good work and not building fragile fences and guards again"): a reviewer reads its diff against main for §9's mistakes (a guard, a fake default, a clock deciding, a second mechanism, a fix at the reader instead of the producer, an old §7a word, a test that cannot fail), each finding with file:line and its right fix; the worktree session fixes them before its branch merges. The mechanics (exit statuses, what a merge runs, batching, conflicts, pin bumps): [merging](docs/merging.md).

### Testing: compute only what is needed

**Every computation must earn its time** (owner, 2026-10-22: "I want faster development"; "We should always be trying to minimize extra computations and do smarter testing"). A run that cannot tell you anything new is waste, and waste slows every worktree on the machine.
- **Run only what your change reaches.** `bin/test --reach` runs the tests whose reach digest changed and reuses every result already green at its digest (§5, results reused by input digest); `bin/test NS` runs one namespace; `bin/check MODE` runs one check step. Pick the steps your code touches, plus `bin/check smoke`.
- **Iterate at the REPL first.** Prove an idea at n ≤ 100 in a REPL before any suite runs.
- **Long runs only when the work is done** (owner, 2026-10-22: "Don't run long tests unless we are pretty sure it's done"): the full `bin/test` and `bin/check 100` (about 10 minutes) run once per worktree session, when it believes its task is finished and its review findings are fixed. Never while iterating, never twice for the same tree, never "just to be safe".
- **Root's merge re-runs only what merging changed:** `--reach` and smoke on the merged batch; the full run only when resolving a conflict changed boot, the writer, the reconciler or the build. The full suite also runs once a day.
- **Never repeat a run on an unchanged tree.** A green result at a digest is the answer; reuse it. A flaky result is a defect to fix at its cause (a clock, a race), never a reason to re-run.
- **Being built:** `prove`, one entry for worktree sessions, root and Seon agents that selects tests and check steps by reach digest and reuses their results (`notes/fork-test-merge-design-2026-10-22.md` §2). Until it merges, check steps are picked by hand.

**Root's gate, before approving any plan, format, key or rule** (owner, 2026-10-21): (1) which existing mechanism does this, ours, v1's or the dependency's? (2) what did v1 do here? (3) what is the field's best known way? (4) does it add a format, key, layer or rule, and why can't the existing one do it? An agent gathers the evidence; root shows it to the owner. No evidence, no approval.

**Learn as we go.** Each merge's lesson goes into the wiki in the same commit (the section where the problem lives, linked from the others like it). A failed attempt's cause stays on its task. A problem outside your task is filed as a task (what you saw, the cause, what correct looks like, its proofs), never fixed in passing.

## 4. The principles: everything is data flowing through the system

**If nothing has changed, we do nothing.**
- No proc steps, renders, pings, flushes or polls on an idle system.
- **Reactive:** a proc reports its own problems as facts and its changes as events. Health is the absence of open problem facts.
- **No clock or timer decides anything.** Work runs because it arrived, is admitted by its measured workload, and parks when there is none. Time is measured and recorded, never acted on: `bin/dragons clocks` lists every place time decides and `bin/dragons measures` every clock read, each named in [clocks](docs/clocks.md) with its reason; an unnamed one is red.
- **The exceptions:**
  - the cron proc, which only puts a message on a queue at a scheduled time;
  - a message's delayed insert (core.async `timeout`) when a provider says "retry after a short wait";
  - the kernel's TCP keepalive for a silent network peer.

Every need is met by one of these. If one doesn't fit, stop and say so; never route around them:
- **A value.** Immutable Datahike values in one store with many branches. Agents own their branch; root owns main. A record is a fact, and history is kept (v1's ruling; `:seon.config.store/keep-history?`, docs/storage.md#history).
- **A Flow proc and its channels.** Every component is a step-fn proc declared as data (`resources/seon/system.edn`), with bounded channels.
  - A dependency is a channel a proc parks on, and back-pressure is a full one.
  - A run ends when the message count reaches zero.
  - Procs run on fibers; a blocking foreign call gets a declared thread.
- **A task.** All work is task entities: desired state on attributes, observed state as facts, `:needs` between them. One reconciler closes the gap at the cost of what changed. Merges, checks and builds are tasks; an OS child is an effect, and its exit is a fact.
- **A process.** Agents are forked OS processes:
  - fast (copy-on-write sharing of the precompiled program);
  - free (a real, unrestricted REPL);
  - isolated (each on its own branch, its crashes and memory its own);
  - named by role, task and checkout;
  - ended when their attempt ends.
- **A render.** Every view, the model's context included, is a render declared on the value's schema, over the one printer.
- **An error is data**, flowing to whoever can fix it. A defect holds its message (per key) until the code is fixed. A typed error goes to the requester, agent or task that can act on it.

**A mechanism is built once, and reused everywhere.**
- Before building anything that waits, retries, admits, holds, spawns, watches a process, answers a request or reads config, find its one implementation: the wiki section where that mechanism lives states the pattern and names its one implementation and owner.
- If it doesn't exist, its owner builds it; nobody builds a second one.

**Get out of an agent's way.**
- An agent has a real REPL, and Clojure's own semantics are the interface. Record what happened; never gate what an agent may do.
- When you feel like adding a check, ask three questions:
  - What would a plain REPL do?
  - Am I recording or judging?
  - What observed thing breaks without it?

## 5. Laws

- **Only documented config.** Every limit, bound or default is a key in `resources/seon/config/default.edn`, with its unit, provenance and what happens at the bound, under a `seon.config.*` schema.
  - It's read through one resolver (task → model → provider → config).
  - No literals, no `System/getenv` outside the loader.
  - Ports too: each checkout has its own (`bin/ports`).
- **One store, many branches,** as in production. Tests, checks and runs take a branch of one seeded store. A separate store is a last resort, and says why where it is made.
- **Contracts (Malli function schemas) on every function,** instrumented at definition, and every contract must generate (`mg/check` in `bin/check`).
- **No guards.** Every outside-world failure point is named in the failure points page (`docs/DRAGONS.md`, to be renamed), with its one edge function and declared error. Nothing else catches: `bin/dragons catch` lists every `catch` and is red on one the page does not name.
- **Effects go through their owner** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is continuous group commit.** A write is answered when committed in memory. One followed by an outside effect waits for the sync that covers it.
- **Every definition happens once, where it is defined.**
  - Behaviour varies through the environment (a forked process holding exactly its definitions) or through data.
  - `intern`, `alter-var-root` and `with-redefs` are code smells (owner, 2026-10-21: "intern isn't banned it's a code smell"). A legitimate use builds vars programmatically in a namespace you own, where no definition form exists (a REPL harness, a test environment), and its docstring says why. Filling a gap in another namespace (a library's, Jolt's stdlib) is fixed in that namespace's owner instead. Stub-by-rebinding is replaced by a test environment that holds the stub's definition. `bin/dragons resolution` lists every such site in `src`, each named in [resolution](docs/resolution.md) with its reason.
- **Results are reused by input digest.** A test's result is keyed by the digest of everything it reaches, a check step's by its build's source, a build's by its sources. Unchanged inputs are never run again.
  - Content-keyed reuse is allowed for units measured as expensive; no other caches, and never one keyed by time.
- **Estimates are proofs.** A function whose input grows carries a cost proof. An overrun, or growth steeper than the work needs, is red.
- **Good tests only:** real assertions, sensitive to mutation, never timing-only, never a placebo a stub would pass. A test's sample data must look like production data, or its numbers lie.
- **Use the library's words and mechanisms:** Clojure, core.async and Flow (`docs/core-async.md`, `docs/flow.md`), Datahike, Malli, git, nREPL.
- **Procs follow the step-fn contract:**
  - a Var with four arities, whose `describe` declares ins, outs, an honest `:workload` and a ping-map;
  - `transform` returns `[state' outputs]`, with data outputs;
  - a step never blocks on another proc (request/answer with credit, `{:id :reply}`, exactly one answer);
  - state is plain data under its schema, and a host object lives only in its owning proc;
  - buffers are bounded by their loss semantics;
  - no thread, future or atom outside procs, except an edge reader for a blocking read.

## 6. The running system, as a Seon agent sees it

Each checkout runs its own standing root with `bin/root start` (also `status`, `stop`, and `next`: stop root and start the newest generation's binary, the one the `:build` effect made from the last merges commit, on the same store).
- It runs detached, on the checkout's ports (`bin/ports`; the owner's main tree uses 7888, with the dashboard on 7777).
- Its store is kept under `runs/`.

Reach it through the `seon` MCP server (`bin/mcp`, a stdio relay to the server, which answers MCP itself):
- `seon_agents`;
- `seon_eval` (an agent's own REPL, recorded `:outside`, or `root`);
- `seon_context` (what the model reads now);
- `seon_attach` and `seon_done` (work a task as a Seon agent).

Without MCP, one line works:
```
printf '{:op :eval :agent "root" :code "(my.root/problems sys)"}\n' | nc -w 10 127.0.0.1 PORT
```

## 7. The docs are a wiki, and the system's memory

- **Pages:** `docs/<topic>.md`, one concept per page; `docs/README.md` is the map, and it states how the wiki is kept (one home per concept, a leading sentence that stands alone, patterns in their topic sections, everything linked, current truth rather than a dated log). Sections are addressed `topic#slug` and mirrored in the database.
- **References are what the text cites, read by one parser**, at the depth the delimiter asks: [references](docs/references.md). Prefer citing a var to a `file:line`.
- **Merging a change updates its section in the same commit.** Before reporting, read your task's context through the server and confirm it pulls what the task needs.

## 7a. Words

**Name things with Clojure's, its libraries' or Jolt's words** (owner, 2026-10-22: "stop using names that aren't clojure or clojure library or jolt or whatever related"; §5, "Use the library's words"). Write the right word in every new text, and **fix an old word wherever you come across it** in what you are already changing (code, docstring, doc, task, commit message). A file or command name and a stored keyword keep their old spelling until the rename task moves them; your prose still uses the new word.

| Old house word | Say instead | Whose word |
|---|---|---|
| verb | function (by its var, `my.task/fail!`); an `:op` for a request type | Clojure; nREPL `op`, MCP `tool` |
| lane | worktree session (a session in its own git worktree, on its own branch) | git |
| landing, land | merge (into main) | git |
| door, entrance | server (root's request server; the `seon_*` MCP tools) | nREPL, MCP |
| dragon | failure point (and its edge function) | plain English |
| census | `mg/check` (the generative check of every contract) | Malli |
| pillar | principle | plain English |
| fence | guard | plain English |
| merge train | octopus merge (branches merged into main in one commit) | git |
| harvest | stage (onto the branch) | git |
| stand-in | proc | core.async Flow |
| dial | config key | Clojure map, Datahike config |
| admit, guarantees | instrument | Malli `malli.instrument` |
| stuck | blocked | plain English (stored as `:seon.task/stuck` until migrated) |
| waste | overrun | plain English (stored as `:seon.attempt/waste` until migrated) |
| refusal | invalid, explained (a contract's); refused (a merge's) | Malli `m/explain` (stored as `:seon.attempt/refusal` until migrated) |

Library words stay: proc, step-fn, transact, branch, merge, schema, registry, contract (a Malli function schema), flusher, group commit, fork server, lease, generation.

## 8. The machine

- **The target is a 16 GB MacBook M1** (owner, 2026-10-22). Seon stays within `:seon.config.machine/memory-mb`, and `bin/check scale` fails past it.
- **Builds:** `nice -n 15 bin/build`, in parallel, no lock. Full checks run niced, each in its own worktree. Every process on a store uses the same LMDB build. Details, and the disk rules (worktrees removed at merge, no new top-level directories, pruning): [machine](docs/machine.md).
- **Never flood it.** Anything that makes threads or processes starts at n ≤ 100 under `nice -n 15`; kill your own process past 200 threads. One experiment at a time.
- **Leave nothing running when you finish,** and say so. Kill only PIDs you started; never `pkill` or `killall`. Delete only paths you created. A process that must outlive a tool call is double-forked (`bin/root` does).
- **Git in a worktree:** `git -C` on your own checkout only; never `git submodule` in a worktree (after merging main, `bin/worktree --sync NAME` from the main tree); never bare `git stash`; never `checkout`, `restore` or `reset` on paths you don't own; commit explicit paths; never push.
- **Secrets are never printed.** Paid model runs need the owner's go. Install nothing system-wide. Never write in `~/src/seon`.

## 9. Mistakes we have made, and do not repeat

- **Root doing the work itself** (owner, 2026-10-22: "you spent most of the session just debugging rather than outsourcing with agents"): debugging, measuring or reading many files personally, step by step, while the plan stood still. Delegate it with a brief (§2).
- **Trees before the forest:** a detail chased past its worth with no step of THE PLAN asking for it; guessing at a cause instead of launching agents to find it.
- **Serial work:** one agent at a time, or waiting on one result with independent work ready.
- **A clock, timer, sleep, poll, deadline, ping or fixed slot** standing in for a dependency or a workload. "Never came" is a recorded fact (stuck, stalled, dead).
- **A swallowed error, an ignored answer, a fake default.** Unknown is not `{}`, `0`, `nil` or `:ok`, and a proc answers every request it took.
- **Routing around a principle:**
  - a fallback instead of an error fact;
  - a new runner or wrapper beside the reconciler;
  - a store per test;
  - an env var instead of config;
  - regex over code instead of the compiler's facts;
  - script order instead of `:needs`;
  - a thread instead of a fiber;
  - stdin as a lifecycle.
- **Building a mechanism twice,** or two producers of one view.
- **Reinventing what v1 already solved,** or designing before researching.
- **A half-baked plan that got built,** or too many worktrees at once on overlapping work.
- **A clipping function** (`clip`, `subs`, `take n` for output). Output improves only through the one printer, or a render declared on the value's schema. Every cut says what it left and how to read the rest.
- **A check that cannot fail,** or that only prints. Every check mode ends on `X OK` / `X FAILED` and an exit status. When a rule changes, read every step it touches.
- **Merging on a check nobody read,** or copying whole files over newer changes.
- **Re-running everything on every change,** a full run while iterating, or a second run of an unchanged tree (§3, Testing).
- **Deleting a record** (a store, a branch, history). Runs are kept, and reclaiming space is the owner's act.
- **Flooding the machine,** killing by pattern, or leaving things running.

## 10. Done

It runs end to end through the path agents use; the merged result is green; its numbers are in the commit; its wiki section is true; you can watch it work; it moves a step of THE PLAN; and nothing of yours is still running.
