# Blind Performance Diagnosis: `seon.schema/canonical-database-attributes` (`17ec81f0b^`)

## 1. Which function does the repeated work and why it repeats on an unchanged input

`seon.schema.datahike/compiled-attribute-selection` (`src/seon/schema/datahike.clj:237-269` at `17ec81f0b^`) does the repeated work. Every call to `seon.schema/canonical-database-attributes`, `seon.schema.datahike/database-attributes-in`, `seon.schema.datahike/database-attributes-core-in`, or `seon.schema.datahike/database-attributes-for-in` delegates directly to `(compiled-attribute-selection projection)` without retaining the derived selection map on the projection's runtime holder.

Although each compiled root schema in `(:seon.schema.projection/registry projection)` is retained and `seon.schema.datahike/compiled-storage` caches its storage result per compiled root via `(m/-cached compiled ::compiled-storage ...)`, `compiled-attribute-selection` itself is uncached and re-walks every root in `(:seon.schema.projection/forms projection)` on every call:

1. **Entity-entry traversal (`6,423` calls to `seon.schema.internal/reference-id`)**: For every root in `(:seon.schema.projection/forms projection)`, `compiled-attribute-selection` calls `seon.schema.internal/entity-properties` and (when `:seon.db/attributes` is `true`) `seon.schema.internal/entity-entries`. Both functions call `seon.schema.internal/entity-maps` (`src/seon/schema/internal.cljc:80-95`) with a fresh `#{}`, walking alias `:ref` chains and `:and` conjunctions and calling `reference-id` at every step; `entity-entries` also calls `seon.schema.internal/same-scoped-schema?` on inherited duplicate members, which again calls `reference-id` while walking child trees. None of `entity-properties`, `entity-entries`, or `entity-maps` is cached on the compiled root or projection.
2. **Repeated storable checks (`6,475` calls to `seon.schema.datahike/declared-storable?` and `6,347` to `seon.schema.datahike/compiled-storage`)**: `compiled-attribute-selection` calls `(declared-storable? projection attribute)` for every `:optional` member of every entity schema (without deduplicating shared optional attributes across entities) and again in the `properties` pass for every qualified keyword in `(mapcat #(keys (m/properties %)) roots)`. Each call to `declared-storable?` looks up `attribute` in `(:seon.schema.projection/registry projection)` and, when a compiled root exists (`6,347` of the `6,475` checks), calls `(compiled-storage root)` (which is an armed Var, so its entry and exit are counted and timed even on an `m/-cached` hit).
3. **Sorting and vector allocation**: Finally, it sorts `core` and `(into core properties)` by `str` and allocates fresh vectors on every call, totaling `12.22 ms` and `18.7 MB` of thread allocation on an unchanged projection.

---

## 2. The smallest correct fix

Wrap the body of `seon.schema.datahike/compiled-attribute-selection` in **`seon.schema/projection-cache-value`**, which stores derived values in the projection's own runtime cache holder (`:seon.schema.projection/compiled`, installed by `seon.schema/with-compiled-cache` and cleared whenever a new projection is derived via `dissoc projection :seon.schema.projection/compiled`).

```clojure
(defn- compiled-attribute-selection
  "Select core and storable property attributes from retained canonical roots."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (let [forms (:seon.schema.projection/forms projection)]
    (schema/projection-cache-value
     projection [::compiled-attribute-selection (keys forms)]
     #(let [registry (:seon.schema.projection/registry projection)
            roots (mapv #(mr/schema registry %) (keys forms))
            ...
```

Because `canonical-database-attributes`, `database-attributes-in`, `database-attributes-core-in`, and `database-attributes-for-in` all read `::attributes` or `::core` from `(compiled-attribute-selection ...)`, memoizing `compiled-attribute-selection` once through `seon.schema/projection-cache-value` fixes all four entry points at their single owner while preserving fallback derivation when `:seon.schema.projection/compiled` is absent.

---

## 3. What the fix's cache key must contain so a subset can never replace the full result

The cache key inside the projection's `:seon.schema.projection/compiled` holder must include the selected form domain—specifically **`(:seon.schema.projection/forms projection)`** (or **`(set (keys (:seon.schema.projection/forms projection)))`** / **`(keys (:seon.schema.projection/forms projection))`**, since `compiled-attribute-selection` only reads `(keys (:seon.schema.projection/forms projection))` from `forms` while resolving every definition from `(:seon.schema.projection/registry projection)`):

- **Why a bare keyword `::compiled-attribute-selection` is broken**: Callers that select attributes for a subset of schemas construct a narrowed map from a held projection via `assoc` or `update` on `:seon.schema.projection/forms` **without** removing `:seon.schema.projection/compiled`:
  - `seon.schema.datahike/database-attributes-for-in` (`src/seon/schema/datahike.clj:383-384`):
    `(compiled-attribute-selection (assoc projection :seon.schema.projection/forms forms))`
  - `seon.turn.batch/attribute-declarations` (`src/seon/turn/batch.clj:414-415`):
    `(let [within (update projection :seon.schema.projection/forms select-keys roots) ... (schema.datahike/database-attributes-in within))`
  - `seon.turn.batch/attribute-change-tx` (`src/seon/turn/batch.clj:436-439`):
    `(schema.datahike/database-attributes-core-in (update current :seon.schema.projection/forms select-keys ...))`
