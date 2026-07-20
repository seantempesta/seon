---
type: research
status: complete
tags: [research, schema, web, health]
---

# Browser validation pipeline — measured costs

Replaces the estimated validation costs in
[[universal-data-browser-design-2026-07-20]] (§3, inherited from
[[schema-aware-inspector-2026-07-20]]) with live measurements, so the
confirm-ON top-level default ships on numbers.

## Environment and method

- Live default-cluster pod: Bun 1.4.0, darwin arm64, Shadow dev `:client`
  build (runtime `client#5`), driven through the repository MCP `eval_cljs`
  shadow nREPL session on 2026-07-20. Dev build (no `:advanced`), so
  production numbers can only be faster.
- Registry population at measurement: **1957 registered schemas**, of which
  **489** are map shapes with ≥1 required key (the inverted-index
  population); **636** distinct required attributes.
- Timing: `js/performance.now` loops (n=50–2000 per cell, one warm call
  before timing); values are per-call microseconds. Async database reads
  were stashed from a `^:async` fn into an atom (top-level `await` is not
  supported through this REPL path).
- The five representative shapes are real registered schemas; the default
  cluster is fresh (0 turns, 0 plan steps), so values were constructed and
  proven valid with `schema/valid-candidate-value?` before timing:

| Shape | Schema | Value | pr-str tokens* |
|---|---|---|---|
| small map | `:seon.web.brand/brand` | 4 keys | 38 |
| turn-sized map | `:seon.agent.turn` | 15 keys | 150 |
| nested entity with refs | `:seon.agent` | 8 keys incl. ref vectors | 65 |
| vector-of | `:my.plan/steps` | 30 `:my.plan/open-step` maps | 1,327 |
| large plan-like | `:my.plan/plan-node` | recursive tree, 85 nodes | 3,956 |

*tokens via `seon.ai.tokens/estimate`.

## 1. Validator compile vs cached call

`schema/candidate-validator` (= `m/deref-recursive` + `m/validator`,
compiles fresh every call) vs invoking the compiled validator, vs the
naive per-call `m/validate` (`schema/valid-candidate-value?`):

| Schema | compile (µs) | naive m/validate (µs) | cached call (µs) |
|---|---:|---:|---:|
| `:seon.web.brand/brand` | 98.5 | 80.7 | 0.29 |
| `:seon.agent.turn` | 334.4 | 203.9 | 2.39 |
| `:seon.agent` | 417.2 | 177.3 | 1.83 |
| `:my.plan/steps` (30 elems) | 126.6 | 97.3 | 16.78 |
| `:my.plan/plan-node` (85 nodes) | 120.6 | 155.9 | 23.38 |

Memoizing the validator is a 5–1400x win; the design's malli grounding
("re-use explainer/validator when performance matters") is confirmed at
two orders of magnitude for map shapes.

## 2. Inverted required-key index (prefilter)

Index derived exactly as designed: `{required-attr #{schema-key}}` over
every registered map form with ≥1 required key.

- **Build over the FULL registry (1957 forms → 489 shapes): 1.81 ms**,
  once per projection activation (registry change), amortized to zero.
- Per-value candidate query (union over the value's keys, then
  required ⊆ key-set filter):

| Value | candidates found | prefilter (µs) |
|---|---:|---:|
| brand | 2 | 3.6 |
| turn | 2 | 7.6 |
| agent | 11 | 16.9 |
| open-step element | 6 | 9.8 |
| plan-node root | 2 | 3.2 |

Prefilter-only is confirmed cheap enough for every render: worst measured
17 µs, bounded candidate sets (2–11 of 489).

## 3. Confirm path (prefilter + memoized validate)

`matching-shapes` = prefilter, then validate every candidate through a
cache atom keyed `[projection-fingerprint schema-key]`.

| Value | cold first render (µs, compiles candidates) | warm (µs) | valid matches |
|---|---:|---:|---:|
| brand | 683 | 4.2 | 2 |
| turn | 724 | 11.3 | 2 |
| agent | 1,972 | 24.6 | 9 |
| plan-node | 684 | 27.6 | 2 |

