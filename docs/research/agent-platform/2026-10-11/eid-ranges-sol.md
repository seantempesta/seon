# SOL assessment: entity ids across branches

2026-10-11. Independent assessment; the Opus eid assessment was not read.
Authority: `hybrid-agent-world.md`, especially P1; Datahike gitlink and checkout
`b2ffa4af0f555483e1a146a98c2546b0a42debad` (personal fork).

**Yes, we can fix allocation in Datahike.** Branches need disjoint allocation ownership,
not merely different initial `max-eid` values. I recommend pursuing store-issued allocation
blocks, with a local cursor separate from the observed high-water mark. This makes a newly
allocated entity's eid stable across this store's branches. It does **not** make every
logical identity canonical, or make complete historical datoms safe to replay unchanged.
The smaller immediate alternative is the agreed P1 identity/ref translation; keep that
until the fork proves allocation, upgrade and copy semantics together.

## What exists and what must change

| Area | Checked symbols and consequence |
|---|---|
| Space | `datahike.constants/e0` = 0, `tx0` = 536870912 (2^29), `emax` = `txmax` = 2147483647 (2^31−1). Ordinary allocation must stay below `tx0`; `emax` also spans transaction entities. There are 536870911 positive ordinary ids, less system/schema reservations. |
| DB | `datahike.db/DB`, `datahike.db.interface/-max-eid`: one scalar; FilteredDB/HistoricalDB/AsOfDB/SinceDB delegate it. `datahike.db/empty-db` starts at `e0` or `ue0`; `init-db` uses `init-max-eid` (highest ordinary indexed eid), and `get-max-tx` scans datom txs. These cannot reconstruct allocation ownership from the largest imported eid. |
| Allocate | `datahike.db.transaction/next-eid` is `(inc (:max-eid db))`; `allocate-eid` and `advance-max-eid` advance for any eid below `tx0`. `entity-map->op-vec` and `transact-tx-data` use this for missing ids, negative/string tempids and ref tempids. `with-datom` and `with-datom-upsert` also advance it on explicit datoms. Copying another range therefore moves the recipient's next allocation into that range today. |
| Upsert | `datahike.db.transaction/upsert-eid`, `retry-with-tempid`, `validate-datom-upsert`: identity upserts still select an existing eid; explicit differing eids with the same unique value refuse. Two independently created copies of one natural identity remain a semantic conflict. |
| Resolve | `datahike.db.utils/numeric-entid?`, `entid`, `entid-strict` accept positive numeric ids, resolve lookup refs via AVET and idents via `:db/ident`; no branch-range decoding is needed for reads. Explicit numeric writes are possible, so uniqueness is an allocator guarantee, not a promise about arbitrary caller-chosen ids. |
| Fork/rewind | `datahike.versioning/branch!` copies the selected stored DB and changes its branch config/secondary roots. It must issue fresh allocation ownership, also when forking an old commit. `force-branch!` must not resurrect a previously consumed cursor after rewind or copy another writable branch's ownership. Reopening the same current head preserves its cursor. |
| Merge | `datahike.versioning/merge!`, `datahike.writer/merge-db!`, `datahike.writing/merge-writer!`: caller supplies tx-data; the writer transacts it and attaches parents. None resolves eid collisions or computes a three-way merge. Allocator state belongs to the target, never to merged source facts. |
| Import/export | `datahike.migrate/export-db` exports sorted datom sequences, not allocator ownership; `import-db` replays Datoms and `update-max-tx` raises the tx watermark. `datahike.db.transaction/transact-entities-directly` (through `datahike.writing/load-entities`) deliberately remaps both eids and txs using its migration map. Neither is a new range-preserving import contract. Arbitrary foreign-store imports must retain remapping. |
| Stored DB | `datahike.writing/db->stored`, `stored->db`, `stored-db?`, `-create-database*` explicitly carry max-eid/max-tx; add optional allocator fields and legacy defaults here. `compact-head`/`resolve-head` indirect through commits. `create-commit-id` must include allocator state: equal content with different available ids is different writable state. |
| Serialization | `datahike.datom/Datom` already uses JVM longs; tx sign encodes added/retracted. `datahike.index.persistent-set/datom-write-handler` and `datom-read-handler` serialize vectors of native values. In-range ids need no datom codec rewrite. `datahike.transit/db->map`, `datahike.json/db->map`, `datahike.db/pr-db`, `datahike.remote/RemoteDB` and `datahike.codegen.pod` carry watermark summaries; `datahike.readers/db-from-reader` restores by commit or reconstructs from datoms. Writable reconstructed values need fresh ownership, not a copied summary. |
| Primary indexes | `datahike.datom/cmp-datoms-eavt`, `cmp-datoms-aevt`, `cmp-datoms-avet`; `datahike.index.persistent-set/insert`, `upsert`, `slice-from-to-tree`: numeric ordering works unchanged below tx0. Gaps do not create empty index nodes; range order groups allocation origins, not global creation time. Old/new numeric keys coexist without reindexing. |
| Secondary indexes | `datahike.db.transaction/update-secondary-indices` sends each effective datom; `datahike.versioning/branch-secondary-indices` already branches selected roots. `datahike.index.entity-set/entity-bitset-add!`, `entity-bitset-contains?`, `entity-bitset-from-longs` cast to int (RoaringBitmap). The existing 29-bit domain fits; widening to 64 bits needs a separate secondary-index/API audit and representation change. Every registered adapter still needs a high-id proof. |
| GC | `datahike.gc/reachable-in-branch` walks branch/commit parents and primary/secondary storage roots, not eid density. A permanent store allocation counter must be included in `datahike.gc/gc-storage!`'s reachable keys. Deleting a branch cannot recycle ids: copied entities and retained history may still use them. Keep P1's plain transaction, without source parents, so copy-back does not retain the source lineage. |
| Cost consumers | `datahike.query.estimate/estimate-pattern-with-bindings` divides attribute cardinality by max-eid; `datahike.query.plan/bound-aware-join-rows` uses it for coverage. Sparse high ids make both estimates wrong. `datahike.query/get-or-create-plan` buckets by max-eid; use population-sensitive inputs instead. `datahike.experimental.graph/graph-size` uses max-eid only in a content-qualified cache key, not as a dense array size. |

