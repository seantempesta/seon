---
title: "Storage write profile: is Datahike/konserve tuned for many small transactions?"
type: research
date: 2026-10-15
status: measured (lane storage-profile, Sonnet); no source edits, no fork changes, default untouched
basis: Seon at the working tree of 2026-10-15 ~13:00 +07, Datahike fork 593a8276, konserve 0.9.359-seon.1 (the OLD read path: numbers taken before root's restart onto konserve af35a974, so read-side costs here predate the buffer fix)
---

# Storage write profile

Method: read-only filesystem and JVM-mode facts from default's store (390,410 files); every write measured in a throwaway JVM
(`clojure -J-Xmx8g -M`, Seon classpath) on an APFS clone of `data/store` (`cp -c -R` + `cp -c -R -n`, 2m47s), replaying the REAL
datoms of main's last 60 commits onto a branch forked 60 commits back (`d/branch!` of the commit, then one `d/transact` per
original tx, retractions then adds, no Seon validator or transaction functions). Clone and JVMs deleted. Scripts are in the
lane's scratchpad only. Timings are wall time on a shared machine (other lanes running): single runs vary 2x, so I give medians
of 50 txs and the spread of repeated runs.

## 1. The workload (measured)

| fact | value |
|---|---|
| commits are one per transaction | max-tx advances by 1 on 1,781 of 1,785 main commits; 1,498 of 1,499 on the chain |
| main (`:cluster-default`) | 1,785 commits since 2026-10-13 17:02 +07 (about 830/day average); busiest hours 216, 189, 184 commits/h (3.6/min) |
| busy chain `:chain-f1f6dc8e6652` | 1,500 commits in 19 h; 713 in its busiest hour (12/min) |
| datoms per tx (delta of commit `:op-count`) | main p10 10, p25 20, **p50 20**, p75 30, p90 51, p99 5,702, p99.9 23,357, mean 169; chain p50 29, p90 60, mean 101 |
| recording-transaction shape | 17 datoms (p50, tx entity excluded) in 20 of 60 replayed txs; 4 of 60 are outliers (4, 5, 38, **5,144** datoms) |
| store | 390,410 files, 1.55 GB logical, **2.44 GB allocated** (APFS 4 KiB blocks: 1.58x) |
| file size distribution | 75% of files (292k) are at most 4,000 B and each occupies a full 4 KiB block; 52k files are 500-1,000 B; 127k 1-2 KB; 111k 2-4 KB; 65k 4-8 KB; 13.5k files of 16-128 KB are the commit records (about 32 KB each) |
| created per hour (mtime) | 2.8k-37k files and 18-180 MB per hour while lanes ran (index nodes plus the commit records) |
| commit record | about **32 KB on disk** (LZ4, p50 31.9 KB, p90 38.9 KB), about 53 KB uncompressed (nocompress run p50 53 KB; larger p90 because of big-tx leaves); decode allocates about 2.4 MB (`datahike-internals.md` finding 2) |

So the workload is the one the owner named: thousands of tiny transactions per day per branch, 17-30 datoms each, with rare
bulk transactions (thousands of datoms) in the same stream.

## 2. What one small transaction costs to write (measured, 50 steady-state txs)

