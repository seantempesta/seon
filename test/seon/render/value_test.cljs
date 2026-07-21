(ns seon.render.value-test
  "Behavioral tests for the structural value renderer (`seon.render.value`).

   We pin MECHANISM, not exact strings (the format will keep iterating):
   bounds are respected, paths survive, opaque handles project, lazy seqs
   never over-realize, the drill hint appears iff the view is partial."
  (:require
    [cognitect.transit :as transit]
    [cljs.test :as t :refer [deftest is testing]]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.registry :as mr]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.config :as config]
    [seon.render.value :as v]
    [seon.render.value-projection-fixture :as projection-fixture]
    [seon.schema :as schema]))

(def configuration (config/resolve-config-singleton {}))

(def drill-schema-keys
  [:seon.render.value/path-segment
   :seon.render.value/path
   :seon.render.value/offset
   :seon.render.value/page-size
   :seon.render.value/bounded-vector
   :seon.render.value/bounded-map
   :seon.render.value/bounded-data
   :seon.render.value/operation-limits
   :seon.render.value/effective-limits
   :seon.render.value/limit-normalization-request
   :seon.render.value/drill-request
   :seon.render.value/schema-status
   :seon.render.value/schema-status-row
   :seon.render.value/schema-statuses
   :seon.render.value/explanation
   :seon.render.value/drilled-projection
   :seon.render.value/drill-error
   :seon.render.value/available-result
   :seon.render.value/unavailable-result
   :seon.render.value/failed-result
   :seon.render.value/drill-result])

(deftest public-drill-scalar-predicates
  (let [negative-zero (/ -1 js/Infinity)]
    (testing "safe integer boundaries"
      (doseq [x [0 1 js/Number.MAX_SAFE_INTEGER]]
        (is (v/safe-nonnegative-int? x)))
      (doseq [x [-1 1.5 js/Infinity js/NaN
                 (inc js/Number.MAX_SAFE_INTEGER) negative-zero]]
        (is (not (v/safe-nonnegative-int? x)) (pr-str x)))
      (is (not (v/safe-positive-int? 0)))
      (is (v/safe-positive-int? 1)))
    (testing "the closed scalar path grammar"
      (doseq [x [nil false true 0 1.5 "x" :x 'x]]
        (is (v/drill-path-segment? x) (pr-str x)))
      (doseq [x [negative-zero js/NaN js/Infinity [] '() {} #{}
                 (random-uuid) (js/Date.) (js-obj)]]
        (is (not (v/drill-path-segment? x)) (pr-str x))))))

(deftest public-drill-schema-population-is-pure-closed-data
  (let [forms (select-keys (schema/snapshot) drill-schema-keys)]
    (is (= (set drill-schema-keys) (set (keys forms))))
    (doseq [[k form] forms]
      (is (= form (reader/read-string (pr-str form))) (str k " round trips"))
      (is (not-any? #{:any} (tree-seq coll? seq form)) (str k " has no :any"))
      (is (not-any? #(and (vector? %) (= :maybe (first %)))
                    (tree-seq coll? seq form))
          (str k " has no maybe schema"))
      (is (not-any? fn? (tree-seq coll? seq form))
          (str k " has no function object"))
      (is (not-any? #(and (symbol? %) (namespace %))
                    (tree-seq coll? seq form))
          (str k " has no unresolved application predicate")))
    (is (not (schema/valid-candidate-value?
               :seon.render.value/drill-request
               {:seon.render.value/path []
                :seon.render.value/offset 0
                :seon.render.value/effective-limits
                {:seon.config.render/value-max-path-segments 32
                 :seon.config.render/value-max-path-bytes 4096
                 :seon.config.render/value-max-realized-items 1024
                 :seon.config.render/value-max-depth 3
                 :seon.config.render/value-max-string 80
                 :seon.config.render/value-shape-sample 8
                 :seon.render.value/page-size 8}
                :seon.render.value/unknown true})))
    (is (not (schema/valid-candidate-value?
               :seon.render.value/operation-limits
               {:seon.render.value/page-size nil}))
        "optional means absent, never stored nil")))

(deftest boot-indexed-complete-population-admits-result-literals
  (let [rows (client/index-schemas)
        forms (into {}
                    (map (fn [row]
                           [(:seon.schema/key row)
                            (reader/read-string (:seon.schema/form row))]))
                    rows)
        projection (schema/build-projection forms)]
    (is (= (count rows) (count forms))
        "the exact boot index contains one canonical row per schema")
    (is (every? #(contains? forms %) drill-schema-keys))
    (is (some? projection)
        "the indexed EDN round trip admits as one complete population")
    (doseq [[schema-key value]
            [[:seon.render.value/available-result
              {:seon.render.value/ok? true
               :seon.render.value/availability :available
               :seon.render.value/projection
               {:seon.render.value/path []
                :seon.render.value/offset 0
                :seon.render.value/page-size 1
                :seon.render.value/summary "nil"
                :seon.render.value/truncated? false
                :seon.render.value/more? false
                :seon.render.value/tree nil
                :seon.render.value/schemas []}}]
             [:seon.render.value/unavailable-result
              {:seon.render.value/ok? true
               :seon.render.value/availability :unavailable
               :seon.render.value/projection
               {:seon.render.value/path []
                :seon.render.value/offset 0
                :seon.render.value/page-size 1
                :seon.render.value/summary "unavailable"
                :seon.render.value/truncated? false
                :seon.render.value/more? false
                :seon.render.value/tree nil
                :seon.render.value/schemas []}
               :seon.render.value/recompute? true}]
             [:seon.render.value/failed-result
              {:seon.render.value/ok? false
               :seon/error {:seon.error/message "failed"
                            :seon.error/kind :user-input}}]]]
      (is (true? (m/validate schema-key value
                             {:registry
                              (mr/composite-registry
                                (m/default-schemas)
                                (mr/fast-registry forms))}))
          (str schema-key " exact branch admits")))))

(deftest drill-predicate-contracts-reuse-the-existing-raw-value-boundary
  (doseq [v [#'v/safe-nonnegative-int?
             #'v/safe-positive-int?
             #'v/drill-path-segment?]]
    (let [form (:malli/schema (meta v))]
      (is (not-any? #{:any} (tree-seq coll? seq form)))
      (is (some #{:seon.render.value/value}
                (tree-seq coll? seq form))
          "hostile scalar input reuses the one proven polymorphic boundary"))))

;; Stand-ins for opaque runtime handles (the real ones are datahike's).
(defrecord FakeDB [max-tx max-eid])
(deftype FakeDatom [e a vv]
  ILookup
  (-lookup [_ k] (case k :e e :a a :v vv nil))
  (-lookup [_ k nf] (case k :e e :a a :v vv nf)))

(deftype CountingMap [n visits value-at]
  IMap
  (-dissoc [this _] this)
  ICounted
  (-count [_] n)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(keyword (str "k" i)) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype UncountedMap [n visits value-at]
  IMap
  (-dissoc [this _] this)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(keyword (str "u" i)) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype KeyedCountingMap [n visits key-at value-at]
  IMap
  (-dissoc [this _] this)
  ICounted
  (-count [_] n)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(key-at i) (value-at i)]
                        (entries (inc i))))))]
      (entries 0))))

(deftype ThrowingCountMap [n visits]
  IMap
  (-dissoc [this _] this)
  ICounted
  (-count [_] (throw (js/Error. "count is hostile")))
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (cons [(keyword (str "t" i)) i]
                        (entries (inc i))))))]
      (entries 0))))

