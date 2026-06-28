---
type: architecture
status: active
tags: [architecture, index]
---

# Current State: How Seon Works Today

> A narrative of how data flows through the live system — the **CLJS pod**
> backed by the **wire-server** datahike writer. For component details, follow
> the links. The paused JVM main-app track is fenced at the bottom under the
> `[JVM track — paused]` section — keep it; it is the convergence target, not
> the present.

## The mental model

Three sentences carry the whole system:

- **The agent is a loop that is a function of the database.** Runnability is a
  single datom; a datahike tx-listener wakes the agent when a message lands; the
  stop policy is one `cond` over DB state. There is no accumulated runtime
  object to keep in sync.
- **Context is a render of the database.** Every message, eval, todo, namespace,
  and document the agent sees is a *renderable* projected from datoms by its
  schema — one recursive walker, two views (text for the model, HTML for the
  human). Fix the underlying data and the surface heals itself; nothing is
  stored that needs clearing ([[../concepts/reactive-context]]).
- **The pod is a read replica; the wire-server is the sole writer.** The pod
  (`seon.client`, a long-running Node process,
  HTTP/inspector on `7890`) does NOT embed datahike. It forwards every write
  over a Unix socket to `wire-server` (the central file-backed datahike writer,
  store at `data/clusters/default/store`) and reads local lazy db values, so
  **pod memory scales with the working set, not the corpus**.

A **cluster** = one DB + a root agent + N task agents. All coordination flows
through the DB. `bin/seon` supervises both processes
([[../process-management]]).

## How an agent wakes and runs

The agent loop (`seon.agent.loop`) is a
**fold of an FSM transition table over events derived from the run's data** —
not a thread that holds state.

1. **Wake.** An inbound message is a datom. The per-tx listener fires; the
   handler derives the agent's state from the local db snapshot. If `:idle` it
   opens a **run**; if already `:running` it renews the lease (the new message
   extends the sliding window). The idle→running open is atomic — a CAS on
   `:seon.agent/run` being absent, so two simultaneous wakes can't both open.
2. **Run.** A **run** (`seon.agent.run`) is
   the bounded unit of work a trigger opens. `run-loop!` drives turns until a
   BOUND fires: the WORK bound (`turn-limit`, a bumpable count) or the
   WALL-CLOCK bound (`deadline`, an absolute instant) — whichever first — or a
   verb closes the run from inside a turn.
3. **The fencing token.** The run-id is the fencing token. Every WORK tx LEADS
   with an in-tx CAS asserting the agent's `:seon.agent/run` STILL names this
   run; a write from a superseded or timed-out run aborts at the single writer
   (the database reports the lost authority, not a pre-read predicate).
4. **Turn.** One turn
   (`seon.agent.turn`) = open a turn → render
   the prompt → call the LLM → parse the reply into forms → eval each form in
   isolation (errors are envelopes, every form runs) → close the turn. The LLM
   is DeepSeek by default; provider/model are a DB-owned `:seon.ai/config` row
   seeded from `SEON_AI_*` env at first boot, live-editable thereafter.

The wall-clock watchdog and the schedule firer are the **one ticker** — there is
a single periodic beat, not a timer per agent.

## How context is assembled

The prompt IS a REPL session over the shared database. The context composer
(`seon.agent.ctx`) renders the agent's
**complete `:seon.agent/ctx` block set**, priority-sorted, top→bottom =
static→volatile:

- a static system header (the seon mechanics, sent as the LLM `system` message);
- the loaded namespaces as the body;
- the agent's own entity as a map;
- the live tile (what the human currently sees);
- reactive warnings + todos (derived — they render only while the underlying
  condition holds, then vanish);
- the comment-block transcript of past turns + the live readline.

Everything through `:namespaces` is the provider-cacheable prefix. Blocks are
SEED-COPIED into a fresh agent at creation (`seed-default-ctx!`) and overridden
per-agent through the ONE scope-aware verb pair `install!` / `remove!`. Renders
are functions, never stored: each block's `:seon.render/ai` slot is a verbatim
string (doctrine) or a symbol resolved late at render time. A broken block
renders an inline error line; it never breaks assembly.

