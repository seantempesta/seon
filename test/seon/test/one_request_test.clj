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
    (is (nil? db/*conn*) "a host test body inherits no cluster custody")
    (is (contains? (registry/roster store) (:seon.agent/branch handle))
        "the member's branch is on the store roster while its body runs")))

(defn- define-agent-test!
  "Evaluate one deftest as an agent does and write its canonical declaration row."
  ([handle source] (define-agent-test! handle source 'seon.test.one-request-test))
  ([handle source namespace-name]
  (let [connection (:seon.db/connection handle)
        ;; The member's own SCI arm governs this body thread, so the fixture
        ;; context evaluates on a thread of its own, as a separate agent does.
        evaluation (support/await-event!
                    (future
                     (db/call-with-custody
                      {:seon.db/connection connection}
                      #(sci.eval/evaluate
                      {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                       :seon.cluster.eval/source source
                       :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                       :seon.sci.admit/caps (config/result-caps config/defaults)
                       :seon.sci.eval/time-limit-ms 5000
                       :seon.config/on-core-error :panic})))
                    ::agent-test-defined)
        row (program/declaration-row (db/carried-projection (db/db connection))
                                     (:seon.program/row evaluation) :all :agent)]
    (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
    (support/transacted! connection [row]))))

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
           (is (true? (:seon.test/passed? result)) (sut/tally result))
           (is (= 2 (:seon.test/executed-count result)))
           (is (= 2 (count (set branches))))
           (is (not-any? (set (registry/roster store)) branches)
               "every member branch is unlinked once its body exits")
           (is (empty? (db/q '[:find [?m ...] :where [_ :seon.test.one-request-test/marker ?m]]
                             (db/db connection)))
               "no member write reaches the execution branch")))
       (testing "unchanged green members are answered from the record"
         (let [again (request #{'seon.test.one-request-test/writes-a})]
           (is (true? (:seon.test/passed? again)) (sut/tally again))
           (is (= 0 (:seon.test/executed-count again)))
           (is (= 1 (:seon.test/reused-count again)))
           (is (every? :seon.test/unchanged (:seon.test/results again)))))
       (testing "a request whose bound fires before a member starts leaves it pending"
         (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                                :seon.test/recording-connection connection
                                :seon.test/policy :named
                                :seon.test/identities #{'seon.test.one-request-test/writes-c}
                                :seon.test/check-time-limit-ms 1})]
           (is (false? (:seon.test/passed? result)) (sut/tally result))
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
         (is (= ['seon.id/c4-long] (map :seon.test/sym (:seon.test/long-excluded result)))
             (sut/tally result))
         (is (= ['seon.id/c4-short] (map :seon.test/sym (:seon.test/results result))))
         (is (false? (:seon.test/passed? result))
             "a skipped declared-long obligation is unfulfilled")
         (is (str/includes? (sut/tally result) "long seon.id/c4-long")))))))

(deftest a-body-outliving-its-bound-is-released-only-after-it-exits
  ;; A timeout is not termination: the request answers at the bound, and the
  ;; member's resources are released by a watcher that observes actual exit.
  (let [gate (java.util.concurrent.CountDownLatch. 1)
        released (promise)
        handle (support/execution-handle nil)
        body (fn [] (is (.await gate 20 java.util.concurrent.TimeUnit/SECONDS)))
        gated (intern 'seon.test.one-request-test (with-meta 'gated-probe {:test body}) nil)
        result (#'sut/bounded-result gated 100 {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)}
                                     (fn [watched?] (deliver released watched?) nil))]
      (try
        (is (false? (:seon.test.run/terminated? result)) "the bound answered before the body exited")
        (is (not (realized? released)) "nothing is released while the body is live")
        (.countDown gate)
        (is (true? (support/await-event! (future (deref released)) ::released))
            "the watcher released after observing exit")
        (finally (.countDown gate) (ns-unmap 'seon.test.one-request-test 'gated-probe)))))

(deftest ^{:seon.test/long "An admission, two recordings and two requests on one fixture branch: 23.3 s measured at 15ffb4936 on a scratch cluster, dominated by the routed per-request acquisition and per-connection projection costs."
           :seon.test/long-ms 35000}
  an-unexited-member-is-never-started-again-until-its-exit-is-recorded
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           test-symbol 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
           request #(sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{test-symbol}})
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
       (is (not (:seon.error/at (record! false))) "the unfinished member is recorded without its exit")
       (let [held (request)]
         (is (= [test-symbol] (:seon.test/pending held)) (sut/tally held))
         (is (= 0 (:seon.test/executed-count held)) "the same test never starts beside a live body")
         (is (false? (:seon.test/passed? held))))
       (is (not (:seon.error/at (record! true))) "the watcher records the observed exit")
       (let [after (request)]
         (is (= 1 (:seon.test/executed-count after)) (sut/tally after))
         (is (true? (:seon.test/passed? after))))))))

(deftest ^{:seon.test/long "One request that acquires its handle and a member branch before its setup throws: 7.6 s measured at 15ffb4936 on a scratch cluster, dominated by the routed per-request acquisition cost."
           :seon.test/long-ms 15000}
  setup-that-throws-still-releases-the-member-branch
  (let [store (:seon.store/store (support/execution-handle nil))
        before (set (registry/roster store))
        failure (try
                  (with-redefs [sut/resolve-test (fn [_] (throw (ex-info "resolution exploded" {::probe true})))]
                    (sut/run {:seon.test/execution (support/execution-handle nil)
                              :seon.test/recording-connection (:seon.db/connection (support/execution-handle nil))
                              :seon.test/policy :named
                              :seon.test/identities #{'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol}}))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= "resolution exploded" (ex-message failure)) "the setup failure propagates whole")
    (is (true? (::probe (ex-data failure))))
    (is (= before (set (registry/roster store)))
        "every member and request branch the failed request acquired is unlinked")))
