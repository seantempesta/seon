(ns ^{:seon.test/platform
       "Moving part: one declaration population per admission, not per node."}
    seon.sci.admit.declaration-population-test
  "The class regression for per-node declaration resolution at the admission seam.

  Admission asks the declarations one question — is this reference value one
  the registry declares admissible by identity alone? With no projection
  supplied on the calling thread, that question re-reads and re-merges all 152
  schema resources from the classpath, ~14 ms each. Asked once per map,
  vector, set, sequence, and record node, it made admission cost linear in
  NODE COUNT: `{:rows [20 small maps]}` resolved the population 22 times and
  took 374 ms, and one ordinary cluster logged 54,884 fallbacks from that one
  line in an hour (2026-08-08). Admission runs on every eval result, every
  effect result, every recorded error value, and every MCP value projection.

  The class is dead when ONE admission performs AT MOST ONE resolution,
  whatever its node count. These tests count reads at the one read seam and
  assert exactly that, so a caller that reintroduces the per-node shape fails
  here rather than as an unexplained CPU load on a live cluster.

  Issue: docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md"
  (:require [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 1000
   :seon.config.eval.result/max-nodes 10000})

(defn- request
  [value]
  {:seon.sci.admit/value value
   :seon.sci.admit/interrupt-fn (fn [] nil)
   :seon.sci.admit/caps caps
   :seon.config/on-core-error :degrade})

(defn- reads-of
  "Schema resource reads performed while calling `thunk`."
  [thunk]
  (let [reads (atom 0)
        read-one @#'schema.edn/read-schema-resource]
    (with-redefs [schema.edn/read-schema-resource
                  (fn [resource] (swap! reads inc) (read-one resource))]
      (thunk)
      @reads)))

(defn- one-population-reads
  []
  (reads-of schema/declaration-population))

(defn- rows
  [n]
  {:rows (mapv (fn [i] {:index i :label (str "row-" i)}) (range n))})

(deftest an-admission-resolves-the-declaration-population-at-most-once
  ;; The identity descriptors resolve their projection functions with
  ;; `requiring-resolve` the first time they are built; warm that so the
  ;; counts below measure the population and nothing else.
  (admit/admit-value (request (rows 1)))
  (let [one (one-population-reads)]
    (testing "one unbound resolution reads every schema resource"
      (is (pos? one)
          "the fallback must actually read resources, or this test is vacuous"))
    (doseq [[label value]
            [["vector of 50" (vec (range 50))]
             ["map of 20 maps" (rows 20)]
             ["map of 100 maps" (rows 100)]
             ["set of 40 maps" (set (:rows (rows 40)))]
             ["lazy sequence of 60 maps" (map (fn [i] {:i i}) (range 60))]
             ["deeply nested maps" (reduce (fn [acc i] {:child acc :i i})
                                           {:leaf true}
                                           (range 6))]]]
      (testing label
        (is (= one (reads-of #(admit/admit-value (request value))))
            (str "admitting a " label
                 " must resolve the declarations ONCE, not once per node"))))))

(deftest an-admission-that-asks-no-identity-question-resolves-nothing
  (admit/admit-value (request (rows 1)))
  (testing "scalars never reach the identity question"
    (doseq [value [42 "a string" :a-keyword 'a-symbol nil true 3.5]]
      (is (zero? (reads-of #(admit/admit-value (request value))))
          (str "admitting " (pr-str value) " must resolve nothing")))))

(deftest a-supplied-projection-is-used-and-resolves-nothing
  (admit/admit-value (request (rows 1)))
  (let [projection (schema/declaration-projection)]
    (testing "with the projection supplied, admission reads no resource at all"
      (is (zero?
           (reads-of
            #(schema/call-with-projection
              projection
              (fn [] (admit/admit-value (request (rows 20)))))))))))
