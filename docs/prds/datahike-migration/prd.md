---
type: prd
status: draft
tags: [prd, database, schema]
---
# PRD: Datahike Migration — Phase 1 (Single-JVM Embedded)

---

## Scope

Phase 1 covers **only** the storage-layer swap: Datalevin → Datahike, embedded in a single Seon JVM, per-namespace databases, single in-process writer, file backend by default. Agents run as core.async.flow state machines in the same JVM and access databases through the normal `seon.db` API (no wire protocol needed for data). Cross-namespace function calls continue to route through flow as they do today.

Later phases are **sketched but not committed** in `decisions.md`. This PRD is scoped to what we've validated and are confident in.

---

## Goals

1. **Replace Datalevin with Datahike** with minimal churn. `seon.db` public API keeps its shape; call sites don't change.
2. **One database per namespace**, each a konserve store under a single git-friendly data directory.
3. **Single in-process writer**, `:writer :self`, owned by Integrant. Impossible (by construction) to start a second concurrent writer on the same store.
4. **Dev-first defaults**: `:file` backend with data under `data/`, `git init`-able so you can time-travel code and data together.
5. **Rock-solid Integrant lifecycle**: start / stop / reset / crash-recover are deterministic. No dangling processes, no lock cleanup, no "did it adopt?" ambiguity.
6. **Tight call-site API**: `(d/transact! :seon.weather [...])` — db-name is a keyword, resolved via an in-JVM registry. Call sites don't hold conn objects; the shape stays friendly to future flow routing without needing code changes then.

---

## Problem Statement

Current state (Datalevin + `core.async.flow` writer/reader): **~2,700 LOC in `seon.db*`** exists mostly to paper over one structural fact — Datalevin was designed to run as a separate JVM process, and Seon spawns/adopts/monitors/reconnects to it. Complexity sinks:

- External JVM lifecycle: PID files, stale lock cleanup, adopt-on-start logic, health polling
- Concurrent-open race across JVMs → per-DB lock map with double-checked locking
- Flow reader/writer processes serialize every read and write for no current in-JVM benefit
- No git-friendly story — LMDB is an opaque binary blob
- LMDB is the only backend; no path to S3 / JDBC / file

Datahike with `:writer :self` + konserve's pluggable backends collapses this. Embedded = no external JVM. `:file` backend = git-ability. `:lmdb` / `:s3` / `:jdbc` are later config swaps, not rewrites.

**Impact:** delete ~1,200 LOC of infrastructure, remove a whole class of "did Datalevin come back?" bugs, gain time-travel debugging for free, unlock backend choice per deployment.

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `reference-code/datahike/` | Datahike source — API, connector, writer, FilteredDB |
| `reference-code/datahike/doc/storage-backends.md` | Backend comparison table, selection guidance |
| `reference-code/datahike-lmdb/scratch/multidb/` | Our validation scratch (four focused tests — see `notes.md`) |
| `src/seon/db.clj` + `src/seon/db/**` | Current API surface to preserve |
| `src/seon/schema.clj` + `src/seon/db/schema.clj` | Malli registry + current Datalevin bridge |
| `docs/prds/datahike-migration/decisions.md` | Decision record with alternatives + open questions |
| `docs/prds/datahike-migration/notes.md` | Validation findings, gotchas, test artifacts |

---

## Solution Design

### Topology (phase 1)

