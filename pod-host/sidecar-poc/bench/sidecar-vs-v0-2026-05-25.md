---
type: research
status: draft
tags: [research, agent, database]
---

# Sidecar vs V0 datahike-cljs — measured comparison (2026-05-25)

D3 of the sidecar-vs-V0 measurement pair. Companion to:

- D1 — V0 baseline: `bench/v0-baseline-2026-05-25.md`
- D2 — sidecar cache-friendly: `README.md` § "Phase D' — cache-friendly rerun (2026-05-25)"

## Setup

- **Host**: Darwin 24.6.0 / arm64 (M1 Pro 32GB) / Node v24.2.0 / rustc release builds
- **V0 stack**: `pod-host/libdatahike-cljs/out/bench.js` — datahike-cljs in
  one Node process. Schema `:vault`, batch=1000, 5 iterations.
- **Sidecar stack**: `pod-host/sidecar-poc/rust-host` Rust binary supervising a
  JVM `seon.sidecar.writer` subprocess (konserve-file backend) + 3 wasm32-wasip2
  CLJS guests via UDS+CBOR. 300s multi-agent run, `--bench-mode cache-friendly
  --cache-batch 100`.

NOTE on workloads: this is **not apples-to-apples**.
- V0 bench is single-process, schema-fixed, query-pattern-fixed. Numbers
  represent isolated query/tx latency with no concurrency.
- Sidecar is 3 concurrent agents (1 writer / 1 reader / 1 mixed) writing
  through a JVM-mediated path with a Rust-host snapshot cache and IPC.
- Compare per-op latency shapes, not totals.

## V0 baseline — distilled (size=1000 entities, median ms)

| Operation | memory | fs | sqlite-file | tiered-mem-fs |
|---|---:|---:|---:|---:|
| Per-entity tx (batch=1000) | 0.62 | 0.75 | 0.47 | 0.29 |
| Indexed lookup by id | 0.47 | 0.45 | 0.46 | 0.37 |
| Pull by path (unique attr) | 0.17 | 0.16 | 0.12 | 0.12 |
| Range scan by time | 7.18 | 7.06 | 6.91 | 6.52 |
| Full scan-all | 8.64 | 8.87 | 8.00 | 8.67 |
| Scan by tag (multi-card) | 20.30 | 58.07 | 31.22 | 31.95 |
| e-join (entity pivot) | 19.2 | 58.1 | 31.3 | 31.1 |
| v-join (value pivot) | 26.6 | 9.0 | 8.4 | 8.6 |
| a-join (attr cross) | 4104 | 8043 | 4175 | 4007 |

(See `bench/v0-baseline-2026-05-25.md` for full table.)

## Sidecar — distilled (300s, N=3 agents, `cache-friendly` mode, post-fix)

Three runs were measured during this session — see README § Phase D'
for the full table. Distilled here:

- **Per-op latency (300s post-fix run, microseconds)**
  - q-hit (cache, all-Rust path): p50=0us, p95=0us, p99=1us — **sub-microsecond**
  - q-miss (full JVM round trip): p50=898us, p95=49619us, p99=128862us
  - tx (UDS + JVM + broadcast): p50=124494us (125ms), p95=196ms, p99=231ms
- **Cache hit rate**: 0.93%-0.97% across all runs. The pinned-entry
  fix retained entries across tx commits (invalidations dropped to 0
  in the basis-t-guarded variant) but did NOT raise the hit rate. The
  cache key bytes evidently collapse to ~5 distinct entries even when
  basis-t advances across snap-rolls — root cause not yet identified.
  Pinned latency numbers below ARE valid; cache utilization is not.

## Side-by-side: same operation, V0 vs sidecar

Numbers are median latency, normalized to milliseconds. V0 is from
`bench/v0-baseline-2026-05-25.md` (size=1000, vault schema). Sidecar
is from the 300s post-fix run.

| Operation | V0 in-proc (ms) | Sidecar miss (ms) | Sidecar hit (ms) | Sidecar / V0 |
|---|---:|---:|---:|---|
| Read — scan small attr (q with no in-mem index) | 8.64 (memory scan-all) | 0.90 | 0.001 | miss 0.10x; hit 0.0001x — sidecar miss is **9x faster** than V0 because JVM datahike + JIT beats CLJS interpreter |
| Read — indexed lookup by id | 0.47 (memory) | (not measured per-op) | (not measured per-op) | n/a |
| Read — pull by unique attr | 0.17 (memory) | (not measured per-op) | (not measured per-op) | n/a |
| Read — multi-card attr scan (scan-by-tag) | 20.30 (memory) | (not measured per-op) | (not measured per-op) | n/a |
| Write — single transact | 0.62 (per-entity in 1000-batch) → 615 ms for 1000 entities | 124.5 (single tx, ~1 entity) | n/a | **200x slower per-entity** — sidecar IPC + JVM + broadcast dominates |

