---
title: What one small durable commit should cost
type: research
status: measured
date: 2026-10-09
lane: commit-cost-astra
---

# What one small durable commit should cost

2026-10-09, lane `commit-cost-astra`. Documentation only; no Seon or dependency source changed.

Datahike currently applies datoms to persistent indexes, writes changed nodes and a fused commit record, then replaces the branch head with a second copy of that record. Keep that composition initially: remove unnecessary Seon commits, remove the duplicate root payload, and share konserve's durability barriers across ready branch writers. A durable datom log is the next architectural option, not an already installed shortcut.

**Expected cost: approximately 4–6 ms for an uncontended small commit whose durable work is one append and one full drive-cache flush on this machine.** That is a measured 3.75–4.07 ms append/flush primitive plus transaction application, encoding and dispatch; it is a target inferred from measurements, not a measured Datahike implementation. The existing two-barrier file protocol has an approximately 8 ms barrier floor, before node work: its favorable small commits measured 10–14 ms. Seon's supplied 21–80 ms observations leave substantial amplification and queueing to remove. Sub-millisecond *amortized* durability is plausible under a group; sub-millisecond *individual acknowledgement latency* is not established.

The recommended next work is **small heads and store-wide group durability in the forks, after deleting avoidable Seon commits**, with the acknowledgement strictly after durability. Keep history and commit ancestry. Do not tune the entire store from an insert-only microbenchmark or substitute ordinary macOS `fsync` for a full device flush.

## 1. Scope, provenance and limits

- At entry, `bin/seon status` and MCP `runtime_status` both answered. Default ran archive `d384bb0c4c3271e00136fca8dce735fac2fc328b`, pid 55181, start 2026-10-08T15:49:51.181Z. Its health was **faulted**, with seven open error signatures, seven errored recordings, projection/AI performance warnings and cache warnings. These are a baseline, not failures caused or resolved here. No eval was submitted to default; no default store was opened by a measurement, and this lane never restarted it.
- All Clojure measurements used **separate, sequential raw JVMs**, `-Xmx1g`, and the absolute classpath from that committed archive's `.cpcache/3150042282.cp`. Application paths were rebased to the archive, never to the working tree. Scratch scripts and fresh stores were confined to `tmp/commit-cost-astra/`. This is dependency benchmarking, not a Seon armed-call/REPL-path proof. Scripts are preserved in the appendix inside this document.
- Dependency pins: Datahike `86c3301a00de8c49b2690083b2b1e69677bf0c8d`; konserve `e4334756413917305968333bf305e658c177765d`; persistent-sorted-set **0.4.137**, Maven artifact, source tag `e1a17bbe767c7801e67407c81f64efabfd2f1601`. The latter is not currently a Seon submodule: DH-2/3 propose starting that fork. Datahike and konserve are maintained forks and may be changed.
- Machine: Apple M5 Max, macOS 27.0.1 arm64, local SSD, OpenJDK 26.0.1. Host RAM reported 128 GiB; the experiment used a fixed 1 GiB heap and proposes no defaults derived from host RAM. Python 3.14.5, SQLite 3.50.4. Other lanes were active; no claim of exclusive disk access.
- Existing archive evidence corroborates the supplied node/root attribution: [store writes](../../2026-10-08/task-census/evidence/store-writes--evidence.edn); [commit floor](../../2026-10-08/task-census/evidence/commit-floor--report.md) distinguishes physical LZ4 bytes from older measurements. The supplied ~88 KB is not a format constant or a freshly remeasured current-default size. This lane did not inspect that production record. Different historical snapshots show 88,160-byte and ~21.7 KB records; compression, tree population, pivots and buffered values matter.
- Source/test changes in flight belonged to other lanes (initially `src/seon/test.clj` and `test/seon/test_reaching_test.clj`; later bootstrap, call preparation, context, SCI lookup and turn owners). None was edited, resumed or messaged. The measurement archive stayed fixed while checkout HEAD advanced.

The full-tree lint command `clj-kondo --lint src script test --cache false` completed in **8,407 ms, 0 errors, 1,462 warnings** (exit 2 for warnings). Those warnings are not a clean-warning result. No behavior test reaches this Markdown file. The requested commit gate names `seon.cluster.store-test/configuration-is-the-one-shape` as an existing smoke check of the configuration described here; no new behavior test is invented for prose. The lane commit reports the gate outcome separately from these research measurements.

## 2. What the current owners actually do

| Owner and seam | Inputs and guarantee | When the work occurs; cost |
|---|---|---|
| `seon.turn.batch/settle`, `seon.db/transact-call` | Recording/program facts; explicit connection and writer. Wait for the transaction result before answering. | Recording facts are already composed into one transaction per eval request; extra tests, effects and administration can still commit separately. |
| `datahike.writer/create-thread`, `datahike.writer/transact!` | Ordered transaction reports on **one connection**; durable callbacks follow `datahike.writing/commit!`. | Drain already queued reports, commit the last database once, give reports the batch's committed `:db-after` and commit id. Batch application remains ordered; report boundaries are not distinct stored snapshots. |
| `datahike.writing/db->stored`, `datahike.writing/commit!` | Immutable database, parents, known previous commit id. Children and commit record precede mutable head. GC guard covers unpublished reachable data. | Flush current indexes and, with history, temporal indexes; detach handles, inline roots, collect pending nodes. Write immutable commit record and the same full record at the branch key. |
| `datahike.index.persistent-set/create-storage`, `org.replikativ.persistent-sorted-set/store` | Dirty tree nodes; unchanged addresses can be reused. | Copy-on-write path nodes enter pending writes. AEVT scatters by attribute; AVET adds work only for indexed/ref/unique attributes. Cost follows distinct touched paths and serialized node content, not just datom count. |
| PSS Java `org.replikativ.persistent_sorted_set.Branch.store`, `org.replikativ.persistent-sorted-set.fressian/node->map` | Existing anchors, per-child changes, branching factor and diff-entry budget. | Buffer only content changes that fit; structural changes require writing. Clean old slots retain their budget; only dirty resident children are eviction candidates. A root contains separator **datoms**, child addresses and buffered changes, or all datoms when the root is a leaf. |
| `datahike.db.transaction/with-datom` | Assertion/retraction and existing current datom. | New assertions enter current indexes. Retractions with history retain the old assertion and negative datom in temporal indexes. History does **not** mean six equally rewritten trees for every append. |
| `konserve.impl.defaults/sync-plan`, `konserve.impl.defaults/update-blob` | Immutable marker, whether key existed, backend capabilities, sync configuration. | On opted-in local macOS filestore: a **new** immutable key gets data sync + rename; mutable/existing keys get data sync, directory full flush, rename, directory full flush. Rewriting an existing immutable key is not eligible for deferral. |
| `konserve.filestore/sync-base` and `PDataSyncBackingStore` methods | File/directory descriptors; failures propagate. | `FileChannel.force(true)` for directory barriers; `FileDescriptor.sync` for deferred data push. The former invokes macOS `F_FULLFSYNC`; ordinary `fsync` alone is cheaper and weaker for drive-cache loss. |
| `seon.cluster.store/datahike-configuration` | Creation configuration. | Already enables LZ4, `:defer-immutable-sync? true`, root fusion, history, branching factor 128, diff buffer 32. Reopen adopts creation-fixed settings. |

