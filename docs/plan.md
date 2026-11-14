# The plan

What Seon is building, in stages. Each stage has one goal, the measure that says it is done, and its work in order with an
owner. A stage starts when the one before it is done; work outside the current stage waits unless it unblocks it. The why
is in [principles](principles.md); the owner's rulings are in [decisions](decisions.md).

## The goal

A user's roughly specified task becomes merged code in as few turns as possible. The measure is taken on rounds of two or
three backlog tasks drawn at random and worked by live agents: one row per task (turns, runs, merged or not and why),
reported to the owner daily. A task that never merges is a failure, not a low number. The draw stays random, because agents must take any task, however
unspecified: a task's specification is its first proof, the page says so reactively while it is missing, and an agent that
improves its task without finishing it has the improvement synced to disk once a Claude session's review finds it better,
so repeated rounds carry every task from start to end (owner, 2026-11-14). Beyond it, the owner's system goal
(2026-11-11): the system improves its own code (correctness, cost, how interwoven it is, the turns its agents take) by
"lots of base hits rather than swinging for homeruns".

## The system we are building toward

The target is a system that, once booted, has no dragons or sharp edges: complexity is pushed to the edges, and every problem
comes back to us as data without breaking anything (owner, 2026-11-14). Five guarantees define it; each names what breaks it
today and the work that makes it true.
1. **Booting is the only place we check.** A process starts through one path, which proves once that it holds exactly what
   its branch names (program, registry, config, environment variables, libraries, pin, binary); past startup that is
   guaranteed. Broken today by test children forked from root's agent, scripts that take the caller's shell, and merges on
   a branch's stale program. Made true by Stage 0.5, Stage 1.6 and Stage 2.1.
2. **Everything inside is plain:** immutable values and Flow procs; no locks, clocks or guards; derived data re-derived by
   the system when its inputs move, never by a caller remembering to. Broken today by cites, the reach graph and imports
   going stale, and by two three-way merges in one. Made true by Stage 1.5, Stage 1.6 and a registry of stated and derived
   attributes (Stage 2).
3. **Complexity sits at the edges, behind one owner each:** the shell, files, network, the model, processes, git and the
   dependency forks are effects, bounded and killable, whose failures come back as typed data. Broken today by a reasoning
   overrun ending a run, a stop with no bound, and git and forks outside the store. Made true by Stage 0.3, Stage 1.7 and
   Stage 3.2.
4. **Self-modification cannot cut the floor out:** an agent may redefine anything, and the machinery it runs on (its turn
   loop, its answers, its test children) runs from the program, not from the REPL's redefinitions. Broken today by an
   agent that redefined how every eval answers and hung its own turn loop. Made true by Stage 0.6 and Stage 4.3.
5. **Every problem comes back as data:** every wait ends with a value or a typed error; every error carries its fix and its
   causes, is drawn on the page of whoever can act on it, and is counted. Broken today by a hang that recorded no stall and
   by a board of rows dug out of logs by hand. Made true by Stage 0.3, Stage 1.8 and Stage 3.1.

**Measured by:** of a week's failures, the share that arrived as classified data rather than found by a person or session in
logs, rising to all of them; and zero hangs, zero false reds, and restarts only for a binary change. Then the turns metric
measures the agents, not the plumbing.

## Why it is slow today

A process often runs on state its branch doesn't name (stale code, pins, schema registry, config, caches), so verdicts are
wrong or flaky, landings fail on what the change didn't touch, and the merge a task needs runs on that stale state. Around
it, the work itself lives outside Seon: blockers, deletions, the landing queue, the turns ledger and the owner's rulings are
files in root's notes and messages between sessions, which no agent reads and nothing checks. Since 2026-11-12 the
sessions have landed 82 times through `bin/land` and git, and no task through Seon's merge, the one path every agent's
task must take. The priority (owner,
2026-11-14) is one view: fix the broken processes that slow us down, and move Claude sessions off files and git onto the
system, working, proving and merging as Seon agents do. Everything else in this plan stays, in order behind it.

## Stage 0: one happy path (now)

**Goal:** one real backlog task merged by an agent. **Done when:** a task drawn from the backlog is on main, with its turns
recorded.
1. Landed 579d62547d: a test child holds the config its checkout's shard loads, and every runner digest moved (tests).
2. One schema registry per process, and a schema entry main deleted merges as its deletion; the memory gauge's proof reads
   its block alone, completing `seon.lib/the-memory-gauges-proof-measures-the-block-alone` (runtime, one landing). The
   cause behind both, a process running state its branch doesn't name, is `seon.program/a-process-runs-its-branchs-program`
   (Stage 2.1).
3. A reasoning overrun is the model call's typed error, not the end of the run: the effect answers it as data, the system
   reads it for the agent on its next turn, and the agent's page shows its recent errors with their causes, one render
   (owner, 2026-11-14). `:seon.config.ai/max-tokens` holds reasoning and reply together; one run of two died of it this
   round (runtime for the effect, context and web for the render).