**Churn (SSE morph re-render), 100 re-projections of the turn value:**

| Scenario | per re-render (µs) |
|---|---:|
| unchanged value (pure memo hits) | 14.0 |
| small mutation every render | 12.2 |

The memo key is `[projection-fingerprint schema-key]` — value mutations
never touch it, so the **hit rate is 100% for any render while the
registry is unchanged**; a registry change (new projection fingerprint)
is the only miss event. The design's memo needs no value-frequency
assumption at all.

- **No value-level result memo is warranted**: full confirm is already
  12–28 µs; `hash` of these values is 0.02–0.06 µs (CLJS caches
  collection hashes) but a result memo would save ~14 µs per render — not
  worth a second cache.
- **No LRU bound is required**: the cache is registry-bounded. Compiling
  ALL 489 map-shape validators takes **97.4 ms total** (0 compile errors)
  and holds ≤489 small closures. Policy: one **unbounded single-generation
  map keyed by projection fingerprint** (`{fp {schema-key validator}}`);
  activating a new projection drops the old generation wholesale.
  Process-local atom, per the runtime contracts.
- Page-scale worst case: **100 mixed rendered values confirmed in 7.7 ms
  warm** (~77 µs/value with the tree-heavy mix).

## 4. Explain + humanize (red path only)

Three deliberately-invalid values: turn missing `:status` + string
`:prompt-chars`; brand with empty `:name`; plan tree with an `:int` title
3 levels deep.

| Value | cold (µs, explainer compile + humanize) | warm (µs) | humanized payload (tokens) |
|---|---:|---:|---:|
| bad turn | 1,079 | 12.4 | 26 |
| bad brand | 271 | 4.1 | 14 |
| bad plan tree | 544 | 52.4 | 23 |

Explain runs only on invalid, and even ON the hot path it would cost
≤52 µs warm — it is comfortably off it. Hover payloads at 14–26 tokens
are far under any shared token cap.

**Spell-checking caveat**: `me/with-spell-checking` produced no output for
a misspelled optional key — malli's spell check fires on `::m/extra-key`
errors, which require `{:closed true}` maps. Seon maps are open, so a
misspelled key manifests only as "missing required key" (measured above)
when the misspelled key was required. The design's spell-checking step is
a no-op for open Seon maps unless explain runs against a closed variant;
the design doc should not promise misspelling detection as-is.

## 5. Verdict

**Confirm-ON at the top level is SAFE.** Measured budget:

- per rendered top value, warm: **4–28 µs** (prefilter 3–17 µs +
  memoized validation of 2–11 candidates);
- per SSE re-render of an unchanged-or-mutated value: **~12–14 µs**,
  memo hit rate 100% between registry changes;
- one-time costs: **≤2 ms** the first time a value shape's candidates
  compile; **1.8 ms** index rebuild + ≤**97 ms** full validator-cache
  rebuild per projection activation (amortizable, optional warmup);
- 100-value page morph: **7.7 ms** warm.

Memo policy the numbers support: **unbounded single-generation
process-local cache keyed `[projection-fingerprint schema-key]`**
(≤489 entries at the current registry; drop the generation on projection
change). No LRU, no value-level result memo.

Observation for the design (not cost): `matching-shapes` on the agent
value returned **9 valid matches**, mostly open request maps
(`:seon.agent/purpose-request` etc.) whose required keys are a subset of
the entity's keys. Specificity ordering among valid matches is
load-bearing for the primary label, and badge rendering should expect
loose request-schema noise on entity-shaped values.

## Measurement code (verbatim, REPL-only)