## How rendering works

`render` (`seon.render`) is the whole system:
one recursive, guarded walker over a node's children in two views —
`:seon.render/ai` → String (the prompt) and `:seon.render/html` → hiccup (a
tile). The view is selected by **key presence, never a stored discriminator**.
Each slot is a fully-qualified symbol (or a literal); `seon.eval/lookup-value`
resolves it by walking `js/globalThis` with `cljs.core/munge` — one path that
serves both core fns (the shadow-cljs precompiled bundle) AND agent-defined fns
(written by `cljs.js/eval-str` at the same munged paths). A throwing or missing
render degrades to a legible value, never a crash. The web UI
(`seon.web.serve` +
`inspector`) streams the HTML view as a
Datastar SSE morph.

## How the database is reached

`seon.db` is the **sole DB API** — never touch
`datahike.api` outside it. Your universe is one connection: `seon.db/*conn*` is
bound before your code runs; never thread it, never open another.

- **Reads are synchronous.** `query` / `pull` / `entity` resolve against the
  current local db value (`@*conn*`) — compose them in straight-line code.
- **Writes return a Promise envelope.** `transact!` is `^:async`; it forwards
  the tx over the socket to the wire-server, and awaiting it yields an ENVELOPE
  (`::db/ok?` + `::db/tx-report` | `::db/error`), never a throw.
- **Schema-first.** `transact!` refuses any tx touching an attribute it doesn't
  recognize; `seon.schema/register!` is the single source of truth that
  auto-derives the datahike schema (see [[../concepts/code-as-data-runtime]] and
  the `/datahike` skill).
- **React by key.** `listen!` / `unlisten!` register per-tx callbacks — the wake
  trigger above is one such listener.

## How the codebase indexes itself

Seon is self-aware: the core's source, the agent's eval log, and the in-memory
analyzer state are three views of one code corpus
([[../concepts/code-as-data-runtime]]). At boot the pod indexes every namespace
into the DB as `:seon.ns` / `:seon.fn` / `:seon.schema` entities; the agent's
context, the program-graph replay on restart, and the publish gate all read from
that one place. There are **no entity "kinds"** — an entity is its attributes +
refs; you FIND by attribute-presence, IDENTIFY by `:db.unique/identity` attr,
RELATE/REMOVE by refs (see the datahike primer + `/datahike` skill).

## How the pod boots

The boot load order lives in
`seon.client`'s `-main` → `start-agent!`
(skipped iff `SEON_NO_AUTO_BOOT`). There is a single config seam and a single
boot entry.

**The one config seam.** `seon.config/load-manifest` reads ONE manifest —
`config/system.edn` by default, the path overridable by `SEON_CONFIG`, the
variant selected by `SEON_PROFILE` (aero `#profile`). The manifest is a pure
OPTIONAL override: absent (or `{}`) ⇒ byte-identical to a no-config boot.
Present ⇒ it curates skills (`include`/`exclude`), per-role context loadouts,
and routes. Add a new concern = one `:seon.config/<section>` schema + one
`resolve-<section>` fn + one key in `:seon.config/manifest`.

**Per-test / per-cluster recipe** — name your own manifest, zero src edits:

- `SEON_CONFIG=config/test.edn bin/test-cljs` — a test run loads its own
  loadout/routes/skills.
- `SEON_PROFILE=minimal bin/seon restart pod` — select a `#profile` variant of
  `config/system.edn`.
- `bin/acme` exports `SEON_CONFIG=config/acme.edn` — the isolated cluster
  curates independently.

**The boot load order:**

