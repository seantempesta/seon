---
type: architecture
status: active
tags: [architecture, index]
---

# Current State: How Seon Works Today

> A narrative of how data flows through the system. For component details, follow the component links below.

## How a Namespace Comes Alive

Everything starts with a name. When the system needs namespace `seon.trading.positions` to be alive, [[components/namespace-lifecycle]] calls `ensure-instance!`. This function orchestrates a careful sequence: it creates a [[components/context]] atom (the namespace's mutable state container), injects that atom into the namespace as `*ctx*` via `intern + .setDynamic`, and wires up the page render function discovered from the code graph. HTTP routes for namespace web views are registered separately by `seon.ns.routes`, which provides a `route-patterns` data var consumed by the web router.

The result is a live namespace instance — it has state, it has routes, it can be reached by the web layer and by other namespaces. The [[components/runtime]] registry tracks that this instance exists, its status, and when it started.

## How State Changes Propagate

Once alive, a namespace's state lives in its ctx atom. When an agent (or any code) calls `swap!` on that atom, a chain of side effects fires. Atom watches on the ctx detect the change. One watch debounces and persists the new state to Datahike via the [[components/database]] layer. Another watch triggers a global SSE broadcast via `seon.web.sse/refresh-all!`, signalling all connected browser clients to refresh.

When a namespace instance is created with `::track-clients? true`, a third watch fires targeted per-client pushes: the server calls the namespace's render function with the new ctx value, converts the Hiccup output to HTML, and sends the fragment directly to each connected client via their http-kit channel. The browser, using Datastar, swaps the fragment into the DOM without a full page reload. This targeted path is used for namespace page views; the global broadcast path is used for system-wide refresh events.

The watches, persistence scheduling, and SSE push are all wired up independently in ctx.clj, not through a unified pipeline.

## How Cross-Namespace Calls Work

When namespace A needs to call a function in namespace B (especially if B runs in a separate agent JVM), the call routes through [[components/flow-topology]]. The caller invokes `topology/request!`, which registers a promise, injects a message into the flow topology, and blocks until the response arrives.

The message travels through the flow graph to the target process. If the target is a remote agent JVM, [[components/harness]] handles the TCP hop — it serializes the request with Nippy, sends it over the socket, waits for the agent to execute, and deserializes the response. The response flows back through the topology to a reply-router process, which delivers the original promise. The caller unblocks with the result.

This is the [[concepts/request-reply]] pattern — one unified mechanism for both local and remote function invocation. The flow topology is the routing backbone; the harness is just the TCP bridge for remote cases.

## How the Code Graph Powers Everything

Seon is self-aware. The [[components/code-graph]] maintains a complete map of the codebase in Datahike: every namespace, function, var, dependency edge, and schema registration. This graph is built by running clj-kondo analysis, extracting entities (functions, vars, deps), and ingesting them into the graph database.

The scanner runs at startup (in a background future to avoid blocking boot) and is triggered by the dev hook on code changes, keeping the graph fresh. Other components query it constantly: [[components/renderer]] uses it to discover render functions (functions whose `:malli/schema` output spec contains `:seon.render/html`). The namespace UI uses it to show dependency trees and caller chains. AI context builders use it to assemble relevant code for agent prompts.

The graph is read-heavy and write-infrequent — a new scan after code changes, then thousands of queries. The query API (`graph/query.clj`) provides high-level lookups: functions-in-namespace, callers-of, dependencies-of, functions-with-output-key.

## How Rendering Works

Given a data map with namespaced keys, the [[components/renderer]] finds the best function to render it. The algorithm is specificity-based ([[concepts/renderer-discovery]]): it examines each candidate render function's input schema, counts how many required keys match the data map, and picks the function that matches the most keys. There are two resolution paths with different tiebreaking:

- `resolve-renderer` (used by namespace page rendering) breaks ties by namespace proximity — a render function in the same namespace as the data wins over a generic one, then `.render` child namespace, then sibling, then distant.
- `find-renderer` (used by the general `render` API) breaks ties by recency (newest `updated-at` wins), then alphabetical qualified-name for determinism.

Render functions produce either `:seon.render/html` (Hiccup for browsers) or `:seon.render/ai` (structured text for agent consumption). The same data can be rendered both ways. Discovery is automatic — define a function with the right `:malli/schema` metadata, and the graph scanner finds it. No registration needed.

## How the System Boots

`[JVM track — paused]` The Integrant/Aero two-phase boot described here belongs to the paused JVM main-app track. The active track is the CLJS pod (Node, port 7890), which forwards writes to the separate `wire-server` central datahike writer over a Unix socket rather than opening datahike in-process.

Startup is orchestrated by Integrant, configured via Aero. [[components/system-lifecycle]] manages a two-phase boot:

**Phase 1** brings up foundational services: Datahike connections (on this JVM track, opened in-process against the LMDB store on disk), schema registration, the runtime registry, and the connection manager. These have no dependencies on the flow topology.

**Phase 2** builds on Phase 1: the infrastructure flow starts (with a sync barrier — `flow/ping` must succeed within 5 seconds), the runtime database initializes, the web server binds ports, the code graph scanner runs its first pass in a background future, and function instrumentation activates.

Integrant's dependency graph enforces the phasing — components declare `#ig/ref` dependencies that determine init order. There is no explicit readiness gate; the dependency chain IS the gate. A Datahike connection failure during Phase 1 fails fast, while a flow timeout in Phase 2 gives clear diagnostics about what's stuck.

## Startup load + config (CLJS pod, active track)

This is the canonical map of what loads when the pod boots and the ONE way to customize it. There is a single config entry (`seon.config`) and a single boot entry (`seon.client/boot-seed!`); everything else references this section.

**The one config seam.** `seon.config/load-manifest` reads ONE manifest — `config/system.edn` by default, the path overridable by `SEON_CONFIG`, the variant selected by `SEON_PROFILE` (aero `#profile`). The reader is aero, coherent with the JVM track's `seon.config`. The manifest is a pure OPTIONAL override: absent (or `{}`) ⇒ byte-identical to a no-config boot. Present ⇒ it curates skills (`include`/`exclude`), per-role context loadouts, and routes. Add a new concern = one `:seon.config/<section>` schema + one `resolve-<section>` fn + one key in `:seon.config/manifest` (the schema lives in [[../prds/agent-fsm/data-model]]).

**Per-test / per-cluster recipe** — name your own manifest, zero src edits:

- `SEON_CONFIG=config/test.edn bin/test-cljs` — a test run loads its own loadout/routes/skills.
- `SEON_PROFILE=minimal bin/seon restart pod` — select a `#profile` variant of `config/system.edn`.
- `bin/acme` exports `SEON_CONFIG=config/acme.edn` — the isolated cluster curates independently.

`bin/seon`, `bin/acme`, and `bin/test-cljs` all export/honor `SEON_CONFIG`/`SEON_PROFILE`; the spawned pod inherits them.

**The boot load order** (`seon.client/-main` → `start-agent!`, skipped iff `SEON_NO_AUTO_BOOT`):

| # | Step | Provenance | Customization seam |
|---|------|-----------|--------------------|
| 1 | `open-cluster-conn!` — ping wire-server, connect, transact pod schema, start listen adapter | — | env |
| 2 | **`boot-seed!`** (`conn` pinned as root `db/*conn*`) | — | — |
| 2a | `manifest = (config/load-manifest)` — read ONCE | — | **the seam** (`SEON_CONFIG`/`SEON_PROFILE`) |
| 2b | `:entity-schemas` / `:core-seed` / `:core-index` — APPEND-ONLY introspection (entity schemas, user+kb seed, program-graph index) | `:core-seed` | source |
| 2c | **routes + skills → `seon.state/reconcile!`** — the DECLARATIVE desired set, synced (upsert + retract-stale), scope `#{:config}` | `:config` | **manifest** |
| 3 | `replay-program-graph!` — load the agent-authored DB layer (topo-sorted ns eval) | runtime | — |
| 4 | per-agent `create!` → `seed-default-ctx!` → `install!(resolve-loadout …)` | — | **manifest** (`:loadouts`) |
| 5 | `ai/seed-config-row!` from the `SEON_AI_*` env table | — | env |
| 6 | `bootstrap-turn!` (newly minted agents only) | — | — |

The keystone of the unification: boot-seed!'s two provenance layers. The `:core-seed` steps are append-only introspection (never a desired set, never retracted). The routes + skills are the ONE managed *declarative* population, written under origin `:config` and synced through `seon.state/reconcile!` — so a route dropped from the manifest, or a skill removed from disk, is RETRACTED on the next boot (it can't persist as a stale datom). reconcile! upserts each row by its own `:db.unique/identity` (`:seon.route/name` / `:my.skills/name`) and retracts any managed (`:config`-origin) row absent from the desired set; the `:core-seed` introspection is outside scope `#{:config}` and is never touched. The seeding model lives in [[../prds/agent-fsm/agent-runtime]].

## Three State Tracking Mechanisms

The system tracks namespace state in three mechanisms:

1. **ctx registry** (atom in ctx.clj) — maps instance-id to a registry entry containing the ctx atom, render fn, client set, and scheduler. This is the "live state" view.
2. **runtime registry** (atom + Datahike in runtime.clj) — tracks instance lifecycle: when started, current status, configuration. This is the "administrative" view.
3. **flow/ping** — the flow topology knows which processes are running. This is the "infrastructure" view.

A namespace can be "running" in the runtime registry but have no ctx atom (if creation failed partway). Or it can have a ctx atom but no flow process (if it's a local-only namespace). Or the flow process can be alive but runtime shows "stopped" (if a restart was interrupted).
