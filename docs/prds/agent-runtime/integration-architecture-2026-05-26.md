---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Integration Architecture — V0/V2 Database Layer Merge

**Date:** 2026-05-26 PM
**Scope:** This doc supersedes the file-layout + naming sections of `pod-host/sidecar-poc/SESSIONS.md` and `pod-host/sidecar-poc/AGENT.md`. The phased transition plan at `v0-to-v2-transition-plan-2026-05-26.md` references this doc for the target architecture.

**Revised 2026-05-26 PM (post-audit):** This doc was initially written assuming several pieces would be built from scratch. The anti-rewrite audit at `integration-anti-rewrite-audit-2026-05-26.md` found that ~50% of those pieces already exist. This doc has been updated to reflect reuse-first paths. The "Anti-rewrite manifest" §7 below is now the authoritative reuse list.

**Revised 2026-05-26 EVE (two-path decision):** The flow-runtime-update spike (`research/flow-runtime-update-spike-2026-05-26.md`) found that dynamic flow registration works but with non-trivial cost (in-flight requests orphan on swap, tx-bus subscribers lost, integrant state stale). Decision: **MVP ships two-path** — Path A (existing seon.clj through `:seon.db/flow`) is left undisturbed; Path B (agent session DBs through a direct-conn registry, no flow) is new. They coexist in one JVM. Flow integration is deferred until we want remote-host session DBs or seon.clj is otherwise stable enough to refactor. See §1.5.

**Locked 2026-05-27 (post-wave-4a):** Terminology, replay model, and runtime-atom consolidation locked after user direction. Notable updates folded in below:

- Backend reality: `:sqlite` is **deferred from MVP** — konserve-jdbc 0.2.91 does not register on konserve 0.9.340's dispatch multimethod. MVP uses `:file`; `:memory` for tests only. See §4.
- Replay model: **dumb per-form replay** is the MVP gate. Smart ns-batched replay via the analyzer + substrate-source seeding are deferred follow-ups. See §12.
- One Integrant component (`:seon.server.session/registry`) manages N sessions via the atom. NOT N components, NOT a separate `:seon.server/wire` component — the registry IS the wire-bearing component. See §5 and Wave 5 in `execution-waves-2026-05-26.md`.
- `:agent` deps alias renamed to `:agent-jvm-pool` (legacy, opt-in). Callsites updated.
- `seon.runtime`'s three private atoms (`generated-ids`, `registry-cache`, `flow-handles`) collapse into one `seon.runtime/!self` atom with three keys. See §13.
- Multi-host topology: MVP = one host, N wasm guests. Tauri shell = one-host-per-user. Scale-out to N hosts via TCP swap is deferred (no work in MVP).
- Wave 4a moved wire-server **verbatim** (Option B: socket-per-session routing, NOT per-request). The registry owns the socket↔conn pair; handlers stay single-conn at runtime. Multi-session = multiple wire-server instances managed by the registry.

## 0. TL;DR

- **One JVM** owns all datahike DBs (not multi-JVM like the V2 PoC did).
- **Two paths inside the JVM** (MVP decision — see §1.5):
  - **Path A** (existing): `:seon.db/flow` for `:seon.runtime`, `:seon.session`, `:seon.orchestrator`, `:seon.repl`, etc. Used by web UI, dev hook, inspector, agent JVM pool. **Untouched.**
  - **Path B** (new): atom-backed `seon.server.session/registry` for `:seon.session/<name>` agent DBs. Direct datahike, no flow. Wire-server routes CLJS requests here.
- **Per-session DBs** `:seon.session/<name>` are created at runtime via `(ensure-db! …)`; agents (wasm guests) connect via the wire-server. N agents can share one session DB (collaboration) or each have their own (isolation).
- **SQLite backend already works on JVM** via konserve-jdbc + xerial/sqlite-jdbc — V2 PoC `jvm-writer/writer.clj:61-70` uses it today. Reuse that config pattern in the registry.
- **Wire-server already exists** at `pod-host/sidecar-poc/jvm-writer/` (battle-tested, 47/189 tests). Port to `src/seon/server/`; rewrite handlers to lookup conn from the registry instead of using their own private one. Codec/transit/broadcast helpers move verbatim.
- **Session entity schema already exists** at `src/seon/orchestrator/session.clj` (609 LOC). Rename → `src/seon/session.clj` (consolidating with the existing 472-LOC `seon.session`). Extend with `::backend` and `::path`.
- **seon.clj is undisturbed.** No temp breakage during MVP. Flow integration deferred to a later PRD.

