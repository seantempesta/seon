---
title: "Argon Performance Hunts B: Live Heap Floor, merge-main! Cost, and prepare-merge! Cost"
type: research
date: 2026-10-15
status: study only (lane argon-hunts-b / agent hunt-1015b); no source edits
basis: Seon a76a9c141 (default JVM before restart) & data/heap/java_pid84529.hprof; persistent-sorted-set 0.4.137
---

# Argon Performance Hunts B (2026-10-15)

Every number below comes from a binary heap-dump scan (`data/heap/java_pid84529.hprof`), a read-only `eval_clj` probe on `default` (before its restart onto `68ceef4ad`), or a source file read in this workspace, with the exact form or path cited. Any inference not directly measured is explicitly labelled **HYPOTHESIS**.

---

## Hunt 1: The Live Heap Floor and `data/heap/java_pid84529.hprof`

### 1.1 Exact Heap-Dump Inventory (`data/heap/java_pid84529.hprof`, 4,365,824,619 bytes)

We parsed all `2,879` `HEAP_DUMP_SEGMENT` records of `data/heap/java_pid84529.hprof` out-of-process using custom streaming Java analyzers (`HprofScan`, `HprofAnalyze2`, `HprofDeep`, `HprofDeep2`, `HprofConnections`, `HprofDumpConfigs`, `HprofSubtrees`).

**Command run:**
```bash
java -Xmx2g -cp /Users/sean/.gemini/antigravity-cli/brain/824ee7cf-17f6-4e93-b037-240e6a8d0a3d/scratch/hunt-1015b HprofAnalyze2 data/heap/java_pid84529.hprof | grep -E "Datom|DB|Connection|Storage|PersistentSortedSet|Branch|Leaf|PSMW|Ctx|BoundedLocalCache" | sort -nr
```
and `HprofScan` top-instances summary:

| Class | Instance Count | Shallow Bytes (HPROF) | Notes |
|---|---:|---:|---|
| `clojure.lang.PersistentVector` | `5,883,304` | `329,465,024` (329.5 MB) | `884,005` in `Datom.v`, `377,159` in Malli schemas |
| `datahike.datom.Datom` | `4,696,981` | `244,243,012` (244.2 MB) | `4,468,346` (95.1%) in `Leaf._keys` arrays |
| `java.lang.String` | `3,413,101` | `102,393,030` (102.4 MB) | Plus `byte[]` backing arrays |
| `clojure.lang.Symbol` | `2,685,321` | `139,636,692` (139.6 MB) | |
| `java.util.concurrent.atomic.AtomicReference` | `1,962,859` | `47,108,616` (47.1 MB) | |
| `clojure.lang.PersistentHashMap$BitmapIndexedNode` | `1,529,682` | `55,068,552` (55.1 MB) | |
| `java.lang.Long` | `1,523,797` | `36,571,128` (36.6 MB) | |
| `clojure.lang.PersistentVector$Node` | `1,335,670` | `42,741,440` (42.7 MB) | Each holds an `Object[32]` (~144B) |
| `clojure.lang.LazySeq` | `1,007,230` | `56,404,880` (56.4 MB) | `969,743` chained via `Cons._more` |
| `clojure.lang.PersistentArrayMap` | `995,499` | `39,819,960` (39.8 MB) | |
| `clojure.lang.Cons` | `974,298` | `46,766,304` (46.8 MB) | `938,045` have `Cons._first` -> `Datom` |
| `clojure.lang.Delay` | `471,311` | `22,622,928` (22.6 MB) | `462,462` (98.1%) in Malli schema fields |
| `clojure.lang.Atom` | `397,710` | `19,090,080` (19.1 MB) | `395,976` (99.6%) in Malli schema `.cache` |
| `java.util.concurrent.locks.ReentrantLock` + `NonfairSync` | `329,389` each | `22,398,452` (22.4 MB) | Konserve / storage locks |
| `malli.core$_schema_schema$reify$reify__64736` | `181,748` | `20,355,776` (20.4 MB) | Compiled Malli `:schema` wrappers |
| `sci.impl.utils.StackFrame` | `105,509` | `10,972,936` (11.0 MB) | |
| `java.lang.ref.WeakReference` | `86,844` | `4,168,512` (4.2 MB) | `53,564` -> `Leaf`, `1,548` -> `Branch` |
| `com.github.benmanes.caffeine.cache.PSMW` | `85,122` | `5,107,320` (5.1 MB) | Caffeine cache entries (`50,388` Datahike nodes + `34,734` `seon.cache`) |
| `org.replikativ.persistent_sorted_set.Leaf` | `61,409` | `2,701,996` (2.7 MB) | Hold `4,468,346` distinct `Datom`s in `_keys` |
| `org.replikativ.persistent_sorted_set.Slot` | `18,106` | `1,013,936` (1.0 MB) | `Branch$BufState` diff buffers |
| `org.replikativ.persistent_sorted_set.Branch` | `4,590` | `330,480` (0.3 MB) | Plus `4,590` `NodeState` + `4,590` `BufState` |
| `org.replikativ.persistent_sorted_set.PersistentSortedSet` | `1,473` | `117,840` (0.1 MB) | |
| `sci.impl.opts.Ctx` | `1,316` | `210,560` (0.2 MB) | SCI contexts |
| `datahike.db.DB` (+ `SinceDB`, `HistoricalDB`) | `33` (+ `1`, `1`) | `6,440` | Only 33 live `DB` records in the dump |
| `datahike.connector.Connection` | `14` | `336` | |
| `datahike.index.persistent_set.CachedStorage` | `13` | `1,456` | |

### 1.2 Which Roots Retain the Datoms and Object[] Arrays?

**Command run (`HprofDeep.java`):**
```bash
javac HprofDeep.java && java -Xmx2g HprofDeep data/heap/java_pid84529.hprof
```
**Exact output:**
```text
Datoms: 4696981, Leaves: 61409, Branches: 4590, PSS: 1473, DBs: 33, PSMWs: 85122
PSMW breakdown: leaves=46517, branches=3871 (total node weight=1610471926), other=34734 (total other weight=493686976)
Distinct Datoms in Leaf._keys arrays: 4468346 (slots: 5126882)
Distinct Datoms in PersistentVector arrays: 68201 (slots: 68442)
Distinct Datoms in Other Object[] arrays: 171653 (slots: 400402)
Datoms ONLY in Leaf._keys arrays: 4456920
Instance field refs to Datom:
      938045  clojure/lang/Cons._first
        2641  clojure/lang/PersistentTreeMap$BlackBranchVal.key
        1731  clojure/lang/PersistentTreeMap$BlackBranchVal.val
        1635  clojure/lang/PersistentTreeMap$RedVal.key
        1582  clojure/lang/PersistentTreeMap$BlackVal.key
        1547  clojure/lang/PersistentTreeMap$RedVal.val
         975  clojure/lang/PersistentTreeMap$BlackVal.val
         232  clojure/lang/PersistentTreeMap$RedBranchVal.key
         143  clojure/lang/PersistentTreeMap$RedBranchVal.val
           2  org/replikativ/persistent_sorted_set/Seq._keyTo
```

**Command run (`HprofDeep2.java` & `HprofDumpConfigs.java`):**
```bash
javac HprofDeep2.java && java -Xmx2g HprofDeep2 data/heap/java_pid84529.hprof
javac HprofDumpConfigs.java && java -Xmx2g HprofDumpConfigs data/heap/java_pid84529.hprof
```
**Exact output (the 5 weighted `BoundedLocalCache` instances in `java_pid84529.hprof`):**
1. `Cache 481116822832` (`SSSMW`, Datahike `node-cache` for file store `/Users/sean/src/seon/data/store`, `:id #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"`): `maximum=512000000`, `weightedSize=511996202` (512.0 MB), shared by 3 `CachedStorage` instances.
2. `Cache 482206742648` (`SSSMW`, Datahike `node-cache` for `:datahike/overlay` `:overlay #uuid "0a1537e2-a165-4fb8-9683-24527316b76e"`): `maximum=512000000`, `weightedSize=511988122` (512.0 MB), shared by 2 `CachedStorage` instances.
3. `Cache 482276177352` (`SSSMW`, Datahike `node-cache` for `:datahike/overlay` `:overlay #uuid "d47dba7d-6264-4486-a226-524a08daf4c7"`): `maximum=512000000`, `weightedSize=344138130` (344.1 MB), shared by 4 `CachedStorage` instances.
4. `Cache 482316138120` (`SSSMW`, Datahike `node-cache` for `:datahike/overlay` `:overlay #uuid "5ad58773-7e3e-4587-ace8-9c0ccdbcbb59"`): `maximum=512000000`, `weightedSize=242349472` (242.3 MB), shared by 4 `CachedStorage` instances.
5. `Cache 481080139288` (`SSLMW`, `seon.cache` global cache): `maximum=512000000`, `weightedSize=493686976` (493.7 MB), `34,734` `PSMW` entries.

