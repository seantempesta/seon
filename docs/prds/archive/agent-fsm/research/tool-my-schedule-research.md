---
type: research
status: draft
tags: [research, agent]
---

# Tool research — `my.schedule` / `remind` (agent-facing schedule + remind)

Capability: "do X at T / every morning / check X in an hour." The
assistant-flavor verb a personal AI obviously needs. This note answers ONE
question with evidence: what is the BEST out-of-the-box implementation, given
seon runs ClojureScript on Node (no JVM) with a DB-as-bus + ONE-ticker
architecture. DESIGN ONLY — no source changed.

## TL;DR

- **Recommendation: HYBRID.** (1) Build `my.schedule` as a THIN wrapper over the
  EXISTING `seon.agent.schedule` floor (`add!` / `list` / `cancel!` /
  `remind!`); the cron entity, the pure cron logic, and the ticker already
  exist and are live-proven — only the agent verb is missing. (2) KEEP the
  hand-rolled pure cron engine (`parse` / `due?` / `next-fire-at`); do NOT add
  an npm cron library. The one real gap the engine flags — timezone-aware
  matching — closes IN PLACE with the SAME native `Intl.DateTimeFormat` the pod
  already uses elsewhere, zero new dependency.
- **Reject `wrap-lib`.** Every npm cron library is either (a) a SCHEDULER that
  owns its own `setTimeout`/`setInterval` + in-memory job state (`node-cron`,
  `node-schedule`, the `cron` package) — the wrong shape, it fights seon's ONE
  ticker + DB-derived-due-ness + crash-recovery model; or (b) a pure parser
  (`cron-parser`, `croner`-used-purely) that buys timezone + exotic syntax at
  the cost of an opaque JS dep that does NOT render as code-as-data, to replace
  ~150 lines of working, tested, in-context CLJS. Neither wins here.
- **The verb is forward-compatible and ships TODAY.** `remind!` (wake the agent
  on schedule + surface a `:say` note) works against today's
  `fire-due-schedules!`. "Every morning, run THIS fn" is the SAME verb with a
  `:fn` — it lands when the one-exec-service sandbox routing arrives (already
  deferred in the floor). Ship the verb shape now.

## What already exists (read first)

`src/seon/agent/schedule.cljs` — the floor — ALREADY has the hard 80%:

- **The entity schema** `:seon.agent.schedule/*`: `id` (`:seon.db/identity`),
  `cron` (5-field string), `fn` (qualified symbol, code-as-data), `timezone`
  (IANA), `concurrency-policy` (`:forbid`/`:allow`). An agent owns a vector of
  these via `:seon.agent/schedules` (a `:seon.db/component` ref vector —
  `src/seon/agent.cljs:96`), so retracting the agent or the schedule cascades.
- **The pure cron logic**, each taking an EXPLICIT `now`/`after` (no implicit
  clock, fully testable): `parse` (5-field string → field sets, supports
  `* */n n a-b a-b/n` + comma lists), `due?` (does an instant match?),
  `next-fire-at` (next matching instant, minute-scan bounded to ~366 days).
  Cron day-of-month/day-of-week OR-semantics implemented correctly
  (`day-matches?`). Errors-are-values throughout (`parse` returns
  `{::ok? false ::error "…"}`, never throws). Proven by
  `test/seon/agent/schedule_test.cljs`.
- **The firing half of the ONE ticker** — `fire-due-schedules!`: for every
  `:idle` agent owning a `due?` schedule, opens a `:schedule`-triggered run and
  drives it (a pure function of the DB — no stored firing state; a per-agent
  per-minute double-fire guard derived from the run log). Driven by
  `seon.agent.loop/run-tick!` (`install-ticker!` → ONE `setInterval` every
  `SEON_TICK_MS`).

**The gap is purely the agent-facing verb.** This matches the toolkit-catalog
verdict verbatim (`docs/prds/agent-fsm/toolkit-catalog.md`, the `my.schedule`
entry + candidate-verdicts table): "IN — build the verb."

## The architectural constraint that decides the library question

seon's runtime is **DB-as-bus + derive-everything + ONE ticker**: there is a
single `setInterval` that, each tick, DERIVES "which schedules are due now" from
the DB and acts. Schedule state lives ONLY in datoms, so it survives a process
crash/restart — the ticker simply re-derives due-ness next tick. There is no
in-memory registry of pending jobs to lose.

