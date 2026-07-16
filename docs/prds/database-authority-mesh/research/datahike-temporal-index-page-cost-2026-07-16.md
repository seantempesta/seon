---
type: research
status: complete
tags: [database, research, decision, flow]
---

# Datahike temporal `index-page` cost

## Decision

Datahike `f0ee54c22d70a20de0279996f93aea98c6a9d1df` is the right
correctness-first repair, but it is performance-incomplete for time-filtered
temporal pages.

Keep it for Unit 7 consumer migration: it restores correct native order and
cursor behavior without adding a Seon comparator, and current-head pages remain
bounded and lazy. Do not treat it as the final density implementation. Before
Unit 10 graduates, measure and replace its time-filtered path with a
Datahike-owned bounded ordered reducer over the existing current and temporal
index slices. Promote that work ahead of consumer migration only if the grown
database falsifier below shows an earlier-coordinate page on a migration hot
path exceeding its latency, allocation, or restored-node budget.

The closest dependency-native seam is not a Seon scan and not another
materialized database value. It is the existing combination of:

- exact lower and upper bounds passed to `IIndex/-slice` or `-rslice`;
- lazy current-plus-temporal merge through `distinct-datoms` or
  `distinct-datoms-desc`;
- the existing temporal wrapper/search context and the query engine's existing
  numeric transaction-cut resolution; and
- one `index-page`-owned reduction which preserves native index order and stops
  after `limit + 1` visible datoms.

This retains Datahike as the comparator, reconstruction, polarity, cursor, and
index-lifetime owner. Seon continues to receive only ordinary datoms.

## Dependency ledger

- Datahike
  `f0ee54c22d70a20de0279996f93aea98c6a9d1df`, especially
  `src/datahike/index_page.cljc`, `src/datahike/db.cljc`,
  `src/datahike/db/search.cljc`, `src/datahike/db/utils.cljc`, and
  `src/datahike/query/execute.cljc`.
- Persistent-sorted-set `e1a17bbe` (`0.4.137`), especially
  `PersistentSortedSet.java`, `Seq.java`, and Datahike's
  `index/persistent_set.cljc` adapter.
- Optional Hitchhiker Tree `0.2.222` through Datahike's vendored adapter
  `src-hitchhiker-tree/datahike/index/hitchhiker_tree.cljc`. The exact
  Hitchhiker Tree dependency source is not mirrored under `reference-code/`;
  it must be mirrored before changing that dependency's iterator internals.
- The regression and paging fixtures in
  `test/datahike/test/index_page_temporal_test.cljc` and
  `test/datahike/test/index_page_test.clj`.

## What `f0ee54c2` fixes

`index-page` used to assume that `seek-datoms` and `rseek-datoms` remained in
the requested index order. That is true for raw and historical index streams,
but false after an `AsOfDB` or `SinceDB` reconstructs current state. Temporal
post-processing groups datoms by entity and attribute into a transient map and
then emits the map values; AVET order is no longer preserved
(`db.cljc:154-171,179-192`). Applying `take-while` for the requested prefix to
that unordered result allowed an unrelated datom to terminate the page early.

The fix obtains Datahike's own comparator, sorts time-filtered candidates in
the requested direction, and only then applies prefix, cursor, and page logic
(`index_page.cljc:39-53,119-159`). That is semantically preferable to any Seon
repair because:

- it uses `index-type->cmp-quick`, including transaction and `added` polarity
  for temporal indexes (`index_page.cljc:39-43`);
- cursor reconstruction still uses Datahike lookup-ref, ref-value, byte-array,
  and datom rules (`index_page.cljc:78-103`); and
- prefix and absent-cursor errors remain in the dependency
  (`index_page.cljc:120-149`).

The real AVET replacement fixture proves forward and reverse concatenation
after a cardinality-one change (`index_page_test.clj:91-130`). The synthetic
fixtures prove that sorting precedes prefix termination and that historical
cursor polarity survives (`index_page_temporal_test.cljc:20-61`). Those tests
are good semantic regressions.

