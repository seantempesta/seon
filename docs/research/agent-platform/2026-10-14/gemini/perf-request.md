# Per-Request Overhead on the One REPL Path (`seon.mcp/eval-clj`)

## Baseline Measurement & Anatomy of One Request

### 1. Isolated Trivial Read-Only Request (`(+ 1 2)`)

`MEASURED` on `default` in JVM mode via two complementary probes:
1. `(seon.profile/measure (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"}))`
2. Uninstrumented `LongAdder` entry/nano deltas across all 3,531 live profile cells around `(#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"})` (so `seon.profile/summary` does not pollute the call count):

```clojure
{:wall-ms          55.77          ;; 36.96 ms best uncontended; 151–348 ms under writer/GC contention
 :stages           {:acquire  1.88
                    :evaluate 1.56
                    :settle  47.99
                    :render   4.17}
 :armed-calls      7939           ;; 654 inside the batch world sink before settle; 7,285 in settle + post-batch
 :db-reads         18             ;; 14 pull + 4 q
 :transactions     1              ;; 42 datoms written on default/main
 :thread-alloc-mb  10.95          ;; 26.7–32.2 MB JVM window}
```

Top armed functions across the full `(+ 1 2)` `eval-clj` round-trip (from raw `LongAdder` deltas):

| Function | Calls / request | Total ms (self+children) | Phase |
|---|---:|---:|---|
| `seon.mcp/eval-clj` | 1 | 55.77 | entry |
| `seon.mcp/jvm-run` | 1 | 55.66 | entry |
| `seon.turn.batch/evaluate-jvm` | 1 | 49.68 | batch |
| `seon.turn.batch/jvm-request` | 1 | 49.65 | batch |
| `seon.turn.batch/settle` | 1 | 47.99 | settle |
| `seon.db/transact!` | 1 | 46.10 | settle |
| `seon.db/transact-call` | 1 | 41.40 | settle |
| `seon.db/validate-write-report` | 1 | 3.00 | settle |
| `seon.config/effective` | 7 | 2.80 | acquire / batch / post |
| `seon.render.value/settle` | 92 | 2.65 | evaluate (`jvm-show`) + render (`printed`) |
| `seon.cluster.agent/acquire-context!` | 1 | 2.15 | acquire |
| `seon.mcp/observed` | 1 | 1.89 | post-settle |
| `seon.turn/record-evaluated-call` | 1 | 1.83 | settle |
| `seon.db/write-owned-values-error` | 1 | 1.81 | settle |
| `seon.render.value/more?` | 757 | 0.95 | evaluate (`jvm-show`) + render (`printed`) |
| `seon.db/stamp-transaction` | 1 | 0.86 | settle |
| `seon.render.value/flat-width` | 222 | 0.78 | evaluate (`jvm-show`) + render (`printed`) |
| `seon.db/transaction-report-datom?` | 380 | 0.56 | settle |
| `seon.schema/projection-cache-value` | 256 | 0.42 | settle / reads |
| `seon.profile/cost-scope` | 285 | 0.38 | settle (`stamp-transaction`) + post (`live-costs`) |
| `seon.render.value/extras` | 293 | 0.38 | evaluate (`jvm-show`) + render (`printed`) |
| `seon.db/database-value?` | 278 | 0.33 | all |
| `seon.print/node-child?` | 300 | 0.24 | evaluate (`jvm-show`) + render (`printed`) |

### 2. Why the Prompt's Baseline Recorded 92,143–116,373 Calls and 101–172 ms in `:evaluate`

In `tmp/argon-perf-profile.edn` (`eval 763159`), the request evaluated `(seon.profile/summary (seon.profile/cells) 120)` and printed a 120-entry table. Two multipliers inside `:evaluate` explain the jump from `7,939` calls / `1.56 ms` to `92,143–116,373` calls / `101–172 ms`:

1. **`seon.profile/summary` over 3,531 cells makes 114,499 armed calls (`104.3 ms`)** because `seon.profile`'s own leaf schema predicates (`seon.profile/adder?`, `seon.profile/counts?`, `seon.profile/histogram?`, `seon.profile/throw-counts?`, `seon.profile/stamped-clock?`, `seon.profile/world-counter?`) are themselves armed with Malli function schemas (Finding 2).
2. **`seon.turn.batch/jvm-evaluate` runs `seon.turn.batch/jvm-show` -> `seon.render.value/project` inside the `:evaluate` timer**, and `seon.render.value`'s per-node tree-walk helpers (`more?`, `extras`, `flat-width`, `nest`, `breaks?`, `settle`) are armed with Malli contracts, adding **20,000–52,000+ armed calls (`35–75 ms`)** whenever a request returns a medium-sized map or vector (Finding 3).

