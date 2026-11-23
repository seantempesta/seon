(ns seon.schema-stress-test
  "Pure colocated schema generative stress tests (decoupled from `seon.main/start-flow-instance!`)."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]))

(deftest all-colocated-schemas-seed42-generative-stress-test
  (with-test-store [{:keys [conn]}]
    (testing "All co-located schemas in `resources/seon/schemas/*.edn` are multi-segment & seed=42 generatable"
      (let [db @conn
            forms (:forms @schema/base-registry)]
        (is (>= (count forms) 80) (str "Expected >= 80 co-located schemas, got " (count forms)))
        (doseq [[k f] forms]
          (is (schema/multi-segment-schema-key? k) (str "Non-multi-segment key: " k))
          (let [r (schema/validate-schema-registration! db {:key k :form f})]
            (is (:ok? r) (str "Schema failed generator gate: " k))))))))