- In all three places, the narrowed `projection` map still carries the **identical** `:seon.schema.projection/compiled` cache atom as the full `projection`.
- If the cache key were merely `::compiled-attribute-selection`, calling `database-attributes-for-in` (or `database-attributes-in` / `database-attributes-core-in` on a `select-keys`-narrowed projection) first would cache the subset selection on the shared holder, and a subsequent `(canonical-database-attributes projection)` or `(database-attributes-in projection)` on the full projection would return the subset (or vice versa).
- Keying by `[::compiled-attribute-selection (:seon.schema.projection/forms projection)]` (or `[::compiled-attribute-selection (set (keys (:seon.schema.projection/forms projection)))]`) guarantees that subset selections and full-population selections never collide in the shared `:seon.schema.projection/compiled` cache.

---

## 4. How to prove the gain end to end

1. **Red/green regression and subset-isolation proof in `test/seon/schema/datahike_test.clj`** (extending `compiled-storage-navigation-reuses-retained-roots` or adding a dedicated `deftest` reaching `seon.schema/canonical-database-attributes`, `seon.schema.datahike/database-attributes-in`, `seon.schema.datahike/database-attributes-core-in`, and `seon.schema.datahike/database-attributes-for-in`):
   - Acquire `projection` via `(schema/handed-projection)`.
   - First call `(schema/canonical-database-attributes projection)` to warm the projection's holder.
   - Interleave subset calls on the same `projection`: call `(schema.datahike/database-attributes-for-in projection (select-keys (:seon.schema.projection/forms projection) [:seon.task/entity]))` and `(schema.datahike/database-attributes-in (update projection :seon.schema.projection/forms select-keys [:seon.task/entity]))`, asserting that they return only the subset's attributes and do not poison the full projection's cached selection.
   - Measure a second call `(seon.profile/measure #(schema/canonical-database-attributes projection))` (and `database-attributes-core-in` / `database-attributes-in` on `projection`) and assert:
     - `(= expected (:seon.profile/value measured))` (and `identical?` to the first full call's vector),
     - `(zero? (seon.profile/calls-of measured 'seon.schema.datahike/declared-storable?))`,
     - `(zero? (seon.profile/calls-of measured 'seon.schema.datahike/compiled-storage))`,
     - `(zero? (seon.profile/calls-of measured 'seon.schema.internal/reference-id))`,
     - `(< (:seon.profile/ms measured) 0.5)` (down from `12.22 ms`),
     - `(< (:seon.profile/alloc-bytes measured) 10000)` (down from `18.7 MB`).
2. **End-to-end REPL verification and test run**:
   - Evaluate `(seon.profile/measure #(seon.schema/canonical-database-attributes (seon.schema/handed-projection)))` twice at the REPL on real default data and record before/after wall ms, thread allocated bytes, and `calls-of` counts in the commit message.
   - Run the reaching test suite (`test/seon/schema/datahike_test.clj`, `test/seon/schema_usage_guard_test.clj`, `test/seon/turn_loop_test.clj`) and `clj-kondo --cache false` with zero findings.

---

## Five-Line Summary

1. **Repeated work**: `seon.schema.datahike/compiled-attribute-selection` is uncached and re-walks every root's entity maps (`6,423` `seon.schema.internal/reference-id` calls) and re-checks optional members and property keys (`6,475` `declared-storable?` and `6,347` `compiled-storage` calls) on every call.
2. **Smallest fix**: Memoize `seon.schema.datahike/compiled-attribute-selection` using `seon.schema/projection-cache-value` on the projection's `:seon.schema.projection/compiled` holder so all four attribute-selection functions share one cached derivation.
3. **Cache key**: Use `[::compiled-attribute-selection (:seon.schema.projection/forms projection)]` (or `(set (keys (:seon.schema.projection/forms projection)))`) because `database-attributes-for-in` and `seon.turn.batch` `assoc`/`update` `:seon.schema.projection/forms` with a subset while retaining the same `:seon.schema.projection/compiled` cache atom.
4. **Subset safety**: Including the forms domain in the key prevents a subset call (`database-attributes-for-in` or `select-keys` on `:seon.schema.projection/forms`) from poisoning or being replaced by the full projection's attribute selection.
5. **End-to-end proof**: Warm `(seon.schema/canonical-database-attributes projection)`, interleave a `database-attributes-for-in` subset call on the same `projection`, and assert via `seon.profile/measure` that the second full call returns the identical `1,160`-attribute vector with `0` calls to `declared-storable?`, `compiled-storage`, and `reference-id`, in `< 0.5 ms` and `< 10 KB`.
