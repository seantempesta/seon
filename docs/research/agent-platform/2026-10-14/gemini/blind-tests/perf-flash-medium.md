# Performance Diagnosis: `seon.schema/canonical-database-attributes`

## 1. Function Doing the Repeated Work and Why It Repeats

**Function:** `seon.schema.datahike/compiled-attribute-selection`
(reached via `seon.schema/canonical-database-attributes` -> `seon.schema.datahike/database-attributes-in`, as well as directly via `seon.schema.datahike/database-attributes-core-in` and `seon.schema.datahike/database-attributes-for-in`).

**Why it repeats on an unchanged input:**
- `compiled-attribute-selection` is an unmemoized private function.
- Even though the supplied `projection` is an immutable map containing a pre-allocated cache holder (`:seon.schema.projection/compiled`, initialized via `seon.schema/with-compiled-cache`), `compiled-attribute-selection` never checks or populates this cache.
- On every call, it traverses all schema keys in `(:seon.schema.projection/forms projection)`, resolves each schema root with `malli.registry/schema`, inspects entity entries and storage properties, and invokes `seon.schema.datahike/declared-storable?` on candidate attributes.
- `declared-storable?` in turn invokes `seon.schema.datahike/compiled-storage` (which walks the schema AST with `malli.core/walk`) and `seon.schema.internal/reference-id`, neither of which are retained between selection calls.
- Because the derived selection `{::core ..., ::properties ..., ::attributes ...}` is never retained on the projection, every repeated call on the identical immutable registry value re-executes 6,475 calls to `declared-storable?`, 6,347 calls to `compiled-storage`, and 6,423 calls to `reference-id`, costing 12.22 ms and allocating 18.7 MB on the calling thread.

## 2. Smallest Correct Fix

**Mechanism:** `seon.schema/projection-cache-value`

**Implementation:**
Wrap the computation inside `seon.schema.datahike/compiled-attribute-selection` with `seon.schema/projection-cache-value`:

```clojure
(defn- compiled-attribute-selection
  "Select core and storable property attributes from retained canonical roots."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (schema/projection-cache-value
   projection
   [::attribute-selection (set (keys (:seon.schema.projection/forms projection)))]
   (fn []
     (let [registry (:seon.schema.projection/registry projection)
           roots (mapv #(mr/schema registry %) (keys (:seon.schema.projection/forms projection)))
           storage-properties #{:seon.db/identity :seon.db/unique :seon.db/index
                                :seon.db/component :seon.db/no-history? :db.secondary/only}
           core
           (reduce
            (fn [attributes [k root]]
              (let [properties (m/properties root)]
                (cond-> (if (true? (:seon.db/attributes (internal/entity-properties root)))
                          (into attributes
                                (comp
                                 (filter (fn [[attribute properties _]]
                                           (or (not (:optional properties))
                                               (declared-storable? projection attribute))))
                                 (map first) (filter qualified-keyword?))
                                (internal/entity-entries root))
                          attributes)
                  (and (qualified-keyword? k) (some #(contains? properties %) storage-properties))
                  (conj k))))
            #{} (map vector (keys (:seon.schema.projection/forms projection)) roots))
           properties
           (into (sorted-set)
                 (comp (mapcat #(keys (m/properties %)))
                       (filter qualified-keyword?)
                       (filter #(declared-storable? projection %)))
                 roots)]
       {::core (vec (sort-by str core))
        ::properties properties
        ::attributes (vec (sort-by str (into core properties)))}))))
```

**Why this is preferred:**
- `seon.schema/projection-cache-value` is Seon's existing, standard mechanism for caching derived values on an immutable projection without introducing external atoms, Caffeine instances, or mutable process state.
- Caching within `compiled-attribute-selection` transparently accelerates all entry points (`canonical-database-attributes`, `database-attributes-in`, `database-attributes-core-in`, and `database-attributes-for-in`).

## 3. What the Cache Key Must Contain

**Cache Key:** `[::attribute-selection (set (keys (:seon.schema.projection/forms projection)))]`

**Why:**
- `seon.schema.datahike/database-attributes-for-in` allows callers to pass a specific `forms` map, which it associates into the projection: `(assoc projection :seon.schema.projection/forms forms)`.
- Because `assoc` preserves the existing `:seon.schema.projection/compiled` cache holder atom on the projection map, both the full projection and any subset projection share the exact same underlying cache.
- If the cache key were a static tag (e.g. `::attribute-selection` alone), a subset query executed via `database-attributes-for-in` would cache its truncated attributes map and poison subsequent calls to `database-attributes-in` or `canonical-database-attributes`. Conversely, an earlier full call would cause subsequent subset queries to receive the full canonical attribute set.
- Including the set of form keys `(set (keys (:seon.schema.projection/forms projection)))` in the cache key guarantees that each subset of forms is keyed independently, ensuring a subset result can never replace or collide with the full canonical result.

## 4. How to Prove the Gain End-to-End

1. **Functional Correctness & Regression Proof:**
   Run reaching tests:
   - `test/seon/schema/datahike_test.clj`
   - `test/seon/schema_test.clj`
   - `test/seon/schema_usage_guard_test.clj`
   - `test/seon/boot_process_attribute_test.clj`
   - `test/seon/schema/vector_storage_test.clj`
   Verify that all 1,160 canonical database attributes match exactly in content and sort order, and verify that querying a subset via `database-attributes-for-in` yields only the subset's attributes while subsequent full calls still return all 1,160 attributes.

2. **Performance and Metric Verification:**
   On a held projection:
   - **First call (cold):** populates the cache (~12.22 ms, ~18.7 MB allocation).
   - **Second call (warm):**
     - Elapsed execution time drops from 12.22 ms to <0.01 ms (sub-microsecond cache hit).
     - Heap allocation drops from 18.7 MB to 0 bytes.
     - Function call counts for `seon.schema.datahike/declared-storable?`, `seon.schema.datahike/compiled-storage`, and `seon.schema.internal/reference-id` drop from ~6,400+ to **exactly 0**.
