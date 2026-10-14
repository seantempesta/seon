# Write-Path Performance Diagnosis (`seon.db/transact-call`)

## 1. Executive Breakdown: Proportionality, Histograms, and Datahike Commit vs. Seon Checks

### Why `seon.db/transact-call` reports 391.9 s `self-ms` out of 393.5 s `total-ms`
In `src/seon/db.clj:6112-6186`, `transact-call` runs `prepared-write` on the calling thread (~1.1 ms mean, accounting for the 1.6 s difference between `total-ms` and `self-ms`), dispatches `(d/transact! connection request)` to Datahike's asynchronous `LocalWriter` channel (`reference-code/datahike/src/datahike/writer.cljc:146-341`), and blocks on `(deref pending ...)`. Because `seon.profile/timed` (`src/seon/profile.clj:221, 265-280`) tracks `callee-frame` in a per-thread `ThreadLocal`, armed Seon functions executed on Datahike's `processing-thread` (`seon.db/digested-call` for `:db.fn/call` transaction functions and `seon.db/validate-write-report` for `:datahike/validate-report`) do **not** subtract their elapsed time from `transact-call`'s frame on the caller thread. Consequently, `transact-call`'s `self-ms` (391.9 s across 1,396 calls at snapshot; 393.3 s across 1,425 calls including probes) is the **entire asynchronous writer round-trip**: Seon's in-transaction `:db.fn/call` functions + Datahike's in-memory index insertion + Seon's `:datahike/validate-report` checks + Datahike's `w/commit!` (index flush, LZ4 compression, Konserve `:file` writes) + writer queue wait.

### Bimodal Distribution in `seon.profile/cells-of` Histograms (`MEASURED`)
Probing `(seon.profile/cells-of ['seon.db/transact-call nil])` alongside its writer-thread callees exposes a sharp **two-mode distribution**:

```clojure
;; MEASURED via eval_clj (mode "jvm", agent "root", cluster "default"):
;; seon.db/transact-call (:calls 1396, :total-ms 393532, :self-ms 391948, :p50-us 33554, :mean-us 281900, :p99-us 4294967, :max-ms 4241)
:buckets
[[[21 2097] 10]    [[22 4194] 90]    [[23 8388] 199]   [[24 16777] 98]
 [[25 33554] 332]  [[26 67108] 323]  [[27 134217] 129] [[28 268435] 18]
 [[29 536870] 7]   [[30 1073741] 42] [[31 2147483] 80] [[32 4294967] 68]]
```

1. **Mode 1 — Ordinary recording transactions (1,181 calls in buckets 21–27, 2–134 ms, `p50` = 33.5 ms, ~42 s total)**:
   - Every REPL / turn recording transaction (`:settle` stage in `:seon.repl/request` is consistently 28–44 ms for 1 transaction).
   - For these 1,181 calls:
     - `seon.db/validate-write-report` / `seon.db/write-report-error` has `p50` = **2.1–4.2 ms** (buckets 20–22 hold 1,165 of 1,520 calls; measured directly via `seon.profile/measure` on a 1-datom eval update at **0.89 ms** and a 1-function update at **5.06 ms**).
     - `prepared-write` + `write-error` on the caller thread takes **~1.1 ms**.
     - **Datahike index insertion + `w/commit!`** takes the remaining **~26–30 ms** (`db->stored` flushing dirty persistent-sorted-set nodes across 6 primary + temporal trees for a 652,089-datom database, computing Merkle commit-id, and `k/multi-assoc` writing LZ4-compressed nodes + commit record + `:cluster-default` branch head to `/Users/sean/src/seon/data/store`).
2. **Mode 2 — Program reconciliation / adoption / publication transactions (190 calls in buckets 30–32, 1.07 s–4.24 s, ~351 s total = 89% of `transact-call` time)**:
   - Exactly **190 calls** sit in the 1.07 s–4.29 s buckets (`42 + 80 + 68 = 190`).
   - The **exact same 190-call upper mode** appears in every Seon writer-thread function's `cells-of` histogram:
     - `seon.db/write-report-error` (1,520 calls, 85.2 s total): buckets 28–30 (`268 ms–1.07 s`) contain `29 + 122 + 39 = 190` calls (~78 s of its 85.2 s).
     - `seon.db/write-owned-values-error` (1,520 calls, 48.9 s total, 22.7 s self): buckets 27–30 (`134 ms–1.07 s`) contain `11 + 129 + 48 + 2 = 190` calls (~45 s of its 48.9 s).
     - `seon.db/write-digest-error` (1,519 calls, 22.8 s total): buckets 26–28 (`67 ms–268 ms`) contain `12 + 122 + 56 = 190` calls (~21 s of its 22.8 s).
     - `seon.db/digested-call` (3,389 calls, 61.8 s total, wrapping `seon.fn/reconcile-tx-in` which has 315 calls, 49.9 s total, `p50` = 268 ms, `p99` = 1.07 s): buckets 27–30 (`134 ms–1.07 s`) contain `55 + 110 + 64 + 5 = 234` calls.

