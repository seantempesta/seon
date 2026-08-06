---
type: research
status: complete
tags: [research, database, architecture]
---

# Bespoke reactive machinery sweep — fold into seon.reactive (2026-07-20)

Owner ruling: `seon.reactive` owns reactive reads — registration, one active
computation, newest pending database value, writer interest replacement,
equality suppression, and final-consumer release. The route-collapse design
([[route-authority-collapse-2026-07-20]] §4) found `seon.web.router/attach!`
hand-rolling a listen/settle/coalesce loop. This sweep exhaustively inventories
EVERY bespoke reactive loop outside `seon.reactive` so each can be folded into
the one mechanism or documented as a legitimately different concern.

Scope: `src/seon/**` and `src/my/**` on branch
`codex/runtime-reliability-refactor`. `src/seon/db/**` and `seon.db`'s own
internals are exempt (the reactive mechanism is built ON the listener bus; the
bus itself is not a duplicate).

## Method

Exact sweeps run (ripgrep over `*.clj{,s,c}`):

| Pattern | Hunts |
|---|---|
| `rg -n "listen!"` | direct `seon.db/listen!`/`unlisten!` consumers |
| `rg -n "setTimeout\|setInterval\|js/setTimeout\|js/setInterval"` | hand-rolled settle/debounce/coalesce timers and polling loops |
| `rg -n "reactive/observe!\|reactive/unobserve!\|observe!"` | existing correct consumers (baseline) |
| `rg -n ":db-after"` | transaction-report consumers that re-derive views |
| `rg -n "!cache\|last-rendered\|prev-value\|dedup\|not= @\|= @!\|swap!.*cache"` | second equality/dedup caches guarding re-renders |
| `rg -n "settle\|coalesc\|debounce\|poll\|interval"` per candidate ns | settle constants and timer loops near database change |
| `rg -n "listen\|Thread/sleep\|schedule\|poll\|timer"` over `embed.clj`, `embed/preflight.clj` | JVM-side loops |

Every hit is classified in the findings table (confirmed duplication) or the
exhaustive-coverage appendix (legitimate non-reactive use), so the sweep is
provably complete over these patterns.

Existing correct `seon.reactive` consumers (baseline, no action):
`seon.web.datastar/observe-connection!` (`src/seon/web/datastar.cljs:431-445`,
release at `:775`) and `seon.ai.generate-code/observe-root!`
(`src/seon/ai/generate_code.cljs:136-155`).

## Findings table

| # | Site | What it hand-rolls | Class | Size | Risk |
|---|---|---|---|---|---|
| 1 | `src/seon/web/router.cljs:332-436` (`reconcile-cache!`, `refresh-routes!`, `settle-routes!`, `attach!`, `detach!`) | listen + owner-identity guard + desired/accepted settle loop + cache-key equality suppression | CONFIRMED — already designed away by route-collapse §4 | ~105 lines deleted | medium (boot ordering) |
| 2 | `src/seon/client.cljs:344-539` (`refresh-runtime-advertisement!`, `runtime-advertisement-event!`, `attach-runtime-advertisement-owner!`, `attach-runtime-advertisement!`, `detach-runtime-advertisement!`) + `reconcile-agent-runtimes!` (`:407-441`) | listen + owner guard + attach-race promise + freshness re-read (`(= database latest)`) + initial-read-after-listen dance | CONFIRMED (derivation half); reconciliation half is a side-effecting consumer that can ride the same registration's notify | ~150 lines → one `observe!` + one notify fn | medium-high (resume side effects, start/stop ordering) |
| 3 | `src/seon/web/serve.cljs:1223-1290` (`handle-agent-run!` completion loop; predicates `latest-run-start-ms` `:492`, `task-turns-settled?` `:513`) | 1500 ms polling loop re-querying `db/db` + derive-state + turn settlement until done/timeout | CONFIRMED — event-driven registration replaces the timer poll | ~45 lines of poll scaffolding | low-medium (request-scoped) |
| 4 | `src/seon/web/reactive/call.cljs:14` docstring | describes the feed as "(`listen!` → render → SSE push)" — the feed observes through `seon.reactive` now | doc drift only | 1 line | none |
| 5 | `seon.reactive/close!` (`src/seon/reactive.cljs:548`) has NO caller in `src/` | shutdown does not release reactive registrations through the owner; today release relies on each consumer's `unobserve!` (socket close) plus `db/close-session!` | gap observation, not duplication | 1 call site to add in `drain-runtime-owners!` | low |

