---
type: architecture
status: draft
tags: [architecture, agent, flow, database, web]
---

# Seon Runtime Architecture

The current-state design for the agent runtime: how agents run, stay isolated,
coordinate, and render. **This is a living document — refine it in place;
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
reactive projection of data. The context unit is the **block**
(`:seon.ctx/block`); every surface — the prompt, an agent's world, the dashboard —
is a derivation of the same blocks.

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

## Glossary

One vocabulary, each name grounded in a namespace + a schema/fn. *("today …" marks
the current code name being renamed.)*

- **block** — the context unit: a function-of-the-DB map with up to two renders,
  merged over the agent's own set by one `:seon.ctx/priority` sort. `:seon.ctx/block`
  in `seon.ctx` *(today `:seon.ctx/section`)*.
- **render** — a block's output; the one word for what older code calls
  "twin"/"face"/"surface". Engine: `seon.render`.
- **ai render** — a block's prompt-text output (a string, or a symbol late-resolved
  via `seon.eval/lookup-value`). `:seon.render/ai`.
- **html render** — a block's hiccup output → a tile. `:seon.render/html`.
- **prompt** — the agent's assembled context: ai renders concatenated by priority.
  `seon.ctx/render-context`.
- **page** — the human's UI: a layout placing html renders into slots. Web layer:
  `seon.ui.*`.
- **tile** — an html render placed in a slot (the live UI element) *(replaces
  "card")*.
- **slot** — a named, DB-keyed hole in a layout: `(slot :name)` → `[:div {:id
  "tile-<name>" :data-slot :name}]`, keyed on `:seon.ctx/name`.
- **layout** — a render whose hiccup contains slots (it nests tiles); a role, not a
  stored kind. A render with no slots is a leaf tile.
- **canvas** — the focal block on an agent's world: the agent↔human communication
  block.
- **world** — an agent's page, route `/agent/{id}`: the canvas + a priority scroll
  of the agent's tiles.
- **dashboard** — the all-agents overview page, route `/`: a preview tile per agent.
- **app** — an agent-authored sub-page, route `/agent/{id}/app/{x}`.
- **route** — a datom mapping a URL pattern to a layout, consumed by reitit.
  `:seon.route/*` (new `seon.route` ns).
- **reitit** — the front door: a router that is a pure derived value of the route
  datoms (vendored, `reference-code/reitit` 0.10.1, `.cljc`). Replaces the
  hand-rolled dispatch.
- **warnings block** — the block surfacing current problems to the agent (ai
  render); the same problems render as error tiles to the human, via
  `seon.ctx.warnings` and `seon.warn`.
- **error value** — the structured value a failed render produces. `:seon.render/error`
  (`:seon.error/message|where|symbol|hint`).
- **core-blocks / default-blocks / set-blocks-provider! / !blocks-provider** — the
  default block set + the override seam (the `set-tee-fn!` idiom), in `seon.ctx`.
- **run / turn / derive-state** — the bounded unit of work (`seon.agent.run`), the
  per-iteration value-transform (`seon.agent.loop`), and the one projection rule for
  agent state (`seon.derive`). Unchanged by the UI redesign.
- **wire-server / pod / tx-listener** — the JVM datahike writer; the Node CLJS
  runtime; the `listen!→derive→push` streamer role (any replica-holding process can
  play it).

## Deployment topology

```
 browser / Tauri webview
        │  SSE down (the live channel), POST up (actions)
        ▼
   NODE UI-HOST ── read-only replica + ONE tx-listener + the route table (reitit)
        │  listen! → derive world/dashboard → slot-tree diff → push    the only browser-facing HTTP/SSE
        ▲                                                              (derives every page; runs NO agent code)
        │  wire (RPC + tx-feed, since-t replay)
   JVM WIRE-SERVER ── single-writer datahike + heavy processing (embeddings/LLM/indexing) + Integrant
        ▲   the bus + authoritative writer; handles only DATA, never agent code
        │
   ISOLATED PER-AGENT NODE RUNTIMES ── each its own SCI cage + event loop; the ONE exec service
     agent-A   agent-B   …   eval / render / interaction → returns DATA (hiccup / result / tx)

```

