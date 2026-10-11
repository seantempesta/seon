(ns seon.test-test
  "Run admission uses the canonical writer and preserves selected obligations."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.batch]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.config :as config]
            [seon.id :as id]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(deftest interpreted-test-bodies-use-the-sci-interrupt-bound
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           evaluation (test-support/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source
                        "(clojure.test/deftest bounded-test (loop [] (recur)))"
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test-test]
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 10000})
           result (sci.eval/run-test
                   {:seon.sci.eval/ctx ctx
                    :seon.test/var (:seon.sci.admit/value evaluation)
                    :seon.sci.eval/time-limit-ms 50
                    :seon.db/connection connection})]
       (is (= 1 (:seon.test/error-count result)))
       (is (pos? (count (:seon.test/failure-message result))))))))

(deftest a-requested-namespace-is-bounded-by-what-its-tests-declare
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           symbols (set (db/q '[:find [?symbol ...]
                                :where [?test :seon.test/sym ?symbol]
                                       [(namespace ?symbol) ?name] [(= ?name "seon.test-test")]]
                              database))
           by-namespace (sut/requested-bound-ms database #{} #{'seon.test-test})]
       (is (seq symbols) "the namespace's tests are in the program")
       (is (= (sut/declared-bound-ms database symbols) by-namespace))
       (is (= by-namespace
              (sut/requested-bound-ms database #{'seon.test-test/interpreted-test-bodies-use-the-sci-interrupt-bound}
                                      #{'seon.test-test}))
           "an identity inside a requested namespace is counted once")))))

