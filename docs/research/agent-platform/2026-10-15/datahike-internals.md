---
title: "Datahike internals for Seon designers: commits, branches, structural sharing, caches"
type: research
date: 2026-10-15
status: study only (lane dh-internals, Opus); no source edits
basis: Seon a76a9c141 (loaded JVM of default), Datahike fork 62f94893, persistent-sorted-set 0.4.137
---

# Datahike internals for Seon designers

Read from source at the pinned fork and measured on default in JVM mode only (read-only: no transaction, no
branch created or deleted, one acquire/release of a connection to `:chain-f1f6dc8e6652`). Timings are warm
unless marked cold. "alloc" is the request-level `:seon.cost/alloc-mb` unless marked "thread" (the calling
thread's `ThreadMXBean` bytes, which undercounts konserve reads by about half). Every number below was
measured in this session unless it is marked **derived** or **unmeasured**. Companion evidence:
`datahike-seams-inventory.md` (every Seon base reader and direct Datahike call) and `own-changes-notes.md`
(the measured own-change set with the right base: 24 identities, done-refusals []).

## 0. The findings that matter most

1. **Transaction ids are per lineage, not per store.** Entity ids come from one store-wide allocator
   (`datahike.writing/eid-allocator`, fork commit 61bca1ed); tx ids do not: each branch continues from its fork
   value's `:max-tx`. Measured: main's commits since the merge base span tx 536872007..536872746, the chain's
   536872009..536873134, and **738 tx ids occur on both**. Any comparison of a t read on one branch against
   datoms of another (`:seon.branch/base-t`, `seon.cluster.registry/advanced-base-t`,
   `seon.cluster.source/branch-base-t`, a base value's `db/basis-t` handed to `seon.program/changed-identities`
   on another lineage) is meaningless. That is the shared root of today's three base failures; a t is only a
   position on the lineage it was read from. Measured consequence: `changed-identities` from the base ref's t to
   the chain head answers 685 identities; the right answer is 24 (`own-changes-notes.md`).
2. **A commit record costs about 2.4 MB to read.** `konserve.core/get` of a commit id decodes the whole stored
   db record: six fused index roots (`:fuse-index-roots? true`, Branch nodes of 39-101 keys, each with diff-buf
   slots) plus `:cache-revisions` (233 attribute revisions). Measured: 100 reads, 45 ms, 250 MB; 50 reads 23 ms,
   130 MB. So every lineage walk over N commits costs about 2.4 N MB: `seon.db/commit-at-t` from main's head to
   the merge base's t (740 commits) 297 ms; `seon.db/basis-revisions` on the chain (1,126 commits) 545 ms; the
   three together 1.2 s and **6.4 GB**. One branch's commits are one per transaction, so chains accumulate
   thousands. This is the likeliest Datahike-side share of `merge-main!`'s 8.9 GB (attribution **unmeasured**:
   merge-main! transacts, so it was not run here).
3. **The merge base is computable from the commit graph, and cheap once parents are cheap to read.** A
   bidirectional parent walk from the chain head and main's head found the merge base (main's commit
   `6acf6fed…`, max-tx 536872008) after 1,896 commit reads: cold 872 ms / 2.4 GB thread, **warm 12 ms** with a
   cid→parents map (1,893 entries). Nothing in the fork computes it today.
4. **The base ref is never on main's lineage.** `seon.cluster.registry/advance-base!` calls `d/force-branch!`
   with main's value and main's parents, which writes a new commit record (`:config :branch
   :base/chain-…`) whose cid differs from main's commit: a sibling, not an ancestor. A graph walk can never meet
   it. 125 of the store's 254 branches are such `:base/*` refs.
5. **Seon's merge to main is already a real merge commit; the forward merge is not.**
   `seon.cluster.source/accept-merge!` passes `:parents (conj merged e-id)` and `seon.db/transact!` routes any
   request with `:parents` to `d/merge-db!`. `seon.merge/merge-main!` writes a plain `db/transact!`, so the chain
   head's parents never name main and Datahike's graph still answers the old fork point.
6. **A structural diff proportional to the change works on this store.** A 40-line REPL prototype (section 2)
   skipping subtrees with equal addresses diffed EAVT (732,188 datoms) between the base ref and main's head
   (37 commits apart) in **13 ms, 11 MB, 127 nodes visited**, and between the merge base and the chain head
   (1,126 commits, ~66k differing datoms) in 302 ms, 425 MB thread, 2,453 nodes.