## Actual execution cost

Let:

- `N` be all datoms in the selected current plus temporal indexes;
- `M` be the candidates from the seek point to the global end in the requested
  direction;
- `P` be the datoms actually inside the requested prefix;
- `U` be distinct transaction IDs among those `M` candidates;
- `R` be reconstructed or time-filtered candidates passed to the new sort; and
- `L` be the requested page limit, at most 200.

For an ordinary raw database, or a `HistoricalDB` with no time predicate, the
path is still appropriately lazy. PSS `slice`/`rslice` seeks through the tree
and its `Seq` restores children as they are consumed
(`PersistentSortedSet.java:176-257`; `Seq.java:50-88`). The temporal forward
and reverse merges are lazy (`db/search.cljc:272-297`;
`db/utils.cljc:229-261`). `index-page` then consumes only cursor verification
plus `L + 1`, as the existing fixture asserts (`index_page_test.clj:299-324`).

For an `AsOfDB`, `SinceDB`, or a historical wrapper carrying either time
predicate, the cost changes before `f0ee54c2`'s sort:

1. `contextual-seek-datoms` or `contextual-rseek-datoms` creates a current plus
   temporal stream whose other bound is the global index end, not the requested
   prefix end (`db.cljc:256-279`; `db/search.cljc:272-297`).
2. `post-process-datoms` calls `filter-txInstant` before the page sees that
   stream (`db.cljc:179-192`).
3. `filter-txInstant` consumes all `M` candidates to collect transaction IDs
   and performs a temporal EAVT lookup for every distinct transaction
   (`db/utils.cljc:277-289`). This happens even for a numeric cut, where the
   query engine already knows that direct datom transaction comparison is
   sufficient (`query/execute.cljc:753-762`).
4. Nonhistorical reads traverse the candidates again, allocate a transient
   map keyed by `[e a]`, retain vectors of all versions, sort each cardinality-
   many group's versions by transaction, and eagerly produce a vector
   (`db.cljc:154-171,184-192`; `tools.cljc:245-253`). Time-filtered historical
   reads still eagerly produce a vector, but skip state reconstruction.
5. `f0ee54c2` comparison-sorts all `R` results. Prefix termination and the
   `L + 1` page bound run only after that sort (`index_page.cljc:134-154`).

The resulting CPU bound is at least `O(M + transaction lookups + R log R)`;
cardinality-many group sorts add their own version-order work. Live allocation
is `O(M + R)` in the lazy merge cells, transaction set, reconstruction map and
vectors, eager result, and comparison sort. A cold PSS database reads the
nodes covering the entire `M` range in both indexes plus up to `U` EAVT seek
paths for transaction metadata. Cache warmth can remove storage reads, but not
the traversal, grouping, allocation, or sort.

The distinction between narrow and broad prefixes is therefore worse than a
normal eager prefix scan:

- A narrow forward prefix pays for the entire suffix beginning at its lower
  seek point. A narrow reverse prefix pays for the entire preceding side. A
  one-datom AVET prefix near the middle can process roughly half the database.
- An empty-components page is intentionally broad and processes the whole
  database side.
- Every cursor page repeats reconstruction and sorting over its remaining
  side. Concatenating many small pages can approach quadratic repeated work in
  the number of candidates divided by `L`.

### PSS and Hitchhiker Tree

PSS has the correct primitives for the final implementation. Its Datahike
adapter delegates directly to lazy `slice` and `rslice`
(`index/persistent_set.cljc:185-202`). The tree restores the root on demand and
each `Seq` descent restores only the selected child
(`PersistentSortedSet.java:57-75,176-257`; `Seq.java:50-88`). The current
time-filtered post-processing defeats that property by consuming the complete
one-sided range.

The Hitchhiker adapter is asymmetric. Forward `slice` is a lazy transducer over
`lookup-fwd-iter` (`index/hitchhiker_tree.cljc:60-69`). Its `-rslice` explicitly
realizes the entire bounded forward range into a vector and reverses it because
the dependency exposes no reverse iterator through this adapter
(`index/hitchhiker_tree.cljc:142-149,177-184`). Therefore:

