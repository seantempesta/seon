(ns seon.schema-projection-writer-test
  "Portable committed-row projection and explicit map-shape proofs."
  (:require [clojure.test :refer [deftest is]]
            [seon.schema :as schema]))

(def rows
  {:seon.schema/schema-rows
   #{[:projection.test/id ":int"]
     [:projection.test/shape
      "[:map [:projection.test/id :projection.test/id]]"]}
   :seon.schema/function-contract-rows
   #{["projection.test/read" "[:=> [:cat :projection.test/shape] :int]"]}})

(deftest relation-sets-build-the-same-complete-projection
  (let [from-set (schema/projection-from-rows rows)
        from-vector
        (schema/projection-from-rows
          (update-vals rows vec))]
    (is (= (:seon.schema.projection/fingerprint from-set)
           (:seon.schema.projection/fingerprint from-vector)))
    (is (= #{'projection.test/read}
           (set (keys (:seon.schema.projection/function-contracts from-set)))))))

(deftest explicit-map-status-and-explanation-ignore-process-candidates
  (let [projection (schema/projection-from-rows rows)
        valid {:projection.test/id 1}
        invalid {:projection.test/id "wrong"}]
    (is (= [:projection.test/shape]
           (mapv :seon.schema/key
                 (schema/matching-shapes-in projection valid))))
    (is (= [:projection.test/shape]
           (mapv :seon.schema/key
                 (schema/candidate-shapes-in projection invalid))))
    (is (map? (schema/explain-shape-in projection
                                      :projection.test/shape invalid)))))

(deftest duplicate-and-unresolved-rows-fail-the-complete-build
  (is (thrown? clojure.lang.ExceptionInfo
               (schema/projection-from-rows
                 {:seon.schema/schema-rows
                  [[:projection.test/id ":int"]
                   [:projection.test/id ":string"]]
                  :seon.schema/function-contract-rows []})))
  (is (thrown? clojure.lang.ExceptionInfo
               (schema/projection-from-rows
                 {:seon.schema/schema-rows
                  [[:projection.test/shape
                    "[:map [:projection.test/missing :projection.test/missing]]"]]
                  :seon.schema/function-contract-rows []}))))

