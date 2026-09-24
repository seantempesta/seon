(ns seon.sci.acquisition-attributes-test
  (:require [clojure.test :refer [deftest is]]
            [seon.schema :as schema]
            [seon.sci.eval]))

(defn- with-dial
  [projection dial]
  (schema/projection-with-schema projection dial [:string {:seon.config/dial true}]
                                 {:seon.schema.admission/source :agent}))

(deftest colliding-fingerprints-keep-each-projections-acquisition-attributes
  ;; "Aa" and "BB" share a Java string hash, so these unequal populations
  ;; share a projection fingerprint.
  (let [base (schema/handed-projection)
        a (with-dial base :probe/Aa)
        b (with-dial base :probe/BB)
        attributes #'seon.sci.eval/acquisition-attributes
        from-a (attributes a)
        from-b (attributes b)]
    (is (not= (:seon.schema.projection/forms a) (:seon.schema.projection/forms b)))
    (is (= (:seon.schema.projection/fingerprint a) (:seon.schema.projection/fingerprint b))
        "The populations must collide for this regression to test anything.")
    (is (contains? from-a :probe/Aa))
    (is (not (contains? from-a :probe/BB)))
    (is (contains? from-b :probe/BB))
    (is (not (contains? from-b :probe/Aa))
        "A colliding projection must not receive another population's derivation.")
    (is (identical? from-b (attributes b)) "The projection's holder retains its answer.")))
