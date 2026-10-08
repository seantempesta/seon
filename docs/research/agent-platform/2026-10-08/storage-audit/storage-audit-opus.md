---
type: research
status: proposed
date: 2026-10-08
lane: storage-audit-opus
---

# Storage audit: Datahike, konserve, blobs and `seon.cache`

Lane `storage-audit-opus`, 2026-10-08. Research and measurement only; no source changed. Read-only JVM-mode probes on
default (pid 97988, started 06:11:54 UTC, HEAD `9246a9ac0`, probes 06:15-06:21 UTC, uptime 5-10 minutes). The
working tree carried another lane's uncommitted SR-D diff in `src/seon/error.clj`; line numbers below are HEAD's.

What exists: one Datahike store on one konserve file store (`data/store`) holds index nodes, commit records and
content-addressed blobs side by side; Datahike's whole-store collection (`datahike.gc/gc-storage!`) sweeps all three,
with Seon supplying blob reachability through Datahike's `:datahike.gc/reachable-extension` seam
(`src/seon/cluster/registry.clj:565-583`). In memory: Datahike's node LRU (8192 nodes), Datahike's soft-referenced
loaded children, Datahike's query-result cache, `seon.cache` (one Caffeine cache, 512 MB), and nine further
`clojure.core.cache` or `memoize` holders. The principal approach of every fix below is: use the dependency's own
seam (konserve metadata and compressor, Datahike's dials, Caffeine's policy) instead of a Seon copy.

## Default's health at measurement

| Number | Value | Source |
| --- | --- | --- |
| Health state | `:faulted`: 10 over-bound warnings, 6 open faults, 49 errored receipts, 26 unowned namespaces | `runtime_status` 06:14 |
| Top over-bound | `seon.cluster/adopt-entities!` mean 1.77 s max 2.99 s; `seon.cluster.source/publish!` max 2.10 s; `seon.fn/index!` max 1.99 s; `seon.health/report` max 2.47 s | same |
| Boot ready | 18 174 ms | same |
| Heap | max 10.99 GB (`-XX:MaxRAMPercentage=8` of a 128 GB machine, `deps.edn:159`), used 5.2 GB, **live 896 MB** after a full GC (`jcmd GC.class_histogram`, 0.5 s) | `jcmd` |
| `seon.cache` | 217 MB of 512 MB at 5 min uptime, **346 MB at 9.5 min**, zero evictions | `seon.cache/status` |
| Store | 968 MB, 166 946 files at 06:18; 974 MB, 167 573 files at 06:19:46 (+627 files, +6 MB in about 90 s). The 03:00 UTC collection left 302 MB, 107 423 objects (receipt `467a4ccccf92`, 320 s, swept 261 804 objects, 1.05 GB): **+666 MB in 3 h 15 min** | `bin/seon status`, `du` |
| Roster | 109 branches: 106 `agent-*`, plus `:db`, `:current-src`, the cluster branch. 108 agent rows are open (no `:seon.agent/admission-closed-tx`); 185 agent rows name a branch | JVM query |
| Datoms | 577 172 current (EAVT), 1 037 474 with history | JVM query |

Probe findings recorded on the way (each a `:slow` finding in the reply): `referenced-blobs` over 109 branches took
3145 ms (current) and 2304 ms (with history); counting the history EAVT took 1890 ms. Both are my measurements of
existing functions, not new cost.

## 1. Inventory

### In memory