---

## Ranked Findings

### Finding 1: Unconditional Main-Branch Transaction on Read-Only JVM Evaluations and Redundant Per-Transaction Config/Sink Work

1. **Functions**:
   - `seon.turn.batch/settle`
   - `seon.turn.batch/jvm-request`
   - `seon.db/transact-call`
   - `seon.db/stamp-transaction`
   - `seon.db/branch-dial`
   - `seon.db/validate-write-report`
   - `seon.db/write-owned-values-error`
   - `seon.mcp/jvm-run`
   - `seon.mcp/sci-run`

2. **What repeats or is super-linear, and on which unchanged input**:
   - **Unconditional write serialization on read-only JVM probes**: Every `eval_clj` call with `:seon.mcp/mode "jvm"` invokes `seon.turn.batch/jvm-request` -> `seon.turn.batch/settle`, which unconditionally executes `(db/transact! connection [[:db.fn/call record-evaluated-call ...]])` on `default`'s `:main` branch. Even when `(+ 1 2)` defines no vars, registers no schemas (`(:seon.batch/program result)` has empty `:seon.program/defined-vars` and `:seon.program/schema-names`), and mutates no domain state, it still writes a 42-datom turn/eval/cost receipt to `:main`, serializing behind the single cluster writer (`30.5–48.0 ms` uncontended, `41–168 ms` when other lanes transact) and running `seon.db/validate-write-report` (`380` calls to `seon.db/transaction-report-datom?`, `92` to `seon.db/write-attribute-plan`, `126` to `seon.db/logical-datom`).
   - **5 repeated `seon.db/branch-dial` queries on the same immutable `db-before` inside every transaction**: Inside `seon.db/transact-call`, `seon.db/stamp-transaction` reads 5 cost dials (`:seon.config.fn.cost/min-calls`, `:seon.config.fn.cost/min-total-us`, `:seon.config.fn.cost/max-functions`, `:seon.config.tx/cost-top`, `:seon.config.tx/max-datom-stats`) by calling `seon.db/branch-dial` 5 separate times on `(:db-before report)`. Each `branch-dial` call runs a fresh Datahike `d/q` (`branch-cluster-query`) plus `d/pull` on `[:seon.config/cluster cluster-name]` (`10` Datahike reads per transaction on the exact same immutable `db-before` value!). Existing mechanism: `seon.config/effective` (or a single `d/pull` of the cluster config entity per `stamp-transaction` call) already resolves all config dials on one database value at once.
   - **Duplicate sink-cost aggregation on the same `:seon.batch/sink`**: `seon.db/stamp-transaction` calls `seon.profile/transaction-observation` (which scans `:seon.batch/sink`, calls `seon.profile/cost-scope` on every touched function, and queries `seon.db/sink-digests`), and then `seon.mcp/jvm-run` and `seon.mcp/sci-run` immediately call `seon.profile/live-costs` on the **exact same** `(:seon.batch/sink batch)` and database basis, scanning the sink and querying `seon.db/sink-digests` a second time.