4. A fork waits for other threads to park, never races them: a refusal agents hit in production, built and pinned on
   jolt/deps, landing after item 2 because a pin change reruns everything (deps; moved from Stage 3).
5. A test child forks only from a process already on its branch's environment, never from root's agent: today the service
   runs another checkout's tests in a fork of root's long-running agent, which carries root's config, environment, heap,
   threads and loaded libraries, and each fix so far swapped one of those (owner, 2026-11-14: make it impossible, not
   another check). The agent `seon.system/prove-tree!` spawns on a branch starts on that branch's environment through the
   one start path, which checks once, at startup, that the process holds exactly that environment (config, environment
   variables, libraries, pin) and refuses to start otherwise; past startup nothing re-checks it; the import of a checkout's files onto a branch and the live route stay, since Stage 1 builds on them
   (runtime, tests; moved up from Stage 4.3).
6. An agent's redefinitions cannot break its own machinery: its turn loop, its eval answers and its test children run from
   the program, and a wait in the turn loop ends with a typed error that is recorded as a stall (round 1: an agent that
   redefined `seon.repl/response` hung at turn 24 with no stall recorded) (context, runtime; moved up from Stage 4.3).
7. `seon.repl/an-uncited-public-defn-is-told-at-its-eval` (proof green at turn 48, its agent now lost) restarted on a fresh
   agent, or the first of the random round's tasks to reach green, merged by `seon_done` (context).

## Stage 1: Claude sessions work through the system (the priority)