- the proposed reducer can preserve HHT forward laziness and avoid the extra
  reconstruction vector and comparison sort; but
- truly lazy reverse HHT paging requires an exact dependency-native reverse
  iterator first. Merely moving code in Datahike cannot provide it.

PSS is the selected deployment index, so the HHT limitation does not block the
authority migration. HHT should not be advertised as having equivalent reverse
page density until its exact source is mirrored and that iterator exists.

## The bounded ordered seam

### Bounds and merge

`index-page` already resolves an exact prefix and cursor with Datahike's value
rules. Use those to build both ends of the raw range before temporal
post-processing. Feed the bounded current and temporal `-slice` streams to
`distinct-datoms` for forward pages and their `-rslice` streams to
`distinct-datoms-desc` for reverse pages. Both merge helpers already preserve
the temporal comparator and current-versus-history inclusion rules
(`db/utils.cljc:216-261`).

This immediately changes a narrow page from `M` work to work proportional to
the prefix history actually scanned. More importantly, keep the merged stream
ordered rather than passing it through the existing global `[e a]` assembly.

### Resolve one numeric cut

Extract and reuse the query engine's existing private date-to-transaction
resolution and numeric transaction predicate instead of repeating
`filter-txInstant`. The query engine already resolves a Date once with a bounded
cache and compares numeric transaction IDs directly
(`query/execute.cljc:680-719,721-762`). Seon's authority sends strict numeric
coordinates, so its normal path needs no transaction-entity lookup at all.
This is an internal owner extraction, not another temporal API or coordinate.

### Reconstruct in native order

Current temporal events for one exact `[e a v]` are adjacent in EAVT, AEVT, and
AVET order. Reduce each adjacent group through the numeric time predicate and
the existing same-transaction polarity order:

- a time-filtered historical page emits every matching event, preserving its
  `added` value and native order;
- a nonhistorical page emits the group's final visible added datom, or nothing
  when the final visible event is a retraction; and
- no-history current datoms continue through the existing current-index merge
  rule.

Grouping `[e a v]`, rather than the legacy whole-result `[e a]` map, is enough
because Datahike records a cardinality-one replacement as a retraction of the
old value plus an assertion of the new value
(`index/persistent_set.cljc:154-171`). It is also exactly the state unit for a
cardinality-many value. The reducer keeps constant group state, appends only
visible page datoms, and stops after `L + 1` results. No comparator sort is
required because groups arrive in the requested native index order.

Cursor resumption must reopen the complete `[e a v]` group containing the
cursor, reconstruct it, verify that it still emits the exact cursor including
polarity, skip that output, and continue. Starting at the cursor's exact
transaction would truncate its history and can produce a false state. This
rescan is bounded to one value group and retains the existing five-field wire
cursor.

For PSS the expected page cost becomes `O(log N + S)`, where `S` is the raw
current/history events consumed to produce and verify `L + 1` visible datoms,
with `O(L)` result memory plus constant reconstruction state. A broad prefix can
still scan many obsolete events when few values are visible; that is intrinsic
without a materialized index for the earlier cut. It no longer sorts or
reconstructs unrelated unconsumed candidates.

## Shortest grown-database falsifier

Build one persisted PSS database with history enabled, a cardinality-one
identity, and an indexed long value:

1. transact 50,000 entities in bounded batches and retain the initial cut;
2. replace the indexed value five times per entity in later bounded batches;
3. close and reopen with a deliberately small index-node cache;
4. request an `AsOfDB` AVET page at the initial cut for one exact middle value,
   `limit 1`, both forward and reverse;
5. repeat warm, then request an empty-components page and concatenate 100
   one-row cursor pages; and
6. run the same semantic fixture under HHT, recording its forward/reverse
   asymmetry rather than claiming parity.

Record wall time, allocated bytes, GC, comparator calls, candidates consumed,
distinct transaction lookups, PSS `CachedStorage` reads/restores/accesses, and
result/cursor equality. The existing storage owner exposes read/access counters
and a restore cost hook (`index/persistent_set.cljc:409-469`). Compare:

