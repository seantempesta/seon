---
type: research
status: active
tags: [research, agent]
---

# Multi-agent unit — test plan (review checklist + gap-filler)

**TL;DR:** every hard testing problem in the multi-agent spec
(`docs/prds/agent-ctx/multiagent-context-spec.md`) dissolves into two moves the
codebase already uses everywhere: (1) **inject time** — the scan/gate/count fns
take an explicit `now` (the `close-overdue-runs!` precedent), and tests
transact **backdated `:inst` datoms** on a hermetic `:memory` conn, so there
are ZERO timers and ZERO sleeps in unit tests; (2) **test the scan fn, not the
interval** — the periodic timer is the existing one ticker, already a thin
shell over directly-callable actions (the `ticker_test` precedent). This doc is
the REVIEW checklist against the implementation's tests, plus three
load-bearing gaps the implementer must resolve (a missing `closed-at` instant,
a spec-internal wake conflict on `:core`-origin outcome notices, and async
fault-bracket hygiene). A walkable checklist closes the doc.

An implementation agent is building this unit concurrently and writing its own
tests — this file is what the reviewer walks; it does not replace those tests.

## Pass 1 — the house conventions the tests MUST use (binding)

All verified against current source/tests on `feature/agent-ctx`, 2026-07-06.

### Hermetic conn + root `set!` (the one fixture pattern)

- Fresh `:memory` datahike conn per test, random-uuid store id,
  `:keep-history? true`, seeded with `client/agent-bootstrap-attrs` + an
  explicit `extra-attrs` list of the run/schedule attrs —
  `test/seon/agent/run_test.cljs:20-50` and
  `test/seon/agent/ticker_test.cljs:23-56` are the exact templates to copy
  (they already include `:seon.agent.run/last-beat-at`,
  `:seon.agent/schedules`, etc.). New attrs this unit registers
  (`:seon.agent.run/result`, `result-ref`, any `closed-at`) must be added to
  that list — or rely on `db/transact!` lazy-install, which the loop-test path
  (`client/open-agent-conn!`, `test/seon/agent_loop_test.cljs:52-63`) uses.
- `with-conn` = root **`set!` of `db/*conn*`**, never `binding` (pops at the
  first await), restore in `.finally`; re-pin (`pinned`) before ambient reads
  after async hops. Canonical: `run_test.cljs:52-62`, `/clojure-testing` skill.
- Agent ids in fixtures are exactly 14 chars (`:seon.db/id`), e.g.
  `"runtest-260625"`.

### Async tests

- `cljs.test/async` + `done` on **both** rails (`.then` AND `.catch`, the
  `.catch` rail asserting `(is false …)`); `^:async` named inner fns with
  `await` are fine (`run_test.cljs:96-124` `work-fence` test). Same pattern in
  `reference-code/sci/test/sci/async_test.cljs:9-26`.
- Fire-and-forget writes (e.g. `seon.error/record!` persistence) need a
  macrotask `tick` before asserting the datom —
  `test/seon/agent_inspect_errors_test.cljs:86-90` (`(tick 100)` after the
  bracket).

### Time is ALREADY injected — extend the precedent, never regress it

- `run/close-overdue-runs!` takes `{:seon.agent/now :inst}`
  (`src/seon/agent/run.cljs:484-516`); `schedule/due?` / `next-fire-at` /
  `fire-due-schedules!` take explicit `now`/`after`
  (`src/seon/agent/schedule.cljs`). `run/deadline-passed?` is pure with a
  passed instant (`run.cljs:195-204`).
- The ticker test **never installs `setInterval`** — it calls the two actions
  directly with a chosen `now` (`ticker_test.cljs:11` states this as policy).
- Write paths stamp `(js/Date.)` inline (`beat!` `run.cljs:400-412`,
  `open-run!`) — that's fine for WRITES; tests backdate by transacting
  explicit `:inst` values directly on the test conn (the `supersede!` /
  `seed-schedule!` idiom of raw `d/transact!` in fixtures).

### Fake LLM / loop integration

- `scripted-llm` / `scripted-llm-seq` (`agent_loop_test.cljs:67-82`) — replay
  fixed reply text; "nothing mocked but the LLM text". Loop-integration tests
  also need the blob-dir fixture redirect (`agent_loop_test.cljs:39-50`) and
  the wake-path macrotask-poll helpers (same file, ~line 117).