```
┌──────────────────────── single Seon JVM ────────────────────────┐
│                                                                  │
│  Integrant:                                                      │
│    :seon.db/flow        (one flow, owns all DB state)            │
│                                                                  │
│  Flow processes (state lives here, not in app-level atoms):      │
│                                                                  │
│    :seon.db/conn-process-{ns}  (one per namespace DB)            │
│       state: {:conn ..., :config ..., :schema-installed? true}   │
│       inputs: :request  (transact!, q, pull, pull-deep, listen!) │
│       outputs: :reply   (results / errors)                       │
│                :tx-report (emitted after every successful tx)    │
│                                                                  │
│    :seon.db/tx-bus                                               │
│       state: {:subscribers {[db-name key] callback}}             │
│       inputs: :tx-report  (from every conn-process)              │
│               :sub        (add subscriber)                       │
│               :unsub      (remove subscriber)                    │
│                                                                  │
│  seon.db public API (unchanged call-site shape):                 │
│    transact! / q / pull / pull-deep / listen!                    │
│    — routes via topology/request! → flow process → reply         │
│                                                                  │
│  Agent flows:                                                    │
│    call seon.db directly — same JVM, flow-to-flow messaging     │
│                                                                  │
│  data/                                                           │
│   ├── seon.weather/ (konserve file store)                        │
│   ├── seon.user/                                                 │
│   └── seon.city/                                                 │
│  (optional) git init data/ → time-travel code + data             │
└──────────────────────────────────────────────────────────────────┘
```

### Key Components