- raw head versus earlier `AsOfDB`;
- exact narrow prefix versus empty components;
- first page versus 100 concatenated pages; and
- `f0ee54c2` versus exact-prefix bounding and the ordered reducer.

The shortest falsification is not a throughput average. It is this invariant:

> A warm or cold exact one-value temporal page must not consume candidates,
> allocate retained structures, or restore nodes proportional to unrelated
> datoms before or after that value's prefix.

`f0ee54c2` should fail it decisively. The ordered reducer should make candidate
count proportional to the value's own temporal events plus one cursor group.

## Options and tradeoffs

### 1. Keep `f0ee54c2` through migration, then implement the reducer — selected

This preserves proven correctness, unblocks the remote database consumer cut,
and obtains realistic workloads before tuning. It is safe only with an explicit
Unit 10 gate and telemetry for temporal page latency/allocation. Current-head
pages and ordinary history pages retain their existing bounded behavior.

### 2. Bound the prefix before existing reconstruction, but retain sorting

This is a smaller interim dependency change. It removes unrelated global-tail
work for narrow prefixes and is easier to prove, but remains `O(P log P)` with
`O(P)` reconstruction and sort memory per page. Broad prefixes and repeated
small cursor pages remain poor. Use it only if the benchmark demands an urgent
low-risk mitigation before the full reducer.

### 3. Materialize an index for every temporal cut

Repeated broad pages could become ordinary index reads, but this duplicates
index memory and creates another cache/lifetime problem for every transaction
cut. It conflicts with the authority mesh's one-index-owner goal and is not
justified before the bounded reducer is measured.

### 4. Recreate order or scan in Seon

Rejected. Seon would have to duplicate Datahike comparators, lookup/ref/byte
resolution, temporal reconstruction, polarity, cursor validation, and backend
behavior. It would also force datom materialization across the authority seam.

## Implementation and proof plan

1. Add the grown PSS benchmark/falsifier without changing semantics. Capture
   candidates, transaction lookups, allocation, node reads, and cursor results.
2. Extract the existing query-engine numeric temporal-cut resolution into one
   shared internal Datahike owner used by query and paging. Preserve date-based
   behavior and nested `HistoricalDB` plus `AsOfDB`/`SinceDB` search contexts.
3. In the existing `datahike.index-page` owner, derive exact prefix bounds and
   reopen the cursor's complete `[e a v]` group. Do not add a public paging
   protocol or another result shape.
4. Merge bounded current and temporal slices with the existing forward/reverse
   helpers. Reduce adjacent exact-value groups and stop after `limit + 1`.
5. Differentially compare every produced page with the current eager semantic
   oracle across EAVT/AEVT/AVET, forward/reverse, cardinality one/many,
   add/retract/re-add, same-value updates, same-transaction polarity, numeric
   and Date cuts, `as-of`, `since`, history around each, no-history attrs,
   lookup refs, refs, bytes, empty/exact/broad prefixes, and every cursor split.
6. Add lazy-consumption fixtures for time-filtered pages analogous to the
   current raw-page fixture. Assert candidate counts for narrow prefixes and
   cursor resumption, not exact implementation allocation prose.
7. Run the persisted cold/warm benchmark. PSS graduation requires no unrelated
   prefix node traversal and bounded page allocation. HHT forward must preserve
   the same behavior; HHT reverse remains an explicitly measured limitation
   until the exact dependency source and a reverse iterator are present.

## Scheduling conclusion

This does not block Unit 7 consumer migration. `f0ee54c2` is semantically
correct, Seon no longer owns comparator logic, and the dominant current-head
page path is still lazy. It is a named Unit 10 performance graduation item,
not optional cleanup: the grown-database falsifier must run before density is
claimed. If a migrated web/data/debug surface routinely requests earlier
coordinates and breaches its budget, promote the dependency-native reducer to
the end of Unit 7 before that surface graduates.