**Three measured retention roots in `java_pid84529.hprof` and live `default`:**

#### Root 1A (OOM Heap Dump): Unshared 512 MB Datahike `node-cache` per `:datahike/overlay` Store + Unreleased Overlay Branch Connections
- In `java_pid84529.hprof`, **`4,468,346` of the `4,696,981` `Datom`s (95.1%)** live inside `Leaf._keys` arrays (`61,409` `Leaf` instances: `46,517` directly in Caffeine `PSMW.value` across the **4 separate Datahike `node-cache`s** totaling `1,610,471,926` weighted bytes = **1.61 GB**, plus `14,895` `Leaf`s reachable via `WeakReference` / `Branch` arrays / `PersistentSortedSet._root`). Another `938,045` `Datom` references sit in a realized `LazySeq` (`Cons._first` / `LazySeq.s`) in the thread that triggered OOM.
- **Why overlays got separate 512 MB caches (`src/seon/cluster/store.clj:691-704`, `reference-code/datahike/src/datahike/store.cljc:76-84`, `reference-code/datahike/src/datahike/connections.cljc:69-75`)**:
  `seon.cluster.store/open-overlay!` builds a config with `:store {:backend :datahike/overlay :id store-id :overlay (UUID/randomUUID) ...}` and inherits `:store-cache-bytes 512000000` from the file store. `datahike.store/physical-store-key` (`store.cljc:76-84`) selects `[:backend :path :id :overlay :scope :host :port :config]`. Because `:overlay` is a fresh random UUID per overlay, `datahike.connections/reserve-connection-opening!` (`connections.cljc:69-75`) does not match the backing file store's `:physical-store-key` and allocates a **new 512 MB Caffeine `node-cache` per overlay** (each deserializing duplicate `Leaf` and `Datom` objects from the same backing file store).
- **Why 3 overlays and 10 overlay `CachedStorage`s were still alive (`src/seon/cluster/store.clj:659-665`)**:
  `seon.cluster.store/release-store!` for `:seon.store/overlay?` only releases `:seon.store/connection-object` (`:db` branch) and calls `d/delete-database` (which only checks/removes `[store-id :db]`). Any additional branch connections opened on the overlay (`:CachedStorage` instances with non-`:db` branch names seen in `HprofDumpConfigs`) remain in `datahike.connections/*connections*`, retaining their `CachedStorage` and the overlay's 512 MB `node-cache` forever.

#### Root 1B (Both Boot Floor and OOM): `datahike.index.node-cache/node-bytes` Under-Weighs Cached `Leaf`, `Branch`, and `Datom` Objects by ~2.5x–3x
- **Live probe run on `default` before restart (step 206 & step 247):**
  ```clojure
  (let [conns @datahike.connections/*connections*
        qrc @@#'datahike.query/query-result-cache]
    {:open-connections
     (mapv (fn [[k v]]
             {:key k :count (:count v) :threshold (:threshold v)
              :node-cache-id (when-let [nc (:node-cache v)] (System/identityHashCode nc))
              :node-cache-stats (when-let [nc (:node-cache v)] (datahike.index.interface/node-cache-stats nc))})
           conns)
     :qrc-generations-count (count (:generations qrc))})
  ```
  **Returned:** `6` open connections (`4` on file store `#uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"` sharing a single `node-cache` `id=662276571`, plus `2` empty memory stores), with:
  `{:nodes 16054, :max-bytes 512000000, :weighted-bytes 511997702, :hits 2821837, :misses 146564, :evictions 47090}`.
- **Why 512 MB of `weighted-bytes` in `datahike.index.node-cache` actually retains ~1.2–1.5 GB of JVM heap (`reference-code/datahike/src/datahike/index/node_cache.clj:9-25`)**:
  1. `node-bytes` (`node_cache.clj:18-25`) weighs a `Leaf` as `(+ 48 (* 32 n) (reduce + (map #(value-bytes (.-v ^Datom %)) keys)))`:
     - Each `Datom` shallow instance is `48` bytes in HPROF (`32` bytes compressed oops) + `8` bytes array slot = `40` bytes, plus `value-bytes`:
     - For `String` values (`:seon.fn/source`, `:seon.fn/doc`, `:seon.program/definition-digest`), `value-bytes` calls `(alength (.getBytes ^String v))` — which both **allocates a temporary UTF-8 `byte[]` on every cache `put!`** and ignores the `24`-byte `String` header + `16`-byte `byte[]` header + 8-byte alignment (`+40` to `+48` bytes per String).
     - For `PersistentVector` tuple values in `Datom.v` (`884,005` instances in `java_pid84529.hprof`, e.g., `:seon.fn/call-arities` `[sym arity]` and `:seon.fn/form-span` `[start end]`), `value-bytes` returns `(+ 24 (* 8 (count v))) = 40` bytes. In reality, each 2-element `PersistentVector` on the JVM is `PersistentVector` (`24B`) + `Node` (`24B`) + `AtomicReference` (`16B`) + `Object[32]` (`144B`) + `Symbol` (`48B` + `String`s) + `Long` (`24B`) = **>208 bytes** (**5.2x larger** than `40` bytes).
  2. For a `Branch`, `node-bytes` only weighs `keys` and `addresses` (`~112 + 48*n` bytes), completely ignoring:
     - `Branch$NodeState` (`24B`) + `children` (`WeakReference[128]` or `SoftReference[128]` = `528B` + `128 * 40B` = `5.6 KB` per `Branch`; `53,564` `WeakReference`s to `Leaf` and `1,548` to `Branch` in HPROF), and
     - `Branch$BufState` (`24B`) + `Slot[128]` (`528B`) + `18,106` `Slot` instances (`56B` each) + their `PersistentTreeMap`s (`139,579` `BlackBranchVal` + `66,271` `BlackVal` + `48,118` `RedVal` + `43,186` `RedBranchVal` = `297,154` tree-map nodes in HPROF).

#### Root 1C (Both Boot Floor and OOM): Unweighed Compiled Malli Schemas and SCI Contexts in `seon.cache` (`src/seon/cache.clj`)
- **Live probe run on `default` before restart (step 223):**
  ```clojure
  (let [st (seon.cache/status)]
    {:max-bytes (:seon.cache/max-bytes st)
     :weighted-bytes (:seon.cache/weighted-bytes st)
     :top-kinds (vec (take 10 (sort-by (comp - :seon.cache/bytes second) (:seon.cache/kinds st))))})
  ```
  **Returned:** `:max-bytes 512000000`, `:weighted-bytes 273209987` (273.2 MB across 89 `:seon.db/projection` misses = `101.7 MB` weighted, 44 `:seon.test.runner/reach-attribute` = `46.4 MB`, 12 `:seon.sci.eval/program-context` = `21.2 MB`, 7 `:seon.test.runner/reach-index-by-revision` = `15.3 MB`, `:seon.contracts-compile-test/schema-resources` = `11.2 MB`, `:seon.schema/projection` = `10.9 MB`).