Replay of the 17-datom recording-shaped tx, base configuration (the store's own: bf 128, diff-buf 32, fused roots, LZ4, deferred sync):

| per commit | p50 | p90 |
|---|---|---|
| wall ms (`d/transact` returns after the commit is durable) | **27-60 ms** (4 runs: 29, 41, 27, 60; earlier run 76 under load) | 83 |
| allocation, all threads | **10 MB** | 11.7 MB |
| konserve files created | **20** | 24 |
| bytes created (logical) | **123 KB** | 165 KB |
| of which the commit record | 32 KB (26%) | 39 KB |
| index nodes created | 18 (3-12 KB each, 1-31 KB range): per index eavt 4, aevt 10, avet 7; temporal indexes 0 for these add-only txs | |
| fsyncs (JFR `jdk.FileForce`) | about **2 per commit** (126 over the whole run incl. setup), mean 7.0 ms, max 19 ms | |

The September floor (commit-floor-2026-09-26.md: 29.6 `F_FULLFSYNC` per commit, 71-76 ms of 118) is gone: `:defer-immutable-sync?`
makes immutable keys `:deferred` and only the head and re-written keys take barriers (`konserve.impl.defaults/sync-plan`). What
remains per commit is roughly: 2 forces (about 14 ms), serialization + LZ4 of 20 values, and Datahike's index flush.

Dominant costs, ranked by the variants below: (1) the two durability barriers; (2) per-file overhead (20 files, each its own
temp-file, write, rename); (3) LZ4 framing allocation; (4) payload bytes are small (123 KB). Datahike's own transact CPU is
about 1-3 ms (commit-floor note).

The heavy tail is the real finding: **the 5,144-datom transaction took 4.4-13.4 s and 3,284 files / 9 MB / 4-8 GB allocated**
(about 0.85-2.6 ms and 0.6 files per datom), against 27-60 ms for 17 datoms. Per-datom cost is an order of magnitude above the
small case because bf 128 / diff-buf 32 rewrite and flush many tiny leaves; that tx alone is 39% of the 50-tx mean wall time
(mean 115-150 ms vs median 27-60 ms).

## 3. Knobs

| knob | current value | where set | class | effect on small txs |
|---|---|---|---|---|
| `:index-config :branching-factor` | 128 | `seon.cluster.store/datahike-configuration` (creation-only; reopen adopts the stored value) | Seon-chosen Datahike config, creation-time: changing it needs a new store | leaf size 128 datoms; chosen on measurement (evidence.edn: 4096 gave 974 KB-4.3 MB per small commit; 128/32 gave 184 KB) |
| `:index-config :diff-buf-size` | 32 | same | same | buffered edits per branch before flush |
| `:fuse-index-roots?` | true | same, creation-only | Datahike config | inlines six root Branches into the commit record: the record is 26% of bytes written and 2.4 MB to decode |
| `:cache-revisions` in the commit record | 233 attribute revisions | `datahike.writing/commit!` (`:datahike.cache/attribute-revisions`) | code, not a config | share of the 32 KB not measured separately |
| `:keep-history?` | true | store creation | Datahike config | small txs touch no temporal index (0 temporal nodes in the replay); only retractions write temporal nodes |
| LZ4 compressor | `{:type :lz4}`, 64 KiB frame blocks | store `:config :compressor` in `datahike-configuration`; block size is a constant in `konserve.compressor` (`LZ4FrameOutputStream$BLOCKSIZE/SIZE_64KB`) | Seon config + konserve constant | applied to every value and its metadata, regardless of size |
| compression floor | **none in konserve.** The 1 KB (4,096 B inline / 1 KB compress floor) in AGENTS.md is `seon.config.blob/compress-min-bytes`, Seon's blob layer for result blobs, not index nodes or commit records | `seon.blob` | Seon dial (blob layer only) | n/a to index nodes |
| backend | `:file`, one file per key (`<sha512-uuid>.ksv`), atomic temp-file + rename | `:store :backend :file` | konserve | 20 file creates per commit; 75% of files waste most of a 4 KiB block |
| fsync policy | `:defer-immutable-sync? true`, `:sync-blob?` default true, `:lock-blob? true` | `datahike-configuration` | Seon config, konserve policy | see variants |
| node cache | `:store-cache-bytes` 512,000,000 (weigher estimate, about 2x over-weighs) | `:seon.config.db/store-cache-bytes` | Seon dial | read side |
| writer batching | `datahike.writer/create-thread`: transaction queue and commit queue (`DEFAULT_QUEUE_SIZE`), `:commit-wait-time`; applies transactions in a batch and commits once per batch | `:writer {:backend :self :write-fn-map ...}` sets no queue size or wait time, so defaults | Datahike config, unset in Seon | a lone caller waits for its own commit; concurrent callers on one connection share a commit. Branch connections each have their own writer, so concurrent agents do NOT share commits |

## 4. Experiments (same 60-tx replay, fresh branch from the same base each time)

| variant | wall ms p50 | alloc MB p50 | files | bytes p50 | verdict |
|---|---|---|---|---|---|
| base (deferred sync, LZ4) | 27-60 (4 runs) | 10.0 | 20 | 123 KB | reference |
| `:defer-immutable-sync? false` (per-write sync, the pre-September behaviour) | **177** | 9.8 | 20 | 123 KB | deferral is worth 3-6x; keep |
| `:sync-blob? false` (no fsync at all, unsafe) | **14.6** | 9.8 | 20 | 123 KB | floor of the remaining durability cost: about 12-45 ms of base is the two barriers; not adoptable |
| no compressor | 27-60 (runs: 60, 27; one noisy 39) | **5.4** | 20 | **225 KB** (1.8x) | time is a wash; allocation halves; disk doubles |

Notes: runs shared the machine with other lanes, so base and no-compress overlap and I cannot claim a time difference between
them. The allocation difference is robust (10.0 vs 5.4 MB, three runs): LZ4 costs about 4.6 MB of allocation per commit, i.e.
about 230 KB per file written, the write-side twin of `argon-konserve-read.md` (two `byte[65536]` per `LZ4FrameOutputStream`).
Compression is still worth keeping on disk: bytes created per commit nearly double without it.

Not run (time): a branching-factor/diff-buf sweep and a fresh-store rebuild. The existing sweep in `tmp/store-writes/evidence.edn`
(2026-09-28, 18k-datom base, about 22 datoms per commit) already shows [128, 0] at 340 KB and 48.8 files per commit versus the
current 128/32; I did not reproduce it on today's store. Not measured: removing `:cache-revisions` or the fused roots from the
record (a fork code change); the record size split.

## 5. Verdict

Seon's configuration is already tuned for small transactions where it counts: bf 128 + diff-buf 32 (chosen on a measured
sweep), deferred immutable sync (was 3-6x), LZ4, fused roots. A typical recording commit is 20 files / 123 KB / 10 MB
allocation / roughly 30-60 ms with two fsyncs; that is not a bottleneck next to the 100 ms recording budget on a quiet machine
but it is not free (a 12/min chain commits about 1.5 s per minute in write time alone).

