---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Sessions — user-facing isolation in the platform

> **SUPERSEDED / current shape (2026-06-03).** Two decisions locked with Sean
> on 2026-06-03 (authoritative plan:
> [platform-v2-node-first-plan-2026-06-03](../../docs/prds/agent-runtime/platform-v2-node-first-plan-2026-06-03.md),
> plumbing: [clusters-and-multi-db-wiring-2026-06-03](../../docs/prds/agent-runtime/clusters-and-multi-db-wiring-2026-06-03.md)):
>
> 1. **One JVM, many DBs — NOT one JVM/OS-process per session.** A *cluster* =
>    one datahike DB + N agents + a task + metrics; a *session* = the
>    DB-isolation boundary (one cluster's DB). Cross-cluster isolation is
>    **per-DB inside one JVM** (the JVM only runs datahike — pure queries).
>    Process/instance-split is a LATER option for crash blast-radius, kept free
>    as the degenerate single-DB case. Concurrency = per-conn datahike write
>    serialization + parallel immutable-value reads (thread-per-request by
>    db-name); the wire-server is flow-free, no core.async time-slicing.
> 2. **Node-first (V2.0), then wasm (V2.1).** Agents run as **Node CLJS
>    processes** (no wasm sandbox; trusted/single-user posture) against the JVM
>    multi-DB wire-server in V2.0. Wasm + WIT-typed capabilities arrive in V2.1.
>
> Everything below that says "separate OS process / JVM writer per session,"
> "separate sockets / broadcast channel per session," or frames the agent as a
> wasm guest reflects the OLDER spawn-per-session + wasm-only design and is now
> false for V2.0. The session-as-namespace **concept**, the agent→session
> binding being lifetime-fixed, and the use-cases all still hold; the physical
> isolation is per-DB-in-one-JVM, and the agent runtime is a Node process. An
> earlier (2026-05-26) revision already retired the multi-JVM framing; this note
> updates it further to Node-first.

A **session** is a user-facing secure namespace owned by the JVM on behalf of
one user (or one experiment). N agents collaborate inside a single session
against the same unified database. Agents don't switch sessions — an agent
has **one** continuous, evolving session, and the agent IS that session over
time. Cross-session isolation: separate datahike DBs in the same JVM,
separate konserve stores, separate tx broadcast channels.

DB name shape: `:seon.session/<name>` (e.g. `:seon.session/alice`). Master
non-session DB is `:seon`. Agent ids are separately `:seon.agent/<id>`.

## 1. Concept

- A session = one user's secure isolated namespace.
- N agents inside the session share one Datahike database, read/write the
  same datoms, and see each other's tx events through the same broadcast
  channel.
- An agent's session is fixed at instantiation. There is no "switch
  sessions" call. To start a fresh universe of state, the host spawns a
  new session with a different name; cross-session communication, if
  ever needed, is an explicit host-side concern.
- The default deployment is a single session named `"default"`. Multi-user
  / multi-experiment deployments add named sessions (`alice`, `bob`,
  `alpha`, `beta`, …). Each session is created on demand by the host —
  first reference to a session name spawns it.

## 2. Two-platform context

Seon has two tracks running in parallel right now:

- **MVP track (V0 / V1)** — a single-process Node CLJS pod with
  in-process datahike-cljs, living at `src/seon/*.cljs`. Other agents are
  evolving this for LLM integration. The MVP track does **not** have
  per-user sessions today; it's a single-tenant agent loop.
- **Platform track (V2, this PoC)** — a multi-DB client-runtime. **Current
  (2026-06-03) shape:** one JVM hosts many datahike DBs (one per cluster);
  agents run as Node CLJS processes (V2.0) bound to a cluster's DB over the
  wire-server. (The original PoC text below — "N JVM datahike-writer processes,
  one per session" + "wasm CLJS agents" — describes the superseded
  spawn-per-session + wasm-only design; see the top-of-file note.) This document
  is for the V2 track and lives entirely under `client-runtime/`.

The platform track is where multi-tenancy, training-data capture, and
parallel experiment infrastructure live. The MVP track ships the agent
loop and LLM integration first.

## 3. What's in a session

```text
data/sessions/
├── default/store/        ← konserve-file (per-session LMDB-style kv store)
├── alice/store/
├── bob/store/
└── alpha/store/

tmp/seon-client-runtime-default-req.sock   tmp/seon-client-runtime-default-pub.sock
tmp/seon-client-runtime-alice-req.sock     tmp/seon-client-runtime-alice-pub.sock
tmp/seon-client-runtime-bob-req.sock       tmp/seon-client-runtime-bob-pub.sock
...
```

In-process, each session owns:

| Resource | Type | Per-session? |
|---|---|---|
| `JvmSupervisor` (child OS process) | `tokio::process::Child` | yes |
| `WriterClient` actor (req/resp mux) | mpsc actor over UDS | yes |
| `broadcast::Sender<TxEvent>` | tokio broadcast | yes |
| Pub-subscriber task | tokio task | yes |
| `SnapshotCache` | DashMap + atomics | yes |
| Cache-invalidation listener | tokio task | yes |
| `LatencyTracker` | mutex<Vec<u32>> per op | yes |
| `TransactBatcher` (opportunistic) | mpsc + tokio task | yes |
| `DbHandle` (the public type) | thin clone of the above | yes |

Cloning a `DbHandle` is cheap (Arcs internally); every wasm agent inside a
session gets its own clone but they all point at the same writer + cache +
broadcast. That's what makes N agents in one session collaborate naturally.

## 4. Agent → session binding

Each wasm guest is bound to a session at instantiation time. The host:

1. Resolves the session from the registry: `registry.get_or_spawn(name)` —
   lazily spawns the JVM writer if this is the first reference.
2. Builds a `GuestStore` carrying that session's `DbHandle` clone.
3. Sets WASI environment variable `SEON_AGENT_SESSION=<name>` so the guest
   can inspect its own session (currently informational only — the
   binding is already physical via the `DbHandle`).
4. Instantiates the wasm component against that store.

All `seon:client-runtime/db` host imports (`q`, `transact`, `pull`,
`subscribe-tx`, etc.) thus forward to the session's writer. There is no way
for a guest to address another session's writer — the WIT surface has no
session parameter.

### Why env-based selection (v1)

`SEON_AGENT_SESSION` is plumbed end-to-end but the WIT contract does NOT yet
carry a session parameter on `connect`. The binding is established before
the wasm component instantiates, so the guest never needs to negotiate it
at runtime. This is a deliberate v1 simplification:

- One session per guest instance (lifetime-bound).
- An agent doesn't switch sessions; if you need a fresh slate, instantiate
  a new agent against a different session (cheap — ~250ms wasm load +
  ~150ms WASI ctx).
- No risk of accidental cross-session routing.

A v2 `connect(session: option<string>) -> conn-handle` is conceivable if a
single guest ever needs to address multiple sessions (e.g. an orchestrator
agent that observes across a user's experiments). Not needed yet, and it
would explicitly opt that one orchestrator out of single-session safety.

## 5. Use cases

- **Multi-tenant.** One user = one session. `data/sessions/<userid>/` and a
  pair of sockets per user. N agents inside that session share the user's
  data; no other user can see in or out.
- **Training data capture.** A session's tx-log IS the training data —
  every datom the agents wrote, with its tx timestamp, in basis-t order.
  Replay = re-issue the same transacts against a fresh session.
- **Testing / forking.** Snapshot the konserve store directory, copy it to
  a new session name, restart — instant A/B fork. The user's data is
  durably on disk, the session boundary is the namespace.
- **Parallel experiments.** Same prompt → N sessions → compare outcomes.
  Sessions stay disjoint by construction; the orchestrator merges
  results in the host. (This is the use case the Phase PF smoke
  exercises: alpha + beta + gamma running the same workload.)
- **Replay / time travel** (future). Datahike's bitemporal history is
  already per-session; `as-of` queries become "what did this user's
  session look like 10 minutes ago".

## 6. Isolation guarantees

> **Current (2026-06-03):** isolation is **per-DB inside one JVM**, not
> per-OS-process. The list below describes the superseded spawn-per-session
> model where each session had its own JVM writer process. In the current shape
> the guarantees come from separate datahike conns / on-disk stores / per-DB
> broadcast routing inside a single JVM (see clusters-and-multi-db-wiring P1).
> Process-per-cluster (separate OS processes) is retained as a LATER option for
> crash blast-radius.

**Physical, by construction (superseded spawn-per-session model):**

- Separate OS processes (the JVM writers) — no shared memory, no shared
  in-process locks.
- Separate UDS sockets — no shared transport.
- Separate on-disk stores — `data/sessions/<name>/store/` is a complete
  konserve store with its own basis-t line, schema history, and datom log.
- Separate in-process `SnapshotCache`, broadcast channel, batcher — no
  shared atom or mutex.

**Verified empirically (Phase PF smoke, see README):**

- 2 sessions × 2 agents × 30s — alpha and beta task-id sets disjoint;
  each session's reader saw exactly its own session's writer events;
  zero `out-of-order` events.
- 3 sessions × 1 agent × 60s — same.

## 7. Cross-session communication

Out of scope for v1. If a future orchestrator agent needs to observe
across sessions, the host adds an explicit "shared" or "admin" session
that holds correlation data, and grants only that orchestrator agent a
multi-session `connect` op (see §4). Normal user-facing agents never see
across sessions, by WIT contract.

## 8. Lazy spawn lifecycle

`SessionRegistry::get_or_spawn(name)`:

1. Acquires the registry mutex.
2. If `name` is already in the map, returns the cached `Arc<Session>`.
3. Otherwise spawns the JVM child process, waits for both UDS sockets
   to come up (poll-then-connect, up to 60s for cold start), wires the
   writer actor + pub subscriber + cache listener + batcher tasks,
   pings to confirm liveness, inserts the new `Arc<Session>` into the
   map, and returns it.
4. Releases the mutex.

For parallel spawn (multi-session smoke), the host calls `get_or_spawn`
for each session inside `futures::future::try_join_all`. Each call holds
the mutex briefly during the slot reservation; the slow JVM boot happens
before the lock is held, so sessions boot concurrently.

Sessions are not currently torn down individually — they live for the
lifetime of the host process. The `JvmSupervisor`'s `Child` is held with
`kill_on_drop(true)`, so when the host exits, every session's JVM is
killed. A future `drop_session(name)` op is feasible (the `Arc<Session>`
reference count would need to drop to zero, which requires removing it
from the map AND all wasm guests bound to it terminating).

## 9. CLI usage

Single-session (the default; "default" session is just a session):

```bash
./target/release/client-runtime-host                    # REPL, default session
./target/release/client-runtime-host --smoke            # one-shot smoke, default session
./target/release/client-runtime-host \
  --guest-wasm <wasm> --multi-agent --multi-duration-ms 30000
# 3 agents (writer/reader/mixed) in default session
```

Multi-session:

```bash
./target/release/client-runtime-host \
  --guest-wasm <wasm> --multi-session \
  --sessions alpha,beta \
  --agents-per-session 2 \
  --multi-duration-ms 30000
```

- `--multi-session` enables the multi-session path.
- `--sessions NAMES` — comma-separated session names. Each becomes its
  own isolated runtime.
- `--agents-per-session N` — how many guests to spawn per session. Roles
  cycle through `writer`, `reader`, `mixed` (so `--agents-per-session 3`
  gives one of each; `--agents-per-session 2` gives writer + reader).
- `--multi-duration-ms` — how long each guest's workload runs.

Aggregate output: per-session task counts, cache stats, per-op latency
percentiles, plus a cross-contamination check that verifies the
`:task/id` sets across any two sessions are disjoint.

## 10. Configuration file (deferred to v2)

A `sessions.edn` declarative spec
(`{:sessions [{:name "alice" :agents [...]}]}`) would let the host
pre-spawn sessions at startup and pin per-session configs (backend, store
path overrides, agent role mix, per-user metadata). Not implemented in v1
— the CLI flags + lazy spawn cover the current use cases.

## 11. Deferred to v2

- **WIT `connect(session)` field.** Env-based selection is sufficient for
  v1. A `connect(session: option<string>)` form would let one wasm
  instance address multiple sessions, useful only for an explicit
  orchestrator role.
- **`sessions.edn` config** (see §10).
- **`drop_session(name)`** (see §8).
- **Per-session metadata** (owning user id, created-at, retention policy)
  — currently the session name IS the identity.

## Implementation pointers

- `client-runtime/host/src/main.rs` — `Session`, `SessionRegistry`,
  `run_multi_session`.
- `client-runtime/host/src/guest.rs` — `GuestStore::with_env` accepts arbitrary
  WASI env vars; the host sets `SEON_AGENT_SESSION=<name>` when
  instantiating a guest for a session.
- `src/seon/server/src/seon/server/writer.clj` —
  `--path` and `--req-sock` / `--pub-sock` flags already parameterize
  the writer; no per-session changes needed inside the JVM.
- `guest-cljs/src/seon/client_runtime/agent.cljs` — agent
  unchanged; sees its session through the WASI env if it cares. The
  binding is established by the host before the agent runs.
