# Performance Hunt: The Test Step Every Agent Reply Pays (`seon.turn/test-step`)

Date: 2026-10-14  
Lane: `teststep-cost`  
Target commit (`HEAD`): `0d683c95c` (`src/seon/test/runner.clj`, `src/seon/test.clj`, `src/seon/turn.clj` unchanged since `14e881c6b`)  
Related task: `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second` ([seon.turn.edn](file:///Users/sean/src/seon/resources/seon/tasks/seon.turn.edn))  
Patch: [teststep-cost.patch](file:///Users/sean/src/seon/docs/research/agent-platform/2026-10-14/teststep-cost.patch)

---

## 1. End-to-End Measurement on `HEAD` (Time + Thread Allocation Attribution)

### Probe Setup

On agent branch `"teststep-cost"` (`:agent-b44f932f24bc` in cluster `"default"`), we measured one SCI eval request defining one function (`user/probe-add`) and one tiny `deftest` (`user/probe-add-test`) end-to-end with `seon.profile/measure` and `(seon.profile/summary (seon.profile/cells) 20)`:

```clojure
(do (require '[clojure.test :refer [deftest is]])
    (defn probe-add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (+ a b 11))
    (deftest probe-add-test (is (= 14 (probe-add 1 2)))))
```

### How Allocation Is Attributed Across Threads

`seon.profile/measure` ([src/seon/profile.clj](file:///Users/sean/src/seon/src/seon/profile.clj#L636-L677)) records `:seon.profile/alloc-bytes` by calling `(.getCurrentThreadAllocatedBytes mx)` on the **calling thread**:

```clojure
        alloc0 (.getCurrentThreadAllocatedBytes mx)
```

When an eval request enters through `seon.repl/eval-String` (`seon.turn/request-eval`), the calling thread submits the request to the agent's `core.async.flow` graph and parks in `seon.await/await-port-operations` (`4,959.2 ms` of `4,966.4 ms`), allocating only `11,444,232` bytes (`10.91 MB`) on the caller thread. The actual evaluation and `seon.turn/test-step` execute on two other threads:

1. **The agent's `core.async.flow` step thread**, where `seon.flow/step-cost` ([src/seon/flow.clj](file:///Users/sean/src/seon/src/seon/flow.clj)) samples `ThreadMXBean.getCurrentThreadAllocatedBytes` around `seon.turn/step` and reports `:seon.cost/step` in the reply's `:seon.repl/request` cost map: **8,012.7 MB** (`11,601.1 MB` when cold).
2. **The dedicated `seon-test-body` thread**, where `seon.test/measured-body` ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L2462-L2492)) samples `ThreadMXBean.getCurrentThreadAllocatedBytes` around the test body and reports `:seon.cost/tests`: **7.4451 MB** across **16.2 ms**.

To attribute every megabyte of the `8,012.7 MB` flow-thread allocation to individual functions inside `seon.turn/test-step`, we wrapped each sub-stage of `test-step` with `(.getCurrentThreadAllocatedBytes (java.lang.management.ManagementFactory/getThreadMXBean))` on the executing thread alongside `(seon.profile/summary (seon.profile/cells) 20)`.

### End-to-End Request Cost (`:seon.repl/request`)

```clojure
{:seon.cost/calls 686464,
 :seon.cost/ms 4966.4,
 :seon.cost/reads 1094,
 :seon.cost/stages {:custody 0.6,
                    :derive 26.2,
                    :evaluate 52.0,
                    :facts 1.1,
                    :fork 0.8,
                    :print 2.5,
                    :settle 102.1,
                    :test-step 4687.0},
 :seon.cost/step #:seon.cost{:alloc-mb 8012.7, :cpu-ms 4589.9},
 :seon.cost/tests #:seon.cost{:alloc-mb 7.4451, :count 1, :ms 16.2},
 :seon.cost/transactions 9}
```

- **94.4% of wall time** (`4,687.0 ms` of `4,966.4 ms`) is spent in `:test-step`.
- Within `:test-step`, executing the 1 test body (`user/probe-add-test`) takes **16.2 ms** and **7.45 MB**.
- **99.65% of `:test-step` time** (`4,670.8 ms`) and **99.91% of allocation** (`8,005.3 MB`) are harness overhead outside the test body.

### Top 20 Profiler Summary (`(seon.profile/summary (seon.profile/cells) 20)`)

| Rank | `:sym` | `:calls` | `:total-ms` | `:self-ms` | `:mean-us` | `:max-us` |
|---:|---|---:|---:|---:|---:|---:|
| 1 | `seon.await/await-port-operations` | 12 | 4959.22 | 4959.21 | 413268.25 | 4955674.08 |
| 2 | `seon.turn/test-step` | 1 | 4686.99 | 2.66 | 4686990.50 | 4686990.50 |
| 3 | `seon.test/run` | 2 | 3691.50 | 0.99 | 1845747.85 | 3220660.13 |
| 4 | `seon.test.runner/commit-results!` | 2 | 3088.91 | 1920.23 | 1544456.83 | 3088097.25 |
| 5 | `seon.db/behavior-digest` | 9176 | 1152.34 | 216.92 | 125.58 | 1906.04 |
| 6 | `seon.test/clean-request-handle` | 2 | 1106.78 | 1.94 | 553392.35 | 855794.96 |
| 7 | `seon.sci.eval/base-ctx` | 2 | 1096.65 | 0.08 | 548322.58 | 847644.13 |
| 8 | `seon.sci.eval/derive-overlay-ctx` | 2 | 1096.56 | 0.14 | 548282.38 | 847571.63 |
| 9 | `seon.sci.eval/interpret-overlay!` | 2 | 1094.81 | 4.89 | 547406.04 | 846477.17 |
| 10 | `seon.db/pull` | 23801 | 762.87 | 762.87 | 32.05 | 3966.58 |
| 11 | `seon.sci.eval/enter-namespace!` | 126 | 724.92 | 719.04 | 5753.36 | 248384.63 |
| 12 | `seon.test/select` | 2 | 556.37 | 4.92 | 278186.10 | 468255.17 |
| 13 | `seon.test/changed-definition-symbols` | 1 | 380.42 | 1.38 | 380419.67 | 380419.67 |
| 14 | `seon.sci.eval/eval-string*` | 48 | 369.51 | 336.50 | 7698.06 | 210772.92 |
| 15 | `seon.db/q` | 930 | 324.47 | 324.47 | 348.89 | 21453.17 |
| 16 | `seon.db/declaration-content-key` | 10 | 269.67 | 269.67 | 26966.96 | 117034.46 |
| 17 | `seon.test/branch-tests` | 1 | 222.52 | 0.02 | 222520.92 | 222520.92 |
| 18 | `seon.test/branch-changes` | 1 | 222.40 | 0.06 | 222398.17 | 222398.17 |
| 19 | `seon.test/branch-delta` | 1 | 221.39 | 0.25 | 221387.58 | 221387.58 |
| 20 | `seon.program/changed-identities` | 1 | 218.23 | 127.08 | 218231.08 | 218231.08 |

---

## 2. Sub-Stage Time & Allocation Breakdown and What Each Cost Is Proportional To

| Sub-stage in `seon.turn/test-step` | Calls / Turn | Cold / Evicted Time & Alloc | Warm Cache Time & Alloc | Proportional To | Why It Repeats on Unchanged Inputs |
|---|---:|---|---|---|
| **1. `seon.test.runner/commit-results!` → `executed-roots`** ([src/seon/test/runner.clj](file:///Users/sean/src/seon/src/seon/test/runner.clj#L2290-L2336)) | **2x** (`run-reaching` + `run-branch`) | **3,088.1 ms / 5,431.7 MB** (`649,788` calls) | **122.7–180.0 ms / 104.2–182.4 MB** (`92,516` calls across 2 runs) | **Whole test-harness + SCI evaluator call & schema graph** (`4,659` functions + `5,035` Malli schemas reachable from `harness-entries`) | `commit-results!` populates `::reach-index-by-revision` for `[user/probe-add-test]` first; then `executed-roots` calls `(reach-memberships tested (vec harness-entries))`. Because `revisions` includes `:seon.program/definition-digest`, **every** function/test edit changes `revisions`, misses `::reach-graph`, and calls 2-arity `(reached-graph database test-symbols)` with `known = {}` (ignoring `prior`!). |
| **2. `seon.test/select` → `changed-definition-symbols` + `(reaching observed-changed)`** ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L1004-L1053)) | **2x** (`run-reaching` + `run-branch`) | **468.3–481.0 ms / 348.6–1,066.3 MB** (`2,480` calls, `47` reads) | **468.3–481.0 ms / 348.6–1,066.3 MB** | **Whole branch history since last run of identical `(namespaces, identities)` + all `10,644` declarations on `AsOfDB`** | In `select`, `proof-request?` is `(and (= :named policy) (seq identities) (empty? (:seon.test/changed request)))`, which is `false` for `run-reaching` (`:seon.test/changed` non-empty). It scans `db/since` + `(db/as-of database basis)` via `declaration-content-key` (`42–117 ms / 90 MB` per `AsOfDB` scan) even though `:named` requests ignore `observed-reached` for candidate selection and already reuse by content-keyed `reach-digests`! |
| **3a. Redundant `run-branch` on tests `run-reaching` just ran** ([src/seon/turn.clj](file:///Users/sean/src/seon/src/seon/turn.clj#L5531-L5546)) | **1x** extra `seon.test/run` | **470.8–1,500+ ms / 237.0–2,000+ MB** | **470.8 ms / 237.0 MB** | **Every branch `deftest` touched in the turn** (full `clean-request-handle` + `select` + `commit-results!`) | `test-step` computes `stale` at line 5531 **before** `run-reaching` runs at line 5544, then passes `(filter (set stale))` to `run-branch` at line 5546 without removing the tests `run-reaching` just executed and recorded! |
| **3b. `seon.test/stale-in` per-test loop + uncached `seon.test/branch-delta`** ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L1645-L1672), [L2124-L2140](file:///Users/sean/src/seon/src/seon/test.clj#L2124-L2140)) | **1x** per turn (even on `(+ 1 2)`) | `stale`: **92.9–451.7 ms / 243.4–961.8 MB**<br>`branch-delta`: **221.4–682.9 ms / 445.2–1,428.7 MB** | `stale`: **11.5 ms / 14.5 MB**<br>`branch-delta`: **10.1 ms / 13.2 MB** | **Number of branch/proof tests × `current-results`**, plus **whole branch history since fork (`changed-identities`)** | `stale-in` calls `(runner/current-results database [test-symbol] task)` inside a `reduce` loop 1 test at a time (thrashing `::reach-index-by-revision` when >1 test exists). `branch-delta` walks `program/changed-identities` and `behavior-of` on `base` vs `candidate` on every turn even when `program-revisions-digest` is unchanged. |
| **3c. `clean-request-handle` → `interpret-overlay!`** ([src/seon/sci/eval.clj](file:///Users/sean/src/seon/src/seon/sci/eval.clj#L2592-L2703), [L3268](file:///Users/sean/src/seon/src/seon/sci/eval.clj#L3268)) | **2x** (`run-reaching` + `run-branch`) | **1,094.8 ms / 1,718.5 MB** (`846.5 ms` + `248.3 ms`) | **367.7 ms / 388.8 MB** per handle | **All `148` file tests across `9` test namespaces in `(:seon.fn/tests closure)` of the branch delta** | When `derive-overlay-ctx` builds a fresh SCI context for a test run, `interpret-overlay!` walks `reverse-closure` of `delta` (`148` file tests in `9` namespaces) and calls `enter-namespace!` + `eval-string*` for all `9` test namespaces even when only `1` test (`user/probe-add-test`) is selected to run.Eliminating the redundant `run-branch` (Fix 3a) immediately halves this per turn. |

---

## 3. Two Systemic Amplifiers Discovered on `HEAD`

### Amplifier A: `seon.cache` Is 89.0% Pinned by `::projection` / `::value-projection`

Inspecting `(seon.cache/status (seon.cache/default-cache))` on `default` revealed why `:seon.test.runner/reach-node` (`25,253` evictions) and `:seon.db/behavior-digest` (`19,259` evictions, **3% hit rate**) thrash during `test-step`:

- Total `seon.cache` budget: **512.0 MB** (`536,870,912` bytes).
- `:seon.schema.admission/projection`: `44` entries holding **379.5 MB** (`397,958,760` bytes).
- `:seon.schema.admission/value-projection`: `44` entries holding **76.5 MB** (`80,174,896` bytes).
- Combined schema projections occupy **456.0 MB (89.0% of the entire 512 MB cache)**, leaving **< 56 MB** for all other 49 cache kinds combined. When `executed-roots` walks `4,659` functions and `5,035` schemas, it allocates `> 56 MB` of reach/digest entries in a single pass and self-evicts in a tight loop.

### Amplifier B: `seon.db/declaration-content-key` Is 25x Slower on `AsOfDB` (`42–117 ms / 90 MB` vs `1.7 ms`)

The reply cost monitor flagged `seon.db/declaration-content-key` at `:live 42243.79 us` vs `:main 1718.18 us` (**25x slower**). Comparing `declaration-content-key` ([src/seon/db.clj](file:///Users/sean/src/seon/src/seon/db.clj#L1546-L1573)) on the live `DB` vs `(db/as-of db basis-t)` in the same JVM proved why:

- On a live `DB`, `(d/datoms database :avet a)` streams the persistent index directly in **1.7–3.9 ms**.
- On an `AsOfDB` (created by `(db/as-of database basis)` inside `seon.test/changed-definition-symbols` at [src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L724) and `seon.test.runner/prepare-failures!` at [src/seon/test/runner.clj](file:///Users/sean/src/seon/src/seon/test/runner.clj#L2446)), Datahike filters the historical index datom-by-datom across all `10,644` declaration entities and their `:seon.schema.admission/source` / value attributes, costing **42–117 ms and ~90 MB of allocation per call**.

---

## 4. Top Three Fixes at the Owner & Same-JVM Proofs

All three fixes are in [teststep-cost.patch](file:///Users/sean/src/seon/docs/research/agent-platform/2026-10-14/teststep-cost.patch) against `HEAD` (`0d683c95c`).

### Fix 1: `seon.test.runner/executed-roots` (`src/seon/test/runner.clj:2287-2336`)

#### Exact Code on `HEAD` ([src/seon/test/runner.clj](file:///Users/sean/src/seon/src/seon/test/runner.clj#L2311-L2319))

```clojure
        ;; The runner's own work inside the body's sink (running the var,
        ;; reporting, printing failures) is the harness's, not the test's, and
        ;; a contract's predicates are its schema's, which the reach names by form.
        harness (let [closures (when (seq executed) (reach-memberships tested (vec harness-entries)))]
                  (cond-> (into (set harness-entries)
                                (keys (schema/predicate-functions-in
                                       (or (db/carried-projection tested) (schema/handed-projection)))))
                    (and closures (not (seon.error.refusal/error? closures)))
                    (into (mapcat val) closures)))]
```

And in `reach-graph` ([src/seon/test/runner.clj](file:///Users/sean/src/seon/src/seon/test/runner.clj#L1007-L1015)):

```clojure
      (if (every? #(contains? (::component-digests entries) %) test-symbols)
        entries
        (let [graph (seon.cache/lookup
                     (::reach-graph (or (db/cache-owner database) (seon.cache/default-cache)))
                     [revisions (set test-symbols)]
                     #(reached-graph database test-symbols))
              merged (merge-reach-index entries (reach-index-from-graph graph))]
```

#### Why It Costs `3,088.1 ms / 5,431.7 MB` (Cold) and `61.3–90.0 ms / 52.1–91.2 MB` (Warm)

1. In `commit-results!` ([src/seon/test/runner.clj](file:///Users/sean/src/seon/src/seon/test/runner.clj#L2382-L2384)), line 2382 calls `(reach-memberships tested test-symbols)` for `[user/probe-add-test]`, populating `::reach-index-by-revision` at `revisions` with only `user/probe-add-test`.
2. Line 2384 calls `(executed-roots tested reaches results)`, which calls `(reach-memberships tested (vec harness-entries))` for `'[seon.test.runner/run-var! seon.sci.eval/run-test]`.
3. Because `revisions` (`reach-revision-key`) includes `:seon.program/definition-digest`, **every function or test edit changes `revisions`**. `(every? #(contains? (::component-digests entries) %) test-symbols)` is `false`, `[revisions (set test-symbols)]` misses `::reach-graph`, and `#(reached-graph database test-symbols)` calls the 2-arity `(reached-graph database test-symbols (reach-declarations database) {} #{})` with **`known = {}`**, computing component digests, schema walks, and `db/behavior-digest` across **4,659 functions and 5,035 Malli schemas** when `executed-roots` only needs the **set of qualified symbols** in the static call/reference/subject closure of `harness-entries`!

#### Smallest Fix at the Owner (Hunk 1 of `teststep-cost.patch`, `src/seon/test/runner.clj:2287-2336`)

Compute `harness-closure` over the 5 edge attributes `[:seon.fn/sym :seon.test/sym :seon.fn/calls :seon.fn/references :seon.test/subject]` using existing `reach-attribute` and `reach-fixed-point`, cached in `seon.cache` under `::harness-closure` keyed by `(db/content-revisions tested [:seon.fn/sym :seon.test/sym :seon.fn/calls :seon.fn/references :seon.test/subject])`. Body-only edits do not even change those 5 attribute revisions.

#### Same-JVM Proof (`executed-roots` on `tested` for `user/probe-add-test`)

| Variant | Wall Time (ms) | Thread Allocation (MB) | Profiled Calls | DB Reads | Speedup / Alloc Reduction |
|---|---:|---:|---:|---:|---|
| **Parent (`HEAD`, cold `::reach-node`)** | **1,983.92 ms** | **5,431.71 MB** | 649,788 | 9,200 | Baseline (cold) |
| **Parent (`HEAD`, warm `::reach-node`)** | **61.33 ms** | **52.08 MB** | 46,258 | 19 | Baseline (warm) |
| **Change (cold `::harness-closure` miss)** | **9.58 ms** | **6.24 MB** | 264 | 25 | **207x faster / 870x less alloc** (vs cold)<br>**6.4x faster / 8.3x less alloc** (vs warm) |
| **Change (warm `::harness-closure` hit, body edits)** | **1.36 ms** | **1.56 MB** | 92 | 20 | **1,458x faster / 3,480x less alloc** (vs cold)<br>**45x faster / 33x less alloc** (vs warm) |

---

### Fix 2: `seon.test/select` Skipping Unneeded `changed-definition-symbols` on Unscoped `:named` Requests (`src/seon/test.clj:1001-1007`)

#### Exact Code on `HEAD` ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L991-L1007), [L1052](file:///Users/sean/src/seon/src/seon/test.clj#L1052))

```clojure
            proof-request? (and (= :named policy) (seq identities) (empty? (:seon.test/changed request)))
...
            [observed-direct observed-bound] (if (and changes-since (not proof-request?))
                                               (changed-definition-symbols database changes-since)
                                               [#{} #{}])
            observed-changed (into observed-direct observed-bound)
```

```clojure
            ;; A named request's `:seon.test/changed` names the tests reaching
            ;; exactly those identities; changes observed since the last run
            ;; widen only incremental requests.
            requested-reached (if (= :named policy) requested-walk #{})
```

#### Why It Costs `468.3–481.0 ms / 348.6–1,066.3 MB` on Every `run-reaching` Call

1. `run-reaching` calls `seon.test/run` with `:seon.test/policy :named` and `:seon.test/changed [user/probe-add user/probe-add-test]` (without `:seon.test/since-previous-run?`).
2. Because `:seon.test/changed` is non-empty, `proof-request?` is `false`.
3. Because `runs` filters by `(= identities (get-in facts [% :seon.test.run/identities] #{}))` (`#{}`), `changes-since` is the `basis-t` of the previous reaching run on the branch.
4. Line 1005 calls `(changed-definition-symbols database changes-since)`, which constructs `(db/as-of database basis)` (`AsOfDB`), scans all `10,644` declarations via `declaration-content-key`, and computes `db/behavior-digest` on every declaration modified since `changes-since`, followed by `(reaching observed-changed)` at line 1045.
5. Yet for a `:named` request without `:seon.test/since-previous-run?`, `narrow?` (line 1023) is `false`, `requested-reached` (line 1052) ignores `observed-reached`, and reuse (lines 1189–1218) already compares `(get current-reach test-symbol)` against `(one member :seon.test.member/reach-digest)` — which is content-keyed by `runner/reach-digests` over every reachable definition's behavior digest!

#### Smallest Fix at the Owner (Hunk 2 of `teststep-cost.patch`, `src/seon/test.clj:1001-1007`)

Gate `(changed-definition-symbols database changes-since)` in `seon.test/select` on `(and changes-since (not proof-request?) (or (= :incremental policy) (:seon.test/since-previous-run? request)))`.

#### Same-JVM Proof (`seon.test/select` on `run-reaching` request)

| Variant | Wall Time (ms) | Thread Allocation (MB) | Profiled Calls | DB Reads | Selected Members |
|---|---:|---:|---:|---:|---|
| **Parent (`HEAD`)** | **480.99 ms** | **1,066.29 MB** | 2,480 | 47 | `[{:seon.test/sym user/probe-add-test, :seon.test.member/reasons #{:reaches-changed}}]` |
| **Change** | **5.18 ms** | **9.51 MB** | 1,813 | 39 | `[{:seon.test/sym user/probe-add-test, :seon.test.member/reasons #{:reaches-changed}}]` (**92.9x faster, 112.2x less alloc**) |

---

### Fix 3: `seon.turn/test-step` Redundant `run-branch` Elimination + Batched `stale-in` + Content-Keyed `branch-delta` (`src/seon/turn.clj:5542-5548`, `src/seon/test.clj:1641-1672`, `2124-2140`)

#### Exact Code on `HEAD`

1. **`seon.turn/test-step` ([src/seon/turn.clj](file:///Users/sean/src/seon/src/seon/turn.clj#L5531-L5547))**:
```clojure
          stale (reduce (fn [result [tests judged]]
                          (let [found (if (seq tests) (@test-stale database tests judged) [])]
                            (if (:seon.error/at found) (reduced found) (into result found))))
                        [] [[branch nil] [(vec proofs) task]])
          _ (when (:seon.error/at stale) (throw (ex-info (:seon.error/message stale) stale)))]
      (when (or (seq changed) (seq stale))
        (let [limit (step-limit-ms cluster agent-id)
              began (System/nanoTime)
              left #(max 1 (- limit (quot (- (System/nanoTime) began) 1000000)))
              execution (@cluster-agent-acquire-context! cluster agent-id)
              connection (:seon.db/connection execution)
              platform (platform-proof (db/db connection) changed)
              pending (set (:seon.turn.loop/pending platform))
              reaching (run-reaching execution (left) changed platform)
              ;; a branch test holding a current result would only answer it again
              branched (run-branch execution (left) (into [] (comp (filter (set stale)) (remove pending)) branch))
```
`stale` is computed at line 5531 **before** `run-reaching` executes at line 5544. When an agent defines or edits a branch `deftest` (or a function it reaches), that test is in `stale` at line 5531, is executed and committed by `run-reaching` at line 5544, and is then **passed again to `run-branch` at line 5546**, triggering a second `clean-request-handle` (`251–856 ms`), a second `select` (`88 ms`), and a second `commit-results!` (`90+ ms`)!

2. **`seon.test/stale-in` ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L1644-L1672))**:
```clojure
(defn- current-result
  "`test-symbol`'s result at its current identity judging `task` (nil: none, `seon.test.runner/current-results`)
  that still describes its source and inputs; nil when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym [:maybe :seon.task/sym]]
                  [:or :nil :seon.test/result :seon.test/result-unknown :seon.test/host-error :seon.error/value]]}
  [database test-symbol task]
  (let [inputs (db/q '[:find [?digest ...] :where [_ :seon.source/test-input-digest ?digest]] database)
        current (runner/current-results database [test-symbol] task)
        present (db/pull database [:seon.test/source :seon.test/fixture-observation]
                         [:seon.test/sym test-symbol])
        result (when (map? current) (get current test-symbol))]
    (or (first (filter :seon.error/at [inputs current present]))
        (when (and (:seon.test/source present)
                   (not (:seon.test/fixture-observation present))
                   (= #{(:seon.test.run/input-digest result)} (set inputs)))
          result))))

(defn- stale-in
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol] [:maybe :seon.task/sym]]
                  [:or [:vector :qualified-symbol] :seon.test/execution-error :seon.error/value]]}
  [database test-symbols task]
  (reduce (fn [result test-symbol]
            (let [current (current-result database test-symbol task)]
              (cond
                (:seon.error/at current)
                (reduced (assoc current :seon.test/execution-refusal :seon.test/sym))
                current result
                :else (conj result test-symbol))))
          [] test-symbols))
```
Calling `(runner/current-results database [test-symbol] task)` per test in `stale-in` updates and evicts `::reach-index-by-revision` N times when N branch/proof tests exist.

3. **`seon.test/branch-delta` ([src/seon/test.clj](file:///Users/sean/src/seon/src/seon/test.clj#L2124-L2140))**:
```clojure
(defn branch-delta
  "The pure branch delta: the declarations `candidate` changed after `base` (a committed value on its lineage, its
  change base), read once by `seon.program/changed-identities` (the history walk the done rule reads), less a
  function or test both hold at one behavior digest (a docstring-only change is none) and a namespace that lost no
  binding (`binding-lost?`), in print order; empty when nothing changed, a refused read its refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] [:or :seon.test/changed :seon.error/value]]}
  [base candidate]
  (let [changed (program/changed-identities (db/basis-t base) candidate source/merge-bound)]
    (if (:seon.error/at changed)
      changed
      (vec (sort print/compare-values
                 (remove (fn [[a v :as declaration]]
                           (if (= :seon.ns/name a)
                             (not (binding-lost? base candidate v))
                             (when-let [behavior (behavior-of base declaration)]
                               (= behavior (behavior-of candidate declaration)))))
                         changed))))))
```
`branch-delta` runs on every turn (including scratch evals `(+ 1 2)`) without caching by `[(or (db/commit-id base) (db/basis-t base)) (runner/program-revisions-digest candidate)]`.

#### Smallest Fix at the Owner (Hunks 3–5 of `teststep-cost.patch`)

- **`src/seon/turn.clj:5542-5548`**: Exclude `answered` (`(into #{} (keep :seon.test/sym) (:seon.test/results reaching))`) from the `branch` tests passed to `run-branch`.
- **`src/seon/test.clj:1641-1672`**: Batch `runner/current-results`, `inputs`, and `present` once for all `test-symbols` in `stale-in`.
- **`src/seon/test.clj:2124-2140`**: Cache `branch-delta` in `seon.cache` under `::branch-delta` keyed by `[(or (db/commit-id base) (db/basis-t base)) (runner/program-revisions-digest candidate)]`.

#### Same-JVM Proof for Fix 3 Components

| Component | Parent (`HEAD`) | Change | Gain |
|---|---|---|---|
| **Redundant `run-branch` in `test-step`** (`1` edited `deftest`) | **470.8 ms / 237.0 MB** (warm) | **0.0 ms / 0.0 MB** (`run-branch` receives `[]`) | **Eliminates entire 2nd `seon.test/run` per turn** |
| **`seon.test/stale` on 5 tests** (cold `::reach-index-by-revision`) | **451.69 ms / 961.76 MB** (`5` `current-results` calls) | **166.97 ms / 306.05 MB** (`1` batched `current-results` call) | **2.7x faster, 655.7 MB less alloc** |
| **`seon.test/branch-delta`** (unchanged program revisions, e.g. scratch eval) | **10.06 ms / 13.24 MB** (warm) / **221.4 ms / 445.2 MB** (cold) | **0.18 ms / 1.28 MB** (`0` DB reads) | **55.9x faster (warm), 1,230x faster (cold)** |

---

## 5. End-to-End Same-JVM Comparison (Parent vs. Change on the Full Eval + Test Turn)

Running the exact same probe `(do (require '[clojure.test :refer [deftest is]]) (defn probe-add ...) (deftest probe-add-test ...))` back-to-back in the same JVM on branch `"teststep-cost"`:

| Metric | Parent (`HEAD` `0d683c95c`) | With Hunks 1–5 Applied | Reduction |
|---|---:|---:|---|
| **Total Request `:seon.cost/ms`** | **4,966.4 ms** (and **5,500.3 ms** repeat) | **603.7 ms** (warm handle) / **1,088.0 ms** (cold handle) | **4.6x–8.2x faster end-to-end** |
| **`:test-step` Stage Time** | **4,687.0 ms** (`5,268.6 ms` repeat) | **439.4 ms** (warm handle) / **912.0 ms** (cold handle) | **5.1x–10.7x faster `:test-step`** |
| **Flow-Thread Allocation (`:seon.cost/step :alloc-mb`)** | **8,012.7 MB** (`11,601.1 MB` repeat) | **428.5 MB** | **18.7x–27.1x less allocation (`-7.58 GB` to `-11.17 GB` per turn)** |
| **Total Profiled Calls (`:seon.cost/calls`)** | **686,464** (`709,792` repeat) | **38,412** | **17.9x fewer function calls** |
| **`seon.test/run` Invocations per Turn** | **2** (`run-reaching` + `run-branch`) | **1** (`run-reaching` only; `run-branch` skipped as already answered) | **Halves SCI test-handle creation & run commits** |

---

## 6. Hunk Map for `identity-stable` and Follow-Up Owners

1. **Hunk 1 (`src/seon/test/runner.clj:2287-2336`)**: Adds private `harness-closure` cached under `::harness-closure` in `seon.cache` by `(db/content-revisions tested [:seon.fn/sym :seon.test/sym :seon.fn/calls :seon.fn/references :seon.test/subject])` and replaces `(reach-memberships tested (vec harness-entries))` in `executed-roots`. Does not touch `current-results` or `reach-digests` signatures.
2. **Hunk 2 (`src/seon/test.clj:1001-1007`)**: Narrows `(changed-definition-symbols database changes-since)` in `seon.test/select` to `(or (= :incremental policy) (:seon.test/since-previous-run? request))`.
3. **Hunk 3 (`src/seon/test.clj:1641-1672`)**: Replaces single-symbol `current-result` loop in `stale-in` with one batched `(runner/current-results database symbols task)` call and one batched `db/q`.
4. **Hunk 4 (`src/seon/test.clj:2123-2140`)**: Wraps `seon.test/branch-delta` in `seon.cache/lookup` under `::branch-delta` keyed by `[(or (db/commit-id base) (db/basis-t base)) (runner/program-revisions-digest candidate)]`.
5. **Hunk 5 (`src/seon/turn.clj:5542-5548`)**: Filters `answered` (`(:seon.test/results reaching)`) out of the `branch` symbols passed to `run-branch`.
6. **Follow-up outside these 3 owners (`src/seon/sci/eval.clj:2592-2703`, `src/seon/cache.clj`)**:
   - In `seon.sci.eval/interpret-overlay!`, stop eagerly calling `enter-namespace!` on all `9` test namespaces (`148` `(:seon.fn/tests closure)` rows, costing `367.7 ms / 388.8 MB` per `clean-request-handle`) when `seon.test/run` only executes the selected test symbols.
   - Evict or bound old `:seon.schema.admission/projection` and `:seon.schema.admission/value-projection` entries in `seon.cache` (`456 MB` / `89%` of the `512 MB` cache across `44` retained projections).