What is NOT tuned, and where the gain is:

| rank | change | measured or estimated gain | cost | risk |
|---|---|---|---|---|
| 1 | **The bulk transaction path**: the 5,144-datom tx costs 4-13 s, 3,284 files, 4-8 GB allocated. Find the cause (leaf flush of many small leaves under diff-buf 32? per-datom index work?) and fix at the owner, or have the producer split/stage it as a blob | measured: 99% of the 50-tx mean wall time and 99% of its allocation is the 4 outlier txs | a profile run of that one tx on the clone (not done), then a fork or producer change | none on format; proportional-work fix |
| 2 | **Skip LZ4 frame buffers on small values** (uncompressed-block fast path, shared buffers; same fix as konserve-read) | measured 4.6 MB of 10 MB per-commit allocation; time effect not resolved by this noise | about 20 lines in `konserve.compressor`, no format change (reads stay compatible) | low |
| 3 | Commit record size: do not inline the cache-revisions/roots, or write them as separate keys | estimated: up to 26% of bytes per commit and the 2.4 MB decode; not measured separately | on-disk format change in `datahike.writing`; needs migration, a measured win, and interacts with the merge-base work | high |
| 4 | Fewer, larger files per commit (batch a commit's nodes into one key or a segment file) | estimated: 20 file creates and rename per commit, and 4 KiB-block waste (2.44 GB allocated vs 1.55 GB logical); unmeasured | new konserve backend or layout: a format change | high |
| 5 | bf / diff-buf retune | evidence.edn says 128/32 is near the optimum for this store; not re-measured today | new store (creation-only) | high; no |

Honest verdict: **no config tuning is worth doing now.** The measured wins are already taken. The one large measured cost is
the rare bulk transaction (rank 1), which is a defect hunt, not a tuning knob. Rank 2 is cheap and format-neutral and
duplicates work the konserve-read lane is already doing. Ranks 3-5 change the on-disk format and have no measured gain yet.

## 6. What I would measure to be sure

1. Profile the one 5,144-datom transaction alone (JFR CPU and file events) to name its dominant cost.
2. Repeat base vs no-compress vs no-sync on a quiet machine, 200+ txs each, interleaved, for a stable ms (my medians span 2x).
3. Split the 32 KB commit record: serialize it with and without `:cache-revisions` and the six fused roots.
4. The full set of the 6 temporal indexes under a retraction-heavy tx (updates), which my add-heavy sample under-weights.
5. Measure through Seon's writer (validator, transaction functions) rather than plain Datahike: this study excludes them, and the September note puts them at about 17-20 ms.
6. Concurrent writers on separate branches: the fsync queue contention that commit-floor-2026-09-26.md saw (3.8 to 10-18 ms per force).
7. Per-key-family file counts from decoding keys (I classified by size only; sidecar `[:datahike/commit-graph cid]` records were not yet in this store, 263 files of 100-200 B).
