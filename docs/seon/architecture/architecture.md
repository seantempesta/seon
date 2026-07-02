---
type: architecture
status: active
tags: [architecture, agent, flow, database, web]
---

# Seon Runtime Architecture

> **Target design** (present tense — the system as it is when built). Current code state + the migration path live in [[roadmap]].

This is the map, not the territory: the thesis, the one vocabulary, the deployment
topology, the cross-cutting principles, and one orienting paragraph per domain.
Every domain detail lives in its own doc — follow the pointer.

## Thesis

Seon is a long-lived runtime where AI agents serve one human. Everything is **data
in a single-writer, multi-reader, bitemporal database**; every moving part — an
agent loop, a render, a status view — is a **function of that database** evaluated
reactively. Isolation, aggregation, and recovery all fall out of that one choice:
units share *data*, not memory, so they run in parallel, can't corrupt each other,
and restart cleanly from the DB (which is itself reversible). The loop is data; the
prompt is a render of data; the UI is a reactive projection of data. The context
unit is the **block** (`:seon.agent.ctx/block`); the prompt, an agent's **world**,
and the **root agent's** world (`/`) are each a derivation of the same blocks.

It is **dual-track**: a CLJ **JVM server** (the DB writer + render/serve + Integrant
lifecycle + heavy processing, data-only) and CLJS **agent executors** (isolated),
sharing the `.cljc` **schema** layer. The derived-state rule and the transition
table are CLJS (`seon.derive` / `seon.agent.loop`), reached from the device the same
way every read is — over the wire against the replica.

**Client/server is the shape.** The pod is a **read-only datahike replica** of the
single JVM **writer** (reads local off the replica, writes forwarded over the wire).
Agent clusters run on user devices, each a replica the writer feeds datoms to —
indexes are materialized once per cluster, all writes forwarded to the one writer
(total order). A **local "system" cluster** does system work; **user clusters** do
local data processing. A threaded db *value* is location-agnostic; the work-fence is
arbitrated at the single writer; `since-t` replay is the network-blip recovery
primitive.

## Glossary

One vocabulary, each name grounded in a namespace + a schema/fn.

- **block** — the context unit: a function-of-the-DB map with up to two renders,
  seeded into the agent's own `:seon.agent/ctx` and rendered in
  `:seon.agent.ctx/priority` order. `:seon.agent.ctx/block` in `seon.agent.ctx`.
  `:seon.agent.ctx/name` is a plain `:keyword` — the app-level upsert key (prompt
  header = DOM `#tile-<name>`), NOT a datahike identity.
- **render** — a block's output; the one word for the projection. Engine:
  `seon.render`. A render is never stored.
- **ai render** — a block's prompt-text output (a string, or a symbol late-resolved
  via `seon.eval/lookup-value`). `:seon.render/ai`.
- **html render** — a block's hiccup output → a tile. `:seon.render/html`.
- **prompt** — the agent's assembled context: ai renders concatenated by priority
  (`seon.agent.ctx/render-context`), prefixed by a fixed system role
  (`seon.agent.ctx/system-text`, a code const — not a block, not per-request
  overridable).
- **page** — the human's UI: a layout placing html renders into slots. `seon.ui.*`.
- **tile** — an html render placed in a slot (the live UI element).
- **slot** — a named, DB-keyed hole in a layout: `(slot :name)` →
  `[:div {:id "tile-<name>" :data-slot :name}]`, keyed on `:seon.agent.ctx/name`.
- **layout** — a render whose hiccup contains slots (it nests tiles); a role, not a
  stored kind. A render with no slots is a leaf tile.
- **canvas** — the focal block on an agent's world: the agent↔human communication
  block.
- **world** — an agent's page, route `/agent/{id}`: the canvas plus a
  `:seon.agent.ctx/priority` scroll of the agent's tiles.
- **root agent** — ONE `:seon.agent/id "root"` that is BOTH the `/`-world owner (the
  UI) AND the system orchestrator (lifecycle) — the same elevated grant, never two
  entities. Its world IS the all-agents overview at route `/`, rendered by the
  IDENTICAL block/layout/route machinery as any agent's world. It holds the elevated
  capability grant — system-level `:seon.fn`s (`start!`, terminate, cross-agent) —
  through the SAME `/call` gate, not a bypass. It is the root of the render + route
  tree (`/` → `/agent/{id}` → apps) and the base case of the bootstrap recursion
  (root has no `:seon.agent/parent`). See [[agent-runtime]].
- **app** — an agent-authored sub-page, route `/agent/{id}/app/{x}`.
- **route** — a datom mapping a URL pattern to a layout, consumed by reitit.
  `:seon.route/*` (`seon.route` ns).