Three roles, decoupled in principle, **co-located in one pod for v1**:

- **JVM wire-server** — the sole authoritative datahike writer (durable, bitemporal)
  plus heavy processing (embeddings, LLM, indexing) and Integrant lifecycle. Data
  only; it never executes agent code. A future JVM-hosted UI is optional and would
  need the render layer promoted to `.cljc` (today the engine is `.cljs`, pod-only).
- **Node UI-host** — the browser's center point: a read-only replica + one
  tx-listener that derives every agent's **world** and the **dashboard** from
  `:seon.agent/ctx`, holds the route table, and streams patches. The streamer is **a
  role, not a process** — any process holding a replica + a tx-listener can play it,
  so the UI-host is relocatable.
- **Isolated per-agent Node runtimes** — the dangerous part. Each runs the one
  sandboxed-execution service in its own SCI cage + event loop; its output is just
  data (hiccup, a result, a transaction) handed back over the wire.

**v1 = a single pod plays all three roles.** The wire + DB-as-bus is the only
boundary; no flow — a `/call` transacts a request → the owning agent's `listen!`
fires → it executes (sandboxed) → transacts the result → the UI-host's `listen!`
fires → derives + pushes. The invariant that survives every split: **no agent code
ever touches an SSE connection.**

- **Edge node (Tauri, desktop + phone)** — secure transport into the system, native
  device-data capture (the phone is the data goldmine), the view, and (convergence
  endpoint) an on-device read replica + local fns. The device is reader +
  data-source + presence. Privacy lever: process the most sensitive data on-device
  so it never leaves.
- **Deployment: same box now, decouple later.** Now: one box. The JVM is a
  ship-to-users non-starter, so the endgame decouples to a **Node-only user box**
  (the UI-host + isolated agents in light VMs) with the writer/server either
  **remote** (a home server over Tailscale/VPN — the user's data stays on their own
  box) or re-homed to Node. Cost: the JVM render/serve we lean on now becomes a
  future Node port — acceptable, not today.

## Coordination & rendering — the DB is the bus

Units are isolated in **compute**, but share one **DB**. So "pull together data
from all of them" = read the one DB they all write to — there are no silos to
aggregate. The reactive loop, end to end:

1. Browser action → `POST` to the edge → edge writes a datom (a fact).
2. Wire-server commits → fans the tx out to every subscriber (units + the Node
   UI-host).
3. The relevant unit's `listen!` fires → it computes → writes its **facts**
   (agent output, eval results, run/turn primitive mutations) back to the DB.
   *Never a render, never derived "state"* — see *The render engine* / *data model*.
4. Wire-server commits → fans out → the **Node UI-host's** `listen!` fires → it
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
write only *facts* to the DB. The **Node UI-host** is the single front door: it
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

### The render engine — two renders, slots, layouts, errors-as-values

One engine, `seon.render`, renders every page and the prompt; renders are
projections, never stored. A **block** carries up to two **renders**, selected by key presence (no
stored `:kind`):

- **ai render** (`:seon.render/ai`) → prompt text. A verbatim string, or a
  qualified symbol late-resolved each render via `seon.eval/lookup-value`.
- **html render** (`:seon.render/html`) → a tile. A symbol, a literal hiccup
  vector, else the structural pretty-print.

`render` (`seon.render`) is one recursive, **guarded** walker over the merged
blocks in two views (`:ai` → String, `:html` → hiccup). A throwing or hung render
yields a structured error value for *that* render only; siblings never crash.

**Slots and layouts.** A render whose hiccup contains `(slot :name)` plays the
**layout** role — `(slot :name)` emits `[:div {:id "tile-<name>" :data-slot
:name}]`, a named, DB-keyed empty hole keyed on `:seon.ctx/name`, resolved by
recursion at expansion (generalizing the anonymous `:seon.render/render`
recursion handle). A render with no slots is a leaf **tile**. Layout-vs-tile is a
role, never stored.

