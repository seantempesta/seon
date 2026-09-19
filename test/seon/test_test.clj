(ns seon.test-test
  "Run admission uses the canonical writer and preserves selected obligations."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.config :as config]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(deftest check-records-only-execution-members-and-reuses-the-green-set
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "check-reuse" {:seon.test/check-time-limit-ms 120000})
     (test-support/transacted! connection
       [{:seon.ns/name 'selection.check}
        {:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [:check :inputs])}])
     (let [ctx (test-support/fork-cluster-ctx connection)]
       (doseq [source ["(defn leaf {:malli/schema [:=> [:cat] :int]} [] 1)"
                       "(clojure.test/deftest ordinary (clojure.test/is (= 1 (leaf))))"
                       "(clojure.test/deftest ^{:seon.test/long \"Declared long fixture\"} slow (clojure.test/is (= 1 (leaf))))"]]
         (let [evaluation (sci.eval/evaluate
                            {:seon.sci.eval/ctx ctx :seon.cluster.eval/source source
                             :seon.cluster.eval/ns [:seon.ns/name 'selection.check]
                             :seon.sci.admit/caps (config/result-caps (config/defaults))
                             :seon.sci.eval/time-limit-ms 120000 :seon.config/on-core-error :panic})
               row (program/declaration-row (:seon.program/row evaluation) :all :agent)]
           (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
           (is (string? (:seon.program/analyzed-source-digest row)) (pr-str row))
           (test-support/transacted! connection [row])
           (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)})))
       (is (= (runner/program-digest (db/db connection))
              (runner/program-digest (:seon.db/db (sci.eval/acquired-program ctx)))))
       (let [request {:seon.db/connection connection :seon.boot/cluster-name "check-reuse"
                      :seon.test/namespaces ['selection.check]
                      :my.program/context {:my.program/base-ctx ctx :seon.sci.eval/ctx ctx
                                           :seon.db/connection connection}}
             first-check (sut/check request)
             second-check (sut/check request)]
         (is (= ['selection.check/ordinary] (:seon.test/tests first-check)) (pr-str first-check))
         (is (empty? (:seon.test/failed first-check)) (pr-str first-check))
         (is (= ['selection.check/ordinary] (:seon.test/passed first-check)) (pr-str first-check))
         (is (= [] (:seon.test/tests second-check)) (pr-str second-check))
         (is (= [true] (mapv :seon.test/unchanged (:seon.test/results second-check))) (pr-str second-check))
         (let [reused (first (:seon.test/results second-check))]
           (is (integer? (:seon.test.run/basis-t reused)))
           (is (= (runner/program-digest (db/db connection)) (:seon.test.run/program-digest reused)))
           (is (= (id/digest 64 [:check :inputs]) (:seon.test.run/input-digest reused))))
         (is (= #{'selection.check/ordinary}
                (set (db/q '[:find [?symbol ...] :where [_ :seon.test.member/symbol ?symbol]
                            [?run :seon.test.run/members ?member] [?member :seon.test.member/symbol ?symbol]]
                           (db/db connection)))))
         (is (= #{['selection.check/slow :long]}
                (db/q '[:find ?symbol ?decision :where [?run :seon.test.run/exclusions ?excluded]
                        [?excluded :seon.test.member/symbol ?symbol]
                        [?excluded :seon.test.selection/disposition ?decision]] (db/db connection))))
         (let [selected (sut/check-admission (db/db connection)
                           (assoc request :seon.test/changed ['selection.check/leaf]))]
           (is (some #(and (= 'selection.check/ordinary (:seon.test.member/symbol %))
                           ((:seon.test.member/reasons %) :reaches-changed))
                     (:seon.test.run/members selected)) (pr-str selected))))))))

(deftest recorded-reuse-requires-the-original-selection
  (test-support/with-database
   (fn [connection]
     (let [passing 'seon.test-runner-failure-fixture/passing-example
           empty-test 'seon.test-runner-failure-fixture/assertionless-example
           execute! (fn [symbols]
                      (let [provenance (runner/provenance (db/db connection))
                            results (runner/run-vars! (mapv requiring-resolve symbols) {})
                            recorded (runner/commit-results!
                                      connection {:seon.test.run/provenance provenance
                                                  :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                                                  :seon.test/run-at (:seon.test.run/at provenance)
                                                  :seon.test.runner/results results})]
                        (is (vector? recorded) (pr-str recorded))))
           reuse #(runner/reusable-result {:seon.db/db (db/db connection) :seon.test/identity passing})]
       (execute! [passing empty-test])
       (is (= :seon.test/execution-required (reuse)))
       (execute! [empty-test])
       (is (= :seon.test/execution-required (reuse))
           "Overwriting another test's latest result cannot shrink the old batch's selection.")
       (execute! [passing])
       (is (true? (:seon.test/unchanged (reuse))))
       (is (= :seon.test/invalid-basis
              (:seon.test/execution-refusal
               (runner/reusable-result {:seon.db/db (db/db connection) :seon.test/identity passing
                                        :seon.test/run-basis-t (inc (db/basis-t (db/db connection)))}))))))))

(deftest interpreted-test-bodies-use-the-sci-interrupt-bound
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           evaluation (sci.eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source
                        "(clojure.test/deftest bounded-test (loop [] (recur)))"
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test-test]
                        :seon.sci.admit/caps (config/result-caps (config/defaults))
                        :seon.sci.eval/time-limit-ms 10000
                        :seon.config/on-core-error :panic})
           result (sci.eval/run-test
                   {:seon.sci.eval/ctx ctx
                    :seon.test/var (:seon.sci.admit/value evaluation)
                    :seon.sci.eval/time-limit-ms 50
                    :seon.db/connection connection})]
       (is (= 1 (:seon.test/error-count result)) (pr-str result))
       (is (pos? (count (:seon.test/failure-message result))) (pr-str result))))))

