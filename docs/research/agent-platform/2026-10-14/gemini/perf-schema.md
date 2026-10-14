# Schema Projection, Queries, and Central Cache Performance Diagnosis (`perf-schema`)

## Executive Overview & Cache Health (`seon.cache/status`)

Across the running `default` JVM session, `seon.cache` is at **99.998% capacity** (`511,991,376` / `512,000,000` bytes across `30,643` entries, `2,179,192` hits, `34,475` misses, `1,807` evictions) and evicts continuously. Three cache kinds hold **73.1%** (`374.4 MB`) of the entire 512 MB budget:

| `:seon.cache/kind` | Bytes Held | Share of 512 MB | Entries | Bytes / Entry | Hits | Misses | Evictions |
|---|---:|---:|---:|---:|---:|---:|---:|
| `:seon.sci.eval/program-context` | `157,493,897` | 30.8% | 45 | ~3.50 MB | 183 | 127 | 82 |
| `:seon.test.runner/reach-index` | `111,688,184` | 21.8% | 14 | ~7.98 MB | 164 | 25 | 11 |
| `:seon.schema/declaration-projection` | `105,236,620` | 20.6% | 10 | ~10.52 MB | 7,179 | 13 | 3 |
| `:seon.schema.edn/packaged-population` | `61,940,736` | 12.1% | 52 | ~1.19 MB | 500,530 | 107 | 63 |
| `:seon.test.runner/reach-index-by-revision` | `32,208,656` | 6.3% | 14 | ~2.30 MB | 20 | 14 | 0 |
| `:seon.schema/projection` | `20,031,372` | 3.9% | 9 | ~2.23 MB | 502,911 | 2,866 | 1,568 |
| `:seon.program/source-resolver-names` | `4,785,208` | 0.9% | 10,384 | ~461 B | 482,664 | 10,384 | 0 |
| `:seon.program/authored-shapes` | `2,192,400` | 0.4% | 9 | ~243.6 KB | 492,765 | 9 | 0 |

---

## Finding 1 (Rank 1): `seon.cache/claim`, `seon.program/authored-shapes`, `seon.program/used-resolver-context`, and `seon.id/structural-digest`

1. **Fully qualified function symbols**:
   `seon.cache/claim`, `seon.program/authored-shapes`, `seon.program/shapes`, `seon.program/shape`, `seon.program/used-resolver-context`, `seon.program/source-resolver-names`, `seon.id/structural-digest`

2. **What repeats or is super-linear and on which unchanged immutable input**:
   - **Per-entity shape lookup + raw 3,749-entry schema-map cache key**: `seon.program/definition-digest` (`634,571` calls, `268,339 ms` total) calls `seon.program/used-resolver-context` (`613,437` calls, `255,960 ms` total). On every single digested entity, `used-resolver-context` calls 1-arity `(shape (first (row-identity entity)))` → `(shapes)` → `(authored-shapes)` (`615,651` calls, `230,837 ms` total) just to read `:seon.program/source-attribute` for one of the 6 static identity attributes (`:seon.fn/symbol`, `:seon.test/symbol`, `:seon.ns/name`, `:seon.fn.file/path`, `:seon.schema/key`, `:seon.lint/id`).
   - **Deep structural map equality inside `seon.cache/claim` (`224,997 ms` self)**: `seon.program/authored-shapes`, `seon.schema/declaration-projection`, `seon.program/program-attributes`, `seon.program/cluster-attributes`, and `seon.fn/population-targets` pass the raw `3,749`-entry `forms` map (`(seon.schema.edn/packaged-forms)`) as the cache key in `seon.cache/lookup`. Because `seon.cache` is at 99.998% capacity, `:seon.schema.edn/packaged-population` is evicted (`63` evictions, `107` misses) while `:seon.program/authored-shapes` (`0` evictions) remains in the cache holding the previous `forms` map instance in its key `[:seon.program/authored-shapes forms-old]`. Once `packaged-population` reloads a new `PersistentHashMap` instance `forms-new` with identical contents, `(identical? forms-new forms-old)` becomes `false`, forcing `(.getIfPresent cache k)` in `seon.cache/claim` to run full structural equality `APersistentMap.equals` across all `3,749` Malli schema forms (~`0.156 ms` per lookup × ~615k calls) on an unchanged schema population!
   - **Repeated `seon.id/structural-digest` on unchanged source before cache lookup**: Inside `used-resolver-context`, `seon.program/source-resolver-names` (`613,437` calls, `15,628 ms` total) computes `(id/id [source qualifiers symbols] 64)` (`seon.id/structural-digest`) on every call *before* consulting `seon.cache/lookup`, instead of keying `::source-resolver-names` directly by `[source qualifiers symbols]` (which are already immutable strings/sets of small symbols). Combined with `definition-digest` (`634,571` calls), this accounts for `1,248,008` of the `2,054,931` total calls to `seon.id/structural-digest` (`22,366 ms` self).

