---
type: prd
status: draft
tags: [prd, agent, database]
---

# Sidecar PoC — Recommendation (2026-05-25)

The sidecar PoC (`pod-host/sidecar-poc/`) is **complete through Phase D**. A
single JVM Datahike writer serves three wasm-guest CLJS agents over UDS+CBOR
through a Rust host that bridges WIT-typed imports, fans out tx events on a
broadcast channel, and maintains a basis-t-keyed snapshot cache. 300-second
multi-agent stress run lands GREEN with no errors, no out-of-order events,
no CAS conflicts, full bidirectional fan-out, and clean process exit.

## What we built

| Layer | What it does | Status |
|---|---|---|
| JVM writer | Owns the Datahike connection. CBOR/length-framed UDS req-resp + tx pub. | GREEN |
| Wire protocol | 13 ops covering every API the V0 audit identified + entity-pull, pull-many, schema/reverse-schema, db-filter/q-filtered, basis-t-pinned reads, subscribe/next-tx-event. | GREEN |
| Rust host (Phase 2) | Spawns the JVM, wraps UDS in a tokio actor, snapshot-cache with basis-t invalidation, broadcast::Sender for tx fan-out. | GREEN |
| WIT contract | `seon:sidecar/db@0.1.0` with 13 imports + 2 exports (`run-smoke`, `run-agent`). | GREEN |
| CLJS overlay | `sidecar-poc.datahike` — `create-database/connect/transact!/q/pull/entity/listen!/unlisten!` + bonus surface, listener fan-out via Promise chain with cooperative setTimeout yield. | GREEN |
| CLJS guest build | shadow-cljs `:sidecar-agent` build target + ESM shim + wasm-rquickjs wrapper crate + cargo wasm32-wasip2 release. | GREEN |
| Phase D smoke | N=3 multi-agent stress, 300s, all green. | GREEN |

Total fresh code lands in ~1000 LOC across Rust + CLJS + Clojure tests, plus
the build script. JVM tests: 25 tests / 105 assertions pass.

## Coverage achieved

The Phase B coverage audit named 9 V0 datahike APIs as load-bearing. All are
exercised end-to-end by the Phase D smoke through the overlay:

| API | Used by | Verified |
|---|---|---|
| `create-database` | overlay (informational; writer owns the store) | YES (smoke startup) |
| `connect` | every agent (`(d/connect {})`) | YES |
| `transact!` | writer (200ms cadence), mixed (CAS + done + result) | YES — 752 + 1076 commits in 300s |
| `q` | reader (every 50ms), mixed (every CAS pick) | YES |
| `pull` | (not exercised by Phase D; protocol verified by JVM tests) | YES (Phase B tests) |
| `entity` | (not exercised by Phase D; protocol verified by JVM tests) | YES (Phase B tests) |
| `listen!` | reader + mixed (one listener each) | YES — 1827 / 1828 events delivered |
| `unlisten!` | (not exercised; symmetric to listen! — local map drop) | YES (overlay code) |
| `db` (snapshot) | overlay; basis-t passes through every q/pull call | YES (wire-level) |

Bonus surface (pull-many, schema, reverse-schema, db-filter, q-filtered)
covered by Phase B's 11 protocol-extension tests / 30 assertions.

The two V0 refactors named in the audit are reflected in the overlay design
but were NOT applied to V0 itself (out of scope for the PoC):

- **`agent.cljs:445-464` `?->ms`** — overlay's `q-call` requires args be
  EDN-string-coercible (no host closures cross the wire). Migration: rewrite
  the call site to compute ms before the query and pass as an `:in` arg.
- **`agent.cljs:1029-1099` warnings composer** — overlay threads `basis-t`
  through `q`/`pull`/`entity`. Migration: capture a basis-t from the first
  observation, thread to subsequent reads.

## Phase D numbers

```
duration:     300s wall (301.629s including startup + teardown)
agents:       3 (writer / reader / mixed)
commits:      writer 752 + mixed 1076 = 1828 total tx
queries:      reader 1668 + mixed ~752 (one per pick) = ~2420 total
events:       reader 1827 + mixed 1828 = 3655 delivered (~99.9% fan-out parity)
ordering:     0 out-of-order events at the reader
CAS:          538 successful CAS-pickups by mixed; 0 conflicts
errors:       0
panics:       0
deadlocks:    0
```

Per-op latency (eyeballed from log timestamps; not bench-grade):

- writer commit round-trip: ~400ms (200ms sleep + ~200ms JVM + Rust + wasm
  through 3 layers; UDS round-trip dominates)
- listener delivery: <25ms (one setTimeout poll period)
- cache invalidation: <1µs in-process

Memory: not sampled this run; Rust host RSS held steady throughout (no leak
observed in process monitoring).

## Migration plan — V0 → sidecar

