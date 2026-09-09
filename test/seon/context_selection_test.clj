(ns seon.context-selection-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(defn- request
  [agent-id run-id contribution-id]
  {:seon.cluster.agent/id agent-id
   :seon.turn/id run-id
   :seon.context.contribution/id contribution-id})

(defn- append-call
  [agent-id run-id contribution-id]
  [:db.fn/call context/append-tx (request agent-id run-id contribution-id)])

(defn- compact-call
  [agent-id run-id contribution-id expected-evaluations]
  [:db.fn/call context/compact-tx
   (assoc (request agent-id run-id contribution-id)
          :seon.context.contribution/evaluations expected-evaluations)])

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
                          (cond-> {:seon.turn/id run-id
                                   :seon.turn/agent
                                   [:seon.cluster.agent/id agent-id]}
                            closed? (assoc :seon.turn/closed-at
                                           #inst "2026-09-06T00:00:00Z")))
                        runs)
                   (for [[_ run-id _] runs
                         :when (not= "selection-empty" run-id)
                         ordinal [0 1]]
                     (cond-> {:seon.cluster.eval/id (str run-id "-" ordinal)
                              :seon.cluster.eval/run
                              [:seon.turn/id run-id]
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
               (context/selection @connection "missing")))))
     (let [before-evaluations (db/q '[:find (count ?e) .
                                      :where [?e :seon.cluster.eval/id]] @connection)
           remove-request {:seon.cluster.agent/id "selection-a"
                           :seon.context.contribution/id "chosen-1"}
           foreign (db/transact!
                    connection
                    [[:db.fn/call context/remove-tx
                      (assoc remove-request :seon.cluster.agent/id "selection-b")]])]
       (is (= :seon.context/foreign-contribution (:seon.context/selection-refused foreign)))
       (is (nil? (:seon.error/kind
                  (db/transact! connection [[:db.fn/call context/remove-tx remove-request]]))))
       (is (nil? (:seon.error/kind
                  (db/transact! connection [[:db.fn/call context/remove-tx remove-request]]))))
       (is (= ["chosen-2"] (mapv :seon.context.contribution/id
                                 (context/selection @connection "selection-a"))))
       (is (= before-evaluations
              (db/q '[:find (count ?e) . :where [?e :seon.cluster.eval/id]] @connection)))))))

(deftest compact-replaces-only-observed-evaluation-refs
  (test-support/with-database
   (fn [connection]
     (let [closed-at #inst "2026-09-06T00:00:00Z"
           _ (db/transact!
              connection
              [{:seon.cluster.agent/id "compact-agent"}
               {:seon.turn/id "compact-before"
                :seon.turn/agent
                [:seon.cluster.agent/id "compact-agent"]
                :seon.turn/closed-at closed-at}
               {:seon.turn/id "compact-after"
                :seon.turn/agent
                [:seon.cluster.agent/id "compact-agent"]
                :seon.turn/closed-at closed-at}
               {:seon.ns/name 'compact.context}
               {:seon.cluster.eval/id "compact-before-0"
                :seon.cluster.eval/run
                [:seon.turn/id "compact-before"]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/author :system
                :seon.cluster.eval/source "(identity 1)"
                :seon.cluster.eval/ns [:seon.ns/name 'compact.context]
                :seon.cluster.eval/result-edn "1"}
               {:seon.cluster.eval/id "compact-after-0"
                :seon.cluster.eval/run
                [:seon.turn/id "compact-after"]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/author :system
                :seon.cluster.eval/source "(identity 1)"
                :seon.cluster.eval/ns [:seon.ns/name 'compact.context]
                :seon.cluster.eval/result-edn "2"}])
           _ (db/transact!
              connection
              [(append-call "compact-agent" "compact-before" "compact-choice")])
           before (first (context/selection @connection "compact-agent"))
           expected (:seon.context.contribution/evaluations before)
           comparison
           (context/comparison
            @connection
            (request "compact-agent" "compact-after" "compact-choice"))
           memory-basis (db/basis-t @connection)
           memory-request
           (assoc (request "compact-agent" "not-persisted" "compact-choice")
                  :seon.turn.loop/evaluated-sources
                  [{:seon.cluster.eval/ordinal 0
                    :seon.turn.loop/admitted-form
                    {:seon.cluster.eval/source "(identity 1)"
                     :seon.cluster.eval/ns [:seon.ns/name 'compact.context]}
                    :seon.sci.eval/evaluation
                    {:seon.sci.admit/value 2 :seon.cluster.eval/result-edn "2"}}])
           memory-comparison (context/comparison @connection memory-request)
           _ (is (= memory-basis (db/basis-t @connection)))
           _ (is (= :ready (:seon.context.comparison/status memory-comparison)))
           _ (is (= expected (set (:seon.context.comparison/baseline-evaluations
                                   memory-comparison))))
           _ (is (= (:seon.turn.loop/evaluated-sources memory-request)
                    (:seon.turn.loop/evaluated-sources memory-comparison)))
           _ (is (= :seon.context/unfinished-evaluation
                    (:seon.context/selection-refused
                     (context/comparison
                      @connection
                      (update-in memory-request
                                 [:seon.turn.loop/evaluated-sources 0
                                  :seon.sci.eval/evaluation]
                                 dissoc :seon.cluster.eval/result-edn)))))
           _ (is (= :different-source
                    (:seon.context.comparison/status
                     (context/comparison
                      @connection
                      (assoc-in memory-request
                                [:seon.turn.loop/evaluated-sources 0
                                 :seon.turn.loop/admitted-form :seon.cluster.eval/ns]
                                [:seon.ns/name 'another.context])))))
           committed
           (db/transact!
            connection
            [(compact-call "compact-agent" "compact-after" "compact-choice"
                           expected)])
           after (first (context/selection @connection "compact-agent"))]
       (is (nil? (:seon.error/kind committed)))
       (is (= :ready (:seon.context.comparison/status comparison)))
       (is (= expected
              (set (:seon.context.comparison/baseline-evaluations comparison))))
       (is (= (:seon.context.contribution/position before)
              (:seon.context.contribution/position after)))
       (is (= "compact-after-0"
              (:seon.cluster.eval/id
               (db/pull @connection [:seon.cluster.eval/id]
                        (first (:seon.context.contribution/evaluations after))))))
       (let [basis (db/basis-t @connection)
             stale (db/transact!
                    connection
                    [(compact-call "compact-agent" "compact-before"
                                   "compact-choice" expected)])]
         (is (= :seon.context/stale-contribution
                (:seon.context/selection-refused stale)))
         (is (= basis (db/basis-t @connection)))
         (is (= after (first (context/selection @connection
                                                "compact-agent")))))))))