This is exactly the shape that an npm cron *scheduler* breaks. A scheduler holds
each job as a live JS timer + in-memory closure; on crash those vanish, and you
now have two sources of truth (the DB rows + the library's timer table) that
must be reconciled on every boot. That is the bifurcated-architecture
anti-pattern the reactive-context doctrine forbids. So the ONLY library role
worth even considering is a PURE PARSER feeding the existing ticker — not a
scheduler.

## Options compared

### A. `wrap-lib` — add an npm cron library

Ranked for the only viable role (PURE parser feeding seon's ticker; schedulers
disqualified by the constraint above):

| Library | Pure parser (no timer)? | IANA tz + DST | `@nicknames` / named fields | Deps | Shape fit |
|---|---|---|---|---|---|
| **croner** | YES — `new Cron(p)` with NO callback is a queryable pattern object (`.nextRun(d)`, `.nextRuns(n,d)`, `.previousRun()`) | YES, built-in (zero-dep minitz) | YES (`@daily`, `L`, names) | **zero** (pure JS; Node/Deno/browser/CLJS) | best of the libs |
| **cron-parser** | YES — `parse(expr,{tz,currentDate})` → `.next()/.prev()` iterator; pure, no scheduler | YES (luxon-backed in current majors) | YES | pulls **luxon** | good, but a dep |
| **`cron` (kelektiv)** | partial — `CronJob` is a scheduler; `.nextDate(s)` exists but the API is built around a callback + job state | YES (luxon) | partial | luxon | wrong-ish shape |
| **node-cron** | NO — `cron.schedule(expr, fn)`, owns timers; pattern-validate only, no next-fire API | tz option, scheduler-only | limited | small | wrong shape |
| **node-schedule** | NO — `RecurrenceRule` + cron, owns long-timeouts + in-memory jobs | yes | object-based | long-timeout | wrong shape |

If we were forced to add a lib, **croner** wins (zero-dep, pure-queryable,
built-in tz/DST, runs in CLJS unchanged). `cron-parser` is the most "standard"
but drags luxon. **But the meta-point stands:** adding either replaces a working
in-repo capability with an opaque dep that does NOT render into agent context as
code-as-data, and duplicates timezone handling the pod already does natively.

### B. `build-fresh` — write a new cron engine

Already done. The floor's `parse`/`due?`/`next-fire-at` IS a from-scratch pure
CLJS engine, live-proven, and — crucially for seon — **code-as-data**: it renders
into agent context, the agent can read/understand/modify it, and it carries zero
JS-interop opacity. Writing a *second* one would be a textbook "don't be a
dumbass" duplicate. Nothing to build here; the engine exists.

### C. `thin-wrap-existing-seon` — the verb over the existing floor (CHOSEN, primary)

Build `my.schedule` as a thin editable wrapper (`:toolkit-seed` origin, per the
catalog's two-tier model) that:

- validates a cron via `seon.agent.schedule/parse` (errors-are-values),
- transacts a `:seon.agent.schedule` entity onto the ALS agent's
  `:seon.agent/schedules`,
- reads the agent's schedules back for `list`,
- retracts one for `cancel!`.

Zero new engine code, zero new deps. This is the bulk of the deliverable.

### D. native-`Intl` tz fix (the HYBRID's second, optional half)

The floor matches in HOST-LOCAL time (`.getHours()`/`.getDate()`/… on a
`js/Date`) and explicitly does NOT honor `:seon.agent.schedule/timezone`
(flagged in the floor docstring). For a single-user pod on the user's OWN
machine, host-local === user tz, so this is CORRECT today. It only bites when the
wire-server/pod moves to a different-tz host (the dual-track JVM-in-cloud
future): "8am every morning" would fire in the server's tz.

The fix needs **no library** — the pod ALREADY does timezone natively with
`Intl.DateTimeFormat`:

- `seon.agent.schedule/host-timezone` → `Intl.DateTimeFormat().resolvedOptions().timeZone`
- `seon.ctx.cljs:328` and `seon.ctx.transcript.cljs:308` both extract tz-correct
  wall-clock fields via `Intl`/`toLocaleString(..., {:timeZone tz})`.

In `matches?`/`next-fire-at`, replace the host-local `.getMinutes/.getHours/
.getDate/.getMonth/.getDay` reads with fields extracted IN THE SCHEDULE'S TZ via
`(.formatToParts (js/Intl.DateTimeFormat. nil #js {:timeZone tz :hour12 false
:minute "2-digit" :hour "2-digit" :day "2-digit" :month "2-digit"
:weekday "short"}) d)`. `Intl` is DST-aware, so this is DST-safe. This is an
IN-PLACE edit to the existing pure matcher (NOT a `*-v2`), gated behind the
schedule's `:timezone`. **Deferrable** — it changes nothing for the
single-user-on-own-machine case that ships first.

`@js-joda/timezone` IS in `package.json`, but it is NOT required by any CLJS pod
namespace (only the `.clj` JVM-track files use real `java.time`). Native `Intl`
is the pod's established tz mechanism — use it, don't pull js-joda into the pod
just for this.

## Recommended agent-facing API (map-in / map-out, composable)

Aligned to the catalog's four shared shapes: **RESULT** (own-ns `ok?` +
`:seon.error/*`, never throws), **ITEMS** (`:seon.items/*` self-describing
vector), **REF** (`:seon.agent.schedule/id` addresses a schedule and threads
straight into `cancel!`). Request fields that are the entity's stored attrs use
the floor's `:seon.agent.schedule/*` keys (the data IS schedule-entity data, ns =
keyword-ns, exactly like `my.todos/add!` takes `:seon.agent.todo/*`); the
envelope discriminator + derived echoes are the verb's own `:my.schedule/*`.

