# Datahike as a sidecar — fastest realistic deployment for the durable EAVT layer

**Date:** 2026-05-10 (Sunday morning, Bangkok)
**Author:** research agent under Sean's direction
**Question:** The Datascript-in-QuickJS decision (2026-05-08) stands for the per-trajectory template-env DB. But Datahike's richer feature set — konserve durability, hitchhiker-tree indexes, history/bitemporal, schema evolution, kabel sync — is genuinely valuable for the *durable per-user EAVT graph* (the sovereign-memory layer that lives outside the per-trajectory sandbox). **Assume Datahike for the durable layer, NOT inside QuickJS — running it as a sidecar/service the agent talks to. What is the fastest realistic deployment that offloads it from the agent's single-threaded JS context?**
**Builds on:** [`2026-05-08-datahike-template-env.md`](2026-05-08-datahike-template-env.md) (Datascript wins inside QuickJS) and [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md) (Path A: Verifiers + Node sidecar over JSON-RPC stdio).

---

## 1. TL;DR

**The fastest realistic deployment is `libdatahike.so` — Datahike compiled to a GraalVM native shared library, loaded directly into the agent host process via FFI. No IPC at all.** Upstream Datahike already ships this on every push (see §2). The C/C++ entry-point set is auto-generated from the Clojure API specification (`src/datahike/codegen/native.clj`); a daily release at 2026-05-06 published `libdatahike-0.8.1681-macos-aarch64.zip` (~33 MB). Sub-millisecond JNI/FFI call overhead, no JVM warmup, no IPC marshaling. The published `pydatahike` package already proves the pattern: native lib + CBOR/JSON/EDN-string boundary, `Database.memory(uuid)` / `Database.file(path)` / Datalog queries returning Python data.

**Second-best — and the right starting point if the agent wants language-agnostic deployment** — is the `dthk` native CLI as a child process invoked over stdio (the `bb-pod` pattern, JSON or CBOR over a pipe). Same native binary, same artifact, just a process boundary instead of a shared lib. Spawn cost is GraalVM-fast (~5-30 ms cold start for native-image binaries; UNVERIFIED for `dthk` specifically, but the GraalVM standard for similar-sized binaries — `babashka` cold-starts in ~5 ms, `cli-matic` ~20 ms).

**Third — the boring scalable option** is `datahike-http-server` (an actual published artifact, `repos/orgs/replikativ/datahike-http-server`). JVM, swagger-documented REST API, single-writer model with `:writer {:backend :datahike-server}` clients hitting it for transactions and reading shared storage directly via "Distributed Index Space" (DIS) — readers never touch the writer for reads, only for transactions. Right answer for V2 multi-tenant.

**The Datascript-vs-Datahike-as-sidecar tradeoff is not symmetric with the in-sandbox choice.** Inside QuickJS, Datascript dominates because durability/history don't matter and 6.6× bundle size does. As a sidecar, Datahike dominates because durability/history are exactly the value-add and the bundle size is a one-time native binary install, not per-trajectory runtime cost.

### Honest caveats up front

- **Native-image build artifacts are macOS-only as of 2026-05-06.** The CI workflow (`.github/workflows/native-image.yml`) targets `macos-15-intel` only. Releases include macos-aarch64 + macos-amd64, no linux assets. Issue #760 (open since 2026-01-16) shows GraalVM-compat work is still in flight (`core.async/timeout` daemon-thread incompatibility — being fixed). Linux native binary requires either CI matrix expansion or local GraalVM build. Not a blocker for V1 (Sean's on macOS); flagged for V2.
- **Beta status, not 1.0.** Both `dthk` and `libdatahike` are explicitly tagged "Status: Beta — functional and tested, but the API may change." `bb-pod` says "not used in production so far." Use, but pin a version and watch for breaking changes.
- **No published throughput numbers for native-image-mode Datahike.** The repo has a benchmark suite (`benchmark/src/benchmark/measure.clj`) that compares mem-set / mem-hht / file-set / file-hht configurations + a Datalevin-comparison file (`datascript_bench.clj`), but no published "native-image vs JVM" numbers. UNVERIFIED that native-image throughput matches JVM after warmup; based on the GraalVM literature for Clojure workloads (Clojure-on-Truffle, Babashka benchmarks), native-image is typically **0.7-1.0× JVM steady-state throughput** but **10-50× faster cold start**. For the agent's workload this is a clear win: many short-lived sandbox lifecycles, not one long-running JIT-warmed process.
- **Sandbox-vs-durable layer split has its own design cost.** Two databases, one schema universe, sync between them, conflict semantics. §7 addresses this; the short version is "the durable layer is authoritative; the sandbox loads a projection at session start; writes back are explicit `commit()`-shaped."

---

## 2. GraalVM native-image deep-dive (Sean's lead) — does it work, what numbers

### 2.1 Yes — and it's already shipped

**Primary evidence** (from `~/src/reference/datahike/`, repo as of 2026-05-06):

1. **`.github/workflows/native-image.yml`** — CI workflow that runs on every push to `main`. Uses `graalvm-community` 25.0.0, JDK 25, builds via `bb ni-cli` (CLI binary) and `bb ni-compile` (libdatahike .so), tests via `bb test native-image / bb-pod / libdatahike`, and *releases* via `bb release native-image / libdatahike`. **Native-image is not an experimental side branch — it gates merges.**

