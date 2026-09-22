(ns seon.test-reaching-test
  (:require [clojure.java.io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [malli.instrument]
            [seon.db :as db]
            [seon.config]
            [seon.sci.eval]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.instrument]
            [seon.test :as sut]
            [seon.render.test :as render.test]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn- run-in-fixture
  "Request one indexed test on the fixture's branch through the one request owner."
  ([test-var connection] (run-in-fixture test-var connection {}))
  ([test-var connection options]
   (support/transacted!
    connection
    [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                (db/db connection))
      :seon.source/test-input-digest (id/digest 64 [::host-fixture-inputs])}])
   (let [test-symbol (symbol (str (:ns (meta test-var))) (str (:name (meta test-var))))
         result (sut/run (merge {:seon.test/execution (support/execution-handle connection)
                                 :seon.test/recording-connection connection
                                 :seon.test/policy :named
                                 :seon.test/identities #{test-symbol}}
                                options))]
     (if (:seon.error/at result) result (first (:seon.test/results result))))))

(defn- changed-request
  "One incremental request for explicit changed identities on the fixture's branch."
  [connection changed options]
  (support/transacted!
   connection
   [{:seon.source/digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                               (db/db connection))
     :seon.source/test-input-digest (id/digest 64 [::host-fixture-inputs])}])
  (sut/run (merge {:seon.test/execution (support/execution-handle connection)
                   :seon.test/recording-connection connection
                   :seon.test/policy :incremental
                   :seon.test/changed changed}
                  options)))

