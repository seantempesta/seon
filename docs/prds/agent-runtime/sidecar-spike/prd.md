---
type: prd
status: draft
tags: [prd, agent, database]
---

# Sidecar Datahike Spike + Architecture Q&A

Spike PRD for validating the **hybrid (a+b)** architecture — libdatahike compiled via `bb ni-compile` to a host-CPU GraalVM native shared library, addressed by a Rust sidecar process, consumed by a wasm-rquickjs CLJS guest via WIT imports through the Tauri Rust host. The architecture and tradeoffs are derived in [datahike-wasm-writer-split-2026-05-24.md](../research/datahike-wasm-writer-split-2026-05-24.md); this document answers the user's three crisp follow-up questions, then specifies a tight ½-day spike to falsify the architecture before committing.

---

## 1. User questions answered

### Q1 — What does the hybrid sidecar buy us over the alternatives?

Compared to:

| Alternative | What it gives up |
|---|---|
| **Pure (a) — libdatahike embedded in Tauri host** | SIGSEGV/SIGBUS cohabitation between wasmtime and GraalVM Substrate VM in the same process. Both runtimes install signal handlers — whichever loads second wins, the other crashes. Workaround (`Config::signals_based_traps(false)`) costs every WASM memory access an explicit bounds check. ([research §2.1.2, §6.3 signal-handling deep dive](../research/datahike-wasm-writer-split-2026-05-24.md#212-graalvm-isolate--wasmtime-in-the-same-process-key-open-risk)) |
| **A' — pure CLJS datahike in each wasm guest** | No central writer → no multi-instance story. Every guest holds ~85 MB RSS of datahike-cljs + indices. Query is QuickJS-interpreted, 10-100× slower than native AOT. ([research §2.3](../research/datahike-wasm-writer-split-2026-05-24.md#23-option-a--pure-cljs-datahike-inside-each-wasm-rquickjs-guest)) |
| **Current V0 — pure CLJS datahike in Node pod** | Same per-instance cost as A', no isolation, no path to multiple cooperating agents over one store. The status quo we're explicitly trying to escape. |

The hybrid uniquely gives:

1. **Native-AOT query/transact performance** — libdatahike is replikativ's production-shipped GraalVM native-image build with the full 20-function C-ABI ([research §1.3](../research/datahike-wasm-writer-split-2026-05-24.md#13-upstream-libdatahike--pydatahike--new-signal-production-shipped)). pydatahike runs this in production today.
2. **Crash and signal isolation** — wasmtime runs in the Tauri host process; the GraalVM isolate runs in a separate sidecar process. Neither runtime can stomp the other's signal handlers; a sidecar segfault doesn't kill the agent loop, the host can respawn it.
3. **Amortized GraalVM RSS** — the ~25-35 MB sidecar footprint is paid once, regardless of how many wasm agent instances are running. Every wasm guest stays small (the ~85 MB datahike-cljs heap moves out of QuickJS entirely).
4. **Single writer with multi-reader concurrency** — datahike is single-writer by design ([writer.cljc:41](../../../../reference-code/datahike/src/datahike/writer.cljc), [research §2.1.1](../research/datahike-wasm-writer-split-2026-05-24.md#211-writer-architecture-matches-our-needs-out-of-the-box)). One sidecar owns all writes; any number of wasm guests read.
5. **Central auth/perms choke-point** — capability checks, rate limits, audit log all live in one place (the sidecar) rather than being replicated per-guest.
6. **Tauri-native deployment** — Tauri has built-in sidecar packaging and lifecycle management; the sidecar is just another bundled binary, no JRE prerequisite, no separate installer.
7. **Drop-in replaceability** — the WIT interface is the contract. The sidecar implementation can later become a Rust-native reader (research §4.1) without changing any guest code.

### Q2 — Can the wasm guest read directly from the same SQLite, or does it have to talk to the sidecar?

**Short answer: the user's "shared SQLite, zero IPC for reads" intuition is technically possible but architecturally regressive — it collapses back into A' wearing a hat. Recommend route (ii): all reads via IPC to the sidecar.**

The two sub-paths spelled out:

**(i) Direct read — guest opens `data/seon.sqlite` in `SQLITE_OPEN_READONLY`.**

What this actually requires: SQLite alone is not the database. konserve-jdbc:sqlite stores serialized blobs (Fressian/Transit-encoded hitchhiker-tree nodes) under content-addressed keys ([research §3.1 write-path walkthrough](../research/datahike-wasm-writer-split-2026-05-24.md#31-option-c--rewrite-the-transactor-in-rust); [writing.cljc:25, :301](../../../../reference-code/datahike/src/datahike/writing.cljc); [connector.cljc:75](../../../../reference-code/datahike/src/datahike/connector.cljc)). A reader opening the raw SQLite store has to:

- Speak the konserve PEDNKeyValueStore protocol against `node:sqlite` (write a CLJS konserve-sqlite shim — does not exist today).
- Decode Fressian / hasch-content-addressed blobs.
- Walk the hitchhiker-tree (persistent-sorted-set 0.4.122 on-disk variant — undocumented as a wire format).
- Run Datalog queries against the materialized index — i.e., **ship most of datahike's read engine in the guest**.

That **is** datahike-cljs. Path (i) is "A' + shared store" — you've recreated all the per-guest RSS and the persistent-sorted-set patches (`btset/from-opts` `:cmp` vs `:comparator`; 3-arg `lookup` not-found semantics — [research §1.2](../research/datahike-wasm-writer-split-2026-05-24.md#12-libdatahike-cljs-spike-datahike-compiled-to-cljs--bench-green-on-3-backends)) you were trying to leave behind, plus a brand-new cache-invalidation problem ([Gemini-A "Cache Invalidation Problem", research §2.2](../research/datahike-wasm-writer-split-2026-05-24.md#22-option-b--dedicated-writer-process-sidecar)). The performance win you imagined (zero IPC) is offset by carrying a 50-80 MB read engine in every QuickJS heap and re-implementing snapshot caching anyway.

**Caveat I cannot fully verify without a spike:** wasm-rquickjs lists `node:sqlite` as an opt-in feature; I have not confirmed it exposes `SQLITE_OPEN_READONLY` and WAL-reader semantics correctly. If someone wants to chase (i) seriously, that capability check is a separate ~½-day investigation. **Not recommended.**

**(ii) Routed read — every query goes guest → WIT host → UDS → sidecar.**

This is Gemini-B's recommended shape ([research §4, §6.3](../research/datahike-wasm-writer-split-2026-05-24.md#4-composing-a--b--the-hybrid-both-geminis-converge-on)). UDS round-trip is 50-100 µs. For a reactive-context render of ~50 queries: ~5 ms IPC overhead. Gemini-B's mitigation: **Immutable Snapshot Caching in the CLJS guest, keyed by `[db-basis-t, query-expression, query-args]`.** Datomic-style "database as value" makes this sound — same basis-t means identical query results forever. Cache hits cost 0 ms. Only basis-t advances (after a transaction) require fresh IPC.

The guest stays tiny (just a cache map + a thin `d/q` shim that calls the WIT import). The sidecar owns the entire read engine. Sub-millisecond perceived latency on warm queries.

**Verdict:** route (ii) is the architecture. Path (i) is a trap.

### Q3 — How does the wasm guest communicate with the sidecar?

Mechanics, top to bottom:

**Process lifecycle.** Tauri ships a built-in sidecar pattern (`tauri.conf.json` → `tauri > bundle > externalBin`; Rust-side `tauri::api::process::Command::new_sidecar(...).spawn()`). Tauri handles spawn, path-mangling for code-signed bundles, stdin/stdout piping, and kill-on-exit. The host owns the sidecar's lifetime; the guest doesn't see it.

**Transport.** Unix Domain Socket on macOS/Linux, Named Pipe on Windows. Both give ~50-100 µs local round-trip (no kernel network stack, no TLS, no localhost loopback overhead). TCP-loopback would add 10-30 µs and require port management — skip. Use `tokio::net::UnixStream` Rust-side.

**Wire format.** CBOR, length-prefixed frames. Rationale:

- libdatahike's C-ABI already supports `"cbor"` as both input and output format ([research §1.3 C-ABI table](../research/datahike-wasm-writer-split-2026-05-24.md#13-upstream-libdatahike--pydatahike--new-signal-production-shipped); [LibDatahikeBase.java](../../../../reference-code/datahike/libdatahike/src/datahike/impl/LibDatahikeBase.java)). pydatahike defaults to CBOR. No new codec work in the sidecar.
- CBOR preserves Clojure data shapes (maps, vectors, sets, keywords-as-tagged-strings) better than JSON.
- Binary — 2-5× smaller than EDN/JSON for typical query results, faster to encode/decode.
- Length-prefix (u32 BE + payload) is the simplest framing that works with `tokio` `AsyncReadExt::read_exact`.

**Message types.**

```
Query:    { op: "q",        query: <edn-str>, inputs: [...], basis: <"db"|"as-of:<t>"|...> }
Transact: { op: "transact", tx-data: <...>, tx-meta: {...} }
Pull:     { op: "pull",     selector: <edn>, eid: <...>, basis: <...> }
TxEvent:  { op: "tx",       basis-t: <int>, commit-id: <uuid> }   // sidecar→host push
```

The first three are request/response (synchronous from the guest's POV — the guest awaits an `^:async/await` Promise that resolves when CBOR comes back). `TxEvent` is a sidecar-initiated push on a separate stream, **not** a reply to a request. The host fans it out as a WIT event so the guest's snapshot cache can invalidate any entries older than the new basis-t.

**WIT capability surface.** Add `datahike` interface to `pod-host/wasm-tauri/src-wit/seon-pod.wit`:

```wit
interface datahike {
  // Synchronous reads/writes — guest awaits the host import.
  q: func(query: string, inputs: list<bytes>, basis: string) -> result<bytes, db-error>;
  transact: func(tx-data: bytes, tx-meta: bytes) -> result<tx-report, db-error>;
  pull: func(selector: string, eid: bytes, basis: string) -> result<bytes, db-error>;

  // Tx event stream — async push for snapshot-cache invalidation.
  // Modeled as a poll-fn the guest calls from a background loop (wasi:io stream).
  next-tx-event: func() -> result<tx-event, db-error>;

  record tx-report  { basis-t: u64, commit-id: string, tempids: bytes }
  record tx-event   { basis-t: u64, commit-id: string }
  variant db-error  { unavailable, query-error(string), tx-error(string), serialization(string) }
}
```

`bytes` carries CBOR payloads. Query/input/selector strings remain EDN for readability and to match libdatahike's existing `query_edn` / `selector_edn` arg types.

Host implementation: each WIT import call funnels into a `tokio::sync::mpsc` queue feeding a single connection task that owns the UnixStream — keeps libdatahike's single-writer invariant clean and serializes IPC trivially.

### Q4 — Perf gains, honestly?

Stitched from research §2.1.3 (in-process latency budget), §6.3 (Gemini-B's IPC budget), and §2.3 (status-quo claims):

| Path | Steps | Latency |
|---|---|---|
| **Warm read (snapshot cache hit)** | Guest checks `[basis-t, query, args]` → returns cached result | **~0-10 µs** (in-guest map lookup) |
| **Cold read (basis-t miss or first query)** | CLJS EDN-encode → WIT → host → UDS send → sidecar libdatahike `q` → CBOR encode → UDS reply → host → WIT → guest CBOR decode | **~200 µs - 1.5 ms** (UDS 50-100 µs + libdatahike `q` 100-500 µs hot index + ~150 µs encode/decode/WIT overhead) |
| **Write (transact)** | Guest encode → WIT → UDS → sidecar enqueue on writer thread → `commit!` → flush hitchhiker tree → konserve write → reply | **~1-10 ms** (dominated by flush + storage write; backend-dependent) |
| **Baseline today — pure CLJS in Node** | datahike-cljs interpreted query in V8 | **~2-20 ms** per query depending on size (no spike measurement, extrapolated from CLJS-2.5 bench in [research §1.2](../research/datahike-wasm-writer-split-2026-05-24.md#12-libdatahike-cljs-spike-datahike-compiled-to-cljs--bench-green-on-3-backends)) |
| **Pure (a) embedded read** | Same as cold read minus UDS hops | **~150 µs - 1 ms** (saves 50-100 µs vs hybrid; loses signal-handler isolation) |

**Headroom analysis.** For a typical reactive-context render (50 queries):

- **Status quo:** 100 ms - 1 s. Visibly laggy on rich agent views.
- **Hybrid, all cold:** 50 × 1 ms = ~50 ms. Acceptable, at the edge of the 50 ms render budget.
- **Hybrid, snapshot cache hit:** ~0 ms. Basis-t only advances on writes — between transactions, all 50 queries are free.
- **Hybrid, batched cold:** Gemini-B's batching optimization sends the 50 queries as one IPC message: 1 × 100 µs + 50 × ~300 µs query exec = ~15 ms.

**Where the win actually comes from**, ranked by impact:

1. **Snapshot cache hits** — eliminates 95%+ of read IPC in a reactive UI. Load-bearing.
2. **Native-AOT query engine** — 10-100× faster than CLJS-in-QuickJS on cold queries. Always-on win.
3. **Avoiding QuickJS interpretation overhead** — every datahike-cljs op currently runs through QuickJS interpretation; libdatahike runs as host ARM64. ~10× per-op.
4. **UDS round-trip vs in-process** — a 50-100 µs tax we accept to keep wasmtime + GraalVM signal handlers separated. Snapshot cache + batching reduce this to noise.

**Honest caveats.** All numbers above are estimates from research synthesis — none are measured on this hardware against this combination. Phases 3.2 and 3.4 of the spike below validate the cold-read and snapshot-cache numbers respectively. If they come in significantly worse, this PRD is wrong and we revisit.

---

## 2. Spike goal + decision gate

**Goal:** in ½ day, prove the hybrid sidecar architecture works end-to-end on Apple Silicon — libdatahike builds, the C-ABI returns sensible CBOR from Rust, and a wasmtime guest can issue one query through a WIT import. Falsifies the architecture cheaply before committing weeks to the full implementation.

**Decision gate at end:** if phases 3.1-3.3 pass green, write the full architecture PRD (`docs/prds/agent-runtime/datahike-native-writer.md`) and schedule build-out. If phase 3.1 fails (libdatahike won't build on Apple Silicon M-series), fall back per §6 — either to A' (CLJS-in-wasm with the existing libdatahike-cljs spike as foundation) or to option B (JVM Clojure transactor as the sidecar). If phase 3.2 or 3.3 fails, diagnose and report; don't try to patch upstream native-image config.

---

## 3. Phases

Each phase is a STOP/GO gate. Do not start phase N+1 unless N is green. Each phase produces ONE concrete artifact and ONE measurement.

### 3.1 Build libdatahike on Apple Silicon

**Action.** `cd reference-code/datahike && bb ni-compile`. This invokes the upstream task chain `codegen-native → prep → ni-ccompile → ni-uber → ni-compile` ([bb.edn lines 87-195](../../../../reference-code/datahike/bb.edn)).

**Prereq.** GraalVM with `native-image` on PATH. User reports having `pydatahike` working locally — that already requires a working libdatahike build, so this may be a no-op verification. Run `bb ni-check` first to confirm.

**Success criteria.**

- `libdatahike/libdatahike.dylib` (or `.so`) exists and is > 30 MB.
- `libdatahike/libdatahike.h` exists with the 20 `@CEntryPoint` declarations.
- The upstream C++ smoke harness passes: `cd libdatahike && ./compile-cpp && ./test_cpp` — creates a DB, transacts, queries, prints results.
- Cross-check: if `pydatahike` already loads against an existing `libdatahike.dylib`, run `pytest` in `pydatahike/` to confirm the fresh build is interchangeable.

**Stop criteria.** If `bb ni-compile` fails with native-image errors, **stop**. Do not attempt to patch upstream's native-image config. Report the failure mode and jump to §6 fallback.

**Time budget.** 1-2 hours, mostly wall-clock waiting on native-image (~5-10 min build). If user already has `pydatahike` working, this collapses to ~15 min verification.

### 3.2 Sidecar FFI smoke test (in-process first)

**Action.** New Rust crate at `pod-host/sidecar-spike/`. Two binaries:

1. **`ffi-smoke`** — in-process FFI test. Loads `libdatahike.dylib` via `libloading`, calls `graal_create_isolate`, invokes `create_database` + `transact` + `q` with hard-coded EDN inputs and CBOR output, prints the result. **No UDS, no async, no tokio.** Purely confirms the FFI path works from Rust before adding any layers.
2. **`uds-smoke`** — only after `ffi-smoke` is green. Spawns a tiny wrapper binary (or uses `ffi-smoke` as the wrapped subprocess via a stdin/stdout protocol if simpler) and round-trips one query over `tokio::net::UnixStream` with length-prefixed CBOR.

Why split this way: libdatahike is a `.dylib` (in-process FFI), not a CLI binary. The cleanest path is "prove FFI works → wrap with UDS → measure", not "build the UDS server and debug both layers at once."

**Success criteria (ffi-smoke).**

- Calling `graal_create_isolate` from Rust returns 0 and a valid `graal_isolatethread_t*`.
- `create_database` + `transact` + `q` round-trip CBOR successfully, result matches the upstream C++ smoke.
- Single-query latency (after warmup): **< 1 ms** for a trivial query on a 10-datom in-memory DB. Print measured µs.

**Success criteria (uds-smoke).**

- One query round-trips through UnixStream + CBOR framing.
- Round-trip latency: **< 1 ms** on a 10-datom DB (sidecar process already warm). Print measured µs.
- Gemini-B claims 50-100 µs UDS round-trip + ~200-500 µs `q` exec. Anything over 2 ms is a red flag — investigate before continuing.

**Stop criteria.** If FFI smoke segfaults or hangs, **stop**. Likely culprits: missing isolate-thread pinning (see [research §2.1.4 callback bridging](../research/datahike-wasm-writer-split-2026-05-24.md#214-async-rust--callback-c-bridge-pattern); use single-thread dispatch even in the smoke, don't trust `tokio::spawn_blocking`), missing CBOR codec, libdatahike-h binding mismatch.

**Time budget.** 2-3 hours. `ffi-smoke` should take ~1 hour; `uds-smoke` ~1-2 hours.

### 3.3 wasmtime guest call via WIT

**Action.** Add a minimal `datahike` interface to `pod-host/wasm-tauri/src-wit/seon-pod.wit` — single `q` function only, no transact, no events. Implement the host import in Rust by calling into the FFI path from §3.2 (skip UDS for the spike — direct FFI in-process is fine here since we're not yet exercising the full sidecar). Build a trivial CLJS guest that calls the import and returns the CBOR result.

Run via `wasmtime` CLI directly (no Tauri).

**Success criteria.**

- WIT interface compiles via `wit-bindgen`.
- CLJS guest, compiled to wasm-rquickjs, calls `datahike/q` with a literal EDN query string.
- Result returns to the guest as a `bytes` (CBOR).
- Guest CBOR-decodes and prints the result.
- End-to-end roundtrip: **< 2 ms** (allows margin for QuickJS interpretation overhead).

**Stop criteria.** If the host-side FFI works (§3.2) but the WIT bridge fails, the issue is binding-generation or wit-bindgen version mismatch — diagnose and fix or report. If the FFI segfaults when invoked from inside the wasmtime process (signal-handler cohabitation — [research §2.1.2](../research/datahike-wasm-writer-split-2026-05-24.md#212-graalvm-isolate--wasmtime-in-the-same-process-key-open-risk)), **this is the load-bearing finding** — it confirms pure (a) is dead, **and validates the sidecar shape from §3.2 as mandatory**. The hybrid then becomes: WIT-host → UDS → out-of-process libdatahike. Update the architecture PRD accordingly and continue.

**Time budget.** 2 hours.

### 3.4 STRETCH — snapshot cache prototype

**Action.** If 3.1-3.3 land green within ~5 hours total, prototype Gemini-B's ImmutableSnapshotCache pattern as a CLJS atom keyed by `[basis-t, query-edn-str, args-hash]`. Populate on miss, return on hit. Issue 1 transact + 100 queries (same query, no basis-t advance between them) and measure.

**Success criteria.**

- First query: full cold-read latency (~1 ms).
- Subsequent 99 queries: cache hits, **< 10 µs each** (in-guest map lookup, no IPC).
- Total render-equivalent time for 100 queries: **< 5 ms** (vs ~100 ms uncached).

**Stop criteria.** Skip the phase if running over budget. The cache is conceptually trivial; the only thing the spike adds is empirical confirmation of the 0 ms hit cost.

**Time budget.** 1-2 hours. Skippable.

---

## 4. Success criteria per phase

Aggregated summary for the decision-gate review:

| Phase | Green | Yellow | Red (stop, fall back) |
|---|---|---|---|
| 3.1 build | `.dylib` builds, C++ smoke passes, pydatahike CI green against fresh build | builds but C++ smoke trips an edge case | `bb ni-compile` errors out with substitution/reachability errors |
| 3.2 FFI | < 1 ms in-process query, UDS adds < 1 ms | 1-2 ms, but stable | segfault, hang, or > 5 ms latency |
| 3.3 WIT | guest gets result back, < 2 ms total | works but slow (3-5 ms) | wasmtime + libdatahike cohabitation segfault — confirms hybrid sidecar shape is mandatory (still GREEN, just changes the architecture write-up) |
| 3.4 cache | < 10 µs cache hits | hits work, latency noisy | not a hard fail — skippable |

---

## 5. Non-goals

Explicitly out of scope for this spike. Each is a separate piece of work, deliberately deferred to keep the spike to ½ day:

- **No write path validation.** Phase 3.2's FFI test transacts to confirm the C-ABI works, but we are not validating write-path correctness, durability, concurrency, or `commit!` semantics.
- **No konserve-jdbc:sqlite.** Phase 3.1-3.3 use `konserve.memory` or `konserve.fs` (whatever the upstream `test_cpp.cpp` uses). SQLite-WAL behavior is a separate spike — see [research §2.2 concurrency model](../research/datahike-wasm-writer-split-2026-05-24.md#22-option-b--dedicated-writer-process-sidecar).
- **No tx-event notification channel.** The WIT `next-tx-event` function in §1.Q3 is part of the full architecture, not the spike. Snapshot cache prototype (3.4) uses a manually-bumped basis-t.
- **No Tauri integration.** Run wasmtime CLI directly. Tauri sidecar packaging is straightforward once the wasmtime-side bindings are proven.
- **No Windows/Linux validation.** Apple Silicon only. Cross-platform is a follow-on.
- **No production sidecar wrapper.** Phase 3.2's `uds-smoke` is throwaway code, not a sidecar to keep.
- **No CLJS API design.** The guest in 3.3-3.4 calls the WIT import directly. The full `seon.db` (cljs) API surface, instrumentation, and Malli-validation wiring is a downstream design task.

---

## 6. Fallback plan if spike fails

Decision tree, keyed off which phase fails:

**Phase 3.1 fails — libdatahike won't build on Apple Silicon.**

Two branches:

- **Fallback A' (smallest delta).** Continue with pure CLJS datahike-cljs in wasm-rquickjs, building on the libdatahike-cljs spike commits `ee7055b` / `16b9a40` / `815ad2a` ([research §1.2](../research/datahike-wasm-writer-split-2026-05-24.md#12-libdatahike-cljs-spike-datahike-compiled-to-cljs--bench-green-on-3-backends)). Open empirical question 6 in research becomes the next spike: does the V0 pod's datahike-cljs build under wasm-rquickjs? Accept ~85 MB RSS per guest and slower queries; no multi-instance story.
- **Fallback option B (medium-effort).** Sidecar but with a JVM Clojure daemon instead of GraalVM-native. Pays JRE prerequisite + 150 MB RSS + 2-5 s cold start. Worse UX than the hybrid but proven architecture (Gemini-A's 2-week plan, [research §6.2](../research/datahike-wasm-writer-split-2026-05-24.md#62-gemini-a-verbatim--agent-1s-call)). Pick this if multi-agent / multi-window matters more than desktop polish.

**Decision criteria:** if local pod use only → A'. If multiple agents need to cooperate on one store → option B.

**Phase 3.2 fails — FFI works in C++ but not from Rust.**

Most likely cause: incorrect callback ABI, missing isolate-thread pinning, or wrong CBOR codec. Diagnose against pydatahike's `_native.py` (working reference, [research §1.3](../research/datahike-wasm-writer-split-2026-05-24.md#13-upstream-libdatahike--pydatahike--new-signal-production-shipped)) — port its lazy-init + callback pattern verbatim. Not an architecture failure; a binding bug.

**Phase 3.3 fails — wasmtime + libdatahike signal-handler cohabitation crash.**

**This is actually the predicted-and-prepared-for case.** It confirms Gemini-A and Gemini-B's prior recommendation — pure (a) is dead, the sidecar shape is mandatory. The spike then pivots from "validate (a+b) hybrid in 4 hours" to "build the sidecar shape in 4 hours" — most of the work (FFI from Rust, WIT bridge) is the same; only the host-side wiring changes from "FFI in-process" to "UDS to subprocess." Still GREEN for the overall decision.

**Phase 3.4 fails — snapshot cache doesn't deliver 0 ms hits.**

Bug in cache key construction or guest-side cache impl. Not architectural.

---

## 7. Time budget + total LOE

½ day = ~5 hours of focused work. Realistic budget:

| Phase | Best case | Likely | Worst (still in budget) |
|---|---|---|---|
| 3.1 build | 15 min (if pydatahike already works locally) | 1 h | 2 h |
| 3.2 FFI + UDS | 1.5 h | 2.5 h | 3 h |
| 3.3 WIT bridge | 1 h | 2 h | 2.5 h |
| 3.4 cache | 1 h | 1.5 h | skip |
| **Total** | **~3.75 h** | **~7 h** | **~7.5 h + skip 3.4** |

Honest assessment: the ½ day estimate is tight. Likely-case is ~7 hours including 3.4, so plan for one focused day. If a phase blows budget, stop and reassess — do NOT push through phase 3.3 if 3.2 already over-ran.

Single biggest unknown: native-image build time on Apple Silicon. Upstream reports ~5-10 min; first build may be slower as it caches.

---

## 8. References

**Primary research synthesis:**

- [docs/prds/agent-runtime/research/datahike-wasm-writer-split-2026-05-24.md](../research/datahike-wasm-writer-split-2026-05-24.md) — full architectural derivation, Gemini-A + Gemini-B consultations, prior-art ledger, 20-function C-ABI table.
  - §1.3 libdatahike + pydatahike anatomy
  - §2.1.2 wasmtime + GraalVM signal-handler cohabitation risk
  - §2.1.3 per-call latency budget
  - §2.1.4 async-Rust ↔ callback-C bridge pattern
  - §4 (a+b) hybrid composition
  - §6.3 Gemini-B verbatim — snapshot caching pattern

**Upstream libdatahike sources:**

- [reference-code/datahike/libdatahike/src/datahike/impl/LibDatahike.java](../../../../reference-code/datahike/libdatahike/src/datahike/impl/LibDatahike.java) — 20 `@CEntryPoint` functions
- [reference-code/datahike/libdatahike/src/datahike/impl/LibDatahikeBase.java](../../../../reference-code/datahike/libdatahike/src/datahike/impl/LibDatahikeBase.java) — codecs, isolate management, callback ABI
- [reference-code/datahike/libdatahike/src/test_cpp.cpp](../../../../reference-code/datahike/libdatahike/src/test_cpp.cpp) — C++ smoke harness (reference implementation)
- [reference-code/datahike/libdatahike/compile-cpp](../../../../reference-code/datahike/libdatahike/compile-cpp) — build script for the smoke harness
- [reference-code/datahike/bb.edn](../../../../reference-code/datahike/bb.edn) lines 87-195 — `bb ni-compile` task chain
- [reference-code/datahike/pydatahike/src/datahike/_native.py](../../../../reference-code/datahike/pydatahike/src/datahike/_native.py) — working Python FFI reference

**Prior spike commits (CLJS path, fallback A'):**

- `ee7055b` — CLJS-1, `:memory` backend green
- `16b9a40` — CLJS-2a/2b, `konserve.fs` + `fake-indexeddb` green
- `815ad2a` — CLJS-2.5, full bench on three backends

**Prior wasmtime spike:**

- [docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md](../research/wasm-spike-2026-05-20.md)
- `pod-host/wasm-tauri/` — existing wasmtime workspace, WIT additions land here
- `pod-host/wasm-tauri/src-wit/seon-pod.wit` — extend with `datahike` interface in phase 3.3

**Gemini-A verbatim:** [research §6.2](../research/datahike-wasm-writer-split-2026-05-24.md#62-gemini-a-verbatim--agent-1s-call)
**Gemini-B verbatim:** [research §6.3](../research/datahike-wasm-writer-split-2026-05-24.md#63-gemini-b-verbatim--sibling-agents-follow-up)
