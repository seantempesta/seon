---
type: research
status: measured
date: 2026-10-08
lane: storage-audit-astra
---

# Storage audit — independent Astra lane

The existing composition is sound in outline: Datahike owns datoms, branches and index storage; Konserve owns the
same store's binary values; Caffeine holds derived runtime values. The largest demonstrated waste is repeated
whole-store enumeration, not blob capacity. The most serious correctness gap is that Seon's blob roots do not
cover all the references and retained database values that Datahike keeps.

This is research and read-only measurement, not an implementation or a successful destructive proof. The
[storage ruling](../storage-roles/storage-roles.md) governs the proposed changes. The independent Opus audit was
not read. Only this file is owned by this lane.

## Evidence and scope

Measurements: MCP `eval_clj`, `mode: jvm`, `agent: root`, `cluster: default`, always obtaining the explicit
`(seon.cluster.boot/connection "default")`. No definitions, prototypes, source loads, configuration changes,
blob writes, transactions authored by this lane, collection, or restart were run in default. MCP itself records
each evaluation normally. Times below are measured inside the form; the REPL's rounded duration is also recorded
where relevant. All numbers are observations of a changing live system, not a simultaneous snapshot.

Default: pid 97988, started 2026-10-08T06:11:54.782Z, source archive
`75aa6990b229bac568cf83d885588f1bcf43f5ef`. Its Datahike gitlink is
`86c3301a00de8c49b2690083b2b1e69677bf0c8d`, Konserve
`8700ca7a95784bbe5d6fb4dce1d7bc2ad96cbc37`; these match the inspected dependency checkouts.
Caffeine is 3.2.4 (`deps.edn:30`), a jar rather than a reference-code checkout.
Checkout HEAD began at `9246a9ac0498b092cf24cfeb9aa65588e404329b` and advanced during research.
Source citations below name the inspected checkout unless explicitly qualified by the running archive.
In-flight edits were present in `src/seon/db.clj`, `src/seon/error.clj`, and
`src/seon/cluster/registry.clj`; they were neither loaded nor edited here.

Health first: CLI status took 2.83 s and initially reported unknown health; MCP reported faulted,
six open faults, 49 errored records, 26 unowned namespaces, and ten over-bound warnings. Examples already have
tasks: adoption 2,991 ms maximum (5dbb7de36bdb), publication 2,101 ms (9665c2ed25f0), indexing 1,987 ms
(12037fb1e077 / 84fdc131a599 / 8b62adf64442), task rendering missing its required id (68f9fc8b8da1).
Those are pre-existing observations, not storage-audit regressions.

Two audit operations exceeded one second: `konserve.core/keys` **26,751.94 ms**
(REPL 26,754 ms, result/ee42025c96255), and `seon.cluster.registry/referenced-blobs`
**4,318.31 ms** (REPL 4,320 ms, result/e19be54195ef3). Both returned slow findings.
The unexpectedly over-ten-second inventory was not repeated; no claim of an acceptable bound is made.
These costs are recorded as the work in findings 1 and 4 below. The 26.75 s observation is a reason to avoid that
operation in an ordinary audit view, not to raise its ordinary bound.

## Findings ranked by impact

### 1. Blob reachability omits collection members and retained commit-only references

**Confirmed installed mismatch.** `seon.effect/content-blobs` is declared
`[:vector :seon.blob/digest]` and installed as cardinality-many strings. Yet
`blob-digest-attributes` returns false for it. Probe result/e47ffa20a15cd: selected false, current values empty,
34.13 ms. The producer publishes these binaries and writes their digests together
(`src/seon/effect.clj:590-630`, `resources/seon/schemas/seon.effect.edn:274`).
The vector bridge keeps the original attribute on each component cell
(`src/seon/schema/datahike.clj:663-697`, `resources/seon/schemas/seon.db.vector.edn:1`).
The collector's hand-written schema recursion recognizes aliases, `:and` and `:or`, but excludes collections
(`src/seon/cluster/registry.clj:439-477`). It consequently misses real stored string digests, not only container
entity ids. Default currently has no such values; this audit did not demonstrate deletion of one.

**Source-backed retained-commit gap.** Datahike walks every retained commit, its schema, current and temporal
index roots (`reference-code/datahike/src/datahike/gc.cljc:22-86`). Seon examines only each roster branch's
head and its temporal AEVT (`src/seon/cluster/registry.clj:525-536`). A reference removed under `:db/noHistory`,
purged, or declared by a schema subsequently removed can still exist in a retained older commit without
appearing in that head's scanned attributes/history. Installed `:seon.dev.mcp.artifact/digest` has
`:db/noHistory true` (zero current values). One-hour commit retention does not eliminate this window.
This is an unproven loss scenario requiring an isolated regression, not evidence that current files were lost.

**False positives also exist.** Thirty-four read-evidence datoms carry four distinct
`:seon.db/read-result-digest` values; none names a stored binary. Across 110 branches the existing walk returned
36 digests, of which only 23 were among the 50 observed binaries. The existing issue
[blob roots derived from shape](../../../../seon/issues/blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact.md)
already owns this class. Verification intersects inferred roots with pre-sweep keys
(`src/seon/maintenance.clj:374-416`), so a genuinely missing blob and a hash never stored can both be counted
as “unstored”; this does not prove referential completeness.

**OWNER DECISION — put external references under Datahike's reachability authority.**

1. **Recommended:** declare which stored attributes name Konserve keys, including their physical scalar members,
   and extend the maintained Datahike GC operation to obtain those keys while visiting the same retained commits
   and index nodes it already marks. Delete Seon's `ANode`/`Branch`/`Slot` walk. Estimated implementation:
   2–4 days including dependency tests; additional work proportional to distinct relevant retained nodes plus
   referenced keys, with one collection-local set, no durable Seon root index.
2. Use Datahike's existing `datoms`/history/commit reads inside the existing reachable extension, over the exact
   retained commit set supplied by the dependency. Smaller code, roughly 1–2 days, but repeated reads across shared
   commits may cost substantially more; measure before accepting. The current extension supplies reachable
   **store keys**, not a typed list of retained database values. Resolving that gap belongs in Datahike; do not
   add a second Seon commit traversal.
3. Temporarily preserve every binary key in collection. It prevents these omissions from deleting blobs but
   sacrifices binary reclamation and still needs enumeration. Hours, not days; only a temporary owner choice.

