# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`notes/merge-log.md` names every merge and how it was verified).

## Now (2026-10-26)

Pins (docs/forks.md): Chez fa6a4c73, Jolt 5cc7458e (main carrier, load's own `*ns*`, suggestions-for public, a timer's throw reaches its owner), Datahike 336de2ab (a report carries a card-one replacement's retraction), http-client e866277d (a fiber-aware read). Paid runs: ~$1.2 of the owner's $30 for 2026-10-25/26. Standing root on 7888/7777 (`runs/store`, test data).

**Where it stands.** Agents work requests design → implement → merge on main. Measured on 2026-10-26 (notes/live-baselines-2026-10-26.md): implement turns 84-92% cached (was 24-31%); designs 7/10 green with thinking off, 2/3 on the long design after "plan after the red proof"; repeats near zero with the repeat rule deleted for its causes; an agent spawns in ~37 ms; the full suite runs in ~8 min with no hang.
**The direction.** One derivation: the agent's record is pulled once as `@ctx`, and the page and the agent's context are both renders of it, re-derivable byte for byte from the db at given t's (notes/one-derivation-2026-10-26.md; the approved design notes/one-derivation-joint-design.md, built in slices).
**How work moves.** Sessions build on worktrees, self-review, and report to root; root merges one branch (or a small batch) at a time after verifying it on main: `bin/prove` (its plan line compared), the process tests the change reaches **by name, including `seon.main-test` (the laws, which bin/prove leaves out)**, and smoke. A red merge is reverted or fixed within the hour. Never edit or commit tracked files while a verification runs (the binary goes stale).

## In flight

What each working session is doing now and takes next; every session is linked to root's (list_sessions linked).

| Session | Now | Next |
|---|---|---|
| **Agent view r3** | slice 2b on jolt/view-parts: `:render` answers the parts; a page never waits (root draws it from the branch, the agent's parts patch it in); the memo keys on each part's ctx members; process tests and prove running | design revision 4 (the wire, the byte-stable spine, enrichments as renders); slice 4c; slice 5 (task seon.render/every-page-is-its-parts: every page off the walk) |
| **ctx-atom** | slice 1 merged (620fb31dd) | slices with agent view |
| **Related sections** | waits for slice 1 | slice 3 (one diff by part key) and 4b's request side (history cut, folds, as-sent into the request's assembly) |
| **Agent loop** | Jolt: timer errors no longer swallowed (approved), the double-fork failure | messages and outside changes as inbox entries carrying ctx-shaped diffs (slice 6); resume rebuilds ctx at a commit |
| **Root as one system** (stability lead), top priority | the zygote: load, then fork, then threads, in shippable pieces | one mode for forking processes (every thread a fiber), the fork-alone codes deleted |
| **Live agents** | design-plan-after-proof ready | the ten-request rerun on main; then measures each one-derivation slice against the 2026-10-26 baselines |
| **full-run-reds**, stability | LMDB EINVAL: macOS ftok collisions share SysV semaphore sets between stores; fix in a seantempesta/lmdb fork (PostgreSQL's way) | the leaked sets, after root sees the list |

## Waiting on the owner

The rulings root holds for the owner.

- Delete jolt/evaluations, jolt/acts-by-second-move and jolt/flow-lab (carried forward or superseded; notes/branch-merit-2026-10-26.md); carry or delete examples, wiki, unitflake, one-content-keyed-derivation.
- The repeat rule was deleted for its causes; confirm, or bring back a bound beyond the turn cap.

## THE PLAN

The destination is **E: a request worked design → implement → merge by Seon agents, end to end, measured as turns to green.** In order:

1. **The agent loop** (done but resume): sessions continue across runs, every agent parks and wakes on a message, one delivery (main-first), the stage boundary as a message. Left: resume from any commit (rebuild ctx at that t).
2. **Requests through the pipeline, measured** (in progress): 2 of 5 requests merged end to end on 2026-10-26; the ten-request rerun measures each change.
3. **One derivation** (approved, building in slices): ctx → view → parts → html / ai → diff; changes arrive as inbox messages carrying ctx-shaped diffs; an eval's answer carries its ripple effects; the request is a pure function of the db at the session's t's. Slice order: 1 ctx facts (merged); 2 parts and the page; 3 one diff by part key; 6′ every outside change an inbox entry carrying its ctx diff (folds take! and "since your last turn" into one mechanism); 4b the request (starting page, tool answers, inbox entries, byte stable from db t); 4c debug as the re-derived request; 5 every page off the walk; then enrichments out of seon.repl into renders.
4. **Root is main, one system:** root's work as tasks, root as an agent with its ctx and page; the zygote and one process mode for every forking process.
5. **Self-modifying environment:** agents change their own ctx (swap!), renders and layout, seen at once; docs in the db at levels.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
