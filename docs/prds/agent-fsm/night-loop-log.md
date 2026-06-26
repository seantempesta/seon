---
type: prd
status: active
tags: [prd, agent, flow]
---

# Night Loop Log — validate-to-code (2026-06-25 → )

Append-only trail of the overnight autonomous build loop on `feature/agent-fsm`.
One entry per pass: what move (refine/repair/rebuild/optimize/prove/lock-in),
what changed, the LIVE proof observed, and the commit. The user reads this on
waking. Newest at the bottom.

Revert point if the experiment goes sideways: `c84e8fc`.

## Operating frame (the user's standing guidance this run)

- **Mold, don't duplicate.** A lot of effort is already in loop.cljs / turn.cljs /
  agent.cljs — reshape in place. `.disabled`-park anything retired so it can be
  re-examined; no `*-v2`.
- **No data to preserve.** `bin/seon nuke --yes` for a fresh world whenever right.
- **Both REPLs live** for debugging: `seon_cljs` MCP → pod; `seon` MCP → JVM
  (revived this run on 7888). Unified log view: `bin/seon logs` (merged).
- **Prove live, then commit.** Each pass ends in an observed proof + a clean commit;
  revert any pass that didn't actually improve things.

## Passes

### Pass 1 — harness + unified logging (tooling)

- **Move:** lock-in / tooling. Stand up the overnight harness.
- **Did:** revived the JVM server (`bin/seon start jvm`, nREPL 7888) so the `seon`
  MCP REPL reconnects → both REPLs confirmed live. Added a merged log view:
  `bin/seon logs` (no name / `all`) interleaves every process log, source-tagged
  (`[pod]`/`[jvm]`/`[wire-server]`/`[cljs-watch]`) and time-ordered. Created this
  log + the de-risk task list.
- **Live proof:** `bin/seon logs all 40` showed pod heartbeats interleaved with JVM
  post-start checks in correct timestamp order; `seon` + `seon_cljs` MCP evals both
  returned. Survey of current→target code landed (the Phase-1 map).
- **Commit:** `4cb7816`

### Pass 2 — run-model data layer (additive, dormant, live-proven)

- **Move:** build (the new engine on the bench — nothing wired into the live loop,
  so every piece is REPL-provable in isolation; the wake-token loop keeps running).
- **Did:** 3 new namespaces + additive attrs.
  - `seon.agent.fsm` (**`.cljc`**, dual-track shared): `transitions` table,
    `transition`, `derive-state` (pure projection of 3 primitives → state). No db/
    platform deps; compiles on JVM too.
  - `seon.agent.run` (`.cljs`): run entity + `open-run!`/`close-run!`/`renew!`/
    `beat!`/`current-run`/`owns-run?`/`snapshot`/`turn-limit-reached?`/
    `deadline-passed?`. `:seon.agent.run/id` is the fencing token. defaults:
    turn-limit 20, deadline 10 min.
  - `seon.agent.schedule` (`.cljs`): cron-as-data — hand-rolled `parse`
    (`*`,`*/n`,`n`,`a-b`,`a-b/n`,lists), `due?`, `next-fire-at` (OR-day + weekend skip).
  - `agent.cljs`: additive optional attrs `:seon.agent/run` (ref/fencing pointer),
    `terminated-at`, `default-turn-limit`, `default-deadline-ms`, `schedules`; new
    `state-snapshot` fingerprint. `turn.cljs`: `:seon.agent.turn/run` ref (additive
    alongside `wake`). NOTHING removed — cutover (pass 3) does the rename/removal.
- **Live proof (independently re-run by the orchestrator, not just the build agent):**
  - fsm: `derive-state` all combos + `transition` all cases correct.
  - run: create → `open-run!`→`:running` (turn-limit 20) → open 2nd run →
    `owns-run? run1`=**false** / `run2`=**true** (fencing) → `close-run!` →`:idle`.
  - schedule: `*/5` due at :05 not :07; `0 9 * * 1-5` due Fri 9:00 not Sat; next-fire
    after Fri noon → **Mon 09:00** (skips weekend); bad cron → error envelope.
  - Full CLJS suite: **553 tests / 2525 assertions / 0 fail / 0 err** (3 new test nses).
- **Spec divergences (recorded; converge at cutover):** FSM lives in `seon.agent.fsm`
  (`.cljc`), not `:seon.agent.loop/transitions`. Derived enum `:seon.agent.fsm/state`
  (`:idle/:running/:paused/:terminated`) is distinct from the still-stored
  `:seon.agent/state` (`:active/:idle/:terminated`) until cutover. `derive-state`
  takes a boolean `:seon.agent.run/open?` primitive (keeps fsm pure/`.cljc`).
  `state-snapshot` omits `unread-count`/`next-fire-at` (no source yet).
- **Carry-over for pass 3 (cutover):** throwaway agents left in the store (inert;
  `bin/seon nuke` before cutover, which needs a fresh world for the renames anyway);
  pre-existing `turn.cljs` public fns lacking `:malli/schema` (Gemini-flagged, not ours).
- **Commit:** (this pass)