(deftype ThrowingLookupMap []
  IMap
  (-dissoc [this _] this)
  ILookup
  (-lookup [_ _] (throw (js/Error. "lookup is hostile")))
  (-lookup [_ _ _] (throw (js/Error. "lookup is hostile"))))

(deftype ThrowingSeqMap []
  IMap
  (-dissoc [this _] this)
  ISeqable
  (-seq [_] (throw (js/Error. "seq is hostile"))))

(deftest shallow-bounded-data-validation-does-not-enumerate-a-container
  (let [visits (atom 0)
        poison (KeyedCountingMap.
                 1000000 visits
                 (fn [_] (throw (js/Error. "shallow validation read a key")))
                 (fn [_] (throw (js/Error. "shallow validation read a value"))))]
    (is (schema/valid-candidate-value? :seon.render.value/bounded-data poison))
    (is (zero? @visits)
        "the shallow map? slot leaves deep bounded validation to its owner")))

(defn- drill-request
  [path offset page-size realized-max]
  {:seon.render.value/path path
   :seon.render.value/offset offset
   :seon.render.value/effective-limits
   {:seon.config.render/value-max-path-segments 32
    :seon.config.render/value-max-path-bytes 4096
    :seon.config.render/value-max-realized-items realized-max
    :seon.config.render/value-max-depth 3
    :seon.config.render/value-max-string 80
    :seon.config.render/value-shape-sample 8
    :seon.render.value/page-size page-size}})

(defn- drill-value [value request]
  (v/drill-value (or (schema/current-projection)
                     (schema/build-projection (schema/snapshot)))
                 value request))

(def ^:private portable-ordinary-fixtures
  [nil 42 [1 2 3 4]])

(def ^:private portable-ordinary-result-bytes
  ["{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"scalar\", :seon.render.value/truncated? false, :seon.render.value/more? false, :seon.render.value/tree nil, :seon.render.value/schemas []}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"scalar\", :seon.render.value/truncated? false, :seon.render.value/more? false, :seon.render.value/tree 42, :seon.render.value/schemas []}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"vector 4 items\", :seon.render.value/truncated? true, :seon.render.value/more? true, :seon.render.value/tree {:seon.render.value/kind :vector, :seon.render.value/shown [1 2 3]}, :seon.render.value/schemas []}}"])

(deftest portable-ordinary-results-have-exact-jvm-parity-bytes
  (let [request (drill-request [] 0 3 32)]
    (is (= portable-ordinary-result-bytes
           (binding [*print-namespace-maps* false]
             (mapv #(pr-str (drill-value % request))
                   portable-ordinary-fixtures))))))

(deftest schema-aware-map-drills-have-the-exact-jvm-parity-bytes
  (let [projection (schema/projection-from-rows projection-fixture/rows)]
    (is (= projection-fixture/expected-fingerprint
           (:seon.schema.projection/fingerprint projection)))
    (is (= projection-fixture/expected-shape-rows-bytes
           (binding [*print-namespace-maps* false]
             (pr-str (vec (vals
                            (:seon.schema.projection/shape-rows projection)))))))
    (let [actual
          (binding [*print-namespace-maps* false]
            (mapv (fn [value request]
                    (pr-str (v/drill-value projection value request)))
                  projection-fixture/values
                  projection-fixture/requests))]
      (doseq [[index expected result]
              (map vector (range) projection-fixture/expected-bytes actual)]
        (is (= expected result)
            (pr-str {:index index
                     :expected-length (count expected)
                     :actual-length (count result)
                     :expected-hash (hash expected)
                     :actual-hash (hash result)}))))))

(deftest nested-qualified-schema-maps-have-the-jvm-parity-fingerprint
  (is (= projection-fixture/expected-nested-fingerprint
         (:seon.schema.projection/fingerprint
           (schema/projection-from-rows projection-fixture/nested-rows)))))

(defn- exact-counting-seq
  [visits poison-at]
  (letfn [(step [i]
            (lazy-seq
              (swap! visits inc)
              (when (= i poison-at)
                (throw (js/Error. "touched poison beyond page budget")))
              (cons i (step (inc i)))))]
    (step 0)))

(declare with-active-projection)

(deftest drill-value-rejects-before-descent-or-realization
  (let [visits (atom 0)
        value (CountingMap. 1000000 visits (constantly :untouched))]
    (doseq [request [nil
                     :not-a-map
                     (drill-request [] 9 8 16)
                     (drill-request (vec (repeat 33 :x)) 0 8 1024)
                     (assoc (drill-request [] 0 8 1024)
                            :seon.render.value/unknown true)]]
      (let [result (drill-value value request)]
        (is (false? (:seon.render.value/ok? result)))
        (is (= :user-input (get-in result [:seon/error :seon.error/kind])))))
    (is (zero? @visits)
        "invalid requests never enumerate or descend into the live value")))

(deftest drill-value-caps-hostile-request-map-admission
  (let [visits (atom 0)
        poison-touches (atom 0)
        request (KeyedCountingMap.
                  1000000 visits
                  (fn [i] (keyword "hostile" (str "k" i)))
                  (fn [i]
                    (if (< i 4)
                      i
                      (do (swap! poison-touches inc)
                          (throw (js/Error. "request admission over-walk"))))))
        result (drill-value :untouched request)]
    (is (false? (:seon.render.value/ok? result)))
    (is (= 4 @visits) "three allowed request keys plus one sentinel")
    (is (zero? @poison-touches))))

(deftest drill-value-descends-only-through-exact-map-and-vector-segments
  (let [value {:safe [{:answer 42}]}
        found (drill-value value
                             (drill-request [:safe 0 :answer] 0 4 32))
        missing (drill-value value
                               (drill-request [:safe 1] 0 4 32))
        seq-path (drill-value '(1 2 3)
                                (drill-request [0] 0 4 32))]
    (is (true? (:seon.render.value/ok? found)))
    (is (= 42 (get-in found [:seon.render.value/projection
                             :seon.render.value/tree])))
    (is (false? (:seon.render.value/ok? missing)))
    (is (false? (:seon.render.value/ok? seq-path))
        "sequences and sets never acquire positional descent semantics")))

(deftest drill-value-turns-hostile-lookup-and-realization-into-closed-failures
  (let [lookup-result (drill-value (ThrowingLookupMap.)
                                     (drill-request [:x] 0 4 32))
        visits (atom 0)
        realization-result
        (drill-value (exact-counting-seq visits 2)
                       (drill-request [] 0 4 32))]
    (is (false? (:seon.render.value/ok? lookup-result)))
    (is (false? (:seon.render.value/ok? realization-result)))
    (is (= 3 @visits)
        "the throwing element is observed, but no later source item is touched")))

(deftest drill-value-sequence-paging-has-an-exact-total-work-bound
  (let [visits (atom 0)
        value (exact-counting-seq visits 8)
        result (drill-value value
                              (drill-request [] 3 4 7))
        projection (:seon.render.value/projection result)]
    (is (true? (:seon.render.value/ok? result)))
    (is (= 8 @visits)
        "offset + page-size + one sentinel are the only source touches")
    (is (= [3 4 5 6]
           (get-in projection [:seon.render.value/tree
                               :seon.render.value/shown])))
    (is (= :seq (get-in projection [:seon.render.value/tree
                                    :seon.render.value/kind])))
    (is (true? (:seon.render.value/more? projection)))
    (is (true? (:seon.render.value/truncated? projection)))
    (is (= [] (:seon.render.value/schemas projection)))))

