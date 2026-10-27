# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`claude_scratch/merge-log.md` names every merge and how it was verified).

## Now (2026-10-27, evening)

Pins (docs/forks.md): Jolt 260a1667 on its way (forked children run none of the parent's hooks or fibers); Datahike 24e7ecda; konserve 3d7ff89. Paid model runs: none spent.

**Where it stands.** Main takes batches continuously (docs/merging.md): a branch proves its own tip, the merge session merges every ready branch and runs one `bin/prove --merge` per batch, and nothing waits on a lock. Main is not yet green: each red has an owning session, named in the last merge-log reading. Test digests now cover only what a test reaches, so a one-function edit reruns tens of tests, not hundreds; the daily full run (cron, `bin/test --all`) is the net for what reach misses.

**What the day found.** A forked child ran its parent's shutdown hooks and could run its fibers, which explains the day's hangs (replies swallowed, agents parked in `recv!`) and is the likely cause of a collector crash in test children; fixed in Jolt, verifying. The automatic run of main's tests on every program commit queued every `(prove)` behind it; it is off by default. The first work done entirely through the server (step 0.7) and a blind run of a task from its context alone both worked their tasks through `seon_eval` in 10-14 evals and were held only by the harness: proofs proved by name counting as unknown, a dead unrelated test making a run's proofs unknown, and cost proofs measuring an agent's own overhead.

**The direction.** The harness findings first (they block 0.7's merge), then the task render the blind agent measured, then relevance in search and in the render's related part.

## In flight

Each Claude session owns an area and takes its next task itself (AGENTS §3); root's list of sessions and their current work is the app's linked sessions.

| Area | Next |
|---|---|
| test service as an agent sees it | names-proved results count; a short prove answer |
| agent-root asks; forks | every ask answered; children run only their own thunk |
| Jolt and Chez | the timer-then-fork deadlock; the collector crash, under the heap verifier |
| renders | Phase B (an eval's tests never block the loop), then `observe` removed, then the blind agent's render fixes |
| the outside REPL | MCP answers are the agent's own turn messages (one producer) |
| relevance | search's `:relevance`/`:min`; a cross-encoder benchmarked on the judged labels |
| tests as facts | results on the standing root through its one writer; per-function cost from every run |
| step 0.7 | the held print fix's `seon_done` retried once the harness fixes land |

## Waiting on the owner

Nothing.

## THE PLAN

The destination is **E: a request worked as one whole task by a Seon agent, end to end, measured as turns to green.** In order:

0. **All work inside Seon; worktrees retired** (owner, 2026-10-27: "The goal is to stop using the worktrees and to do all work within seon itself"). Claude sessions work as outside agents (seon_attach, seon_eval at their branch's REPL, seon_done), merged by Seon's one gate and written back by root. The steps that move it forward, in order, each a task with its proofs:
   1. **Main follows the checkout** (merged).
   2. (merged) **A merge's write-back is never lost** (seon.root/root-repairs-a-failed-merge-from-its-repl, gaps 1-2): a failed write-back is a root cause with a retry (my.root/write-back!), and a worktree root never writes the shared merges ref.
   3. **An outside agent is a first-class worker:** attach no longer leaves the task's model "outside" for good (gap 5); my.root/hold! takes a running run back (gap 6); a merge never stays :behind forever, and :behind reruns are bounded (gaps 3-4); a behind branch merges main before it is judged (gap 7).
   4. (merged: digests by reach, no whole-tree inputs; the daily full run is the net) **The gate is cheap:** results reused across bin/test and the root's judge (today 0 of 917, because :env digests the whole config in force; seon.merge/an-outside-branch-hands-back-through-the-store), so a merge reruns only what it moved.
   5. **The tools an agent needs at the REPL are there:** tests-reaching merged, the root-repair playbook written into docs/merging.md with runnable forms, and help that names every form an outside agent uses.
   6. (merged) **Watchable:** 3b-root finished, so root's page and an agent's page and debug panel show the work live.
   7. (in progress: worked twice through `seon_eval`, held by the harness) **The first real piece of work done entirely through the server** on the standing root by a Claude session: one small src change from the task files, attached, worked at its branch's REPL, merged by Seon, written back. Every place it needed git, a worktree or a workaround becomes a task.
   8. **From then on, new work starts as a Seon task worked through the server;** a worktree only for what the store doesn't hold yet (docs/, bin/, deps.edn, pins, native code), each such case a task to bring it inside: docs and task files are already data in the store, so their write-back comes first.

1. **No hang goes unseen** (mostly done): every wait ends on its peer's exit or answer; a stall is a fact with its evidence and root's task; the fiber dump names any wait; flaky results are kept and shown. Left: one process mode, the fiber dump in a private wait.
2. **One rendering system** (build 3 merged; 3b-root and build 4 left): one walk, one cache, the model reads the render of ctx; root's page through the same walk.
3. **Context for about 20 turns** (in progress): the page holds cited source, tests with their last results and section text (D1–D5), cut to budget; the one-go instructions (reproduce first; stop reading once the output is wrong; build inputs like the existing tests); requests checked not done on main. Measured by the next live run.
4. **Merge only when it can, by definitions:** a work task merges itself through the one gate (main's tests on the merged program, results reused by digest); conflicts are per var and root repairs them from its REPL (the playbook); Seon's merge path is kept whole and exercised before anything of it is cut; Claude worktree branches come through the store only once an end-to-end hand-back writes back a tree equal to the branch.
5. **Work the system can check** (owner, 2026-10-26: "reduce programming to its more mathematical origins"): a contract carries `:seon.cost/bound`; a solution passes only when every function it defines or modifies, and every project function its code references (`:form/refs`), has a contract and a bound, and the composed bound fits the task's budget. Declared design (functions declared with contract and bound before they exist, tests red) is the experiment; one whole task stays the default until it proves out. A score of the rules (claude_scratch/rules-score/) is revised before any agent sees it: it ranked the deleted parts layer first.
6. **Root is main, one system:** root's work as tasks, root's page through the same walk, the fork server's boot order and one process mode.
7. **Self-modifying environment:** agents change their own ctx (swap!), renders, layout and pull pattern, seen at once.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