- **reitit** — the front door: a router that is a pure derived value of the route
  datoms (vendored, `reference-code/reitit`, `.cljc`). The capability gate
  (`seon.web.reactive.call`) authorizes the fn behind `/call`. See [[ui]].
- **warnings block** — the block surfacing current problems: an ai render to the
  agent, error tiles to the human, one source. `seon.agent.ctx.warnings` + `seon.warn`.
  Self-healing — empty when clean.
- **`:seon/error`** — the structured value any failure produces: ONE base shape
  (`:seon.error/message` humanized via `malli.error/humanize`; `:seon.error/data` the
  explain map; where/symbol/hint), specialized only on real divergence
  (`:seon.db/error` carries the exception, `:seon.ai/error` the provider fields).
  Never crash, always surface. See [[data-model]].
- **seed-copy** — the seed/override mechanism: ALL blocks are copied into the agent's
  own `:seon.agent/ctx` at creation; render reads that COMPLETE set sorted by
  priority. There is no render-merge, no separate default set, no provider.
- **`install!` / `remove!`** — the sole seed/override verbs, in `seon.agent.ctx`.
  `install!` is scope-aware + variadic (a single map OR a vector-of-maps); idempotent
  upsert-by-`:seon.agent.ctx/name`; at boot/no-scope it builds the default seed set,
  in an agent's scope it targets that agent's `:seon.agent/ctx`. `remove!` drops by
  name (component cascade). Override = `install!`/`remove!`, period — for seon, for
  acme (from its own nses via `SEON_EXTRA_SRC`), and for the agents themselves.
- **orchestrator-root** — the lifecycle facet of the root agent: it `start!`s and
  manages child agents through `/call`, writing `:seon.agent/parent`. See
  [[agent-runtime]].
- **run / turn / derive-state** — the bounded unit of work a trigger opens
  (`seon.agent.run`), the per-iteration value-transform (`seon.agent.loop`), and the
  one projection rule for agent state (`seon.derive`). See [[agent-runtime]].
- **wire-server / pod / tx-listener** — the JVM datahike writer; the Node CLJS
  runtime; the `listen!→derive→push` streamer role (any replica-holding process can
  play it).

## Deployment topology

```
 browser / Tauri webview
        │  SSE down (the live channel), POST up (actions)
        ▼
   NODE UI-HOST ── read-only replica + ONE tx-listener + the route table (reitit)
        │  listen! → derive world → whole-element morph → push  the only browser-facing HTTP/SSE
        ▲                                                    (derives every page; runs NO agent code)
        │  wire (RPC + tx-feed, since-t replay)
   JVM WIRE-SERVER ── single-writer datahike + heavy processing (embeddings/LLM/indexing) + Integrant
        ▲   the bus + authoritative writer; handles only DATA, never agent code
        │
   ISOLATED PER-AGENT NODE RUNTIMES ── each its own SCI cage + event loop; the ONE exec service
     agent-A   agent-B   …   eval / render / interaction → returns DATA (hiccup / result / tx)
```

Three roles, decoupled in principle, co-located in one pod for v1:

- **JVM wire-server** — the sole authoritative datahike writer (durable, bitemporal)
  plus heavy processing (embeddings, LLM, indexing) and Integrant lifecycle. Data
  only; it never executes agent code.
- **Node UI-host** — the browser's single front door: a read-only replica + one
  tx-listener that derives every agent's **world** (including the root agent's world
  at `/`) from `:seon.agent/ctx`, holds the route table, and streams patches. The
  streamer is a **role, not a process** — any process holding a replica + a
  tx-listener can play it, so the UI-host is relocatable.
- **Isolated per-agent Node runtimes** — the dangerous part: each runs the one
  sandboxed-execution service in its own SCI cage + event loop; its output is just
  data (hiccup, a result, a transaction) handed back over the wire. The isolation
  tiers (worker + SCI / microVM) live in [[agent-runtime]].