**Safe to start only when all of these are on main,** each proved red first (2026-11-14: a merge gate on the standing system
booted ~74 test roots at once and took the owner's machine to load 70; a prove waited 17 minutes on that gate):
- admission weighs a test that boots a root as a whole system, so no run, gate or landing makes more processes than the
  cores and memory admit, and a killed or failed child never restarts a root on its own (tests);
- no eval waits on a gate, and every wait on a gate or a reply ends with its value or a typed error (tests);
- an update that must restart root waits until nothing is in flight, and a wall-time end is a platform problem, never a
  cached red (web, runtime);
- the MCP relay answers each call on its own (web);
- one session's edit, proved and merged through the system end to end, keeps the machine's load within its cores
  (`seon.server/a-sessions-file-edit-merges-through-done`, with its cost proof).
Until then no session calls `seon_done`, `seon_prove` or `(my.agent/prove)` on the standing system, and the path's own
tasks land through a worktree and `bin/land`.

**Goal:** a Claude session changes Seon the way a Seon agent does: its change enters the one system as a valid branch, is
proved there by what it reaches, and merges into main through Seon's own merge, with git following the store. Everything a
session needs to get work done is a task or the wiki. **Done when:** one area's session lands a week of work with no
worktree, no per-checkout system and no `bin/land`, and root's notes hold no board, ledger, queue or ruling. Then the
infrastructure this replaces is cut, each removal brought to the owner as an audit.
1. **Approved designs are tasks:** each approved, unbuilt design (the one-unit spec, the Flow standard, process start, the
   effects plan, the online takeover and resume, data's 11-13 interface agreements, main keeping its own proofs, the
   landing queue as data) is one task whose plan holds the design, so no compaction loses it (owner, 2026-11-14) (each
   design's area).
2. **A task's specification is its first proof:** a task whose plan lacks what the work needs is shown so on its page,
   reactively, and specifying it is the agent's first step; an improved task reaches disk after a Claude session's review
   (context, data).
3. **A session is an agent:** each Claude session holds one agent keyed by its area's name on one branch of the one store
   (`seon_attach` exists), with its REPL and its branch's program; `seon_eval` and `seon.repl/edit!` change code, docs,
   tasks and `bin/` scripts as writes on that branch (runtime).
4. **Pushing a change is easy:** a file a session edits is imported as a transaction on its branch (the same path an
   agent's eval takes), so a session that works on files gets a valid branch with no extra step; a read-only copy of the
   branch is written out for reading (runtime, data).
5. **Proving is on the branch, in the system:** the session's agent proves by reach, live, and by a full build only when
   the change may differ (a pin, native code, `deps.edn`). Results are entities in the store, shaped for how they are read
   back (owner, 2026-11-14: "pull first"): each holds its test, its status (written once), everything its run read
   (datoms through Datahike's read recorder; config entries, files, environment variables and the pin as recorded outside
   reads), the environment and process it ran in, and its cost. Reuse, "why is this red", "what ran where" and "flaky" are
   then queries, not keys chosen in advance; the result files go (tests, data, with runtime for the outside reads).
6. **Merging is Seon's:** `seon_done` runs the gate and Seon's three-way merge on main's program; conflicts go back to the
   branch; one three-way merge, not two (`seon.task/carry-tx` goes); a merge costs what moved (today one merge of main
   costs 62 s, 9.9B ticks and 47.5 GB, measured) (data, runtime).
7. **Git follows the store:** a merge is written back, and git's main moves after root reads its diff (`my.root/sync!`);
   main is pushed to GitHub (runtime, root).
8. **The work lives in Seon:** a blocker is a task in the owning area's namespace with its causes (`:seon.task/causes`,
   a set of schema keys each with its evidence, re-derived, owed a schema entry by data), named in the blocked task's `:seon.task/needs`; a deletion is a proof that the old path is gone; the
   merge queue is tasks waiting to merge, in `:seon.task/needs` order; turns to merged is a query shown on the task and the
   dashboard; the owner's rulings are in [decisions](decisions.md); a message between sessions is about a task and is
   written there, and one that names no task becomes one; when a landing completes what a task needed, the sessions waiting on
   it are told (`my.root/landed!` sends `seon_message`); a flaky result is a derived problem class, never a hand-filed row
   (context, tests, root).
9. **Pilot, then all:** one area moves first after two or three merged tasks; then the rest. The audit to cut follows:
   worktrees, per-checkout systems and ports, `checkout-env!`, `bin/land`'s local prove and git bridge, the shell test
   runner and its shards, the result files, the boards as files (root, with each owner).

## Stage 2: the database is the one source of truth

**Goal:** no process can run on state its branch doesn't name. **Done when:** a law goes red on any program load outside
the one start path, and no failure in a week comes from a stale or mismatched environment.
1. An environment is one reference, `{:branch :commit :t}`; the program, its config, its files and its pins are data on the
   branch; one start path brings a process to it and refuses only a pin or runtime mismatch, as a typed error; only start,
   fork and merge make an environment. A result read under one environment is then keyed by it with no extra input
   (runtime, deps, data).
2. A build writes the branch's files from the store and builds from them; a git tree matters only when files are imported
   (runtime, deps).
3. Deleted, after an audit to the owner: tree ids and tree diffs, per-tree program caches, health's base tree.

## Stage 3: live, and nothing hangs

**Goal:** Seon restarts only for a binary change, and every wait ends with its value or a typed error. **Done when:** a week
of landings with no restart except for a pin, native code or `deps.edn`, and no hang.
1. Every restart reason classified: forms load in place, procs swap through Flow's `update!`, schemas and config reload,
   boot-only code waits for the next build; a pin change goes through an online takeover, with a clean shutdown, resume, and
   a path back after a crash that reports what was lost; a stop that waits on `held --end` ends with a typed error
   (runtime, deps, data).
2. One effects system: each effect's bound declared on its request schema (malli `:default`, overridden by config), a call
   only shortens it, Flow enforces ticks and bytes and the effects owner wall time and child memory; effects routed at
   Jolt's host primitives; concurrency admitted from cores and memory; every outside read (a file, an env var) recorded as a
   read (runtime, deps, data).
3. Time is data on channels: no polling; the only clock is an effect's deadline, arriving as a message. The Flow standard
   and its examples; the ten parts that should be procs become procs (runtime).

## Stage 4: agents finish in few turns

**Goal:** median turns to merged falls across random rounds. **Done when:** set from Stage 0's first measurements.
1. Every stored, synced or public shape a named schema; the schema graph chooses what an agent is shown, each item
   explained; duplicate schemas and unnamed public shapes flagged on change (data, context).
2. What an agent reads: a cache-stable prefix and a fresh tail; one walk gives the page and the request; "What your task
   still needs" (built as edits after one whole page; the whole list every turn is a trial); assembly costs what changed at
   1M-token pages, and the reactive walker is deleted; reference sessions in the native wire format; compaction: deleting, editing, compacting
   or forgetting an eval is a change of data the next request is rendered from, with page controls for each (only
   unlinking has a test today) (context, web).
3. REPL redefinitions: the REPL may do anything; only definitions become program; each eval's answer says what was saved
   (runtime, context).
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
in the stage that builds its mechanism: turns counted by the system (Stage 1.8); no design starting outside the Seon agent;
a proof seen red first; a duplicate schema or unnamed public shape seen twice (Stage 4.1); `Deletes:` checked (Stage 1.8);
effects routed at Jolt's host primitives (Stage 3.2); no program loaded outside the one start (Stage 2.1); atoms; no test
reading wall time, load or system memory; a second status at one digest filed as a defect; claims marked measured or
hypothesis.

## Throughout

What holds in every stage.

- Context runs the random rounds and reads every run's reasoning; system problems become tasks for their area.
- Every claim is marked measured or hypothesis; a research round lands a fix or stops for review.
- The code shrinks as mechanisms merge until it reaches a healthy weight; every landing reports its `src` delta, and useful
  functionality is removed only after an audit to the owner.
- A retrospective closes each stage; the principles and this plan are pruned then, never appended to between. A rule is
  removed only with its reason: the incident or ruling behind it, what holds it now, and why it no longer earns its place.
