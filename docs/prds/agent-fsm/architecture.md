---
type: architecture
status: draft
tags: [architecture, agent, flow, database, web]
---

# Seon Runtime Architecture

The current-state design for the agent runtime: how agents run, stay isolated,
coordinate, and render. **This is a living document — refine sections in place;
it is not a decision log.** Detailed specs + the research behind each choice are
linked at the bottom; this is the map, not the territory.

## Thesis

Seon is a long-lived runtime where AI agents serve one human. Everything is
**data in a single-writer, multi-reader, bitemporal database**; every moving
part (an agent loop, a render, a status view) is a **function of that database**
evaluated reactively. Isolation, aggregation, and recovery all fall out of that
one choice: units share *data*, not memory, so they can run in parallel, can't
corrupt each other, and restart cleanly from the DB (which is itself
reversible). The loop is data; the context is a render of data; the UI is a
reactive projection of data.

## Deployment topology

```
 browser / Tauri webview
        │  ONE connection: SSE down (datastar), POST up (actions)
        ▼
   EDGE GATEWAY ─────────────────────────────┐  the only browser-facing HTTP/SSE
        │  listen!(tx-log) → push fragments    │  (today: seon.web.serve/inspector)
        ▼                                      │
   WIRE-SERVER (JVM) ── single-writer datahike ┘  the bus + aggregation point
        ▲ ▲ ▲   each unit is a wire client (reads its replica, writes its output)
        │ │ │
   ┌────┴─┴─┴──────── isolated compute (private network) ────────────────┐
   │  agent-A   agent-B   live-tile   debug-overlay   chat   …            │
   │  Tier 1: worker_threads + SCI   |   Tier 2 (opt-in): microVM         │
   └─────────────────────────────────────────────────────────────────────┘
```

- **Server tier** — the JVM **wire-server** (datahike, the sole authoritative
  writer, durable + bitemporal), the **agent runtime** (isolated execution), and
  **heavy processing** (embeddings, LLM calls, indexing). Agents run here.
- **The wire is the boundary.** Single writer, many readers. The same property
  that makes worker isolation clean makes the device just another reader.
- **Edge node (Tauri, desktop + phone)** — secure transport into the system,
  native device-data capture (the phone is the data goldmine), the view, and
  (convergence endpoint) an on-device read replica + local fns. Agents and heavy
  lifting stay server-side; the device is reader + data-source + presence.
  Privacy lever: process the most sensitive data on-device so it never leaves.

## Coordination & rendering — the DB is the bus

Units are isolated in **compute**, but share one **DB**. So "pull together data
from all of them" = read the one DB they all write to — there are no silos to
aggregate. The reactive loop, end to end:

1. Browser action → `POST` to the edge → edge writes a datom.
2. Wire-server commits → fans the tx out to every subscriber (units + edge).
3. The relevant unit's `listen!` fires → it computes → writes its result
   (a render fragment, agent output, state) back to the DB.
4. Wire-server commits → fans out → the **edge's** `listen!` fires → it pushes
   the keyed fragment over the one SSE stream (datastar `merge-fragment`).
5. Browser patches that part of the DOM.

**Units never serve HTTP to the browser and never talk to each other** — only
to the DB. The edge is the single front door + a dumb relay. Rendering is
distributed: each unit renders its own fragment and transacts it; the edge fans
DB changes to the browser. This is the existing inspector/serve layer, with the
render *producers* moved into isolated units.

### One render path — derived, never stored