The exact library gap: `gc-storage!` provides `:datahike.gc/reachable-extension`
(`gc.cljc:160-170`) but no declared external-reference attribute traversal over each retained commit. Do not
claim this proposed operation already exists. No prototype is supplied.

Files: `reference-code/datahike/src/datahike/gc.cljc` and its GC tests,
`src/seon/cluster/registry.clj`, `src/seon/maintenance.clj`, blob-reference schema declarations.
Proposed proof: in the isolated physical-store publication test, retain a binary solely through a collection
member; replace a no-history reference while keeping its old commit; remove its old schema; collect and read every
retained reference. Then retire the last retaining commit and prove reclamation. Existing proof to retain:
`seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings`.
Budget: no per-read root walk; collection must visit a shared node once, not once per branch. Current 110-branch
walk is 4.32 s; it is not an ordinary-operation budget.

### 2. Publication is ordered and guarded, but not universally committed with its fact

`with-publication!` publishes bytes, verifies them, invokes the fact transaction, then releases Datahike's
reachability permit in `finally` (`src/seon/blob.clj:303-323`). That is the correct existing integration:
successful references become durable after their bytes, and the permit excludes a competing sweep.
Konserve does not promise one atomic transaction spanning `bassoc` and Datahike's head write.
Refused fact transactions can leave unreferenced binary keys; collection is their reclamation, not rollback.

It is therefore incorrect to answer “every blob is committed with its fact”:

- Public `put!` and `put-binary!` release the permit after returning a digest, without a fact transaction
  (`src/seon/blob.clj:325-345`).
- The running archive's `src/seon/error.clj:590` calls `blob/put!` from `prepare-result`, before the fact.
  The in-flight error rewrite in the checkout removes this call; it is not yet evidence about pid 97988.
- `seon.db/transact-call` can return outcome-unknown after a timeout while Datahike still owns the pending write
  (`src/seon/db.clj:5733-5774`). The publication `finally` then releases the permit before actual write exit.
  A GC operation on another connection to the same store is the relevant race. This needs an isolated test;
  a normal synchronous success test does not prove it safe.

Slice: finish the ruled error producer conversion; restrict durable callers to staged writes plus their fact
transaction, and have the publication owner retain its permit until the transaction's actual terminal event.
Use the existing Datahike transaction completion and GC guard; add no retry or separate lock.
Files: blob, db transaction completion, error producer, publication tests.
Proof: delayed transaction, timeout, concurrent collection on the store's other connection, then actual completion;
the blob must remain readable if the fact commits. Also a refused transaction must leave no fact and its orphan
must become collectable. Retain the existing exclusion test above. Budget: 1 day to prove the race and the completion
composition; no extra full-content reads or normal-path transactions. Newly staged orphan files are acceptable
garbage, never proof of successful fact publication.

### 3. The 4 GB heap target is not enforced by the central cache

`seon.cache` is bounded, but live result objects are held strongly by both the SCI result namespace and its
result map (`src/seon/sci/eval.clj:579-598`). These are two references to the same object, not necessarily two
copies. They bypass eviction. Result/ecc49e1047766 found 666 root handles: 566 gone markers and 100 other values,
including this audit's compact results. An earlier count of 633 alone would exaggerate retained live payload.

The process maximum is **10,997,465,088 bytes**, not 4 GB. At initial status the last reported live heap was
1,306,129,384 bytes; used heap varied above 5 GB before collection. At 06:21:26Z, JMX reported 875,016,880 old-gen
bytes and 80,711,032 survivor bytes after their most recent collections, with 503,316,480 current Eden bytes.
Those pool observations are not a forced full-GC retained-heap measurement.

The central cache had 217,401,694 estimated bytes across 6,748 entries at the detailed census and later 346,726,126 bytes / 7,424 entries.
Weights are estimates, not object graph sizes, and an estimate does not bound state retained outside the cache.
One observed projection had 1,437 compiled-cache entries. Projection LRUs held 24 + 4 keys but only four distinct
realized value identities. Blindly summing their estimates would count shared objects repeatedly.

Slice: implement the storage ruling's live-result eviction through the existing Caffeine owner and remove both
strong retention paths for evicted results. Keep the missing-result answer. Audit every other holder in the
inventory below. Files: `src/seon/sci/eval.clj`, `src/seon/cache.clj`, `src/seon/repl.clj`,
`src/seon/render/walk.clj`, result tests. Proposed proof: evaluate many large values through the real REPL,
evict, and prove both namespace and result-map readers answer gone; an un-evicted handle still returns its value.
Budget: 1–2 days, one lookup per handle; no deep object traversal per evaluation.

Do **not** use shown-text tokens × 4 as a universal retained-heap measure. A database, closure, short printed
array, or lazy sequence can retain vastly more than it prints. Calibrate the existing owner's weights by value
class and handle opaque values explicitly. This qualification is necessary to make the ruled bound meaningful.
Existing issue: [cohosted clusters share unbounded agent heap](../../../../seon/issues/cohosted-clusters-share-one-unbounded-agent-heap.md).

### 4. Collection repeatedly enumerates the same entire Konserve store

The measured 166,693-key inventory took **26.75 s** to find just 50 binary keys.
Konserve `list-keys` opens and reads each key's metadata (`reference-code/konserve/src/konserve/impl/defaults.cljc:408-451`).
Its sweep already enumerates those keys (`reference-code/konserve/src/konserve/gc.cljc:25-35`).

Seon adds another full `k/keys` inside the reachable extension for a denominator
(`src/seon/cluster/registry.clj:582`). The real maintenance operation also enumerates for before counts,
pre-sweep membership, after-first counts, second collection, and final counts
(`src/seon/maintenance.clj:309-315,368-372,528-573`).
Static call composition gives **eight enumerations** in one successful real collection: three observations,
one pre-sweep set, two registry counts and two Konserve sweeps. This is not a measured total collection time:
8 × 26.75 s = 214 s is only a same-cost estimate, before marking/deletion. Even dry-run performs three.
The first-party verification of the root set is useful; re-reading every unrelated index key for its count is not.

