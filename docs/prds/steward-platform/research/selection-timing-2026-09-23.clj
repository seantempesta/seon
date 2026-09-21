(ns seon.test.selection-timing-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as program]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest ^{:seon.test/fixture-observation "Measures initial published selection over the canonical population. Run: bin/test-fast seon.test.selection-timing-test"
           :seon.test/long "Measures canonical fixture acquisition and initial selection separately."
           :seon.test/long-ms 10000}
  published-selection-phases
  (println "fixture start" (java.time.Instant/now))
  (support/with-database
   (fn [connection]
     (println "fixture acquired" (java.time.Instant/now))
     (let [database (db/db connection)
           inputs (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] database)
           query db/q
           provenance (assoc (with-redefs [db/q (fn [& args]
                                                  (let [started (System/nanoTime)
                                                        result (apply query args)]
                                                    (println "provenance query" (first args)
                                                             "ms=" (/ (double (- (System/nanoTime) started)) 1e6))
                                                    result))]
                               (runner/provenance database))
                             :seon.test.run/branch :current-src)
           provenance (assoc provenance
                             :seon.test.run/published-base-digest (:seon.test.run/program-digest provenance)
                             :seon.test.run/overlay-input-digest inputs)
           operations [#'sut/selection-facts #'sut/green-members
                       #'sut/changed-definition-symbols #'runner/program-digest]
           measured (into {}
                          (map (fn [v]
                                 (let [f @v]
                                   [v (fn [& args]
                                        (let [started (System/nanoTime)
                                              result (apply f args)]
                                          (println (symbol v) "ms="
                                                   (/ (double (- (System/nanoTime) started)) 1e6))
                                          result))]))) operations)
           started (System/nanoTime)
           result (with-redefs-fn measured
                    #(sut/select {:seon.db/db database :seon.test.run/provenance provenance
                                  :seon.test.run/policy :incremental :seon.test.run/members []
                                  :seon.test.run/input-digest inputs}))]
       (println "selection total ms=" (/ (double (- (System/nanoTime) started)) 1e6))
       (is (seq (:seon.test.run/members result)) (pr-str result))
       (let [started (System/nanoTime)]
         (println "declared-reference-edges start" (java.time.Instant/now))
         (flush)
         (let [edges (with-redefs [db/q (fn [& args]
                                          (let [started (System/nanoTime)
                                                result (apply query args)]
                                            (println "declared-reference query" (first args)
                                                     "ms=" (/ (double (- (System/nanoTime) started)) 1e6))
                                            result))]
                       (#'program/declared-reference-edges database))]
           (println "declared-reference-edges ms=" (/ (double (- (System/nanoTime) started)) 1e6)
                    "edges=" (count edges))
           (is (set? edges))))))))