(deftest drill-value-enforces-the-ruled-1025-touch-ceiling
  (let [visits (atom 0)
        value (exact-counting-seq visits 1025)
        accepted (drill-value value
                                (drill-request [] 1016 8 1024))]
    (is (true? (:seon.render.value/ok? accepted)))
    (is (= 1025 @visits))
    (is (= (vec (range 1016 1024))
           (get-in accepted [:seon.render.value/projection
                             :seon.render.value/tree
                             :seon.render.value/shown]))))
  (let [visits (atom 0)
        refused (drill-value
                               (exact-counting-seq visits 0)
                               (drill-request [] 1017 8 1024))]
    (is (false? (:seon.render.value/ok? refused)))
    (is (zero? @visits))))

(deftest drill-value-map-window-is-bounded-honest-and-nonpageable
  (let [visits (atom 0)
        poison-touches (atom 0)
        value (CountingMap.
                1000000 visits
                (fn [i]
                  (if (< i 5)
                    i
                    (do (swap! poison-touches inc)
                        (throw (js/Error. "map poison beyond sentinel"))))))
        request (drill-request [] 0 4 16)
        first-result (drill-value value request)
        first-tree (get-in first-result [:seon.render.value/projection
                                         :seon.render.value/tree])
        visits-after-first @visits
        second-result (drill-value value request)
        offset-result (drill-value value
                                     (drill-request [] 1 4 16))]
    (is (= 5 visits-after-first)
        "a million-entry map touches one bounded page plus the sentinel")
    (is (= 10 @visits)
        "repeating the same stable value repeats the exact bounded work")
    (is (zero? @poison-touches))
    (is (= 4 (count (:seon.render.value/map-entries first-tree))))
    (is (= :more (:seon.render.value/elided-keys first-tree)))
    (is (false? (get-in first-result [:seon.render.value/projection
                                      :seon.render.value/more?]))
        "map omission is honest but never advertised as pageable")
    (is (= (pr-str first-result) (pr-str second-result))
        "the same concrete map and iteration order produce identical bytes")
    (is (false? (:seon.render.value/ok? offset-result)))
    (is (= 10 @visits)
        "nonzero map offset is refused before source entry access")))

(deftest drill-value-never-trusts-or-calls-a-partial-maps-count
  (let [visits (atom 0)
        result (drill-value (ThrowingCountMap. 1000000 visits)
                              (drill-request [] 0 4 16))
        projection (:seon.render.value/projection result)]
    (is (true? (:seon.render.value/ok? result)))
    (is (= 5 @visits))
    (is (= :more (get-in projection [:seon.render.value/tree
                                     :seon.render.value/elided-keys])))
    (is (true? (:seon.render.value/truncated? projection)))
    (is (= [] (:seon.render.value/schemas projection)))))

