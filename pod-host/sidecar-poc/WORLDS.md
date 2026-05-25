---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Multi-world isolation (Phase PF)

Parallel agents working on the same goal in physically isolated datahike
databases. Multiple agents share a goal, each runs in its own world, and the
operator can compare outcomes across worlds (e.g. "run the same prompt to 3
agents, see which world reaches the best solution"). No state crosses world
boundaries unless explicitly synthesized at a higher layer.

## Concept

A **world** is a complete sidecar runtime instance:

- One JVM Datahike writer subprocess
- Its own konserve-file store on disk
- Its own pair of UDS sockets (req/resp + pub)
- Its own snapshot cache + tx broadcast channel + opportunistic transact
  batcher in the Rust host
- Its own pool of wasm guest agents

The default deployment is a single world named `"default"`. Multi-world
deployments add named worlds (`alpha`, `beta`, `gamma`, …). Each world is
created on demand by the host — first reference to a world name spawns it.

## Per-world resources

```
data/worlds/
├── default/store/        ← konserve-file (per-world LMDB-style kv store)
├── alpha/store/
├── beta/store/
└── gamma/store/

/tmp/seon-poc-default-req.sock   /tmp/seon-poc-default-pub.sock
/tmp/seon-poc-alpha-req.sock     /tmp/seon-poc-alpha-pub.sock
/tmp/seon-poc-beta-req.sock      /tmp/seon-poc-beta-pub.sock
...
```

In-process, each world owns:

| Resource | Type | Per-world? |
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

Cloning a `DbHandle` is cheap (Arcs internally); each wasm guest in a world
gets its own clone.

## Agent → world binding

Each wasm guest is bound to a world at instantiation time. The host:

1. Resolves the world from the registry: `registry.get_or_spawn(name)` —
   lazily spawns the JVM writer if this is the first reference.
2. Builds a `GuestStore` carrying that world's `DbHandle` clone.
3. Sets WASI environment variable `SIDECAR_WORLD=<name>` so the guest can
   inspect its own world (currently informational only — the binding is
   already physical via the `DbHandle`).
4. Instantiates the wasm component against that store.

All `seon:sidecar/db` host imports (`q`, `transact`, `pull`,
`subscribe-tx`, etc.) thus forward to the world's writer. There is no way
for a guest to address another world's writer — the WIT surface has no
"world" parameter.

### Why env-based selection (v1)

`SIDECAR_WORLD` is plumbed end-to-end but the WIT contract does NOT yet
carry a world parameter on `connect`. The binding is established before
the wasm component instantiates, so the guest never needs to negotiate it
at runtime. This is a deliberate v1 simplification:

- One world per guest instance (lifetime-bound).
- Cross-world calls require a new wasm instance (cheap — ~250ms loading
  + ~150ms WASI ctx).
- No risk of accidental cross-world routing.

A v2 `connect(world: option<string>) -> conn-handle` is feasible if a
single guest ever needs to address multiple worlds. Not needed yet.

## Lazy spawn lifecycle

`WorldRegistry::get_or_spawn(name)`:

1. Acquires the registry mutex.
2. If `name` is already in the map, returns the cached `Arc<World>`.
3. Otherwise spawns the JVM child process, waits for both UDS sockets
   to come up (poll-then-connect, up to 60s for cold start), wires the
   writer actor + pub subscriber + cache listener + batcher tasks,
   pings to confirm liveness, inserts the new `Arc<World>` into the
   map, and returns it.
4. Releases the mutex.

For parallel spawn (multi-world smoke), the host calls `get_or_spawn`
for each world inside `futures::future::try_join_all`. Each call holds
the mutex briefly during the slot reservation; the slow JVM boot
happens before the lock is held, so worlds boot concurrently.

Worlds are not currently torn down individually — they live for the
lifetime of the host process. The `JvmSupervisor`'s `Child` is held with
`kill_on_drop(true)`, so when the host exits, every world's JVM is killed.

## Isolation guarantees

**Physical, by construction:**

- Separate OS processes (the JVM writers) — no shared memory, no shared
  in-process locks.
- Separate UDS sockets — no shared transport.
- Separate on-disk stores — `data/worlds/<name>/store/` is a complete
  konserve store with its own basis-t line, schema history, and datom log.
- Separate in-process `SnapshotCache`, broadcast channel, batcher — no
  shared atom or mutex.

**Verified empirically (Phase PF smoke, 2026-05-25, see README):**

- 2 worlds × 2 agents × 30s — alpha and beta task-id sets disjoint;
  each world's reader saw exactly its own world's writer events;
  zero `out-of-order` events.
- 3 worlds × 1 agent × 60s — same.

## Out of scope (v1)

- **Cross-world reads/writes.** No WIT op spans worlds. If an orchestrator
  needs to merge results from multiple worlds, it does so in the Rust
  host (or above), not inside a guest.
- **Shared schema across worlds.** Each world installs its own schema on
  startup. Default-world conventions can be replicated by transacting
  the same schema in every world — `install_phase_d_schema` in the host
  does this for the multi-world smoke.
- **Dynamic world spawn from a guest.** Guests cannot create new worlds.
  Only the host can; the registry isn't exposed across WIT.
- **Per-world shutdown.** Worlds live for the host's lifetime. A future
  `drop_world(name)` op is feasible (the `Arc<World>` reference count
  would need to drop to zero, which requires removing it from the map
  + all wasm guests bound to it terminating).
- **Cross-world causality.** No global clock; each world has its own
  basis-t line. Cross-world comparison must use wall-clock or a host-
  side correlation id.

## CLI usage

Single-world (the default; "default" world is just a world):

```bash
./target/release/sidecar-host                    # REPL, default world
./target/release/sidecar-host --smoke            # one-shot smoke, default world
./target/release/sidecar-host \
  --guest-wasm <wasm> --multi-agent --multi-duration-ms 30000
# 3 agents (writer/reader/mixed) in default world
```

Multi-world:

```bash
./target/release/sidecar-host \
  --guest-wasm <wasm> --multi-world \
  --worlds alpha,beta \
  --agents-per-world 2 \
  --multi-duration-ms 30000
```

- `--multi-world` enables the multi-world path.
- `--worlds NAMES` — comma-separated world names. Each becomes its own
  isolated runtime.
- `--agents-per-world N` — how many guests to spawn per world. Roles
  cycle through `writer`, `reader`, `mixed` (so `--agents-per-world 3`
  gives one of each; `--agents-per-world 2` gives writer + reader).
- `--multi-duration-ms` — how long each guest's workload runs.

Aggregate output: per-world task counts, cache stats, per-op latency
percentiles, plus a cross-contamination check that verifies the
`:task/id` sets across any two worlds are disjoint.

## Configuration file (deferred)

A `worlds.edn` declarative spec (`{:worlds [{:name "alpha" :agents [...]}]}`)
would let the host pre-spawn worlds at startup and pin per-world configs
(backend, store path overrides, agent role mix). Not implemented in v1 —
the CLI flags + lazy spawn cover the use cases.

## Implementation pointers

- `rust-host/src/main.rs` — `World`, `WorldRegistry`, `run_multi_world`.
- `rust-host/src/guest.rs` — `GuestStore::with_env` accepts arbitrary
  WASI env vars; the host sets `SIDECAR_WORLD=<name>` when instantiating
  a guest for a world.
- `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj` —
  `--path` and `--req-sock` / `--pub-sock` flags already parameterize
  the writer; no per-world changes needed inside the JVM.
- `pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/agent.cljs` — agent
  unchanged; sees its world through the WASI env if it cares. The
  binding is established by the host before the agent runs.
