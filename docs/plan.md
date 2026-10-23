# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23)

Pins: Jolt `4342b73c`, Chez `80e4b9f` (docs/forks.md) · `src` 17,470 lines (aim ~10k) · paid runs $0.144 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data).

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). A small real request has gone design → implement on DeepSeek; no model-made change has merged yet.
**Owner's focus:** (1) the agent's context, tuned by evidence: it speaks Clojure, with thinking beside the forms; (2) the agent debug view; (3) shrink brief 1.
Honest state, what is solid and what is not: `notes/ASSESSMENT-2026-10-23.md`.

## In flight

Merged to main: no automatic retries (``jolt/hold-for-root``), rename pass 2 (stored names; the standing store is erased, not migrated), ``jolt/agent-ready``, ``jolt/printer-eval``, ``jolt/process-tests``, ``jolt/reach-index``.

**Merge queue** (each branch reviewed before merge and proven on a frozen tree; root bumps a fork's pin at merge, and each worktree session runs `bin/worktree --sync NAME` after merging main), next first: ``jolt/health`` (dead citations and other problems as classes of `seon.problems/report`), ``jolt/test-root``, then ``jolt/wiki-live`` (this branch: the wiki made current), then ``jolt/brief3`` (shrink brief 3; agents share greens), ``jolt/dry-run`` (step E: the gate's `:tests`, the three test tiers, thinking off, `reasoning_content` sent back), ``jolt/global-ids`` (content-derived ids, global addressing), ``jolt/pure-tests``, ``jolt/proof-ends``.

**Research and planning:**
- ``jolt/agent-view``: the agent debug view, with thinking beside the forms.
- ``jolt/self-contained``: `next` as the self-contained Seon root (fork homes are the GitHub forks; the project root is `~/src/seon-next`, worktrees at `.worktrees/NAME`); main-tree steps need root's go.

## Open owner questions

The ones that block THE PLAN; the full list (19) is at the end of [decisions](decisions.md).
- What a merge carries and who wins a conflict (main wins, `:seon.merge/kept`, or an explicit `:seon.task/carry`), with D6's names and D7's task config.
- Changed-since: a Datahike tree diff by node address, or a `:seon.tx/changed` stamp? Prototype the tree diff first (task `seon.merge/program-cost-follows-what-changed`).
- No progress: does a byte-identical repeated reply end the attempt (``jolt/dry-run`` builds `:no-progress`), or is it only recorded?
- Where each fork lives: the fork repos named in `.gitmodules` lack the newest pinned commits (they exist only in the main tree's submodule clones); self-contained plans the homes.

## THE PLAN

The destination is **E: a request worked design → implement → merge by Seon agents, end to end, measured as turns to green.** Every step serves E; done steps leave this list (git and the wiki keep them). What blocks E, in order:

1. **The context tells a model what to change, where, and how it will know it is done.** Real task contexts given to fast models with thinking off, scored on their first move; iterate until they act on turn 1; confirm with paid runs (``jolt/dry-run``, `notes/ASSESSMENT-2026-10-23.md` "Recommended next").
2. **The merge gate merges a model-made change** (``jolt/dry-run``: `:tests`, the three test tiers, `reasoning_content`; ``jolt/hold-for-root`` first).
3. **The owner can watch each turn:** the agent debug view (``jolt/agent-view``; `docs/parity.md` "Context, printing, the page").
4. **`src` shrinks:** shrink brief 1, drivers out of `src` (≈ −1,800; task `seon.main/drivers-leave-src`).

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog, each a task in `resources/seon/tasks/`; the task files hold the rest of the work.