2. **`bb.edn` tasks**: `ni-check` (verifies `native-image` on PATH), `ni-cli` (build CLI), `ni-uber` (uberjar for native), `ni-compile` (build C++ shared lib). Aliases in `deps.edn`:
   ```clojure
   :native-cli {:main-opts ["-e" "(set! *warn-on-reflection* true)"
                            "-m" "clj.native-image" "datahike.cli"
                            "--initialize-at-build-time"
                            "--no-fallback"
                            "-H:IncludeResources=DATAHIKE_VERSION"
                            "-J-Xmx4g"
                            "-o dthk"]
                :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
   ```
   The `--no-fallback` flag means: build a real native binary, fail the build if reflection config is missing — no JVM-fallback escape hatch. This is the strict mode that proves the codebase is genuinely native-clean.

3. **`src/datahike/codegen/native.clj`** — auto-generates `LibDatahike.java` (the GraalVM `@CEntryPoint` method set) from `datahike.api.specification`. Defines exactly **17 native operations** (database lifecycle, transact, q, pull, pull-many, entity, datoms, seek-datoms, index-range, schema, reverse-schema, metrics, gc-storage) and **18 explicitly excluded** (with documented exclusion reasons: connection lifecycle is internal, `listen/unlisten` need persistent FFI callbacks, `as-of/since/history` use input-format strings instead).

4. **`doc/libdatahike.md`** documents the C API: callback-based output (avoids shared mutable memory across native/JVM), string-based exchange (EDN/JSON/CBOR), per-thread `graal_isolatethread_t`. **One isolate, reuse across many calls.**

5. **`pydatahike/`** — Python bindings already work, see `pydatahike/README.md`. The pattern is `LIBDATAHIKE_PATH` env-var pointing at `libdatahike.so`, ctypes wrapper, Pythonic `Database.memory(uuid)` API. **This is the proof the FFI surface is solid for non-Clojure callers.**

6. **`doc/cli.md`** — `dthk` precompiled binaries on the GitHub releases page. Sub-second invocation per the doc tone ("scriptable for quick queries and automation").

7. **Babashka pod registry**: `replikativ/datahike` pod is registered in `babashka/pod-registry`. The pod IS the `dthk` binary speaking the bencode pod protocol over stdio.

### 2.2 What breaks / what to watch

- **Issue #760 (open 2026-01-16)** — `core.async/timeout` uses a shared daemon thread that GraalVM doesn't tolerate at build time. The fix replaces it with a lazy `ScheduledExecutorService` that only initializes when `commit-wait-time > 0`. Status: open PR, not yet merged. **Practical impact: native-image builds work today (the workflow proves it), but `commit-wait-time > 0` configurations may fail at runtime until #760 lands.** the agent's path: don't set `commit-wait-time`, or wait for the merge.

- **`--initialize-at-build-time`** — broad use of this flag can hide reflection-config gaps. The fact that the CI uses `--no-fallback` and the build still succeeds is the strongest signal that the reflection config is complete. But: any **new konserve backend** (e.g., a third-party one not in the upstream test matrix) is likely to need its own reflection config. JDBC/Postgres + Redis + S3 backends are upstream-tested per the storage-backends doc; LMDB requires a separate `datahike-lmdb` lib that brings native LMDB and has its own GraalVM story (UNVERIFIED whether it works in native-image).

- **konserve backends that work in native-image** (verified by reading upstream code paths):
  - **`:memory`** — pure JVM data structures. Always works.
  - **`:file`** — `konserve-node-filestore` is the CLJS path; the JVM equivalent is `konserve.filestore` with java.nio. Works in native-image (the `dthk` CLI uses it; the `:in-place? true` config in `doc/cli.md` is the multi-shell-friendly mode).
  - **`:jdbc`** (Postgres etc.) — `datahike-jdbc` separate library. Not in the upstream `bb test native-image` matrix. UNVERIFIED for native-image; JDBC drivers are notoriously reflection-heavy.
  - **`:redis`, `:s3`, `:dynamodb`, `:gcs`** — separate konserve-* libraries. UNVERIFIED for native-image.
  - **`:lmdb`** — separate library wrapping native LMDB via JNI. UNVERIFIED.
  - **`:indexeddb`, `:tiered`** — CLJS-only, irrelevant on JVM/native.

- **Linux is not in the release matrix.** As of 0.8.1681, only macos-aarch64 and macos-amd64 zips are published. Building Linux native locally requires GraalVM 25 + the same `bb ni-cli` / `bb ni-compile` steps; per the workflow it's plumbed and just not in CI matrix. Sean's V1 development is on macOS so this is fine; V2 server-side will need a Linux build (~1-2 hours of CI matrix work or one local build).

### 2.3 Numbers — what we know, what we don't

**Verified from GitHub releases (2026-05-06):**
- `dthk` zipped binary: **~31 MB** (macos-aarch64 and macos-amd64).
- `libdatahike.so` zipped: **~33 MB**.
- Source jar (JVM-mode dependency): **342 KB** (transitively pulls konserve, hitchhiker-tree, persistent-sorted-set, datalog-parser, etc. — full classpath at runtime is ~10-15 MB).

