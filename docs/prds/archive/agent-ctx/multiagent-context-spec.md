---
type: prd
status: active
tags: [prd, agent]
---

# Multi-agent context — subagent visibility, durable results, bounded spawn

Owner-ruled design (2026-07-06). Four small pieces, all built from existing
mechanisms (parent refs, run entity, messages, derived sections). Nothing new
is invented: no registry, no inbox, no subscription, no capability-grant
system. Survey grounding this spec: the multi-agent survey in this session
(spawn/`complete`/`derive-status` all exist; the gaps are visibility, durable
results, an enforced depth bound, and orphan surfacing).

## Settled rulings (do NOT re-litigate)

- **Visibility = direct children only.** An agent's subagents section shows
  the agents it spawned, not the whole subtree. Root's *dashboard*
  (`system-view` canvas) keeps showing the whole fleet — unchanged.
- **Spawn depth cap = 1.** Root (depth 0) may spawn; a spawned agent
  (depth 1) may NOT spawn. Enforced as a computed structural rule (walk
  `:seon.agent/parent` chain) inside `start!`'s own body — a config-dialable
  number, never a name list. The soft gate (spawn verbs only in root's
  home-requires) stays as-is; the body check is the hard backstop against
  full-qualified calls.
- **Durable return value = run datoms.** `:seon.agent.run/result` (capped
  string) + optional `:seon.agent.run/result-ref` (`:seon.db/ref`).
  `complete` writes the datoms unconditionally AND still sends the message to
  the parent — message = wake signal, datom = durable value.
- **Orphans: surface to root, no auto-action.** A derived root-only section
  lists live agents whose parent is terminated. No cascade-terminate, no
  reparenting — observe first.
- **Outcome routing: parent owns task outcomes.** ALL run closes that are
  outcomes (completed + turn-limit/deadline/error/no-forms/crashed) message
  the PARENT; root gets no per-run stream — its supervision is derived
  sections + the core-fault workflow. Exception: `:crashed` (wedge) ALSO
  messages root directly.
- **Wedge = `:core` fault.** The watchdog close records via
  `seon.error/record!` — wedges enter the standard triage chain; dev pod
  exits loudly on one (`:crash` dial), by design.
- **Root wedge: stay idle, no auto-rewake.** Closing the run unsticks root;
  next natural contact resumes it. Crash-loop guard = the schedule-wake
  circuit breaker (derived from the run log, no stored state).

## Piece 1 — `:seon.agent.run/result` + `result-ref` (do this first)

In `src/seon/agent/run.cljs`, register:

- `:seon.agent.run/closed-at` — `:inst`, written in EVERY close tx
  (wherever `closed-reason` is asserted, whichever reason). Needed by the
  Piece 2d breaker window (datahike `txInstant` cannot be backdated, so
  tests need an explicit close instant) and generally useful (run duration
  is derivable).
- `:seon.agent.run/result` — `:string`, the short answer or pointer
  one-liner. Same length discipline `complete` already enforces on the
  outgoing message (reuse the existing cap — do not invent a second
  constant; if the cap is inline today, hoist it once and reference it).
- `:seon.agent.run/result-ref` — `:seon.db/ref`, optional. Points at the
  stored work product (a `my.kb` source, a blob entity, a plan root, …).

In `src/seon/agent/lifecycle.cljs` `complete`:

- Request map gains optional `::result-ref` (entity id). Extend the
  registered request schema; keep map-in/map-out.
- Write `result` (and `result-ref` when given) onto the run entity in the
  same tx that closes the run (`closed-reason :completed`) — or an adjacent
  tx if the close path makes same-tx awkward; the invariant is: after
  `complete` returns, the closed run carries the result datoms.
- The message-to-parent behavior is unchanged, INCLUDING the existing
  "skip if already messaged this run" guard — but the result datoms are
  written **unconditionally** (that guard must not gate the datom).

## Piece 2 — depth-capped spawn (hard backstop in `start!`)

In `src/seon/agent.cljs`:

- `spawn-depth` — pure fn: db + agent-id → depth (root/parentless = 0,
  child = parent + 1). Walk `:seon.agent/parent` refs with a cycle guard
  (visited set; a cycle is a `:core`-fault-worthy invariant break — but the
  fn itself returns a value, never throws).