```clojure
(require '[malli.core :as m] '[malli.error :as me]
         '[seon.schema :as schema] '[seon.ai.tokens :as tokens]
         '[seon.db :as db])

;; -- async value fetch (top-level await unsupported; stash via atom) --
(defonce !v (atom nil))
(-> ((fn ^:async fetch []
       (let [dbv (await (db/db))
             pull1 (fn [r] (db/pull {:seon.db/db dbv
                                     :seon.db/pull-pattern '[*]
                                     :seon.db/ref r}))
             agent-v (dissoc (await (pull1 [:seon.agent/id "root"])) :db/id)
             turn-es (await (db/query '[:find ?e ?at
                                        :where [?e :seon.agent.turn/at ?at]]
                                      dbv))]
         (reset! !v {:agent agent-v :turn-count (count turn-es)}))))
    (.catch (fn [e] (reset! !v {:err (str e)}))))
;; fresh cluster: 0 turns/steps -> constructed values, each proven with
;; (schema/valid-candidate-value? <k> <v>) => true for all five.

(defn bench [n f]
  (f)
  (let [t0 (js/performance.now)]
    (dotimes [_ n] (f))
    (let [dt (- (js/performance.now) t0)]
      {:n n :total-ms (.toFixed dt 2)
       :per-call-us (js/parseFloat (.toFixed (/ (* 1000 dt) n) 2))})))

(def brand-v {:seon.web.brand/id "phosphor" :seon.web.brand/name "Phosphor Terminal"
              :seon.web.brand/tagline "the live system" :seon.web.brand/theme "phosphor-dark"})
(def turn-v {:seon.agent.turn/id "abc123def456" :seon.agent.turn/at (js/Date.)
             :seon.agent.turn/status :done :seon.agent.turn/run 42
             :seon.agent.turn/scheduled? false :seon.agent.turn/prompt-chars 48231
             :seon.agent.turn/rendered-tx 536870911 :seon.agent.turn/prompt-blob 1001
             :seon.agent.turn/reply-blob 1002 :seon.agent.turn/llm-retries 0
             :seon.agent.turn/llm-usage "{:prompt 12040 :completion 512}"
             :seon.agent.turn/llm-meta "{:model \"muse-spark-1.1\"}"
             :seon.agent.turn/usage-estimated? false
             :seon.agent.turn/evals [2001 2002 2003] :seon.agent.turn/llm-attempts [3001]})
(def agent-v {:seon.agent/id "root" :seon.agent/namespace 17
              :seon.agent/purpose "system root agent" :seon.agent/run 42
              :seon.agent/parent 1 :seon.agent/default-turn-limit 50
              :seon.agent/schedules [4001 4002]
              :seon.agent/ctx [5001 5002 5003 5004 5005 5006 5007 5008]})
(def steps-v (vec (for [i (range 30)]
                    {:my.plan/id (str "a" (.padStart (str i) 11 "b"))
                     :my.plan/title (str "step " i)
                     :my.plan/status (nth [:open :active :done] (mod i 3))
                     :my.plan/created-at (js/Date.)
                     :my.plan/description (str "do the thing " i)})))
(defn mk-node [depth branch]
  (cond-> {:my.plan/title (str "node-" depth "-" branch)
           :my.plan/description "a plan node with a reasonably long description of intended work"
           :my.plan/expect "observable outcome recorded as database facts"}
    (pos? depth)
    (assoc :my.plan/children (vec (for [b (range branch)]
                                    (mk-node (dec depth) branch))))))
(def plan-v (mk-node 3 4)) ; 85 nodes
(def cases [[:seon.web.brand/brand brand-v] [:seon.agent.turn turn-v]
            [:seon.agent agent-v] [:my.plan/steps steps-v]
            [:my.plan/plan-node plan-v]])

;; 1. compile vs cached
(into {} (map (fn [[k _]] [k (bench 50 #(schema/candidate-validator k))])) cases)
(def validators (into {} (map (fn [[k _]] [k (schema/candidate-validator k)])) cases))
(into {} (map (fn [[k v]] [k (bench 2000 #((get validators k) v))])) cases)
(into {} (map (fn [[k v]] [k (bench 50 #(schema/valid-candidate-value? k v))])) cases)

;; 2. inverted required-key index
(defn map-form? [f] (and (vector? f) (= :map (first f))))
(defn map-entries [f] (let [r (rest f)] (if (map? (first r)) (rest r) r)))
(defn required-attrs [f]
  (into #{} (keep (fn [e] (when (vector? e)
                            (let [[k maybe-opts] e]
                              (when-not (and (map? maybe-opts) (:optional maybe-opts))
                                k)))))
        (map-entries f)))
(defn build-shape-index [forms]
  (let [rows (into {} (keep (fn [[k f]]
                              (when (map-form? f)
                                (let [req (required-attrs f)]
                                  (when (seq req) [k req])))))
                   forms)]
    {:seon.schema.projection/required-by-key rows
     :seon.schema.projection/shape-index
     (reduce-kv (fn [idx k req]
                  (reduce (fn [i a] (update i a (fnil conj #{}) k)) idx req))
                {} rows)}))
(def all-forms (schema/registered-schemas))
(bench 20 #(build-shape-index all-forms))
(def IDX (build-shape-index all-forms))
(defn candidate-keys [{:seon.schema.projection/keys [shape-index required-by-key]} m]
  (let [ks (set (keys m))
        cands (into #{} (mapcat #(get shape-index %)) ks)]
    (into [] (filter (fn [c] (every? ks (get required-by-key c)))) cands)))
(into {} (map (fn [[k v]] [k (when (map? v) (bench 2000 #(candidate-keys IDX v)))])) cases)

;; 3. confirm path + churn
(def FP (:seon.schema.projection/fingerprint (schema/current-projection)))
(defonce !vcache (atom {}))
(defn validator! [k]
  (or (get @!vcache [FP k])
      (let [v (schema/candidate-validator k)] (swap! !vcache assoc [FP k] v) v)))
(defn matching-shapes [idx m]
  (into [] (filter (fn [k] ((validator! k) m))) (candidate-keys idx m)))
(into {} (map (fn [[k v]] (when (map? v) [k (bench 2000 #(matching-shapes IDX v))]))) cases)
(defn churn [mutate?]
  (let [t0 (js/performance.now)]
    (dotimes [i 100]
      (let [v (if mutate?
                (assoc turn-v :seon.agent.turn/prompt-chars (+ 48231 i))
                turn-v)]
        (matching-shapes IDX v)))
    (js/parseFloat (.toFixed (* 10 (- (js/performance.now) t0)) 2))))
[(churn false) (churn true)
 (into {} (map (fn [[k v]] [k (bench 2000 #(hash v))])) cases)]

;; 4. explain + humanize (invalid-only)
(def bad-turn (-> turn-v (dissoc :seon.agent.turn/status)
                 (assoc :seon.agent.turn/prompt-chars "not-an-int")))
(def bad-brand (assoc brand-v :seon.web.brand/name ""))
(def bad-plan (assoc-in plan-v [:my.plan/children 2 :my.plan/children 1 :my.plan/title] 42))
(defonce !ecache (atom {}))
(defn explainer! [k]
  (or (get @!ecache [FP k])
      (let [e (schema/candidate-explainer k)] (swap! !ecache assoc [FP k] e) e)))
(def einvalid [[:seon.agent.turn bad-turn] [:seon.web.brand/brand bad-brand]
               [:my.plan/plan-node bad-plan]])
(into {} (map (fn [[k v]] [k (bench 500 #(me/humanize ((explainer! k) v)))])) einvalid)

;; 5. bounds: full-registry validator precompile + 100-value page morph
(def all-shape-keys (vec (keys (:seon.schema.projection/required-by-key IDX))))
(def t0 (js/performance.now))
(def all-vs (into {} (map (fn [k]
                            [k (try (schema/candidate-validator k)
                                    (catch :default e :compile-error))]))
                  all-shape-keys))
(- (js/performance.now) t0) ; => 97.4 ms, 489 shapes, 0 compile errors
(let [vs (vec (take 100 (cycle [brand-v turn-v agent-v (first steps-v) plan-v])))
      t (js/performance.now)]
  (doseq [v vs] (matching-shapes IDX v))
  (- (js/performance.now) t)) ; => 7.69 ms

```
