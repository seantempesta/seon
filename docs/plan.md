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

**The constraint (owner, 2026-11-10): the pull → ratom → render path does not change; the work is the data on entities and the renders.** Five sessions each own an area, and the managing session batches their ready branches: each session proves its own branch with `bin/prove` (everything its change reaches, the check steps included, every green at its digest reused: the whole suite at the cost of what changed), and the managing session merges the tips into one branch, proves it once and lands it ([merging](merging.md#a-branch-lands-with-binland)). Every session and agent checks its whole work before handing it over (owner, 2026-11-10: "make the entire suite run very fast so every agent can fully check their work", replacing "nobody proves a small change alone").

**First: the time from a change to main** (owner, 2026-11-10: "I don't want to keep wasting time running tests all day. This is the main bottleneck for progress"). Measured that day: an agent's `done` on a one-function change 455 s against about 5 s designed; one process test 0.9-1.2 s against 60 ms, nearly all of it waiting on root; a whole prove 9-15 minutes against 60-90 s.

- **Tests: a suite every agent can run whole, and reuse that is sound.** A process test's digest takes what its root runs (the owner reversed the 2026-10-27 rule on 2026-11-10: a writer change had reused 226 of 233 greens); every prove reruns a sample of the greens it reused; one pre-tip command; then the speed budget by seconds saved: process tests from inside (`seon.fx/process-tests-run-from-inside`), a session's branch proved by root (`seon.root/a-session-is-a-branch-and-bin-prove-asks-root`), check steps as tests (`seon.check/check-steps-are-tests-on-the-service`), recorded reads (`seon.program/a-tests-verdict-holds-the-schema-entries-it-read`), every reader of a binary's tree asking one rule (`seon.root/every-reader-of-the-binarys-tree-asks-binary-current`).
- **Optimization: the running system's cost in Seon's own code.** Root's writer answering a write when it is applied (a write's wait 21-24 ms against 0.45 ms measured on its branch; taken out of a batch for ten reds, being fixed); a root's stop in under a second; the notice that says whether program content moved, so an agent and root's readers skip a commit that touches nothing they read (`seon.turn/a-merge-turn-is-a-turn-with-only-the-merge-left`); the fork server serving while it warms (`seon.fork-server/a-first-fork-waits-for-nothing-it-does-not-use`); a merged task made in the store reaching main's files (`seon.reconcile/a-store-made-tasks-merge-reaches-mains-files`).
- **Deep fixes: the dependencies.** Jolt is at upstream's head; 13 of Jolt's own 192 gates are still red on its line (`jolt.host/jolt-is-at-upstreams-head`). The store's locks are the kernel's record locks and an OS crash loses only the commits since the last sync ([failure points](failure-points.md#lmdb-locks)); an open reads no branch head once its ids page is stamped ([storage](storage.md#ids)). What a commit, a diff, a seek and a cold decode cost: a one-datom ``d/transact`` 171k ticks on a 28.7k-datom store, about 150k designed; through root's writer 23-26 nodes a write, 13 of them the transaction entity's (`datahike.writer/a-commit-of-one-datom-costs-its-nodes`); a diff of two commits 238k ticks for 21 datoms, 22.2 M for 203 scattered (`datahike.db/a-diff-of-two-commits-costs-the-nodes-that-differ`). Datahike as upstream's head plus what Seon needs.
- **Context: what an agent reads.** The model's page at what moved (a cold request at ten turns 37.4k calls that morning, about 12k now, bound 2,700; `seon.render/a-model-render-draws-each-turns-diff`); what one contract check costs (`seon.contract/a-contract-is-checked-once-and-costs-nothing`); the task files kept true (349 entries to 301 that day, every ready task naming its proof by its claim).
- **Web: pages built from data** ([decisions](decisions.md#pages-are-built-from-data-by-one-walk-owner-2026-11-10)): one walk over the pull, each entity drawn by its schema's render, on top or nested; root, the user and an agent differing by their data alone; the page's separate renders and hand-placed lists deleted (`seon.render.ui` 2,437 lines when it began); then the schema changes it needs under one store erase (senders as refs, one ref for the agents an agent started, root's agent made at boot).

What the model reads and a person sees is one design ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)): an agent's history is its own pulled data as native messages; what is looked up or computed is computed once, in the hiccup render, shown styled and read by the model stripped; the conversation's display is never sent to the model twice.

Open with the owner, each brought when it blocks work: whether typing on home is a message to root; whether an agent's render edit shows before its merge; task kinds; restating in ticks the cost proofs whose call bounds count contract checks, and tightening five that sit far above what is measured; whether new entity ids from a content hash are worth the index pages they scatter (303 datoms dirtied 350 leaves); the fork commits of 2026-11-10 that carry AI trailers against docs/forks.md; whether an agent's busy and idle belong on main.

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
