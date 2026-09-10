(ns my.plan-api-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
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
       (is (= ["my.plan/add!" "my.plan/update!" "my.plan/complete!" "my.plan/current!"]
              (mapv :seon.fn/sym (take 4 (evaluate "(dir my.plan)")))))
       (is (= item-id (:my.plan.item/id added)))
       (is (= :ready (:my.plan/state added)))
       (is (= "The query returns 90." (:my.plan.item/done-when added)))
       (is (= 9 (:my.plan.item/position
                 (db/pull @connection [:my.plan.item/position] [:my.plan.item/id item-id]))))
       (let [updated (evaluate (pr-str (list 'my.plan/update!
                                            {:my.plan.item/id item-id
                                             :my.plan.item/done-when "The query returns 130."})))
             selected (evaluate (pr-str (list 'my.plan/current! {:my.plan.item/id item-id})))
             completed (evaluate (pr-str (list 'my.plan/complete! {:my.plan.item/id item-id})))]
         (is (= "The query returns 130." (:my.plan.item/done-when updated)))
         (is (= :current (:my.plan/state selected)))
         (is (= :completed (:my.plan/state completed)))
         (is (inst? (get-in completed [:my.plan.item/completed-tx :db/txInstant])))
         (is (= {} (evaluate "(my.plan/current)")))
         (is (= completed (evaluate (pr-str (list 'my.plan/update! {:my.plan.item/id item-id}))))))))))
