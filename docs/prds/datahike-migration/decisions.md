---
type: decision
status: draft
tags: [prd, decision, database]
---
# Architectural Decisions: Datahike Migration

This document captures decisions made (committed) and decisions deferred (acknowledged, not made). The goal is to make the design compatible with future choices without pre-committing to them.

---

## Decision 1: Datahike replaces Datalevin

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Datalevin runs as an external JVM process. That single architectural fact drives most of `seon.db`'s ~2,700 LOC: process lifecycle, TCP reconnect, concurrent-open race workarounds, PID file management. The benefit/cost ratio is poor for Seon's current usage (single JVM, no real multi-client need).

Datahike embeds in-process, uses konserve for pluggable storage, and has a first-class single-writer-multiple-reader model. Same datalog semantics, same query shape.

### Decision

Adopt Datahike (`org.replikativ/datahike 0.7.1663+`) with `:writer :self` and `:store :file` as the phase-1 defaults. All DB access goes through `seon.db`, unchanged in surface.

### Rationale

- **Remove a whole process lifecycle** — no external JVM to spawn, monitor, adopt, or clean up
- **Backend choice becomes config**, not code (file → LMDB → S3 → JDBC all via konserve)
- **Git-friendly file storage** unlocks time-travel debugging for code + data together
- **Smaller LOC** — delete ~1,200 lines of infrastructure plumbing
- **Malli bridge is a small change** — schema format differs but derivation logic is similar

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Keep Datalevin, simplify flow | Minimal churn | Doesn't remove the external-JVM problem | Doesn't solve the root cause |
| XTDB 2 | Bitemporal, SQL-ish | Young 2.x, different query model | Larger migration, uncertain returns |
| Datascript only (no persistence) | Simplest | No durability | Non-starter |
| Custom on top of rocksdb | Max control | Months of work | No business case |

### Consequences

**Benefits:**
- Single JVM, single process to reason about in phase 1
- Backend is a config concern, deployable on laptop / VM / cloud without code changes
- Time-travel via git when using the file backend
- Datomic-compatible query surface — skills transfer

**Costs:**
- Migration work: bridge rewrite, schema format change, test rewrite
- One-time perf characterization — Datahike's query planner is less mature than Datalevin's in some edge cases
- Phase-3 LMDB backend needs Java 22+ (Panama FFI) — acceptable trade-off when we get there

**Risks:**
- Datahike's LMDB backend (`datahike-lmdb`) is newer than the file backend. Mitigation: phase 3 only, after file backend is stable in prod.
- Cross-database query planning is per-DB; large cross-DB joins may be less efficient than a single-DB query. Mitigation: cross-ns references are designed as value-level (UUID), cross-DB queries are expected to be rare by design.

---

## Decision 2: One database per namespace

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Seon has many namespaces, each owning different kinds of entities (users, trades, agent state, weather readings). They have different cardinalities, different growth rates, different backup requirements.

### Decision

Each Seon namespace (`:seon.weather`, `:seon.user`, `:seon.runtime`, etc.) gets its own Datahike database. All databases live under a single `data/` root.

### Rationale

- **Compartmentalization**: per-namespace schema changes don't affect other namespaces
- **Independent scaling**: hot namespaces can migrate to LMDB or S3 independently of cold ones
- **Git-friendly**: each namespace's history is separable; `git log data/seon.weather/` shows just weather changes
- **Security future-proofing**: namespace-level `d/filter` is cleaner than filtering a mega-DB
- **Matches Seon's code structure** — attribute namespaces (`:seon.weather/*`) already line up with DB names

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| One mega-DB | Single query plan, no cross-DB joins | Schema coupling, harder security filters | Gives up more than it gains |
| One DB per "domain" (trading, health) | Coarser bundling | Arbitrary boundaries, churn when reorganizing | Namespace is the natural unit |

### Consequences

**Benefits:**
- Clear ownership per namespace
- Per-namespace lifecycle — one can be wiped/restored without affecting others
- Sets up phase-4 security model naturally

**Costs:**
- Cross-namespace queries need explicit multi-DB `:in $a $b` or application-level composition
- More Integrant keys (N stores + N conns)
- Backup coordination across N stores (later concern)

---