**Prompt == world by construction.** Both derive from the same blocks over the
same db value (`as-of` the turn's `t`); the prompt is `seon.ctx/render-context`
(ai renders concatenated by `:seon.ctx/priority`), the world is the same blocks'
html renders placed into a world layout's slots. Nothing is stored; "what the
agent saw at turn N" is a re-derive from `db-as-of(t)`.

**Errors are values, never crashes — surfaced everywhere the agent can see.** A
guarded render that fails constructs a **`:seon/error`** value (the one general
error base — `:seon.error/message` humanized via `malli.error/humanize`,
`:seon.error/data` keeping the structured payload, plus where/symbol/hint; NO
`:kind` — discriminate by the carrier attr). It renders as an **error tile** (html)
for the human and feeds the **warnings block** (ai) for the agent via a
`:render-health` check in `seon.warn/checks` — one source, two renders,
self-healing (empty when clean). Render is just one instance of a system-wide
discipline: **no uncaught path, no silent swallow** — every failure (render, eval,
transact, capability denial, schema rejection, LLM error, a throwing check or
handler) is caught at its site and surfaced as a `:seon/error` value in a derived,
agent-visible place, never a process crash (the pod is single-threaded — one
uncaught throw would blank every agent + the UI host). The full failure-site →
surface map is [[data-model-2026-06-27]] §5.

**The default block set + the override seam.** `core-blocks` (`seon.ctx`) is the
stable public default vector every fresh agent merges over. A downstream cluster
swaps it without editing core via the `set-tee-fn!` idiom: `set-blocks-provider!`
installs a provider into the `!blocks-provider` atom, `default-blocks` is the
guarded read (provider-or-`core-blocks`, errors-as-values), and `context-root`
reads `default-blocks`. Per-agent additions live in the stored `:seon.agent/ctx`
vector, merged by one `:seon.ctx/priority` sort (override-by-name).

**Capability + cache.** Agent-authored renders, layouts, route handlers, and
blocks run SCI-bounded (`seon.render.sci/invoke-bounded`, a deadline), never
`lookup-value`-direct; core symbols run compiled, and the bootstrap CLJS compiler
stays out of the web bundle. The byte-stable cache prefix (`stable-boundary` /
`split-context` at `:seon.ctx/priority` ≤ 20) is preserved for provider
prefix-caching.

### The page system — world, dashboard, app

The human UI is **pages**, each a **layout** placing block html renders into named
**slots**; each filled slot is a **tile**. Three page kinds, all routed:

- **world** (`/agent/{id}`) — one agent: a **canvas** (the focal agent↔human
  communication block) in `(slot :canvas)` plus a `:seon.ctx/priority`-ordered
  scroll of the agent's tiles. (Today: `console-shell`, the closer of two
  competing shells; the older datastar consumer view is retired.)
- **dashboard** (`/`) — every agent, a preview tile each (step back to see all,
  dive into one).
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol, SCI-bounded.

**The live channel is ours, not reitit's.** One tx-listener on a read-replica
derives every world + the dashboard and streams patches; SSE is a pure derivation
of the tx-log (`listen! → derive → diff → push`), reconnect-lossless via
per-subscriber `since-t`. The diff is a per-connection `!last-tree` slot-tree BFS
to fixpoint: a leaf patch on content change, one expanded-subtree patch on a shape
change (so the client stays unchanged). **No agent code ever opens or writes a
stream** — agents transact, the tx-listener derives and streams. reitit has zero
streaming primitives by design; it only routes the GET that *opens* the feed.

**The streamer is a ROLE, not a process.** Both halves are first-class:
`seon.derive` is a process-portable read layer (pure `(db, id)`, requires only
`seon.db`) and the feed is a per-subscriber `since-t`-replayable subscription. So
the Node UI-host can split into N independent streamer-processes — each a
read-only replica + `seon.derive` + one subscription. *Needs baking* (see below):
the actual fan-out is the deferred multi-process split; today they are N SSE
streams inside the one pod.

### Routing is data — reitit + the capability gate

