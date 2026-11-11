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

## Now

**The constraint (owner, 2026-11-10): the pull → ratom → render path does not change; the work is the data on entities and the renders.** Six sessions each own an area (Context, Web, Optimization, Tests, Deep fixes, and since 2026-11-11 the Data model, which owns no code: it holds the data model as one design and reviews every attribute, ref, pattern and render input before it is built), and the managing session batches their ready branches: each session proves its own branch with `bin/prove` (everything its change reaches, the check steps included, every green at its digest reused: the whole suite at the cost of what changed), and the managing session merges the tips into one branch, proves it once and lands it ([merging](merging.md#a-branch-lands-with-binland)). Every session and agent checks its whole work before handing it over (owner, 2026-11-10: "make the entire suite run very fast so every agent can fully check their work", replacing "nobody proves a small change alone").

**First: the time from a change to main** (owner, 2026-11-10: "I don't want to keep wasting time running tests all day. This is the main bottleneck for progress"). Measured that day: an agent's `done` on a one-function change 455 s against about 5 s designed; one process test 0.9-1.2 s against 60 ms, nearly all of it waiting on root; a whole prove 9-15 minutes against 60-90 s.

- **Tests: a suite every agent can run whole, and reuse that is sound.** A process test's digest takes what its root runs (the owner reversed the 2026-10-27 rule on 2026-11-10: a writer change had reused 226 of 233 greens); every prove reruns a sample of the greens it reused; one pre-tip command; then the speed budget by seconds saved: process tests from inside (`seon.fx/process-tests-run-from-inside`), a session's branch proved by root (`seon.root/a-session-is-a-branch-and-bin-prove-asks-root`), check steps as tests (`seon.check/check-steps-are-tests-on-the-service`), recorded reads (`seon.program/a-tests-verdict-holds-the-schema-entries-it-read`), every reader of a binary's tree asking one rule (`seon.root/every-reader-of-the-binarys-tree-asks-binary-current`).
- **Deep fixes: the dependencies.** Jolt is at upstream's head; 13 of Jolt's own 192 gates are still red on its line (`jolt.host/jolt-is-at-upstreams-head`). The store's locks are the kernel's record locks and an OS crash loses only the commits since the last sync ([failure points](failure-points.md#lmdb-locks)); an open reads no branch head once its ids page is stamped ([storage](storage.md#ids)). What a commit, a diff, a seek and a cold decode cost: a one-datom ``d/transact`` 171k ticks on a 28.7k-datom store, about 150k designed; through root's writer 23-26 nodes a write, 13 of them the transaction entity's (`datahike.writer/a-commit-of-one-datom-costs-its-nodes`); a diff of two commits 238k ticks for 21 datoms, 22.2 M for 203 scattered (`datahike.db/a-diff-of-two-commits-costs-the-nodes-that-differ`). Datahike as upstream's head plus what Seon needs.
- **Optimization: the running system's cost in Seon's own code.** Readers read the store themselves, not through root: tests' and the MCP relay's reads of a branch (each through root ~14 ms and an eval record on main, against ~1 ms read directly) and root's own reads through the writer's queue; a root's write-back that leaves its checked-out tree clean (`seon.fx/a-write-back-moves-the-checked-out-trees-files`); every wait ending by its producer's own quiet (`seon.check/a-wait-on-a-quiet-root-that-cannot-come-ends`), the problems step's unrecorded stall among them; the parent, not root, as the agent's point of contact (the de-rooting, through the Data model session); a merge loaded in place unbinding what it deletes (`seon.program/a-merge-loaded-in-place-unbinds-what-it-deletes`); the fork server serving while it warms (`seon.fork-server/a-first-fork-waits-for-nothing-it-does-not-use`); a merged task made in the store reaching main's files (`seon.reconcile/a-store-made-tasks-merge-reaches-mains-files`).
- **Context: what an agent reads.** The model's page at what moved (a cold request at ten turns 37.4k calls that morning, about 12k now, bound 2,700; `seon.render/a-model-render-draws-each-turns-diff`); what one contract check costs (`seon.contract/a-contract-is-checked-once-and-costs-nothing`); the task files kept true (349 entries to 301 that day, every ready task naming its proof by its claim).
- **Web: pages built from data** ([decisions](decisions.md#pages-are-built-from-data-by-one-walk-owner-2026-11-10)): one walk over the pull, each entity drawn by its schema's render, on top or nested; root, the user and an agent differing by their data alone; the page's separate renders and hand-placed lists deleted (`seon.render.ui` 2,437 lines when it began); then the schema changes it needs under one store erase (senders as refs, one ref for the agents an agent started, root's agent made at boot).

What the model reads and a person sees is one design ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)): an agent's history is its own pulled data as native messages; what is looked up or computed is computed once, in the hiccup render, shown styled and read by the model stripped; the conversation's display is never sent to the model twice.

Open with the owner, each brought when it blocks work: whether an agent's render edit shows before its merge; task kinds; restating in ticks the cost proofs whose call bounds count contract checks, and tightening five that sit far above what is measured; whether new entity ids from a content hash are worth the index pages they scatter (303 datoms dirtied 350 leaves); the fork commits of 2026-11-10 that carry AI trailers against docs/forks.md; whether an agent's busy and idle belong on main.

## The plan

**The system goal (owner, 2026-11-11):** "get the system ready for a recursive self improvment of the entire codebase. Correctness, perf, complexity (how many functions are interwoven? we have the graph...), number of steps for agents to solve their tasks". And how: "The goal isn't radical change, but refinement over time. Think lots of base hits rather than swinging for homeruns." Each ruling behind a part is in [decisions](decisions.md#2026-11-11-rulings).

**Held to:** the pull → ratom → render path does not change, and the data is designed for the pull; one agent mechanism for every role, root included; no hand-rolled system and no hard-coded word or role ("We need system based rules"); nothing is built from a design the Data model session has not reviewed.

The parts, in the order they unblock each other. Parts 4 to 7 run beside the others.

1. **The data model** (Data model session). A design was written on 2026-11-11 and is not agreed: the owner wants it worked through live in Seon, one small step at a time ("start smaller. What's the next step that will strain our data model and let's focus on refining the data model and keep building up from there").
   - **The step being built:** a pattern can ask for the transaction an attribute was written in, beside its value, so an agent's messages are ordered by when they arrived and no code asks which turn read one. Nothing new is stored. Its proof: the recorded requests do not change by a byte.
   - **The step after:** a finished agent that a task names as an example is drawn by the agent's own renders from the task's pull, which shows by running it whether a session is just an agent (the owner's lean).
   - The rest of this list is the design's direction, built only as each step reaches it.
   - A session is an agent: an agent names the agent whose conversation it continues, a fork's branch is the cut, and the words that open a conversation are data.
   - An agent holds a set of tasks, and all must pass.
   - The hierarchy is `:seon.agent/agents` at any depth; a question goes to whoever started the task; `:seon.agent/root` goes.
   - What is observed is a fact on the thing observed; what is computed from facts (a score, a problem, a standing against a bound) is a render, never stored.
   - One store erase carries every change of a stored attribute. Debt: 34 sites in `src` branch on `:seon.task/kind` against the 2026-11-08 ruling; no template adds a value to it.
2. **The self-improvement loop** (Context, once part 1 is agreed).
   - A chain by `:seon.task/needs`, each step a fresh agent handed what the one before produced: work → refine → review.
   - Refine: the worker's conversation continued, with the task to curate its own task and bring the docs it pulled up to date, so the work would have gone better.
   - Review: a separate agent with its own proofs. It judges and files tasks, and never edits the work it reviews. Green merges a change to the system itself; the owner gets a digest and can revert.
   - A refinement is tried by running the work again from the earlier point in the store's history, beside the original, which is never overwritten.
   - Mid-task, a fork with a small task reflects on the work so far, more often early.
   - A reviewed conversation is written to an import area in the repository, read as the task files are, so a store erase loses nothing. None exists yet: no task file names an example, and `runs/curated` holds one file in a shape the import refuses (read 2026-11-11).
   - Done: the finishing agent's reply says what would have saved it turns (batch 34).
3. **Root as the strategic layer** (Web, with Optimization).
   - The user talks to root; root picks direction and presents; a focus agent for each area (context, web, tests, storage, runtime to begin) is an ordinary long-running agent; a sub-agent's contact is whoever started its task.
   - A page is a stage: a layout is a render over the same pulled data, and the chat beside its tiles is the default one. First: root on a fresh system.
   - Done: root's and the user's pages are the agent's render over their pulls (pages from data, steps 1 to 3).
4. **Agents stop asking root for what they can read** (Optimization). An agent's process already reads every branch itself; the reads that still go through root come from tests, the MCP relay and root's own branch reads. Then the removal of `:seon.agent/root`, with part 1's erase.
5. **The test system inside the runtime, as a Flow** (Tests, with Optimization): one service keyed by a test at a digest, as many workers as the cores hold, each pulling one item; every item run once for every asker; an asker answered at its first red or its last green; `bin/prove` a thin client that sends a branch. No shards, no locks, no timers ([merging](merging.md#the-test-service)).
6. **Measures as a system** (Tests, with Context): a measurement is one call, and precision accumulates from normal use; a measure past its threshold is a problem, root files a task with the evidence, an agent works it, a review judges, the measure moves ([cost](cost.md#a-bound-in-the-contract)).
7. **The store** (Deep fixes): done, LMDB's locks are the kernel's and a crash with sync off never corrupts (batch 35). Next, what a commit and a diff cost.
8. **How we work** is [AGENTS.md](../AGENTS.md) §2 and §3 and [sessions](sessions.md).

**Waiting on the owner:** the data model, step by step with him (part 1); whether verdicts are store facts or stay the evictable cache he ruled on 2026-10-28 (part 5); how the three waived request cost proofs are bounded (part 6).

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