- Most of this unit does NOT need the loop at all — closes, scans, gates, and
  sections are directly callable fns over the db.

### Fault-gate hygiene (`expecting-core-fault!`)

- The bracket is **async-safe**: a thunk returning a Promise keeps the bracket
  open until it settles (`src/seon/error.cljs:372-417`,
  `depth-bracket!`). So the watchdog test wraps the WHOLE scan call:
  `(error/expecting-core-fault! (fn [] (watchdog-scan! {… ::now …})))` and
  awaits the returned Promise — the `:core` fault fired mid-scan prints
  `SEON-EXPECTED-CORE-FAULT`, which `bin/test-cljs` does not count
  (`bin/test-cljs:284-288`). Then `tick` before asserting the fault datom.
- Caveat: the bracket covers work the thunk **returns**; detached async work
  fired inside the scan but not chained into its Promise escapes the bracket
  and reds the gate. The scan must chain its `record!` persistence (or at
  minimum call `record!` before the scan's Promise resolves).
- Suite manifest is `config/test.edn` (`bin/test-cljs:43`); its
  `on-core-error` default is `:gate` (`src/seon/config.cljs:672-686`), so
  bracketed faults never exit the test process.

### Message assertions

- Hermetic message assertion = query/pull the message log:
  `test/seon/agent/message_test.cljs:93-126` asserts the stored row's
  `from`/`to`/`content`/`at`/`hops`/`origin` and the envelope shape. "Exactly
  one parent-directed message" = count rows with
  `[?m :seon.agent.message/from child-eid] [?m :seon.agent.message/to
  parent-eid] [?m :seon.agent.message/at ?at]` with `?at ≥` run `started-at`
  (this exact derived query already exists as `lifecycle`'s
  `messaged-recipient-since?`, `src/seon/agent/lifecycle.cljs:72-89`).

### Derived-section tests

- Call the block fn directly, the way the render engine does:
  `(section-fn {:seon.db/db db :seon.agent/id id :seon.render/node {…}})` and
  assert on the returned value / nil-when-empty —
  `test/seon/agent/ctx/warnings_test.cljs:52-60` is the template. Test
  behavior/presence, not exact strings.

### Config dials

- New dials (`:seon.config/spawn-depth-cap`, watchdog interval + staleness
  threshold, breaker N + window) follow the memoized-accessor pattern
  (`src/seon/config.cljs:670-686` `on-core-error`;
  `tick-ms` at `:839` is the env-override variant). **Test the pure fns with
  the dial passed as an argument**; the accessor itself needs at most one
  default-value test. Never mutate config from a test.

## Pass 2 — proven patterns from `reference-code/` (evidence)

Full agent report preserved in essence; citations spot-checkable.

- **inspect-ai (COPY THIS):** injectable clock + call the check fn directly.
  `reference-code/inspect-ai/tests/checkpoint/test_checkpointer.py:274-305` —
  `fake_now = [1000.0]` patched in as the clock; tests advance it by hand and
  assert `fire_count` steps 0→0→1→2 across explicit nows. Also
  `tests/util/test_limit_working.py:23-70` (`_MockTime.advance(5)` then call
  the limit check, assert the error). Zero sleeps, exact fire/no-fire
  assertions at each synthetic `now`. This is precisely our
  `close-overdue-runs!`-with-explicit-`now` shape.
- **letta (the ANTI-pattern):**
  `letta/monitoring/event_loop_watchdog.py:32,44,110` reads `time.monotonic()`
  inline — clock not injectable — so its test
  (`test_watchdog_hang.py:29-87`) starts a real watchdog, `time.sleep(8)`, and
  eyeballs logs. Non-deterministic smoke script. If any of our scan/gate fns
  reads `(js/Date.)` inline instead of taking `now`, we are building letta's
  watchdog — flag it in review.
- **core.async:** its JVM timing tests are real-wall-clock + tolerance
  (`reference-code/core.async/src/test/clojure/clojure/core/async/timers_test.clj:6-27`)
  — flaky by design, does not transfer. Its **CLJS** suite instead tests the
  underlying skip-list data structure with fixed keys, bypassing the clock —
  the transferable idea: test the decision function, not the timer.
- **datahike:**
  `reference-code/datahike/test/datahike/test/time_variance_test.cljc` — per-
  test `:memory` conn + `:keep-history?` (`:26-30`); as-of by **tx-id**
  (`:170-172`) and explicit-`:db/txInstant` joins (`:139-150`) are the
  deterministic variants; the `(sleep 10)` + `(now)` history tests (`:126-131`)
  are the flaky variant to avoid. Relevant consequence for us:
  **`:db/txInstant` cannot be backdated** for window tests — see Gap A.
- **test.check:** ships NO stateful/commands facility (only `for-all` pure
  properties — `reference-code/test.check/src/main/clojure/clojure/test/check/properties.cljc:45-68`).
  A model-based test of the run FSM would be hand-rolled over `gen/bind`.
  **Verdict: not worth it for this unit** — the FSM transitions are already
  pinned example-by-example (`run_test.cljs`, including the CAS/TOCTOU
  discriminating tests), the async-Promise plumbing makes shrinkable
  command-sequences expensive, and the marginal bug class (interleaving) is
  covered by the concurrent-opens and fence tests. Revisit only if a real
  interleaving bug escapes.
- **expectations** ships a `freeze-time` global-clock macro
  (`reference-code/expectations/src/cljc/expectations.cljc:639-644`) — JVM/
  Joda only, explicitly unimplemented for CLJS, and global mutation besides.
  Reinforces: inject `now` as an argument, don't freeze a global clock.
  kaocha has nothing relevant.

## Pass 3 — gaps the implementer must resolve (report-backs)

### Gap A — no `closed-at` instant on runs (blocks the breaker's window query)

`close-run!` writes only `:seon.agent.run/status` + `closed-reason`
(`src/seon/agent/run.cljs:308-324`); there is no `:seon.agent.run/closed-at`
attr anywhere. Piece 2d's derived count — "`:crashed` closes for this agent
within a recent window" — has no instant to window over:

- `:db/txInstant` exists on the close tx but **cannot be backdated** in tests
  (monotonic; see the datahike time-variance suite), so a window test would
  need real waits — the letta trap.
- `started-at` is the wrong instant (a long run's crash lands far from its
  start).

**Recommendation:** register `:seon.agent.run/closed-at :inst` and write it in
the same close tx (one attr, same tx, no second mechanism). Tests then
transact N runs with explicit backdated `closed-at` values and call the gate
fn with a chosen `now` — fully deterministic. If the implementer instead
windows over tx-meta, the tests cannot backdate and the plan below does not
work — report back to the owner before proceeding.

### Gap B — spec-internal conflict: `:core`-origin notices never wake

Piece 2b: "Failure notices use message `origin :core`" (spec line ~103).
Piece 2c: "the Piece 2b routing messages root, and the inbound message WAKES
root into a fresh run" (root self-heal, spec ~177-181). But
`waking-inbound?` **excludes `origin :core` from waking** — "a substrate nudge
never wakes an idle agent" (`src/seon/agent/message.cljs:122-136`). As written,
a `:crashed` escalation with `origin :core` cannot wake root, and a
`:turn-limit` notice cannot wake a parked parent — exactly the spec's own
"a lost outcome notice is a parked parent" failure. Resolution is the
implementer's (waking origin for outcome notices, or a carve-out in the one
`waking-inbound?` rule) — but the TESTS must pin the resolved behavior
**end-to-end through the real wake gate**, not just message existence
(see 2b/2c cases below). If the implementation only asserts the message datom
exists, the root-self-heal claim is untested.