3. **Evidence**:
   - `MEASURED`:
     ```clojure
     ;; Probe on default (JVM mode):
     (seon.profile/measure
       (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"}))
     ;; => :settle 30.56 ms .. 47.99 ms out of 36.96 ms .. 55.77 ms total (82–86% of wall time)
     ;;    :transactions 1 (42 datoms)
     ;; Raw LongAdder breakdown inside :settle for a single (+ 1 2) request:
     ;;   seon.db/transact!                   1 call   46.10 ms
     ;;   seon.db/transact-call               1 call   41.40 ms
     ;;   seon.db/validate-write-report       1 call    3.00 ms
     ;;   seon.turn/record-evaluated-call     1 call    1.83 ms
     ;;   seon.db/write-owned-values-error    1 call    1.81 ms
     ;;   seon.db/stamp-transaction           1 call    0.86 ms
     ;;   seon.db/branch-dial                 5 calls   0.36 ms (5 d/q + 5 d/pull on same db-before)
     ;;   seon.db/transaction-report-datom? 380 calls   0.56 ms
     ;;   seon.profile/cost-scope           285 calls   0.38 ms (called in stamp-transaction AND live-costs)
     ;;   seon.db/sink-digests                2 calls   0.30 ms (called in stamp-transaction AND live-costs)
     ```
   - `INFERRED`:
     In `seon.db/stamp-transaction`:
     ```clojure
     (let [db (:db-before report)
           min-calls (branch-dial db branch :seon.config.fn.cost/min-calls)
           min-total-us (branch-dial db branch :seon.config.fn.cost/min-total-us)
           max-functions (branch-dial db branch :seon.config.fn.cost/max-functions)
           cost-top (branch-dial db branch :seon.config.tx/cost-top)
           max-datom-stats (branch-dial db branch :seon.config.tx/max-datom-stats) ...] ...)
     ```
     where `seon.db/branch-dial` runs `d/q` + `d/pull` on every call:
     ```clojure
     (defn- branch-dial [db branch attribute]
       (let [cluster-name (when (and db (keyword? branch))
                            (d/q branch-cluster-query db branch))
             entity (when (string? cluster-name)
                      (d/pull db [attribute] [:seon.config/cluster cluster-name]))]
         (get entity attribute (get (config-defaults) attribute))))
     ```

4. **Smallest fix, by owner**:
   - **`seon.db/stamp-transaction` (`seon.db`)**: Resolve `cluster-name` once on `(:db-before report)` and pull all 5 dial attributes in a single `d/pull` (or look up the cached effective config map for `(committed-value-identity db)`), cutting 5 `d/q` + 5 `d/pull` calls per transaction to 1.
   - **`seon.mcp/jvm-run` & `seon.mcp/sci-run` (`seon.mcp` / `seon.turn.batch`)**: Return or reuse the `:seon.profile/function-costs` already computed from `:seon.batch/sink` during settlement instead of calling `seon.profile/live-costs` + `seon.db/sink-digests` a second time.
   - **`seon.turn.batch/jvm-request` (`seon.turn.batch`)**: When a JVM eval request has no program mutations (`empty?` `:seon.program/defined-vars` and `:seon.program/schema-names` and no error), either skip the main-branch receipt transaction or record the ephemeral JVM probe in memory without contending on the `:main` Datahike writer.

5. **Expected gain and why**:
   - Eliminating the 4 redundant `branch-dial` queries + duplicate `live-costs` scan saves **~1.2 ms and ~200 armed calls on every transaction system-wide**.
   - Skipping the synchronous `:main` transaction on pure read-only JVM probes removes **30–160 ms of writer-serialized latency (80%+ of trivial request time), 1 transaction (42 datoms), ~2,200 armed calls, and ~7 MB of allocation per read-only probe**.

6. **End-to-end probe**:
   ```clojure
   (seon.profile/measure
     (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"}))
   ;; Verify :settle drops from 30–168 ms to <1 ms on read-only JVM probes (and branch-dial calls drop from 5 to 1 on writing turns).
   ```

---

### Finding 2: Armed Malli Function Contracts on `seon.profile` Leaf Predicates and `O(N)` Queue Copy in `seon.profile/cost-scope`

1. **Functions**:
   - `seon.profile/adder?`
   - `seon.profile/counts?`
   - `seon.profile/histogram?`
   - `seon.profile/throw-counts?`
   - `seon.profile/stamped-clock?`
   - `seon.profile/world-counter?`
   - `seon.profile/observation`
   - `seon.profile/counts`
   - `seon.profile/summary`
   - `seon.profile/cost-scope`

