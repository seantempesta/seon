# Performance Change Evaluation: Datom Attribute Fressian Caching

## 1. Expected Impact on Recording Performance

**Prediction: Stay the same** (negligible delta, well within the sub-millisecond noise).

### Proportionality of the Work
In Seon, a warm recording transaction takes 21.6–28.2 ms. The work breaks down as follows:
- **Disk I/O / File Force (~9.65 ms)**: `FileChannel.force` (fsync) accounts for 35–45% of total commit time and is completely unaffected by node encoding.
- **Index Mutation and Structure (~5–8 ms)**: Applying the transaction across six persistent B-tree indexes (`eavt`, `aevt`, `avet`, `temporal-eavt`, `temporal-aevt`, `temporal-avet` under `:keep-history? true`) using `d/with` / `transact!`.
- **Serialization Overhead (~7–10 ms, the 71 of 107 CPU samples)**: Dominated by:
  1. In-memory data transformation: `node->map` in persistent-sorted-set calls `(vec (.keys leaf))` to copy up to 128 elements into a Clojure `PersistentVector`, followed by `(merge (node->map leaf) (node-config leaf))` to produce map metadata.
  2. Full database snapshot record serialization: serializing `db-to-store` (commit metadata, index roots, schema hashes) on every commit.
  3. Datom traversal: serializing all five fields (`e`, `a`, `v`, `tx`, `added`) and list framing for each datom.

### The Real Work for Datom Attributes
In an ordinary recording transaction, only 20–50 datoms are transacted. This updates roughly 15–25 leaf nodes across all six indexes:
- In `eavt`, datoms are clustered by entity `e`. A leaf contains multiple entities with varied attributes (`:seon.turn/id`, `:seon.turn/agent`, `:seon.turn/opened-tx`, etc.). Attribute repetition within a leaf is minimal.
- In `aevt`, datoms are clustered by attribute `a`, so an `aevt` leaf does contain datoms with the same attribute.
- However, Seon's index branching factor is 128, not 1000.
- Most importantly, Clojure's Fressian keyword handler (`write-named` in `clojure.data.fressian`) **already caches the namespace and name strings** (`(.writeObject w (namespace s) true)` and `(.writeObject w (name s) true)`).
- For repeated attributes, status quo Fressian was already writing 1-byte priority cache references for the namespace and name strings. Caching the `clojure.lang.Keyword` object itself saves only a single tag byte, a handler lookup, and a second string cache lookup—saving ~2 bytes and ~20 nanoseconds per datom.
- Across ~2,000 total datoms across all dirty leaves in a commit, this saves ~4 KB of serialized bytes and ~0.04 ms of CPU time. Against a 25 ms transaction with a 9.65 ms fsync floor, a 0.04 ms shift is ~0.16% of the work—completely unmeasurable.

---

## 2. Validity of the 1000 -> 1 Metric

**The 1000 -> 1 count is NOT a sufficient proof of a gain.**

### Why the Metric is Misleading
1. **Counts an isolated micro-step, not system latency**: Reducing encoder invocations from 1000 to 1 merely verifies that Fressian's `oldIndex` recognized the object; it does not measure CPU cycles, memory allocation, or transaction elapsed time.
2. **Unrepresentative synthetic topology**:
   - The test assumes a node of 1,000 datoms with 100% attribute homogeneity.
   - In Seon, the branching factor is 128 (a node never holds 1,000 datoms).
   - In `eavt` (the primary entity index), nodes have high attribute cardinality per entity, meaning consecutive datoms do not share the same attribute.
   - A recording transaction adds 20–50 datoms, not 1,000 identical datoms.
3. **Ignores baseline amortization**: Because `write-named` already string-caches the namespace and name components, the 1000 writes in the baseline were already fast single-byte cache lookups, not full string re-encodings.

### What Constitutes Sufficient Proof
In accordance with Seon design laws (AGENTS.md: *"Measure before and after. A design is judged by its measured cost against what the work must do, on real data, with the numbers in the commit message"*):
- Benchmark a realistic recording workload on the running cluster (e.g. repeated warm evaluation turns committing to Datahike).
- Measure end-to-end transaction wall-clock latency (the 21.6–28.2 ms warm recording duration) and JFR CPU profiles before and after.
- Prove a statistically significant reduction in transaction latency or Datahike serialization CPU samples on production-sized databases.

---

## 3. Added Costs and Risks

1. **Cache Lookup Overhead on Misses**:
   - For every distinct attribute or first occurrence in a node, `writeObject(a, true)` must query `InterleavedIndexHopMap.oldIndex(a)`.
   - On a miss, it writes code 205 (`PUT_PRIORITY_CACHE`)—adding 1 byte of wire overhead—and interns the keyword into the map.
   - In `eavt` nodes where attributes vary across entities, this increases serialization CPU and blob size.
2. **Priority Cache Eviction and Slot Dilution**:
   - Fressian's priority cache has limited single-byte slots (indices 0–31).
   - Interning `clojure.lang.Keyword` objects consumes priority cache capacity alongside strings (namespaces, names, string values), increasing cache contention and pushing frequent objects beyond index 31 into multi-byte reference encodings (code 204 + int32).
3. **Stored Encoding Invalidation**:
   - Persisted `.ksv` files in Konserve will contain priority cache tokens for datom keywords.
   - While compliant Fressian readers parse priority cache tokens, changing the byte representation of node blobs creates subtle format fragmentation across storage generations for zero measurable gain.

---

## 4. Recommendation

**Recommendation: DROP.**

### Rationale
- The proposal is an optimization trap: an artificial benchmark demonstrating a dramatic ratio (1000:1) on a micro-operation that contributes almost nothing to the measured bottleneck.
- JFR's 71/107 serialization samples reflect index tree copying (`(vec (.keys leaf))`), node map creation, full database metadata commits (`db-to-store`), and multi-index flushing across six trees under `:keep-history?`.
- Caching the keyword wrapper saves negligible CPU (~0.04 ms) and zero I/O time against a 9.65 ms fsync floor.
- Optimizations should target the true proportionality of the work (e.g. avoiding unnecessary index flushes, optimizing node vector allocation, or batching fsync), rather than micro-tweaking keyword serialization.

---

## Five-Line Summary

The proposed change will not noticeably speed up recording because commit latency is dominated by fsync (9.65 ms) and six-index B-tree maintenance.
The 1000-to-1 write count is a synthetic micro-metric on an unrepresentative 1000-datom single-attribute node, whereas real recording touches 20–50 datoms with high attribute diversity in eavt.
Clojure's Fressian keyword writer already caches namespace and name strings, so repeated attributes were already compressed to lightweight string references.
Caching the keyword object adds priority cache lookup overhead and slot pollution on misses while saving only an imperceptible ~0.04 ms per transaction.
The recommendation is to DROP the proposal and focus optimization on actual commit cost drivers: index tree copying, node allocations, and fsync frequency.
