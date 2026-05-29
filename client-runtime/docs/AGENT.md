---
type: orchestrator
status: active
tags: [orchestrator, agent, database]
---

# AGENT.md — sidecar-poc (V2 / Platform track)

> **REVISED 2026-05-26 PM.** Architecture pivoted from "multi-JVM (one per
> session)" to "**one seon JVM owns all sessions as multi-DB datahike**". This
> file is being kept for guest-side (wasm CLJS) mental model. For JVM-side
> integration shape and file layout, **read first**:
> `docs/prds/agent-runtime/integration-architecture-2026-05-26.md`.

## TL;DR

You are in `pod-host/sidecar-poc/`. **This is V2 — the future platform**, NOT
the MVP. The MVP (V1) lives at `src/seon/*.cljs` and is where active LLM agent
work happens. Do not touch V1 from a V2 task and do not touch V2 from a V1
task. If your task is in this directory, read this file end-to-end before
editing anything. Cutover progress: [CUTOVER.md](CUTOVER.md). Full status:
[README.md](README.md).

## Two-platform map

| Track | Path | What it is | Owner |
|---|---|---|---|
| **V1 / MVP** | `src/seon/*.cljs` | Single-process Node CLJS pod. In-process datahike-cljs. Real LLM agent loop, deepseek HTTP client, bootstrap CLJS compiler, loopback HTTP+SSE. Run via `node out/client/main.js`. | MVP-track agents |
| **V2 / Platform** | `pod-host/sidecar-poc/` | Multi-session sidecar. JVM datahike writer per session + Rust host + wasm32-wasip2 CLJS guests. Transit-JSON values inside CBOR envelopes over UDS. | Platform-track agents (you, if you're reading this) |

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
  per session via `sidecar-poc.fs`. RO violations return EACCES.
- **Multi-agent fanout.** N wasm guests per session, all listening on one
  broadcast channel. Phase D N=3 / 300s smoke: 0 out-of-order, 0 errors.
- **Per-session WASI env.** `SIDECAR_SESSION=<name>` plumbed to every guest.

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
through Phase PF (multi-session isolation) — GREEN. The next-blocking item
is porting a real V0 agent turn into a wasm guest with a stubbed LLM (the
WASI preopen for bootstrap caches + the deepseek stub + V0 turn driver).
Tauri integration is out of scope for cutover.

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
`sidecar-poc.datahike` instead of `datahike.api`. **Never** copy a `.cljs`
file from `src/seon/` and edit it in place inside `guest-cljs/src/`; the
overlay system exists precisely so V0 and V2 can diverge cleanly.

**Always namespace your sockets and stores by session.** `default` is a
session like any other. Do not hardcode `/tmp/seon-poc-req.sock`; use the
session-suffixed forms `/tmp/seon-poc-<session>-req.sock`.

## Where things live

| Path | Purpose |
|---|---|
| `README.md` | Phase-by-phase status, run commands, smoke output. Authoritative. |
| `CUTOVER.md` | Cutover checklist V1 → V2. |
| `SESSIONS.md` | Session concept, isolation guarantees, CLI usage. |
| `PROTOCOL.md` | Wire protocol — CBOR envelope + Transit-JSON values. |
| `RECOMMENDATION.md` | Phase D recommendation + V0 → sidecar migration plan. |
| `jvm-writer/` | JVM Clojure writer subprocess. `deps.edn`, `src/seon/sidecar/`, `test/`. |
| `jvm-writer/resources/seed/` | `facts-schema.edn` + `facts-seed.edn` — knowledge base seed. |
| `rust-host/` | Rust host. `src/main.rs` (Session, registry, REPL, smokes), `src/guest.rs` (wasm bridge), `wit/sidecar.wit`. |
| `rust-host/target/` | gitignored build artifacts. |
| `guest-cljs/src/` | Overlay-side support code (`sidecar_poc/{wit,datahike,agent,fs,facts}.cljs`). |
| `guest-cljs/src-overlay/seon/` | **Overlay namespaces** — V0 ns shadows that route through the overlay's WIT/db surface. Add new ports here. |
| `guest-cljs/test/` | CLJS tests for guest code. |
| `guest/guest.mjs` | Legacy JS-only smoke guest (Phase 3). |
| `guest/sidecar-agent.mjs` | ESM shim that imports the WIT host module + the CLJS bundle. |
| `guest/build/` | gitignored wasm-rquickjs wrapper crate for `guest.mjs`. |
| `guest/sidecar-agent-build/` | gitignored wasm-rquickjs wrapper crate for the CLJS agent. |
| `bench/` | Bench reports — `tx-batcher-and-cache-fix-*.md`, `v0-port-survey.md`. |
| `data/` | gitignored. Per-session konserve stores (`data/sessions/<name>/store/`) + scratch mounts. |
| `data/phase-*-*.log` | Smoke run output. |
| `smoke/` | Driver scripts. |
| `build-sidecar-agent` | One-shot script: builds CLJS bundle + wasm component, optional `--run`. |

## Build commands

```bash
# (a) Build the CLJS guest (shadow-cljs + wasm-rquickjs + cargo).
cd /Users/sean/src/seon/pod-host/sidecar-poc
./build-sidecar-agent

# (b) Build the Rust host alone.
cd /Users/sean/src/seon/pod-host/sidecar-poc/rust-host
cargo build --release

# (c) Run the Phase D N=3 multi-agent smoke (300s).
./build-sidecar-agent --run --duration-ms=300000

# (c') Or short iteration (15s).
./build-sidecar-agent --run --duration-ms=15000

# (d) Run JVM-side tests.
cd /Users/sean/src/seon/pod-host/sidecar-poc/jvm-writer
clojure -M:test
# 25 tests / 105 assertions expected green (+ 6 facts tests / 16 assertions).

# (e) Multi-session smoke (Phase PF).
cd /Users/sean/src/seon/pod-host/sidecar-poc/rust-host
cargo run --release -- \
  --guest-wasm ../guest/sidecar-agent-build/target/wasm32-wasip2/release/sidecar_guest.wasm \
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
3. **Build artifacts are gitignored.** `rust-host/target/`, `guest/build/`,
   `guest/sidecar-agent-build/`, `data/` — never commit these. Source of
   truth for build inputs is `shadow-cljs.edn` (root) + `Cargo.toml` +
   `wit/sidecar.wit` + the CLJS sources.
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
   Overlay's `sidecar-poc.wit` handles this; new wrappers must too.
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
