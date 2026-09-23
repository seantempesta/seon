(ns seon.eval-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.test-support :as support]))

(deftest history-orders-turn-transactions-before-ordinals-and-keeps-unfinished-forms
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agents-tx @connection ["a" "b"]))
     (is (= [] (evaluation/of-agent @connection "a")))
     (doseq [[turn-id timestamp agent-id sources]
             [["z-first" 2000 "a" [[1 "a1"] [0 "a0"]]]
              ["peer" 0 "b" [[0 "b0"]]]
              ["a-second" 1000 "a" [[0 "a2"]]]]]
       (support/transacted!
        connection
        (into [{:seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id]
                :seon.turn/opened-tx "datomic.tx"}
               {:seon.agent/id agent-id
                :seon.agent/runtime {:seon.runtime/agent [:seon.agent/id agent-id]
                                     :seon.runtime/turns [[:seon.turn/id turn-id]]}}]
              (map (fn [[ordinal source]]
                     {:seon.cluster.eval/id source
                      :seon.cluster.eval/at (java.util.Date. timestamp)
                      :seon.cluster.eval/run [:seon.turn/id turn-id]
                      :seon.cluster.eval/ordinal ordinal
                      :seon.cluster.eval/source source}) sources))))
     ;; THE HISTORY IS THE AGENT'S RUNTIME RELATION, not the turn's own
     ;; agent ref. This turn carries a complete `:seon.turn/agent` for "a"
     ;; and is deliberately absent from that agent's `:seon.runtime/turns`,
     ;; so an implementation that walked the reverse edge would show "a3".
     ;; Retracting `:seon.turn/agent` to make the same point left a turn the
     ;; schema refuses, and the writer rejected the whole transaction
     ;; (`bin/test` batch 112, `eval_test.clj:30`): a fixture proves a
     ;; behaviour with complete entities or it proves nothing.
     (support/transacted!
      connection
      [{:seon.turn/id "a-unlinked"
        :seon.turn/agent [:seon.agent/id "a"]
        :seon.turn/opened-tx "datomic.tx"}
       {:seon.cluster.eval/id "a3"
        :seon.cluster.eval/at (java.util.Date. 3000)
        :seon.cluster.eval/run [:seon.turn/id "a-unlinked"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/source "a3"}])
     (let [basis (db/basis-t @connection)
           history (evaluation/of-agent @connection "a")]
       (is (= ["a0" "a1" "a2"] (mapv :seon.cluster.eval/source history)))
       (is (= ["b0"] (mapv :seon.cluster.eval/source
                           (evaluation/of-agent @connection "b"))))
       (is (= basis (db/basis-t @connection)))
       (is (every? #(nil? (:seon.cluster.eval/result-edn %)) history))))))
