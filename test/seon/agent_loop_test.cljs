(ns seon.agent-loop-test
  "Pod wake leaves for the claim-native portable driver."
  (:require [cljs.test :refer [deftest is testing]]
            [seon.agent.loop :as loop]))

(deftest pending-inbound-is-an-explicit-unconsumed-edge
  (let [query @#'loop/pending-inbound-query
        where (:where query)]
    (testing "coverage is the durable consumed-input ref, never run closure"
      (is (some
           #{'(not-join [?message]
                       [?run :seon.agent.run/consumed-input ?message])}
           where)))
    (testing "ordering remains deterministic at the message transaction"
      (is (= '[?message-tx :asc ?message :asc] (:order-by query)))
      (is (= 1 (:limit query))))))