3. **Evidence**:
   - **`MEASURED`** (live JVM profile via `(seon.profile/summary (seon.profile/cells) 3000)` and equality microbenchmark via `eval_clj`):
     ```clojure
     ;; Profile cells (cumulative since boot):
     seon.cache/claim                  {:calls 2728112, :self-ms 224997, :total-ms 224997, :max-ms 35}
     seon.program/definition-digest    {:calls  634571, :self-ms   1522, :total-ms 268339, :max-ms 62}
     seon.program/used-resolver-context{:calls  613437, :self-ms   7206, :total-ms 255960, :max-ms 47}
     seon.program/shape                {:calls  616142, :self-ms    709, :total-ms 232595, :max-ms 35}
     seon.program/shapes               {:calls  614650, :self-ms   1090, :total-ms 231912, :max-ms 35}
     seon.program/authored-shapes      {:calls  615651, :self-ms   2158, :total-ms 230837, :max-ms 35}
     seon.program/source-resolver-names{:calls  613437, :self-ms   1821, :total-ms  15628, :max-ms 40}
     seon.id/structural-digest         {:calls 2054931, :self-ms  22366, :total-ms  22366, :max-ms 62}

     ;; Measured 1,000 non-identical 3,749-entry forms map equality checks vs keyword digest equality:
     (let [cur-forms (seon.schema.edn/packaged-forms)
           forms-clone (into {} cur-forms)
           t0 (System/nanoTime)
           _ (dotimes [_ 1000] (= cur-forms forms-clone))
           eq-1000-ms (/ (- (System/nanoTime) t0) 1e6)
           t1 (System/nanoTime)
           _ (dotimes [_ 1000] (= :digest-a :digest-a))
           kw-1000-ms (/ (- (System/nanoTime) t1) 1e6)]
       {:eq-1000-non-identical-forms-ms eq-1000-ms, :kw-1000-ms kw-1000-ms})
     ;; => {:eq-1000-non-identical-forms-ms 155.616333, :kw-1000-ms 0.074166} (2,098x slower)
     ```
   - **`INFERRED`** (`src/seon/program.cljc:400-404` and `src/seon/program.cljc:533`):
     ```clojure
     ;; src/seon/program.cljc:400-404
     (let [forms (schema.edn/packaged-forms)]
       (seon.cache/lookup ::authored-shapes forms
                          #(* shape-bytes-per-family (count %))
                          #(shapes-in (schema/declaration-projection forms))))
     ;; src/seon/program.cljc:533
     source (some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))
     ```

4. **Smallest fix by the owner**:
   - In `seon.schema.edn/load-population` (`src/seon/schema/edn.clj:613`), attach `:seon.schema/declaration-digest (seon.schema/declaration-digest forms)` to the returned population map and expose `(seon.schema.edn/packaged-digest)` (or attach metadata/digest to `forms`), and key `::authored-shapes`, `::declaration-projection`, `::declaration-predicates`, `::program-attributes`, and `::cluster-attributes` by `(seon.schema/declaration-digest forms)` (or `projection-key` via `seon.schema/projection-cache-value`) instead of the raw 3,749-entry `forms` map.
   - In `seon.program/used-resolver-context` (`src/seon/program.cljc:533`), resolve the identity attribute's `:seon.program/source-attribute` via a small constant map derived alongside `seon.program/identity-attributes` (`src/seon/program.cljc:30-40`) or pass `entity-shapes` into `definition-digest` / `used-resolver-context` via 2-arity `seon.program/shape` (`src/seon/program.cljc:463`), eliminating all ~615k per-entity `(authored-shapes)` calls.
   - In `seon.program/source-resolver-names` (`src/seon/program.cljc:513`), key `seon.cache/lookup` directly by `[source qualifiers symbols]` instead of hashing with `(id/id [source qualifiers symbols] 64)` on every lookup.