```clojure
;; NEW field: what to surface to the agent when the schedule fires.
(schema/register! :seon.agent.schedule/say :string)
(schema/register! :my.schedule/ok?           :seon.result/ok?)   ; shared discriminator shape
(schema/register! :my.schedule/next-fire-at  :inst)              ; derived echo (confirms WHEN)

;; add! — register a recurring schedule. cron validated via parse (errors are
;; values: a bad cron → ok?-false with the parse message, NEVER an instrument throw).
;; Echoes next-fire-at so the agent can immediately confirm to the human.
(schema/register! :my.schedule/add-request
  [:map
   [:seon.agent.schedule/cron     :seon.agent.schedule/cron]
   [:seon.agent.schedule/say      {:optional true} :seon.agent.schedule/say]
   [:seon.agent.schedule/fn       {:optional true} :seon.agent.schedule/fn]       ; "run THIS when due" (lands w/ exec routing)
   [:seon.agent.schedule/timezone {:optional true} :seon.agent.schedule/timezone]])
(defn ^:async add! [m]
  #_"{:seon.agent.schedule/cron \"0 8 * * *\" :seon.agent.schedule/say \"morning check-in\"}
     -> {:my.schedule/ok? true :seon.agent.schedule/id <id> :my.schedule/next-fire-at <inst>}
      | {:my.schedule/ok? false :seon.error/message …}")

;; list — your schedules → the ITEMS envelope; each item is SELF-DESCRIBING and
;; carries :seon.agent.schedule/id so it threads straight into cancel!.
(defn list [m]
  #_"{} (owner-scoped to the ALS agent) ->
     {:my.schedule/ok? true
      :seon.items/items [{:seon.agent.schedule/id <id> :seon.agent.schedule/cron \"0 8 * * *\"
                          :seon.agent.schedule/say \"…\" :my.schedule/next-fire-at <inst>} …]
      :seon.items/count <int> :seon.items/truncated? false}")

;; cancel! — REF in, RESULT out; retract one schedule (component ref drops from
;; :seon.agent/schedules). Idempotent; unknown id → legible ok?-false.
(defn ^:async cancel! [m]
  #_"{:seon.agent.schedule/id <id>} -> {:my.schedule/ok? true :seon.agent.schedule/id <id>}")

;; remind! — sugar over add!: the natural-language verb. Recurring (cron) OR
;; one-shot (:at). Wake + surface :say works TODAY.
(defn ^:async remind! [m]
  #_"{:seon.agent.schedule/say \"stretch\" :seon.agent.schedule/cron \"0 8 * * *\"}  ; every morning
     {:seon.agent.schedule/say \"check the build\" :seon.agent.schedule/at <inst>}    ; one-shot (see gotcha 3)
     -> same envelope as add!")
```

### How it threads (every arrow is total, no reshape)

```clojure
;; list → filter → cancel!  (ITEMS → REF → RESULT)
(->> (list {})
     :seon.items/items
     (filter #(= "0 8 * * *" (:seon.agent.schedule/cron %)))
     (map cancel!))                       ; item carries the id; feeds cancel! directly

;; add! → confirm to the human  (RESULT → REF + derived echo)
(let [{:keys [:my.schedule/ok? :seon.agent.schedule/id :my.schedule/next-fire-at]}
      (await (add! {:seon.agent.schedule/cron "0 8 * * *"
                    :seon.agent.schedule/say  "morning check-in"}))]
  ;; next-fire-at lets the agent say "set — next fires 2026-06-27T08:00"
  )
```

## Gotchas (carry into the build)

1. **Timezone matching (the headline gap).** Floor matches in HOST-LOCAL time;
   `:timezone` is stored but not honored. CORRECT for the single-user-on-own-
   machine case shipping first; breaks "8am every morning" only when the host tz
   ≠ user tz (cloud dual-track). Fix native + in-place via `Intl.formatToParts`
   (option D) — no dep, DST-safe. Deferrable.
