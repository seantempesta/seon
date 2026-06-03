---
type: prd
status: draft
tags: [prd, agent, database, flow]
---

# Clusters + multi-DB wiring (P1 seam) — 2026-06-03

> A **cluster** is one datahike database + the N wasm-sandboxed agents
> collaborating on it toward a task. We launch many clusters with different
> tasks and measure how they perform. This doc specifies the core-DB plumbing
> (P1) that makes many clusters share one JVM, and the seam the reactive engine
> (the other track) plugs into.

Vocabulary is canonical per [glossary](glossary.md).

## Decisions locked (2026-06-03)

- **Name:** *cluster* = one database + its N agents + a task + metrics. The
  isolation boundary is one **database** (one datahike conn). *session* is
  **fully retired** (it pre-loads user/auth semantics and collides with nREPL
  sessions); the registry renames `seon.server.session` → `seon.server.registry`
  and `:seon.session/<name>` → `:seon.cluster/<name>` in P1. The only surviving
  "session" is the nREPL/MCP eval session (see glossary). An *agent* runs in a
  *guest* (Node now,
  isolated runtime later).
- **Isolation, now:** **one JVM, many DBs.** Cheapest per-cluster (a new
  datahike conn is sub-second vs ~11s JVM cold start); scales to many for
  parallel experiments. Agents run in wasm, not the JVM, so the JVM only runs
  datahike (pure queries, mature) — the crash/OOM surface is "big query / big
  DB," not "agent ran wild."
- **Isolation, later:** process-per-cluster (or process-per-tenant) for crash
  blast-radius containment. P1 must keep this path free: a single-DB process is
  just the degenerate case of the DB registry.
