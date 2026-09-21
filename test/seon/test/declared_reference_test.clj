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
           actual (#'program/declared-reference-edges database)
           elapsed (/ (double (- (System/nanoTime) started)) 1e6)
           expected (reduce
                     (fn [edges rule]
                       (let [started (System/nanoTime)
                             result (db/q '[:find ?caller ?target :in $ %
                                            :where (declared-edge ?caller ?target)]
                                          database [rule])]
                         (println "declared reference rule ms="
                                  (/ (double (- (System/nanoTime) started)) 1e6))
                         (into edges result)))
                     #{} @#'program/declared-reference-rules)]
       (println "indexed declared references ms=" elapsed "edges=" (count actual))
       (is (seq expected) "the canonical publication contains declared references")
       (is (= expected actual) "capability, function-owned and data-row rules retain their identities")
       (is (< elapsed 1000.0) "indexed acquisition reads the declared rows, not the entire program")))))
