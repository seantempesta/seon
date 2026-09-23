(ns seon.test.runner-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fn :as program-fn]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.test.runner :as runner]
            [seon.test-runner-failure-fixture]
            [seon.test-support :as test-support]))

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
        (is (= (symbol (str namespace-name) "outer") (:seon.test/sym result)))
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
               (select-keys (get by-symbol (symbol (str namespace-name) "inherits"))
                            [:seon.test/long :seon.test/long-ms]))
            "a namespace-declared long reaches the row of a test that declares nothing")
        (is (= {:seon.test/long own-reason :seon.test/long-ms 600000}
               (select-keys (get by-symbol (symbol (str namespace-name) "overrides"))
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
  "Retract the synthetic declaration; its past remains in history."
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-keyword]
                  :seon.db/transaction-report]}
  [connection schema-key]
  (test-support/transacted!
   connection [[:db/retractEntity [:seon.schema/key schema-key]]]))

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
