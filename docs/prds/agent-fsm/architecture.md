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

It is **dual-track** — a CLJ **JVM server** (DB writer + render/serve + Integrant
lifecycle + heavy processing, data-only) and CLJS **agent executors** (isolated),
sharing the `.cljc` **schema** layer. The derived-state rule and the transition
table are CLJS (`seon.derive` / `seon.agent.loop`), reached from the device the
same way every read is — over the wire against the replica. This **revives the
JVM** as the convergence always intended; it is not a CLJS-only effort.

**Client/server is the shape, and it already exists in miniature.** The pod is a
**read-only datahike replica** of the single JVM **writer** (reads local off the
replica, writes forwarded over the wire). The end-state generalizes this: agent
clusters run on user devices, each a replica the writer feeds datoms to —
**indexes are materialized once per cluster**, all writes forwarded to the one
writer (total order). A **local "system" cluster** does system work; **user
clusters** do local data processing. The fixes below are the prerequisites for
that, not obstacles to it (a threaded db *value* is location-agnostic; the CAS
work-fence is arbitrated at the single writer; `since-t` replay is the
network-blip recovery primitive). *Needs baking* (see below): a tiered
local-write path for offline.

## Deployment topology

```
 browser / Tauri webview
        │  ONE connection: SSE down (datastar), POST up (actions)
        ▼
   WEB RENDERER (the edge) ───────────────────┐  the only browser-facing HTTP/SSE
        │  listen! → derive → hash → push      │  (today: seon.web.serve/inspector)
        ▼                                      │
   JVM SERVER (same process) ── single-writer datahike + render/serve + Integrant
        ▲ ▲ ▲   the bus + aggregation point; handles only DATA, never agent code
        │ │ │   (wire + DB-as-bus: transact a /call → agent reacts → result back)
   ┌────┴─┴─┴──────── CLJS agents — isolated executors (private network) ─────────┐
   │  agent-A   agent-B   …   each runs the ONE exec service: eval / render-fns /  │
   │  interactions → returns DATA (hiccup/result/tx).  Tier 1 worker+SCI | Tier 2 microVM │
   └──────────────────────────────────────────────────────────────────────────────┘
   (live-tile / debug / chat are FEEDS the JVM renders from agent data, not units)

```

- **The JVM is "the server" — several roles, infra for free.** One JVM hosts the
  **wire-server** (datahike, the sole authoritative writer, durable + bitemporal),
  the **web renderer / edge** (hiccup→html, datastar, SSE, the `/call` route — the
  existing `seon.web` code, mostly as-is), **heavy processing** (embeddings, LLM,
  indexing), and **Integrant lifecycle + logging**. It handles only *data* — it
  never executes untrusted agent code.
- **CLJS agents are the isolated executors.** The one sandboxed-execution service
  (eval / render-fn / interaction) runs *here* (worker+SCI, or microVM). The key
  split: **executing an agent's fn is the dangerous part; its output is just data**
  (hiccup, a result, a transaction). Isolate the *execution* in CLJS; move the
  *data* to the trusted JVM, which renders/hashes/serves it. Untrusted code never
  reaches the JVM — so the JVM render code is safe to run as-is.
- **JVM↔CLJS is the wire + the DB — no flow.** The boundary is the existing
  transit-over-the-socket + the DB-as-bus: a `/call` transacts a request → the
  owning agent's `listen!` fires → it executes (sandboxed) → transacts the
  result/hiccup → the renderer's `listen!` fires → renders + pushes. We already
  have `listen!`, transit, and the socket; flow was the over-complicated version
  of "react to a datom."
- **The wire is the boundary.** Single writer, many readers — the property that
  makes worker isolation clean makes the device just another reader.
- **Edge node (Tauri, desktop + phone)** — secure transport into the system,
  native device-data capture (the phone is the data goldmine), the view, and
  (convergence endpoint) an on-device read replica + local fns. The device is
  reader + data-source + presence. Privacy lever: process the most sensitive data
  on-device so it never leaves.