## 1. Conceptual model

```
┌─ One JVM ──────────────────────────────────────────────────────────┐
│                                                                    │
│   :seon.runtime          master DB (existing) — runtime registry,  │
│                          orchestrator entities, session entities,  │
│                          inspector data                            │
│   :seon.session/default  default session DB                        │
│   :seon.session/alice    named session for "alice"                 │
│   :seon.session/bob      named session for "bob"                   │
│   :seon.session/tmp-XYZ  ephemeral (:memory) — for tests           │
│                                                                    │
│   seon.db.relay (existing 339 LOC) ──► adapted to UDS + CBOR for   │
│                                       CLJS guests; routes by      │
│                                       :db-name to datahike conn   │
│                                                                    │
└────────────────┬───────────────────────────────────────────────────┘
                 │ UDS (CBOR control + Transit-JSON values)
   ┌─────────────┼─────────────┐
   │             │             │
[agent A]   [agent B]   [agent C]    ← wasm CLJS guests
in session   in session   in session
"alice"      "alice"      "bob"

Agents A and B share alice's DB (collaboration).
Agent C is isolated in bob's DB.

```

## 1.5. Two-path inside the JVM (MVP decision)

```
┌─────────────────────── ONE JVM ─────────────────────────────────────┐
│                                                                     │
│  Path A — existing seon.clj                                         │
│      :seon.db/flow (core.async.flow)                                │
│        ├─ :seon.runtime   conn-process ─► datahike conn (master)    │
│        ├─ :seon.session   conn-process ─► datahike conn             │
│        ├─ :seon.orchestrator ...                                    │
│        ├─ :seon.repl      ...                                       │
│        ├─ :seon.flow      ...                                       │
│        └─ :seon.phase2.demo ...                                     │
│      Used by: web UI, dev hook, REPL, inspector, agent JVM pool,    │
│               orchestrator sessions. Existing CLJ code.             │
│                                                                     │
│  Path B — new wire-server (NO flow)                                 │
│      seon.session/registry (atom of {db-name -> conn})              │
│        ├─ :seon.session/alice  ─► datahike conn (direct)            │
│        ├─ :seon.session/bob    ─► datahike conn (direct)            │
│        └─ :seon.session/...                                         │
│      Used by: wire-server handling CLJS guest requests via UDS.     │
│      Adapted from pod-host/sidecar-poc/jvm-writer (battle-tested,   │
│      47 tests / 189 assertions).                                    │
│                                                                     │
│  The two paths share: the JVM, the schema registry, Malli, the      │
│  inspector (via tx events). They do NOT share conns or routing.     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Why two paths instead of integrating session DBs into the flow:** the spike (`research/flow-runtime-update-spike-2026-05-26.md`) verified that flow rebuilds work but orphan in-flight `pending-promises`, drop tx-bus subscribers, and stale Integrant state on every dynamic registration. For agent runtimes making many concurrent DB calls, the cost is real. The direct-conn registry in Path B has none of that — `(swap! sessions assoc db-name (d/connect cfg))` is atomic, idempotent, cheap.

**What we lose**: no flow remoting for session DBs (Path B is JVM-local-only); listener routing duplicated between flow's tx-bus and the new wire-server broadcast; "one DB system" elegance temporarily fractured into two systems.

**What we gain**: ship in ~7-9h instead of ~11-16h + spike unknowns; seon.clj is undisturbed (no temp breakage); the entire Path B code already exists and is tested in the V2 PoC — we just move it.

**Convergence later**: when seon.clj is stable enough to refactor AND we want remote-host session DBs, we plumb Path B through flow (or rip flow and unify on direct-conn). That's a separate PRD; not blocking V2.

## 2. Definitions (precise — locked 2026-05-27)

| Term | Meaning | Identifier |
|---|---|---|
| **session** | One DB + konserve store + wire-server socket pair. The unit of data sharing. Persistent (file/sqlite) or ephemeral (memory). Named via the existing ID generator; no hardcoded names except `:seon.runtime` master. One session can hold N agents. | `:seon.session/<name>` keyword |
| **agent** | One wasm runtime joining a session. N agents per session = collaboration; 1 agent per session = isolation. | `:seon.agent/<id>` (14-char hex) |
| **seon** | THE seon JVM Clojure system (the server). Canonical name. | n/a |
| **wire-server** | UDS server in the seon JVM that handles wasm guest requests. Lives at `src/seon/server/`. Wave 4 landed it. | n/a |
| **guest-cljs** | CLJS source compiled to `wasm32-wasip2` via `wasm-rquickjs`. Future siblings: `guest-python/`, etc. | n/a |
| **host** | Rust binary (was "rust-host", "wasmtime-host"). Loads wasm components, manages session→socket routing. Wrapped by `shell` for product builds. Lives at `host/` (rename from `pod-host/sidecar-poc/rust-host/` due in Wave 6). | n/a |
| **shell** | Tauri packaging that wraps `host` for desktop distribution. Not built yet. | n/a |
| **master DB** | `:seon.runtime` (existing). Path A. Hosts runtime registry, orchestrator entities, session-entity records, inspector data. Not a session — the JVM's own primary DB. | `:seon.runtime` |

**Retired terms** (do not use in new docs): "sidecar", "PoC", "pod" (V0-only; retire at cutover), "platform" (as a directory name), "jvm-writer", "rust-host".

**The DB doesn't know about agents directly.** The session is the storage unit. Agent identity is metadata on datoms (e.g., `:seon.db/agent-id` stamps via tx-context).

**The DB doesn't know about agents directly.** The session is the storage unit. Agent identity is metadata on datoms (e.g., `:seon.db/agent-id` stamps via tx-context).

## 3. File storage layout

```
seon/data/                                ← project-local; gitignored
│
├── seon.runtime/                         ← master :seon.runtime DB
│   └── store.sqlite                      ← konserve-jdbc:sqlite (existing dep path)
│   (or store/ tree if file backend during transition)
│
├── sessions/
│   ├── default/                          ← :seon.session/default
│   │   └── store.sqlite
│   ├── alice/                            ← :seon.session/alice
│   │   └── store.sqlite
│   └── <name>/
│       └── store.sqlite
│
└── blobs/                                ← future: content-addressed blob store