7. **Opening is already cheap.** `seon.cluster.store/acquire-branch!` (a `d/connect` to an unconnected branch):
   2.8 ms, 2.2 MB thread; release 0.5 ms. `d/commit-as-db` 0.7-0.9 ms, 1.3 MB thread (the record decode).
8. **The node cache is full.** One Caffeine cache per physical store, shared by every connection to it:
   15.6-16.0k nodes, weighted 511-512 MB of its 512 MB budget, ~49k evictions, hit rate 95%. Its weight is the
   weigher's estimate (`datahike.index.node-cache/node-bytes`), not measured retained heap.

## 1. Commits and branches

### What a commit is

`datahike.writing/commit!` builds the stored form with `datahike.writing/db->stored`, then writes, in one
ordered konserve batch (`konserve.core/multi-assoc`): the pending index nodes, the schema-meta value (if new),
the commit record under its cid (immutable), and last the mutable branch head.

- **The commit record** (key = cid, a UUID): `:config`, `:meta`, `:hash`, `:max-tx`, `:max-eid`, `:op-count`,
  `:schema-meta-key`, `:merkle-roots`, `:cache-revisions`, the six index roots `:eavt-key` … `:temporal-avet-key`
  (PersistentSortedSet roots detached from storage, carrying `_address`), and with root fusion the six root
  nodes inlined as `:eavt-root` … `:temporal-avet-root`. Measured on default: 21 keys; 233 attribute revisions.
- **`:meta`**: `:datahike/commit-id`, `:datahike/parents` (a set of cids), `:datahike/created-at`,
  `:datahike/updated-at`, `:datahike/id`, version keys.
- **The branch head** (key = branch keyword): since fork c9e57c6f and `datahike.writing/compact-head`, just
  `{:meta {:datahike/commit-id cid}}`; `datahike.writing/read-head` / `resolve-head` follow it to the record.
- **`:branches`**: the roster set, the GC discovery pointer, published last.
- **cid**: `datahike.writing/create-commit-id`. Here `:crypto-hash? false`, so it is a squuid over
  `[hash max-tx max-eid meta]`; node addresses are squuids too (`datahike.index.persistent-set/gen-address`),
  so **equal address ⇒ the same stored node**, but equal content does not imply equal address.
