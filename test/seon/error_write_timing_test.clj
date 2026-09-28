(ns seon.error-write-timing-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest program-call-changes-still-enter-final-arity-validation
  (support/with-database
   (fn [connection]
     (let [original @#'db/arity-mismatches-with
           calls (atom 0)
           result (with-redefs-fn
                    {#'db/arity-mismatches-with
                     (fn [& args] (swap! calls inc) (apply original args))}
                    #(db/transact! connection
                                   [[:db/add [:seon.fn/sym 'seon.id/id]
                                     :seon.fn/call-arities ['seon.id/id 99]]]))]
       (is (= 1 @calls))
       (is (:seon.db/transaction-refused result))
       (println "ERROR-WRITE-PROGRAM-REFUSAL" (:seon.error/message result))))))

(deftest retention-validates-expanded-writes-and-retained-target-identities
  (support/with-database
   (fn [connection]
     (let [issue [:seon.issue/id "error-write-retention"]
           creator [:seon.agent/id "error-write-creator"]
           worker [:seon.agent/id "error-write-worker"]
           a [:seon.test/sym 'seon.error-test/the-signature-ignores-the-message]
           b [:seon.test/sym 'seon.error-test/normalization-is-total]
           write (fn [actor tx]
                   (db/transact! connection {:tx-data tx :tx-meta {:seon.db/user actor}}))]
       (support/transacted!
        connection
        (into (support/agents-tx @connection [(second creator) (second worker)])
          [{:seon.issue/id (second issue) :seon.issue/title "Retain tests"
            :seon.issue/path "docs/seon/issues/error-write-retention.md"
            :seon.issue/problem "Verify indexed retention at the final writer."
            :seon.issue/status :open :seon.issue/severity :cleanup
            :seon.issue/budget (Integer/valueOf 7)
            :seon.issue/created-by creator :seon.issue/agent worker
            :seon.issue/tests #{a b}}]))
       (is (= 7 (:seon.issue/budget (db/pull (db/db connection) [:seon.issue/budget] issue)))
           "Java integers in actual storage entries still become Datahike longs.")
       (doseq [tx [[[:db.fn/call (fn [_] [[:db/retract issue :seon.issue/tests a]])]]
                   [[:db/retract a :seon.test/sym (second a)]]
                   [[:db/add issue :seon.issue/created-by worker]]]]
         (let [before (db/basis-t (db/db connection))
               refusal (write worker tx)]
           (is (re-find #"Started issue tests" (:seon.error/message refusal "")))
           ;; A returned write refusal, never an unknown outcome.
           (is (:seon.db/transaction-refused refusal))
           (is (not (:seon.db/transaction-outcome-unknown refusal)))
           (is (= before (db/basis-t (db/db connection))))))
       (is (:db-after (write creator [[:db/retract issue :seon.issue/agent worker]])))
       (is (nil? (:db-after (write worker [[:db/retract issue :seon.issue/tests a]]))))
       (is (:db-after (write creator [[:db/retract issue :seon.issue/tests a]])))
       (is (nil? (:db-after (write creator [[:db/retract issue :seon.issue/tests b]]))))))))

(deftest transaction-functions-receive-their-request-object-unchanged
  (support/with-database
   (fn [connection]
     (let [request {:seon.schema/projection (db/carried-projection (db/db connection))
                    ::java-integer (Integer/valueOf 7)}
           received (atom nil)]
       (support/transacted!
        connection
        [[:db.fn/call (fn [_ supplied]
                       (reset! received supplied)
                       [{:seon.issue/id "error-write-function-output"
                         :seon.issue/title "Normalize returned storage data"
                         :seon.issue/problem "The request object stays unchanged."
                         :seon.issue/status :open :seon.issue/severity :cleanup
                         :seon.issue/budget (::java-integer supplied)}]) request]])
       (is (true? (identical? request @received))
           "Transaction-function arguments are in-memory values, not storage data to walk.")
       (is (identical? (::java-integer request) (::java-integer @received)))
       (is (= 7 (:seon.issue/budget
                  (db/pull (db/db connection) [:seon.issue/budget]
                           [:seon.issue/id "error-write-function-output"]))))))))
