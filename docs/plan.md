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

What each session is on, in order:

- **Context: agents doing real work.** A person's code owes no wiki citation (`seon.ctx/a-persons-code-owes-no-wiki-citation`); stage 2b with Web, the model's page as generated `my.render/page` and `my.render/diff` calls (`seon.render/a-model-render-draws-each-turns-diff`); search cost (`seon.ctx.search/a-search-costs-what-it-ranks`); a triage of every task file (current, rewritten to what is true, done, or not work yet; proofs stated for what stays), judged by the `:stale-plan` and `:no-proofs` rows of [health](health.md) going down; then the curated task pairs measured again.
- **Web: the UI the user drives.** Root's session pulls every agent and moves a person's view (`my.user/focus!`), the user schema's own pattern, the home tiles (one store erase); then stage 2b's page half; then one frame for every role (the user entity's render holds the focus; sidebar, conversation and tiles by the same renders), a CSS transition on a view change, the system entity's pattern without `*` and `:as`, eval tiles, and the hiccup as a schema-checked intermediate.
- **Optimization: the running system's cost in Seon's own code.** A merged task reaches main's files: a reviewed sync, a problem row for every merged task the files do not hold (`seon.reconcile/a-merged-task-reaches-mains-files`; owner, 2026-11-10: "If the task is done well integrate it and merge to disk and remove the task"); a root brings its store's main to the checkout's tree at start, building and indexing only what changed (owner, 2026-11-10; the plan first); an agent's turn, measured by phase on the real program, then the merge of main costing what moved (`seon.turn/a-merge-turn-is-a-turn-with-only-the-merge-left`); boot, fork and the store.
- **Deep fixes: the dependencies.** Jolt at its upstream fixes, gate by gate; Datahike's upstream merge in slices; the decode cost in every first fork; the remaining waived cost proofs; embeddings by content identity after Context's discovery numbers.
- **Tests: tests and proving, and no slow tests** (owner: "there shouldn't be any slow tests"). The process tests' cost a test (1.2 s measured against 50-100 ms designed); every reader of a binary's tree asking `seon.root/binary-current`; laws judged on the forms that moved; recorded reads, so a schema edit reruns what read the entry and not most of the suite; the daily full run, its reds as tasks; the gaps a digest still has ([merging](merging.md#what-a-digest-holds-and-what-it-does-not-yet)); a check step on a branch of the seeded store, never a file copy.

A task a Seon agent finishes is reviewed by the session that owns its area, fixed there when it is almost right, brought to main's files and its entry removed (owner, 2026-11-10).

What the model reads and a person sees is one design ([render](render.md#the-models-context-a-cached-prefix-then-the-turn)): an agent's history is its native messages; what is processed or looked up goes through the hiccup, an intermediate format with no styling; the conversation's own display is never sent to the model twice.

Open with the owner, one at a time as each blocks: whether process tests are reached by a change to root; whether typing on home is a message to root; whether an agent's render edit shows before its merge; task kinds; the three remaining waived cost proofs; the pull requests upstream.

## After that

These follow once the tree works, in this order.

1. A Seon agent works real tasks on the standing root, on the owner's yes, end to end. Measured as tasks merged, turns to green and time from a proved change to main.
2. The backlog: 309 tasks kept after a first pass, not all shown valid; 15 wait on the owner, one decision at a time.
3. Compaction, once the tree works: an eval is data, so removing or reducing one is a change the tree re-renders.

What happened and when is in git's merge messages, and how the system works is in the [map](README.md).
