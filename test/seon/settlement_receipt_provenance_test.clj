(ns seon.settlement-receipt-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]
            [seon.turn :as turn]
            [seon.print.value]))

(deftest receipt-created-by-the-write-is-resolved-before-provenance
  (support/with-database
   (fn [connection]
     (let [agent-id "same-write-receipt"
           run-id "same-write-receipt-run"
           ordinal 0
           receipt-id (turn/receipt-identity run-id ordinal)
           receipt-var (ns-resolve 'seon.db '*receipt*)]
       (support/transacted! connection (support/agent-tx @connection agent-id))
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
         (is (some? (:db-after result)) (seon.print.value/bounded-text result))
         (is (= [receipt-id ordinal] provenance)
             "the receipt entity precedes the transaction's provenance ref"))))))

(deftest a-write-during-a-form-opens-its-run-first
  ;; One commit per form writes a source run's entities at its settlement, yet a
  ;; form's own durable writes name its evaluation (the receipt), its run
  ;; (effects, faults, captures). Every write the form makes carries the run's
  ;; open first and the writer applies it once (task c9e0cde3edb3).
  (support/with-database
   (fn [connection]
     (let [agent-id "open-on-first-write"
           run-id "open-on-first-write-run"
           _ (support/transacted! connection (support/agent-tx @connection agent-id))
           namespace-name (db/q '[:find ?name . :in $ ?id :where [?a :seon.agent/id ?id]
                                  [?a :seon.agent/namespace ?n] [?n :seon.ns/name ?name]]
                                @connection agent-id)
           open (#'turn/submission-open-tx
                 {:seon.agent/id agent-id :seon.turn/id run-id :seon.turn/opened-tx "datomic.tx"
                  :seon.turn/starting-ns [:seon.ns/name namespace-name]
                  :seon.turn/sources [{:seon.cluster.eval/source "1"}]})
           receipt [:seon.cluster.eval/id (turn/receipt-identity run-id 0)]
           write! #(with-bindings {(ns-resolve 'seon.db '*receipt*) receipt
                                   (ns-resolve 'seon.db '*open*) open}
                     (db/transact! connection (support/agent-tx @connection %)))
           first-write (write! "open-on-first-write-a")
           second-write (write! "open-on-first-write-b")]
       (is (:db-after first-write) (seon.print.value/bounded-text first-write))
       (is (:db-after second-write) (str "a later write carries the open again; the writer skips it: " (seon.print.value/bounded-text second-write)))
       (is (= 1 (count (db/q '[:find [?run ...] :in $ ?id :where [?run :seon.turn/id ?id]] @connection run-id))))
       (is (= (second receipt)
              (db/q '[:find ?rid . :in $ ?agent-id
                      :where [?agent :seon.agent/id ?agent-id ?tx] [?tx :seon.db/receipt ?r]
                      [?r :seon.cluster.eval/id ?rid]]
                    @connection "open-on-first-write-a"))
           "the first write's provenance names the evaluation its own open wrote")
       (is (= [] (db/absent-call @connection [:seon.turn/id run-id] open))
           "the settlement's open is a no-op once a write opened the run")))))