2. **What repeats or is super-linear, and on which unchanged input**:
   - **Self-instrumented leaf predicates multiplied across 3,531 cells × 40 histogram buckets**: `seon.profile` defines `adder?`, `counts?`, `histogram?`, `throw-counts?`, `stamped-clock?`, and `world-counter?` with `{:malli/schema [:=> [:cat :any] :boolean]}` AND registers them as `[:fn adder?]`, etc. inside `:seon.profile/histogram`, `:seon.profile/counts`, and `:seon.profile/cell`. Because `seon.instrument` arms every function carrying `:malli/schema` in `seon.*`, validating a single `:seon.profile/counts` map calls the armed `seon.profile/adder?` **40 times** (once per histogram bucket `LongAdder`!) plus `histogram?`, `counts?`, `throw-counts?`, `stamped-clock?`, and `world-counter?`. Calling `seon.profile/summary` (or `seon.profile/measure` when summarizing cells) over 3,531 registered functions triggers **114,499 armed calls (`104.3 ms`)** just to read the profile counters. The `LongAdder` array inside each cell is allocated once in `fresh-counts` and never changes type.
   - **Repeated `.toArray` copy on `cells-by-key` in `seon.profile/cost-scope`**: During every request's settlement and cost reporting, `seon.profile/cost-scope` is called once per touched function (`285` times in a trivial `(+ 1 2)` request, `500–1,500` times in an agent turn). `cost-scope` looks up the `ConcurrentLinkedQueue` in `cells-by-key` and calls `(some ... (.toArray queue))`, allocating an array and scanning cell maps on every call even though `:seon.fn/cost-scope` is a static property of the `function-symbol`'s schema/namespace.

3. **Evidence**:
   - `MEASURED`:
     ```clojure
     ;; Raw LongAdder delta around (seon.profile/summary (seon.profile/cells) 10):
     {:elapsed-ms 104.31
      :total-armed-calls 114499
      :top [[:seon.profile/adder?         88800  29.84]
            [:seon.profile/counts?         7064  10.17]
            [:seon.profile/observation     3532  88.24]
            [:seon.profile/counts          3532  69.39]
            [:seon.profile/throw-counts    3532   8.50]
            [:seon.profile/stamped-clock?  3532   4.86]
            [:seon.profile/world-counter?  3532   4.70]
            [:seon.profile/histogram?      3532   4.46]
            [:seon.profile/throw-counts?   3532   4.44]]}
     ```
   - `INFERRED`:
     In `seon.profile`:
     ```clojure
     (defn adder? {:malli/schema [:=> [:cat :any] :boolean]} [x] (instance? LongAdder x))
     ```
     and in `seon.profile/cost-scope`:
     ```clojure
     (defn cost-scope {:malli/schema [:=> [:cat :symbol] :keyword]} [function-symbol]
       (or (when-let [^ConcurrentLinkedQueue queue (.get cells-by-key function-symbol)]
             (some (fn [cell]
                     (when-let [var-obj (:seon.profile/var cell)]
                       (var-cost-scope var-obj)))
                   (.toArray queue)))
           (namespace-cost-scope (some-> function-symbol namespace symbol))))
     ```

4. **Smallest fix, by owner**:
   - **`seon.profile` (`adder?`, `counts?`, `histogram?`, `throw-counts?`, `stamped-clock?`, `world-counter?`)**: Remove `{:malli/schema ...}` from the six `instance?` leaf predicates used inside `:seon.profile/*` `[:fn ...]` schemas (or mark `seon.profile` leaf predicates uninstrumented), and check `(and (vector? x) (= 40 (count x)))` in `histogram?` instead of `[:tuple <40x [:fn adder?]>]` on every `counts` call.
   - **`seon.profile/cost-scope` (`seon.profile`)**: Compute `:seon.fn/cost-scope` once when registering a cell in `seon.profile/register-cell!` (stored on the cell or `.peek` of `queue`) instead of calling `(.toArray queue)` and `var-cost-scope` (`meta` lookup) 285+ times per request.

