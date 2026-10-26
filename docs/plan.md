# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`notes/merge-log.md` names every merge and how it was verified).

## Now (2026-10-26, evening)

Pins (docs/forks.md): Chez fa6a4c73; Jolt 2eda21da (d598fc04, the fiber dump, merging next); Datahike e5a55664; LMDB 15420ce; http-client e866277d. Paid model runs: $30 overnight (owner, 2026-10-26 night), only after a free audit of each request's context finds none of its known problems, and once the hang fixes are on main.

**Where it stands.** Main is green on prove, `bin/check contracts`, the process tests and smoke. A request is one whole task by default (design, build and merge in one run; owner, 2026-10-26 night); design-build and `stages` remain as experiment choices passed to `my.root/request!`. The last live run (design-build, 2026-10-26): 1/10 merged, 93% cached, 1.7k first-turn tokens; four of the ten requests were already done on main (refreshed since) and the rest capped for context the page lacked (notes/context-for-20-turns-2026-10-26.md).

**The direction.** Three things, in this order of urgency:
1. **Stability: no process waits forever unseen.** Today's hangs each had a cause found and fixed (the watcher's lost first event, a dead fork server's waiters, a test child's held pipe, a forked child unwinding into its parent, a fresh root booting with no tasks). A stall becomes a fact, an open problem and a task for root (agent loop, building). `kill -INFO` on any process prints every fiber and what it waits on (the fiber dump).
2. **One rendering system** (notes/render-design-2026-10-26.md, the agreed design): ctx is the agent's data pulled by a stored pattern and patched by each transaction with untouched parts `identical?`; a render is `(fn [opts & children] hiccup)`; the walk draws every value by its render or its schema's render; one cache in the shape of Reagent's ratom with inferred cursors, plus `d/with-reads` for renders that query; the model reads the same hiccup, whole on the first turn and then the diffs.
3. **Context good enough for about 20 turns,** and work the system can check: cost bounds on contracts, a score of the rules (notes/rules-score/).

**How work moves.** Sessions build on worktrees, self-review, and report ready to root; root merges one branch or a small batch after verifying it on main: `bin/prove` (its left-out count compared), `bin/check contracts`, the process tests the change reaches by name (always `seon.main-test`), and smoke. Nothing tracked is edited while a verification runs. A red on main gets an owner within the hour. A worktree is removed when its branch merges.

## In flight

What each working session is doing now and takes next; every session is linked to root's (list_sessions linked).

| Session | Now | Next |
|---|---|---|
| **Render build** (agent view) | build 3 on jolt/render-walk: seon.ratom (Reagent's ratom, cursors inferred), the walk, one cache, the per-render ledger, the task render drawing cited source, tests and section text within the printer's budget; deletes the parts layer and the hand-built cache keys | 3b-root (task seon.render/every-page-is-its-parts): root's page and the debug page through the same walk, deleting seon.render.system and the old unit walk |
| **ctx** (ctx-atom) | D1–D5 page data ready to merge (jolt/the-page-holds-what-the-work-needs); `seon.ctx/changes` walked in step over sorted collections (no sets) | the cited-var choice for the page (D5) |
| **Cost bounds** (related sections) | declared design step 1 merged (`:seon.cost/bound`); build 4's proofs written red (jolt/the-request-is-the-page-and-its-diffs) | build 4 after build 3: the model's request from the page and the two diffs, deleting the 280 ms per-turn rebuild; then declared design steps 2–4 |
| **Agent loop** | stall visibility (jolt/agent-loop): the stall fact, the :stalled problem, the stall task with `:seon.task/cause`, bin/test's STALLED bound relative to each test's history | the busy-with-no-process defect |
| **Stability** (stability lead) | piece 1: the fork server's boot order (jolt/zygote-one-mode), rebased on Jolt d598fc04 | one process mode for every forking process |
| **Hangs** (full-run reds) | the fiber dump ready (jolt/jolt-fiber-dump) | agent a6's wait on root (cause unknown); the next full run on a clean main |
| **Live agents** | one-go default with the merge gate judging main's tests, not the branch's (proof red, fix next) | the next live run on the refreshed requests, when the owner allows paid runs |

## Waiting on the owner

The rulings and calls root holds for the owner.

- Delete jolt/evaluations, jolt/acts-by-second-move and jolt/flow-lab (carried forward or superseded; notes/branch-merit-2026-10-26.md); carry or delete examples, wiki, unitflake, one-content-keyed-derivation.
- Three worktrees hold uncommitted work (live-agents, stability's jolt/fork-guard, acts-by-second-move): keep or drop.

## THE PLAN

The destination is **E: a request worked as one whole task by a Seon agent, end to end, measured as turns to green.** In order:

1. **No hang goes unseen** (in progress): every wait ends on its peer's exit or answer; a stall is a fact, an open problem and root's task; the fiber dump names any wait. Left: agent a6's wait on root, one process mode.
2. **One rendering system** (in progress, builds 3, 3b-root and 4): one walk, one cache, the model reads the page.
3. **Context for about 20 turns** (in progress): the page holds cited source, tests with their last results and section text (D1–D5), cut to budget; the one-go instructions (reproduce first; stop reading once the output is wrong; build inputs like the existing tests); requests checked not done on main. Measured by the next live run.
4. **Merge only when it can:** auto-merge on facts (main's tests green on the merged tree, not the branch's set), merge-readiness on the agent's page each turn.
5. **Work the system can check** (owner, 2026-10-26: "reduce programming to its more mathematical origins"): a contract carries `:seon.cost/bound`; a solution passes only when every function it defines or modifies, and every project function its code references (`:form/refs`), has a contract and a bound, and the composed bound fits the task's budget. Declared design (functions declared with contract and bound before they exist, tests red) is the experiment; one whole task stays the default until it proves out. A score of the rules (notes/rules-score/) is revised before any agent sees it: it ranked the deleted parts layer first.
6. **Root is main, one system:** root's work as tasks, root's page through the same walk, the fork server's boot order and one process mode.
7. **Self-modifying environment:** agents change their own ctx (swap!), renders, layout and pull pattern, seen at once.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
