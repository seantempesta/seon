(ns seon.error-write-timing-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [datahike.writing :as writing]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error-result-test :as result-test]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- measured-write [f]
  ;; Profiling delegates every call to its entering armed root. Restoration
  ;; encloses the synchronous transaction result, including its writer work.
  (let [events (atom [])
        preparing-result? (volatile! false)
        rendering-depth (volatile! 0)
        record! (fn [sym start]
                  (swap! events conj [sym (/ (- (System/nanoTime) start) 1e6)]))
        symbols '[seon.error/prepare seon.error/prepare-result seon.error/recording
                  seon.error/commit-call seon.error/recurrence
                  seon.error/agent-exists? seon.error/entity-exists?
                  seon.error/observation-selector seon.db/pull
                  seon.db/write-error seon.db/write-entity-schemas
                  seon.db/write-owned-values-error seon.db/write-entity-value
                  seon.db/write-entity-error seon.db/write-report-error
                  seon.db/arity-mismatches-with seon.db/write-deletion-error
                  seon.db/transact-call seon.db/jdk-integers->long
                  seon.schema.datahike/encode-transaction-in
                  seon.db/retention-snapshot seon.db/retention-check seon.db/retention-report-check
                  seon.error/stored-observation seon.error/signature
                  seon.error/facets seon.error/bounded-error-admission
                  seon.schema/projection-from-database
                  seon.render.value/prepare seon.render.value/render-ai-data
                  seon.blob/put! seon.sci.eval/bind-result!
                  datahike.db.transaction/transact-tx-data]
        roots (into {} (map (fn [sym]
                             (let [v (find-var sym) original @v]
                               [v (fn [& args]
                                    (let [start (System/nanoTime)
                                          label (if (= sym 'seon.render.value/prepare)
                                                  [sym (cond
                                                         (not @preparing-result?) :legacy
                                                         (= :seon.render/html (second args)) :complete
                                                         :else :capped)]
                                                  sym)]
                                      (when (= sym 'seon.error/prepare-result)
                                        (vreset! preparing-result? true))
                                      (when (= sym 'seon.render.value/prepare)
                                        (vswap! rendering-depth inc))
                                      (try (apply original args)
                                           (finally
                                             (when (= sym 'seon.error/prepare-result)
                                               (vreset! preparing-result? false))
                                             (when (or (not= sym 'seon.render.value/prepare)
                                                       (= 1 @rendering-depth))
                                               (record! label start))
                                             (when (= sym 'seon.render.value/prepare)
                                               (vswap! rendering-depth dec))))))]))) symbols)
        commit writing/commit!
        roots (assoc roots #'writing/commit!
                     (fn [& args]
                       (let [start (System/nanoTime)
                             result (apply commit args)]
                         (if (false? (nth args 2 true))
                           (async/map (fn [value]
                                        (record! 'datahike.writing/commit! start)
                                        value) [result])
                           (do (record! 'datahike.writing/commit! start) result)))))]
    (let [collectors (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans)
          before (reduce + (map #(.getCollectionTime %) collectors))]
      (with-redefs-fn roots f)
      (println "ERROR-WRITE-GC-MS"
               (- (reduce + (map #(.getCollectionTime %) collectors)) before)))
    (doseq [[sym samples] (sort-by (comp str key) (group-by first @events))]
      (println "ERROR-WRITE-SPLIT" sym
               {:calls (count samples) :ms (reduce + (map second samples))}))
    @events))

(deftest ^{:seon.test/long "Acquire the complete canonical SCI program once, then profile one armed error write without replacing its behavior."
           :seon.test/long-ms 60000}
  one-error-write-has-a-measured-preparation-validation-and-commit
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "error-write-timing")
     (support/transacted! connection [{:seon.agent/id "error-write-timing"}])
     (support/transacted! connection
                          (turn/open-tx {:seon.turn/id "error-write-timing-turn"
                                         :seon.turn/agent [:seon.agent/id "error-write-timing"]
                                         :seon.turn/opened-tx "datomic.tx"}))
     (let [base (support/fork-cluster-ctx connection "error-write-timing")
           ctx (:seon.sci.eval/ctx
                (sci.eval/fork-for-turn {:seon.sci.eval/ctx base
                                         :seon.db/db (db/db connection)
                                         :seon.agent/id "error-write-timing"}))
           request (assoc (#'result-test/request connection
                           (#'result-test/observation {:error-write/value [1 2 3]}))
                          :seon.sci.eval/ctx ctx :seon.agent/id "error-write-timing"
                          :seon.turn/id "error-write-timing-turn")
           events (measured-write
                   #(let [started (System/nanoTime)
                          recording (error/recording (db/db connection) request)
                          report (support/transacted! connection (:seon.db/tx-data recording))]
                      (println "ERROR-WRITE-TOTAL-MS" (/ (- (System/nanoTime) started) 1e6)
                               "datoms" (count (:tx-data report)))
                      (is (:db-after report))))]
       (is (some #(= 'datahike.writing/commit! (first %)) events))
       (is (some #(= 'seon.sci.eval/bind-result! (first %)) events))
       (is (not-any? #(= 'seon.db/arity-mismatches-with (first %)) events)
           "An error write does not inspect unrelated program call sites.")))))

(deftest program-call-changes-still-enter-final-arity-validation
  (support/with-database
   (fn [connection]
     (let [original @#'db/arity-mismatches-with
           calls (atom 0)
           result (with-redefs-fn
                    {#'db/arity-mismatches-with
                     (fn [& args] (swap! calls inc) (apply original args))}
                    #(db/transact! connection
                                   [[:db/add [:seon.fn/sym 'seon.id/id]
                                     :seon.fn/call-arities ['seon.id/id 99]]]))]
       (is (= 1 @calls))
       (is (:seon.db/transaction-refused result))
       (println "ERROR-WRITE-PROGRAM-REFUSAL" (:seon.error/message result))))))

(deftest retention-validates-expanded-writes-and-retained-target-identities
  (support/with-database
   (fn [connection]
     (let [issue [:seon.issue/id "error-write-retention"]
           creator [:seon.agent/id "error-write-creator"]
           worker [:seon.agent/id "error-write-worker"]
           a [:seon.test/sym 'seon.error-test/the-signature-ignores-the-message]
           b [:seon.test/sym 'seon.error-test/normalization-is-total]
           write (fn [actor tx]
                   (db/transact! connection {:tx-data tx :tx-meta {:seon.db/user actor}}))]
       (support/transacted!
        connection
        [{:seon.agent/id (second creator)} {:seon.agent/id (second worker)}
         {:seon.issue/id (second issue) :seon.issue/title "Retain tests"
          :seon.issue/path "docs/seon/issues/error-write-retention.md"
          :seon.issue/problem "Verify indexed retention at the final writer."
          :seon.issue/status :open :seon.issue/severity :cleanup
          :seon.issue/budget (Integer/valueOf 7)
          :seon.issue/created-by creator :seon.issue/agent worker
          :seon.issue/tests #{a b}}])
       (is (= 7 (:seon.issue/budget (db/pull (db/db connection) [:seon.issue/budget] issue)))
           "Java integers in actual storage entries still become Datahike longs.")
       (doseq [tx [[[:db.fn/call (fn [_] [[:db/retract issue :seon.issue/tests a]])]]
                   [[:db/retract a :seon.test/sym (second a)]]
                   [[:db/add issue :seon.issue/created-by worker]]]]
         (let [before (db/basis-t (db/db connection))
               refusal (write worker tx)]
           (is (re-find #"Started issue tests" (:seon.error/message refusal "")) (pr-str refusal))
           (is (= before (db/basis-t (db/db connection))))))
       (is (:db-after (write creator [[:db/retract issue :seon.issue/agent worker]])))
       (is (nil? (:db-after (write worker [[:db/retract issue :seon.issue/tests a]]))))
       (is (:db-after (write creator [[:db/retract issue :seon.issue/tests a]])))
       (is (nil? (:db-after (write creator [[:db/retract issue :seon.issue/tests b]]))))))))

(deftest transaction-functions-receive-their-request-object-unchanged
  (support/with-database
   (fn [connection]
     (let [request {:seon.schema/projection (db/carried-projection (db/db connection))
                    ::java-integer (Integer/valueOf 7)}
           received (atom nil)]
       (support/transacted!
        connection
        [[:db.fn/call (fn [_ supplied]
                       (reset! received supplied)
                       [{:seon.agent/id "error-write-request"}
                        {:seon.issue/id "error-write-function-output"
                         :seon.issue/title "Normalize returned storage data"
                         :seon.issue/problem "The request object stays unchanged."
                         :seon.issue/status :open :seon.issue/severity :cleanup
                         :seon.issue/budget (::java-integer supplied)}]) request]])
       (is (true? (identical? request @received))
           "Transaction-function arguments are in-memory values, not storage data to walk.")
       (is (identical? (::java-integer request) (::java-integer @received)))
       (is (= 7 (:seon.issue/budget
                  (db/pull (db/db connection) [:seon.issue/budget]
                           [:seon.issue/id "error-write-function-output"]))))))))