- A spawn-depth cap value, default `1`, read from config
  (`:seon.config/spawn-depth-cap` following however existing
  `seon.config` dials are plumbed — match the existing pattern exactly; if
  config plumbing is disproportionate, a def with a clear docstring is
  acceptable for now, flagged in the report).
- `start!` (and therefore `delegate!`, which goes through it) refuses when
  the CALLER's depth ≥ cap: returns the standard error ENVELOPE (match the
  envelope shape `start!`/`seon.db/transact!` already use — read the source),
  message stating the caller's depth, the cap, and that subagents may not
  spawn. Never throws into the loop.
- Home-requires are unchanged: spawn verbs stay root-only in
  `config/system.edn`. Raising the cap later = bump the dial + add the
  requires to the general agent-context.

## Piece 2b — outcome routing: ALL run outcomes go to the parent

Owner ruling (2026-07-06, revised same day): a closed run is a TASK
OUTCOME, and the task's owner is the PARENT — including the bounds the
parent itself set. Root gets no per-run outcome messages (except for its
own children, as any parent, and the wedge escalation below).

- **Every outcome messages the parent** (or the user when parentless):
  `:completed` (already built — the result), and the abnormal closes
  `:turn-limit`, `:deadline-exceeded`, `:error`, `:no-forms`, `:crashed` —
  a short message via the SAME `message!` path: child id, closed-reason,
  turn count, purpose one-liner. Failure notices use message
  `origin :agent`, `from` = the child — the loop/watchdog sends ON THE
  CHILD'S BEHALF, same authorship model as `complete`. NOT `origin :core`:
  the wake gate (`waking-inbound?`, message.cljs) deliberately excludes
  `:core`-origin messages from waking, and an outcome notice that does not
  wake the parent defeats the design. Tests must pin the wake END-TO-END
  (notice → parent run opens), not just message existence.
- **Budget closes are recoverable — say so.** `:turn-limit` /
  `:deadline-exceeded` message content includes the affordance: re-message
  the child to open a NEW run with fresh budget (its context and plan
  persist). Exhausted budget is not death; the parent decides continue vs
  accept-partial.
- **Wedges additionally escalate to root**: on `:crashed` (watchdog close),
  ALSO message root — dedup naturally when the parent IS root (one message,
  not two). Rationale: a wedge is system-health, not just task outcome.
- `:waited`, `:terminated`, and `:superseded` do NOT message — `:waited` is
  the agent parking itself (not an outcome), `:terminated` was an act BY
  someone who already knows, `:superseded` is internal plumbing.
- **Delivery must be reliable**: verify the hop derivation cannot refuse an
  outcome notice at the cap (depth-cap 1 bounds any cascade anyway); if
  hop semantics would ever drop one, report back — a lost outcome notice is
  a parked parent.
- Implementation point: wherever runs are closed with these reasons (the
  loop's bound-enforcement + error paths — find the single choke point if
  one exists; if closes are scattered, route them through one internal
  close fn first rather than sprinkling sends).
- Mid-flight remains silent by design: no progress pings, no per-turn
  notifications. Progress is derived (Piece 3) and read whenever the parent
  is awake anyway.

Tests: a child hitting turn-limit produces exactly one parent-directed
message carrying the closed-reason + continue affordance; `:waited`
produces none; a `:crashed` close with a non-root parent produces exactly
two messages (parent + root), with a root parent exactly one.

## Piece 2c — heartbeat watchdog (stale beat ⇒ `:crashed` ⇒ Piece 2b)

A WEDGED agent never closes its run — no event ever fires — so heartbeat
failure needs an observer outside the agent. Keep it ONE mechanism by
collapsing into the failure path, not a second notification system:

- A core-level watchdog in the pod: a periodic SCAN (core machinery, not an
  agent; interval and staleness threshold generous — e.g. scan every ~60s,
  stale = no `:seon.agent.run/last-beat-at` progress for several minutes;
  make both dials, match however existing core config dials are plumbed).
  Scan-based deliberately: stateless per-run (no per-run armed timers, no
  re-arming churn), everything derived from datoms each pass, so it
  survives pod restarts and catches pre-restart wedges on the first scan.
- **The scan is a pure fn of (db, now)** — `now` is an ARGUMENT, never an
  inline `(js/Date.)` in the scan/breaker core; the periodic timer is a
  thin shell that supplies real now. Tests call one scan pass directly
  with backdated datoms + an explicit now — zero timers/sleeps in tests.
