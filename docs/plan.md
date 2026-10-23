# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-23, afternoon)

Pins: see docs/forks.md · `src` 17,391 lines (19,304 this morning; aim ~10k) · paid runs ~$0.16 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data), loading main's source from `../next-merges` (root's write-back tree: live, never remove).

**Goal:** real Seon agents fixing problems in the system (THE PLAN's step E). **Reached once:** paid run #10, DeepSeek flash with thinking off, carried `seon.context/the-map-reads-the-program-not-the-process` through design (3 turns), implement (7) and merge, for about $0.004. The lever was act-first design instructions. Seon's own per-turn time is ~1.8 s; the model's ~16–18 s now dominates.
**How work moves now:** sessions self-review, then report ready to the merge session ("Merge: integrate ready branches into main"), which merges, proves and updates. Its log is `notes/merge-log.md`. A session that finishes claims its next task by creating its branch (AGENTS §3). Root keeps THE PLAN, rulings, briefs and docs (AGENTS §2, "Root's working rules").

## In flight

**Merge queue,** worked by the merge session in this order:
1. ``jolt/in-scalar``: the planner seeks a probed entity instead of scanning (3.24M → 55k ticks at 10k forms), Datahike `7de92bb`.
2. ``jolt/store-release``: a release drains pending writes (a data-loss fix), and LMDB envs are released (Datahike `9cd6e7b`, konserve `84d8bc0`). It combines its Datahike pin with in-scalar's first.
3. ``jolt/health`` (7 review fixes, review trailers from HEAD only, `bin/check health` as a prove step), then ``jolt/wiki-stale`` (76 of 81 stale sections fixed, Jolt-diagnostics triage).
4. ``jolt/calc-stop`` (e2e-calc: an ended agent answered at the producer) and ``jolt/dry-run`` (act-first instructions, code-depth citations).
5. ``jolt/proof-ends``, ``claude/eval-diagnostics-75a04b`` (eval errors as data, did-you-mean), ``jolt/agent-view`` (chat window, renders as `(value opts)` functions, messages as facts, per-tile updates; its full run first).
6. **Cutover to `~/src/seon-next`** (owner, 2026-10-23: a clean repo before the account changes): root holds main still, then ``jolt/rename-run`` (attempt → run) merges, then ``jolt/self-contained`` (step A, the results folder inside the root; step C, GitHub URLs). Then step R: a fresh clone into `~/src/seon-next`, build, prove, a grep for `~/src/seon-jolt` that finds nothing, and the full run. Unmerged branches are fetched into the new repo.

**Working, each on its own branch:**
- the write-back puts a new form before its first user (dry-run; it blocks every merge that adds a helper), then a second paid real task;
- the context render on Seon's own program, then Datahike's per-datom add path (~7k ticks a datom, so a 5.4 s import where ~0.35 s is due) (pure-tests);
- `src` audits for dead code and second mechanisms (shrink);
- every call kind recording ticks, bytes and calls, and each REPL eval saying what it reused (agent-view, after its merge);
- the clocks still deciding: render's 90 s retire timer and the fork server's 100 ms idle poll (check-events).

**Waiting on the owner:** task `seon.derive/one-content-keyed-derivation` (reuse of expensive pure results; cold paths are fixed first, and nothing is cached that is fast enough); the type-findings-as-warnings task (on ``jolt/wiki-stale``, not merged yet).

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
