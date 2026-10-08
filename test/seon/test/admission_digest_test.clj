(ns seon.test.admission-digest-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest an-admission-confirms-the-tested-digest-without-deriving-the-program
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "admission-revisions")
     (let [input (id/digest 64 [::inputs])]
       (support/transacted!
        connection [{:seon.source/digest
                     (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                     :seon.source/test-input-digest input}])
       (let [database (db/db connection)
             provenance (runner/provenance database)
             request {:seon.test.run/provenance provenance
                      :seon.test.run/cluster [:seon.cluster/name "admission-revisions"]
                      :seon.test.run/input-digest input
                      :seon.test.run/policy :incremental
                      :seon.test.run/include-long? false
                      :seon.test.run/members []}
             result-write [:db/add [:seon.test/sym 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
                           :seon.test/pass-count 1]
             program-write (support/revision database [:seon.fn/sym 'seon.id/id]
                                             {:seon.fn/doc "Changed before admission."})]
         (doseq [[label writes] [[:unchanged []] [:result-write [result-write]]]]
           (testing (name label)
             (let [[after calls]
                   (support/armed-calls
                    #{'seon.test.runner/program-written-since?}
                    #(db/with database (conj writes [:db.fn/call sut/admit-run request])))]
               (is (db/database-value? after))
               (is (some? (db/pull after [:seon.test.run/id]
                                  [:seon.test.run/id (:seon.test.run/id provenance)])))
               (is (zero? (get calls 'seon.test.runner/program-written-since?))))))
         (doseq [recorded [provenance (dissoc provenance :seon.test.run/program-revisions)]]
           (let [refused (db/with database [program-write
                                           [:db.fn/call sut/admit-run
                                            (assoc request :seon.test.run/provenance recorded)]])]
             (is (= :seon.test/program-mismatch (:seon.test/admission-refusal refused))))))))))