Slice: let Konserve's existing sweep report its observed count and selected/deleted keys from its one enumeration;
thread that result through the existing Datahike GC return and Seon maintenance. Do not create another store index.
For blob verification, check declared reference keys directly instead of building a full store-key membership set.
Remove the second sweep as a “fixed point” criterion once the existing root-preservation proof is retained.
Files: Konserve GC, Datahike GC, registry, maintenance, their existing tests.
Proof: a counted backing-store key enumeration occurs once per collection; quiet and concurrently advancing
branches remain readable, counts describe their captured observation, missing declared blobs refuse.
Budget: 1–2 days; O(store keys + distinct reachable nodes), O(candidate/reference keys) transient space,
zero extra permanent facts. Target at least seven fewer full enumerations in the present real path.
A sub-second global scan is not promised; per-entity audit must never call it.

### 5. Binary “streaming” verification still allocates the whole stored value

Konserve's installed filestore `bget` reads the payload into a ByteArrayInputStream, in both sync and async
implementations (`reference-code/konserve/src/konserve/filestore.clj:420-441,500-509`).
The callback's `:size` is the **whole physical file size**, including metadata/header, not payload bytes.
For all 50 observed files that difference was 147 bytes each: 1,275,793 physical bytes versus 1,268,443 payload bytes.

Read-only measurement of one 65,536-byte blob, five samples, current-thread allocation:

| Operation | Median ms | Warm allocated bytes | Value observed |
|---|---:|---:|---|
| Konserve bget, callback reads one byte | 0.1031 | 106,288 | first byte 103 |
| Existing blob/read-chunk, one byte | 0.0412 | 17,232 | one byte |
| blob/get, decode and verify full text | 0.1285 | 241,328 | 65,536 characters |

Result/ec5fba7f442f6, full evaluation 7 ms. This is warm local data, not a disk throughput benchmark.
`stage-binary!` bounds its own prefix, but `publish-staged!` always calls `verify-stored!`, including duplicate
digests (`src/seon/blob.clj:164-178`); verification calls `bget` (`:98-120`).
Thus the complete write path is not bounded merely because the staging loop is.
`cluster.status/agents` also calls `blob/get` merely to obtain each size
(`src/seon/cluster/status.clj:134-142`); cost follows total content bytes and repeats across shared agents.

Slice: fix streaming at Konserve's existing binary callback owner, not a Seon second binary reader. Preserve the
callback's resource lifetime and document physical versus payload size. Use existing byte-size facts for status.
Files: Konserve filestore and binary compliance tests, blob test, cluster status.
Proof: the existing
`seon.blob-test/large-binary-write-and-small-chunk-have-bounded-allocation` plus a bget callback test over a
large file that reads only one byte; allocation must follow buffer/request size, not file size. Round-trip/digest
verification still passes. Budget: 1 day; 64 KiB-or-smaller read buffer, bounded callback lifetime.
Do not remove digest verification without separately choosing its integrity guarantee.

### 6. Some durable payloads still occupy both datoms and blobs

The live default head references 18 distinct binaries, 542,356 payload bytes:

| Referring attribute | Datoms | Distinct blobs | Payload bytes attributed |
|---|---:|---:|---:|
| seon.error/data-blob | 9 | 9 | 382,884 |
| seon.error.occurrence/blob-digest | 9 | same 9 | same 382,884 |
| seon.test.failure/actual-blob | 9 | 9 | 159,472 |

The two error rows share digests: their bytes must be counted once, not twice. Existing error serialization can
also produce a different node-tree copy through `prepare-result`; equality or duplication of that copy was not
assumed. The checkout's in-flight error change already implements the ruled shown-text representation.

Another concrete duplicate producer remains in `src/seon/effect.clj:540-559`: a large admitted result always
keeps `:result-edn`, then adds `:result-blob` containing those same UTF-8 bytes. No current default effect results
exist, so the present disk contribution is zero. This should be fixed at that producer, not hidden in a render.

Distinct inline values at the later census (not datom counts, because the query projects distinct values):
shown 546 / 288,359 UTF-8 bytes / max 4,862 / 16 over 4,096;
output 5 / 14,012 / max 13,890 / 1 over;
error data 15 / 22,311 / max 4,751 / 3 over.
Probe 4.81 ms, 3,949,664 current-thread allocated bytes. Scanning and encoding every string is not a status path.

Slice: finish the storage ruling at effect/output/shown producers and their readers. Use
`seon.blob/inline-or-stage` (`src/seon/blob.clj:217-237`) and commit its staged writes with the fact.
No additional serialization of full runtime values. The AI reasoning writer still counts characters
(`src/seon/turn.clj:4200-4208`); convert that caller too.
Files: effect, eval producer/readers, turn, owning schemas and tests.
Proposed proof: large effect text has exactly one durable text representation plus digest/UTF-8 size;
multibyte text crosses at 4,097 bytes, and transcript/requery returns all original shown text.
Budget: 0.5–1 day per producer family; one encoding/digest per staged payload, no extra transaction.
The 4,096-byte dial is already installed; this audit does not re-run the earlier write benchmarks.

### 7. Seon owns counters Caffeine already supplies; an unconditional cache rewrite would also be wrong

`seon.cache/create` does not call `recordStats`, confirmed live by `isRecordingStats = false`.
Seon instead maintains hits, misses, evictions, load nanos and bytes in a ConcurrentHashMap of LongAdders
(`src/seon/cache.clj:40-73,140-157,198,281-307`). Caffeine already supplies aggregate hit/miss/load/eviction
statistics and weighted occupancy. The per-kind cumulative split is an additional requirement, not something
its aggregate stats answer automatically. Byte counters are also asynchronously decremented by the removal
listener; a concurrent status read is an observation, not an exact retained-heap balance.

Enabling `recordStats` alone does not report the load duration of Seon's manually completed futures: that work
must enter Caffeine's supported loading operation for its load statistics to apply. Preserve this distinction
when converting the adapter; do not relabel incomplete aggregate counters as complete accounting.

Caffeine's `AsyncCache.get` owns per-key computation and failed-entry removal, but explicitly disallows its
mapping function updating other mappings of the same cache. Seon's derivations intentionally call other kinds
(`src/seon/cache.clj:121-158,160-180`). Replacing `claim` with a synchronous recursive mapping function would
break that guarantee. Inspect the library's async scheduling seam before deleting this adapter.

**OWNER DECISION — required statistics.**

1. **Recommended:** Caffeine `recordStats` for cumulative aggregate statistics; derive current per-kind
   entry counts and weights on demand in one `asMap` traversal. Delete Seon's parallel cumulative counters.
   Loses exact historical per-kind hit rates, keeps the one cache/budget. About half a day; O(entries) audit only.
