# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23, afternoon)

Pins: see docs/forks.md · `src` resists growth (AGENTS §1; no line target) · paid runs ~$0.40 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data), loading main's source from `.worktrees/merges` (root's write-back tree: live, never remove). The repo is `~/src/seon-next` since the cutover of 2026-10-23: next's history joined to Seon v1's (tag `v1-final`); the old tree `~/src/seon-jolt/next` is an archive.

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). **Reached once:** paid run #10, DeepSeek flash with thinking off, carried `seon.context/the-map-reads-the-program-not-the-process` through design (3 turns), implement (7) and merge, for about $0.004. The lever was act-first design instructions. Seon's own per-turn time is ~1.8 s; the model's ~16–18 s now dominates.
**How work moves now:** sessions self-review, then report ready to the merge session ("Merge: integrate ready branches into main"), which merges, proves and updates. Its log is `notes/merge-log.md`. A session that finishes claims its next task by creating its branch (AGENTS §3). Root keeps THE PLAN, rulings, briefs and docs (AGENTS §2, "Root's working rules").

## In flight

Updated 2026-10-24 morning by root. The repo is `~/src/seon-next`; every session below still runs in its old folder and works here by absolute path. Sessions are found with list_sessions in the sidebar group seon-jolt, and messaged by id. Each reports ready to the merge session; a new session needs root's go unless the owner starts it (AGENTS §2).

| Session | Last done | Now / next |
|---|---|---|
| **Merge** | 33 merges since the cutover, main b80738d74 | idle; merges what reports ready |
| **Agent loop** | the first-move judge, the standard chat format (decisions: the model's context is the standard format), the live state at the end of each turn | the standard builder into `seon.turn`; then examples, one per stage |
| **Agent view** (+ page, evaluations, embed, eval-cost) | chat window; links as refs; the walk (data, schema-declared keys); one page for root, agents and any entity (Tailwind); embeddings; per-eval cost; all merged | `/agent/{id}/debug`, the whole model conversation as a transcript (THE PLAN 3; `notes/agent-view-handoff-2026-10-24.md`). Page and embed are done (archive). Evaluations: the outside run's end (`jolt/an-outside-run-writes-its-end`) is with merge; its data pieces wait on agent-loop's standard turn. Eval-cost: `jolt/task-plans-cite` and `jolt/wiki-true-2` are with merge |
| **Walk** | watcher survives a vanished file; one-off folds | the rest of the one-offs survey (`notes/one-offs-2026-10-23.md`) |
| **Store** | Datahike add path, test-store races, tick quantum, predicate errors | ``jolt/ticks-are-exact-2`` (the parked Chez cause), its next task |
| **Reuse** | option B (program db per tree), emitter −49%, image registration opt-in | its next cost task; the memo waits on the data context |
| **Vocab** | the rename, the cutover, GC sweepers | the decider's open reds (prove-agent run key, mcp relay, durable-read) |

Not yet assigned (for the next root): the morning audit's five corrections (notes/audit-2026-10-24.md) and the perf wins (notes/perf-2026-10-24.md), first the context render's whole-index rebuild per turn (~10x).

Open on main: the decider's reds above; `seon.cost/ticks-are-exact` (a Chez timer quantum, allowed for).

**src size** (owner, 2026-10-23): not a line target; resistance to growth, so agents reuse and improve the core rather than add one-offs.

## Open owner questions

The ones that block THE PLAN; the full list (19) is at the end of [decisions](decisions.md).
- What a merge carries and who wins a conflict (main wins, `:seon.merge/kept`, or an explicit `:seon.task/carry`), with D6's names and D7's task config.
- Changed-since: a Datahike tree diff by node address, or a `:seon.tx/changed` stamp? Prototype the tree diff first (task `seon.merge/program-cost-follows-what-changed`).
- No progress: does a byte-identical repeated reply end the run (``jolt/dry-run`` builds `:no-progress`), or is it only recorded?
- Where each fork lives: the fork repos named in `.gitmodules` lack the newest pinned commits (they exist only in the main tree's submodule clones); self-contained plans the homes.

## THE PLAN

The destination is **E: a request worked design → implement → merge by Seon agents, end to end, measured as turns to green.** Every step serves E; done steps leave this list (git and the wiki keep them). What blocks E, in order:

1. **The context tells a model what to change, where, and how it will know it is done.** Measured by the first-move judge: real task contexts, fast models with thinking off, scored from compiler facts. The baseline is 0 of 10 acting on the first move, and wording, material and a stronger model all left it there (2026-10-23, $0.071), because one read is the models' normal first step. So the measure is "acts by its second move", with the read answered by the real REPL. Iterate the data context until it does, then confirm with paid runs.
2. **The merge gate merges a model-made change** (``jolt/dry-run``: `:tests`, the three test tiers, `reasoning_content`; ``jolt/hold-for-root`` first).
3. **The owner can watch each turn:** `/agent/{id}/debug` shows exactly what each model call was sent and what came back, as a chat transcript in order (system, user, the model's tool calls, the tool results), nothing cut, each message's tokens and the provider's cache hits, as v1's `/agent/{id}/debug` did (`~/src/seon/src/seon/render/web.clj` `debug-response`, `sent.clj`). Owner, 2026-10-24: "We made no improvements to the context and this is not a user friendly design and it's not a /debug url." What exists: the page (`/agent/{id}`, `/walk/{eid}`, seon.render/page) shows an agent's evaluations as data, a context read cut to its first lines; it is not the debug view. The debug view renders the recorded turns, so it shows agent-loop's standard format as soon as that is in `seon.turn` (`notes/agent-view-handoff-2026-10-24.md`).
4. **`src` resists growth:** duplication removed and core parts improved, so new work reuses them (drivers out of `src`, task `seon.main/drivers-leave-src`).

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog, each a task in `resources/seon/tasks/`; the task files hold the rest of the work.