```

- `:memory` backend never creates a directory.
- Directory presence on disk does NOT auto-register the session — registration happens via the API (see §5).

## 4. Backend defaults (revised 2026-05-27)

| Use case | MVP backend | Eventual | Reason |
|---|---|---|---|
| Master `:seon.runtime` | `:file` | `:sqlite` | Production data; long-lived |
| Named session `:seon.session/<name>` | `:file` | `:sqlite` | Persistent by default; named = caller cares |
| Auto-id ephemeral session `:seon.session/tmp-<uuid>` | `:memory` | `:memory` | Tests only |

**`:sqlite` deferred from MVP.** Wave 1a discovered that `io.replikativ/konserve-jdbc 0.2.91` does not register on `io.replikativ/konserve 0.9.340`'s `konserve.protocols/-PEDNKeyValueStore` dispatch multimethod — `(d/create-database {:store {:backend :jdbc ...}})` throws `Unsupported store backend: :jdbc`. The fix is either a konserve version alignment or an explicit `(require 'konserve-jdbc.core)` on the load path; deferred until needed. There is a TODO comment in `src/seon/server/store.clj`.

**`:memory` backend is for testing ONLY.** Anything that needs to persist data uses `:file` for now and `:sqlite` once the dependency mismatch is resolved. The earlier note that "SQLite backend already works on JVM via konserve-jdbc + xerial/sqlite-jdbc" referred to the V2 PoC's `jvm-writer/writer.clj:61-70`; that pattern works there but does NOT round-trip through `datahike.api/create-database` on the current `deps.edn` pin. See `pod-host/sidecar-poc/jvm-writer/deps.edn` for the working coords if/when we revisit.

## 5. Session registration

**Two-tier**: live conns in a process atom + durable entities in the master DB.

**Live tier** — `seon.server.session/registry` atom:

```clojure
(defonce ^:private registry (atom {}))
;; shape: {db-name -> {:conn <datahike-conn> :backend :sqlite :path "..." :pub-chan <chan>}}

(defn ensure-db!
  [{::keys [db-name backend path] :or {backend :sqlite}}]
  (or (get @registry db-name)
      (let [cfg (build-config db-name backend path)
            _   (when-not (d/database-exists? cfg) (d/create-database cfg))
            conn (d/connect cfg)
            entry {:conn conn :backend backend :path (resolve-path db-name backend path)}]
        (swap! registry assoc db-name entry)
        ;; durable side-effect: transact session-entity into master DB
        (record-session-entity! db-name entry)
        entry)))
```

Atomic, idempotent, cheap. Direct datahike — no flow, no orphan risk. The wire-server handlers look up the conn via `(:conn (get @registry db-name))` per request.

**Durable tier** — extends the **existing** `seon.orchestrator.session` schema (609 LOC, in production). NOT a new parallel `:seon.session/*` entity set. The existing schema already has `::db-name` (orchestrator/session.clj:59), `start-agent-session!`, `stop-agent-session!`, `list-agent-sessions`, and a live-vs-persistent state pattern.

**Phase task**: rename `src/seon/orchestrator/session.clj` → `src/seon/session.clj` (consolidating with the existing 472-LOC `seon.session`, replacing it with the richer orchestrator version). Extend the schema with two new attrs: `::backend` (`:enum [:sqlite :file :memory]`) and `::path` (`:string`). All existing schema + lifecycle fns survive.

```clojure
;; entities in :seon.runtime DB (existing schema + 2 new attrs):
{:seon.session/name       :alice
 :seon.session/db-name    :seon.session/alice  ;; already exists in orchestrator/session.clj:59
 :seon.session/backend    :sqlite              ;; NEW
 :seon.session/path       "data/sessions/alice/store.sqlite"  ;; NEW
 :seon.session/created-at #inst "2026-05-26T..."
 :seon.session/state      :active}  ;; existing :state enum

```

**Why in the master DB**: discoverable via the existing seon.web inspector (already wired for :seon.runtime entities), queryable, render-able via the existing render pipeline, survives restart, single source of truth.

**Lifecycle (locked 2026-05-27)**:

```clojure
(seon.session/create!  {::name :alice ::backend :file ::path "..."}) ; throws if exists
(seon.session/start!   {::name :alice})                              ; throws if absent
(seon.session/ensure!  {::name :alice ::backend :file})              ; auto-detect: create-if-absent + start
(seon.session/stop!    {::name :alice})                              ; halt wire-server; DB stays on disk
(seon.session/destroy! {::name :alice ::confirm :yes-really})        ; DELETE everything
```

- `ensure!` is the workhorse for agent callers; `create!`/`start!`/`stop!`/`destroy!` are the explicit verbs for orchestrator + UI.
- JVM boot → query `[?s :seon.session/state :active]`, lazy-register conns on first reference (no eager re-open).
- `stop!` halts the wire-server socket pair; the on-disk store survives. `destroy!` is the only thing that touches the filesystem.

**One Integrant component manages N sessions.** `:seon.server.session/registry` IS the wire-bearing component — it owns the atom AND spawns/halts per-session wire-server sockets. There is NO separate `:seon.server/wire` component. The original sketch (one `:seon.server/wire` listening on a single fixed socket pair) doesn't compose with Option B socket-per-session routing locked in Wave 4a; the registry took that role.

**Comparison to V2 PoC**: there the Rust host kept a `SessionRegistry` HashMap in Rust memory. In V2-PoC, "the session existed if a JVM process was running for it." Lost on host restart unless the agent re-referenced.

In the new architecture, **sessions are first-class datoms** in the master DB. They're durable, queryable, and visible to the existing seon.web UI without any new tooling.

## 6. CLJS-to-JVM bridge (wire-server)

The CLJS pod (V0 today, eventually V2 wasm) doesn't open datahike directly. It calls the wire-server over UDS:

```
guest CLJS  --WIT-->  Rust host  --UDS-->  JVM seon.server.wire  --direct-->  datahike conn
                                                  │
                                                  └── routes by :db-name in request

```

- Wire-server lives in `src/seon/server/wire.clj` (new).
- Listens on `/tmp/seon-poc-req.sock` for req/resp, `/tmp/seon-poc-pub.sock` for tx events.
- Each request carries `:db-name :seon.session/<name>`. The handler looks up that session's conn and routes the op there.
- Master `:seon` DB is reachable too — wire-server handlers accept `:db-name :seon` (subject to access control later).

**For MVP**: wire-server calls datahike directly (`(d/transact conn ...)`, `(d/q ...)`). It does NOT route through `seon.db.datahike.flow`. The existing seon.clj code keeps using flow; we run both in parallel.

**Why bypass flow now**:
- Flow has no CLJS equivalent. Wire-server is the cross-platform boundary.
- Routing through flow adds layers without value at MVP.
- We may break seon.clj temporarily while sorting access; explicitly accepted.

**Plan-for-flow later**: once both sides stabilize, route the wire-server through flow so the "remote process" capability the flow was designed for actually lights up.

## 7. What exists already (do not rewrite)

This is the **anti-rewrite manifest**. Other agents working on this should READ what exists before creating parallel code.

### Storage / DB infrastructure

| Thing | Location | Status |
|---|---|---|
| Multi-DB seon API (transact!, query, pull-by-name take db-name keyword) | `src/seon/db.clj` | EXISTS, in production |
| `:seon.db/flow` Integrant component | `src/seon/db/datahike/flow.clj` + `system.clj` | EXISTS, in production |
| Per-DB tx broadcast | `src/seon/db/datahike/tx_bus.clj` | EXISTS, in production |
| Cross-process relay | `src/seon/db/relay.clj` | EXISTS, designed for remote |
| Master `:seon` DB | `resources/system.edn :seon.db/flow :namespaces [:seon ...]` | EXISTS, in production |
| Schema registry (shared CLJ/CLJS) | `src/seon/schema.cljc` | EXISTS, in production |
| konserve-sqlite-cljs (the CLJS adapter) | `src/konserve_sqlite_cljs/core.cljs` (438 LOC) + dup at `pod-host/libdatahike-cljs/src/konserve_sqlite_cljs/core.cljs` | EXISTS, used by the bench harness |
| Bench harness across konserve backends | `pod-host/libdatahike-cljs/src/seon/podhost/libdatahike/bench.cljs` | EXISTS, 1015 LOC |
| Multi-reader spike (RED result) | `pod-host/libdatahike-cljs/spikes/multi-reader/` | EXISTS, documents the failure mode |
| Datahike fork with CLJS analyzer fixes | `https://github.com/seantempesta/datahike` SHA `01ba3f18` | pinned via `deps.edn :override-deps` |

### Wire protocol / serialization

| Thing | Location | Status |
|---|---|---|
| CBOR codec (length-framed control frames) | `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/codec.clj` | EXISTS — to port to `src/seon/server/codec.clj` |
| Transit-JSON for values | `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/transit.clj` + guest-side `transit.cljs` | EXISTS — both sides |
| UDS req/resp + pub | `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj` | EXISTS — handlers route to its own datahike conn; PORT this to call `seon.db` instead, or call raw conns from a session registry |
| WIT contract (guest ↔ host) | `pod-host/sidecar-poc/rust-host/wit/sidecar.wit` | EXISTS — stable |
| Rust host (wasmtime + UDS + sessions) | `pod-host/sidecar-poc/rust-host/src/*.rs` | EXISTS — needs reduction: drop its own JVM-spawning code, connect to existing seon JVM's wire-server |

### Tests / infrastructure

| Thing | Location | Status |
|---|---|---|
| Protocol integration tests | `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/protocol_integration_test.clj` + extensions + facts + wire_types + transact_batch | EXISTS, 47 tests / 189 assertions green |
| seon JVM Integrant lifecycle | `src/seon/system.clj` | EXISTS, in production |
| `(user/reset)` cycle | dev hook | EXISTS, in production |
| Test runner for JVM | `seon.test-utils` + `bin/test` | EXISTS, in production |
| Test runner for CLJS | `seon.test.runner` (the gold-standard three-tier example) | EXISTS, in production |

### What does NOT exist (the actually-new work, post-audit + two-path decision)

| Thing | Why needed | Estimate |
|---|---|---|
| **`seon.server.session/registry` atom + `ensure-db!`/`remove-db!`/`list-sessions`** | Path B's session lifecycle. Direct `datahike.api/connect`, no flow. Atomic, idempotent, cheap. | ~80 LOC, 1-2h |
| **konserve-jdbc:sqlite config builder fn** + 2 deps (`io.replikativ/konserve-jdbc`, `org.xerial/sqlite-jdbc`) | Build the `(d/create-database)` / `(d/connect)` cfg given `{:db-name :backend :path}`. Pattern copied from `pod-host/sidecar-poc/jvm-writer/writer.clj:61-70`. | ~30 LOC, 1h |
| **Port wire-server from `jvm-writer/`** | Move `writer.clj`/`codec.clj`/`transit.clj`/`broadcast.clj` to `src/seon/server/`. Handlers swap their private conn for `(get @registry db-name)`. Codec/transit/broadcast move verbatim. | ~400 LOC, 3-4h |
| **Two new attrs on the existing session schema** | `seon.session/backend` + `seon.session/path`. Rename `seon.orchestrator.session` → `seon.session` + consolidate. | ~30 LOC, 1-2h |
| **`:seon.server/wire` Integrant component** | Init/halt of the UDS listeners. Lives alongside `:seon.db/flow`, doesn't touch it. | ~50 LOC, 1h |
| **Rust host trim** | Drop `JvmSupervisor`; connect to existing seon JVM's wire-server. | ~3h (subtractive) |

**Estimated total new code: ~590 LOC.** **Estimated time: ~10-13h.** (Slightly more LOC than the flow-integrated path; about half the calendar time because there's no spike and no orphan-mitigation work.)

**Dynamic flow registration spike**: DONE — see `research/flow-runtime-update-spike-2026-05-26.md`. Verdict: workable but orphans in-flight requests, drops tx-bus subscribers, stales Integrant state. Cost is real. **Deferred** to a later flow-integration PRD; not blocking MVP.

## 8. Naming conventions (locked)

| Use | Form | Path |
|---|---|---|
| Existing master | `:seon.runtime` (existing; runtime registry, orchestrator entities, session entities) | Path A (flow) |
| Existing static DBs | `:seon.session`, `:seon.orchestrator`, `:seon.repl`, `:seon.flow`, `:seon.phase2.demo` (existing) | Path A (flow) |
| Agent session DB | `:seon.session/<name>` — `<name>` is a simple keyword name | Path B (registry, new) |
| Ephemeral agent session | `:seon.session/tmp-<uuid>` by convention; the API doesn't enforce | Path B (registry, new) |
| Agent identity | `:seon.agent/<id>` — id is 14-char like existing `:seon.agent/id` | either |
| Datom stamping | `:seon.db/agent-id <agent-id>` (existing pattern from V0's `with-agent` ALS) | either |

**Important**: `:seon.session` (the existing static flow-resident DB; for the existing seon.orchestrator code) is DIFFERENT from `:seon.session/<name>` (the new agent-session DBs in Path B). The slash distinguishes. Same word, two roles — accepted because Path B sessions intentionally evoke "session in the orchestrator sense" but live in their own registry, not in the flow.

The dot-namespace + slash distinguishes db-names from agent ids and from concept namespaces.

## 9. Migration sequence (revised — two-path)

The flow spike is DONE (`research/flow-runtime-update-spike-2026-05-26.md`) and its conclusion was "doable but with real cost." We're deferring that integration. The MVP just ships Path B (direct-conn registry) alongside the existing flow.

1. **Consolidate sessions namespace.** Rename `src/seon/orchestrator/session.clj` → `src/seon/session.clj` (replacing the existing 472-LOC `seon.session` with orchestrator's richer 609-LOC version). Extend schema with `::backend` and `::path`. Adjust call sites. ~2h.
2. **`seon.server.session` registry.** Atom `{db-name -> {:conn :backend :path :pub-chan}}`. `ensure-db!` / `remove-db!` / `list-sessions` API. Direct `datahike.api/connect`. ~1h.
3. **konserve-jdbc:sqlite config builder.** A small fn that builds the cfg map for `(d/create-database)` / `(d/connect)` given `{:db-name :backend :path}`. Already wired in V2 PoC's `jvm-writer/writer.clj:61-70`; copy that pattern. Add 2 deps to `deps.edn`. ~1h.
4. **Port wire-server from `pod-host/sidecar-poc/jvm-writer/`** to `src/seon/server/`. The handlers stop opening their own conn and instead `(get @seon.server.session/registry db-name)`. Codec/transit/broadcast helpers move verbatim. ~3-4h.
5. **`:seon.server/wire` Integrant component.** Add to `src/seon/system.clj` + `resources/system.edn`. Starts UDS listeners, doesn't touch flow. ~1h.
6. **Update Rust host** to connect to the seon JVM's wire-server instead of spawning its own. Drop `JvmSupervisor`. Sessions = db-name strings. ~3h.
7. **Smoke**: Phase D + PF against the seon JVM. ~1h.

Total: **~12h** — no spike, no flow internals, no orphan-promise risk. Path A (existing seon.clj + flow) is untouched throughout.

CLJS guest overlay needs no change — wire format is unchanged.

## 10. Open / deferred

- Flow integration: deferred to Phase 8 (per the transition plan).
- Master DB access control from agents: TBD. By default agents only see their session; master `:seon` access is privileged.
- Blob storage WIT capability: deferred to its own PRD.
- Tauri packaging: out of scope.
- Multi-tenancy hardening: PoC quality; production hardening is a separate pass.

## 11. Replay model (locked 2026-05-27)

**MVP gate: dumb per-form replay** of `:seon.fn` / `:seon.ns` / `:seon.schema` entities. This is what V0 has via `replay-program-graph!` and what V2 inherits. Smart ns-batched replay via the analyzer is a **deferred optimization**, not an MVP blocker.

**Hard rule for V2 session DBs:**

> Every state-changing thing must transact a datom or be reconstructable from one.

Agent runtime atom state (e.g., `seon.agents/!self`) is NOT serialized directly — it is reconstructed by replaying datoms on resume. The pattern is "datoms are the log; atoms are the projection." This matches the three-tier storage rule in MEMORY.md.

**Substrate-source seeding deferred.** Substrate code (the seon namespaces themselves) lives in the compiled bundle and is loaded the normal way at JVM/pod boot. Only AGENT-defined code (forms the agent wrote at runtime via `eval`) is replayed from datoms. Smart substrate-seeding — where the substrate itself comes from a datom corpus visible to the agent — is item 9 in `v2-open-questions-investigation-2026-05-27.md` (~150 LOC) and is queued AFTER V2 cutover, not before.

This split keeps the MVP scope tight: V2 cutover = "agent can run, transact, query, resume with its own definitions intact." Smart seeding = "agent can see and modify the substrate's own definitions" — a follow-up capability, not a prerequisite.

## 12. Runtime atom consolidation (locked 2026-05-27)

`src/seon/runtime.clj` currently has three private atoms at lines 295, 330, 883:

- `generated-ids` — set, for ID dedup
- `registry-cache` — cache
- `flow-handles` — map of flow component handles

These collapse into ONE atom with three keys, matching `atom-state-system-2026-05-26.md`'s "one substrate atom per concern" pattern:

```clojure
(defonce !self
  (atom {::generated-ids #{}
         ::registry-cache {}
         ::flow-handles {}}))
```

Small refactor (~50 LOC of edits). Listed as **Item D** in Wave 4.5 of the execution plan. The investigation report already noted these are all the same concern (`seon.runtime`'s own state); collapsing them is consistency hygiene, not new capability.

## 13. Doc cross-references

- `pod-host/sidecar-poc/SESSIONS.md` — original session concept; this doc supersedes the file-layout sections
- `pod-host/sidecar-poc/AGENT.md` — agent mental model; still valid for V2 guest-side
- `pod-host/sidecar-poc/PROTOCOL.md` — wire format; unchanged
- `docs/prds/agent-runtime/v0-to-v2-transition-plan-2026-05-26.md` — phased plan; §2 now points here
- `docs/prds/agent-runtime/atom-state-system-2026-05-26.md` — atom-based runtime state; complementary
- `pod-host/sidecar-poc/bench/v0-port-survey.md` — V0 API coverage; relevant for migration
- `docs/prds/agent-runtime/v2-open-questions-investigation-2026-05-27.md` — pre-Wave-5 sweep that fed the 2026-05-27 locks above
- `docs/prds/agent-runtime/RESUME-2026-05-27.md` — session handoff; entry point for next agent
- `docs/prds/agent-runtime/execution-waves-2026-05-26.md` — wave-by-wave status
