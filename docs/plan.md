# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23, afternoon)

Pins: see docs/forks.md · `src` 17,391 lines (19,304 this morning; aim ~10k) · paid runs ~$0.16 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data), loading main's source from `../next-merges` (root's write-back tree: live, never remove).

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). **Reached once:** paid run #10, DeepSeek flash with thinking off, carried `seon.context/the-map-reads-the-program-not-the-process` through design (3 turns), implement (7) and merge, for about $0.004. The lever was act-first design instructions. Seon's own per-turn time is ~1.8 s; the model's ~16–18 s now dominates.
**How work moves now:** sessions self-review, then report ready to the merge session ("Merge: integrate ready branches into main"), which merges, proves and updates. Its log is `notes/merge-log.md`. A session that finishes claims its next task by creating its branch (AGENTS §3). Root keeps THE PLAN, rulings, briefs and docs (AGENTS §2, "Root's working rules").

## In flight

Updated 2026-10-23 evening by root. Sessions are grouped so related tasks stay in one context (owner: "grouped tasks can stay in one agents context"); each reports ready to the merge session. A new session needs root's go unless the owner starts it (AGENTS §2).

| Session | Working on | Next |
|---|---|---|
| **Merge** (main tree) | ``jolt/analysis-lookups`` (Jolt 86f49de8), with the cost proofs a pin bump runs | ``jolt/agent-view`` (chat window), then the freeze |
| **Vocabulary** | ``jolt/rename-run``: run → run and every AGENTS §7a word, one scripted pass and a `bin/laws` check; ready | merges first in the freeze; the standing store is erased once |
| **Agent view** | the context frame and the renders (data, not prose; the printer stays general), on ``jolt/a-page-is-an-entity-and-its-neighbors``, which merges in the freeze | the default HTML value render |
| ↳ evaluations, page | the context as data (the frame, then the walk), the page entity | through agent view |
| ↳ embed | similar search by embedding (approved, model download included) | reports to root and merge |
| **Walk** | shrink `src` (THE PLAN step 4), one small branch per deletion; its health branch is with merge | reconcile's duplication |
| **Wiki** (was eval-cost) | main's 35 dead citations and 167 stale sections | |
| **Agent loop** | ``jolt/resumed-branch`` (a resumed branch keeps what main gained; `test/` in every root's dirs) | the first-move judge: ten real tasks, one call each, scored from compiler facts |
| **Store** (covering shrink) | the reach-update regression from Jolt 0d39edc3 (n^1.96) | a check's roots take explicit config, not the checkout's `config/local.edn` |
| **Reuse** | option B: `bin/test` branches a kept program db per git tree | the memo is paused until the data context is measured (two attacks: `notes/reuse/`) |

Ended: shrink (its folder was removed at merge), eval-cost (merged).

**src size** (owner, 2026-10-23): not a line target; resistance to growth, so agents reuse and improve the core rather than add one-offs. Shrink work goes on only where it removes duplication or improves a core part.

**The freeze**, on root's word: ``jolt/rename-run``, then ``jolt/a-page-is-an-entity-and-its-neighbors``, then the store is erased; then ``jolt/self-contained`` and step R (`~/src/seon-next`). Other sessions keep working, and afterwards re-run the rename's scripts on their branches (`notes/rename-run/` on ``jolt/rename-run``).

## Open owner questions

The ones that block THE PLAN; the full list (19) is at the end of [decisions](decisions.md).
- What a merge carries and who wins a conflict (main wins, `:seon.merge/kept`, or an explicit `:seon.task/carry`), with D6's names and D7's task config.
- Changed-since: a Datahike tree diff by node address, or a `:seon.tx/changed` stamp? Prototype the tree diff first (task `seon.merge/program-cost-follows-what-changed`).
- No progress: does a byte-identical repeated reply end the run (``jolt/dry-run`` builds `:no-progress`), or is it only recorded?
- Where each fork lives: the fork repos named in `.gitmodules` lack the newest pinned commits (they exist only in the main tree's submodule clones); self-contained plans the homes.

## THE PLAN

The destination is **E: a request worked design → implement → merge by Seon agents, end to end, measured as turns to green.** Every step serves E; done steps leave this list (git and the wiki keep them). What blocks E, in order:

1. **The context tells a model what to change, where, and how it will know it is done.** Real task contexts given to fast models with thinking off, scored on their first move; iterate until they act on turn 1; confirm with paid runs (``jolt/dry-run``, `notes/ASSESSMENT-2026-10-23.md` "Recommended next").
2. **The merge gate merges a model-made change** (``jolt/dry-run``: `:tests`, the three test tiers, `reasoning_content`; ``jolt/hold-for-root`` first).
3. **The owner can watch each turn:** the agent debug view (``jolt/agent-view``; `docs/parity.md` "Context, printing, the page").
4. **`src` resists growth:** duplication removed and core parts improved, so new work reuses them (drivers out of `src`, task `seon.main/drivers-leave-src`).

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog, each a task in `resources/seon/tasks/`; the task files hold the rest of the work.