## Fork sketch and capacity

1. Keep `max-eid` meaning “largest observed ordinary eid”; add a persisted allocation
   block `{start, next, end}` to the writable DB. `next-eid` reads `next`; allocation advances
   that cursor only within its own block. Foreign explicit ids advance the watermark only.
2. Issue blocks durably from a monotonic **store** counter, through the existing store
   update/branch-publication owner (`datahike.versioning/update-branches-held!` uses
   `konserve.core/update`). No branch-name hash, random prefix, count of live branches or
   recycled branch number can prove uniqueness. Reservation precedes publication; a crash
   may waste a block, never issue it twice. Use the existing serialization mechanism.
3. Seed every newly writable branch from a fresh block, preserving inherited eids.
   Reserve another block when full through the same owner. Pure `datahike.core/with` remains
   speculative: evaluating the same immutable basis twice may allocate the same ids;
   persisting independent speculative outcomes needs independently reserved ownership.
4. Persist/restore the fields, include them in commit identity, retain the store counter
   through GC, and replace watermark-based cardinality assumptions at their producers.

Within today's 29 bits, an eid `(prefix << L) + offset` has these limits:

| Prefix/local bits | Total blocks | Slots per block |
|---|---:|---:|
| 9 / 20 | 512 | 1,048,576 |
| 12 / 17 | 4,096 | 131,072 |
| 16 / 13 | 65,536 | 8,192 |

Prefix zero loses id zero and system/legacy allocations. These are **lifetime allocation
blocks**, not reusable concurrent-branch slots. A fixed partition per attempt wastes its
unused tail and eventually exhausts the store. Smaller blocks with refill decouple branch
count from per-branch entity limits; size is an explicit policy/configuration decision.
A Datomic-like wider split is possible only by widening tx0 and all affected domains,
including temporal comparisons, Roaring ids, CLJS safe integers and downstream adapters;
JVM long fields alone do not establish compatibility. I would not combine that with P1.