| # | Step | Customization seam |
|---|------|--------------------|
| 1 | `open-cluster-conn!` — ping wire-server, connect, transact pod schema, start listen adapter | env |
| 2 | **`boot-seed!`** (`conn` pinned as root `db/*conn*`) | — |
| 2a | `manifest = (config/load-manifest)` — read ONCE | **the seam** (`SEON_CONFIG`/`SEON_PROFILE`) |
| 2b | `:entity-schemas` / `:core-seed` / `:core-index` — APPEND-ONLY introspection (entity schemas, user+kb seed, program-graph index) | source |
| 2c | **routes + skills → `seon.state/reconcile!`** — the DECLARATIVE desired set, synced (upsert + retract-stale), scope `#{:config}` | **manifest** |
| 3 | `replay-program-graph!` — load the agent-authored DB layer (topo-sorted ns eval) | — |
| 4 | per-agent `create!` → `seed-default-ctx!` → `install!(resolve-loadout …)` | **manifest** (`:loadouts`) |
| 5 | `ai/seed-config-row!` from the `SEON_AI_*` env table | env |
| 6 | `bootstrap-turn!` (newly minted agents only) | — |

The keystone: boot-seed!'s two provenance layers. The `:core-seed` steps are
append-only introspection (never a desired set, never retracted). The routes +
skills are the ONE managed *declarative* population, written under origin
`:config` and synced through `seon.state/reconcile!` — so a route dropped from
the manifest, or a skill removed from disk, is RETRACTED on the next boot. The
seeding model lives in [[../../prds/agent-fsm/agent-runtime]].

---

## [JVM track — paused]

> Everything below describes the **paused** JVM main-app track (`./bin/run`,
> nREPL 7888 / HTTP 8080, embedded in-process datahike on LMDB, core.async
> flow). It is NOT the present — it is the convergence target the two tracks are
> moving toward (JVM = authoritative server + DB writer, CLJS = on-device
> replica + local fn execution). Kept here, not deleted.

### How a namespace comes alive (JVM)

When the system needs a namespace to be alive, [[../components/namespace-lifecycle]]
calls `ensure-instance!`. This creates a [[../components/context]] atom (the
namespace's mutable state container), injects it as `*ctx*` via
`intern + .setDynamic`, and wires the page render function discovered from the
code graph. HTTP routes are registered by `seon.ns.routes` via a `route-patterns`
data var consumed by the web router. The [[../components/runtime]] registry tracks
that the instance exists, its status, and when it started.

### How state changes propagate (JVM)

A namespace's state lives in its ctx atom. A `swap!` fires atom watches: one
debounces and persists to Datahike via the [[../components/database]] layer,
another triggers a global SSE broadcast (`seon.web.sse/refresh-all!`). With
`::track-clients? true`, a third watch renders per-client fragments and pushes
them over each http-kit channel; the browser (Datastar) swaps them in without a
reload. The watches, persistence scheduling, and SSE push are wired
independently in ctx.clj, not through a unified pipeline.

### How cross-namespace calls work (JVM)

When namespace A calls namespace B (especially a B in a separate agent JVM), the
call routes through [[../components/flow-topology]] via `topology/request!` —
register a promise, inject a message into the flow topology, block until reply.
If the target is a remote agent JVM, [[../components/harness]] handles the TCP
hop: serialize with Nippy, send over the socket, wait, deserialize. The response
flows back through a reply-router that delivers the promise. This is the
[[../concepts/request-reply]] pattern — one mechanism for local and remote
invocation.

### How the system boots (JVM)

Startup is orchestrated by Integrant, configured via Aero, in two phases.
**Phase 1** brings up foundational services: in-process Datahike connections
(against the LMDB store on disk), schema registration, the runtime registry, the
connection manager. **Phase 2** builds on it: the infrastructure flow starts
(with a `flow/ping` sync barrier, 5 s), the runtime database initializes, the web
server binds ports, the code-graph scanner runs its first pass in a background
future, and function instrumentation activates. Integrant's dependency graph
enforces the phasing — the dependency chain IS the readiness gate.

### Three state tracking mechanisms (JVM)

The JVM track tracks namespace state in three places: the **ctx registry** (atom
in ctx.clj — the "live state" view: ctx atom, render fn, client set, scheduler),
the **runtime registry** (atom + Datahike in runtime.clj — the "administrative"
view: started-at, status, config), and **flow/ping** (the flow topology's
"infrastructure" view of which processes run). They can disagree (running in the
registry but no ctx atom; ctx atom but no flow process), which is the
accumulated-state problem the live CLJS track avoids by deriving everything from
the DB.
