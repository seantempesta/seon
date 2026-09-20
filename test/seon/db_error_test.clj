(ns seon.db-error-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest unclassified-writer-exceptions-return-a-complete-write-refusal
  (test-support/with-database
   (fn [connection]
     (test-support/apply-config! connection "writer-refusal"
                                 {:seon.config/on-core-error :record})
     (let [transaction [[:db.fn/call (fn [_] (throw (ex-info "unclassified writer failure" {::evidence 42})))]]
           result (db/transact! connection transaction)]
       (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.db.write/validation-refusal) result))
       (is (= 42 (get-in result [:seon.error/data ::evidence])))
       (is (= transaction (get-in result [:seon.error/data :seon.db.write.attempt/transaction])))
       (is (true? (:seon.db/transaction-outcome-unknown result))))
     (let [projection (schema/handed-projection)
           explicit (test-support/transacted! connection [])
           elided (binding [db/*conn* connection] (db/transact! []))]
       (is ((schema/projection-validator projection :seon.db/transaction-report) explicit))
       (is ((schema/projection-validator projection :seon.db/transaction-result) elided))
       (is (not (contains? elided :db-before)))))))
