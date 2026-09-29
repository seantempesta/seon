(ns seon.receipt-write-carrier-test
  "Regression for receipt provenance on agent-authored database writes."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps config/defaults))

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
       (support/transacted! connection (support/agent-tx @connection agent-id))
       (support/transacted!
               connection
               (turn/open-tx
                {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}))
       (support/transacted!
               connection
               (turn/receipt-start-tx
                {:seon.turn/id run-id
                 :seon.cluster.eval/ordinal ordinal
                 :seon.cluster.eval/at now}))
       (let [evaluation
             (support/evaluate
              {:seon.cluster.eval/source
               (str
                "(seon.db/transact! "
                (pr-str
                 (list 'quote
                       {:tx-data [(support/task-row "receiptcarry" "written in evaluation")]
                        :tx-meta
                        {:seon.db/user
                         [:seon.agent/id agent-id]}}))
                ")")
               :seon.sci.eval/ctx (evaluation-context connection)
               :seon.sci.admit/caps caps
               :seon.sci.eval/time-limit-ms 2000
               :seon.boot/cluster-name "receipt-write-carrier-test"
               :seon.agent/id agent-id
               :seon.turn/id run-id
               :seon.cluster.eval/ordinal ordinal})]
         (testing "every write during evaluation names its receipt on the transaction"
           (is (nil? (:seon.cluster.eval/error evaluation)))
           (is (= [receipt-id agent-id]
                  (db/q
                   '[:find [?receipt-id ?agent-id]
                     :in $ ?task-id
                     :where
                     [?task :seon.task/id ?task-id]
                     [?task :seon.task/intent _ ?tx]
                     [?tx :seon.db/receipt ?receipt]
                     [?receipt :seon.cluster.eval/id ?receipt-id]
                     [?tx :seon.db/user ?agent]
                     [?agent :seon.agent/id ?agent-id]]
                   @connection "receiptcarry")))))
       (support/transacted! connection
                            (support/agent-tx @connection system-agent-id))
       (testing "a system write outside receipt custody asserts no receipt"
         (is (nil?
              (db/q
               '[:find ?receipt .
                 :in $ ?agent-id
                 :where
                 [?agent :seon.agent/id ?agent-id ?tx]
                 [?tx :seon.db/receipt ?receipt]]
               @connection system-agent-id))))))))
