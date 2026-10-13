(ns seon.test.written-since-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest program-written-since-reads-only-the-transactions-after-its-basis
  (support/with-database
   (fn [connection]
     (let [basis (db/basis-t (db/db connection))
           _ (support/transacted! connection [[:db/add [:seon.test/sym 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
                                                :seon.test/pass-count 1]])
           result-written (db/db connection)
           _ (support/transacted! connection [(support/revision result-written [:seon.fn/sym 'seon.id/id]
                                                                {:seon.fn/doc "Changed after the basis."})])
           program-written (db/db connection)]
       (testing "no transaction after the basis"
         (is (false? (runner/program-written-since? program-written (db/basis-t program-written)))))
       (testing "only a test result written after the basis"
         (let [measured (profile/measure #(runner/program-written-since? result-written basis))]
           (is (false? (:seon.profile/value measured)))
           ;; One transaction after the basis: a commit record read, never the program's history.
           (is (< (:seon.profile/ms measured) 20.0))
           (is (< (get-in measured [:seon.cost/jvm-window :seon.cost/alloc-mb]) 20.0))))
       (testing "a function written after the basis"
         (is (true? (runner/program-written-since? program-written basis)))
         (is (true? (runner/program-written-since? program-written (db/basis-t result-written)))))
       (testing "an uncommitted value carrying a program write"
         (is (true? (runner/program-written-since?
                     (db/with result-written [(support/revision result-written [:seon.fn/sym 'seon.id/id]
                                                                {:seon.fn/doc "Changed in the transaction."})])
                     basis))))))))