- **Deployment: same box now, decouple later.** Now: one box, JVM server + CLJS
  agents (pragmatic — free infra). The JVM is a ship-to-users non-starter, so the
  endgame decouples to a **Node-only user box** (CLJS agents in light VMs) with
  the writer/server either **remote** (a home server reached over Tailscale/VPN —
  the user's data stays on their own box) or re-homed to Node. Cost: the JVM
  render/serve we lean on now becomes a future Node port — acceptable, not today.

## Coordination & rendering — the DB is the bus

Units are isolated in **compute**, but share one **DB**. So "pull together data
from all of them" = read the one DB they all write to — there are no silos to
aggregate. The reactive loop, end to end:

1. Browser action → `POST` to the edge → edge writes a datom (a fact).
2. Wire-server commits → fans the tx out to every subscriber (units + the web
   renderer).
3. The relevant unit's `listen!` fires → it computes → writes its **facts**
   (agent output, eval results, run/turn primitive mutations) back to the DB.
   *Never a render, never derived "state"* — see *One render path* / *data model*.
4. Wire-server commits → fans out → the **web renderer's** `listen!` fires → it
   re-derives the affected fragment from DB state, **fast-hashes** it, and pushes
   via datastar `merge-fragment` **only if the hash changed**.
5. Browser patches that part of the DOM.

**Two channels on the wire, different reliability.** Reads / writes / heavy calls
(`q`/`transact`/`pull`/`knn-search` — embeddings included) ride a **request-reply
RPC** that is reliable by construction (only *values* cross — `:db.fn/cas` is
data and crosses fine, closures can't). The **tx feed** that fans commits out is a
separate broadcast; it is now **lossless across reconnect** — each subscriber
tracks a basis-t watermark and re-subscribes with `since-t`, and the writer
replays the gap from its bitemporal tx-log (per-subscriber, no pod-singleton). A
dropped feed event used to be treated as "harmless — re-read latest"; that holds
for rendering but is fatal for the **wake edge** (the event *is* the trigger to
act), which `since-t` now closes. Within a run the loop reads the store itself
each turn, so the feed's only jobs are **waking idle agents** and **driving the
renderer**.

**Units never serve HTTP to the browser and never talk to each other** — they
write only *facts* to the DB. The **web renderer** is the single front door: it
owns the SSE connections and derives every fragment from DB state on demand —
`listen! → render → fast-hash → push-only-when-changed`, caching `{fragment →
hash}` in its own process memory (most re-renders hash-equal → no push → no
churn). It never persists a render. Trusted/core fragments render on the JVM directly;
an **agent-authored render fn executes in the owning CLJS agent's sandbox** (the
one exec service) and returns hiccup *data*, which the JVM renders — so untrusted
code never runs in the renderer. A hang/throw → a fallback for *that* fragment
only, so one bad render can't take the page down (datastar merges per element; a
renderer crash → supervisor restart → re-derive from DB → re-push; the Tauri
webview never runs render code). This generalizes the existing inspector/serve
layer.

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

### Feeds — a video-wall, not one webapp

The UI is N independent **feeds** (live-tile, debug, chat, each agent), each its
own SSE stream + render→hash→push pipeline. datastar opens one connection per
`@get`, so **one SSE per feed** gives independent windows: one crashed feed is
one dead tile (auto-retried via fetch backoff + `data-on:online__window`), not a
black screen. Seon already serves per-region SSE (`/agent/<id>/sse`, `/debug/sse`,
…). New: a feed registry, pod-side compression (Node zlib; JVM has brotli),
HTTP/2 via Caddy to lift the ~6-connection cap for large walls.

The full feed/interaction/time-travel design — the poor-man's-datastar client
(a `data-action`→POST tile, no agent-facing datastar), composable hiccup+Tailwind
regions, `as-of` time-travel ("apps" = saved cursors), and the streaming-effect
volatile tier — is [[interactive-feeds]]. It builds on the `:seon.render/html` twin
and lands after the loop work.

