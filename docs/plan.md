# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`notes/merge-log.md` names every merge and how it was verified).

## Now (2026-10-27, morning)

Pins (docs/forks.md): Chez fa6a4c73; Jolt 07d9655d; Datahike cc614f4e; LMDB 15420ce; http-client e866277d. Paid model runs: $30 approved by the owner, spent only after a free audit of each request's context finds none of its known problems; two audits ran and neither was clean, so nothing is spent. The sessions that did the night's work have ended; their state is in the task files, notes/root-handoff-2026-10-27.md and notes/session-state-2026-10-27/.

**Where it stands.** Main is green under `bin/prove --merge` (every test and check step the change reaches, process tests included; nothing left out), with one known intermittent red, merge-cost's three-way proof (a 96-byte runtime variance; task held), which the flaky rule now marks :flaky. Main goes first through one lock. After a merge, restart root with `bin/update` (never `bin/root stop/start`) until jolt/the-source-tree-follows-main merges: the standing root imports main's program from .worktrees/merges, which only bin/update moves.

**The direction.** In this order:
1. **Use what's built, not only test it** (owner, 2026-10-27: "I want to actually use all the stuff we've been building so make sure it's all working and don't just report success because some tests passed"): main's program current on the standing root; root's and an agent's page and debug panel live in the browser (3b-root); an outside agent attached to a request and worked by hand, every wrong or confusing answer fixed.
2. **Context for about 20 turns:** the third free audit once tests-reaching and 3b-root are on main; then paid runs.
3. **One merge system, from what Seon already has** (owner: "don't throw out the baby with the bathwater"): Seon merges by definitions; root's repair playbook from its REPL; a work task merges itself through the one gate; Claude worktree branches come through the store only after the end-to-end experiment passes.

## In flight

Every Claude session that worked last night has ended; these are their branches, each with its task entry naming the next step.

| Branch | State | Next |
|---|---|---|
| jolt/the-source-tree-follows-main | approved, not built | e2e proof red first (task seon.fx/the-source-tree-follows-main) |
| jolt/every-page-is-its-parts | 3b-root in progress | finish per task seon.render/every-page-is-its-parts; browser-check every page |
| jolt/a-cited-vars-tests-are-its-own-query | tests-reaching, verifying when its session ended | bin/prove --merge, then merge; then swap the clause in :work/:whole |
| (task seon.root/root-repairs-a-failed-merge-from-its-repl) | merged as a task | stage the four failures on a worktree root and write the playbook into docs/merging.md |
| jolt/the-request-is-the-page-and-its-diffs | build 4, proofs red | after 3b-root |

## Waiting on the owner

The rulings root holds for the owner.

- Build 3's eval shape: root took "prose plus each eval's handle, the value drawn when opened" as the owner's meaning of "not show the same eval data"; confirm or correct.

## THE PLAN

The destination is **E: a request worked as one whole task by a Seon agent, end to end, measured as turns to green.** In order:

0. **All work inside Seon; worktrees retired** (owner, 2026-10-27: "The goal is to stop using the worktrees and to do all work within seon itself"). Claude sessions work as outside agents (seon_attach, seon_eval at their branch's REPL, seon_done), merged by Seon's one gate and written back by root. What stands between here and there, in order: the standing root's main follows the checkout (task seon.fx/the-source-tree-follows-main); a failed write-back is never lost and worktree roots don't share the write-back ref (root-repair gaps 1-2, task seon.root/root-repairs-a-failed-merge-from-its-repl); a merge never stays :behind forever (gaps 3-4); tests-reaching merged; then the first real piece of work done by a Claude session entirely through the server on the standing root, watched live. Changes the store doesn't hold yet (docs/, bin/, deps, pins, native code) stay root's git merges until the write-back carries them (task seon.merge/an-outside-branch-hands-back-through-the-store).

1. **No hang goes unseen** (mostly done): every wait ends on its peer's exit or answer; a stall is a fact with its evidence and root's task; the fiber dump names any wait; flaky results are kept and shown. Left: one process mode, the fiber dump in a private wait.
2. **One rendering system** (build 3 merged; 3b-root and build 4 left): one walk, one cache, the model reads the render of ctx; root's page through the same walk.
3. **Context for about 20 turns** (in progress): the page holds cited source, tests with their last results and section text (D1–D5), cut to budget; the one-go instructions (reproduce first; stop reading once the output is wrong; build inputs like the existing tests); requests checked not done on main. Measured by the next live run.
4. **Merge only when it can, by definitions:** a work task merges itself through the one gate (main's tests on the merged program, results reused by digest); conflicts are per var and root repairs them from its REPL (the playbook); Seon's merge path is kept whole and exercised before anything of it is cut; Claude worktree branches come through the store only once an end-to-end hand-back writes back a tree equal to the branch.
5. **Work the system can check** (owner, 2026-10-26: "reduce programming to its more mathematical origins"): a contract carries `:seon.cost/bound`; a solution passes only when every function it defines or modifies, and every project function its code references (`:form/refs`), has a contract and a bound, and the composed bound fits the task's budget. Declared design (functions declared with contract and bound before they exist, tests red) is the experiment; one whole task stays the default until it proves out. A score of the rules (notes/rules-score/) is revised before any agent sees it: it ranked the deleted parts layer first.
6. **Root is main, one system:** root's work as tasks, root's page through the same walk, the fork server's boot order and one process mode.
7. **Self-modifying environment:** agents change their own ctx (swap!), renders, layout and pull pattern, seen at once.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
