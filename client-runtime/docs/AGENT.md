---
type: orchestrator
status: active
tags: [orchestrator, agent, database]
---

# AGENT.md — client-runtime (V2 / Platform track)

> **SUPERSEDED / current shape (2026-06-03).** Read the authoritative plan
> FIRST: [platform-v2-node-first-plan-2026-06-03](../../docs/prds/agent-runtime/platform-v2-node-first-plan-2026-06-03.md)
> (+ [clusters-and-multi-db-wiring-2026-06-03](../../docs/prds/agent-runtime/clusters-and-multi-db-wiring-2026-06-03.md)).
> Two locked decisions change this file's assumptions:
>
> 1. **One JVM, many DBs** — a *cluster* = one DB + N agents + task + metrics;
>    a *session* = one cluster's DB (the isolation boundary). Cross-cluster
>    isolation is per-DB inside ONE JVM, not one JVM/OS-process per session.
>    Process-split is a LATER crash-isolation option.
> 2. **Node-first.** In **V2.0** agents run as **Node CLJS processes** (no wasm
>    sandbox; trusted/single-user) against the JVM multi-DB wire-server — this
>    is the convergence of the MVP track's real agent loop with the Platform
>    track's multi-DB server. **V2.1** swaps Node→wasm + WIT-typed capabilities.
>    The wasm work is sequenced after the Node POC, not cancelled.
>
> Below, wherever this file says "wasm guest," "JVM writer per session," or
> "separate process per session," read it as the V2.1 (wasm) / superseded
> (per-process) design. The overlay mechanism, wire protocol, and DB-op surface
> all carry forward to the Node runtime unchanged. An earlier 2026-05-26
> revision already retired the multi-JVM framing; this note updates it to
> Node-first.

## TL;DR

You are in `client-runtime/`. **This is V2 — the future platform**, NOT
the MVP. The MVP (V1) lives at `src/seon/*.cljs` and is where active LLM agent
work happens. Do not touch V1 from a V2 task and do not touch V2 from a V1
task. If your task is in this directory, read this file end-to-end before
editing anything. Cutover progress: [CUTOVER.md](CUTOVER.md). Full status:
[README.md](README.md).

## Two-platform map

