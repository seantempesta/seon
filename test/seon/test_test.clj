(ns seon.test-test
  "Run admission uses the canonical writer and preserves selected obligations."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(deftest recording-derives-only-missing-admission-at-the-writer
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           [test-symbol namespace-id]
           (first (db/q '[:find ?symbol ?namespace
                           :where [?test :seon.test/sym ?symbol]
                                  [?test :seon.test/ns ?namespace]
                                  [?test :seon.schema.admission/source :core]
                                  [?namespace :seon.schema.admission/source :core]]
                         database))
           _ (is (some? test-symbol) "the canonical population supplies a real test")
           provenance (runner/provenance database)
           completion {:seon.test.run/provenance provenance
                       :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                       :seon.test/run-at (:seon.test.run/at provenance)
                       :seon.test.runner/results
                       [{:seon.test/sym test-symbol
                         :seon.test/pass-count 1
                         :seon.test/fail-count 0
                         :seon.test/error-count 0}]}
           source-of #(get (db/pull (db/db connection)
                                   [:seon.schema.admission/source]
                                   [:seon.test/sym test-symbol])
                           :seon.schema.admission/source)]
       (test-support/transacted!
        connection
        [[:db/add namespace-id :seon.schema.admission/source :agent]
         [:db.fn/call runner/record-tx completion]])
       (is (= :core (source-of)) "recording preserves an existing test's admission")
       (test-support/transacted!
        connection
        [[:db.fn/retractEntity [:seon.test/sym test-symbol]]
         [:db.fn/call runner/record-tx completion]])
       (is (= :agent (source-of))
           "recreation sees namespace provenance at the mid-transaction database")))))

