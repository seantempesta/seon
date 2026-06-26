---
type: prd
status: draft
tags: [prd, agent, schema, flow]
---

# Agent Runtime Spec — the coherent data model (2026-06-25)

The single, fully-namespaced contract for the agent record + its run / turn /
trigger / schedule / context model. Every concept is data; the loop is a
function of that data; one periodic ticker is the only active piece. Names are
industry-grounded (Temporal durable execution + k8s Jobs + Akka actors —
[[loop-cycle-naming-precedent-2026-06-25]]) and reconciled with the data-model
audit ([[agent-data-model-audit-2026-06-25]]) and the cycle/timestamp design.

Supersedes the scattered `:seon.agent.loop/*` wake-token model. Lock target:
this is the contract the loop, the render, cron, and the watchdog all read.

## The model in one paragraph

An agent is normally **`:idle`** (asleep). A **trigger** — an inbound
**message** or a due **schedule** (cron) — opens a **run**: a bounded unit of
work. While the run is open the agent is **`:running`** and the loop executes
**turns** until a bound is hit. A run has **two independent bounds** (whichever
fires first closes it): a **work-quantity** bound (`turn-limit`, a bumpable
count) and a **wall-clock** bound (`deadline`, an absolute instant). New
messages **renew the lease** (bump both bounds — the sliding window). The
clock bound is enforced **externally** by one periodic **ticker** (a stalled
LLM burns the clock and can't self-detect). The **run-id is a fencing token**:
a write from a superseded/timed-out run is rejected. Everything else — status,
liveness, history, fleet view — is a **query** over the bitemporal DB.

## Namespace plan (key namespace = code namespace that manages it)

| namespace | owns (`:ns/*` keys) | manages (fns colocated here) |
|---|---|---|
| `seon.agent` | the agent entity + its config/pointers | mint, `set-state!`, `state-snapshot` (fingerprint), `set-purpose!`, `add-section!`/`remove-section!` |
| `seon.agent.run` *(NEW)* | the **run** entity + its bounds/lifecycle | `open-run!`, `close-run!`, `renew!` (lease bump), `beat!`, `turn-limit-reached?`, `deadline-passed?`, `current-run`, `owns-run?` (fencing), `close-overdue-runs!` (watchdog action) |
| `seon.agent.schedule` *(NEW)* | the **schedule** (cron) entity | `parse`, `next-fire-at`, `due?`, `fire-due-schedules!` |
| `seon.agent.loop` | *(no data — the driver)* | `run-loop!`, `wake-handler` (message trigger → `open-run!`), `install-wake-trigger!`, `install-ticker!` (the one timer → schedule + watchdog) |
| `seon.agent.turn` | the **turn** entity | `open-turn!`, `close-turn!`, `run-turn!` |
| `seon.agent.message` | the **message** entity | `message!`, `inbound?`, hop guard |
| `seon.agent.todo` | the **todo** entity | `add!`, `complete!`, `list-open` |
| `seon.ctx` | the **section** shape + composer | `render-context`, the composer |
| `seon.render.live-tile` | the **tile** | tile resolution/render |

(There is no `session` — a **run** is the wake-episode grouping. Runs link back
to the agent via `:seon.agent.run/agent`; turns to their run via
`:seon.agent.turn/run`.)

## Schema — fully-namespaced, by owning namespace

### `seon.agent` — the agent entity

```clojure
(schema/register! :seon.agent/id      [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent/run     :seon.db/ref)   ; → the CURRENT run (fencing pointer; spine of derived state)
(schema/register! :seon.agent/terminated-at :inst)    ; presence ⇒ derived state :terminated
(schema/register! :seon.agent/state   [:enum :idle :running :paused :terminated]) ; DERIVED shape — computed, NEVER transacted
(schema/register! :seon.agent/sections [:vector {:seon.db/component true} :seon.db/ref]) ; the agent's OWN ctx sections (was :seon.agent/ctx)
(schema/register! :seon.agent/schedules [:vector {:seon.db/component true} :seon.db/ref]) ; self-managed cron maps (0..N)
(schema/register! :seon.agent/purpose  :string)        ; optional; renders into context
(schema/register! :seon.agent/parent   :seon.db/ref)   ; optional; aspirational (no writer until spawn)
(schema/register! :seon.agent/default-turn-limit  :int) ; optional; seeds a new run's turn-limit (else global 20)
(schema/register! :seon.agent/default-deadline-ms  :int) ; optional; seeds a new run's deadline (else global)
;; tile wiring lives in seon.render.live-tile:
(schema/register! :seon.render.live-tile/content ...)
```

**State is DERIVED, never stored** (the data primitives ARE the state):
`:terminated` if `:seon.agent/terminated-at` exists; else `:idle` if no open
run; else `:paused` if the open run carries `:seon.agent.run/paused-at`; else
`:running`. `:idle` is the only triggerable state. The transition table below
maps each event to the primitive MUTATION (open/close/pause a run, set
`terminated-at`); the state label is just their projection.

### `seon.agent.run` — the run entity (NEW)

```clojure
(schema/register! :seon.agent.run/id     [:and {:seon.db/identity true} :seon.db/id]) ; the FENCING TOKEN
(schema/register! :seon.agent.run/agent  :seon.db/ref)   ; back-ref → agent (fleet/history queries)
(schema/register! :seon.agent.run/started-at :inst)      ; the wake time
(schema/register! :seon.agent.run/trigger    [:enum :message :schedule])
(schema/register! :seon.agent.run/cause      :seon.db/ref) ; → the message (when :message)
(schema/register! :seon.agent.run/turn-limit :int)        ; WORK-QUANTITY bound (bumpable)
(schema/register! :seon.agent.run/deadline   :inst)       ; WALL-CLOCK bound (absolute)
(schema/register! :seon.agent.run/last-beat-at :inst)     ; heartbeat (liveness; written per turn)
(schema/register! :seon.agent.run/status     [:enum :open :closed])
(schema/register! :seon.agent.run/closed-reason
                  [:enum :completed :waited :turn-limit :deadline-exceeded
                         :terminated :superseded :error])  ; present iff :closed
```

The two bounds are deliberately separate attrs (k8s `backoffLimit` count vs
`activeDeadlineSeconds` clock): **whichever is hit first closes the run.**

### `seon.agent.schedule` — self-managed cron maps (NEW)

The agent owns a vector of schedule maps (`:seon.agent/schedules`) and
adds/removes them by transacting on its own record — each map carries its cron
expression AND the **fn to call** when due (a qualified symbol, resolved like
tile-content / section-ai — code-as-data). This generalizes cron from "wake me"
to "at this schedule, run this fn."

```clojure
(schema/register! :seon.agent.schedule/id       [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.schedule/cron     :string)   ; 5-field cron
(schema/register! :seon.agent.schedule/fn       :symbol)   ; qualified fn to invoke when due (code-as-data)
(schema/register! :seon.agent.schedule/timezone :string)   ; IANA tz; default host tz
(schema/register! :seon.agent.schedule/concurrency-policy
                  [:enum :forbid :allow])   ; default :forbid (single-agent: don't open a 2nd run)
```

(The mechanism that FIRES a due schedule — the ticker vs. a flow process — is
held pending the execution-mechanism research; the data shape above stands
either way.)

### `seon.agent.turn` — the turn (rename only)

`:seon.agent.turn/run :seon.db/ref` **replaces** `:seon.agent.turn/wake` (turns
belong to a run). Keep: `id`, `at`, `status [:enum :running :done :error]`,
`evals`, `prompt-chars`, `prompt-file`, `llm-usage`. `llm-meta` — drop or mark
write-only (audit: never read). Plus the render twin (single-render-path wave):
`:seon.agent.turn/render-file` (the stored ai+html result) + `token-estimate`.

### Unchanged: `seon.agent.message`, `seon.agent.todo`

Message `origin [:enum :human :agent :core]` stays (cron fires a run **directly**,
not a synthetic message — so no `:cron` origin). Human inbound still auto-mints
a todo. Hop-exhausted messages are our **dead-letter** (stay as datoms, render
as a reactive warning).

## The two bounds, the lease, the heartbeat

- **`turn-limit`** (count): a new run opens with `turn-limit =
  default-turn-limit (or 20)`. **current turn = `count(turns where run = this)`
  — derived, nothing stored.** Loop stops when `turn ≥ turn-limit`.
- **`deadline`** (clock): a new run opens with `deadline = started-at +
  default-deadline-ms`. Absolute, so it survives restart and is a pure DB read.
- **Lease renewal = the sliding window:** an inbound message during an open run
  calls `run/renew!` → `turn-limit += 1` **and** pushes `deadline` out. "The
  human keeps talking" extends both bounds. Any process (orchestrator, cron)
  may renew.
- **Heartbeat:** `run/beat!` writes `last-beat-at` per turn (coarse — see
  Disadvantages). Enables fleet liveness + finer stall detection (open run with
  a stale beat = stuck, even if within deadline).

## The one ticker + fencing (the only active machinery)

The DB is passive about wall-clock: `now > deadline` is true in the world but
nothing fires until something checks. So **exactly one periodic ticker**
(`loop/install-ticker!`, wired at client boot beside `install-wake-trigger!`):
every N seconds it
1. `schedule/fire-due-schedules!` — open a `:schedule`-triggered run for each
   due schedule on an `:idle` agent (respecting `concurrency-policy`);
2. `run/close-overdue-runs!` — for each `:open` run where `now > deadline` (or
   `last-beat-at` is stale): `close-run!` with `:deadline-exceeded`, emit the
   error, reset the agent to `:idle`.

The ticker is **idempotent** (acts on db state; safe to re-run). **Fencing:**
`run/close-run!`/`renew!`/`beat!` first check `owns-run?` — the agent's current
`:seon.agent/run` must equal the run being written; a late write from a
superseded or timed-out run (different run-id) is rejected. This is the lease
hazard ("slow holder wakes after expiry and still writes"), solved for free by
the run-id already in the DB.

## Derived state — the fingerprint

`seon.agent/state-snapshot` (map-in/map-out) returns the COMPLETE state from
the record + cheap queries — every field specced, so one call fingerprints the
agent:

```clojure
;; Reference registered schemas (register once, reference everywhere); only
;; the DERIVED-only fields (no standalone attr) carry a bare base type.
(schema/register! :seon.agent/state-snapshot
  [:map
   [:seon.agent/state                              :seon.agent/state]
   [:seon.agent.run/status      {:optional true}   :seon.agent.run/status]
   [:seon.agent.run/trigger     {:optional true}   :seon.agent.run/trigger]
   [:seon.agent.run/turn-limit                     :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline    {:optional true}   :seon.agent.run/deadline]
   [:seon.agent.run/last-beat-at {:optional true}  :seon.agent.run/last-beat-at]
   [:seon.agent.run/closed-reason {:optional true} :seon.agent.run/closed-reason] ; last
   ;; derived-only (no standalone attr): bare base types
   [:seon.agent.run/turn            :int]   ; current = count(turns this run)
   [:seon.agent.run/turns-remaining :int]   ; turn-limit − turn
   [:seon.agent.run/ms-remaining    {:optional true} :int]   ; deadline − now
   [:seon.agent/total-turns         :int]   ; ever-increasing
   [:seon.agent.message/last-human-at {:optional true} :inst]
   [:seon.agent.schedule/next-fire-at {:optional true} :inst]
   [:seon.agent.todo/open-count     :int]
   [:seon.agent.message/unread-count :int]])
```

## The loop as a data-declared process (flow's good parts, no channels)

We keep the loop in `seon.agent.loop`, but model it like a `core.async.flow`
process — the parts worth stealing: **a defined initial state, one transition
function, and the whole FSM represented as data.** The imperative `while` +
scattered `cond` becomes a transition table you can read, render, and
fingerprint. (We do NOT adopt flow's channels — single-threaded in CLJS, no
parallelism; isolation is the separate worker layer below.)

**The FSM as data** (`:seon.agent.loop/transitions` — the whole machine in one
value; `event → next-state`, with the guard/effect named):

```clojure
(def transitions
  {:idle       {:trigger     :running}      ; a wake (message/schedule) opens a run
   :running    {:turn-ok     :running       ; within both bounds → another turn
                :wait        :idle           ; verb
                :complete    :idle           ; verb
                :turn-limit  :idle           ; work bound hit (clean)
                :deadline    :idle           ; clock bound hit (ticker; :deadline-exceeded)
                :superseded  :idle           ; a newer run won the fence
                :error       :idle           ; turn threw
                :pause       :paused
                :terminate   :terminated}
   :paused     {:resume      :running        ; flow's start-paused/resume — "hold, don't kill"
                :terminate   :terminated}
   :terminated {}})                          ; terminal
```

**Defined initial state:** a fresh agent boots `:idle`. A run opens at
`:running` (or `:paused`-then-`:resume`, flow-style, when minted held).

**One transition function** — `(loop/transition agent event) → effects` —
applies the table + the bound checks + the fencing, in ONE place (replacing the
spread-out conds in `run-loop!`/`wake-handler`). The driver is then trivial:

```
run-loop!(agent, run):
  loop:
    event = next-event(agent, run)   ; turn-ok | wait | complete | turn-limit
                                     ; | deadline | superseded | error | pause | terminate
    [state', effects] = transition(state, event)   ; data-driven
    apply!(effects)                  ; beat!, run-turn!, close-run!, set-state!…
    if state' is terminal-for-this-run: break
```

`next-event` derives the event from the run's data each iteration: still
`:running` and within `turn-limit` + `deadline` and `owns-run?` → `:turn-ok`
(beat + run a turn); a verb inside the turn emits `:wait`/`:complete`/
`:terminate`/`:pause`; a bound or the ticker emits `:turn-limit`/`:deadline`/
`:superseded`. The whole loop is a fold of `transition` over events — a function
of the run's data, and the machine itself is inspectable/renderable data.

## Rendering the state (both surfaces, one source)

A **run-status section** (`seon.ctx` section, ai + html twins) renders from the
run entity:
- **agent context (ai):** *"Run · woken by ‹human msg› · turn 3/20 · 9m to
  deadline · open"*
- **inspector (html):** the same, from the same data.

It folds into the single-render-path wave (the render result is itself stored
on the turn — [[single-render-path-design-2026-06-25]]). Agent-view and
debug-view agree by construction.

## What we deliberately DON'T adopt — and why (the DB advantage)

Most durable-execution machinery exists to work around the absence of a
bitemporal, reactive DB. We have one, so:

- **Continue-as-new (Temporal): NOT adopted.** It bounds history because
  Temporal *replays* full event history. We never replay — the rendered
  context is a **query over a window** (last-N / since-T / this-run); history
  accumulates as cheap deltas. Bound the **view**, not the storage.
- **External fencing/lease service (Chubby/etcd): NOT needed.** The run-id on
  the agent record IS the fence; single writer (wire-server) gives the
  ordering.
- **Heartbeat service: NOT a service** — one `last-beat-at` datom; fleet
  status = one unfiltered query, live over `listen!`.
- **Dead-letter queue / metrics pipeline / event-sourcing rebuild: free** —
  hop-exhausted-as-datoms, the tx-log IS the event stream, datahike as-of is
  native.

## Disadvantages to respect

1. **The ticker is the one irreducible timer** — DB is passive about
   wall-clock. Keep it single + idempotent.
2. **Heartbeat cadence = tx churn.** datahike WRITES are the measured
   bottleneck (~1040→324 ent/s) and every beat is retained in history forever.
   **Beat coarsely** — per turn (or only at LLM-call start), never per-second.
3. **Single writer (wire-server)** is the coordination point — the
   ticker/watchdog logic lives there (or a monitor), not per-pod.

## Open decisions (flagged, not decided unilaterally)

1. **`session` vs `run`** — RESOLVED: `run` REPLACES `session`. There is no
   `seon.agent.session`; runs link back to the agent via `:seon.agent.run/agent`,
   turns to their run via `:seon.agent.turn/run`. (Touches `turns-this-wake`'s
   join → `turns-this-run`.)
1b. **`state` stored vs derived** — RESOLVED: state is DERIVED, never stored
   (`:terminated`←`terminated-at`, `:idle`←no open run, `:paused`←`run/paused-at`,
   else `:running`). The presence/absence of primitives IS the state.
2. **`schedules`** — RESOLVED: a self-managed vector of schedule maps on the
   agent (`:seon.agent/schedules`), each carrying cron + the fn to call +
   timezone/concurrency. The firing mechanism (ticker vs flow process) is held
   pending the execution-mechanism research.
3. **`parent` / `llm-meta` / `wait-note`** — `wait-note` dropped (orphaned
   write; "why parked" is now `run/closed-reason`). `parent` kept aspirational
   (no writer until spawn). `llm-meta` write-only — drop or mark. Confirm.
4. **Heartbeat granularity** — per-turn (cheap, coarse) vs per-LLM-call-start
   (finer stall detection, more writes). Recommend per-turn to start.
5. **Deadline default** — what `default-deadline-ms` should be (and whether a
   run with no deadline is allowed, i.e. turn-limit-only). Recommend a generous
   default (e.g. 10 min) with the turn-limit as the usual stopper.

## Migration (renames, atomic)

- `:seon.agent/state` value `:active` → **`:running`**
- `:seon.agent/wake` → **`:seon.agent/run`** (id token → ref to run entity)
- `:seon.agent.turn/wake` → **`:seon.agent.turn/run`**
- `:seon.agent/max-turns-per-loop` → **`:seon.agent/default-turn-limit`** (+ the
  cycle's live `:seon.agent.run/turn-limit`); env `SEON_MAX_TURNS_PER_LOOP` →
  `SEON_DEFAULT_TURN_LIMIT`
- `:seon.agent/ctx` → **`:seon.agent/sections`**
- drop `:seon.agent/wait-note`
- new: `seon.agent.run/*`, `seon.agent.schedule/*`, `:seon.agent/run`,
  `:seon.agent/schedule`, `:seon.agent/default-*`

Fresh world via `bin/seon nuke` — no data migration (we re-seed the core), so
the renames are pure code + schema changes.
