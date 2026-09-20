(ns seon.plan-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.plan :as plan]
            [seon.test-support :as support]))

(deftest retracting-a-prerequisite-does-not-make-its-dependent-ready
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "plan-deletion")
      (support/transacted!
       connection
       (agent/creation-tx {:seon.agent/id "plan-deletion"
                           :seon.ns/name 'my.agents.plan-deletion
                           :seon.cluster/name "plan-deletion"}))
      (is (= "prepare"
             (:my.plan.item/id
              (plan/add! {:my.plan.item/id "prepare"
                          :my.plan.item/title "Prepare"}
                         connection "plan-deletion"))))
      (is (= "verify"
             (:my.plan.item/id
              (plan/add! {:my.plan.item/id "verify"
                          :my.plan.item/title "Verify"
                          :my.plan.item/needs #{"prepare"}}
                         connection "plan-deletion"))))
      (is (= ["verify"] (mapv :my.plan.item/id
                              (plan/blocked (db/db connection) "plan-deletion"))))
      (support/transacted! connection
                           [[:db/retractEntity [:my.plan.item/id "prepare"]]])
      (let [database (db/db connection)
            dependent (db/pull database
                               '[:my.plan.item/id (limit :my.plan.item/needs nil)]
                               [:my.plan.item/id "verify"])]
        (is (= "verify" (:my.plan.item/id dependent)))
        (is (= #{"prepare"} (set (:my.plan.item/needs dependent))))
        (is (= ["prepare"] (:my.plan/needs
                            (first (plan/blocked database "plan-deletion")))))
        (is (= [] (plan/ready database "plan-deletion"))))
      (support/transacted! connection
                           [[:db/retract [:my.plan.item/id "verify"]
                             :my.plan.item/needs "prepare"]])
      (is (= ["verify"] (mapv :my.plan.item/id
                              (plan/ready (db/db connection) "plan-deletion")))))))