**Routing is data; authorization is a gate.** The front door is **reitit**
(vendored `reference-code/reitit` 0.10.1, `.cljc`) consuming `:seon.route/*`
datoms: `db->routes` projects them into reitit's vector, and a ~20-line Node↔Ring
adapter feeds the router (a pure derived value of the datoms, rebuilt on tx via a
reloading thunk). This **replaces the hand-rolled `case`/`cond`/`re-matches`
dispatch** in `seon.web.serve` / `inspector` / `tile`. Seeded core routes: `/`
(dashboard), `/agent/{id}` (world), `/agent/{id}/feed` (SSE), `/call`, `/eval`;
agents add `/agent/{id}/app/{x}`. reitit's nested route-data meta-merge makes
**nested routes = nested layouts**, and `match-by-name` gives reverse routing.

**Agents author interactivity as normal Clojure fn-calls in handler slots; the
browser sees only standard datastar.** A render-time server-side postwalk
(`transform-hiccup`) rewrites a fn-call `(cancel-order! id)` or a fn-ref
`submit-order!` into one standard `@post('/call', {:fn …, :args …})` (args
transit-serialized; the ref case pulls form values from datastar signals).
Routing is orthogonal to this rewrite — reitit does not touch it.

**`/call` is the one action door, and the capability gate is unchanged.** reitit
dispatches the URL; the gate (`seon.web.reactive.call`) authorizes the fn:
**namespace is the route** — `my.agent.<id>/foo` resolves to the owning agent,
granted only if it is a registered `:seon.fn` in that agent's home ns; refusal
precedes any invoke; args stay data; the call runs SCI-bounded → it transacts →
the reactive page re-derives and pushes. reitit replaces the **fragile** dispatch,
not the **secure** gate. Per-route auth and error-catch ride free as reitit
route-data middleware (`:seon.route/owner`, meta-merged parent→child; middleware
referenced by keyword through a registry); the same-origin CSRF guard becomes one
such middleware. Auth is wired empty for v1 — adding it later is one keyword + one
registry entry, zero handler edits.

## One sandboxed execution service (eval = render = interaction)

Eval, render fns, and interactions are **three doors to one service**: "run an
agent-granted fn with args, safely." Every call resolves the owning agent from
the fn's namespace, is capability-checked (only that agent's granted `:seon.fn`s
resolve — the same surface that denies `fs`), Malli-validates its args, runs in
that agent's sandbox, and transacts a fact (→ the reactive feeds update). **One
API, one capability model, one route, one pool.** The *backend* is tiered
(below) — worker + SCI by default, microVM when isolation must be a kernel
boundary — but callers see one service regardless. This is the single "eval +
render + interaction safety" solution; there is no second mechanism. Agent-authored
**layouts and route handlers** go the same door (SCI-bounded, deadline-killed),
never `lookup-value`-direct.

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
  session concept). Plus **`ctx[]`** (`:seon.agent/ctx`, the agent's own
  `:seon.ctx/block` vector, merged over `core-blocks` by one `:seon.ctx/priority`
  sort), `schedules[]` (self-managed cron maps, each with its fn),
  `default-turn-limit`, `purpose`, `parent`, `terminated-at` (optional `:inst`). A
  **block** (`:seon.ctx/block`) is `{:seon.ctx/name :keyword, :seon.ctx/priority
  :int, :seon.render/ai {:optional true}, :seon.render/html {:optional true}}` —
  `:seon.ctx/name` is the single identity (upsert key = prompt header = DOM
  `#tile-<name>`). **State is derived, not stored** — `:terminated` if
  `terminated-at` exists, else `:idle` if no open run, else `:paused` if the open
  run has a `paused-at` marker, else `:running`. Every primitive (the open run,
  `paused-at`, `terminated-at`) is its own control axis; state is just their
  projection — the ONE projection rule is `seon.derive/derive-state`, in the
  acyclic `seon.derive` leaf every consumer (loop, ctx, render, ui, schedule)
  reads.
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
- **The turn is a value-transform — "Snap-to-Tx"** *(derive leaf, per-turn
  threading, and in-tx work-fence all landed — Units 1+2 live-proven).* Each turn
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
   the run-status render block, single-process on a fresh world. Fully
   testable; the worker/edge/Tier-2 layers slot in additively.
