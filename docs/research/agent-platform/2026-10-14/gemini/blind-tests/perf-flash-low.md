# Blind Performance Diagnosis: canonical-database-attributes

## 1. Which function does the repeated work and why it repeats on an unchanged input

The function that performs the repeated work is `seon.schema.datahike/compiled-attribute-selection` (called via `seon.schema.datahike/database-attributes-in`, `database-attributes-core-in`, and `database-attributes-for-in`, and reaching callers such as `seon.schema/canonical-database-attributes`).

It repeats on an unchanged input because `compiled-attribute-selection` is an unmemoized private function that performs a full scan over all canonical schema roots in `(:seon.schema.projection/forms projection)` on every call. For each schema root, it resolves the compiled schema via `(mr/schema registry ...)`, extracts entity entries and properties, and repeatedly invokes `declared-storable?`, which in turn calls `compiled-storage` and walks the schema definitions (triggering `seon.schema.internal/reference-id` to check references and cyclic entity declarations). Because the derived attribute selection map (`{::core ..., ::properties ..., ::attributes ...}`) is never retained in the projection's compiled cache, every subsequent call recomputes the entire scan and allocation from scratch.

## 2. The smallest correct fix, preferring a mechanism Seon already has

The smallest correct fix is to retain the result of `compiled-attribute-selection` in the projection's existing cache holder using `seon.schema/projection-cache-value`.

Specifically:
- In `seon.schema.datahike/compiled-attribute-selection`, wrap the computation in:
  ```clojure
  (seon.schema/projection-cache-value
   projection
   [::attribute-selection (set (keys (:seon.schema.projection/forms projection)))]
   (fn [] ...))
  ```
  (or cache at the entrance of `compiled-attribute-selection` using `projection-cache-value`).

This uses the existing mechanism `seon.schema/projection-cache-value` that is already used across `seon.schema.datahike` (e.g. for `::vector-shaped`, `::identity`, `::ref`) and `seon.schema`, avoiding any new cache, atom, or custom memoization facility.

## 3. What the fix's cache key must contain

The cache key must include:
1. A qualifying namespaced tag, e.g. `::attribute-selection` (or `::database-attributes`).
2. The exact population/set of forms being evaluated, specifically `(or (:seon.schema.projection/canonical-keys projection) (set (keys (:seon.schema.projection/forms projection))))` (or `(:seon.schema.projection/forms projection)` / its key set).

This is necessary because `seon.schema.datahike/database-attributes-for-in` calls `compiled-attribute-selection` on `(assoc projection :seon.schema.projection/forms forms)` where `forms` can be an arbitrary subset or alternate generation of authored forms. If the cache key were only a static keyword or the projection identity without the forms key-set, a subset query from `database-attributes-for-in` could cache a partial attribute list under a collision, or the full attribute list could be returned when a restricted subset was requested.

## 4. How to prove the gain end to end

1. **Micro-benchmark / Allocation and Timing Assertion**:
   - Hold an immutable projection instance: `(let [p (seon.schema/declaration-projection)] ...)`.
   - Call `(seon.schema/canonical-database-attributes p)` once to warm the cache.
   - Benchmark or measure the second call: verify that execution time drops from ~12 ms to sub-millisecond (typically < 0.05 ms) and allocation drops from ~18.7 MB to negligible (near 0 bytes).
2. **Call Count Instrumentation**:
   - Instrument `seon.schema.datahike/declared-storable?`, `seon.schema.datahike/compiled-storage`, and `seon.schema.internal/reference-id` (or track via invocation counters).
   - Verify that on the second call to `canonical-database-attributes` on the same projection, the call counts to these functions are exactly 0.
3. **Correctness Parity Across Callers**:
   - Verify that `database-attributes-in`, `database-attributes-core-in`, and `database-attributes-for-in` return identical results before and after the fix.
   - Specifically verify that calling `database-attributes-for-in` with a restricted subset of forms returns only the subset's attributes and does not poison or get poisoned by a call to `canonical-database-attributes`.
4. **Test Suite Verification**:
   - Run the reaching tests (e.g. `test/seon/schema/datahike_test.clj`, `test/seon/schema_test.clj`, and `bin/test test/seon/schema`) to ensure zero regressions across schema registration, database bridge derivation, and transaction encoding.
