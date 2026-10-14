# Static Analysis and Program Derivation Performance Diagnosis

Baseline profile: `tmp/argon-perf-profile.edn` (PID `10712`, cumulative since boot) and live read-only JVM measurements on `default` via `seon.profile/measure`.

---

## Ranked Findings

### 1. `seon.cluster/rehearse-source!` & `seon.cluster/full-source-refresh!` — Full-repo git discovery, file hashing, `.clj-kondo/.cache` tree copy, two-pass re-analysis, and uncached file artifacts across rehearsals

1. **Function**: `seon.cluster/rehearse-source!`, `seon.cluster/full-source-refresh!`, `seon.fn/analyzed-files`, `seon.fn/analyzed-artifacts`, `seon.fn.analyzer/invoke-kondo`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**: `seon.cluster/rehearse-source!` ran **120 times** for **1,557,408 ms total** (55,246 ms self, max 33,238 ms, ~13.0 s/call); `seon.fn/analyzed-files` ran **187 times** for **743,266 ms total**; `seon.fn/analyzed-artifacts` ran **435 times** for **668,836 ms total** (25,185 ms self); `seon.fn.analyzer/analyze` ran **382 times** for **346,981 ms total** (`seon.fn.analyzer/invoke-kondo` **206,894 ms self**).
   - **Four compounding causes**:
     1. **Every `rehearse-source!` copies `.clj-kondo/.cache` and forces `partial? = false` (`src/seon/cluster.clj:1895-1940`)**: `rehearse-source!` copies the entire `.clj-kondo/.cache` directory tree (`babashka.fs/copy-tree`) to a temp dir, then calls `full-source-refresh!` with `:seon.source/changed-paths []`. Because `(seq (:seon.source/changed-paths roots))` is `nil`, `partial?` (`src/seon/cluster.clj:1764`) is `false`, so every rehearsal runs `test.cache/widening-paths` and `source/discover-paths` (two `git ls-files` sweeps over 6,907 repo files) and `source/capture-paths` (reading and SHA-256 hashing all ~920 source and input files from disk) even when the caller or git diff knows the changed paths.
     2. **Whole-program widening on any schema or widening-path diff (`src/seon/cluster.clj:1808-1812`)**: When `(or (empty? current-src) (some #(str/starts-with? % "resources/seon/schemas/") changed) (seq (set/intersection changed widening-paths)))` holds, `selected` becomes `(set (keys next-src))` — **all ~588 source files in `src`, `test`, and `script`** are re-analyzed and re-derived from scratch, even though `seon.cluster/derivation-keys` (`src/seon/cluster.clj:1615`) already tracks per-file schema dependencies (`seon.cluster/file-schema-keys`).
     3. **Recursive retry in `full-source-refresh!` discards Pass 1 (`src/seon/cluster.clj:1829-1872`)**: `full-source-refresh!` first runs `seon.fn/analyze-entities` on `selected` (line 1829), then computes `stale` files via `derivation-keys` (lines 1848–1869). When `(seq stale)` is non-empty, line 1870 calls `(full-source-refresh! root store (assoc roots ::rederive stale))`, which **throws away the analysis of `changed` just computed**, re-runs `discover-paths` + `capture-paths`, and re-analyzes `(into changed stale)` from scratch.
     4. **Up to 3 separate `analyzed-artifacts` / `invoke-kondo` batches per `index!` (`src/seon/fn.clj:3002-3020, 4215-4219`) and zero artifact caching across calls**: `analyzed-files` calls `analyzed-artifacts` on `selected` (line 3002) and again on `dependents` (line 3018); `index!` then commits and calls `analyzed-artifacts` a third time on `(caller-files committed)` whenever any `signature-attributes` datom changed (line 4217). Note: `clj-kondo`'s `.cache` only caches cross-namespace var arities/macros for dependency resolution — `clj-kondo/run!` still lexes, parses, macroexpands, and lints every file in `:lint` on every call, and `analyze` writes and deletes a temp mirror directory (`write-mirror!`) each time.
   - **Immutable input**: A file's derived artifact is a pure function of its `seon.cluster/file-derivation-key` (already computed and cached in `seon.cache` under `:seon.cluster/file-derivation-key` from the file's SHA-256 digest, macro/inline dependency digests, used schema forms, and invoked targets) plus the known first-party function symbol set (`first-party-functions` / callee signatures).
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn`):
     ```clojure
     {:sym seon.cluster/rehearse-source!, :calls 120, :self-ms 55246, :total-ms 1557408, :max-ms 33238}
     {:sym seon.fn/analyzed-files, :calls 187, :self-ms 1293, :total-ms 743266, :max-ms 17013}
     {:sym seon.fn/analyzed-artifacts, :calls 435, :self-ms 25185, :total-ms 668836, :max-ms 16300}
     {:sym seon.fn.analyzer/analyze, :calls 382, :self-ms 56037, :total-ms 346981, :max-ms 8991}
     {:sym seon.fn.analyzer/invoke-kondo, :calls 382, :self-ms 206894, :total-ms 206901, :max-ms 5549}
     ```
   - **MEASURED** (live JVM probe on `default` measuring one full-tree `seon.fn.analyzer/analyze` on `src`, `test`, `script`):
     ```clojure
     (seon.profile/measure
       (fn []
         (seon.fn.analyzer/analyze
           {:seon.fn.analyzer/cache-root "/Users/sean/src/seon"
            :seon.fn.analyzer/paths ["/Users/sean/src/seon/src"
                                     "/Users/sean/src/seon/test"
                                     "/Users/sean/src/seon/script"]})))
     ;; => {:ms 13444.12,
     ;;     :called #:seon.fn.analyzer{contains-position? 2879532, parsed-facts 1117, invoke-kondo 2, analyze 2}}
     ```
   - **INFERRED** (`src/seon/cluster.clj:1808-1812, 1829-1872`):
     ```clojure
     selected (if (or (empty? current-src)
                      (some #(str/starts-with? % "resources/seon/schemas/") changed)
                      (seq (set/intersection changed widening-paths)))
                (set (keys next-src))
                (into changed redeliver))
     ...
     analyzed (when (seq selected)
                (seon.fn/analyze-entities ...))
     ...
     (if (seq stale)
       (full-source-refresh! root store (assoc roots ::rederive stale))
       ...)
     ```
4. **Smallest fix**:
   - In `seon.cluster/full-source-refresh!` (`src/seon/cluster.clj:1808-1872`):
     1. Compute `stale` for unchanged files whose `seon.cluster/file-derivation-key` changed (using the new schema forms / captured digests) *before* calling `seon.fn/analyze-entities`, or analyze *only* `stale` on the second pass and merge its artifacts with the already-analyzed `changed` artifacts instead of recursing and discarding Pass 1.
     2. When a `resources/seon/schemas/` file changes, select only files whose `seon.cluster/file-schema-keys` intersect the changed schema keys (via `derivation-keys`) plus `changed`, rather than `(set (keys next-src))`.
     3. In `seon.cluster/rehearse-source!` (`src/seon/cluster.clj:1895`), pass `:seon.fn/cache-root nil` (using `seon.fn/shared-dependency-analysis` read-only from the checkout's `.clj-kondo/.cache` as `analyzed-artifacts` already supports at `src/seon/fn.clj:2792-2795`) when only a few files changed, avoiding `babashka.fs/copy-tree` of the entire `.clj-kondo/.cache` directory on every rehearsal.
5. **Expected gain**:
   - Work becomes proportional to `|changed ∪ stale|` files (typically 1–5 files, ~150–300 ms) instead of `2 × |selected|` or all ~588 files (~13–33 s), and eliminates copying `.clj-kondo/.cache` 120 times. Expected savings: **~900–1,200 s** across 120 rehearsals (reducing average rehearsal from ~13 s to ~1–2 s).
6. **End-to-end probe**:
   ```clojure
   (let [conn (seon.cluster.boot/connection "default")]
     (seon.profile/measure
       (fn []
         (seon.cluster/rehearse-source! "default" (seon.fs/source-directory) (datahike.api/db conn)))))
   ```

---

### 2. `seon.program/used-resolver-context` & `seon.program/source-resolver-names` — Calling 1-arg `seon.program/shape` (`authored-shapes`) and re-hashing full source strings on every `definition-digest`

1. **Function**: `seon.program/used-resolver-context`, `seon.program/source-resolver-names`, `seon.program/authored-shapes`, `seon.program/definition-digest`, `seon.fn/var-entity`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**: `seon.program/definition-digest` ran **492,245 times** for **193,425 ms total**; `seon.program/used-resolver-context` ran **479,299 times** for **183,829 ms total** (5,539 ms self); `seon.program/authored-shapes` ran **479,483 times** for **163,902 ms total**; `seon.fn/var-entity` ran **359,533 times** for **202,420 ms total**.
   - **Two bugs inside `used-resolver-context` and `source-resolver-names` (`src/seon/program.cljc:492-540`)**:
     1. **1-arity `(shape (first (row-identity entity)))` called 479,299 times (`src/seon/program.cljc:533`)**: To read the source string of `entity` (`:seon.fn/source`, `:seon.test/source`, or `:seon.ns/source`), line 533 calls `(some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))`. The 1-argument `seon.program/shape` calls `(shapes)` -> `(authored-shapes)` -> `(schema.edn/packaged-forms)` -> `(schema.edn/packaged-population)` -> `(seon.cache/lookup ::authored-shapes forms ...)`, where `forms` is the ~1,000-entry `packaged-forms` map! On every single `definition-digest` call (479,299 times!), `seon.cache/claim` hashes and compares the entire ~1,000-entry `forms` map as a `ConcurrentHashMap` key, costing **163,902 ms** (`authored-shapes`) out of `used-resolver-context`'s 183,829 ms!
     2. **Full-source structural digest computed just to build the cache key of `source-resolver-names` (`src/seon/program.cljc:513`)**: `source-resolver-names` calls `(seon.cache/lookup ::source-resolver-names (id/id [source qualifiers symbols] 64) ...)`. Computing `(id/id [source qualifiers symbols] 64)` runs `seon.id/structural-digest` over the entire `source` string and the `qualifiers` and `symbols` sets on every call (even on the 96% cache hits: 477,737 hits, 14,856 misses), and keys the parse on `[qualifiers symbols]` even though the AST tokens spelled in `source` depend **only on `source`**.
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn` and `seon.cache/status`):
     ```clojure
     {:sym seon.fn/var-entity, :calls 359533, :self-ms 5917, :total-ms 202420}
     {:sym seon.program/definition-digest, :calls 492245, :self-ms 1134, :total-ms 193425}
     {:sym seon.program/used-resolver-context, :calls 479299, :self-ms 5539, :total-ms 183829}
     {:sym seon.program/authored-shapes, :calls 479483, :self-ms 1588, :total-ms 163902}
     {:sym seon.schema.edn/packaged-forms, :calls 487305, :self-ms 288, :total-ms 11524}
     ;; From (seon.cache/status):
     ;; :seon.program/authored-shapes {:hits 492765, :misses 12, :load-ms 30}
     ;; :seon.program/source-resolver-names {:hits 477737, :misses 14856, :load-ms 3581}
     ```
   - **MEASURED** (live JVM probe on `default` for 1,000 calls to `seon.program/definition-digest` and 1 call to `seon.fn/analysis-entities-by-file` on `src/seon/fn.clj`):
     ```clojure
     ;; 1,000 calls to (seon.program/definition-digest ent rc):
     ;; :ms 86.07, :calls 36540
     ;; seon.cache/claim: 3022 calls; seon.id/structural-digest: 2008 calls (54 ms self / 86 ms total!)
     ;; seon.program/authored-shapes: 1000 calls; seon.schema.edn/packaged-population: 1003 calls (9 ms self)

     ;; 1 call to (#'seon.fn/analysis-entities-by-file ana fp ctx {} proj) on src/seon/fn.clj (246 defs):
     ;; :ms 67.63, of which seon.cache/claim is 54 ms self (843 calls)!
     ```
   - **INFERRED** (`src/seon/program.cljc:512-517, 533-536`):
     ```clojure
     (seon.cache/lookup
      ::source-resolver-names (id/id [source qualifiers symbols] 64)
      ...)
     ...
     source (some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))
     {:keys [qualifiers symbols]}
     (when (string? source)
       (source-resolver-names source (set (keys aliases)) (set (keys refers))))
     ```