5. **Expected gain with reasoning**:
   - Work per `definition-digest` call drops from $O(|\text{schema forms}|)$ (`3,749` schema entries compared when `forms` is non-identical + `15` instrumented calls per entity + SHA-256 `structural-digest` before cache lookup) to $O(1)$ map lookup on the entity's identity attribute.
   - Saves **~220–235 s cumulative CPU time** (~`160–220 s` in `seon.cache/claim`, ~`14 s` in `source-resolver-names` / `seon.id/structural-digest`, and `9.2M` instrumented calls across the session).

6. **End-to-end probe**:
   ```clojure
   (let [row {:seon.fn/symbol 'seon.cache/claim
              :seon.fn/source "(defn claim [k w] k)"
              :seon.fn/calls #{'seon.cache/lookup}}
         ctx {:requires '#{seon.cache} :aliases {} :refers {} :imports {}}]
     (seon.profile/measure #(dotimes [_ 1000] (seon.program/definition-digest row ctx))))
   ```

---

## Finding 2 (Rank 2): `seon.schema/contract-references`, `seon.schema/compiled-contract-product`, `seon.schema/arity-references`, and `seon.program/with-contract-facts`

1. **Fully qualified function symbols**:
   `seon.program/contract-facts`, `seon.program/with-contract-facts`, `seon.fn/contract-facts-row`, `seon.fn/add-contract-facts`, `seon.schema/contract-product`, `seon.schema/compiled-contract-product`, `seon.schema/contract-references`, `seon.schema/arity-references`

2. **What repeats or is super-linear and on which unchanged immutable input**:
   - **Stripping `::contract-products` and `::projection-cache` before calling `seon.schema/contract-product`**:
     In `seon.fn/add-contract-facts` (`src/seon/fn.clj:3261-3293`), `projection` is built via `(schema/build-projection schema-forms function-contracts ...)`, which already compiles every function contract and its `contract-references` into `(:seon.schema/contract-products projection)` (`src/seon/schema.clj:2293-2299`). However, `add-contract-facts` and `contract-facts-row` (`src/seon/fn.clj:3225-3235`) strip `projection` down to `compile-options`, `predicate-functions`, and `schema-keys`, and `seon.program/contract-facts` (`src/seon/program.cljc:1348-1354`) constructs a bare 3-key map `{:seon.schema.projection/registry ... :seon.schema.projection/canonical-keys ...}` **without** `:seon.schema/contract-products` or `:seon.schema/projection-cache` and passes it to `seon.schema/contract-product` (`38,854` calls, `15,966 ms` total). Because `:seon.schema/contract-products` is `nil` on that synthetic map, `contract-product` misses 100% of the time and recompiles every contract and re-walks `contract-references` (`372,851` calls, `34,686 ms` self) and `arity-references` (`110,672` calls, `32,010 ms` total)!
   - **Re-walking `contract-references` 3× per contract during projection build & instrumentation**:
     For each contract form in `build-projection` (`3,766` calls, `50,786 ms` total):
     1. `assert-complete-contract!` (`src/seon/schema.clj:2102`) calls `(contract-references projection compiled)`.
     2. `compiled-contract-product` (`src/seon/schema.clj:2221`, `110,672` calls, `42,540 ms` total) calls `(contract-references projection compiled)` again on both `:shape` and `:armed` modes (even though `seon.instrument/compiled-wrapper` in `:armed` mode only reads `:seon.schema/compiled` and never reads `:seon.schema/references`, and `:shape` mode already computed the exact same references for the same contract form).
     3. `function-arities-in` (`src/seon/schema.clj:2398`) calls `(contract-references projection compiled)` a third time when `contract-product` is not hit.
   - **Important contract-validation hazard on `seon.schema/contract-product`**: `seon.schema/contract-product` declares argument 0 as `:seon.schema/projection` (`[:map [:seon.schema.projection/forms [:map-of :any :any]] ...]`). When passed a full `projection` containing all 3,749 `:seon.schema.projection/forms`, Malli input validation on `contract-product` validates the entire 3,749-entry map on every call (`69.95 ms` per 100 calls vs `1.1 ms` per 100 calls on a map without `:seon.schema.projection/forms`).

