# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`notes/merge-log.md` names every merge and how it was verified).

## Now (2026-10-27, night)

Pins (docs/forks.md): Chez fa6a4c73; Jolt 07d9655d (System/exit runs no finally; the host-winder fix; the fiber dump); Datahike cc614f4e (attribute comparison without a try); LMDB 15420ce; http-client e866277d. Paid model runs: $30 overnight, only after a free audit of each request's context finds none of its known problems; the first audit was not clean (notes/known-context-problems-2026-10-26.md), so none has run.

**Where it stands.** Main is green under `bin/prove --merge` (every process key the change reaches, contracts, smoke) except one owned intermittent red, seon.source-test/a-restart-catches-up-and-a-new-generation-resumes (the narrowed watch triggers it). Main's merge verification goes first through one flock; plain prove now leaves out 127 keys (was 203). A request is one whole task by default; the task's render carries its instructions, the cited functions' source and tests, and section text.

**The direction.** Three things, in this order of urgency:
1. **Stability: no process waits forever unseen.** Stalls are facts, root's tasks, with their evidence captured; reds hidden in left-out steps are run by `--merge`; hangs found tonight were each fixed at their cause (Jolt's zip read across a park, System/exit's finally, a forked child's status).
2. **One rendering system** (notes/render-design-2026-10-26.md): build 3 (the ratom walk) next, then 3b-root and build 4 (the request from the render's diffs).
3. **Context good enough for about 20 turns:** the second free audit once build 3 is on main; then paid runs.

**How work moves.** Sessions build on worktrees, self-review, and report ready to root; root merges and verifies with `bin/prove --merge` in the main tree, reads every red it names, and restarts main's root on the result. Nothing tracked is edited while a verification runs. A worktree is removed when its branch merges.

## In flight

What each working session is doing now and takes next; every session is linked to root's (list_sessions linked).

| Session | Now | Next |
|---|---|---|
| **Render build** (agent view) | build 3 on jolt/render-walk, held on its own red (seon.source-test/importing-src: seon.ratom's deftypes reloaded at boot) until the restart test's cause is known | the cited tests by the render's own query (ctx-atom's measured option C); compact styling; 3b-root |
| **ctx** (ctx-atom) | the cited tests query handed to build 3 | the index observe's remaining fixed cost (task seon.reconcile/an-index-is-observed-at-the-cost-of-what-changed) |
| **Cost bounds** (related sections) | build 4's proofs written red (jolt/the-request-is-the-page-and-its-diffs) | build 4 after build 3 |
| **Agent loop** | a cost proof with fewer than 3 points under the cap is refused, and its left-out sizes named; ~30 proofs given a third size | busy-with-no-process follow-ups |
| **Stability** | the :died/:failed ordering at the producer | one process mode for every forking process |
| **Hangs** (full-run reds) | the restart test's red (jolt/two-merges-two-commits): a merge's write-back outcome always recorded; why the member names no file | Jolt tasks filed: spit through a FIFO, the fiber dump in a private wait |
| **Live agents** | holding until build 3 merges | the second free context audit, then the run list for paid runs |

## Waiting on the owner

The rulings and calls root holds for the owner.

- Build 3's eval shape: root took "prose plus each eval's handle, the value drawn when opened" as the owner's meaning of "not show the same eval data"; confirm or correct.
- The old live-agents worktree's runs/store-r0..r8 (2.3 GB, the paid rounds' raw stores): keep or delete.

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
