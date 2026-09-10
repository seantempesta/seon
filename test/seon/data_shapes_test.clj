(ns seon.data-shapes-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.context-blocks-fixture :as fixture]
            [seon.test-support :as support]))

(defn plan-probe
  "Apply the chart's raw forms to the canonical Juniper plan, without committing."
  [database]
  (let [seed (:db-after
              (d/with database
                      (into (agent/creation-tx
                             {:seon.agent/id "juniper"
                              :seon.ns/name 'my.agents.juniper
                              :seon.cluster/name "data-lane"})
                            [{:seon.agent/id "juniper"
                              :seon.agent/plan (update fixture/authored-plan :my.plan/steps set)}])))
        plan-ref [:my.plan/agent [:seon.agent/id "juniper"]]
        before (d/pull seed '[:db/id {:my.plan/steps [:my.plan.item/id]}] plan-ref)
        complete (d/with seed [[:db/add [:my.plan.item/id "juniper/query"]
                               :my.plan.item/completed-tx "datomic.tx"]])
        add (d/with (:db-after complete)
                    [{:my.plan/agent [:seon.agent/id "juniper"]
                      :my.plan/steps [{:my.plan.item/id "orders/verify"
                                       :my.plan.item/title "Verify the new total"
                                       :my.plan.item/done-when "…"
                                       :my.plan.item/position 6}]}])
        current (d/with (:db-after add)
                        [[:db/add plan-ref :my.plan/current-step
                          [:my.plan.item/id "juniper/aggregate"]]])
        remove (d/with (:db-after current)
                       [[:db.fn/retractEntity [:my.plan.item/id "orders/verify"]]])
        after (d/pull (:db-after remove)
                      '[:db/id {:my.plan/steps [:my.plan.item/id]}
                        {:my.plan/current-step [:my.plan.item/id]}] plan-ref)
        shown (d/pull (:db-after complete)
                      '[{:my.plan.item/completed-tx [:db/txInstant]}]
                      [:my.plan.item/id "juniper/query"])]
    {:seon.test/before before
     :seon.test/after after
     :seon.test/added (d/pull (:db-after add)
                            '[:db/id {:my.plan/steps [:my.plan.item/id]}] plan-ref)
     :seon.test/completed shown
     :seon.test/completed-instant (:v (first (filter #(= :db/txInstant (:a %)) (:tx-data complete))))
     :seon.test/removed (d/pull (:db-after remove) '[*] [:my.plan.item/id "orders/verify"])
     :seon.test/reports
     (into {} (map (fn [[op report]]
                    [op (mapv (fn [datom] [(:e datom) (:a datom) (:v datom)
                                           (:tx datom) (:added datom)])
                              (:tx-data report))]))
           [[:seon.test/complete complete] [:seon.test/add add]
            [:seon.test/current current] [:seon.test/remove remove]])}))

(deftest raw-plan-forms-preserve-the-component-and-use-transaction-time
  (support/with-database
   (fn [connection]
     (let [probe (plan-probe @connection)]
       (is (= 6 (count (get-in probe [:seon.test/before :my.plan/steps]))))
       (is (= 7 (count (get-in probe [:seon.test/added :my.plan/steps]))))
       (is (= 6 (count (get-in probe [:seon.test/after :my.plan/steps]))))
       (is (apply = (map #(get-in probe [% :db/id])
                        [:seon.test/before :seon.test/added :seon.test/after])))
       (is (= "juniper/aggregate"
              (get-in probe [:seon.test/after :my.plan/current-step :my.plan.item/id])))
       (is (nil? (:seon.test/removed probe)))
       (is (inst? (:seon.test/completed-instant probe)))
       (is (= (:seon.test/completed-instant probe)
              (get-in probe [:seon.test/completed :my.plan.item/completed-tx :db/txInstant])))))))