| Holder | Bound | Measured on default | Where |
| --- | --- | --- | --- |
| Datahike node LRU (`clojure.core.cache` LRU of materialized nodes, shared by every connection to the store) | `:store-cache-size` 8192 **nodes, not bytes** | Full at 5 min: 6761 leaves + 1431 branches, 552 437 datoms, 89 922 string values holding 18.9 M characters (440 strings over 4096). Storage counters: 930 017 node accesses, 30 613 disk reads: **96.7 % hit** | `src/seon/cluster/store.clj:203`; `reference-code/datahike/src/datahike/index/interface.cljc:14-17`; `persistent_set.cljc:486-490` |
| Datahike loaded children held by `SoftReference` in every tree (`RefType/SOFT`, verified on the live EAVT's settings) | Heap pressure only (`-XX:SoftRefLRUPolicyMSPerMB`, default 1000 ms per free MB, so about 80 min at 5 GB free) | 48 997 `Leaf` objects and 50 685 `SoftReference`s live; **3 761 946 live `Datom` objects (150 MB)** against 577 172 current datoms; the LRU holds 552 437 of them | `jcmd` histogram; `persistent-sorted-set` 0.4.137 `Settings._refType` |
| Datahike query-result cache | 64 snapshots and 1 000 000 shallow weight (`DATAHIKE_QUERY_CACHE_*`) | 13 snapshots, weight 622 912, 436 stores, 6 skips; **no hit counter exists** | `reference-code/datahike/src/datahike/query.cljc:2444-2537`, `:2679-2700` |
| Datahike plan cache, parse memo, schema-meta LRU (1024), schema write cache | count | schema-meta 11 entries | `query.cljc:2449`, `schema_cache.cljc:8-12` |
| Datahike `:search-cache-size` 10 000 | none | **no consumer**: only `config.cljc` and `connector.cljc:153,262` mention it | dead dial |
| konserve read-through cache (`konserve.cache`) | 32 | not attached (`(:cache store)` is nil) | `reference-code/konserve/src/konserve/cache.cljc:19-25` |
| `seon.cache` (one Caffeine `AsyncCache`, W-TinyLFU) | `:seon.config.cache/max-bytes` 512 000 000 estimated bytes | 29 kinds; table below | `src/seon/cache.clj:53-74` |
| `seon.db` projection LRU | 24 entries | 24 keys over **3 distinct projections** (two of 3403 forms); keys: 14 commit, 8 revision, 1 builder, 1 content. Their compiled-schema memo holds **1349 compiled Malli schemas** unbounded | `src/seon/db.clj:1451-1458`, `src/seon/schema.clj:420` |
| `seon.db` value-projection LRU | 4 entries | 4 keys, 2 distinct projections | `src/seon/db.clj:1457` |
| `seon.sci.eval` program-identity and refusal-recording LRUs | 256 entries each | not measured | `src/seon/sci/eval.clj:3115`, `:3358` |
| Snapshot memo per projection, destructive-reach memo | 24 and 4 per projection | not measured | `src/seon/call_preparation.clj:402`, `src/seon/test.clj:416` |
| Schema reference memo | none (per call) | per call | `src/seon/schema.clj:2052` |
| `memoize manifest-roots` | per checkout path | one | `src/seon/fn/analyzer.clj:258` |
| Live results: SCI `result` namespace and `::result-objects` atom | none | covered by SR-H | `src/seon/sci/eval.clj:592`, `:3072` |

`seon.cache` kinds at 5 min (hits / misses / kB / derivation ms), the largest at 9.5 min in brackets:

| Kind | Hits | Misses | kB | Load ms |
| --- | --- | --- | --- | --- |
| `:seon.task/tasks-tx` | 2 | 3 | 30 011 [60 MB, 11 / 6] | 364 |
| `:seon.sci.eval/base-context` | 1 | 2 | 23 212 | 1534 |
| `:seon.sci.eval/loaded-function-rows` | **0** | 2 | 22 954 | 73 |
| `:seon.cluster/derivation-world` | 2 | 2 | 19 098 [28 MB] | 39 |
| `:seon.schema/declaration-projection` | 74 | 3 | 17 590 | 448 |
| `:seon.fn/population-targets` | 8 | 2 | 17 386 [43 MB, 25 / 5] | 23 |
| `:seon.task/file-grammar` | 1 | 2 | 17 355 | 11 |
| `:seon.test.runner/reach-declarations` | **0** | 1 | 17 143 | 879 |
| `:seon.schema/projection` | 0 | 1 | 9698 [29 MB] | 468 |
| `:seon.task/task-basis` | 3 | 1 | 9340 | 12 |
| `:seon.sci.eval/loaded-digests` | 3 | 5 | 7260 | 637 |
| `:seon.schema.edn/packaged-population` | 88 | 5 | 6391 | 407 |
| `:seon.test.runner/reach-node` | **0** | 2539 | 3912 | 52 |
| `:seon.health/derived` | 7 | 31 | 3866 | 5496 |
| `:seon.cluster/file-derivation-key` | 976 | 981 | 2770 | 334 |
| `:seon.task/file-declarations` | 78 | 159 | 2540 | 36 |
| `:seon.cluster/file-schema-keys` | 1457 | 996 | 2026 | 28 |
| `:seon.test.runner/reach-schema-node` | **0** | 2378 | 1807 | 181 |
| `:seon.schema.datahike/vector-pull` | 1638 | 78 | 968 | 19 |
| `:seon.test.runner/reach-index` | 0 | 1 | 907 | 116 |
| `:seon.test.runner/reach-graph` | 0 | 1 | 453 | 2056 |
| `:seon.schema/canonical-keys` | 2685 | 1 | 326 | 1 |
| eight smaller kinds (health, refusal, decoded-edn, predicates) | | | under 300 each | |

### On disk

| What | Count and bytes | Where |
| --- | --- | --- |
| konserve files (nodes, commit records, blobs, `:branches`) | 167 573 files, 974 MB | `data/store` |
| Blobs a datom names, current value of `:db` | 9 error node-tree blobs (384 207 bytes, max 54 932), the same 9 digests again under `:seon.error.occurrence/blob-digest`, 9 thrown-test-actual blobs (160 795 bytes, max 22 644) | JVM query, file lengths |
| Digests the collector treats as blob roots, all 109 branches with history | 36 | `seon.cluster.registry/referenced-blobs` |
| Staging files (`data/blob-staging`) | 0 | `ls` |
| Attributes whose schema resolves to `:seon.blob/digest` | 37 schema keys, 19 installed attributes | `registry.clj:439-477` |

The 19 installed digest attributes and their datom counts on `:db`: `:seon.db/read-result-digest` 34 (4 distinct,
**none stored as blobs**), `:seon.error/data-blob` 9, `:seon.error.occurrence/blob-digest` 9,
`:seon.test.failure/actual-blob` 9, and 0 for `my.edit/{after,before,expected}-digest`, `my.fs/{after,before}-digest`,
`:my.shell/stdin-blob-digest`, `:seon.ai.attempt/reasoning-blob`, `:seon.dev.mcp.artifact/{digest,id}`,
`:seon.effect/result-blob`, `:seon.eval/value-digest`, `:seon.test.accretion/report-blob`,
`:seon.test.failure/{expected,message}-blob`, `:seon.turn/reply-blob`.

Large strings that stay in datoms, current `:db` (count; total characters; over 4096: count, characters; max):

| Attribute | n | chars | over 4096 | max |
| --- | --- | --- | --- | --- |
| `:seon.fn/source` | 5674 | 5.38 M | 164, 1.17 M | 28 549 |
| `:seon.test/source` | 2478 | 4.12 M | 122, 0.72 M | 40 296 |
| `:datahike.read/dependency-plan` | 1813 | 942 K (550 K distinct) | 18 | 14 167 |
| `:seon.fn/doc` | 3481 | 858 K | 0 | 3477 |
| `:datahike.read/revision` | 1813 | 590 K (262 K distinct) | 4 | 16 483 |
| `:seon.schema/form` | 3403 | 549 K | 4 | 10 020 |
| `:seon.eval/shown` | 589 | 253 K | 14 | 4800 |
| `:seon.cluster.eval/source` | 589 | 150 K | 0 | 3324 |
| `:seon.db/read-request` | 34 | 85 K (14 K distinct) | 1 | 6127 |
| `:seon.cluster.eval/output` | 6 | 28 K | 2 | 13 890 |
| `:seon.error/data-edn` | 15 | 22 K | 3 | 4751 |

In the node LRU the same attributes appear many times over: 1540 `:seon.fn/source` strings (3.7 M chars), 1335
`:seon.test/source` (3.0 M), 138 `:seon.error/data-edn` strings for 15 facts. A string in a datom is held once per
index leaf that holds it (EAVT, AEVT, the temporal pair) and once per node version that was loaded.

### Dials that decide inline or blob

`:seon.config.eval.result/blob-threshold` 4096, `:seon.config.shell/inline-output-bytes` 4096,
`:seon.config.fs/max-inline-bytes` 8192, `:seon.config.web/max-inline-bytes` 8192,
`:seon.config.error/max-evidence-bytes` 16 384. The storage-roles rule names one dial.

## 2-5. Findings, ranked by impact

| # | Finding | Evidence | Impact | Action |
| --- | --- | --- | --- | --- |
| 1 | **Heap and cache budgets are sized for a 128 GB machine.** Default runs `-XX:MaxRAMPercentage=8` (`deps.edn:159`): 11 GB here, **1.3 GB on a 16 GB laptop**, against 896 MB live heap at 5 minutes. `seon.cache` is a fixed 512 MB (`seon.config.cache.edn`, "5 % of a 10.5 GB heap"): 40 % of a 1.3 GB heap, 12.5 % of the 4 GB target. It reached 346 MB with zero evictions in 9.5 minutes | `jcmd`, `seon.cache/status` | Memory: an out-of-memory exit on a laptop is plausible | OWNER DECISION A |
| 2 | **Datahike keeps a second, heap-pressure-bounded node cache.** Loaded children are `SoftReference`s in every tree (`RefType/SOFT`); with gigabytes free, G1 keeps them for about an hour. 3.76 M live Datom objects (150 MB, plus their strings in the 236 MB of `byte[]`) are 6.5 times the current datoms; the counted LRU holds only 15 % of them. Every `:seon.fn/source` string over 4 KB is repeated across indexes and node versions | `jcmd`; node LRU walk | Memory: the largest single consumer of live heap | OWNER DECISION B |
| 3 | **Blob writes skip konserve's own durability and compression seams.** `seon.blob/publish-staged!` calls `k/bassoc` (`blob.clj:173-175`), which takes no metadata (`konserve/core.cljc:673-688`), so a blob is never `:immutable?` and always takes the `:barriered` plan (two store syncs; `impl/defaults.cljc:60-77`), the measured flat 8 ms per blob (storage-roles §1). Datahike marks nodes `{:immutable? true}` (`datahike/writing.cljc:458-459`) and gets `:deferred`, made durable by the following commit's barrier. Binary writes also bypass the store's LZ4 compressor (`impl/defaults.cljc:123-126`): a 54 785-byte error blob is stored as 54 932 bytes; LZ4 makes it 4700 (11.7 times smaller). `publish-staged!` then re-reads and re-hashes the whole blob (`verify-stored!`, `blob.clj:122-139,175`) | file bytes, `.compress` | Write latency per blob (8 ms to about 1 ms, to verify) and disk | Slice K (konserve fork) |
| 4 | **Whole-program cache entries per program change.** `:seon.task/tasks-tx` (about 10 MB per entry, key includes the whole forms map and every task file's text, `src/seon/task.clj:243`), `:seon.fn/population-targets` (8.6 MB), `:seon.schema/projection` (9.7 MB), `:seon.cluster/derivation-world` (9.5 MB): each adoption adds one whole-program entry per kind; 129 MB more in 4.5 minutes. W-TinyLFU will evict, but every change pays a whole-program derivation (the over-bound `adopt-entities!`, `publish!`, `index!` rows above) | `seon.cache/status` twice | Compute per change and cache churn | Existing tasks on those owners; record for Astra's performance lane (out of scope here) |
| 5 | **Two schema-projection memos.** `seon.db`'s 24-entry LRU holds 3 projections (two full, 3403 forms) plus an unbounded compiled-schema memo with 1349 compiled Malli schemas, outside the byte budget; `seon.cache` holds `:seon.schema/declaration-projection` (3 entries, 17.6 MB) and `:seon.schema/projection` (1 entry, 9.7 MB) built by a different path. No object is shared between them (identity check: 0 of 3) | JVM probe | Memory (an estimated 25-50 MB unbudgeted) and a second derivation of one population | SR-J, widened: slice J2 |
| 6 | **Digest attributes that are not blob references share the blob schema.** `:seon.db/read-result-digest`, `my.edit/*-digest`, `my.fs/*-digest`, `:my.web.body/digest`, `:my.shell.output/digest`, `:seon.dev.mcp.artifact/id` resolve to `:seon.blob/digest`, so the collector treats them as roots, and the collection verifier had to learn "unstored digests" to stop refusing clean collections (`src/seon/maintenance.clj:380-400`). 34 of the 61 digest datoms on `:db` name nothing stored. Neither "which values are in blobs" nor the collector's root set can be read off the schema exactly | query | Correctness of the audit and the verifier | Slice D2 |
| 7 | **The same digest named twice on one entity.** Entity 70857 carries `:seon.error/data-blob` and `:seon.error.occurrence/blob-digest` with the same 9 digests (pull shows only those two attributes) | pull | Small; one name too many | Retired by SR-D (in flight) |
| 8 | **Blob retention follows history, not reference.** The collector reads the temporal AEVT of every roster branch (`referenced-blobs ... true`, `registry.clj:583`), so a blob stays as long as any branch's history names it, even after its fact is retracted; only unlinking a branch frees it. With 108 open agents never closed, nothing an agent wrote is ever reclaimed. The snapshot window (1 h) prunes commit records, not temporal datoms | query; roster | Disk growth on laptops | OWNER DECISION C |
| 9 | **Read evidence stores repeated text.** `:datahike.read/dependency-plan` 942 K characters with 550 K distinct, `:datahike.read/revision` 590 K with 262 K distinct, `:seon.db/read-request` 85 K with 14 K distinct: 0.79 M characters (49 %) are repeats of a value already stored | query | Disk and index-leaf copies | Slice R |
| 10 | **Collection does its expensive work several times.** One `seon.maintenance/collect!` lists every konserve key (`k/keys`, one header read per file) for the inventory count (`registry.clj:582`), again in konserve's `sweep!`, again in `konserve-key-set` before and after (`maintenance.clj:368,532`), walks the directory for bytes (`:315`), then runs the whole collection a second time. It took 320 s on a 1.35 GB store and holds the sweep permit, so `branch!` and blob publications wait. The blob-root half re-reads and `edn/read-string`s every schema form once per branch (`blob-digest-attributes`, 14 ms each, about 1.5 s for 109 branches) | receipts, `registry.clj:439-477` | Compute: minutes per day, blocking writers | Existing tasks `seon.maintenance/collection-marks-slowly-and-records-nothing`, `seon.maintenance/a-test-request-collects-the-whole-store-under-the-exclusive-gate`; small slice G below for the per-branch schema reparse |
| 11 | **Program source over 4096 bytes stays in datoms.** 286 definitions (1.9 M characters) exceed the threshold the storage-roles rule set for text; each is copied into every index leaf that holds it and repeated in the node caches (finding 2) | query | Memory and disk; modest | OWNER DECISION D |
| 12 | **Datahike's query cache has no hit counter; Seon wraps whole-program queries in its own kinds.** Datahike exposes stores, skips and single-flight saves but not hits (`query.cljc:2679-2700`), and its 1 M weight limit cannot hold a whole-program join, so `:seon.sci.eval/loaded-function-rows` (23 MB, keyed by store and commit, the key Datahike uses) holds it instead, with 0 hits so far | metrics | Auditability; possibly a duplicate cache | Slice Q (Datahike fork counter) then OWNER DECISION E |
| 13 | **Kinds that have not hit.** `reach-node` (0 / 2539), `reach-schema-node` (0 / 2378), `reach-declarations` (0 / 1), `loaded-function-rows` (0 / 2) after 9.5 minutes. Either the keys never repeat (then the entries are pure cost) or the window was too short | status | 46 MB retained | Measure over a day before acting (see Gaps) |
| 14 | **Five dials decide inline versus blob.** 4096 (eval, shell), 8192 (fs, web), 16 384 (error evidence) | config facts | Consistency with the ruled rule | Slice T |
| 15 | **Staging files have no cleaner.** `stage-binary!` leaves its file under `blob-staging` when a write is interrupted or its settlement refused (`blob.clj:296-301`); nothing else names the directory | grep | Disk; currently 0 files | Slice S |
| 16 | **Dead Datahike dial.** `:search-cache-size` is accepted and has no consumer | `reference-code/datahike` grep | None | Note for the fork; no Seon action |

Not a duplication, recorded so nobody "fixes" it: Seon's per-kind `LongAdder` counters beside Caffeine. Caffeine's
`recordStats` counts one cache as a whole and cannot split by kind; `seon.cache/status` already reads Caffeine's
`weightedSize` for the total (`cache.clj:293`). Seon's content addressing (SHA-256 keys) does not duplicate
konserve, which is a key-value store that names files by a hash of the key, not of the content; it does not duplicate
Datahike either, whose node addresses are its own. The blob collector composes Datahike's mark and konserve's sweep
through Datahike's extension seam rather than walking the store itself: the right shape.

### Correctness answers (question 3)

- **Same transaction.** Every blob writer at HEAD stages and publishes under `blob/with-publication!` with the commit
  that names it (`effect.clj:625`, `fault.clj:266`, `schedule.clj:594`, `test/runner.clj:1983`, `turn.clj:80,2238,
  3618,3920,3991,4266,4542,5972`), except `seon.error/prepare-result`'s `blob/put!` (`error.clj:590` at HEAD), which
  publishes, releases the permit, then commits later. A collection between the two can sweep the blob, since it is
  older than the safe point and unreferenced. SR-D removes it and is in flight. `put!` and `put-binary!` remain public
  with no other `src` callers; after SR-D they invite the bug class back (slice P).
- **Reclaimed when unreferenced?** Only when no roster branch's history names it (finding 8). Unreferenced blobs
  from refused settlements or the `put!` path are swept at the next collection once older than the safe point.
- **Who collects, when, how.** `seon.maintenance/collect!`, scheduled daily at 03:00 UTC (`schedule.clj:58-62`, cron `0 3 * * *`) on the schedule proc when
  `:seon.config.maintenance/collect?` is true (it is). Datahike's reachability marks nodes; Seon's
  `referenced-blobs` walks AEVT (and temporal AEVT) for the digest attributes and returns the blob roots through
  `:datahike.gc/reachable-extension`; konserve's `sweep!` deletes everything else older than the safe point. One
  collection, one permit, no separate blob walk of the store. Publication and sweep exclude each other through the
  fork's `datahike.gc-guard` permits.

### Auditability (question 4): "what is where, when", from what exists

Smallest design, no new stored fact:

1. `seon.cache/present?` `[kind key] -> boolean`: `(.containsKey (.asMap cache) [kind key])` on a completed future.
   It reads Caffeine's map without recording an access, so asking does not change eviction. About 100 ns. For a
   listing, Caffeine's own `Policy.Eviction/hottest` and `coldest` (bounded `n`) answer what is held without walking
   the cache, unlike `seon.cache/retained`.
2. `seon.blob/placement` `[db eid] -> {attribute {:seon.blob/digest d :seon.blob/size n :seon.blob/stored? b}
   | {:inline-bytes n}}`: one `d/pull`, the entity's attributes classified by their declared schema (blob reference
   or inline text), `stored?` from `konserve.core/exists?` (measured `get-meta` at 0.26 ms per digest), size from the
   sibling size attribute where one is declared (SR-B's `inline-or-stage` writes one; slice K adds `:size` to konserve
   metadata so every blob has it). Pass `(d/as-of db t)` and the same call answers "as of t"; `(d/history db)` datoms
   of the entity give each value's transaction, which is the "when" for both datoms and blob references.
3. For an evaluation, the caller adds the cache key it knows (after SR-H, `[:seon.repl/result eval-id]`) and gets
   the third column from `present?`. The cache has no past: "in the cache as of t" is not answerable, and the reply
   says so rather than guessing.

Cost: one pull plus one `exists?` per blob reference plus one map lookup per named cache key: under 2 ms for an
evaluation. The schema classification must not re-read schema forms per call as `blob-digest-attributes` does (14
ms); derive it once per projection as a `seon.cache` kind keyed by the projection identity (slice G). Exactness
needs finding 6 fixed first, so that a `:seon.blob/digest` attribute always means "a blob".

## Slices

| Slice | What | Files | Proof (asserts data) | Budget |
| --- | --- | --- | --- | --- |
| K | konserve fork: `bassoc` takes metadata (as `assoc` does, `core.cljc:354`) and applies the store compressor to binary values, recording `:size`; Seon's `publish-staged!` passes `{:immutable? true}` and keeps the `exists?` check. Verify first that the commit's barrier covers a deferred blob before its fact is visible (`sync-plan`, `impl/defaults.cljc:60-77`) | `reference-code/konserve/src/konserve/core.cljc`, `impl/defaults.cljc`; `src/seon/blob.clj` | `seon.blob-test/published-blob-is-compressed-and-deferred`: a 54 KB text blob's file is under 10 KB, `get` returns it unchanged, `get-meta` has `:size` 54 785, and the write plan is `:deferred`; timing row: publish median over 20 under 2 ms on a disposable store | Opus or Astra; about 1 h; konserve fork |
| D2 | Content digests that are not blob references declare a non-blob digest schema (the existing `:seon.source/digest` shape, or a new name the owner picks); `:seon.blob/digest` means "a blob"; the verifier's unstored-digest branch (`maintenance.clj:380-400`) is deleted | `resources/seon/schemas/seon.db.edn:120`, `my.edit`, `my.fs`, `my.web.body`, `my.shell.output`, `seon.dev.mcp.artifact` schema files; `src/seon/maintenance.clj` | `seon.cluster.registry-test/every-blob-digest-attribute-names-stored-content`: on a fresh test world, every datom of every attribute `blob-digest-attributes` returns is `k/exists?` | Sonnet; schema adoption on a live-store branch |
| G | `blob-digest-attributes` derived from the carried projection's parsed forms as a `seon.cache` kind keyed by projection identity, instead of `edn/read-string` of every form per branch per collection | `src/seon/cluster/registry.clj:439-477` | `seon.cluster.registry-test/blob-attributes-derive-once-per-projection`: two calls on two branches at one commit record one miss in `seon.cache/status` | Sonnet; 15 min |
| J2 | SR-J extended: `seon.db`'s projection and value-projection LRUs become `seon.cache` kinds and share the projection the schema owner already caches; the compiled-schema memo inside a projection is weighed | `src/seon/db.clj:1441-1458`, `src/seon/schema.clj:420` | `seon.cache-test/one-projection-per-population`: after reading one commit through `carried-projection` and `schema/declaration-projection`, one projection object is retained (identity) and `status` lists its bytes | Opus (boot-critical read path) |
| R | Read evidence content-addressed: plan, revision and read-request stored once per digest (an identity entity keyed by `seon.id` digest), referenced from each evidence row | `src/seon/db.clj` read-evidence writer, `seon.db.read.edn` | `seon.db-read-evidence-test/equal-plans-store-once`: two evaluations with the same query store one plan entity | Sonnet after the evaluation spec owner agrees (storage-roles §3 left this to that owner) |
| T | One inline-or-blob dial: shell, fs, web and error evidence read `:seon.config.eval.result/blob-threshold` through `seon.blob/inline-or-stage`; the four other storage dials retire (agent-facing preview sizes stay) | `src/seon/shell/jvm.clj:307`, `src/seon/web/jvm.clj:284,417`, `src/seon/fs/jvm.clj:518`, `src/seon/edit/jvm.clj:137`, config schemas | `seon.blob-threshold-test/every-text-writer-uses-the-one-threshold`: a 5000-byte web body and shell output each store a blob digest and size | Sonnet |
| P | After SR-D, delete `seon.blob/put!` and `put-binary!` (no `src` callers), so a blob can only be written with its fact | `src/seon/blob.clj`, tests naming them | `seon.blob-test/blobs-publish-only-with-a-commit`: `(ns-resolve 'seon.blob 'put!)` is nil | Sonnet; 15 min |
| S | Staging files older than the oldest live publication permit are deleted by collection | `src/seon/maintenance.clj` | `seon.maintenance-test/stale-staging-files-are-collected` | Sonnet |
| Q | Datahike fork: `query-cache-metrics` reports hits and misses | `reference-code/datahike/src/datahike/query.cljc` | fork test: a repeated query reports one hit | Sonnet; fork |
| A11y | `seon.cache/present?` and `seon.blob/placement` (design above), after D2 and G | `src/seon/cache.clj`, `src/seon/blob.clj` | `seon.blob-test/placement-names-datom-blob-and-cache`: an entity with one inline text and one blob reference reports both with sizes, and a named cache key reports `present?` | Sonnet |

## Owner decisions

**A. How large may the heap and the cache be?** Today the heap is a percentage of the machine (8 %: 11 GB here,
1.3 GB on a 16 GB laptop) and the cache a fixed 512 MB.
1. Fixed `-Xmx4g` on every machine, cache stays a byte dial set to 400 MB (10 %). Predictable; the cache never
   adapts.
2. **Recommended:** `-XX:MaxRAMPercentage=25` with `-Xmx4g` as the cap (so 4 GB on 16 GB and up), and the cache
   default derived at boot as 10 % of `Runtime.maxMemory` unless the dial is set. Laptop-safe; one more derived
   default to explain.
3. Keep today's percentages; document that Seon needs 128 GB. No work; laptops fail.

**B. How much of Datahike's index stays in memory?** Soft-referenced children keep about 3 M datoms beyond the
8192-node LRU.
1. **Recommended:** keep `SOFT`, cap the heap (decision A) and set `-XX:SoftRefLRUPolicyMSPerMB` lower (for example
   100), so loaded nodes are kept about a tenth as long. One JVM flag; measure p50 query time before and after.
2. Switch Datahike's ref type to `WEAK` (fork setting) and raise `:store-cache-size`, so the counted LRU is the only
   retention. Predictable memory; more disk reads when the LRU is cold; a fork change.
3. Make the node LRU byte-weighted (fork: Caffeine weigher by datom count) and use `WEAK`. Best bound; the largest
   change.

**C. When may a blob, or an agent's history, be reclaimed?**
1. **Recommended:** close agent rows when their task ends (the agent-loop's retire path), so their branches leave the
   roster and collection reclaims their nodes and blobs. History of live branches stays whole.
2. In addition, mark only current AEVT for blob roots (history keeps the digest, the bytes go): a historical blob
   reference then answers "gone", like a live result after restart. Less disk; history loses content.
3. Keep everything until a branch is unlinked by hand. No work; disk grows with every agent.

**D. Should program source over 4096 bytes be a blob?**
1. Yes, by the one rule (`:seon.fn/source-blob` and size); readers fetch once per definition. Less node churn and
   memory (finding 2); every source reader changes.
2. **Recommended:** no; source is program data read on every analysis, and its size is better attacked by
   decision B and by shrinking oversized functions (a 28 KB `defn` is itself the defect).
3. Store source only in Git for default's program and as datoms only on branches that change it. Smallest store;
   splits where source lives.

**E. One query cache or two?** (after slice Q measures Datahike's hits)
1. **Recommended:** keep both; Seon kinds only for derivations of more than one query, deleting kinds that wrap one
   `d/q` keyed by commit.
2. Raise Datahike's weight limit (its dial) and delete every Seon kind that wraps a single query.
3. Make Datahike's result cache a `seon.cache` kind (fork seam). One budget; ties the fork to Seon.

## Gaps and limits

- The JVM had been up 5-10 minutes; hit rates for the test-reach kinds and the query cache need a day's sample before
  any kind is deleted. Re-run the two status forms of this audit at the next idle point.
- Retained sizes of `seon.cache` entries are the owners' estimates; `clj-memory-meter` is only on the `:measure`
  alias, so no retained-size measurement was taken on default.
- Bytes written per commit by index (the +666 MB in 3 hours) is SR-GROW's measurement, not repeated here.
- The `jcmd GC.class_histogram` forced one full GC on default (0.5 s wall).
- Out-of-scope finding, not recorded as a task by this lane (the lane may not edit `resources/seon/tasks`): 108 open
  agent rows keep 106 agent branches in the roster (decision C's first option names the owner).
