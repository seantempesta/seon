(ns seon.settlement-receipt-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest receipt-created-by-the-write-is-resolved-before-provenance
  (support/with-database
   (fn [connection]
     (let [agent-id "same-write-receipt"
           run-id "same-write-receipt-run"
           ordinal 0
           receipt-id (turn/receipt-identity run-id ordinal)
           receipt-var (ns-resolve 'seon.db '*receipt*)]
       (support/transacted! connection [{:seon.agent/id agent-id}])
       (support/transacted!
        connection
        (turn/open-tx
         {:seon.turn/id run-id
          :seon.turn/agent [:seon.agent/id agent-id]
          :seon.turn/opened-tx "datomic.tx"}))
       (let [before (db/pull @connection [:db/id]
                             [:seon.cluster.eval/id receipt-id])
             transaction
             (turn/receipt-start-tx
              {:seon.turn/id run-id
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at #inst "2026-09-22T10:19:00Z"})
             result
             (with-bindings {receipt-var [:seon.cluster.eval/id receipt-id]}
               (db/transact! connection transaction))
             provenance
             (db/q '[:find [?receipt-id ?ordinal]
                     :in $ ?receipt-id
                     :where
                     [?receipt :seon.cluster.eval/id ?receipt-id]
                     [?receipt :seon.cluster.eval/ordinal ?ordinal ?tx]
                     [?tx :seon.db/receipt ?receipt]]
                   @connection receipt-id)]
         (is (nil? (:db/id before)))
         (is (some? (:db-after result)) (pr-str result))
         (is (= [receipt-id ordinal] provenance)
             "the receipt row precedes the transaction's provenance ref"))))))