### Split of `transact-call`'s 393.5 s Total Time (`MEASURED`)
| Component on the Write Path | Cumulative Time (s) | Share of `transact-call` | Where it runs & what it is proportional to |
|---|---:|---:|---|
| **Seon `:datahike/validate-report` (`seon.db/validate-write-report`)** | **102.2 s** | **26.0%** | Writer `processing-thread`: `write-report-error` (85.2 s: `write-owned-values-error` 48.9 s + `write-digest-error` 22.8 s + `report-arity-comparison` 7.6 s + `retention-report-check` 1.1 s) + `speculative-report-projection` (11.7 s) + `carried-projection` (4.0 s). Proportional to **touched entities × all ~25 component attributes + whole namespace member count + whole schema map**. |
| **Seon `:db.fn/call` (`seon.db/digested-call`)** | **62.0 s** | **15.8%** | Writer `processing-thread`: `seon.fn/reconcile-tx-in` (49.9 s) + `encode-call-output-in` (7.8 s) + `namespace-digests-tx` (3.4 s) + `cost-upserts` (0.8 s) + `vectors-tx` (0.3 s). Proportional to **the entire passed entity population (1,200–7,000 entities pulled with `[*]` and normalized on the writer thread)** even when 0 or 1 entity changed. |
| **Seon caller-side `prepared-write` + `hold-committed-projection!`** | **2.3 s** | **0.6%** | Caller thread: `prepared-write` (~1.6 s) + `hold-committed-projection!` (0.7 s). |
| **Datahike index apply (`core/with`) + `w/commit!` + writer queue wait** | **227.0 s** | **57.6%** | Writer `processing-thread` + `commit-thread`: ~35–40 s across the 1,181 ordinary transactions (~30 ms/commit for 6-tree PSS flush + LZ4 + file write) and ~187–192 s across the 190 multi-thousand-datom reconciliation/adoption transactions (plus callers queued behind a 1–4 s writer transaction). |

---

## 2. Ranked Findings

### Finding 1: `seon.fn/reconcile-tx-in`, `seon.fn/normalized-index-entity`, and `seon.db/stored-collection` pull and normalize the entire entity population inside the single-threaded writer and re-derive `(seon.db/carried-projection database)` 2.54M times

