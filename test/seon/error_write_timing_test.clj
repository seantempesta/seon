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
                       []) request]])
       (is (true? (identical? request @received))
           "Transaction-function arguments are in-memory values, not storage data to walk.")
       (is (identical? (::java-integer request) (::java-integer @received)))))))
