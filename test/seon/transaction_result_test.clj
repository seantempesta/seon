(ns seon.transaction-result-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest agent-transactions-return-resolved-changes-and-preserve-refusals
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "transaction-result"})
     (is (:db-after (db/transact! connection
                                 [[:db/add "cluster" :seon.cluster/name "transaction-result"]])))
     (let [ctx (support/fork-cluster-ctx connection "transaction-result")
           created (support/agent-value
                    ctx
                    "(seon.db/transact! [{:db/id \"item\" :my.plan.item/id \"report/item\" :my.plan.item/title \"Verify\"} [:db/add \"item\" :my.plan.item/needs \"datomic.tx\"]])")
           changes (:seon.db/datoms created)
           transaction (:seon.db/tx created)]
       (is (= #{:seon.db/tx :seon.db/datoms} (set (keys created))) (pr-str created))
       (is (integer? transaction))
       (is (some #{[[:my.plan.item/id "report/item"] :my.plan.item/title "Verify" true]} changes))
       (is (some #{[[:my.plan.item/id "report/item"] :my.plan.item/needs transaction true]} changes)
           "the current transaction and the entity tempid are resolved")
       (is (= "Verify" (:my.plan.item/title
                        (db/pull @connection [:my.plan.item/title]
                                 [:my.plan.item/id "report/item"]))))
       (let [removed (support/agent-value
                      ctx "(seon.db/transact! [[:db.fn/retractEntity [:my.plan.item/id \"report/item\"]]])")]
         (is (some #{[[:my.plan.item/id "report/item"] :my.plan.item/id "report/item" false]}
                   (:seon.db/datoms removed))
             "a retracted identity is resolved from the report's before value")
         (is (nil? (db/pull @connection [:my.plan.item/id]
                           [:my.plan.item/id "report/item"]))))
       (let [before (db/basis-t @connection)
             refused (support/agent-value
                      ctx "(seon.db/transact! [[:db/add \"bad\" :my.plan.item/title 42]])")]
         (is (string? (:seon.db.write.attempt/request-id refused)) (pr-str refused))
         (is (= before (db/basis-t @connection))))))))
