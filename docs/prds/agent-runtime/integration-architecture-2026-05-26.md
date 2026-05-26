---
type: architecture
status: active
tags: [architecture, agent, database, platform]
---

# Integration Architecture — V0/V2 Database Layer Merge

**Date:** 2026-05-26 PM
**Scope:** This doc supersedes the file-layout + naming sections of `pod-host/sidecar-poc/SESSIONS.md` and `pod-host/sidecar-poc/AGENT.md`. The phased transition plan at `v0-to-v2-transition-plan-2026-05-26.md` references this doc for the target architecture.

## 0. TL;DR

- **One JVM** owns all datahike DBs (not multi-JVM like the V2 PoC did).
- **Master `:seon` DB** holds all existing CLJ code's data, unchanged.
- **Per-session DBs** `:seon.session/<name>` are created at runtime; agents (wasm guests) connect to them.
- **N agents can share one session** (collaborate via one DB) or each have their own (isolation).
- Wire-server bridges CLJS guests to JVM-hosted DBs. **Raw datahike access, bypassing flow, for MVP.** Flow integration is later.
- **konserve-sqlite-cljs already exists** at `src/konserve_sqlite_cljs/core.cljs` (438 LOC). JVM equivalent does NOT exist yet — porting it is the only "new" storage code.
- We accept temporary breakage of existing seon.clj code during integration; restored after install.

## 1. Conceptual model

```
┌─ One JVM ──────────────────────────────────────────────────────────┐
│                                                                    │
│   :seon                  master DB — existing CLJ code uses this   │
│   :seon.session/default  default session DB                        │
│   :seon.session/alice    named session for "alice"                 │
│   :seon.session/bob      named session for "bob"                   │
│   :seon.session/tmp-XYZ  ephemeral (:memory) — for tests           │
│                                                                    │
│   seon.server.wire (UDS server) ──► routes CLJS requests to DB    │
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
| **Master DB** | The single `:seon` DB. Holds all existing seon CLJ code's data. Not a session, just the JVM's own DB. | `:seon` (unchanged) |

**The DB doesn't know about agents directly.** The session is the storage unit. Agent identity is metadata on datoms (e.g., `:seon.db/agent-id` stamps via tx-context).

## 3. File storage layout

```
seon/data/                                ← project-local; gitignored
│
├── seon/                                 ← master :seon DB
│   └── store.sqlite                      ← konserve-sqlite (when JVM port lands)
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
| Master `:seon` | `:sqlite` (preferred) or `:file` (transitional) | Production data; long-lived |
| Named session `:seon.session/<name>` | `:sqlite` (preferred) or `:file` (transitional) | Persistent by default; named = caller cares |
| Auto-id ephemeral session `:seon.session/tmp-<uuid>` | `:memory` | Tests only |

**Memory backend is for testing ONLY.** Anything that persists data uses sqlite (preferred long-term) or file (acceptable transitional). The user's words: "Everything else should be sqlite or file if the code isn't already written. I want sqlite eventually."

## 5. Session registration

**Where**: as entities in the master `:seon` DB. NOT in an in-memory atom (that would lose registrations on restart).

```clojure
;; entities in :seon DB:
{:seon.session/name       :alice
 :seon.session/backend    :sqlite
 :seon.session/path       "data/sessions/alice/store.sqlite"
 :seon.session/created-at #inst "2026-05-26T..."
 :seon.session/state      :active}  ;; or :archived, :gc'd
```

**Why in the master DB**: discoverable via the existing seon.web inspector, queryable, render-able via the existing render pipeline, survives restart, single source of truth.

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

### What does NOT exist (the actually-new work)

| Thing | Why needed |
|---|---|
| `seon.db.datahike.konserve-sqlite` (JVM-side) | konserve-jdbc 0.2.91 is built against konserve 0.8.x; datahike 0.8.1681 pulls 0.9.x. The CLJS adapter exists but is node-sqlite3-wasm. Port the CLJS adapter to JVM using `org.xerial/sqlite-jdbc`. ~400-500 LOC. |
| Dynamic session registration | `(db/ensure-db! :seon.session/<name>)` to spawn a conn-process at runtime. Lifecycle pieces exist in the flow; expose as runtime API. ~100-150 LOC. |
| Wire-server that routes by db-name to existing seon.db conns | Port `jvm-writer/writer.clj` handlers; rewrite to look up the conn from a session registry instead of using its own private conn. ~400-500 LOC. |
| Session entity schema in master `:seon` DB | `:seon.session/{name,backend,path,created-at,state}` Malli schemas. ~30 LOC. |

**Estimated total new code: ~1000-1200 LOC**, mostly mechanical translations of code that already exists in either V2 PoC (jvm-writer) or CLJS adapter form.

## 8. Naming conventions (locked)

| Use | Form |
|---|---|
| Master DB | `:seon` |
| Session DB | `:seon.session/<name>` — `<name>` is a simple keyword name |
| Ephemeral session | `:seon.session/tmp-<uuid>` by convention; the API doesn't enforce |
| Agent identity | `:seon.agent/<id>` — id is 14-char like existing `:seon.agent/id` |
| Datom stamping | `:seon.db/agent-id <agent-id>` (existing pattern from V0's `with-agent` ALS) |

The dot-namespace + slash distinguishes db-names from agent ids and from concept namespaces.

## 9. Migration sequence (small, ordered)

1. **Write `seon.db.datahike.konserve-sqlite`** — port from `src/konserve_sqlite_cljs/core.cljs`. Same protocol shape, JVM threads instead of node-sqlite3-wasm. Verify by writing on JVM, reading file from CLJS, identical datoms.
2. **Add `(db/ensure-db! :seon.session/<name>)` + `(db/remove-db! ...)`** — runtime API on the existing flow. Idempotent. Lazy.
3. **Write session-entity Malli schemas** — `:seon.session/{name,backend,path,...}`.
4. **Port wire-server from `jvm-writer/writer.clj`** to `src/seon/server/wire.clj`. Rewrite handlers to look up conn from db-name (raw datahike access). Reuse the existing codec/transit/broadcast helpers verbatim.
5. **Add `:seon.server/wire` Integrant component** wiring it into the seon JVM lifecycle.
6. **Update Rust host** to connect to the existing seon JVM's wire-server instead of spawning its own JVM writers. Drop the `JvmSupervisor` code path; keep the sessions API but make sessions = db-name strings.
7. **Update CLJS guest's `sidecar-poc.datahike` overlay** if needed — likely no change since the wire format is identical.
8. **Smoke**: Phase D + PF against the seon JVM. Should be identical to before, just routed through a different JVM.

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