(deftest resolution-follows-admitted-source-and-acquisition
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           before (db/db connection)
           stale (sci.eval/fork-cluster-ctx ctx before connection)
           source "(clojure.test/deftest admitted-fileless-test (clojure.test/is (seon.db/database-value? (seon.db/db))))"
           evaluation (sci.eval/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source source
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test-test]
                        :seon.sci.admit/caps (config/result-caps (config/defaults))
                        :seon.sci.eval/time-limit-ms 10000
                        :seon.config/on-core-error :panic})
           declaration (program/declaration-row (:seon.program/row evaluation) :all :agent)]
       (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
       (is (string? (:seon.program/analyzed-source-digest declaration)))
       (test-support/transacted! connection [declaration])
       (let [database (db/db connection)
             request {:seon.db/db database :seon.db/connection connection
                      :seon.test/identity 'seon.test-test/admitted-fileless-test
                      :seon.sci.eval/ctx stale
                      :seon.schema/projection (schema/projection-from-database database)
                      :seon.test/class-loader (clojure.lang.RT/baseLoader)}]
         (is (= :seon.test/program-mismatch (:seon.test/resolution-refusal (sut/resolve-test request))))
         (sci.eval/install-evaluated-rows!
          {:seon.sci.eval/ctx ctx :seon.db/db database
           :seon.sci.eval/installations
           [{:seon.program/row declaration :seon.sci.eval/evaluation evaluation}]})
         (let [worker-result
               (#'runner/run-task!
                {:seon.test.runner/task-namespace "seon.test-test"
                 :seon.test.runner/task-symbols ["seon.test-test/admitted-fileless-test"]}
                (assoc request :seon.sci.eval/ctx ctx
                               :seon.db/custody-request {:seon.db/connection connection}))]
           (is (= {:seon.test.runner/test-count 1 :seon.test.runner/pass-count 1
                   :seon.test.runner/fail-count 0 :seon.test.runner/error-count 0}
                  (:seon.test.runner/task-summary worker-result))
               (pr-str worker-result)))
         (let [resolved (sut/resolve-test (assoc request :seon.sci.eval/ctx ctx))]
           (is (runner/var-reference? resolved) (pr-str resolved))
           (when (runner/var-reference? resolved)
             (let [result (sut/run-owned
                           {:seon.test/var resolved
                            :seon.db/connection connection
                            :my.program/context
                            {:seon.sci.eval/ctx ctx :my.program/base-ctx ctx
                             :seon.db/connection connection}})]
               (is (= [1 0 0] (mapv result [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count]))
                   (pr-str result)))))
         (let [core-symbol (first (db/q '[:find [?symbol ...]
                                         :in $ ?namespace
                                         :where [?ns :seon.ns/name ?namespace]
                                         [?test :seon.test/ns ?ns]
                                         [?test :seon.test/sym ?symbol]]
                                       database 'seon.test-runner-test))
               resolved (sut/resolve-test
                         (assoc request :seon.sci.eval/ctx ctx
                                        :seon.test/identity (symbol core-symbol)))]
           (is (var? resolved) (pr-str resolved))
           (let [file (get-in (db/pull database [:seon.fn/file]
                                      [:seon.test/sym core-symbol])
                              [:seon.fn/file :db/id])
                 override (assoc declaration :seon.fn/file file)]
             (test-support/transacted! connection [override])
             (sci.eval/install-evaluated-rows!
              {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
               :seon.sci.eval/installations
               [{:seon.program/row override :seon.sci.eval/evaluation evaluation}]})
             (let [resolved (sut/resolve-test
                             (assoc request :seon.sci.eval/ctx ctx
                                            :seon.db/db (db/db connection)))]
               (is (and (runner/var-reference? resolved) (not (var? resolved)))
                   "Agent provenance wins even when a core file coordinate remains."))))
         (is (= :seon.test/identity-unresolved
                (:seon.test/resolution-refusal
                 (sut/resolve-test (assoc request :seon.sci.eval/ctx ctx
                                         :seon.test/identity 'seon.test-test/no-such-test))))))))))

(deftest recording-preserves-admission-and-refuses-a-deleted-definition
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
       (let [basis (db/basis-t (db/db connection))
             refused (db/transact!
                      connection
                      [[:db.fn/retractEntity [:seon.test/sym test-symbol]]
                       [:db.fn/call runner/record-tx completion]])]
         (is (:seon.error/kind refused) (pr-str refused))
         (is (= basis (db/basis-t (db/db connection))))
         (is (= :core (source-of))
             "result recording cannot recreate a deleted definition"))))))

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
         (is (true? (:seon.db/invalid-read read-refusal)))
         (is (= read-refusal refused)
             "a refused read is never evidence of an existing run or an identity collision")
         (is (= basis (db/basis-t (db/db connection)))))
       (let [basis (db/basis-t (db/db connection))
             changed (assoc first-run :seon.test.run/basis-t (inc (:seon.test.run/basis-t first-run)))
             refused (test-support/refusal-data
                      #(runner/commit-results! connection (completion changed)))]
         (is (string? (:seon.test.run/immutable refused)) (pr-str refused))
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
     (test-support/transacted! connection
       [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [:admission :inputs])}])
     (let [database (db/db connection)
           provenance (runner/provenance database)
           symbols (->> (db/q '[:find [?symbol ...]
                                :where [_ :seon.test/sym ?symbol]] database)
                        sort (take 3) (mapv symbol))
           member #(hash-map :seon.test.member/symbol %
                             :seon.test.member/reasons #{:first-run})
           request {:seon.test.run/provenance provenance
                    :seon.test.run/cluster [:seon.cluster/name "test-admission"]
                    :seon.test.run/input-digest (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] database)
                    :seon.test.run/policy :incremental
                    :seon.test.run/include-long? false
                    :seon.test.run/members (mapv member (take 2 symbols))}
           second-id (id/id)
           second-request (-> request
                              (assoc-in [:seon.test.run/provenance :seon.test.run/id] second-id)
                              (assoc :seon.test.run/members (mapv member (drop 1 symbols))))
           report (test-support/transacted!
                   connection
                   [[:db.fn/call sut/admit-run request]
                    [:db.fn/call sut/admit-run second-request]])
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
                     connection [[:db.fn/call sut/admit-run second-request]])]
         (is (empty? (filter #(or (= "seon.test.run" (namespace (:a %)))
                                 (= "seon.test.member" (namespace (:a %))))
                            (:tx-data replay)))
             "An identical admission replay writes no evidence datoms."))
       (let [before (db/basis-t (db/db connection))
             refused (db/transact! connection
                       [[:db.fn/call sut/admit-run
                         (assoc second-request :seon.test.run/members [])]])]
         (is (= :seon.test.run/immutable (:seon.test/admission-refusal refused)) (pr-str refused))
         (is (= before (db/basis-t (db/db connection)))))))))