3. **Evidence**:
   - **`MEASURED`** (live JVM profile and `seon.profile/measure` via `eval_clj`):
     ```clojure
     ;; Profile cells (cumulative since boot):
     seon.schema/compiled-contract-product {:calls 110672, :self-ms 10529, :total-ms 42540, :max-ms 167}
     seon.program/with-contract-facts      {:calls 153081, :self-ms 14497, :total-ms 37258, :max-ms 38}
     seon.schema/contract-references       {:calls 372851, :self-ms 34686, :total-ms 35159, :max-ms 25}
     seon.schema/arity-references          {:calls 110672, :self-ms 10280, :total-ms 32010, :max-ms 27}
     seon.program/contract-facts           {:calls  38452, :self-ms  4062, :total-ms 22717, :max-ms 38}
     seon.schema/contract-product          {:calls  38854, :self-ms   295, :total-ms 15966, :max-ms 38}

     ;; Measured 100 calls to contract-product (stripped-proj vs full-proj with 3,749 :forms):
     :measure-contract-product-stripped-100 #:seon.profile{:calls 1300, :ms 1.1}
     :measure-contract-product-cached-100   #:seon.profile{:calls 1200, :ms 69.95}
     ```
   - **`INFERRED`** (`src/seon/program.cljc:1348-1354` and `src/seon/schema.clj:4300-4306`):
     ```clojure
     ;; src/seon/program.cljc:1348-1354
     (let [product (schema/contract-product
                    (cond-> {:seon.schema.projection/registry (:seon.schema.projection/registry compile-options)
                             :seon.schema.projection/canonical-keys schema-keys}
                      predicate-functions
                      (assoc :seon.schema.projection/predicate-functions predicate-functions))
                    {:seon.schema/form (read-edn spec)
                     :seon.schema/mode :shape}) ...]
     ```

4. **Smallest fix by the owner**:
   - In `seon.schema/contract-product` (`src/seon/schema.clj:4295-4306`) and `seon.schema/compiled-contract-product` (`src/seon/schema.clj:2204-2222`), cache compiled contract products via `seon.schema/projection-cache-value` on `[::contract-product mode form]` (or carry `:seon.schema/contract-products` and `:seon.schema/projection-cache` in `seon.fn/contract-facts-row` / `seon.program/contract-facts` without `:seon.schema.projection/forms`, or narrow the input schema of `contract-product` to the keys it actually reads so Malli does not re-validate 3,749 `:seon.schema.projection/forms` on each call).
   - In `seon.schema/compiled-contract-product` (`src/seon/schema.clj:2204-2222`), compute `(contract-references projection compiled)` once per contract form (in `:shape` mode) and reuse those references in `assert-complete-contract!`, `function-arities-in`, and `:armed` mode.

5. **Expected gain with reasoning**:
   - Contract compilation and reference walking become proportional to **distinct changed contract forms** rather than repeating on every `with-contract-facts` call (`38,452` calls) and 3× per contract during projection build.
   - Saves **~45–60 s cumulative CPU time** across `compiled-contract-product`, `contract-references`, `arity-references`, and `with-contract-facts`.

6. **End-to-end probe**:
   ```clojure
   (let [proj (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
         row {:seon.fn/symbol 'seon.cache/claim
              :seon.fn/contract-source "[:=> [:cat :any :int] :any]"}]
     (seon.profile/measure #(dotimes [_ 200] (seon.fn/contract-facts-row proj row))))
   ```

---

## Finding 3 (Rank 3): `seon.schema/projection-cache-value` (47.0M calls) via Per-Datom / Per-Attribute Lookups in `seon.db/write-attribute-plan` and `seon.schema.datahike`

1. **Fully qualified function symbols**:
   `seon.schema/projection-cache-value`, `seon.db/write-attribute-plan`, `seon.schema.datahike/vector-shaped-attr-in?`, `seon.schema.datahike/vector-attribute-in?`, `seon.schema.datahike/ref-attr-in?`, `seon.schema.datahike/identity-attr-in?`

2. **What repeats or is super-linear and on which unchanged immutable input**:
   - `seon.schema/projection-cache-value` has been called **46,974,466 times** (`16,005 ms` self, `22,826 ms` total).
   - Four per-attribute / per-datom callers account for **82.5% (38.7M)** of all 47.0M calls on an unchanged `projection`:
     1. `seon.db/write-attribute-plan` (`17,443,273` calls, `9,343 ms` total): called inside `seon.db/write-entity-value` (`src/seon/db.clj:4284`) and `seon.db/cell-vectors` (`src/seon/db.clj:4248`) on **every datom** of every entity read during transaction validation. Each call allocates a 3-element vector `[::write-attribute-plan attribute many?]` and calls `projection-cache-value`.
     2. `seon.schema.datahike/vector-shaped-attr-in?` (`15,198,432` calls, `8,207 ms` total; `8,616,286` via `vector-attribute-in?`, `4,553,857` via `decode-pulled`, `1,750,000+` via `logical-collection`): allocates `[::vector-shaped-attr attribute]` and calls `projection-cache-value` on every pulled attribute and datom.
     3. `seon.schema.datahike/ref-attr-in?` (`6,568,710` calls, `3,163 ms` total): allocates `[::ref-attr attribute]` and calls `projection-cache-value` on every attribute.
     4. `seon.schema.datahike/identity-attr-in?` (`1,091,349` calls, `543 ms` total): allocates `[::identity-attr attribute]` and calls `projection-cache-value`.
   - Yet `seon.schema.datahike/compiled-attribute-selection` (`src/seon/schema/datahike.clj:262-279`) *already* computes projection-wide sets (`::attribute-set`, `::identity-set`, `::ref-set`, `::vector-set`, `::edn-set`) once per projection!

