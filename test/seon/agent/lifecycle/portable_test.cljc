(ns seon.agent.lifecycle.portable-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [seon.agent.lifecycle :as lifecycle]
            [seon.agent.lifecycle.core :as core]))

(deftest portable-lifecycle-contract
  (testing "every agent-facing lifecycle entry declares its ruled effect"
    (is (= :external (:seon.capability/effect (meta #'lifecycle/wait))))
    (is (= :idempotent (:seon.capability/effect (meta #'lifecycle/complete))))
    (is (= :external (:seon.capability/effect (meta #'lifecycle/pause))))
    (is (= :external (:seon.capability/effect (meta #'lifecycle/resume))))
    (is (= :idempotent (:seon.capability/effect (meta #'lifecycle/terminate)))))
  (testing "durable lifecycle transactions are pure data"
    (let [at #?(:clj (java.util.Date. 1000) :cljs (js/Date. 1000))]
      (is (= [[:db.fn/cas [:seon.agent/id "a"] :seon.agent/run
               [:seon.agent.run/id "r"] [:seon.agent.run/id "r"]]
              {:seon.agent.run/id "r" :seon.agent.run/status :closed
               :seon.agent.run/closed-reason :waited
               :seon.agent.run/closed-at at}
              [:db/retract [:seon.agent/id "a"] :seon.agent/run]]
             (core/close-tx-data "a" "r" :waited at))))))