(deftest admission-refuses-stale-program-and-records-empty-selection
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "test-admission-empty")
     (test-support/transacted! connection
       [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [:admission :inputs])}])
     (let [database (db/db connection)
           request {::ignored :not-a-database-attribute
                    :seon.test.run/provenance (runner/provenance database)
                    :seon.test.run/cluster [:seon.cluster/name "test-admission-empty"]
                    :seon.test.run/input-digest (db/q '[:find ?digest . :where [_ :seon.source/test-input-digest ?digest]] database)
                    :seon.test.run/policy :incremental
                    :seon.test.run/include-long? false
                    :seon.test.run/members []}
           before (db/basis-t database)
           refused (db/transact! connection
                     [[:db.fn/call sut/admit-run
                       (assoc-in request [:seon.test.run/provenance :seon.test.run/program-digest]
                                 (id/digest 64 [:different :program]))]])]
       (is (= :seon.test/program-mismatch (:seon.test/admission-refusal refused)) (pr-str refused))
       (is (= before (db/basis-t (db/db connection))))
       (let [wrong-input (db/transact! connection
                           [[:db.fn/call sut/admit-run
                             (assoc request :seon.test.run/input-digest
                                    (id/digest 64 [:different :inputs]))]])]
         (is (= :seon.test/program-mismatch (:seon.test/admission-refusal wrong-input))
             (pr-str wrong-input))
         (is (= before (db/basis-t (db/db connection)))))
       (let [report (test-support/transacted!
                     connection [[:db.fn/call sut/admit-run request]])
             row (db/pull (:db-after report)
                         [:seon.test.run/selection-tx :seon.test.run/members]
                         [:seon.test.run/id (get-in request [:seon.test.run/provenance :seon.test.run/id])])]
         (is (integer? (get-in row [:seon.test.run/selection-tx :db/id])))
         (is (not (seq (:seon.test.run/members row)))))))))
