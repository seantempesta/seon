(ns seon.test-test
  "Run admission uses the canonical writer and preserves selected obligations."
  (:require [clojure.data.json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.batch]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.db :as db]
            [seon.mcp]
            [seon.task]
            [seon.config :as config]
            [seon.id :as id]
            [seon.program :as program]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]
            [seon.cluster.db-flow-test :as db-flow-test]))

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

(deftest staleness-reads-the-requested-results-together
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source!
      connection 'tq.stale
      "(ns tq.stale (:require [clojure.test :refer [deftest is]]))\n(deftest a (is true))\n(deftest b (is true))")
     (let [names ['tq.stale/a 'tq.stale/b]
           measured (profile/measure #(sut/stale (db/db connection) names))]
       (is (= names (:seon.profile/value measured)))
       (is (= 1 (profile/calls-of measured 'seon.test.runner/current-results)))))))

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

(deftest
  one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body
  ;; Warm acquisition once, then measure the entire interpreted check.
  ;; Remaining setup cost: `seon.test/one-test-run-repeats-whole-program-setup`.
  (let [execution (assoc (test-support/execution-handle nil) :seon.agent/id "one-interpreted-test")
        run! #(sut/run {:seon.test/execution execution
                        :seon.test/recording-connection (:seon.db/connection execution)
                        :seon.test/policy :named
                        :seon.test/rerun? true
                        :seon.test/changed ['seon.id-test/a-host-object-has-no-identity]})
        _ (run!)
        began (System/currentTimeMillis)
        measured (profile/measure run!)
        ended (System/currentTimeMillis)
        result (:seon.profile/value measured)]
    (when-let [path (System/getenv "SEON_F27_MEASUREMENTS")]
      (spit path (value/core-text
                  (assoc (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes
                                               :seon.cost/jvm-window])
                         :began began :ended ended
                         :seon.profile/ranked-by-self (vec (take 8 (:seon.profile/ranked-by-self measured)))
                         :calls (select-keys (:seon.profile/called measured)
                                             '[seon.sci.eval/install-row! seon.sci.eval/loaded-database
                                               seon.sci.eval/install-caller-host-bindings!
                                               seon.sci.eval/bind-lazy-members! seon.sci.eval/promote-member!]))) :append true))
    (is (= 1 (:seon.test/executed-count result)))
    (is (true? (:seon.test/passed? result)))
    (is (< (:seon.profile/ms measured) 200))
    (is (< (get-in measured [:seon.cost/jvm-window :seon.cost/alloc-mb]) 220))
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


(deftest ^{:seon/tasks #{'seon.test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record}
           :seon.test/long "Two forks and one nested seon.test/run of one member on the second."
           :seon.test/long-ms 15000}
  a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record
  ;; After a restart onto a commit without a contracted function, a branch
  ;; forked before it still recorded the old commit and built its test world
  ;; on it, so every test step re-installed the function and refused.
  (let [target (test-support/execution-handle nil)
        store (:seon.store/store target)
        main (db/db (:seon.db/connection target))
        gone 'seon.test-test/gone-at-the-fork-point
        probe 'seon.test-test/the-world-lacks-what-the-loaded-program-lost
        fork! (fn [branch database]
                (registry/fork! {:seon.store/store store :seon.store/branch branch
                                 :seon.cluster.registry/from (db/commit-id database) :seon.db/db database})
                (cluster.agent/acquire-context! target nil {:seon.agent/branch branch}))
        old (keyword (str "fork-point-" (id/id)))
        branch (keyword (str "forked-before-" (id/id)))
        old-handle (fork! old main)
        old-conn (:seon.db/connection old-handle)
        _ (test-support/transacted! old-conn [(test-support/program-fn-entity
                                                (db/db old-conn) gone "(defn gone-at-the-fork-point {:malli/schema [:=> [:cat :int] :int]} [x] x)")])
        fork-point (db/db old-conn)
        handle (fork! branch fork-point)
        conn (:seon.db/connection handle)
        cluster (:seon.cluster/name target)]
    (try
      ;; The branch records the program it was forked at, as a branch forked before the restart does.
      (test-support/transacted! conn [{:seon.cluster/name cluster :seon.source/commit-id (db/commit-id fork-point)}])
      (test-support/transacted! conn [(test-support/program-entity
                                       (db/db conn) [:seon.test/sym probe]
                                       (str "(clojure.test/deftest the-world-lacks-what-the-loaded-program-lost"
                                            " (clojure.test/is (nil? (seon.db/q '[:find ?e . :where [?e :seon.fn/sym " gone "]] (seon.db/db)))))"))])
      (is (some? (db/q '[:find ?e . :in $ ?s :where [?e :seon.fn/sym ?s]] (db/db conn) gone))
          "the branch holds the function its fork point had")
      (let [ran (db/with-branch {:seon.db/connection conn}
                  #(sut/run {:seon.test/policy :named :seon.test/execution handle
                             :seon.test/recording-connection conn :seon.test/identities #{probe}}))]
        (is (= [{:seon.test/fail-count 0 :seon.test/error-count 0}]
               (mapv #(select-keys % [:seon.test/fail-count :seon.test/error-count]) (:seon.test/results ran)))
            "the branch's test world is the loaded program plus the branch's own change"))
      (finally
        (doseq [[h b] [[handle branch] [old-handle old]]]
          (cluster.agent/release-context! h)
          (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(defn- world-leaf
  "What the world callers answer; a branch changes it."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  :loaded)

(defn world-first
  "The leaf, as the first request's test calls it."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  (world-leaf))

(defn world-second
  "The leaf, as the second request's test calls it."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  (world-leaf))

(deftest ^{:seon.test/long "Two forks and two nested seon.test/run requests, one interpreted member each."
           :seon.test/long-ms 20000}
  a-second-test-world-of-one-program-runs-its-interpreted-callers
  ;; S5's merge branch (2026-10-15) recorded a program other than the loaded one at its merge base, so its test
  ;; world interprets its changes over an in-memory overlay commit. The program context is shared by content: a
  ;; later request's member called a lazy caller root naming the first request's released overlay commit and threw
  ;; NullPointerException (withMeta on nil) before its body.
  (let [target (test-support/execution-handle nil)
        store (:seon.store/store target)
        main (db/db (:seon.db/connection target))
        cluster (:seon.cluster/name target)
        fork! (fn [branch database]
                (registry/fork! {:seon.store/store store :seon.store/branch branch
                                 :seon.cluster.registry/from (db/commit-id database) :seon.db/db database})
                (cluster.agent/acquire-context! target nil {:seon.agent/branch branch}))
        source (keyword (str "world-source-" (id/id)))
        branch (keyword (str "world-candidate-" (id/id)))
        source-handle (fork! source main)
        source-conn (:seon.db/connection source-handle)
        ;; the merge base records another program than the loaded one, as S5's did
        _ (test-support/transacted! source-conn [{:seon.cluster/name cluster :seon.source/commit-id (db/commit-id main)}])
        ;; the branch changes the leaf, so both its loaded callers interpret lazily in the world
        _ (test-support/transacted! source-conn (test-support/replacement-tx
                                                  (db/db source-conn) '[:seon.fn/sym seon.test-test/world-leaf]
                                                  (str "(defn- world-leaf \"What the world callers answer; a branch changes it.\""
                                                       " {:malli/schema [:=> [:cat] :keyword]} [] :changed)")
                                                  {}))
        ;; one test per request, each calling a caller the other request never called
        _ (doseq [n ["first" "second"]]
            (test-support/transacted! source-conn [(test-support/program-entity
                                                     (db/db source-conn) [:seon.test/sym (symbol "seon.test-test" (str "calls-world-" n))]
                                                     (str "(clojure.test/deftest calls-world-" n
                                                          " (clojure.test/is (= :changed (seon.test-test/world-" n "))))"))]))
        handle (fork! branch (db/db source-conn))
        conn (:seon.db/connection handle)
        run! (fn [n] (db/with-branch {:seon.db/connection conn}
                       #(sut/run {:seon.test/policy :named :seon.test/execution handle :seon.test/rerun? true
                                  :seon.test/recording-connection conn
                                  :seon.test/identities #{(symbol "seon.test-test" (str "calls-world-" n))}})))
        counts #(mapv (fn [result] (select-keys result [:seon.test/fail-count :seon.test/error-count]))
                      (:seon.test/results %))]
    (try
      (is (= [{:seon.test/fail-count 0 :seon.test/error-count 0}] (counts (run! "first")))
          "the first world interprets the branch's change through its caller")
      (is (= [{:seon.test/fail-count 0 :seon.test/error-count 0}] (counts (run! "second")))
          "a second world of the same program interprets its other caller from its own commit")
      (finally
        (doseq [[h b] [[handle branch] [source-handle source]]]
          (cluster.agent/release-context! h)
          (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest ^{:seon.test/long "One fork and one program context derivation of its branch."
           :seon.test/long-ms 15000}
  a-value-definition-calling-a-lazy-caller-installs-when-its-program-is-derived
  ;; Chain 10d1fd5956a9 attempt 3, 2026-10-15: the agent's (def task (get-in (my.task/current) ...)) is
  ;; interpreted when its branch's program context is derived, with no custody; `my.task/current` was a lazy
  ;; caller root, whose promotion read the executing context's connection, absent there, and every value
  ;; definition refused with NullPointerException (withMeta on nil), so the agent's definitions never installed.
  (let [target (test-support/execution-handle nil)
        store (:seon.store/store target)
        main (db/db (:seon.db/connection target))
        branch (keyword (str "derived-value-" (id/id)))
        _ (registry/fork! {:seon.store/store store :seon.store/branch branch
                           :seon.cluster.registry/from (db/commit-id main) :seon.db/db main})
        handle (cluster.agent/acquire-context! target nil {:seon.agent/branch branch})
        conn (:seon.db/connection handle)]
    (try
      ;; the branch changes the leaf, so its loaded caller is a lazy root, and defines a value calling that caller
      (test-support/transacted! conn (test-support/replacement-tx
                                      (db/db conn) '[:seon.fn/sym seon.test-test/world-leaf]
                                      (str "(defn- world-leaf \"What the world callers answer; a branch changes it.\""
                                           " {:malli/schema [:=> [:cat] :keyword]} [] :changed)")
                                      {}))
      (test-support/transacted! conn [(test-support/program-fn-entity
                                       (db/db conn) 'seon.test-test/world-value
                                       "(def world-value (seon.test-test/world-first))")])
      (let [ctx (sci.eval/base-ctx (db/db conn))]
        (is (empty? (:seon.sci.eval/agent-mistakes (:seon.sci.eval/acquisition ctx)))
            "the derivation interprets the value definition without a refusal")
        (is (= :changed (sci/eval-string* ctx "seon.test-test/world-value"))
            "the value is the branch's change, read through the lazy caller"))
      (finally
        (cluster.agent/release-context! handle)
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
        "the member's omitted agent id is its own world's, never the requester's")
    (is (= [{:seon.test/fail-count 0 :seon.test/error-count 0}]
           (mapv #(select-keys % [:seon.test/fail-count :seon.test/error-count :seon.test/failure-message])
                 (:seon.test/results
                  (binding [seon.batch/*world* {:seon.boot/cluster-name "default"
                                                :seon.env/environment (:seon.env/environment execution)
                                                :seon.agent/id "member-world-requester"}]
                    (sut/run {:seon.test/execution execution
                              :seon.test/recording-connection (:seon.db/connection execution)
                              :seon.test/policy :named :seon.test/rerun? true
                              :seon.test/identities #{'seon.test-test/a-member-body-runs-in-the-constructors-world-for-its-branch}})))))
        "the member's world is the constructor's value for its own branch")))

(deftest ^{:seon/tasks #{'seon.batch/every-world-is-built-by-one-constructor}}
  a-member-body-runs-in-the-constructors-world-for-its-branch
  ;; Run as a member (every deftest is one, `seon.test/run`): the world its
  ;; body sees is `seon.turn.world/world`'s closed value for the member's own
  ;; branch, never its requester's.
  (let [world seon.batch/*world*]
    (is (schema/valid-candidate-value? (db/carried-projection (db/db (:seon.db/connection world))) :seon.batch/world-base world)
        "the body's world is the constructor's closed value")
    (is (identical? (:seon.db/connection world) (:seon.db/connection sut/*member*))
        "its custody is the member's branch")
    (is (nil? (:seon.agent/id world))
        "it carries no requester's agent")))

(deftest an-observed-change-a-script-test-reaches-does-not-refuse-the-step
  ;; Before, a definition changed since the previous run, reached by a test this
  ;; request cannot run, refused the whole request and the eligible tests never ran.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) 'my.agents.observed)])
     (let [define! (fn [source-kind & identity-sources]
                     (test-support/transacted!
                      connection
                      (mapv (fn [[declaration source]]
                              (test-support/program-entity (db/db connection) declaration source
                                                           {:seon.schema.admission/source source-kind}))
                            (partition 2 identity-sources))))
           request (fn [changed]
                     (test-support/transacted!
                      connection
                      [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] (db/db connection))
                        :seon.source/test-input-digest (id/digest 64 [::observed-inputs])}])
                     (sut/run {:seon.test/execution (test-support/execution-handle connection)
                               :seon.test/recording-connection connection
                               :seon.test/policy :named :seon.test/since-previous-run? true
                               :seon.test/changed changed}))
           executed (fn [run] (set (keep #(when-not (:seon.test/unchanged %) (:seon.test/sym %)) (:seon.test/results run))))]
       (define! :agent '[:seon.fn/sym my.agents.observed/other] "(defn other {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                '[:seon.fn/sym my.agents.observed/callee] "(defn callee {:malli/schema [:=> [:cat :int] :int]} [x] x)")
       (define! :agent '[:seon.test/sym my.agents.observed/ok-test] "(deftest ok-test (is (= 1 (callee 1))))")
       (define! :core '[:seon.test/sym my.agents.observed/ineligible-test] "(deftest ineligible-test (is (= 1 (callee 1))))")
       (request '[my.agents.observed/ok-test])
       (define! :agent '[:seon.fn/sym my.agents.observed/callee] "(defn callee {:malli/schema [:=> [:cat :int] :int]} [x] (identity x))")
       (let [run (request '[my.agents.observed/other])]
         (is (nil? (:seon.test/selection-refusal run)) "the observed change does not refuse the step")
         (is (= '#{my.agents.observed/ok-test} (executed run)) "the eligible reaching test runs"))))))

(deftest ^{:seon/tasks #{'seon.test/same-test-symbol-on-two-branches-runs-each-bodys-content}}
  same-test-symbol-on-two-branches-runs-each-bodys-content
  (let [test-symbol 'tq.chain-proofs/proves-a
        run-body
        (fn [source]
          (test-support/with-database
           (fn [connection]
             (#'db-flow-test/chain-world
              connection {"a" [#{} source]}
              (fn [handle]
                (let [facts (cluster.agent/launch-facts (db/db connection) (registry/branches (cluster.agent/handle-store handle)))
                      actions (cluster.agent/launch-actions facts (cluster.agent/registry-facts (:seon.agent/routing handle)) 1)
                      _ (cluster.agent/apply-actions! handle (:seon.agent/routing handle)
                                                   (mapv #(cond-> % (= :start-flow (:seon.flow.actions/op %))
                                                            (assoc :seon.task/execution :paused)) actions))
                      attempt (cluster.agent/acquire-context! handle nil {:seon.agent/branch (:seon.task/branch (first actions))})
                      conn (:seon.db/connection attempt)
                      request {:seon.test/execution (assoc attempt :seon.agent/id "evidence-proof")
                               :seon.test/recording-connection conn
                               :seon.test/policy :named :seon.test/identities #{test-symbol}}
                      check! #(db/with-branch {:seon.db/connection conn} (fn [] (sut/run %)))
                      ran (check! (assoc request :seon.test/rerun? true))
                      reused (check! request)]
                  {:digest (sut/reach-digest (db/db connection) test-symbol)
                   :ran ran :reused reused}))))))
        red (run-body "(clojure.test/is (= 1 2))")
        green (run-body "(clojure.test/is (= 1 1))")]
    (is (not= (:digest red) (:digest green)))
    (doseq [[result passed? fails] [[red false 1] [green true 0]]]
      (is (= 1 (get-in result [:ran :seon.test/executed-count])))
      (is (= passed? (get-in result [:ran :seon.test/passed?])))
      (is (= [fails] (mapv :seon.test/fail-count (get-in result [:ran :seon.test/results]))))
      (is (= passed? (get-in result [:reused :seon.test/passed?])))
      (is (= 1 (get-in result [:reused :seon.test/reused-count]))))))

(defn- agent-eval
  "Evaluate `code` as `agent-id` of the running instance `running` through the MCP entrance; the parsed result."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (clojure.data.json/read-str
   (seon.mcp/call (clojure.data.json/write-str
                   {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(defn- unrun-deftest!
  "Write deftest `sym` with `source` on `connection`'s branch through the canonical writer, outside any eval
  request, so no test step has run it."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.test/sym :string] :nil]}
  [connection sym source]
  (let [declaring (symbol (namespace sym))]
    (when-not (seq (db/datoms (db/db connection) :avet :seon.ns/name declaring))
      (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) declaring)]))
    (test-support/transacted! connection [(test-support/program-entity (db/db connection) [:seon.test/sym sym] source)])
    nil))

(deftest ^{:seon.test/long "Three eval requests through the MCP entrance, each followed by the step's test worlds."
           :seon.test/long-ms 30000}
  an-earlier-deftest-on-the-branch-is-still-selected
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (agent-eval running agent-id "(ns tq.s2b1e) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)")
     (unrun-deftest! connection 'tq.s2b1e-test/earlier "(clojure.test/deftest earlier (clojure.test/is (= 1 1)))")
     (agent-eval running agent-id "(+ 1 2)")
     (agent-eval running agent-id "(in-ns 'tq.s2b1e) (defn g {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
     (let [results (runner/latest-results (db/db connection) ['tq.s2b1e-test/earlier])]
       (is (= [1] (mapv :seon.test/pass-count results))
           "the deftest the branch defined is selected and recorded though it reaches no changed function")))))

(deftest ^{:seon.test/long "Two eval requests through the MCP entrance, each followed by the step's test worlds."
           :seon.test/long-ms 30000}
  a-plan-only-update-runs-the-tasks-proof
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (agent-eval running agent-id "(ns tq.s2b1p) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)")
     (unrun-deftest! connection 'tq.s2b1p-test/proof "(clojure.test/deftest proof (clojure.test/is (= 1 (tq.s2b1p/f 1))))")
     (let [id (:seon.task/id (seon.task/create! {:seon.db/connection connection :seon.agent/id agent-id
                                                 :seon.task/ns 'tq.s2b1p :seon.task/name "f" :seon.task/plan "Identity."
                                                 :seon.task/proofs #{'tq.s2b1p-test/proof}}))]
       (seon.task/assign! {:seon.agent/context-source (test-support/running-cluster running) :seon.db/connection connection
                           :seon.task/assigned id :seon.task/agent [:seon.agent/id agent-id]
                           :seon.task/branch (get-in (db/db connection) [:config :branch])}))
     (let [tests (test-support/mcp-tests
                  (agent-eval running agent-id "(my.task/update! 'tq.s2b1p/f :seon.task/plan (constantly \"Identity, unchanged.\"))"))
           results (runner/latest-results (db/db connection) ['tq.s2b1p-test/proof])]
       (is (not-any? #(get-in tests [% :seon.turn.loop/test-error]) [:seon.turn.loop/branch :seon.turn.loop/reaching :seon.turn.loop/proofs])
           "the step makes no selection error")
       (is (= [1] (mapv :seon.test/pass-count results)) "the task's proof ran and is recorded")))))

(defn- interpreted-outcome
  "Run the loaded test `test-symbol` once with its namespace interpreted from its rows, as an agent branch runs it:
  its evaluator, fail and error counts, or the run's error message."
  {:malli/schema [:=> [:cat :seon.test/sym] [:or [:tuple :keyword :int :int] :string]]}
  [test-symbol]
  (let [execution (test-support/execution-handle nil)
        result (binding [sut/*member* nil]
                 (sut/run {:seon.test/policy :named :seon.test/execution execution
                           :seon.test/recording-connection (:seon.db/connection execution)
                           :seon.test/identities #{test-symbol} :seon.test/rerun? true
                           :seon.test/interpreted-namespaces #{(symbol (namespace test-symbol))}}))]
    (or (:seon.error/message result)
        ((juxt :seon.test.member/evaluator :seon.test/fail-count :seon.test/error-count) (first (:seon.test/results result))))))

(deftest ^{:seon.test/long "One nested run interpreting the whole loaded seon.error-test namespace from its rows."
           :seon.test/long-ms 30000}
  ns-interns-answers-only-a-namespaces-own-vars-interpreted
  ;; live run 7: green compiled on HEAD, red interpreted on an agent branch that changed nothing it reads: in SCI
  ;; (ns-interns 'seon.error) also lists the clojure.core names SCI lacks (ensure, ref, agent ...), interned there
  (is (= [:interpreted 0 0] (interpreted-outcome 'seon.error-test/every-error-owner-function-declares-its-input-and-output))
      "an interpreted run of a test green compiled is green"))

(deftest ^{:seon.test/long "One nested run interpreting the whole loaded seon.turn-test namespace from its rows."
           :seon.test/long-ms 30000}
  an-inst-tag-reads-as-an-inst-while-a-default-data-reader-fn-is-bound-interpreted
  ;; live run 7: green compiled on HEAD, red interpreted on an agent branch that changed nothing it reads: SCI's
  ;; read-string hands #inst to a bound *default-data-reader-fn* where Clojure applies its default data readers first
  (is (= [:interpreted 0 0] (interpreted-outcome 'seon.turn-test/evaluation-ai-is-only-the-repl-session))
      "an interpreted run of a test green compiled is green"))