A single render fn over `context-root` produces, per section, the **twin**:
`:seon.render/ai` (text, for the loop's prompt) **and** `:seon.render/html`
(hiccup, for the view) in one pass. Inputs derive from the db (no injected ctx
keys to diverge).

**Renders are projections of data — we never write them to the DB.** Only
*facts* are stored (incl. one scalar per turn: the **tx-basis `t`** the agent
rendered against). The view is derived on demand:

- The **web renderer** is a component that reads DB state, renders, and hosts
  its own HTTP server. Renders are ephemeral; it may memoize in its *own process
  memory* (perf escape hatch — not a DB row) and re-derive reactively on `tx`.
- **"What the agent saw at turn N"** = re-render from **`db-as-of(t)`**. The
  bitemporal DB *is* the storage; no render blob.
- **Prompt == view, byte-identical by construction** — both are the *same* fn
  over the *same* DB value (`as-of` the turn's `t`). Nothing stored. Tests assert
  via that fn (no hand-built ctx).

Drop the `render-file`/`prompt-file` blobs (ephemeral projections). *Caveat:* a
re-derived historical view uses the *current* renderer (today's view of
yesterday's data), not the literal bytes sent — fine for inspection; store the
prompt text only as a deliberate exception if strict audit is ever needed.

## Isolation — two tiers

| | Tier 1 — worker_threads + SCI (default) | Tier 2 — microVM (opt-in) |
|---|---|---|
| weight | ~8MB / ~30ms per worker; `terminate()` 0.8ms | ~5MB+guest / ~125ms boot; second kernel |
| isolation | process boundary (real kill) + SCI cage (hallucination guard) | full kernel isolation — "contain a stranger" |
| DB reads | in-process, sub-ms (great for reactive readers) | vsock/VM-exit hop (fine for LLM-paced, bad for sub-ms re-render) |
| npm | direct `require`, shared pnpm `node_modules` trivially | full Node inside; shared store via virtio-fs RO mount |
| on macOS | native | libkrun (HVF) / Apple `container` (not Firecracker — KVM/Linux) |
| use for | reactive readers, UI, the trusted single-user agent | untrusted/dangerous code; the future multi-tenant case |

**Two safety layers, not one:** SCI catches the common interpreted runaway
in-process (~0.2ms); `worker.terminate()` is the CPU-proof backstop for the
residual SCI can't (native loop, ReDoS) and the deadline-watchdog's only real
kill. We keep the bootstrap `cljs.js` compiler (agents author code at runtime);
SCI is the cheap cage, the worker is the boundary.

**Worker pool shape** (from the piscina/tinypool dive): warm `min 4 / max 8`
pre-bootstrapped SCI cages; `concurrentTasksPerWorker 1`; recycle = terminate +
respawn + re-read the DB (DB-stateless, no handoff); AbortSignal → terminate on
deadline; a bootstrap-failure breaker stops respawn storms.

## The data model

The agent record is the root of its context and its loop control; everything
else is reachable from it or derived. Full schema:
[[agent-runtime-spec]]. In brief:

- **agent** (`:seon.agent/*`) — `state {:idle/:running/:paused/:terminated}`,
  `run` → current run (fencing pointer), `sections[]` (the agent's own context
  sections), `sessions[]` → runs/turns, `schedules[]` (self-managed cron maps,
  each with the fn to call), `default-turn-limit`, `purpose`, `parent`, tile.
- **run** (`:seon.agent.run/*`) — the bounded unit of work a trigger opens:
  `started-at`, `trigger {:message/:schedule}`, `cause`, **two bounds**
  `turn-limit` (work quantity, bumpable) + `deadline` (wall clock), `status`,
  `closed-reason`, `last-beat-at` (heartbeat). The run-id is the fencing token.
- **turn / message / todo** — turns belong to a run; messages carry
  `origin {:human/:agent/:core}` (human inbound auto-mints a todo;
  hop-exhausted = dead-letter); todos are the work items.

**Derived (the `state-snapshot` fingerprint)** — current turn (count), turns- &
ms-remaining, total turns, last-closed-reason, last-human-at, next-fire-at, open
todos, unread inbound. One call fingerprints the whole agent.

## The execution model

The loop lives in `seon.agent.loop` and is shaped like a flow process — the
parts worth borrowing: a **defined initial state, one transition function, the
FSM as data** (no channels: CLJS channels are single-threaded, so they buy no
parallelism; isolation is the worker tier).

- **Transitions are a data table** (`{state {event → next-state}}`) — the
  machine is inspectable/renderable, and the loop is a fold of `transition` over
  events derived from the run's data each iteration.
- **Triggering is reactive:** a wake (an inbound message, or a due schedule via
  the ticker) opens a run if the agent is `:idle`; if already `:running`, the
  new data is absorbed by the running run's window. Fencing (run-id) means two
  wakes can't both start a loop.
- **Two bounds, externally enforced:** the loop stops at `turn ≥ turn-limit`;
  the **ticker** (one periodic timer — the only active piece, since the DB is
  passive about wall-clock) fires due schedules and `terminate()`s runs past
  `deadline` (`:deadline-exceeded` → reset). A sync runaway can't block the
  ticker because the ticker is off the runaway's thread (it's the worker that
  hangs, not the main loop).
- **Eval is offloaded:** the SCI `eval-batch!` runs in the worker pool; the
  deadline-watchdog terminates a runaway worker — the CPU-proof kill an
  in-process timer can't deliver.

## Shared packages

**pnpm content-addressed store** — one copy on disk, hard-linked into each
unit's `node_modules`. Install once → every unit sees it, realtime, no restart,
no loader shim. Tier-2 microVMs mount the store read-only via virtio-fs.

## What we adopt vs. deliberately don't

- **Compose, don't adopt a framework** — no turnkey sandbox (E2B/microsandbox/
  OpenHands) fits; they're built for a heavier, multi-tenant threat model. We
  compose worker_threads + SCI + datahike + pnpm + borrowed pool patterns.
- **No `core.async.flow` port** — JVM-only, and CLJS channels don't solve the
  lock-up (single-threaded). We borrow its *patterns* (initial state, transition
  fn, supervision), not its channels.
- **No continue-as-new** — Temporal needs it because it replays history; we
  never replay. Bound the *view* (query a window), not the storage.
- **Not QuickJS-WASM / Wasmtime for eval** — prior spikes found `cljs.js` broken
  under QuickJS + non-reproducible build; a WASM guest can't do realtime npm.
- **Native primitives that are free** — fencing (run-id), dead-letter
  (hop-exhausted datoms), event stream (tx-log), "state at T" (datahike as-of).

## Build phases

1. **Phase 1 — the data-driven loop** (mechanism-agnostic): run model +
   transition-table FSM + cron-as-data + the `state-snapshot` fingerprint +
   the run-status render section, single-process on a fresh world. Fully
   testable; the worker/edge/Tier-2 layers slot in additively.
2. **Phase 2 — Tier-1 worker isolation:** offload `eval-batch!` to the warm
   worker pool with terminate-on-deadline.
3. **Later, additive:** the edge/render-from-worker generalization; Tier-2
   microVM for untrusted code; the Tauri edge node + on-device replica.

## Open risks & questions (refine these)

**Correctness gaps to fix during build** (from validation —
[[architecture-validation-gemini-2026-06-25]]):

- **Buffer worker writes, commit on the main thread atomically** after the
  worker returns — this is the keystone fix: it closes both the *fencing bypass*
  (agent eval can `transact!` without an `owns-run?` check) and the *in-flight
  split-brain* (a terminated worker leaving a half-committed write). Single most
  important structural decision.
- **Crash recovery on boot** — boot must scan `:running` agents, reset them to
  `:idle`, and close their orphaned runs `:crashed`. This *is* the promised
  "restart to known-good state"; without it a crash deadlocks the agent.
- **Atomic wake** — idle→running + run-creation as ONE tx asserting prior
  `:idle`, so a message + a cron firing together can't spawn two runs (one
  orphaned).