(deftest resolution-follows-admitted-source-and-acquisition
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "resolution")
     (test-support/transacted!
      connection
      [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                  (db/db connection))
        :seon.source/test-input-digest (id/digest 64 [::resolution-inputs])}])
     (let [ctx (test-support/fork-cluster-ctx connection)
           before (db/db connection)
           _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db before})
           stale (sci.eval/fork-cluster-ctx ctx before connection)
           source "(clojure.test/deftest admitted-fileless-test (clojure.test/is (seon.db/database-value? (seon.db/db))))"
           evaluation (test-support/evaluate
                       {:seon.sci.eval/ctx ctx
                        :seon.cluster.eval/source source
                        :seon.cluster.eval/ns [:seon.ns/name 'seon.test-test]
                        :seon.sci.admit/caps (config/result-caps config/defaults)
                        :seon.sci.eval/time-limit-ms 10000})
           declaration (program/declaration-row (seon.schema/handed-projection) (:seon.program/row evaluation) :all :agent)]
       (is (nil? (:seon.cluster.eval/error evaluation)))
       (is (string? (:seon.program/analyzed-source-digest declaration)))
       (let [written-on (db/db connection)
             written (test-support/transacted! connection [declaration])
             database (db/db connection)
             request {:seon.db/db database :seon.db/connection connection
                      :seon.test/identity 'seon.test-test/admitted-fileless-test
                      :seon.sci.eval/ctx stale
                      :seon.schema/projection (schema/projection-from-database database)
                      :seon.test/class-loader (clojure.lang.RT/baseLoader)}]
         (is (= :seon.test/program-mismatch (:seon.test/resolution-refusal (sut/resolve-test request))))
         (is (= :seon.test/program-mismatch
                (:seon.test/resolution-refusal (sut/resolve-test request)))
             "resolution never mutates the stale context into acquiring")
         (sci.eval/install-evaluated-rows!
          {:seon.sci.eval/ctx ctx :seon.db/db database :seon.db/transaction-report written
           :seon.sci.eval/written-on written-on
           :seon.sci.eval/installations
           [{:seon.program/row declaration :seon.sci.eval/evaluation evaluation}]})
         (let [resolved (sut/resolve-test (assoc request :seon.sci.eval/ctx ctx))]
           (is (runner/var-reference? resolved)))
         (let [core-symbol (first (db/q '[:find [?symbol ...]
                                         :in $ ?namespace
                                         :where [?ns :seon.ns/name ?namespace]
                                         [?test :seon.test/ns ?ns]
                                         [?test :seon.test/sym ?symbol]]
                                       database 'seon.test-runner-test))
               resolved (sut/resolve-test
                         (assoc request :seon.sci.eval/ctx ctx
                                        :seon.test/identity (symbol core-symbol)))]
           (is (var? resolved))
           (let [file (get-in (db/pull database [:seon.fn/file]
                                      [:seon.test/sym core-symbol])
                              [:seon.fn/file :db/id])
                 override (assoc declaration :seon.fn/file file)]
             (let [override-on (db/db connection)
                   overridden (test-support/transacted! connection [override])]
               (sci.eval/install-evaluated-rows!
                {:seon.sci.eval/ctx ctx :seon.db/db (:db-after overridden) :seon.db/transaction-report overridden
                 :seon.sci.eval/written-on override-on
                 :seon.sci.eval/installations
                [{:seon.program/row override :seon.sci.eval/evaluation evaluation}]}))
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
         (is (inst? (:seon.error/at refused)))
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
           ;; The fixture branch carries the cluster's own runs; this test owns only its two.
           existing-runs (set (db/q '[:find [?id ...] :where [_ :seon.test.run/id ?id]] database))
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
           (is (vector? result))))
       (is (= #{(:seon.test.run/id first-run) (:seon.test.run/id second-run)}
              (set/difference
               (set (db/q '[:find [?id ...] :where [_ :seon.test.run/id ?id]]
                          (db/db connection)))
               existing-runs)))
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
         ;; The writer returns the read's refusal with its own attempt context
         ;; (request id, basis, transaction) added beside it.
         (is (= (dissoc read-refusal :seon.error/data)
                (select-keys refused (keys (dissoc read-refusal :seon.error/data))))
             "a refused read is never evidence of an existing run or an identity collision")
         (is (true? (:seon.db/transaction-refused refused)))
         (is (= basis (db/basis-t (db/db connection)))))
       (let [basis (db/basis-t (db/db connection))
             changed (assoc first-run :seon.test.run/basis-t (inc (:seon.test.run/basis-t first-run)))
             refused (test-support/refusal-data
                      #(runner/commit-results! connection (completion changed)))]
         (is (string? (:seon.test.run/immutable refused)))
         (is (= (:seon.test.run/basis-t first-run)
                (get-in refused [:seon.error/expected
                                 :seon.test.run/basis-t])))
         (is (= (:seon.test.run/basis-t changed)
                (get-in refused [:seon.error/offending
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
       ;; The fixture branch carries the cluster's own evidence: count this test's runs.
       (is (= 3 (count (into (memberships (:seon.test.run/id provenance) :seon.test.run/members)
                             (memberships second-id :seon.test.run/members)))))
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
         (is (= :seon.test.run/immutable (:seon.test/admission-refusal refused)))
         (is (= before (db/basis-t (db/db connection)))))))))

(deftest admission-refuses-stale-program-and-records-empty-selection
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "test-admission-empty")
     (test-support/transacted! connection
       [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
         :seon.source/test-input-digest (id/digest 64 [:admission :inputs])}])
     (let [stale (runner/provenance (db/db connection))
           _ (test-support/transacted!
              ;; A digested attribute changes through the entity its writer digests.
              connection [(test-support/revision (db/db connection) [:seon.fn/sym 'seon.id/id]
                                                 {:seon.fn/doc "Written after the tested basis."})])
           database (db/db connection)
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
                       ;; A program written after the tested basis is a stale program.
                       (assoc request :seon.test.run/provenance stale)]])]
       (is (= :seon.test/program-mismatch (:seon.test/admission-refusal refused)))
       (is (= before (db/basis-t (db/db connection))))
       (let [wrong-input (db/transact! connection
                           [[:db.fn/call sut/admit-run
                             (assoc request :seon.test.run/input-digest
                                    (id/digest 64 [:different :inputs]))]])]
         (is (= :seon.test/program-mismatch (:seon.test/admission-refusal wrong-input)))
         (is (= before (db/basis-t (db/db connection)))))
       (let [report (test-support/transacted!
                     connection [[:db.fn/call sut/admit-run request]])
             entity (db/pull (:db-after report)
                         [:seon.test.run/selection-tx :seon.test.run/members]
                         [:seon.test.run/id (get-in request [:seon.test.run/provenance :seon.test.run/id])])]
         (is (integer? (get-in entity [:seon.test.run/selection-tx :db/id])))
         (is (not (seq (:seon.test.run/members entity)))))))))

