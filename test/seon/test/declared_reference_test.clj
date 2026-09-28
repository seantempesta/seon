(ns seon.test.declared-reference-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as program]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Compares indexed declared references with the original Datalog rules over the canonical published population."
           :seon.test/long-ms 10000}
  indexed-declared-references-preserve-rule-results
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           started (System/nanoTime)
           actual (#'program/declared-callers @#'program/read-or-throw! database)
           elapsed (/ (double (- (System/nanoTime) started)) 1e6)
           expected (reduce
                     (fn [edges rule]
                       (let [started (System/nanoTime)
                             result (db/q '[:find ?caller ?target-symbol :in $ %
                                            :where (declared-edge ?caller ?target)
                                                   [?target :seon.fn/sym ?target-symbol]]
                                          database [rule])]
                         (println "declared reference rule ms="
                                  (/ (double (- (System/nanoTime) started)) 1e6))
                         (reduce (fn [by-target [caller target]] (update by-target target (fnil conj #{}) caller))
                                 edges result)))
                     {} (#'program/declared-reference-rules database))]
       (println "declared callers ms=" elapsed "targets=" (count actual))
       (is (seq expected) "the canonical publication contains declared references")
       (is (= expected actual) "capability, function-owned and data-entity rules retain their identities")
       (is (< elapsed 1000.0) "the relation reads the declaring attributes, not the entire program")))))
