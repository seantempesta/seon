(ns seon.schema.program-test
  (:require [clojure.test :refer [deftest is]]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(deftest program-rows-have-one-canonical-persisted-shape
  (let [attributes (set (schema/canonical-database-attributes))]
    (is (= [:enum :io :compute]
           (schema/schema-definition :seon.fn/workload)))
    (is (contains? attributes :seon.fn/workload))
    (is (contains? attributes :seon.ns.require/target))
    (is (contains? attributes :seon.ns.require/refers)))
  (is (schema/valid-candidate-value?
       :seon.fn/fn
       {:seon.fn/sym "sample/f"
        :seon.fn/ns [:seon.ns/name 'sample]
        :seon.fn/source "(defn ^{:seon.workload :io} f [x] x)"
        :seon.fn/arglists "([x])"
        :seon.fn/private? false
        :seon.fn/workload :io}))
  (is (schema/valid-candidate-value?
       :seon.ns/ns
       {:seon.ns/name 'sample
        :seon.ns/source "(ns sample)"
        :seon.ns/require-edges #{}})))