## Decision 3: Single in-process writer (`:writer :self`)

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Datahike supports `:writer :self` (in-process), `:datahike-server` (HTTP-remote), and `:kabel` (WebSocket). Our validation testing proved:
- Two `:self` writers on the same store silently corrupt data (neither Datahike nor LMDB coordinates at the tx-log level)
- `:self` writer + shared storage between JVMs causes stale reads (the local writer's `streaming? true` short-circuits fresh-on-deref)
- `:datahike-server` writer works correctly for cross-JVM access (validated: 256 commits, zero loss, 81ms mean freshness)

### Decision

Phase 1 runs a single JVM. Each namespace DB has exactly one `:writer :self` connection, owned by Integrant. No other writers exist. Agents run as flow state machines in the same JVM; they call `seon.db` directly.

### Rationale

- **Enforced-by-construction safety** — Integrant singleton + in-JVM registry means you can't accidentally open a second writer
- **Simplest mental model** — one process, one writer per DB
- **Matches the current reality** — Seon today runs in one JVM, all agents live alongside it

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Multi-JVM via `:datahike-server` | True isolation for untrusted code | Infra complexity, HTTP deps | Over-engineered for the current threat model |
| External `datahike.http.server` process | Separate lifecycle | Same external-process problem as Datalevin | Defeats the purpose of switching |
| No writer enforcement, rely on convention | Simple | Silently corrupt on mistake | Validated as unacceptable risk |

### Consequences

**Benefits:**
- Zero network round-trip for writes
- No HTTP server to configure or harden
- No race on concurrent opens

**Costs:**
- Cannot run untrusted agent code in a separate JVM in phase 1 (mitigated: `core.async.flow` policy gate + `d/filter` provides in-JVM isolation; real OS-level isolation is phase 2+)

**Risks:**
- If we later split Seon into multiple JVMs, we'll need to revisit. The `seon.db` API shape (db-name keywords, not conn objects) was deliberately chosen so the call sites don't change even if we add a flow-routed writer later.

---

## Decision 4: File backend default for dev; LMDB/S3 are phase-3

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Konserve supports multiple backends. Each has a different profile:

| Backend | Write | Read | Portable | Git-friendly | Multi-machine | Extra setup |
|---------|-------|------|----------|--------------|---------------|-------------|
| `:file` | Good | Fast (SSD) | Yes (pure Java) | **Yes** | via shared FS | none |
| `:memory` | Fastest | Fastest | Yes | No | No | none |
| `:lmdb` | Excellent | Excellent | mac/linux/win* | No | single-box only | Java 22, liblmdb |
| `:s3` | Good | Variable | Yes | No | **Yes** | AWS |
| `:jdbc` | Good | Good | Yes | No | **Yes** | Postgres/MySQL |

(*Windows needs explicit `KONSERVE_LMDB_LIB` path.)

### Decision

Phase 1 ships with `:file` as `:dev` default, `:memory` as `:test` default. Config placeholders for `:prod-local` (LMDB) and `:prod-remote` (S3) are present but not wired until phase 3.

### Rationale

- **File backend gives us git time-travel** — kills a large class of debugging pain for AI-agent workflows ("what did the agent see?")
- **Pure Java** — works on mac, linux, windows without native libs
- **Performance is sufficient** for dev and small/medium prod workloads
- **Deferring LMDB/S3** keeps phase 1 tractable; swap-in is a config change later

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| LMDB default | Fastest | Needs Java 22 + native lib | Raises the bar for onboarding |
| S3 default | Most prod-ready | Irrelevant for dev | Wrong target |
| Two defaults (file for dev, LMDB for "real" usage) | Balanced | Schedules the migration work into phase 1 | Scope creep |

### Consequences

**Benefits:**
- Single dev setup works everywhere (including Windows)
- `git init data/` gives you time-travel debugging immediately

**Costs:**
- File backend is slower than LMDB for write-heavy workloads (accepted; unknown how much this matters in practice for Seon)
- High-churn DBs accumulate many small files — watch inode pressure, run `gc-storage!` periodically

**Risks:**
- If a Seon workload turns out to be write-heavy enough to matter, we'll need phase-3 LMDB migration sooner. Low risk for current use.

---

## Decision 5: db-name is a Clojure keyword

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Call sites need a way to address a specific namespace DB: `(d/transact! <what> tx-data)`. Options: keyword (`:seon.weather`), string (`"seon.weather"`), or a conn object.

### Decision

Use namespaced keywords matching the namespace they represent: `:seon.weather`, `:seon.user`, `:seon.runtime`. Never pass a live connection object across call-site boundaries.

### Rationale

- **Idiomatic Clojure**: keywords for identifiers, strings for text
- **Serializes cleanly** over EDN / transit — ready for flow routing without changing call-site code
- **Matches `:malli/schema` keyword namespaces** — the same namespace keyword identifies the schema group, the DB, and the Integrant component
- **Compile-time friendly**: `::keyword` expands inside the owning namespace, reducing typos
- **No conn object**: call sites don't hold a reference that might go stale across Integrant restarts

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Strings | Maximally portable | Not keyword-native, loses namespace-auto-expansion | Clojure convention prefers keywords |
| Conn object | Direct | Breaks across restarts; can't serialize | Hostile to future flow routing |
| Integer IDs | Compact | Semantically empty, requires lookup table | No reason to prefer |

### Consequences

**Benefits:**
- API stays the same whether a call runs in-process or (future) gets flow-routed
- Call sites are readable: `(d/transact! :seon.weather ...)` says what it does

**Costs:**
- Small per-call cost to deliver a message to the conn-process and await the reply (see Decision 9). In a single-JVM flow, channel operations are microseconds.

---

## Decision 9: All stateful data lives in flow state, not app-level atoms

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Seon's design principle is "flow as backbone" — every coordinating / stateful component is a `core.async.flow` process, with its state held by the flow engine. Inter-component communication goes through flow messages.

An earlier draft of this PRD proposed a `:seon.db/registry` Clojure atom mapping db-name keywords to live connections. That's the "normal" approach but it introduces a second state mechanism parallel to flow — two places state lives, two patterns to reason about.

### Decision

- **One Integrant key: `:seon.db/flow`.** It builds and starts a `core.async.flow` topology that owns all DB state.
- **One `conn-process-{ns}` per namespace**, holding the live Datahike `Connection` as its flow state. Init opens and schema-installs; halt releases.
- **One `tx-bus` process**, holding the subscriber map as flow state. Receives `:tx-report` from every conn-process, dispatches to subscribers.
- **The `seon.db` public API** composes request messages and uses `seon.flow.topology/request!` to send/await. Sync-on-top of async.
- **No app-level atoms** for registry, subscribers, or any other stateful DB data. (Datahike's own internal atoms — inside the `Connection` — stay as they are; we don't reach in.)

### Rationale

- **One pattern for state.** Flow state across the whole system — no atoms side-by-side with flow processes.
- **Natural inspection / gating point.** Every DB operation flows through a flow process on the way in and out. Future security layer (grants, policy) plugs in without a new mechanism.
- **Backpressure and ordering for free.** Flow channels expose them; an atom-based design would have to reinvent it.
- **Consistency with agent flows.** Each agent is already a flow. Agent-to-DB communication becomes flow-to-flow, matching the rest of the architecture.
- **Lifecycle correctness.** Flow halts in reverse dependency order just like Integrant; connections release cleanly when the flow halts.

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| `:seon.db/registry` atom + `:tx-bus` mult (original draft) | Simplest mechanically; zero per-call latency | Second state pattern parallel to flow; no natural policy-gate insertion point; inconsistent with "flow as backbone" | Cheap mechanism; expensive conceptual cost |
| Atoms held inside a single flow process | Merges the two | Loses flow's state-as-process ergonomics; awkward to split later | Half-measure |
| Datahike conn held as Integrant component, per-op routing decided at call site | Flexible | Ad-hoc routing, no single policy point | Drifts back toward the atom-registry shape |

### Consequences

**Benefits:**
- Single state-management story across Seon
- Every DB op passes through an inspectable point — easy to add metrics, audit logs, security filters, debugging tools
- Matches agent-flow model; fits naturally if agents later talk to DB over flow across JVMs
- Connection handles are owned by flow processes; their lifecycle is tied to the flow's lifecycle (not to separate Integrant keys that might get out of sync)

**Costs:**
- Small latency on every DB op for the channel round-trip (microseconds in-JVM; insignificant for typical workloads, negligible for non-hot-path use). If a real hot-path emerges, the conn-process can expose a direct-call escape hatch for that caller.
- More code structure upfront — defining process factories instead of just reg-ing conns in an atom
- Tests must either start a flow or stub the flow's request/reply shape; existing `*direct-mode*` no longer fits as-is

**Risks:**
- If the flow engine bugs out (process deadlock, dropped message), DB access goes dark silently. Mitigation: existing `topology/request!` has timeout + error propagation; reuse it. Monitor with a liveness check on the `:seon.db/flow` component.
- If the request-reply pattern turns out to be too async for a specific REPL use case, we can always expose a synchronous direct-call within the seon.db namespace that bypasses flow for local-only calls (documented, limited scope).

---

## Decision 6: Refs are plain UUIDs + schema registry (not tuples)

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Cross-DB references need to be self-anchored (so the caller knows what DB to resolve against). Validated two options:

- **Tuple**: `:db.type/tuple [:db.type/keyword :db.type/uuid]`, stored as `[:seon.user uuid]` — self-describing in storage
- **Plain UUID**: `:db.type/uuid`, target namespace declared in Malli schema metadata, routing via an in-memory ref-registry

Tuple storage requires `(nth ?tup 1)` destructuring in queries — awkward.

### Decision

Default convention: refs are plain `:db.type/uuid` values. The Malli schema declares the target namespace via `:seon.db/ref-to`. `pull-deep` reads this metadata to route follows to the right DB.

### Rationale

- **Queries stay clean** — `[?r :seon.weather/observer ?uid]` with no `nth`
- **Storage is minimal** — one UUID per ref, indexable natively
- **Schema is the source of truth** — target namespace lives in Malli registry, same place as all other attr metadata
- **Pulled output still self-describes** because `:seon.db/namespace` is stamped on every entity

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Tuple refs everywhere | Self-describing in storage, no registry needed | Query awkwardness | Costs exceed benefits for uniform refs |
| Two-attr refs (`:attr-ns`, `:attr-id`) | Self-describing, polymorphic | Double writes, easy to get out of sync | Worst of both worlds |
| `:db.type/ref` (native) | Auto-follow via pull | Per-DB eid only — cannot span DBs | Doesn't solve the problem |

### Consequences

**Benefits:**
- Clean queries
- Small storage
- Schema-driven routing easy to reason about

**Costs:**
- Polymorphic refs (one attr pointing to different namespaces) need the tuple escape hatch on a per-attr basis. Rare in practice.

**Risks:**
- If agents discover a need for polymorphic refs, we add `:db.type/tuple` for that specific attr without changing the default.

---

## Decision 7: Auto-stamp `:seon.db/namespace` on every entity

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

Pulled entities should be self-describing — an agent receiving `{:id X :name "Alice"}` should know whether it's a user, an org, or something else without consulting a schema.

### Decision

`seon.db/transact!` stamps `:seon.db/namespace <db-name>` on every entity map in tx-data before calling `d/transact`. Cost: one keyword datom per entity.

### Rationale

- **Self-describing pull output** — `pull-deep` results are readable without schema knowledge
- **Cheap** — one additional indexed datom per entity
- **Introspection** — audit queries like "find misplaced entities" are trivial
- **Future security** — `d/filter` predicates can route on entity namespace without having to resolve each eid through schema

### Consequences

**Benefits:**
- Agent-to-agent data exchange has unambiguous types in the payload
- Debugging is easier — every pulled entity tells you what it is

**Costs:**
- Small storage overhead (one keyword datom per entity)
- Entities transacted through `datahike.api` directly (bypassing `seon.db`) won't be stamped — enforce via code review / lint
---

## Decision 8: Integrant + flow lifecycle guarantees

**Date:** 2026-04-19
**Status:** Committed (phase 1)

### Context

The old Datalevin stack had tricky lifecycle edge cases (PID file stale, lock cleanup, adopt-on-start). Datahike embedded is simpler, but per Decision 9 all DB state lives in the flow — so Integrant only manages the flow, and the flow manages its own process lifecycles.

### Decision

**Integrant: one key.**

```clojure
{:seon.db/flow {:namespaces #ref [:seon.db/namespaces]  ;; list of db-names
                :backend    #ref [:seon.db/backend]
                :data-root  #ref [:seon.db/data-root]}}
```

`:seon.db/flow` builds a `core.async.flow` topology from the config:
- For each namespace keyword, a `:seon.db/conn-process-{ns}` process
- One `:seon.db/tx-bus` process wired to receive `:tx-report` from every conn-process

**Lifecycle contracts:**

- **Integrant init of `:seon.db/flow`** → `flow/start` → each conn-process runs its `:init` handler, which opens the konserve store (create-or-connect) and installs the Malli-derived schema. Startup fails loudly if any store can't be opened.
- **Integrant halt of `:seon.db/flow`** → `flow/stop` → conn-processes run `:halt` which releases the Datahike connection. tx-bus process cleans up subscriber channels. All in reverse dependency order.
- **Integrant suspend/resume**: no-op — files persist; the new flow opens fresh connections on resume.
- **Double-writer guard**: the conn-process factory refuses to register two processes targeting the same `:store :path`. Attempt → startup failure with clear error.
- **Crash recovery**: `SIGTERM` mid-tx → Datahike's writer has committed up to its last durable root in konserve; restart opens that root. No lock files to clean up.

### Rationale

- **Startup order is explicit** via Integrant refs
- **Halt is deterministic** — reverse of init order, all conns released before stores close
- **No external process state** — everything is in-JVM, Integrant owns it all

### Consequences

**Benefits:**
- Deterministic `(go) / (halt) / (reset)` behavior
- Crash recovery is automatic — restart reopens the store at its last committed root

**Costs:**
- N+2 Integrant keys per system (N = namespaces)
- Config drift: adding a new namespace means adding its store + conn keys (can be automated)

---

## Open Questions (Deferred Decisions)

The following are **acknowledged trade-off spaces where we deliberately haven't made a final decision.** The phase-1 architecture is designed to be compatible with any reasonable choice when the requirement concretizes.

### Q1: Cross-process DB access (if we ever need it)

**Context:** Today agents run as flow state machines in the main JVM. If we ever want real OS-level isolation for untrusted agent code, we need cross-process DB access.

**Options we've validated:**
- `:datahike-server` HTTP writer on main Seon + `:writer {:backend :datahike-server}` on agents (validated, 81ms freshness)
- Custom flow-mediated RPC (bespoke, routes writes through flow, ~30 LOC writer backend)
- Kabel WebSocket (bigger dep surface, reactive features we don't need for this case)

**Why deferred:** The current threat model ("accidental fuckups" per user) is adequately addressed by flow-level policy + in-JVM `d/filter`. OS-level isolation is a different threat model and will be solved if/when that threat arrives.

**Design compatibility:** The `seon.db` API uses db-name keywords exclusively. Call sites can be routed over flow or HTTP without any code changes at the call site — the swap is entirely inside `seon.db`.

### Q2: Security model shape

**Context:** Real security filtering needs some model. Options layered by expressiveness:

- **Classification** (fixed sensitivity label: `:public/:internal/:secret`) — simple, coarse
- **RBAC** (subjects, grants, groups — namespaces as protected objects) — middle ground
- **ABAC** (arbitrary predicates over attributes, committer, tx-time) — most flexible

**Why deferred:** The threat model (untrusted LLM agents, "accidental fuckups") suggests RBAC + namespace-level grants + `d/filter` is probably right. But we haven't lived with the pain yet, so we don't know which knobs matter.

**Design compatibility:**
- `d/filter` is a stable datahike primitive — any model compiles down to a filter predicate
- `:seon.db/namespace` + `:seon.db/committer` (tx-meta) are universal filter inputs
- Grants-as-data fits naturally into a `:seon.security` DB — same mechanism as everything else

The phase-1 code shouldn't prevent any of these; it should stay permissive and let the security layer be added above it later.

### Q3: Polymorphic refs

**Context:** Most refs target one fixed namespace (`:seon.weather/observer` → always `:seon.user`). Some might need polymorphism (e.g., `:seon.audit/subject` → could be user OR org OR agent).

**Options:**
- Tuple (`:db.type/tuple` per attr) — self-describing, query-awkward
- Two-attr pattern (`:attr-ns`, `:attr-id`) — verbose
- Discriminated union at the schema level — possible but adds complexity

**Why deferred:** No concrete polymorphic-ref use case in Seon today. Default is plain UUID + schema-declared target. Tuple is the documented escape hatch.

### Q4: Backup and checkpoint coordination

**Context:** Per-namespace DBs = N separate stores. Consistent backups across N stores need some coordination (pause writers, snapshot each, resume).

**Why deferred:** We don't have production data yet. The file backend supports `rsync`; LMDB has its own backup tools. S3 has versioning. Each backend's best-practice is different, and "what do we need" depends on data volume + RPO/RTO requirements we haven't established.

### Q5: `:attribute-refs? true` for Datomic compatibility

**Context:** Datahike supports storing attribute idents as entity IDs (`:attribute-refs? true`). Benefits: Datomic compatibility, faster integer comparisons. Cost: schema-write only, schema-migration cost.

**Why deferred:** Default is `false` (schema-read compatible). Switching is a phase-3 perf decision once we have real workload data.