1. **Function**: `seon.fn/reconcile-tx-in`, `seon.fn/normalized-index-entity`, `seon.db/stored-collection`, `seon.db/carried-projection`, `seon.db/speculative-projection`, `seon.db/declares-program?`.
2. **What repeats or is super-linear and on which unchanged input**:
   - **Whole-population work inside the single-threaded Datahike writer on unchanged entities**: `reconcile-tx-in` (`src/seon/fn.clj:3721-3792`, 315 calls, **49.9 s total**, `p50` = 268 ms, max = 1,148 ms) runs inside Datahike's writer via `[:db.fn/call reconcile-tx-in ...]`. For every reconciliation or adoption batch (`entity-maps`), it unconditionally calls `(db/pull-many database '[*] identities)` on **every desired identity** (pulling and decoding full entity graphs and vector cells for thousands of unchanged entities) and then runs `normalized-index-entity` twice per entity (`normalized-current` and `normalized-desired`, **387,804 calls, 25.6 s total**), even when the stored entity's immutable `:seon.program/definition-digest` (or `:seon.fn.file/content-digest` / `:seon.fn.file/derivation-digest`) already equals the desired entity's digest and no non-digest owned attributes changed.
   - **Per-attribute projection lookup on an unchanged `database` value**: Inside `normalized-index-entity` (`src/seon/fn.clj:3665`) and `published-index-entities` (`src/seon/fn.clj:3951, 3956`), every cardinality-many attribute of every entity calls `(db/stored-collection database attribute normalized)`. `seon.db/stored-collection` (`src/seon/db.clj:5409-5418`) takes `database` instead of `projection` (or `entity-shapes`, which `reconcile-tx-in` already holds in its first parameter!) and calls `(carried-projection database)` on **every single cardinality-many attribute**.
   - **90.5% of all `carried-projection` calls in the system come from `stored-collection`**: `stored-collection` was called **2,540,820 times (24.6 s total)** out of **2,806,477 total calls to `carried-projection` (64.8 s total)**. Worse, when `database` is the writer's uncommitted in-transaction `DB` value (`committed-value-identity` is `nil`), `carried-projection` (`src/seon/db.clj:1935-1974`) cannot take the `committed` fast path at line 1922: on **every single call** it executes `(declares-program? source)` (doing an AVET index seek `(first (d/datoms database :avet :seon.schema/key))`, **1,207,409 calls, 2.55 s**), `(projection-builder origin)` (**2,808,302 calls, 7.27 s**), `(projection-cache-key source)` (**2,809,324 calls, 2.73 s**), and—whenever the transaction touched any declaration attribute—`(speculative-projection @builder revision-key source)` (**708,978 calls, 13.68 s**).
   - Both `projection` and `entity-shapes` (`(seon.program/shapes-in projection)`, which already precomputes `:seon.program/set-attributes` for each family) are functions of the immutable `database` basis and are **already held in local bindings** by `reconcile-tx-in` (`src/seon/fn.clj:3721-3723`) and `published-index-entities` (`src/seon/fn.clj:3909`).
3. **Evidence**:
   - `MEASURED` (cumulative profile & `cells-of` on `default`):
     - `seon.db/carried-projection`: **2,806,477 calls, 64,798 ms total, 8,046 ms self** (`p50-us` 8, `mean-us` 23, `max-ms` 1,970).
     - `seon.db/stored-collection`: **2,540,820 calls, 24,565 ms total, 4,313 ms self**.
     - `seon.db/speculative-projection`: **708,978 calls, 13,681 ms total, 3,124 ms self**.
     - `seon.db/projection-builder`: **2,808,302 calls, 7,273 ms total, 3,606 ms self**.
     - `seon.db/declares-program?`: **1,207,409 calls, 2,545 ms total, 2,452 ms self**.
     - `seon.fn/reconcile-tx-in`: **315 calls, 49,923 ms total, 712 ms self** (`p50-us` 268,435, `p99-us` 1,073,741, `max-ms` 1,148).
     - `seon.fn/normalized-index-entity`: **387,804 calls, 25,639 ms total, 4,132 ms self**.
   - `MEASURED` (live read-only probe running `reconcile-tx-in` on just 20 unchanged functions on an uncommitted speculative DB):
     ```clojure
     (let [conn (seon.cluster.boot/connection "default")
           db (seon.db/db conn)
           proj (seon.db/carried-projection db)
           shapes (seon.program/shapes-in proj)
           fn-eids (vec (take 20 (map :e (datahike.api/datoms db :aevt :seon.fn/sym))))
           fn-identities (mapv (fn [eid] [:seon.fn/sym (:v (first (datahike.api/datoms db :eavt eid :seon.fn/sym)))]) fn-eids)
           published-20 (seon.fn/published-index-entities db fn-identities)
           spec-datom (first (datahike.api/datoms db :aevt :seon.fn/spec))
           uncommitted-db (:db-after (datahike.core/with db [[:db/add (:e spec-datom) :seon.fn/spec (:v spec-datom)]]))]
       (seon.profile/measure
         (fn [] (#'seon.fn/reconcile-tx-in shapes uncommitted-db published-20 fn-identities false))))
     ;; => {:ms 8.74, :calls 7087, :alloc-bytes 11007480, :tx-ops-returned 0,
     ;;     :by-calls [[seon.db/database-value? 2219] [seon.schema/projection-cache-value 992]
     ;;                [seon.schema.datahike/vector-shaped-attr-in? 478] [seon.db/declares-program? 302]
     ;;                [seon.db/carried-projection 302] [seon.db/projection-builder 302]
     ;;                [seon.db/projection-cache-key 302] [seon.db/stored-collection 292]]}
     ```
     For just **20 unchanged functions** (0 tx-ops returned), `reconcile-tx-in` makes **7,087 armed calls**, calls `carried-projection` and `declares-program?` **302 times**, allocates **11.0 MB**, and takes **8.74 ms** (scaling linearly to **~1.5 s and ~1.2M calls** on a 3,500-entity population).
   - `INFERRED` (`src/seon/db.clj:5409-5418` and `src/seon/fn.clj:3665`):
     ```clojure
     ;; src/seon/db.clj:5409-5418
     (defn stored-collection
       [database attribute values]
       (if (schema.datahike/vector-attribute-in? (carried-projection database) attribute)
         (vec values)
         (set values)))
     ```
4. **Smallest fix by the owner**:
   - In `seon.db/stored-collection` (`src/seon/db.clj:5409`), accept `projection` (or add a 3-arity / pass `projection` from callers that already hold it, or call `schema.datahike/logical-collection projection attribute values` directly in `seon.fn/normalized-index-entity` and `seon.fn/published-index-entities` using the `projection` already bound in `reconcile-tx-in` and `published-index-entities`).
   - In `seon.db/carried-projection` (`src/seon/db.clj:1920-1975`), check the revision-key cache (`projection-cache` and `value-projection-cache`) **before** calling `(declares-program? source)` on native uncommitted databases (just as lines 1921–1933 already do for `view-key` and `committed`), eliminating 1.2M AVET seeks in `declares-program?`.
   - In `seon.fn/reconcile-tx-in` (`src/seon/fn.clj:3728-3750`), filter `entity-maps` before full `[*]` pull/normalization by comparing stored digests (`:seon.program/definition-digest`, `:seon.fn.file/content-digest`, `:seon.fn.file/derivation-digest`) and graph-edge attributes first, pulling `[*]` and normalizing only entities whose digest or non-digest attributes actually differ.
5. **Expected gain**:
   - Eliminates ~2.5M calls to `carried-projection` / `projection-builder` / `projection-cache-key`, ~1.2M calls to `declares-program?`, and ~708k calls to `speculative-projection` (**~35–45 s cumulative CPU/wall time**), and cuts `reconcile-tx-in` writer-thread blocking time by **70–90% (~35–45 s across the 315 calls)**, directly removing the writer queue stalls behind Mode 2 transactions.
6. **End-to-end probe**:
   - Run the `seon.profile/measure` probe above on `(#'seon.fn/reconcile-tx-in shapes uncommitted-db published-20 fn-identities false)`: `:calls` drops from `7,087` to `< 100`, `seon.db/carried-projection` calls drop from `302` to `1`, and `:ms` drops from `8.74 ms` to `< 0.5 ms`.

---

### Finding 2: `seon.db/write-owned-values-error` and `seon.db/write-report-error` repeat schema scans, $O(|S| \times |C|)$ reverse-component index seeks, duplicate `:before`/`:after` entity reconstructions, and 15x `:seon.db/transaction-report` datom validations per transaction

1. **Function**: `seon.db/write-owned-values-error`, `seon.db/write-entity-value`, `seon.db/write-attribute-plan`, `seon.db/write-report-error`, `seon.db/transaction-report-datom?`.
2. **What repeats or is super-linear and on which unchanged input**:
   - **Per-transaction scan of the full 2,404-attribute Datahike schema map (`src/seon/db.clj:4451-4457`)**: On every call to `write-owned-values-error` (1,556 calls, **48.9 s total, 22.7 s self**), it merges `(dbi/-schema before)` and `(dbi/-schema after)` (2,404 entries each) to find `:db/isComponent` attributes (`components`) and queries `mr/schema` for each component's `:seon.db/component-schema` (`targets`). Both `components` and `targets` are pure functions of the immutable `projection` (via `seon.schema/projection-cache-value`) plus any newly installed schema attributes when `before` and `after` differ.
   - **$O(|\text{seeds}| \times |\text{component-attributes}|)$ AVET seeks on both `before` and `after` (`src/seon/db.clj:4516-4556`)**: For every touched entity ID $\le \text{max-eid}$ (`seeds`), `owning-ancestors` calls `owners-of` on `:before` and again on `:after`. `owners-of` loops over every populated `:db/isComponent` attribute in the store (~25 attributes) and runs `(dbi/search database [nil searched entity-id])` (~50 index seeks per touched entity!). Yet:
     1. A top-level entity that carries an identity attribute (e.g. `:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name`, `:seon.cluster.eval/id`, `:seon.turn/id`) is a root entity and is **never** an anonymous component of another entity.
     2. A vector cell entity (`:seon.db.vector/position`) can **only** be owned via `:seon.db.vector/cells` (1 seek, not 25 seeks across `:seon.fn/arities`, etc.).
   - **Unconditional `:before` entity + vector-cell reconstruction in `root-identities` (`src/seon/db.clj:4655-4660`)**:
     ```clojure
     root-identities
     (mapv (fn [root]
             (let [value (get expanded root)]
               [root value (merge (select-keys (entity-map :before before root) identity-attrs)
                                  (select-keys value identity-attrs))]))
           roots)
     ```
     For **every** root entity in `roots`, line 4658 unconditionally calls `(entity-map :before before root)` → `write-entity-value` (reading all EAVT datoms and all vector cells of `root` in `before`, **doubling `write-entity-value` calls on roots**) solely to extract `(select-keys ... identity-attrs)` in case an identity attribute was retracted! Checking `identity-attrs` on `before` only when `(select-keys value identity-attrs)` is empty (or reading only retracted `identity-attrs` from `(:tx-data report)`) avoids reconstructing the entire `:before` entity and its vector cells.
   - **Repeated validation of every datom in `:seon.db/transaction-report` across internal `defn-` helpers (`29,884,776` calls to `seon.db/transaction-report-datom?`, 3.34 s self)**: Because `validate-write-report`, `written-declaration-identities`, `write-report-error`, `report-identity-attributes`, `retention-report-check`, `render-declaration-touched?`, `write-owned-values-error`, `write-digest-error`, `logical-datoms`, `report-arity-comparison`, `arity-verdict`, `hold-committed-projection!`, `charge-writer!`, `transact-call`, and `transact!` all declare `:seon.db/transaction-report` on their `report` parameter/return, Malli's contract checker walks `:tx-data` and `:datahike/attempted-tx-data` and invokes the armed predicate `seon.db/transaction-report-datom?` **15–17 times per datom per transaction** (~30M calls across 1,425 transactions!).
3. **Evidence**:
   - `MEASURED` (cumulative profile & `cells-of` on `default`):
     - `seon.db/write-owned-values-error`: **1,556 calls, 48,926 ms total, 22,718 ms self** (`p50-us` 2,097, `mean-us` 31,443, `p99-us` 536,870, `max-ms` 620).
     - `seon.db/write-entity-value`: **781,706 calls, 21,827 ms total, 6,974 ms self** (`mean-us` 27, `p50-us` 16).
     - `seon.db/write-attribute-plan`: **15,009,053 calls, 7,777 ms total, 3,276 ms self**.
     - `seon.db/write-entity-error`: **613,926 calls, 3,986 ms total, 1,873 ms self**.
     - `seon.db/transaction-report-datom?`: **29,884,776 calls, 3,335 ms self**.
     - `seon.db/cell-vectors`: **202,066 calls, 2,036 ms total**; `seon.db/cell-rows`: **208,143 calls, 800 ms total**.
   - `MEASURED` (live read-only probe of `validate-write-report` on a 1-datom function doc change in `default`):
     ```clojure
     (let [conn (seon.cluster.boot/connection "default")
           db (seon.db/db conn)
           proj (seon.db/carried-projection db)
           fn-eid (:e (first (datahike.api/datoms db :aevt :seon.fn/sym)))
           fn-report (datahike.core/with db [[:db/add fn-eid :seon.fn/doc "probe doc"]])]
       (seon.profile/measure (fn [] (#'seon.db/validate-write-report proj fn-report))))
     ;; => {:ms 5.06, :calls 1306, :alloc-bytes 5265376,
     ;;     :by-self [[seon.db/write-owned-values-error 1 3ms]
     ;;               [seon.schema/projection-cache-value 262 0ms]
     ;;               [seon.db/database-value? 159 0ms]
     ;;               [seon.db/write-attribute-plan 157 0ms]
     ;;               [seon.schema.datahike/vector-shaped-attr-in? 102 0ms]
     ;;               [seon.db/transaction-report-datom? 29 0ms]]}
     ```
     Validating a **1-datom `:seon.fn/doc` change** takes **5.06 ms, 1,306 armed calls, and 5.27 MB of allocation**, with `write-owned-values-error` taking 3 ms self time and calling `write-attribute-plan` 157 times (because `root-identities` reconstructs the function entity and all its vector cells on both `:before` and `:after`!).
   - `INFERRED` (`src/seon/db.clj:4451-4457, 4550-4555, 4655-4660`):
     ```clojure
     ;; src/seon/db.clj:4451-4457
     components (into #{} (keep (fn [[a properties]]
                                  (when (:db/isComponent properties) a)))
                      (merge (dbi/-schema before) (dbi/-schema after)))
     targets (into {} (map (fn [a]
                             [a (some-> (mr/schema (:seon.schema.projection/registry projection) a)
                                        m/properties :seon.db/component-schema)]))
                   components)
     ```
4. **Smallest fix by the owner**:
   - Cache `[components targets]` per `projection` via `seon.schema/projection-cache-value` (merging only when `(not (identical? (dbi/-schema before) (dbi/-schema after)))`).
   - Cache the attribute plan map `{attribute plan}` in `projection` via `seon.schema/projection-cache-value` so `write-entity-value` looks up `(get plans attribute)` in a plain map instead of invoking the armed `write-attribute-plan` function 15,009,053 times.
   - In `owners-of` (`src/seon/db.clj:4527-4557`), if `entity-id` is a vector cell (has `:seon.db.vector/position`), seek only `[nil :seon.db.vector/cells entity-id]`; if `entity-id` already carries a top-level identity attribute (which cannot be an anonymous component), skip the 25 component-attribute AVET seeks.
   - In `root-identities` (`src/seon/db.clj:4655-4660`), only read `identity-attrs` from `before` when `(select-keys value identity-attrs)` is empty or when `(:tx-data report)` retracted an `identity-attrs` datom on `root`, instead of calling `(entity-map :before before root)`.
5. **Expected gain**:
   - Cuts `write-owned-values-error` (48.9 s), `write-entity-value` (21.8 s), and `write-attribute-plan` (7.8 s) by **60–75% (~45–55 s cumulative gain)**, and cuts 1-function write validation from **5.06 ms / 5.27 MB** to **~1.5 ms / ~1.5 MB**.
6. **End-to-end probe**:
   - Run the `seon.profile/measure` probe on `(#'seon.db/validate-write-report proj fn-report)` above: `write-attribute-plan` calls drop from `157` to `0` (cached on `projection`), `write-entity-value` calls drop by half (no `:before` full reconstruction), and `:ms` drops from `5.06 ms` to `< 2.0 ms`.

---

### Finding 3: `seon.db/write-digest-error`, `seon.db/namespace-digests-tx`, and `seon.db/definition-digest-of` reconstruct and re-digest unchanged namespace members and call 1-arity `(seon.program/shape ...)` (which reloads `packaged-population` on every digest)

1. **Function**: `seon.program/used-resolver-context`, `seon.db/definition-digest-of`, `seon.db/namespace-digests-tx`, `seon.db/write-digest-error`, `seon.db/declaration-value`.
2. **What repeats or is super-linear and on which unchanged input**:
   - **Defect A — `seon.program/used-resolver-context` calls 1-arity `shape` instead of 2-arity `(shape entity-shapes ...)` (`src/seon/program.cljc:533`)**:
     In `seon.db/definition-digest-of` (`src/seon/db.clj:5529-5534`, **131,809 calls, 11.0 s total**), line 5532 already retrieves `shapes` from `projection` (`(schema/projection-cache-value projection ::program-shapes #(@program-shapes-in projection))`) and passes it to 2-arity `canonical-row`. It then calls `(@program-definition-digest ... resolver-context)` → `used-resolver-context` (`src/seon/program.cljc:520-540`). Line 533 of `used-resolver-context` runs:
     ```clojure
     source (some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))
     ```
     Calling 1-arg `shape` invokes `(shapes)` → `(authored-shapes)` (`src/seon/program.cljc:396-405`), which calls `(schema.edn/packaged-forms)` → `seon.schema.edn/packaged-population` (**167,611 calls, 5.27 s total**) → `seon.schema.edn/resource-loader` (**167,853 calls, 3.26 s self**) and then hashes the **entire 3,660-entry `forms` map** in `(seon.cache/lookup ::authored-shapes forms ...)` on **every single function/test digest calculation**!
   - **Defect B — Whole-namespace reconstruction in `namespace-digests-tx` and duplicate digest verification in `write-digest-error` (`src/seon/db.clj:5576-5648`)**:
     1. Whenever a transaction writes a namespace entity (`:seon.ns/name` with `:seon.program/definition-digest`), `prepared-write` / `digested-call` appends `[:db.fn/call namespace-digests-tx projection namespaces written]` (`src/seon/db.clj:5983-5990`, **329 calls, 3.37 s total**). `namespace-digests-tx` queries **every function and test in those namespaces**, reconstructs every member via `declaration-value` (**143,785 calls, 12.34 s total, 10.05 s self**), and recomputes `definition-digest-of`—even when the namespace's resolver context (`:seon.ns/requires`, `:seon.ns/aliases`, `:seon.ns/refers`, `:seon.ns/imports`) did **not** change (e.g. only `:seon.ns/doc` or another namespace attribute was touched, or `reconcile-tx-in` already wrote the namespace's changed members in `written`!).
     2. Then, milliseconds later in the same transaction, `validate-write-report` calls `write-digest-error` (`src/seon/db.clj:5599-5648`, **1,555 calls, 22.80 s total**), which calls `logical-datoms` (2.97 s), finds every touched function/test entity, reconstructs each entity from EAVT + vector cells via `declaration-value` **again**, and runs `definition-digest-of` **again**!
3. **Evidence**:
   - `MEASURED` (cumulative profile & `cells-of` on `default`):
     - `seon.db/write-digest-error`: **1,555 calls, 22,804 ms total, 1,303 ms self** (`p50-us` 131, `mean-us` 14,664, `p99-us` 268,435, `max-ms` 253; 190 calls in buckets 26–28 take 67–253 ms each).
     - `seon.db/declaration-value`: **143,785 calls, 12,339 ms total, 10,052 ms self** (`mean-us` 85, `p50-us` 131).
     - `seon.db/definition-digest-of`: **131,809 calls, 10,958 ms total, 570 ms self** (`mean-us` 83).
     - `seon.db/namespace-digests-tx`: **329 calls, 3,371 ms total, 584 ms self**.
     - `seon.schema.edn/packaged-population`: **167,611 calls, 5,274 ms total, 1,196 ms self**; `seon.schema.edn/resource-loader`: **167,853 calls, 3,261 ms self**.
   - `MEASURED` (live read-only probe of `namespace-digests-tx` on `#{'seon.db}`):
     ```clojure
     (let [conn (seon.cluster.boot/connection "default")
           db (seon.db/db conn)
           proj (seon.db/carried-projection db)]
       (seon.profile/measure (fn [] (#'seon.db/namespace-digests-tx db proj #{'seon.db} #{}))))
     ;; => {:ms 58.75, :calls 19532, :alloc-bytes 75753648,
     ;;     :by-self [[seon.db/declaration-value 296 25ms]
     ;;               [seon.id/structural-digest 590 9ms]
     ;;               [seon.schema.edn/resource-loader 295 5ms]
     ;;               [seon.schema.edn/packaged-population 295 2ms]
     ;;               [seon.program/used-resolver-context 295 2ms]
     ;;               [seon.db/declared-collections 2518 1ms]
     ;;               [seon.schema.datahike/logical-collection 2030 1ms]]}
     ```
     Re-deriving digests for a single namespace (`seon.db`, 295 declarations) takes **58.75 ms, 19,532 armed calls, and 75.8 MB of allocation**, and calls `resource-loader` and `packaged-population` **295 times** (once per function)!
   - `INFERRED` (`src/seon/program.cljc:533` and `src/seon/db.clj:5529-5534`):
     ```clojure
     ;; src/seon/program.cljc:533
     source (some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))
     ```
4. **Smallest fix by the owner**:
   - In `seon.program/definition-digest` and `seon.program/used-resolver-context` (`src/seon/program.cljc:520-561`), accept `entity-shapes` (or read `(or (:seon.fn/source entity) (:seon.test/source entity))` / pass `entity-shapes` to `(shape entity-shapes ...)` from `seon.db/definition-digest-of`, which already holds `shapes` on line 5532!).
   - In `seon.db/namespace-digests-tx` (`src/seon/db.clj:5576-5597`), skip re-digesting a namespace's members when the namespace's `stored-namespace-context` (`:requires`, `:aliases`, `:refers`, `:imports`) is unchanged from `:db-before`, and filter out `written` entities **before** calling `(declaration-value ...)` on line 5594 (currently line 5594 calls `declaration-value` and `definition-digest-of` on **every** entity in the namespace and only checks `(not (written ...))` on line 5596 **after** reconstructing and digesting it!).
5. **Expected gain**:
   - Checking `(not (written ...))` before `declaration-value` in `namespace-digests-tx` and passing `entity-shapes` into `used-resolver-context` eliminates ~130k calls to `resource-loader` / `packaged-population` and ~70k redundant `declaration-value` + `definition-digest-of` calls, saving **~18–22 s cumulative time** and cutting `namespace-digests-tx` on `seon.db` from **58.75 ms / 75.8 MB** to **< 1 ms** when all members are in `written` or resolver context is unchanged.
6. **End-to-end probe**:
   - Run `(seon.profile/measure (fn [] (#'seon.db/namespace-digests-tx db proj #{'seon.db} #{})))`: `seon.schema.edn/resource-loader` and `seon.schema.edn/packaged-population` calls drop from `295` to `0`, and when `written` contains the namespace's declarations, `declaration-value` calls drop from `296` to `1` (the namespace row itself).

---

### Finding 4: `seon.db/with-declarations` (319,857 calls, 154.8 s self / 383.9 s total) wraps unarmed Datahike reads while its 229.1 s child time is dominated by `pull-logical` and `vector-query-call`

1. **Function**: `seon.db/with-declarations`, `seon.db/pull-logical`, `seon.db/vector-query-call`.
2. **What repeats or is super-linear and on which unchanged input**:
   - **Why `with-declarations` has 154.8 s `self-ms` (`MEASURED` + `INFERRED`)**:
     In `src/seon/db.clj:2015-2040`, `with-declarations` is the outermost armed wrapper around the `continue` callback passed by `seon.db/q` (52,473 calls, 121.8 s), `seon.db/datoms` (128,873 calls, 156.7 s), `seon.db/pull-call` (138,511 calls, 110.2 s), and `seon.db/pull-many` (9,431 calls, 91.8 s). A direct micro-benchmark of 200 calls to `(#'seon.db/with-declarations db 'seon.db/pull (fn [_] :ok))` takes **0.35 ms total (1.75 µs/call)**. Why then does `with-declarations` accumulate **154.8 s `self-ms`** across 319,857 calls (~484 µs/call)? Because `continue` invokes **unarmed Datahike functions** (`datahike.api/q`, `datahike.api/pull`, `datahike.api/pull-many`, `datahike.api/datoms`, and lazy sequence realization of B-tree iterators inside `decode-datoms`). Since `datahike.*` functions are not armed by `seon.profile`, `seon.profile/timed` attributes **100% of raw Datahike query, pull, and index-iteration time to `with-declarations`'s `self-ms`**!
   - **What genuinely repeats inside `with-declarations` (and its 229.1 s of armed child calls)**:
     1. **3-arity self-call through the armed Var (`src/seon/db.clj:2032-2034`)**: `([database operation continue] (with-declarations database operation nil continue))` invokes the armed Var a second time whenever the 3-arity is called, and both arities specify `[:=> [:cat :map] :seon.schema/value]` on `continue` and `:seon.schema/value` on the return value (validating the entire query/pull/datoms output collection twice: once returning from `with-declarations` and once returning from `q`/`pull`/`datoms`).
     2. **In `seon.db/pull-logical` (138,511 calls, 89.3 s self / 104.5 s total) and `seon.db/pull-many` (9,431 calls, 91.8 s total)**: When `pull-many` is called (such as in `reconcile-tx-in` on thousands of entities), `pull-many` loops over every entity calling `pull-logical` (`src/seon/db.clj:3385-3395`), and `pull-logical` re-checks `vector-attributes-in` and reconstructs vector cells per entity rather than hoisting pattern analysis once per `pull-many` call.
     3. **In `seon.db/vector-query-call` (52,473 calls, 86.8 s self / 117.3 s total)**: Every `seon.db/q` call invokes `encode-query-request` (20.7 s), `may-read-vectors?` (1.4 s), `query-find-attributes` (5.2 s), and `decode-query-result` (5.7 s), re-parsing the Datalog query and checking vector attributes on every call instead of caching the lowered query plan + find-attribute decoders by `[query-form projection]` in `seon.schema/projection-cache-value`.
3. **Evidence**:
   - `MEASURED` (`cells-of` and live probes on `default`):
     - `seon.db/with-declarations`: **319,857 calls, 383,895 ms total, 154,801 ms self** (`p50-us` 65, `mean-us` 1,200, `p99-us` 16,777, `max-ms` 4,288).
     - `seon.db/pull-logical`: **138,511 calls, 104,535 ms total, 89,303 ms self** (`p50-us` 65, `mean-us` 754).
     - `seon.db/vector-query-call`: **52,473 calls, 117,307 ms total, 86,817 ms self** (`p50-us` 131, `mean-us` 2,235).
     - `seon.db/encode-query-request`: **52,473 calls, 20,662 ms total**; `seon.db/decode-query-result`: **52,473 calls, 5,705 ms total**; `seon.db/query-find-attributes`: **52,473 calls, 5,228 ms total**.
     - Direct measurement of `with-declarations` overhead alone:
       ```clojure
       (seon.profile/measure
         (fn [] (dotimes [_ 200] (#'seon.db/with-declarations db 'seon.db/pull (fn [_] :ok)))))
       ;; => {:seon.profile/ms 0.35, :seon.profile/calls 1000, :seon.profile/alloc-bytes 419664}
       ```
4. **Smallest fix by the owner**:
   - Cache the static query preparation (`may-read-vectors?`, `lowered-query-request` structure, `query-find-attributes`, and whether any `:find` attribute is EDN-encoded) per `query` form on `projection` via `seon.schema/projection-cache-value` in `seon.db`. When no `:find` attribute is EDN-encoded, `decode-query-result` is `identity` instead of walking every result tuple.
   - Combine with Finding 1 (avoiding whole-population `db/pull-many '[*]'` inside `reconcile-tx-in` for digest-unchanged entities).
5. **Expected gain**:
   - Caching query plan/find metadata per `[projection query-form]` and skipping `decode-query-result` when no `:find` attribute is EDN-encoded saves **~20–25 s** across 52,473 `seon.db/q` calls; skipping redundant `pull-many '[*]'` in `reconcile-tx-in` saves another **~30–40 s** in `pull-many` / `pull-logical`.
6. **End-to-end probe**:
   - Measure 100 calls to `(seon.db/q '[:find [?e ...] :where [?e :seon.fn/sym _]] db)` under `seon.profile/measure`: calls to `query-find-attributes`, `may-read-vectors?`, and `decode-attribute-maps` drop to 0 on cache hits.

---

### Finding 5: `seon.db/declaration-content-key` (2,639 calls, 7.7 s self) and `seon.db/content-projection` (2,201 calls, 28.5 s total) thrash `value-projection-cache` (size 4) and `projection-cache` (size 24)

1. **Function**: `seon.db/declaration-content-key`, `seon.db/content-projection`, `seon.db/speculative-projection`, `seon.db/value-projection-cache`, `seon.db/projection-cache`.
2. **What repeats or is super-linear and on which unchanged input**:
   - `seon.db/declaration-content-key` (`src/seon/db.clj:1529-1548`) scans **all 34,841 datoms** across the 5 `seon.schema/projection-attributes` (`:seon.schema.admission/source`, `:seon.schema/key`, `:seon.schema/form`, `:seon.fn/sym`, `:seon.fn/spec`) in `:aevt` order and allocates two 34,841-element vectors per call (`datoms` and `(into [] (map :tx) datoms)`, **1.33 MB allocated and ~1.2–3.0 ms per call**).
   - Although there were only 1,425 committed transactions since boot (and declaration schemas/specs change in only a fraction of them), `declaration-content-key` was called **2,639 times (7.72 s self)** and `content-projection` **2,201 times (28.52 s total)**!
   - Why does it miss so often on unchanged or repeated declaration content?
     1. `value-projection-cache` (`src/seon/db.clj:1515-1518`) is a `clojure.core.cache.wrapped/lru-cache-factory` with `:threshold 4` (**only 4 entries**!), yet every `speculative-projection` call (`src/seon/db.clj:1630-1636`) inserts **two keys** into `value-projection-cache`: `[builder value-key]` AND `[::declaration-content builder (declaration-content-key database)]`! That means `value-projection-cache` holds at most **2 speculative projections** before evicting! During a transaction with multiple `:db.fn/call` steps or concurrent branches/evaluators, `value-projection-cache` thrashes continuously, re-running `declaration-content-key` (34,841 datoms) on every miss.
     2. `projection-cache` (`src/seon/db.clj:1508-1513`) has `:threshold 24`, but stores **4 different key shapes** in the same 24-slot LRU map: `[::builder commit]`, `revision-key`, `[::commit commit-id builder]`, and `[::declaration-content builder content-key]`. A single committed projection occupies 3–4 slots, so `projection-cache` holds only **~6–8 projections** across all branches, commits, and builders before evicting!
     3. Worse, `declaration-content-key` builds `[attribute datoms (into [] (map :tx) datoms)]` containing **34,841 `Datom` objects + 34,841 boxed longs** and stores that massive vector **as the map key** in `projection-cache` and `value-projection-cache`, meaning every cache lookup/insertion hashes and compares 69,682 elements across the 5 tuples! UsingDatahike's `:datahike.cache/attribute-revisions` for the 5 `schema/projection-attributes` (when native `:cache-context` is present) or hashing `declaration-content-key` to a `seon.id/digest` (or only re-reading the subset of the 5 attributes whose `:datahike.cache/attribute-revisions` changed) avoids storing and hashing 70k-element vectors as map keys.
3. **Evidence**:
   - `MEASURED` (`cells-of` and live probe on `default`):
     - `seon.db/declaration-content-key`: **2,639 calls, 7,724 ms total, 7,722 ms self** (`p50-us` 2,097, `mean-us` 2,927, `p99-us` 67,108, `max-ms` 149).
     - `seon.db/content-projection`: **2,201 calls, 28,516 ms total, 1,826 ms self** (`p50-us` 16,777, `mean-us` 12,956, `max-ms` 1,966).
     - Live measurement of one `declaration-content-key` call on `default`:
       ```clojure
       (let [db (seon.db/db (seon.cluster.boot/connection "default"))]
         {:proj-datom-count (reduce + 0 (map #(count (datahike.api/datoms db :aevt %)) seon.schema/projection-attributes))
          :measure (dissoc (seon.profile/measure (fn [] (#'seon.db/declaration-content-key db))) :seon.profile/value)})
       ;; => {:proj-datom-count 34841,
       ;;     :measure {:seon.profile/ms 1.19, :seon.profile/alloc-bytes 1331048, :seon.profile/calls 5}}
       ```
   - `INFERRED` (`src/seon/db.clj:1508-1518, 1630-1636`):
     ```clojure
     ;; src/seon/db.clj:1508-1518
     (defonce ^:private projection-cache
       (cache/lru-cache-factory {} :threshold 24))

     (defonce ^:private value-projection-cache
       (cache/lru-cache-factory {} :threshold 4))
     ```
4. **Smallest fix by the owner**:
   - Increase `value-projection-cache` threshold from `4` to `32` and `projection-cache` threshold from `24` to `96` (or separate `[::builder commit]` into its own small cache so 8-byte builder digests don't evict compiled projections).
   - In `declaration-content-key` (`src/seon/db.clj:1529-1548`), memoize each of the 5 `schema/projection-attributes`' `[attribute datoms txs]` slice by its `:datahike.cache/attribute-revisions` UUID (from `(:cache-context database)` when present), so a speculative write that modifies only `:seon.fn/spec` re-reads only `:seon.fn/spec` instead of all 5 attributes (34,841 datoms).
5. **Expected gain**:
   - Eliminates ~80–90% of cache-thrash misses in `value-projection-cache` and `projection-cache`, saving **~20–25 s** across `content-projection` (28.5 s) and `declaration-content-key` (7.7 s) and avoiding ~3.5 GB of transient vector allocations.
6. **End-to-end probe**:
   - Create 4 speculative `d/with` database values that each touch one `:seon.fn/spec` datom and call `(seon.db/carried-projection db)` twice on each: with `:threshold 4`, the second pass misses `value-projection-cache` and re-invokes `declaration-content-key` 4 times; with `:threshold 32` and per-attribute revision caching, the second pass makes `0` calls to `declaration-content-key`.

---

## Five-Line Summary

1. `seon.db/transact-call`'s 391.9 s `self-ms` (1,396 calls) is the full asynchronous writer wait because `seon.profile/timed`'s `ThreadLocal` `callee-frame` does not subtract writer-thread callees (`validate-write-report` 102.2 s + `digested-call` 62.0 s = 42% Seon checks/tx-fns vs. 227.0 s = 58% Datahike index/commit/queue); similarly, `with-declarations`'s 154.8 s `self-ms` is unarmed Datahike `d/q`, `d/pull`, `d/pull-many`, and `d/datoms`.
2. `cells-of` histograms show `transact-call` is bimodal: 1,181 ordinary recording transactions have `p50` = **33.5 ms** (~4.2 ms Seon validation + ~28 ms Datahike 6-index flush/LZ4/file commit), while **190 reconciliation/adoption transactions** take **1.07–4.24 s each (~351 s = 89% of total write time)**.
3. Inside the writer, `seon.fn/reconcile-tx-in` (49.9 s) pulls `[*]` and runs `normalized-index-entity` (25.6 s) over the **entire desired entity population** even when `:seon.program/definition-digest` is unchanged, calling `(seon.db/stored-collection database ...)` → `(seon.db/carried-projection database)` **2,540,820 times** (90.5% of all 2.8M `carried-projection` calls, 64.8 s).
4. On every transaction, `seon.db/write-owned-values-error` (48.9 s) and `seon.db/write-report-error` (85.2 s) rescan the 2,404-attribute schema map, run ~25 AVET component-owner seeks per seed on both `before` and `after`, reconstruct `:before` roots via `write-entity-value` (781k calls, 21.8 s; `write-attribute-plan` 15.0M calls, 7.8 s), and validate `:seon.db/transaction-report` datoms 15+ times per transaction (`transaction-report-datom?` 29.9M calls, 3.3 s).
5. `seon.db/write-digest-error` (22.8 s) and `seon.db/namespace-digests-tx` (3.4 s) reconstruct unchanged namespace members via `declaration-value` (143,785 calls, 12.3 s) and call 1-arity `seon.program/shape` inside `used-resolver-context`, which reloads `seon.schema.edn/packaged-population` (167,611 calls, 5.3 s) on every digest, while `value-projection-cache` (size 4) and `projection-cache` (size 24) thrash `declaration-content-key` (2,639 scans of 34,841 datoms, 7.7 s).
