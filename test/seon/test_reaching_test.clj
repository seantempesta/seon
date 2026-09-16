(ns seon.test-reaching-test
  (:require [clojure.java.io]
            [clojure.test :as t :refer [deftest is]]
            [malli.instrument]
            [seon.db :as db]
            [seon.config]
            [seon.sci.eval]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.instrument]
            [seon.test :as sut]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest reach-digests-follow-only-changed-closures
 (support/with-database
  (fn [connection]
   (let [a "reach.fixture/a" b "reach.fixture/b" ta "reach.fixture/a-test" tb "reach.fixture/b-test"
         rows [{:db/id "fn-a" :seon.fn/ns [:seon.ns/name 'seon.test-reaching-test] :seon.schema.admission/source :core :seon.fn/sym a :seon.fn/source "(defn a [x] x)" :seon.fn/spec "[:=> [:cat :seon.test/sym] :seon.test/sym]"}
               {:db/id "fn-b" :seon.fn/ns [:seon.ns/name 'seon.test-reaching-test] :seon.schema.admission/source :core :seon.fn/sym b :seon.fn/source "(defn b [x] x)" :seon.fn/spec "[:=> [:cat :int] :int]"}
               {:seon.schema.admission/source :core :seon.test/sym ta :seon.test/source "(deftest a-test (is (= \"x\" (a \"x\"))))" :seon.test/subject "fn-a"}
               {:seon.schema.admission/source :core :seon.test/sym tb :seon.test/source "(deftest b-test (is (= 1 (b 1))))" :seon.fn/calls ["fn-b"]}]
         _ (is (:db-after (db/transact! connection rows)))
         initial (runner/reach-digests (db/db connection) [ta tb])
         stats (fn [] (select-keys (:seon.test.runner/reach-index
                                   (meta (:seon.sci.eval/projection-state (meta (db/db connection)))))
                                   [:seon.test.runner/reach-updated :seon.test.runner/reach-computed]))]
    (is (= 2 (count initial)))
    (is (= initial (runner/reach-digests (db/db connection) [ta tb])))
    (is (= 0 (:seon.test.runner/reach-computed (stats))))
    (doseq [tx [[[:db/add [:seon.fn/sym a] :seon.fn/source "(defn a [x] (str x))"]]
                [[:db/add [:seon.fn/sym a] :seon.fn/spec "[:=> [:cat :seon.test/sym] :string]"]]
                [[:db/add [:seon.schema/key :seon.test/sym] :seon.schema/form "[:string {:min 2}]"]]]]
     (let [before (runner/reach-digests (db/db connection) [ta tb])]
      (is (:db-after (db/transact! connection tx)))
      (let [after (runner/reach-digests (db/db connection) [ta tb])]
       (is (not= (get before ta) (get after ta)))
       (is (= (get before tb) (get after tb)))
       (is (= 1 (:seon.test.runner/reach-computed (stats)))))))
    (let [before (runner/reach-digests (db/db connection) [ta tb])]
     (is (:db-after (db/transact! connection [[:db/add [:seon.test/sym ta] :seon.test/pass-count 1]])))
     (is (= before (runner/reach-digests (db/db connection) [ta tb])))
     (is (= {:seon.test.runner/reach-updated 0 :seon.test.runner/reach-computed 0} (stats))))))))

(deftest reaching-is-the-program-graph-relation
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            actual (sut/reaching {:seon.db/db database
                                  :seon.test/changed ['my.note/add!]})]
        (is (:db/id (db/pull database [:db/id] [:seon.fn/sym "my.note/add!"])))
        (is (vector? actual))
        (is (= (set (functions/tests-reaching database "my.note/add!")) (set actual)))
        (is (= :seon.test/unknown
               (:seon.error/kind
                (sut/reaching {:seon.db/db database
                               :seon.test/changed ['absent.function/no-row]}))))))))

(defn- with-test [connection body assertion]
  (support/seed-cluster! connection "default")
  (let [namespace-name (symbol (str "reaching.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (str namespace-name "/probe")
        source (list 'clojure.test/deftest 'probe body)
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (support/transacted! connection
                           [{:seon.ns/name namespace-name}
                            {:seon.test/sym test-symbol
                             :seon.schema.admission/source :core
                             :seon.test/ns [:seon.ns/name namespace-name]
                             :seon.test/source (pr-str source)}])
      (assertion test-symbol test-var)
      (finally (remove-ns namespace-name)))))

(deftest unchanged-closures-reuse-green-results
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is (= 4 (+ 2 2)))
        (fn [s v]
          (let [request {:seon.db/connection connection
                         :seon.test/namespaces [(symbol (namespace (symbol s)))]}
                before (db/db connection)
                first-result (sut/check request)
                second-result (sut/check request)
                current (db/db connection)]
            (is (= [s] (:seon.test/passed first-result)) (pr-str first-result))
            (is (= [] (:seon.test/tests second-result)) (pr-str second-result))
            (is (= 1 (:seon.test/skipped-count second-result)))
            (is (.contains (sut/feedback second-result) "unchanged reach digest"))
            (is (= (sut/reach-digest before s)
                   (:seon.test/reach-digest (db/pull current '[*] [:seon.test/sym s]))))
            (is (true? (sut/verified? current s)))
            (is (not (some #{s} (sut/stale current))))
            (is (:db-after (db/transact! connection
                             [[:db/add [:seon.test/sym s] :seon.test/source
                               "(clojure.test/deftest probe (clojure.test/is (= 5 (+ 2 3))))"]])))
            (is (some #{s} (sut/stale (db/db connection))))
            (is (false? (sut/verified? (db/db connection) s)))
            (is (= [s] (:seon.test/tests (sut/check request))))))))))

(deftest concurrent-completions-use-the-canonical-program
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            s "seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol"
            completion (assoc (runner/provenance database)
                              :seon.test.runner/results [{:seon.test/sym s
                                :seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0}])
            expected {s (sut/reach-digest database s)}
            workers (mapv (fn [_] (future (#'runner/completion-reach-digests completion))) (range 2))]
        (try
          (doseq [worker workers]
            (let [result (support/await-event! worker :reach-digest/completion)]
              (is (= expected (:seon.test/reach-digests result)))
              (is (= (:seon.test.run/program-digest completion)
                     (:seon.test.run/program-digest result)))))
          (finally (doseq [worker workers] (future-cancel worker))))))))

(deftest transported-results-retain-the-tested-database-digest
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [s v]
          (let [tested (db/db connection)
                provenance (runner/provenance tested)
                digest (sut/reach-digest tested s)
                result (runner/run-var! v)
                transported (#'runner/completion-reach-digests
                              (assoc provenance :seon.test/reach-digests {s digest}
                                     :seon.test/reaches (runner/reach-memberships tested [s])
                                     :seon.test.runner/results [result]))]
            (is (:db-after (db/transact! connection
                             [[:db/add [:seon.test/sym s] :seon.test/source
                               "(clojure.test/deftest probe (clojure.test/is (= 1 1)))"]])))
            (let [completion {:seon.test.runner/results [result]
                              :seon.test/reach-digests (:seon.test/reach-digests transported)
                              :seon.test/reaches (:seon.test/reaches transported)
                              :seon.test.run/provenance provenance
                              :seon.test/run-basis-t (:seon.test.run/basis-t provenance)
                              :seon.test/run-at (:seon.test.run/at provenance)}
                  recorded (runner/commit-results! connection completion)]
              (is (= digest (:seon.test/reach-digest (first recorded))) (pr-str recorded))
              (is (not= digest (sut/reach-digest (db/db connection) s)))
              (is (false? (sut/verified? (db/db connection) s)))
              (is (some #{s} (sut/stale (db/db connection))))
              (is (.contains (#'runner/persistent-results-form transported) digest)))))))))

(deftest fixture-observations-remain-stale
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [s v]
          (is (= 1 (:seon.test/pass-count (sut/run v connection))))
          (is (:db-after (db/transact! connection
                           [[:db/add [:seon.test/sym s] :seon.test/fixture-observation
                             "External fixture bytes must be observed."]])))
          (is (some #{s} (sut/stale (db/db connection))))
          (is (true? (sut/verified? (db/db connection) s))))))))

(deftest in-process-results-detect-worker-global-drift
  (support/with-database
    (fn [connection]
      (support/preserving-instrumentation-state
        (fn []
          (with-test connection
            '(do (alter-var-root #'seon.id/digest malli.instrument/-f->original)
                 (clojure.test/is true))
            (fn [_ v]
              (is (some #{#'seon.id/digest} (seon.instrument/instrumented)))
              (let [result (runner/run-var! v)]
                (is (= 1 (:seon.test/error-count result)) (pr-str result))
                (is (.contains (:seon.test/failure-message result "")
                               "Worker-global state changed"))))))))))

(deftest failed-results-with-native-datoms-remain-recordable
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [s v]
          (let [database (db/db connection)
                observed (first (seon.db/datoms database :eavt))]
            (alter-meta! v assoc :test #(t/is (= [] observed) "Original Datom failure"))
            (let [result (binding [t/report (constantly nil)] (sut/run v connection))]
              (is (= 1 (:seon.test/fail-count result)) (pr-str result))
              (is (= 0 (:seon.test/error-count result)))
              (is (.contains (:seon.test/failure-message result "") "Original Datom failure"))
              (is (= (sut/reach-digest database s) (:seon.test/reach-digest result))))))))))

(deftest agent-admitted-tests-reach-their-tested-function
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [namespace-name 'my.agents.reach-digest
            _ (support/transacted! connection [{:seon.ns/name namespace-name}])
            ctx (support/fork-cluster-ctx connection)
            effective (seon.config/effective (db/db connection) "default")
            sources ["(defn largest-customer {:malli/schema [:=> [:cat [:vector {:min 1} [:map [:seon.test/pass-count :seon.test/pass-count]]]] [:map [:seon.test/pass-count :seon.test/pass-count]]]} [rows] (apply max-key :seon.test/pass-count rows))"
                     "(clojure.test/deftest largest-customer-test (clojure.test/is (= {:seon.test/pass-count 9} (largest-customer [{:seon.test/pass-count 2} {:seon.test/pass-count 9}]))))"]]
        (doseq [source sources]
          (let [evaluation (seon.sci.eval/evaluate
                             {:seon.cluster.eval/source source
                              :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                              :seon.sci.eval/ctx ctx
                              :seon.sci.eval/time-limit-ms 10000
                              :seon.sci.admit/caps (seon.config/result-caps effective)
                              :seon.config/on-core-error (:seon.config/on-core-error effective)
                              :seon.db/db (db/db connection) :seon.db/connection connection})
                analysis (functions/analyze-forms (db/db connection)
                           [{:seon.cluster.eval/source source
                             :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                             :seon.program/row (:seon.program/row evaluation)}])
                row (when-not (:seon.error/kind analysis) (second (first analysis)))]
            (is (map? row) (pr-str evaluation))
            (when row
              (is (:db-after (db/transact! connection [(dissoc row :seon.sci.eval/evaluated?)]))))))
        (let [database (db/db connection)
              s "my.agents.reach-digest/largest-customer-test"
              f "my.agents.reach-digest/largest-customer"
              before (sut/reach-digest database s)
              index (#'runner/reach-refresh database nil)
              entry (#'runner/reach-entry index s)
              target (:db/id (db/pull database [:db/id] [:seon.fn/sym f]))]
          (is (contains? (:seon.test.runner/reach-dependencies entry) target)
              (pr-str (db/pull database
                        '[:seon.test/source {:seon.fn/calls [:seon.fn/sym]}
                          {:seon.test/subject [:seon.fn/sym]}]
                        [:seon.test/sym s])))
          (is (:db-after (db/transact! connection
                           [[:db/add [:seon.fn/sym f] :seon.fn/source
                             (str (first sources) "\n")]])))
          (is (not= before (sut/reach-digest (db/db connection) s))))))))

(deftest fixture-state-observation-is-total
  (support/with-database
    (fn [connection]
      (let [unrealized (delay (throw (ex-info "must not force" {})))
            failed (delay (throw (ex-info "failed fixture acquisition" {})))
            acquired (delay {:seon.sci.eval/ctx (support/fork-cluster-ctx connection)})]
        (try @failed (catch Exception _ nil))
        @acquired
        (is (nil? (#'runner/sci-base-namespace-sizes unrealized)))
        (is (not (realized? unrealized)))
        (let [observation (#'runner/sci-base-namespace-sizes failed)]
          (is (= :seon.test.runner/fixture-base-unavailable (:seon.error/kind observation)))
          (is (= "failed fixture acquisition" (:seon.error/message observation))))
        (let [sizes (#'runner/sci-base-namespace-sizes acquired)]
          (is (seq sizes))
          (is (some pos? (vals sizes))))
        (is (map? (#'runner/ambient-snapshot)))))))

(deftest declared-observations-defer-before-cheap-reaching-tests
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [cheap-symbol _]
          (with-test connection '(clojure.test/is true)
            (fn [observed-symbol observed-var]
              (let [reason "Observe the external fixture lifecycle explicitly."
                    root (doto (clojure.java.io/file "tmp" (str "observation-index-" (id/id))) .mkdirs)
                    source (str "(ns " (namespace (symbol observed-symbol))
                                " (:require [clojure.test :refer [deftest is]]))\n"
                                "(deftest ^{:seon.test/fixture-observation " (pr-str reason)
                                "} probe (is true))\n")]
                (try
                  (spit (clojure.java.io/file root "probe.clj") source)
                  (let [rows (functions/rows {:seon.fn/roots [(.getPath root)]})
                        row (first (filter #(= observed-symbol (:seon.test/sym %)) rows))]
                    (is (= reason (:seon.test/fixture-observation row)) (pr-str row))
                    ;; The indexed test row refers to its file and namespace rows, so the
                    ;; whole emitted artifact is admitted exactly as publication admits it.
                    (is (:db-after (db/transact! connection rows))))
                  (let [result (sut/check {:seon.db/connection connection
                                           :seon.test/changed [observed-symbol cheap-symbol]})
                        deferred [{:seon.test/sym observed-symbol
                                   :seon.test/fixture-observation reason
                                   :seon.test/command ["bin/test-check" "default" "--test" observed-symbol]}]
                        feedback (sut/feedback result)]
                    (is (= [cheap-symbol] (:seon.test/tests result)) (pr-str result))
                    (is (= [cheap-symbol] (:seon.test/passed result)))
                    (is (= deferred (:seon.test/deferred result)))
                    (is (.contains feedback reason) feedback)
                    (is (.contains feedback (str "'bin/test-check' 'default' '--test' '" observed-symbol "'")) feedback)
                    (is (nil? (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                                       [:seon.test/sym observed-symbol]))))
                    (let [explicit (sut/run observed-var connection)]
                      (is (= 1 (:seon.test/pass-count explicit)) (pr-str explicit))))
                  (finally (support/delete-recursively! root)))))))))))

(deftest check-records-provenance-and-verifies-green
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is (= 4 (+ 2 2)))
        (fn [test-symbol _]
          (let [basis (db/basis-t (db/db connection))
                result (sut/check {:seon.db/connection connection
                                   :seon.test/changed [test-symbol]
                                   :seon.test/paths ["src/seon/id.clj"]})
                database (db/db connection)
                runs (db/q '[:find [?run ...] :where [?run :seon.test.run/id]] database)]
            (is (= [test-symbol] (:seon.test/passed result)) (pr-str result))
            (is (= basis (:seon.test.run/basis-t result)))
            (is (= 1 (count runs)))
            (is (= basis (:seon.test.run/basis-t (db/pull database '[*] (first runs)))))
            (is (true? (sut/verified? database test-symbol (:seon.test.run/program-digest result))))
            (is (= ["bin/test" "--paths" "src/seon/id.clj" "--platform"]
                   (second (:seon.test/next-tier result))))))))))

(deftest red-check-names-failure-and-stops-escalation
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is false "expected red reaching probe")
        (fn [test-symbol _]
          (let [result (binding [t/report (constantly nil)]
                         (sut/check {:seon.db/connection connection
                                     :seon.test/changed [test-symbol]}))]
            (is (= :none (:seon.test/next-tier result)) (pr-str result))
            (is (= test-symbol (get-in result [:seon.test/failed 0 :seon.test/sym])))
            (is (.contains (get-in result [:seon.test/failed 0 :seon.test/failure-message] "")
                           "expected red reaching probe"))
            (is (= [test-symbol] (get-in result [:seon.test/failed 0 :seon.test/changed])))
            (is (= 1 (:seon.test/fail-count
                       (db/pull (db/db connection) '[*] [:seon.test/sym test-symbol]))))))))))

(deftest widened-hook-check-reports-and-runs-nothing
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [result (sut/check {:seon.db/connection connection
                               :seon.test/changed []
                               :seon.test/paths ["deps.edn"]
                               :seon.test/namespaces ['seon.id-test]
                               :seon.test/defer-widened? true})]
        (is (string? (:seon.test/widened result)) (pr-str result))
        (is (= [] (:seon.test/tests result)))
        (is (= ["bin/test" "--paths" "deps.edn" "--" "seon.id-test"]
               (first (:seon.test/next-tier result))))
        (is (empty? (db/q '[:find [?run ...] :where [?run :seon.test.run/id]] (db/db connection))))
        (let [unbounded (sut/check {:seon.db/connection connection
                                    :seon.test/changed []
                                    :seon.test/paths ["deps.edn"]
                                    :seon.test/defer-widened? true})]
          (is (= [] (:seon.test/tests unbounded)))
          (is (= ["bin/test" "--paths" "deps.edn"]
                 (first (:seon.test/next-tier unbounded)))))))))

(deftest a-test-completion-bound-is-recorded-as-a-named-error
  (support/with-database
    (fn [connection]
      (with-test connection '(.await (java.util.concurrent.CountDownLatch. 1))
        (fn [test-symbol test-var]
          (let [provenance (runner/provenance (db/db connection))
                result (binding [t/report (constantly nil)]
                         (sut/run test-var connection
                                  {:seon.test.run/provenance provenance
                                   :seon.test/remaining-ms 50}))]
            (is (= 1 (:seon.test/error-count result)) (pr-str result))
            (is (.contains (:seon.test/failure-message result "") test-symbol))
            (is (= 1 (:seon.test/error-count
                       (db/pull (db/db connection) '[*] [:seon.test/sym test-symbol]))))))))))

(deftest empty-check-does-not-acquire-run-provenance
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [seals (db/q '[:find [?entity ...] :where [?entity :seon.source/digest]]
                        (db/db connection))
            removed (db/transact! connection
                                  (mapv #(vector :db.fn/retractAttribute % :seon.source/digest) seals))]
        (is (seq seals))
        (is (:db-after removed) (pr-str removed))
        (is (:seon.error/kind (runner/provenance (db/db connection))))
        (let [result (sut/check {:seon.db/connection connection
                                 :seon.test/changed []
                                 :seon.test/paths ["docs/README.md"]})]
          (is (= [] (:seon.test/tests result)) (pr-str result))
          (is (nil? (:seon.test.run/program-digest result)))
          (is (empty? (:seon.test/failed result)))
          (is (empty? (db/q '[:find [?run ...] :where [?run :seon.test.run/id]]
                            (db/db connection)))))))))

(deftest run-carries-the-connections-projection-to-the-test-thread
  (support/with-database
    (fn [connection]
      (with-test connection
        '(clojure.test/is (seq (seon.schema/declaration-population)))
        (fn [_ test-var]
          (let [completion (java.util.concurrent.FutureTask.
                             ^java.util.concurrent.Callable
                             (fn [] (sut/run test-var connection)))
                _ (.start (Thread/ofVirtual) ^Runnable completion)
                result (support/await-event! completion ::run-completed)]
            (is (= 1 (:seon.test/pass-count result)) (pr-str result))
            (is (zero? (:seon.test/fail-count result)) (pr-str result))
            (is (zero? (:seon.test/error-count result)) (pr-str result))))))))

;;; ---------------------------------------------------------------------------
;;; An in-process run refuses a destructive drill on a development root
;;; ---------------------------------------------------------------------------

;; The 2026-09-17 store wipe was an in-process seon.test/run, inside the
;; development JVM, of a test whose reach includes a function that deletes a
;; filesystem path it did not create
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md). The
;; cold gate's platform tier refuses the same class from its own side; these
;; regressions own the in-process half.

(def ^:private destructive-owner "seon.test-support/populate-published-root!")

(defn- with-destructive-test
  "A probe whose indexed reach is the declared destructive owner.

  Its body creates a marker directory, so an execution that was supposed to be
  refused leaves evidence on disk instead of passing silently."
  [connection assertion]
  (support/seed-cluster! connection "default")
  (let [namespace-name (symbol (str "destructive.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (str namespace-name "/probe")
        marker (clojure.java.io/file "tmp" (str "destructive-probe-" (id/id)))
        source (list 'clojure.test/deftest 'probe
                     (list 'clojure.test/is
                           (list '.mkdirs (list 'clojure.java.io/file (.getPath marker)))))
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (is (:db/id (db/pull (db/db connection) [:db/id] [:seon.fn/sym destructive-owner]))
          "the canonical population declares the destructive owner")
      (support/transacted! connection
                           [{:seon.ns/name namespace-name}
                            {:seon.test/sym test-symbol
                             :seon.schema.admission/source :core
                             :seon.test/ns [:seon.ns/name namespace-name]
                             :seon.test/source (pr-str source)
                             :seon.fn/calls [[:seon.fn/sym destructive-owner]]}])
      (assertion test-symbol test-var marker)
      (finally
        (support/delete-recursively! marker)
        (remove-ns namespace-name)))))

(deftest a-development-root-is-the-declared-root-this-jvm-operates
  (let [working (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))]
    (is (= working (#'sut/development-root working)))
    (is (= working (#'sut/development-root "."))
        "a relative declaration resolves to the working directory it names")
    (is (nil? (#'sut/development-root nil)) "bin/test-fast declares nothing")
    (is (nil? (#'sut/development-root "")))
    (is (nil? (#'sut/development-root (str working "/tmp/isolated-run-root")))
        "a bin/test worker or a lane --root JVM operates an isolated root")))

(deftest an-in-process-run-under-a-development-root-refuses-a-destructive-test
  (support/with-database
    (fn [connection]
      (with-destructive-test connection
        (fn [test-symbol test-var marker]
          (let [database (db/db connection)
                working (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))
                result (sut/run test-var connection
                                {:seon.db/db database
                                 :seon.test.run/provenance (runner/provenance database)
                                 :seon.test/remaining-ms 10000
                                 :seon.test/declared-root working})]
            (is (= :seon.test/destructive-in-process (:seon.error/kind result)) (pr-str result))
            (is (= test-symbol (:seon.test/sym result)))
            (is (= destructive-owner (:seon.fn/sym result)))
            (is (= [test-symbol destructive-owner] (:seon.test/destructive-path result)))
            (is (= ["bin/test" "--" (namespace (symbol test-symbol))]
                   (:seon.test/command result)))
            (is (.contains (:seon.error/message result "") working) (pr-str result))
            (is (not (.exists marker))
                "the refused test executed nothing, so no fixture root was created")
            (let [row (db/pull (db/db connection)
                               [:seon.test/run :seon.test/pass-count :seon.test/error-count]
                               [:seon.test/sym test-symbol])]
              (is (nil? (:seon.test/run row)) (pr-str row))
              (is (nil? (:seon.test/pass-count row)) (pr-str row)))))))))

(deftest an-in-process-run-under-an-isolated-root-runs-the-same-test
  (support/with-database
    (fn [connection]
      (with-destructive-test connection
        (fn [test-symbol test-var marker]
          (let [database (db/db connection)
                isolated (.getCanonicalPath (clojure.java.io/file "tmp" (str "isolated-root-" (id/id))))
                result (sut/run test-var connection
                                {:seon.db/db database
                                 :seon.test.run/provenance (runner/provenance database)
                                 :seon.test/remaining-ms 10000
                                 :seon.test/declared-root isolated})]
            (is (= 1 (:seon.test/pass-count result)) (pr-str result))
            (is (= 0 (:seon.test/fail-count result)) (pr-str result))
            (is (.exists marker) "the admitted test executed its body")
            (is (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                         [:seon.test/sym test-symbol])))))))))

(deftest an-in-process-check-excludes-a-destructive-test-and-reports-it
  (support/with-database
    (fn [connection]
      (with-destructive-test connection
        (fn [test-symbol _ marker]
          (with-test connection '(clojure.test/is true)
            (fn [cheap-symbol _]
              (let [working (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))
                    result (sut/check {:seon.db/connection connection
                                       :seon.test/declared-root working
                                       :seon.test/changed [test-symbol cheap-symbol]})
                    excluded (:seon.test/destructive-excluded result)
                    feedback (sut/feedback result)]
                (is (= [cheap-symbol] (:seon.test/tests result)) (pr-str result))
                (is (= [{:seon.test/sym test-symbol
                         :seon.fn/sym destructive-owner
                         :seon.test/destructive-path [test-symbol destructive-owner]
                         :seon.test/command ["bin/test" "--" (namespace (symbol test-symbol))]}]
                       excluded)
                    (pr-str result))
                (is (.contains feedback "destructive-excluded 1") feedback)
                (is (.contains feedback destructive-owner) feedback)
                (is (not (.exists marker)) "the excluded test executed nothing")
                (is (nil? (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                                   [:seon.test/sym test-symbol]))))
                (let [isolated (sut/check {:seon.db/connection connection
                                           :seon.test/declared-root
                                           (.getCanonicalPath
                                            (clojure.java.io/file "tmp" (str "isolated-root-" (id/id))))
                                           :seon.test/changed [test-symbol]})]
                  (is (= [test-symbol] (:seon.test/passed isolated)) (pr-str isolated))
                  (is (nil? (:seon.test/destructive-excluded isolated)))
                  (is (.exists marker)))))))))))

(deftest a-destructive-owner-without-a-program-row-refuses-instead-of-admitting
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            owner (db/pull database [:db/id] [:seon.fn/sym destructive-owner])
            removed (db/transact! connection
                                  [[:db/retract (:db/id owner) :seon.fn/sym destructive-owner]])
            after (db/db connection)
            reach (#'sut/destructive-reach after)]
        (is (:db-after removed) (pr-str removed))
        (is (= :seon.test/unknown (:seon.error/kind reach)) (pr-str reach))
        (is (.contains (:seon.error/message reach "") destructive-owner) (pr-str reach))
        (is (:seon.error/kind (#'sut/destructive-refusal
                               after
                               (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))
                               "seon.id-test/does-not-matter"))
            "an unanswerable reach refuses the run instead of admitting it")))))

;;; ---------------------------------------------------------------------------
;;; An in-process check excludes a declared-long test and reports what expired
;;; ---------------------------------------------------------------------------

;; A declared-long test is a real boot or a multi-minute fixture. Selecting one
;; into the in-process check spends the whole shared
;; :seon.test/check-time-limit-ms allowance and used to return one bare
;; :seon.test/unknown, discarding every verdict already recorded
;; (docs/seon/issues/in-process-check-selects-declared-long-tests.md). The cold
;; runner excludes the same declaration from every tier but --full; these
;; regressions own the in-process half and the honest expiry.

(def ^:private long-declaration
  "Real source publication and two cohosted clusters cost most of one allowance.")

(defn- with-long-test
  "A probe declared :seon.test/long whose body is trivial.

  Its body creates a marker directory, so an excluded test that ran anyway
  leaves evidence on disk instead of passing silently."
  [connection manifest assertion]
  (support/seed-cluster! connection "default" manifest)
  (let [namespace-name (symbol (str "long.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (str namespace-name "/probe")
        marker (clojure.java.io/file "tmp" (str "long-probe-" (id/id)))
        source (list 'clojure.test/deftest
                     (with-meta 'probe {:seon.test/long long-declaration})
                     (list 'clojure.test/is
                           (list '.mkdirs (list 'clojure.java.io/file (.getPath marker)))))
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (support/transacted! connection
                           [{:seon.ns/name namespace-name}
                            {:seon.test/sym test-symbol
                             :seon.schema.admission/source :core
                             :seon.test/ns [:seon.ns/name namespace-name]
                             :seon.test/source (pr-str source)
                             :seon.test/long long-declaration}])
      (assertion test-symbol test-var marker)
      (finally
        (support/delete-recursively! marker)
        (remove-ns namespace-name)))))

(deftest the-long-declaration-is-indexed-onto-the-test-row
  (let [root (doto (clojure.java.io/file "tmp" (str "long-index-" (id/id))) .mkdirs)]
    (try
      (spit (clojure.java.io/file root "probe.clj")
            (str "(ns long.index" (id/id) " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ^{:seon.test/long " (pr-str long-declaration) "} probe (is true))\n"))
      (let [rows (functions/rows {:seon.fn/roots [(.getPath root)]})
            row (first (filter :seon.test/sym rows))]
        (is (= long-declaration (:seon.test/long row))
            (str "the indexer lifts the declaration onto the row a check queries: " (pr-str row))))
      (finally (support/delete-recursively! root)))))

(deftest an-in-process-check-excludes-a-declared-long-test-and-names-it
  (support/with-database
    (fn [connection]
      (with-long-test connection {}
        (fn [test-symbol _ marker]
          (with-test connection '(clojure.test/is true)
            (fn [cheap-symbol _]
              (let [result (sut/check {:seon.db/connection connection
                                       :seon.test/changed [test-symbol cheap-symbol]})
                    excluded (:seon.test/long-excluded result)
                    feedback (sut/feedback result)]
                (is (= [cheap-symbol] (:seon.test/tests result)) (pr-str result))
                (is (= [cheap-symbol] (:seon.test/passed result)) (pr-str result))
                (is (= [{:seon.test/sym test-symbol
                         :seon.test/long long-declaration
                         :seon.test/command ["bin/test" "--" (namespace (symbol test-symbol))]}]
                       excluded)
                    (pr-str result))
                (is (.contains feedback "long-excluded 1") feedback)
                (is (.contains feedback test-symbol) feedback)
                (is (.contains feedback long-declaration) feedback)
                (is (not (.exists marker)) "the excluded test executed nothing")
                (is (nil? (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                                   [:seon.test/sym test-symbol]))))))))))))

(deftest the-declared-opt-in-includes-the-long-test
  (support/with-database
    (fn [connection]
      (with-long-test connection {}
        (fn [test-symbol _ marker]
          (let [result (sut/check {:seon.db/connection connection
                                   :seon.test/changed [test-symbol]
                                   :seon.test/include-long? true})]
            (is (= [test-symbol] (:seon.test/passed result)) (pr-str result))
            (is (nil? (:seon.test/long-excluded result)) (pr-str result))
            (is (.exists marker) "the opted-in test executed its body")
            (is (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                         [:seon.test/sym test-symbol])))))))))

(defn- with-expiring-selection
  "Two probes in one namespace under a small declared check allowance.

  The symbols sort so the trivial one runs first, so the bound fires with
  exactly one completed verdict already recorded. The second probe waits under
  its own bound, so nothing parks forever."
  [connection limit-ms assertion]
  (support/seed-cluster! connection "default" {:seon.test/check-time-limit-ms limit-ms})
  (let [namespace-name (symbol (str "expiry.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        completed (str namespace-name "/a-completes")
        unreturned (str namespace-name "/z-never-returns")
        sources {completed (list 'clojure.test/deftest 'a-completes
                                 (list 'clojure.test/is true))
                 unreturned (list 'clojure.test/deftest 'z-never-returns
                                  (list 'clojure.test/is
                                        (list '.await (list 'java.util.concurrent.CountDownLatch. 1)
                                              30 'java.util.concurrent.TimeUnit/SECONDS)))}]
    (binding [*ns* namespace-object]
      (clojure.core/refer 'clojure.core)
      (doseq [source (vals sources)] (eval source)))
    (try
      (support/transacted! connection
                           (into [{:seon.ns/name namespace-name}]
                                 (map (fn [[test-symbol source]]
                                        {:seon.test/sym test-symbol
                                         :seon.schema.admission/source :core
                                         :seon.test/ns [:seon.ns/name namespace-name]
                                         :seon.test/source (pr-str source)}))
                                 sources))
      (assertion completed unreturned)
      (finally (remove-ns namespace-name)))))

(deftest an-expired-check-reports-the-verdicts-it-already-recorded
  (support/with-database
    (fn [connection]
      (with-expiring-selection connection 3000
        (fn [completed unreturned]
          (let [result (binding [t/report (constantly nil)]
                         (sut/check {:seon.db/connection connection
                                     :seon.test/changed [completed unreturned]}))
                expiry (:seon.test/expired result)
                feedback (sut/feedback result)]
            (is (nil? (:seon.error/kind result))
                (str "the expiry never discards the runs it holds: " (pr-str result)))
            (is (= [completed] (:seon.test/tests result)) (pr-str result))
            (is (= [completed] (:seon.test/passed result)) (pr-str result))
            (is (= 1 (count (:seon.test/results result))) (pr-str result))
            (is (= :none (:seon.test/next-tier result)))
            (is (= [unreturned] (:seon.test/pending result)) (pr-str result))
            (is (= unreturned (:seon.test/unknown expiry)) (pr-str expiry))
            (is (.contains (:seon.error/message expiry "") unreturned) (pr-str expiry))
            (is (double? (:seon.test/elapsed-ms result)) (pr-str result))
            (is (.contains feedback "tests run 1") feedback)
            (is (.contains feedback "expired") feedback)
            (is (.contains feedback unreturned) feedback)
            (is (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                         [:seon.test/sym completed]))
                "the completed run's facts were recorded before the bound fired")))))))