### Gap C — watchdog staleness inputs the scan must define (and tests must pin)

- A run with **no beat yet** (`last-beat-at` absent — it's optional,
  `run.cljs:78`): staleness must fall back to `started-at`, else a
  wedged-before-first-beat run is invisible forever. One test pins this.
- Scan signature: takes `now` (+ threshold, as arg or dial) — the
  `close-overdue-runs!` shape. If the landed code reads `(js/Date.)` inline
  in the scan, request the refactor (pure core, thin timer shell) rather than
  writing sleep-based tests around it.
- The watchdog close should route through `close-run!` (fenced, pointer-
  retracting, single choke point) — then the late-beat no-op is already
  proven (`run_test.cljs:96-124`) and needs only one integration re-check.

### Wart to not copy

`ticker_test.cljs:205-233` (double-fire guard) couples to the real wall clock
(builds its cron from the current minute) because `started-at` is stamped
internally. Breaker/watchdog tests must NOT inherit this — backdated datoms +
explicit `now` avoid it entirely. (If `closed-at` lands per Gap A, nothing in
this unit needs the wall clock.)

## The test plan, per spec piece

Unless stated otherwise: hermetic `:memory` conn per the run_test template,
`with-conn` root `set!`, explicit `now` everywhere, no timers, no sleeps, no
LLM. Agent-scoped verbs (`complete`) run under `db/with-agent` (ALS scope),
as in `agent_loop_test.cljs:90-93`.

