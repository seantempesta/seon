---
type: research
status: complete
tags: [research, database, architecture, prd]
---

# Cloud store surfaces for the database (S3/GCS) — 2026-07-20

Owner ask: support cloud writes — GCP and AWS surfaces — for the database.
Owner design steer (mid-investigation): evaluate the Datomic-style model as
the PRIMARY candidate — cloud object store as the durable write target, local
content-addressed cache warming over time. This report grounds every claim in
the vendored fork sources and the live default cluster; it ranks the owner's
model on the derived numbers and keeps replicate-to-cloud as the comparison
column.

## 1. The konserve backend protocol (fork @ b5c99bc0)

Two implementation styles exist in the fork, at two layers:

- **Store layer** (`reference-code/konserve/src/konserve/protocols.cljc`):
  `PEDNKeyValueStore` (lines 4-12: `-exists?`, `-get-meta`, `-get-in`,
  `-update-in`, `-assoc-in`, `-dissoc`), `PBinaryKeyValueStore` (38-41),
  `PKeyIterable` (46-49), plus the fork's optional extensions —
  `PMultiKeySupport`/`PMultiKeyEDNValueStore` (14-36, atomic multi-key
  batches), `PWriteHookStore` (57-64, post-write callbacks — the sync/relay
  primitive), `PLockFreeStore` (66-71). All optional protocols have safe
  `Object` defaults (75-86), so a backend that implements none of them still
  works.
- **Backing layer** (`reference-code/konserve/src/konserve/impl/storage_layout.cljc`):
  `PBackingStore` (171-187: create/delete/copy/atomic-move blobs, store
  lifecycle), `PBackingBlob` (226-240: read/write header, meta, value,
  binary), `PBackingLock` (242-243), optional `PMultiWriteBackingStore` /
  `PMultiReadBackingStore` (189-210), and `PReadMissSafe` (212-224) — a
  marker written **explicitly for S3**: it removes the `-blob-exists?` probe
  before every read ("saving a round trip on remote stores (an S3 HEAD
  before every GET)").

**Legacy-header requirement:** the fork's legacy-header layer (commit
`fbdccc9`, "Port legacy header compatibility to Konserve 0.9.359") lives
entirely in `storage_layout.cljc` **above** the backing store: `read-meta-size`
(65-84, the legacy single-byte CLJS meta-size encoding) and the 8-byte
small-header detection in `parse-header` (104-112). A backing-layer backend
stores opaque header/meta/value byte ranges and inherits legacy-header reads
for free. A **direct-style** store (the vendored
`reference-code/konserve-lmdb/src/konserve_lmdb/store.clj`, which implements
`PEDNKeyValueStore` directly with its own buffer codec and no konserve
headers) bypasses that stack entirely. Consequence: **an S3/GCS backend must
be a backing-layer implementation**, and konserve-lmdb is the wrong template
for it; the JVM `filestore.clj` is the right structural template, minus
`PReadMissSafe` which the remote store should implement.

## 2. What exists for S3/GCS

Nothing cloud is vendored. Grounded pointers in the checkout:

- `reference-code/konserve/doc/api-walkthrough.md:15-17` lists the known
  external backends: `replikativ/konserve-s3`, `konserve-jdbc`,
  `konserve-redis`.
- `reference-code/datahike/doc/storage-backends.md` names the supported
  distributed backends with config shapes: S3 (line 130-152,
  `org.replikativ/konserve-s3`, `{:backend :s3 :bucket ... :region ...}`),
  **GCS** (154-170, `org.replikativ/konserve-gcs`, `{:backend :gcs :bucket
  ... :project-id ...}`), DynamoDB (`konserve-dynamodb`), JDBC. It also
  states the single-writer model explicitly (line 82).
- `reference-code/datahike/doc/distributed.md` describes the Distributed
  Index Space: index nodes written to shared storage (S3/JDBC/file), readers
  go direct, writers behind one endpoint — exactly Seon's pod/writer split.
