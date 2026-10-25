# The plan

What is in flight, what the owner has not yet ruled, and the steps from here to the goal (Seon agents fixing problems in the system); [AGENTS.md](../AGENTS.md) says who does what and the method, the [map](README.md) how the system works, [decisions](decisions.md) the owner's standing rulings, and git the history.

## Now (2026-10-25, morning)

Pins: see docs/forks.md (chez fa6a4c73, datahike 6dc9ce64 since 2026-10-25) · paid runs ~$1 of the $50 budget · standing root on 7888/7777 (`runs/store`, test data). Root's working notes for this phase: `notes/root-handoff-2026-10-25.md`; the research behind every decision: `notes/research-2026-10-25/`.

**Goal:** real Seon agents doing real work (THE PLAN's step E), measured as turns to green.
**Where it stands:** the agent's view and context are on main as renders of its record (design `notes/design-agent-view-2026-10-24.md` r3): a function-calling conversation with prose-first evals, screen evals, the short system prompt (405 tokens), render once per set of reads, `/agent/{id}` and `/agent/{id}/debug`. Measured (step 6, thinking off, hand-prepared tasks): turns to green at parity with the old context (8/15, 7/15 vs 7/15); prompt tokens -45% since.
**How work moves now:** sessions self-review and report to root; root merges verified fixes (owner, 2026-10-24), comparing `bin/prove`'s plan line with main's (AGENTS §3). Rulings: docs/decisions.md, "2026-10-24 rulings" and "2026-10-25 rulings".

## In flight

Updated 2026-10-25 by root. Sessions are linked to root's session (list_sessions linked).

| Session | Last done | Now / next |
|---|---|---|
| **Agent loop** (Opus) | design r2; delivery measured: main-first (§3a); main carrier (jolt 0cf54944); part 1 static fork server (734da4c1c) | self-review, pin bump, then parts 2-4; owns the fork hang (seon.lib/a-fork-from-a-threaded-process-never-hangs) |
| **Agent view r3** (Opus) | docs at levels, dogfood merged | jolt/dogfood-rulings reverted on main (10 reconciler tests changed class): finding the edge; story pulls kept on jolt/story-pulls |
| **Printer** (Opus) | audit, 144-line elision fixed, flat cost (jolt/printer-everywhere) | the reach edge (680 of 706 tests take the tree; 2 program-test reds), reply budget 16,000 |
| **Root as one system** | judge through the test service merged | three main-test/check-test reds from the full run; cases 3-4 after the loop's parts |
| **Full-run reds** (Opus) | started 2026-10-25 | bin/check 100: idle, record, durable-read, 6 e2e, swarm red on main |
| ctx-atom (idle) | design r3; §11 superseded | builds after the loop's part 1 merges |

Baseline: `notes/baseline-2026-10-25.md`.

## Waiting on the owner

What waits on the owner's ruling or go before the work can move.

- The agent loop's rulings: eviction (owner chose always park), new attributes (agent key, `:continues`, `:ns`, message refs, `:parked`, `:seon.run/taken`, `:seon.eval/calls`, `:seon.resume/*`), the name `my.agent/message!`, the design's open questions.
- Go for the request pilot and measurement (~$0.50): the five fit tasks as user requests through design, implement and merge (`seon.fixtures/fit-requests`).
- The ideas of 2026-10-25 (handoff "New ideas"): a `ctx` atom mirroring the agent's data, self-modifying views, simple tasks from a fork, docs in the db at levels of detail.

## Open owner questions

The ones that block THE PLAN; the full list (19) is at the end of [decisions](decisions.md).
- What a merge carries and who wins a conflict (main wins, `:seon.merge/kept`, or an explicit `:seon.task/carry`), with D6's names and D7's task config.
- Changed-since: a Datahike tree diff by node address, or a `:seon.tx/changed` stamp? Prototype the tree diff first (task `seon.merge/program-cost-follows-what-changed`).
- No progress: does a byte-identical repeated reply end the run (``jolt/dry-run`` builds `:no-progress`), or is it only recorded?
- Where each fork lives: the fork repos named in `.gitmodules` lack the newest pinned commits (they exist only in the main tree's submodule clones); self-contained plans the homes.

## THE PLAN

The destination is **E: a request worked design → implement → merge by Seon agents, end to end, measured as turns to green.** In order:

1. **The agent loop** (design on ``jolt/agent-loop``): sessions that continue across messages, every agent parked and woken by a message, one send for everyone (the user is an agent), resume from any commit, the screen as diffs.
2. **Requests through the pipeline, measured:** the five fit requests through design, implement and merge, thinking off first; the debug page shows each turn.
3. **Root is main, one system:** root's own work as tasks run on main's current commit; root as an agent on main.
4. **Self-modifying environment:** agents and the user improve their own views and context (the `ctx` idea), docs pulled from the db at levels of detail, dogfooded through MCP.

Beside E, the missing and partial rows of `docs/parity.md` are the parity backlog; the task files hold the rest of the work.