**The renderer is a ROLE, not a single process.** "Single front door" means
*units never serve the browser and untrusted code never runs in the renderer* —
not "one OS process." A feed is `subscribe → derive → hash → push`, and both
halves are now first-class: `seon.derive` is a process-portable read layer (pure
`(db, id)`, requires only `seon.db`) and the feed is a per-subscriber
`since-t`-replayable subscription. So the video-wall horizontally splits into **N
independent feed-processes** — each loads the same compiled bundle, an **env-var
picks its entry point** (one feed), and it is a **read-only replica plus
`seon.derive` plus one feed subscription**: simpler than the agent pod (no writer, no loop, no
echo-suppression) and **live-restartable** (rejoins via `since-t`, no lost
events). *Needs baking* (see below): the actual fan-out into separate processes is
the deferred Feeds feature + the microVM snapshot-fork — today they are N SSE
streams inside the one pod, but the collapse removed the couplings that would
have made the split a rewrite.

### Interactivity — agent fn-calls → predictable datastar → the owning VM

Agents make tiles interactive by writing *normal Clojure fn-calls* in handler
slots; the browser only ever sees *standard* datastar.

- **Authoring:** a fn-**call** `(cancel-order! id)` (args bound at render time) or
  a fn-**ref** `submit-order!` (args from click-time signals).
- **Render-time rewrite** (server-side postwalk, not a browser macro): both →
  one standard `@post('/call', {:fn 'my.agent.X/…, :args …})` (args
  transit-serialized; the ref case pulls form values from datastar signals).
- **Namespace is the route:** `/call` resolves the *owning agent* from the fn
  symbol's namespace (no routing table — the name is the route) and
  **sandbox-invokes in that agent's VM** (capability-checked to agent-granted
  `:seon.fn`s, Malli-validated) → it transacts → the reactive feed re-derives and
  pushes.

This is the **same sandboxed call-routing path as eval and render** — an
interaction is just an eval authored as hiccup and routed by its namespace.
**Most of this exists on the JVM track** (`reactive/transform.clj` rewrite,
`ns/routes.clj` resolve-and-call, `sse.clj` render→hash→push, `inspector.cljs`
per-feed SSE); the pod work is the `.cljc` port + replacing the JVM `seon.*`
prefix-whitelist with namespace-as-route-into-the-sandbox.

## One sandboxed execution service (eval = render = interaction)

Eval, render fns, and interactions are **three doors to one service**: "run an
agent-granted fn with args, safely." Every call resolves the owning agent from
the fn's namespace, is capability-checked (only that agent's granted `:seon.fn`s
resolve — the same surface that denies `fs`), Malli-validates its args, runs in
that agent's sandbox, and transacts a fact (→ the reactive feeds update). **One
API, one capability model, one route, one pool.** The *backend* is tiered
(below) — worker + SCI by default, microVM when isolation must be a kernel
boundary — but callers see one service regardless. This is the single "eval +
render + interaction safety" solution; there is no second mechanism.

## Isolation — the execution service's backend tiers

| | Tier 1 — worker_threads + SCI (default) | Tier 2 — microVM (opt-in) |
|---|---|---|
| weight | ~8MB / ~30ms per worker; `terminate()` 0.8ms | ~5MB+guest / ~125ms boot; second kernel |
| isolation | process boundary (real kill) + SCI cage (hallucination guard) | full kernel isolation — "contain a stranger" |
| DB reads | in-process, sub-ms (great for reactive readers) | vsock/VM-exit hop (fine for LLM-paced, bad for sub-ms re-render) |
| npm | direct `require`, shared pnpm `node_modules` trivially | full Node inside; shared store via virtio-fs RO mount |
| on macOS | native | libkrun (HVF) / Apple `container` (not Firecracker — KVM/Linux) |
| use for | reactive readers, UI, the trusted single-user agent | untrusted/dangerous code; the future multi-tenant case |

**Three isolation axes — and `worker_threads` are NOT a security boundary:**

- **Fault** (a hang/crash can't take down others) → worker_threads +
  `terminate()`. SCI catches the common interpreted runaway in-process (~0.2ms);
  `terminate()` is the CPU-proof backstop for what SCI can't (native loop,
  ReDoS) and the deadline-watchdog's only real kill.