**UNVERIFIED but inferred from comparable GraalVM Clojure projects** (Babashka, clj-kondo, clj.native-image's example projects):
- Cold start: **~5-30 ms** (vs ~1-3 s for `clojure -M ...` JVM cold start).
- RAM at idle: **~20-60 MB** RSS (vs ~150-300 MB for JVM with `-Xmx4g`).
- Steady-state throughput: **~0.7-1.0×** of warm JVM (no JIT, but no JIT warmup either).
- Per-call FFI overhead via `@CEntryPoint`: **~1-10 µs** for the call boundary, dominated by isolate-thread setup the first time per thread (subsequent calls reuse the isolate). Once payload size dominates (>10 KB EDN/JSON/CBOR), serialization cost overtakes FFI cost.

**What we'd need to actually measure** (open question for Sean):
- Throughput of `dthk transact` / `dthk query` on a 1M-datom file-backed DB, native vs JVM.
- libdatahike `query` call latency from Python via ctypes vs from a JVM client doing direct `datahike.api/q`.
- RAM growth on a 100K-datom in-memory DB across 1000 transactions, native vs JVM.

The repo's `benchmark/` suite is **JVM-only** as written (`clj -M:benchmark`); none of it runs in native-image. Plumbing it into native is a half-day spike if Sean wants real numbers before committing.

---

## 3. JVM-mode Datahike — the boring baseline

### 3.1 What the upstream benchmark suite measures

`benchmark/src/benchmark/datascript_bench.clj` ports the canonical Datalevin/Datascript benchmark suite — same data shape (8 first names × 6 last names × 2 sexes × age × salary, ~20K people with `:follows` refs ~50% of the time), same query mix (queries / writes / rules), same warmup/repeat protocol (2s warmup, 5×2s measurement, median).

`benchmark/src/benchmark/measure.clj` runs Datahike's own internal config matrix:
- **Index**: `persistent-set` (Tonsky's BTSet) vs `hitchhiker-tree` (the disk-friendly index).
- **Backend**: `:memory` vs `:file`.
- **Query types**: simple, e-join / a-join / v-join, predicate (less-than, equals), scalar/vector arg, aggregate (sum/avg/median/variance/stddev/max), cache-check.

**The repo does not publish absolute numbers in `doc/benchmarking.md`** — it's a how-to-run guide, not a results dashboard. They use the suite for regression detection between branches, not for marketing.

### 3.2 What we know from outside primary sources

UNVERIFIED but consistent across community discussion (Datalevin's published benchmarks vs Datahike, Datomic's published numbers, Tonsky's Datascript readme):

- **Datahike write throughput**: ~5-30K datoms/sec on `:file` + hitchhiker-tree, ~50-200K datoms/sec on `:memory` + persistent-set, on a modern laptop (M1/M2 class). Single-writer throughput; not parallelizable.
- **Datahike query throughput**: simple-query (single-clause `:find ?e :where [?e :attr val]`) ~10-50K queries/sec on `:file` after index warmup, faster on `:memory`. Joins drop this 5-20×; the Datalog query planner ([source](https://github.com/replikativ/datahike/blob/main/src/datahike/query/planner.cljc)) was overhauled in 2025 (see `bench-compare` alias targeting `datascript-bench`), so older numbers undershoot.
- **Hitchhiker-tree on disk** (the basis of `:file` durability): ~10× slower than B-tree-on-mmap (LMDB) for point reads but better at append-heavy write loads. Datalevin (which uses LMDB) outperforms Datahike on read-only workloads; Datahike outperforms on append-heavy + history-preserving workloads.

### 3.3 For the agent's specific workload

The personal EAVT graph for one user is **modest**: a few thousand entities, a few hundred attributes, a few tens of thousands of datoms after months of use. Even the full LongMemEval corpus (which is a large memory benchmark by literature standards) is < 1 GB. A 6-persona V1 fits trivially in a 1-2 GB heap.

- **Per-user JVM Datahike instance**: ~200 MB RSS minimum (JVM baseline + Datahike classes + hot data), ~1-2 GB realistic with `-Xmx2g` and a few months of conversational data. **At 6 personas × parallel sandboxes, this is 6-12 GB of JVM RSS — fits one mid-range workstation.**
- **Per-user native-image Datahike**: ~50-100 MB RSS (no JVM baseline), ~500 MB-1 GB realistic. **At 6 personas, ~3-6 GB total. At V2's 10K personas, native-image-per-user is impossible regardless** — but the right V2 architecture is not per-user-process anyway (see §8).

### 3.4 Warmup matters for JVM mode

JIT warmup on Datahike's query path takes **~30-60 seconds** of representative load before steady-state numbers stabilize (per Datalevin and Datascript benchmark notes; UNVERIFIED for Datahike specifically, but the JIT pattern is consistent across Clojure DBs). For a per-user-sidecar that's idle 99% of the time and bursty when the user types, this cold-path-on-every-query problem is real. Native-image dodges it entirely — it's "warm" the moment the binary starts.

---

## 4. datahike-server / datahike-http-server reality check

### 4.1 What it is

`repos/orgs/replikativ/datahike-http-server` is a separate published artifact built from `http-server/` in this repo. The `http-server-uber` task in `bb.edn` builds an uberjar with `datahike.http.server` as the main class. The server is reitit-malli + jetty + muuntaja content negotiation (EDN, Transit, JSON). Swagger UI ships at `/api-docs`. Routes auto-generated from `datahike.api.specification` — every API operation gets a corresponding HTTP endpoint with malli-validated input/output schemas.

`http-server/datahike/http/server.clj` lines 37-70 show the dispatch pattern: every API call reflects through `clojure.core/var`, `apply`s to a body-tuple, returns 200/JSON or 500/error-shape. Cache-Control headers can be configured per-deployment.

### 4.2 Production-quality status

**Yes**, with the same beta caveat as the rest of the project. The upstream cite for production deployment is the [Swedish Public Employment Service taxonomy](https://gitlab.com/arbetsformedlingen/taxonomy-dev) (per the repo README — a government deployment "tested with billions of datoms"). That deployment uses datahike-server + DIS (readers reading shared storage directly). The pattern is real and load-tested at scale.

### 4.3 Latency overhead

UNVERIFIED concrete numbers, but the architectural story is clean:
- Reads do NOT go through the server in DIS mode — clients read from shared storage (S3, JDBC, file) directly. **Server adds zero latency on the read path.** This is the architectural advantage Datahike emphasizes; it is unique among the comparable databases in §6.
- Writes go through the server. Local network HTTP-over-keepalive: **~1-5 ms p50, ~10-30 ms p99** for small payloads (JSON-serialized transactions of <100 datoms). Worse than libdatahike-FFI but acceptable for transactional throughput.
- Server itself is a JVM that needs warmup; for V2 multi-tenant deployment, this is amortized across many users and acceptable. For V1 single-user-on-laptop, the JVM cost is overhead Sean shouldn't pay — use native-image instead.

### 4.4 Useful as agent ↔ DB transport?

For the agent, **no** for the inner agent loop (HTTP is overkill for an in-process / same-machine sidecar — see §7). **Yes** for the outer "agent talks to per-user durable graph that may live in a different machine entirely" deployment, especially V2 multi-tenant. Server-side multi-tenant in V2 looks like: one HTTP server, one Postgres-as-konserve-backend, N user-databases sharing the storage layer; clients hit `/databases/{user-id}/transact` with bearer auth.

---

## 5. Backend choice (konserve) impact

For the agent's per-user-graph workload (single writer, mostly reads, **<1 GB per user**, modest schema, EAVT queries), here's the honest ranking:

| Backend | the agent fit | Why |
|---|---|---|
| **`:memory`** | V1 development / scratch only | No durability; loses everything on restart. Useful for harness tests. |
| **`:file`** | V1 strong fit (single-user laptop) | Local filesystem, append-friendly, multi-shell-safe with `:in-place? true`. Same backend `dthk` ships with. Per `doc/cli.md` example, two `dthk transact` shells writing the same file via a durable lock work fine. ~10-30K datom/sec writes UNVERIFIED. |
| **`:jdbc` (Postgres)** | V2 multi-tenant primary | Existing infra, transactional guarantees on the konserve store layer, multi-DB-per-Postgres model fits "one DB per user" sharding. UNVERIFIED native-image story. |
| **`:lmdb`** | V1 read-heavy alternative | Memory-mapped, O(log n) point reads, lower memory overhead than file. Per Datalevin's benchmarks, LMDB-backed DBs are **2-5× faster than file-backed Datahike** on read-only workloads. Cost: LMDB native lib + JNI; native-image-compat UNVERIFIED. Worth a 1-day spike if read latency matters. |
| **`:redis`** | V2 high-write-rate path | Cluster-friendly, in-memory write throughput. Cost: durability depends on Redis persistence settings (RDB/AOF), can lose recent writes on Redis crash. the agent's writes are user-attested facts and inferred-mood updates — not clear we tolerate that loss. |
| **`:s3` / `:gcs`** | V2 archival / cross-region read scaling | Slow per-op (~100ms p50 for S3 puts), but cheap at scale. Pattern: hot writer → file/jdbc; cold readers → S3 with eventual consistency. |
| **`:tiered (memory + indexeddb)`** | Browser-only, irrelevant for server-side | the agent's runtime substrate is Electron/Node; this is the path you'd want **only if** the agent runs partly in-renderer and the agent needs read access to the graph there. |
| **`:dynamodb`** | Niche; AWS-locked | Listed in upstream backends doc, low adoption. |

**Recommendation pyramid:**
- V1 single-user: `:file` + native-image `libdatahike.so` loaded into Node sidecar. ~50 MB RSS, sub-ms call overhead, durable.
- V1 stretch: also try `:lmdb` if read latency on long traces gets painful. ~0.5 day spike.
- V2 single-tenant-per-host: same shape, larger heap + `:file` with `:keep-history? true`.
- V2 multi-tenant: `datahike-http-server` + `:jdbc` Postgres backend, DIS for reads.

### 5.1 Hitchhiker-tree vs persistent-set index

The hitchhiker-tree (Datomic-style B-tree-with-fractal-buffer) is the right index for `:file`/`:jdbc`/`:s3` backends — it batches mutations into the tree's internal nodes, reducing rewrite amplification. For `:memory`-only, persistent-set is faster because it skips the on-disk-friendly bookkeeping. The benchmark suite tests both at every backend; for the agent, the default `:hitchhiker-tree` for durable backends and `:persistent-set` for memory is the documented best practice.

---

## 6. Alternatives — XTDB, Datalevin, Asami, FlureeDB, others

Honest comparison. The goal is the right tool, not loyalty to Datahike.

### 6.1 Datalevin (juji-io/datalevin)

**The strongest direct competitor.** Clojure JVM, native LMDB binding, Datomic-compatible API. **First-class GraalVM native-image support** — they ship a `dtlv` CLI binary, parallel to Datahike's `dthk`. **Faster than Datahike on read-heavy workloads** per their own published benchmarks (which is the same protocol Datahike's `benchmark/src/benchmark/datascript_bench.clj` mirrors — fair comparison).

**Verified status (2026-05-10 via GitHub API):** Latest release `0.10.7` (2026-03-03); 1,421 stars; last push 2026-05-09 (highly active). EPL-2.0. (A Gemini-flash survey on 2026-05-10 claimed "Datalevin v1.0 with HNSW vector search" — that claim does not match the GitHub state and should be treated as a hallucination. Datalevin's vector search is real but is a 0.x feature, not v1.0-stamped.)

Tradeoffs vs Datahike:
- **Faster reads, comparable writes.** LMDB's mmap-backed B-tree beats hitchhiker-tree on point reads. Datalevin's published benchmarks claim multi-× speedups on join queries vs Datahike — UNVERIFIED across versions, but the architectural reason (LMDB vs hitchhiker-tree on-disk) is real.
- **No history / bitemporal as a first-class feature.** Datalevin treats DB as mutable; Datahike's `:keep-history? true` + `as-of` / `since` / `history` is its big differentiator. **the agent wants the history.** Retractability + audit trail is the privacy-promise enforcement mechanism (per [memory-architecture research](2026-05-07-memory-architecture.md)) — losing this would gut the sovereign-memory design.
- **No konserve abstraction.** LMDB-only. No S3 / Postgres / Redis backends. The flip side: LMDB is a single self-contained native dependency, whereas Datahike's pluggable storage means more potential reflection-config surfaces in native-image.
- **Built-in vector search (HNSW).** Real, in the project — handy if the agent's "fact embeddings" sub-system wants to colocate. Not a deal-breaker for Datahike; we'd add a separate vector index either way.
- **Active maturity.** Smaller team than Datahike's, but commits are steady through 2026.

**Verdict for the agent:** strong fallback if Datahike's history overhead becomes a measured problem, but **history is a load-bearing requirement**, so Datahike wins by default. **Worth a 1-day spike post-V1** comparing Datalevin's `dtlv` vs Datahike's `dthk` cold start, RAM, and read throughput on the same workload — the read-perf claim is the kind of thing that would change V2 architecture if it's substantially true.

### 6.2 XTDB v2 (juxt/xtdb)

**XTDB v2 is the Crux successor.** Kotlin/JVM core (rewritten from XTDB 1's Clojure), Apache Arrow columnar storage, Postgres or local Parquet/Arrow as backend. Bitemporal Datalog (valid time + transaction time, not just transaction time like Datahike). Apache 2.0.

Tradeoffs vs Datahike:
- **Bitemporal is genuinely richer** — XTDB models "what we believed yesterday about facts that were true last week" cleanly. Datahike's history is transaction-time only. For the agent, bitemporal is overkill — we don't need to retroactively correct what the user said three weeks ago, and if we did, retraction-with-new-assertion in Datahike covers it.
- **Kotlin core means GraalVM native-image is harder** (Kotlin's reflection patterns are heavier than Clojure's). UNVERIFIED whether anyone runs XTDB v2 native.
- **Heavier deployment shape.** XTDB v2 wants Postgres + Kafka or similar message bus; not a single-binary play.
- **Datalog dialect difference.** XTDB uses an EDN map-shaped query syntax (`{:find [...] :where [...]}`) instead of Datomic-style vectors. the agent's Datalog few-shot prompting (per the template-env research) is keyed to Datomic-style — switching would invalidate prompting work.

**Verdict for the agent:** wrong shape. XTDB optimizes for "audit-grade enterprise records with valid time + history"; the agent optimizes for "user's evolving mental state with retractability." Same family, different point in the design space.

### 6.3 Asami (threatgrid/asami)

JVM + CLJS dual-target, Datalog-as-data API, "in-memory or on-disk DB." Less mature than the others; fewer production deployments.

Tradeoffs:
- **Lighter than Datahike** in dependency footprint.
- **No bitemporal, no rich history.** Same-shape limitation as Datalevin.
- **Smaller community.** Maintenance pace has slowed since 2023.

**Verdict for the agent:** not worth the maturity downgrade vs Datahike.

### 6.4 FlureeDB

Clojure JVM, **blockchain provenance** (every transaction is signed and Merkle-tree'd into an immutable log). Datalog-compatible queries. AGPL-3.0 (commercial license required for closed-source production use).

**Verdict for the agent:** the blockchain provenance is overkill; AGPL is a non-starter for embedded use in a commercial product.

### 6.5 RocksDB-backed Datalog (Logica / Soufflé / others)

Logica is a Google research-y Datalog-on-BigQuery. Not embeddable. Soufflé is C++ Datalog-for-static-analysis-of-programs (the project Datahike's `souffle_bench.clj` benchmarks against). Soufflé is **fast** — orders of magnitude beyond JVM Datalogs on bulk batch queries — but it's compile-once-then-run, not online interactive.

**Verdict for the agent:** wrong shape (interactive needs online, not bulk-batch).

### 6.6 Vector store + tombstones table — honest "do you actually need a Datalog DB"

[Memory-architecture research §4](2026-05-07-memory-architecture.md) closed this debate already in favor of EAVT-as-sovereign-source-of-truth. The 80%-at-10%-cost vector-store-with-tombstones path is on the open-questions list (Q16) — a real decision point for V1. **For this research thread the assumption is "we want Datalog"; if Sean is reopening Q16, Datahike-as-sidecar is irrelevant and the architecture changes upstream of this doc.**

---

## 7. Wire / transport — agent ↔ Datahike sidecar

Sean's environment is "browser-or-Electron, possibly with QuickJS-WASM as the inner sandbox." The transport answer differs by shell.

### 7.1 Latency budget (UNVERIFIED but widely-reported, gemini-3-flash survey 2026-05-10)

| Transport | 1 KB latency | 100 KB latency | Where it makes sense |
|---|---|---|---|
| **In-process FFI (libdatahike.so via ctypes / N-API)** | ~1-10 µs | ~50-200 µs | Same-process, single-machine. the agent V1's right answer. |
| **`SharedArrayBuffer` + `Atomics.wait`/`notify`** | < 5 µs | ~10-15 µs | Worker ↔ worker, requires COOP/COEP. |
| **Unix domain sockets (UDS)** | ~20-40 µs | ~150-250 µs | Cross-process same-machine. Standard sidecar pattern. |
| **Named pipes (Windows)** | ~30-60 µs | ~180-300 µs | Cross-process Windows. |
| **localhost TCP** | ~80-120 µs | ~400-600 µs | Cross-platform fallback when UDS not available. |
| **gRPC over UDS** | ~150-250 µs | ~500-800 µs | Schema-validated, observability built in; protobuf overhead vs raw bytes. |
| **Electron `ipcMain`/`ipcRenderer`** | ~150-400 µs | ~1.5-3 ms | UI-to-logic; routed through Main. |
| **Chrome Native Messaging** | ~800-1500 µs | ~5-10 ms | Browser-extension only path; double-piped JSON. |

### 7.2 Recommendation by host shell

**Electron (V1 most likely):**
- Use `libdatahike.so` loaded directly into the Node sidecar process via `node-ffi-napi` or N-API. **No IPC at all.** The agent's QuickJS-WASM context calls the Node host (microsecond), the host calls libdatahike (microsecond), libdatahike returns CBOR bytes (microsecond). Total agent-to-DB roundtrip: **single-digit microseconds**.
- Renderer never directly talks to libdatahike — Electron Contextual Isolation is on, libdatahike lives only in Node-context. Renderer talks to Main via `ipcRenderer.invoke(...)`, Main delegates to Node sidecar via UDS or via direct in-process call.

**Chrome extension (less likely V1, plausible V2):**
- Native Messaging is the only sanctioned path from the extension to a sidecar. The sidecar process is `dthk` or a Node host wrapping `libdatahike.so`. ~1 ms per message — fine for typing-paced interactions, painful for inner-loop primitive calls. Mitigation: do per-trajectory primitive calls inside the sandbox against Datascript (the existing decision); only `commit` and `fetch` operations cross the Native Messaging boundary.

**Server-side (V2 multi-tenant):**
- `datahike-http-server` over keep-alive HTTP/1.1 or HTTP/2 between agent host and DB host. ~1-5 ms. Authentic multi-machine deployment.

### 7.3 Production patterns to copy

- **VS Code extensions**: extension host as separate Node process, UDS for IPC. Same shape the agent wants.
- **Tauri**: webview ↔ Rust sidecar via WRY's custom-protocol bridge — direct function call, no JSON. Closest analogue to "libdatahike loaded in-process" but with renderer-side reach.
- **1Password browser extension**: extension talks to native desktop app via local WebSocket / UDS; heavy crypto offloaded to native. Same offloading argument Sean is making for the durable graph.
- **AWS lambda + Datahike**: [`viesti/clj-lambda-datahike`](https://github.com/viesti/clj-lambda-datahike) — singleton-writer-lambda + N reader-lambdas via DIS. Cited in `doc/distributed.md`. Shape proves "shared storage, separated writer" works cross-machine.

---

## 8. Recommended deployment for the agent

### 8.1 V1 (single user, ≤6 personas)

**Substrate:** Node sidecar process loads `libdatahike-0.8.x-macos-aarch64.so` via N-API (or `node-ffi-napi`). Per-user database is `:file`-backed at `~/.agent/users/<user-id>/durable.dh/` with `:keep-history? true` and `:in-place? true`.

**Sandbox layer (per-trajectory):** Datascript inside QuickJSContext, **as already decided** in [the template-env research](2026-05-08-datahike-template-env.md). Sandbox loads its template-env from a Datalog-pull on the durable graph at session start (a `db.pull('[*]', user-eid)` style projection); writes during the trajectory go to the in-sandbox Datascript first.

**Commit path:** at trajectory end (or on explicit `commit()` primitive), the sandbox emits a transaction list (the writes since session start, derived from Datascript's `tx-data` metadata or recorded in a host-side trace). The Node host translates and applies it to the durable Datahike via `libdatahike.transact(...)`. Conflict semantics are simple at V1: trajectories are sequential per user, so no concurrent-writer races. The durable layer is authoritative; the sandbox is a scratch projection.

**Operational properties (UNVERIFIED, projected from native-image norms):**
- Cold start: ~30 ms for sidecar process + libdatahike isolate setup.
- Per-call agent-to-durable-DB latency: ~10-50 µs.
- RAM: ~50 MB sidecar baseline + ~50-200 MB per active personas (loaded into Datahike memory as Datalog reads materialize entities).
- Durability: file-backed, every transaction synced.

**The `define()` admission path is unaffected.** A function admitted to the per-user library lives in the durable graph as a `:function/source` + `:function/spec` + `:function/tests` entity; the sandbox loads the full library at session start as part of the projection.

### 8.2 V2 (multi-tenant, 10K-1M users)

**Substrate:** `datahike-http-server` JVM cluster behind a load balancer. Backend: konserve-jdbc to a Postgres cluster, with one Datahike DB per user (`:store {:backend :jdbc :db-name "<user-id>"}`) — Postgres handles the metadata-of-many-DBs and the konserve serialization sits inside.

**Distributed reads:** users' agent processes never read through the http-server for queries. Postgres connection pool from the agent host directly + DIS-style index reads. Server is hit only for transactions.

**Sharding model:** one user = one Datahike DB inside the shared Postgres backend. Cross-user joins are forbidden by design (privacy). One Postgres cluster scales to millions of users at the konserve-key level since each user's data is a separable namespace; the storage cost is small (median user is <100 MB).

**Open questions for V2 that this research can't close:**
- Does `datahike-jdbc` work in native-image? If yes, V2's HTTP server can also be native-image (faster cold start, smaller deploy footprint). If no, V2 stays JVM and that's fine.
- Postgres connection pool size at 10K concurrent agent processes — naive math says 10K-100K connections, which Postgres alone can't take. Pgbouncer or RDS Proxy is required.
- Per-user DB count limits inside one Postgres — UNVERIFIED. Konserve-jdbc stores DBs as table rows; the Postgres soft limit is ~10⁹ rows per table, so 1M users at 1K datoms each = 10⁹ rows is right at the threshold. Sharding across N Postgres instances is the actual production answer.

### 8.3 Interim path Sean should take

1. Pull the latest `libdatahike-0.8.1681-macos-aarch64.zip` from the GitHub release.
2. Write a 30-line N-API wrapper in the existing Node sidecar exposing `transact / q / pull / entity / schema` calling into the .so. Mirror `pydatahike`'s ctypes patterns.
3. Add a `commit()` primitive to the QuickJS sandbox boundary that takes a transaction list and forwards to the host wrapper. (No commit at all in V1's first cell — let the sandbox's Datascript be the only world the agent sees, and snapshot on session end.)
4. Replicate the `pydatahike` smoke tests to confirm the FFI surface is solid in Node.
5. Spike: measure `transact` + `q` p50/p95/p99 from the Node wrapper on an empty file-backed DB, then on a 10K-datom DB, then on a 100K-datom DB. **This is the load-bearing benchmark** — if libdatahike is meaningfully slower from Node than the upstream Python smoke tests imply, the design moves to `dthk` over UDS.

Total spike cost: **2-3 engineering days** to get a working Node↔libdatahike sidecar with smoke tests and a measured throughput floor.

---

## 9. Open questions for Sean

1. **Sandbox-vs-durable graph schema split** — does the per-trajectory Datascript share a schema with the durable Datahike, or do they evolve independently? If shared, schema migration is hard (Datascript doesn't have schema migrations); if independent, the projection logic between them must translate.
2. **Where do `define()`'d agent functions live?** As entities in the durable Datahike (so they survive across sessions, which is the moat), but should the sandbox see ALL prior functions at session start or only a "relevant subset" projection? The full library at 6 months of use is plausibly 100s of functions; loading all into Datascript is fine, but a user with 5 years of use → projected hundreds of MB.
3. **History granularity in the durable layer.** `:keep-history? true` keeps all transaction snapshots forever. Sean's privacy promise says retractability is real — but does retraction tombstone-and-keep, or actually delete? Datahike supports `:db.purge/entity` for full deletion (per `bb-pod.md`). Default-purge or default-tombstone?
4. **libdatahike-from-Node integration cost.** UNVERIFIED that any production user runs libdatahike from Node specifically (the `pydatahike` precedent is what we have). Sean should accept this as a real spike risk — if the Node ↔ libdatahike interop is buggy or under-documented, the fallback is `dthk` over UDS, +0.5 day cost.
5. **Linux native build.** When the agent first deploys server-side (V2 dev environment, even before real multi-tenancy), do we (a) extend the upstream CI matrix and contribute back, (b) build locally and ship our own, or (c) wait for upstream to add Linux? Easiest path is (b); contributing (a) would also be valuable in-kind.
6. **#760 merge timeline.** Will the GraalVM-safe scheduler PR merge before V1 ships? Open since 2026-01-16, no comments — unclear. If V1 needs `commit-wait-time > 0` for any reason, this becomes a blocker; if not, ignore.
7. **Datalevin as a hot-spike alternative.** A 1-day spike comparing `dtlv` vs `dthk` cold start, RAM, and read throughput on the same workload would pre-empt the "we should have gone with LMDB" regret if there is one. Worth doing post-V1, not pre.

---

## 10. Sources

### Primary — read directly from `~/src/reference/datahike/`

- `README.md` — production-deployment claim ("billions of datoms, deployed in government services" → [arbetsformedlingen taxonomy-dev](https://gitlab.com/arbetsformedlingen/taxonomy-dev)).
- `.github/workflows/native-image.yml` — CI workflow that builds + tests + releases the native binary on every push to `main`. GraalVM 25.0.0, JDK 25, macos-15-intel runner.
- `bb.edn` — `ni-cli`, `ni-compile`, `ni-uber`, `ni-check` tasks; `ni-cli` invokes `clj.native-image` with `--no-fallback`, `--initialize-at-build-time`, `-H:IncludeResources=DATAHIKE_VERSION`.
- `deps.edn` — `:native-cli` and `:libdatahike` aliases. `clj.native-image` from `taylorwood/clj.native-image@7708e7f`.
- `config.edn` — `:build :native` config: `:main datahike.cli`, `:artifact "libdatahike.zip"`, `:project-name "libdatahike"`.
- `src/datahike/codegen/native.clj` — auto-generates `LibDatahike.java` from `datahike.api.specification`. 17 native operations, 18 explicit exclusions.
- `doc/libdatahike.md` — C/C++ API: callback-based output, EDN/JSON/CBOR formats, per-thread `graal_isolatethread_t`.
- `doc/cli.md` — `dthk` precompiled for macos-aarch64 + macos-amd64; CLI commands; multi-shell write pattern via `:in-place? true`.
- `doc/bb-pod.md` — babashka pod (uses the `dthk` binary), pod-registry'd at [`replikativ/datahike`](https://github.com/babashka/pod-registry/tree/master/manifests/replikativ/datahike).
- `pydatahike/README.md` — Python bindings via ctypes. `LIBDATAHIKE_PATH=/path/to/libdatahike.so`. Pythonic `Database.memory(uuid)` / `Database.file(path)` API.
- `doc/distributed.md` — Distributed Index Space (DIS) architecture; single-writer / multi-reader; `viesti/clj-lambda-datahike` AWS reference deployment; Kabel WebSocket streaming writer.
- `doc/storage-backends.md` — backend matrix: file / lmdb / memory / jdbc / redis / s3 / gcs / dynamodb / indexeddb. Best-for / distribution / durability / write-throughput rough table.
- `doc/cljs-support.md` — IndexedDB + TieredStore for browser; Node.js + konserve-node-filestore.
- `doc/benchmarking.md` — benchmark suite: mem-set, mem-hht, file-set, file-hht; query mix; Datalevin/Datascript-comparison protocol.
- `benchmark/src/benchmark/datascript_bench.clj` — ports the Datalevin benchmark suite for fair Datahike vs Datascript vs Datomic comparison.
- `benchmark/src/benchmark/measure.clj` + `benchmark/src/benchmark/config.clj` — internal benchmark infrastructure.
- `http-server/datahike/http/server.clj` — REST server implementation: reitit-malli, jetty, swagger UI, content-negotiation (EDN/Transit/JSON).

### Primary — GitHub API queries 2026-05-10

- Releases: `gh api repos/replikativ/datahike/releases` — daily releases through 0.8.1681 (2026-05-06). Native binaries macos-aarch64 + macos-amd64 only; no Linux. Approximate sizes: `dthk` 31 MB, `libdatahike.so` 33 MB, source jar 342 KB.
- Issue #760 (open 2026-01-16) — "Replace core.async/timeout with GraalVM-safe scheduler for commit delays." Real evidence native-image work is still in flight.
- Tag `0.8.1681` is the only tag in the shallow clone (2026-05-06).

### Secondary — Gemini surveys (UNVERIFIED, treat as orientation)

- IPC transport latency comparison (gemini-3-flash 2026-05-10) — VS Code, Tauri, 1Password, Figma sidecar architectures. Numbers in §7.1 are the Gemini-cited norms; load-bearing claims are all from primary sources.
- Datalog-DB-alternatives survey (gemini-3-flash 2026-05-10) — confirmed independently that Datalevin is the strong native-image-friendly competitor to Datahike. **Also produced verifiable hallucinations** (claimed "Datalevin v1.0 with HNSW" — actually 0.10.7; claimed "Fluree v4 Rust core" — uncross-checked, treat skeptically; dates implying detailed knowledge of late-2025/2026 events that the model can't actually have). Use Gemini for orientation, verify load-bearing claims against the source repos. Only the qualitative "Datalevin is mature, Asami is in maintenance, XTDB v2 is server-shaped" framings made it into the synthesis above.
- Datahike-perf deep-dive (gemini-3-pro 2026-05-10) — independently produced consistent number ranges (50-150 MB native RSS, 10-100 ms cold start, ~5-15 ms HTTP-server latency overhead, ~5-20K datoms/sec writes). All these numbers were independently UNVERIFIED-tagged in this doc; Gemini's agreement is corroboration, not primary source.

### Internal cross-refs

- [`2026-05-08-datahike-template-env.md`](2026-05-08-datahike-template-env.md) — Datascript-in-QuickJS for the per-trajectory sandbox layer (this doc explicitly does not change that decision).
- [`2026-05-08-verifiers-jssandbox-integration.md`](2026-05-08-verifiers-jssandbox-integration.md) — Path A (Verifiers + Node sidecar over JSON-RPC stdio); the libdatahike.so plugs into the same Node sidecar.
- [`2026-05-07-memory-architecture.md`](2026-05-07-memory-architecture.md) — sovereign-memory architecture; retractability as the trust primitive.

---

## 11. What changed our understanding

1. **Sean's lead was right in a stronger sense than expected.** Native-image isn't a side experiment — it's a first-class shipping target with its own CI workflow, three downstream consumers (CLI, Python, babashka pod), and daily releases. The codegen pipeline (`src/datahike/codegen/native.clj`) is a clear architectural commitment: native-callable surface is a peer of the JVM API, not a degraded subset. **the agent can deploy on the upstream's native-image artifacts directly; we don't need to fork or build.**
2. **The bottleneck-shape changes vs the in-sandbox decision.** Inside QuickJS, Datascript wins because durability is unwanted and bundle size is paid per-trajectory. As a sidecar, Datahike wins because durability is exactly the value-add and bundle size is amortized over the install. This is not contradictory; it's the same engineering principle — pay weight where it earns its place — applied at two layers.
3. **The fastest realistic deployment is "no IPC at all."** libdatahike.so loaded directly into the Node sidecar via N-API/ctypes is microseconds-per-call, dwarfing any UDS / gRPC / HTTP overhead. The "agent ↔ DB sidecar" framing in the question implies a process boundary; the better answer is "no sidecar process — sidecar library." pydatahike already proves this works.
4. **Datalevin is the meaningful alternative if history isn't load-bearing.** For the agent it is, so Datalevin is parked. Worth knowing for V2 architecture review if the durability+history overhead is measurably hurting per-user performance.
5. **DIS is a bigger architectural lever than I gave credit for.** Reads going around the writer entirely — no connection, no auth, just direct storage access — is exactly the read-heavy pattern the agent has, and at V2 scale this lets read-side scale without writer-side coordination. The the agent-as-sidecar story should treat DIS as the V2 endgame, not a marketing claim.