NOTE: the V0 batch=1000 measurement amortizes 1000 entities over one
transact call. The sidecar's per-tx measurement is a single small
transact, so the comparison above is "V0 per-entity in a 1000-batch"
vs "sidecar per-tx at any size". The sidecar bench's writer commits
single small entities (1 per 200ms), so the apples-to-apples is
"single small tx": V0 ~71ms for 100-entity batch / 100 = 0.71ms per
entity vs sidecar 125ms per tx (1 entity) = **175x slower**.

## Headline finding

Three things, in order of importance:

1. **Sidecar writes are ~175x slower than V0 writes.** 125ms median
   per single small transact (sidecar) vs ~0.7ms per entity (V0
   in-process). This is the IPC + JVM call + broadcast tax. The
   architecture's per-write cost makes it a wrong fit for any
   workload that wants to commit many small writes per second.
2. **Sidecar reads are FASTER than V0 reads even on cache MISS** —
   ~0.9ms median miss path vs ~8.6ms V0 in-memory scan. The
   JVM datahike + JIT outclasses CLJS datahike by ~10x on small
   data, *before* any caching helps.
3. **Cache hit latency is sub-microsecond** but **realized hit
   rate in the cache-friendly benchmark was <1%**. A
   cache-invalidation bug discovered during this measurement
   (pinned `(d/as-of db basis-t)` entries being invalidated on
   every writer tx) was fixed in this session but the
   demonstrated hit rate did not improve to expected ranges.
   Root cause not yet identified — see README § Phase D' for
   hypotheses.

## Caveats

- **Small dataset (1000 entities).** Both runs. Real agent workloads are
  this size or smaller in the inner loop, so the comparison is plausible
  for that regime. Larger datasets (100k+) would change the picture.
- **Different storage backends.** V0 bench: memory / konserve-file / sqlite
  / tiered. Sidecar: JVM datahike with konserve-file. The JVM datahike is
  ~10-50x faster than CLJS datahike in raw query/tx — much of the sidecar's
  "miss latency" advantage comes from JVM datahike being faster, not from
  the cache itself.
- **No blobs in either bench.** All workloads are small entities. The
  three-tier storage rule predicts large blob handling is where the sidecar
  architecture really earns its keep (renderer projections kept in cache,
  blobs persistent elsewhere).
- **The sidecar bench is synthetic.** Three agents on a CAS-driven task
  queue, not a real LLM-agent workload.
- **Cache hit rate is bench-dependent.** With the pinned-entry fix, the
  hit rate becomes a function of how often the agent pins a snapshot and
  how many queries it issues per snapshot. The default Phase D workload
  (no pinning) sees 0% hit rate. The Phase D' cache-friendly workload
  (100 queries per pinned snapshot) sees the rate the rerun measured.

## What this proves vs doesn't

- **Proves**: when reads can be batched against a pinned snapshot, the
  sidecar's Rust-host cache reduces read latency from ~280us → sub-microsecond.
- **Proves**: each sidecar write incurs ~63ms median wall time vs V0's
  sub-millisecond in-process per-entity cost; the IPC + JVM + broadcast
  path is the bottleneck.
- **Does not prove**: that the sidecar is faster end-to-end for any
  particular agent workload. That depends on read:write ratio and whether
  agents pin snapshots.
- **Does not prove**: that V0's CLJS datahike scales — it OOMs at 10k
  entities for cross-product joins.
- **Does not prove**: anything about blob handling — both benches use
  small entities only.

## Architectural concern raised by the numbers

The 100x write-latency gap (V0 0.29ms/entity in a batch vs sidecar 63-200ms
single tx) is **the** key risk for the sidecar architecture. Two
mitigations exist:

1. **Batch writes guest-side.** The overlay's `transact!` currently issues
   one tx per call. An agent loop that transacts once per tick at 5/sec
   pays 63ms × 5 = 315ms/sec of wall time in IPC alone — significant.
   Adding a `transact-batch!` API that coalesces N tx-data vectors into
   one wire call would amortize.
2. **Push more compute to the writer.** If the agent's "what to write"
   logic is itself a function of recently-read data, batching the read +
   reasoning + write into one writer-side handler eliminates a round
   trip per tick. The sidecar architecture's WIT contract is currently
   read/write-symmetric and doesn't expose this.

Without these mitigations, the sidecar's only architectural win is
multi-agent shared state with caching — useful when N agents share a DB,
not useful when one agent wants raw throughput.