Pinned source: [Datahike writing](https://github.com/seantempesta/datahike/blob/86c3301a00de8c49b2690083b2b1e69677bf0c8d/src/datahike/writing.cljc), [writer](https://github.com/seantempesta/datahike/blob/86c3301a00de8c49b2690083b2b1e69677bf0c8d/src/datahike/writer.cljc), [konserve defaults](https://github.com/seantempesta/konserve/blob/e4334756413917305968333bf305e658c177765d/src/konserve/impl/defaults.cljc), [filestore](https://github.com/seantempesta/konserve/blob/e4334756413917305968333bf305e658c177765d/src/konserve/filestore.clj), [PSS Branch.store](https://github.com/replikativ/persistent-sorted-set/blob/e1a17bbe767c7801e67407c81f64efabfd2f1601/src-java/org/replikativ/persistent_sorted_set/Branch.java#L1849), [PSS buffering rationale](https://github.com/replikativ/persistent-sorted-set/blob/e1a17bbe767c7801e67407c81f64efabfd2f1601/doc/diff-buffering.md), [OpenJDK macOS force](https://github.com/openjdk/jdk/blob/master/src/java.base/macosx/native/libnio/ch/FileDispatcherImpl.c).

Upstream check: Datahike's locally fetched upstream revision `23692d7730f324d35fbebe687641ae40e431f9f5` also drains the commit queue and has `commit-wait-time`; batching is not a newly proposed Seon scheduler. Konserve upstream revision `485c23353ec3b443272cab36fc37390e20d9ce4f` lacks the fork's `sync-plan`/deferred-sync implementation. These are inspected revisions, not claims about the latest upstream head.

Important existing-policy distinctions:

1. `:sync?` selects blocking versus asynchronous API execution. **It is not a durability-off switch.** `:sync-blob? false` is the nondurable diagnostic arm below.
2. `:commit-wait-time` sleeps **after** a commit. Default is 0. Increasing it adds serial latency and only helps the same connection's subsequent queue; it does not combine independent branch writers. The archived 20 ms experiment increased sequential latency from 11.6 to 35.7 ms.
3. `konserve.core/append` is a linked key/value log: new entry, prior entry's next link, then head. It is **not** a one-file append/one-flush WAL on this filestore.
4. `:commit-graph? false` removes provenance/ancestry and commit-id branching; it is not an acceptable way to obtain a one-write Seon commit. History-off independently removes historical query behavior.
5. Root fusion saves separate root files, but serializes *unchanged* roots in both full records too. Turning fusion off may trade bytes for files and cold reads. Neither changing fusion nor index creation settings is a live reset-free tuning knob in Seon's current configuration.

## 3. Measurements

All latency units below are milliseconds. Harness p50 is the upper middle sample; the replacement-only and Python tables use the mean of the two middle samples. p95 is the sample at `floor(0.95*n)`. These are finite observations, not latency guarantees. New-file bytes mean retained new objects, excluding the overwritten branch head; add the head write for total bytes written. Filesystem allocation rounding is also excluded unless stated. Collection was not run during a sample.

The assignment supplied these `settle-cost` observations on default at `d384bb0c4`, 2026-10-09. They are external baseline evidence, not measurements rerun on default by this lane:

| Supplied operation | Observed cost |
|---|---:|
| Eval recording, about 28 datoms | 21–80 ms; about 93% waiting for Datahike/konserve durable commit |
| Raw durable transaction, 17 datoms | 19–25 ms |
| Raw empty tx-data | 12–13 ms |
| One konserve branch-head write | 8–10 ms |
| 20 new immutable konserve keys | 5.6 ms total |
| Same in-memory `d/with` | 0.12 ms |

The supplied 30–41 node files and ~88 KB fused-root record describe a much broader store than the favorable append fixture. The archived source evidence and the replacement/scatter fixtures below explain that difference; no speedup ratio is claimed between different workloads.

### 3.1 Durability primitive and SQLite control

Five warmups, 60 samples per row. Existing append file; no per-transaction creation/rename. Each group appends all its records and waits for one `F_FULLFSYNC`. This is a **primitive experiment**, not a recovery-capable log implementation. The SQLite control has `journal_mode=WAL`, `synchronous=FULL`, explicit `fullfsync`, and automatic checkpointing disabled during measurement; it measures warm commit, not checkpoint/recovery cost.

| Operation | p50 | p95 | Interpretation |
|---|---:|---:|---|
| Append 512 B + full flush | 3.75 | 4.20 | Smallest measured durable append arm |
| Append 4 KiB + full flush | 4.07 | 4.31 | Practical small-record barrier floor |
| Append 16 KiB + full flush | 4.02 | 4.29 | Similar floor |
| Append 88 KiB + full flush | 4.06 | 5.29 | Bytes alone do not explain 20–80 ms |
| 8 × 4 KiB, one full flush | 4.15 total | 5.13 | 0.52 ms amortized per record; each still waits for group |
| 32 × 4 KiB, one full flush | 4.14 total | 5.19 | 0.13 ms amortized per record |
| Append 4 KiB + ordinary `fsync` | 0.038 | 0.057 | Not the same drive-cache durability guarantee |
| SQLite: 1 KiB row, FULL, fullfsync ON | 4.13 | 5.16 | Independent durable database control |
| SQLite: same, fullfsync OFF | 0.075 | 0.115 | Do not advertise this as equivalent durability |
| SQLite: 32 × 1 KiB rows, fullfsync ON | 4.21 total | 5.89 | One SQL transaction, not 32 separately acknowledged transactions |

The single-flush target is therefore `apply + encode(delta) + append(delta) + full-flush`, approximately 4–6 ms here for tens of small scalar datoms and a ready local log. Index maintenance/checkpointing is amortized later, not made free. A bounded group adds admission wait but divides flush work. The current file protocol requires **two** ordered barriers; replacing two with one is a protocol/recovery change, not simply deleting a force call.

### 3.2 Datom count: favorable insert-only shape

Fresh store per configuration; 10,000 entities / 30,000 user datoms seeded, then four warmups and 20 measured commits per input size, in ascending order. New entities receive one unindexed scalar attribute. Thus these rows understate Seon's multi-attribute scatter. With history, empty tx-data still adds the transaction instant; 17 input assertions produce 18 report datoms. With history off, that instant is absent. `d/with` neither writes nor proves durability.

| Input assertions | Current 128/32 p50 / p95 | No-sync p50 / p95 | Per-key full sync p50 / p95 | New files / commit, current |
|---:|---:|---:|---:|---:|
| 0 | 10.84 / 15.96 | 1.39 / 1.92 | 19.06 / 30.37 | 1.00 |
| 1 | 11.07 / 13.03 | 1.99 / 2.81 | 19.38 / 42.86 | 1.75 |
| 17 | 12.02 / 14.08 | 2.47 / 3.34 | 39.09 / 50.63 | 4.05 |
| 28 | 11.95 / 13.99 | 2.95 / 3.97 | 49.82 / 61.92 | 5.35 |
| 100 | 13.91 / 15.14 | 4.09 / 4.88 | 61.05 / 84.25 | 8.20 |
| 1,000 | 28.36 / 31.95 | 18.65 / 19.57 | 214.95 / 262.30 | 37.00 |

The no-sync arm is diagnostic only. Current deferral is already a large improvement over per-key sync. The remaining ~9 ms difference on tiny writes is consistent with the two full barriers and scheduling, not 17 datoms' in-memory insertion. On these stores a warm 17-assertion `d/with` was about 0.07 ms (the supplied production-shaped probe was 0.12 ms).

### 3.3 Index settings: the same 17-assertion insert workload

| Branching / buffer | p50 / p95 | New files | New bytes per commit |
|---|---:|---:|---:|
| 128 / 32, history | 12.02 / 14.08 | 4.05 | 7,365 |
| 128 / 32, no history | 11.96 / 27.10 | 3.60 | 6,004 |
| 32 / 8 | 13.17 / 19.92 | 9.75 | 8,060 |
| 512 / 32 | 11.96 / 13.23 | 2.30 | 13,825 |
| 4,096 / 256 | 10.01 / 13.13 | 1.15 | 9,250 |
| 128 / 128 | 10.12 / 12.07 | 2.65 | 5,223 |
| 128 / 0 | 13.01 / 17.71 | 7.85 | 13,063 |
| 128 / 32, unfused | 12.03 / 13.91 | 6.05 | 7,686 |

History off buys little for pure insertion because temporal indexes chiefly retain replaced/retracted data. A bigger branching factor can win this tiny append workload while doing much worse with large values and retained history. The table does not justify changing the production creation config.

### 3.4 Replacements, history, large values and root size

10,000 entities, indexed string values. Each transaction replaces 14 scattered values. The public report contains 15 datoms (14 effective additions plus the transaction instant); the index/history path also removes the prior current values. Fifty warmup transactions, then 60 measured, 110 total. Payload is deterministic Base64 of 12 or 512 seeded random bytes. Root sizes are measured by **the installed Fressian handlers and LZ4 compressor**, after the final transaction, not by printed EDN. They exclude konserve's outer file header/metadata.

| Configuration | p50 / p95 | Full record, raw / LZ4 bytes |
|---|---:|---:|
| Short values, 128/32, history | 35.21 / 43.95 | 14,340 / 6,932 |
| Long values, 128/32, history | 36.42 / 46.83 | 179,582 / 112,400 |
| Long values, 128/32, no history | 27.80 / 30.99 | 35,402 / 32,381 |
| Long values, 128/128, history | 26.67 / 30.80 | 500,723 / 349,329 |
| Long values, 512/32, history | 25.94 / 35.21 | 264,360 / 181,457 |
| Long values, 4,096/256, history | 33.75 / 39.90 | 6,924,528 / 3,722,982 |

**Why a root record can be ~88 KB:** `datahike.writing/db->stored` embeds up to six complete root nodes. `org.replikativ.persistent-sorted-set.fressian/node->map` includes each branch's separator keys, addresses and slots. A separator is a full datom, including its value. A leaf root includes its entire leaf. The diff budget counts entries, not their encoded bytes. The record also has root descriptors, config, metadata and a schema-meta *reference*; it does not normally copy the entire schema population each commit.

Measured long-value 128/32 example: config serialized to 500 raw bytes (minor revision/path-length differences are expected); the three temporal roots each occupied ~57.4 KB raw, versus current roots 1.6–2.9 KB. A temporal AEVT root had 32 separator keys (~22.5 KB raw), 32 buffered changes (~34.2 KB slots) and only 610 bytes of child addresses. In the 4,096/256 case the temporal roots remained **leaves**, each ~2.16 MB raw. Therefore neither “six pointers” nor “schema overhead” explains the root. Independent field compression sizes are not additive because whole-record compression can share patterns.

The preserved production evidence gives the other common case: broad root fan-out and large separator datoms even when the transaction itself is small. Fusion re-embeds unchanged temporal roots and the whole record is encoded/written twice. A compact head pointing at the immutable commit record removes one copy, but does not by itself shrink that immutable record.

### 3.5 Multi-attribute scatter and allocation

Fresh stores with 2,000 entities × 32 indexed scalar attributes. Each transaction replaces values on 14 different entity/attribute pairs. Fifty warmups, 40 measured transactions. This is deliberately closer to recording's AEVT scatter, while still not a replay of production. Allocation is JVM-wide thread allocation in this otherwise single-workload JVM, including asynchronous writer threads; no Seon profiling wrapper was loaded.

| Branching / buffer | p50 / p95 ms | New files / commit | New bytes / commit | Final head bytes | Median allocated MB / commit |
|---|---:|---:|---:|---:|---:|
| 128 / 32 | 50.23 / 63.03 | 70.43 | 115,026 | 7,170 | 37.33 |
| 128 / 128 | 37.03 / 51.99 | 41.88 | 116,660 | 12,246 | 31.87 |
| 32 / 8 | 90.01 / 98.95 | 127.53 | 99,113 | 2,865 | 52.43 |
| 512 / 32 | 35.93 / 40.11 | 45.25 | 136,253 | 20,441 | 38.39 |
| 4,096 / 256 | 27.10 / 242.65 | 3.98 | 95,515 | 58,764 | 31.47 |

Releasing and reconnecting each store gave **64,220 current datoms, 66,740 history datoms, and last test value 20,039** in every configuration. This checks ordinary reopen/read equivalence for the finite workload, not sudden-power-loss recovery. The scatter JVM took 51.56 s including load, seed, all five cases and reopen scans; maximum RSS was 1,176,371,200 bytes, peak memory footprint 1,155,009,488 bytes. Heap was capped at 1 GiB. This is the experiment total, not one ordinary operation. Bulk seed cost is proportional to tens of thousands of datoms (the 4,096 arm was 1,370.63 ms); ordinary sampled commits were under one second, though the p95 regression is material and its cause was not isolated.

This independently reproduces DH-8/W-2's large allocation-to-written-byte ratio. It does not independently identify each allocation stack: retain the existing JFR attribution to Fressian handler lookup/encoding and konserve LZ4 buffers. It also refutes “make leaves smaller” as a blanket improvement: 32-way trees write less payload but many more files, allocate more and take longer.

### 3.6 Batching versus independent branches

Each item adds 17 scalar datoms. Twelve bursts per row, first two omitted from summaries. Same-connection rows enqueue all asynchronous transactions before awaiting them. Separate-branch rows use independently connected Datahike branches of the same store. Counts are **distinct durable commit ids**, not inferred syscall counts.

| Concurrent items | Same connection: p50 / p95, commits | Separate branches: p50 / p95, commits |
|---:|---|---|
| 1 | 12.21 / 13.18, 1 | 11.32 / 12.32, 1 |
| 8 | 22.39 / 26.40, 2 | 30.37 / 43.28, 8 |
| 32 | 24.50 / 37.46, 2 | 156.21 / 187.24, 32 |

A `FileChannel.force(true)` append control after each burst had medians 3.48–3.74 ms. Current same-connection batching therefore works; the missing composition is **one durability group spanning distinct branch writers**, without coalescing their logical histories. Filesystem/device flushes may overlap or coalesce internally: 32 branches do not imply measured latency must equal exactly 64 × one-flush time.

The first run was noisier: one-item p50 242/198 ms, eight-item 316/447 ms, 32-item 31/170 ms (same/separate). It lacked the flush control, so it cannot establish the cause. The repeated run above adds the control and is the basis for comparison. This is not a claim that contention or tails disappear.

## 4. Comparisons: mechanisms, not portable benchmark numbers

| System | Relevant mechanism and lesson |
|---|---|
| Datomic | Writes transaction log blocks durably before success; sorted indexes are maintained in background and combined with a memory index. Its documentation explicitly describes batching under load giving one or fewer average writes per transaction. Borrow the separation of acknowledgement from checkpointing; do not assume a Datomic transaction is literally one POSIX append or transfer its deployment latency to this SSD. [ACID](https://docs.datomic.com/transactions/acid.html), [background indexing](https://docs.datomic.com/indexes/background-indexing.html), [batch log timing metric](https://docs.datomic.com/operation/monitoring.html). |
| XTDB | A totally ordered transaction log is separate from indexed/object storage; current documentation distinguishes local and remote logs and, in v2.2, source/replica logs. Submission, durable log acceptance and query/index visibility are distinct boundaries. A Seon adaptation must still make the next evaluation see its predecessor's committed effects. No XTDB latency benchmark or particular group-flush guarantee was measured here. [Log](https://docs.xtdb.com/ops/config/log.html). |
| Datalevin / LMDB | LMDB serializes writes and commits changed pages/metadata; it is not intrinsically a datom WAL. Datalevin's async APIs batch and resolve futures after durability. Its optional WAL has strict/relaxed/extra profiles; the published macOS full-durability choice is **extra**, whereas relaxed may acknowledge before sync. Its WAL opt-in is currently relaxed by default, so adopting a flag blindly would violate Seon's contract. [Transactions](https://github.com/datalevin/datalevin/blob/master/doc/transact.md), [WAL](https://github.com/datalevin/datalevin/blob/master/doc/wal.md), [LMDB flags and sync contract](https://github.com/LMDB/lmdb/blob/mdb.master/libraries/liblmdb/lmdb.h). |
| SQLite WAL | Appends page frames and a commit marker, syncs on FULL commits, checkpoints separately. It logs pages, not datoms. On macOS compare with `fullfsync=ON`; the otherwise impressive ~0.075 ms result has different drive-cache guarantees. Checkpoint and log-retention costs remain. [WAL](https://www.sqlite.org/wal.html), [fullfsync](https://www.sqlite.org/pragma.html#pragma_fullfsync). |
| Datahike / PSS | Existing diff buffering is a checkpoint representation that embeds changes against unchanged anchors, not a WAL. Root fusion + buffers can reduce object PUTs; the upstream SaaS example's one-PUT result additionally disables commit ancestry, which Seon needs. [PSS buffering](https://github.com/replikativ/persistent-sorted-set/blob/e1a17bbe767c7801e67407c81f64efabfd2f1601/doc/diff-buffering.md), [upstream example and sacrifices](https://github.com/replikativ/datahike-saas-starter). |
| konserve / existing backend | `konserve.core/multi-assoc` already accepts ordered pairs; atomicity depends on the backend. `konserve-lmdb.store` implements atomic multi-key writes, making it a smaller storage experiment than writing a new packing engine. It is **not on the measured Seon classpath**; codec compatibility, platform durability and GC/branch proofs remain. LMDB packing alone still persists index nodes, not just datoms. [API](https://github.com/replikativ/konserve), [LMDB adapter](https://github.com/replikativ/konserve-lmdb), [adapter source](https://github.com/replikativ/konserve-lmdb/blob/main/src/konserve_lmdb/store.clj). |

## 5. Owners of the gap

1. **Seon request/recording owners:** unnecessary independent commits multiply every lower-level cost. CO-2 specifically makes a check answered from recorded results write nothing; it is not the implementation of one recording commit per eval. TX-1 removes needless hosting/empty commits; TX-5 combines seeding; the other TX rows reduce administrative writes and repeated provenance. `seon.turn.batch/settle` already combines recording/program facts. Do not delete a required evaluation record or blindly skip every empty Datahike transaction (tx instant/metadata can be intentional).
2. **Datahike writing and root readers:** duplicate full record, all fused roots, pending-node publication and per-connection durability boundary. Existing task `seon.cluster.store/each-commit-serializes-its-root-record-twice`. A new head representation touches connector refresh/connect, versioning, branch-from-commit, history/merge, GC and store validation. A tiny edit only in `commit!` is insufficient. Historic commit roots cannot be recovered from today's mutable head.
3. **PSS and Datahike index bridge:** copy-on-write paths, per-branch entry-count buffering and full datom separators. Existing `seon.cluster.store/a-small-commit-rewrites-thirty-index-nodes` and `seon.cluster.store/an-ordinary-commit-writes-26-files-and-136-kb`; SR-GROW. DH-1..3 concern subtree-count correctness/range reads, not the main durability barrier; retain their proofs when modifying nodes. DH-4..7 are cache/planner work, not a substitute for reducing persisted data.
4. **Konserve file persistence:** full barrier coordination, serialization/compression, one-file-per-key and APFS allocation floor. The historical per-node-full-force task has already been addressed for **new immutable keys** by the installed deferral. Remaining barriers per head and their cross-branch duplication are the current problem; do not re-propose the already installed fix.
5. **Konserve/Fressian plus Datahike serialization:** DH-8/W-2. Excess transient allocation raises CPU/GC/tails and competes across agents. It is separate from durable bytes and flush count; reducing one does not prove the other fixed.

## 6. Ranked options and guarantees

Effort estimates below include integration/recovery proof, not just lines changed. They are planning estimates; stop each bounded slice when evidence contradicts the design.

### Option 1 — remove avoidable commits first; about 0.5–2 hours for one chosen Seon slice

Apply existing CO-2/TX cards at their owners and retain one recording transaction per evaluated request. Delete redundant provenance and writes only where the consumers no longer need them. No new scheduler, store format or weaker acknowledgement.

- **Guarantee:** required facts and effects are durable before acknowledgement; a truly reused test result creates no new fact needing durability.
- **Cost/win:** avoids roughly one whole current commit per removed transaction; no improvement to the indispensable commit's 10–80 ms latency. Existing proof names include `seon.test-test/a-request-answered-from-its-record-writes-nothing` and the TX card tests (targets in the ledger, not asserted installed/green by this lane).
- **Sacrifice:** cannot reach the ~4 ms append floor; no queryability or history sacrifice. Do not pack all eval facts into an opaque map simply to avoid AEVT: that moves a storage problem into the public data model.

### Option 2 — retain the index model, shrink duplicate roots and share durable flushes — **recommended**

Start with the full-root duplication task, then introduce store-wide durability groups in konserve. Estimate **1–3 hours** for serialization reuse without a format change, **3–6 hours** for compact heads with reader/GC compatibility, **4–8 hours** for bounded cross-branch grouping and failure tests. These can land independently; the combined end-to-end result is not measured yet.

- **Guarantee:** same transaction validation, branch order, history and commit ancestry. Every successful callback waits for the barrier covering its own head and referenced immutable data. No multi-branch atomicity is promised.
- **Cost/win:** reuse encoded root bytes first if the existing serializer/backend seam permits it; this saves CPU but still writes both copies. A compact branch head that references the immutable commit record removes the second large payload but adds a cold read on head resolution. For N ready branches, share **two ordered barriers per group** under today's file protocol, not two per branch. A 4–6 ms *serial* target remains out of reach until the acknowledgement protocol needs only one barrier; a file-group barrier contribution is approximately 8/N ms plus staging/queueing.
- **Sacrifice:** slightly more coordination and potentially bounded admission wait; cold head resolution may need one more read. The group must have declared time, item and byte bounds and release ownership only after actual completion. Initial defaults should be conservative and measured on the 16 GB target: a zero idle delay/adaptive ready-group approach is preferable to an unconditional sleep. Numbers in this report are experimental parameters, not new hardcoded policies.

Safe group composition, to implement inside the existing konserve owner: stage each participating branch's new immutable keys and temporary head; complete each data push; freeze the ready group; full barrier; rename its heads; full barrier; acknowledge exactly those participants. Keep Datahike's GC guards until their heads are covered. New arrivals join a later group. A failed flush cannot deliver success; a failed rename or later barrier must preserve/identify old-or-new state and report uncertainty rather than pretend rollback. A faster in-memory `:applied` result is not the durable completion signal.

A smaller root can also come from selective fusion (leave unchanged/oversized roots as referenced immutable nodes) or compact separators. Both require read/GC/content-hash proofs. Try the existing `:fuse-index-roots? false` option on representative data first; the favorable insert experiment showed no latency gain. Do not increase branching/buffer sizes globally based on one shape: the 3.7 MB root case is a counterexample. PSS buffers or a recent-datom tail may help, but the latter is an index-format design, not a renamed config setting. A previously tried stale-slot eviction policy saved only ~10% of PUTs at 128/32 while increasing bytes ~4%; [preserved evidence](../../2026-10-08/task-census/evidence/store-writes--evidence.edn) already ranks it poorly.

Before building a new packing backend, probe the existing konserve LMDB multi-key adapter with this exact workload and equivalent macOS full durability. It can eliminate per-node files without replacing Datahike, but no installation or compatibility claim is made here.

### Option 3 — durable datom log plus checkpointed indexes; roughly 3–5 days, explicit fork design first

Change Datahike's durability representation: validate/apply once, persist the **resolved transaction delta** and essential branch/parent/schema/blob references once through a proven log backend, wait for the group watermark, and checkpoint indexes separately. Use the maintained forks and an existing durable backend, not a Seon-owned parallel log/store.

- **Guarantee:** the acknowledged prefix is recoverable, queries see the committed in-memory database immediately, and restart reconstructs that same value from checkpoint plus durable deltas. Replay resolved datoms and metadata, **never arbitrary transaction functions or external effects**. Branch lineage, commit identity, schema transitions and blob reachability must survive too.
- **Cost/win:** the path that can approach the measured **4–6 ms serial** floor and ~0.13–0.52 ms amortized flush work at 32/8 participants. Cost becomes O(delta bytes) for foreground durability, plus amortized index work. It does not erase query, validation, indexing or compression costs.
- **Sacrifice:** recovery replay, bounded tail memory, checkpoint work, format compatibility and log retention become explicit dependency responsibilities. Branch/merge/as-of and GC proofs are substantial. The current root-derived commit identity and `stored->db` cannot simply be left unchanged. Prove a maintained backend seam before coding; `konserve.core/append` on the filestore is not sufficient.

Never choose as a “performance fix”: sync off, early acknowledgement followed by async flushing, history off, commit ancestry off, raising unbounded buffers, or default-store resets to make the benchmark small. History-off and no-sync measurements here quantify sacrifices, not authorize them.

## 7. Approximately fifteen-minute fork slices and proofs

These are bounded implementation/research cards for root to assign after review, not claims that a whole storage redesign takes fifteen minutes. Proposed tests below describe required behaviors; only explicitly named existing tests are claimed to exist. Each code slice must tighten/declare its producer contract, use the fork's test task, and convert every reader/caller it affects before landing. Multi-file representation changes may need a larger coordinated slice; stop and return the exact boundary rather than leave a half-supported format.

| Slice | Exclusive owner / bounded action | Proof and stop boundary |
|---|---|---|
| A, 15 min | Datahike writing tests: capture a small commit's pending objects, both records and serialized sizes through existing storage hooks. | Establish root encode/write count and per-index path counts for append, scattered update, no-op and large value. Preserve `datahike.test.store-test/test-diff-buf-upsert-reopen` and `test-detached-root-count-after-reopen`. No production optimization yet. |
| B, 15 min | Konserve serializer: profile one fixed root's repeated encoding; establish whether prepared encoded payload can be shared by two writes without sharing key metadata. | Identical decoded values and correct independent key metadata; count one value encoding; old file decoding unchanged. If no library seam supports prepared bytes, report the exact gap instead of a Seon wrapper. |
| C, 15 min | Datahike connector/versioning/GC reader census: enumerate all branch-head consumers and specify backward-compatible full-record versus compact-reference decoding. | Test old head, proposed compact head, missing record, branch-from-commit and retained-history reads. Do not enable compact writes until every reader and collector consumes them. This slice is a design/test inventory, not a format landing. |
| D, 15 min each after C | Datahike compact-head implementation split only along loadable compatibility stages: readers first, writer last. | Reopen, branch, merge, history, GC marking, secondary-index roots, commit-graph policy; inject failure before/after head publication. Every published head resolves to a complete commit closure. Keep old-format stores readable. |
| E, 15 min | Konserve filestore tests: establish the two-barrier group trace with injected backing I/O and two independent head writers. | Pause before each barrier and assert **no** durable callback; fail data sync/rename/each barrier and assert no false success. Existing `sync-plan` cases remain covered. No default-store test. |
| F, 15 min per bounded stage | Konserve store lifecycle/grouping owner: admit ready staged writes to one bounded group, then share the existing two barriers. | Per-branch order, late arrival goes to next group, one failure does not leave waiters silent, shutdown awaits actual exit; barrier count is 2 per group. Crash-process reopen is additional evidence, not a substitute for modeled torn-write/power-loss tests. Keep GC permits through durable completion. |
| G, 15 min | Datahike writer integration test only, after F. | Two connections to two branches both wait for the shared barrier, get their own committed branch/database, and preserve failure delivery. Re-run the 1/8/32 measurement and report latency **and** count/allocation/bytes. No Seon group scheduler. |
| H, 15 min | PSS research only: quantify serialized pivots, slot bytes and write count under existing 128/32, 128/128, 512/32 and unfused configurations. | Same finite query/reopen data across settings; long-value and saturated-history counterexamples included. DH-1..3 count laws remain true or their existing defects remain explicit. Stop before a new tail/separator representation without its design. |
| I, 15 min | Konserve backend compatibility probe: existing LMDB adapter + Datahike `multi-assoc` path, disposable store. | Verify runtime APIs/codecs, full durability behavior, one atomic storage transaction for ordered nodes+record+head, release/reopen and GC. If absent/incompatible, name the exact dependency gap. Do not add a new backend from scratch. |
| J, 15 min | Log/checkpoint research, only if option 3 is selected: one resolved transaction round-trip through the chosen dependency log. | Checkpoint + delta equals the original DB, explicit tx ids/schema/branch parents preserved; truncated tail excluded, acknowledged prefix retained. Stop before changing Datahike commit identity or recovery policy without review. |

Dials belong at owners and are validated at admission: branching factor, entry/byte buffer bounds, selective-fusion threshold if introduced, group max items/bytes/wait, outstanding bytes, checkpoint/log segment/retention thresholds and allocation budgets. Seon's current 128/32 literals are creation policies in `seon.cluster.store/datahike-configuration`; the “every size is a dial” rule is a reason to expose validated creation configuration, not permission to silently change existing stores. A datom-count buffer cannot promise a byte bound for arbitrary string values.

## 8. Landing and remaining evidence

- This note changes zero production/source/test lines. The benchmark code is preserved as documentation below, not added to runtime/test discovery.
- The reproduced gap is in Datahike/PSS node/root persistence, konserve barriers/serialization and Seon's number of commits. DH-8/W-2 and the existing store tasks cover the observed defect classes; no issue index or task resource was edited by this research lane.
- Measured implementations: current forks, existing configurations, same-connection batching, separate branches, raw append/full sync and SQLite WAL. **Not implemented/measured:** compact head, selective fusion, cross-branch grouped filestore, new tail node, LMDB integration or datom WAL. Their costs are projections.
- No power-cut durability certification, production replay, filesystem syscall census or complete Seon end-to-end before/after is claimed. Proposed fork changes require those proofs before adoption. No extrapolation from `d/with` or no-sync timing to durable acknowledgement.
- Independent full-tree lint: 0 errors, 1,462 warnings, 8.407 s. All benchmark JVMs exited; 30 owned store directories and append/SQLite files were removed (about 2.38 GiB allocated scratch space before cleanup). Probe sources and JSON evidence remain in the owned scratch directory, with scripts and measured summaries preserved here.
- The first candidate check passed `seon.cluster.store-test/configuration-is-the-one-shape`: 9 assertions, 0 failures/errors, 1.365 s test execution; 67.063 s total, including 53.724 s to readiness. Publication took 321 ms, adoption 4 ms, with zero changed identities. This is a configuration smoke proof, not a sub-second boot/test claim. The commit guard then refused because this lane corrected the report during the check; no bytes were committed. The gate is rerun on the finished report, with its final result reported alongside the commit. No pre-existing red appeared in the named test; broader tests were not run here.

## Appendix: reproducible measurement scripts

The following blocks preserve the exact successful probe sources. They are standalone research programs, not admitted Seon functions or behavior tests. They create only new lane-owned stores. Extract each block to the stated scratch filename; run once into an empty owned scratch directory, because reusing a store would change the workload. The shared library block is loaded only into each throwaway JVM. JSON output is raw research evidence, not a new product printer.

Archive/classpath preparation used:

```python
from pathlib import Path
archive = Path('/Users/sean/src/seon/data/source/d384bb0c4c3271e00136fca8dce735fac2fc328b')
cp = (archive / '.cpcache/3150042282.cp').read_text()
Path('tmp/commit-cost-astra/classpath').write_text(':'.join(
    str(archive / x) if not x.startswith('/') else x for x in cp.split(':')))
```

For each Clojure script, the command was (one JVM at a time):

```sh
java -Xmx1g --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -cp "$(cat tmp/commit-cost-astra/classpath)" clojure.main \
  tmp/commit-cost-astra/bench.clj > tmp/commit-cost-astra/bench.jsonl \
  2> tmp/commit-cost-astra/bench.stderr
```

Replace `bench` with `update`, `groups`, `groups-repeat`, or `scatter`. The scatter run was prefixed with `/usr/bin/time -l`; Python was `python3 tmp/commit-cost-astra/flush.py`. The classpath binds the dependency pins and source, while the measurement source is explicitly the scratch script. No Seon boot or default connection is involved.

### bench.clj

```clojure
(require '[datahike.api :as d] '[konserve.core :as k]
         '[konserve.impl.defaults :as kd] '[clojure.java.io :as io]
         '[clojure.data.json :as json])
(def home "/Users/sean/src/seon/tmp/commit-cost-astra")
(defn emit [x] (println (json/write-str x)) (flush))
(defn timed [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))
(defn summary [xs] (let [s (vec (sort xs)) n (count s)]
  {:n n :p50 (nth s (quot n 2)) :p95 (nth s (min (dec n) (int (* n 0.95)))) :min (first s) :max (peek s)}))
(defn files [path] (into {} (for [f (file-seq (io/file path)) :when (.isFile f)] [(.getName f) (.length f)])))
(def schema [{:db/ident :p/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             {:db/ident :p/v :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/index true}
             {:db/ident :p/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
(defn tx [n offset] (mapv (fn [j] [:db/add (+ 1000000 offset j) :p/n (+ offset j)]) (range n)))
(defn run-case [tag bf buf hist sync defer fuse]
 (let [path (str home "/store-" tag)
       config {:store {:backend :file :path path :id (random-uuid) :config {:sync-blob? sync :defer-immutable-sync? defer :compressor {:type :lz4}}}
               :schema-flexibility :write :keep-history? hist :fuse-index-roots? fuse
               :index-config {:branching-factor bf :diff-buf-size buf} :writer {:backend :self :commit-wait-time 0}}]
  (d/create-database config)
  (let [c (d/connect config)]
   (try
    @(d/transact! c schema)
    (let [[ms _] (timed #(deref (d/transact! c (mapv (fn [i] {:p/id i :p/v (str "value-" (mod (* i 7919) 100003)) :p/n i}) (range 10000)))))] (emit {:case tag :seed-ms ms}))
    (doseq [n [0 1 17 28 100 1000]]
     (dotimes [j 4] @(d/transact! c (tx n (+ (* n 10000) (* j 1100)))))
     (let [before (files path)
           rows (mapv (fn [j] (let [[ms r] (timed #(deref (d/transact! c (tx n (+ (* n 100000) (* j 1100))))))] {:ms ms :datoms (count (:tx-data r))})) (range 20))
           after (files path) fresh (apply dissoc after (keys before))]
       (emit {:case tag :input-datoms n :actual-datoms (:datoms (first rows)) :latency (summary (map :ms rows)) :new-files-per-tx (/ (count fresh) 20.0) :new-bytes-per-tx (/ (reduce + (vals fresh)) 20.0) :samples (mapv :ms rows)})))
    (let [store (:store @c) branch (get-in @c [:config :branch]) record (k/get store branch nil {:sync? true})]
      (emit {:case tag :root-file-bytes (.length (io/file path (kd/key->store-key branch))) :record-keys (mapv str (keys record)) :root-classes (into {} (for [[key v] record :when (.endsWith (name key) "root")] [(name key) (.getName (class v))]))}))
    (let [before (System/nanoTime)
          ps (mapv #(d/transact! c (tx 17 (+ 700000000 (* % 100)))) (range 32))
          rs (mapv deref ps)]
      (emit {:case tag :burst-32-ms (/ (- (System/nanoTime) before) 1e6) :durable-commits (count (set (map #(get-in % [:tx-meta :db/commitId]) rs)))}))
    (emit {:case tag :with (summary (mapv (fn [_] (first (timed #(d/with @c (tx 17 900000000))))) (range 30)))})
    (finally (d/release c))))))
(doseq [args [["base" 128 32 true true true true]
              ["no-history" 128 32 false true true true]
              ["bf32" 32 8 true true true true]
              ["bf512" 512 32 true true true true]
              ["bf4096" 4096 256 true true true true]
              ["buf128" 128 128 true true true true]
              ["buf0" 128 0 true true true true]
              ["no-fusion" 128 32 true true true false]
              ["no-sync" 128 32 true false false true]
              ["per-key-sync" 128 32 true true false true]]]
 (apply run-case args))
(shutdown-agents)
```

### probe-lib.clj

```clojure
(require '[datahike.api :as d] '[konserve.core :as k]
         '[konserve.impl.defaults :as kd] '[clojure.java.io :as io]
         '[clojure.data.json :as json])
(def home "/Users/sean/src/seon/tmp/commit-cost-astra")
(defn emit [x] (println (json/write-str x)) (flush))
(defn timed [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))
(defn summary [xs] (let [s (vec (sort xs)) n (count s)]
  {:n n :p50 (nth s (quot n 2)) :p95 (nth s (min (dec n) (int (* n 0.95)))) :min (first s) :max (peek s)}))
(defn files [path] (into {} (for [f (file-seq (io/file path)) :when (.isFile f)] [(.getName f) (.length f)])))
(def schema [{:db/ident :p/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
             {:db/ident :p/v :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/index true}
             {:db/ident :p/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
(defn tx [n offset] (mapv (fn [j] [:db/add (+ 1000000 offset j) :p/n (+ offset j)]) (range n)))
```

### update.clj

```clojure
(load-file "/Users/sean/src/seon/tmp/commit-cost-astra/probe-lib.clj")
(require '[konserve.protocols :as kp])
(import '[java.io ByteArrayOutputStream] '[java.util Base64 Random])
(defn payload [seed n]
 (let [a (byte-array n)] (.nextBytes (Random. seed) a) (.encodeToString (Base64/getEncoder) a)))
(defn encoded-size [store value compressed?]
 (let [base (get (:serializers store) (:default-serializer store))
       serializer (if compressed? ((:compressor store) base) base)]
  (with-open [b (ByteArrayOutputStream.)]
   (kp/-serialize serializer b (:write-handlers store) value) (.size b))))
(defn record-shape [c]
 (let [store (:store @c) branch (get-in @c [:config :branch]) r (k/get store branch nil {:sync? true})]
  {:full (encoded-size store r false) :lz4 (encoded-size store r true)
   :fields (into {} (for [[key v] r] [(name key) {:raw (encoded-size store v false) :lz4 (encoded-size store v true)
    :class (.getName (class v))}]))
   :roots (into {} (for [[key v] r :when (instance? org.replikativ.persistent_sorted_set.Branch v)]
    [(name key) {:level (.level v) :keys (count (.keys v)) :buffered (.bufEntries v)
                :keys-bytes (encoded-size store (.keys v) false)
                :addresses-bytes (encoded-size store (.addresses v) false)
                :slots-bytes (encoded-size store (.slotsForStorage v) false)}]))}))
(defn update-case [tag bf buf hist payload-size]
 (let [path (str home "/updates-" tag)
       config {:store {:backend :file :path path :id (random-uuid) :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
               :schema-flexibility :write :keep-history? hist :fuse-index-roots? true
               :index-config {:branching-factor bf :diff-buf-size buf} :writer {:backend :self}}
       _ (d/create-database config) c (d/connect config)]
  (try
   @(d/transact! c schema)
   (emit {:case tag :seed-ms (first (timed #(deref (d/transact! c (mapv (fn [i] {:p/id i :p/v (payload i payload-size) :p/n i}) (range 10000))))))})
   (doseq [j (range 110)]
    (let [txs (mapv (fn [i] {:p/id (mod (+ (* j 137) (* i 719)) 10000)
                            :p/v (payload (+ 100000 (* j 100) i) payload-size)}) (range 14))
          [ms r] (timed #(deref (d/transact! c txs)))]
     (when (>= j 50) (emit {:case tag :iteration j :ms ms :datoms (count (:tx-data r))}))))
   (emit {:case tag :shape (record-shape c)})
   (finally (d/release c)))))
(doseq [args [["short" 128 32 true 12] ["long" 128 32 true 512]
              ["long-no-history" 128 32 false 512] ["long-buf128" 128 128 true 512]
              ["long-bf512" 512 32 true 512] ["long-bf4096" 4096 256 true 512]]]
 (apply update-case args))
(shutdown-agents)
```

### groups.clj

```clojure
(load-file "/Users/sean/src/seon/tmp/commit-cost-astra/probe-lib.clj")
(def config {:store {:backend :file :path (str home "/groups") :id (random-uuid)
                     :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
             :writer {:backend :self} :keep-history? true :fuse-index-roots? true
             :index-config {:branching-factor 128 :diff-buf-size 32} :schema-flexibility :write})
(d/create-database config)
(def c (d/connect config))
(try
 @(d/transact! c schema)
 @(d/transact! c (mapv (fn [i] {:p/id i :p/n i}) (range 1000)))
 (doseq [i (range 32)] (d/branch! c :db (keyword (str "b" i))))
 (let [cs (mapv #(d/connect (assoc config :branch (keyword (str "b" %)))) (range 32))]
  (try
   (doseq [n [1 8 32] kind [:one-connection :separate-branches]]
    (let [selected (if (= kind :one-connection) (vec (repeat n c)) (subvec cs 0 n))
          results (mapv (fn [j]
                    (let [[ms reports] (timed #(mapv deref (mapv (fn [conn i] (d/transact! conn (tx 17 (+ (* n 1000000) (* j 10000) (* i 100))))) selected (range n))))]
                      {:ms ms :commits (count (set (map #(get-in % [:tx-meta :db/commitId]) reports)))})) (range 12))]
      (emit {:group n :kind kind :latency (summary (map :ms (drop 2 results))) :results results})))
   (finally (run! d/release cs))))
 (finally (d/release c)))
(shutdown-agents)
```

### groups-repeat.clj

```clojure
(load-file "/Users/sean/src/seon/tmp/commit-cost-astra/probe-lib.clj")
(import '[java.nio.channels FileChannel] '[java.nio.file Paths StandardOpenOption OpenOption] '[java.nio ByteBuffer])
(def flush-channel (FileChannel/open (Paths/get (str home "/group-sentinel") (make-array String 0))
                     (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])))
(defn barrier [] (first (timed #(do (.write flush-channel (ByteBuffer/wrap (byte-array 1024))) (.force flush-channel true)))))
(def config {:store {:backend :file :path (str home "/groups-repeat") :id (random-uuid)
                     :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
             :writer {:backend :self} :keep-history? true :fuse-index-roots? true
             :index-config {:branching-factor 128 :diff-buf-size 32} :schema-flexibility :write})
(d/create-database config)
(def c (d/connect config))
(try
 @(d/transact! c schema)
 @(d/transact! c (mapv (fn [i] {:p/id i :p/n i}) (range 1000)))
 (doseq [i (range 32)] (d/branch! c :db (keyword (str "b" i))))
 (let [cs (mapv #(d/connect (assoc config :branch (keyword (str "b" %)))) (range 32))]
  (try
   (doseq [n [1 8 32] kind [:one-connection :separate-branches]]
    (let [selected (if (= kind :one-connection) (vec (repeat n c)) (subvec cs 0 n))
          results (mapv (fn [j]
                    (let [[ms reports] (timed #(mapv deref (mapv (fn [conn i] (d/transact! conn (tx 17 (+ (* n 1000000) (* j 10000) (* i 100))))) selected (range n))))]
                      {:barrier-ms (barrier) :ms ms :commits (count (set (map #(get-in % [:tx-meta :db/commitId]) reports)))})) (range 12))]
      (emit {:group n :kind kind :latency (summary (map :ms (drop 2 results))) :results results})))
   (finally (run! d/release cs))))
 (finally (d/release c)))
(.close flush-channel)
(shutdown-agents)
```

### scatter.clj

```clojure
(load-file "/Users/sean/src/seon/tmp/commit-cost-astra/probe-lib.clj")
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(def attrs (mapv #(keyword "s" (str "a" %)) (range 32)))
(def schema32 (mapv #(hash-map :db/ident % :db/valueType :db.type/long :db/cardinality :db.cardinality/one :db/index true) attrs))
(defn scatter-case [bf buf]
 (let [path (str home "/scatter-" bf "-" buf)
       cfg {:store {:backend :file :path path :id (random-uuid) :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
            :keep-history? true :schema-flexibility :write :fuse-index-roots? true :writer {:backend :self}
            :index-config {:branching-factor bf :diff-buf-size buf}}
       _ (d/create-database cfg) c (d/connect cfg)]
  (try
   @(d/transact! c schema32)
   (emit {:scatter [bf buf] :seed-ms (first (timed #(deref (d/transact! c (mapv (fn [i] (into {:db/id (+ 1000000 i)} (map (fn [a] [a i]) attrs))) (range 2000))))))})
   (doseq [j (range 50)] @(d/transact! c (mapv (fn [i] [:db/add (+ 1000000 (mod (+ (* j 137) (* i 719)) 2000)) (nth attrs i) (+ 10000 j)]) (range 14))))
   (let [before (files path)
         rs (mapv (fn [j]
             (let [txs (mapv (fn [i] [:db/add (+ 1000000 (mod (+ (* j 137) (* i 719)) 2000)) (nth attrs i) (+ 20000 j)]) (range 14))
                   alloc (.getTotalThreadAllocatedBytes mx)
                   [ms r] (timed #(deref (d/transact! c txs)))
                   bytes (- (.getTotalThreadAllocatedBytes mx) alloc)]
               {:ms ms :alloc-bytes bytes :datoms (count (:tx-data r))})) (range 40))
         after (files path) fresh (apply dissoc after (keys before))
         head-size (.length (io/file path (kd/key->store-key :db)))]
    (emit {:scatter [bf buf] :latency (summary (map :ms rs)) :allocation (summary (map :alloc-bytes rs))
           :new-files-per-commit (/ (count fresh) 40.0) :new-bytes-per-commit (/ (reduce + (vals fresh)) 40.0)
           :final-head-file-bytes head-size :samples rs}))
   (d/release c)
   (let [r (d/connect cfg)]
    (try (emit {:scatter [bf buf] :reopened-current-count (count (d/datoms @r :eavt))
                :history-count (count (d/datoms (d/history @r) :eavt))
                :last-value (:s/a0 (d/pull @r [:s/a0] (+ 1000000 (mod (* 39 137) 2000))))})
     (finally (d/release r))))
   (finally (d/release c)))))
(doseq [[bf buf] [[128 32] [128 128] [32 8] [512 32] [4096 256]]] (scatter-case bf buf))
(shutdown-agents)
```

### flush.py

```python
import os, fcntl, json, time, sqlite3, statistics, platform
from pathlib import Path
root=Path('/Users/sean/src/seon/tmp/commit-cost-astra')
def stats(xs):
 s=sorted(xs); return {'n':len(s),'p50':statistics.median(s),'p95':s[int(.95*len(s))],'min':s[0],'max':s[-1]}
def measure(f,n=60):
 for _ in range(5):f()
 xs=[]
 for _ in range(n):
  t=time.perf_counter_ns();f();xs.append((time.perf_counter_ns()-t)/1e6)
 return stats(xs)
print(json.dumps({'python':platform.python_version(),'macOS':platform.mac_ver(),'sqlite':sqlite3.sqlite_version}))
for size in [512,4096,16384,90112]:
 with open(root/f'append-{size}', 'wb', buffering=0) as f:
  b=bytes([x%251 for x in range(size)])
  for group in [1,8,32]:
   def full():
    for _ in range(group):f.write(b)
    fcntl.fcntl(f,fcntl.F_FULLFSYNC)
   r=measure(full);print(json.dumps({'append-bytes-per-tx':size,'group':group,'fullfsync':r,'amortized-p50':r['p50']/group}))
  print(json.dumps({'append-bytes':size,'fsync-only':measure(lambda:(f.write(b),os.fsync(f.fileno())))}))
for full in [0,1]:
 c=sqlite3.connect(root/f'wal-{full}.db')
 c.execute('pragma journal_mode=WAL');c.execute('pragma synchronous=FULL');c.execute(f'pragma fullfsync={full}');c.execute('pragma wal_autocheckpoint=0')
 c.execute('create table kv (k integer primary key, v blob)');c.commit()
 for group in [1,8,32]:
  payload=b'x'*1024
  def transaction():
   c.execute('begin')
   for _ in range(group):c.execute('insert into kv(v) values (?)',(payload,))
   c.commit()
  r=measure(transaction);print(json.dumps({'sqlite-wal-fullfsync':full,'rows-per-tx':group,'latency':r,'amortized-row-p50':r['p50']/group}))
 c.close()
```
