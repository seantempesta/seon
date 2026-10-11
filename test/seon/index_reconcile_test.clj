(ns seon.index-reconcile-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as index]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.config/a-reapplied-manifest-converges-with-zero-operations}}
  indexing-an-existing-population-uses-one-writer
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           identities (mapv #(vector :seon.fn/sym %)
                            (take 100 (sort (db/q '[:find [?sym ...] :where [_ :seon.fn/sym ?sym]] database))))]
       (is (= 100 (count identities)))
       (doseq [n [1 10 100]]
         (let [selected (subvec identities 0 n)
               [result calls]
               (support/armed-calls
                '#{seon.db/transact!}
                #(schema/call-with-projection
                  (db/carried-projection database)
                  (fn []
                    (index/index! {:seon.db/connection connection
                                   :seon.source/database database
                                   :seon.reconcile/adopt-identities (set selected)}))))]
           (is (true? (:seon.reconcile/converged? result)))
           (is (zero? (:seon.reconcile/operations result)))
           (is (empty? (:seon.reconcile/adopt-identities result)))
           (is (= 1 (get calls 'seon.db/transact!)))
           (is (= (index/published-index-entities database selected)
                  (index/published-index-entities (db/db connection) selected)))))))))