- A run that has NEVER beaten (wedged before its first turn completed)
  has no `last-beat-at` — staleness falls back to
  `:seon.agent.run/started-at`. A no-beat run must not be invisible to
  the watchdog.
- Reuse the existing in-process timer plumbing (the machinery that arms
  `:seon.agent/schedules`) — do NOT introduce a parallel `setInterval`
  convention. Read how schedules arm timers first and match it.
- False-positive posture: a slow-but-alive turn closed `:crashed` costs one
  spurious root message + one wasted in-flight turn (fencing makes the late
  driver a no-op — no corruption). Acceptable; the fail direction is
  correct (spurious wake beats unnoticed wedge). Keep it rare via the
  measured threshold.
- On finding an OPEN run with a stale beat: close it
  `closed-reason :crashed`. That single fact triggers the standard Piece 2b
  routing (parent message + root escalation). No dedup state needed — a
  closed run cannot go stale again.
- **A wedge is OUR bug — record it** (owner ruling 2026-07-06): the
  watchdog close also calls `seon.error/record!` with `:core` fault,
  carrying the agent/run refs and the stale-beat evidence. This plugs
  wedges into the ENTIRE existing triage chain (watch-faults → inspect →
  repro → fork). Consequence, accepted: under the dev `:crash` dial the
  pod exits loudly on a wedge — that is the house posture for core bugs;
  prod (`:log`) records + surfaces only.
- **Root wedge recovery = stay idle, no auto-rewake** (owner ruling): the
  close itself unsticks root (idle + wakeable); the outcome message goes to
  the user (root is parentless); root resumes on the next natural contact.
  No special case, no wake→wedge→wake loop possible from the watchdog.
- MUST respect the run fencing semantics: verify against the loop's
  fencing (`:seon.agent.run/id` as fencing token) that a late-beating
  driver against a watchdog-closed run is safely ignored/superseded, not a
  double-drive. Read the loop source first; if fencing does not already
  cover this, report back rather than improvising.
- Do not fire on `paused-at` runs (paused agents legitimately don't beat —
  check how pause interacts with `last-beat-at`/`remaining-ms` in the
  source).

**Root is not special — same close path, no rewake.** Root is an agent
with a run; if root's run wedges, the watchdog closes it `:crashed` like
any other. Root is parentless, so the outcome notice goes to the USER; per
the stay-idle ruling nothing rewakes root — the close itself unsticks it
(idle + wakeable) and the next natural contact resumes it. Requires the
fencing verification above (a late-resolving old driver must be a no-op).

**Staleness threshold vs turn granularity.** The beat is per-turn today; a
turn legitimately spans a full LLM call. Threshold must exceed worst-case
turn time (LLM timeout × retries + eval bounds + margin). If that is
uncomfortably loose, the sanctioned tightening is beating at PHASE
boundaries within the turn (render / LLM-return / eval / persist — a few
datoms per turn) — implementer measures worst-case turn time on the live
pod before picking, and reports the chosen threshold.

**Coverage boundary (by construction).** The in-process watchdog covers
RUN-level wedges only (event loop alive). A PROCESS-level wedge (blocked
Node event loop — e.g. the known overlapping-cljs.test wedge — or a
dead-but-not-exited pod) cannot be caught by anything in-process; that
needs an external observer (`bin/seon` probe in the watch-faults
convention). Out of scope for this unit; tracked as its own follow-up.

Tests: hermetic — an open run with an old beat gets closed `:crashed` by
one watchdog pass and root receives exactly one message; a paused run does
not; a fresh-beat run does not; root's own stale run closes and the
resulting message opens a fresh root run.

## Piece 2d — schedule-wake circuit breaker (crash-loop guard)

With no auto-rewake, the ONE autonomous repeat-wake source is schedules —
a deterministic wedge + a periodic schedule = a slow crash loop with fault
spam. Guard it, derive-don't-store style (owner ruling 2026-07-06):

- In the schedule wake-gate (wherever a schedule trigger opens a run): a
  derived query — count `:crashed` closes for this agent within a recent
  window. At ≥N (dials: e.g. N=3, window ~30min — match config-dial
  plumbing), REFUSE schedule-triggered wakes for that agent.
- Human and agent MESSAGES still wake — deliberate contact is not a loop,
  and the human must be able to poke a breaker-tripped agent.
