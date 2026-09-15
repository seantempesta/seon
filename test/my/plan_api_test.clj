(ns my.plan-api-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.plan :as plan]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest plan-writes-use-one-request-map-and-return-the-changed-item
  (support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "plan-api"})
      (support/seed-cluster! connection "plan-api")
      (db/transact! connection
                    (agent/creation-tx {:seon.agent/id "plan-api"
                                        :seon.ns/name 'my.agents.plan-api
                                        :seon.cluster/name "plan-api"}))
      (db/transact! connection
                    [{:my.plan/agent [:seon.agent/id "plan-api"]
                      :my.plan/steps [{:my.plan.item/id "existing"
                                       :my.plan.item/title "Existing"
                                       :my.plan.item/position 8}]}])
      (let [ctx (support/fork-cluster-ctx connection "plan-api")
            forked (sci.eval/fork-for-turn {:seon.sci.eval/ctx ctx
                                            :seon.db/db @connection
                                            :seon.db/connection connection
                                            :seon.agent/id "plan-api"})
            evaluate (fn [source]
                       (let [result (sci.eval/evaluate
                                     {:seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
                                      :seon.db/db @connection :seon.db/connection connection
                                      :seon.agent/id "plan-api"
                                      :seon.cluster.eval/ns [:seon.ns/name 'my.agents.plan-api]
                                      :seon.cluster.eval/source source
                                      :seon.sci.admit/caps (config/result-caps (support/effective-config))
                                      :seon.sci.eval/time-limit-ms 5000
                                      :seon.config/on-core-error :panic})]
                         (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
                         (:seon.sci.admit/value result)))
            item-id (id/id "Verify total")
            added (evaluate "(my.plan/add! {:my.plan.item/title \"Verify total\" :my.plan.item/done-when \"The query returns 90.\"})")]
        (let [declared (db/q '[:find [?symbol ...] :where
                               [?namespace :seon.ns/name my.plan]
                               [?function :seon.fn/ns ?namespace]
                               [?function :seon.fn/private? false]
                               [?function :seon.fn/sym ?symbol]] @connection)]
          (is (seq declared))
          (is (= (set (map symbol declared))
                 (set (map :sym (:functions (evaluate "(dir my.plan)")))))))
        (is (= item-id (:my.plan.item/id added)))
        (is (= :ready (:my.plan/state added)))
        (is (= :ready (:my.plan/state
                       (evaluate (pr-str (list 'my.plan/item {:my.plan.item/id item-id}))))))
        (is (= "The query returns 90." (:my.plan.item/done-when added)))
        (is (= 9 (:my.plan.item/position
                  (db/pull @connection [:my.plan.item/position] [:my.plan.item/id item-id]))))
        (let [updated (evaluate (pr-str (list 'my.plan/update!
                                              {:my.plan.item/id item-id
                                               :my.plan.item/done-when "The query returns 130."})))
              selected (evaluate (pr-str (list 'my.plan/current! {:my.plan.item/id item-id})))
              reread (evaluate (pr-str (list 'my.plan/item {:my.plan.item/id item-id})))
              completed (evaluate (pr-str (list 'my.plan/complete! {:my.plan.item/id item-id})))]
          (is (= "The query returns 130." (:my.plan.item/done-when updated)))
          (is (= :current (:my.plan/state selected)))
          (is (= :current (:my.plan/state reread)))
          (is (= :completed (:my.plan/state completed)))
          (is (inst? (get-in completed [:my.plan.item/completed-tx :db/txInstant])))
          (is (= {} (evaluate "(my.plan/current)")))
          (is (= completed (evaluate (pr-str (list 'my.plan/update! {:my.plan.item/id item-id}))))))))))

(deftest item-and-plan-agree-on-state-depth-and-parent
  (support/with-database
    (fn [connection]
      (is (:db-after
           (db/transact! connection
                         [{:seon.agent/id "plan-reader"
                           :seon.agent/plan
                           {:my.plan/agent [:seon.agent/id "plan-reader"]
                            :my.plan/steps
                            [{:my.plan.item/id "parent" :my.plan.item/title "Parent"
                              :my.plan.item/steps
                              [{:my.plan.item/id "first" :my.plan.item/title "First"}
                               {:my.plan.item/id "second" :my.plan.item/title "Second"
                                :my.plan.item/needs [[:my.plan.item/id "first"]]}]}]}}])))
      (let [database @connection
            view (plan/plan {:seon.db/db database :seon.agent/id "plan-reader"})]
        (is (= #{:open :ready :blocked} (set (map :my.plan/state (:my.plan/steps view)))))
        (is (= 3 (count (:my.plan/steps view))))
        (is (= (:my.plan/steps view)
               (plan/items {:seon.db/db database
                            :my.plan/item-ids (mapv :my.plan.item/id (:my.plan/steps view))})))
        (doseq [step (:my.plan/steps view)]
          (is (= step (plan/item {:seon.db/db database
                                  :my.plan.item/id (:my.plan.item/id step)}))))))))
