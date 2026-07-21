---
type: research
status: draft
tags: [research, agent, flow]
---

# The reactive wake / engagement router — current mechanism + data-driven target

## TL;DR

- **Today there are TWO wake mechanisms, not one.** (1) A **per-agent
  tx-listener** (`seon.agent.loop/install-wake-trigger!`) wired on `db/listen!`,
  whose handler hardcodes ONE wake condition (`inbound-msg-datom?` — a message
  whose `to ∋ me`). (2) A **single 30 s `setInterval` ticker**
  (`install-ticker!`) that closes overdue run deadlines and fires due cron
  schedules. They complect runtime control: the condition is hand-coded to the
  message shape, and the ticker is a separate time-based poll giving **up to 30 s
  latency** on every time-based wake.
- **The target is ONE data-driven mechanism.** Each agent carries a
  `:seon.agent/wake-fn` SYMBOL (data → replayable). One **global** tx-listener
  decodes each commit once, runs the relevant wake-fns, and truthy → engages the
  agent via the existing `open-run!` CAS (the engage-gate is unchanged; only the
  CONDITION generalizes). `inbound-msg-datom?` becomes the DEFAULT wake-fn.
- **The prefilter is NOT hard — because it already exists in this repo.**
  `src/seon/server/reactive.clj` (JVM track, 315 lines, tested against the four
  failure modes) is a complete Posh port: a ~15-line e/a/v datom-matcher,
  `query->patterns` auto-derivation, an inverted index `{:by-attr … :by-entity …
  :all}` that turns per-tx routing into O(subs touching the tx's attrs), and a
  **two-gate** dispatch (cheap pattern gate → comprehensive check). The CLJS
  wake-router is a near-mechanical port where GATE 2 becomes "run the wake-fn".
  **Verdict: build the prefilter from day one by porting `seon.server.reactive`;
  the run-all-on-every-tx baseline is the fallback only if the port slips.**
- **Replace the 30 s poll with precise `setTimeout`.** For each known future
  instant (a run `deadline`; a schedule `next-fire-at` — `next-fire-at` already
  exists) arm a Node `setTimeout` that, on fire, MINTS A `:core` WAKE EVENT (a
  datom) so time-wakes flow through the SAME data-wake path as message-wakes.
  Timers are **runtime artifacts re-derived from DATA on boot** — never stored
  (honours the "derive, don't store" rule).
- **CRITICAL Node footgun (verified directly, see §5):** `setTimeout` clamps any
  delay `> 2147483647 ms (≈ 24.86 days)`, OR negative, OR `NaN`, to **1 ms** — it
  fires almost immediately. A deadline > 24.8 days out, or a mis-computed delay,
  becomes a SPURIOUS INSTANT WAKE. The chunked re-arm pattern is mandatory, not
  optional.
- **ONE global listener beats N per-agent listeners** — grounded: `d/listen`
  fires ALL callbacks sequentially per commit, and seon's `wrap-listen-handler`
  re-decodes the full tx-data (map-per-datom + group-by) **once per listener**.
  N agents ⇒ N redundant decodes of the same tx. The global router decodes once
  and routes via the inverted index.

---

## 1. The current mechanism, explained (file:line) + its gaps

### 1a. Message wake — the per-agent tx-listener

The chain, end to end:

1. **Arm.** `seon.agent.loop/install-wake-trigger!`
   (`src/seon/agent/loop.cljs:442`) registers ONE listener per agent under the
   stable key `[:seon.agent/user-message-trigger id]` (`loop.cljs:462`),
   unlistening any prior handler first (idempotent under hot reload), then
   `db/listen!` with `(wake-handler input)`. It also stamps the agent's loop
   `input` into the process-local `!loop-input` atom (`loop.cljs:112`, `:458`) so
   RESUME can re-drive (`drive-run!`, `loop.cljs:412`).

2. **Fire.** On every commit, `db/listen!` (`src/seon/db.cljs:1056`) invokes the
   handler that `seon.db.internal/wrap-listen-handler` (`db/internal.cljs:1473`)
   built. The wrapper calls `build-handler-input` (`db/internal.cljs:1460`) →
   `{:seon.db/db, :seon.db/db-before, :seon.db/datoms (mapv datom->map …),
   :seon.db/attr-index (group-by ::db/a datoms)}`.

3. **Filter.** `wake-handler` (`loop.cljs:306`) resolves `my-eid` for this agent,
   then filters the `:seon.agent.message/to` datoms in the `attr-index` for
   `:seon.db/added?` AND `agent/inbound-msg-datom?` (`loop.cljs:315-320`).
   `inbound-msg-datom?` (`src/seon/agent.cljs:378`) = `to = my-eid` AND
   `seon.agent.message/waking-inbound?` (`src/seon/agent/message.cljs:120`),
   i.e. `from ≠ me` AND `origin ≠ :core`. Hop-exhausted messages are
   partitioned out and refused loudly (`loop.cljs:322-338`).

4. **Engage.** For a waking datom: derive the agent's state
   (`derive/derive-state`); if `:idle` → `js/setTimeout(…, 0)` re-enters
   `with-agent` and calls `run/open-run!` (`loop.cljs:368-393`); if `:running` →
   `renew!` the lease (`loop.cljs:355-363`). The idle→running open is the ATOMIC
   gate: `open-run!` (`src/seon/agent/run.cljs:220`) ends its tx with
   `[:db.fn/cas [:seon.agent/id id] :seon.agent/run nil [:seon.agent.run/id R]]`
   (`run.cljs:274`) — the pointer must be ABSENT — so two simultaneous wakes
   can't both open; the loser renews instead.

### 1b. Time wake — the ONE ticker

`install-ticker!` (`loop.cljs:514`) installs a single `setInterval` at
`default-tick-ms` = **30000 ms** (`loop.cljs:484`), overridable by `SEON_TICK_MS`
(`env-tick-ms`, `loop.cljs:490`). Each `run-tick!` (`loop.cljs:498`):

1. `run/close-overdue-runs!` (`src/seon/agent/run.cljs:459`) — the deadline
   WATCHDOG: queries `:open`, non-paused runs whose `deadline < now`, closes each
   `:deadline-exceeded`, retracting the pointer so state falls to `:idle`.
2. `schedule/fire-due-schedules!` (`src/seon/agent/schedule.cljs:300`) — for each
   `:idle` agent owning a `due?` schedule (not already fired this minute), opens a
   `:schedule` run and `drive!`s it (`drive-run!` injected to avoid a require
   cycle).

The cron logic is complete and pure: `parse` / `due?` / `next-fire-at`
(`schedule.cljs:126/182/200`). **`next-fire-at` already computes the next matching
instant** — the exact value the precise-timer design needs.

### 1c. Resume / boot path

`seon.client/start-agent!` (`src/seon/client.cljs:2187`): on first boot,
`run/recover-crashed-runs!` (`run.cljs:503`) closes every orphaned `:open` run so
each agent is wakeable; then `armable-agent-ids` (`src/seon/derive.cljs:159`, the
non-terminated `:idle` filter) drives per-agent `boot-one-agent!`
(`client.cljs:1985`) which arms each wake trigger; finally `install-ticker!`
(`client.cljs:2376`). Hot reload re-arms via `rearm-wake-triggers!`
(`client.cljs:1935`).

### 1d. How `db/listen!` actually fires (read from datahike source)

- **Registration** is O(1): `d/listen` `swap!`s the callback into the conn's
  `:listeners` atom keyed by `k`
  (`reference-code/datahike/src/datahike/core.cljc:213-216`;
  optimistic `optimistic.cljc:487-522`).
- **Fire** iterates ALL listeners sequentially with the SAME report:
  `(doseq [[_ callback] @(:listeners …)] (callback report))`
  (`datahike/js.cljs:121-127`; optimistic `emit-tx!`,
  `optimistic.cljc:180-190`). Cost per commit is **O(#listeners) callback
  invocations**, each handed the identical raw tx-report.
- **The optimistic writer fires MORE THAN ONCE per logical write** — an
  `:overlay-add` (predicted datoms) then a `:conn-advance` (durable datoms), plus
  conflict/resolve events (`optimistic.cljc:487-516`). A wake-fn can therefore be
  evaluated 2× for one agent write. (The `open-run!` CAS makes this idempotent,
  but the router must tolerate double-fire — see Risks.)
- **seon re-decodes per listener.** `wrap-listen-handler` calls
  `build-handler-input` (the `mapv datom->map` + `group-by`) INSIDE each
  listener's closure (`db/internal.cljs:1460-1493`). So N listeners ⇒ N decodes
  of the same tx-data.

**Live proof (pod, 2026-06-28).** One agent `root`, `:idle`; **0** schedules;
listener registry on the live conn:
`[:seon.web.datastar/world  :seon.web.debug/debug  [:seon.agent/user-message-trigger "root"]]`
— i.e. the per-agent wake listener is exactly one-per-agent, sitting alongside
the UI listeners; every commit fires all three.

### 1e. Where it is NOT general (the gaps)

1. **The wake CONDITION is hardcoded.** Only `inbound-msg-datom?` can wake an
   agent. "Wake when my todo count crosses N", "wake when another agent errors",
   "wake when a file lands" are all impossible without editing `wake-handler`.
   The condition is not data; it is a compiled predicate.
2. **The prefilter is hand-coded to the message shape.** `wake-handler` reaches
   specifically into `(:seon.agent.message/to attr-index)`. Any other trigger
   attribute is invisible to it.
3. **TWO mechanisms, not one.** Message-wakes go through `db/listen!`;
   time-wakes go through a separate `setInterval`. Two control surfaces for "make
   the agent run".
4. **30 s poll latency.** A `deadline` that passes, or a cron that becomes due,
   is not noticed until the next tick — **up to 30 s late**. The `SEON_TICK_MS`
   knob trades latency for tick cost but can't eliminate either.
5. **N per-agent listeners** (§1d) — N redundant tx-decodes per commit, growing
   linearly with the fleet on the hot path.
6. **`fire-due-schedules!` is dead weight today** — 0 schedules exist, so the
   schedule half of the ticker does nothing every 30 s, forever, until the
   `my.schedule` tool ships. The watchdog half is the only live time-work.

---

## 2. The target design — one data-driven wake router

### 2a. The wake-fn data model

Add to the agent entity:

```clojure
;; symbol → resolved late via seon.eval/lookup-value (code-as-data, like
;; :seon.ctx/fn and :seon.agent.schedule/fn). Absent ⇒ the DEFAULT wake-fn.
(schema/register! :seon.agent/wake-fn :symbol)
```

A wake-fn is a pure predicate over a db value + the agent id + the tx delta:

```clojure
(defn inbound-message-wake?
  "The DEFAULT wake-fn — today's inbound-msg-datom? rule, now data-selected."
  [{:seon.db/keys [db datoms] :seon.agent/keys [id]}]
  …) ; truthy ⇒ engage
```

- The wake-fn **SYMBOL is DATA** (a datom, replayed like any attr). The wake-fn
  **BODY is code in the program graph** (`:seon.fn`, replayed/reconstituted like
  every other fn). This is exactly the `:seon.ctx/fn` / `:seon.agent.schedule/fn`
  pattern (`docs/seon/concepts/code-as-data-runtime.md`). Nothing new to persist.
- `inbound-msg-datom?` is preserved verbatim as the body of the default wake-fn —
  this is an extension, not a parallel system (no `wake-v2`).
- The engage-gate is UNCHANGED: truthy wake-fn → `open-run!`'s CAS
  (`run.cljs:274`). The wake-fn generalizes the CONDITION only. (Hard constraint
  honoured: `open-run!`'s CAS stays.)

### 2b. One mechanism — message AND time are both data-wakes

The unification: a TIME wake becomes a DATA wake by minting a real event datom
when the timer fires. Concretely a `:core`-origin "now/tick" event (or, for
deadlines, the watchdog's existing close-tx) writes a datom; the global router's
tx-listener sees it and runs the wake-fns whose patterns match. So:

- **Message** lands → `:seon.agent.message/to` datom → router runs wake-fns.
- **Timer** fires → mint a `:core` wake-event datom → router runs wake-fns.

Both paths converge on ONE listener + ONE wake-fn evaluation step. The `:core`
origin keeps the synthetic event from spuriously waking *message* wake-fns
(`waking-inbound?` already excludes `:core`); a *time* wake-fn matches the wake-
event attr instead. The "what woke whom" history is just the message/event log
(already data) — no new transient store.

### 2c. Precise scheduling — replace the poll with `setTimeout`

For every known future instant the system holds a precise timer instead of
polling:

- **Run deadlines.** On `open-run!` / `renew!` (which set/extend `deadline`), arm
  a `setTimeout(deadline − now)` that, on fire, runs the watchdog close for THAT
  run. On `close-run!` / supersede, clear it. The watchdog
  (`close-overdue-runs!`) stays as the idempotent SAFETY NET (and the
  reconcile-on-boot path), but it is no longer the primary latency path.
- **Schedules.** On schedule create/change, compute `next-fire-at`
  (`schedule.cljs:200`, already exists) and arm `setTimeout` to that instant; on
  fire, open+drive the `:schedule` run AND re-arm the next `next-fire-at`. No
  per-minute polling, no double-fire-within-the-minute guard needed (the timer
  fires once at the instant).

Timers live in a process-local registry (same class as `!loop-input` /
`!runs-this-process`): `{:seon.agent.run/id → timeout-id}` and
`{:seon.agent.schedule/id → timeout-id}`. They are **runtime artifacts**, NEVER
datoms — re-derived from the `deadline` / `next-fire-at` DATA on boot.

**The mandatory chunked re-arm (the 24.8-day footgun).** `setTimeout` clamps any
delay `> 2147483647 ms (≈ 24.86 days)`, `≤ 0`, or `NaN` to **1 ms** and fires
almost immediately (verified, §5). So:

```
arm(instant):
  delay = instant − now
  if delay <= 0:        fire now (already due)
  else if delay > MAX:  setTimeout(re-arm-after-MAX, MAX)   ; chunk, don't fire
  else:                 setTimeout(fire, delay)
  timer.unref()         ; never keep the process alive just for a far timer
```

Without the chunk, a deadline/cron more than ~24.8 days out — or any NaN/negative
delay from a clock or parse bug — becomes a SPURIOUS INSTANT WAKE storm. This is
the single sharpest correctness hazard in the whole design.

### 2d. The prefilter — DIFFICULTY VERDICT (grounded)

**The hard part already exists in this repo and is tested.**
`src/seon/server/reactive.clj` (JVM track) is a complete Posh port:

- `pattern-match?` / `datom-match?` / `any-datoms-match?` (`reactive.clj:58-71`)
  — a ~15-line e/a/v matcher ported from `posh.lib.datom-matcher`
  (`reference-code/posh/src/posh/lib/datom_matcher.cljc`). Each pattern position
  is `'_` (wildcard), a SET (membership), or a literal (equality).
- `query->patterns` (`reactive.clj:93-102`) — AUTO-DERIVES e/a/v patterns from a
  datalog query by `tree-seq`-walking the `:where` clauses recursively (so
  `not`/`or`/`and`/join nesting is covered), qvars/`_` → wildcard, literals kept.
  Documented invariant: "Over-collecting is safe (the cheap gate + result-diff
  confirm); under-collecting is not."
- An INVERTED INDEX `{:by-attr {a #{id}} :by-entity {e #{id}} :all #{id}}`
  (`reactive.clj:110-137`) — `candidate-subs` turns per-tx routing from O(all
  subs) into **O(subs touching the tx's modified attrs/entities)**.
- TWO-GATE dispatch in `on-tx!` (`reactive.clj:221-259`): GATE 1 = cheap
  `any-datoms-match?` (confirms the index superset); GATE 2 = the comprehensive
  check (there: re-run query + result-diff). ONE global `d/listen!` callback, not
  per-sub listeners.
- Persisted as durable datoms with `rebuild!`-from-DB (`reactive.clj:197-212`) —
  query stored as a SOURCE STRING (code-as-data).
- `test/seon/server/reactive_test.clj` hunts the four failure modes (over-match,
  under-match, spurious emit, missed emit) with a brute-force oracle.

**For the wake-router, the port is mechanical:**

| `seon.server.reactive` (subscriptions)        | wake-router (agents)                                  |
|-----------------------------------------------|-------------------------------------------------------|
| sub entry `{:query :patterns :last-result}`   | wake entry `{:wake-fn-sym :patterns}`                 |
| GATE 2 = re-run query + `not= last-result`    | GATE 2 = `(wake-fn …)` truthy                         |
| `emit!` changed-summaries                     | `open-run!` (engage) / `renew!`                       |
| index by `:by-attr` / `:by-entity` / `:all`   | identical                                             |

Where do a wake-fn's patterns come from? Two grounded options, pick by ergonomics:

1. **Declared trigger-query** (preferred). The agent stores a
   `:seon.agent/wake-query` source string; `query->patterns` derives the patterns
   for free (reuse `reactive.clj:93`). The default wake-fn's query is
   `[:find ?m :where [?m :seon.agent.message/to <my-eid>]]` → patterns
   `[[_ :seon.agent.message/to <my-eid>]]` → indexed `:by-attr`. The wake-fn body
   is the comprehensive GATE 2.
2. **Declared patterns** directly (`:seon.agent/wake-pattern`) — even simpler;
   then the prefilter is a pure set-membership test and there is zero query
   analysis. This is the "trivial" end of the spectrum.

**Verdict.** The Posh-style prefilter is the right call FROM DAY ONE, because the
difficult machinery (matcher + auto-derivation + inverted index + four-failure
oracle) is already written, tested, and idiomatic in `seon.server.reactive` — the
CLJS port is a `.clj`→`.cljs` translation, not new research. The
run-all-wake-fns-on-every-tx baseline is a legitimate fallback (and a fine
*correctness reference* for testing the indexed path against), but it is the
fallback, not the plan. The ONLY part that is genuinely "hard" in Posh — full
`q_analyze` (575 lines) + `pull_analyze` (233 lines) that derive patterns from
arbitrary query+pull with `core.match` — is NOT needed: `query->patterns`'
recursive over-collecting walk is sufficient precisely because GATE 2 (the
wake-fn) is the comprehensive check, so the prefilter only has to be a safe
SUPERSET.

### 2e. Global listener vs N per-agent listeners — VERDICT

**ONE global listener.** Grounded reasons:

- `d/listen` fires every callback sequentially per commit (§1d); seon re-decodes
  the tx-data once per listener (`build-handler-input` inside each closure). N
  per-agent listeners ⇒ N redundant `mapv`+`group-by` over the same tx-data, plus
  N `my-eid` entity lookups, EVERY commit — including UI-only and bootstrap txs
  that wake nobody. The live registry already shows the wake listener sitting
  next to two UI listeners, all fired per commit.
- The global router decodes ONCE, then `candidate-subs` consults the inverted
  index to touch only the wake-fns subscribed to the tx's modified attrs. A tx
  that touches no wake-trigger attr does O(1) work after the single decode.

The one legitimate argument FOR per-listener isolation is **error containment**:
`d/listen`/`emit-tx!` already try/catch per callback, so one bad listener can't
kill the others. The global router must replicate this — wrap each wake-fn
evaluation in its own try/catch (a throwing wake-fn logs loudly and is skipped,
never aborting the router or sibling wake-fns). With that, the global model is
strictly better. (The UI listeners `:seon.web.datastar/world` /
`:seon.web.debug/debug` can stay separate or fold into the same router later —
out of scope here; the WAKE listeners are what collapse to one.)

### 2f. The DATA vs RUNTIME split (for replay/restore)

| Concern                              | Where it lives        | Replayable?                          |
|--------------------------------------|-----------------------|--------------------------------------|
| wake CONFIG (`:seon.agent/wake-fn` symbol, `wake-query`/`wake-pattern`, schedules) | DATOMS | YES — replayed like any attr         |
| wake-fn BODY                          | program graph (`:seon.fn`) | YES — reconstituted like every fn    |
| "what woke whom"                      | message/event log (datoms) | YES — already the durable record     |
| the global tx-listener + inverted index | process-local atom  | RE-DERIVED at boot from the config datoms |
| the `setTimeout` timers              | process-local registry | RE-DERIVED at boot from `deadline` / `next-fire-at` DATA |

This satisfies the owner's "data-based ⇒ easily restore" and the hard rule "NEVER
store transient/renderable data; derive from the DB". The timers and the index
are NEVER datoms — on boot the router rebuilds the index from the agents'
`wake-*` datoms (exactly `seon.server.reactive/rebuild!`), and re-arms each timer
from the open runs' `deadline` and the schedules' `next-fire-at`. Restore =
read config datoms → rebuild index → re-arm timers. No runtime-code injection
beyond the program-graph replay that already happens.

---

## 3. Migration path — REPLACE, do not parallel

The whole repo is on a feature branch; this is an atomic refactor, not a v2.

**Step 1 — Default wake-fn + global router (replaces N per-agent listeners).**
- Port `seon.server.reactive`'s matcher + `query->patterns` + inverted index +
  `on-tx!` two-gate dispatch into a CLJS `seon.agent.wake` ns (the GATE-2
  comprehensive check is `(wake-fn …)`, the engage action is `open-run!`).
- Register `:seon.agent/wake-fn` (default = the symbol of a wake-fn whose body is
  today's `inbound-msg-datom?` rule). The agent's wake-query/pattern defaults to
  the message-`to` pattern.
- Replace `install-wake-trigger!` (the per-agent `db/listen!`) with ONE global
  `db/listen!` (key `:seon.agent.wake/router`) wired once at boot. DELETE the
  per-agent listener arming in `boot-one-agent!` / `rearm-wake-triggers!`;
  replace with "rebuild the router index from the agents' wake datoms".
- `wake-handler`'s idle→`open-run!` / running→`renew!` / hop-cap-refuse logic
  moves into the router's per-agent engage step UNCHANGED (the CAS engage-gate is
  untouched).

**Step 2 — Precise timers (replaces the 30 s `setInterval`).**
- Add a `seon.agent.timers` process-local registry + the chunked-`unref` arm/
  clear helpers (§2c).
- `open-run!`/`renew!`/`close-run!` arm/re-arm/clear the deadline timer; on fire,
  call `close-overdue-runs!` for that run (or mint the `:core` wake-event).
- schedule create/change arms a `next-fire-at` timer; on fire, open+drive the
  `:schedule` run and re-arm.
- DELETE `install-ticker!` / `run-tick!` / `default-tick-ms` / `env-tick-ms` /
  `!ticker` / `uninstall-ticker!` (`loop.cljs:482-537`). KEEP
  `close-overdue-runs!` and `recover-crashed-runs!` as the idempotent boot-recon
  + safety net, but invoke the watchdog from a precise per-run timer, plus ONE
  coarse low-frequency sweep (e.g. on boot and opportunistically) purely as a
  belt-and-braces backstop — NOT the 30 s primary path.

**Step 3 — Boot/resume re-derivation.**
- `start-agent!`: after `recover-crashed-runs!`, (a) rebuild the router index
  from every armable agent's wake datoms; (b) re-arm a deadline timer for each
  still-open run and a `next-fire-at` timer for each schedule. Hot reload re-runs
  both (idempotent), replacing `rearm-wake-triggers!`'s per-agent re-arm loop.

**No parallel system at any step.** The default wake-fn IS the old predicate; the
router IS the old listener generalized; the timers ARE the old ticker made
precise. Old code is deleted in the same patch.

---

## 4. Open questions + risks

1. **Optimistic double-fire.** The optimistic writer emits `:overlay-add` then
   `:conn-advance` for one logical write (`optimistic.cljc:487-516`), so a
   wake-fn may evaluate twice per agent write. The `open-run!` CAS makes ENGAGE
   idempotent (the second loses the race → renew), but the router should ideally
   gate on `:origin`/basis-t to avoid double work. Decide: filter to one origin,
   or rely on the CAS. (Needs a live check of which origins the POD's conn
   actually emits — the pod path may be the simple `js.cljs/transact` single-fire,
   not the optimistic writer; confirm before relying on either.)
2. **Wake-fn cost on the hot path.** GATE 2 runs the wake-fn body, which may run a
   datalog query. The inverted index keeps GATE-2 evaluations to the subscribed
   wake-fns only, but a pathological wake-fn (expensive query, fires on a hot
   attr) taxes every matching commit. Mitigation: keep wake-fns cheap by
   convention; the cheap GATE-1 pattern match is the throttle. Consider a budget/
   timeout per wake-fn.
3. **Pattern under-collection = missed wakes.** If a wake-query's patterns
   under-collect, the agent silently never wakes (the worst failure). The
   `query->patterns` recursive walk + "over-collect is safe" stance addresses it,
   and the run-all baseline is the correctness oracle to test against — port
   `reactive_test.clj`'s four-failure-mode oracle to CLJS.
4. **`:by-entity` keys and lookup-refs.** The default message-`to` pattern keys on
   `my-eid` (a concrete entity id). Entity ids are assigned by the writer; on a
   fresh boot the agent's eid may differ, so the index must be rebuilt from
   CURRENT eids at boot (the `rebuild!` already captures `db` once). Confirm the
   pod's eids are stable across the wire path or always rebuild.
5. **Timer storms / fleet scale.** At thousands of agents × (deadline + schedules)
   the active-timer count grows. Node's libuv timer heap handles thousands cheaply,
   but the `unref` + clear-on-close discipline must be airtight or timers leak.
   (Could not get Gemini's confirmation — it timed out twice; this is the one
   external fact left unverified. The direct Node verification in §5 covers the
   correctness footgun, not the high-cardinality scaling curve.)
6. **Worker isolation for sync runaways unchanged.** The precise deadline timer
   fires on the event loop; a truly-SYNC runaway in the same thread blocks it
   exactly as it blocks the ticker today. Off-thread enforcement (worker kill) is
   the separate Phase-2 isolation concern, untouched by this design.

---

## 5. Raw external responses + direct verifications (verbatim)

### 5a. Node `setTimeout` overflow/negative/NaN — VERIFIED DIRECTLY (`node -e`)

```
TIMEOUT_MAX = 2147483647 ms = 24.86 days
(node:80194) TimeoutOverflowWarning: 5184000000 does not fit into a 32-bit signed integer.
Timeout duration was set to 1.
(node:80194) TimeoutNegativeWarning: -5 is a negative number.
Timeout duration was set to 1.
(node:80194) TimeoutNaNWarning: NaN is not a number.
Timeout duration was set to 1.
over-max fired after 1 ms (delay was 60 days)
negative-delay fired after 1 ms
NaN-delay fired after 1 ms
```

Interpretation: a `setTimeout` delay `> 2147483647 ms (≈ 24.86 days)`, `≤ 0`, or
`NaN` is silently clamped to **1 ms** — it fires almost immediately. This is the
load-bearing footgun behind the mandatory chunked re-arm (§2c).

### 5b. Live pod state — VERIFIED (`mcp__seon_cljs__eval`, 2026-06-28)

```clojure
;; agents + derived state, schedule count, agents-with-schedules
=> {:agents [["root" :idle]], :schedule-count 0, :agents-with-schedules []}

;; tx-listeners registered on the live conn
=> {:listener-keys-via-meta
      [:seon.web.datastar/world
       :seon.web.debug/debug
       [:seon.agent/user-message-trigger "root"]],
    :listener-count 3}
```

Confirms: per-agent wake listener (one per agent, keyed
`[:seon.agent/user-message-trigger id]`) firing alongside the UI listeners on
every commit; zero schedules exist (the ticker's schedule half is dead weight
today).

### 5c. Gemini consultation — DID NOT COMPLETE

Two `agy -p` calls (a 5-question version, then a tighter 3-question Node-facts
version) both timed out / were killed without returning output (rate-limit or
slow backend, 2026-06-28). The questions sent are preserved in
`scratchpad/gem-prompt.txt` / `gem2.txt`. The most load-bearing facts those calls
targeted (the `setTimeout` clamp behavior, §5a) were instead verified DIRECTLY
against Node, which is stronger grounding than a model summary. The only fact left
to external uncertainty is the high-cardinality libuv timer-heap scaling curve
(Risk §4.5).

### 5d. In-repo prior art read directly (the decisive finding)

`src/seon/server/reactive.clj` (315 lines, `test/seon/server/reactive_test.clj`)
and `reference-code/posh/src/posh/lib/datom_matcher.cljc` were read in full. The
matcher, `query->patterns`, the inverted index, and the two-gate `on-tx!` are
quoted/cited by line in §2d. This is the basis for the prefilter verdict: the
Posh-style prefilter is already built and tested on the JVM track; the CLJS
wake-router is a port, not new research.