3. **Evidence**:
   - **`MEASURED`** (live JVM profile and `seon.profile/measure` via `eval_clj`):
     ```clojure
     ;; Profile cells (cumulative since boot):
     seon.schema/projection-cache-value          {:calls 46974466, :self-ms 16005, :total-ms 22826, :max-ms 166}
     seon.db/write-attribute-plan                {:calls 17443273, :self-ms  3920, :total-ms  9343, :max-ms 166}
     seon.schema.datahike/vector-shaped-attr-in? {:calls 15198432, :self-ms  3051, :total-ms  8207, :max-ms 37}
     seon.schema.datahike/vector-attribute-in?   {:calls  8616286, :self-ms  2220, :total-ms  6811, :max-ms 17}
     seon.schema.datahike/ref-attr-in?           {:calls  6568710, :self-ms  1266, :total-ms  3163, :max-ms 12}
     seon.schema.datahike/identity-attr-in?      {:calls  1091349, :self-ms   209, :total-ms   543, :max-ms 8}

     ;; Measured 10,000 calls: per-attribute projection-cache-value vs projection-cached set contains?:
     :measure-proj-cache-value-10000 #:seon.profile{:calls 10000, :ms 3.42}
     :measure-set-contains-10000     #:seon.profile{:calls 0,     :ms 0.26} ;; 13.1x faster
     ```
   - **`INFERRED`** (`src/seon/db.clj:4183-4185`, `src/seon/db.clj:4254-4259`, `src/seon/schema/datahike.clj:401-429`):
     ```clojure
     ;; src/seon/db.clj:4254-4259
     (defn- write-attribute-plans [database projection]
       (if (nil? projection)
         (constantly nil)
         (fn [attribute]
           (write-attribute-plan projection attribute (db.utils/multival? database attribute)))))
     ```