2. If per-kind cumulative hit rates are required, request/implement the minimum supported Caffeine statistics
   extension after proving its API gap; preserve one global weight budget. About 1–2 days to investigate and prove.
   Do not split into one independent cache per kind to obtain stats.
3. Keep the present counters temporarily with an explicit owner exception and a deletion task. No implementation
   cost now, but it retains duplicate accounting and approximate asynchronous bytes.

Proof for a later slice: retain `seon.cache-test/concurrent-misses-of-one-key-derive-once`,
`a-derivation-may-look-up-another-kind`, `failures-and-refusals-are-not-retained`, and
`kind-status-names-its-kind-and-states-hit-and-byte-shares` (adjust only the chosen statistics contract).
No API gap is treated as permission to add a second coordinator.

Library authorities: [AsyncCache.java 3.2.4:54-98](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/AsyncCache.java#L54),
[Caffeine.java recordStats](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java).
The map iterator is weakly consistent, so counts during mutation need an observation time.

### 8. A declared Datahike search-cache dial is disconnected from its reader

Default advertises `:search-cache-size 10000`; installed `:cache-size` is absent; the search-cache roster has
zero snapshots. `datahike.db.search/memoize-for` checks `:cache-size` and therefore calls its reader directly
(`reference-code/datahike/src/datahike/db/search.cljc:17-26`).
The declared/default/environment dial is `:search-cache-size`
(`reference-code/datahike/src/datahike/config.cljc:23,34,123,233`).
This is a concrete naming disconnect, not evidence that enabling another cache will improve Seon.

Slice in the dependency: choose one supported dial, wire its consumer and provide configuration/occupancy
evidence; benchmark whether it adds value beside query results and shared nodes. Prefer leaving it disabled
if the same workload gains nothing. Files: Datahike config/search and tests; no Seon search cache.
Proof: nonzero selected dial admits bounded search results; zero bypasses; query answers unchanged.
Budget: half a day, bounded by retained datom count, not an unexplained additional heap allowance.

## Store and cache inventory

### Physical storage and reachability

| Place | Measured state | Authority / lifecycle |
|---|---|---|
| Shared file Konserve store | 166,693 keys: 166,643 EDN, 50 binary, at 06:17:33Z | `src/seon/cluster/store.clj:155-216`; Datahike and blobs use the same `:store` |
| Binary payload | 1,268,443 bytes; physical file lengths 1,275,793 | 50 bget callbacks, 14.64 ms; payload measured with the installed ByteArrayInputStream's available bytes |
| Current default blob references | 18 distinct / 542,356 bytes | Current installed scalar reference attributes; 28.31 ms |
| Existing all-branch/history mark | 110 branches, 36 candidate digests, 23 existing binaries / 743,154 bytes | Existing `referenced-blobs`, 4.32 s; omissions in finding 1 apply |
| Outside that mark | 27 binaries / 525,289 bytes | **Not certified garbage** until missing collection/retained-commit roots are fixed |
| Blob staging | sibling `blob-staging` directory, oversized writes only | `src/seon/blob.clj:61-80,239-301`; publish removes successful stage file; interrupted stage remains for diagnosis, outside Konserve sweep |
| Datahike current/history nodes and commits | part of the 166,643 EDN keys; no separate physical database copy | three current and three temporal indexes, shared immutable nodes; no per-entity physical-size attribution measured |
| Konserve file dials | sync-blob true; in-place false; lock-blob true; defer-immutable-sync true; LZ4 | live store config; binary payload path is raw, metadata/EDN serialization uses configured compressor |
| Datahike dials | persistent-set, fused roots, branching-factor 128, diff-buf-size 32, keep-history true, attribute-refs false, crypto-hash false | live `:config`; `src/seon/cluster/store.clj:195-215` |
| Commit retention | 3,600,000 ms | live config; `registry/retention-cutoff` uses latest captured head and largest declared window, not idle wall time |
| External derived files | kondo dependency cache, committed source archives and JVM/dependency classes | `src/seon/fn/analyzer.clj:281-290,338-356`; outside Caffeine/Konserve GC; not binary copies of evaluation results |

No disk-size sum is presented for external class/source caches, OS page cache, or all EDN node files. The
operator's earlier 302,161,997-byte store observation was at 03:05:20Z and is stale, so it is not substituted
for a current census. Konserve key enumeration can skip concurrent read failures; the inventory is a live
observation, not a quiescent completeness proof (`defaults.cljc:433-444`).

The blob mark is Seon's custom walk plus Datahike reachability, **not** automatic reachability of arbitrary
digest-valued datoms. Datahike GC takes the exclusive sweep permit and a writer safe point, marks branch/commit
roots, adds Seon's extension, and delegates candidate selection/deletion to Konserve
(`datahike/gc.cljc:127-177`, `konserve/gc.cljc:25-58`).
The safe point and commit retention cutoff are different. Retracting a fact does not reclaim a blob still named
by retained history. Holding an old DB value alone is not a new GC root.

### Every observed seon.cache kind

Columns: entries, estimated bytes, hits, misses, integer hit percent, accumulated load milliseconds.
One scan of AsyncCache.asMap and status took **1.2345 ms**, result/edd7930fa6111.
All 29 observed kinds are included, including cold kinds with zero hits. This process was recently restarted;
a cold kind's zero percent is not a recommendation to remove it.

| Kind | Entries | Bytes | Hits | Misses | Hit % | Load ms |
|---|---:|---:|---:|---:|---:|---:|
| seon.cluster/derivation-world | 2 | 19098148 | 2 | 2 | 50 | 39 |
| seon.cluster/file-derivation-key | 981 | 2769984 | 976 | 981 | 49 | 334 |
| seon.cluster/file-schema-keys | 498 | 2026176 | 1457 | 996 | 59 | 28 |
| seon.error.refusal/authored-namespace | 34 | 10880 | 70 | 34 | 67 | 8 |
| seon.fn/population-targets | 2 | 17386276 | 8 | 2 | 80 | 23 |
| seon.health/derived | 15 | 3866368 | 7 | 31 | 18 | 5496 |
| seon.health/problems | 1 | 2304 | 3 | 1 | 75 | 3235 |
| seon.health/reads | 16 | 271872 | 8 | 32 | 20 | 0 |
| seon.health/replaced-roots | 4 | 3072 | 0 | 4 | 0 | 176 |
| seon.health/untested | 1 | 68864 | 3 | 1 | 75 | 2084 |
| seon.schema/canonical-keys | 1 | 326944 | 2685 | 1 | 99 | 1 |
| seon.schema/declaration-predicates | 3 | 11712 | 92 | 3 | 96 | 108 |
| seon.schema/declaration-projection | 3 | 17590668 | 92 | 3 | 96 | 448 |
| seon.schema/projection | 1 | 9698806 | 0 | 1 | 0 | 468 |
| seon.schema.datahike/decoded-edn | 9 | 11234 | 10 | 9 | 52 | 0 |
| seon.schema.datahike/vector-pull | 78 | 968256 | 1695 | 78 | 95 | 19 |
| seon.schema.edn/packaged-population | 5 | 6391400 | 106 | 5 | 95 | 407 |
| seon.sci.eval/base-context | 2 | 23212544 | 1 | 2 | 33 | 1534 |
| seon.sci.eval/loaded-digests | 5 | 7260416 | 3 | 5 | 37 | 637 |
| seon.sci.eval/loaded-function-rows | 2 | 22954356 | 0 | 2 | 0 | 73 |
| seon.task/file-declarations | 159 | 2540080 | 78 | 159 | 32 | 36 |
| seon.task/file-grammar | 2 | 17355812 | 1 | 2 | 33 | 11 |
| seon.task/task-basis | 1 | 9340258 | 3 | 1 | 75 | 12 |
| seon.task/tasks-tx | 3 | 30011008 | 2 | 3 | 40 | 364 |
| seon.test.runner/reach-declarations | 1 | 17143168 | 0 | 1 | 0 | 879 |
| seon.test.runner/reach-graph | 1 | 453312 | 0 | 1 | 0 | 2056 |
| seon.test.runner/reach-index | 1 | 907808 | 0 | 1 | 0 | 116 |
| seon.test.runner/reach-node | 2539 | 3912544 | 0 | 2539 | 0 | 52 |
| seon.test.runner/reach-schema-node | 2378 | 1807424 | 0 | 2378 | 0 | 181 |

The kind key names its owner. No blob-byte kind was observed: there is no evidence here that Seon caches each
blob a second time. Full datom text can appear in Datahike nodes and in derived Seon values; that is a potential
shared reference or decoded representation, not proven byte-for-byte heap duplication.
`seon.cache/retained` scans all entries (`src/seon/cache.clj:246-261`); do not call it once for every kind.

### Dependency caches and Seon holders outside that budget

| Owner / contents | Bound and observed occupancy | Evidence |
|---|---|---|
| Datahike shared node LRU | 8,192 entries, full; storage counters writes 2,181 / physical reads 21,822 / accessed 645,503 | `index/persistent_set.cljc:433-491`, `connector.cljc:299-308`; keys immutable addresses, shared by physical store |
| Datahike query results | 64 snapshot buckets / 1,000,000 shallow structural weight; observed 8 buckets / 568,588 weight / 4 open generations | `query.cljc:2489-2537,2679-2686`; weight is **not bytes** |
| Datahike concurrent queries | 0 active flights/callers/waiters; 496 owners, 490 stores, 6 skips, 2 saved computations; 7,522,625,525 compute ns cumulative | same query metrics; cached-hit total is not supplied by these counters |
| Parsed-query and query-plan caches | 100 entries each maximum | `query.cljc:62,2444-2449`; separate products, not a second result store |
| Datom search LRU | max 5 DB caches; observed 0, disconnected dial above | `db/search.cljc:17-26`; inner limit counts datoms |
| Schema metadata reads | max 1,024; observed 11 | `schema_cache.cljc:8`, `config.cljc:15` |
| Schema write-known keys | max 1,024 stores × 1,024 keys; observed 2 store entries | `schema_cache.cljc:11-20`; keys prevent unnecessary durable schema writes, GC clears them |
| Date-to-tx resolution | reset when prior count exceeds 2,048 (can hold 2,049) | `query/execute.cljc:755-795`; cached numbers, separate temporal lookup |
| Konserve generic LRU | **absent on default**; optional library cache defaults to 32 entries | `konserve/cache.cljc:19-25`; `datahike/store.cljc:25-32` deliberately avoids unused wrapper |
| Konserve binary cache | none from generic cache | `konserve/cache.cljc:200-201` delegates binary operations directly |
| DB projection LRUs | max 24 + 4; observed full; four distinct realized value identities across 28 keys | `src/seon/db.clj:1441-1458,1794-1847` |
| SCI program identity LRU | max 256; observed 62 | `src/seon/sci/eval.clj:3103-3112` |
| SCI refusal-recording LRU | max 256; observed 0 | `src/seon/sci/eval.clj:3352-3356` |
| Projection compiled products | basic cache, no byte bound; observed 1,437 entries in one projection | `src/seon/schema.clj:380-445`; dies with owner, may grow after central weight was computed |
| Call-preparation snapshots | 24 entries per projection | `src/seon/call_preparation.clj:382-403` |
| Destructive test reach | 4 entries per projection | `src/seon/test.clj:414-420` |
| Render evidence | context-owned atom, no byte limit found | `src/seon/render.clj:1692-1719` |
| SCI live results and program snapshot | context-owned, no live-result bound; snapshot stores program rows | `src/seon/sci/eval.clj:579-598`, `src/seon/sci/kernel.clj:218-248` |
| Manifest roots | process memoize keyed by checkout path | `src/seon/fn/analyzer.clj:255-258`; process-lifetime assumption, no entry/byte dial |
| Per-call accumulation/memos | lifetime of call, not persistent cache | `src/seon/db.clj:4231,5295`, `src/seon/fn.clj:3485,3660`, `src/seon/test/runner.clj:1735` |
| Operational atoms | loaded identity, schema fallback counts, process/agent contexts and pending work | `sci/eval.clj:1017`, `schema.clj:1026`, `cluster/agent.clj:615-621`; do not treat live resources as evictable derivations |

Malli's validators belong to its compiled schema objects (`reference-code/malli/src/malli/core.cljc:353-361`,
`:2672-2686`). The inspected projection census contains no `seon.db/write-validator` kind: do not repeat an
older note's claim of 1,262 duplicate validators as a live finding. There can still be duplicate compiled objects
across distinct projections. Moving a registry-owning key to a global cache can extend that registry's lifetime;
measure ownership before consolidation. Per-projection growth must be included in its budget or its derived
products moved under the existing central budget; no new eviction implementation.