### Piece 1 — `run/result` + `result-ref`

1. `complete` on an open run writes `:seon.agent.run/result` onto the run,
   closes it `:completed`, pointer retracted, derived `:idle`. Assert the
   datoms via `db/entity` on the run's lookup-ref.
2. **Unconditional past the message-skip guard:** child messages the parent
   mid-run (so `messaged-recipient-since?` fires), then `complete` → message
   count to parent for this run is **still 1** (no second message) AND the
   result datom is present. This is the discriminating test for "the guard
   must not gate the datom".
3. `result-ref` round-trips as a ref: seed any entity, pass its eid, pull the
   run and assert the ref resolves to it.
4. Length cap: a result at cap+1 exercises whatever the shared cap does
   (truncate/refuse) — assert the BEHAVIOR and that it matches the message
   cap's behavior (the "one hoisted constant" ruling); don't pin the number
   twice.
5. Blank result: delivers nothing (message count 0) and closes; pin whether a
   result datom is written (absent = no key — never an empty string).
6. Parentless agent: result message goes to the user
   (`msg/user-ref`), run carries the datoms.

### Piece 2 — depth-capped spawn

1. `spawn-depth` pure fn: parentless → 0; child → 1; grandchild (seeded by
   raw transact, bypassing the cap) → 2.
2. Cycle guard: transact A←parent—B and B←parent—A directly; `spawn-depth`
   **returns a value** (never throws / never loops). If the fn additionally
   records a `:core` fault for the invariant break, the test wraps in
   `expecting-core-fault!`.
