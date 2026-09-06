(ns seon.context-selection-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(defn- request
  [agent-id run-id contribution-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster.run/id run-id
   :seon.context.contribution/id contribution-id})

(defn- append-call
  [agent-id run-id contribution-id]
  [:db.fn/call context/append-tx (request agent-id run-id contribution-id)])

(deftest selection-references-terminal-evaluations-in-writer-decided-order
  (test-support/with-database
   (fn [connection]
     (let [runs [["selection-a" "selection-first" true]
                 ["selection-a" "selection-second" true]
                 ["selection-b" "selection-foreign" true]
                 ["selection-a" "selection-open" false]
                 ["selection-a" "selection-empty" true]
                 ["selection-a" "selection-pending" true]]
           seed
           (db/transact!
            connection
            (into [{:seon.cluster.agent/id "selection-a"}
                   {:seon.cluster.agent/id "selection-b"}]
                  (concat
                   (map (fn [[agent-id run-id closed?]]
                          (cond-> {:seon.cluster.run/id run-id
                                   :seon.cluster.run/agent
                                   [:seon.cluster.agent/id agent-id]}
                            closed? (assoc :seon.cluster.run/closed-at
                                           #inst "2026-09-06T00:00:00Z")))
                        runs)
                   (for [[_ run-id _] runs
                         :when (not= "selection-empty" run-id)
                         ordinal [0 1]]
                     (cond-> {:seon.cluster.eval/id (str run-id "-" ordinal)
                              :seon.cluster.eval/run
                              [:seon.cluster.run/id run-id]
                              :seon.cluster.eval/ordinal ordinal}
                       (not= "selection-pending" run-id)
                       (assoc :seon.cluster.eval/result-edn (pr-str ordinal)))))))
           _ (is (nil? (:seon.error/kind seed)))
           before-count (db/q '[:find (count ?e) .
                                :where [?e :seon.cluster.eval/id]] @connection)
           committed (db/transact!
                      connection
                      [(append-call "selection-a" "selection-first" "chosen-1")
                       (append-call "selection-a" "selection-second" "chosen-2")
                       (append-call "selection-a" "selection-first" "chosen-1")
                       (append-call "selection-b" "selection-foreign" "chosen-b")])
           selected (context/selection @connection "selection-a")]
       (is (nil? (:seon.error/kind committed)))
       (is (= ["chosen-1" "chosen-2"]
              (mapv :seon.context.contribution/id selected)))
       (is (= [0 1] (mapv :seon.context.contribution/position selected))
           "the second append observes the first within the same transaction")
       (is (= [0] (mapv :seon.context.contribution/position
                        (context/selection @connection "selection-b"))))
       (is (= #{"selection-first-0" "selection-first-1"}
              (set (map #(-> (db/pull @connection [:seon.cluster.eval/id] %)
                             :seon.cluster.eval/id)
                        (:seon.context.contribution/evaluations
                         (first selected)))))
           "every original evaluation is referenced")
       (is (= before-count
              (db/q '[:find (count ?e) .
                      :where [?e :seon.cluster.eval/id]] @connection)))
       (is (= #{:db/id :seon.context.contribution/id
                :seon.context.contribution/agent
                :seon.context.contribution/position
                :seon.context.contribution/evaluations}
              (set (keys (db/pull @connection '[*]
                                 [:seon.context.contribution/id "chosen-1"])))))
       (doseq [[agent-id run-id contribution-id rule]
               [["missing" "selection-first" "bad" :seon.context/no-such-agent]
                ["selection-a" "missing" "bad" :seon.context/no-such-run]
                ["selection-a" "selection-foreign" "bad" :seon.context/foreign-run]
                ["selection-a" "selection-open" "bad" :seon.context/run-open]
                ["selection-a" "selection-empty" "bad" :seon.context/no-evaluations]
                ["selection-a" "selection-pending" "bad"
                 :seon.context/unfinished-evaluation]
                ["selection-a" "selection-second" "chosen-1"
                 :seon.context/contribution-conflict]]]
         (testing (name rule)
           (let [basis (db/basis-t @connection)
                 refused (db/transact!
                          connection
                          [(append-call "selection-a" "selection-first"
                                        "must-roll-back")
                           (append-call agent-id run-id contribution-id)])]
             (is (= rule (:seon.context/selection-refused refused)))
             (is (= basis (db/basis-t @connection)))
             (is (nil? (db/pull @connection [:seon.context.contribution/id]
                                [:seon.context.contribution/id "must-roll-back"])))
             (is (= selected (context/selection @connection "selection-a"))))))
       (is (= :seon.context/no-such-agent
              (:seon.context/selection-refused
               (context/selection @connection "missing"))))))))
