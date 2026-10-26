# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents doing real work, measured as turns to green); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history (`notes/merge-log.md` names every merge and how it was verified).

## Now (2026-10-26)

Pins (docs/forks.md): Chez fa6a4c73, Jolt 5cc7458e (main carrier, load's own `*ns*`, suggestions-for public, a timer's throw reaches its owner), Datahike e5a55664 (as-of reads keep index order; a report carries a card-one replacement's retraction; an opt-in newest-first pull option), http-client e866277d (a fiber-aware read). Paid runs: ~$1.2 of the owner's $30 for 2026-10-25/26. Standing root on 7888/7777 (`runs/store`, test data).

**Where it stands.** Agents work each request as one whole task (design, implement and merge in one run) on main. Measured on 2026-10-26 (notes/live-baselines-2026-10-26.md): implement turns 84-92% cached (was 24-31%); designs 7/10 green with thinking off, 2/3 on the long design after "plan after the red proof"; repeats near zero with the repeat rule deleted for its causes; an agent spawns in ~37 ms; the full suite runs in ~8 min with no hang.
**The direction.** One derivation: the agent's record is pulled once as `@ctx`, and the page and the agent's context are both renders of it, re-derivable byte for byte from the db at given t's (notes/one-derivation-2026-10-26.md; the approved design notes/one-derivation-joint-design.md, built in slices).
**How work moves.** Sessions build on worktrees, self-review, and report to root; root merges one branch (or a small batch) at a time after verifying it on main: `bin/prove` (its plan line compared), the process tests the change reaches **by name, including `seon.main-test` (the laws, which bin/prove leaves out)**, and smoke. A red merge is reverted or fixed within the hour. Never edit or commit tracked files while a verification runs (the binary goes stale).

## In flight

What each working session is doing now and takes next; every session is linked to root's (list_sessions linked).

| Session | Now | Next |
|---|---|---|
| **full-run-reds** | build 0: the three Jolt gaps the ratom port found (sorted?/subseq on a deftype, reset!/swap!/add-watch through IAtom/IRef, a volatile-mutable field in a map literal), on a jolt/ branch | the next full run on clean main |
| **Agent view** | build 1: every render as [f opts & children], lists keyed by eid | build 3: the ratom (Reagent's, cursors inferred), the walk, one cache, the per-render ledger, the print boundary, keyed appends |
| **ctx-atom** | Datahike's as-of order (jolt/time-pred-order) | build 2: the pattern in ctx, the pattern-driven patch, symbols resolved by lookup ref, newest-first windows; the task held once; bookkeeping off the agent's data |
| **Related sections** | idle | build 4: the model's request from the page and the two diffs; today's per-turn rebuild deleted |
| **Agent loop** | 6′ (messages and stages from main, a red verdict wakes on new reasons, opened evals and run status by take!) | :cap recorded as :cap; the busy-with-no-process defect |
| **Root as one system** (stability lead) | the fork server's boot order; a dead fork server answers its waiters; Jolt's zip errors reach the caller | one process mode |
| **Live agents** | the default request as design-build plus merge (staged kept for experiments) | the next live run, after agent loop's :cap fix |

The render build follows notes/render-design-2026-10-26.md (owner's go, 2026-10-26: "build it however you think is best"), in small branches measured against the lab's numbers (notes: render-investigation-2026-10-26.md).

## Waiting on the owner

The rulings root holds for the owner.

- Delete jolt/evaluations, jolt/acts-by-second-move and jolt/flow-lab (carried forward or superseded; notes/branch-merit-2026-10-26.md); carry or delete examples, wiki, unitflake, one-content-keyed-derivation.
- The repeat rule was deleted for its causes; confirm, or bring back a bound beyond the turn cap.

## THE PLAN

The destination is **E: a request worked as one whole task by a Seon agent, end to end, measured as turns to green.** In order:

1. **The agent loop** (done but resume): sessions continue across runs, every agent parks and wakes on a message, one delivery (main-first), the stage boundary as a message. Left: resume from any commit (rebuild ctx at that t).
2. **Requests through the pipeline, measured** (in progress; one-go whole tasks for every run, owner 2026-10-26; the target is a request solved in one task in about 20 turns or fewer, and a run that caps is a context failure to fix: "If we can't give clear instructions and have an agent in one task solve the problem then we need to improve the context"): 2 of 5 requests merged end to end on 2026-10-26; the ten-request rerun measures each change.
3. **One rendering system** (owner, 2026-10-26: "the system itself just builds what the data describes"): ctx; one layout render declared on ctx's schema; the tree walk drawing every value by its render, else its schema's, else the printer; one cache by render fn and args; the model reads the same hiccup and each turn's diff. The parts layer built beside it is being deleted. Earlier shape, for history: ctx is the agent's data pulled from the db, nothing derived; renders (memoized by value) draw it as one page; the agent gets two outputs: each eval's data, and the page as hiccup exactly as rendered, whole on the first turn, then ctx's diff and the page's diff each turn; no ai face; the request is a pure function of the db at the session's t's. Today four paths remain side by side (seon.context's request assembly, seon.render.ai's faces, seon.screen, the page); this step collapses them into one, and nothing new gets its own path. Designs come from REPL experiments on live agents first. The page is `render-ctx`, the render of ctx (task column, prose chat with evals drawn when opened, tiles for values with a render); everything shown comes through ctx, outside facts by `take!`. Slice order: 1 ctx facts (merged); 2 parts and the page; 3 one diff by part key; 6′ every outside change an inbox entry carrying its ctx diff (folds take! and "since your last turn" into one mechanism); 4b the request (starting page, tool answers, inbox entries, byte stable from db t); 4c debug as the re-derived request; 5 every page off the walk; then enrichments out of seon.repl into renders.
4. **Root is main, one system:** root's work as tasks, root as an agent with its ctx and page; the fork server's boot order and one process mode for every forking process.
5. **Self-modifying environment:** agents change their own ctx (swap!), renders and layout, seen at once; docs in the db at levels.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