No second equality/dedup cache guarding re-renders was found anywhere outside
`seon.reactive` (finding-class 4 of the mission is empty; the cache-pattern
sweep hits are all query-`:with`-dedup prose, the exempt `seon.db` session
cache, and the search path cache — appendix).

## Finding 1 — router attach! (known; confirming scope)

`attach!` installs one `db/listen!` with the route query, and `refresh-routes!`
→ `reconcile-cache!` → `settle-routes!` re-derives the reitit ring-handler per
commit with its own owner-identity token, desired-db/accepted-db settle loop,
and `cache-key` equality suppression — every one of these is a re-implementation
of a `seon.reactive` registration responsibility (newest pending database
value, one active computation, equality suppression, release).

- Replacement: one `reactive/observe!` with `::reactive/key ::routes`,
  `compute` = route-projection query returning `{projection}` under
  `db/with-read-evidence`, `notify` = swap the compiled ring-handler when the
  projection differs (reactive already suppresses equal values, so the
  `cache-key` comparison collapses to config-identity only).
- Behavior to preserve: the awaited first value before HTTP admission
  (`serve.cljs:1723` awaits `attach!`; `observe!` resolves after the first
  delivery, same contract); hot-reload idempotency (stable key + consumer key
  replaces); rebuild on config `install!` stays a local swap.
- Ordering/settle: route datoms change rarely; the default
  `:seon.config/reactive-settle-ms` + max-latency bound is acceptable.
- Owner: route-authority-collapse (stage 4). This sweep only confirms the
  classification; the full design lives in
  [[route-authority-collapse-2026-07-20]] §4.

## Finding 2 — client runtime advertisement + runtime reconciliation

`src/seon/client.cljs:344-539`. One `db/listen!` (`::runtime-advertisement`,
datom patterns `:seon.agent/id`, `:seon.agent/terminated-at`,
`:seon.agent.runtime/wake?`, `:seon.agent.run/paused-at`) fans out to two
halves:

1. **`refresh-runtime-advertisement!` (`:344-379`) — a reactive read.** It
   derives `resumable-agent-ids` at the event's `db-after`, then re-reads
   `db/db` and only accepts the projection when `(= database latest)` — a
   hand-rolled newest-pending-wins. Plus `attach-runtime-advertisement-owner!`
   (`:455-499`): owner-identity token, attach-race promise
   (`::advertisement-attaching`), unlisten-on-lost-race, initial read after
   listen with an "event may win the race" comment — all registration
   lifecycle that `observe!` owns (first consumer installs interest, initial
   evaluation, later consumers get the current value, `unobserve!` releases).
