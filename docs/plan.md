# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23, evening)

Pins: Jolt `e0351af8`, Datahike `b4596cf`, persistent-sorted-set `1cae7f2`, Malli `0762fb6b`, Chez `80e4b9f` (docs/forks.md) · `src` ~18.5k lines (aim ~10k) · paid runs $0.144 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data, erased at each rename pass).

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). The loop turn is measured at 11.4 s (112 s this morning); the cold automatic test run is ~54–62 s (26 min this morning); a warm one is its plan only.
**Root's working schedule:** `notes/root-schedule-2026-10-23.md` in root's worktree (the queue, the rules, what was rescheduled).

## In flight

Merged today, in order: ``jolt/hold-for-root``, ``jolt/agent-ready``, ``jolt/printer-eval``, ``jolt/wiki-live``, ``jolt/reach-index``, ``jolt/process-tests``, rename pass 2, ``jolt/health`` part 1 (problem classes, the watcher, the done refusal), ``jolt/test-root``, ``jolt/starter-fifo`` (and the stop-hang fix), ``jolt/pure-tests``, ``jolt/brief3`` (shared greens), ``jolt/shard-time``, ``jolt/ticks-cost``, ``jolt/no-idle-build``, ``jolt/reach-scc2``, ``jolt/dry-run`` (the gate on demand, turn-cost fixes), ``jolt/global-ids`` (the store-wide counter, content ids, delta and verbatim merges, the all-branches union `seon.union`, the store-gc fix).

**Merge queue,** one heavy run at a time, root scheduling it; each branch merges main, does the revert check, runs one `bin/prove` plus the named process tests it reaches, and gets a §9 review:
1. ``jolt/health``: the test service (`seon_prove`, one runner for every agent), proving now; then `caused` at O(changed), the Flow loader and commit stream, every write checked by `mg/check`.
2. ``jolt/reach-update``: the incremental reach graph (a plan ~20–45 ms after one build per base).
3. ``jolt/refresh``: the incremental re-import (re-reads only changed files; the per-form diff is in progress).
4. ``jolt/divergence``: the turn's divergence from deltas.
5. dry-run's fork-server fix: the replica reads main's head instead of replaying (first spawn 2.4 s → ~0.4 s).
6. ``jolt/proof-ends``: proof forks end with their agent.
7. ``jolt/agent-view``: the chat window, renders as (value opts) functions with auto-render, and messages as facts through the union; then its full run.
8. ``jolt/rename-run``: rename pass 3, attempt → run (a script, refreshed over each main).
9. ``jolt/self-contained``: step A (test-results inside the root), the fork pushes, step C (GitHub URLs), step R (a fresh clone into `~/src/seon-next`, prove, grep clean), then move the sessions.
Then the first full run, and step E with paid DeepSeek runs.

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