- The fork's own CHANGELOG/comments show the S3 path was already designed
  for: `writing.cljc:497-516` ("A torn batch ... is what makes the batch
  safe WITHOUT atomic multi-key writes, which S3 and filesystems cannot
  give us anyway"), and `konserve/CHANGELOG.md` entries about S3 round-trip
  and delete semantics.

**Honest gap:** `konserve-s3` and `konserve-gcs` are not in
`reference-code/`, and upstream tracks upstream konserve — not this fork
(0.9.359 base + legacy header + multi-key + write hooks + `PReadMissSafe` +
`:immutable?` metadata). They must be **mirrored into `reference-code/` and
compat-tested against the fork** (run `konserve/test/konserve/compliance_test.cljc`
and the fork's storage-layout tests over the real backing store) before any
plan builds on them. The fork's optional-protocol `Object` defaults mean a
stock backend degrades safely: no multi-key → `commit!` uses the ordered
individual-write fallback (`writing.cljc:529-552`), which is the designed S3
path.

## 3. Latency/cost model

### Per-commit store operations (from the real flush path)

Source: `reference-code/datahike/src/datahike/writing.cljc`.

- Index flush accumulates content-addressed node writes into
  `pending-writes` — no I/O during flush itself (`db->stored`, lines 49-150).
- `commit!` then performs, in order (lines 486-552):
  1. **N node PUTs** — the flushed hitchhiker/PSS nodes, `k/assoc ...
     {:immutable? true}`. With `sync? false` these are issued concurrently
     and then awaited (`write-pending-kvs!`, 410-421). The writer commits
     with `sync? false` (`datahike/src/datahike/writer.cljc:221`).
  2. **+1 PUT** schema-meta, only on schema change (awaited before nodes'
     dependents, 530-536).
  3. **1 PUT** commit record under the commit id (awaited before the head
     write, 540-549).
  4. **1 PUT** branch head — the only mutable key, last (550-552).
- **GETs per ordinary commit: 0.** The branch-head read is skipped via the
  writer's head-cid cache (451-467, comment: "3 sequential requests on S3
  backends" saved). Explicit-parents commits (merge/branch machinery) keep
  the read.

N (node PUTs) under Seon's current config
(`src/seon/db/backend.clj:120-124`: `:keep-history? true`,
`:fuse-index-roots? true`): 6 indices (3 primary + 3 temporal) × path
nodes. Root fusion drops the root PUT per index outright without
crypto-hash (`datahike/doc/write-amplification.md`, "Root fusion"). A small
commit on a shallow tree ⇒ N ≈ 6-12; `keep-history? true` is a ~2×
multiplier on the index part.

### Wall-clock per commit

Sequential depth is **3 round-trips** (parallel node batch → commit record →
branch head), +1 on schema change. At object-store latency (~50-100 ms/PUT
in-region):

| Store | Durability point per commit |
|---|---|
| Local file | ~1-5 ms |
| S3/GCS same-region | **~150-300 ms** (3 sequential RTTs) |
| + diff buffering / commit-graph off | approaches 1 RTT ≈ 50-100 ms |

`datahike/doc/write-amplification.md` documents the exact dials for
request-priced object stores: `:index-config {:diff-buf-size N}` (collapses
interior-node PUTs), `:fuse-index-roots? true` (already on in Seon),
`:commit-graph? false` (drops the provenance record — **not recommended for
Seon**: the restore/audit machinery and `cluster fork <t>` depend on the
commit graph). All are create-time-fixed store properties.

### Live commit cadence (default cluster, read-only probe 2026-07-20)

Queried over the writer's io-prepl (port 64977), `:db/txInstant` on
`:default`:

- 349 transactions in the current 24 h window; 6 in the last idle hour.
- Inter-commit gaps: **min 76 ms, p10 118 ms, median 154 ms**, p90 55.6 s;
  245/348 gaps < 1 s.

Interpretation: activity is bursty — during an agent turn commits arrive
every ~120-160 ms, faster than a 150-300 ms cloud durability point. The
single-writer commit loop serializes per branch, so cloud-primary sync
commits queue ~2× during bursts (transact acks are already asynchronous to
callers, so this is added latency, not deadlock). **Cloud-as-primary is
viable but marginal at today's burst cadence without the diff-buffering
dial; with it, the floor (~1 RTT) sits at or under the burst gap.** No new
batching layer needs inventing — the dials exist in the fork.

## 4. The Datomic-style model (owner's primary candidate)

Verified from source: hitchhiker/PSS nodes are content-addressed and written
`{:immutable? true}` (`writing.cljc:415-420, 521-527`); only the branch-head
key is mutable (526, "the branch-head pointer stays mutable"). So a local
cache over a cloud store needs **no invalidation** — immutable keys never
change; only the branch head must be read through.

**The compose mechanism already exists — one mechanism, a store wrapping a
store:** `reference-code/konserve/src/konserve/tiered.cljc`. Write policies
`#{:write-through :write-behind :write-around :frontend-only}` (line 22) and
read policies `#{:frontend-first :frontend-only}` (31), with sync strategies
for warming a frontend from a backend (52-121, incl. multi-get batch
warming). The owner's model is exactly:

```clojure
{:backend :tiered
 :frontend-config {:backend :file #_or :lmdb ...}   ; local content-addressed cache
 :backend-config  {:backend :s3 :bucket ... :region ...}
 :write-policy :write-through                        ; durability = the cloud PUT
 :read-policy  :frontend-first}

```

`konserve.cache` (LRU over one store) additionally exists for in-memory
read caching (`cache.cljc:19-25`). Datahike's own docs recommend precisely
this tiering ("Server: Memory → LMDB → S3", `storage-backends.md`
TieredStore section).

One correctness rule to add in the unit that ships this: the **branch-head
key must not be served stale from the frontend** for any process that is not
the writer itself. For the single JVM writer (Seon's only writer,
`seon.db.server`) the frontend is coherent because the writer wrote it; a
future non-writer cloud reader must read the head with a frontend-bypass
(the `:frontend-only` write-policy + konserve-sync-style hook feed is the
documented shape for that peer, `tiered.cljc:23-28`).

**Head-swap under concurrent writers:** the fork requires only ordered
`k/assoc` semantics — values first, mutable head last (`writing.cljc:
500-516, 535-552`); a torn sequence leaves collectable orphans, never a
dangling head. There is **no CAS in the konserve protocols**; safety is the
single-writer model (`storage-backends.md:82`) plus Seon's process
supervision. S3 conditional writes (`If-Match` ETag) / GCS generation
preconditions (`x-goog-if-generation-match`) can be added **inside the
mirrored backend** as defense-in-depth on the branch-head PUT (fencing a
split-brain second writer). Optional hardening, not a correctness
prerequisite.

## 5. Proximum (first-class concern, honest verdict)

Feared blocker — HNSW wants mmap — is **not what the source says**:

- Edges: `reference-code/proximum/src/proximum/storage.clj` — `CachedStorage`
  is konserve-backed with an LRU cache and `pending-writes` flushed via
  `multi-assoc` or individual `k/assoc` (lines 48-75, 171-193). No mmap.
- Vectors: `reference-code/proximum/src/proximum/vectors.clj:1-32` — "Dual
  storage model: Konserve: **source of truth**, distributed via
  konserve-sync; Local mmap: **runtime cache** for fast SIMD access. On
  open: loads chunks from konserve into local mmap (with optional reuse)."
  Chunks are stored at `[:vectors :chunk uuid]`, content-addressed in merkle
  mode.

So datoms-cloud-primary does not strand Proximum: the mmap file **stays
local by design** (it is a derived cache, rebuilt/reused on open), while its
konserve source of truth rides the same tiered store. Honest caveats: (a)
cold open loads all chunks from the backend — on S3 that is one GET per
1000-vector chunk, unmeasured here; (b) when `SEON_EMBED` is active,
Proximum's `-sec-flush` writes konserve keys inside the commit's GC guard
(`writing.cljc:444-446`), adding PUTs to the per-commit count; (c) none of
this was benchmarked against a real bucket — it is a source-grounded shape,
not a measured one.

## 6. Credentials/config seam

Backend selection today is a closed enum threaded through three owners:

- `src/seon/db/backend.clj:36` — `(schema/register! ::backend [:enum :memory :file])`;
  `datahike-config` (line ~129) maps it to Datahike's private `:store` map.
  This adapter is the **only** place the third-party `:store` shape exists.
- `src/seon/db/protocol.cljc:238` — the same enum on the wire protocol.
- `src/seon/launch.cljc:271` — the cluster descriptor hard-codes
  `::protocol/backend :file`; branch-runtime descriptors inherit it
  (397, 457). The writer receives it via the launch/database descriptor
  (`src/seon/db/server.clj:44-46`).

The seam for cloud: extend that one enum (`:s3`, `:gcs`) and give
`datahike-config` the corresponding `:store` case (bucket/region/project-id
are non-secret config and belong in the manifest/descriptor). **Credentials
never enter this path**: the AWS/GCP SDK default credential chains (env
vars, `~/.aws/...`, `GOOGLE_APPLICATION_CREDENTIALS`, instance metadata)
resolve inside the writer process, matching the standing rule (credentials,
project IDs, service-account files stay outside Git) and the existing
env-at-boot pattern in `server.clj` (`System/getenv`, lines 356, 460).

## 7. Replicate-to-cloud (comparison column)

Existing mechanisms, extended in place — no new machinery:

- **Committed-report feed**: the writer already derives and relays committed
  transactions (`src/seon/db/writer.clj:15` `datahike.committed-report`,
  selective interests at 2118-2120) — the pod replica's replay source. It is
  a *transaction* feed; a cloud replica built on it would re-apply
  transactions, not copy store bytes.
- **Store-level relay**: the fork's write hooks (`protocols.cljc:57-64`,
  invoked at every write, `core.cljc:299, 341, 371, 506`) with
  `:immutable?`-aware dedup are the designed konserve-sync relay primitive;
  `tiered.cljc` `:write-behind` (backend written asynchronously after the
  frontend, lines 242-243 etc.) is the local-primary + streamed-cloud-replica
  shape in one config.
- **Recovery consumer**: the existing restore machinery
  (`src/seon/dev/restore.clj`, `src/seon/db/restore_admin.clj`,
  `seon.db.registry/admin-restore-main!` — branch-head transitions with
  undo branch and roster checks) restores from any store the writer can
  open; a warmed local copy of the cloud backend is just such a store.

Trade-off vs cloud-primary: zero commit-latency risk, but the cloud copy's
durability lags by the write-behind horizon, and `:write-behind` currently
has no persistent retry queue (`tiered.cljc` TODO at line 19 — stale
exception handling) — a crash can lose not-yet-relayed writes, which is
exactly the window a primary does not have.

## 8. Recommendation

**Both as configs; rank the owner's cloud-primary model first for new/cloud
clusters; keep local-file as the default for the dev cluster.**

- The numbers support cloud-primary: 3 sequential RTTs per commit (~150-300
  ms durability point), 0 GETs per ordinary commit, with a fork-documented
  path to ~1 RTT (diff buffering; root fusion already on). Live burst
  cadence (median gap 154 ms) means bursts queue ~2× without the diff dial
  and roughly break even with it. The tiered store gives the local
  content-addressed cache with no invalidation problem by construction.
- Replicate-to-cloud (`:write-behind`) stays as the zero-latency-risk config
  and the recovery story is shared either way.
- Keep `:commit-graph?` ON (restore/fork machinery consumes it); keep
  `:keep-history?` per current config, acknowledging it ~doubles node PUTs.

### Ordered unit sketch (follow-on lane, non-blocking)

1. **U-cs1 — mirror + fork-compat (1 unit per surface, S3 first):** vendor
   `konserve-s3` (then `konserve-gcs`) into `reference-code/`, port to the
   fork's backing layer, implement `PReadMissSafe`, run the fork's
   compliance + storage-layout suites against a real bucket. Includes the
   conditional-PUT head hardening (If-Match / generation-match) behind a
   store option.
2. **U-cs2 — the config seam (1 unit):** extend the `::backend` enum through
   `backend.clj` / `protocol.cljc` / `launch.cljc`, add the `:store` cases
   and non-secret manifest keys; credentials via SDK default chains only.
3. **U-cs3 — tiered composition + measurement (1 unit):** local-frontend /
   cloud-backend `:write-through` config, branch-head read-through rule,
   measured per-commit latency and $/commit against a representative
   workload; decide `:diff-buf-size` for cloud-created stores.
4. **U-cs4 — replicate-to-cloud config + restore drill (1 unit):**
   `:write-behind` (or write-hook relay) replica of the default cluster,
   then a full restore-from-cloud through `admin-restore-main!`; fix the
   write-behind retry gap if the drill exposes it.
5. **U-cs5 — Proximum on cloud (0.5-1 unit, only when SEON_EMBED matters
   there):** measure cold-open chunk GET fan-in and `-sec-flush` PUT
   contribution; keep mmap local-primary as designed.

### Blockers / risks (ranked)

1. `konserve-s3`/`konserve-gcs` are not vendored and target upstream
   konserve, not the fork — mirroring + compat testing is the gate for
   everything else (U-cs1).
2. Burst cadence vs commit latency is marginal without diff buffering;
   diff-buf is create-time-fixed, so existing stores can't adopt it in
   place (migrate via branch/restore machinery).
3. Tiered branch-head staleness for any future non-writer reader.
4. Proximum cold-open and secondary-flush costs are derived, not measured.
5. `:write-behind` durability window (no persistent retry) if the
   replicate-to-cloud config is chosen as primary protection.
