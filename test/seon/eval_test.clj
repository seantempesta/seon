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
                :seon.turn/opened-tx "datomic.tx"}]
              (map (fn [[ordinal source]]
                     {:seon.cluster.eval/id source
                      :seon.cluster.eval/at (java.util.Date. timestamp)
                      :seon.cluster.eval/run [:seon.turn/id turn-id]
                      :seon.cluster.eval/ordinal ordinal
                      :seon.cluster.eval/source source}) sources))))
     ;; THE HISTORY IS THE REVERSE OF `:seon.turn/agent`: a turn that names
     ;; its agent is in that agent's history, ordered after its elders.
     (support/transacted!
      connection
      [{:seon.turn/id "a-third"
        :seon.turn/agent [:seon.agent/id "a"]
        :seon.turn/opened-tx "datomic.tx"}
       {:seon.cluster.eval/id "a3"
        :seon.cluster.eval/at (java.util.Date. 3000)
        :seon.cluster.eval/run [:seon.turn/id "a-third"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/source "a3"}])
     (let [basis (db/basis-t @connection)
           history (evaluation/of-agent @connection "a")]
       (is (= ["a0" "a1" "a2" "a3"] (mapv :seon.cluster.eval/source history)))
       (is (= ["b0"] (mapv :seon.cluster.eval/source
                           (evaluation/of-agent @connection "b"))))
       (is (= basis (db/basis-t @connection)))
       (is (every? #(nil? (:seon.cluster.eval/result-edn %)) history))))))
