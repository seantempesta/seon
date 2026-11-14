# The plan

What Seon is building, in stages. Each stage has one goal, the measure that says it is done, and its work in order with an
owner. A stage starts when the one before it is done; work outside the current stage waits unless it unblocks it. The why
is in [principles](principles.md); the owner's rulings are in [decisions](decisions.md).

## The goal

A user's roughly specified task becomes merged code in as few turns as possible. The measure is taken on rounds of two or
three backlog tasks drawn at random and worked by live agents: one row per task (turns, runs, merged or not and why),
reported to the owner daily. A task that never merges is a failure, not a low number. Beyond it, the owner's system goal
(2026-11-11): the system improves its own code (correctness, cost, how interwoven it is, the turns its agents take) by
"lots of base hits rather than swinging for homeruns".

## Why it is slow today

A process often runs on state its branch doesn't name (stale code, pins, schema registry, config, caches), so verdicts are
wrong or flaky, landings fail on what the change didn't touch, and the merge a task needs runs on that stale state. Around
it, the work itself lives outside Seon: blockers, deletions, the landing queue, the turns ledger and the owner's rulings are
files in root's notes and messages between sessions, which no agent reads and nothing checks. The stages remove the stale
state first, then bring the work into Seon, then make the store the only source, then the turns agents spend.

## Stage 0: one happy path (now)

**Goal:** one real backlog task merged by an agent. **Done when:** a task drawn from the backlog is on main, with its turns
recorded.
1. A test child holds exactly the config its checkout's shard loads (shipped file only, no local layers), so a result's key
   names the environment it really ran in, and the runner digest moves with it (tests).
2. One schema registry per process, and a schema entry main deleted merges as its deletion; the memory gauge's proof reads
   its block alone (runtime, one landing).
3. `seon.repl/an-uncited-public-defn-is-told-at-its-eval` (proof green at turn 48, its agent now lost) restarted on a fresh
   agent, or the first of the random round's tasks to reach green, merged (context).

## Stage 1: the work lives in Seon, as tasks and the wiki

**Goal:** everything an agent needs to get work done is in the store, read through its pull: what to do is a task, how the
system works is the wiki, and nothing lives in a file or message only a Claude session sees. **Done when:** root's notes hold
no board, ledger, queue or ruling, and a landing needs no message from root.
1. **A blocker is a task** in the owning area's namespace, with its `:seon.task/evidence` and `:seon.task/cause`; the task
   it blocks names it in `:seon.task/needs`, so "what is blocking this" is a pull, and it ends when its merge completes it.
   The blockers board goes (context, with each area filing its rows).
2. **A deletion is a proof:** the replacement's task carries a test that the old path is gone, red until it is; the
   deletions ledger goes, and the `Deletes:` line is what that proof names (tests).
3. **The landing queue is tasks waiting to merge:** a branch whose proofs are green asks to merge by its task; `bin/land`
   admits from that, in `:seon.task/needs` order, with `--check` first; main keeps its own results, so a landing's reds split
   into main's and the branch's. Only what Stage 2 keeps is built: no queue file, no new script (tests, deps).
