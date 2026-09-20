(ns seon.test.published-selection-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test.selection-test :as selection-test]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Three selection decisions (5 s each), two terminal admission writes (5 s each), one source edit (3 s) and fixture acquisition (2 s): named green basis, changed reach with platform obligations, unchanged repeat."
           :seon.test/long-ms 30000}
  published-database-selects-reach-and-reuses-green
  (println "published selection: fixture" (java.time.Instant/now))
  (support/with-database
   (fn [connection]
     (let [inputs (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] (db/db connection))
           digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
           request! (fn []
                      (let [database (db/db connection)]
                        {:seon.db/db database
                         :seon.test.run/policy :incremental
                         :seon.test.run/input-digest inputs
                         :seon.test.run/members []
                         :seon.test.run/provenance
                         {:seon.test.run/id (id/id) :seon.test.run/at (java.util.Date.)
                          :seon.test.run/program-digest (runner/program-digest database)
                          :seon.test.run/published-base-digest digest
                          :seon.test.run/overlay-input-digest inputs
                          :seon.test.run/basis-t (db/basis-t database)
                          :seon.test.run/branch :current-src}}))
           baseline (assoc (request!)
                           :seon.test.run/policy :named
                           :seon.test.run/members
                           (mapv (fn [sym]
                                   {:seon.test.member/symbol sym
                                    :seon.test.member/reasons #{:named}})
                                 ['seon.test.bounds-test/bounds-derive-from-declarations-and-admit-only-the-orchestrator
                                  'seon.id-test/data-shape-and-explicit-length-determine-identity]))]
       ;; Real admission records synthetic terminal evidence for two named
       ;; tests. Establishing a green basis does not require the whole suite.
       (#'selection-test/complete-selection! connection baseline)
       (println "published selection: baseline recorded" (java.time.Instant/now))
       (support/transacted! connection
         [[:db/add [:seon.fn/sym 'seon.test.bounds/silence-seconds]
           :seon.fn/source
           (str (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source]
                                         [:seon.fn/sym 'seon.test.bounds/silence-seconds])) "\n")]])
       (println "published selection: source changed" (java.time.Instant/now))
       (let [changed (#'selection-test/complete-selection! connection (request!))
             reached (filter #(contains? (:seon.test.member/reasons %) :reaches-changed)
                             (:seon.test.run/members changed))]
         (is (contains? (set (map :seon.test.member/symbol reached))
                        'seon.test.bounds-test/bounds-derive-from-declarations-and-admit-only-the-orchestrator)
             (pr-str changed))
         (is (not-any? #(= 'seon.id-test/data-shape-and-explicit-length-determine-identity (:seon.test.member/symbol %))
                       (:seon.test.run/members changed))))
       (println "published selection: changed reach recorded" (java.time.Instant/now))
       (let [repeat-selection (sut/select (request!))]
         (println "published selection: repeat selected" (java.time.Instant/now))
         (is (empty? (:seon.test.run/members repeat-selection)) (pr-str repeat-selection))
         (is (seq (:seon.test.selection/unchanged repeat-selection)))
         (is (.contains
              (with-out-str
                (runner/print-recorded-tally! (:seon.test.selection/unchanged repeat-selection)))
              "Recorded 0 executed,"))
         (is (not (contains? repeat-selection :seon.test.run/cluster))))))))

(deftest empty-coordinator-stage-executes-zero-tests
  (let [workers (atom [])
        outcome (#'runner/run-parallel-stage! [] (atom {}) {} workers [])]
    (is (empty? @workers))
    (is (empty? (:seon.test.runner/task-results outcome)))
    (is (zero? (get-in outcome [:seon.test.runner/task-summary :seon.test.runner/test-count])))))
