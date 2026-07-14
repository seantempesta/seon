---
type: architecture
status: active
tags: [architecture, agent, flow, database, web]
---

# Seon Runtime Architecture

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

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
unit is the **block** (`:seon.agent.ctx/block`); the prompt, an agent’s **view**,
and the **root agent's** view (`/`) are each a derivation of the same blocks.

It is **dual-runtime**: a CLJ **JVM database server** (the authoritative writer +
heavy data processing, data-only) and a CLJS **agent and web runtime**, sharing
the `.cljc` **schema** layer. The JVM does not carry a second application or
renderer. The derived-state rule and transition table are CLJS (`seon.derive` /
`seon.agent.loop`), reached from the device the same way every read is — through
the database protocol against the replica.

**Client/server is the shape.** The pod reads a local immutable Datahike replica
and forwards writes through the database protocol to the single JVM writer. A
threaded database value is location-agnostic, and the work fence is arbitrated at
that writer. A replica attaches at one complete
`{database-id, branch, commit-id, t}` coordinate, applies committed transactions
in order, and repairs a gap without inventing state. Remote attachment,
bootstrap, state transfer, backfill, cancellation, and retention are owned by a
future remote-replication PRD; no particular remote algorithm is settled here.

## The core ideas

The thesis, unpacked into the ideas everything else serves. Each is backed by
a mechanism — a principle without a code anchor is a slogan. When designing
anything, ask: which of these does it serve, and which existing primitive
already expresses it? (A new noun is a parallel-system risk — map every
concept to `ns`/`defn`/`require`/refs/var-meta/a db value.)

1. **Everything is data in one graph.** One bitemporal db; every moving part —
   the loop, the prompt, a surface, the plan, the code itself — is a function of a
   frozen db snapshot. Replay, debugging, diffing, and time-travel are queries,
   not archaeology. Entities are attributes + refs (no kinds); provenance rides
   the tx entity. Anchors: `seon.db` (sole API), the single writer, [[data-model]].
2. **The language is the harness.** No hand-crafted tool catalog: agents act by
   writing Clojure against fully-specced fns over namespaced data. Every fn's
   in/out is schema'd in the program graph, so "what can process what I'm
   holding" is a Datalog join — relevance is computed, not curated. Prose only
   for what cannot execute. Anchors: `schema/register!` + `:malli/schema`,
   `:seon.fn`/`:seon.ns`, [[toolkit]]. (Trajectory, bounded by the measured
   present: the minimal base plus relevant namespace source beats a standing
   skills manual, and composition functions render their complete relevant
   value — [[laws]].)
3. **The agent authors its own environment.** A `defn` returning
   `:seon.render/ai` and/or `:seon.render/hiccup` supplies one or both render
   twins —
   writing a function IS authoring context, tools, and UI at once. Both keys =
   twins: agent and human look at ONE derived value; shared situational
   awareness is structural, not messaged (canvas-first mitigates fabrication —
   prose is where agents lie, [[laws]]). Every specced fn an agent writes
   teaches the system when to surface it. Anchors: the render twins,
   `install!`/`remove!`, current-ns auto-run — [[context]].
