(ns seon.schema.registry-retention-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema :as schema]))

(deftest changed-projection-keeps-only-live-reference-edges
  (let [leaf ::leaf
        dependent ::dependent
        unrelated ::unrelated
        local ::local
        forms {leaf :int
               dependent [:map [::value leaf]]
               unrelated :string
               local [:schema {:registry {::node [:or :string [:vector [:ref ::node]]]}}
                      [:ref ::node]]}
        p1 (schema/build-projection forms)
        p2 (schema/projection-with-schema
            p1 leaf [:int {:min 0}]
            {:seon.schema.admission/source :agent})
        old-registry (:seon.schema.projection/registry p1)
        new-registry (:seon.schema.projection/registry p2)
        retained (mr/schema new-registry unrelated)]
    (is (not (identical? old-registry new-registry)))
    (is (identical? retained (mr/schema old-registry unrelated)))
    (is (= (count forms)
           (count (filter #(and (m/schema? %)
                                (:seon.schema/scoped-registry? (m/options %)))
                          (vals (mr/schemas new-registry))))))
    (is (every? (fn [compiled]
                  (let [captured (:registry (m/options compiled))]
                    (and (not (identical? old-registry captured))
                         (nil? (mr/schema captured unrelated)))))
                (keep (fn [compiled]
                        (when (and (m/schema? compiled)
                                   (:seon.schema/scoped-registry? (m/options compiled)))
                          compiled))
                      (vals (mr/schemas new-registry)))))
    (is (m/validate (mr/schema new-registry dependent) {::value 1}))
    (is (not (m/validate (mr/schema new-registry dependent) {::value -1})))
    (is (m/validate (mr/schema new-registry local) ["a" ["b"]]))))
