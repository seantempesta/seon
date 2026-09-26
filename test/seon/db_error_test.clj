(ns seon.db-error-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest unclassified-writer-exceptions-return-a-complete-write-refusal
  (test-support/with-database
   (fn [connection]
     (test-support/apply-config! connection "writer-refusal"
                                 {})
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

(defn- refusing-call
  "A transaction function that refuses whatever it is handed."
  [_database _world]
  (throw (ex-info "refused by its transaction function" {})))

(deftest a-refused-write-names-its-transaction-function-never-its-arguments
  ;; Class regression (fix-dial-retirement-adoption 2026-09-26): boot's config
  ;; apply passed the schema projection to its transaction function, and the
  ;; refusal copied the whole transaction. The prepl printed 8.1 MB that could
  ;; not be read back ("Map literal contains duplicate keys"), so the operator
  ;; lost the diagnostic. The caller keeps the arguments; the error names the
  ;; function.
  (test-support/with-database
   (fn [connection]
     (let [world (schema/handed-projection)
           result (db/transact! connection [[:db.fn/call #'refusing-call world]])
           text (pr-str result)]
       (is (= [[:db.fn/call #'refusing-call]]
              (get-in result [:seon.error/data :seon.db.write.attempt/transaction])))
       (is (< (count text) 20000) (str (count text) " chars"))
       (is (= (:seon.error/message result)
              (:seon.error/message (binding [*default-data-reader-fn* tagged-literal] (read-string text))))
           "the printed refusal reads back")))))