2. **`reconcile-agent-runtimes!` (`:407-441`) — a side-effecting consumer.**
   It reads the event's `tx-data` to find touched agents/runs and calls
   `agent-runtime/resume!` per id. As written it is event-consuming, but the
   effect is idempotent reconciliation ("resume every currently-resumable
   agent this pod should host"), which is exactly a derivation over current
   facts: the same computed resumable-id set can drive it without reading
   `tx-data`.

- Replacement: one `observe!` with `::reactive/key ::runtime-advertisement`,
  `compute` = `(agent/resumable-agent-ids! {::db/db database})` under read
  evidence, `notify` = `(fn [ids] (swap! !state assoc ::resumable-agent-ids
  ids) (run! resume-if-unhosted! ids))`. `acquire-resumable-agent-ids!`
  awaits the registration's first value (the `observe!` return contract);
  `detach-runtime-advertisement!` becomes `unobserve!`.
- Behavior to preserve: `resume!` idempotency against already-hosted runtimes
  (it must be a no-op re-resume — verify before folding, since today
  reconciliation only fires for entities in the commit while the fold fires
  for the whole current set on every relevant change); the datom-pattern
  narrowing becomes Datahike read-evidence interest derived from the compute's
  actual query (strictly more precise); teardown ordering in
  `drain-runtime-owners!` (`:2538-2565` — advertisement detaches AFTER
  execution hosts stop).
- Settle: wake latency matters — resume after a pause/unpause should not wait
  a long settle. The default `reactive-settle-ms` applies; if measured resume
  latency regresses, pass a small `::reactive/settle-ms` selector rather than
  reintroducing a bespoke path.
- Risk: medium-high. This is boot/stop machinery with side effects; needs a
  live proof (pod restart resumes agents; hot reload does not double-resume).
- Estimated size: −150 lines bespoke, +~30 lines registration/notify.

## Finding 3 — POST /agents/run completion poll

`src/seon/web/serve.cljs:1223-1290` (inside `handle-agent-run!`): a `loop`
that sleeps 1500 ms (`js/setTimeout resolve 1500`), re-acquires `db/db`, and
re-runs three reads (`derive/derive-state`, `latest-run-start-ms`,
`task-turns-settled?`) until `done?` or `timeout?`. This is a timer poll over
database facts where a registration is event-driven: nothing advances the
predicate except a committed transaction.

- Replacement: request-scoped `observe!` with key
  `[::agent-run-settled aid injected-at]`, consumer key = a request uuid,
  `compute` = the same three reads under `db/with-read-evidence` returning
  `{state latest-start settled?}`, `notify` = resolve a completion promise
  when the done predicate holds. The wall-clock `timeout-ms` stays a plain
  `js/setTimeout` (a genuine timer concern, like the loop's deadline
  machinery) that resolves the same promise with `::timed-out`. On either
  resolution, `unobserve!` in a `finally`, then keep today's timeout path
  unchanged (close the current run `:superseded`, read the final database).
- Behavior to preserve: the exact done predicate (`:idle` AND
  `latest-start >= injected-at` AND all opened turns terminal); the timeout
  close; the truthful final-database re-read after completion.
- Won behavior: sub-1500 ms completion latency (settle-bounded), no idle
  re-query churn during long runs.
- Risk: low-medium — request-scoped; the only hazard is leaking a
  registration on an error exit, so release must be structural (finally).
- Owner suggestion: stage 5 (deletions and small unifications), or ride the
  stage-4 serve refactor if `handle-agent-run!` is already being reshaped to
  Ring handlers there.
- Estimated size: −45 lines poll scaffolding, +~30 lines registration.

## Finding 4 — stale feed docstring

`src/seon/web/reactive/call.cljs:14`: "the existing reactive feed (`listen!`
→ render → SSE push)". The feed path is `reactive/observe!` → render → SSE
push since datastar folded in. One-line doc fix; ride whichever stage next
touches the file (stage 2 pod-term rename sweep is fine).

## Finding 5 — `reactive/close!` has no caller

`seon.reactive/close!` (releases every registration, timer, and interest) is
exported but never called; `seon.client/drain-runtime-owners!`
(`client.cljs:2538+`) drains ticker, wake triggers, agent work, execution
hosts, and the advertisement listener, then closes the database session —
reactive registrations are only released transitively (datastar sockets
close → `unobserve!`). After findings 1-3 add non-socket registrations,
shutdown should call `reactive/close!` explicitly (after HTTP stop, before
`db/close-session!`) so no interest survives into session teardown. Ride the
finding-2 fold.

## Non-findings (mission classes with zero hits)

- **Second equality/dedup caches (class 4): none.** Datastar shares one
  render per subscription and relies on reactive's
  `::equal-notifications-suppressed`; the backpressure "newest pending
  event" atom (`datastar.cljs:745`) is per-socket write coalescing below the
  reactive boundary (socket drain), not a re-render guard.
- **Manual re-render pokes after `transact!` (class 5): none.** Writers write;
  the datastar feed and generate-code observer close the loop through
  `seon.reactive`. `my.canvas`/call path transacts and lets the feed react
  (`web/reactive/call.cljs` invoke → transact → observed feed).

## Exhaustive-coverage appendix — every remaining hit, classified legitimate

Direct `listen!` consumers outside `seon.db`/`seon.reactive`:

- `src/seon/agent/loop.cljs:1127` `install-wake-trigger!` — event DELIVERY,
  not view derivation: wakes an agent on a message-to-me datom and drives the
  open run on the protocol resynchronization event; the handler consumes the
  event's datoms once. Stable per-agent key already gives hot-reload
  replacement. Legitimate. (If it ever grows settle/coalesce logic, revisit.)
- `src/seon/web/router.cljs:401`, `src/seon/client.cljs:463` — findings 1-2.

Timer/interval sites (all `js/setTimeout`/`js/setInterval` hits):

- `src/seon/agent/loop.cljs:1237` `install-ticker!` — THE one wall-clock
  ticker (deadlines/schedules are passive facts; something must check).
  Legitimate wake owner by design.