- **Capability** (*what* code may do) → the **SCI curated surface**. Agent code
  runs in SCI exposing only GRANTED fns (`db/query`, `message!`, the wire
  capabilities); `fs`/`child_process`/`net`/`require` aren't in scope, so a
  worker can't format the disk — the symbol doesn't resolve. A bare worker has
  *full* process perms and `terminate()` can't stop an instant `fs` call, so
  **untrusted agent code MUST go through SCI**; the bootstrap `cljs.js` compiler
  is only for *our* trusted code. (CLAUDE.md: "isolation comes from process
  boundaries + the wire capability surface" — SCI is that surface.)
- **Resource** (runaway memory/CPU) → worker `resourceLimits` / Tier-2 microVM.

Tier-1 (worker + SCI) covers fault + capability-by-grant + resource for the
single-user, non-adversarial case. **Tier-2 microVM** is the *kernel* boundary
for genuinely untrusted/multi-tenant code or defense-in-depth against an SCI
escape: a guest can format its own disk but not the host's; fs/network governed
by VM config.

**Worker pool shape** (from the piscina/tinypool dive): warm `min 4 / max 8`
pre-bootstrapped SCI cages; `concurrentTasksPerWorker 1`; recycle = terminate +
respawn + re-read the DB (DB-stateless, no handoff); AbortSignal → terminate on
deadline; a bootstrap-failure breaker stops respawn storms.

## The data model

The agent record is the root of its context and its loop control; everything
else is reachable from it or derived. Full schema:
[[agent-runtime-spec]]. In brief:

- **agent** (`:seon.agent/*`) — `run` → current run (fencing pointer + the spine
  of derived state); runs link back via `:seon.agent.run/agent`, turns via
  `:seon.agent.turn/run` (a **run** replaces the old "session" — there is no
  session concept). Plus `sections[]` (its own context sections), `schedules[]`
  (self-managed cron maps, each with its fn), `default-turn-limit`, `purpose`,
  `parent`, `terminated-at` (optional `:inst`), tile. **State is derived, not
  stored** —
  `:terminated` if `terminated-at` exists, else `:idle` if no open run, else
  `:paused` if the open run has a `paused-at` marker, else `:running`. Every
  primitive (the open run, `paused-at`, `terminated-at`) is its own control axis;
  state is just their projection — the ONE projection rule is
  `seon.derive/derive-state`, in the acyclic `seon.derive` leaf every consumer
  (loop, ctx, render, inspector, schedule) reads.
- **run** (`:seon.agent.run/*`) — the bounded unit of work a trigger opens:
  `started-at`, `trigger {:message/:schedule}`, `cause`, `deadline` (wall-clock
  bound), `status`, `closed-reason`, `last-beat-at` (heartbeat). The run-id is
  the fencing token. **The work bound is DERIVED** (no per-message write):
  `default-turn-limit` + count of inbound messages this run; a stored
  `turn-limit-override` appears only when a process *explicitly* bumps/stops it.
  (Same principle as derived state — store facts, derive windows.)
- **turn / message / todo** — turns belong to a run; messages carry
  `origin {:human/:agent/:core}` (human inbound auto-mints a todo;
  hop-exhausted = dead-letter); todos are the work items.

**Derived (the `seon.derive/derive-status` fingerprint)** — derived state,
current turn (count), turns- & ms-remaining, turn-limit, deadline, total turns,
last-beat-at, last-closed-reason, last-human-at, open-todo count, plus the open
run's status/trigger. One call fingerprints the whole agent; it composes the
same `seon.derive` primitives (`derive-state`, `current-run`, `run-turn-count`,
…) every other surface reads.

## The execution model

The loop lives in `seon.agent.loop` and is shaped like a flow process — the
parts worth borrowing: a **defined initial state, one transition function, the
FSM as data** (no channels: CLJS channels are single-threaded, so they buy no
parallelism; isolation is the worker tier).

- **Transitions are a data table** (`{state {event → next-state}}`) —
  `seon.agent.loop/transitions` + `transition`, living with the loop that folds
  them. The effect of each event mutates a primitive (open/close/pause a run,
  set `terminated-at`); the agent's state is *derived* from those primitives via
  `seon.derive/derive-state`, never stored. The machine is inspectable/renderable,
  and the loop is a fold of `transition` over events derived from the run's data
  each iteration.
- **The turn is a value-transform — "Snap-to-Tx"** *(derive leaf + this model
  landed; per-turn threading + work-fence = Unit 2, in progress).* Each turn
  threads ONE frozen db value (re-read once at the top) through `next-event` +
  the prompt render + the bound checks, so the LLM reasons over a single
  consistent basis-t; the next turn re-reads the latest store (single writer ⇒ it
  sees every other writer's commits — never a private world). Every WORK tx
  (`beat!`, `open-turn!`, `eval-batch!`) LEADS with an in-tx
  `[:db.fn/cas [:seon.agent/id id] :seon.agent/run [run R] [run R]]`: the
  *database*, not a pre-read predicate, tells the loop it has lost authority — if
  a watchdog/human/newer run moved the pointer the tx aborts and the work never
  lands (live-proven). This replaces the old `owns-run?` check-then-act pre-read
  and fences the eval batch that was previously unfenced.
- **Triggering is reactive:** a wake (an inbound message, or a due schedule via
  the ticker) opens a run if the agent is `:idle`; if already `:running`, the
  new data is absorbed by the running run's window. Fencing is two-layered: the
  OPEN race is a `:db.fn/cas` on `:seon.agent/run` being *absent* (two wakes can't
  both open — single-writer serialized); the WORK is fenced by the per-tx CAS
  above (a superseded run's writes are rejected at commit). A stop between turns
  exits cleanly at the next `next-event`; a stop mid-turn is rejected at the CAS
  (hard-aborting an in-flight LLM call is Phase-2 worker-kill).
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
- **Native primitives that are free** — fencing (in-tx `:db.fn/cas`), dead-letter
  (hop-exhausted datoms), event stream (tx-log + `since`-replay), "state at T"
  (datahike `as-of`).
- **Port datahike/Datomic primitives — don't roll our own.** `seon.db` is the
  sole DB API; we surface and use the fork's primitives (`as-of`/`since`/
  `tx-range`/`:db.fn/cas`/`d/with`) instead of reinventing coordination, caching,
  or replay. Agents don't know datahike internals, so a hand-rolled version drifts
  from reality (e.g. *do not memoize on a db value — `equiv-db` walks the EAVT
  index and faults konserve nodes in; key on basis-t*). Read the fork; the mindset
  and source map live in [[datahike-primer]].

## Build phases

1. **Phase 1 — the data-driven loop** (mechanism-agnostic): run model +
   transition-table FSM + cron-as-data + the `derive-status` fingerprint +
   the run-status render section, single-process on a fresh world. Fully
   testable; the worker/edge/Tier-2 layers slot in additively.
2. **Phase 2 — Tier-1 worker isolation:** offload `eval-batch!` to the warm
   worker pool with terminate-on-deadline.
3. **Later, additive:** the edge/render-from-worker generalization; Tier-2
   microVM for untrusted code; the Tauri edge node + on-device replica.

## Open risks & questions (refine these)

**Correctness gaps to fix during build** (from validation —
[[architecture-validation-gemini-2026-06-25]]):

- **Fencing bypass — RESOLVED (Unit 2):** every WORK tx (`beat!`/`open-turn!`/
  `eval-batch!`) now LEADS with the in-tx `:db.fn/cas` work-fence, so a superseded
  run's writes (including the eval batch, previously unfenced) abort at commit —
  no more "agent eval `transact!`s without an ownership check." The remaining
  *in-flight split-brain* half (a terminated worker leaving a half-committed
  write) folds into **Phase-2 worker isolation**: the worker's writes are buffered
  and committed atomically through the same fenced tx after it returns.
- **Reconnect replay — RESOLVED (Unit 3):** `subscribe-tx` takes a `since-t`; each
  subscriber tracks a basis-t watermark and replays the gap from the writer's
  tx-log on reconnect, so a UDS drop no longer loses wake messages. (Full
  two-process drop-UDS live-proof pending; logic proven in isolation.)
- **Offload the agent's prompt render to its worker** (not just `eval-batch!`) —
  a big context render shouldn't block the main event loop. (The *view* render is
  the web-renderer component's job, already off the agent's hot path.)

**Pause vs. absolute deadline — RESOLVED** (build pass 3): `pause` banks
`remaining-ms = deadline − now`; `resume` re-extends the absolute `deadline` by it,
so a long pause no longer insta-kills on resume.

**Crash-recovery / atomic-wake / async-listener — RESOLVED** (build pass 5; see
[[night-loop-log]]): boot runs `run/recover-crashed-runs!` (first-boot-gated, closes
orphaned `:open` runs `:crashed` → `:idle`, live-proven via `kill -9`); `open-run!`
ends with a `[:db.fn/cas … :seon.agent/run nil …]` so a second concurrent wake's tx
aborts (single-writer serialized); `store/wire.cljs` fires each tx-feed listener on its
own `setTimeout 0`. Now also resolved: **reconnect-since-t replay** (Unit 3) and the
**fencing bypass** (Unit 2's in-tx CAS work-fence). Still open: the **worker-write
buffer** (the in-flight split-brain half), folded into Phase-2 worker isolation.

**Resolved decisions** (no longer open): sliding cap is **derived** (window =
`default-turn-limit` + inbound-count; stored `turn-limit-override` only on an
explicit bump/stop) — see *The data model*. Render storage is **derived, never
stored** — see *One render path*. State is **derived** from primitives — see
*The data model*.

**Minor / later:** heartbeat cadence (start per-turn); `default-deadline-ms`
value + whether deadline-less runs are allowed; `parent`/`llm-meta` disposition;
dead-letter clear/age-out; thin Tauri webview vs on-device replica timing; edge
scaling for multi-user (each edge = a read-replica + relay).

## Not yet — needs baking before implementing

Designed-or-implied above but deliberately NOT built; the model is sound and the
fixes don't preclude them, but each needs a real trigger or an open decision
first. (Marked *needs baking* at their mention.)

- **Tiered local writes for offline.** All writes forward to the single writer
  today (and LLM calls already require network). A local-write tier — buffer
  writes on-device, replay to the writer when the link returns — is additive at
  the wire seam but needs a conflict model and is gated on local models or an
  accepted offline window. Don't build until offline is a real requirement.
- **Multi-process feed fan-out.** The renderer-as-N-processes model is designed
  but unbuilt; it lands with the Feeds feature + the microVM snapshot-fork (boot
  the bundle once, fast-restore per feed/agent, env-var entry point). Gated on the
  microVM experiment (`research/microvm-isolation-experiment.md`).
- **A named function-call RPC registry.** The wire is already a values-only
  op-RPC (`q`/`transact`/`knn-search`); turning the op surface into a first-class
  "expose a JVM fn as a wire-callable, Malli-validated by its schema" registry is
  ergonomics, not correctness — defer until the op list grows enough to hurt.
- **Phase-2 per-agent isolation** (worker_threads/SCI then microVM) — gated on the
  microVM experiment; until it lands, eval stays in-process and the worker-write
  buffer (the in-flight split-brain half of the keystone) waits with it.

## Detail docs

- [[agent-runtime-spec]] — full schema + the run model + the FSM table.
- [[single-render-path-design-2026-06-25]] — one render → twin → transact →
  reactive inspector.
- [[interactive-feeds]] — the UI/view successor: composable live feeds, the
  poor-man's `data-action`→POST client (no agent-facing datastar), `as-of`
  time-travel + saved-cursor "apps", the streaming-effect volatile tier, and the
  decoupled rebuild-the-views POC. Builds on the `:seon.render/html` twin.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset
  (db is a value, only values cross the wire, CAS-as-assertion, basis-t caching,
  where to read in the fork). Read before touching the loop.
- [[abstractions-review-2026-06-26]] — the Snap-to-Tx collapse; §8 is the locked
  model (per-turn db-value threading, the in-tx CAS work-fence, the `seon.derive`
  leaf, the feed/RPC channel split).
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