2. **Phase 2 — Tier-1 worker isolation:** offload `eval-batch!` to the warm
   worker pool with terminate-on-deadline.
3. **Later, additive:** the edge/render-from-worker generalization; Tier-2
   microVM for untrusted code; the Tauri edge node + on-device replica.
4. **Layout-context unification** (see [[layout-context-unification-design-2026-06-27]]):
   the atomic `section`→`block` rename (`:seon.ctx/block`, `:seon.agent/ctx`) across
   both lanes + a cluster reset; the override seam
   (`core-blocks`/`default-blocks`/`set-blocks-provider!`); R's derived tiles become
   html-only blocks; `:seon.route/*` + `db->routes` + the reitit adapter; the
   `:seon.render/error` value + the `:render-health` warn check; the page system
   (world/dashboard/app layouts, slots, the `!last-tree` diff) in `seon.ui.*`. R owns
   context/schema/seed/render-engine; U owns `seon.ui`/web/css/reitit.

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
  a big context render shouldn't block the main event loop. (The *page* render is
  the Node UI-host's job, already off the agent's hot path.)

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
stored** — see *The render engine*. State is **derived** from primitives — see
*The data model*.

**UI/context unification — refine during build:**

- **Dynamic-slot streaming** — the `!last-tree` slot-tree diff must handle shape
  changes (a tile that gains/loses sub-slots), not just leaf content; needs
  shape-change tests.
- **Capability bound** — agent-authored layouts and route handlers must go
  SCI-bounded, never `lookup-value`-direct.
- **Web bundle** — keep the bootstrap CLJS compiler out of the web bundle through
  the rename (the lean `core-views` resolution table).
- **Atomic wide rename** — `section`→`block` / `:seon.agent/sections`→`:seon.agent/ctx`
  is one patch; a missed stored-attr read returns an empty query, not an error
  (grep-verify zero `:seon.ctx/section` / `:seon.agent/sections` before the reset).

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
- **Multi-process UI-host fan-out.** The streamer-as-N-processes model is designed
  but unbuilt; each page (world/dashboard/app) becomes its own streamer-process —
  a read-only replica + `seon.derive` + one subscription, rejoining via `since-t`.
  Lands with the microVM snapshot-fork (boot the bundle once, fast-restore per
  page/agent, env-var entry point). Gated on the microVM experiment
  (`research/microvm-isolation-experiment.md`).
- **A named function-call RPC registry.** The wire is already a values-only
  op-RPC (`q`/`transact`/`knn-search`); turning the op surface into a first-class
  "expose a JVM fn as a wire-callable, Malli-validated by its schema" registry is
  ergonomics, not correctness — defer until the op list grows enough to hurt.
- **Phase-2 per-agent isolation** (worker_threads/SCI then microVM) — gated on the
  microVM experiment; until it lands, eval stays in-process and the worker-write
  buffer (the in-flight split-brain half of the keystone) waits with it.

## Detail docs

- [[agent-runtime-spec]] — full schema + the run model + the FSM table.
- [[data-model-2026-06-27]] — the complete current data model: every entity +
  attribute + exact datahike facet, the three relationship kinds, the general
  `:seon/error` model, and the never-crash failure-site → surface table.
- [[layout-context-unification-design-2026-06-27]] — **the canonical context + UI
  spec**: the block / render / tile / slot / layout system, world/dashboard/app
  pages, routing-as-data via reitit, the override seam, and friendly errors → the
  warnings block. Supersedes the two below for the UI/context surface.
- [[layout-context-migration-2026-06-27]] — the grounded file:line migration plan
  (lanes R/U, dependency-ordered, silent-failure-flagged).
- [[single-render-path-design-2026-06-25]] — the original one-render → two-renders +
  reactive-inspector design (superseded for the surface by the spec above; the
  derive-don't-store and prompt==page facts survive in *The render engine*).
- [[interactive-feeds]] — the earlier feed/time-travel design (the page system
  supersedes the "N feeds" framing; the `data-action`→POST client, `as-of`
  time-travel, and the SSE-derivation facts survive in *The page system*).
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
