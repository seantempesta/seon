# Performance Diagnosis: Repeated Work in `canonical-database-attributes`

**Commit analyzed:** `17ec81f0b^` (parent commit)  
**Target:** `seon.schema/canonical-database-attributes`, `seon.schema.datahike/compiled-attribute-selection`

---

## 1. Which function does the repeated work and why it repeats on an unchanged input

### The Function
The repeated work is performed by `seon.schema.datahike/compiled-attribute-selection`.

### What it does
`compiled-attribute-selection` computes the database-attribute selection for a given projection, returning `{::core ..., ::properties ..., ::attributes ...}`. On every call, it:
1. Iterates over all form keys in `(:seon.schema.projection/forms projection)` (thousands of schema declarations) to resolve compiled roots via `(mr/schema registry %)`.
2. Inspects entity schemas where `:seon.db/attributes` is true, traversing `seon.schema.internal/entity-entries`, which navigates `entity-maps` and invokes `seon.schema.internal/reference-id` (6,423 calls).
3. Evaluates optional entity attributes and storable metadata properties with `seon.schema.datahike/declared-storable?` (6,475 calls).
4. Each call to `declared-storable?` invokes `seon.schema.datahike/compiled-storage` (6,347 calls), which performs a recursive `malli.core/walk` fold over the compiled Malli schema AST to extract storage types, cardinalities, and persistence properties.
5. Builds and sorts the final attribute collections (`::core`, `::properties`, `::attributes`).

### Why it repeats on an unchanged input
Neither `compiled-attribute-selection` nor its callers (`database-attributes-in`, `database-attributes-core-in`, and `canonical-database-attributes`) retain or cache the derived selection map on the projection. Even though the input projection is an immutable, held value carrying an allocated compiled cache holder (`:seon.schema.projection/compiled`), `compiled-attribute-selection` re-executes the complete walk and AST traversal on every invocation, regenerating all 1,160 attributes from scratch at a cost of 12.22 ms and 18.7 MB of thread allocation per call.

---

## 2. The smallest correct fix (preferring an existing Seon mechanism)

### Fully Qualified Symbol
`seon.schema/projection-cache-value`

### The Fix
In `src/seon/schema/datahike.clj`, wrap the body of `compiled-attribute-selection` with `seon.schema/projection-cache-value`, retaining the derived map on the projection's existing `:seon.schema.projection/compiled` holder:

```clojure
(defn- compiled-attribute-selection
  "Select core and storable property attributes from retained canonical roots."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (schema/projection-cache-value
   projection
   [::attribute-selection
    (:seon.schema.projection/forms projection)
    (:seon.schema.projection/registry projection)]
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

### Why this is the smallest correct fix
- It changes only the private owner of the derivation (`compiled-attribute-selection`).
- All callers—`database-attributes-in`, `database-attributes-core-in`, `database-attributes-for-in`, and `seon.schema/canonical-database-attributes`—share the benefit immediately without interface changes or new cache atoms.
- Projections assembled without a cache holder gracefully fall through and compute correctly.

---

## 3. What the cache key must contain so a subset can never replace the full result

### The Subset Seam
`seon.schema.datahike/database-attributes-for-in` allows callers to select attributes for a specific subset generation of forms:
```clojure
(defn database-attributes-for-in
  [projection forms]
  (::attributes (compiled-attribute-selection
                 (assoc projection :seon.schema.projection/forms forms))))
```
Because `(assoc projection :seon.schema.projection/forms forms)` retains the exact same `:seon.schema.projection/compiled` cache object as the parent `projection`, any cache write into that holder is visible across both maps.

If the cache key were a static keyword (e.g. `::attribute-selection`), calling `database-attributes-for-in` on a subset would overwrite or populate `::attribute-selection` with the subset result. Subsequent calls to `database-attributes-in` or `canonical-database-attributes` would return the subset instead of the full 1,160 attributes. Conversely, if the full population were computed first, a call to `database-attributes-for-in` would return the full population rather than the subset.

### Required Cache Key Components
The cache key must contain:
1. A namespaced discriminator: e.g. `::attribute-selection`.
2. The specific forms map being selected: `(:seon.schema.projection/forms projection)` (or its key set).
3. The resolving schema registry: `(:seon.schema.projection/registry projection)`.

Key structure:
```clojure
[::attribute-selection
 (:seon.schema.projection/forms projection)
 (:seon.schema.projection/registry projection)]
```
Because the subset `forms` map is distinct from the full projection's forms map, their cache keys never collide, preventing any subset from replacing or shadowing the full attribute population.

---

## 4. How to prove the gain end to end

Rather than asserting arbitrary invented latency or allocation bounds, the gain is proven by measuring what the work is proportional to and verifying the elimination of redundant work:

1. **Behavioral Equivalence & Subset Isolation:**
   - Verify that `(canonical-database-attributes projection)` returns the identical sorted vector of 1,160 attributes before and after the change.
   - Prove isolation under both call orders:
     - Call `database-attributes-for-in` with a subset, followed by `canonical-database-attributes`: verify the full population is returned.
     - Call `canonical-database-attributes`, followed by `database-attributes-for-in`: verify only the subset's attributes are returned.

2. **Call Count Reduction to Zero:**
   - Instrument or counter-check calls on the second invocation with the same projection:
     - `declared-storable?`: drops from 6,475 calls to 0.
     - `compiled-storage`: drops from 6,347 calls to 0.
     - `reference-id`: drops from 6,423 calls to 0.
     - Inner derivation thunk execution: exactly 1 call total across repeated invocations.

3. **Measured Thread Cost on Real Data:**
   - Measure thread allocation and elapsed time on the calling thread:
     - Cold call: ~12 ms, ~18.7 MB allocation (proportional to full schema tree compilation and storable property filtering).
     - Warm call (second invocation): sub-microsecond cache lookup, 0 bytes thread allocation, returning the held vector directly.

4. **Integration & Reaching Tests:**
   - Execute the reaching test suite: `test/seon/schema/datahike_test.clj` (specifically `compiled-storage-navigation-reuses-retained-roots` and `canonical-population-native-parity`).
   - Verify through Seon's REPL pipeline (`seon.turn.batch/jvm-evaluate` or `eval_clj`) that turn call-profile facts report zero callee calls and negligible self-time for repeated attribute queries.
   - Run `script/seon/sentinel.clj` against its baseline to confirm system-wide health and no regressions.

---

## Summary
1. `seon.schema.datahike/compiled-attribute-selection` does the repeated work because it re-walks the entire schema population on every call without caching.
2. The smallest correct fix wraps `compiled-attribute-selection` in `seon.schema/projection-cache-value`.
3. The cache key must include `(:seon.schema.projection/forms projection)` and the registry so subset selections in `database-attributes-for-in` do not collide with full selections.
4. Proof requires zero callee calls on repeated invocation, 0 bytes allocation on the warm path, exact attribute parity, and verified subset isolation.
5. All public attribute queries share the cached result through the projection's existing compiled holder without adding new mutable state.