(deftest recording-distinguishes-run-replay-from-a-new-event
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           test-symbol (first (db/q '[:find [?symbol ...]
                                      :where [_ :seon.test/sym ?symbol]] database))
           first-run (runner/provenance database)
           second-run (runner/provenance database)
           completion (fn [run]
                        {:seon.test.run/provenance run
                         :seon.test/run-basis-t (:seon.test.run/basis-t run)
                         :seon.test/run-at (:seon.test.run/at run)
                         :seon.test.run/branch :current-src
                         :seon.db/db database
                         :seon.test.runner/results
                         [{:seon.test/sym test-symbol
                           :seon.test/pass-count 1
                           :seon.test/fail-count 0
                           :seon.test/error-count 0}]})]
       (is (not= (:seon.test.run/id first-run) (:seon.test.run/id second-run)))
       (doseq [run [first-run first-run second-run]]
         (let [result (runner/commit-results! connection (completion run))]
           (is (vector? result) (pr-str result))))
       (is (= #{(:seon.test.run/id first-run) (:seon.test.run/id second-run)}
              (set (db/q '[:find [?id ...] :where [_ :seon.test.run/id ?id]]
                         (db/db connection)))))
       (let [pull db/pull
             read-refusal (pull database [:db/id] [:seon.test.run/id 42])
             basis (db/basis-t (db/db connection))
             refused (with-redefs [db/pull
                                   (fn [database selector entity]
                                     (if (= [:seon.test.run/id (:seon.test.run/id first-run)] entity)
                                       read-refusal
                                       (pull database selector entity)))]
                       (test-support/refusal-data
                        #(runner/commit-results! connection (completion first-run))))]
         (is (= :seon.db/invalid-read (:seon.error/kind read-refusal)))
         (is (= read-refusal refused)
             "a refused read is never evidence of an existing run or an identity collision")
         (is (= basis (db/basis-t (db/db connection)))))
       (let [basis (db/basis-t (db/db connection))
             changed (assoc first-run :seon.test.run/basis-t (inc (:seon.test.run/basis-t first-run)))
             refused (test-support/refusal-data
                      #(runner/commit-results! connection (completion changed)))]
         (is (= :seon.test.run/immutable (:seon.error/kind refused)) (pr-str refused))
         (is (= (:seon.test.run/basis-t first-run)
                (get-in refused [:seon.error/data :seon.error/diagnostic-expected
                                 :seon.test.run/basis-t])))
         (is (= (:seon.test.run/basis-t changed)
                (get-in refused [:seon.error/data :seon.error/diagnostic-offending
                                 :seon.test.run/basis-t])))
         (is (= basis (db/basis-t (db/db connection)))))))))

(deftest overlapping-admissions-reserve-complementary-memberships
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "test-admission")
     (let [database (db/db connection)
           provenance (runner/provenance database)
           symbols (->> (db/q '[:find [?symbol ...]
                                :where [_ :seon.test/sym ?symbol]] database)
                        sort (take 3) (mapv symbol))
           member #(hash-map :seon.test.member/symbol %
                             :seon.test.member/reasons #{:first-run})
           request {:seon.test.run/provenance provenance
                    :seon.test.run/cluster [:seon.cluster/name "test-admission"]
                    :seon.test.run/input-digest (id/digest 64 [:admission :inputs])
                    :seon.test.run/policy :incremental
                    :seon.test.run/include-long? false
                    :seon.test.run/members (mapv member (take 2 symbols))}
           second-id (id/id)
           second-request (-> request
                              (assoc-in [:seon.test.run/provenance :seon.test.run/id] second-id)
                              (assoc :seon.test.run/members (mapv member (drop 1 symbols))))
           report (test-support/transacted!
                   connection
                   [[:db.fn/call runner/admit-run request]
                    [:db.fn/call runner/admit-run second-request]])
           after (:db-after report)
           memberships (fn [run-id attribute]
                         (set (db/q '[:find [?symbol ...]
                                      :in $ ?id ?attribute
                                      :where [?run :seon.test.run/id ?id]
                                             [?run ?attribute ?member]
                                             [?member :seon.test.member/symbol ?symbol]]
                                    after run-id attribute)))]
       (is (= 3 (count symbols)) "The fixture must contain real test identities.")
       (is (= (set (take 2 symbols))
              (memberships (:seon.test.run/id provenance) :seon.test.run/members)))
       (is (= #{(last symbols)} (memberships second-id :seon.test.run/members)))
       (is (= #{(second symbols)} (memberships second-id :seon.test.run/covered-by)))
       (is (= 3 (db/q '[:find (count ?member) .
                        :where [?member :seon.test.member/symbol]] after)))
       (let [replay (test-support/transacted!
                     connection [[:db.fn/call runner/admit-run second-request]])]
         (is (empty? (filter #(or (= "seon.test.run" (namespace (:a %)))
                                 (= "seon.test.member" (namespace (:a %))))
                            (:tx-data replay)))
             "An identical admission replay writes no evidence datoms."))
       (let [before (db/basis-t (db/db connection))
             refused (db/transact! connection
                       [[:db.fn/call runner/admit-run
                         (assoc second-request :seon.test.run/members [])]])]
         (is (= :seon.test.run/immutable (:seon.error/kind refused)) (pr-str refused))
         (is (= before (db/basis-t (db/db connection)))))))))

(deftest admission-refuses-stale-program-and-records-empty-selection
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "test-admission-empty")
     (let [database (db/db connection)
           request {::ignored :not-a-database-attribute
                    :seon.test.run/provenance (runner/provenance database)
                    :seon.test.run/cluster [:seon.cluster/name "test-admission-empty"]
                    :seon.test.run/input-digest (id/digest 64 [:admission :inputs])
                    :seon.test.run/policy :incremental
                    :seon.test.run/include-long? false
                    :seon.test.run/members []}
           before (db/basis-t database)
           refused (db/transact! connection
                     [[:db.fn/call runner/admit-run
                       (assoc-in request [:seon.test.run/provenance :seon.test.run/program-digest]
                                 (id/digest 64 [:different :program]))]])]
       (is (= :seon.test/program-mismatch (:seon.error/kind refused)) (pr-str refused))
       (is (= before (db/basis-t (db/db connection))))
       (let [report (test-support/transacted!
                     connection [[:db.fn/call runner/admit-run request]])
             row (db/pull (:db-after report)
                         [:seon.test.run/selection-tx :seon.test.run/members]
                         [:seon.test.run/id (get-in request [:seon.test.run/provenance :seon.test.run/id])])]
         (is (integer? (get-in row [:seon.test.run/selection-tx :db/id])))
         (is (not (seq (:seon.test.run/members row)))))))))