Datahike node “accessed” and “reads” count different operations. Their ratio is **not** a certified hit rate.
Dependency retained bytes and exact hit rates absent from installed metrics remain unknown; no deep heap walk
was performed to invent them.

### Installed digest attribute census

37 schema names resolve to the collector's digest shape; only 19 are installed Datahike attributes.
The populated four are in finding 6, including the non-blob read-result hashes.
Installed but empty: `:my.edit/after-digest`, `:my.edit/before-digest`, `:my.edit/expected-digest`,
`:my.fs/after-digest`, `:my.fs/before-digest`, `:my.shell/stdin-blob-digest`,
`:seon.ai.attempt/reasoning-blob`, `:seon.dev.mcp.artifact/digest`, `:seon.dev.mcp.artifact/id`,
`:seon.effect/result-blob`, `:seon.eval/value-digest`, `:seon.test.accretion/report-blob`,
`:seon.test.failure/expected-blob`, `:seon.test.failure/message-blob`, `:seon.turn/reply-blob`.
Of these 19, only artifact/digest has noHistory. The missing collection member is additional to those 19.
Source/definition/program digests such as `:seon.program/definition-digest` are identities of content, not
evidence of Konserve keys. They must never enter the blob inventory just because their strings have 64 characters.

## Smallest useful audit, now and as of t

