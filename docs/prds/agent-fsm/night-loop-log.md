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
- **Commit:** `f3fdd79`

### Pass 3 — KEYSTONE cutover: wake-token loop → run-model + FSM + derived state

- **Move:** rebuild (atomic in-place swap — the new engine replaces the old; nothing
  dual-pathed left behind). 17 src files + tests, ~849+/982− (heavy deletion).
- **Did:** `run-loop!` is now a fold of `fsm/transition` over a run-derived
  `next-event`; `wake-handler` `open-run!`s an `:idle` agent (or `renew!`s a
  `:running` one — the sliding lease). DELETED the stored `:seon.agent/state`, `wake`/
  `fresh-wake!`, `max-turns-per-loop`, `wait-note`, the whole **session** entity +
  `start-session!`/`ensure-session!` (turns are now standalone, run-stamped, and
  persist as history). RENAMED `ctx`→`sections`. Lifecycle verbs → run mutations;
  ADDED `pause`/`resume` with `remaining-ms` banking (**this is Gemini fix #1**,
  pause-vs-absolute-deadline — folded in). State is DERIVED everywhere via
  `fsm/derive-state` / `state-snapshot`. Context/render/inspector re-keyed
  session→run.
- **Live proof (orchestrator-run on a FRESH world — the agent couldn't, pod was down):**
  - Clean boot on the new schema: program-graph replay **9/9 ok**, instrumentation
    **224 fns / 0 bad-spec**, agent settles `:idle` (no dangling run).
  - **Full E2E loop:** `POST /chat` (real user message) → `wake-handler` →
    `open-run!` → derived **`:running`** (run `xig-…`, trigger `:message`, turn 1/20)
    → real DeepSeek turn → `wait` verb → `close-run! :waited` → derived **`:idle`**,
    pointer cleared. The FSM path `idle→trigger→running→wait→idle` ran live.
  - `bin/test-cljs` re-run independently: **PASS (69s)**. Build green, 0 warnings.
- **Deferred (correctly, per spec — own passes):** atomic-wake CAS (#3), the ticker
  (#4: `fire-due-schedules!`/`close-overdue-runs!`), crash-recovery on boot (#5).
  Consequence noted: two racing `:idle` wakes leave the loser's run orphaned-open
  until a ticker/crash pass closes it.
- **Casualty captured as a task:** `seon.gym.driver` (the live DeepSeek-drive harness)
  references deleted wake/session vars — orphaned from the suite, needs a run-model
  rewrite before it can drive agents again (`wake-active!`/`wake-idle!` →
  `open-run!`/`close-run!`). Two gym tests `.disabled`-parked with reasons.
- **Struck from architecture.md open-risks:** pause-vs-absolute-deadline (fixed here).
- **Commit:** `b15faef`

### Pass 3.5 — adversarial review of the keystone + repair

- **Move:** test/prove (adversarial) then repair. A 4-lens ultracode workflow reviewed
  the cutover diff (`b15faef`) — **10 raised, 7 real (4 major)**. The happy-path live
  proof (`message→wait`) structurally couldn't catch these; the review did.
- **Bugs found + fixed (one repair pass):**
  - **(MAJOR) resume didn't re-drive the loop** — `pause` exits `run-loop!`; `resume!`
    only cleared `paused-at`+re-extended the deadline, so a resumed agent was derived
    `:running` but UNDRIVEN + leaked an open run. Fix: a process-local `!loop-input`
    registry (stashed at trigger-arm time — same staleness as the wake closure, the
    llm-fn is a genuine runtime artifact) + `loop/drive-run!`; `lifecycle/resume`
    re-enters `run-loop!` on the still-open run via the same `setTimeout(0)+with-agent`.
  - **(MAJOR) failed turn-open masqueraded as a no-op success** → loop hot-spins to
    deadline under a wire write outage (turn-count never advances). Fix: `run-turn!`
    surfaces an open-tx failure as an `:error` turn; loop's `errored?` also treats a
    no-turn-created result (nil `:seon.agent.turn/id`) as error.
  - **(MAJOR) wake-handler lost all test coverage** — added 3 deftests (install the
    real trigger + transact inbound): `:idle` opens+drives w/ cause, `:running`
    renews (no 2nd run), hop-cap message refused / fresh chain wakes.
  - **(MINOR) resume! paused-guard; ms-remaining pause-freeze; remaining-ms test.**
- **Live proof (orchestrator, on the fixed build):** opened a run on the live agent →
  `pause` → derived `:paused`, budget **frozen** (`ms-remaining` = banked 599786, not
  decaying) → `resume` → `:running` + the loop **re-drove** (`total-turns` 2→10, run
  closed `:waited`) → agent settled `:idle`, bounded (no runaway). `bin/test-cljs`
  **PASS (67s)** (covers the turn-fail, wake, banking, paused-guard fixes).
- **Commit:** `9186577`

### Pass 4 — the one ticker (deadline watchdog + schedule firing) — Phase 1 COMPLETE

- **Move:** build (additive; completes Phase 1's one active piece — the DB is passive
  about wall-clock, so nothing enforces `deadline` or fires a cron until the ticker checks).
- **Did:** `run/close-overdue-runs!` (scans `:open` runs, closes `now>deadline` as
  `:deadline-exceeded`; SKIPS paused runs — their deadline is frozen). `schedule/
  fire-due-schedules!` (idle-gated, opens+drives a `:schedule` run for a due cron;
  per-agent same-minute double-fire guard). `loop/install-ticker!` — ONE idempotent
  `js/setInterval` (`SEON_TICK_MS`, default 30s), each tick runs watchdog then
  schedule-fire, error-wrapped so a throw isn't fatal. Boot-wired in client.cljs;
  added the missing `:seon.agent.schedule/*` bootstrap attrs. Loop↔schedule require
  cycle broken by **injecting** `drive-run!` (schedule needs run, not loop).
- **Live proof (orchestrator, fresh world, `SEON_TICK_MS=4000`):**
  - watchdog: backdated a run's deadline → the 4s ticker **autonomously** closed it
    `:deadline-exceeded` → agent `:idle` (confirmed I did NOT call the fn).
  - schedule: added a `* * * * *` cron to the idle agent → the ticker **autonomously**
    opened exactly ONE `:schedule`-triggered run (double-fire guard held across ~10 ticks).
  - `bin/test-cljs` **PASS (75s)**, 0 warnings.
- **Deferred (flagged, by design):** schedule `:fn` sandboxed execution (needs the
  one-exec-service pass — firing today = "wake on schedule"); `concurrency-policy :allow`
  (worker-isolation concern; all policies are idle-gated today).
- **Test-method note for future passes:** in the POD, `db/transact!` does NOT accept the
  `:seon` keyword conn (that's `query`-only) — use the map-in shape
  `(db/transact! {:seon.db/tx-data [...]})`. (Cost me two false-alarm "watchdog broken"
  reads before I spotted my own bad call.)
- **Commit:** (this pass)
