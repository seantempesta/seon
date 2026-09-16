(ns seon.test.runner-test
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fn :as program-fn]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.test.arm :as arm]
            [seon.test.cache :as cache]
            [seon.schema :as schema]
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
                    {:seon.fn/source-path "test/seon/test_support.clj"
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
                          {:seon.fn/source-path (str file)
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

(deftest the-platform-tier-declares-no-destructive-drill
  ;; The platform tier runs FIRST on every bin/test invocation. A test there
  ;; that deletes a filesystem path deletes before the run has produced any
  ;; evidence — on 2026-09-17 that emptied the development store
  ;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
  ;; The owners are RESOLVED against the program graph and the reach is
  ;; derived from :seon.fn/calls, so neither a rename nor metadata drift can
  ;; leave the checker walking to nothing and reporting the tier healthy.
  (let [root (doto (io/file "tmp" (str "destructive-tier-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.destructive-" (id/id)))
        support (program-fn/build-artifact
                 {:seon.fn/source-path "test/seon/test_support.clj"
                  :seon.fn.file/first-party-functions []})
        operator (program-fn/build-artifact
                  {:seon.fn/source-path "src/seon/operator.clj"
                   :seon.fn.file/first-party-functions []})
        known (vec (keep :seon.fn/sym (concat (:seon.fn.file/rows support)
                                              (:seon.fn.file/rows operator))))]
    (try
      (spit file
            (str "(ns " namespace-name
                 " (:require [clojure.test :refer [deftest]] [seon.test-support :as support]))\n"
                 "(defn- indirect [] (support/populate-published-root! \"unused\"))\n"
                 "(deftest ^{:seon.test/platform \"probe\"} drill (indirect))\n"
                 "(deftest ^{:seon.test/platform \"probe\"} ordinary"
                 " (support/with-database (fn [_] nil)))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [support operator
                       (program-fn/build-artifact
                        {:seon.fn/source-path (str file)
                         :seon.fn.file/first-party-functions known})]}
            selected #(vector (ns-resolve namespace-name %))
            rows (#'runner/manifest-rows manifest)]
        (let [owner-rows (#'runner/destructive-owner-rows rows)
              owners (into {} (map (juxt :seon.fn/sym :seon.fn/destroys)) owner-rows)]
          (is (seq owner-rows) "the analyzed source declares its destructive owners")
          (is (every? #(and (string? %) (seq %)) (vals owners))
              "every owner says what it destroys")
          (is (contains? owners "seon.test-support/populate-published-root!")
              "the declaration at the definition is admitted as a program fact")
          (is (str/includes? (get owners "seon.test-support/populate-published-root!" "")
                             "store")
              (pr-str owners)))
        (let [drifted (mapv #(dissoc % :seon.fn/destroys) rows)
              refusal (try (#'runner/destructive-owner-rows drifted)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "a program declaring nothing refuses instead of reporting the tier clean")
          (is (= :seon.test.runner/missing-destructive-owners
                 (:seon.error/kind (ex-data refusal)))))
        (let [refusal (try (#'runner/verify-platform-tier-carries-no-destructive-drill!
                            manifest (selected 'drill))
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))
              offender (first (:seon.test.runner/destructive-platform-tests
                               (ex-data refusal)))]
          (is (some? refusal) "a platform test reaching a destructive owner refuses")
          (is (= (str namespace-name "/drill") (:seon.test/sym offender)))
          (is (= [(str namespace-name "/drill")
                  (str namespace-name "/indirect")
                  "seon.test-support/populate-published-root!"]
                 (:seon.test.runner/destructive-path offender))
              "the refusal carries the call path to the owner")
          (is (str/includes? (ex-message refusal) (str namespace-name "/drill"))))
        (is (nil? (#'runner/verify-platform-tier-carries-no-destructive-drill!
                   manifest (selected 'ordinary)))
            "an ordinary platform test is admitted"))
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

(deftest executor-submissions-carry-the-callers-handed-projection
  ;; `on-caller-loader` pinned the submitting thread's CLASSLOADER and
  ;; conveyed nothing else, so any runner work that hopped to an executor
  ;; thread ran with no handed projection at all: seon.db then reported
  ;; Datahike's base attributes as the only registered candidates. The
  ;; class this kills is "a wrapper that conveys one part of the caller's
  ;; frame" — the pinned loader without the bindings that came with it.
  (test-support/with-database
   (fn [_]
     (let [handed (schema/handed-projection)
           executor (java.util.concurrent.Executors/newSingleThreadExecutor)
           observed (promise)]
       (is (some? handed) "The fixture hands its projection to this thread.")
       (try
         (.execute executor
                   ^Runnable (#'runner/on-caller-loader
                              (fn [] (deliver observed (schema/handed-projection)))))
         (is (identical? handed
                         (deref observed
                                (long (* 1000 test-support/event-backstop-seconds))
                                ::never-arrived)))
         (finally (.shutdownNow executor)))))))

(deftest the-exchange-bound-derives-from-the-long-declaration
  ;; §2.3: a bound that ignores the declaration is a tuned constant standing
  ;; in for an observable event. A `:seon.test/long` test says it runs past
  ;; the ordinary per-exchange bound; `:seon.test/long-ms` says how long, so
  ;; the bound derives from the declaration instead of expiring the test the
  ;; program already admitted would take longer.
  (let [root (doto (io/file "tmp" (str "long-bound-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.long-bound-" (id/id)))
        reason "Boots two co-hosted clusters against a real store."
        plain-reason "Forks a published root once."
        allowance-ms 900000
        support (program-fn/build-artifact
                 {:seon.fn/source-path "test/seon/test_support.clj"
                  :seon.fn.file/first-party-functions []})
        known (vec (keep :seon.fn/sym (:seon.fn.file/rows support)))]
    (try
      (spit file
            (str "(ns " namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ^{:seon.test/long " (pr-str reason)
                 " :seon.test/long-ms " allowance-ms "} allowed (is true))\n"
                 "(deftest ^{:seon.test/long " (pr-str plain-reason)
                 "} declared (is true))\n"
                 "(deftest ordinary (is true))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [support (program-fn/build-artifact
                                {:seon.fn/source-path (str file)
                                 :seon.fn.file/first-party-functions known})]}
            declarations (#'runner/long-declarations manifest)
            all-vars (mapv #(ns-resolve namespace-name %)
                           '[allowed declared ordinary])
            task-for (fn [name-symbol]
                       (first (#'runner/test-tasks
                               all-vars [(ns-resolve namespace-name name-symbol)]
                               declarations)))
            default-bound (#'runner/exchange-bound-seconds)]
        (is (= {(str namespace-name "/allowed")
                {:seon.test/long reason :seon.test/long-ms allowance-ms}
                (str namespace-name "/declared")
                {:seon.test/long plain-reason}}
               declarations)
            "both halves of the declaration are lifted onto the program row")
        (let [task (task-for 'allowed)
              bound (#'runner/task-exchange-bound-seconds task)]
          (is (true? (::runner/task-long? task)))
          (is (= allowance-ms (::runner/task-long-ms task)))
          (is (= 900 bound)
              "the declared allowance, not the default, bounds the exchange")
          (is (> bound default-bound))
          (let [notice (#'runner/task-bound-notice "pool-1" task bound)]
            (is (str/includes? notice "bound=900s") notice)
            (is (str/includes? notice ":seon.test/long-ms 900000") notice)
            (is (str/includes? notice reason) notice)))
        (let [task (task-for 'declared)]
          (is (true? (::runner/task-long? task)))
          (is (nil? (::runner/task-long-ms task)))
          (is (= default-bound (#'runner/task-exchange-bound-seconds task))
              "a declaration without an allowance keeps the default bound")
          (is (str/includes? (#'runner/task-bound-notice "pool-1" task default-bound)
                             "(default per-exchange bound)")))
        (let [task (task-for 'ordinary)]
          (is (false? (::runner/task-long? task)))
          (is (= default-bound (#'runner/task-exchange-bound-seconds task))
              "an undeclared test keeps the default bound"))
        (is (nil? (#'runner/verify-long-declarations-indexed! declarations all-vars))
            "the indexed rows agree with the Vars that declared them")
        (let [refusal (try (#'runner/verify-long-declarations-indexed!
                            (dissoc declarations (str namespace-name "/allowed"))
                            all-vars)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "an unindexed declaration refuses instead of reading the row as NOT-LONG")
          (is (= [(str namespace-name "/allowed")]
                 (mapv :seon.test/sym
                       (:seon.test.runner/drifted-long-declarations
                        (ex-data refusal)))))))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest a-declared-long-exchange-widens-the-silence-horizon
  ;; The suite watchdog and the per-exchange bound cannot both be tuned
  ;; constants: when a declared allowance exceeds the silence horizon, the
  ;; watchdog would dump every JVM for a wait the program declared legal.
  (let [progress (atom {::runner/description "probe"
                        ::runner/at-nanos (System/nanoTime)})]
    (swap! progress assoc-in [::runner/silence-allowances "task-a"] 630)
    (#'runner/announce! progress "BEGIN probe")
    (is (= {"task-a" 630} (::runner/silence-allowances @progress))
        "an announcement never drops a declared allowance from the horizon")
    (swap! progress update ::runner/silence-allowances dissoc "task-a")
    (is (= {} (::runner/silence-allowances @progress)))))

(deftest a-namespace-declared-long-reaches-every-test-row
  ;; A namespace of real-boot drills declares the cost ONCE on its ns form
  ;; (seon.cluster.armed-test, concurrency-streams, program-restart, …). The
  ;; Var-side reader inherited that; the static indexer read only the deftest
  ;; Var, so a published base answered NOT-LONG for twelve real drills and
  ;; the row-reading checker refused every cold gate. One rule now serves both
  ;; seams: seon.program/test-markers, deftest winning per attribute.
  (let [root (doto (io/file "tmp" (str "ns-long-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.ns-long-" (id/id)))
        namespace-reason "Every test here boots a real cluster."
        own-reason "This one also forks a published root."]
    (try
      (spit file
            (str "(ns ^{:seon.test/long " (pr-str namespace-reason)
                 " :seon.test/long-ms 600000} " namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest inherits (is true))\n"
                 "(deftest ^{:seon.test/long " (pr-str own-reason)
                 "} overrides (is true))\n"))
      (let [rows (:seon.fn.file/rows
                  (program-fn/build-artifact
                   {:seon.fn/source-path (str file)
                    :seon.fn.file/first-party-functions []}))
            by-symbol (into {} (keep (fn [row]
                                       (when-let [s (:seon.test/sym row)]
                                         [s row])))
                            rows)]
        (is (= 2 (count by-symbol)) (pr-str (keys by-symbol)))
        (is (= {:seon.test/long namespace-reason :seon.test/long-ms 600000}
               (select-keys (get by-symbol (str namespace-name "/inherits"))
                            [:seon.test/long :seon.test/long-ms]))
            "a namespace-declared long reaches the row of a test that declares nothing")
        (is (= {:seon.test/long own-reason :seon.test/long-ms 600000}
               (select-keys (get by-symbol (str namespace-name "/overrides"))
                            [:seon.test/long :seon.test/long-ms]))
            "the deftest's own reason wins while it still inherits the allowance"))
      (finally
        (test-support/delete-recursively! root)))))

(deftest one-rule-answers-both-lifting-seams
  ;; The static indexer and the loaded-Var indexer must not be able to
  ;; disagree about what a test declared.
  (is (= {} (program/test-markers nil nil)))
  (is (= {:seon.test/long "ns"} (program/test-markers {} {:seon.test/long "ns"})))
  (is (= {:seon.test/long "var"}
         (program/test-markers {:seon.test/long "var"} {:seon.test/long "ns"}))
      "the deftest wins on conflict")
  (is (= {:seon.test/long "var" :seon.test/long-ms 42}
         (program/test-markers {:seon.test/long "var"} {:seon.test/long-ms 42}))
      "each attribute is decided on its own")
  (is (= {} (program/test-markers {:seon.test/long nil} {}))
      "a declared nil is no declaration")
  (is (= {:seon.test/platform "ns"}
         (program/test-markers {} {:seon.test/platform "ns"}))
      "the platform marker is lifted by the same one rule")
  (is (= {:seon.test/fixture "ns"}
         (program/test-markers {} {:seon.test/fixture "ns"}))
      "so is the fixture marker, which a namespace declares once")
  (is (= {:seon.test/platform "var"}
         (program/test-markers {:seon.test/platform "var"}
                               {:seon.test/platform "ns"}))
      "the deftest wins on conflict for every marker"))

(deftest the-platform-tier-partitions-on-the-indexed-fact-not-var-metadata
  ;; The tier partition used to read Var metadata while the long marker read
  ;; the published row. One partition, two authorities: a base that never
  ;; indexed the declaration still answered PLATFORM from the Var, so nothing
  ;; could refuse the drift the `long` side already refuses. The fact is the
  ;; authority now, and the Vars below keep their metadata precisely so a
  ;; partition that fell back to it would fail this test.
  (let [root (doto (io/file "tmp" (str "platform-fact-" (id/id))) .mkdirs)
        file (io/file root "declarations.clj")
        namespace-name (symbol (str "seon.fixture.platform-" (id/id)))
        namespace-reason "Every test here exercises the boot sequence."
        own-reason "This one also re-arms instrumentation."]
    (try
      (spit file
            (str "(ns ^{:seon.test/platform " (pr-str namespace-reason) "} "
                 namespace-name
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest inherits (is true))\n"
                 "(deftest ^{:seon.test/platform " (pr-str own-reason)
                 "} overrides (is true))\n"))
      (load-file (str file))
      (let [manifest {:seon.fn.manifest/artifacts
                      [(program-fn/build-artifact
                        {:seon.fn/source-path (str file)
                         :seon.fn.file/first-party-functions []})]}
            declarations (#'runner/platform-declarations manifest)
            inherits (str namespace-name "/inherits")
            overrides (str namespace-name "/overrides")
            all-vars (mapv #(ns-resolve namespace-name %) '[inherits overrides])
            partition-with
            (fn [rows]
              (#'runner/test-selection
               [namespace-name]
               {::runner/include-long? true
                ::runner/long-declarations {}
                ::runner/platform-declarations rows
                ::runner/selected-symbols :all}))]
        (is (= {inherits {:seon.test/platform namespace-reason}
                overrides {:seon.test/platform own-reason}}
               declarations)
            "a namespace-declared platform reason reaches every test row, and the deftest's own reason wins")
        (let [selection (partition-with declarations)]
          (is (= #{inherits overrides}
                 (into #{} (map (comp str #'runner/var-symbol))
                       (::runner/platform selection)))
              "the partition selects the platform tier from the indexed rows")
          (is (empty? (::runner/selected selection))))
        (let [selection (partition-with (dissoc declarations overrides))]
          (is (= #{inherits}
                 (into #{} (map (comp str #'runner/var-symbol))
                       (::runner/platform selection)))
              "a row the manifest does not carry is NOT platform, however the Var is annotated")
          (is (= #{overrides}
                 (into #{} (map (comp str #'runner/var-symbol))
                       (::runner/selected selection)))))
        (is (nil? (#'runner/verify-platform-declarations-indexed!
                   declarations all-vars))
            "the indexed rows agree with the Vars that declared them")
        (let [refusal (try (#'runner/verify-platform-declarations-indexed!
                            (dissoc declarations overrides) all-vars)
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "an unindexed declaration refuses instead of running a platform regression in the bulk tier")
          (is (= [overrides]
                 (mapv :seon.test/sym
                       (:seon.test.runner/drifted-platform-declarations
                        (ex-data refusal)))))))
      (finally
        (remove-ns namespace-name)
        (test-support/delete-recursively! root)))))

(deftest the-bare-namespace-set-is-derived-from-indexed-facts
  ;; `find test -name '*_test.clj'` answered gate membership before, and was
  ;; wrong in both directions: it missed `seon.repl-parity-test`, whose
  ;; deftests a macro emits so the file indexes a namespace and zero
  ;; `:seon.test/sym` rows, and "has test rows" would have admitted
  ;; `seon.test-runner-failure-fixture`, whose `failing-example` asserts
  ;; (= 5 (+ 2 2)) on purpose. Membership and exclusion are different facts.
  (let [root (doto (io/file "tmp" (str "bare-set-" (id/id))) .mkdirs)
        gate-file (io/file root "gate.clj")
        macro-file (io/file root "macro.clj")
        fixture-file (io/file root "fixture.clj")
        source-file (io/file root "source.clj")
        gate-ns (symbol (str "seon.fixture.bare-gate-" (id/id)))
        macro-ns (symbol (str "seon.fixture.bare-macro-" (id/id)))
        fixture-ns (symbol (str "seon.fixture.bare-fixture-" (id/id)))
        source-ns (symbol (str "seon.fixture.bare-source-" (id/id)))
        fixture-reason "Deliberate failure evidence another test asserts over."
        ;; The artifacts carry canonically built rows; only the relative path
        ;; is re-keyed, because the path root IS the input under test and
        ;; `build-artifact` records the probe's own tmp location.
        rooted (fn [source-path published-path]
                 (assoc (program-fn/build-artifact
                         {:seon.fn/source-path source-path
                          :seon.fn.file/first-party-functions []})
                        :seon.fn.file/relative-path published-path))]
    (try
      (spit gate-file
            (str "(ns " gate-ns " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ordinary (is true))\n"))
      ;; Indexes a namespace and no test rows: its deftests are macro-emitted.
      (spit macro-file (str "(ns " macro-ns ")\n(defn emitted [] true)\n"))
      (spit fixture-file
            (str "(ns ^{:seon.test/fixture " (pr-str fixture-reason) "} "
                 fixture-ns
                 " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest deliberately-red (is (= 5 (+ 2 2))))\n"))
      (spit source-file (str "(ns " source-ns ")\n(defn ordinary [] true)\n"))
      (let [fixture-artifact (rooted (str fixture-file) "test/fixture.clj")
            manifest {:seon.fn.manifest/artifacts
                      [(rooted (str gate-file) "test/gate.clj")
                       (rooted (str macro-file) "test/macro.clj")
                       fixture-artifact
                       (rooted (str source-file) "src/source.clj")]}
            derived (#'runner/bare-namespaces manifest)]
        (is (some :seon.test/fixture (:seon.fn.file/rows fixture-artifact))
            "the marker reaches the row through the same one lifting rule")
        (is (= [gate-ns macro-ns] derived)
            "every namespace indexed under the test root, fixture namespaces excluded, source namespaces never admitted")
        (let [refusal (try (#'runner/bare-namespaces
                            {:seon.fn.manifest/artifacts
                             [(rooted (str source-file) "src/source.clj")]})
                           nil
                           (catch clojure.lang.ExceptionInfo failure failure))]
          (is (some? refusal)
              "a manifest with no test-rooted namespace refuses instead of running nothing and reporting success")
          (is (= :seon.test.runner/no-bare-namespaces
                 (:seon.error/kind (ex-data refusal))))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest the-deliberate-failure-fixtures-declare-their-exclusion
  ;; The bare gate no longer excludes these by filename, so the two
  ;; namespaces whose tests are material rather than members must say so.
  ;; Losing either declaration turns the bare gate permanently red.
  (doseq [namespace-name '[seon.test-runner-failure-fixture my.examples-fixture]]
    (require namespace-name)
    (let [reason (:seon.test/fixture (meta (find-ns namespace-name)))]
      (is (string? reason) (str namespace-name " declares no :seon.test/fixture reason"))
      (is (not (str/blank? reason))))))

;;; ---------------------------------------------------------------------------
;;; The schema restore derives from FACTS
;;; ---------------------------------------------------------------------------

(defn- probe-environment
  "One cluster snapshot entry shaped exactly like `live-cluster-schema-states`:
  the connection whose facts decide the projection, and the projection value."
  [connection projection]
  (env/environment {:seon.boot/cluster-name "restore-probe"
                    :seon.db/connection connection
                    :seon.schema/projection projection
                    :seon.db/basis-t (db/basis-t (db/db connection))}))

(defn- declare-schema-key!
  "Commit one synthetic declaration the way the canonical rows express it."
  [connection schema-key]
  (test-support/transacted!
   connection (schema/canonical-schema-rows {schema-key :string})))

(defn- retract-schema-key!
  "Retract one declaration's definition fact. The identity row survives as a
  tombstone (ruling 47); the projection loses the key because its form is gone."
  [connection schema-key]
  (let [row (db/pull (db/db connection) [:db/id :seon.schema/form]
                     [:seon.schema/key schema-key])]
    (test-support/transacted!
     connection [[:db/retract (:db/id row) :seon.schema/form
                  (:seon.schema/form row)]])))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-committed-retraction-survives-the-restore-and-is-named-a-committed-change
  ;; 2026-09-17: a probe turn deliberately retracted four declarations from
  ;; `default` DURING a run, and the restore put them back from its entering
  ;; snapshot — a mirror acting against the writer (AGENTS §2.1). The
  ;; projection then disagreed with the cluster's own committed facts until
  ;; someone advanced it by hand.
  (test-support/with-database
    (fn [connection]
      (let [retracted :seon.test.runner-test.probe/retracted-during-run
            added :seon.test.runner-test.probe/added-during-run]
        (declare-schema-key! connection retracted)
        (let [entering (schema/projection-from-database (db/db connection))
              state (atom (probe-environment connection entering))
              before {"restore-probe" [state @state]}]
          (is (contains? (:seon.schema.projection/forms entering) retracted)
              "the run enters with the declaration its facts declared")
          ;; The run commits both directions while it is in flight.
          (retract-schema-key! connection retracted)
          (declare-schema-key! connection added)
          (let [report (runner/restore-live-cluster-schema! before)
                row (first report)
                forms (:seon.schema.projection/forms (:seon.schema/projection @state))]
            (is (= 1 (count report)) (pr-str report))
            (is (= "restore-probe" (:seon.cluster/name row)))
            (is (not (contains? forms retracted))
                "the declaration its own writer retracted STAYS retracted")
            (is (contains? forms added)
                "and the one its writer added is in the projection the cluster now holds")
            (is (= [(str retracted)] (:seon.test.runner/committed-removed row))
                "the retraction is named a committed change, by key")
            (is (= [(str added)] (:seon.test.runner/committed-added row))
                "so is the addition")
            (is (nil? (:seon.test.runner/drift-added row)))
            (is (nil? (:seon.test.runner/drift-removed row)))
            (is (empty? (runner/schema-restore-drift report))
                "a committed change is the writer doing its job, never a test error")))))))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-registration-that-committed-nothing-is-restored-away-and-named-drift
  ;; The other direction, and the original disease: a run registers a
  ;; declaration into the projection a live cluster's writer compiles
  ;; against, commits nothing, and every later write on that cluster is
  ;; refused. The facts never held the key, so the facts remove it.
  (test-support/with-database
    (fn [connection]
      (let [leaked :seon.test.runner-test.probe/never-committed
            entering (schema/projection-from-database (db/db connection))
            state (atom (probe-environment connection entering))
            before {"restore-probe" [state @state]}]
        (swap! state update-in [:seon.schema/projection :seon.schema.projection/forms]
               assoc leaked [:string {:seon.db/identity true}])
        (let [report (runner/restore-live-cluster-schema! before)
              row (first report)]
          (is (= 1 (count report)) (pr-str report))
          (is (= [(str leaked)] (:seon.test.runner/drift-added row))
              "the leaked key is named, not merely counted")
          (is (nil? (:seon.test.runner/committed-removed row)))
          (is (not (contains? (:seon.schema.projection/forms
                               (:seon.schema/projection @state))
                              leaked))
              "and the cluster is left holding what its facts declare")
          (is (= report (runner/schema-restore-drift report))
              "an uncommitted registration IS the run's own failure"))))))

(deftest ^{:seon.test/platform
           "Moving part: the live cluster projection an in-process run leaves behind."}
  a-run-that-touched-no-declaration-reports-nothing
  (test-support/with-database
    (fn [connection]
      (let [entering (schema/projection-from-database (db/db connection))
            state (atom (probe-environment connection entering))]
        (is (empty? (runner/restore-live-cluster-schema!
                     {"restore-probe" [state @state]}))
            "no schema activity, nothing to report")
        (is (= entering (:seon.schema/projection @state))
            "and nothing to change"))))
  ;; A snapshot with no connection has no authority to derive from. That is
  ;; the typed unknown, never silence (AGENTS §2.4).
  (let [environment (env/environment {:seon.boot/cluster-name "restore-probe"})
        report (runner/restore-live-cluster-schema!
                {"restore-probe" [(atom environment) environment]})]
    (is (= 1 (count report)))
    (is (string? (:seon.test.runner/schema-authority-unavailable (first report))))
    (is (empty? (runner/schema-restore-drift report))
        "an unavailable observation is not a test failure")))