Use the current entity/evaluation and its declared schemas, the existing db pull/history/as-of entrance,
Konserve metadata/existence/range reads, and Caffeine's existing map/policy observation.
Do not add a blob catalog, per-cache-entry database facts, another hash, or a second storage traversal.

1. Given the entity id and immutable database value, pull its declared facts and owned components. Classify
   inline text, blob-key attributes and byte-size attributes from their producer declarations, not from value
   shape. Show digest, declared payload size, and present/missing/unknown separately. The reference declaration
   missing in finding 1 is required schema knowledge, not a duplicate per-entity ledger.
2. For each distinct referenced digest, use its existing size fact; check Konserve existence/metadata only when
   the user asks about physical presence. Never read the payload to obtain a size. For historical rows lacking
   a size, answer unknown until a deliberate read measures it; do not persist a second fact opportunistically.
3. For an evaluation's live result, derive its existing result handle and use the cache's exact immutable key
   after the ruled result-cache change. Return present, absent or pending with the observation time and estimated
   weight. Today inspect the result owner; do not advertise an installed result-cache API.
4. For other derived products, have each existing producer expose its existing content-key derivation to the
   audit view. Query that exact key. A shared schema/program result is shared, not chargeable in full to every
   referring entity. A whole-cache kind table needs one map fold only; “all values related to entity E” is not
   currently indexed and must not imply a new global reverse index.
5. For as-of t, use Datahike as-of for retained datom history, or the exact retained commit when no-history
   attributes matter. Report whether that commit remains available. The blob digest in that value names immutable
   bytes, but presence **now** is a separate observation; Konserve last-write is not an access log.
   No-history values and purged/collected commits cannot be reconstructed merely by supplying t.
6. Historical cache residency is **not reconstructible** from database history. Caffeine is runtime-only and
   has no replay log. Return unknown for “was it cached at t?” unless a previously saved audit observation exists.
   Exact history would require recording cache events, contrary to the cheap-derivation requirement.

Price: per-entity data is O(entity datoms + owned components + distinct referenced blobs); exact known cache keys
are expected O(1) each. Additional permanent memory/facts: zero beyond genuine reference/size declarations and
the already ruled producer size facts. Current kind census: 1.23 ms at thousands of entries. Current scalar
producer census: 28.31 ms. Blob metadata requests add one file operation each; latency depends on residency.
Proposed acceptance budget: <50 ms warm for an ordinary entity with at most ten blobs, <1 s hard diagnostic bound;
these are targets, not measured promises. Whole-store history/root enumeration belongs to maintenance.

**OWNER DECISION — historical cache information:** recommend “unknown unless explicitly observed” (zero event
writes); alternatively store occasional compact audit summaries (bounded rows, sampling gaps remain); or record
every admission/eviction/hit (O(cache activity) writes and retention cost, not recommended on a 4 GB heap).
The view can ship without choosing the expensive third option.

Audit slice files: existing db/render/status owners and their schemas, after reference declaration and result-cache
slices. Proposed proof: same entity at two retained times shows the right digest and byte size; a cache eviction
changes only the current runtime observation; a no-history/collected value and historical cache request answer
unknown; measuring presence never reads blob payloads. Budget: 1 day, no new store or counter.

## 16 GB machine, 4 GB heap

There is no evidence here that this workload is optimal at 4 GB: it was measured with a 10.24 GiB heap maximum.
The small blob population is not the current heap pressure. A 512,000,000-byte estimated central budget is
about 11.9% of a 4 GiB heap, before Datahike, compiled code/schema objects, SCI forks, active evaluations and
unbudgeted result retention.

Recommended acceptance allocation for a later isolated 4 GiB run, **not a configuration change made here**:
about 1.25 GiB shared program/schema/idle runtime, at most 0.5 GiB central derived values including live results,
about 0.5 GiB calibrated Datahike retained nodes/results, leaving 1.75 GiB for active computation and collection
headroom. These are planning allowances, not measured class sizes or new per-owner quotas.
Keep the existing 8,192-node dial until measurement says its working set can shrink: reducing it blindly can
cause each branch to reread and hold private node copies. Query weight is structural, so its 1,000,000 limit
cannot be presented as one megabyte.

Proof must use a committed archive on an isolated store, representative concurrent agents and long enough
history to reach steady retention. Measure heap after collection, allocation per turn, pause time, cache
occupancy/misses, branch retirement and retained handles. Double agent count and confirm cost follows active
work and bounded retained values; ordinary operations remain below one second and changed-input work does not
become whole-program work. A single idle post-GC observation below 1 GB is encouraging, not that proof.
Do not force GC or dump all objects in the shared default JVM for a per-entity audit.

## Landing and verification

Only this research Markdown changes; source/test delta is zero. New proof-test names described above are
proposals, not claimed installed APIs. Existing tests were named by var where possible.