- **Parents**: an ordinary commit's parents are `#{branch}` resolved to the head cid
  (`datahike.writing/branch-heads-as-commits`, using the writer's in-memory head when it has one). A merge:
  `datahike.writing/merge-writer!` runs the ordinary `transact!` and puts `:datahike/merge-parents` (the given
  parents plus the connection's own branch) on the db meta; the writer loop (`datahike.writer/create-thread`)
  unions merge parents across the batch and calls `commit!` with them, so the commit's `:datahike/parents`
  has two or more cids. The merged-in parents' datoms do not enter the head's indexes except through the
  merge's own tx-data, which is one new transaction on the head's lineage.

### The operations

| Function | What it does (source) | Cost |
|---|---|---|
| `datahike.versioning/branch!` | under the roster permit: reads the source head or commit record, writes the new head (compact head when no secondary indexes, c9e57c6f), adds to `:branches` | one record read + two small writes (**derived**) |
| `datahike.versioning/merge!` / `datahike.api/merge-db!` | `datahike.writer/merge-db!` → `merge-writer!`; same basis fence and report validation as `transact!`; caller supplies the merged tx-data | one transaction |
| `datahike.versioning/force-branch!` | writes `db` as a NEW commit record on `branch` with the given parents (new cid), then the head | one commit (index flush + record) |
| `datahike.versioning/commit-id`, `parent-commit-ids` | read `[:meta …]` of a db value | free |
| `datahike.versioning/commit-as-db` | `k/get` cid + `stored->db` | 0.7-0.9 ms, 1.3 MB thread |
| `datahike.versioning/branch-history` | walks ALL ancestors, materializing every one as a db (`stored->db`) | 2.4 MB × ancestors: unusable on a chain (thousands) |
| `datahike.versioning/delete-branch!` | removes from `:branches` (refuses with an active connection); data stays until GC | one small write |

### The merge base

No function in the fork computes a lowest common ancestor. `branch-history` and `fork-database`'s `:at` walk
are the only graph walks, both full. The parents are readable from every record, so a walk works; what makes it
expensive is that each parent read decodes a 2.4 MB record.

`:max-tx` is not a generation number: tx ids are per lineage (finding 1) and one measured merge commit on main
(max-tx 536872330) has a parent from `:merge-28e40cade73a` with max-tx 536872332. A max-tx-ordered walk
happened to return the right base here but is unsound in general.

Smallest addition to the fork (`datahike.versioning`, ~45 lines):

1. **A generation number in the commit meta**: `commit!` and `force-branch!` stamp
   `:datahike/generation = 1 + max(parent generations)`. The writer holds its own previous commit's generation
   in memory as it holds `known-head-cid`; a merge parent costs the one record read `branch-heads-as-commits`
   already does for a keyword parent. ~8 lines.
2. **A cid → `{:parents :generation :max-tx :branch}` read that does not decode indexes**: either a small
   immutable sidecar key per commit written in the same ordered batch (`[:datahike/commit-graph cid]`, ~10
   lines), or a process-wide Caffeine map keyed by cid (records are immutable, so it never invalidates; ~1,900
   entries × ~200 B). The sidecar makes the cold walk cheap too; the map alone makes it cheap only warm.
3. **`merge-base [conn-or-store a b]`**: git's paint-down walk — pop the highest generation from one queue,
   mark it with the sides that reach it, stop when the newest commit reached from both sides is above
   everything left. Cost: the commits on either side since the base (here 1,126 + 740), at ~0.5 µs each with
   the sidecar or map instead of 2.4 MB and 0.45 ms each. ~25 lines.

Measured with a REPL prototype (cid map, no generations): cold 872 ms / 2.4 GB thread (1,896 record reads),
warm 12 ms. With the sidecar the cold case is **derived** at ~1,900 small reads ≈ 20-40 ms.

### Does Seon already write merge commits?

Yes for merges into main: `seon.cluster.source/accept-merge!` (`:parents (conj merged e-id)`, both the chain's
head and the candidate), with `seon.db/transact!` choosing `d/merge-db!` when the request carries `:parents`.
`seon.cluster.source/main-parent` and `merge-accepted` already read Datahike's lineage back
(`d/parent-commit-ids`). No for the forward merge: `seon.merge/merge-main!` transacts without `:parents`, then
moves the base ref (`advance-base!`) and the adoption record (`adoption-record-tx`) by hand.

## 2. Structural sharing and diff

- **Indexes**: six persistent-sorted-set B+trees (eavt, aevt, avet and their temporal twins, `:keep-history?
  true`), branching factor 128, diff-buf-size 32 (`seon.cluster.store/datahike-configuration`). A node is a
  `org.replikativ.persistent_sorted_set.Leaf` (sorted Datom keys) or `Branch` (child max-keys, child addresses
  `Branch.address(i)`, lazily restored children `Branch.child(storage, i)`, and per-child buffered edits
  `Branch.slots()`: a `Slot` whose `diff` is a sorted map of pending adds/removes for that child, so a child's
  effective content is its anchor node ⊕ its slot diff).
- **Storage**: each node is one konserve value under its address (squuid here), written immutable at commit;
  copy-on-write rewrites only the path to changed leaves, or only the parent's slot when the edit is buffered.
  Unchanged subtrees keep their address across commits and branches: every branch of the store shares every
  node it did not change.
- **c9e57c6f** ("Branch primary indexes by their existing commit pointer"): `branch!` now writes the new head as
  a compact head naming the source's existing commit record instead of copying the full record under the new
  branch key. Its commit message measured 611,136 → 6,368 bytes and 0.70 → 0.04 ms per head encoding. A branch
  is therefore one pointer: nothing about it is copied.
- **Diff proportional to the change**: nothing in the fork or PSS does it. The nearest is
  `datahike.index.persistent-set/mark-unseen` (GC marking that does not descend into an address already seen)
  and `datahike.datom/diff-sorted` (a sorted merge diff of two key sequences).

The function (prototyped at the REPL, ~40 lines in `datahike.index.persistent-set`): keep the unmatched
subtrees of each side; drop every address both sides hold (identical subtree); expand the highest-level
remaining nodes into children, collecting the keys of any child's slot diff on either side; when only leaves
remain, `diff-sorted` their keys. It is sound because removing a set of keys common to both sides does not
change the symmetric difference, and anchor ⊕ diff differs from anchor' ⊕ diff' only inside
symdiff(anchor, anchor') ∪ keys(diff) ∪ keys(diff'). It is exact on leaves and a superset only by slot keys.
Cost: the nodes on paths to changes, about (changed leaves) × depth (3 here), plus their decode.

| EAVT diff (732,188 datoms) | nodes visited | time | alloc |
|---|---|---|---|
| base ref → main head (37 commits) | 127 | 13 ms | 11 MB thread |
| merge base → chain head (1,126 commits; 7,273 / 59,076 datoms only-in, 1,959 slot keys, 7,935 entities) | 2,453 | 302 ms | 425 MB thread |

Not yet done: a key-range bound so the walk on AEVT visits only the program attributes' range (skip a child
whose max-key precedes the range start); and a property test against the full set difference on generated
stores (**unverified** beyond the argument above).

## 3. Opening a branch, as-of, history

| Operation (warm) | time | alloc (thread) |
|---|---|---|
| `seon.cluster.store/acquire-branch!` of an unconnected branch (`d/connect`) | 2.8 ms (1.8 ms again) | 2.2 MB |
| `seon.cluster.store/release-branch!` | 0.5 ms | 0.1 MB |
| `d/commit-as-db` / `seon.db/commit-database` | 0.7-0.9 ms | 1.3 MB |
| `d/branch-as-db` | 0.9 ms | 1.5 MB |
| first 10 EAVT datoms of a commit-as-db value | 1.2 ms | 1.3 MB |
| `d/datoms :aevt :seon.fn/sym` on a commit-as-db value | 4.8 ms | 5.2 MB |

Shared versus per connection (`datahike.connector/-connect-impl*`, `datahike.connections/reserve-connection-opening!`):

- **Shared per physical store**: the node cache (found from a live sibling's registry entry with the same
  threshold), the write hooks; **process-wide**: the schema-meta cache, the query-result cache, the plan cache,
  the eid allocator.
- **Per connection**: its own konserve store connection with its own `CachedStorage` (stats and
  pending-write atoms, freelist), a writer (`datahike.writer/create-writer`), its current db value, a
  query-cache generation. A connection to the same branch is reference-counted and shared.
- **Per db value**: its six root nodes held strongly (`stored->db` seeds fused roots into its own copy); child
  references are `:weak` because `:store-cache-bytes` is set (`datahike.index.persistent-set` handlers), so the
  rest of the tree is retained only through the node cache or an in-flight read.

`as-of` and `history` are filtered views over the same db value's current and temporal indexes: no copy
(**derived** from `datahike.api/as-of`, `history`; not separately measured).

## 4. Caches and memory

| Cache | Bound | Measured state |
|---|---|---|
| node cache (`datahike.index.node-cache`, Caffeine, per physical store) | `:store-cache-bytes` = `:seon.config.db/store-cache-bytes`, 512,000,000 by weigher estimate | 15.6-16.0k nodes, 511-512 MB weighted, 49k evictions, 2.9M hits / 0.16M misses |
| schema-meta read cache (`datahike.schema-cache/schema-meta-cache`, process-wide) | 1,024 entries, **count, not bytes** | 20 entries |
| schema write caches (per store) | 1,024 stores × 1,024 keys, count | not measured |
| query-result cache (`datahike.query/query-result-cache`) | 64 snapshot buckets and 1,000,000 units of **shallow** structural weight | 64 buckets, 423,134 weight |
| query and plan LRUs (`datahike.query/query-cache`, `plan-cache`) | count | not measured |
| commit records | none: every read decodes the full record | 2.4 MB per read |

Other connections: 6 registered (main, 2 agents, 3 `:db` connections of other stores); the two other stores'
node caches are empty.

Could ~250 branches explain a 2.5 GB floor? Not by themselves: a branch that no connection holds is a konserve
key and costs no heap; 254 branches → 6 connections. What a db value retains beyond the shared cache is its six
roots (up to ~1.3 MB decoded per value, **derived** from the decode allocation; retained size
**unmeasured**). So Seon-held db values (materialized commits, cached as-of views, results that capture a db)
scale the floor by count; the heap dump lane should count `datahike.db.DB` instances and their retainers. The
node cache contributes its 512 MB estimate. Not bounded by bytes: the schema caches (count), the query-result
cache (shallow weight: a large lazy result counts as small), konserve's own per-connection state, and any db
value a caller keeps.

## 5. Recommendations

| # | Change | Lines | Deletes in Seon | Cost before → after |
|---|---|---|---|---|
| A | **Forward merge as a merge commit.** `seon.merge/merge-main!` passes `:parents #{(d/commit-id main)}` on its transaction (the `seon.db/transact!` → `d/merge-db!` path already exists). | ~1 in Seon | `advance-base!` call in merge-main!; then (with B) the adoption-record move's role as a base | no new cost; the graph now answers the base |
| B | **One merge base: `datahike.versioning/merge-base`** with a generation number and a cheap parent read (section 1). Seon's base is `(merge-base chain-head main-head)`, read when needed, held nowhere. | fork ~45 | `registry/base-branch`, `base-commit-id`, `advance-base!`, `held-branches` (as a base reader), the 125 `:base/*` branches, `:seon.branch/base-t` with `branch-fork-entity`/`advanced-base-t`/`target-advanced-base-t`/`fork-of` as base, `seon.cluster.source/branch-base-t` and `merge-base`, `seon.turn/fork-commit`, `seon.test/fork-commit` + its `:seon.test/commit-facts` cache kind, `seon.test/change-base`, `:seon.task/merge-base` as a stored copy (a recorded merge commit stays history) | measured walk 872 ms / 2.4 GB cold → 12 ms warm; with the sidecar, cold **derived** ~20-40 ms |
| C | **Changed declarations since the base**: candidates from the structural diff of AEVT between the merge base and the head, bounded to the program attributes' key range, then the existing `seon.program/digest-map` + `three-way` filter. Replaces every t-based walk. | fork ~50 (diff + range bound) | `seon.program/changed-identities`'s t path, `seon.db/entities-changed-after` (scans the attribute's whole history: 7,821 sym datoms for 163 entities), `seon.db/basis-revisions` and `commit-at-t` as base readers | t walk from a foreign t: wrong (685 vs 24) and 33-55 ms / 58 MB plus 0.3-0.5 s / GBs when a bare t forces a lineage walk → EAVT prototype 13 ms / 11 MB for 37 commits of change; AEVT range-bounded **derived** lower |
| D | **Cheap commit-graph reads.** The sidecar (or cid map) of B also fixes every other lineage walk Seon still has. | (in B) | — | 2.4 MB/commit → ~0.2 KB/commit (**derived**) |
| E | **Cheap branch open**: already cheap (2.8 ms / 2.2 MB connect; 0.7 ms commit-as-db). Make no change; stop holding connections or base refs to make it cheaper. | 0 | — | — |

The one merge base, recommended design: **Datahike's commit graph is the only record of where a branch starts.**
`merge-main!` writes a merge commit whose parents are the chain head and main's head (A); `branch!` already
records the fork commit as the chain's first parent. The merge base of a chain is
`(datahike.versioning/merge-base conn chain-head-cid main-head-cid)`, computed on demand by the generation
walk over cheap parent reads (B), keyed by content (two cids → one cid, cacheable forever). The own change is
the structural diff between that base and the chain head, filtered by digest three-way (C). No t crosses a
lineage; no base ref, fork entity t, attempt-row copy or walked fork commit survives; and `git merge main` /
merge-to-main / done rule / step context all read the same one function.

## Verification limits

- `merge-main!`'s 8.9 GB was not reproduced (it transacts); the share due to lineage walks is inferred from the
  per-record cost.
- The structural diff is a prototype in the JVM REPL (not committed); its soundness rests on the argument in
  section 2 and was not checked against a full set difference.
- Node-cache bytes are the weigher's estimate; retained heap per db value and per connection are unmeasured
  (the heap-dump lane owns those).
- Thread allocation undercounts konserve reads (records decode partly off the calling thread): 1.3 MB thread
  vs ~2.4 MB request-level per record.