- `src/seon/agent/loop.cljs:731,760,782,828,851,985` — turn/run scheduling
  yields and bounded waits inside the loop FSM. Not reactive derivation.
- `src/seon/web/datastar.cljs:346` — SSE proxy keep-alive heartbeat (inert
  comment frames). Transport concern.
- `src/seon/client.cljs:547` `start-heartbeat!` — event-loop keep-open log
  tick. Process lifecycle.
- `src/seon/client.cljs:2388` `next-quiescence-observation!` +
  `drain-agent-work!` (`:2450-2510`) — 10 ms-yield SHUTDOWN drain awaiting
  terminal turn statuses, running after wake triggers are uninstalled and
  while the runtime is being torn down; it also closes runs (writes). A
  registration during teardown would race its own release; deliberate poll,
  bounded by `quiescence-deadline`. Legitimate. (If finding 3's registration
  pattern proves clean, this is the ONLY remaining poll and could be
  reconsidered later; not part of this fold.)
- `src/seon/web/serve.cljs:1225` — finding 3.
- `src/seon/execution/host.cljs:224,368,422,582,701,743` — child-process
  idle/ready/kill-grace/invocation timeouts. Process supervision.
- `src/seon/execution.cljs:924,1100` — invocation timeout; deferred process
  exit. Process supervision.
- `src/seon/subprocess.cljs:182,190` — SIGTERM→SIGKILL escalation and
  subprocess timeout. Process supervision.
- `src/seon/eval.cljs:253` — LLM/eval timeout race. Timeout concern.
- `src/seon/ai/diffusiongemma.cljs:431` — provider request timeout/abort.
- `src/seon/retry.cljs:140` — backoff sleep primitive for the one retry
  authority.
- `src/seon/agent/web/internal.cljs:466,637,733` — fetch abort timers.
- `src/seon/test/runner.cljs:398` — test harness timeout.
- `src/seon/db.cljs:860`, `src/seon/db/transport/uds.cljs:285,325,537` —
  `seon.db` internals (reconnect backoff, deadline expiry). Exempt.

`:db-after` consumers not already covered: `src/seon/agent/run.cljs:710`
(uses the report's db-after to notify an outcome once — transaction-report
consumption at the write site, not a standing view);
`src/seon/runtime/recovery.cljs:419` and `src/seon/state.cljs:457` (shape
predicates on values, no listener); `src/seon/agent/loop.cljs:876` (the wake
handler above).

Cache-pattern hits: `seon.db` session cache (`db.cljs:284,343,730,870,1255`
— exempt authority cache); `src/seon/agent/search/internal.cljs:321`
(per-invocation grep path cache); `src/seon/eval.cljs:338,407` +
`src/seon/repl.cljs:97-102` (compiler/init-version process-local state);
everything else is prose about datalog `:with` dedup. None guards a
re-render.

Checked clean (no listener, timer, poll, or cache): `seon.warn` (pure check
queries), `seon.web.debug` (renders through the datastar feed registry),
`seon.render.canvas`, `seon.agent.ctx.*` (blocks are pure renders),
`src/my/**` (`my.canvas` transacts; the observed feed reacts),
`seon.repl.autocomplete` (pure export pipeline), `seon.embed.cljs` (one
query); `seon.embed.clj`/`embed/preflight.clj` are JVM writer-side with only
a retry `Thread/sleep` (`embed.clj:675`) — no pod reactive runtime exists
there.

## Ordered fold-in plan

1. **Router attach! → registration** — owned by the route-authority collapse
   (stage 4); design complete in [[route-authority-collapse-2026-07-20]] §4.
   Nothing new from this sweep.
2. **`handle-agent-run!` poll → request-scoped registration** (finding 3) —
   stage 5, or fold into the stage-4 serve handler refactor if the same
   function is already being reshaped there. Independent of 1.
3. **Client runtime advertisement + reconciliation → one registration**
   (finding 2) — stage 5; do it AFTER 1 lands so boot has exactly one
   attach-ordering pattern to copy, and verify `resume!` idempotency first.
   Add the `reactive/close!` shutdown call (finding 5) in the same unit.
4. **Docstring fix** (finding 4) — ride any touching commit.

Each fold's acceptance: the bespoke listen/timer path is DELETED in the same
commit (one mechanism); live proof = the behavior the loop guarded (routes
re-derive on a route transaction; a restarted pod resumes its agents; a
`/agents/run` request returns on the settling commit, not a poll tick) plus
`reactive/measurements` showing the new registration active and released.
