---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Sessions — user-facing isolation in the platform

> **REVISED 2026-05-26 PM.** Original design assumed N JVM processes (one per
> session). New design: **ONE JVM owns all sessions** as separate datahike DBs.
> Isolation is per-DB inside the same JVM, not per-process. See
> `docs/prds/agent-runtime/integration-architecture-2026-05-26.md` for the
> authoritative current shape. This file kept for the conceptual model;
> file-layout and lifecycle sections supersede below.

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
- **Platform track (V2, this PoC)** — a multi-session client-runtime. A Tauri
  Rust host owns N JVM datahike-writer processes (one per session) and
  embeds wasm CLJS agents (multiple per session). This document is for
  the V2 track and lives entirely under `client-runtime/`.

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

**Physical, by construction:**

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
