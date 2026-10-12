(ns seon.test.one-request-test
  "A test is an isolated agent for one body: `seon.test/run` acquires each
  member's branch through the agent entrance and unlinks it after the body."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest a-member-body-runs-on-its-own-listed-branch
  (let [handle (support/execution-handle nil)
        store (:seon.store/store handle)]
    (is (= :isolated (:seon.agent/mode handle)))
    (is (true? (:seon.agent/owns-branch? handle)))
    (is (identical? (:seon.db/connection handle) db/*conn*)
        "a host test body runs under its own member branch's custody, never an inherited one")
    (is (contains? (registry/roster store) (:seon.agent/branch handle))
        "the member's branch is on the store roster while its body runs")))

(defn- define-agent-test!
  "Evaluate one deftest as an agent does and write its canonical declaration entity."
  ([handle source] (define-agent-test! handle source 'seon.test.one-request-test))
  ([handle source namespace-name]
  (let [connection (:seon.db/connection handle)
        ;; The member's own SCI arm governs this body thread, so the fixture
        ;; context evaluates on a thread of its own, as a separate agent does.
        evaluation (support/await-event!
                    (future
                     (db/with-branch
                      {:seon.db/connection connection}
                      #(sci.eval/evaluate
                      {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                       :seon.cluster.eval/source source
                       :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                       :seon.sci.admit/caps (config/result-caps config/defaults)
                       :seon.sci.eval/time-limit-ms 5000})))
                    ::agent-test-defined)
        entity (program/declaration-row (db/carried-projection (db/db connection))
                                     (:seon.program/row evaluation) :all :agent)]
    (is (nil? (:seon.cluster.eval/error evaluation)))
    ;; A redefinition replaces the stored declaration exactly: an upsert keeps
    ;; the owned attributes the new entity drops, and the writer refuses its digest.
    (support/transacted! connection
                         (if-let [stored (db/pull (db/db connection) '[*] [:seon.test/sym (:seon.test/sym entity)])]
                           (program/exact-replacement-tx stored entity)
                           [entity])))))

(def ^:private marker
  (support/file-store-probe-schema :seon.test.one-request-test/marker))

(defn- marker-test [label]
  (str "(clojure.test/deftest writes-" label
       " (seon.db/transact! [{:seon.test.one-request-test/marker \"" label "\"}])"
       " (clojure.test/is (= #{\"" label "\"}"
       " (set (seon.db/q '[:find [?m ...] :where [_ :seon.test.one-request-test/marker ?m]] (seon.db/db))))))"))

(deftest ^{:seon.test/long "Three nested one-body requests, each acquiring its program at a new commit: 23.2 s measured at 15ffb4936 on a scratch cluster, dominated by the routed whole-program acquisition and per-connection projection costs (docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md)."
           :seon.test/long-ms 30000}
  one-request-runs-each-member-as-an-isolated-agent
  (support/with-database
   {:seon.test-support/extra-schema marker}
   (fn [connection]
     (let [handle (support/execution-handle connection)
           store (:seon.store/store handle)
           request (fn [identities]
                     (sut/run {:seon.test/execution (support/execution-handle connection)
                               :seon.test/recording-connection connection
                               :seon.test/policy :named
                               :seon.test/identities identities}))]
       (define-agent-test! handle (marker-test "a"))
       (define-agent-test! handle (marker-test "b"))
       (define-agent-test! handle (marker-test "c"))
       (testing "independent members run on distinct unlinked branches off one commit"
         (let [result (request #{'seon.test.one-request-test/writes-a
                                 'seon.test.one-request-test/writes-b})
               branches (map :seon.agent/branch (:seon.test/timings result))]
           (is (true? (:seon.test/passed? result)))
           (is (= 2 (:seon.test/executed-count result)))
           (is (= 1 (count (set (map :seon.test.member/completed-tx (:seon.test/results result)))))
               "the request records its two executed members in one batch transaction")
           (is (= 2 (count (set branches))))
           (is (not-any? (set (registry/roster store)) branches)
               "every member branch is unlinked once its body exits")
           (is (empty? (db/q '[:find [?m ...] :where [_ :seon.test.one-request-test/marker ?m]]
                             (db/db connection)))
               "no member write reaches the execution branch")))
       (testing "unchanged green members are answered from the record"
         (let [again (request #{'seon.test.one-request-test/writes-a})]
           (is (true? (:seon.test/passed? again)))
           ;; Reuse also requires the loaded program to be the one the entities
           ;; record (proved both ways in
           ;; `evidence-earned-by-another-loaded-program-is-never-reused`);
           ;; a restart from files before adoption executes again, by name.
           (if (:seon.test/loaded-source-drift again)
             (is (= 1 (:seon.test/executed-count again)))
             (do (is (= 0 (:seon.test/executed-count again)))
                 (is (= 1 (:seon.test/reused-count again)))
                 (is (every? :seon.test/unchanged (:seon.test/results again)))))))
       (testing "a request whose bound fires before a member starts leaves it pending"
         (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                                :seon.test/recording-connection connection
                                :seon.test/policy :named
                                :seon.test/identities #{'seon.test.one-request-test/writes-c}
                                :seon.test/check-time-limit-ms 1})]
           (is (false? (:seon.test/passed? result)))
           (is (= ['seon.test.one-request-test/writes-c] (:seon.test/pending result)))
           (is (= 0 (:seon.test/executed-count result)))))))))

(deftest a-development-root-excludes-a-destructive-member-with-its-platform-command
  (let [database (db/db (:seon.db/connection (support/execution-handle nil)))
        reach (#'sut/destructive-reach database)
        [test-symbol owner] (first (sort reach))
        excluded (#'sut/host-exclusions database (System/getProperty "user.dir") [test-symbol])
        isolated (#'sut/host-exclusions database "/nonexistent-isolated-root" [test-symbol])]
    (is (qualified-symbol? test-symbol) "the program declares a destructive owner some test reaches")
    (is (= [test-symbol] (map :seon.test/sym (:seon.test/destructive-excluded excluded))))
    (is (= owner (:seon.fn/sym (first (:seon.test/destructive-excluded excluded)))))
    (is (= ["bin/test" "--platform" "--" (str test-symbol)]
           (:seon.test/command (first (:seon.test/destructive-excluded excluded)))))
    (is (= {:seon.test/destructive-excluded [] :seon.test/deferred []} isolated)
        "a JVM operating any other root excludes nothing")))

;; An agent namespace with no indexed host tests: a namespace request over it
;; selects exactly the tests these regressions define on their own branch.
(def ^:private probe-ns 'seon.id)

(deftest ^{:seon.test/long "Two agent test definitions and one request: 10.0 s measured at 15ffb4936 on a scratch cluster, dominated by the routed per-request acquisition and per-connection projection costs."
           :seon.test/long-ms 20000}
  an-excluded-long-member-is-never-read-as-green
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)]
       (define-agent-test! handle "(clojure.test/deftest c4-short (clojure.test/is true))" probe-ns)
       (define-agent-test!
        handle
        "(clojure.test/deftest ^{:seon.test/long \"a declared long probe\" :seon.test/long-ms 6000} c4-long (clojure.test/is true))"
        probe-ns)
       (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/namespaces #{probe-ns}})]
         (is (= ['seon.id/c4-long] (map :seon.test/sym (:seon.test/long-excluded result))))
         (is (= ['seon.id/c4-short] (map :seon.test/sym (:seon.test/results result))))
         (is (false? (:seon.test/passed? result))
             "a skipped declared-long obligation is unfulfilled")
         (is (str/includes? (sut/tally result) "excluded (declared long; include-long runs it) seon.id/c4-long")))))))

(deftest a-body-outliving-its-bound-is-released-only-after-it-exits
  ;; A timeout is not termination: the request answers at the bound, and the
  ;; member's resources are released by a watcher that observes actual exit.
  (let [gate (java.util.concurrent.CountDownLatch. 1)
        released (promise)
        handle (support/execution-handle nil)
        body (fn [] (is (.await gate 20 java.util.concurrent.TimeUnit/SECONDS)))
        ;; The gated Var lives in a namespace this test creates and removes.
        owned (create-ns (gensym "seon.test.one-request-test.gated-"))
        gated (intern owned (with-meta 'gated-probe {:test body}) nil)
        loaded (atom {})
        result (#'sut/bounded-result loaded @loaded
                                     (symbol gated) (constantly gated) 100 {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)}
                                     (fn [watched?] (deliver released watched?) nil))]
      (try
        (is (false? (:seon.test.run/terminated? result)) "the bound answered before the body exited")
        (is (not (realized? released)) "nothing is released while the body is live")
        (.countDown gate)
        (is (true? (support/await-event! (future (deref released)) ::released))
            "the watcher released after observing exit")
        (finally (.countDown gate) (remove-ns (ns-name owned))))))

(deftest ^{:seon.test/long "An admission, two recordings and two requests on one fixture branch: 37.7 s measured at 0e53eb8b1 on a scratch cluster, dominated by the routed per-request acquisition and per-connection projection costs."
           :seon.test/long-ms 45000}
  a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           test-symbol 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
           other 'seon.id-test/printed-identity-ignores-the-callers-print-bindings
           request #(sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{%}})
           database (db/db connection)
           admission (sut/selection-admission
                      {:seon.db/db database
                       :seon.test.run/cluster [:seon.cluster/name (:seon.cluster/name handle)]
                       :seon.test.run/policy :named
                       :seon.test/identities #{test-symbol}})
           provenance (:seon.test.run/provenance admission)
           record! (fn [terminated?]
                     (runner/commit-results!
                      connection
                      {:seon.db/db database
                       :seon.test.runner/results
                       [{:seon.test/sym test-symbol :seon.test.member/began? false :seon.test.member/ended? false
                         :seon.test/pass-count 0 :seon.test/fail-count 0 :seon.test/error-count 1
                         :seon.test/failure-message "a body still live at its bound"}]
                       :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                       :seon.test/run-at (:seon.test.run/at provenance)
                       :seon.test.run/provenance provenance
                       :seon.test.run/terminated? terminated?}))]
       (support/transacted! connection [[:db.fn/call sut/admit-run admission]])
       (let [run-id (:seon.test.run/id provenance)
             exit! #(runner/record-exit! connection run-id test-symbol)
             exit-tx #(:seon.test.member/terminated-tx
                       (first (#'runner/execution-members (db/db connection) run-id [test-symbol])))]
         (is (not (:seon.error/at (exit!))) "an exit seen before the result exists is not a refusal")
         (is (nil? (exit-tx)) "and writes nothing: the result's own write carries its exit")
         (is (not (:seon.error/at (record! false))) "the unfinished member is recorded without its exit")
         ;; The live body holds its own test and only that: a request for
         ;; another test runs, and a request for the live test reports it
         ;; pending, never a second body beside it (2026-09-27: one stuck
         ;; web-test member refused every later request of the cluster).
         (let [runnable (request other)]
           (is (nil? (:seon.test/admission-refusal runnable)))
           (is (= 1 (:seon.test/executed-count runnable)))
           (is (true? (:seon.test/passed? runnable))))
         (let [held (request test-symbol)]
           (is (nil? (:seon.test/admission-refusal held)))
           (is (= [test-symbol] (:seon.test/pending held)) "the live body's test is reported pending")
           (is (zero? (:seon.test/executed-count held)) "no body started beside a live one")
           (is (false? (:seon.test/passed? held))))
         ;; The watcher's write is the exit only, never a re-sent outcome
         ;; (a changed outcome was refused as immutable and held the test).
         (let [written (exit!)]
           (is (not (:seon.error/at written))))
         (let [recorded (exit-tx)]
           (is (some? recorded) "the observed exit is recorded")
           (is (not (:seon.error/at (exit!))))
           (is (= recorded (exit-tx)) "a second observation changes nothing")))
       (let [after (request test-symbol)]
         (is (= 1 (:seon.test/executed-count after)))
         (is (true? (:seon.test/passed? after))))))))

(deftest ^{:seon.test/long "One agent test definition, one entity edit and one nested request on a fixture branch: 13.8 s measured on default's JVM (load 14.7): define 1.0 s, edit 0.2 s, member 0.06 s, the nested request's admission and recording 12.6 s (docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md)."
           :seon.test/long-ms 30000}
  a-member-whose-test-does-not-resolve-is-red-and-its-branch-released
  ;; A real resolution failure: the entity's namespace is not its symbol's, so
  ;; the member's resolution refuses its provenance.
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           store (:seon.store/store handle)
           test-symbol 'seon.test.one-request-test/resolves-nowhere]
       (define-agent-test! handle "(clojure.test/deftest resolves-nowhere (clojure.test/is true))")
       ;; A partial edit of the stored entity, digested by its final namespace as the writer digests it.
       (support/transacted! connection [(support/revision (db/db connection) [:seon.test/sym test-symbol]
                                                          {:seon.test/ns [:seon.ns/name probe-ns]})])
       (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{test-symbol}})
             branches (map :seon.agent/branch (:seon.test/timings result))]
         (is (false? (:seon.test/passed? result)))
         (is (= [test-symbol] (map :seon.test/sym (:seon.test/results result))))
         (is (pos? (:seon.test/error-count (first (:seon.test/results result)) 0)))
         ;; Only this request's own branches: other lanes acquire on the same store.
         (is (= 1 (count branches)))
         (is (not-any? (set (registry/roster store)) branches)
             "the member branch is unlinked after its resolution failed"))))))

(deftest ^{:seon.test/long "One request over three indexed members in batches of two: 24.0 s measured at 0e53eb8b1 on a scratch cluster, dominated by the routed config-transaction and per-connection projection costs."
           :seon.test/long-ms 30000}
  a-request-admits-and-releases-in-bounded-batches
  (support/with-database
   (fn [connection]
     (let [members #{'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
                     'seon.id-test/data-shape-and-explicit-length-determine-identity
                     'seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch}
           result (sut/run {:seon.test/execution (support/execution-handle connection)
                            :seon.test/recording-connection connection
                            :seon.test/policy :named
                            :seon.test/identities members
                            :seon.test/batch-limit 2})
           runs (set (map (comp second :seon.test/run) (:seon.test/results result)))
           sizes (map (fn [run-id]
                        (count (db/q '[:find [?member ...] :in $ ?id
                                       :where [?run :seon.test.run/id ?id]
                                              [?run :seon.test.run/members ?member]]
                                     (db/db connection) run-id)))
                      runs)]
       (is (true? (:seon.test/passed? result)))
       (is (= 3 (:seon.test/executed-count result)))
       (is (= 2 (count runs)) "three members over a limit of two admit as two runs")
       (is (every? #(<= % 2) sizes))))))

(deftest ^{:seon.test/long "One request recording green, then two selections: about 10 s on a scratch cluster at 0e53eb8b1, dominated by the routed per-request costs."
           :seon.test/long-ms 30000}
  evidence-earned-by-another-loaded-program-is-never-reused
  ;; A restart from changed files loads new source while the cluster's program
  ;; entities still describe the old program: green recorded against the entities then
  ;; vouched for code the JVM no longer runs (run bba05ce63419, 2026-09-23).
  (support/with-database
   (fn [connection]
     (let [test-symbol 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
           handle (support/execution-handle connection)
           recorded (sut/run {:seon.test/execution handle
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{test-symbol}})
           database (db/db connection)
           cluster [:seon.cluster/name (:seon.cluster/name handle)]
           entities-commit (:seon.source/commit-id (db/pull database [:seon.source/commit-id] cluster))
           ;; a selection reuses only its own evaluator's evidence: the recorded run's
           evaluator (:seon.test.run/evaluator (db/pull database [:seon.test.run/evaluator]
                                                        [:seon.test.run/id (:seon.test.run/id recorded)]))
           select (fn [loaded]
                    (sut/select {:seon.db/db database :seon.test.run/cluster cluster
                                 :seon.test.run/policy :named :seon.test/identities #{test-symbol}
                                 :seon.test.run/evaluator evaluator
                                 :seon.test/loaded-source loaded}))
           matching (select entities-commit)
           drifted (select (random-uuid))]
       (is (true? (:seon.test/passed? recorded)))
       (is (uuid? entities-commit) "the cluster records the source commit its entities came from")
       (is (#{:compiled :interpreted} evaluator) "the run records the evaluator its members ran under")
       (is (= [test-symbol] (map :seon.test/sym (:seon.test.selection/unchanged matching)))
           "the same loaded program reuses its recorded green")
       (is (empty? (:seon.test.selection/unchanged drifted)))
       (is (= [test-symbol] (map :seon.test/sym (:seon.test.run/members drifted)))
           "another loaded program executes the member again")
       (is (:seon.test/loaded-source-drift drifted))
       (testing "a recorded run names its loaded program; one naming none is unknown"
         (let [run [:seon.test.run/id (:seon.test.run/id recorded)]]
           (is (= entities-commit (:seon.test.run/loaded-commit-id
                               (db/pull database [:seon.test.run/loaded-commit-id] run))))
           (support/transacted! connection [[:db/retract run :seon.test.run/loaded-commit-id entities-commit]])
           (let [old (sut/select {:seon.db/db (db/db connection) :seon.test.run/cluster cluster
                                  :seon.test.run/policy :named :seon.test/identities #{test-symbol}
                                  :seon.test.run/evaluator evaluator
                                  :seon.test/loaded-source entities-commit})]
             (is (empty? (:seon.test.selection/unchanged old)))
             (is (= [test-symbol] (map :seon.test/sym (:seon.test.run/members old)))))))))))

(deftest ^{:seon.test/long "Two requests over three agent tests on one fixture branch: the first is held for its whole 10 s bound by a member that runs until the bound fires (the stop being proved), which then runs to its own 15 s bound; the rest is definition and admission. Measured 38.5 s on default's JVM (2026-09-24) with a 20 s exit wait since removed; the second request now waits for B's recorded exit (at most B's remaining 5 s)."
           :seon.test/long-ms 60000}
  a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it
  (support/with-database
   {:seon.test-support/extra-schema marker}
   (fn [connection]
     (let [handle (support/execution-handle connection)
           members #{'seon.test.one-request-test/a-quick
                     'seon.test.one-request-test/b-runs-until-released
                     'seon.test.one-request-test/c-after}
           unstarted 'seon.test.one-request-test/c-after
           request #(sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities members
                              :seon.test/include-long? true
                              :seon.test/check-time-limit-ms 10000})]
       (define-agent-test! handle "(clojure.test/deftest a-quick (clojure.test/is true))")
       (define-agent-test! handle
         ;; Its own 15 s bound outlasts the request's 10 s, so the request
         ;; stops on it unfinished; SCI then interrupts the body at 15 s.
         (str "(clojure.test/deftest ^{:seon.test/long \"runs until the request bound fires\" :seon.test/long-ms 15000}"
              " b-runs-until-released"
              " (if (= [\"go\"] (vec (seon.db/q '[:find [?m ...] :where [_ :seon.test.one-request-test/marker ?m]] (seon.db/db))))"
              " (clojure.test/is true) (loop [] (recur))))"))
       (define-agent-test! handle "(clojure.test/deftest c-after (clojure.test/is true))")
       (let [stopped (request)
             run-id (some #(when (= unstarted (:seon.test/sym %)) (second (:seon.test/run %)))
                          (:seon.test/results stopped))
             database (db/db connection)
             latest (first (runner/latest-results database [unstarted]))
             stop (db/q '[:find ?cause . :in $ ?id :where [?r :seon.test.run/id ?id] [?r :seon.test.run/stopped ?cause]]
                        database (second (:seon.test/run latest)))]
         (is (false? (:seon.test/passed? stopped)))
         (is (some #{unstarted} (:seon.test/pending stopped)))
         (is (nil? run-id) "the unstarted member has no captured result")
         (is (= unstarted (:seon.test/error-test-symbol latest)))
         (is (not-any? #(contains? latest %) [:seon.test/pass-count :seon.test/error-count])
             "never started is an unknown, not a red")
         (is (= :member-unfinished stop) "the run names why it stopped")
         (is (str/includes? (:seon.error/message latest) "member-unfinished"))
         (is (false? (sut/verified? database unstarted)) "an unknown never verifies"))
       ;; B's old body keeps its world until SCI interrupts it at its own
       ;; 15 s bound; no request of the cluster is admitted until the watcher
       ;; records that exit. A test world holds the program, never the
       ;; execution's data, so B is released by redefining it: the changed
       ;; entity reaches the next world, where B reruns and passes, and C runs.
       (support/await-event! connection ::unfinished-body-exited
                             (fn [database]
                               (empty? (db/q '[:find [?m ...]
                                               :where [?m :seon.test.member/completed-tx]
                                                      (not [?m :seon.test.member/terminated-tx])]
                                             database)))
                             20000)
       (define-agent-test! handle
         (str "(clojure.test/deftest ^{:seon.test/long \"runs until the request bound fires\" :seon.test/long-ms 15000}"
              " b-runs-until-released (clojure.test/is true))"))
       (let [again (request)]
         (is (some #(= unstarted (:seon.test/sym %)) (:seon.test/results again))
             "an identical later request is admitted and reruns the unstarted member")
         (is (not-any? #{unstarted} (:seon.test/pending again)))
         (is (= 1 (:seon.test/pass-count (first (runner/latest-results (db/db connection) [unstarted]))))))))))

(deftest ^{:seon.test/long "One agent test definition and one nested request stopped at admission: unmeasured; bounded by the sibling one-definition, one-request test's measured 13.8 s (define 1.0 s, request 12.6 s)."
           :seon.test/long-ms 20000}
  a-refused-recording-write-is-a-stored-core-fault-carrying-its-whole-refusal
  ;; The recording writer is the request's value (lane impl-world-injection
  ;; 2026-09-25): a writer the dependency refuses, with no caller mistake, is
  ;; stored as a core fault and the request returns the refusal beside it
  ;; (`seon.fault/fault!` returns its receipt; 0cbf4336f deleted the :panic
  ;; policy). A caller mistake stays a returned refusal (source-test's foreign
  ;; recording connection).
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           refused (atom nil)
           writer (fn [recording tx-data]
                    (reset! refused
                            (db/transact! recording
                                          (conj (vec tx-data)
                                                [:db.fn/call (fn [_] (throw (ex-info "recording writer refused"
                                                                                     {:error ::writer-refused})))]))))
           _ (define-agent-test! handle "(clojure.test/deftest refused-recording (clojure.test/is true))")
           result (sut/run {:seon.test/execution (support/execution-handle connection)
                            :seon.test/recording-connection connection
                            :seon.db/writer writer
                            :seon.test/policy :named
                            :seon.test/identities #{'seon.test.one-request-test/refused-recording}})
           refusal @refused
           ;; The refusal's own declared operation (the writer's) names the
           ;; fault; the observing boundary's (`seon.test/admit-run`) is used
           ;; only when the failure declares none (seon.error/prepare, 2f380a4d6).
           recorded {:seon.error/signature
                     (db/q '[:find ?signature . :where [?e :seon.error/operation seon.db/transact!]
                             [?e :seon.error/signature ?signature]]
                           (db/db connection))}]
       (is (= {:error ::writer-refused} (:seon.error/data (peek (:seon.error/chain refusal)))))
       (is (= refusal result) "the request returns the whole refusal, its cause chain included")
       (is (string? (:seon.error/signature recorded)) "the refused admission is a stored core fault")
       ;; The occurrence keeps the evidence inline (`:seon.error/data-edn`).
       (let [entity (db/pull (db/db connection)
                          [{:seon.error/occurrences [:seon.error/data-edn]}]
                          [:seon.error/signature (:seon.error/signature recorded)])
             occurrence (first (:seon.error/occurrences entity))
             evidence (:seon.error/data-edn occurrence)]
         (is (string? evidence) "the fault is stored with its evidence")
         (is (str/includes? evidence "writer-refused")
             "the stored evidence keeps the root cause's data"))
       (is (empty? (db/q '[:find [?m ...] :where [?m :seon.test.member/symbol seon.test.one-request-test/refused-recording]]
                         (db/db connection)))
           "the refused admission reserved nothing")))))

(deftest a-refused-request-names-the-test-it-refused
  ;; `bin/seon check` prints only the tally: an ineligible test must be named.
  (is (str/ends-with? (sut/tally {:seon.error/at (java.util.Date.)
                                  :seon.error/layer :seon.test/selection
                                  :seon.error/operation 'seon.test/select
                                  :seon.error/message "The requested test is not eligible."
                                  :seon.error/offending 'my.ns-test/a-new-test})
                      "not eligible. my.ns-test/a-new-test")))