- No stored breaker state, nothing to reset: the run log IS the state; the
  window sliding past re-enables schedules (worst case one wedge per window
  while the cause persists). Applies uniformly to every agent — root is not
  special.
- The refusal must be visible: a breaker-tripped agent's derive-status /
  subagents-section line should show it (derivable from the same query).

Tests: hermetic — an agent with N recent `:crashed` closes gets its
schedule wake refused; a message wake still opens a run; an agent with
stale (outside-window) crashes wakes normally.

## Deferred follow-ups (designed, NOT in this unit)

- **External liveness probe** — `bin/seon` watch-liveness in the
  watch-faults convention (HTTP ping on 7890 / beat-freshness via the wire
  write stream): the ONLY layer that can catch a blocked Node event loop or
  dead-but-not-exited pod. Own small unit.
- **`reset-root!`** — retract root's `:seon.agent/ctx` (component cascade)
  and re-seed blocks from the manifest, keeping identity/history: the
  middle rung between `restart pod` and `cluster reset` for POISONED
  DURABLE state. Deferred until observed; the preferred response to a
  deterministic wedge remains the fault workflow (inspect → repro → fork →
  fix).

## Piece 3 — `subagents` context section

A new derived section (block `:seon.render/ai` fn), following the existing
section-fn conventions in `src/seon/agent/ctx/` and the manifest in
`config/system.edn`:

- Query: agents whose `:seon.agent/parent` = the rendering agent (from ALS
  scope, same as other agent-scoped sections). Renders NOTHING when empty —
  the standard reactive-context vanish.
- Per child, one compact line derived from `derive-status` + the latest run:
  - id · state (dot+word, e.g. `● running`) · purpose (truncated)
  - when running: `turn 3/10`, open plan count, last-beat age
  - when idle with a completed latest run: the `:seon.agent.run/result`
    string (+ a ref pointer if `result-ref` present)
  - when closed abnormally: the `closed-reason` (`:error`, `:turn-limit`, …)
    — a parent MUST see a child that died, not just one that succeeded.
- Token-cap the section like other sections (all sizes reported in TOKENS).
- Manifest placement: add to the GENERAL agent-context (it renders empty →
  absent for childless agents, so it costs nothing; root gets it via the
  same manifest). Priority: near the transcript, below the plan — implementer
  matches surrounding conventions.

This section is the parent's monitoring surface: completion is a fact in the
DB, so a parent that was mid-turn or restarted still sees every child result.
No notification state, nothing to acknowledge or clear.

## Piece 4 — `orphaned-agents` root section

A root-only section (config-injected via `:seon.config/root-context`, same
mechanism as `:core-faults` / `:instrumentation-gaps`):

- Query: agents NOT terminated whose `:seon.agent/parent` IS terminated.
- Render: one line each — id, state, purpose, parent id. Empty → absent.
- No action machinery. Root (or the human) decides per case with existing
  verbs.

## Testing + live proof

- Unit tests (`.cljs`, hermetic in-memory conns, per `/clojure-testing`):
  - `complete` writes result datoms; unconditional even when the
    message-skip guard fires; `result-ref` round-trips as a ref.
  - `spawn-depth` on root=0 / child=1 / cycle-guarded.
  - `start!` from a depth-1 caller returns the refusal envelope (no child
    entity created, no throw).
  - subagents section: empty for childless; renders running child status;
    renders completed child's result; renders a `:error`-closed child.
  - orphan section: empty normally; renders after parent termination.
- Full `bin/test-cljs` ONCE at the end (fault gate green).
- Live proofs on the default pod (7890): root `delegate!` → child completes
  with a result → read the run's result datoms back via REPL query; render
  root's context and OBSERVE the subagents section line (read the actual
  rendered output — flag garbage over fake optimization); attempt a
  full-qualified `start!` from inside the child → observe the refusal
  envelope datom-free.

## Docs to update (same patch)

- `docs/seon/architecture/agent-runtime.md` — replace the aspirational
  `/call`-gate spawn description with the real rule: soft gate (root
  home-requires) + hard depth-cap backstop; document `run/result`.
- `docs/seon/architecture/context.md` — the subagents + orphaned-agents
  sections.
- `docs/prds/agent-ctx/roadmap.md` — add this unit to the we-are-here.