(deftest ^{:seon/tasks #{'seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte}}
  one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body
  ;; The body allocates about 6.5 MB; forking a member and interpreting its
  ;; namespace's rows is bounded by the test, not the program: 100 MB. The
  ;; first run acquires the JVM's cluster base once; the second is measured.
  (let [execution (assoc (test-support/execution-handle nil) :seon.agent/id "one-interpreted-test")
        run! #(sut/run {:seon.test/execution execution
                        :seon.test/recording-connection (:seon.db/connection execution)
                        :seon.test/policy :named
                        :seon.test/rerun? true
                        :seon.test/changed ['seon.id-test/a-host-object-has-no-identity]})
        _ (run!)
        measured (profile/measure run!)
        result (:seon.profile/value measured)]
    (is (= 1 (:seon.test/executed-count result)))
    (is (true? (:seon.test/passed? result)))
    (is (< (:seon.profile/alloc-bytes measured) 100000000))))

(deftest ^{:seon.test/long "One isolated member acquisition and one canonical problems fixture (measured 1,572 ms run on default)."
           :seon.test/long-ms 15000}
  a-member-body-reads-its-member
  ;; seon.problems-test's `world-namespaces` reads the member's connection from
  ;; `seon.test/*member*`; run outside any member (as a REPL check is), an
  ;; interpreted body read nil and called `seon.db/db` with it.
  (let [execution (assoc (test-support/execution-handle nil) :seon.agent/id "member-body-reads-its-member")
        result (binding [sut/*member* nil]
                 (sut/run {:seon.test/execution execution
                           :seon.test/recording-connection (:seon.db/connection execution)
                           :seon.test/policy :named
                           :seon.test/rerun? true
                           :seon.test/changed ['seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs]}))]
    (is (= 1 (:seon.test/executed-count result)))
    (is (true? (:seon.test/passed? result)) "the body finds its member's connection")))


(deftest ^{:seon/tasks #{'seon.test/change-reds-judges-a-test-main-does-not-hold-as-not-red-there}
           :seon.test/long "One fork, one nested seon.test/run of one red member on it and change-reds' read of main."
           :seon.test/long-ms 15000}
  a-red-test-only-the-branch-holds-is-the-changes
  ;; agent pv-probe's red deftests, defined only on its branch, answered select's
  ;; identity-unresolved refusal from main instead of the red.
  (let [target (test-support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        test-symbol 'seon.test-test/only-on-the-branch
        branch (keyword (str "change-reds-" (id/id)))
        _ (registry/fork! {:seon.store/store store :seon.store/branch branch
                           :seon.cluster.registry/from (db/commit-id (db/db main-conn)) :seon.db/db (db/db main-conn)})
        candidate (cluster.agent/acquire-context! target nil {:seon.agent/branch branch})
        conn (:seon.db/connection candidate)]
    (try
      (test-support/transacted! conn [(test-support/program-entity
                                       (db/db conn) [:seon.test/sym test-symbol]
                                       "(clojure.test/deftest only-on-the-branch (clojure.test/is (= 1 2)))")])
      (let [ran (db/call-with-custody {:seon.db/connection conn}
                  #(sut/run {:seon.test/policy :named :seon.test/execution candidate
                             :seon.test/recording-connection conn :seon.test/identities #{test-symbol}}))]
        (is (= [1] (mapv :seon.test/fail-count (:seon.test/results ran))) "the test is red on the branch")
        (is (empty? (db/datoms (db/db main-conn) :avet :seon.test/sym test-symbol)) "main does not hold it")
        (is (= [test-symbol] (sut/change-reds (db/db conn) target [test-symbol]))
            "a red main does not hold is the change's"))
      (finally
        (cluster.agent/release-context! candidate)
        (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))

(deftest ^{:seon/tasks #{'seon.test/a-members-reads-stay-out-of-its-callers-evidence}
           :seon.test/long "Two member runs, one of them a canonical problems fixture (measured 1,572 ms run on default)."
           :seon.test/long-ms 15000}
  a-members-reads-stay-out-of-its-callers-evidence
  ;; A form's read-evidence sink lives until its request ends; conveyed into the
  ;; member's body thread it held every member's databases and query results
  ;; (86 members as root held about 400 MB, default 2026-10-11).
  (let [execution (assoc (test-support/execution-handle nil) :seon.agent/id "members-reads-stay-out")
        captured (fn [test-symbol]
                   (let [sink (atom [])
                         result (binding [db/*read-evidence-sink* sink]
                                  (sut/run {:seon.test/execution execution
                                            :seon.test/recording-connection (:seon.db/connection execution)
                                            :seon.test/policy :named :seon.test/rerun? true
                                            :seon.test/identities #{test-symbol}}))]
                     {:passed? (:seon.test/passed? result) :reads (count @sink)}))
        quiet (captured 'seon.id-test/a-host-object-has-no-identity)
        reading (captured 'seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs)]
    (is (= [true true] [(:passed? quiet) (:passed? reading)]))
    ;; The caller's own reads (selection, acquisition, recording) are alike for
    ;; both; the problems fixture's body reads about 200 more.
    (is (< (:reads reading) (* 1.5 (:reads quiet)))
        "a body that reads its fixture adds nothing to the caller's evidence")))

(deftest ^{:seon/tasks #{'seon.test/a-test-green-on-a-fresh-jvm-is-green-on-the-live-root}}
  a-member-runs-in-its-own-world-never-its-requesters
  ;; Root's `my.test/check` ran members inside root's evaluation: the body
  ;; thread conveyed root's world, so `seon.error/normalize`'s optional
  ;; agent id was supplied as root and six seon.error-test members failed on
  ;; the live root only (post-restart-verify, 2026-10-11).
  (let [execution (assoc (test-support/execution-handle nil) :seon.agent/id "member-world-requester")
        result (binding [seon.batch/*world* {:seon.boot/cluster-name "default"
                                             :seon.env/environment (:seon.env/environment execution)
                                             :seon.agent/id "member-world-requester"}]
                 (sut/run {:seon.test/execution execution
                           :seon.test/recording-connection (:seon.db/connection execution)
                           :seon.test/policy :named :seon.test/rerun? true
                           :seon.test/identities #{'seon.error-test/attribution-is-a-lookup-ref-or-nothing}}))]
    (is (true? (:seon.test/passed? result))
        "the member's omitted agent id is its own world's, never the requester's")))