- **Command run (`HprofSubtrees.java`, `task-228.log`):**
  In `java_pid84529.hprof`, `malli.core` compiled schema `reify` instances (`181,748` `_schema_schema`, `50,582` `_simple_schema`, `31,446` `_map_schema`, `27,247` `__EQ__GT`, `27,199` `_sequence_schema`, `19,767` `_val_schema`, `19,485` `_or_schema`, `15,406` `_collection_schema`) retain:
  - **`395,976` of the `397,710` `clojure.lang.Atom` instances (99.6%)** via their `.cache` field,
  - **`462,462` of the `471,311` `clojure.lang.Delay` instances (98.1%)** via their `.form`, `.explicit_children`, and `.default_schema` fields,
  - **`377,159` `PersistentVector` instances** via their `.children` and `.forms` fields.
- **Why `seon.cache` under-weighs `:seon.db/projection` and `:seon.sci.eval/program-context` (`src/seon/cache.clj`)**:
  `seon.cache/retained` does not descend into `reify`/`deftype` fields (`malli.core.Schema` implementations, `sci.impl.opts.Ctx`, `sci.lang.Var`), `Atom`, or `Delay`. Each compiled Malli schema in `:seon.schema.projection/compiled` is weighed as a single 64-byte leaf (`object-bytes`), when its retained subgraph (`Atom` + `Delay`s + parser closures + child `PersistentVector`s) is ~1.5–3 KB per schema (~5,000 schemas per `:seon.db/projection` entry = ~10–15 MB actual heap per entry vs ~1.1 MB weighed).
