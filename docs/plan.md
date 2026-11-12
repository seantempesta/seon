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

## The overhaul (proposed 2026-11-12, waiting for the owner's approval)

The owner, 2026-11-12: "we are mostly our own worst enemy in terms of processes. I want that overhauled ... documenting everything that needs to happen ... scheduling it and holding to it." Ranked by how much of all development each item blocks. Every item is a row of the schedule (Point's `schedule/schedule.json`), with an owner, an estimate, a bound and a check that proves it done. The measured numbers come from 2026-11-12.

**Process: the worst of it**
1. **Tests that rerun everything, slowly.** A prove ran 10 to 20 min. Each test ran about 52 times a day. A docs edit reran about 280 process tests. 64% of the suite's wall time was spent waiting on boots and forks.
   - Already landed (`ee16279d99`): process tests run in forks, and fixtures are made once.
   - Still to come:
     - `jolt/tests` takes every request through the `:prove` service, with workers set from the cores;
     - recorded reads: a change reruns only the tests whose reads moved (about 8x fewer, simulated);
     - one test system per program, and a test system's agent ready in under 2 s (16 s today);
     - the check steps run as forks;
     - the shell path is deleted.
   - **Target:** the minimal set in seconds; the whole suite in under 60 s.
   - **Why first:** every change by every session and every agent waits on it.
2. **Point is the landing queue.** Every branch waits for one session to read, merge, prove and land it. Measured on 2026-11-12:
   - The median gap between landings was 16 min (worst 120 min overnight, 107 min with a 60-min failed landing).
   - LAND mails waited from under 1 min to 57 min, and nine tips were still waiting after 2.5 hours.
   - A prove reran about 60% of the suite whatever the change (median about 1,100 of 1,860 tests).
   - The 37 check steps were the slowest part of a prove (8 to 15 min).
   - 3 of 4 failed landings were overruns under load, or reds already on main; none was the branch's own fault.
   - **Proposal:** once item 1's service lands, each session runs `bin/land` on its own branch whenever it is green. `bin/land` already proves the merged tree, moves main atomically and updates the system.
   - Point reads each landed diff within the hour, and files a fix when it finds a problem.
3. **Resources and order decided by hand.** Shard counts, prove caps, holds, GOs, stops and restarts all came from Point.
   - The service owns workers and order: the asker nearest done goes first, and the critical path is a priority, not a person.
   - The data schedule wakes Point at a bound, so nothing sits.
4. **Estimates that bind nothing.** The test budget floor was 4e9 ticks, about 8,000x a typical test, and runs went 10 to 1,000 times over without anyone stopping.
   - Every item and every test states an estimate, and 10x over stops the work.
   - In code: Tests' estimate tips (the floor and the ratchet deleted, each test's budget its estimate).
   - In process: schedule bounds, and `measured.tsv` checks for the cost items.
5. **Switched-off mechanisms and workarounds.** Forks were turned off for a bug; overruns were stored as "signals" with no verdict; a check sampled tests at random.
   - **Rule:** a bug is fixed at its cause, and nothing is switched off for it.
   - **Sweep:** every config boolean or sample that disables a mechanism. Owner: Tests and Optimization.
6. **Knowledge that rots.** 257k words of docs, 583k words of mail, 34 wrong memory notes, and task plans built on the shell path.
   - Cut today: docs to 106k words, AGENTS.md rewritten, memory fixed.
   - Still to do: about 11 task plans and 10 doc sentences built on the shell path (owners Tests and Optimization). Each session rewrites its own notes at every compaction.

**The core system, by measured cost: each is 10 to 90 times what it should be (`evidence/hotspots-2026-11-12.md`)**
7. **Starting a system or an agent:** 10.7 s to boot a system, 16 s until an agent is ready (Tests and Optimization).
8. **Datahike's writes and connections**, `seed!`, `connect`, `checked`: 15% of the suite's ticks, 11 to 40x (Deep fixes).
9. **Recomputing the whole when one part changed:**
   - the schema registry (80x) and task pulls that fetch every attribute (25x bytes), both Data model;
   - a source import that re-analyzes every form, and whole-file rewrite-clj parses (20 to 90x), Optimization;
   - `ctx/moved` and the merge's diffs without shared nodes (15 to 20x), Context and Data model.
10. **Jolt:** about 56 bytes allocated per character read, eval allocation 10x per form, and a build cache that writes on every hit (fseventsd at 100%); Deep fixes, in the next pin.

**The product: the measure of all of it**
11. **The real-task loop as the standing canary.** Data model's and Context's real tasks run on the standing system continuously, and turns to green and tasks merged per day are reported. Only about 2 tasks have merged end to end since 11-09, and both of today's causes were the test system.

