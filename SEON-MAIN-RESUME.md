# SEON-MAIN resume (for a new Claude session with no memory)

Updated 2026-10-20 by the "SEON - Main" session (root of `~/src/seon`). Read this first, then `AGENTS.md`.

## The situation in five lines

1. The owner chose the **Jolt architecture**: agents as forked native processes, Flow, no SCI. It is being built in
   `~/src/seon-jolt/next` by the **Jolt session**, whose breadcrumbs are `~/src/seon-jolt/RESUME.md`.
2. This repo (`~/src/seon`, the JVM and SCI Seon) is **paused**. Owner: "you aren't supposed to be working beyond helping
   jolt agent", and "follow instructions from the jolt agent going forward".
3. Owner's guidance for the move: carry over the best **ideas**, not the exact code, and keep things simple. Keep git
   history for IP reasons; drop the baggage.
4. This session's job is to help the Jolt session: census cuts, a read-only review of `next/`, and the move plan.
   Make no feature changes here. A fix is allowed only where it makes a census spec test meaningful.
5. **Never edit anything under `~/src/seon-jolt`.** Send findings and questions to the Jolt session; it routes them to
   its lanes.

## What this session owns

- `~/src/seon`: `main` is pushed to GitHub (`origin`, seantempesta/seon). The tree is clean, and old branches are archived as
  `archive/*` on GitHub.
- The reference-code forks: every pin is pushed. Each fork has a canonical branch, `seon` (or `seon/main` for datahike and
  konserve), recorded in `.gitmodules`. edamame and core.async now have forks under seantempesta.
- The default cluster JVM (`bin/seon status`): it runs `8066f1239`. A later restart would pick up the docs, the config
  dials and a `seon.context` fix. Only restart if asked.

## Deliverables and status

All live in `docs/research/agent-platform/2026-10-20/`.

| What | Path | Status |
|---|---|---|
| Census: keep/delete inventory, ideas first | `census/README.md`, plus `ideas.md`, `static.md`, `gen.md`, `tests-run.md` and the EDN data | **final** (`cb9568dea`) |
| The 12 double-red spec tests judged | `census/spec-reds.md` | **final** (`79b1a9272`): 7 green, 4 kept red as spec, 1 retired |
| Independent review of `~/src/seon-jolt/next` against AGENTS.md and the 20 ideas | `next-review.md` | **in progress**; may be partial, check its status line |
| The v2 move plan (nothing executed) | `move-plan.md` | **draft complete** (`8fbfbd51d`); owner decisions D1–D11 open |

## What is left

1. Finish `next-review.md`. Every finding needs a file, a symbol, the law or idea it breaks, and a reproducing form or
   failing test, all run in a scratchpad copy, never in `~/src/seon-jolt`. Then send the findings to the Jolt session.
2. `move-plan.md` is complete (draft): it waits on the owner's decisions D1–D11 and on `next/` being committed and paused.
   turned into a deletion list, the spec tests made runnable, and the owner's open decisions. The owner decides when to
   execute it.
3. Stay on call for census cuts the Jolt session asks for.
4. Waiting on the owner: whether `(help)` may teach the `my.attempt-<your attempt>` placeholder. Three `seon.help-test`
   tests are red on it.

## Rules a successor must keep

- **Commit only through `bin/lane`:**
  - `bin/lane claim LANE PATHS`, then `bin/lane commit LANE -m MSG PATHS`, then `bin/lane release LANE`.
  - Add `LANE_ADOPT=1` when root claims files that have unclaimed changes.
  - Use `--at-candidate --test NS/VAR` for code; a docs-only commit needs no test.
  - Never `git add -A`, `stash` or `reset --hard`, and never leave anything staged.
- **Pushing:** pushing `main` and the forks is authorized for keeping history safe. Don't open pull requests.
- **Default:** don't restart it without being asked; only root restarts default. No SCI requests as root on default, and
  no heavy JVM-mode work on it (they share its heap).
- **When a plan's premise is wrong:** stop and send it back to its planner; nobody redesigns on the fly (AGENTS.md).
- **Watch the zsh shell:** `"$var:x"` is a zsh modifier, so write `"${var}:x"`. Use `${=files}` for word splitting.

## Where the history is

- Memory: `~/.claude/projects/-Users-sean-src-seon/memory/` (`MEMORY.md` is the index) holds the owner's rulings.
- Earlier runtime work, now frozen and superseded by the Jolt architecture:
  - `docs/research/agent-platform/2026-10-19/` (`branch-runtime.md`, `closed-runtime-hybrid.md`);
  - `docs/research/agent-platform/2026-10-20/runtime-review*.md`.