- **Concurrency:** no core.async time-slicing. Per-conn write serialization
  (datahike's own model) + fully-parallel immutable-value reads, on a
  thread-per-request pool dispatched by db-name. The wire-server stays
  flow-free by design (`session.clj`).

## Current state (verified 2026-06-03)

- `wire.clj` — "owns the **single** Datahike connection." Every `handle-op`
  threads one `conn`. Broadcast event **hardcodes** `"db-name" "default"`
  (`wire.clj:232`).
- `broadcast.clj` — a **process-global** subscriber set; no per-DB routing.
- `session.clj` — the `{db-name → conn}` + `{agent-id → db-name}` registry
  **exists and is tested** (`session_test.clj`) but `wire.clj` does **not**
  use it (`grep` → 0).
- `listen!` is called **nowhere** server-side.
- Rust host (`main.rs`) — `SessionRegistry::get_or_spawn` spawns **one JVM
  child per session** + one pub socket + one broadcast channel each.
- DB-op surface: **green** — 61 tests / 240 assertions, 0 failures
  (re-verified this session; not just a doc claim).

So: the multi-DB registry is built but unwired; broadcast can't target a DB;
there's no reactive hook. These are exactly the prerequisite gaps the reactive
track flagged.

## P1 scope — make many clusters share one JVM

### JVM wire-server (`src/seon/server/`)

1. **Per-request conn resolution.** Requests carry `db-name` (or `agent-id`,
   resolved to db-name via `session.clj`'s `{agent-id → db-name}` index).
   `handle-op` resolves its conn from `session/!registry` instead of receiving
   a single ambient conn. Unknown db-name → typed `not-found` error.
2. **`ensure-db` op.** Idempotent: open (or create-with-schema) the conn for a
   db-name, register it in `session.clj`, return its current basis-t. This is
   how the host materializes a cluster's DB.
3. **Real db-name on broadcast events.** Kill the hardcoded `"default"`; the
   event carries the db-name of the conn that committed.
4. **Per-DB broadcast routing.** `broadcast.clj` keys subscriber interest by
   db-name (or emits a single tagged stream the host demuxes — see host
   section). A subscriber to cluster A never receives cluster B's events.
5. **`listen!`-registration hook per conn — the reactive seam.** On
   `ensure-db`, register a single datahike `d/listen!` per conn whose callback
   is the integration point for the reactive engine (the other track). Default
   callback = the existing raw `broadcast!`. The reactive engine replaces/wraps
   it to do pattern routing and emit the second event type. **I own the hook;
   the reactive track owns what runs inside it.**

### Rust host (`client-runtime/host/`)

6. **`SessionRegistry`: spawn-per-session → one-writer + DB-registry.** Spawn a
   single JVM writer at host start. `get_or_spawn(name)` becomes
   `ensure_cluster(name)` → send `ensure-db` to the writer + set up the
   per-DB broadcast channel. No new JVM process per cluster.
7. **Pub demux by db-name.** The single pub socket now carries all clusters'
   events, each tagged with db-name. `run_pub_subscriber` demuxes to the
   correct per-cluster `broadcast::Sender`. Guests still bind to one cluster
   (WIT has no db-name on the read/write path — the host routes by which
   cluster the guest is bound to, adding db-name to the request envelope).
8. **Snapshot cache keyed per-DB.** Cache key gains db-name so cluster A's
   cached reads never serve cluster B.

### Degenerate single-DB case (preserves the later process-split)

Everything above no-ops cleanly when there's one DB: the registry has one
entry, the demux has one channel, db-name routing is identity. The later
"process-per-cluster for crash isolation" path is then just "run K host+writer
pairs, each with one DB" — no rework.

## The reactive seam (for the other track)

```
d/transact conn            ; per-conn, serialized by datahike
   │  (synchronous)
   ▼
d/listen! callback (P1 hook, one per conn)     ← reactive engine plugs in HERE
   │   default: bcast/broadcast! raw tx event
   │   reactive: Posh pattern routing → emit "really-changed summaries + results"
   ▼
broadcast.clj  (per-DB routed)
   │
   ▼
Rust host pub demux → per-cluster broadcast::Sender → guests

```

Contract I provide / they consume:
- I give: a per-conn `listen!` hook, the raw `tx` event (kept — needed for
  own-tx dedup + basis-t catch-up), real db-name tagging, per-DB routing.
- They give: the engine inside the hook + the second event-type payload shape.
- **Keep both event types.** Raw `tx` (cache-priming, dedup, catch-up) +
  semantic `changed-summaries` (the routing win).

## Test plan (quality coverage, P1)

- **Multi-DB isolation (wire level):** two DBs in one process; writes to A
  never appear in B's queries or B's broadcast stream; basis-t lines
  independent. (Generative: random interleaved txns across K DBs.)
- **Per-request routing:** request with db-name X hits conn X; unknown name →
  typed error; agent-id → db-name resolution.
- **`ensure-db` idempotency:** second call returns the same conn, no reseed.
- **Broadcast routing:** subscriber to A gets only A's events, in basis-t
  order, zero cross-bleed (the multi-cluster analog of the Phase PF check).
- **listen! hook:** default hook fires raw event on commit; a replaced hook
  receives the full synchronous `TxReport`.
- **Wire-op contract (P0, locks the surface before P1):** Transit roundtrip +
  every op as generative/property tests at the boundary.

## Dependencies / coordination

- **Reactive track:** their Milestone 2 == this doc's P1. They build on the
  `listen!` hook + define the changed-summary payload. Schema for
  agent/summary/subscription/fn entities (their gap b) — **fresh server-side**
  (decided 2026-06-03), not ported from the V0 pod.
- **Build:** the guest `.wasm` rebuild shares the live shadow-cljs server
  (:9630). Back up any working `.wasm` before rebuilding; coordinate timing.

## REPL access (cross-cutting — full design in research doc)

Two REPL targets, with the hard requirement that the **diagnostic plane is
external and has an independent failure domain** from the data path:

- **JVM/DB server:** dev-mode, localhost-bound, flag-gated nREPL inside the
  wire-server. One REPL reaches every cluster via `session.clj`'s `!registry`.
  This is the always-available *external* diagnostic — it lives in a different
  process/fiber than any agent, and the agents' eval log + state are queryable
  datoms, so a wedged guest can be observed by querying the DB without touching
  the guest.
- **Agent (wasm guest):** the guest is a single QuickJS fiber → any in-fiber
  REPL shares fate with whatever the fiber is doing. So in-guest REPL is a
  *convenience* for interactive agent dev, NOT the primary diagnostic. It must
  be paired with a host-side watchdog (per-agent heartbeat/liveness) +
  wasmtime epoch-interruption/fuel preemption so a stuck agent can be detected
  and torn down/restarted **from outside the fiber**. Whether REPL eval is a
  control message on the wake channel or a dedicated channel is secondary to
  the independent-observation requirement.

Full investigation + plan: `research/repl-access-design-2026-06-03.md`.

## Out of scope for P1

- Cluster declarative config (`clusters.edn`) — P2.
- LLM HTTP capability + real agent turn in guest — P3.
- Per-cluster metrics/comparison — P4.
- The reactive engine itself — other track.
