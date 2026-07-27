(ns seon.render.value-writer-test
  "JVM scalar and traversal proof for the portable value-drill owner.

   Schema-aware JVM map parity intentionally remains downstream of the open
   `jvm-value-drill-lacks-committed-schema-projection` issue and the accepted
   projection-admission boundary grounded in research at `6177ae2e`."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.render.value :as value]
            [seon.render.value-projection-fixture :as fixture]
            [seon.schema :as schema]))

(def ^:private limits
  {:seon.config.render/value-max-path-segments 32
   :seon.config.render/value-max-path-bytes 4096
   :seon.config.render/value-max-realized-items 32
   :seon.config.render/value-max-depth 3
   :seon.config.render/value-max-string 80
   :seon.config.render/value-shape-sample 2
   :seon.render.value/page-size 3})

(defn- request
  ([] (request [] 0 limits))
  ([path offset effective-limits]
   {:seon.render.value/path path
    :seon.render.value/offset offset
    :seon.render.value/effective-limits effective-limits}))

(def ^:private empty-projection (schema/build-projection {}))

(defn- drill-value [value request]
  (value/drill-value empty-projection value request))

(def ^:private ordinary-fixtures
  [nil 42 [1 2 3 4]])

(def ^:private ordinary-result-bytes
  ["{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"scalar\", :seon.render.value/truncated? false, :seon.render.value/more? false, :seon.render.value/tree nil, :seon.render.value/schemas []}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"scalar\", :seon.render.value/truncated? false, :seon.render.value/more? false, :seon.render.value/tree 42, :seon.render.value/schemas []}}"
   "{:seon.render.value/ok? true, :seon.render.value/availability :available, :seon.render.value/projection {:seon.render.value/path [], :seon.render.value/offset 0, :seon.render.value/page-size 3, :seon.render.value/summary \"vector 4 items\", :seon.render.value/truncated? true, :seon.render.value/more? true, :seon.render.value/tree {:seon.render.value/kind :vector, :seon.render.value/shown [1 2 3]}, :seon.render.value/schemas []}}"])

(defn- exact-counting-seq [visits]
  (letfn [(step [i]
            (lazy-seq
              (swap! visits inc)
              (cons i (step (inc i)))))]
    (step 0)))

(deftest ordinary-results-have-exact-cross-runtime-bytes
  (is (= ordinary-result-bytes
         (binding [*print-namespace-maps* false]
           (mapv #(pr-str (drill-value % (request)))
                 ordinary-fixtures)))))

(deftest numeric-identity-matches-the-wire-contract
  (doseq [x [0 1 1.5 9007199254740991.0]]
    (is (value/drill-path-segment? x)))
  (doseq [x [-0.0 ##NaN ##Inf ##-Inf]]
    (is (not (value/drill-path-segment? x))))
  (is (value/safe-nonnegative-int? 9007199254740991))
  (is (not (value/safe-nonnegative-int? 9007199254740992N))))

(deftest logical-million-and-infinite-seq-have-source-work-bounds
  (testing "offset plus page plus one sentinel on a logical million items"
    (let [visits (atom 0)
          result (drill-value (take 1000000 (exact-counting-seq visits))
                                    (request [] 2 limits))]
      (is (true? (:seon.render.value/ok? result)))
      (is (= 6 @visits))))
  (testing "offset plus page plus one sentinel on an infinite sequence"
    (let [visits (atom 0)
          result (drill-value (exact-counting-seq visits)
                                    (request [] 2 limits))]
      (is (true? (:seon.render.value/ok? result)))
      (is (= 6 @visits))
      (is (true? (get-in result [:seon.render.value/projection
                                  :seon.render.value/more?]))))))

(deftest platform-native-values-use-an-honest-fixed-marker
  (let [result (drill-value (Object.) (request))]
    (is (= "jvm/Object"
           (get-in result [:seon.render.value/projection
                           :seon.render.value/tree
                           :seon.eval/opaque])))))

(deftest retained-value-admission-is-portable-bounded-and-identity-preserving
  (let [ordinary {:portable/value [1 2 3]}
        shared (vec (range 3000))
        shared-container [shared shared]
        hundred-mb (.repeat "x" 100000000)]
    (is (identical? ordinary (value/admit-retained-value ordinary)))
    (is (identical? shared-container
                    (value/admit-retained-value shared-container)))
    (is (= :seon.eval/weight-cap-exceeded
           (:seon.eval/retained-reason
            (value/admit-retained-value {:portable/payload hundred-mb}))))
    (is (= :seon.eval/node-cap-exceeded
           (:seon.eval/retained-reason
            (value/admit-retained-value (vec (range 5000))))))
    (is (= :seon.eval/unbounded-collection
           (:seon.eval/retained-reason
            (value/admit-retained-value (iterate inc 0)))))
    (is (= :seon.eval/opaque-value
           (:seon.eval/retained-reason
            (value/admit-retained-value (Object.)))))))

(deftest effective-limits-may-only-narrow-each-trusted-component
  (is (value/effective-limits-within? limits limits))
  (doseq [k (keys limits)]
    (let [narrowed (update limits k #(max 1 (dec %)))
          widened (update limits k inc)]
      (is (value/effective-limits-within? narrowed limits) (str k))
      (is (not (value/effective-limits-within? widened limits)) (str k))))
  (is (not (value/effective-limits-within? (dissoc limits
                                                   :seon.render.value/page-size)
                                           limits))))

(deftest schema-aware-map-drills-have-the-exact-portable-bytes
  (let [projection (schema/projection-from-rows fixture/rows)]
    (is (= fixture/expected-fingerprint
           (:seon.schema.projection/fingerprint projection)))
    (is (= fixture/expected-shape-rows-bytes
           (binding [*print-namespace-maps* false]
             (pr-str (vec (vals
                            (:seon.schema.projection/shape-rows projection)))))))
    (is (= fixture/expected-bytes
           (binding [*print-namespace-maps* false]
             (mapv (fn [value request]
                     (pr-str (value/drill-value projection value request)))
                   fixture/values fixture/requests))))))

(deftest nested-qualified-schema-maps-have-the-portable-fingerprint
  (is (= fixture/expected-nested-fingerprint
         (:seon.schema.projection/fingerprint
           (schema/projection-from-rows fixture/nested-rows)))))
