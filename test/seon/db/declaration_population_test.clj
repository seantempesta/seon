(ns ^{:seon.test/platform "Database reads never rebuild a missing declaration projection."}
    seon.db.declaration-population-test
  "Carried values decode normally; missing input never rebuilds declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]
            [seon.print.value]))

(deftest ^{:seon.test/long
           "First canonical fixture acquisition indexes the complete program before testing carried database views and missing-projection refusals."
           :seon.test/long-ms 180000}
  reads-require-their-carried-projection-including-transaction-results
  (test-support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           state (env/environment-state
                  (env/environment
                   {:seon.boot/cluster-name "projection-carriage"
                    :seon.db/connection connection
                    :seon.schema/projection projection}))
           report
           (db/transact!
            connection
            (into (test-support/agent-tx @connection "p1-carriage")
              [(test-support/namespace-entity @connection 'seon.db.carriage)
               [:db.fn/call
                (fn [value]
                  (schema/call-with-projection-state
                   (atom {})
                   (fn []
                     (let [agent-entity (db/pull value [:seon.agent/id]
                                          [:seon.agent/id "p1-carriage"])]
                       [[:db/add [:seon.ns/name 'seon.db.carriage]
                         :seon.ns/doc (:seon.agent/id agent-entity)]]))))]]))
           raw @connection
           database (db/carry-projection-state raw state)
           warnings (java.io.StringWriter.)
           lookup [:seon.agent/id "p1-carriage"]
           namespace-lookup [:seon.ns/name 'seon.db.carriage]]
       (is (some? (:db-after report)) (:seon.error/message report))
       (is (= "p1-carriage"
              (:seon.ns/doc (db/pull (:db-after report) [:seon.ns/doc]
                                    [:seon.ns/name 'seon.db.carriage]))))
       (is (identical? projection (db/carried-projection (:db-before report))))
       (is (identical? projection (db/carried-projection database)))
       (testing "a retained database does not follow a later environment replacement"
         (env/replace-environment!
          state (env/environment {:seon.boot/cluster-name "replacement"}))
         (is (identical? projection (db/carried-projection database))))
       (testing "query find shapes decode identically with supplied and carried projections"
         (let [queries ['[:find [(pull ?e [:seon.ns/name]) ...]
                         :in $ ?name :where [?e :seon.ns/name ?name]]
                        '[:find ?name . :in $ ?name
                          :where [_ :seon.ns/name ?name]]
                        '[:find [?name] :in $ ?name
                          :where [_ :seon.ns/name ?name]]]
               result
               (tc/quick-check
                30
                (prop/for-all [query (gen/elements queries)]
                  (let [supplied (schema/call-with-projection
                                  projection #(db/q query raw 'seon.db.carriage))
                        carried (schema/call-with-projection-state
                                 (atom {}) #(db/q query database 'seon.db.carriage))]
                    (= supplied carried)))
                :seed 20260915)]
           (is (:pass? result) (seon.print.value/bounded-text result))))
       ;; Acquire the shipped print grammar before probing running-path work.
       ;; Its first-use validator is independent of the database projection.
       (is (= 0 (:seon.sci.admit/value
                 (admit/admit-value
                  {:seon.sci.admit/value 0
                   :seon.sci.admit/caps {}
                   :seon.sci.admit/unbounded? true
                   :seon.sci.admit/interrupt-fn (fn [])
                   :seon.schema/projection projection}))))
       ;; The armed per-Var counters observe rebuilds and resource reads (the
       ;; worker thread's included); no Var is redefined.
       (let [[_ calls]
             (test-support/armed-calls
              '#{seon.schema/projection-from-database seon.schema.edn/read-schema-resource}
              (fn []
         (testing "all read values retain the supplied projection without a thread carrier"
           (schema/call-with-projection-state
            (atom {})
            (fn []
              (is (= (db/database-value-identity database)
                     (:seon.sci.admit/value
                      (admit/admit-value
                       {:seon.sci.admit/value database
                        :seon.sci.admit/caps {}
                        :seon.sci.admit/unbounded? true
                        :seon.sci.admit/interrupt-fn (fn [])}))))
              (doseq [value [database (:db-after report)
                             (db/history database)
                             (db/as-of database (:max-tx database))
                             (db/since database 0)]]
                (is (identical? projection (db/carried-projection value)))
                (is (= {:seon.agent/id "p1-carriage"}
                       (db/pull value [:seon.agent/id] lookup))))
              (is (= 'seon.db.carriage (:seon.ns/name (db/entity database namespace-lookup))))
              (is (= [{:seon.ns/name 'seon.db.carriage}]
                     (db/pull-many database [:seon.ns/name] [namespace-lookup])))
              (is (= 'seon.db.carriage
                     (db/q '[:find ?name . :in $ ?name
                             :where [_ :seon.ns/name ?name]]
                           database 'seon.db.carriage)))
              (is (some #(= 'seon.db.carriage (:v %))
                        (db/datoms database :avet :seon.ns/name))))))
         ;; A committed value holding declaration rows derives its own
         ;; projection (ca9c40639), and a pull that reads no vector storage
         ;; needs none (18ce1b1a4): the raw value answers, with no fallback.
         ;; A value holding NO declaration rows still refuses by name
         ;; (`seon.db/construction-projection`).
         (testing "a raw committed value answers from its own declarations"
           (let [answer (binding [*err* warnings]
                          (schema/call-with-projection-state
                           (atom {}) #(db/pull raw [:seon.agent/id] lookup)))]
             (is (= {:seon.agent/id "p1-carriage"} answer))
             (is (not (str/includes? (str warnings) "projection-fallback")))))
         (testing "fixture connection carriage survives a fresh worker thread"
           (let [completed (atom nil)
                 worker (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (reset! completed
                                      (let [report (db/transact! connection [])]
                                        {:projection (db/carried-projection (db/db connection))
                                         :report-projection (db/carried-projection (:db-after report))
                                         :error (:seon.error/message report)}))
                             (catch Throwable failure
                               (reset! completed failure)))))]
             (.start worker)
             (let [result (test-support/await-event! completed ::worker-carriage some?)]
               (is (nil? (:error result)) (str (:error result)))
               (is (identical? projection (:projection result)))
               (is (identical? projection (:report-projection result))))))))]
         (is (= {'seon.schema/projection-from-database 0 'seon.schema.edn/read-schema-resource 0} calls)
             "no projection is reconstructed and no resource read"))))))
