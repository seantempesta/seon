(ns seon.test.one-request-test
  "A test is an isolated agent for one body: `seon.test/run` acquires each
  member's branch through the agent entrance and unlinks it after the body."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster.registry :as registry]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
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
  [handle source]
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
                       :seon.cluster.eval/ns [:seon.ns/name 'seon.test.one-request-test]
                       :seon.sci.admit/caps (config/result-caps config/defaults)
                       :seon.sci.eval/time-limit-ms 5000
                       :seon.config/on-core-error :panic})))
                    ::agent-test-defined)
        row (program/declaration-row (db/carried-projection (db/db connection))
                                     (:seon.program/row evaluation) :all :agent)]
    (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
    (support/transacted! connection [row])))

(def ^:private marker
  (support/file-store-probe-schema :seon.test.one-request-test/marker))

(defn- marker-test [label]
  (str "(clojure.test/deftest writes-" label
       " (seon.db/transact! [{:seon.test.one-request-test/marker \"" label "\"}])"
       " (clojure.test/is (= #{\"" label "\"}"
       " (set (seon.db/q '[:find [?m ...] :where [_ :seon.test.one-request-test/marker ?m]] (seon.db/db))))))"))

(deftest ^{:seon.test/long "Three nested one-body requests, each acquiring its program once at a new commit (0.8 s each on a scratch cluster, 2026-09-23, lane-realities-commit-4; docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md)."
           :seon.test/long-ms 15000}
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