4. **Smallest fix**:
   - In `seon.program/used-resolver-context` (`src/seon/program.cljc:533`), read the source attribute directly as `(or (:seon.fn/source entity) (:seon.test/source entity) (:seon.ns/source entity))` (matching `seon.fn/declaration-digests` at `src/seon/fn.clj:1758`) instead of calling 1-arg `shape` -> `authored-shapes` -> `packaged-forms`.
   - In `seon.program/source-resolver-names` (`src/seon/program.cljc:492-518`), skip calling `source-resolver-names` when `(and (empty? aliases) (empty? refers))`, and cache the spelled `{:qualifiers ... :symbols ...}` in `seon.cache/lookup` keyed directly by `source` (`java.lang.String`, whose `hashCode` is cached on the `String` instance in $O(1)$ without running `seon.id/id` / `structural-digest`), then intersect with `(set (keys aliases))` and `(set (keys refers))` on return.
5. **Expected gain**:
   - Eliminates 479,299 calls to `authored-shapes`, `packaged-forms`, `packaged-population`, and `seon.id/structural-digest` on `source`. Saves **~165–175 seconds** out of the 193.4 s total in `seon.program/definition-digest` (and reduces `seon.fn/analysis-entities-by-file` on `src/seon/fn.clj` from 67.6 ms to ~13 ms, a **5× speedup**).