4. **Turns to merged is a query** over the store (each task's agents, their turns, merged or why not), shown on the task and
   the dashboard; the turns file goes (context).
5. **The owner's rulings are in [decisions](decisions.md)**, his words and where each is stated; root's answers file goes.
   A message between sessions is about a task and is written there (`seon_message` to its agent); one that names no task
   becomes one (root, all).
6. **Results are entities in the store**, `:seon.proof/*` on the results branch, keyed by what each run read (Datahike's
   read recorder, the one the query cache's lineage uses); the result files under `target/results` go (tests, data).

## Stage 2: the database is the one source of truth

**Goal:** no process can run on state its branch doesn't name. **Done when:** a law goes red on any program load outside
the one start path, and no failure in a week comes from a stale or mismatched environment.
1. An environment is one reference, `{:branch :commit :t}`; the program, its config, its files and its pins are data on the
   branch; one start path brings a process to it and refuses only a pin or runtime mismatch, as a typed error; only start,
   fork and merge make an environment. A result read under one environment is then keyed by it with no extra input
   (runtime, deps, data).
2. A build writes the branch's files from the store and builds from them; a git tree matters only when files are imported
   (runtime, deps).
3. Root merges main and an agent's branch on main's program, proves the merged result, and promotes it when green;
   conflicts go back to the branch. A merge costs what moved: today one merge of main costs 62 s, 9.9B ticks and 47.5 GB
   (measured), far over any estimate (runtime).
4. A file edit is imported as a transaction on a branch, the path an agent's eval takes. Each Claude session gets a Seon
   agent on its own branch; its edits flow there, minimal tests run by reach, and root merges it when nothing conflicts.
   Pilot one area after two or three merged tasks, then the rest (runtime, tests, context).
5. Deleted: tree ids and tree diffs, per-tree program caches, health's base tree, worktrees, per-checkout systems and
   ports, `checkout-env!`.

## Stage 3: live, and nothing hangs

**Goal:** Seon restarts only for a binary change, and every wait ends with its value or a typed error. **Done when:** a week
of landings with no restart except for a pin, native code or `deps.edn`, and no hang.
1. Every restart reason classified: forms load in place, procs swap through Flow's `update!`, schemas and config reload,
   boot-only code waits for the next build; a pin change goes through an online takeover, with a clean shutdown, resume, and
   a path back after a crash that reports what was lost (runtime, deps, data).
2. One effects system: each effect's bound declared on its request schema (malli `:default`, overridden by config), a call
   only shortens it, Flow enforces ticks and bytes and the effects owner wall time and child memory; effects routed at
   Jolt's host primitives; concurrency admitted from cores and memory; every outside read (a file, an env var) recorded as a
   read (runtime, deps, data).
3. Time is data on channels: no polling; the only clock is an effect's deadline, arriving as a message. The Flow standard
   and its examples; the ten parts that should be procs become procs (runtime).
4. A fork waits for other threads to park, never races them (deps, pinned on jolt/deps).

## Stage 4: agents finish in few turns

**Goal:** median turns to merged falls across random rounds. **Done when:** set from Stage 0's first measurements.
1. Every stored, synced or public shape a named schema; the schema graph chooses what an agent is shown, each item
   explained; duplicate schemas and unnamed public shapes flagged on change (data, context).
2. What an agent reads: a cache-stable prefix and a fresh tail; one walk gives the page and the request; "What your task
   still needs" every turn (built); reference sessions in the native wire format (context, web).
3. REPL redefinitions: the REPL may do anything; only definitions become program; each eval's answer says what was saved;
   test children fork from a clean branch-program process (runtime, context).
4. The loop closes at a task's end: work, then refine (the worker's conversation continued, curating its own task and the
   docs it pulled), then review (a separate agent that judges and files tasks, never editing what it reviews; green merges a
   change to the system itself). A refinement is tried by rerunning from the earlier point in history beside the original,
   and a reviewed session is written to an import area in the repository (context).
5. Gold sessions and templates: a template is a function from a saved gold session's EDN plus parameters to a new task
   carrying that session as its example; similar earlier tasks offered as precedents; a problem class with one solved
   instance becomes a swarm of tasks, docstrings over fifty words first (context, data).
6. Measures as a system: a measurement is one call whose precision accumulates from use; a measure past its threshold is a
   task with its evidence (tests, context).

## Stage 5: the user's system

**Goal:** the owner works with Seon itself rather than with Claude sessions. **Done when:** set when Stage 4 is done.
1. A session is an agent: it names the agent whose conversation it continues, a fork's branch is the cut, the hierarchy is
   `:seon.agent/parent` at any depth, and a question goes to whoever started the task (data).
2. The user talks to the agent at the top; it picks direction and presents; a focus agent per area is an ordinary
   long-running agent. Every page is a render over pulled data, with no hand-rolled layout (web).
3. Merging branches syncs all non-conflicting data, with no list of attributes; each source transaction becomes a new, later
   transaction of the receiving branch (data, runtime).

## Enforcement owed

The principles each name what enforces them; these are not yet enforced, and each is owed a law or a test, filed as a task
in the stage that builds its mechanism: turns counted by the system (Stage 1.4); no design starting outside the Seon agent;
a proof seen red first; a duplicate schema or unnamed public shape seen twice (Stage 4.1); `Deletes:` checked (Stage 1.2);
effects routed at Jolt's host primitives (Stage 3.2); no program loaded outside the one start (Stage 2.1); atoms; no test
reading wall time, load or system memory; a second status at one digest filed as a defect; claims marked measured or
hypothesis.

## Throughout

- Context runs the random rounds and reads every run's reasoning; system problems become tasks for their area.
- Every claim is marked measured or hypothesis; a research round lands a fix or stops for review.
- The code shrinks as mechanisms merge until it reaches a healthy weight; every landing reports its `src` delta, and useful
  functionality is removed only after an audit to the owner.
- A retrospective closes each stage; the principles and this plan are pruned then, never appended to between.
