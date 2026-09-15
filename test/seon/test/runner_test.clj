(ns seon.test.runner-test
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [sci.core :as sci]
            [seon.fn :as program-fn]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.test.arm :as arm]
            [seon.test.cache :as cache]
            [seon.test.runner :as runner]
            [seon.test-runner-failure-fixture]
            [seon.test-support :as test-support]))

(deftest ^{:seon.test/fixture-observation
           "Verifies refusal before published-root/fresh-store acquisition and graph selection; no expensive fixture is acquired."}
  expensive-fixtures-require-a-declared-observation
  (let [root (doto (io/file "tmp" (str "fixture-reason-" (id/id))) .mkdirs)
           file (io/file root "declarations.clj")
           namespace-name (symbol (str "seon.fixture.reason-" (id/id)))
           reason "Observes store-global blob deletion, which a branch cannot isolate."
           support (program-fn/build-artifact
                    {:seon.fn.file/path "test/seon/test_support.clj"
                     :seon.fn.file/first-party-functions []})
           known (vec (keep :seon.fn/sym (:seon.fn.file/rows support)))]
       (try
         (spit file
               (str "(ns " namespace-name
                    " (:require [clojure.test :refer [deftest]] [seon.test-support :as support]))\n"
                    "(deftest unreasoned (support/populate-published-root! \"unused\"))\n"
                    "(deftest ^{:seon.test/fixture-observation " (pr-str reason)
                    "} reasoned (support/populate-published-root! \"unused\"))\n"
                    "(deftest ordinary (support/with-database (fn [_] nil)))\n"
                    "(deftest fresh (support/with-database {:seon.test-support/fresh-store? true} (fn [_] nil)))\n"))
         (load-file (str file))
         (let [manifest
               {:seon.fn.manifest/artifacts
                [support (program-fn/build-artifact
                          {:seon.fn.file/path (str file)
                           :seon.fn.file/first-party-functions known})]}
               selected #(vector (ns-resolve namespace-name %))
               expensive (#'runner/expensive-fixture-tests manifest)]
           (is (contains? expensive (str namespace-name "/unreasoned")))
           (is (contains? expensive (str namespace-name "/reasoned")))
           (is (contains? expensive (str namespace-name "/fresh")))
           (is (not (contains? expensive (str namespace-name "/ordinary"))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (#'runner/verify-fixture-observations! manifest (selected 'unreasoned))))
           (is (nil? (#'runner/verify-fixture-observations! manifest (selected 'reasoned))))
           (is (nil? (#'runner/verify-fixture-observations! manifest (selected 'ordinary))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (#'runner/verify-fixture-observations! manifest (selected 'fresh)))))
         (let [unscoped (fn [f] (binding [test/*testing-vars* []] (f)))]
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(test-support/populate-published-root!
                                    (str (io/file root "refused"))))))
           (is (not (.exists (io/file root "refused"))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(test-support/with-database
                                    {:seon.test-support/fresh-store? true} (fn [_] nil)))))
           (is (= reason (unscoped #(runner/fixture-observation!
                                     'seon.test-support/with-fresh-database
                                     {:seon.test/fixture-observation reason}))))
           (is (thrown? clojure.lang.ExceptionInfo
                        (unscoped #(runner/fixture-observation!
                                    'seon.test-support/with-fresh-database
                                    {:seon.test/fixture-observation "   "})))))
         (finally
           (remove-ns namespace-name)
           (test-support/delete-recursively! root)))))

(deftest assertion-report-uses-bounded-value-renderer
  (let [ctx (sci/init {:namespaces
                       {'large.fixture
                        (into {} (map (fn [n] [(symbol (str "value" n)) n]))
                              (range 2000))}})
        options {:seon.print/length 4 :seon.print/level 3
                 :seon.render/profile
                 {:seon.render.profile/id ::assertion
                  :seon.render.profile/token-budget 64
                  :seon.render.profile/max-depth 3
                  :seon.render.profile/max-children 3
                  :seon.render.profile/max-string-length 40
                  :seon.render.profile/composition :single-line}}
        event {:type :fail :file "runner_test.clj" :line 99
               :var #'assertion-report-uses-bounded-value-renderer
               :expected :small :actual ctx}
        capture (atom {::runner/order [] ::runner/results {}})
        counters (ref test/*initial-report-counters*)
        output
        (with-out-str
          (binding [test/*test-out* *out*
                    test/*report-counters* counters
                    test/*testing-vars* [#'assertion-report-uses-bounded-value-renderer]]
            (#'runner/capture-and-report-event!
             options capture #{'seon.test.runner-test}
             (fn [_] (throw (ex-info "Raw assertion reporter bypass" {})))
             (atom #{}) event)))
        result (first (#'runner/captured-results @capture))]
    (is (= 1 (:fail @counters)))
    (is (= 1 (:seon.test/fail-count result)))
    (is (= [(#'runner/failure-identity options
             'seon.test.runner-test/assertion-report-uses-bounded-value-renderer event)]
           (:seon.test/failing-assertions result)))
    (is (str/includes? output "assertion-report-uses-bounded-value-renderer"))
    (is (str/includes? output "runner_test.clj:99"))
    (is (str/includes? output ":seon.print/omitted"))
    (is (str/includes? output (#'runner/report-value options ctx)))
    (is (< (count output) 1500) "The small supplied profile bounds the SCI world.")
    (is (not (str/includes? output "value1999")))))

(deftest nested-runs-own-their-counters-and-evidence
  (let [namespace-name (symbol (str "seon.nested-report-probe." (id/id)))
        namespace-object (create-ns namespace-name)
        counters (ref test/*initial-report-counters*)
        events (atom [])
        contexts (atom [])
        nested (atom [])
        output (java.io.StringWriter.)
        declare-test (fn [test-name body]
                       (let [v (intern namespace-object test-name (fn []))]
                         (alter-meta! v assoc :test body)
                         v))]
    (try
      (let [children
            [(declare-test 'green #(is true))
             (declare-test 'red #(is false "expected nested red"))
             (declare-test 'interrupted
                           #(throw (InterruptedException. "expected nested interruption")))]
            outer
            (declare-test
             'outer
             (fn []
               (doseq [[v expected] (map vector children
                                        [[1 0 0] [0 1 0] [0 0 1]])]
                 (let [result (runner/run-var! v)]
                   (swap! nested conj result)
                   (swap! contexts conj (mapv :name (map meta test/*testing-vars*)))
                   (is (= expected (mapv result [:seon.test/pass-count
                                                 :seon.test/fail-count
                                                 :seon.test/error-count])))))))
            result
            (binding [test/*report-counters* counters
                      test/*testing-vars* [#'nested-runs-own-their-counters-and-evidence]
                      test/*testing-contexts* ["enclosing gate"]
                      test/*test-out* output
                      test/report #(swap! events conj %)]
              (runner/run-var! outer))]
        (is (= test/*initial-report-counters* @counters))
        (is (empty? @events) "No nested event reaches the enclosing reporter.")
        (is (= [['outer] ['outer] ['outer]] @contexts))
        (is (= (str namespace-name "/outer") (:seon.test/sym result)))
        (is (= [3 0 0] (mapv result [:seon.test/pass-count
                                     :seon.test/fail-count :seon.test/error-count])))
        (is (nil? (:seon.test/failing-assertions result)))
        (is (= [0 1 1] (mapv #(count (:seon.test/failing-assertions %)) @nested)))
        (is (str/includes? (:seon.test/failure-message (second @nested))
                           "expected nested red"))
        (is (str/includes? (:seon.test/failure-message (last @nested))
                           "expected nested interruption"))
        (is (not (str/includes? (str output) "enclosing gate"))))
      (finally (remove-ns namespace-name)))))

(deftest unused-workers-own-no-checkout
  (let [root (doto (io/file "tmp" (str "unused-workers-" (random-uuid))) .mkdirs)
        snapshot (io/file root "snapshot")
        serial (io/file root "serial")
        confirmation (io/file root "confirmation")
        admitted (delay
                   (cache/worker-checkout! (str snapshot) (str serial))
                   {::runner/worker-id "serial"})
        task {::runner/task-id "admitted"}]
    (try
      (.mkdirs (io/file snapshot "src"))
      (spit (io/file snapshot "src" "identity.clj") "immutable snapshot bytes")
      (is (= [] (#'runner/run-task-pool! nil [] admitted [] [])))
      (is (not (realized? admitted)))
      (is (not (.exists serial)))
      (is (not (.exists confirmation)))
      (with-redefs-fn
        {#'runner/execute-worker-task! (fn [_ worker admitted-task]
                                        (assoc admitted-task ::runner/executed-by
                                               (::runner/worker-id worker)))}
        #(is (= [(assoc task ::runner/executed-by "serial")]
                (#'runner/run-task-pool! nil [] admitted [] [task]))))
      (is (= "immutable snapshot bytes"
             (slurp (io/file serial "src" "identity.clj"))))
      (is (not (.exists confirmation)))
      (finally (test-support/delete-recursively! root)))))

(deftest default-red-does-not-launch-confirmation
  (let [task {::runner/task-id "default-red"
              ::runner/task-ordinal 0
              ::runner/task-namespace "seon.test-runner-failure-fixture"
              ::runner/task-symbols ["seon.test-runner-failure-fixture/failing-example"]}
        red (assoc (#'runner/run-task! task) ::runner/executed-by "pool-1")
        launches (atom 0)
        outcome (atom nil)]
    (with-out-str
      (with-redefs-fn
        {#'runner/confirmation-symbols (constantly #{})
         #'runner/run-task-pool! (fn [& _] [red])
         #'runner/confirm-parallel-failure! (fn [& _] (swap! launches inc))}
        #(reset! outcome
                 (#'runner/run-parallel-stage!
                  [] nil {:seon.fn.manifest/artifacts []} [] nil [task]))))
    (is (= 0 @launches))
    (is (= [red] (::runner/task-results @outcome)))
    (is (= 1 (get-in @outcome [::runner/task-summary ::runner/fail-count])))
    (is (= "pool-1" (::runner/executed-by red)))
    (is (= 1 (count (:seon.test/failing-assertions
                    (first (::runner/task-results red))))))
    (is (str/includes? (::runner/task-output red) "deliberate broken-test evidence"))
    (is (= [#'seon.test-runner-failure-fixture/failing-example]
           (#'runner/confirmation-vars
            [#'seon.test-runner-failure-fixture/passing-example
             #'seon.test-runner-failure-fixture/failing-example]
            #{"seon.test-runner-failure-fixture/failing-example"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'runner/confirmation-vars [] #{"missing/test"})))))

(deftest initialization-acquires-one-projection
  (test-support/preserving-instrumentation-state
   (fn []
     (doseq [initialize [#'arm/initialize-contracts! #'runner/initialize-contracts!]]
       (let [acquire @#'arm/packaged-test-projection
             acquisitions (atom [])
             initialized
             (with-redefs-fn
               {#'arm/packaged-test-projection
                (fn [role]
                  (let [projection (acquire role)]
                    (swap! acquisitions conj projection)
                    projection))}
               #(initialize "one-projection" ['seon.test.runner-test]))
             program (#'arm/declared-program-namespaces)
             armable (instrument/armable program)
             installed (instrument/instrumented)]
         (is (= 1 (count @acquisitions)) (str initialize))
         (is (identical? (first @acquisitions)
                         (:seon.test.runner/projection initialized)))
         (is (seq armable) "An absent program cannot prove complete arming.")
         (is (empty? (set/difference armable installed))
             "Every armable program Var carries its real contract wrapper."))))))