4. **Perpetual motion = plan + location + window.** Grounding that survives
   forever: purpose + the `my.plan` anchor (WHAT I'm doing) + current-ns
   (WHERE I am) + the sliding transcript window (what I'm doing RIGHT NOW). A
   1000-turn agent behaves like a turn-3 agent because context is derived,
   windowed, and cache-stable — never accumulated and never replaced by a
   lossy conversation summary. Raw recent events occupy a bounded window;
   intent and knowledge that must survive it are ordinary plan/database facts
   that remain directly queryable. (The claim under test: the
   plan-survives-restart bench is its measurement.) Anchors: [[context]] §order.
5. **Measured, not asserted.** The context/tool surface is a fitness landscape:
   every dial is config data, so contexts are A/B'd and selected against frozen
   benchmarks (`pass^k`, the eval ledger). The laws in [[laws]] are
   measurements, not opinions. Every scorer check must be stated in the agent's
   context, or the bench measures prompt-omission. Endgame: the agent adjusts
   its own initial-context config and the loop selects what works.
6. **Failures are data; core faults fail admission.** Agent and user failures
   become `:seon/error` values in derived surfaces. A failed core publication or
   readiness transition records one bounded core fault and, in development,
   may intentionally fail the process/readiness gate. Units share data rather
   than mutable application state; the writer arbitrates total order and late
   writes fail the CAS work fence. Anchors: [[agent-runtime]] and
   [[observability]].
7. **One human, one bond; local compute first.** The runtime serves ONE human;
   the canvas is the shared value the pair looks at together. Ordinary
   personal-data work executes on local models when the measured capability
   permits it. A stronger hosted model is an explicit consultant for planning,
   ambiguity, or recovery, not an inference tax on every tool call. Model
   routing preserves the same context, functions, schemas, database facts, and
   Inspect evidence across both roles, so escalation changes compute rather
   than inventing a second agent system. Infrastructure choices answer to that
   relationship — modest-hardware operation, on-device privacy, honest
   termination, and the agent's own code as the compounding asset serving its
   human. Anchors: the root agent, [[agent-runtime]], `docs/seon/vision/` (the
   Seon premise).
8. **The horizon: think in Clojure, translate out.** Index ANY codebase into
   the same graph (LSP); plan/solve in Clojure and translate into the client's
   language — the Clojure artifact is the executable spec; seon-writes-seon
   behind the tests-and-validations publish gate. Vision tier:
   `docs/seon/vision/think-in-clojure.md`.

## Glossary

One vocabulary, each name grounded in a namespace + a schema/fn.

- **block** — the context unit: a function-of-the-DB map with up to two renders,
  seeded into the agent's own `:seon.agent/ctx` and rendered in
  `:seon.agent.ctx/priority` order. `:seon.agent.ctx/block` in `seon.agent.ctx`.
  `:seon.agent.ctx/name` is a plain `:keyword` — the app-level upsert key (prompt
  header = DOM `#surface-<name>`), NOT a datahike identity.
- **render** — a block's output; the one word for the projection. Engine:
  `seon.render`. A render is never stored.
- **ai render** — a block's prompt-text output (a string, or a symbol late-resolved
  via `seon.eval/lookup-value`). `:seon.render/ai`.
- **html render** — `:seon.render/html` selects the function that produces the
  human render; its response carries `:seon.render/hiccup`, which becomes a
  surface.
- **prompt** — the agent's assembled context: ai renders concatenated by priority
  (`seon.agent.ctx/render-context`), prefixed by a system role resolved by
  `seon.ai/effective-system-prompt` — the per-request `:seon.ai/system-prompt`
  override → the cluster's `:seon.config/system-text` datom → the shipped
  `seon.agent.ctx/system-text` default. The system prompt is DB state and
  per-request overridable ([[context]] §"The system prompt itself is DB state").
- **page** — the human's UI: a layout placing html renders into slots. `seon.ui.*`.
- **surface** — a resolved html render displayed by the web UI.
- **card** — a compact/expanded visual CSS component; never an architectural
  object or persisted entity.
- **slot** — a named, DB-keyed hole in a layout: `(slot :name)` →
  `[:div {:id "surface-<name>" :data-slot :name}]`, keyed on
  `:seon.agent.ctx/name`.
- **layout** — a render whose hiccup contains slots (it nests surfaces); a role,
  not a stored kind. A render with no slots is a leaf surface.
- **canvas** — the focal, agent-controlled surface in an agent view: the
  agent↔human communication area.
- **view** — an agent's page, route `/agent/{id}`: the canvas plus a
  recency-ordered rail of the agent's other html surfaces.
- **root agent** — ONE `:seon.agent/id "root"` that is BOTH the `/` system-view
  owner (UI) AND the system orchestrator (lifecycle) — the same elevated grant,
  never two entities. Its all-agents overview uses a dedicated system layout
  over the SAME blocks, render units, route resolution, and live-morph machinery
  as ordinary agent pages. It holds the elevated system-level lifecycle
  functions (`start!`, terminate, cross-agent) in its
  discoverable context; those functions enforce their own caller rules. Its
  registered home callbacks still pass through the ordinary `/call` browser
  gate. It is the root of the render + route
  tree (`/` → `/agent/{id}` → apps) and the base case of the bootstrap recursion
  (root has no `:seon.agent/parent`). See [[agent-runtime]].
- **app** — an agent-authored sub-page, route `/agent/{id}/app/{x}`.
- **route** — a datom mapping a URL pattern to a layout, consumed by reitit.
  `:seon.route/*` (`seon.route` ns).
- **reitit** — the front door: a router that is a pure derived value of the route
  datoms (vendored, `reference-code/reitit`, `.cljc`). The capability gate
  (`seon.web.reactive.call`) authorizes the fn behind `/call`. See [[ui]].
- **warnings block** — the block surfacing current problems: an ai render to the
  agent, error cards to the human, one source. `seon.agent.ctx.warnings` + `seon.warn`.
  Self-healing — empty when clean.
- **`:seon/error`** — the structured value an agent, user, provider, or guarded
  runtime-boundary failure produces: one base shape
  (`:seon.error/message`, structured `:seon.error/data`, where/symbol/hint),
  specialized only on real shape divergence. A core publication/readiness
  failure instead records one `:seon.error/fault :core` and fails admission in
  development or returns the configured bounded production fallback. See
  [[data-model]] and [[observability]].
- **seed-copy** — the seed/override mechanism: ALL blocks are copied into the agent's
  own `:seon.agent/ctx` at creation; render reads that COMPLETE set sorted by
  priority. There is no render-merge, no separate default set, no provider.
- **`install!` / `remove!`** — the sole seed/override functions, in `seon.agent.ctx`.
  `install!` is scope-aware + variadic (a single map OR a vector-of-maps); idempotent
  upsert-by-`:seon.agent.ctx/name`; at boot/no-scope it builds the default seed set,
  in an agent's scope it targets that agent's `:seon.agent/ctx`. `remove!` drops by
  name (component cascade). Override = `install!`/`remove!`, period — for core,
  downstream namespaces, and agents themselves.
- **orchestrator-root** — the lifecycle facet of the root agent: it `start!`s and
  manages child agents through `/call`, writing `:seon.agent/parent`. See
  [[agent-runtime]].
- **run / turn / derive-state** — the bounded unit of work a trigger opens
  (`seon.agent.run`), the per-iteration value-transform (`seon.agent.loop`), and the
  one projection rule for agent state (`seon.derive`). See [[agent-runtime]].
- **database server / pod / transaction listener** — the JVM Datahike writer;
  the Node CLJS agent and web UI runtime; and the
  `listen! → derive → push` role inside the pod.

## Deployment topology

The normal local cluster has two processes and three logical roles:

- **JVM database server** — the sole authoritative datahike writer (durable,
  bitemporal)
  plus bounded heavy processing (embeddings and indexing). Data only; it never
  executes agent code or serves a second web application.
- **Node UI host** — the browser's single front door: a read-only replica + one
  tx-listener that derives every agent’s **view** (including the root agent’s view
  at `/`) from `:seon.agent/ctx`, holds the route table, and streams patches. The
  streamer is a **role, not a process** — any process holding a replica + a
  tx-listener can play it, so the UI-host is relocatable.
- **Agent execution** — the pod owns the one bounded SCI execution service and
  agent loops. Per-agent workers or microVMs may later implement the same
  data-only execution contract; they do not move database authority out of the
  JVM server.

The JVM server is one process. The CLJS pod is the second process and combines
the UI-host and agent-execution roles. Remote servers, thin clients,
mobile packaging, and stronger execution isolation are separate target domains,
not alternate local mechanisms.

### Downstream distribution boundary

Seon's source repository is the release producer; a downstream product does
not require a Seon source checkout. One release operation publishes a
coordinated standalone database-server uberjar and a relocatable Node runtime +
consumer SDK. The runtime package contains the immutable pod closure, self-host
bootstrap, bounded program-source corpus, static web assets, base config,
production Node dependencies, semantic operator, and license/notices. The SDK
contains the public CLJS source/macros and build entry needed to compile a
consumer's namespaces into the same runtime.

One compatibility manifest binds the Seon release/source revision, database
protocol and config/SDK versions, Java/Node requirements, writer/runtime/
bootstrap/source/assets digests, maintained Datahike/Konserve dependency
identities, npm lock, and license/SBOM metadata. A downstream package embeds
that manifest plus its own source/dependency/config/brand digest. Build and
startup reject an incompatible or mixed set before database mutation; neither
selects a mutable “latest” dependency or recompiles in production.

The downstream repository owns its declared Clojure and npm dependencies,
compiled namespaces/preload, config delta, routes and renders, brand CSS/assets,
secrets, and deployment policy. It pins a released Seon SDK/runtime and invokes
the shipped build/operator commands. Development projects add a compiler
watcher to the same process graph; immutable packaged operation runs only the
writer and pod. Product customization composes public data and function seams;
it does not patch Seon source or introduce a second runtime mechanism.

## Cross-cutting principles

### DB-as-bus

Units are isolated in **compute** but share one **DB**, so "pull together data from
all of them" = read the one DB they all write to — there are no silos to aggregate.
The reactive loop, end to end: a browser action POSTs a fact → the writer commits and
fans the tx out → the relevant unit's `listen!` fires, it computes and writes its
**facts** back → the writer fans out → the Node UI-host's `listen!` fires,
re-renders the WHOLE element (`view = f(db-at-coordinate)`) and pushes one gzip datastar
**morph**, which idiomorph diffs client-side (a coalescing throttle collapses a tx
burst into one morph). Two channels share the database protocol with different reliability:
reads/writes/heavy calls ride a **request-reply RPC** (only *values* cross —
`:db.fn/cas` is data and crosses fine, closures can't); the **tx feed** is a separate
broadcast made lossless across reconnect by a full attachment/commit cursor and
branch-local tx replay from the bitemporal log (this is the pod↔writer
replication layer—the dropped event
is the wake trigger, so it must not be lost; the browser stream needs no UI-side
`since-t`, it just repaints `view = f(db)` on reconnect). **No agent code ever touches
an SSE connection** — agents write facts; the UI-host derives and streams. The
writer publishes raw committed-transaction frames and bounded replay pages; it
does not persist query subscriptions, changed-row summaries, or another
in-process invalidation bus.

### Derive everything

Everything — the loop, the prompt, an agent's view, a status view, the work
bound, the agent's state — is a **function of the DB at render time**; nothing
derivable is stored. Agent state is the `seon.derive/derive-state` projection of
primitives (an open run, `paused-at`, `terminated-at`), never a stored field. The
work bound is `default-turn-limit` + the inbound-message count, not a per-message
write. Renders are projections, never persisted. New ways to surface data are new
block render fns, not new mechanisms — when the underlying problem is fixed, the
query returns empty and the surface vanishes (self-healing). Frozen caches key
on the full resolved `{database-id, branch, commit-id, t}` coordinate, never on a db
value or bare t.

### Failures are data; core faults are explicit

Agent-authored, provider, input, capability, and handler failures are caught at
their boundary and represented as `:seon/error` values. A core publication or
readiness failure is recorded once with `:seon.error/fault :core`; development
fails loudly at the owning transition, while a production boundary may return a
bounded fallback. Detailed diagnostics are pulled on demand rather than injected
as a standing census. The full failure map lives in [[data-model]].

### Dependency-aware tests, one runner per runtime boundary

Fast feedback is a projection of the same program graph, not a third test
harness. CLJS correctness uses the one CLJS runner; retained JVM database-server
correctness uses the one writer runner; agent/model behavior uses Inspect. A
source edit selects declared tests through proven namespace/function/schema
dependency edges and reports why each test was selected. Selection is
conservative: incomplete analyzer data, dynamic lookup, macro/build/config
changes, deletions, or a cross-runtime contract widen to the owning namespace,
dependency closure, runtime tier, or full checkpoint. A selector may do extra
work but must never silently omit a possibly affected test.

The compiler artifact or watch process remains warm only behind an exact source
fingerprint, and test database/runtime fixtures remain isolated from the live
pod. Individual vars and affected namespaces therefore reuse current compiled
code instead of recompiling the whole suite, while the complete checkpoint
periodically tests the selector and runner themselves. Disabled suites,
generated historical results, bespoke drive scripts, and parallel harnesses are
not an optimization cache; they are deleted once their current behavior is
covered by the owning runner.

### Seed-copy, not merge

ALL blocks are copied into the agent's own `:seon.agent/ctx` at creation; render
reads that complete set sorted by `:seon.agent.ctx/priority` and stops. There is no
render-merge over a separate default set, no provider, no central catalog. Override
is `install!`/`remove!` against a scope (boot scope builds the default seed set;
agent scope targets that agent). The `my.*` nses define the render fns + block data
and are batch-installed at seed. See [[ui]].

### Roles are capabilities

A role = the discoverable functions/context plus the guarded operations an agent
can perform, NEVER a stored `:kind`/`:role` enum. "Orchestrator" sees the
spawn/terminate/system functions; "worker" does not, while each operation owns
its enforcement. The `/call` gate covers registered browser callbacks in an
agent's home namespace; it does not authorize direct REPL/eval calls to core
lifecycle functions. This is the entity-level case
of the general rule: an entity's kind is the attributes it carries, never a stored
discriminator (see [[data-model]]).

### Code as data

The core's source, the agent's eval log, and the analyzer state are three views of
one code corpus. Agent-defining forms persist as `:seon.fn` / `:seon.ns` /
`:seon.schema` entities; the DB IS the running system (query → reconstitute →
topo-sort by `:seon.ns/require-edges` → load; redefine = upsert). Agent birth
commits its context components, home namespace/require rows, and safe declaration
facts atomically. After commit, the runtime loads those declarations without
manufacturing quiet eval/transcript rows. The agent sees the resulting facts and
code through ordinary context projections. See [[agent-runtime]].

## The domains

### Data model — [[data-model]]

Everything is namespaced datoms in one bitemporal DB; the agent record is the root of
its context and its loop control, and everything else is reachable from it or derived.
The doc owns every entity/attr/type, the three relationship kinds (datahike ref,
identity/lookup attr, symbol-as-value late binding), the `:seon/error` model + the
entity-kind-vs-value-enum rule (kind = attribute presence, never a stored
discriminator), and the domain schemas: `my.kb` (no agent ref → global, one KB all
agents see), `my.plan` (a TREE via `:my.plan/parent` + derived roll-up, scoped per
agent by `:my.plan/agent`), and `my.agent` (`:my.agent/purpose`, the per-agent seed
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
idle-entity, fact-first initialization, the **orchestrator-root** lifecycle
(`start!` = a core function surfaced to root, writing `:seon.agent/parent` and
enforcing its own caller/depth rule; roles-as-capabilities; root = the cluster-boot base case;
UI-root == orchestrator-root), and the one execution-service contract. Backend
isolation choices belong to their owning implementation PRD. See
[[agent-runtime]].

### UI — [[ui]]

The human UI is **pages** — a **layout** placing block html renders into named
**slots**, each filled slot a **surface**; all pages are agent **views**, a tree of
routes: the root agent’s view (`/`), per-agent views (`/agent/{id}`), and apps
(`/agent/{id}/app/{x}`). Routing is data via **reitit** over `:seon.route/*` datoms;
`/call` is the one browser-action door and its gate authorizes registered
agent-owned home callbacks (it is not lifecycle authorization). The **live
channel is ours**: one tx-listener on a read-replica derives
every view and streams it as a per-connection gzip whole-element **morph**
(idiomorph-diffed client-side); reconnect just repaints `view = f(db)`, no UI-side
`since-t` replay. The doc owns block/render/canvas/slot/layout, the page tree,
reitit + the gate, the SSE channel, and the seed-copy + variadic `install!`/`remove!`
override model. See [[ui]].

### Toolkit — [[toolkit]]

The agent's editable composition layer is the intended `my.*` corpus:
`my.blob`, `my.canvas`, `my.data`, `my.kb`, `my.ns`, `my.plan`, `my.skills`, and
`my.ui`. Protected filesystem, search, shell, web, lifecycle, and evaluation
capabilities remain under `seon.*` and are exposed only by an agent's curated
home requirements. Exact function contracts are discoverable program facts,
not a second catalog in architecture prose.

### Observability — [[observability]]

Every question about agent behavior — what an agent saw at turn N, what changed
between turns, why it acted — is answered by a **query against the database plus
the blob archive**. Process logs remain operational evidence rather than turn
truth. Each turn persists the frozen
`{database-id, branch, commit-id, t}` coordinate that makes context
re-derivable, the assembled prompt
verbatim as a blob, and the raw reply. `agent-debug/turn` reconstructs any turn;
`turn-diff` shows what changed between two; a dedicated **forensic agent** runs
these queries on demand; the `/agents/run` door drives a reproducible task
through an agent in the pod's own cluster for an external harness. See
[[observability]].

## Documentation boundary

Architecture documents own timeless intended mechanisms, vocabulary, and
boundaries. [[roadmap]] alone owns current implementation state, gaps, work
order, dates, measurements, and acceptance evidence.

## Detail docs

- [[data-model]] — entity shapes, attributes, relationship forms, error values,
  and the `my.kb`/`my.plan`/`my.agent` schemas.
- [[agent-runtime]] — loop/run/turn/FSM/derived-state/two-bounds, creation-as-idle,
  bootstrap-as-seeded-forms, orchestrator-root lifecycle, and the one
  execution-service contract.
- [[ui]] — block/render/canvas/slot/layout, the page tree, reitit + the capability gate,
  the gzip-morph SSE live channel, the seed-copy + `install!`/`remove!` override.
- [[toolkit]] — the `my.*` function catalog over the protected `seon.*` floor.
- [[observability]] — historical turn reconstruction (resolved coordinate + prompt blob + reply), `agent-debug/turn` /
  `turn-diff`, the blob archive, the forensic agent, cluster lifecycle + the
  `/agents/run` door.
- [[context]] — the dynamic context system: `context = f(db, location,
  window, tail)`, the three-band cache gradient (stable prefix / sliding
  transcript window / free dynamic tail), namespace-as-location, pull-first
  relevance retrieval, and the UI twin of every band.
- [[laws]] — the drive-measured empirical laws (render-prominence,
  cache-stability, canvas-first, pass^k, keep-iff-lifts-battery) that
  constrain every design above. Not principles — measurements.
- [[roadmap]] — current implementation state, gaps, work order, and evidence.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset (db is
  a value, only values cross the database protocol, CAS-as-assertion,
  resolved-coordinate caching). Read before
  touching the loop.
- [[library-grounding]] — the current concept-to-source read map for Datahike,
  Malli, SCI, Reitit, Datastar, and the test selectors.