3. `start!` from a depth-1 caller (ALS scope = the child): returns the
   standard error envelope (`{:seon.db/ok? false …}` — match `start!`'s
   existing shape), message names the caller's depth and the cap; **no agent
   entity created** (count `:seon.agent/id` datoms before/after — the
   spec's "datom-free" refusal); nothing thrown.
4. Depth-0 caller still spawns (regression guard).
5. Cap as data: call the pure check with cap 2 → depth-1 spawn allowed
   (proves the dial is a parameter, not a constant baked into the logic).

### Piece 2b — outcome routing

All cases: seed parent + child (+ root where relevant) with explicit
`:seon.agent/parent` refs; open a run on the child; close it via the ONE
close choke point with the target reason; assert message counts by
from/to/at-window query (the `messaged-recipient-since?` shape).

1. `:turn-limit` close → **exactly one** parent-directed message; content
   carries the child id + closed-reason + the continue affordance (assert the
   reason keyword's name and the child id appear — behavior, not exact
   strings); origin per the Gap-B resolution.
2. `:deadline-exceeded` — same, via `close-overdue-runs!` with a backdated
   deadline (or an explicit far-future `now`) so the notice rides the REAL
   watchdog path, not a hand call.
3. `:waited` (child calls `wait`) → **zero** outcome messages.
4. `:terminated`, `:superseded` → zero (one test, table-style).
5. `:crashed`, parent ≠ root → **exactly two** messages (one to parent, one
   to root). `:crashed`, parent = root → **exactly one** (dedup).
   Parentless `:crashed` → one, to the user.
6. Hop safety: seed the {child, parent} pair AT `seon.warn/hop-cap` (send
   cap-many prior messages, or transact `hops` directly), then close
   `:turn-limit` → the outcome notice is still delivered/stored (spec:
   "delivery must be reliable"). If `message!`/the wake gate would drop it,
   the test documents the refusal and the finding goes back to the owner —
   do not silently accept a droppable outcome notice.
7. **Wake end-to-end (Gap B):** parent idle → child close → drive the real
   inbound wake gate (the transact-datom + macrotask-poll helpers from
   `agent_loop_test.cljs`) → parent has a NEW open run. This is the one test
   that catches the `origin :core` conflict; message-existence alone does not.

### Piece 2c — heartbeat watchdog

Unit tests call ONE scan pass directly with explicit `now` + threshold;
beats are backdated by transacting `:seon.agent.run/last-beat-at` directly.

1. Stale open run (beat = `now − threshold − ε`) → closed `:crashed`,
   pointer retracted, derived `:idle`; Piece 2b routing observed (root gets
   exactly one message when parent = root). **Whole scan wrapped in
   `expecting-core-fault!`** (async-safe bracket — pass the scan Promise out
   of the thunk), then `tick` and assert the `:seon.error/fault :core` datom
   exists and carries the agent/run refs + stale-beat evidence.
2. Fresh beat (`now − threshold + ε`) → untouched. Boundary pair ±ε around
   the threshold, both with the SAME injected `now` — the inspect-ai
   fire_count idiom.
3. Paused run with a stale beat → untouched (mirrors
   `close-overdue-runs!-skips-a-paused-run`, `ticker_test.cljs:129-147`).
4. No-beat-yet run: stale by `started-at` → closed (Gap C pin).
5. Idempotence: second pass at the same `now` closes nothing, sends nothing,
   records nothing (a closed run can't re-stale — the spec's no-dedup-state
   claim, made falsifiable).
6. Fencing integration (one case): capture the run-id pre-scan, scan closes
   it, then `run/beat!` with the old run-id → `{:seon.db/ok? false}`, no
   datom landed (the late-driver no-op).
7. Root self-heal: root's own stale run closes; the outcome message goes to
   the user; **the wake test from 2b.7 applied to root** — a fresh root run
   opens. (Gap B again — this is the spec's headline self-heal claim.)
8. Threshold/interval dials: pure fn takes them as args; one test with a
   non-default threshold proves no baked constant.

**Live proof that the timer is armed (the ONLY timing test):** the watchdog
must ride the existing ticker (`install-ticker!`,
`src/seon/agent/loop.cljs:648-664` — spec: reuse the timer plumbing, no
parallel `setInterval`). Minimal proof, on the live pod: `logs/pod.log`
shows the ticker-installed line; then create an open run with a backdated
beat (REPL) and observe it closed `:crashed` within ~one `SEON_TICK_MS`
cadence, plus the root message. One pass, once, live — never in the suite.
Review check: the suite contains NO `setInterval`/sleep-based watchdog test;
`run-tick!` (or its successor) is the only place wiring scan → timer.

### Piece 2d — schedule-wake circuit breaker

Requires Gap A's backdatable close instant.

1. Seed N runs closed `:crashed` with `closed-at` inside the window (explicit
   backdated `:inst`s), agent idle, schedule due at `now` →
   `fire-due-schedules!` (or the extracted wake-gate fn) returns
   `fired = []` — refused. No timers: `now` chosen, datoms backdated.
2. N−1 crashes in-window → fires normally.
3. N crashes all OUTSIDE the window (backdated past it) → fires normally
   (the sliding-window re-enable, no reset state).
4. Message wake still works while tripped: same seeding, then the inbound
   message path opens a run (assert via `current-run`).
5. Non-`:crashed` closes (`:completed`, `:turn-limit`) in-window do NOT
   count toward N.
6. Visibility: `derive-status` / the subagents-section line for a tripped
   agent shows the tripped state (presence assertion, derived from the same
   query — no stored flag; also assert nothing breaker-ish is ever
   transacted).
7. Dials (N, window) as args: one non-default-value test.

### Piece 3 — `subagents` section

`warnings_test` shape: call the section fn with
`{:seon.db/db db :seon.agent/id parent-id :seon.render/node {…}}`.

1. Childless agent → renders nothing (nil/absent — the reactive vanish).
2. Running child → line contains child id, running state, `turn i/limit`,
   beat age. Beat age needs `now` — either the section derives it at render
   time (assert by pattern, with the beat backdated a KNOWN interval so the
   age is deterministic) or takes `now` via the node; prefer deterministic.
3. Idle child, completed latest run with `result` (+ `result-ref`) → the
   result string (and pointer) render.
4. `:error`-closed child → the closed-reason renders (the "parent MUST see a
   dead child" case). Same for `:turn-limit`.
5. Direct children ONLY: seed a grandchild → absent from the parent's
   section (the settled visibility ruling, falsified).
6. Token cap: seed children with long purposes → rendered size ≤ the cap in
   TOKENS (`seon.ai.tokens/estimate` — never chars).

### Piece 4 — `orphaned-agents` root section

1. Normal world (live parents) → renders nothing.
2. Terminate the parent (transact `:seon.agent/terminated-at`), child alive
   → one line: child id, state, purpose, parent id.
3. Terminated child of a terminated parent → excluded (only LIVE orphans).
4. Root-only placement is config (`:seon.config/root-context`,
   `config/system.edn:168-199` next to `:core-faults`) — verify by config
   inspection/live proof, not a unit test.

## What NOT to test (and what the reviewer should reject)

- **Real intervals / wall-clock waits.** Any test that sleeps to let a
  threshold pass or a window slide is wrong — backdate the datom instead.
  The one timing check is the single live-pod proof above.
- **LLM turn content.** No test in this unit needs a real LLM; the few
  loop-integration cases use `scripted-llm`. The messages' prose beyond
  {child id, reason keyword, affordance presence} is not asserted.
- **Exact rendered strings / token counts.** Presence + structure + cap
  bounds ("test behavior, not strings").
- **Re-proving the CAS/fencing matrix** — `run_test.cljs` owns it; one
  integration case per new close path suffices.
- **Cron parsing / due? matching** — `ticker_test` + schedule tests own it;
  the breaker tests take "schedule is due" as a given via a matching `now`.
- **The config accessors' plumbing** beyond one default-value check.
- **Model-based/generative FSM testing** — evaluated (pass 2) and declined
  for this unit; test.check has no commands facility and the example tests
  already pin the transitions.

## Reviewer TL;DR checklist

Walk the implementation's tests; every box should check:

- [ ] All new unit tests run on hermetic `:memory` conns (run_test template),
      root `set!` not `binding`, `done` on both rails, new run attrs added to
      the fixture attr list.
- [ ] **Zero sleeps, zero `setInterval`, zero inline `(js/Date.)`
      dependencies in scan/gate/count assertions** — every staleness/window
      test injects `now` and backdates `:inst` datoms.
- [ ] The watchdog scan and breaker gate are pure-core fns taking
      `now` (+ dials) as args; the timer wiring is the existing ticker only,
      covered by ONE documented live-pod proof, not a suite test.
- [ ] A backdatable close instant exists (`:seon.agent.run/closed-at` or
      equivalent) — else the breaker window tests are impossible (Gap A
      reported to owner if unresolved).
- [ ] The `origin :core` vs `waking-inbound?` conflict (Gap B) is resolved
      explicitly, and there is an END-TO-END wake test: child close → parent
      (and wedged-root → fresh root run) actually opens a run through the
      real inbound gate — not just a message-datom existence check.
- [ ] `complete`'s result datoms proven **unconditional** against the
      message-skip guard (message count still 1, datom present).
- [ ] Depth-cap refusal proven datom-free (agent count unchanged), envelope-
      shaped, non-throwing; `spawn-depth` cycle case returns a value.
- [ ] Outcome-routing matrix covered: one message for each of
      `:turn-limit`/`:deadline-exceeded`/`:error`/`:no-forms`; ZERO for
      `:waited`/`:terminated`/`:superseded`; `:crashed` = 2 msgs (non-root
      parent) / 1 (root parent) / 1-to-user (parentless); the
      `:deadline-exceeded` case rides the real `close-overdue-runs!` path.
- [ ] Hop-cap cannot drop an outcome notice (tested at the cap; refusal, if
      any, reported back — not silently accepted).
- [ ] Watchdog tests: stale→`:crashed` + fault datom, fresh→untouched (±ε
      boundary pair at one `now`), paused→skipped, no-beat→`started-at`
      fallback, idempotent second pass, late `beat!` fenced no-op.
- [ ] Every deliberate `:core` fault wrapped in `expecting-core-fault!` with
      the **scan's Promise returned from the thunk** (async-safe bracket) +
      a `tick` before datom assertions; `bin/test-cljs` green with zero
      un-expected `SEON-CORE-FAULT` markers.
- [ ] Breaker: refuses schedule wake at N in-window `:crashed` closes;
      fires at N−1 and at N out-of-window; message wake unaffected; only
      `:crashed` counts; tripped state visible and derived (nothing stored).
- [ ] Sections: childless/orphanless render NOTHING; direct-children-only
      falsified with a grandchild; abnormal closes visible; caps asserted in
      tokens via `seon.ai.tokens/estimate`.
- [ ] Full `bin/test-cljs` ONCE at the end; live proofs per the spec's
      "Testing + live proof" section actually observed on the pod (rendered
      section READ, not inferred).