## Existing stores and tx ids

No datom migration/reindexing is necessary for **future** unique allocation. On adoption,
reserve the legacy occupied prefix space using the maximum across all retained writable
heads/history being supported, seed their future cursors freshly, then persist the counter.
Existing already-colliding branch eids cannot be made unique without remapping; finish or
translate those branches using P1. Missing fields allow reading old snapshots, not allocating
independently from copied legacy cursors. Do not infer the reservation boundary from main
alone. Writable restore/import from outside this store needs new ownership/remapping.

`datahike.db.transaction/current-tx` is `inc` of db-before max-tx; `transact-tx-data`
and `transact-entities-directly` update max-tx. Both branches share tx ids today.
Giving transactions branch prefixes breaks numeric `as-of`/`since` ordering when an older
source tx has a numerically higher prefix; `datahike.db/as-of-pred` and `since-pred`
compare txs numerically. This is a separate temporal design, not an eid tweak.
For P1, emit accepted `[:db/add e a v]` / `[:db/retract e a v]` operations with target tx
provenance. Preserve eids and ordinary refs, **not** source datom tx/tx-entity ids. A ref to
an excluded runtime entity or source transaction still requires selection/translation.

## What Seon gains, and what remains

P1 can compare inherited and distinct new entities by `[eid attribute]`, preserve ordinary
ref values and identity-less component ids, and remove routine lookup-ref construction and
component reconstruction for transport. It still selects declared cluster facts, computes
three-way changes/retractions, checks reference closure, applies conflict rules and records
one target transaction. Unique identity collisions (for example two occurrence entities with
one signature) still need identity matching/coalescing and ref translation for that class.
Ranges identify allocation origin; they do not establish semantic sameness or resolve a
concurrent edit to the same inherited eid. Unchanged datom replay also overwrites history's
transaction attribution, even where entity ids are safe.

Allocation is O(1) arithmetic per entity, one durable reservation per block; branch creation
adds O(1) metadata I/O, no entity scan after upgrade. Index writes remain O(log datoms),
copy work proportional to selected changed facts. Allocation metadata is O(1) per branch
plus one permanent counter. Number magnitude changes serialization/compression constants;
fragmented ranges affect locality/bitmap containers. No modified-fork timing is claimed.

## Scratch evidence and landing

Raw isolated JVM, `clojure -M -e`, no connection to default and no disk database. Reproduce:
`datahike.core/empty-db` with schema `{:x/id {:db/unique :db.unique/identity}
:x/ref {:db/valueType :db.type/ref}}`, config `{:schema-flexibility :read :keep-history? true}`.
Call `datahike.core/with` twice on that basis with `{:db/id -1 :x/id "a"}` / `"z"`:
**both eid 1, both tx 536870913**. Seed a copy using `(assoc b :max-eid 1048576)`;
transact `[{:db/id -1 :x/id "s"} {:db/id -2 :x/id "r" :x/ref -1}]`:
**eids 1048577/1048578**, ref **1048577**. Replay its `:tx-data` onto `b`: ids/ref survive,
but the next ordinary tempid is **1048579**, proving the target cursor was stolen.
First create `"s"` on unseeded `b`, then replay seeded tx-data: **`:transact/unique`**.
Complete five-case probe: **8.63 ms first execution, 2.08 ms warm**; allocation memory
unmeasured. These test values prove present mechanisms, not an implemented range allocator.
The initial display attempt used `(vec datom)` and failed; corrected to `(into [] (seq datom))`.

Default health: running, `:faulted`, one existing open fault; no default store probe/writes.
Working-tree `clj-kondo --lint src script test --cache false --parallel`: **0 errors,
1,515 warnings, 5,218 ms**. Warnings are outside this documentation-only slice.
No functions/contracts/schema changed, so no reaching tests; the requested documentation
commit uses `bin/lane`'s documentation-only path (no publication or throwaway JVM).
Only this assessment is owned/changed; independent lane files were not read or edited.
Recommendation: prove store-issued blocks in the fork, then simplify P1 for eid-preserving
transport; retain identity coalescing for semantic duplicates and legacy/foreign data.