5. **Expected gain and why**:
   - Cuts `seon.profile/summary` / profile-inspection evals from **114,499 armed calls and 104 ms to <3 ms and ~3,500 calls** (a **~100 ms / 97% reduction**, eliminating the primary cause of the 92k–116k call spike in the prompt's baseline).
   - Saves **~0.4–1.5 ms and 285+ array allocations** on every ordinary request in `stamp-transaction` and `live-costs`.

6. **End-to-end probe**:
   ```clojure
   (seon.profile/measure
     (#'seon.mcp/eval-clj {:seon.mcp/code "(seon.profile/summary (seon.profile/cells) 20)"
                           :seon.mcp/mode "jvm"}))
   ;; Verify :evaluate drops from ~105–170 ms to <15 ms and total armed calls drop from ~115,000 to <10,000.
   ```

---

### Finding 3: Per-Node Armed Contracts in `seon.render.value` / `seon.print` Tree Walks and Unconditional Dual AI+HTML Emission (`emit-both`)

1. **Functions**:
   - `seon.render.value/project`
   - `seon.render.value/select-nodes`
   - `seon.render.value/settle`
   - `seon.render.value/flat-width`
   - `seon.render.value/extras`
   - `seon.render.value/more?`
   - `seon.render.value/nest`
   - `seon.render.value/breaks?`
   - `seon.render.value/start-column`
   - `seon.render.value/chain`
   - `seon.print/emit-both`
   - `seon.print/node-child?`

2. **What repeats or is super-linear, and on which unchanged input**:
   - **Per-ancestor armed calls inside `select-nodes` -> `settle`**: Every `eval_clj` request calls `seon.render.value/project` at least twice: once in `seon.turn.batch/jvm-show` (inside `:evaluate`!) for the evaluated value, and once in `seon.mcp/printed` (inside `:render`) for the `:seon.repl/request` map. When `select-nodes` admits a child node at depth $d$, `seon.render.value/settle` walks the ancestor chain (`chain`, length $d$) and at each ancestor calls `flat-width` (which calls `more?`) and `extras` (which calls `more?` and `nest`) on **both** the `old` and `new` ancestor nodes. Because `more?`, `extras`, `flat-width`, `nest`, `breaks?`, `start-column`, `chain`, `settle`, and `seon.print/node-child?` are all armed with Malli `:malli/schema` contracts, every node admission executes $O(d)$ armed Malli-validated calls.
   - **Unconditional dual emission (`seon.print/emit-both`) on AI-only renders**: `seon.render.value/project` takes an explicit `output` face (`:seon.render/ai` or `:seon.render/html`), yet unconditionally calls `(print/emit-both selected options)` and constructs `:seon.render.value/html` (`breadcrumbs` + hiccup tree) even when `output` is `:seon.render/ai` (such as `seon.mcp/printed` and `seon.render.transcript/floor-text`), where `:seon.render.value/html` is immediately discarded by `render-ai` -> `(:seon.render.value/text (project ...))`.

3. **Evidence**:
   - `MEASURED`:
     ```clojure
     ;; 1. Trivial (+ 1 2) request (prints `3` and the `:seon.repl/request` map):
     ;;    seon.render.value/more?        757 calls   0.95 ms
     ;;    seon.print/node-child?         300 calls   0.24 ms
     ;;    seon.render.value/extras       293 calls   0.38 ms
     ;;    seon.render.value/flat-width   222 calls   0.78 ms
     ;;    seon.render.value/nest         155 calls   0.18 ms
     ;;    seon.render.value/breaks?      122 calls   0.25 ms
     ;;    seon.render.value/settle        92 calls   2.65 ms
     ;;    Total on trivial request: ~2,200 armed calls, ~4.2 ms.

     ;; 2. Returning a 25-row vector of maps (Step 63):
     ;;    seon.render.value/more?      17534 calls  21.40 ms
     ;;    seon.render.value/extras      6999 calls   9.12 ms
     ;;    seon.print/node-child?        6216 calls   5.10 ms
     ;;    seon.render.value/flat-width  4955 calls  16.80 ms
     ;;    seon.render.value/nest        4173 calls   4.85 ms
     ;;    seon.render.value/breaks?     2831 calls   5.60 ms
     ;;    seon.render.value/settle      2043 calls  54.20 ms
     ;;    Total on 25-row result: ~52,000 armed calls, ~75 ms in :evaluate + :render!
     ```
   - `INFERRED`:
     In `seon.render.value/project`:
     ```clojure
     (let [...
           emitted (print/emit-both selected options)
           text (:seon.print/text emitted)
           hiccup (into [:div {:class "seon-value"} (breadcrumbs root path)]
                        (:seon.print/hiccup emitted))]
       {:seon.render.value/text text
        :seon.render.value/html hiccup ...})
     ```
     and in `seon.render.value/render-ai`:
     ```clojure
     (defn render-ai [value budget]
       (:seon.render.value/text (project value budget [] :seon.render/ai)))
     ```

4. **Smallest fix, by owner**:
   - **`seon.render.value` (`more?`, `flat-width`, `nest`, `extras`, `start-column`, `breaks?`, `chain`, `settle`) & `seon.print` (`node-child?`, `text-node`, `hiccup-node`, `emit-node`)**: Keep Malli schemas on the public entry points (`project`, `prepare`, `render-ai`, `render-html`, `text`, `html`, `emit-both`, `emit-text`, `emit-hiccup`) and make the inner per-node recursion helpers private/uninstrumented functions.
   - **`seon.render.value/project` (`seon.render.value`)**: When `output` is `:seon.render/ai` (as in `render-ai`), call `seon.print/emit-text` instead of `seon.print/emit-both` (only calling `emit-both` inside `prepare`, which explicitly caches both faces).

5. **Expected gain and why**:
   - Saves **~3 ms and ~2,100 armed calls** on every trivial `eval_clj` request, and **35–70 ms and 20,000–50,000 armed calls** on requests returning non-trivial maps/collections (where `O(nodes * depth)` inner helpers currently dominate `:evaluate`).

6. **End-to-end probe**:
   ```clojure
   (seon.profile/measure
     (#'seon.mcp/eval-clj {:seon.mcp/code "(vec (range 100))" :seon.mcp/mode "jvm"}))
   ;; Verify :evaluate + :render armed calls in seon.render.value/* drop by >90% and wall time drops by 15–40 ms.
   ```

---

### Finding 4: `seon.config/effective` Recomputed and Pulled 7 Times per Request on the Same Database Basis

1. **Functions**:
   - `seon.config/effective`
   - `seon.config/effective-in`
   - `seon.config/declared-projection`
   - `seon.mcp/dial`
   - `seon.mcp/observed`
   - `seon.mcp/jvm-run`
   - `seon.mcp/with-diff`
   - `seon.turn.world/world`
   - `seon.turn.batch/jvm-request`

2. **What repeats or is super-linear, and on which unchanged input**:
   - `seon.config/effective` is a pure function of `(db, cluster-name)` and the schema projection (`(db/carried-projection db)` + `(declared-projection)`). Yet it has **no cache**, and a single `eval_clj` request invokes `seon.config/effective` **7 separate times** for `"default"`:
     1. `seon.turn.world/world` -> `admission`
     2. `seon.turn.batch/jvm-request` -> `settings`
     3. `seon.mcp/observed`
     4. `seon.mcp/jvm-run` -> `(dial database cluster-name :seon.config.fn.cost/min-total-us)`
     5. `seon.mcp/jvm-run` -> `(dial database cluster-name :seon.config.test.cost/top)`
     6. `seon.mcp/with-diff` -> `(dial main cluster-name :seon.config.fn.cost/min-calls)`
     7. `seon.cluster.agent/acquire-context!` / `evaluate-source!`
   - Every call to `seon.config/effective` recomputes `(declared-projection)` -> `(schema.edn/packaged-forms)` -> `(schema.edn/packaged-population)`, computes `(dial-attributes declared)` and `(dial-attributes projection)` twice (`14` calls), and executes a full `(db/pull db '[*] [:seon.config/cluster cluster-name])` through `seon.db/pull-call` (`7` of the `14` total pulls in a request!).
   - Existing caching mechanism: `seon.cache/lookup` (which already caches `:seon.schema/declaration-projection` and `:seon.schema/carried-projection`) and `datahike.db/committed-value-identity` (already used in `seon.db` to key committed database values).

3. **Evidence**:
   - `MEASURED`:
     ```clojure
     ;; Raw LongAdder breakdown on a single (+ 1 2) eval_clj request:
     ;;   seon.config/effective              7 calls   2.80 ms
     ;;   seon.config/effective-in           7 calls   2.16 ms
     ;;   seon.config/declared-projection    7 calls   0.31 ms
     ;;   seon.schema.edn/packaged-forms     7 calls   0.18 ms
     ;;   seon.config/dial-attributes       14 calls   0.22 ms
     ;;   seon.db/pull ([:seon.config/cluster "default"]) 7 calls  1.75 ms
     ```
   - `INFERRED`:
     In `seon.config/effective-in` and `seon.config/effective`:
     ```clojure
     (defn effective-in [projection db cluster-name]
       (let [declared (declared-projection)
             defaults (merge (defaults-in declared) (defaults-in projection))
             entity (when (and db (db/entity-known? db [:seon.config/cluster cluster-name]))
                      (db/pull db '[*] [:seon.config/cluster cluster-name]))]
         (assoc (merge defaults
                       (select-keys entity (into (dial-attributes declared)
                                                 (dial-attributes projection))))
                :seon.config/cluster cluster-name)))
     ```
     and in `seon.mcp`:
     ```clojure
     (defn- dial [db cluster-name key]
       (get (config/effective db cluster-name) key))
     ```

4. **Smallest fix, by owner**:
   - **`seon.config/effective-in` (`seon.config`)**: Cache `effective-in` in `seon.cache/lookup` when `(datahike.db/committed-value-identity db)` is non-nil, keyed by `[(datahike.db/committed-value-identity db) cluster-name (System/identityHashCode projection) (System/identityHashCode (declared-projection))]`.
   - **`seon.mcp/jvm-run` & `seon.mcp/sci-run` (`seon.mcp`)**: Resolve `(config/effective database cluster-name)` once after settlement and read `:seon.config.fn.cost/min-total-us`, `:seon.config.test.cost/top`, and `:seon.config.fn.cost/min-calls` from that held map instead of calling `dial` repeatedly.

5. **Expected gain and why**:
   - Reduces `seon.config/effective` database pulls from **7 per request to 0 on unchanged basis (or 1 on a new commit)**, saving **2.5–3.5 ms, 7 `db/pull` calls, and ~600 armed calls per request**.

6. **End-to-end probe**:
   ```clojure
   (seon.profile/calls-of
     (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"})
     'seon.config/effective-in 'seon.db/pull-call)
   ;; Verify seon.config/effective-in pulls drop from 7 to <=1 (0 when basis is unchanged).
   ```

---

### Finding 5: Unnecessary `seon.cluster.agent/acquire-context!` and Post-Settlement Receipt Re-Query (`seon.mcp/observed` -> `seon.render.transcript/history`) on JVM Requests

1. **Functions**:
   - `seon.mcp/jvm-run`
   - `seon.mcp/observed`
   - `seon.cluster.agent/acquire-context!`
   - `seon.cluster.agent/adopt-cluster-declarations!`
   - `seon.cluster/branch-declaration-changes`
   - `seon.render.transcript/history-entries`
   - `seon.render.transcript/candidate-history`
   - `seon.render.transcript/history`
   - `seon.turn.batch/evaluate-jvm`

2. **What repeats or is super-linear, and on which unchanged input**:
   - **`acquire-context!` on every JVM request**: `seon.mcp/jvm-run` calls `(agent/acquire-context! handle agent-id {:seon.agent/branch branch})`, which pulls the agent entity, runs `adopt-cluster-declarations!` -> `seon.cluster/branch-declaration-changes` -> `attribute-change-tx` (diffing all schema declarations against the branch), and calls `seon.sci.eval/fork-for-turn` to build a fresh SCI turn context — even though `jvm-run` evaluates on the host JVM, never uses the forked SCI context, and on `main` only needs `(:seon.db/connection handle)`!
   - **Re-querying Datahike in `seon.mcp/observed` for the receipt `batch` already holds in memory**: `seon.render.transcript/candidate-history` already has an in-memory fast path `(held-entry db selected-run-id evaluated-sources)` that `seon.mcp/sci-run` uses by passing `(:seon.turn.loop/evaluated-sources batch)`. However, `seon.mcp/jvm-run` calls `(observed after cluster-name agent-id (:seon.turn/id batch) nil)` — passing `nil` for `evaluated-sources`! Passing `nil` forces `candidate-history` to call `seon.render.transcript/history`, executing **4 Datalog queries** (`selected-run-entity-ids`, `branch-errors`, etc.) and `2` `pulled-many` calls to read back from Datahike the exact receipt that `evaluate-jvm` just constructed in memory.

3. **Evidence**:
   - `MEASURED`:
     ```clojure
     ;; Raw LongAdder breakdown on a single (+ 1 2) JVM eval_clj request:
     ;;   seon.cluster.agent/acquire-context!           1 call   2.15 ms
     ;;   seon.cluster.agent/acquire-once!              1 call   1.88 ms
     ;;   seon.cluster.agent/adopt-cluster-declarations! 1 call  0.76 ms
     ;;   seon.cluster/branch-declaration-changes       1 call   0.72 ms
     ;;   seon.sci.eval/fork-for-turn                   1 call   0.61 ms (never used in JVM mode!)
     ;;   seon.mcp/observed                             1 call   1.89 ms
     ;;   seon.render.transcript/history-entries        1 call   1.45 ms
     ;;   seon.render.transcript/candidate-history      1 call   1.29 ms
     ;;   seon.render.transcript/history                1 call   1.07 ms (4 d/q + 2 d/pull-many!)
     ```
   - `INFERRED`:
     In `seon.mcp/jvm-run`:
     ```clojure
     (let [execution (agent/acquire-context! handle agent-id {:seon.agent/branch branch})
           connection (or (:seon.db/connection execution) (:seon.db/connection handle))
           ...
           batch (turn.batch/evaluate-jvm connection cluster-name agent-id branch code)
           ...
           observed (observed after cluster-name agent-id (:seon.turn/id batch) nil)] ...)
     ```
     Compare with `seon.mcp/sci-run`, which passes `(:seon.turn.loop/evaluated-sources batch)`:
     ```clojure
     (observed after cluster-name agent-id run-id (:seon.turn.loop/evaluated-sources batch))
     ```

4. **Smallest fix, by owner**:
   - **`seon.mcp/jvm-run` (`seon.mcp`)**: When `branch` equals `(:seon.cluster/branch handle)` (`:main`), use `(:seon.db/connection handle)` directly instead of calling `seon.cluster.agent/acquire-context!` (avoiding the unused `sci.eval/fork-for-turn` and `branch-declaration-changes`).
   - **`seon.turn.batch/evaluate-jvm` & `seon.mcp/jvm-run` (`seon.turn.batch`, `seon.mcp`)**: Populate `:seon.turn.loop/evaluated-sources` on the map returned by `seon.turn.batch/evaluate-jvm` (from its held `:seon.batch/entries`) and pass it to `seon.mcp/observed` so `seon.render.transcript/candidate-history` takes its existing `held-entry` fast path instead of querying Datahike.

5. **Expected gain and why**:
   - Saves **~3.3–4.0 ms, 4 Datalog queries, 3 `db/pull` calls, 1 unused SCI fork, and ~600 armed calls** on every JVM `eval_clj` request.

6. **End-to-end probe**:
   ```clojure
   (seon.profile/calls-of
     (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/mode "jvm"})
     'seon.cluster.agent/acquire-context! 'seon.sci.eval/fork-for-turn 'seon.render.transcript/history)
   ;; Verify all three drop from 1 to 0 on a main-branch JVM eval_clj request.
   ```

---

## Five-Line Summary

1. **Baseline**: An isolated trivial JVM `eval_clj` (`(+ 1 2)`) makes **7,939 armed calls**, **18 DB reads**, and **1 main-branch transaction** in **37–56 ms** (`151–348 ms` under writer contention); the prompt's **92k–116k calls / 101–172 ms `evaluate`** occurs when evaluating `seon.profile/summary` or printing large results.
2. **Settle & Transaction (`Finding 1`, 30–168 ms)**: `seon.turn.batch/settle` transacts a 42-datom receipt to `:main` even on pure read-only JVM probes, while `seon.db/stamp-transaction` runs `branch-dial` (`d/q` + `d/pull`) 5 times on the same `db-before` and `seon.mcp` recomputes `seon.profile/live-costs` on the same sink.
3. **Self-Instrumented Profile Predicates (`Finding 2`, 104 ms / 114k calls)**: `seon.profile/adder?` (called 40x per cell across 3,531 cells = 88,800 calls) and 5 sibling predicates carry armed Malli schemas, and `seon.profile/cost-scope` copies `.toArray` on `cells-by-key` 285x per request.
4. **Per-Node Armed Printer Helpers & Dual Emission (`Finding 3`, 4–75 ms / 2.2k–52k calls)**: `seon.render.value` arms inner tree-walk helpers (`more?`, `extras`, `flat-width`, `nest`, `breaks?`, `settle`) called $O(\text{nodes} \times \text{depth})$ times and unconditionally calls `seon.print/emit-both` (building unused HTML hiccup) on `:seon.render/ai` requests.
5. **Uncached Config & Redundant JVM Context/History Reads (`Findings 4 & 5`, 6–8 ms / 1.2k calls)**: Each request calls `seon.config/effective` 7 times (7 `db/pull`s on the same cluster config), forks an unused SCI context in `acquire-context!`, and re-queries Datahike in `seon.mcp/observed` instead of passing the held `:seon.turn.loop/evaluated-sources`.