**`:seon.db/flow`** — the single Integrant key. Builds and starts a `core.async.flow` topology containing all DB state: one `conn-process-{ns}` per namespace, plus the `tx-bus` process. Halt stops the flow (which releases all Datahike connections via each conn-process's `:halt` handler). This is the **only** Integrant key for the DB layer — everything else is flow-internal.

**`:seon.db/conn-process-{ns}` (flow process, one per namespace)** — holds the Datahike `Connection` as its flow state. On process `:init`, opens the konserve store (create-or-connect) and installs the Malli-derived schema (idempotent). Receives request messages (`:transact!`, `:q`, `:pull`, `:pull-deep`, `:listen!`) on its `:request` input, replies on `:reply`, emits a `:tx-report` message on every successful transaction. On `:halt`, releases the connection.

**`:seon.db/tx-bus` (flow process)** — holds subscriber callbacks as its flow state. Every `conn-process-{ns}` emits `:tx-report` here; `tx-bus` dispatches to registered subscribers filtered by db-name. Subscribers register via `:sub` messages from `seon.db/listen!` and de-register via `:unsub`. Fan-out is in-JVM (channel ops); no wire involved.

**`seon.db` (public namespace)** — ~200 lines. Functions compose a request map (`{:op :q, :db-name :seon.weather, :args [...]}`), invoke `seon.flow.topology/request!` against the relevant flow process, deref the reply, and return. Sync public API on top of the async flow backbone. Auto schema-install and entity namespace stamp happen inside the conn-process before it calls `d/transact`.

**Why flow-state and not atoms:** all stateful data (connection handles, subscriber maps, any future per-DB policy state) lives in flow process state. One pattern for state across the whole system. Every DB operation passes through the flow, giving us a natural inspection / gating / audit point for the future security layer without having to add it later.

### API shape (unchanged from call-site perspective)

```clojure
(d/transact! :seon.weather [{:seon.weather/id id :seon.weather/temp 12}])
(d/q         :seon.weather '[:find ?t :where [?e :seon.weather/temp ?t]])
(d/pull      :seon.weather '[*] [:seon.weather/id id])
(d/pull-deep :seon.weather '[*] [:seon.weather/id id])  ;; new — follows refs across DBs
(d/listen!   :seon.weather ::my-key (fn [tx-report] ...))
```

### Schema (Malli remains source of truth)

`seon.schema/register!` unchanged. `seon.db.schema` bridge gains a new target: datahike's `{:db/ident ... :db/valueType ... :db/cardinality ... :db/unique ...}` entity format. Installed on first `transact!` per namespace, idempotent on subsequent starts.

### Refs across databases

Plain `:db.type/uuid` values, with `:seon.db/ref-to` metadata on the Malli schema pointing at the target namespace. `pull-deep` reads the ref-registry to route follows. Tuple-typed refs are available as an escape hatch for polymorphic references (rare) — documented but not the default.

### Entity namespace auto-stamp

Every `seon.db/transact!` stamps `:seon.db/namespace <db-name>` on each entity map in tx-data. Result: pulled entities are self-describing, introspectable without schema knowledge. Cheap (one keyword datom per entity) and pays off during debugging, serialization, and future filter-based security.

### Configuration (Aero)

Single EDN config. Per-namespace stores are declared once; profiles select the backend:

```clojure
{:seon.db/data-root #or [#env SEON_DATA_ROOT "data"]
 :seon.db/namespaces [:seon.weather :seon.user :seon.city :seon.runtime :seon.ai]
 :seon.db/backend    #profile {:dev :file, :test :memory}}
```

Per-namespace store config is derived from `:seon.db/data-root`, the namespace keyword, and the backend. The Integrant key set is built programmatically from `:seon.db/namespaces`.

---

## Constraints

- **No call-site code changes.** Domain namespaces do not change imports or function signatures.
- **Malli stays the source of truth.** No hand-written datahike schema in app code.
- **Single writer per store, enforced.** One `conn-process-{ns}` per namespace, flow-owned; startup validation refuses to build a second flow process for a store path already claimed.
- **All stateful data lives in flow state**, not in app-level atoms. Connections, subscriber lists, any future policy/grant caches are process state managed by the flow.
- **Crash-safe.** `SIGTERM` mid-transaction leaves the store in a valid state; restart recovers to the last committed root.
- **REPL-first.** `(user/reset)` is robust. Schema changes on disk don't silently corrupt — either migrate or refuse to start with a clear error.
- **Tests use real Datahike.** `:memory` backend for fast unit tests, tmp `:file` dirs for integration tests. No mocking of datahike internals.
- **Phase 1 does not change agent JVM behavior.** Agents run in-process as flow state machines; they call `seon.db` directly. No HTTP server, no konserve-sync, no kabel.

---

## Success Criteria

1. All existing `seon.db` tests pass, rewritten to target datahike directly.
2. External JVM dependency removed. No `dtlv server`, no port 8898, no PID files.
3. `git log data/` shows real transaction history. `git checkout <sha>` + `(user/reset)` reconstitutes that past state.
4. **Lifecycle matrix passes:**
   - Fresh-dir bootstrap: `(go)` → transact → `(halt)` → `(go)` → data visible, no errors
   - Reset: `(reset)` mid-quiet-system → same data, no errors
   - Crash: `kill -TERM` mid-tx → restart → last committed state intact
   - Double-writer guard: attempting to boot a second `:writer :self` on the same store fails with a clear error (either in-JVM duplicate or detected via konserve lock)
5. At least three namespaces fully migrated (e.g. `seon.runtime`, `seon.ai`, one domain ns) with passing tests and updated component notes.
6. Write and query throughput measured — within the same order of magnitude as current Datalevin on file backend (LMDB-parity benchmarking is phase-3).
7. No remaining references to `datalevin.core` or `seon.db.datalevin.*` anywhere in `src/`.

---

## Deliverables

### Code

- [ ] `seon.db` public namespace (same call-site API, new internals)
  - [ ] `transact!`, `q`, `pull`, `pull-deep`, `listen!`
  - [ ] Thin wrappers that compose request maps + call `seon.flow.topology/request!` + deref reply
  - [ ] Auto-stamp `:seon.db/namespace` on entity maps before the request is sent
  - [ ] Auto `:tx-meta` populated from the caller context (`:committer`, extensible)
  - [ ] Test injection point — tests can swap the flow for an in-process stub, or target a `:memory` flow instance directly
- [ ] Flow topology
  - [ ] `:seon.db/conn-process` factory — one instance per namespace, built from config
  - [ ] Process lifecycle: `:init` opens store + installs schema; `:halt` releases conn
  - [ ] Message handlers: `:transact!`, `:q`, `:pull`, `:pull-deep`, emit `:tx-report` on success
  - [ ] `:seon.db/tx-bus` process — subscriber map in flow state, `:sub` / `:unsub` / `:tx-report` handlers
  - [ ] Single-writer guard inside the conn-process factory (refuse duplicate store paths in one topology)
- [ ] `seon.db.schema` — Malli → datahike bridge
  - [ ] Type translation table (every Malli type used in Seon → datahike `:db.type/*`)
  - [ ] Cardinality (`:vector X` → `:db.cardinality/many`)
  - [ ] Uniqueness properties (`:seon.db/identity`, `:seon.db/unique`)
  - [ ] Idempotent `ensure-schema!` called by conn-process on `:init`
  - [ ] Load-order guard with clear error messages
- [ ] `seon.db.pull-deep` — ref-walking using `seon.schema/ref-registry`; runs inside the conn-process, cross-DB refs send follow-up requests to the target namespace's conn-process
- [ ] Integrant: one key — `:seon.db/flow` — that builds and starts the flow from config
- [ ] Config (Aero)
  - [ ] `resources/config.edn` with `:dev` / `:test` profiles
  - [ ] Documented-but-inactive `:prod-local` (LMDB) and `:prod-remote` (S3) placeholders
  - [ ] Flow topology built programmatically from `:seon.db/namespaces`
- [ ] Deletions
  - [ ] `src/seon/db/datalevin/**`
  - [ ] Datalevin dep from `deps.edn` (main and `:agent` alias)
  - [ ] `seon.db.datalevin.server` and external-JVM plumbing
  - [ ] Old reader/writer processes in the infrastructure flow (replaced by per-namespace conn-processes)

### Tests

- [ ] Unit: schema bridge coverage for every registered Malli type
- [ ] Unit: auto-stamp, pull-deep, listen! semantics
- [ ] Integration: Integrant lifecycle matrix (see criteria #4)
- [ ] Integration: crash recovery (`kill -TERM` mid-tx)
- [ ] Integration: double-writer guard fails loudly
- [ ] Property: tx round-trip preserves all registered attrs via generators

### Documentation

- [ ] `docs/seon/components/db.md` — rewritten for datahike
- [ ] `docs/seon/concepts/schema-bridge.md` — Malli → datahike translation
- [ ] `docs/conventions.md` — update "Database Access" (keyword db-names, UUID refs, namespace stamp)
- [ ] ADR in `docs/seon/architecture/decisions/` — pointer to this PRD
- [ ] `docs/prds/datahike-migration/notes.md` kept current during implementation

---

## Out of Scope (phase 1)

- **Multi-JVM / cross-process DB access** — validated experimentally, not on the v1 roadmap. We may or may not need it later. See `decisions.md` §"Cross-process access".
- **LMDB / S3 / JDBC backends** — config-level swap later; phase 1 is `:file` + `:memory`.
- **Security filtering (`d/filter`, grants, groups)** — sketched in `decisions.md` §"Security model" so today's design doesn't close doors, but not implemented.
- **Kabel WebSocket writer / browser replica** — Datastar handles reactive UI; kabel is not a current need.
- **Cross-DB datalog query helpers** — users call `d/q` with explicit `:in $a $b` when needed. A `q-multi` helper waits for real demand.
- **Attribute-level redaction, entity-level ACLs, temporal grants** — maybe never; certainly not v1.

---

## Open Questions (intentionally not decided in this PRD)

See `decisions.md` for trade-off analysis. Calling them out here so we don't later think they were accidentally omitted:

- Exactly how cross-process access works if we ever need it (HTTP writer vs. flow-mediated RPC) — both feasible, pick when the requirement arrives
- Exact security model shape (classification vs. ABAC vs. capability grants) — design is compatible with any; decide under pressure of a real requirement
- Whether to adopt `:attribute-refs? true` later for Datomic compatibility / perf — phase-3 consideration, some schema migration cost
- Backup/checkpoint coordination across N per-namespace stores — we'll know the shape of this once we have real data volumes