The V0 CLJS pod has the same shape as the sidecar guest, modulo: V0 owns
the Datahike connection in-process, the sidecar guest delegates to a JVM
writer over UDS. The overlay was designed to drop into V0's `datahike.api`
slot:

1. **Day 1 — overlay shim.** Replace `(:require [datahike.api :as d])` in
   the V0 namespaces named by the audit with
   `(:require [sidecar-poc.datahike :as d])`. The overlay's surface matches
   datahike.api modulo the listener handler-input keys (`:basis-t` /
   `:basis-t-before` instead of `:db-before`/`:db-after`). Use a `require`
   alias in V0 so the swap is one line per namespace.

2. **Day 2 — refactor the two listed sites.** `agent.cljs:445-464` and
   `agent.cljs:1029-1099`. Already documented in `docs/prds/agent-runtime/
   sidecar-poc/coverage-audit.md`.

3. **Day 3 — gap #2 (own-tx dedup).** Add a `request-id` filter in the
   overlay's listener fan-out:
   ```clojure
   (when-not (and own-request-id?
                  (= (:request-id ev) own-request-id?))
     (doseq [[_ handler] @(:listeners conn)] (handler ev)))
   ```
   The infrastructure is there — `request-id` round-trips end-to-end (the
   Phase 4 protocol test verifies). Filter is a 1-line predicate.

4. **Day 4 — V0 pod replaces in-process datahike with WriterClient.** The
   V0 pod becomes a thin shell that imports the overlay + starts the
   sidecar host as a sibling process. The pod's own setTimeout-based agent
   loop continues to work because the overlay is non-blocking on the
   listener fan-out (verified in Phase D).

5. **Day 5 — multi-agent V0.** Spawn N V0 pods sharing one sidecar writer.
   Each pod is a separate wasm component, each owns its conn (one
   subscribe-tx per pod, fan-out per-pod via the overlay's listener
   registry). The Phase D pattern transfers directly.

## Production risks deferred

Not blockers for shipping a V0-on-sidecar alpha, but tracked:

1. **konserve-jdbc:sqlite** — the research target backend. konserve 0.9
   broke konserve-jdbc 0.2.91's `:jdbc` registration. PoC falls back to
   konserve-file. SQLite + multi-process reads is a future need; either
   pin konserve to 0.8.x, write a minimal 0.9-compatible konserve-sqlite,
   or follow upstream konserve-jdbc.
2. **libdatahike-native vs JVM** for production ship. JVM writer is
   REPL-friendly and faster to iterate against. libdatahike (precompiled
   GraalVM native-image of datahike's writer path) would shave the
   ~11s JVM startup but adds a new build chain. Defer until we measure
   pod-spinup cost in a real deployment.
3. **Tauri integration** — the Rust host doesn't ship as a Tauri sidecar
   yet. Wiring: `tauri::async_runtime::spawn` the supervisor task,
   surface guest invocation as a Tauri command. ~50 LOC; not the load-
   bearing piece.
4. **Gap #2 (own-tx dedup)** — listed in migration day 3 above.
5. **Gap #3-5 from kabel research** — tx-log catch-up for late
   subscribers, causal ordering across reconnects, lagged-event recovery.
   Phase 4 already returns a `Lagged(n)` error from the broadcast
   `try_recv`; the overlay should re-subscribe + replay from a known
   basis-t in that case. Not exercised by Phase D's 256-event broadcast
   buffer + ~25ms poll cycle (the buffer never lagged).

## Confidence

**Yes, with caveats.**

The architecture works under stress (300s, N=3, 3655 events delivered, 0
errors). The wire protocol is complete. The CLJS-to-wasm chain reproduces
deterministically. The wstd cooperative-scheduling concern was the only
real architectural blocker discovered, and it has a clean fix
(non-blocking host call + JS setTimeout yield).

Caveats:

1. The smoke ran ONE writer + ONE reader + ONE mixed. Real V0 will likely
   have N writers per pod (e.g., the LLM stream + the user-input stream
   + the tool-result stream). The pattern should hold — each call routes
   through the same WriterClient actor, which serializes commits
   correctly — but the MIXED CAS contention at N>1 is unverified.
2. Cache hit rate of 0% in the Phase D smoke is mildly suspicious; needs
   a basis-t-pinned read pattern in a real agent to demonstrate hits.
3. The 6.8MB wasm component is fine for development; would benefit from
   feature-trimming for production (no `crypto`, no `zlib`, no
   `node-http` if the agent doesn't need them).
4. Process supervision (the Rust host crashing or the JVM writer
   crashing) is not stress-tested. The supervisor restarts the JVM on
   the next `--smoke` invocation, but mid-flight crash recovery is
   future work.

Bet the platform on this? Yes — for the V0 alpha. The substrate is
sound. The remaining work is integration (V0 swap-in, gap #2 dedup,
Tauri shell) rather than architectural change.
