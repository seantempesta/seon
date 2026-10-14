# Blind Judgment: Caching Datom Attributes in Fressian (`writeObject` Cache Flag)

## 1. Expected Effect on Recording Speed

**Recording will stay the same or get slightly slower.**

What the work is proportional to:
1. **Keywords already cache their strings in Fressian.** In [`datom-write-handler`](file:///Users/sean/src/seon/reference-code/datahike/src/datahike/index/persistent_set.cljc#L545-L558), `(.-a d)` is written via `(.writeObject w (.-a d))` (`cache = false`). For a `clojure.lang.Keyword`, Fressian invokes `clojure.data.fressian/write-named`:
   ```clojure
   (defn- write-named [tag ^Writer w s]
     (.writeTag w tag 2)
     (.writeObject w (namespace s) true)
     (.writeObject w (name s) true))
   ```
   - `(.writeTag w "key" 2)` interns `"key"` in `FressianWriter.structCache` (`InterleavedIndexHopMap`) on the first keyword in the stream and emits a 1-byte packed struct code (`0xA0..0xAF`) on every subsequent keyword.
   - `(.writeObject w (namespace s) true)` and `(.writeObject w (name s) true)` **already pass `cache = true`**. Both the namespace `String` and name `String` are interned in `FressianWriter.priorityCache` on first use and never UTF-8 encoded again within that Fressian stream; furthermore, distinct attributes in the same namespace (e.g., `:seon.eval/*`) already share the cached namespace string on their very first occurrence.
   - Consequently, a repeated attribute keyword today costs **zero UTF-8 string encoding** and emits only 3 bytes (`[struct-code ns-cache-code name-cache-code]`, which Konserve's `:lz4` compressor collapses anyway) via three hop-map hits.
2. **Real Seon node sizes and index shapes.** Seon configures Datahike (`seon.cluster.store/datahike-configuration` in [store.clj](file:///Users/sean/src/seon/src/seon/cluster/store.clj#L188-L250)) with `:branching-factor 128`, `:diff-buf-size 32`, `:fuse-index-roots? true`, and `:keep-history? true`.
   - A PSS `Leaf` holds 64–128 datoms (not 1,000), and many commits buffer into the 32-entry diff slots on the fused root `Branch`es inside `db-to-store` ([writing.cljc](file:///Users/sean/src/seon/reference-code/datahike/src/datahike/writing.cljc#L235-L276)).
   - In `:eavt` and `:temporal-eavt` (and in `db-to-store`'s `:cache-revisions` map), datoms are grouped by entity, so each entity carries *distinct* attributes with low per-keyword repetition.
3. **Commit serialization cost is dominated by per-blob setup, whole-node projection, and value encoding.** On each serialized Konserve blob (`prepare-multi-assoc` / `update-blob` in [defaults.cljc](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/impl/defaults.cljc#L109-L162)), `FressianSerializer.-serialize` ([serializers.cljc](file:///Users/sean/src/seon/reference-code/konserve/src/konserve/serializers.cljc#L41-L54)) merges handler maps and constructs a fresh `InheritanceLookup` + `CachingLookup` (`ConcurrentHashMap`) + `FressianWriter` (re-walking class hierarchies per blob), `pss-fress/node->map` allocates intermediate maps/vectors for every flushed node and all 6 fused roots in `db-to-store`, `FressianWriter` UTF-8 encodes uncached datom values `(.-v d)`, and Konserve runs LZ4 compression before the 9.65 ms file `force`. Shaving two hop-map lookups on repeated `(.-a d)` keywords addresses a negligible fraction of the 71/107 serialization CPU samples while adding `priorityCache` overhead on first-seen attributes.

## 2. Is the 1000 -> 1 Handler Count a Sufficient Proof of a Gain?

**No.**

- **Why 1000 -> 1 is a trap:** Counting invocations of the `Keyword` write handler on a synthetic 1,000-datom single-attribute node only proves that Fressian's outer `priorityCache` short-circuited `write-named` 999 times. It mistakes handler-entry count for work done: `write-named` was *already* hitting `structCache` and `priorityCache` for `(namespace a)` and `(name a)` on all 999 repeats without re-encoding strings. Moreover, a 1,000-datom node with 1 attribute does not exist in Seon (`:branching-factor 128`, multi-attribute entities in EAVT).
- **What a sufficient proof requires:**
  1. End-to-end measurement on real recording transactions against the live store configuration (`:branching-factor 128`, `:diff-buf-size 32`, `:fuse-index-roots? true`, `:compressor {:type :lz4}`), using the writer's own per-commit thread counters (`measured-commit!` in [writer.cljc](file:///Users/sean/src/seon/reference-code/datahike/src/datahike/writer.cljc#L125-L134) / `:seon.cost/commit` `:cpu-ms` and `:alloc-mb` in [db.clj](file:///Users/sean/src/seon/src/seon/db.clj#L6095-L6110)) and wall-clock commit / eval-request latency.
  2. Before-and-after JFR CPU and allocation profiles showing that commit serialization samples actually drop and total commit CPU ms decreases on realistic multi-attribute transactions.

## 3. Costs Added by the Change

1. **`priorityCache` pollution and 1-byte index exhaustion (stored encoding + CPU):**
   - When `(.writeObject w (.-a d) true)` sees a keyword `:ns/name` for the first time in a blob, `FressianWriter.doWrite` interns the `Keyword` object at index $i$, emits `PUT_PRIORITY_CACHE` (`0xCD`, +1 byte), and then calls `write-named`, which *also* interns `"ns"` at $i+1$ and `"name"` at $i+2$ in the **same** `priorityCache`.
   - Every distinct attribute keyword now consumes **two** `priorityCache` slots (the `Keyword` plus its `name` string, alongside its `namespace` string) instead of one.
   - Fressian only has **32** single-byte packed priority-cache codes (`0x80..0x9F`, indices `0..31`; indices $\ge 32$ require 2–3 bytes: `GET_PRIORITY_CACHE` `0xCC` + packed int). Doubling cache consumption per attribute pushes the stream past index 31 after ~12–15 distinct attributes (common in EAVT leaves and fused `db-to-store` commit records), degrading subsequent cache hits—including shared namespace strings and map keys—from 1 byte to 2–3 bytes, and adds `0xCD` (`PUT_PRIORITY_CACHE`) bytes for attributes that appear only once in a blob.
2. **Extra hash-table work and resizing on write and read (cache lookup):**
   - `FressianWriter.writeAs` still calls `writeHandlerLookup.requireWriteHandler(null, a)` *before* checking `priorityCache` in `doWrite`.
   - On every distinct attribute in a blob, `priorityCache` (`InterleavedIndexHopMap`, initial capacity 16) performs an extra hash lookup + insertion (hash + `.equals` on `Keyword`) and resizes/rehashes at half the distinct-attribute count.
   - On deserialization (`FressianReader.readAndCacheObject`), every cached `Keyword` allocates/populates an extra slot (`UNDER_CONSTRUCTION` placeholder then `.set`) in the reader's `priorityCache` `ArrayList`.
3. **Compatibility / cross-target:**
   - Wire-compatible with standard JVM Fressian readers (`FressianReader` handles nested `PUT_PRIORITY_CACHE` via `UNDER_CONSTRUCTION` placeholders), so existing `:file` store readers and old blobs remain readable, and Merkle root hashes (`leaf-content-uuid` / `branch-content-uuid` in [persistent_set.cljc](file:///Users/sean/src/seon/reference-code/datahike/src/datahike/index/persistent_set.cljc#L274-L296)) are unaffected because they hash in-memory vectors before Fressian encoding. However, the JVM handler in `persistent_set.cljc` diverges from the `:cljs` handler and `datahike.kabel.fressian-handlers` (which serialize `(vec (seq d))`).

## 4. Recommendation

**Drop.** (Or if anyone insists, **measure first** on real recording commits before touching `reference-code/datahike`—do not commit on the 1000 -> 1 handler count.) The proposal is based on a false premise that `(.writeObject w (.-a d))` writes repeated attribute strings uncached, whereas `clojure.data.fressian/write-named` already caches both `(namespace a)` and `(name a)` in `priorityCache` and `"key"` in `structCache`.

---

Prediction: recording speed will stay flat or regress slightly; it will not meaningfully reduce the 21.6–28.2 ms recording transaction time.
Mechanism: `clojure.data.fressian` already caches keyword `namespace` and `name` strings (`writeObject ... true`) and the `"key"` struct tag, so repeated attributes already avoid UTF-8 string encoding.
Proof gap: the 1000 -> 1 test only counts `Keyword` handler entries on a synthetic 1,000-datom single-attribute node (Seon leaves have branching factor 128 and mixed EAVT attributes), not commit CPU or bytes.
Added cost: caching `Keyword` objects alongside their `namespace` and `name` strings doubles `priorityCache` slot consumption per distinct attribute, exhausting Fressian's 32 1-byte packed cache codes faster and triggering extra `InterleavedIndexHopMap` inserts/resizes.
Recommendation: drop the change (do not commit without end-to-end `:seon.cost/commit` CPU/allocation proof on real recording transactions).