4. **Smallest fix by the owner**:
   - In `seon.schema.datahike/compiled-attribute-selection` (`src/seon/schema/datahike.clj:262-279`), include `::vector-shaped-set` alongside `::vector-set`, `::ref-set`, and `::identity-set`, and have `vector-shaped-attr-in?`, `vector-attribute-in?`, `ref-attr-in?`, and `identity-attr-in?` check `(contains? (::<kind>-set (compiled-attribute-selection projection)) attribute)` (one `projection-cache-value` lookup per projection or per operation instead of allocating `[::kind attribute]` per datom).
   - In `seon.db/write-attribute-plans` (`src/seon/db.clj:4254-4259`), cache a single projection-level map `::write-attribute-plans` in `projection-cache-value` (or memoize the attribute-to-plan map for the transaction's `[projection (dbi/-schema database)]`) so `(plan a)` inside `write-entity-value` and `cell-vectors` is a direct map/set lookup per datom instead of calling `write-attribute-plan` and `projection-cache-value` 17.4M times.

5. **Expected gain with reasoning**:
   - Eliminates ~38.7M of the 47.0M calls to `projection-cache-value`, ~38.7M short-lived 2- and 3-element vector key allocations, and ~87M instrumented function calls across `write-attribute-plan`, `vector-shaped-attr-in?`, `vector-attribute-in?`, and `ref-attr-in?`.
   - Saves **~20–25 s cumulative CPU time** directly on transaction writes and pull decoding.

6. **End-to-end probe**:
   ```clojure
   (let [conn (seon.cluster.boot/connection "default")
         db (seon.db/db conn)
         proj (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))]
     (seon.profile/measure
      #(dotimes [_ 10000]
         (seon.schema.datahike/vector-attribute-in? proj :seon.fn/arglists)
         (seon.schema.datahike/ref-attr-in? proj :seon.fn/ns))))
   ```

---

## Finding 4 (Rank 4): `seon.db/pull-logical` (112.9 s self) and `seon.db/vector-query-call` (93.7 s self)

1. **Fully qualified function symbols**:
   `seon.db/pull-logical`, `seon.schema.datahike/vector-pull`, `seon.schema.datahike/decode-pulled`, `seon.schema.datahike/decode-vectors`, `seon.db/vector-query-call`, `seon.db/vector-query`

2. **What repeats or is super-linear and on which unchanged immutable input**:
   - **Where `pull-logical` (`162,275` calls, `112,930 ms` self, `132,158 ms` total) and `vector-query-call` (`60,032` calls, `93,734 ms` self, `133,805 ms` total) spend their self time**:
     In `seon.profile`, self-time is `elapsed - armed-callees-elapsed` (`src/seon/profile.clj:265-266`). Because Datahike's `datahike.api/pull`, `datahike.api/pull-many`, `datahike.pull-api/pull-spec`, and `datahike.api/q` are **unarmed library functions**, 100% of Datahike's underlying index traversal, datom fetch, and Datalog join execution inside `pull-logical` (~`0.70 ms`/call self) and `vector-query-call` (~`1.56 ms`/call self) is recorded as the self-time of `pull-logical` and `vector-query-call`.
   - **What repeats on unchanged `[projection selector]` and `[projection query]` around those calls (`19.2 s` in `pull-logical` children; `40.1 s` in `vector-query-call` children + `vector-query`)**:
     1. On every `pull-logical` call (`src/seon/db.clj:2923-2946`), `seon.schema.datahike/vector-pull` (`src/seon/schema/datahike.clj:795-824`, `163,789` calls, `2,413 ms`) calls `(vector-attributes-in projection)` — which walks all `3,749` keys of `(:seon.schema.projection/forms projection)` when not projection-cached — builds a set `vectors`, and looks up `[(projection-key projection) vectors selector]` in `seon.cache/lookup` (which has `707` entries in `seon.cache`). Then `decode-vectors` (`955,392` calls, `14,929 ms` total) and `decode-pulled` (`4,553,857` calls, `8,193 ms` total) recursively walk every pulled map and every map entry, calling `vector-shaped-attr-in?` and `attribute-decoder-in` even when the selector pulled zero vector or EDN-encoded attributes!
     2. On every `seon.db/q` call (`src/seon/db.clj:2660-2737`), `seon.db/vector-query` walks the entire query AST via `tree-seq` to check for vector attributes and lower the query without caching the lowered query plan by `[(projection-key projection) query]`. Inside `vector-query-call`, any query predicate or function call back into armed Seon functions (`40,071 ms` of armed child calls) runs per tuple.

3. **Evidence**:
   - **`MEASURED`** (live JVM profile and `seon.profile/measure` via `eval_clj`):
     ```clojure
     ;; Profile cells (cumulative since boot):
     seon.db/pull-logical                {:calls  162275, :self-ms 112930, :total-ms 132158, :max-ms 1258}
     seon.db/vector-query-call           {:calls   60032, :self-ms  93734, :total-ms 133805, :max-ms 3085}
     seon.schema.datahike/decode-vectors {:calls  955392, :self-ms   3649, :total-ms  14929, :max-ms 18}
     seon.schema.datahike/decode-pulled  {:calls 4553857, :self-ms   5398, :total-ms   8193, :max-ms 18}
     seon.schema.datahike/vector-pull    {:calls  163789, :self-ms    277, :total-ms   2413, :max-ms 25}

     ;; Measured 100 calls to vector-pull vs raw datahike.api/pull:
     :measure-vector-pull-100 #:seon.profile{:calls 1000, :ms 0.47}
     :measure-d-pull-100      #:seon.profile{:calls 0,    :ms 0.86}
     ```
   - **`INFERRED`** (`src/seon/schema/datahike.clj:799-824` and `src/seon/db.clj:2923-2946`):
     ```clojure
     ;; src/seon/schema/datahike.clj:799-805
     (let [vectors (set (filter #(get (:seon.schema.projection/forms projection) %)
                                (vector-attributes-in projection)))]
       (if (empty? vectors)
         {:datahike.pull/selector selector}
         (seon.cache/lookup
          ::vector-pull [(projection-key projection) vectors selector] ...)))
     ```

4. **Smallest fix by the owner**:
   - In `seon.schema.datahike/vector-pull` (`src/seon/schema/datahike.clj:795-824`), cache the lowered pull plan directly on `projection` via `seon.schema/projection-cache-value` keyed by `[::vector-pull selector]` (using `(::vector-set (compiled-attribute-selection projection))` instead of recomputing `vectors` and hitting `seon.cache/lookup`), and record on the returned plan whether the selector can match any vector or EDN-encoded attribute (`:seon.schema.datahike/needs-decode?`). When `false` (the common case for narrow selectors like `[:db/id :seon.fn/symbol]` without wildcard `'*`), skip `decode-vectors` and `decode-pulled` altogether in `seon.db/pull-logical`.
   - In `seon.db/vector-query` (`src/seon/db.clj:2660-2737`), cache the lowered query plan via `seon.schema/projection-cache-value` on `[::vector-query query]`.

5. **Expected gain with reasoning**:
   - Eliminates `163,789` `seon.cache/lookup` calls in `vector-pull` and skips `decode-vectors` / `decode-pulled` (`5.5M` calls, `14.9 s`) on narrow non-vector pulls, plus eliminates repeated query-AST walks in `vector-query`.
   - Saves **~12–18 s cumulative CPU time** on pull/query wrappers (while the remaining self-time of `pull-logical` and `vector-query-call` is genuine Datahike index/join work proportional to caller query volume).

6. **End-to-end probe**:
   ```clojure
   (let [db (seon.db/db (seon.cluster.boot/connection "default"))]
     (seon.profile/measure
      #(dotimes [_ 200]
         (seon.db/pull db [:db/id :db/ident] 1))))
   ```

---

## Finding 5 (Rank 5): `seon.schema/call-with-projection-state` (136.0 s self across 1,152 calls) & `seon.cache` 512 MB Thrashing

1. **Fully qualified function symbols**:
   `seon.schema/call-with-projection-state`, `seon.sci.eval/program-context`, `seon.test.runner/reach-index`, `seon.schema/declaration-projection`

2. **What repeats or is super-linear and on which unchanged immutable input**:
   - **Why `seon.schema/call-with-projection-state` reports `135,984 ms` self (`248,922 ms` total, `1,152` calls, max `9,980 ms`)**:
     `call-with-projection-state` (`src/seon/schema.clj:1159-1164`) does nothing except `(with-bindings (projection-state-bindings projection-state) (f))`. Because `seon.profile` computes a parent frame's self-time by subtracting only **armed** child calls from the parent's wall-clock elapsed time (`src/seon/profile.clj:265-266`), every **unarmed** operation inside `(f)` — including `clj-kondo.core/run!`, JVM `clojure.lang.Compiler/eval`, unarmed SCI evaluation loops, raw Datahike commits, and blocking I/O or channel waits during turns and tests — is credited to `call-with-projection-state` as self-time (already tracked under task `seon.profile/scope-wrappers-report-callback-time-as-work` at `src/seon/schema.clj:1134`).
   - **Why `seon.cache` is pinned at `511,991,376 / 512,000,000` bytes and evicting `::projection` (`1,568` evictions) and `::packaged-population` (`63` evictions)**:
     `45` entries of `:seon.sci.eval/program-context` (`157.5 MB`), `14` entries of `:seon.test.runner/reach-index` (`111.7 MB`), `10` entries of `:seon.schema/declaration-projection` (`105.2 MB`), and `52` entries of `:seon.schema.edn/packaged-population` (`61.9 MB`) consume `436.3 MB` (85.2%) of the 512 MB process cache. Why are there **52** `:seon.schema.edn/packaged-population` entries (`1.19 MB` each) when there is only 1 set of packaged resource files? Because in `seon.schema.edn/url-fingerprint` (`src/seon/schema/edn.clj:630-639`), jar/file URLs or test resource overrides produce distinct keys, and old heavy entries (`program-context`, `reach-index`, `declaration-projection`) from prior branch/test revisions occupy 436 MB, squeezing `:seon.schema/projection` (`1,568` evictions out of `2,866` misses!) into continuous churn.

3. **Evidence**:
   - **`MEASURED`** (live JVM profile and `seon.cache/status` via `eval_clj`):
     ```clojure
     ;; Profile cell:
     seon.schema/call-with-projection-state {:calls 1152, :self-ms 135984, :total-ms 248922, :max-ms 9980}

     ;; Cache status top evictions and bytes:
     {:seon.cache/bytes 511991376, :seon.cache/max-bytes 512000000,
      :seon.cache/hits 2179192, :seon.cache/misses 34475, :seon.cache/evictions 1807}
     ;; :seon.schema/projection -> {:bytes 20031372, :hits 502911, :misses 2866, :evictions 1568}
     ;; :seon.sci.eval/program-context -> {:bytes 157493897, :hits 183, :misses 127, :evictions 82}
     ```
   - **`INFERRED`** (`src/seon/schema.clj:1134-1164`):
     ```clojure
     (defn call-with-projection-state
       {:seon/tasks #{'seon.profile/scope-wrappers-report-callback-time-as-work} ...}
       [projection-state f]
       (with-bindings (projection-state-bindings projection-state)
         (f)))
     ```

4. **Smallest fix by the owner**:
   - For `call-with-projection-state`: in `seon.profile` (under existing task `seon.profile/scope-wrappers-report-callback-time-as-work`), exclude transparent dynamic-binding scope wrappers from self-time attribution (or measure only `projection-state-bindings` setup before invoking `(f)` outside the wrapper's self-timer).
   - For `seon.cache` thrashing: key `:seon.schema/declaration-projection` by `(seon.schema/declaration-digest forms)` (so equal schema populations share one 10.5 MB entry instead of 10 entries = 105.2 MB), and in `seon.schema.edn/packaged-population` (`src/seon/schema/edn.clj:641-664`), hold the current default packaged population in a lightweight 1-entry holder or content-digest key so 52 historical copies (`61.9 MB`) do not sit in the 512 MB cache evicting `:seon.schema/projection` (`1,568` evictions).

5. **Expected gain with reasoning**:
   - Deduplicating `:seon.schema/declaration-projection` by content digest and keeping only active `:seon.schema.edn/packaged-population` frees **~140 MB** (27% of the 512 MB cache), stopping the `1,568` evictions of `:seon.schema/projection` (`~20–50 ms` per rebuild × `1,568` = **~30–60 s saved**) and preventing `:seon.schema.edn/packaged-population` evictions from triggering the non-identical `forms` map equality cascade in Finding 1.

6. **End-to-end probe**:
   ```clojure
   (select-keys (seon.cache/status)
                [:seon.cache/bytes :seon.cache/max-bytes :seon.cache/evictions :seon.cache/by-kind])
   ```

---

## Five-Line Summary

1. **Rank 1 (~220–235 s saved)**: `seon.program/used-resolver-context` calls 1-arity `shape` → `authored-shapes` on every digested entity (`615k` calls) using the raw `3,749`-entry `forms` map as a `seon.cache` key (`0.156 ms`/call in `APersistentMap.equals` inside `seon.cache/claim` whenever `packaged-population` evicts) and hashes `[source qualifiers symbols]` with `seon.id/structural-digest` (`613k` calls) before cache lookup.
2. **Rank 2 (~45–60 s saved)**: `seon.program/contract-facts` and `seon.fn/contract-facts-row` strip `:seon.schema/contract-products` from the projection passed to `seon.schema/contract-product`, forcing `38,854` contract recompilations, while `build-projection` walks `seon.schema/contract-references` (`372,851` calls) and `arity-references` (`110,672` calls) 3× per contract.
3. **Rank 3 (~20–25 s saved)**: `seon.schema/projection-cache-value` runs `47.0M` times because `seon.db/write-attribute-plan` (`17.4M` calls), `vector-shaped-attr-in?` (`15.2M`), and `ref-attr-in?` (`6.6M`) allocate per-attribute vector keys on every datom instead of reading projection-wide sets from `seon.schema.datahike/compiled-attribute-selection`.
4. **Rank 4 (~12–18 s saved)**: `seon.db/pull-logical` (`112.9 s` self) and `seon.db/vector-query-call` (`93.7 s` self) spend their self-time in unarmed Datahike `d/pull` and `d/q`, while repeating `seon.schema.datahike/vector-pull` (`163k` cache lookups), `decode-vectors`/`decode-pulled` (`5.5M` calls even on scalar pulls), and uncached `seon.db/vector-query` AST walks.
5. **Rank 5 (~30–60 s saved from eviction churn)**: `seon.schema/call-with-projection-state` (`136.0 s` self across `1,152` calls) attributes unarmed callback time in `(f)` to itself, while `seon.cache` (`511.99 / 512.00 MB`) thrashes `:seon.schema/projection` (`1,568` evictions) because 10 `declaration-projection` (`105.2 MB`) and 52 `packaged-population` (`61.9 MB`) entries crowd out active projections.