Working-tree lint: `clj-kondo --lint src script test --cache false --parallel`,
7,230 ms, **0 errors / 1,470 warnings**, exit 2 due to warnings. No source was changed to suppress them.
The required commit command is
`bin/lane commit storage-audit-astra -m "storage-audit-astra: measure storage ownership and collection gaps" --test seon.cache-test/kind-status-names-its-kind-and-states-hit-and-byte-shares docs/research/agent-platform/2026-10-08/storage-audit/storage-audit-astra.md`.
That existing var checks the status data used in this audit; it does not prove the proposed fixes. No new test
is appropriate for research prose (testing skill §1). A successful commit is conditional on this command's
candidate check. Broad tiers belong to root. No pre-existing red is asserted without a stored test result.

**Completed:** commit `bd0bfcbf1` added this note (586 lines, source/test delta 0). Candidate check
`5aedfa7010f0af17588124b40d40c138c90aec44`, run `ea5227ed23ff`: executed 1, reused 0,
3 assertions passed, 0 failures, 0 errors, passed true. Runner total 5,929 ms; operator test phase 6,171 ms.
Whole check 181,258 ms: slot wait 62,386 ms, prepare 73,327 ms, boot 62,779 ms (ready 55,976 ms),
publication 10,158 ms, adoption 866 ms. These phases overlap or nest and must not be summed.
The check's source publication reported 138 adopted identities from its HEAD candidate; this is not a claim that
the documentation itself defines program entities. The installed check waited for its two-JVM limit
(`script/seon/operator.clj:1516-1555`), a foreign scheduling boundary; no other lane was inspected or interrupted.
The check owns disposal of its throwaway JVM and root and returned successfully. No lane-owned JVM was launched
outside that command. The follow-up recording these results changes only prose; the same program proof is reused.

Measurement corrections, not product defects: one malformed local let was refused by the reader
(result/ec3e62931589f); an initial count treated an unknown-attribute refusal map as ten rows, so those counts
were discarded and the corrected census intersects installed schema attributes (result/ef9baa4505395).
An initial saved-result lookup included the handle's leading e and returned no inventory; its zeros were
discarded. The corrected lookup recovered all 50 binaries (result/e19f15136b9c5).
No missing measurement is reported as zero. Probe forms below preserve reproducibility without installing code.

## Exact read-only measurement forms

