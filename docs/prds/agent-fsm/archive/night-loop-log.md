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
- **Commit:** `2d25aad`

### Pass 5 — correctness cluster (crash-recovery, atomic-wake, async-listener)

- **Move:** repair (the remaining Gemini-validation correctness fixes; reconnect-since-t
  deferred to its own two-sided pass).
- **Did:**
  - **Crash-recovery (Gemini #4):** `run/recover-crashed-runs!` (closes ALL `:open` runs
    of non-terminated agents `:crashed`), wired in `client.cljs` boot AFTER `*conn*`,
    BEFORE the resume roster — **gated to genuine first-boot** so `/agents/new` in a live
    process can't clobber running agents.
  - **Atomic-wake (Gemini #3):** `open-run!` tx now ends with `[:db.fn/cas [:seon.agent/id id]
    :seon.agent/run nil [run-ref]]` — datahike CAS with nil old-value = "succeed only if
    the pointer is ABSENT" (= derived `:idle`). Single-writer serialization makes the 2nd
    concurrent opener's whole tx abort → never a second `:open` run. Loser renews the
    winner's run. (Agent read the datahike source + verified the 5-elt CAS op survives the
    wire passthrough.)
  - **Async-listener (Gemini #8):** `store/wire.cljs/fire-native-listeners!` schedules each
    callback on its own `setTimeout 0` (per-callback try/catch preserved) so one slow
    listener can't stall the tx-feed pump for all agents.
- **Live proof (orchestrator, fresh world):** opened a run on the agent (open-run! +CAS
  works live) → derived `:running` → **`kill -9` the pod** (wire-server, the separate
  store process, survives) → restart → boot log: *"crash recovery: closed 1 orphaned
  run(s) :crashed [zNQ-…]"*, agent **resumed** → derived `:idle`, run `:closed`/`:crashed`,
  no open run. Concurrent-opens + async-dispatch test-proven. `bin/test-cljs` **PASS (73s)**.
- **Deferred (own passes):** reconnect-since-t replay (Gemini #9, two-sided protocol);
  keystone worker-write buffering (Phase 2). Pre-existing smells flagged (this-process-run?/
  drive-run!/ping! missing `:malli/schema`; `!own-write-ids` `def` vs `defonce`).
- **Struck from architecture.md open-risks:** crash-recovery, atomic-wake, async-listener.
- **Commit:** `a83160e`

### Pass 6 — single render path (prompt == view, byte-identical by construction)

- **Move:** refine + prove (kill a divergence, lock the claim). The render machinery was
  ALREADY one fn (`render` over `ctx/context-root`) — but the prompt and inspector entry
  points passed DIVERGENT db values, so bytes matched only by coincidence.
- **Found + fixed (a real bug):** the prompt rendered over `@*conn*` (full); the inspector
  rendered over a `d/filter`ed `agent-view` that DROPS peer txs — so a message **from a
  peer** showed in the prompt but was FILTERED OUT of the inspector (it lied about what the
  agent saw). Unified: new single producer `ctx/render-context` (map-in, honors the per-agent
  `:seon.render/ai` override, db defaults to `@*conn*`); `turn/render-prompt` is now a thin
  delegate to it; `inspect/ctx-preview` renders via it over the SAME `@*conn*`. Bytes
  unchanged — unified WHO calls the renderer + over WHICH db, not the rendering.
- **Derived-never-stored confirmed:** the prompt is not a datom (only `prompt-chars` count +
  an optional off-by-default debug `prompt-file`); rendering writes ZERO datoms (tested).
- **Live proof (orchestrator, real pod):** `(turn/render-prompt id)` ≡ `(ctx/render-context
  {…id})` byte-identical (154 294 B / ~38k tok, `prompt==producer true`); the inspector full
  text (169 402 B = system block + context, ~42k tok) **ends-with the prompt byte-for-byte**
  (`inspector-ends-with-prompt? true`). Sample prompt is clean reader-valid `;`-comment prose
  (system head → cache boundary → self-demarcating section brackets → one live readline).
  `bin/test-cljs` **PASS (73s)** incl. the new `prompt-and-inspector-are-byte-identical` test.
- **Flagged follow-ups (minor/forward):** turn has no tx-basis `t` yet (so exact
  `db-as-of(t)` re-render of "what the agent saw at turn N" is future); `inspector/snapshot`
  still uses the filtered `agent-view` for the tile/state (equivalent today — own entity);
  `prompt-file` kept (the sanctioned audit-exception / gym evidence hook).
- **Commit:** `2b9a9c4`

### Pass 7 — interactivity: /call + namespace-as-route into the owning sandbox

- **Move:** build/port (the THIRD door of the one exec service — eval = render = interaction).
- **Did:** ported the JVM-track `web/reactive/transform.clj` + `ns/routes.clj` to the pod.
  - `web/reactive/transform.cljs` — render-time postwalk: a handler slot holding a fn-**call**
    `(cancel-order! "o-1")` (args bound at render) or a fn-**ref** `submit-order!` (args from
    click signals) → ONE standard datastar `@post('/call?fn=…&args=…')`. Browser sees only
    standard datastar. Wired into `render.cljs` for agent-authored tiles.
  - `web/reactive/call.cljs` + `POST /call` in `serve.cljs` — **namespace-as-route** (replaces
    the JVM `seon.*` prefix-whitelist): resolve the owning agent from the fn symbol's ns
    (`my.agent.<id>/foo`→`<id>` iff a live agent row exists), **capability-check** (the fn must
    be a granted `:seon.fn` in the agent's home ns — refuse otherwise, never invoke),
    Malli-validate, then **invoke via `seon.eval/eval`** in the agent's home ns (the SAME eval
    path — "an interaction is an eval authored as hiccup, routed by namespace") → transact →
    the existing `listen!`→render→SSE feed pushes.
  - Datastar v1 adaptation: the `{:fn,:args}` descriptor rides the URL query (v1 `@post`'s 2nd
    arg is fetch options; the body carries signals) — args transit-serialized, apostrophe-escaped.
- **Live proof (orchestrator, real `/call` route):** the **capability gate refuses** —
  `fs/readFileSync` (no owning agent) → **403**, `seon.client/start-agent!` (core) → **403**,
  `my.agent.<id>/nonexistent` (home ns, ungranted) → **403** with a precise message. Granted-fn
  invoke→transact is test-proven (`call-invokes-granted-fn-and-it-transacts`). `bin/test-cljs`
  **543/2487/0/0** incl. the new transform + capability-gate tests.
- **Flagged:** home-ns-only handlers (deliberate security narrowing — domain-ns handlers would
  need an agent→ns ownership map, deferred); minor `read-body`/`query-val` dup (a `seon.web.http`
  util would dedup — cycle blocks reuse today); `bin/test-cljs` footer grep over-matches
  expected-error log lines (verdict-by-exit-code is correct).
- **Commit:** `5b047b1`

### Pass 7.5 — adversarial review of /call → caught a SHIPPED RCE → fixed

- **Move:** test/prove (adversarial, security lens) then repair. A 3-lens ultracode review
  (capability-bypass / arg-injection / rewrite) of `5b047b1` raised 9, **7 real (3 blockers)**.
- **The hole (BLOCKER, RCE):** the capability gate validated only the fn SYMBOL, but `invoke!`
  `pr-str`-spliced the args into the eval'd form — and transit decodes `["~#list",…]` into a
  real list that `pr-str` renders as EXECUTABLE code. So `POST /call?fn=<any-granted-fn>&args=`
  with a transit list `[(js/require "child_process" …)]` ran arbitrary host code BEFORE the
  gated fn (granted fn-syms are public in the rendered UI). Same via the fn-REF signals path.
  Plus: no CSRF/Origin guard (cross-origin no-cors POST reachable), and a malformed-args hang.
- **Fix:** `invoke!` is now **resolve-and-apply** — resolve the capability-approved symbol to
  its value via `seon.eval/lookup-value` and `(apply f args)` with args as DATA (the
  eval-of-string sink is DELETED); `decode-args` is a recursive **data-only whitelist** (rejects
  symbol/list/tagged transit → `:user-input`); a **same-origin guard** wraps every state-changing
  POST (`/call`,`/chat`,`/agents/new`,`/clear`,`/complete`); malformed `?args=` → a written 422,
  no hang. Three independent defenses now (whitelist + apply-as-data + gate).
- **Live proof (orchestrator):** `decode-args` refuses the malicious transit list (`:user-input`);
  a `js/eval` marker payload **never executed** (global stayed nil) after BOTH the decode attempt
  and a real HTTP `POST /call` exploit; cross-origin `Origin` → **403**, same-origin passes the
  guard; no hang. Pure-data args still decode + the granted-fn happy path is intact (resolve-and-
  apply, agent's probe test).
- **CRITICAL infra bug uncovered:** `bin/test-cljs` silently **drops every test ns sorting after
  `seon.web.inspector-chips-test`** — so `seon.web.reactive.*` + `seon.web.serve-test` NEVER ran.
  That's why the RCE shipped under a "543/0/0 PASS" — a FALSE GREEN. (Run-model/ticker/correctness/
  render tests all sort BEFORE `seon.web.*`, so THOSE greens were real.) Captured as a task — the
  next pass; until fixed, late-sorting tests can't be trusted via `bin/test-cljs`.
- **Op note:** the shadow `.shadow-cljs/builds/client` output + nREPL port got wiped during the
  truncation investigation → the pod crash-looped + the cljs MCP dropped; recovered with
  `bin/seon restart cljs-watch` (full recompile, port restored) — the auto-reconnect path works.
- **Commit:** `70026ba`

### Pass 8 — INFRA: cure the bin/test-cljs false-green (the truncation that hid the RCE)

- **Move:** repair the TOOLING (the user: "your tools are as important as the system"). This
  false-green is what let the /call RCE ship under "543/0/0".
- **Root cause (observed, not inferred):** `bin/test-cljs` derived PASS from node's exit code
  alone. shadow `:node-test` calls `process.exit` ONLY from cljs.test's `:end-run-tests`, which
  fires only after the WHOLE suite completes — cljs.test chains every test as ONE async
  continuation (each `(async done)` must call `done`). `seon.web.inspector-chips-test` (4 tests
  each running a full `client/boot-seed!` inside an async block) intermittently fails to settle
  under cumulative in-process load (~ns 58); the chain breaks → `:end-run-tests` never fires →
  node drains + **exits 0** → every ns sorting after it silently skipped. Proof: direct
  `node out/test/test.js` ran all 61 ns / 550 tests; `bin/test-cljs` truncated 4/4 at exactly
  `inspector-chips-test`, dropping `call-test`/`transform-test`/`serve-test` (the RCE+CSRF regressions).
- **Fix (bin/test-cljs only — a test-level fix can't catch an under-load async stall):**
  (1) **Backstop** — verdict = exit-code + **ran-count vs discovered (`--list`=61)** + end-summary
  presence + cljs.test's own `FAIL/ERROR in (` reports (not a bare grep that over-matched log
  lines). A truncated/short run **FAILs loudly (exit 1) and NAMES the dropped nses** — false-green
  is now impossible. (2) **Tail-retry** — on a clean-exit truncation with zero real failures,
  re-run the un-completed nses in a FRESH process (no cumulative pressure → they pass) + merge;
  real failures are never retried away, a re-stall still FAILs. (+ a `grep -c || echo 0`
  double-print bash bug fixed.)
- **Live proof:** final `bin/test-cljs` → pass-1 truncated at ns 58 → retry re-ran the 4 cleanly →
  **"namespaces: all 61 ran (after tail-retry) … PASS (86s)"**; the security nses now execute —
  explicit run: **16 tests / 49 assertions / 0 fail / 0 err** (`call-refuses-injected-list-arg`,
  `decode-args-refuses-*`, `same-origin-*` — so the RCE fix is now test-proven too, not just live).
  (Orchestrator re-running it independently to confirm the intermittent recovery is reliable.)
- **Flagged (deeper, deferred):** the single-process `:node-test` runner's `^:async`-under-load
  fragility remains — a fuller fix = per-ns process isolation or lightening inspector-chips-test's
  4× `boot-seed!`. The backstop+retry make the tool trustworthy + green meanwhile.
- **Commit:** `eda4856`

### Pass 9 — render follow-up: delete the divergent filtered-db (`agent-view`)

- **Move:** refine (the "one mechanism" north star — kill the last divergent db source).
- **Did:** rewired `inspector/snapshot` from the `d/filter`ed `agent-view` to the one unfiltered
  `@db/*conn*` (the tile/state read the agent's OWN entity — behavior unchanged, but now ONE db
  source matching the loop + the context pane). **DELETED `src/seon/agent_view.cljs`** (grep
  confirmed zero remaining callers — it was the only divergent-db mechanism left). Retargeted 3
  stale comments (inspector `on-tx`, client, db/internal) that named the deleted ns.
- **Live proof:** `bin/test-cljs` **"all 61 ran PASS (89s)"** (the fixed runner's tail-retry
  recovered a stall again — meta-confirming the infra fix); pod still serves the inspector
  (`GET /agents` → 200) after the hot-reload. Codebase is smaller by one ns.
- **Flagged (pre-existing, separate cleanups):** `inspector.cljs` uses `datahike.api` directly
  (`d/q`/`d/datoms`/`d/entity`) in violation of the "only inside `src/seon/db/`" rule — clean
  swap to `db/query`/`db/entity` later; the origin-forge guard (`warn-on-seed-origin-forge!`) is
  now lower-value (agent-view's peer-injection vector is gone) — revisit.
- **Commit:** `6471402`

### Pass 10 — gym live-drive harness rewritten to the run model

- **Move:** repair (un-orphan the casualty the cutover created — the live-drive eval harness).
- **Did:** `test/seon/gym/driver.cljs` rewired wake/session → run: drive fns `run/open-run!` a
  `:message` run (cause = the waking msg) → drive `run-turn!`/`run-loop!` → `close-run! :completed`;
  marker queries walk `agent ← run ← turn` filtered on `:seon.agent.run/cause` (caused runs =
  message-driven, so the no-cause `bootstrap-turn!` run-0 is excluded — the discriminator that
  replaced the old `wake` clause). Re-enabled `driver_test.cljs` (off `.disabled`) + rewrote the
  `s01` scenario (it must now `(wait)` to close its run — see the zero-forms flag below).
  **`paid_test` kept `.disabled`** (paid-provider drives; its scenarios still carry old-model attrs;
  cautious default — one env-var from a money-burning run otherwise).
- **Live proof:** `bin/test-cljs` **576/2635/0/0, "ran 62 of 62 discovered" PASS (138s)**;
  `seon.gym.driver-test` RAN; the `:s01-stub-pipeline-smoke` scorecard **`pass? true`** (all 5
  predicates green) — the scripted-replay loop drove a real agent through the run model end to end.
- **Flagged (decisions for the owner):** (1) **`run-loop!` has NO zero-forms halt** — the old
  wake-loop stopped after a streak of no-actionable-forms turns; the new loop runs to
  turn-limit/deadline/verb, so an unresponsive LLM spins to the cap. `turn.cljs:256-258` still
  claims the halt exists (stale). Decide: re-add an empty-streak halt, or rely on verbs+bounds +
  delete the comment. (2) **8 paid/todo scenario EDNs carry old-model attrs** — need run-model
  conversion (+ a terminal verb per scenario, given #1) before the next paid sweep.
- **Commit:** (this pass)
