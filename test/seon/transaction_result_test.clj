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
                    "(seon.db/transact! [{:db/id \"item\" :seon.task/id \"reportitem00\" :seon.task/ns (quote fixture.tasks) :seon.task/name \"report\" :seon.task/plan \"Verify\"} {:seon.agent/id \"reporter\" :seon.agent/branch :db :seon.agent/admission-closed-tx \"datomic.tx\"}])")
           changes (:seon.db/datoms created)
           transaction (:seon.db/tx created)]
       (is (= #{:seon.db/tx :seon.db/datoms} (set (keys created))))
       (is (integer? transaction))
       (is (some #{[[:seon.task/id "reportitem00"] :seon.task/plan "Verify" true]} changes))
       (is (some (fn [[_ attribute value added]]
                   (and (= :seon.agent/admission-closed-tx attribute) (= transaction value) added))
                 changes)
           "the current transaction is resolved")
       (is (= "Verify" (:seon.task/plan
                        (db/pull @connection [:seon.task/plan]
                                 [:seon.task/id "reportitem00"]))))
       (let [removed (support/agent-value
                      ctx "(seon.db/transact! [[:db.fn/retractEntity [:seon.task/id \"reportitem00\"]]])")]
         (is (some #{[[:seon.task/id "reportitem00"] :seon.task/id "reportitem00" false]}
                   (:seon.db/datoms removed))
             "a retracted identity is resolved from the report's before value")
         (is (nil? (db/pull @connection [:seon.task/id]
                           [:seon.task/id "reportitem00"]))))
       (let [before (db/basis-t @connection)
             refused (support/agent-value
                      ctx "(seon.db/transact! [[:db/add \"bad\" :seon.task/plan 42]])")]
         (is (string? (:seon.db.write.attempt/request-id refused)))
         (is (= before (db/basis-t @connection))))))))
