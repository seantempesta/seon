(ns seon.receipt-write-carrier-test
  "Regression for receipt provenance on agent-authored database writes."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- evaluation-context
  [connection]
  (let [base-ctx (support/fork-cluster-ctx connection)
        environment (support/environment "receipt-write-carrier-test"
                                         connection)]
    (env/carry-state base-ctx (env/environment-state environment))))

(deftest evaluation-receipt-is-transaction-metadata
  (support/with-database
   (fn [connection]
     (let [agent-id "receipt-writer"
           system-agent-id "system-writer"
           run-id "receipt-write-run"
           ordinal 0
           receipt-id (pr-str [run-id ordinal])
           now (java.util.Date. 1785000000000)]
       (db/transact! connection [{:seon.cluster.agent/id agent-id}])
       (db/transact!
        connection
        (run/open-tx
         {::run/id run-id
          ::run/agent [:seon.cluster.agent/id agent-id]
          ::run/opened-at now}))
       (db/transact!
        connection
        (run/receipt-start-tx
         {::run/id run-id
          :seon.cluster.eval/ordinal ordinal
          :seon.cluster.eval/at now}))
       (let [evaluation
             (sci.eval/evaluate
              {:seon.cluster.run.form/source
               (str
                "(seon.db/transact! "
                (pr-str
                 {:tx-data
                  [{:my.note/id "receipt-carrier"
                    :my.note/agent
                    [:seon.cluster.agent/id agent-id]
                    :my.note/content "written in evaluation"}]
                  :tx-meta
                  {:seon.db/user
                   [:seon.cluster.agent/id agent-id]}})
                ")")
               :seon.sci.eval/ctx (evaluation-context connection)
               :seon.sci.admit/caps caps
               :seon.sci.eval/time-limit-ms 2000
               :seon.config/on-core-error :panic
               :seon.boot/cluster-name "receipt-write-carrier-test"
               :seon.cluster.agent/id agent-id
               :seon.cluster.run/id run-id
               :seon.cluster.run.form/ordinal ordinal})]
         (testing "every write during evaluation names its receipt on the transaction"
           (is (nil? (:seon.cluster.eval/error evaluation)))
           (is (= [receipt-id agent-id]
                  (db/q
                   '[:find [?receipt-id ?agent-id]
                     :in $ ?note-id
                     :where
                     [?note :my.note/id ?note-id]
                     [?note :my.note/content _ ?tx]
                     [?tx :seon.db/receipt ?receipt]
                     [?receipt :seon.cluster.eval/id ?receipt-id]
                     [?tx :seon.db/user ?agent]
                     [?agent :seon.cluster.agent/id ?agent-id]]
                   @connection "receipt-carrier")))))
       (db/transact! connection
                     [{:seon.cluster.agent/id system-agent-id}])
       (testing "a system write outside receipt custody asserts no receipt"
         (is (nil?
              (db/q
               '[:find ?receipt .
                 :in $ ?agent-id
                 :where
                 [?agent :seon.cluster.agent/id ?agent-id ?tx]
                 [?tx :seon.db/receipt ?receipt]]
               @connection system-agent-id))))))))