### Corrected installed digest census — result/ef9baa4505395

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) start (System/nanoTime) attributes (filter #(contains? (:schema database) %) (#'seon.cluster.registry/blob-digest-attributes database))] {:basis (:max-tx database) :rows (mapv (fn [a] (let [rows (seon.db/q database '[:find ?e ?v :in $ ?a :where [?e ?a ?v]] a)] [a (if (map? rows) rows (count rows)) (get-in (:schema database) [a :db/noHistory] false)])) (sort attributes)) :ms (/ (- (System/nanoTime) start) 1e6)})
```

Each was submitted as root/default/JVM. The physical-key census is slow: do not put it in a routine view or rerun it without an explicit maintenance budget. Saved result ids below refer to this JVM and are not durable object handles after restart.

### cacheProbe — result/edd7930fa6111

```clojure
(let [connection (seon.cluster.boot/connection "default") start (System/nanoTime) held @(var-get #'seon.cache/process) cache (:seon.cache/cache held) entries (frequencies (map (comp first key) (.asMap cache)))] {:rows (mapv (fn [[k v]] [k (get entries k 0) (:seon.cache/bytes v) (:seon.cache/hits v) (:seon.cache/misses v) (:seon.cache/hit-percent v) (:seon.cache/load-ms v)]) (:seon.cache/kinds (seon.cache/status))) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### dependencyProbe — result/e930a7fbf3093

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) start (System/nanoTime) storage (:storage (:store database)) node-cache (get (:store database) datahike.index.interface/node-cache-key)] {:basis (:max-tx database) :query (datahike.query/query-cache-metrics) :query-dials [datahike.query/*query-cache-size* datahike.query/*query-cache-weight-limit*] :node-cache {:entries (when node-cache (count @node-cache)) :class (class node-cache) :storage-keys (vec (keys storage)) :stats (some-> storage :stats deref)} :search-cache {:snapshots (count @datahike.db.search/db-caches) :effective-cache-size (get-in database [:config :cache-size]) :declared-search-cache-size (get-in database [:config :search-cache-size])} :schema-caches {:reads (count @datahike.schema-cache/schema-meta-cache) :write-stores (count @datahike.schema-cache/schema-write-caches) :limits [datahike.config/*schema-meta-cache-size* datahike.config/*schema-write-cache-max-db-count*]} :konserve-cache? (contains? (:store database) :cache) :blob-attributes (vec (sort (#'seon.cluster.registry/blob-digest-attributes database))) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### physicalProbe — result/ee42025c96255

```clojure
(let [connection (seon.cluster.boot/connection "default") start (System/nanoTime) store (:store @connection) ks (konserve.core/keys store {:sync? true}) binary (filterv #(= :binary (:type %)) ks)] {:at (java.util.Date.) :store-key-count (count ks) :types (frequencies (map :type ks)) :binary binary :ms (/ (- (System/nanoTime) start) 1e6)})
```

### blobSizeCorrected — result/e19f15136b9c5

```clojure
(let [connection (seon.cluster.boot/connection "default") store (:store @connection) start (System/nanoTime) objects @(:seon.sci.eval/result-objects (:seon.sci.eval/ctx seon.batch/*world*)) inventory (get objects "e42025c96255") binaries (:binary inventory) sizes (into {} (map (fn [{digest :key}] [digest (konserve.core/bget store digest (fn [{:keys [input-stream size]}] {:physical size :payload (.available ^java.io.InputStream input-stream)}) {:sync? true})])) binaries)] {:captured? (some? inventory) :binary-count (count binaries) :payload-bytes (reduce + (keep :payload (vals sizes))) :physical-bytes (reduce + (keep :physical (vals sizes))) :sizes sizes :ms (/ (- (System/nanoTime) start) 1e6)})
```

### producerProbe — result/e5b875487698a

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) start (System/nanoTime) objects @(:seon.sci.eval/result-objects (:seon.sci.eval/ctx seon.batch/*world*)) sizes (:sizes (get objects "19f15136b9c5")) attributes (filter #(contains? (:schema database) %) (#'seon.cluster.registry/blob-digest-attributes database)) refs (into {} (keep (fn [a] (let [rows (seon.db/q database '[:find ?e ?v :in $ ?a :where [?e ?a ?v]] a)] (when (seq rows) [a rows])))) attributes) digests (set (mapcat #(map second %) (vals refs)))] {:basis (:max-tx database) :rows (mapv (fn [[a rows]] (let [ds (set (map second rows))] [a (count rows) (count ds) (reduce + (keep #(get-in sizes [% :payload]) ds)) (count (remove sizes ds))])) refs) :distinct-current-blobs (count (filter sizes digests)) :distinct-current-bytes (reduce + (keep #(get-in sizes [% :payload]) digests)) :not-referenced-by-default (count (remove digests (keys sizes))) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### reachProbe — result/e19be54195ef3

```clojure
(let [connection (seon.cluster.boot/connection "default") start (System/nanoTime) branches (konserve.core/get (:store @connection) :branches nil {:sync? true}) digests (seon.cluster.registry/referenced-blobs connection branches true) objects @(:seon.sci.eval/result-objects (:seon.sci.eval/ctx seon.batch/*world*)) sizes (:sizes (get objects "19f15136b9c5"))] {:branches (count branches) :root-digests (count digests) :existing-blobs (count (filter sizes digests)) :referenced-bytes (reduce + (keep #(get-in sizes [% :payload]) digests)) :unreferenced-blobs (count (remove digests (keys sizes))) :unreferenced-bytes (reduce + (keep #(get-in sizes [% :payload]) (remove digests (keys sizes)))) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### vectorProbe — result/e47ffa20a15cd

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) start (System/nanoTime) attrs (#'seon.cluster.registry/blob-digest-attributes database)] {:content-blobs-selected? (boolean (some #{:seon.effect/content-blobs} attrs)) :content-blobs-installed (get (:schema database) :seon.effect/content-blobs) :content-blobs-form (seon.db/q database '[:find ?f . :where [?e :seon.schema/key :seon.effect/content-blobs] [?e :seon.schema/form ?f]]) :content-blobs-current (seon.db/q database '[:find ?e ?v :where [?e :seon.effect/content-blobs ?v]]) :retention (seon.db/q database '[:find ?v . :where [_ :seon.config.db/snapshot-window-ms ?v]]) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### allocationProbe — result/ec5fba7f442f6

```clojure
(let [connection (seon.cluster.boot/connection "default") store (:store @connection) digest "168c400273605dcf1d7767af15df8e2ee839969f4fab20075e5e2bb15caed07a" bean (java.lang.management.ManagementFactory/getThreadMXBean) thread-id (.threadId (Thread/currentThread))] (mapv (fn [[operation f]] {:operation operation :samples (mapv (fn [_] (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) start (System/nanoTime) value (f)] {:ms (/ (- (System/nanoTime) start) 1e6) :allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) before) :size value})) (range 5))}) [[:bget-one-byte #(konserve.core/bget store digest (fn [{:keys [input-stream]}] (.read ^java.io.InputStream input-stream)) {:sync? true})] [:range-one-byte #(alength ^bytes (seon.blob/read-chunk connection digest 0 1))] [:get-text #(count (seon.blob/get connection digest))]]))
```

### strayProbe — result/e077412e87587

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) ctx (:seon.sci.eval/ctx seon.batch/*world*) projection (seon.schema/projection-from-database database) compiled (:seon.schema.projection/compiled projection) start (System/nanoTime)] {:projection-lru-entries (count @(var-get #'seon.db/projection-cache)) :value-projection-lru-entries (count @(var-get #'seon.db/value-projection-cache)) :program-identity-entries (count @(var-get #'seon.sci.eval/program-identity-cache)) :refusal-entries (count @(var-get #'seon.sci.eval/refusal-recording-cache)) :projection-compiled-count (count @compiled) :compiled-key-kinds (frequencies (map #(if (sequential? %) (first %) %) (keys @compiled))) :root-live-results (count @(:seon.sci.eval/result-objects ctx)) :caffeine-recording? (.isRecordingStats (.policy (.synchronous (:seon.cache/cache @(var-get #'seon.cache/process))))) :ms (/ (- (System/nanoTime) start) 1e6)})
```

### memoryProbe — result/ecc49e1047766

```clojure
(let [connection (seon.cluster.boot/connection "default") ctx (:seon.sci.eval/ctx seon.batch/*world*) objects @(:seon.sci.eval/result-objects ctx) held @(var-get #'seon.cache/process) cache (:seon.cache/cache held) projection-cells (concat (vals @(var-get #'seon.db/projection-cache)) (vals @(var-get #'seon.db/value-projection-cache))) realized (filter #(and (instance? clojure.lang.IPending %) (realized? %)) projection-cells)] {:at (java.util.Date.) :root-results {:total (count objects) :gone-markers (count (filter #(and (map? %) (:seon.eval/unstored %)) (vals objects))) :classes (frequencies (map #(some-> % class .getName) (vals objects)))} :projection-cells {:keys (count projection-cells) :distinct-realized-values (count (set (map #(System/identityHashCode @%) realized)))} :cache-entry-count (.size (.asMap cache)) :cache-bytes (:seon.cache/weighted-bytes (seon.cache/status)) :heap (mapv (fn [pool] (let [u (.getUsage pool) c (.getCollectionUsage pool)] {:name (.getName pool) :used (.getUsed u) :after-gc (when c (.getUsed c))})) (java.lang.management.ManagementFactory/getMemoryPoolMXBeans))})
```

### inlineProbe — result/e3102f8d61b94

```clojure
(let [connection (seon.cluster.boot/connection "default") database (seon.db/db connection) start (System/nanoTime) bean (java.lang.management.ManagementFactory/getThreadMXBean) thread-id (.threadId (Thread/currentThread)) before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) objects @(:seon.sci.eval/result-objects (:seon.sci.eval/ctx seon.batch/*world*)) blobs (:binary (get objects "e42025c96255")) sizes (:sizes (get objects "19f15136b9c5"))] {:binary-writes-by-day (mapv (fn [[day rows]] [day (count rows) (reduce + (keep #(get-in sizes [(:key %) :payload]) rows))]) (group-by #(subs (.toString (.toInstant ^java.util.Date (:last-write %))) 0 10) blobs)) :inline (mapv (fn [a] (let [vs (seon.db/q database '[:find [?v ...] :in $ ?a :where [_ ?a ?v]] a) sizes (mapv seon.blob/utf8-size vs)] [a (count vs) (reduce + sizes) (reduce max 0 sizes) (count (filter #(> % 4096) sizes))])) [:seon.eval/shown :seon.cluster.eval/output :seon.effect/result-edn :seon.error/data-edn]) :ms (/ (- (System/nanoTime) start) 1e6) :allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) before)})
```