(deftest bounded-deep-result-validation-rejects-marker-and-index-corruption
  (let [limits (:seon.render.value/effective-limits
                 (drill-request [] 0 1 32))
        good (drill-value {:safe 1}
                            (drill-request [] 0 1 32))
        projection (:seon.render.value/projection good)
        tree (:seon.render.value/tree projection)
        bad-index (assoc-in good
                            [:seon.render.value/projection
                             :seon.render.value/tree
                             :seon.render.value/non-drillable-key-indexes]
                            [0 0])
        unknown-marker (assoc-in good
                                 [:seon.render.value/projection
                                  :seon.render.value/tree
                                  :seon.render.value/unknown]
                                 true)
        poison (js-obj)
        visited (atom [])
        oversized-tree
        {:seon.render.value/kind :vector
         :seon.render.value/shown
         (conj (vec (range 16)) poison)}]
    (is (v/bounded-drill-result? good limits))
    (is (not (v/bounded-drill-result? bad-index limits)))
    (is (not (v/bounded-drill-result? unknown-marker limits)))
    (is (not (binding [v/*bounded-tree-visit!* #(swap! visited conj %)]
               (v/bounded-sampled-tree? oversized-tree limits))))
    (is (not-any? #(identical? poison %) @visited)
        "a poison child one past the collection budget is never visited")
    (is (= tree (:seon.render.value/tree projection)))))

(deftest bounded-result-validation-enforces-path-size-and-frame-fields
  (let [request (drill-request [] 0 4 32)
        limits (:seon.render.value/effective-limits request)
        good (drill-value [1 2] request)
        huge-name (apply str (repeat 5000 "x"))
        huge-keyword (keyword huge-name)
        forged
        [(assoc-in good [:seon.render.value/projection
                         :seon.render.value/path]
                   (vec (repeat 33 :x)))
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/summary]
                   huge-name)
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/page-size]
                   5)
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/offset]
                   31)
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/tree]
                   huge-keyword)
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/path]
                   [huge-name])
         (assoc-in good [:seon.render.value/projection
                         :seon.render.value/schemas]
                   [{:seon.schema/key huge-keyword
                     :seon.schema/entity? false
                     :seon.render.value/status :valid}])
         {:seon.render.value/ok? false
          :seon/error {:seon.error/message "failed"
                       :seon.error/kind huge-keyword}}]]
    (is (v/bounded-drill-result? good limits))
    (doseq [result forged]
      (is (not (v/bounded-drill-result? result limits))))))

(deftest public-deep-validators-are-total-on-hostile-containers
  (let [limits (:seon.render.value/effective-limits
                 (drill-request [] 0 4 32))]
    (doseq [candidate [(ThrowingSeqMap.) (ThrowingLookupMap.)]]
      (is (false? (v/bounded-sampled-tree? candidate limits)))
      (is (false? (v/bounded-drill-result? candidate limits))))))

(deftest drill-result-union-round-trips-through-the-existing-transit-codec
  (let [request (drill-request [] 0 4 32)
        limits (:seon.render.value/effective-limits request)
        available (drill-value [1 2] request)
        projection (:seon.render.value/projection available)
        unavailable {:seon.render.value/ok? true
                     :seon.render.value/availability :unavailable
                     :seon.render.value/projection projection
                     :seon.render.value/recompute? true}
        failed {:seon.render.value/ok? false
                :seon/error {:seon.error/message "retired"
                             :seon.error/kind :unavailable}}
        writer (transit/writer :json)
        reader (transit/reader :json)]
    (doseq [result [available unavailable failed]]
      (is (v/bounded-drill-result? result limits))
      (is (= result (transit/read reader (transit/write writer result)))))))

(deftest drill-schema-validation-and-explanation-run-only-for-complete-slices
  (let [attr :value-test.drill/value
        shape :value-test.drill/shape
        forms {attr :int shape [:map [attr attr]]}
        request (drill-request [] 0 4 32)]
    (with-active-projection
      forms
      (fn []
        (let [matching-calls (atom 0)
              explain-calls (atom 0)
              original-matching schema/matching-shapes-in
              original-explain schema/explain-shape-in]
          (with-redefs [schema/matching-shapes-in
                        (fn [projection value]
                          (swap! matching-calls inc)
                          (original-matching projection value))
                        schema/explain-shape-in
                        (fn [projection schema-key value]
                          (swap! explain-calls inc)
                          (original-explain projection schema-key value))]
            (let [complete (drill-value {attr 1} request)
                  invalid (drill-value {attr "wrong"} request)
                  after-complete @matching-calls
                  after-explain @explain-calls
                  partial (drill-value (range)
                                         request)]
              (is (true? (:seon.render.value/ok? complete)))
              (is (= :valid
                     (get-in complete [:seon.render.value/projection
                                       :seon.render.value/schemas 0
                                       :seon.render.value/status])))
              (is (pos? after-complete))
              (is (= 1 after-explain))
              (is (contains? (:seon.render.value/projection invalid)
                             :seon.render.value/explanation))
              (is (= after-complete @matching-calls)
                  "a sentinel-partial slice performs no schema validation")
              (is (= after-explain @explain-calls)
                  "a sentinel-partial slice performs no schema explanation")
              (is (= [] (get-in partial [:seon.render.value/projection
                                         :seon.render.value/schemas]))))))))))

(deftest drill-result-admits-the-schema-owners-full-candidate-cap
  (let [attr :value-test.drill-cap/value
        shapes (into {}
                     (map (fn [i]
                            [(keyword "value-test.drill-cap" (str "shape-" i))
                             [:map [attr attr]]]))
                     (range schema/shape-candidate-limit))
        forms (assoc shapes attr :int)]
    (with-active-projection
      forms
      (fn []
        (let [candidate-visits (atom 0)
              result (binding [schema/*candidate-visit!*
                               (fn [_] (swap! candidate-visits inc))]
                       (drill-value {attr 1}
                                      (drill-request [] 0 1 32)))
              rows (get-in result [:seon.render.value/projection
                                   :seon.render.value/schemas])]
          (is (true? (:seon.render.value/ok? result)))
          (is (= schema/shape-candidate-limit (count rows)))
          (is (<= @candidate-visits schema/shape-candidate-limit))
          (is (every? #(= :valid (:seon.render.value/status %)) rows)))))))

(deftype HugePrintedRecord [writes]
  IRecord
  IPrintWithWriter
  (-pr-writer [_ writer _]
    (let [chunk (apply str (repeat 1024 "x"))]
      (dotimes [_ 102400]
        (swap! writes + (count chunk))
        (-write writer chunk)))))

(deftype ThrowingPrintedValue []
  IPrintWithWriter
  (-pr-writer [_ _ _]
    (throw (js/Error. "unrelated printer failure"))))

(defn- sampled-map [sampled]
  (into {}
        (map (fn [[k v]]
               [k (if (and (map? v)
                           (contains? v :seon.render.value/map-entries))
                    (sampled-map v)
                    v)]))
        (:seon.render.value/map-entries sampled)))

(defn- with-active-projection [forms body]
  (let [before (schema/snapshot-state)]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (body)
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

;; ============================================================
;; sample — depth + breadth bounds, marker shapes.
;; ============================================================

(deftest small-value-fully-shown
  (testing "a small value samples to itself (no markers)"
    (is (= [1 2 3]
           (:seon.render.value/shown (v/sample configuration [1 2 3] {}))))
    (is (= {:a 1 :b 2}
           (sampled-map (v/sample configuration {:a 1 :b 2} {}))))))

(deftest breadth-bound-on-vectors
  (testing "a wide vector keeps max-items elements + an exact elided tail"
    (let [skel (v/sample configuration (vec (range 100)) {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= 92 (:seon.render.value/elided skel))))))

(deftest breadth-bound-on-maps
  (testing "a wide map keeps max-keys entries + an elided-keys count"
    (let [m    (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
          skel (v/sample configuration m {:max-keys 6})]
      (is (= 6 (count (:seon.render.value/map-entries skel))))
      (is (= 14 (:seon.render.value/elided-keys skel))))))

(deftest million-entry-map-work-is-bounded
  (let [entry-visits  (atom 0)
        child-touches (atom 0)
        poison-touches (atom 0)
        n 1000000
        k 8
        work-budget 32
        value-at (fn [i]
                   (if (< i work-budget)
                     (map (fn [x] (swap! child-touches inc) x) [i])
                     (map (fn [_]
                            (swap! poison-touches inc)
                            (throw (js/Error. "poison beyond map budget")))
                          [i])))
        m (CountingMap. n entry-visits value-at)
        skel (v/sample configuration m {:max-keys k
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget))
        "only the bounded candidate window plus tail sentinel is enumerated")
    (is (<= @child-touches work-budget)
        "only candidate values are recursively sampled")
    (is (zero? @poison-touches)
        "the tail sentinel and every later value remain untouched")
    (is (= k (count (:seon.render.value/map-entries skel))))
    (is (= (- n k) (:seon.render.value/elided-keys skel)))))

(deftest projected-key-index-work-stays-inside-the-map-visit-budget
  (let [entry-visits (atom 0)
        key-writes (atom 0)
        work-budget 16
        child-touches (atom 0)
        m (KeyedCountingMap.
            1000000 entry-visits
            (fn [i]
              (if (even? i)
                (keyword (str "safe" i))
                (HugePrintedRecord. key-writes)))
            (fn [i]
              (map (fn [x] (swap! child-touches inc) x) [i])))
        skel (v/sample configuration m {:max-keys 6
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget))
        "index derivation never revisits the source map")
    (is (<= @child-touches work-budget)
        "only candidate children are sampled")
    (is (zero? @key-writes)
        "retained and discarded hostile key printers remain untouched")
    (is (seq (:seon.render.value/non-drillable-key-indexes skel)))
    (is (every? #(< % (count (:seon.render.value/map-entries skel)))
                (:seon.render.value/non-drillable-key-indexes skel)))))

(deftest uncounted-map-work-is-bounded-with-an-honest-unknown-tail
  (let [entry-visits (atom 0)
        child-touches (atom 0)
        work-budget 12
        m (UncountedMap. 1000000 entry-visits
                         (fn [i]
                           (map (fn [x] (swap! child-touches inc) x) [i])))
        skel (v/sample configuration m {:max-keys 4
                                        :max-map-visits work-budget})]
    (is (<= @entry-visits (inc work-budget)))
    (is (<= @child-touches work-budget))
    (is (= 4 (count (:seon.render.value/map-entries skel))))
    (is (= :more (:seon.render.value/elided-keys skel)))))

(deftest bounded-map-projection-is-deterministic-and-collision-free
  (let [pairs (mapv (fn [i] [(keyword (str "k" i)) {:row/id i}]) (range 1000))
        render #(pr-str (v/sample configuration % {:max-keys 8
                                                   :max-map-visits 32}))
        a (into {} pairs)
        b (into {} (reverse pairs))
        projected-pairs (mapv (fn [i]
                                [(if (even? i)
                                   [(keyword (str "display" i))]
                                   (keyword (str "path" i)))
                                 {:row/id i}])
                              (range 24))
        projected-a (v/sample configuration (into {} projected-pairs)
                              {:max-keys 8 :max-map-visits 24})
        projected-b (v/sample configuration (into {} (reverse projected-pairs))
                              {:max-keys 8 :max-map-visits 24})
        reserved {:seon.render.value/elided-keys 99 :ordinary/value 1}
        reserved-out (v/render-ai configuration "reserved" reserved)]
    (is (= (render a) (render b))
        "ordinary persistent hash-map iteration is insertion-independent")
    (is (= 1 (count (set (repeatedly 25 #(render a))))))
    (is (= (pr-str projected-a) (pr-str projected-b))
        "insertion-equivalent projected keys produce identical bytes")
    (is (= (:seon.render.value/non-drillable-key-indexes projected-a)
           (:seon.render.value/non-drillable-key-indexes projected-b)))
    (is (= (:seon.render.value/non-drillable-key-indexes projected-a)
           (vec (sort (:seon.render.value/non-drillable-key-indexes
                        projected-a)))))
    (is (= reserved (sampled-map (v/sample configuration reserved {})))
        "every user key stays inside the explicit entry collection")
    (is (= (pr-str reserved) reserved-out)
        "the reserved-looking user key does not fabricate a partial view")))

(deftest opaque-and-huge-map-keys-force-safe-bounded-projections
  (let [writes (atom 0)
        opaque-key (HugePrintedRecord. writes)
        opaque-ai (v/render-ai configuration "opaque-key" {opaque-key 1})
        opaque-html (v/render-html-data configuration "opaque-key" {opaque-key 1})
        projected-key (-> opaque-html
                          :seon.render.value/tree
                          :seon.render.value/map-entries
                          first first)
        huge-key (apply str (repeat 1000000 "k"))
        huge-ai (v/render-ai configuration "huge-key" {huge-key 1})]
    (is (zero? @writes) "opaque map-key printers are never invoked")
    (is (str/includes? opaque-ai "partial view"))
    (is (= "seon.render.value-test/HugePrintedRecord"
           (:seon.eval/opaque projected-key)))
    (is (not (identical? opaque-key projected-key))
        "the ordinary HTML projection carries no host-object key")
    (is (zero? @writes))
    (is (< (count huge-ai) 600))
    (is (str/includes? huge-ai "map-key/string"))
    (is (str/includes? huge-ai "partial view"))))

(deftest map-key-drillability-is-output-local-and-honest
  (let [huge (apply str (repeat 1000000 "h"))
        raw-children (atom {})
        source (array-map
                 :safe/keyword 1
                 "short" 2
                 [:collection] 3
                 huge 4
                 -7.5 5
                 false 6)
        skel (v/sample configuration source {:max-keys 5
                                             :max-map-visits 6})
        entries (:seon.render.value/map-entries skel)
        indexes (:seon.render.value/non-drillable-key-indexes skel)
        index-set (set indexes)]
    (is (= indexes (vec (sort indexes)))
        "indexes ascend in final retained output order")
    (is (every? #(< % (count entries)) indexes))
    (doseq [[i [display-key sampled-child]] (map-indexed vector entries)]
      (if (contains? index-set i)
        (is (map? display-key) "a display-only key exposes no path component")
        (do
          (swap! raw-children assoc display-key (get source display-key))
          (is (= sampled-child (get-in source [display-key]))
              "every unmarked displayed key is the exact original lookup key"))))
    (is (seq indexes))
    (is (= @raw-children
           (into {}
                 (keep-indexed
                   (fn [i [k _]]
                     (when-not (contains? index-set i)
                       [k (get source k)])))
                 entries)))
    (is (not (str/includes? (pr-str skel) huge))
        "the unsafe original huge key never enters the returned skeleton")))

(deftest non-finite-and-negative-zero-map-keys-are-display-only
  (doseq [k [js/NaN js/Infinity js/-Infinity (/ -1 js/Infinity)]]
    (let [skel (v/sample configuration {k :child} {})]
      (is (= [0] (:seon.render.value/non-drillable-key-indexes skel)))
      (is (= "map-key/number"
             (get-in skel [:seon.render.value/map-entries 0 0
                           :seon.eval/opaque]))))))

(deftest direct-error-maps-use-ordinary-map-sampling
  (let [error {:seon.error/message "writer unavailable"
               :seon.error/kind :system
               :seon.error/data {:operation :transact}}
        sampled (sampled-map (v/sample configuration error {}))]
    (is (= error sampled))
    (is (not (contains? sampled :seon.db/ok?)))
    (is (not (contains? sampled :seon.db/error)))))

(deftest depth-bound-prunes-nested
  (testing "nesting past max-depth becomes a typed+counted prune marker"
    (let [skel (v/sample configuration {:a {:b {:c {:d 1 :e 2}}}}
                         {:max-depth 3})
          c    (get-in (sampled-map skel) [:a :b :c])]
      (is (= :map (:seon.render.value/pruned c)))
      (is (= 2 (:seon.render.value/count c))))))

(deftest empty-colls-not-pruned-at-depth
  (testing "an empty coll at the depth boundary renders verbatim, not a marker"
    (let [skel (v/sample configuration {:a {:b {:c []}}} {:max-depth 3})
          c (get-in (sampled-map skel) [:a :b :c])]
      (is (= [] (:seon.render.value/shown c))))))

(deftest navigation-paths-preserved
  (testing "a path read off the skeleton resolves on the LIVE value"
    (let [live {:api/results [{:user/id 1 :user/name "John"}
                              {:user/id 2 :user/name "Jane"}]}
          skel (v/sample configuration live {})
          results (:api/results (sampled-map skel))]
      ;; key + index retained → get-in path is identical on both
      (is (= 1 (-> results :seon.render.value/shown first sampled-map :user/id)))
      (is (= "John" (get-in live [:api/results 0 :user/name]))))))

;; ============================================================
;; lazy safety + homogeneity.
;; ============================================================

(deftest lazy-seq-never-over-realized
  (testing "an infinite seq samples to a bounded head + :more, no hang"
    (let [realized (atom 0)
          s        (map (fn [i] (swap! realized inc) i) (range))
          skel     (v/sample configuration s {:max-items 8})]
      (is (= 8 (count (:seon.render.value/shown skel))))
      (is (= :more (:seon.render.value/elided skel)))
      ;; head+1 probe only — never the whole infinite seq
      (is (<= @realized 50)))))

(deftest poisoned-lazy-seq-never-crashes-the-walk
  ;; Regression — T4 D1 pod crash (error-workflow 2026-07-06). An agent eval
  ;; can return a lazy seq that THROWS when forced, e.g. `(keys non-map)` →
  ;; a KeySeq whose -first calls `(key non-map-entry)`. The eval records
  ;; `ok? true` (lazy, unrealized); forcing it in the renderer must NOT
  ;; propagate — a propagated throw is recorded `:core` and CRASHES the pod.
  (testing "sample degrades a throw-on-realize seq to an opaque marker"
    (let [skel (v/sample configuration (keys [[1 2] [3 4]]) {})]
      (is (contains? skel :seon.eval/opaque))
      (is (str/includes? (:seon.eval/opaque skel) "realization threw"))))
  (testing "render-ai NEVER throws on a poisoned value — top / nested / deep"
    (doseq [val [(keys [[1 2] [3 4]])
                 (vals [[1 2]])
                 {:a (map (fn [_] (throw (js/Error. "boom"))) [1 2 3])}
                 {:a {:b (keys [[9 9]])}}]]
      (let [out (v/render-ai configuration "rid" val)]
        (is (string? out))
        (is (str/includes? out "result/rid")))))
  (testing "a normal value still renders verbatim (guard is inert)"
    (is (= "{:a 1, :b [1 2 3]}"
           (v/render-ai configuration "n" {:a 1 :b [1 2 3]})))))

(deftest homogeneous-collection-shows-shared-keys
  (testing "a big collection of uniform maps carries its shared key-set"
    (let [rows (mapv (fn [i] {:seon.fn/name (str "f" i) :seon.fn/arity (mod i 3)})
                     (range 40))
          skel (v/sample configuration rows {:max-items 5})]
      (is (= [:seon.fn/arity :seon.fn/name] (:seon.render.value/shape skel)))
      (is (= 35 (:seon.render.value/elided skel))))))

;; ============================================================
;; opaque handles + long strings.
;; ============================================================

(deftest datahike-db-projects-to-opaque-marker
  (let [skel (v/sample configuration (->FakeDB 42 99) {})]
    (is (= "datahike/DB" (:seon.eval/opaque skel)))
    (is (str/includes? (:seon.eval/summary skel) "max-tx=42"))))

(deftest datom-projects-to-datom-marker
  (let [skel (v/sample configuration (FakeDatom. 42 :user/name "Jane") {})]
    (is (= [42 :user/name "Jane"] (:seon.eval/datom skel)))))

(deftest opaque-handle-nested-in-collection-is-projected
  (testing "an opaque node inside a vector is sanitized, not just a top-level one"
    (let [skel (v/sample configuration [(->FakeDB 7 7) :ok] {})]
      (is (= "datahike/DB" (:seon.eval/opaque (first (:seon.render.value/shown skel))))))))

(deftest opaque-values-never-invoke-arbitrary-printers
  (let [writes (atom 0)
        marker (v/sample configuration (HugePrintedRecord. writes) {})]
    (is (string? (:seon.eval/opaque marker)))
    (is (<= (count (:seon.eval/opaque marker)) 80))
    (is (nil? (:seon.eval/summary marker)))
    (is (zero? @writes)
        "a logical 100 MiB printer is never entered for an opaque value")))

(deftest capped-printer-bounds-ordinary-data-and-propagates-real-failures
  (let [huge (apply str (repeat 1000000 "x"))
        nested {:payload huge :after :still-bounded}
        out (tokens/bounded-pr-str nested 20)]
    (is (<= (count out) 81))
    (is (str/ends-with? out "…"))
    (is (= "…" (tokens/bounded-pr-str nested 0)))
    (is (try
          (tokens/bounded-pr-str (ThrowingPrintedValue.) 20)
          false
          (catch :default e
            (= "unrelated printer failure" (.-message e)))))))

(deftest datom-value-is-sampled-through-the-same-bounds
  (let [payload (apply str (repeat 1000 "x"))
        marker (v/sample configuration (FakeDatom. 42 :demo/value payload)
                         {:max-string 20})
        sampled-value (get-in marker [:seon.eval/datom 2])]
    (is (= 1000 (:seon.render.value/string-len sampled-value)))
    (is (<= (count (:seon.render.value/head sampled-value)) 20))
    (let [rendered (v/render-ai configuration "datom-long"
                                (FakeDatom. 42 :demo/value payload))]
      (is (< (count rendered) 500))
      (is (str/includes? rendered "tokens⟩")))))

(deftest long-string-clipped-with-length
  (let [skel (v/sample configuration (apply str (repeat 300 "x"))
                       {:max-string 80})]
    (is (= 300 (:seon.render.value/string-len skel)))
    (is (<= (count (:seon.render.value/head skel)) 80))))

(deftest huge-named-scalars-never-reach-raw-pr-str
  (let [huge-name (apply str (repeat 1000000 "n"))
        huge-keyword (keyword "demo" huge-name)
        huge-symbol (symbol "demo" huge-name)]
    (doseq [x [huge-keyword huge-symbol]]
      (let [out (v/render-ai configuration "huge-named" x)]
        (is (< (count out) 500))
        (is (str/includes? out "partial view"))))))

;; ============================================================
;; project-plain — the UNBOUNDED reader-safe projection (the read-side net
;; reused by seon.eval/sanitize-result-edn). Opaque → marker, plain
;; survives, full structure preserved (no breadth/depth bound).
;; ============================================================

(deftest project-plain-leaves-plain-data-untouched
  (testing "scalars + plain collections (incl. #inst) survive verbatim, unbounded"
    (let [plain {:a [1 2 3] :b #{:x :y} :c {:d (vec (range 100))}
                 :t #inst "2020-01-01"}]
      (is (= plain (v/project-plain plain)))
      ;; UNbounded — every one of the 100 elements is kept (unlike `sample`)
      (is (= 100 (count (get-in (v/project-plain plain) [:c :d])))))))

(deftest project-plain-projects-opaque-nodes-to-markers
  (testing "a datahike-shaped handle / datom becomes a compact marker"
    (is (= "datahike/DB" (:seon.eval/opaque (v/project-plain (->FakeDB 5 5)))))
    (is (= [1 :user/name "Jo"] (:seon.eval/datom (v/project-plain (FakeDatom. 1 :user/name "Jo")))))))

(deftest project-plain-projects-opaque-nested-in-collections
  (testing "an opaque node nested in a coll is projected; plain siblings survive"
    (let [out (v/project-plain {:keep [1 2] :db (->FakeDB 7 7)})]
      (is (= [1 2] (:keep out)))
      (is (= "datahike/DB" (:seon.eval/opaque (:db out))))
      ;; round-trips through pr-str (the sanitize-result-edn use)
      (is (string? (pr-str out))))))

;; ============================================================
;; render-ai — text composition + the drill hint contract.
;; ============================================================

(deftest prepared-ai-reuses-one-lazy-realization-across-eval-ids
  (testing "preparation owns lazy effects; ID formatting reads immutable data"
    (let [realized       (atom 0)
          raw            (map (fn [i]
                                (swap! realized inc)
                                {:row/id i})
                              (range 2000))
          prepared       (v/prepare-ai {:seon.config/configuration configuration
                                        ::v/value raw})
          after-prepare  @realized
          first-out      (v/format-ai {::v/eval-id "first-id"
                                       ::v/prepared prepared})
          after-first    @realized
          second-out     (v/format-ai {::v/eval-id "second-id"
                                       ::v/prepared prepared})
          after-second   @realized]
      (is (pos? after-prepare) "the raw lazy value was actually sampled")
      (is (< after-prepare 2000) "preparation remains bounded")
      (is (= after-prepare after-first after-second)
          "formatting two allocator candidates performs no further realization")
      (is (str/includes? first-out "partial view"))
      (is (str/includes? first-out "result/first-id"))
      (is (not (str/includes? first-out "result/second-id")))
      (is (str/includes? second-out "partial view"))
      (is (str/includes? second-out "result/second-id"))
      (is (not (str/includes? second-out "result/first-id")))
      (is (every? qualified-keyword? (keys prepared))
          "the prepared contract has only fully namespaced keys"))))

(deftest render-ai-small-value-has-no-hint
  (testing "a fully-shown value renders verbatim, no partial-view hint"
    (let [out (v/render-ai configuration "abc" [1 2 3])]
      (is (= "[1 2 3]" out))
      (is (not (str/includes? out "partial view"))))))

(deftest render-ai-small-deep-renders-whole
  (testing "a small but deep/long value prints VERBATIM — the agent sees the
            real nesting of its own stored data, not {…N keys}/\"…\""
    (let [v   {:name "widget" :stock {:warehouse {:shelf {:bin 42}}}
               :note (apply str (repeat 90 "x"))}
          out (v/render-ai configuration "s2" v)]
      (is (= (pr-str v) out))
      (is (not (str/includes? out "partial view")))
      (is (not (str/includes? out "…"))))))

(deftest render-ai-truncated-names-the-live-var
  (testing "a clipped value points the agent at result/<id> for the whole value"
    (let [out (v/render-ai configuration "xyz123" (vec (range 2000)))]
      (is (str/includes? out "partial view"))
      (is (str/includes? out "result/xyz123"))
      (is (str/includes? out "get-in")))))

(deftest map-elision-keeps-smallest-load-bearing-keys
  (testing "over the key bound, tiny keys (hashes/counts) survive and the bulk
            payload strings are elided — ranked by rendered size, not first-N"
    (let [big  (apply str (repeat 400 "X"))   ; huge payload
          mid  (apply str (repeat 30 "m"))    ; medium filler (> a hash)
          m    (into {:seon.agent.shell/out-blob "c4685deadbeefc4685deadbeef"
                      :seon.agent.shell/err-tokens 17}
                     (concat [[:payload-a big] [:payload-b big] [:payload-c big]]
                             (for [i (range 8)] [(keyword (str "f" i)) mid])))
          out  (v/render-ai configuration "eid1" m)]
      ;; the two tiny load-bearing keys survive
      (is (str/includes? out "c4685deadbeefc4685deadbeef"))
      (is (str/includes? out "err-tokens"))
      ;; the huge payloads are elided (never rendered whole)
      (is (not (str/includes? out big)))
      ;; honest elision marker
      (is (str/includes? out "more keys"))
      ;; every retained key still resolves against the live value (path valid)
      (is (str/includes? out "out-blob")))))

(deftest dominant-string-renders-as-body-not-stub
  (testing "a map whose payload is ONE dominant string (a read function's content)
            renders that string as a bounded BODY BLOCK — many lines, honest
            ⟨N tokens⟩, header keys intact — not a 2-line stub (O1)"
    (let [content (apply str (for [i (range 1 54)]
                               (str " " i "\t# line " i " of the file body\n")))
          env     {:seon.agent.fs/ok? true
                   :seon.agent.fs/path "/testbed/two_bucket.py"
                   :seon.agent.fs/content content
                   :seon.agent.fs/from-line 1
                   :seon.agent.fs/lines-returned 53
                   :seon.agent.fs/total-lines 53
                   :seon.agent.fs/file-sha "f1b6e41cabc123"}
          out     (v/render-ai configuration "yPy-1" env)]
      ;; a real body is shown — not just the first ~80 chars (the old stub
      ;; stopped around line 3; the body now reaches deep into the file)
      (is (str/includes? out "line 30 of the file body"))
      ;; honest truncation marker on the dominant string
      (is (str/includes? out "tokens⟩"))
      ;; header keys survive verbatim next to the body
      (is (str/includes? out "f1b6e41cabc123"))
      (is (str/includes? out ":seon.agent.fs/total-lines 53"))
      ;; recovery handle present (result/<id> + keep + get-in)
      (is (str/includes? out "result/yPy-1"))
      (is (str/includes? out "get-in")))))

(deftest dominant-rule-does-not-fire-on-many-similar-strings
  (testing "a map of several comparably-sized strings has NO dominant payload,
            so each stays inline-clipped — the body-block rule must not fire"
    (let [s   (fn [n] (apply str (repeat 500 (str n))))
          m   {:a (s 1) :b (s 2) :c (s 3)}
          out (v/render-ai configuration "m1" m)]
      ;; no single string is shown as a 500-char body block
      (is (not (str/includes? out (s 1))))
      (is (not (str/includes? out (s 2))))
      ;; still a partial view with the handle
      (is (str/includes? out "result/m1")))))

(deftest render-ai-hint-teaches-durability-promotion
  (testing "a partial view's drill hint names BOTH recovery and the my.blob/put!
            keep idiom when a result id exists"
    (let [out (v/render-ai configuration "keep1" (vec (range 2000)))]
      (is (str/includes? out "partial view"))
      ;; recovery idiom
      (is (str/includes? out "get-in"))
      ;; durability idiom
      (is (str/includes? out "keep:"))
      (is (str/includes? out "my.blob/put! result/keep1")))))

(deftest render-ai-long-string-reports-length
  (let [out (v/render-ai configuration "s1"
                         (apply str (repeat 2000 "x")))]
    (is (str/includes? out "tokens⟩"))
    (is (str/includes? out "result/s1"))))

(deftest render-ai-output-is-bounded
  (testing "even a huge deeply-nested value renders to a small bounded string"
    (let [huge (vec (repeat 500 (into {} (map (fn [i] [(keyword (str "k" i))
                                                       (vec (range 50))])
                                              (range 30)))))
          out  (v/render-ai configuration "big" huge)]
      (is (< (count out) 4000)))))

(deftest render-ai-never-emits-fences-or-backticks
  (testing "output stays valid comment prose (no ``` / ` that break the eval'able context)"
    (let [out (v/render-ai configuration "h"
                           {:a (vec (range 100)) :b "x"})]
      (is (not (str/includes? out "`"))))))

;; ============================================================
;; render-html-data — the U panel DATA CONTRACT.
;; ============================================================

(deftest html-data-contract-shape
  (let [data (v/render-html-data configuration "eid42" (vec (range 100)))]
    (is (= "eid42" (:seon.render.value/eval-id data)))
    (is (true? (:seon.render.value/truncated? data)))
    (is (string? (:seon.render.value/summary data)))
    (is (contains? data :seon.render.value/tree))
    ;; the tree is the same skeleton render-ai emits
    (is (= (v/sample configuration (vec (range 100)) {})
           (:seon.render.value/tree data)))))

(deftest html-data-samples-once-and-returns-the-identical-skeleton
  (let [calls (atom 0)
        skeleton {:seon.render.value/kind :vector
                  :seon.render.value/shown [1]
                  :seon.render.value/elided 1}]
    (with-redefs [v/sample (fn [_ _ _]
                             (swap! calls inc)
                             skeleton)
                  schema/candidate-shapes-in (fn [_ _] [])]
      (let [data (v/render-html-data configuration "one-pass" [1 2])]
        (is (= 1 @calls))
        (is (identical? skeleton (:seon.render.value/tree data)))
        (is (= [] (:seon.render.value/schemas data)))))))

(deftest html-data-schema-status-is-activated-ordered-and-invalid-only
  (let [a :value-test.schema/a
        b :value-test.schema/b
        alpha :value-test.schema/alpha
        beta :value-test.schema/beta
        specific :value-test.schema/specific
        forms {a :string
               b :int
               alpha [:map [a a]]
               beta [:map [a a]]
               specific [:map [a a] [b b]]}]
    (with-active-projection
      forms
      (fn []
        (let [valid (v/render-html-data configuration "valid" {a "yes" b 7})
              invalid (v/render-html-data configuration "invalid"
                                          {a 42 b "wrong"})]
          (is (= [[specific :valid] [alpha :valid] [beta :valid]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas valid))))
          (is (not (contains? valid :seon.render.value/explanation)))
          (is (= [[specific :invalid]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas invalid))))
          (is (map? (get-in invalid [:seon.render.value/explanation
                                     :seon.render.value/humanized])))
          (is (map? (get-in invalid [:seon.render.value/explanation
                                     :seon.render.value/error-value]))))))))

(deftest html-data-every-partial-marker-forbids-validation-and-explanation
  (let [sample-calls (atom 0)
        candidate-calls (atom 0)
        matching-calls (atom 0)
        explainer-calls (atom 0)
        row {:seon.schema/key :value-test.partial/shape
             :seon.schema/entity? false}
        skeletons
        [{:seon.render.value/kind :seq
          :seon.render.value/shown [] :seon.render.value/elided 1}
         {:seon.render.value/map-entries []
          :seon.render.value/elided-keys 1}
         {:seon.render.value/map-entries [[:safe 1]]
          :seon.render.value/non-drillable-key-indexes [0]}
         {:seon.render.value/pruned :map :seon.render.value/count 1}
         {:seon.eval/opaque "host/value"}
         {:seon.eval/datom [1 :value-test.partial/a 2]}
         {:seon.render.value/string-len 100
          :seon.render.value/head "head"}]]
    (with-redefs [v/sample (fn [_ _ _]
                             (let [skeleton (nth skeletons @sample-calls)]
                               (swap! sample-calls inc)
                               skeleton))
                  schema/candidate-shapes-in (fn [_ _]
                                            (swap! candidate-calls inc)
                                            [row])
                  schema/matching-shapes-in (fn [_ _]
                                           (swap! matching-calls inc)
                                           [row])
                  schema/explain-shape-in (fn [_ _ _]
                                         (swap! explainer-calls inc)
                                         {:errors [:unsafe]})]
      (doseq [i (range (count skeletons))]
        (let [data (v/render-html-data configuration (str "partial-" i)
                                       {:value-test.partial/a "value"})]
          (is (true? (:seon.render.value/truncated? data)))
          (is (= [[:value-test.partial/shape :shape-only]]
                 (mapv (juxt :seon.schema/key
                             :seon.render.value/status)
                       (:seon.render.value/schemas data))))
          (is (not (contains? data :seon.render.value/explanation)))))
      (is (= (count skeletons) @sample-calls))
      (is (= (count skeletons) @candidate-calls))
      (is (zero? @matching-calls))
      (is (zero? @explainer-calls)))))

(deftest html-data-million-entry-map-and-schema-work-are-bounded
  (let [shared :k0
        shapes (into {}
                     (map (fn [i]
                            [(keyword "value-test.bound" (str "shape-" i))
                             [:map [shared shared]]]))
                     (range 100))
        forms (assoc shapes shared :int)]
    (with-active-projection
      forms
      (fn []
        (let [entry-visits (atom 0)
              child-touches (atom 0)
              poison-touches (atom 0)
              schema-visits (atom 0)
              value (CountingMap.
                      1000000 entry-visits
                      (fn [i]
                        (if (< i schema/shape-input-key-limit)
                          (map (fn [x] (swap! child-touches inc) x) [i])
                          (map (fn [_]
                                 (swap! poison-touches inc)
                                 (throw (js/Error. "poison beyond budget")))
                               [i]))))
              data (binding [schema/*candidate-visit!*
                             (fn [_] (swap! schema-visits inc))]
                     (v/render-html-data configuration "million" value))]
          (is (<= @entry-visits
                  (+ (inc 32) schema/shape-input-key-limit))
              "sampler head+tail and schema input windows are both bounded")
          (is (zero? @poison-touches)
              "neither bounded pass touches the value beyond its window")
          (is (<= @child-touches 32))
          (is (= schema/shape-candidate-limit @schema-visits))
          (is (= schema/shape-candidate-limit
                 (count (:seon.render.value/schemas data))))
          (is (every? #(= :shape-only
                          (:seon.render.value/status %))
                      (:seon.render.value/schemas data)))
          (is (= (- 1000000
                    (count (get-in data [:seon.render.value/tree
                                         :seon.render.value/map-entries])))
                 (get-in data [:seon.render.value/tree
                               :seon.render.value/elided-keys]))))))))

(deftest html-data-status-is-deterministic-and-activated-only
  (let [before (schema/snapshot-state)
        attr :value-test.activation/value
        shape :value-test.activation/shape
        string-forms {attr :string shape [:map [attr attr]]}
        int-forms {attr :int shape [:map [attr attr]]}]
    (try
      (schema/activate-projection! (schema/build-projection string-forms))
      (let [value-a (into {} [[attr "active"] [:ordinary/x 1]])
            value-b (into {} [[:ordinary/x 1] [attr "active"]])
            p1 (v/render-html-data configuration "same" value-a)]
        (is (= (:seon.render.value/schemas p1)
               (:seon.render.value/schemas
                 (v/render-html-data configuration "same" value-b))))
        (schema/restore! int-forms)
        (is (= (:seon.render.value/schemas p1)
               (:seon.render.value/schemas
                 (v/render-html-data configuration "candidate-only" value-a))))
        (schema/activate-projection! (schema/build-projection int-forms))
        (let [p2 (v/render-html-data configuration "p2" value-a)]
          (is (= :invalid
                 (get-in p2 [:seon.render.value/schemas 0
                             :seon.render.value/status])))
          (is (contains? p2 :seon.render.value/explanation))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

;; ------------------------------------------------------------
;; Explicit-whitespace rendering (transcript-render redesign) — the central
;; capability for surgical edits. Gated by config; default is byte-identical.
;; ------------------------------------------------------------

(deftest visible-whitespace-is-gated-and-central
  (testing "all knobs off (default) → byte-identical passthrough"
    (is (= "a\tb c\n  x "
           (v/visible-whitespace configuration "a\tb c\n  x "))))
  (testing ":visible → every space `·` and every tab `→`"
    (let [configuration (assoc configuration
                               :seon.config.render/whitespace :visible)]
      (is (= "a→b·c"
             (v/visible-whitespace configuration "a\tb c")))))
  (testing ":tabs :arrow alone → tabs `→`, spaces untouched"
    (let [configuration (assoc configuration
                               :seon.config.render/tabs :arrow)]
      (is (= "a→b c"
             (v/visible-whitespace configuration "a\tb c")))))
  (testing ":trailing-ws :dot marks ONLY trailing whitespace"
    (let [configuration (assoc configuration
                               :seon.config.render/trailing-ws :dot)]
      (is (= "a b·\nx"
             (v/visible-whitespace configuration "a b \nx"))
          "interior space kept, trailing space dotted")))
  (testing ":line-numbers prepends a 1-based gutter"
    (let [configuration (assoc configuration
                               :seon.config.render/line-numbers true)]
      (is (= "1  a\n2  b"
             (v/visible-whitespace configuration "a\nb"))))))

(deftest render-ai-string-value-uses-whitespace-view-only-when-active
  (testing "default → string value renders as quoted pr-str (byte-identical)"
    (is (= (pr-str "a\tb")
           (v/render-ai configuration "eidX" "a\tb"))
        "quoted/escaped form, exactly as today"))
  (testing "whitespace active → string value renders RAW bytes with glyphs"
    (let [configuration (assoc configuration
                               :seon.config.render/whitespace :visible)]
      (is (= "a→b" (v/render-ai configuration "eidX" "a\tb"))
          "raw content with tab→ glyph, not the quoted pr-str form"))))
