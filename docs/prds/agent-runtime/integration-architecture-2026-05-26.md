---
type: architecture
status: active
tags: [architecture, agent, database, platform]
---

# Integration Architecture — V0/V2 Database Layer Merge

**Date:** 2026-05-26 PM
**Scope:** This doc supersedes the file-layout + naming sections of `pod-host/sidecar-poc/SESSIONS.md` and `pod-host/sidecar-poc/AGENT.md`. The phased transition plan at `v0-to-v2-transition-plan-2026-05-26.md` references this doc for the target architecture.

**Revised 2026-05-26 PM (post-audit):** This doc was initially written assuming several pieces would be built from scratch. The anti-rewrite audit at `integration-anti-rewrite-audit-2026-05-26.md` found that ~50% of those pieces already exist. This doc has been updated to reflect reuse-first paths. The "Anti-rewrite manifest" §7 below is now the authoritative reuse list.

## 0. TL;DR

- **One JVM** owns all datahike DBs (not multi-JVM like the V2 PoC did).
- **Master `:seon.runtime` DB** is promoted as the master (existing db-name; already in production use by seon.runtime/register-flow! etc.). No new `:seon` db-name is introduced.
- **Per-session DBs** `:seon.session/<name>` are created at runtime; agents (wasm guests) connect to them.
- **N agents can share one session** (collaborate via one DB) or each have their own (isolation).
- Wire-server bridges CLJS guests to JVM-hosted DBs. **Raw datahike access, bypassing flow, for MVP.** Flow integration is later.
- **SQLite backend already works on JVM** via konserve-jdbc + xerial/sqlite-jdbc — V2 PoC `jvm-writer/` uses it today. NO new konserve-sqlite-JVM port is needed. Just add deps + a `:sqlite` arm in `seon.db.datahike.flow/namespace-config`.
- **Wire-server foundation already exists** at `src/seon/db/relay.clj` (339 LOC, op-dispatch by db-name + length-framed socket). Adapt it: TCP → UDS, Nippy → CBOR control + Transit-JSON values. ~150 LOC of touch-ups, not ~500 of fresh port.
- **Session entity schema already exists** at `src/seon/orchestrator/session.clj` (609 LOC). Rename → `src/seon/session.clj` (consolidating with the existing 472-LOC `seon.session` — orchestrator's version is the richer one). Extend with `::backend` and `::path`.
- **The one genuinely new piece**: dynamic conn-process registration on the running flow. Today the flow is built monolithically at boot. Need a `(db/ensure-db! …)` runtime API. **Requires a focused spike** on `core.async.flow` internals before committing the API shape.
- We accept temporary breakage of existing seon.clj code during integration; restored after install.

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

## 2. Definitions (precise)

| Term | Meaning | Identifier |
|---|---|---|
| **Session** | One datahike DB + its konserve store. The unit of data sharing. One session can hold N agents. Persistent by default. | `:seon.session/<name>` keyword |
| **Agent** | One wasm runtime instance that joins a session. Has identity, can transact, can subscribe to tx events. | `:seon.agent/<id>` keyword |
| **Master DB** | `:seon.runtime` (existing). Hosts runtime registry, orchestrator entities, session-entity records, inspector data. Not a session, just the JVM's own primary DB. | `:seon.runtime` (existing) |

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

## 4. Backend defaults

| Use case | Default backend | Reason |
|---|---|---|
| Master `:seon.runtime` | `:sqlite` (preferred) or `:file` (transitional) | Production data; long-lived |
| Named session `:seon.session/<name>` | `:sqlite` (preferred) or `:file` (transitional) | Persistent by default; named = caller cares |
| Auto-id ephemeral session `:seon.session/tmp-<uuid>` | `:memory` | Tests only |

**Memory backend is for testing ONLY.** Anything that persists data uses sqlite (preferred long-term) or file (acceptable transitional). The user's words: "Everything else should be sqlite or file if the code isn't already written. I want sqlite eventually."

## 5. Session registration

**Where**: as entities in the master `:seon.runtime` DB. Extends the **existing** `seon.orchestrator.session` schema (609 LOC, in production) — NOT a new parallel `:seon.session/*` entity set. The existing schema already has `::db-name` (orchestrator/session.clj:59), `start-agent-session!`, `stop-agent-session!`, `list-agent-sessions`, and a live-vs-persistent state pattern.

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

**Lifecycle**:
- `(db/ensure-db! :seon.session/alice)` → if a `:seon.session/name :alice` entity exists, reuse the conn (or spawn if dead). If not, transact the entity + spawn the conn. Idempotent.
- `(db/remove-db! :seon.session/alice)` → close conn, mark entity `:seon.session/state :archived`. Filesystem dir is kept by default (configurable flag for hard delete).
- JVM boot → query `[?s :seon.session/state :active]`, lazy-register conns on first reference (no eager re-open).

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

### What does NOT exist (the actually-new work, post-audit)

| Thing | Why needed | Estimate |
|---|---|---|
| **`:sqlite` arm in `seon.db.datahike.flow/namespace-config`** + 2 deps (`io.replikativ/konserve-jdbc`, `org.xerial/sqlite-jdbc`) | konserve-jdbc:sqlite already works on JVM (V2 PoC uses it). Just expose the choice in the existing flow's config. | ~30 LOC, 2h |
| **Dynamic conn-process registration** — `(db/ensure-db! :seon.session/<name>)` runtime API | Flow today is built monolithically at boot. This is THE one genuinely hard piece; needs a focused spike on `core.async.flow` internals BEFORE the API shape is committed. | ~120-200 LOC, 4-8h after spike |
| **UDS + CBOR/Transit adaptation of `seon.db.relay`** | `seon.db.relay` (339 LOC) already does op-dispatch by db-name + length-framed sockets, but uses TCP + Nippy. Swap transport (TCP → UDS) + codec (Nippy → CBOR control + Transit-JSON values). | ~150 LOC, 3-4h |
| **Two new attrs on the existing session schema** | `seon.session/backend` (`:enum [:sqlite :file :memory]`) + `seon.session/path` (`:string`). Existing `seon.orchestrator.session` schema gets these + the rename to `seon.session`. | ~30 LOC, 1h |
| **System.edn config arm + Integrant wiring for the wire-server** | New `:seon.server/wire` component init/halt. | ~50 LOC, 1h |

**Estimated total new code: ~330-460 LOC** (down from ~1000-1200). **Estimated time: 11-16h** (similar to prior estimate; the dynamic flow registration got harder, but everything else got smaller).

### The dynamic flow registration spike (pre-requisite)

Before committing to a runtime `ensure-db!` API, run a ~2-3h focused spike:

1. Read `reference-code/core.async/src/main/clojure/clojure/core/async/flow*` end-to-end (it's not huge).
2. Determine: can processes be added to a running flow? If yes, what's the API + cost? If no, what workarounds?
3. Probes in the REPL against the running `:seon.db/flow` — test the answer experimentally.
4. Output: a small markdown doc + a recommended `ensure-db!` implementation shape.

Until this spike lands, the integration's "runtime DB creation" semantics aren't pinned down. Sessions could be statically declared at boot in the worst case (works, but ergonomically poor for the "agent gets its own DB at runtime" use case).

## 8. Naming conventions (locked)

| Use | Form |
|---|---|
| Master DB | `:seon` |
| Session DB | `:seon.session/<name>` — `<name>` is a simple keyword name |
| Ephemeral session | `:seon.session/tmp-<uuid>` by convention; the API doesn't enforce |
| Agent identity | `:seon.agent/<id>` — id is 14-char like existing `:seon.agent/id` |
| Datom stamping | `:seon.db/agent-id <agent-id>` (existing pattern from V0's `with-agent` ALS) |

The dot-namespace + slash distinguishes db-names from agent ids and from concept namespaces.

## 9. Migration sequence (revised post-audit)

1. **SPIKE: dynamic flow registration.** Read `reference-code/core.async/` flow source. REPL-probe against running JVM. Determine ensure-db! API shape. ~2-3h. Output: a small spike doc. **Gates the rest.**
2. **`:sqlite` backend in `seon.db.datahike.flow/namespace-config`.** Add 2 deps, add the case arm. ~2h. (No new namespace; konserve-jdbc:sqlite already works.)
3. **Consolidate sessions namespace.** Rename `src/seon/orchestrator/session.clj` → `src/seon/session.clj` (replacing the existing 472-LOC `seon.session` with orchestrator's richer 609-LOC version). Extend schema with `::backend` and `::path`. Adjust call sites. ~2h.
4. **Implement `(db/ensure-db! …)` / `(db/remove-db! …)`** per the spike output. Idempotent. Lazy. ~4-8h.
5. **Adapt `seon.db.relay` to UDS + CBOR + Transit.** Reuse op-dispatch shape; swap transport + codec. New file at `src/seon/server/wire.clj`. ~3-4h.
6. **`:seon.server/wire` Integrant component.** Add to `src/seon/system.clj` + `resources/system.edn`. ~1h.
7. **Update Rust host** to connect to the seon JVM's wire-server instead of spawning its own. Drop `JvmSupervisor`. Keep the sessions API; sessions = db-name strings. ~3h.
8. **Update CLJS guest overlay** if needed — likely no change since the wire format adaption is server-side only. ~0-1h.
9. **Smoke**: Phase D + PF against the seon JVM. Should be identical to before, just routed through the real seon JVM. ~1h.

## 10. Open / deferred

- Flow integration: deferred to Phase 8 (per the transition plan).
- Master DB access control from agents: TBD. By default agents only see their session; master `:seon` access is privileged.
- Blob storage WIT capability: deferred to its own PRD.
- Tauri packaging: out of scope.
- Multi-tenancy hardening: PoC quality; production hardening is a separate pass.

## 11. Doc cross-references

- `pod-host/sidecar-poc/SESSIONS.md` — original session concept; this doc supersedes the file-layout sections
- `pod-host/sidecar-poc/AGENT.md` — agent mental model; still valid for V2 guest-side
- `pod-host/sidecar-poc/PROTOCOL.md` — wire format; unchanged
- `docs/prds/agent-runtime/v0-to-v2-transition-plan-2026-05-26.md` — phased plan; §2 now points here
- `docs/prds/agent-runtime/atom-state-system-2026-05-26.md` — atom-based runtime state; complementary
- `pod-host/sidecar-poc/bench/v0-port-survey.md` — V0 API coverage; relevant for migration