2. **`:fn` exec is DEFERRED.** `:seon.agent.schedule/fn` is stored but NOT run in
   the agent sandbox yet — that needs the one-exec-service routing (floor flags
   it ~L224-228). `say`/wake works now; the `:fn` path is forward-compatible —
   ship the verb shape, the fn-exec lands later with no API change.
3. **One-shot ("do X at T") needs a small floor addition.** The floor is
   cron-only (cron recurs). "Every morning" works today; a TRUE one-shot
   ("remind me at 3pm tomorrow", "check in an hour") wants a
   `:seon.agent.schedule/at :inst` field + a one-shot branch in
   `fire-due-schedules!` (`due?` when `now >= at` AND not-yet-fired, then
   self-retract). ~10 lines on the floor. Spec `:at` in `remind!` now; build the
   one-shot fire-path in the same unit OR defer to a follow-up. (Avoid faking it
   with a yearly-recurring cron at that minute — it would re-fire annually.)
4. **`:say` must actually SURFACE on wake.** Firing today opens+drives a
   `:schedule` run, but nothing injects the `:say` into the woken run's context —
   the agent would wake "for no visible reason." Recommended reactive-context
   wiring (no new stored notification): set the run's
   `:seon.agent.run/cause` (an existing optional `:seon.db/ref`,
   `run.cljs:210`) to the firing schedule entity, then add a render section that
   DERIVES the cause-schedule's `:say` into context. Self-healing: nothing to
   clear. Flag this to the render/run-open owner — it is the one wiring gap that
   makes `remind!` USEFUL rather than just a silent wake.
5. **Minute granularity only.** 5-field cron + minute-scan ⇒ "in 30 seconds" is
   inexpressible; latency is bounded by `SEON_TICK_MS`.
6. **Idle-gated, no misfire catch-up.** A schedule fires only when the agent is
   `:idle`; an 8am fire while the agent is busy is SKIPPED for that minute (not
   queued/back-filled). Usually fine for a personal assistant; note it. The
   double-fire guard is per-AGENT per-minute, so at most one schedule fires per
   agent per minute (serial runs).
7. **`next-fire-at` can be nil.** An impossible cron (e.g. day 30 of Feb) scans
   the full ~366-day window then returns nil. `add!` should treat a nil
   `next-fire-at` as a warning ("this cron never fires") rather than silently
   storing a dead schedule.
8. **Instrumentation.** Every verb is a public fn with a `:malli/schema`
   map-in/map-out; register the request/response schemas. `add!`/`cancel!`/
   `remind!` are `^:async` (they `await` `transact!`); `list` is sync (pure DB
   read).
9. **Origin = `:toolkit-seed`, not `:core-seed`.** `my.schedule` is an OWNED,
   editable wrapper (the agent can tweak it); the floor `seon.agent.schedule`
   stays `:core-seed`-guarded. Per the catalog two-tier model.

## Sources

- `src/seon/agent/schedule.cljs` — the floor: entity schema, pure
  `parse`/`due?`/`next-fire-at`, `fire-due-schedules!`. (read in full)
- `src/seon/agent/loop.cljs` ~L469-537 — the ONE ticker (`run-tick!`,
  `install-ticker!`, `setInterval` per `SEON_TICK_MS`).
- `src/seon/agent/run.cljs` ~L206-245 — `open-run!` (CAS idle→running,
  `:seon.agent.run/trigger :schedule`, optional `:seon.agent.run/cause` ref).
- `src/seon/agent.cljs:96` — `:seon.agent/schedules` (`:seon.db/component` ref
  vector).
- `src/seon/ctx.cljs:328`, `src/seon/ctx/transcript.cljs:308` — the pod's
  established native `Intl.DateTimeFormat` / `toLocaleString({:timeZone tz})`
  timezone idiom (the basis for option D, no new dep).
- `package.json` — `@js-joda/*` present but NOT required by any CLJS pod ns
  (JVM-track / dev only); no cron/scheduling lib installed.
- `docs/prds/agent-fsm/toolkit-catalog.md` — the `my.schedule` catalog entry +
  the four shared shapes (PATH/REF/ITEMS/RESULT, map-in/map-out, errors-as-
  values) + the two-tier `:toolkit-seed` origin model.
- npm cron libraries (`croner`, `cron-parser`, `cron`, `node-cron`,
  `node-schedule`) — evaluated for the pure-parser role; all rejected (scheduler
  shape mismatch, or opaque-dep vs the in-repo code-as-data engine). Knowledge
  cutoff Jan 2026; `agy` web-research call timed out, comparison drawn from
  library knowledge + the architectural constraint.
