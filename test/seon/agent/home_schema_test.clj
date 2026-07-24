(ns seon.agent.home-schema-test
  "Cold-load coverage for agent home namespace schema references."
  (:require [clojure.test :refer [deftest is]]
            [seon.agent.home]
            [seon.schema :as schema]))

(deftest home-loads-the-namespace-identity-before-building-candidates
  (is (= [:symbol {:seon.db/identity true}]
         (get (schema/snapshot) :seon.ns/name)))
  (let [home-candidate
        (select-keys
         (schema/snapshot)
         [:inst
          :seon.ns/name
          :seon.agent.home/latest-successful-ns
          :seon.agent.home/namespace-assignment])]
    (is (map? (schema/build-projection home-candidate)))))
