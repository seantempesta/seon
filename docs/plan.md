# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23, afternoon)

Pins: see docs/forks.md · `src` 17,391 lines (19,304 this morning; aim ~10k) · paid runs ~$0.16 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data), loading main's source from `../next-merges` (root's write-back tree: live, never remove).

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). **Reached once:** paid run #10, DeepSeek flash with thinking off, carried `seon.context/the-map-reads-the-program-not-the-process` through design (3 turns), implement (7) and merge, for about $0.004. The lever was act-first design instructions. Seon's own per-turn time is ~1.8 s; the model's ~16–18 s now dominates.
**How work moves now:** sessions self-review, then report ready to the merge session ("Merge: integrate ready branches into main"), which merges, proves and updates. Its log is `notes/merge-log.md`. A session that finishes claims its next task by creating its branch (AGENTS §3). Root keeps THE PLAN, rulings, briefs and docs (AGENTS §2, "Root's working rules").

## In flight

Five worktree sessions, grouped so that related tasks stay in one session's context (owner, 2026-10-23: "grouped tasks can stay in one agents context"); each reports ready to the merge session, and talks to the groups named beside it. Restarted on the new account 2026-10-23; `docs/restart.md` holds each branch's state.

| Group | Tasks, in order | Model | Talks to |
|---|---|---|---|
| **Vocabulary** | ``jolt/rename-run`` widened to every AGENTS §7a word: one scripted pass, a `bin/laws` check, `seon.fences.edn` dissolved into its owners' files | Sonnet | merge (the freeze) |
| **Merge and cutover** (main tree) | ``jolt/health`` again (merge main, revert the revert 1ef9f0e9, prove), ``jolt/wiki-stale``, ``jolt/reconcile-topo`` and ``jolt/live-forms``, then the freeze: ``jolt/rename-run`` (attempt → run, store erased), ``jolt/self-contained`` (steps A, D), step R (`~/src/seon-next`) | Sonnet | every group; root for the freeze |
| **Shrink** | the two `seon.agent-test` reds on main ("No such namespace: seon.e2e"), the remaining driver deletions from `src` (task `seon.main/drivers-leave-src`), the clocks still deciding (``jolt/memory-is-read-when-asked``, the fork server's 100 ms idle poll) | Sonnet | merge |
| **Agent loop** (THE PLAN 1, 2, E) | ``jolt/writeback-order`` (a new form before its first user; WIP committed a4d1ae96), ``jolt/ctx-loop`` (the context render on Seon's own program), then step E's second paid real task | Opus | agent view |
| **Agent view** (THE PLAN 3) | ``jolt/agent-view`` (self-review, its full run, merge); then, re-scoped by the owner, a page is an entity pulled with the neighbours its schema declares (v1's `:seon.render/units`) and the links between root, agent, run, turns and messages are refs (the tasks the agents links are refs and a page is an entity and its neighbors, on ``jolt/a-page-is-an-entity-and-its-neighbors``; it deletes zoom-value, the attempt route, agent-card and the grid, and needs the store erase, so it merges at the freeze); then every call kind recording ticks, bytes and calls | Opus | agent loop, vocab (the freeze) |
| **Store** | ``jolt/datom-cost``: Datahike's add path at ~7k ticks a datom (`notes/handoff-store.md`) | Opus | merge (pin bumps) |

During the freeze the other groups keep working; after it each merges main and re-runs the rename's scripts on its branch (`notes/rename-run/`).

**Waiting on the owner:** the task one-content-keyed-derivation (on ``jolt/one-content-keyed-derivation``, not merged yet) (reuse of expensive pure results; cold paths are fixed first, and nothing is cached that is fast enough); the type-findings-as-warnings task (on ``jolt/wiki-stale``, not merged yet).

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