- **HYPOTHESIS on the 2.5 GB Fresh-Boot Floor:**
  Because live heap is ~2.5 GB immediately after boot (with 0 overlay stores and before agent turns), Root 1A (`:datahike/overlay` leak) is what pushed the 2.5 GB floor over the 4 GB ceiling into OOM during test/overlay runs, whereas the **2.5 GB boot floor itself** is built by boot warming the 512 MB-weighted Datahike `node-cache` (whose true JVM heap is ~1.2–1.5 GB due to Root 1B) plus boot compiling and caching `:seon.db/projection`, `:seon.schema/projection`, and `:seon.sci.eval/program-context` (whose true JVM heap is several hundred MB above `seon.cache`'s reported weight due to Root 1C).

### 1.3 Agreement / Disagreement with `datahike-internals.md` (d10e70f10)

- **Agree:**
  1. Unopened branches in `:branches` (254 in the store) cost **zero heap** (`java_pid84529.hprof` had only `33` `datahike.db.DB` instances and `14` `Connection` instances total).
  2. The file store's Datahike `node-cache` is full at its `512,000,000` weighted bound (`511,997,702` weighted bytes on `default`, `511,996,202` in the HPROF dump), and its weight is `datahike.index.node-cache/node-bytes`'s estimate, not actual retained JVM heap.
- **Disagree / New Evidence from `java_pid84529.hprof`:**
  1. `datahike-internals.md` (§4) hypothesized that Seon-held `datahike.db.DB` values might scale the floor by count. Our HPROF scan refutes that: there were only **`33` `datahike.db.DB` instances** in the entire 4.4 GB OOM heap dump.
  2. `datahike-internals.md` (§3–4) stated there is "One Caffeine cache per physical store, shared by every connection to it." While true for branches of the *same* `:file` store config, **`seon.cluster.store/open-overlay!`** puts `:overlay (UUID/randomUUID)` into `:physical-store-key`, creating a **separate 512 MB `node-cache` per overlay store**, and `release-store!` leaks non-`:db` branch connections opened on overlays (`4` `SSSMW` caches totaling `1.61 GB` weighted / `4.47M` `Datom`s in the OOM dump).

### 1.4 Owners and Smallest Fixes

1. **`datahike.index.node-cache/node-bytes` & `value-bytes` (`reference-code/datahike/src/datahike/index/node_cache.clj:9-25`) and `:seon.config.db/store-cache-bytes`**:
   - In `value-bytes`: replace `(alength (.getBytes s))` with `(+ 40 (alength (.getBytes s)))` (or `(+ 40 (.length s))` to avoid allocating a `byte[]` on every node cache insertion!), and weigh `PersistentVector` as `(+ 192 (* 32 (count v)))` to account for `PersistentVector` + `Node` + `Object[32]` + element headers.
   - In `node-bytes`: add `+ 1024` per `Branch` for `NodeState.children` (`WeakReference[128]`) and `BufState.slots` (`Slot[128]`), plus `Slot` diff sizes.
   - Lower the default `:store-cache-bytes` dial from `512000000` to `128000000` (128 MB) once weighed accurately.
2. **`seon.cluster.store/open-overlay!` and `seon.cluster.store/release-store!` (`src/seon/cluster/store.clj:659-704`)**:
   - Give overlay stores a small `:store-cache-bytes` (e.g. `16000000` = 16 MB) instead of inheriting the backing file store's `512000000`, and in `release-store!` close all connections in `@datahike.connections/*connections*` whose `[:physical-store-key :overlay]` matches the overlay.
3. **`seon.cache` (`src/seon/cache.clj`) / `seon.db/carried-projection` (`src/seon/db.clj`)**:
   - Account for compiled Malli schema weight in `:seon.db/projection` (or share compiled Malli schemas across projections by schema form digest so unchanged schemas are not recompiled and duplicated across `:seon.db/projection` entries).

---

## Hunt 2: `merge-main!` Cost (`10.6 s / 8.9 GB` for 675 moved; `1.3 s / 0.47 GB` for 9 moved)

### 2.1 Exact Commits and Transactions Identified on `default`

**Form run (step 286 & step 288):**
```clojure
(let [conn (seon.cluster.boot/connection "default")
      store (:store @conn)
      head-cid #uuid "6ad047ad-e94a-5503-962e-26b017917ff2"
      commits (loop [cid head-cid acc []]
                (if (or (nil? cid) (> (count acc) 70))
                  acc
                  (let [raw (konserve.core/get store cid nil {:sync? true})
                        meta (:meta raw)]
                    (recur (first (:datahike/parents meta))
                           (conj acc {:cid cid :max-tx (:max-tx raw)
                                      :updated (:datahike/updated-at meta)
                                      :parents (vec (:datahike/parents meta))})))))]
  (filterv #(contains? #{536873072 536873073 536873074 536873087 536873088 536873089} (:max-tx %)) commits))
```
**Returned exact commits for the two `merge-main!` transactions on `:chain-f1f6dc8e6652`:**
- **Call 1 (`tx 536873073`, `#inst "2026-10-15T03:09:57.776Z"`, 675 moved declarations):**
  - `base-1`: `#uuid "6acf6fed-6b55-5297-9f31-af2c339648cb"` (`max-tx 536872008`, the initial fork commit)
  - `head-1`: `#uuid "6acfdaa8-9993-510f-b3a4-bc1fe5746efe"` (`max-tx 536873072`)
  - `main-1`: `#uuid "6ad0440f-8ed2-571f-8f02-9f42a53e701b"` (`max-tx 536872684`)
  - Result commit: `#uuid "6ad0440d-2926-5f3b-ae1e-402f5c1091f2"` (`max-tx 536873073`)
- **Call 2 (`tx 536873088`, `#inst "2026-10-15T03:21:54.316Z"`, 9 moved declarations):**
  - `base-2`: `#uuid "6ad0440f-8ed2-571f-8f02-9f42a53e701b"` (`max-tx 536872684`, advanced by Call 1's `advance-base!`)
  - `head-2`: `#uuid "6ad046d2-95a0-59c1-96db-aba25d5b9203"` (`max-tx 536873087`)
  - `main-2`: `#uuid "6ad046cc-b95a-55be-b3d4-8b5f2242faa4"` (`max-tx 536872709`)
  - Result commit: `#uuid "6ad046d3-ebd6-5491-a1b3-6f980715a2c6"` (`max-tx 536873088`)

### 2.2 Phase-by-Phase Measurement of Call 1 (675 moved) and Call 2 (9 moved)

**Form run (step 418):**
```clojure
(let [conn (seon.cluster.boot/connection "default")
      base-1 (datahike.api/commit-as-db @conn #uuid "6acf6fed-6b55-5297-9f31-af2c339648cb")
      head-1 (datahike.api/commit-as-db @conn #uuid "6acfdaa8-9993-510f-b3a4-bc1fe5746efe")
      main-1 (datahike.api/commit-as-db @conn #uuid "6ad0440f-8ed2-571f-8f02-9f42a53e701b")
      base-2 (datahike.api/commit-as-db @conn #uuid "6ad0440f-8ed2-571f-8f02-9f42a53e701b")
      head-2 (datahike.api/commit-as-db @conn #uuid "6ad046d2-95a0-59c1-96db-aba25d5b9203")
      main-2 (datahike.api/commit-as-db @conn #uuid "6ad046cc-b95a-55be-b3d4-8b5f2242faa4")
      meas (fn [f]
             (let [[t0 [c0 a0]] [(System/nanoTime) (seon.flow/thread-counters)]
                   v (f)
                   [t1 [c1 a1]] [(System/nanoTime) (seon.flow/thread-counters)]]
               {:val v :ms (/ (- t1 t0) 1e6) :alloc-mb (/ (- a1 a0) 1048576.0)}))
      profile (fn [base head main]
                (let [since (seon.cluster.source/branch-base-t head main :cluster-default :chain-f1f6dc8e6652)
                      m-ours (meas #(seon.program/changed-identities since head seon.cluster.source/merge-bound))
                      m-theirs (meas #(seon.program/changed-identities base main seon.cluster.source/merge-bound))
                      changed (into (:val m-ours) (:val m-theirs))
                      bound (assoc seon.cluster.source/merge-bound :seon.program/identities changed)
                      m-dm-base (meas #(seon.program/digest-map base bound))
                      m-dm-head (meas #(seon.program/digest-map head bound))
                      m-dm-main (meas #(seon.program/digest-map main bound))
                      m-three (meas #(seon.program/three-way (:val m-dm-base) (:val m-dm-head) (:val m-dm-main)))
                      delta (:seon.program/changed-on-head (:val m-three))
                      m-carried (meas #(@#'seon.merge/carried-entities main head delta))
                      m-reconcile (meas #(seon.fn/reconcile-tx head (:val m-carried) (vec delta)))
                      m-record (meas #(@#'seon.merge/adoption-record-tx main head))
                      tx (into (:val m-reconcile) (:val m-record))
                      projection (seon.db/carried-projection head)
                      m-prep (meas #(@#'seon.db/prepared-write projection head {:tx-data tx}))
                      m-dwith-no-val (meas #(datahike.api/with head {:tx-data (:tx-data (:val m-prep))}))
                      m-val (meas #(@#'seon.db/validate-write-report projection (:val m-dwith-no-val)))]
                  {:counts {:ours (count (:val m-ours)) :theirs (count (:val m-theirs))
                            :changed (count changed) :delta (count delta)
                            :entities (count (:val m-carried)) :tx (count tx)
                            :prep-tx (count (:tx-data (:val m-prep)))
                            :effective-datoms (count (:tx-data (:val m-dwith-no-val)))}
                   :steps (mapv (fn [[k m]] [k (dissoc m :val)])
                                [[:ours m-ours] [:theirs m-theirs]
                                 [:dm-base m-dm-base] [:dm-head m-dm-head] [:dm-main m-dm-main]
                                 [:three-way m-three] [:carried-entities m-carried]
                                 [:reconcile-tx m-reconcile] [:adoption-record-tx m-record]
                                 [:prepared-write m-prep] [:d-with-no-validator m-dwith-no-val]
                                 [:validate-write-report m-val]])}))]
  {:call-2 (profile base-2 head-2 main-2)
   :call-1 (profile base-1 head-1 main-1)})
```

**Exact measured table (from step 418; plus step 346 for Call 2 when `:seon.db/projection` was cold on `stored->db`):**

| Step | Call 1 (675 moved) Counts | Call 1 ms | Call 1 Thread Alloc MB | Call 2 (9 moved) Counts | Call 2 Warm ms (Alloc MB) | Call 2 Cold-Proj ms (Alloc MB) |
|---|---:|---:|---:|---:|---:|---:|
| `ours` (`changed-identities since head`) | `24` | `20.5 ms` | `14.8 MB` | `700` | `17.5 ms` (`16.5 MB`) | `24.9 ms` (`21.3 MB`) |
| `theirs` (`changed-identities base main`) | `705` | `49.4 ms` | `24.0 MB` | `9` | `7.5 ms` (`7.1 MB`) | `12.2 ms` (`11.7 MB`) |
| `dm-base` (`digest-map base bound`) | `729` | `265.1 ms` | `57.9 MB` | `704` | `6.3 ms` (`6.0 MB`) | `23.5 ms` (`22.4 MB`) |
| `dm-head` (`digest-map head bound`) | `729` | `27.6 ms` | `14.9 MB` | `704` | `6.3 ms` (`6.0 MB`) | `23.1 ms` (`22.0 MB`) |
| `dm-main` (`digest-map main bound`) | `729` | `12.6 ms` | `12.0 MB` | `704` | `6.9 ms` (`6.6 MB`) | `21.4 ms` (`20.4 MB`) |
| `three-way` | `675` delta | `0.5 ms` | `0.5 MB` | `9` delta | `0.9 ms` (`0.8 MB`) | `0.6 ms` (`0.6 MB`) |
| `carried-entities` (`main` -> `head`) | `660` entities | `962.2 ms` | `418.6 MB` | `7` entities | `4.1 ms` (`3.9 MB`) | `225.7 ms` (`215.2 MB`) |
| `reconcile-tx` (`head` vs `entities`) | `5,600` tx ops | `704.6 ms` | `244.2 MB` | `66` tx ops | `3.8 ms` (`3.6 MB`) | `356.2 ms` (`337.0 MB`) |
| `adoption-record-tx` | `1` op | `2.3 ms` | `1.8 MB` | `1` op | `0.3 ms` (`0.3 MB`) | `12.3 ms` (`11.7 MB`) |
| `prepared-write` | `5,594` ops | `35.7 ms` | `34.1 MB` | `68` ops | `0.8 ms` (`0.7 MB`) | `0.9 ms` (`0.8 MB`) |
| `datahike.api/with` (no validator) | `28,173` datoms | **`19,139.6 ms`** | **`6,730.7 MB`** | `601` datoms | `76.7 ms` (`72.9 MB`) | `1,157.1 ms` (`741.9 MB`) |
| `validate-write-report` | `28,173` datoms | **`1,493.1 ms`** | **`1,221.5 MB`** | `601` datoms | `34.5 ms` (`32.1 MB`) | `74.6 ms` (`61.5 MB`) |
| **Total across steps** | **675 moved** | **`22.71 s`** | **`8,775.0 MB` (8.78 GB)** | **9 moved** | **`165.7 ms` (`156.7 MB`)** | **`1,932.5 ms` (`1,467.2 MB`)** |

### 2.3 Agreement / Disagreement with `datahike-internals.md` (d10e70f10)

- **Disagree on where `merge-main!`'s 8.9 GB goes:**
  `datahike-internals.md` (§0.2 and §6) noted it did not measure `merge-main!` and hypothesized that `seon.db/commit-at-t` and `seon.db/basis-revisions` lineage walks (6.4 GB) were the main share of `merge-main!`'s 8.9 GB.
  Our direct step-by-step measurement of Call 1 (`675` moved declarations) proves that **`merge-main!` does NOT call `commit-at-t` or `basis-revisions` during `merge-base`** because `:base/chain-f1f6dc8e6652` exists in `branches`, so `seon.cluster.source/merge-base` (`src/seon/cluster/source.clj:894-905`) takes the first branch of its `cond` (`(contains? branches (registry/base-branch candidate))` -> `commit-database`, costing **1.6 ms / 1.4 MB**, measured in step 550) and `changed-identities` takes an integer `since = 536872008` (for `ours`, costing **20.5 ms / 14.8 MB**) and a `DB` `base` whose `:max-tx` `536872008` is an ancestor on `main` (for `theirs`, costing **49.4 ms / 24.0 MB**).
  Instead, **90.6% (`7,952.2 MB` = 7.95 GB) of Call 1's `8,775.0 MB` allocation** is inside the transaction application itself:
  - **`datahike.api/with`: `6,730.7 MB` (`19,139.6 ms`)**
  - **`seon.db/validate-write-report`: `1,221.5 MB` (`1,493.1 ms`)**
  - **`seon.merge/carried-entities`: `418.6 MB` (`962.2 ms`)**
  - **`seon.fn/reconcile-tx`: `244.2 MB` (`704.6 ms`)**

### 2.4 Four Measured Root Causes of `merge-main!` Cost

#### Cause 2A: `org.replikativ.persistent_sorted_set.Branch.remove` Wipes `_subtreeCount = -1L` and Faults Every Sibling Leaf on Retractions (`6,730.7 MB` in Call 1)
We split Call 1's `5,594` prepared transaction operations into retractions (indices `0..2312`: `24` `:db/retractEntity`, `775` `:db/retract`, `1,514` `:db.fn/retractAttribute` -> `5,087` retracted datoms) vs additions/maps (indices `2313..5591` -> `16,055` added datoms) vs the 2 trailing `:db.fn/call` ops (`vectors-tx` and `namespace-digests-tx`).

**Form run (step 428 & step 454):**
```clojure
;; Step 428:
(let [m-retracts (meas #(datahike.api/with head-1 {:tx-data (subvec tx-data 0 idx-first-add)}))
      m-adds-and-maps (meas #(datahike.api/with (:db-after (:val m-retracts)) {:tx-data (subvec tx-data idx-first-add (- n 2))}))]
  ...)
;; Step 454 (CachedStorage stats diff across the 4 slices of Call 1):
{:retracts-stats (merge-with - stats1 stats0)
 :adds-stats (merge-with - stats2 stats1)
 :vectors-tx-stats (merge-with - stats3 stats2)
 :ns-digests-tx-stats (merge-with - stats4 stats3)}
```
**Exact numbers returned:**
- **Retracting `5,087` datoms (`retracts-only`)**: `1,945.2 ms`, `1,784.3 MB` (on warmed cache; `~5.5 GB` when cold), **`12,656` Konserve storage reads**, **`195,270` node-cache accesses** (`38.4` node accesses per retracted datom!).
- **Adding `16,055` datoms (`adds-and-maps`, 3.15x more datoms!)**: **`111.3 ms`, `106.0 MB`**, **`50` storage reads**, **`3,519` node-cache accesses** (`0.22` node accesses per added datom!).
- **`vectors-tx` (`:db.fn/call`)**: `1,890` storage reads, `75,132` node-cache accesses (`303.7 ms`, `289.1 MB`, measured in step 426).
- **`namespace-digests-tx` (`:db.fn/call`)**: `275` storage reads, `107,982` node-cache accesses (`365.3 ms`, `345.9 MB`, measured in step 426).

**Why `-remove` is ~50x more expensive than `-insert` (`org.replikativ.persistent_sorted_set.Branch`, decompiled from `persistent-sorted-set-0.4.137.jar` in steps 488–514):**
1. When `datahike.index.persistent-set/-remove` (`persistent_set.cljc:258`) calls `.disjoin(pset, datom, cmp)`, `Branch.remove` removes the key from the target `Leaf` (or level-1 `Branch`) and then calls:
   ```java
   long newSubtreeCount = this.tryComputeSubtreeCountFromChildren(newChildren, this.len(), storage);
   Branch newBranch = new Branch(this._level, newKeys, newAddresses, newChildren, newSubtreeCount, this._settings);
   ```
2. Inside `Branch.tryComputeSubtreeCountFromChildren` (decompiled at step 514):
   ```java
    ANode child = this.childIfPresent(children, i);
   if (child == null) return -1L;
   ```
   Because Datahike uses lazy `WeakReference` children (`ref-type` `:weak` when `:store-cache-bytes` is configured), any sibling child of that level-1 `Branch` that is not currently strongly/weakly resident in `children[]` is `null`! So `tryComputeSubtreeCountFromChildren` returns **`-1L`**, wiping out `newBranch._subtreeCount = -1L` (even though `this._subtreeCount` was known and exact, and the delta was `-1`!).
3. Immediately afterward, `Branch.remove` calls `parent.carryAndDeposit(storage, cmp, ...)` -> `parent.depositKV(...)`, which calls `newBranch.count(storage)` -> `newBranch.subtreeCount(storage)` -> **`newBranch.computeSubtreeCount(storage)`**, which loops `for (int i = 0; i < len; ++i) total += this.child(storage, i).count(storage)` — **faulting and deserializing every single sibling `Leaf` (64 to 125 `Leaf`s per `Branch`, measured in step 500!)** from `CachedStorage` / Konserve just to recompute the count that was just decremented by 1!
4. Furthermore, in `reconcile-tx-in` (`src/seon/fn.clj:4013-4043`), every updated declaration emits `[:db.fn/retractAttribute eid attr]` for every component/multi attribute (`1,514` ops in Call 1) AND `[:db/retract eid attr v]` (`775` ops) before re-adding all attributes (`2,619` `:db/add` + `660` maps) — even for attributes whose value on the entity did not change!

#### Cause 2B: `seon.cluster.source/branch-base-t` Returns Initial Fork `536872008` Instead of `(db/basis-t base)` (`536872684`), Plus `merge-main!` Diffs `ours` Unnecessarily
**Form run (step 342):**
```clojure
{:since-1 (seon.cluster.source/branch-base-t head-1 main-1 :cluster-default :chain-f1f6dc8e6652)
 :since-2 (seon.cluster.source/branch-base-t head-2 main-2 :cluster-default :chain-f1f6dc8e6652)
 :base-1-t (seon.db/basis-t base-1)
 :base-2-t (seon.db/basis-t base-2)}
;; => {:since-1 536872008, :since-2 536872008, :base-1-t 536872008, :base-2-t 536872684}
```
- In Call 2 (`9` moved declarations), `base-2` had already been advanced to `536872684`, so `theirs` (`(program/changed-identities base-2 main-2 merge-bound)`) had only **`9` declarations**.
- However, `branch-base-t` (`src/seon/cluster/source.clj:907-916`) returned `536872008` (the initial fork tx), so `ours` (`(program/changed-identities since head-2 merge-bound)`) returned **`700` declarations** (the `675` declarations merged in Call 1 plus `25` branch declarations)!
- Unioning `ours` (`700`) and `theirs` (`9`) produced `changed = 704` declarations, forcing `digest-map` on `base`, `head`, and `main` to pull and digest `704 * 3 = 2,112` declarations instead of `9 * 3 = 27`!
- Even more fundamentally: `merge-main!` (`src/seon/merge.clj:460-476`) only ever acts on `:seon.program/changed-on-head` and `:seon.program/conflict`. By definition of `seon.program/three-way` (`src/seon/program.clj:861-891`), every identity in `:seon.program/changed-on-head` or `:seon.program/conflict` **must have changed between `base` and `main`** (i.e. must be in `theirs`)! Computing `ours` in `merge-main!` is 100% redundant: restricting `changed` to `theirs` (`(program/changed-identities base main merge-bound)`) gives the exact same `:changed-on-head` and `:conflict` while doing work proportional only to what `main` changed (`9` instead of `704`).

#### Cause 2C: `seon.db/namespace-digests-tx` (`365.3 ms / 345.9 MB`) and `seon.db/validate-write-report` (`1,493.1 ms / 1,221.5 MB`)
- In `seon.db/namespace-digests-tx` (`src/seon/db.clj`), for every touched namespace (102 namespaces in Call 1), it queries all declarations in that namespace (including unchanged ones) and calls `declaration-value` and `definition-digest-of` on **every declaration in the namespace before checking `(not (written ...))`**, causing `107,982` node-cache accesses (`345.9 MB` in Call 1).
- In `seon.db/validate-write-report` (`src/seon/db.clj`), every datom in `(:tx-data report)` (`28,173` datoms across `~4,500` entities in Call 1, including retracted entities and vector cells) triggers full entity reconstruction and Malli validation (`1,493.1 ms`, `1,221.5 MB`).

#### Cause 2D: `seon.merge/carried-entities` (`962.2 ms / 418.6 MB`) + `seon.fn/reconcile-tx` (`704.6 ms / 244.2 MB`)
- `carried-entities` calls `seon.fn/published-index-entities` on `main` (`660` entities), and `reconcile-tx` calls `current-index-entity` on `head` and runs `normalized-index-entity` twice (once on `current`, once on `desired`), which calls `db/stored-collection` -> `db/carried-projection` on every cardinality-many attribute of every entity.

---

## Hunt 3: `prepare-merge!` on a One-Change Branch (`~0.9 GB` allocation)

### 3.1 Sub-Step Measurements on `default`

We measured the sub-steps of `seon.cluster.source/prepare-merge!` (`src/seon/cluster/source.clj:1020-1156`) on `:chain-f1f6dc8e6652` (`12` branch changes, `1` task proof `seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`, `29` reaching seed tests) and on a 1-declaration delta (`#{[:seon.fn/sym 'seon.error/library-names]}`).

**Forms run (step 549, step 553, step 677):**
```clojure
;; Step 553:
(let [instance (get @seon.operator.runtime/running-instances "default")
      store (:seon.store/store instance)
      conn (:seon.boot/cluster-connection instance)
      head (seon.db/db conn)
      branches (seon.cluster.registry/branches store)
      m-compare (measure-fn #(seon.cluster.source/compare-branch store branches :chain-f1f6dc8e6652 head :cluster-default))
      {:keys [c-id base branch changed three]} (:value m-compare)
      task-sym 'seon.error/an-unresolved-alias-name-offers-its-nearest-core-name-3
      whole (into (:seon.program/changed-on-branch three)
                  (concat (:seon.program/added three) (:seon.program/retracted three)))
      m-tested (measure-fn #(seon.cluster.source/tested-task-basis head branch base task-sym))
      tested (:value m-tested)
      {:seon.task/keys [step] owed :seon.task/proofs designed :seon.task/product} (:seon.task/obligations tested)
      proofs (into (set owed) (when (= :prove step) designed))
      m-seeds (measure-fn #(seon.cluster.source/merge-seeds branch whole proofs task-sym))
      seeds (:value m-seeds)
      m-list (measure-fn #(seon.cluster.source/merge-list branch (into whole (:seon.program/conflict three)) seeds))
      listed (:value m-list)
      selection (some->> listed :seon.merge/will-merge (into #{} (filter whole)))
      m-reach (measure-fn #(#'seon.cluster.source/unselected-reach branch whole selection))
      delta (into selection (filter whole) (for [[_ v] selection :when (qualified-symbol? v)] [:seon.ns/name (symbol (namespace v))]))
      m-tx (measure-fn #(#'seon.cluster.source/replacement branch head delta))] ...)
```
and in **step 677** (evicting `::reach-index-by-revision` and `::reach-head` for `branch` to measure cold reach-owner behavior, as occurs on a fresh branch and on `:merge-<id>`):

| `prepare-merge!` Sub-Step | Measured Time (`ms`) | Measured Thread Alloc (`MB`) | What It Is Proportional To |
|---|---:|---:|---|
| 1. `compare-branch` (after `merge-main!` on `:chain-f1f6dc8e6652`, 693 `changed`) | `39.5–79.4 ms` | `42.4–77.5 MB` (warm) / `299.6 MB` (cold) | All `693` declarations changed since initial fork (`536872008`) instead of branch's `12` changes |
| 2. `tested-task-basis` | `1.7–96.5 ms` | `1.8–40.4 MB` | Task history / obligation lookup |
| 3. `merge-seeds` -> `seon.fn/reverse-closure` (`src/seon/fn.clj:1971-2033`) | `15.2 ms` | `35.4 MB` | **Whole program**: `declared-callers` (`src/seon/fn.clj:1831-1888`) is called with `nil` targets (`(when-not depth (declared-callers read! database))`), scanning all `:seon.fn/reference-to`, `:seon.fn/invokes`, and `:seon.effect/capability` datoms across the entire DB |
| 4. `merge-seeds` -> `seon.test.runner/current-results` -> `reach-digests` (`src/seon/test/runner.clj:1242-1298`, cold branch owner) | `103.4 ms` (`118.6 ms` total `merge-seeds`) | **`169.5 MB` (`205.0 MB` total `merge-seeds`)** | **Entire transitive reach closure of all candidate tests** (~2,000–5,000+ nodes & schemas) + Tarjan SCC (`reach-index`) + SHA-256 hashing of every component |
| 5. `merge-list` -> `seon.test.runner/reach-closure` (`src/seon/test/runner.clj:1478-1497`) | `11.4–19.4 ms` | `17.0–17.6 MB` | Walking `reach-graph` over `seeds` (29 tests) |
| 6. `unselected-reach` + `replacement` (`carried-entities` + `reconcile-tx`) | `17.0 ms` | `8.9 MB` | `delta` (`9` declarations, `39` tx ops) |
| 7. `candidate-gate!` (`src/seon/cluster.clj:2712-2749`):<br>— `store/branch!` (`s = :merge-<id>`) + `acquire-context!`<br>— `write!` (`db/transact!` of `tx` onto `s`)<br>— `seon.test/run` on `s`:<br>&nbsp;&nbsp;• `reach-digests` on `s` (`:merge-<id>` has a brand-new `reach-owner`!)<br>&nbsp;&nbsp;• `admit-run` (`db/transact!` recording test run on `s`) | `66.7 ms` (just `reach-digests` on cold owner) + transactions (**HYPOTHESIS** for full `candidate-gate!` wrapper: `~250–350 MB` total) | **Second full transitive reach walk + Tarjan SCC** (`131.2 MB` measured in step 677 for `cold-reach-digests` on 29 seeds) + 2 transactions on `:merge-<id>` (`write!` of `tx` + `admit-run`) |
| 8. `merge-verdict` -> `results-verdict` (`src/seon/cluster/source.clj:1260-1308`):<br>— `contract-gaps` (`fn/contract-findings`)<br>— `fn/gate-sets` per function in `delta` | `5.0 ms` (`contract-gaps`) + `19.9 ms` (`gate-sets`) | `3.9 MB` (`contract-gaps`) + `29.3 MB` (`gate-sets`, ~`3.7 MB` per fn) | **Whole program** per function in `delta`: `gate-sets` -> `gate-edges` (`src/seon/fn.clj:2169-2214`) queries all `[:find [?s ...] :where [_ :seon.fn/sym ?s]]`, all `[:find [?s ...] :where [_ :seon.test/sym ?s]]`, and `declared-callers` across the entire DB |

### 3.2 Four Measured Root Causes of `prepare-merge!`'s ~0.9 GB Allocation

#### Cause 3A: `seon.test.runner/reach-owner` Keys `::reach-head` and `::reach-attribute-head` by `:seon.store/branch` (`src/seon/test/runner.clj:868-874`)
```clojure
(defn- reach-owner [database]
  (when-let [identity (db/committed-value-identity database)]
    (assoc (dissoc identity :datahike.value/commit-id)
           :seon.store/branch (get-in (db/schema-database database) [:config :branch]))))
```
- When `reach-entries` (`src/seon/test/runner.clj:1242-1298`) runs on a database whose `reach-revision-key` is not yet in `::reach-index-by-revision` (because 1 declaration was edited), it tries to incrementally reuse `prior` via `(cache/present ::reach-head identity)` and `(cache/present ::reach-attribute-head [identity attribute shape])`.
- Because `identity` (`reach-owner`) includes **`:seon.store/branch`**, any newly created branch — first `:chain-<id>`, and then the temporary scratch branch **`s = (keyword (str "merge-" (id/id)))`** created on every `prepare-merge!` (`src/seon/cluster/source.clj:1120`) — has a **brand-new `:seon.store/branch` keyword that has never been seen in `::reach-head` or `::reach-attribute-head`**!
- Consequently:
  1. `previous-head` in `reach-entries` (`runner.clj:1260`) is **`nil`**, so **`prior` is `nil`**, and `known` in `reached-graph` (`runner.clj:996`) is **`{}`**!
  2. For every changed attribute (such as `:seon.fn/sym`), `reach-attribute-map` (`runner.clj:942-957`) ALSO misses `::reach-attribute-head` because `[identity attribute shape]` contains `:seon.store/branch`, so it rebuilds the entire attribute digest map from scratch instead of using `::reach-attribute`'s incremental diff!
  3. With `prior = nil`, `reached-graph` walks the **entire transitive closure** of all seed tests (`1,500–5,000+` functions and schemas), and `reach-index` (`runner.clj:1136-1186`) sorts all node names, builds `int-array` adjacency lists, runs Tarjan's SCC (`reach-components`), and SHA-256 hashes every component (`169.5 MB` in `merge-seeds`, and another **`131.2 MB`** when `seon.test/run` calls `reach-digests` on `:merge-<id>`!).
- Furthermore, even when `prior` IS found on the same branch (e.g. a second edit on `:chain-<id>`), `reach-index` (`runner.clj:1136-1186`) still runs Tarjan's SCC and builds `::component-digests` over **all `nodes` and `schemas` in `reached-graph`** (including unchanged nodes that hit `known`, because `reached-graph` adds `{::leaf {::known digest}}` to `nodes` for every seed and every neighbour of a changed node, and then merges the entire `9,165`-entry `prior` maps into `entries`).

#### Cause 3B: `merge-list` Calls `reach-closure` After `merge-seeds` Already Computed `reach-entries`, and `reach-entries` Strips `::out` Edges From Cached `::reach-index-by-revision` (`src/seon/test/runner.clj:1022-1037, 1280-1298`)
- In `reach-entries` (`runner.clj:1280-1282`):
  ```clojure
  concrete (fn [m] (into {} (remove (comp map? ::leaf val)) m))
  ```
  When `prior` is non-nil, `reached-graph` short-circuits any node with a matching `known` digest to `{::leaf {::known digest}}` (where `(map? (::leaf v))` is true and `::out` is omitted). `concrete` strips those nodes before merging with `prior`.
- Even when `::reach-index-by-revision` hits in `reach-graph` (`runner.clj:1022-1037`), `reach-closure` (`runner.clj:1478-1497`) re-walks the graph (`14.7 MB` warm in step 677) and re-pulls `:seon.fn/ns` for every reached function (`(map #(db/pull database [:seon.fn/ns] [:seon.fn/sym %]) functions)`).

#### Cause 3C: Whole-Program Edge Scans in `seon.fn/reverse-closure` (`35.4 MB`) and `seon.fn/gate-sets` (`29.3 MB`)
- `seon.fn/closure-walk` (`src/seon/fn.clj:1971-2033`, called by `reverse-closure` in `merge-seeds`) calls `(when-not depth (declared-callers read! database))` with 2 arguments (`targets = nil`), scanning **every** `:seon.fn/reference-to`, `:seon.fn/invokes`, and `:seon.effect/capability` datom in the database instead of passing the frontier `targets` to the 3-argument `(declared-callers read! database targets)`.
- `seon.fn/gate-sets` (`src/seon/fn.clj:2216-2260`, called by `results-verdict` for each function in `delta` and by `seon.test/destructive-reach` in `host-exclusions`) calls `gate-edges`, which queries **every function and test in the database** (`[:find [?s ...] :where [_ :seon.fn/sym ?s]]`, `[:find [?s ...] :where [_ :seon.test/sym ?s]]`) and calls 2-arity `(declared-callers read! database)` on every call.

#### Cause 3D: `compare-branch` Uses `branch-base-t` Instead of Merge Base (`42.4–299.6 MB` after `merge-main!`)
- As shown in step 549, `compare-branch` (`src/seon/cluster/source.clj:918-950`) calls `(program/changed-identities (branch-base-t branch head target candidate) branch merge-bound)`. After `merge-main!`, `branch-base-t` still returns the initial fork `t` (`536872008`), so `changed` includes all `653` declarations merged from `main`, and `compare-branch` pulls and digests `693 * 3 = 2,079` declarations (`77.5 MB` warm / `299.6 MB` cold) instead of the `12` declarations changed on the branch.

### 3.3 Owners and Smallest Fixes

1. **`seon.test.runner/reach-owner` (`src/seon/test/runner.clj:868-874`)**:
   - Remove `:seon.store/branch` from `reach-owner` (key `::reach-head` and `::reach-attribute-head` by store identity `[(:datahike.store/identity identity) (:datahike.value/connection-id identity)]` or seed `::reach-head` from the parent commit's `reach-revision-key` via `d/parent-commit-ids`!). Better yet: key `prior` directly by the parent commit's `reach-revision-key` (content/commit lineage, Law #2 & #3: "What immutable value already holds the answer? Key it by content, never by a branch/place!"). Then `:chain-<id>` and `:merge-<id>` immediately inherit `main`'s `::reach-index-by-revision` as `prior`, reducing cold `reach-digests` on a 1-change branch from **`169.5 MB + 131.2 MB = 300.7 MB`** to **`< 2 MB`**!
2. **Reuse the Chain Branch's Green Reach Without Transacting on `:merge-<id>` when `main`'s Reach Is Unchanged (`seon.cluster.source/prepare-merge!`, `src/seon/cluster/source.clj:1112-1135`)**:
   - When `delta` has no conflicts and the reach digests of `seeds` on `head ⊕ delta` equal their green reach digests on `branch` (or when `main` has not touched any declaration in `reach-closure` of `seeds` since `base`), `candidate-gate!` does not need to create branch `:merge-<id>`, transact `tx`, run `seon.test/run`, and transact `admit-run` at all — `d/with` (or checking that `(:seon.program/changed-on-head three)` is disjoint from `reach-closure`) proves the candidate's reach is identical to `branch`'s already-tested reach!
3. **`seon.fn/closure-walk` (`src/seon/fn.clj:1994`) and `seon.fn/gate-edges` (`src/seon/fn.clj:2169-2214`)**:
   - In `closure-walk`: delete `declared (when-not depth (declared-callers read! database))` and call `(declared-callers read! database frontier)` per step so only the frontier's incoming `:avet` refs are queried (`O(frontier)` instead of `O(whole DB)`).

---

## 4. Questions for Root

1. **Boot heap floor breakdown on the new `68ceef4ad` process (pid 52140):**
   Now that `default` is freshly booted on `68ceef4ad` at ~2.5 GB with nothing run, should we take a lightweight `jmap -histo:live 52140` (or inspect `(datahike.index.interface/node-cache-stats ...)` and `(seon.cache/status)`) on the fresh boot to confirm the exact split between Root 1B (Datahike `node-cache` under-weighing `512 MB` weighted -> `~1.3 GB` actual) and Root 1C (`seon.cache` under-weighing compiled Malli schemas in `:seon.db/projection` / `:seon.schema/projection`)?
2. **`persistent-sorted-set` `Branch.remove` fix location:**
   Root Cause 2A (`Branch.remove` setting `newSubtreeCount = -1L` when any sibling `WeakReference` child is `null` and then faulting all 64–125 sibling `Leaf`s in `depositKV` -> `computeSubtreeCount`) is in `org.replikativ.persistent-sorted-set` (`0.4.137`). Does the owner want a fork/patch of `persistent-sorted-set` (computing `newSubtreeCount = this._subtreeCount - 1` when `this._subtreeCount >= 0`), or should Seon avoid unnecessary component/attribute retractions in `seon.fn/reconcile-tx-in` (`src/seon/fn.clj:4013-4043`) by diffing attribute values before emitting `:db.fn/retractAttribute` and `:db/retract`? (Doing the diff in `reconcile-tx-in` is ~15 lines in Seon and also eliminates unchanged datom churn.)
3. **Keying `reach-entries` `prior` by parent commit instead of `reach-owner` (`:seon.store/branch`):**
   In `seon.test.runner` (`src/seon/test/runner.clj:868-874, 1242-1298`), `::reach-head` and `::reach-attribute-head` are currently keyed by `reach-owner` (which includes `:seon.store/branch`), violating AGENTS.md Choosing the Design #3 ("Never key by an agent, context or place when content is available"). May we schedule a task to key `prior` lookup by the parent commit's `::reach-index-by-revision` (or store-wide `reach-owner` without `:seon.store/branch`)?

---

## 5. Should This Continue?

- **Hunt 1 (Live Heap Floor): STOP INVESTIGATION — CAUSES ARE CLEAR AND READY FOR FIX LANES.**
  - *What we would do next if continued:* Run `jmap -histo:live 52140` and a 10-line `eval_clj` check of `node-cache-stats` and `seon.cache/status` on the freshly booted `default` (`~5 minutes`) to record the exact fresh-boot instance counts alongside the OOM dump counts.
  - *Merit judgment:* The three retention mechanisms (Root 1A: per-overlay 512 MB `node-cache` + leaked overlay branch connections; Root 1B: `datahike.index.node-cache/node-bytes` under-weighing `Leaf`/`Datom`/`PersistentVector`/`Branch` by ~3x; Root 1C: `seon.cache/retained` weighing compiled Malli `Schema` graphs as 64B leaves) are completely isolated with exact class counts and line numbers. No further hunting is needed.
- **Hunt 2 (`merge-main!` Cost): STOP INVESTIGATION — CAUSES ARE CLEAR (AND PARTIALLY IN FLIGHT).**
  - *What we would do next if continued:* Nothing needed for diagnosis; a phase-2 lane is already converting `merge-main!` to a merge commit with Datahike `merge-base` (`593a8276`).
  - *Merit judgment:* Alongside the `merge-base` fix, our measurements uncovered two huge, independent multipliers in `merge-main!` that the `merge-base` lane alone will NOT fix: (1) `merge-main!` unioning `ours` into `changed` when only `theirs` can ever be in `:changed-on-head` or `:conflict`, and (2) `Branch.remove` faulting all sibling `Leaf`s on every retraction (`6.73 GB` in Call 1) + `seon.fn/reconcile-tx-in` retracting and re-adding unchanged attributes on moved entities + `seon.db/namespace-digests-tx` digesting unwritten declarations (`345.9 MB`). These are ready to file as targeted implementation tasks.
- **Hunt 3 (`prepare-merge!` Cost): STOP INVESTIGATION — CAUSES ARE CLEAR AND READY FOR FIX LANES.**
  - *What we would do next if continued:* Measure `candidate-gate!`'s `seon.test/run` in isolation on a disposable branch (`~10 minutes`) to split the remaining `~200 MB` inside `candidate-gate!` between `admit-run` and `carried-results`.
  - *Merit judgment:* We already isolated over `500–700 MB` of the `~900 MB` one-change `prepare-merge!` allocation to four exact owners: `reach-owner` including `:seon.store/branch` (causing cold full-graph walks and Tarjan SCC twice per `prepare-merge!`: `205.0 MB` in `merge-seeds` + `131.2 MB` on `:merge-<id>`), `compare-branch` using `branch-base-t` (`42.4–299.6 MB`), `seon.fn/closure-walk` calling 2-arity `declared-callers` across the whole DB (`35.4 MB`), and `seon.fn/gate-sets` scanning all functions/tests across the whole DB (`29.3 MB`). The causes are clear and actionable now.

---

## 6. Ten-Line Summary

1. **Hunt 1 (OOM Heap Dump `java_pid84529.hprof`)**: `4,468,346` of `4,696,981` `Datom`s (`95.1%`) are held in `61,409` `Leaf._keys` arrays across **4 separate 512 MB Datahike `node-cache` instances (`1.61 GB` weighted)** because `seon.cluster.store/open-overlay!` puts `:overlay (UUID/randomUUID)` into `physical-store-key` and `release-store!` leaks non-`:db` overlay branch connections.
2. **Hunt 1 (2.5 GB Boot Floor)**: `datahike.index.node-cache/node-bytes` under-weighs `Datom` `PersistentVector` tuples (`884,005` in HPROF, weighed as `40B` vs `>208B` actual), `String` headers, and `Branch` `WeakReference[128]`/`Slot[128]` arrays (`18,106` `Slot`s, `297,154` `PersistentTreeMap` nodes) by ~2.5x–3x, while `seon.cache/retained` weighs compiled Malli schemas (`395,976` `Atom`s, `462,462` `Delay`s, `377,159` `PersistentVector`s) as `64B` leaves.
3. **Hunt 2 (`merge-main!` Call 1, 675 moved)**: Disagrees with `datahike-internals.md`'s unmeasured hypothesis that lineage walks caused the `8.9 GB`; in Call 1 (`tx 536873073`), `datahike.api/with` allocated **`6,730.7 MB` (`19.14 s`)** and `seon.db/validate-write-report` allocated **`1,221.5 MB` (`1.49 s`)** (`7.95 GB` = `90.6%` of total).
4. **Hunt 2 (Why `d/with` allocated 6.73 GB)**: Retracting `5,087` datoms took **`1,945.2 ms / 1,784.3 MB` (`12,656` storage reads, `195,270` cache accesses)** while adding `16,055` datoms took only **`111.3 ms / 106.0 MB` (`50` reads, `3,519` accesses)** because `persistent_sorted_set.Branch.remove` resets `_subtreeCount = -1L` whenever any sibling `WeakReference` child is `null`, forcing `depositKV` -> `computeSubtreeCount` to fault all 64–125 sibling `Leaf`s per retraction.
5. **Hunt 2 (`merge-main!` Call 2, 9 moved)**: `seon.cluster.source/branch-base-t` returned initial fork `536872008` instead of `(db/basis-t base)` (`536872684`), so `ours` had `700` declarations while `theirs` had `9`; unioning `ours` into `changed` (`704`) pulled `704 * 3 = 2,112` declarations even though `merge-main!` only ever acts on subsets of `theirs` (`:changed-on-head` and `:conflict`).
6. **Hunt 3 (`prepare-merge!` ~0.9 GB on 1-change branch)**: `seon.test.runner/reach-owner` keys `::reach-head` and `::reach-attribute-head` by `:seon.store/branch`, so both a new branch and the scratch `:merge-<id>` branch created in `candidate-gate!` miss `prior` (`nil`) and rebuild/hash the entire transitive reach graph and Tarjan SCC from scratch (**`205.0 MB` in `merge-seeds` + `131.2 MB` on `:merge-<id>` = `336.2 MB`**).
7. **Hunt 3 (Whole-program scans in `prepare-merge!`)**: `seon.fn/closure-walk` (in `reverse-closure`) calls 2-arity `(declared-callers read! database)` scanning all declared edges in the DB (**`35.4 MB`**), `seon.fn/gate-sets` (in `results-verdict`) scans all functions and tests in the DB per delta function (**`29.3 MB`**), and `compare-branch` uses `branch-base-t` (**`42.4–299.6 MB`**).
8. **Open Questions for Root**: (1) Confirm fresh-boot histogram on pid `52140` (`jmap -histo:live`); (2) Patch `Branch.remove` in `persistent-sorted-set` and/or diff attributes in `seon.fn/reconcile-tx-in` to stop retracting unchanged attributes; (3) Key `reach-entries` `prior` by parent commit `reach-revision-key` instead of `:seon.store/branch`.
9. **Recommendation (Hunts 1 & 2)**: **STOP** — causes, owners, and exact numbers are proven; convert findings into implementation tasks (fix `node-bytes` + overlay cache/release, `reconcile-tx-in` attribute diffing, `namespace-digests-tx` filter order, and `merge-main!` `theirs`-only diff).
10. **Recommendation (Hunt 3)**: **STOP** — the primary allocation owners (`reach-owner`'s `:seon.store/branch` key, `closure-walk`'s 2-arity `declared-callers`, `gate-sets`'s whole-DB scan, and `compare-branch`'s `branch-base-t`) are proven and ready for implementation lanes.