- **Pause vs. absolute deadline** — on pause store `remaining-ms`, re-extend the
  absolute `deadline` on resume (else a long pause insta-kills on resume).
- **Async listener dispatch** in the tx-feed pump (`wire.cljs`) — one slow
  listener must not halt the pump and stall every agent's wakes.
- **Reconnect replay** — `subscribe-tx` needs a `since-t` basis, or a UDS drop
  silently loses wake messages → an agent sits `:idle` with unread mail.
- **Offload the agent's prompt render to its worker** (not just `eval-batch!`) —
  a big context render shouldn't block the main event loop. (The *view* render is
  the web-renderer component's job, already off the agent's hot path.)

**Design tension needing a call:**

- **Sliding cap: derive vs. store.** The spec's `renew!` writes `turn-limit` +
  `deadline` on *every* inbound message; the current code *derives* the window
  (`effective-cap`, no writes). Datahike writes are the ceiling. **Recommend a
  hybrid:** derive the default window (base + inbound count — no writes), store
  an explicit override *only* when a process actually bumps/stops it (rare). Keeps
  "other processes can extend it" without per-message churn.

  (Render storage is no longer a tension — renders are derived, never stored;
  see *One render path*.)

**Minor / later:** heartbeat cadence (start per-turn); `default-deadline-ms`
value + whether deadline-less runs are allowed; `parent`/`llm-meta` disposition;
dead-letter clear/age-out; thin Tauri webview vs on-device replica timing; edge
scaling for multi-user (each edge = a read-replica + relay).

## Detail docs

- [[agent-runtime-spec]] — full schema + the run model + the FSM table.
- [[single-render-path-design-2026-06-25]] — one render → twin → transact →
  reactive inspector.
- `research/execution-mechanism-loop-vs-flow-2026-06-25.md` — loop vs flow vs
  DB-queue; the two-step worker plan.
- `research/worker-threads-spike-2026-06-25.md` — measured worker cost +
  isolation proof.
- `research/parallelism-alternatives-2026-06-25.md` — worker vs WASM vs
  isolated-vm; the layered verdict.
- `research/isolated-node-oss-survey-2026-06-25.md` — compose-vs-adopt + the
  microVM tier + pnpm.
- `research/worker-pool-patterns-2026-06-25.md` — piscina/tinypool patterns +
  the pool shape.
- `research/loop-cycle-naming-precedent-2026-06-25.md` — the industry naming.
- [[agent-data-model-audit-2026-06-25]] — dead/duplicate/naming + gaps.