(deftest reach-digests-follow-only-changed-closures
 (support/with-database
  (fn [connection]
   (let [a 'reach.fixture/a b 'reach.fixture/b ta 'reach.fixture/a-test tb 'reach.fixture/b-test
         rows [{:db/id "fn-a" :seon.fn/ns [:seon.ns/name 'seon.test-reaching-test] :seon.schema.admission/source :core :seon.fn/sym a :seon.fn/source "(defn a [x] x)" :seon.fn/spec "[:=> [:cat :seon.test/sym] :seon.test/sym]"}
               {:db/id "fn-b" :seon.fn/ns [:seon.ns/name 'seon.test-reaching-test] :seon.schema.admission/source :core :seon.fn/sym b :seon.fn/source "(defn b [x] x)" :seon.fn/spec "[:=> [:cat :int] :int]"}
               {:seon.schema.admission/source :core :seon.test/sym ta :seon.test/source "(deftest a-test (is (= \"x\" (a \"x\"))))" :seon.test/subject "fn-a"}
               {:seon.schema.admission/source :core :seon.test/sym tb :seon.test/source "(deftest b-test (is (= 1 (b 1))))" :seon.fn/calls [b]}]
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
        (is (:db/id (db/pull database [:db/id] [:seon.fn/sym 'my.note/add!])))
        (is (vector? actual))
        (is (= (set (functions/tests-reaching database 'my.note/add!)) (set actual)))
        (is (string? (:seon.test/unknown (sut/reaching {:seon.db/db database
                               :seon.test/changed ['absent.function/no-row]}))))))))

(defn- with-indexed-tests [connection namespace-name sources assertion]
  (let [root (doto (clojure.java.io/file "tmp" (str "reaching-source-" (id/id))) .mkdirs)]
    (try
      (spit (clojure.java.io/file root "probe.clj")
            (str "(ns " namespace-name " (:require [clojure.test] [clojure.java.io] [seon.id] [seon.schema] [malli.instrument]))\n"
                 (binding [*print-meta* true]
                   (str/join "\n" (map pr-str sources)))))
      (support/transacted! connection (functions/rows {:seon.fn/roots [(.getPath root)]}))
      (assertion)
      (finally (support/delete-recursively! root)))))

(defn- with-test [connection body assertion]
  (support/seed-cluster! connection "default")
  (let [namespace-name (symbol (str "reaching.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (symbol (str namespace-name) "probe")
        source (list 'clojure.test/deftest 'probe body)
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (with-indexed-tests connection namespace-name [source]
        #(assertion test-symbol test-var))
      (finally (remove-ns namespace-name)))))

(deftest fixture-built-test-symbol-is-qualified-at-the-write
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [test-symbol _]
          (let [written (:seon.test/sym
                         (db/pull (db/db connection) [:seon.test/sym]
                                  [:seon.test/sym test-symbol]))]
            (is (qualified-symbol? written) (pr-str written))))))))

(deftest concurrent-completions-use-the-canonical-program
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            s 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol
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
          (is (= 1 (:seon.test/pass-count (run-in-fixture v connection))))
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
            (let [result (binding [t/report (constantly nil)] (run-in-fixture v connection))]
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
                row (when (vector? analysis) (second (first analysis)))]
            (is (map? row) (pr-str evaluation))
            (when row
              (is (:db-after (db/transact! connection [(dissoc row :seon.sci.eval/evaluated?)]))))))
        (let [database (db/db connection)
              s 'my.agents.reach-digest/largest-customer-test
              f 'my.agents.reach-digest/largest-customer
              before (sut/reach-digest database s)
              index (#'runner/reach-refresh database nil [s])
              entry (#'runner/reach-entry index s)
              target (:db/id (db/pull database [:db/id] [:seon.fn/sym f]))]
          (is (contains? (:seon.test.runner/reach-dependencies entry) target)
              (pr-str (db/pull database
                        '[:seon.test/source :seon.fn/calls
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
          (is (string? (:seon.test.runner/fixture-base-unavailable observation)))
          (is (= "failed fixture acquisition" (:seon.error/message observation))))
        (let [sizes (#'runner/sci-base-namespace-sizes acquired)]
          (is (seq sizes))
          (is (some pos? (vals sizes))))
        (is (map? (#'runner/ambient-snapshot)))))))

(deftest run-carries-the-connections-projection-to-the-test-thread
  (support/with-database
    (fn [connection]
      (with-test connection
        '(clojure.test/is (seq (seon.schema/declaration-population)))
        (fn [_ test-var]
          (let [completion (java.util.concurrent.FutureTask.
                             ^java.util.concurrent.Callable
                             (fn [] (run-in-fixture test-var connection)))
                _ (.start (Thread/ofVirtual) ^Runnable completion)
                result (support/await-event! completion ::run-completed)]
            (is (= 1 (:seon.test/pass-count result)) (pr-str result))
            (is (zero? (:seon.test/fail-count result)) (pr-str result))
            (is (zero? (:seon.test/error-count result)) (pr-str result))))))))

;;; ---------------------------------------------------------------------------
;;; Where a test may run: a destructive drill never runs on a development root
;;; ---------------------------------------------------------------------------

;; The 2026-09-17 store wipe was an in-process seon.test/run, inside the
;; development JVM, of a test whose reach includes a function that deletes a
;; filesystem path it did not create
;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
;; `seon.test/run` excludes that class on a development root; the exclusion
;; itself is proven in `seon.test.one-request-test`.

(def ^:private destructive-owner 'seon.test-support/populate-published-root!)

(defn- owner-destroys
  "What the declared owner says it destroys, read from its program row."
  [database]
  (:seon.fn/destroys (db/pull database [:seon.fn/destroys]
                              [:seon.fn/sym destructive-owner])))

(defn- with-destructive-test
  "A probe whose indexed reach is the declared destructive owner.

  Its body creates a marker directory, so an execution that was supposed to be
  refused leaves evidence on disk instead of passing silently."
  [connection assertion]
  (support/seed-cluster! connection "default")
  (let [namespace-name (symbol (str "destructive.probe" (id/id)))
        namespace-object (create-ns namespace-name)
        test-symbol (symbol (str namespace-name) "probe")
        marker (clojure.java.io/file "tmp" (str "destructive-probe-" (id/id)))
        source (list 'clojure.test/deftest 'probe
                     (list 'clojure.test/is
                           (list '.mkdirs (list 'clojure.java.io/file (.getPath marker)))))
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (is (seq (:seon.fn/destroys
                (db/pull (db/db connection) [:seon.fn/destroys]
                         [:seon.fn/sym destructive-owner])))
          "the canonical population carries the owner's own :seon.fn/destroys")
      (with-indexed-tests connection namespace-name [source]
        (fn []
          (support/transacted! connection
                               [{:seon.test/sym test-symbol
                                 :seon.fn/calls [destructive-owner]}])
          (assertion test-symbol test-var marker)))
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

(deftest a-program-declaring-no-destroyer-refuses-instead-of-admitting
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            owners (sut/destroyers database)
            _ (is (contains? owners destructive-owner) (pr-str owners))
            removed (db/transact!
                     connection
                     (vec (for [[owner what] owners]
                            [:db/retract (:db/id (db/pull database [:db/id] [:seon.fn/sym owner]))
                             :seon.fn/destroys what])))
            after (db/db connection)
            derived (sut/destroyers after)]
        (is (:db-after removed) (pr-str removed))
        (is (string? (:seon.test/unknown derived)) (pr-str derived))
        (is (.contains (:seon.error/message derived "") ":seon.fn/destroys") (pr-str derived))
        (is (string? (:seon.test/unknown (sut/host after 'seon.id-test/anything)))
            "an unanswerable declaration never answers in-process")
        (is (string? (:seon.test/unknown
                      (#'sut/host-exclusions
                       after
                       (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))
                       ['seon.id-test/does-not-matter])))
            "an unanswerable reach refuses the request instead of admitting it")))))

(deftest a-test-with-no-program-row-is-unknown-and-is-never-run-in-process
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [database (db/db connection)
            namespace-name (symbol (str "unindexed.probe" (id/id)))
            namespace-object (create-ns namespace-name)
            test-symbol (symbol (str namespace-name) "probe")
            marker (clojure.java.io/file "tmp" (str "unindexed-probe-" (id/id)))
            test-var (binding [*ns* namespace-object]
                       (clojure.core/refer 'clojure.core)
                       (eval (list 'clojure.test/deftest 'probe
                                   (list 'clojure.test/is
                                         (list '.mkdirs (list 'clojure.java.io/file
                                                              (.getPath marker)))))))
            ]
        (try
          (is (nil? (:db/id (db/pull database [:db/id] [:seon.test/sym test-symbol])))
              "the probe is deliberately not indexed")
          (let [report (sut/host database test-symbol)]
            (is (string? (:seon.test/unknown report)) (pr-str report))
            (is (nil? (:seon.test/host report))
                "an unknown call graph never reads as in-process")
            (is (.contains (sut/host-text database test-symbol) "unknown")))
          (let [result (run-in-fixture test-var connection)]
            (is (= :seon.test/identity-unresolved (:seon.test/selection-refusal result))
                (pr-str result))
            (is (not (.exists marker)) "an unknown test executed nothing"))
          (finally
            (support/delete-recursively! marker)
            (remove-ns namespace-name)))))))

(deftest a-tests-render-pair-shows-where-it-runs-and-why
  (support/with-database
    (fn [connection]
      (with-destructive-test connection
        (fn [test-symbol _ _]
          (let [database (db/db connection)
                unit {:seon.db/db database
                      :seon.render/value {:seon.test/sym test-symbol}}
                what (owner-destroys database)
                ;; THE RULED LINE: where it runs, the declared call path, what
                ;; the owner destroys in its own words, and the cold command.
                ;; A declaration carrying a newline would reach the agent
                ;; escaped inside the rendered form, so the line is one line.
                line (str "runs: isolated snapshot, under its own operator root ("
                          test-symbol " -> " destructive-owner ": " what
                          "). Cold invocation: bin/test -- "
                          (namespace (symbol test-symbol)))
                ai (render.test/render-ai unit)
                html (pr-str (render.test/render-html unit))]
            (is (= line (sut/host-text database test-symbol)))
            (is (not (.contains what "\n")) what)
            (is (.contains ai line) ai)
            (is (.contains html line) html))
          (let [cheap (symbol (str "render.probe" (id/id)) "cheap")]
            (support/transacted! connection
                                 [{:seon.test/sym cheap
                                   :seon.schema.admission/source :core
                                   :seon.test/source "(deftest cheap (is true))"}])
            (let [ai (render.test/render-ai {:seon.db/db (db/db connection)
                                             :seon.render/value {:seon.test/sym cheap}})]
              (is (.contains ai "runs: in the cluster process") ai))))))))

(deftest reused-results-render-the-recording-basis-as-data
  (let [unit {:seon.render/value
              {:seon.test/sym 'seon.test-reaching-test/reused-results-render-the-recording-basis-as-data
               :seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0
               :seon.test/run-basis-t 42 :seon.test/recorded-basis-t 43
               :seon.test/unchanged true}}
        ai (render.test/render-ai unit)
        html (pr-str (render.test/render-html unit))]
    (is (.contains ai ":seon.test/unchanged true"))
    (is (.contains ai ":seon.test/recorded-basis-t 43"))
    (is (.contains ai ":seon.test/run-basis-t 42"))
    (is (.contains html "Unchanged; reused the result recorded at :t "))
    (is (.contains html "43"))))

;;; ---------------------------------------------------------------------------
;;; A request excludes a declared-long test unless it opts in
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
        test-symbol (symbol (str namespace-name) "probe")
        marker (clojure.java.io/file "tmp" (str "long-probe-" (id/id)))
        source (list 'clojure.test/deftest
                     (with-meta 'probe {:seon.test/long long-declaration})
                     (list 'clojure.test/is
                           (list '.mkdirs (list 'clojure.java.io/file (.getPath marker)))))
        test-var (binding [*ns* namespace-object]
                   (clojure.core/refer 'clojure.core)
                   (eval source))]
    (try
      (with-indexed-tests connection namespace-name [source]
        #(assertion test-symbol test-var marker))
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
              (let [result (changed-request connection [test-symbol cheap-symbol] {})
                    excluded (:seon.test/long-excluded result)
                    tally (sut/tally result)]
                (is (= [cheap-symbol] (map :seon.test/sym (:seon.test/results result))) (pr-str result))
                (is (true? (:seon.test/passed? result)) tally)
                (is (= [{:seon.test/sym test-symbol
                         :seon.test/long long-declaration
                         :seon.test/command ["bin/test-check" "default" "--test" (str test-symbol)]}]
                       excluded)
                    (pr-str result))
                (is (.contains tally (str "long " test-symbol)) tally)
                (is (.contains tally long-declaration) tally)
                (is (not (.exists marker)) "the excluded test executed nothing")
                (is (nil? (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                                   [:seon.test/sym test-symbol]))))))))))))

(deftest the-declared-opt-in-includes-the-long-test
  (support/with-database
    (fn [connection]
      (with-long-test connection {}
        (fn [test-symbol _ marker]
          (let [result (changed-request connection [test-symbol] {:seon.test/include-long? true})]
            (is (= [test-symbol] (map :seon.test/sym (:seon.test/results result))) (pr-str result))
            (is (true? (:seon.test/passed? result)) (sut/tally result))
            (is (nil? (:seon.test/long-excluded result)) (pr-str result))
            (is (.exists marker) "the opted-in test executed its body")
            (is (:seon.test/run (db/pull (db/db connection) [:seon.test/run]
                                         [:seon.test/sym test-symbol])))))))))