**v1 = a single pod plays all three roles.** The wire + DB-as-bus is the only
boundary. The endgame decouples to a **Node-only user box** (UI-host + isolated
agents) with the writer/server either remote (a home server over a private network —
the user's data stays on their own box) or re-homed to Node.

- **Edge node (Tauri, desktop + phone)** — secure transport into the system, native
  device-data capture (the phone is the data goldmine), the view, and an on-device
  read replica + local fns. Privacy lever: process the most sensitive data on-device
  so it never leaves.

## Cross-cutting principles

### DB-as-bus

Units are isolated in **compute** but share one **DB**, so "pull together data from
all of them" = read the one DB they all write to — there are no silos to aggregate.
The reactive loop, end to end: a browser action POSTs a fact → the writer commits and
fans the tx out → the relevant unit's `listen!` fires, it computes and writes its
**facts** back → the writer fans out → the Node UI-host's `listen!` fires,
re-renders the WHOLE element (`view = f(db-as-of t)`) and pushes one gzip datastar
**morph**, which idiomorph diffs client-side (a coalescing throttle collapses a tx
burst into one morph). Two channels ride the wire with different reliability:
reads/writes/heavy calls ride a **request-reply RPC** (only *values* cross —
`:db.fn/cas` is data and crosses fine, closures can't); the **tx feed** is a separate
broadcast made lossless across reconnect by per-subscriber `since-t` replay from the
bitemporal tx-log (this is the pod↔writer wire-replication layer — the dropped event
is the wake trigger, so it must not be lost; the browser stream needs no UI-side
`since-t`, it just repaints `view = f(db)` on reconnect). **No agent code ever touches
an SSE connection** — agents write facts; the UI-host derives and streams.

### Derive everything

Everything — the loop, the prompt, an agent's world, a status view, the work
bound, the agent's state — is a **function of the DB at render time**; nothing
derivable is stored. Agent state is the `seon.derive/derive-state` projection of
primitives (an open run, `paused-at`, `terminated-at`), never a stored field. The
work bound is `default-turn-limit` + the inbound-message count, not a per-message
write. Renders are projections, never persisted. New ways to surface data are new
block render fns, not new mechanisms — when the underlying problem is fixed, the
query returns empty and the surface vanishes (self-healing). Caching keys on basis-t,
never on a db value.

### Never crash, always surface

Every failure — render, eval, transact, capability denial, schema rejection, LLM
error, a throwing check or handler — is caught at its site and surfaced as a
**`:seon/error`** value in a derived, agent-visible place, never a process crash (the
pod is single-threaded; one uncaught throw would blank every agent + the UI-host).
One source, two renders: the **warnings block** (ai) for the agent and an **error
tile** (html) for the human. The full map of failure-site → where it surfaces
lives in [[data-model]] §7.

### Seed-copy, not merge

ALL blocks are copied into the agent's own `:seon.agent/ctx` at creation; render
reads that complete set sorted by `:seon.agent.ctx/priority` and stops. There is no
render-merge over a separate default set, no provider, no central catalog. Override
is `install!`/`remove!` against a scope (boot scope builds the default seed set;
agent scope targets that agent). The `my.*` nses define the render fns + block data
and are batch-installed at seed. See [[ui]].

### Roles are capabilities

A role = the set of `:seon.fn` capabilities granted to an agent + which bootstrap
form-vector ran, NEVER a stored `:kind`/`:role` enum. "Orchestrator" = an agent
granted the spawn/terminate/system fns; "worker" = an agent without them.
Differentiation is Datomic presence/absence of grants, queried at the `/call` gate —
root is superuser by grant, not by a special code path. This is the entity-level case
of the general rule: an entity's kind is the attributes it carries, never a stored
discriminator (see [[data-model]]).

### Code as data

The core's source, the agent's eval log, and the analyzer state are three views of
one code corpus. Agent-defining forms persist as `:seon.fn` / `:seon.ns` /
`:seon.schema` entities; the DB IS the running system (query → reconstitute →
topo-sort by `:seon.ns/requires` → eval; redefine = upsert). An agent's **bootstrap**
is seeded eval'd forms run quietly (`:core` origin, no wake, no turn-count) in the new
agent's scope before any trigger — the batched `(ctx/install! […])`, the
`:my.agent/purpose` schema + refine fn, the home-ns `defn`s — so the agent SEES its
own startup, not hidden core magic. See [[agent-runtime]].

## The domains

### Data model — [[data-model]]

Everything is namespaced datoms in one bitemporal DB; the agent record is the root of
its context and its loop control, and everything else is reachable from it or derived.
The doc owns every entity/attr/type, the three relationship kinds (datahike ref,
identity/lookup attr, symbol-as-value late binding), the `:seon/error` model + the
entity-kind-vs-value-enum rule (kind = attribute presence, never a stored
discriminator), and the domain schemas: `my.kb` (no agent ref → global, one KB all
agents see), `my.todo` (a TREE via `:my.todo/parent` + derived roll-up, scoped per
agent by `:my.todo/agent`), and `my.agent` (`:my.agent/purpose`, the per-agent seed
worked-example). Global-vs-per-agent is decided by the **data's** agent-ref, never by
the block. Index every ns's valid forms; render `my.*` in full. See [[data-model]].

### Agent runtime — [[agent-runtime]]

The loop is a fold of one transition over events derived from the run's data each
iteration — a defined initial state, a data transition table, the FSM as data, no
channels. Creation = an IDLE agent entity (id + optional default-turn-limit/deadline
seeds + the ctx seed + the home ns); the loop opens a **run** only on a **trigger**
(an inbound message or a due schedule via the one ticker), bounded two ways
(turn-limit + wall-clock deadline). A **turn** threads one frozen db value and leads
every work tx with an in-tx `:db.fn/cas` work-fence, so a superseded run's writes
abort at commit. The doc owns the run/turn/FSM/derived-state mechanics, creation-as-
idle-entity, bootstrap-as-seeded-forms, the **orchestrator-root** lifecycle
(`start!` = a core verb granted to root, aliasing `create!` through `/call`, writing
`:seon.agent/parent`; roles-as-capabilities; root = the cluster-boot base case;
UI-root == orchestrator-root), and the isolation tiers. See [[agent-runtime]].

### UI — [[ui]]

The human UI is **pages** — a **layout** placing block html renders into named
**slots**, each filled slot a **tile**; all pages are agent **worlds**, a tree of
routes: the root agent's world (`/`), per-agent worlds (`/agent/{id}`), and apps
(`/agent/{id}/app/{x}`). Routing is data via **reitit** over `:seon.route/*` datoms;
`/call` is the one action door and the capability gate authorizes the fn (namespace
is the route). The **live channel is ours**: one tx-listener on a read-replica derives
every world and streams it as a per-connection gzip whole-element **morph**
(idiomorph-diffed client-side); reconnect just repaints `view = f(db)`, no UI-side
`since-t` replay. The doc owns block/render/tile/slot/layout, the page tree,
reitit + the gate, the SSE channel, and the seed-copy + variadic `install!`/`remove!`
override model. See [[ui]].

### Toolkit — [[toolkit]]

The agent's action surface is the **`my.*` verb catalog** — thin, agent-owned,
editable wrappers (`my.code`, `my.recall`/`my.kb`, `my.todo`, `my.schedule`,
`my.tile`, `my.shell`, `my.test`, `my.search`, `my.files`) over a protected `seon.*`
floor, composed on four shared shapes (path, ref, items, the never-throw result
envelope). See [[toolkit]].

### Observability — [[observability]]

Every question about agent behavior — what an agent saw at turn N, what changed
between turns, why it acted — is answered by a **query against the DB plus the
blob store**, never by hunting log files: each turn persists its `basis-t` (the
frozen db coordinate that makes the context re-derivable), the assembled prompt
verbatim as a blob, and the raw reply. `inspect/turn` reconstructs any turn;
`turn-diff` shows what changed between two; a dedicated **forensic agent** runs
these queries on demand; the `/solve` door hands a reproducible turn to an
external solver. See [[observability]].

## Build path — [[roadmap]]

The current code state, the gap to this target, and the comprehensive,
dependency-ordered, replace-in-place migration (file:line, the lane split, the final
grep-verify + cluster-reset gate) live in [[roadmap]]. It is the only "we are here"
doc; this one stays pure target.

## Detail docs

- [[data-model]] — entities + attrs + the three relationship kinds, the `:seon/error`
  model + entity-kind-vs-value-enum, the `my.kb`/`my.todo`/`my.agent` schemas + data-
  agent-ref scoping, index-everything.
- [[agent-runtime]] — loop/run/turn/FSM/derived-state/two-bounds, creation-as-idle,
  bootstrap-as-seeded-forms, orchestrator-root lifecycle, isolation tiers.
- [[ui]] — block/render/tile/slot/layout, the page tree, reitit + the capability gate,
  the gzip-morph SSE live channel, the seed-copy + `install!`/`remove!` override.
- [[toolkit]] — the `my.*` verb catalog over the protected `seon.*` floor.
- [[observability]] — turn replay (basis-t + prompt blob + reply), `inspect/turn` /
  `turn-diff`, the blob store, the forensic agent, the `/solve` door.
- [[context]] — the dynamic context system: `context = f(db, location,
  window, tail)`, the three-band cache gradient (stable prefix / sliding
  transcript window / free dynamic tail), namespace-as-location, the
  affordance tail, and the UI twin of every band.
- [[laws]] — the drive-measured empirical laws (render-prominence,
  cache-stability, canvas-first, pass^k, keep-iff-lifts-battery) that
  constrain every design above. Not principles — measurements.
- [[roadmap]] — we-are-here → the gap → the migration checklist + the final gate.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset (db is
  a value, only values cross the wire, CAS-as-assertion, basis-t caching). Read before
  touching the loop.
- [[library-grounding]] — the concrete `reference-code/…:LINE` read-map (datahike,
  malli, SCI, reitit) per phase: every load-bearing claim grounded in real source +
  the idioms to imitate. **Every build-agent reads its phase's rows before coding.**