| Track | Path | What it is | Owner |
|---|---|---|---|
| **V1 / MVP** | `src/seon/*.cljs` | Single-process Node CLJS pod. In-process datahike-cljs. Real LLM agent loop, deepseek HTTP client, bootstrap CLJS compiler, loopback HTTP+SSE. Run via `node out/client/main.js`. | MVP-track agents |
| **V2 / Platform** | `client-runtime/` | Multi-session client-runtime. JVM datahike writer per session + Rust host + wasm32-wasip2 CLJS guests. Transit-JSON values inside CBOR envelopes over UDS. | Platform-track agents (you, if you're reading this) |

There is **one git tree** and **one feature branch (`feature/agent-runtime`)**.
Both tracks evolve in parallel and we will retire V1 when the V2 cutover
checklist is green. Until then: stay in your lane.

## What V2 has that V1 doesn't

- **Multi-session isolation.** Separate JVM writer, sockets, store, snapshot
  cache, and broadcast channel per session. Cross-session contamination is
  physically impossible. See [SESSIONS.md](SESSIONS.md).
- **JVM-AOT datahike.** Real Clojure JVM owns the connection — full feature
  set, hot-reloadable, REPL-friendly, ~125ms p50 commit through IPC.
- **Snapshot cache.** Rust host caches `(basis-t, query, args)` results;
  warm reads are sub-microsecond. 99.1% hit rate on cache-friendly workloads.
- **Transit-JSON wire fidelity.** Keywords, sets, instants, ratios, BigInts
  round-trip cleanly between CLJS guest and JVM writer. Rust host treats
  values as opaque blobs.
- **WASI-sandboxed FS.** Guests get scoped read-only and read-write preopens
  per session via `seon.client-runtime.fs`. RO violations return EACCES.
- **Multi-agent fanout.** N wasm guests per session, all listening on one
  broadcast channel. Phase D N=3 / 300s smoke: 0 out-of-order, 0 errors.
- **Per-session WASI env.** `SEON_AGENT_SESSION=<name>` plumbed to every guest.

## What V1 has that V2 doesn't (yet)

- **Real LLM-driven agent loop.** `src/seon/agent.cljs` already wires
  deepseek + tool calls + turn driver + section composer + warnings.
- **HTTP+SSE UI.** Loopback web UI for the agent loop.
- **Run-the-real-thing.** Today, only V1 actually drives an LLM with tools.
  V2 has a synthetic-workload agent (writer/reader/mixed) plus
  `learn`/`fs-smoke` smokes; the V0 agent has NOT been ported into a wasm
  guest yet.

## Cutover state

See [CUTOVER.md](CUTOVER.md) for the full checklist. High-level:
through Phase PF (multi-session isolation) — GREEN. **Current (2026-06-03):**
the next-blocking item is the V2.0 work — repoint the real V0 agent loop at a
**Node** wire client against the JVM multi-DB wire-server (native `fetch` LLM
HTTP, no capability work), proving the architecture end to end. The wasm port
(real agent turn in a wasm guest, LLM HTTP through WIT) moves to **V2.1**,
sequenced after the Node POC — not the immediate blocker. Tauri integration is
out of scope for cutover.

## Hard rules for agents working in V2

**Do not touch these paths from a V2 task:**

- `src/seon/` — V1 source. Owned by MVP track. If you need to look at V1
  to understand what V2 must reproduce, read but do not edit.
- `pod-host/wasm-tauri/` — older spike workspace. Reference for build
  patterns (`eval-smoke-build/` has a working shadow-cljs → wasm chain),
  but treat as read-only from a V2 task.
- `pod-host/libdatahike-cljs/` — separate native-datahike attempt. Not
  the path we're on.

**Always use the overlay, never the V0 source directly.** When porting V0
code into a guest, place it under `guest-cljs/src-overlay/seon/` (see
"Where things live"). The overlay namespace declares the same `seon.foo`
ns names V0 uses, but its `:require` graph points at
`seon.client-runtime.datahike` instead of `datahike.api`. **Never** copy a `.cljs`
file from `src/seon/` and edit it in place inside `guest-cljs/src/`; the
overlay system exists precisely so V0 and V2 can diverge cleanly.

**Always namespace your sockets and stores by session.** `default` is a
session like any other. Do not hardcode `tmp/seon-client-runtime-req.sock`; use the
session-suffixed forms `tmp/seon-client-runtime-<session>-req.sock`.

## Where things live

| Path | Purpose |
|---|---|
| `README.md` | Phase-by-phase status, run commands, smoke output. Authoritative. |
| `CUTOVER.md` | Cutover checklist V1 → V2. |
| `SESSIONS.md` | Session concept, isolation guarantees, CLI usage. |
| `PROTOCOL.md` | Wire protocol — CBOR envelope + Transit-JSON values. |
| `src/seon/server/` | The JVM wire-server (sole datahike master). `codec`, `wire`, `broadcast`, `store`, `session`, `transit`; `test/seon/server/`. |
| `resources/seed/` | `facts-schema.edn` + `facts-seed.edn` — knowledge base seed. |
| `client-runtime/host/` | Rust host. `src/main.rs` (Session, registry, REPL, smokes), `src/guest.rs` (wasm bridge), `wit/db.wit`. |
| `client-runtime/host/target/` | gitignored build artifacts. |
| `guest-cljs/src/` | Overlay-side support code (`seon/client_runtime/{wit,datahike,agent,fs,facts}.cljs`). |
| `guest-cljs/src-overlay/seon/` | **Overlay namespaces** — V0 ns shadows that route through the overlay's WIT/db surface. Add new ports here. |
| `guest-cljs/test/` | CLJS tests for guest code. |
| `guest/guest.mjs` | Legacy JS-only smoke guest (Phase 3). |
| `guest/agent.mjs` | ESM shim that imports the WIT host module + the CLJS bundle. |
| `guest-cljs/build/crate/` | gitignored wasm-rquickjs wrapper crate for `guest.mjs`. |
| `guest-cljs/build/crate/` | gitignored wasm-rquickjs wrapper crate for the CLJS agent. |
| `bench/` | Bench reports — `tx-batcher-and-cache-fix-*.md`, `v0-port-survey.md`. |
| `data/` | gitignored. Per-session konserve stores (`data/sessions/<name>/store/`) + scratch mounts. |
| `data/phase-*-*.log` | Smoke run output. |
| `smoke/` | Driver scripts. |
| `build-guest` | One-shot script: builds CLJS bundle + wasm component, optional `--run`. |

## Build commands

```bash
# (a) Build the CLJS guest (shadow-cljs + wasm-rquickjs + cargo).
cd /Users/sean/src/seon/client-runtime
./build-guest

# (b) Build the Rust host alone.
cd /Users/sean/src/seon/client-runtime/host
cargo build --release

# (c) Run the Phase D N=3 multi-agent smoke (300s).
./build-guest --run --duration-ms=300000

# (c') Or short iteration (15s).
./build-guest --run --duration-ms=15000

# (d) Run JVM wire-server tests. From the REPL: (user/run-tests 'seon.server.<ns>-test)
#     or the whole server suite via the project test runner. The server tests
#     live at test/seon/server/ under the repo root (NOT a separate
#     src/seon/server/deps.edn — there is none; the :writer alias is in the
#     ROOT deps.edn). Latest green: 61 tests / 237 assertions for seon.server.*.

# (e) Multi-session smoke (Phase PF).
cd /Users/sean/src/seon/client-runtime/host
cargo run --release -- \
  --guest-wasm ../guest-cljs/build/crate/target/wasm32-wasip2/release/guest.wasm \
  --multi-session --sessions alpha,beta --agents-per-session 2 --multi-duration-ms 30000
```

## Wire protocol (one paragraph)

Two-layer encoding: a **CBOR control envelope** (op name, basis-t, handle,
request-id, etc. — string keys, opaque to Rust) wraps **Transit-JSON
strings** for all Clojure values (queries, args, tx-data, tx-meta, datom
a/v, results). The Rust host forwards Transit blobs as opaque `String`s —
only the JVM writer and the CLJS guest encode/decode. Pub events ship the
full datahike tx-report shape (`tx-data`, `tx-meta`, `basis-t-before`,
`db-name`, `request-id`). EDN-string fallback exists on the input side for
the Rust REPL/smoke harness but **is slated for removal at cutover** — do
not add new EDN-only callers. See [PROTOCOL.md](PROTOCOL.md).

## Session concept (one paragraph)

A **session** is a user-facing secure namespace. N agents collaborate
inside one session against the same Datahike database; cross-session is
physically impossible (separate JVM, separate sockets, separate
on-disk konserve store, separate broadcast channel, separate snapshot
cache). The default deployment uses session name `"default"`. Sessions
are spawned lazily on first reference via `SessionRegistry::get_or_spawn`.
Each guest is bound to one session at instantiation; the WIT surface has
no session parameter and a guest cannot reach another session. See
[SESSIONS.md](SESSIONS.md).

## Common gotchas

1. **Three wire smells are now fixed.** (a) `:added` keyword access on Datom
   (no reflection warning), (b) Transit-JSON for value payloads (no more
   keyword/inst fidelity loss), (c) `payload` single-decode field on every
   response (guest decodes one Transit string instead of N fields). If you
   see code reading per-field structured responses on the CLJS side, prefer
   the `:payload` path.
2. **ALS / async-context isolation gap.** Phase D ran N=3 agents in one
   process, but cross-await context isolation under `AsyncLocalStorage` is
   NOT yet smoked. If you wire new async paths in the host, assume the
   isolation contract is not yet enforced — add a smoke before claiming it.
3. **Build artifacts are gitignored.** `client-runtime/host/target/`, `guest-cljs/build/crate/`,
   `guest-cljs/build/crate/`, `data/` — never commit these. Source of
   truth for build inputs is `shadow-cljs.edn` (root) + `Cargo.toml` +
   `wit/db.wit` + the CLJS sources.
4. **`(d/db conn)` basis-t.** The cache-friendly workload didn't deliver
   hits as designed (README Phase D'). Hypothesis: the snapshot's basis-t
   silently coerces to 0 on the WIT boundary. If you touch overlay's
   `basis-t-of` or the `q-call` argument plumbing, instrument first.
5. **wasm-rquickjs host imports are synchronous from QuickJS's POV.** A
   blocking `next-tx-event` starves the wasm event loop. The fix in place
   is `try_recv` + 25ms setTimeout yield in the overlay listener loop —
   do not "improve" this by blocking the host call.
6. **BigInt coercion at the WIT boundary.** WIT `s64` ↔ JS BigInt. Passing
   a `Number` throws `Error converting from js 'int' into type 'big_int'`.
   Overlay's `seon.client-runtime.wit` handles this; new wrappers must too.
7. **wstd `block_on` requires an empty task queue to settle.** Listener
   loops must terminate via `stop!` when the agent's work completes,
   otherwise the wasm export call hangs.
8. **JVM cold start is ~11s per session.** Tolerable for long-running pods,
   not for per-request spawns.
9. **Konserve-jdbc is NOT working.** Pinned to konserve-file. Don't sink
   time into konserve config (per the PRD contingency); if SQLite is ever
   needed, see README's "Phase 1 — what didn't work" section.

## When in doubt

- Read [README.md](README.md) phase notes for the system you're touching.
- For new CLJS overlay ports, copy the pattern in
  `guest-cljs/src-overlay/seon/db.cljs` (already shadows the V0 ns).
- For new protocol ops, follow the Phase B trail: WIT + Rust host
  `db_iface::Host` + JVM writer `handle-op` + protocol/overlay tests.
- For Tauri or end-user-facing UI changes — **stop**, that's out of scope
  for cutover; flag it.