**Order:**
- Items 1 and 3 first, in flight now (Tests and Optimization).
- Item 2 the day item 1 lands.
- Items 4 to 6 alongside them, each by its owner.
- Items 7 to 10 by measured share, each session in its own one branch.
- Item 11 as soon as Optimization's base-digest and overrun fixes land.
- Code shrink (about 1,100 lines, `evidence/src-cuts-2026-11-12.md`) rides along in each session's branch.

## Now

**The constraint (owner, 2026-11-10): the pull → ratom → render path does not change; the work is the data on entities and the renders.** Six sessions each own an area ([sessions](sessions.md#sessions-own-their-areas)): Context, Web, Optimization, Tests, Deep fixes and the Data model, which owns no code: it is in charge of every change to schemas and pull patterns and reviews every attribute, ref, pattern and render input before it is built. One managing session keeps the landing order. A tip is proved whole by its own session and lands alone, by the managing session with `bin/land`, which reuses that prove ([a tip arrives proved whole](sessions.md#the-managing-session)); a small batch lands proved once ([merging](merging.md#the-managing-session-lands-a-batch)). Plain `bin/prove` still runs in the session's own shell, so no more than two proves run at once on this machine ([machine](machine.md#builds-and-checks)); `bin/prove --root PORT` asks the system ([a session proves through the system](merging.md#a-session-proves-through-root)). Every session and agent checks its whole work before handing it over (owner, 2026-11-10: "make the entire suite run very fast so every agent can fully check their work").

**First: all proving through the system** (owner, 2026-11-11, order B, A, D, C: [decisions](decisions.md#when-lines-of-work-collide-proving-the-product-loop-renders-as-data-jolt-fiber-safety-owner-2026-11-11)): the time from a change to main is the bottleneck (owner, 2026-11-10: "I don't want to keep wasting time running tests all day. This is the main bottleneck for progress"). Its state is part 5 of THE PLAN below.

- **Tests: a suite every agent can run whole, and reuse that is sound.** Landed: a process test's digest takes what the system runs it on; the one queue `seon.prove` takes tests, process tests, check steps, a tree's build and its seed, bounded by `max-workers`, `max-cores` and `max-load` (step 4, `40c91ad445`). Open tasks: the process tests as items run from inside (`seon.fx/process-tests-run-from-inside`), check steps as tests on the service (`seon.check/check-steps-are-tests-on-the-service`), the one call that proves a reference (`seon.program/a-process-runs-its-branchs-program`), the shell runner's deletion (`seon.prove/the-shell-runner-is-deleted`), a test's verdict holding the schema entries it read (`seon.program/a-tests-verdict-holds-the-schema-entries-it-read`).
- **Deep fixes: the dependencies** ([forks](forks.md)). On main: Jolt at `7135d660` (a fiber is a second mutator, `c5e8be67`) and the Datahike pin `7a52e4a1`. The store's locks are the kernel's, a crash loses only the commits since the last sync ([failure points](failure-points.md#lmdb-locks)). Still to land: the remaining Jolt pins of [a fiber's state](forks.md#a-fibers-state-is-safe-by-construction) (the slice and what is read per OS thread, the wind constructors, the shared tables), and the one merge (replay of the source's net change as transactions of the receiving branch in one commit, conflicts computed and never stored: the code is not on main). Owed: the 13 of Jolt's 195 gates still red on its line (`jolt.host/jolt-is-at-upstreams-head`).
- **Optimization: the running system's cost in Seon's own code.** Tests read a branch from the store themselves (batch 40); still through the system: the MCP relay's reads and the system's own branch reads through the writer's queue; every wait ending by its producer's own quiet (`seon.check/a-wait-on-a-quiet-root-that-cannot-come-ends`); a merge loaded in place unbinding what it deletes (`seon.program/a-merge-loaded-in-place-unbinds-what-it-deletes`); the fork server serving while it warms (`seon.fork-server/a-first-fork-waits-for-nothing-it-does-not-use`); a merged task made in the store reaching main's files (`seon.reconcile/a-store-made-tasks-merge-reaches-mains-files`).
- **Context: what an agent reads.** The model's request at what moved (`seon.render/a-model-render-draws-each-turns-diff`); what one contract check costs (`seon.contract/a-contract-is-checked-once-and-costs-nothing`); the task files kept true, every ready task naming its proof by its claim.
- **Web: pages built from data** ([decisions](decisions.md#pages-are-built-from-data-by-one-walk-owner-2026-11-10)): landed, the system's and the user's page are the agent's render over their own pulls (step 3). Still to do: the page's separate renders and hand-placed lists deleted (`seon.render.ui` is 2,589 lines), then the schema changes it needs under one store erase (senders as refs, one ref for the agents an agent started).

What the model reads and a person sees is one design ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)): an agent's history is its own pulled data as native messages; what is looked up or computed is computed once, in the hiccup render, shown styled and read by the model stripped; the conversation's display is never sent to the model twice.

**When lines of work collide the order is B, A, D, C** (owner, 2026-11-11, [decisions](decisions.md)): B, all proving through the system; A, the product loop; D, renders as data; C, Jolt fiber safety.

Open with the owner, each brought when it blocks work: whether an agent's render edit shows before its merge; task kinds (`:seon.task/kind` is read on 33 lines of `src` against the 2026-11-08 ruling); restating in ticks the cost proofs whose call bounds count contract checks, and tightening five that sit far above what is measured; whether new entity ids from a content hash are worth the index pages they scatter (303 datoms dirtied 350 leaves); the fork commits of 2026-11-10 that carry AI trailers against docs/forks.md; whether an agent's busy and idle belong on main.

## The plan

**The system goal (owner, 2026-11-11):** "get the system ready for a recursive self improvment of the entire codebase. Correctness, perf, complexity (how many functions are interwoven? we have the graph...), number of steps for agents to solve their tasks". And how: "The goal isn't radical change, but refinement over time. Think lots of base hits rather than swinging for homeruns." Each ruling behind a part is in [decisions](decisions.md#2026-11-11-rulings).

**Held to:** the pull → ratom → render path does not change, and the data is designed for the pull; one agent mechanism for every role, root included; no hand-rolled system and no hard-coded word or role ("We need system based rules"); nothing is built from a design the Data model session has not reviewed.

The parts, in the order they unblock each other. Parts 4 to 7 run beside the others.

1. **The data model** (Data model session). A design was written on 2026-11-11 and is not agreed: the owner wants it worked through live in Seon, one small step at a time ("start smaller. What's the next step that will strain our data model and let's focus on refining the data model and keep building up from there").
   - **The step landed:** a pattern can ask for the transaction an attribute was written in, beside its value (`:tx`, batch 39 and 44; [ctx](ctx.md)). Placing an agent's messages by that time landed and was reverted (batch 48: an entity whose stored pattern predates the join had its messages placed nowhere); the task `seon.ctx/a-windowed-join-asked-its-transaction-patches-as-a-build` holds the relanding.
   - **The step after:** a finished agent that a task names as an example (`:seon.task/examples`) is shown by the agent's own renders from the task's pull, which shows by running it whether a session is just an agent (the owner's lean).
   - The rest of this list is the design's direction, built only as each step reaches it.
   - A session is an agent: an agent names the agent whose conversation it continues, a fork's branch is the cut, and the words that open a conversation are data.
   - An agent holds a set of tasks, and all must pass.
   - The hierarchy is `:seon.agent/agents` at any depth; a question goes to whoever started the task; `:seon.agent/root` goes (it is still written in `src/seon/system.clj` and `src/seon/task.clj`).
   - What is observed is a fact on the thing observed; what is computed from facts (a score, a problem, a standing against a bound) is a render, never stored.
   - One store erase carries every change of a stored attribute. Debt: `:seon.task/kind` is read on 33 lines of `src` against the 2026-11-08 ruling; no template adds a value to it.
2. **The self-improvement loop** (Context, once part 1 is agreed).
   - A chain by `:seon.task/needs`, each step a fresh agent handed what the one before produced: work → refine → review.
   - Refine: the worker's conversation continued, with the task to curate its own task and bring the docs it pulled up to date, so the work would have gone better.
   - Review: a separate agent with its own proofs. It judges and files tasks, and never edits the work it reviews. Green merges a change to the system itself; the owner gets a digest and can revert. Not started; no task entry.
   - A refinement is tried by running the work again from the earlier point in the store's history, beside the original, which is never overwritten. Not started; no task entry.
   - Mid-task, a fork with a small task reflects on the work so far, more often early. Not started; no task entry.
   - A reviewed conversation is written to an import area in the repository, read as the task files are, so a store erase loses nothing. Not started; no task entry and no code.
   - Done: the finishing agent's reply says what would have saved it turns (batch 34).
3. **The system as the strategic layer** (Web, with Optimization).
   - The user talks to the agent at the top of the hierarchy; it picks direction and presents; a focus agent for each area (context, web, tests, storage, runtime to begin) is an ordinary long-running agent; a sub-agent's contact is whoever started its task. Web's part: the system's session presents as the strategist (task ``seon.render/the-systems-session-presents-as-the-strategist``) and a focus agent's result reaches the system's page by its own render (task ``seon.render/a-focus-agents-result-is-on-the-systems-page``); the focus agents themselves and their parent contact are Optimization's.
   - A layout is a render over the same pulled data, and the chat beside its tiles is the default one. Built: an entity names its own layout as its ``:seon.render/hiccup``; the system's session is given the stage (``seon.render.ui/stage``) at boot, so a fresh system opens on "Hello." and how to ask, then the latest exchange in front in large type with the values its turn made drawn inline, and one fold turns the person's view back to the conversation ([an entity may name its own render](render.md#an-entity-may-name-its-own-render)). Left: what a fresh system shows above the stage (task ``seon.render/a-fresh-system-says-nothing-it-has-not-done``).
   - Done: the system's and the user's page are the agent's render over their pulls (pages from data, steps 1 to 3, batch 33).
4. **Agents stop asking the system for what they can read** (Optimization). An agent's process already reads every branch itself, and tests now read a branch from the store themselves (batch 40); the reads that still go through the system come from the MCP relay and its own branch reads. Then the removal of `:seon.agent/root`, with part 1's erase.
5. **The test system inside the runtime, as a Flow** (Tests, with Optimization). Owner, 2026-11-11: "Confirm we are building the test suite for the runtime version of seon not the offline test version that's processing files, but the online version that's processing our code in the db"; "Just keep the worktrees and index it like I said into a branch and then you can run it from the mcp tools" ([decisions](decisions.md#the-test-suite-is-for-the-online-version-in-the-db-and-the-workflow-is-the-agents-owner-2026-11-11)).
   - **Landed (step 4, `40c91ad445`):** one queue, `seon.prove`, for tests, process tests, check steps, a tree's build and its seed; as many workers as `max-workers`, `max-cores` and `max-load` allow, each item run once for every asker, no locks, no timers; an asker gets every one of its proofs' verdicts past a red ([merging](merging.md#the-test-service)). A run refuses when the process's program differs from its branch (`:seon.agent/program-differs`), and a tree's run names every red.
   - **Still in a shell:** plain `bin/prove` and its shards, `bin/land`'s `bin/prove --merge`, the daily full run, and the bodies of process-test and step items. A landing proved through the system (batch 82, 2026-11-11) starved: 370 verdicts in 29 minutes of about 1,600 items, since the system's plan and the shell's key tests by different digests and share no greens (task `seon.prove/one-digest-whichever-runner-plans-it`).
   - **The direction** ([decisions](decisions.md#proving-is-one-call-on-a-reference-fork-main-load-that-points-program-whole-run-its-proofs-owner-2026-11-11)): proving is one call that takes a reference to a branch or a point in time (`seon.system/prove!` given the reference, ruled 2026-11-11, task `seon.program/a-process-runs-its-branchs-program`): a process forked from main loads the forms where the reference differs (```seon.program/load-diff!```), drops the vars it lacks and asserts nothing differs, then runs its proofs; the fallback is a binary built from the reference's source. The whole program loads in one eval (5,676 forms, no error, measured 2026-11-11), but at 134 s it is the far-back replay, not the route for a branch (a warm build is 62 s).
   - **Open tasks:** `seon.prove` (its entries in `resources/seon/tasks/seon.prove.edn`), `seon.check/check-steps-are-tests-on-the-service`, `seon.fx/process-tests-run-from-inside`, `seon.system/a-session-is-a-branch-and-bin-prove-asks-root`, `seon.program/a-process-runs-its-branchs-program`.
6. **Measures as a system** (Tests, with Context): a measurement is one call, and precision accumulates from normal use; a measure past its threshold is a problem, root files a task with the evidence, an agent works it, a review judges, the measure moves ([cost](cost.md#a-bound-in-the-contract)).
7. **The store** (Deep fixes): done, the store's locks are the kernel's and a crash with sync off never corrupts (batch 35), and a point is found past a merge whose merged-in parent was collected (the Datahike pin `7a52e4a1`). Next, what a commit and a diff cost.
8. **How we work** is [AGENTS.md](../AGENTS.md) §2 and §3 and [sessions](sessions.md).

**Waiting on the owner:** the data model, step by step with him (part 1); whether verdicts are store facts or stay the evictable cache he ruled on 2026-10-28 (part 5); how the three waived request cost proofs are bounded (part 6).

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing system, with the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: the 366 task entries of `resources/seon/tasks`, not all shown valid.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