6. **End-to-end probe**:
   ```clojure
   (let [db (datahike.api/db (seon.cluster.boot/connection "default"))
         ent (select-keys (datahike.api/entity db [:seon.fn/sym 'seon.fn/analyzed-artifacts])
                          [:seon.fn/sym :seon.fn/source :seon.fn/arglists :seon.fn/spec
                           :seon.fn/doc :seon.fn/calls :seon.fn/references :seon.fn/keywords])
         rc {:requires #{} :aliases {'analyzer 'seon.fn.analyzer} :refers {} :imports {}}]
     (seon.profile/measure (fn [] (dotimes [_ 1000] (seon.program/definition-digest ent rc)))))
   ```

---

### 3. `seon.fn.analyzer/parsed-facts` — Uncached re-parsing (`kondo.utils/parse-string-all` + `kondo.utils/sexpr`) of unchanged files and double-parsing of files with `:seon.fn/invokes`

1. **Function**: `seon.fn.analyzer/parsed-facts`, `seon.fn.analyzer/analyze`, `seon.fn/analysis-entities-by-file`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**: `seon.fn.analyzer/parsed-facts` ran **9,792 times** for **65,253 ms self** (65,310 ms total, max 139 ms).
   - **Two causes**:
     1. **No content-digest cache on `parsed-facts` (`src/seon/fn/analyzer.clj:626-687`)**: Every call to `seon.fn.analyzer/analyze` calls `(parsed-facts filename source)` on every file in the batch (9,792 calls across ~588 distinct files = each file parsed **~16.6 times** on average). `parsed-facts` parses the entire file string with `kondo.utils/parse-string-all` and converts every top-level form with `(map kondo.utils/sexpr (:children root))` to extract `::mentioned-symbols`, `::var-quotes`, `::require-forms`, `::literal-defs`, `::namespaced-attribute-maps`, and `::task-maps-after-name`.
     2. **Immediate second `kondo.utils/parse-string-all` in `analysis-entities-by-file` (`src/seon/fn.clj:1566-1567`)**: For every file with any `:seon.fn/invokes` holder, `analysis-entities-by-file` runs `(map kondo.utils/sexpr (:children (kondo.utils/parse-string-all (:text context))))` again on the exact same `:text` that `parsed-facts` just parsed! Furthermore, lines 1558–1562 scan all `(::analyzer/var-definitions analysis)` for each `filename` in `contexts` ($O(F \times D)$) instead of grouping `var-definitions` by `::analyzer/filename` once.
   - **Immutable input**: In `parsed-facts`, the result (with relative/canonical `filename` attached to `::var-quotes` and `::require-forms`) is a pure function of `[filename source]` (keyable in `seon.cache/lookup` by `[filename (seon.id/sha-256 (.getBytes source StandardCharsets/UTF_8))]` or `source` when `contexts` already holds `:seon.fn.file/digest`).
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn` and live JVM probe):
     ```clojure
     {:sym seon.fn.analyzer/parsed-facts, :calls 9792, :self-ms 65253, :total-ms 65310, :max-ms 139}
     ;; Live JVM probe on src, test, script (1,117 parsed-facts calls in one analyze):
     ;; {:sym seon.fn.analyzer/parsed-facts, :calls 1117, :self-ms 5356}
     ;; Live JVM probe on src/seon/fn.clj alone:
     ;; {:sym seon.fn.analyzer/parsed-facts, :calls 1, :self-ms 40}
     ```
   - **INFERRED** (`src/seon/fn/analyzer.clj:636-647` and `src/seon/fn.clj:1556-1571`):
     ```clojure
     ;; src/seon/fn/analyzer.clj:636-647
     (defn- parsed-facts [filename source]
       (let [root (binding [kondo.reader/*reader-exceptions* (atom [])]
                    (kondo.utils/parse-string-all source))
             nodes (tree-seq (comp seq :children) :children root)]
         {::mentioned-symbols
          (into #{} (comp (map kondo.utils/sexpr) (mapcat #(tree-seq coll? seq %)) (filter qualified-symbol?))
                (:children root)) ...}))

     ;; src/seon/fn.clj:1566-1567
     values (map kondo.utils/sexpr
                 (:children (kondo.utils/parse-string-all (:text context))))
     ```
4. **Smallest fix**:
   - In `seon.fn.analyzer/parsed-facts` (`src/seon/fn/analyzer.clj:626`), wrap the parse in `seon.cache/lookup` under `::parsed-facts` keyed by `[filename source]` (storing location metadata without `filename` and associating `:filename` on lookup, or keying by `[filename source]`).
   - In `seon.fn/analysis-entities-by-file` (`src/seon/fn.clj:1556-1571`), group `(::analyzer/var-definitions analysis)` by `::analyzer/filename` once instead of scanning all `var-definitions` per file.
5. **Expected gain**:
   - With ~588 unique files across 9,792 calls (~94% hit rate), caching `parsed-facts` eliminates ~9,200 redundant full-file AST parses and `sexpr` walks, saving **~60 seconds** of self-time.
6. **End-to-end probe**:
   ```clojure
   (let [src (slurp "src/seon/fn.clj")]
     (seon.profile/measure
       (fn []
         (dotimes [_ 20]
           (#'seon.fn.analyzer/parsed-facts "src/seon/fn.clj" src)))))
   ```

---

### 4. `seon.fn.analyzer/contains-position?` & `seon.fn.analyzer/attributed-usages` — Quadratic $O(U \times Q)$ unindexed `var-quotes` scan and $O(U \times D)$ backward walk on miss in `innermost-span`

1. **Function**: `seon.fn.analyzer/contains-position?`, `seon.fn.analyzer/innermost-span`, `seon.fn.analyzer/attributed-usages`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**: `seon.fn.analyzer/contains-position?` was called **80,647,488 times** (**15,864 ms self**, ~211,119 calls per `analyze` invocation).
   - **Two quadratic loops in `attributed-usages` (`src/seon/fn/analyzer.clj:552-613`)**:
     1. **Unindexed $O(U \times Q)$ linear scan of `var-quotes` (`src/seon/fn/analyzer.clj:602, 610-611`)**:
        - `var-quotes` is grouped only by `:filename` (`(group-by :filename (:var-quotes analysis))`), not indexed by position.
        - For **every single usage** ($U$ usages in the file: `:var-usages` + `quoted` `:symbols` + `:java-class-usages`), line 610 runs `(some #(contains-position? % usage) (get var-quotes (:filename usage)))`.
        - Because fewer than 1% of usages are inside a `#'...` reader macro, 99%+ of usages scan **every `var-quote` in the file**. In `test/seon/fn_test.clj` alone ($U = 3,662$ usages, $Q = 42$ `var-quotes`), a single file analysis executes `3,662 × 42 ≈ 153,804` calls to `contains-position?`! Across a single full-tree `analyze` (`src`, `test`, `script`), `contains-position?` is called **2,879,532 times** (761 ms self).
     2. **$O(U \times S)$ backward linear walk to index `0` on miss in `innermost-span` (`src/seon/fn/analyzer.clj:568-571`)**:
        - `innermost-span` binary-searches `index` for `starting` (the count of spans starting at or before `entry`), then runs:
          `(loop [i (dec starting)] (when-not (neg? i) (let [span (nth index i)] (if (contains-position? span entry) span (recur (dec i))))))`
        - Whenever `entry` is **not** inside any span in `index` — which happens on `(innermost-span (get spans (:filename usage)) usage)` (line 604) for every ordinary usage in any file that has $M > 0$ `defmethod`s or `protocol-impl`s, and on `(innermost-span (get definitions (:filename usage)) usage)` (line 607) for every top-level usage without `:from-var` — the loop does not stop at the preceding disjoint top-level form; it walks `i` from `(dec starting)` **all the way down to `0`**, testing every earlier span in the file!
     3. **Redundant Malli contract and `:filename` check on every inner-loop call (`src/seon/fn/analyzer.clj:536, 541`)**: `contains-position?` is a private `defn-` with `{:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :boolean]}` and checks `(= (:filename span) (:filename entry))` even though `attributed-usages` already partitions `spans`, `definitions`, `calls`, and `var-quotes` by `:filename`.
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn`):
     ```clojure
     {:sym seon.fn.analyzer/contains-position?, :calls 80647488, :self-ms 15864, :total-ms 15864, :max-ms 30}
     ```
   - **MEASURED** (live JVM probe on `default` isolating `test/seon/fn_test.clj`):
     ```clojure
     (seon.profile/measure
       (fn []
         (seon.fn.analyzer/analyze
           {:seon.fn.analyzer/cache-root "/Users/sean/src/seon"
            :seon.fn.analyzer/paths ["/Users/sean/src/seon/test/seon/fn_test.clj"]})))
     ;; => {:ms 259.03, :called {seon.fn.analyzer/contains-position? 153242, ...}}

     ;; Breakdown of test/seon/fn_test.clj:
     ;; {:usages 3662, :var-usages 3464, :quoted-symbols 180, :java-class-usages 18,
     ;;  :without-from-var 302, :defs 101, :defmethods 0, :protocol-impls 0,
     ;;  :var-quotes 42, :usages-x-quotes 153804}
     ```
   - **INFERRED** (`src/seon/fn/analyzer.clj:568-571, 601-612`):
     ```clojure
     (loop [i (dec starting)]
       (when-not (neg? i)
         (let [span (nth index i)]
           (if (contains-position? span entry) span (recur (dec i))))))
     ...
     var-quotes (group-by :filename (:var-quotes analysis))
     (mapv (fn [usage]
             (if-let [span (innermost-span (get spans (:filename usage)) usage)]
               (merge usage (select-keys span [:from :from-var]))
               (let [definition (when-not (:from-var usage)
                                  (innermost-span (get definitions (:filename usage)) usage))]
                 (cond-> usage
                   definition (assoc :from (:ns definition) :from-var (:name definition))
                   (some #(contains-position? % usage)
                         (get var-quotes (:filename usage)))
                   (assoc :var-quote true)))))
           usages)
     ```
4. **Smallest fix**:
   - In `seon.fn.analyzer/attributed-usages` (`src/seon/fn/analyzer.clj:602, 610`): index `var-quotes` with `span-index` (`(update-vals (group-by :filename (:var-quotes analysis)) span-index)`) and query `(innermost-span (get var-quotes (:filename usage)) usage)` instead of `(some #(contains-position? % usage) ...)`.
   - In `seon.fn.analyzer/span-index` and `seon.fn.analyzer/innermost-span` (`src/seon/fn/analyzer.clj:547-571`): attach `:max-end` (`[end-row end-col]` prefix maximum) to each indexed span during `span-index` (5 lines), and in `innermost-span`'s backward loop, stop immediately (`nil`) when `(neg? (compare (:max-end span) position))` — since no span at `<= i` extends to `position`.
5. **Expected gain**:
   - Reduces `contains-position?` calls on `test/seon/fn_test.clj` from **153,242** to **~500** (>300× reduction), and across the full codebase from **2,879,532** per full `analyze` (80.6M since boot) to **~15,000** per full `analyze`, saving **~15.5 seconds** of pure CPU and instrumentation overhead.
6. **End-to-end probe**:
   ```clojure
   (seon.profile/measure
     (fn []
       (seon.fn.analyzer/analyze
         {:seon.fn.analyzer/cache-root "/Users/sean/src/seon"
          :seon.fn.analyzer/paths ["/Users/sean/src/seon/test/seon/fn_test.clj"]})))
   ```

---

### 5. `seon.program/with-contract-facts`, `seon.fn/declaration-digests`, & `seon.fn.signature/declaration-metadata` — Uncached re-reading and Malli compilation of unchanged function contracts and sources

1. **Function**: `seon.program/with-contract-facts`, `seon.program/contract-facts`, `seon.fn/declaration-digests`, `seon.fn.signature/declaration-metadata`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**:
     - `seon.program/with-contract-facts`: **113,783 calls**, **29,609 ms total** (10,701 ms self).
     - `seon.fn/declaration-digests`: **9,609 calls**, **14,189 ms total** (1,898 ms self), calling `seon.fn.signature/declaration-metadata` **354,010 times** (**9,838 ms self**).
     - `seon.fn/authored-contract`: called on every contract-bearing `var-entity`, calling `seon.schema/canonical-definition` **63,991 times** (**11,929 ms total**).
   - **Why it repeats**:
     1. `seon.program/contract-facts` (`src/seon/program.cljc:1323-1390`) parses `(:seon.fn/spec entity)` with `edn/read-string`, compiles the Malli schema via `schema/contract-product`, and parses `(:seon.fn/source entity)` with `signature/function-signatures` (`LispReader/read`). It is called 113,783 times across indexing and admission (`seon.fn/add-contract-facts` at `src/seon/fn.clj:3284`) with **no cache**, even though its output is a pure function of `[(:seon.schema.projection/forms projection) (:seon.fn/sym entity) (:seon.fn/spec entity) (:seon.fn/arglists entity) (:seon.fn/source entity)]`.
     2. `seon.fn/declaration-digests` (`src/seon/fn.clj:1745-1775`) is called both during `artifact` construction (`src/seon/fn.clj:1814`) AND on every file in the database inside `seon.fn/database-manifest` (`src/seon/fn.clj:4033`), calling `(signature/declaration-metadata source (or namespace-name 'user) aliases)` (**354,010 calls, 9,838 ms self**), which runs `LispReader/read` over every function's `:seon.fn/source` without caching. Furthermore, for every `:seon.ns/name` entity in `entities` (`src/seon/fn.clj:1769-1774`), `declaration-digests` linearly scans `entities`!
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn`):
     ```clojure
     {:sym seon.program/with-contract-facts, :calls 113783, :self-ms 10701, :total-ms 29609}
     {:sym seon.fn/declaration-digests, :calls 9609, :self-ms 1898, :total-ms 14189}
     {:sym seon.fn.signature/declaration-metadata, :calls 354010, :self-ms 9838, :total-ms 9838}
     {:sym seon.schema/canonical-definition, :calls 63991, :self-ms 634, :total-ms 11929}
     ```
   - **INFERRED** (`src/seon/program.cljc:1323-1335` and `src/seon/fn.clj:1761-1769`):
     ```clojure
     ;; src/seon/program.cljc:1323-1335
     (defn contract-facts [projection entity]
       (when-let [spec (:seon.fn/spec entity)]
         (let [function-symbol (:seon.fn/sym entity)
               form (edn/read-string spec)
               {:keys [arities refs]} (schema/contract-product projection function-symbol form)
               signatures (when-let [source (:seon.fn/source entity)]
                            (signature/function-signatures source)) ...])))

     ;; src/seon/fn.clj:1768
     (when source (signature/declaration-metadata source (or namespace-name 'user) aliases))
     ```
4. **Smallest fix**:
   - In `seon.program/contract-facts` (`src/seon/program.cljc:1323`), cache the derived contract facts map via `seon.cache/lookup` under `::contract-facts` keyed by `[(:seon.schema.projection/id projection) (:seon.fn/sym entity) (:seon.fn/spec entity) (:seon.fn/arglists entity) (:seon.fn/source entity)]` (falling back to `:seon.schema.projection/forms` when `:id` is absent).
   - In `seon.fn.signature/declaration-metadata` (`src/seon/fn/signature.cljc:118`), cache via `seon.cache/lookup` (on `#?(:clj ...)`) keyed by `[source current-ns aliases]`.
5. **Expected gain**:
   - Across 113,783 `with-contract-facts` calls and 354,010 `declaration-metadata` calls over ~4,700 distinct declarations (~98% hit rate), saves **~35–38 seconds** of repeated `LispReader/read` and Malli schema compilation.
6. **End-to-end probe**:
   ```clojure
   (let [db (datahike.api/db (seon.cluster.boot/connection "default"))
         proj (seon.db/carried-projection db)
         ent (select-keys (datahike.api/entity db [:seon.fn/sym 'seon.fn/analyzed-artifacts])
                          [:seon.fn/sym :seon.fn/spec :seon.fn/arglists :seon.fn/source])]
     (seon.profile/measure (fn [] (dotimes [_ 500] (seon.program/with-contract-facts proj ent)))))
   ```

---

### 6. `seon.fn/assert-clean-analysis!`, `seon.fn/host-bound-callers`, & `seon.fn/writes-by-writer` — Duplicate calls in `analyzed-artifacts` and eager scans of all `var-usages` and file keywords

1. **Function**: `seon.fn/assert-clean-analysis!`, `seon.fn/publication-findings`, `seon.fn/host-bound-callers`, `seon.fn/writes-by-writer`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**:
     - `seon.fn/assert-clean-analysis!`: **496 calls**, **14,760 ms self** (14,765 ms total).
     - `seon.fn/host-bound-callers`: **378 calls**, **8,473 ms self** (9,115 ms total).
     - `seon.fn/writes-by-writer`: **378 calls**, **5,960 ms self**.
   - **Three concrete inefficiencies**:
     1. **`assert-clean-analysis!` is called TWICE per `analyzed-artifacts` call (`src/seon/fn.clj:2796, 2810`) and eagerly builds `external-usage-spans` over all `var-usages` even when there are no `:unresolved-var` findings (`src/seon/fn.clj:1434-1464`)**:
        - In `analyzed-artifacts`, line 2796 calls `(assert-clean-analysis! analysis (first-party-function-symbols analysis))`, line 2810 calls `(assert-clean-analysis! analysis functions)` again, and line 2811 calls `(publication-findings analysis functions)`.
        - Each of those 3 calls invokes `(external-usage-spans analysis first-party-functions)`, which iterates over **every entry in `(::analyzer/var-usages analysis)`** and allocates `(select-keys entry source-span-keys)` into a hash set — even though `external-spans` is **only** read by `admitted-external-finding?` when a finding has `(= :unresolved-var (::analyzer/type finding))`! When `(::analyzer/findings analysis)` contains no `:unresolved-var` findings (the common case), building `external-spans` 3 times per `analyzed-artifacts` call does 100% wasted work across all `var-usages`.
     2. **`host-bound-callers` scans `(::analyzer/var-usages analysis)` 3 times (`src/seon/fn.clj:1194-1216`)**:
        - Passes over `(::analyzer/var-usages analysis)` three separate times (for `unresolved-macro-refusal`, for `reify`/`proxy`/`definterface`/`import`, and for `host-call?` macros), recomputing `usage-symbol` and `host-call?` each time, and `interpreted-namespaces` rebuilds `(into (map (comp symbol namespace)) first-party-functions)` on every call.
     3. **`writes-by-writer` allocates a `keyword` for every keyword in the file before checking `span-contains?` (`src/seon/fn.clj:475-494`)**:
        - For every `seon.db/transact!` usage in a file, `writes-by-writer` iterates over all `(get keywords-by-file (::analyzer/filename usage))`, allocating `(keyword (str keyword-namespace) (str keyword-name))` on every keyword in the file *before* checking `(span-contains? usage entry)` (and without binary-searching or filtering keywords by `[row col]` span).
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn`):
     ```clojure
     {:sym seon.fn/assert-clean-analysis!, :calls 496, :self-ms 14760, :total-ms 14765, :max-ms 280}
     {:sym seon.fn/host-bound-callers, :calls 378, :self-ms 8473, :total-ms 9115, :max-ms 190}
     {:sym seon.fn/writes-by-writer, :calls 378, :self-ms 5960, :total-ms 5960, :max-ms 177}
     ```
   - **INFERRED** (`src/seon/fn.clj:1447-1464, 2796, 2810-2811`):
     ```clojure
     (defn- blocking-findings [analysis first-party-functions]
       (let [external-spans (external-usage-spans analysis first-party-functions)]
         (filterv #(and (load-refusal-finding? %)
                        (not (admitted-external-finding? external-spans %)))
                  (::analyzer/findings analysis))))
     ...
     _ (assert-clean-analysis! analysis (first-party-function-symbols analysis))
     ...
     _ (assert-clean-analysis! analysis functions)
     findings (group-by ::analyzer/filename (publication-findings analysis functions))
     ```
4. **Smallest fix**:
   - In `seon.fn/publication-findings` and `seon.fn/blocking-findings` (`src/seon/fn.clj:1447-1464`), compute `external-spans` (`(delay (external-usage-spans analysis first-party-functions))`) only when `(::analyzer/findings analysis)` actually contains an `:unresolved-var` finding, and remove the redundant first `(assert-clean-analysis! analysis (first-party-function-symbols analysis))` at `src/seon/fn.clj:2796` (or check only syntax/namespace findings before the DB query if needed).
   - In `seon.fn/host-bound-callers` (`src/seon/fn.clj:1194-1216`), fold the three passes over `(::analyzer/var-usages analysis)` into a single `reduce` over `var-usages`.
   - In `seon.fn/writes-by-writer` (`src/seon/fn.clj:480-490`), check `(span-contains? usage entry)` and `keyword-namespace` *before* constructing `(keyword (str keyword-namespace) (str keyword-name))`.
5. **Expected gain**:
   - Eliminates `external-usage-spans` completely on clean analyses (saving **~14.5 s** in `assert-clean-analysis!` + `publication-findings`), cuts `host-bound-callers` by ~60% (**~5 s**), and cuts `writes-by-writer` by ~75% (**~4.5 s**): **~24 seconds total**.
6. **End-to-end probe**:
   ```clojure
   (let [ana (seon.fn.analyzer/analyze
               {:seon.fn.analyzer/cache-root "/Users/sean/src/seon"
                :seon.fn.analyzer/paths ["/Users/sean/src/seon/src/seon/fn.clj"]})
         fp (#'seon.fn/first-party-function-symbols ana)]
     (seon.profile/measure
       (fn []
         (dotimes [_ 50]
           (#'seon.fn/assert-clean-analysis! ana fp)))))
   ```

---

### 7. `seon.test.cache/input-path?` & `seon.test.cache/widening-path?` — 3.57M instrumented calls scanning 45 roots across all 6,907 `git ls-files` paths (83% in `docs/`, `evals/`, `v1/`, `tmp/`)

1. **Function**: `seon.test.cache/input-path?`, `seon.test.cache/widening-path?`, `seon.test.cache/input-paths`, `seon.cluster.source/discover-paths`
2. **What repeats or is super-linear and on which unchanged input**:
   - **Since boot**: `seon.test.cache/input-path?` was called **3,571,759 times** (**11,003 ms self**); `seon.test.cache/widening-path?` was called **1,134,274 times** (**7,030 ms total**, 2,091 ms self).
   - **Why it repeats and is super-linear ($O(P \times |R|)$ with per-call Malli set validation)**:
     1. `seon.test.cache/input-paths` (`src/seon/test/cache.clj:118-131`) runs `git ls-files -c -o --exclude-standard` across the entire repository with no pathspecs, returning **6,907 paths** — of which **5,731 paths (83%)** live in `docs/` (4,040), `evals/` (753), `v1/` (561), and `tmp/` (377) outside both `source-roots` and `input-roots`.
     2. `seon.test.cache/input-roots` (`src/seon/test/cache.clj:105-116`) returns a set of **45 roots** (including 37 `reference-code/*` submodule roots).
     3. In `widening-paths` (`src/seon/test/cache.clj:173`), `discover-paths` (`src/seon/cluster/source.clj:141-151`), and `snapshot-test-input-digest` (`src/seon/cluster/source.clj:250`), `widening-path?` and `input-path?` are called inside per-path `filter` predicates: `(filter #(widening-path? roots %) (input-paths directory))`.
     4. Because `input-path?` and `widening-path?` carry Malli contracts `[:=> [:cat [:set [:string {:min 1}]] [:string {:min 1}]] :boolean]`, **every single call validates the 45-element `roots` set** (`3,571,759 + 1,134,274 = 4,706,033` calls × 45 elements = **211.7 million string validations**!), and then `input-path?` linearly scans all 45 `roots` doing `(str root "/")` allocation and `str/starts-with?`!
3. **Evidence**:
   - **MEASURED** (`tmp/argon-perf-profile.edn`):
     ```clojure
     {:sym seon.test.cache/input-path?, :calls 3571759, :self-ms 11003, :total-ms 11003, :max-ms 15}
     {:sym seon.test.cache/widening-path?, :calls 1134274, :self-ms 2091, :total-ms 7030, :max-ms 15}
     ```
   - **MEASURED** (live JVM probe on `default` over `/Users/sean/src/seon`):
     ```clojure
     (let [dir "/Users/sean/src/seon"
           roots (seon.test.cache/input-roots dir)
           paths (seon.test.cache/input-paths dir)
           m (seon.profile/measure
               (fn []
                 (count (filter #(seon.test.cache/widening-path? roots %) paths))))]
       {:roots-count (count roots) :paths-count (count paths) :ms (:seon.profile/ms m)
        :called (:seon.profile/called m)})
     ;; => {:roots-count 45, :paths-count 6907, :ms 73.69,
     ;;     :called {seon.test.cache/widening-path? 6907,
     ;;              seon.test.cache/input-path? 6907,
     ;;              seon.test.cache/source-file? 588}}
     ;; Top-level directory breakdown of the 6,907 paths:
     ;; [["docs" 4040] ["evals" 753] ["v1" 561] ["test" 449] ["tmp" 377]
     ;;  ["resources" 329] ["src" 111] ...]
     ```
   - **INFERRED** (`src/seon/test/cache.clj:142-150`):
     ```clojure
     (defn input-path?
       {:malli/schema [:=> [:cat [:set [:string {:min 1}]] [:string {:min 1}]] :boolean]}
       [roots path]
       (boolean
        (some (fn [root]
                (or (= path root)
                    (str/starts-with? path (str root "/"))))
              roots)))
     ```
4. **Smallest fix**:
   - In `seon.test.cache/input-path?` (`src/seon/test/cache.clj:142-150`), check whether `path` or any `/` prefix of `path` is in the hash set `roots` (`contains? roots ...`) via `str/index-of` in $O(\text{depth}(path)) \approx 2$ hash-set lookups instead of iterating over all 45 `roots` and allocating `(str root "/")`.
   - Pass pathspecs `(into (vec seon.fn/source-roots) roots)` to `git ls-files` in `input-paths` (or filter batches inside `widening-paths` / `discover-paths` using an uninstrumented inner predicate after validating `roots` once at the batch entry).
5. **Expected gain**:
   - Reduces `input-paths` output from 6,907 paths to ~920 relevant paths and eliminates 211M Malli set-element validations and $45\times$ linear root scans, saving **~15–17 seconds** across boot/rehearsals (~70 ms saved per `discover-paths` + `widening-paths` pair).
6. **End-to-end probe**:
   ```clojure
   (let [dir "/Users/sean/src/seon"
         roots (seon.test.cache/input-roots dir)
         paths (seon.test.cache/input-paths dir)]
     (seon.profile/measure
       (fn []
         (count (filter #(seon.test.cache/widening-path? roots %) paths)))))
   ```

---

## Five-Line Summary

1. **Whole-program re-analysis & rehearsal overhead (`1,557 s` in `rehearse-source!`, `669 s` in `analyzed-artifacts`)**: `rehearse-source!` copies `.clj-kondo/.cache` and runs `full-source-refresh!` with `partial? = false` (scanning all 6,907 repo files), widens to all ~588 source files on any schema/widening change, and discards Pass 1 analysis when recursing on `stale` files while `index!` invokes `analyzed-artifacts` up to 3 times per refresh without caching file artifacts by `seon.cluster/file-derivation-key`.
2. **Repeated schema-population cache claims per declaration (`164 s` in `authored-shapes`, `193 s` in `definition-digest`)**: `seon.program/used-resolver-context` calls 1-arg `seon.program/shape` (`authored-shapes` -> `packaged-forms` -> `seon.cache/lookup` on a 1,000-entry map key) and `source-resolver-names` runs `seon.id/structural-digest` on full source strings on every single `definition-digest` call (479,299 times).
3. **Uncached AST parses and contract compilations (`65 s` in `parsed-facts`, `30 s` in `with-contract-facts`, `14 s` in `declaration-digests`)**: `seon.fn.analyzer/parsed-facts` (9,792 calls), `seon.program/contract-facts` (113,783 calls), and `seon.fn.signature/declaration-metadata` (354,010 calls) re-parse unchanged source/spec strings with `kondo.utils/parse-string-all` and `LispReader/read` without consulting `seon.cache/lookup`.
4. **Quadratic position scans in analyzer (`80.6M` calls / `15.9 s` in `contains-position?`)**: `seon.fn.analyzer/attributed-usages` linearly scans all `var-quotes` in a file for every usage ($O(U \times Q)$, 153k calls on `test/seon/fn_test.clj` alone, 2.88M per full tree) and `innermost-span` walks backward to index `0` on every miss ($O(U \times D)$).
5. **Redundant `var-usages` and `git ls-files` scans (`15 s` in `assert-clean-analysis!`, `15 s` in `host-bound-callers` + `writes-by-writer`, `18 s` in `input-path?`/`widening-path?`)**: `analyzed-artifacts` calls `assert-clean-analysis!` twice and builds `external-usage-spans` 3 times even when no `:unresolved-var` findings exist, while `input-path?` validates the 45-element `roots` set via Malli and scans 45 roots on all 6,907 `git ls-files` paths (83% in `docs/`, `evals/`, `v1/`, `tmp/`).
