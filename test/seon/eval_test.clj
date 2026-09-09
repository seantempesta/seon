(ns seon.eval-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.test-support :as support]))

(deftest history-orders-turn-transactions-before-ordinals-and-keeps-unfinished-forms
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.agent/id "a"}
                               {:seon.agent/id "b"}])
     (is (= [] (evaluation/of-agent @connection "a")))
     (doseq [[turn-id timestamp agent-id sources]
             [["z-first" 2000 "a" [[1 "a1"] [0 "a0"]]]
              ["peer" 0 "b" [[0 "b0"]]]
              ["a-second" 1000 "a" [[0 "a2"]]]]]
       (db/transact!
        connection
        (into [{:seon.turn/id turn-id
                :seon.turn/agent [:seon.agent/id agent-id]
                :seon.turn/opened-at (java.util.Date. timestamp)}]
              (map (fn [[ordinal source]]
                     {:seon.cluster.eval/id source
                      :seon.cluster.eval/run [:seon.turn/id turn-id]
                      :seon.cluster.eval/ordinal ordinal
                      :seon.cluster.eval/source source}) sources))))
     (let [basis (db/basis-t @connection)
           history (evaluation/of-agent @connection "a")]
       (is (= ["a0" "a1" "a2"] (mapv :seon.cluster.eval/source history)))
       (is (= ["b0"] (mapv :seon.cluster.eval/source
                           (evaluation/of-agent @connection "b"))))
       (is (= basis (db/basis-t @connection)))
       (is (every? #(nil? (:seon.cluster.eval/result-edn %)) history))))))
