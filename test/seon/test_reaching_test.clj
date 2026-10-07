(ns seon.test-reaching-test
  (:require [clojure.java.io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [malli.instrument]
            [seon.cache]
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

(defn- define!
  "Write synthetic declarations through the production analyzer, refusing at the write."
  [connection & identity-sources]
  (support/transacted!
   connection
   (mapv (fn [[declaration source]] (support/program-entity (db/db connection) declaration source))
         (partition 2 identity-sources))))

(defn- seed-reach-fixture!
  "Two functions in `reach.fixture`, each with one test that calls it."
  [connection]
  (support/transacted! connection [(support/namespace-entity (db/db connection) 'reach.fixture)])
  (define! connection
           '[:seon.fn/sym reach.fixture/a] "(defn a {:malli/schema [:=> [:cat :seon.test/sym] :seon.test/sym]} [x] x)"
           '[:seon.fn/sym reach.fixture/b] "(defn b {:malli/schema [:=> [:cat :int] :int]} [x] x)")
  (define! connection
           '[:seon.test/sym reach.fixture/a-test] "(deftest a-test (is (= 'x (a 'x))))"
           '[:seon.test/sym reach.fixture/b-test] "(deftest b-test (is (= 1 (b 1))))"))

(defn- derived-digests
  "The reach indexes the central cache holds for the request `test-symbols`,
  one per distinct reached graph content it was derived for."
  {:malli/schema [:=> [:cat [:set :seon.test/sym]] :int]}
  [test-symbols]
  (count (filter #(= test-symbols (first %)) (keys (seon.cache/retained :seon.test.runner/reach-index)))))

(deftest reach-digests-follow-only-changed-closures
 (support/with-database
  (fn [connection]
   (seed-reach-fixture! connection)
   (let [ta 'reach.fixture/a-test tb 'reach.fixture/b-test
         initial (runner/reach-digests (db/db connection) [ta tb])]
    (is (= 2 (count initial)))
    (is (= initial (runner/reach-digests (db/db connection) [ta tb])))
    ;; Source, contract and schema writes each change the digest of only the
    ;; test they reach; the other test's closure content is unchanged.
    (doseq [write! [#(define! % '[:seon.fn/sym reach.fixture/a]
                              "(defn a {:malli/schema [:=> [:cat :seon.test/sym] :seon.test/sym]} [x] (identity x))")
                    #(define! % '[:seon.fn/sym reach.fixture/a]
                              "(defn a {:malli/schema [:=> [:cat :seon.test/sym] :any]} [x] (identity x))")
                    #(support/transacted! % [[:db/add [:seon.schema/key :seon.test/sym]
                                              :seon.schema/form "[:qualified-symbol {:min 2}]"]])]]
     (let [before (runner/reach-digests (db/db connection) [ta tb])]
      (write! connection)
      (let [after (runner/reach-digests (db/db connection) [ta tb])]
       (is (not= (get before ta) (get after ta)))
       (is (= (get before tb) (get after tb))))))
    ;; A data write leaves every closure's content equal.
    (let [before (runner/reach-digests (db/db connection) [ta tb])
          derived (derived-digests #{ta tb})]
     (support/transacted! connection [[:db/add [:seon.test/sym ta] :seon.test/pass-count 1]])
     (is (= before (runner/reach-digests (db/db connection) [ta tb])))
     (is (= derived (derived-digests #{ta tb})) "equal reached content derives nothing"))))))

(deftest a-reach-digest-is-its-closures-alone-across-cycles
 ;; Digests compose per call-graph component (Merkle over strongly connected
 ;; components): a test's digest is a function of its closure only, the same
 ;; asked alone or beside other tests, and a change to any member of a cycle
 ;; it reaches changes it.
 (support/with-database
  (fn [connection]
   (seed-reach-fixture! connection)
   (support/transacted! connection [(support/namespace-entity (db/db connection) 'reach.cycle)])
   (define! connection '[:seon.fn/sym reach.cycle/d] "(defn d {:malli/schema [:=> [:cat :int] :int]} [n] n)")
   (define! connection '[:seon.fn/sym reach.cycle/c]
            "(defn c {:malli/schema [:=> [:cat :int] :int]} [n] (if (pos? n) (d (dec n)) n))")
   (define! connection
            '[:seon.fn/sym reach.cycle/d] "(defn d {:malli/schema [:=> [:cat :int] :int]} [n] (c n))"
            '[:seon.test/sym reach.cycle/c-test] "(deftest c-test (is (= 0 (c 1))))")
   (let [tc 'reach.cycle/c-test ta 'reach.fixture/a-test
         together (runner/reach-digests (db/db connection) [tc ta])]
    (is (contains? (get (runner/reach-memberships (db/db connection) [tc]) tc) 'reach.cycle/d)
        "c and d call each other")
    (is (= (get together tc) (get (runner/reach-digests (db/db connection) [tc]) tc)) "asked alone")
    (is (= (get together ta) (get (runner/reach-digests (db/db connection) [ta]) ta)) "asked alone")
    (define! connection '[:seon.fn/sym reach.cycle/d]
             "(defn d {:malli/schema [:=> [:cat :int] :int]} [n] (c (identity n)))")
    (let [after (runner/reach-digests (db/db connection) [tc ta])]
     (is (not= (get together tc) (get after tc)) "a change inside the cycle")
     (is (= (get together ta) (get after ta)) "a test outside the cycle keeps its digest"))))))

(deftest a-call-edge-changed-under-an-unchanged-definition-digest-changes-the-reach
 ;; Calls derived from the declaration population (an invoker retargeted to
 ;; another handler) are not part of the definition digest, so a reach node
 ;; cached under that digest kept the old edges and the old reach digest.
 (support/with-database
  (fn [connection]
   (seed-reach-fixture! connection)
   (let [ta 'reach.fixture/a-test
         digest-of-a #(:seon.program/definition-digest
                       (db/pull (db/db connection) [:seon.program/definition-digest] [:seon.fn/sym 'reach.fixture/a]))
         before (runner/reach-digests (db/db connection) [ta])
         a-digest (digest-of-a)]
    (is (= before (runner/reach-digests (db/db connection) [ta])) "warm")
    (support/transacted! connection [[:db/add [:seon.fn/sym 'reach.fixture/a] :seon.fn/calls 'reach.fixture/b]])
    (is (= a-digest (digest-of-a)) "the definition digest is unchanged")
    (is (contains? (get (runner/reach-memberships (db/db connection) [ta]) ta) 'reach.fixture/b))
    (let [retargeted (runner/reach-digests (db/db connection) [ta])]
     (is (not= (get before ta) (get retargeted ta)) "the new edge changes the reach")
     (define! connection '[:seon.fn/sym reach.fixture/b] "(defn b {:malli/schema [:=> [:cat :int] :int]} [x] (identity x))")
     (is (not= (get retargeted ta) (get (runner/reach-digests (db/db connection) [ta]) ta))
         "a change to the newly reached function changes the reach"))))))

(deftest concurrent-reach-reads-of-one-value-derive-once
 ;; A digest is cached under its closure's content: equal values share one
 ;; derivation, a changed closure derives again.
 (support/with-database
  (fn [connection]
   (seed-reach-fixture! connection)
   (let [ta 'reach.fixture/a-test
         database (db/db connection)
         before (derived-digests #{ta})
         start (java.util.concurrent.CountDownLatch. 1)
         readers (mapv (fn [_] (future (.await start) (runner/reach-digests database [ta]))) (range 4))
         _ (.countDown start)
         results (mapv deref readers)]
    (is (apply = results))
    (is (string? (get-in results [0 ta])))
    ;; Equal content already derived (an earlier run) derives nothing.
    (is (contains? #{before (inc before)} (derived-digests #{ta})))
    (define! connection '[:seon.fn/sym reach.fixture/a]
             "(defn a {:malli/schema [:=> [:cat :seon.test/sym] :seon.test/sym]} [x] (identity x))")
    (is (not= (first results) (runner/reach-digests (db/db connection) [ta])))))))

(deftest a-fresh-branch-with-unchanged-program-entities-derives-nothing
 ;; A nested request runs on a fresh branch that writes only non-program facts;
 ;; the digest is keyed by the closure content it reads, so it hits.
 (let [s 'seon.id-test/data-shape-and-explicit-length-determine-identity
       read-reach (fn [write?]
              (support/with-database
               (fn [connection]
                (when write?
                 (support/transacted! connection [[:db/add [:seon.test/sym s] :seon.test/pass-count 1]]))
                [(runner/reach-digests (db/db connection) [s]) (derived-digests #{s})])))
       [digests derivations] (read-reach false)
       [again again-derivations] (read-reach true)]
  (is (string? (get digests s)))
  (is (= digests again))
  (is (= derivations again-derivations) "the fresh branch derived no entry")))

(deftest reaching-is-the-program-graph-relation
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            actual (sut/reaching {:seon.db/db database
                                  :seon.test/changed ['my.turn/complete]})]
        (is (:db/id (db/pull database [:db/id] [:seon.fn/sym 'my.turn/complete])))
        (is (vector? actual))
        (is (= (set (functions/tests-reaching database 'my.turn/complete)) (set actual)))
        (is (= :seon.test/identity-unresolved
               (:seon.test/selection-refusal
                ;; Built at run time: a quoted symbol here would itself be an
                ;; indexed reference edge from this test.
                (sut/reaching {:seon.db/db database
                               :seon.test/changed [(symbol "absent.function"
                                                           (str "no-row-" (random-uuid)))]}))))))))

(defn- with-indexed-tests [connection namespace-name sources assertion]
  (let [root (doto (clojure.java.io/file "tmp" (str "reaching-source-" (id/id))) .mkdirs)]
    (try
      (spit (clojure.java.io/file root "probe.clj")
            (str "(ns " namespace-name " (:require [clojure.test] [clojure.java.io] [seon.id] [seon.schema] [malli.instrument]))\n"
                 (binding [*print-meta* true]
                   (str/join "\n" (map pr-str sources)))))
      (support/transacted! connection (functions/program-entities {:seon.fn/roots [(.getPath root)]}))
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
            (is (qualified-symbol? written))))))))

(deftest fixture-observations-remain-stale
  (support/with-database
    (fn [connection]
      (with-test connection '(clojure.test/is true)
        (fn [s v]
          (is (= 1 (:seon.test/pass-count (run-in-fixture v connection))))
          ;; The declaration changes through its revision, digest included.
          (is (:db-after (db/transact! connection
                           [(support/revision (db/db connection) [:seon.test/sym s]
                                              {:seon.test/fixture-observation
                                               "External fixture bytes must be observed."})])))
          (is (some #{s} (sut/stale (db/db connection))))
          ;; An observed external fixture is never verified by recorded evidence
          ;; (`seon.test/verified?` since b0b5eedfe).
          (is (false? (sut/verified? (db/db connection) s))))))))

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
                (is (= 1 (:seon.test/error-count result)))
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
              (is (= 1 (:seon.test/fail-count result)))
              (is (= 0 (:seon.test/error-count result)))
              (is (.contains (:seon.test/failure-message result "") "Original Datom failure"))
              ;; Admitted members record their reach (f3a4b33d9).
              (is (= (sut/reach-digest database s) (:seon.test.member/reach-digest result))))))))))

(deftest agent-admitted-tests-reach-their-tested-function
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "default")
      (let [namespace-name 'my.agents.reach-digest
            _ (support/transacted! connection [(support/namespace-entity (db/db connection) namespace-name)])
            ctx (support/fork-cluster-ctx connection)
            effective (seon.config/effective (db/db connection) "default")
            sources ["(defn largest-customer {:malli/schema [:=> [:cat [:vector {:min 1} [:map [:seon.test/pass-count :seon.test/pass-count]]]] [:map [:seon.test/pass-count :seon.test/pass-count]]]} [entities] (apply max-key :seon.test/pass-count entities))"
                     "(clojure.test/deftest largest-customer-test (clojure.test/is (= {:seon.test/pass-count 9} (largest-customer [{:seon.test/pass-count 2} {:seon.test/pass-count 9}]))))"]]
        (doseq [source sources]
          (let [evaluation (support/evaluate
                             {:seon.cluster.eval/source source
                              :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                              :seon.sci.eval/ctx ctx
                              :seon.sci.eval/time-limit-ms 10000
                              :seon.sci.admit/caps (seon.config/result-caps effective)
                              :seon.db/db (db/db connection) :seon.db/connection connection})
                analysis (functions/analyze-forms (db/db connection)
                           [{:seon.cluster.eval/source source
                             :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                             :seon.program/row (:seon.program/row evaluation)}])
                entity (when (vector? analysis) (second (first analysis)))]
            (is (map? entity))
            (when entity
              (support/transacted! connection [(dissoc entity :seon.sci.eval/evaluated?)]))))
        (let [database (db/db connection)
              s 'my.agents.reach-digest/largest-customer-test
              f 'my.agents.reach-digest/largest-customer
              before (sut/reach-digest database s)]
          (is (contains? (get (runner/reach-memberships database [s]) s) f))
          ;; A changed definition is re-analyzed, so its stored digest changes.
          (support/transacted! connection
                               [(support/program-entity database [:seon.fn/sym f]
                                                     (str/replace (first sources) "entities)" "(seq entities))"))])
          (is (not= before (sut/reach-digest (db/db connection) s))))))))

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
            (is (= 1 (:seon.test/pass-count result)))
            (is (zero? (:seon.test/fail-count result)))
            (is (zero? (:seon.test/error-count result)))))))))

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
  "What the declared owner says it destroys, read from its program entity."
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
            _ (is (contains? owners destructive-owner))
            ;; Each owner loses its declaration with the digest of its final
            ;; value, as the writer checks (support/revised-entity).
            removed (db/transact!
                     connection
                     (vec (mapcat (fn [[owner what]]
                                    (let [eid (:db/id (db/pull database [:db/id] [:seon.fn/sym owner]))
                                          stored (@#'seon.db/declaration-value database (db/identity-attributes database) eid)]
                                      [[:db/retract eid :seon.fn/destroys what]
                                       [:db/add eid :seon.program/definition-digest
                                        (:seon.program/definition-digest
                                         (support/revised-entity database (dissoc stored :seon.fn/destroys) {}))]]))
                                  owners)))
            after (db/db connection)
            derived (sut/destroyers after)]
        (is (:db-after removed))
        (is (string? (:seon.test/unknown derived)))
        (is (.contains (:seon.error/message derived "") ":seon.fn/destroys"))
        (is (string? (:seon.test/unknown (sut/host after 'seon.id-test/anything)))
            "an unanswerable declaration never answers in-process")
        (is (string? (:seon.test/unknown
                      (#'sut/host-exclusions
                       after
                       (.getCanonicalPath (clojure.java.io/file (System/getProperty "user.dir")))
                       ['seon.id-test/does-not-matter])))
            "an unanswerable reach refuses the request instead of admitting it")))))

(deftest a-test-with-no-program-entity-is-unknown-and-is-never-run-in-process
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
            (is (string? (:seon.test/unknown report)))
            (is (nil? (:seon.test/host report))
                "an unknown call graph never reads as in-process")
            (is (.contains (sut/host-text database test-symbol) "unknown")))
          (let [result (run-in-fixture test-var connection)]
            (is (= :seon.test/identity-unresolved (:seon.test/selection-refusal result)))
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
            (is (not (.contains what "\n")))
            (is (= (dissoc (sut/host database test-symbol) :seon.test/sym) (:seon.render.test/host ai))
                "the AI face carries where it runs as the host report's data")
            (is (= :seon.test.host/isolated-snapshot (get-in ai [:seon.render.test/host :seon.test/host])))
            (is (.contains html line)))
          ;; The cheap test is indexed from its source, digests included.
          (let [namespace-name (symbol (str "render.probe" (id/id)))
                cheap (symbol (str namespace-name) "cheap")]
            (with-indexed-tests connection namespace-name ['(clojure.test/deftest cheap (clojure.test/is true))]
              (fn []
                (let [ai (render.test/render-ai {:seon.db/db (db/db connection)
                                                 :seon.render/value {:seon.test/sym cheap}})]
                  (is (= :seon.test.host/in-process (get-in ai [:seon.render.test/host :seon.test/host]))))))))))))

(deftest a-run-renders-its-outcomes-counted-and-its-reds-by-name
  (let [member (fn [sym fails errors] {:seon.test.member/symbol sym :seon.test.member/completed-tx {:db/id 1}
                                       :seon.test.member/fail-count fails :seon.test.member/error-count errors})
        run {:seon.test.run/id "run-1" :seon.test.run/basis-t 7 :seon.test.run/policy :fixed
             :seon.test.run/members [(member 'a/ok 0 0) (member 'a/red 1 0) (member 'a/erred 0 1)
                                     {:seon.test.member/symbol 'a/cut}]}]
    (is (= {:seon.test.run/id "run-1" :seon.test.run/basis-t 7
            :seon.render.test/outcomes {:pass 1 :fail 1 :error 1 :unfinished 1}
            :seon.render.test/red ['a/erred 'a/red]}
           (render.test/render-run-ai {:seon.render/value run})))
    (is (= '(seon.db/pull '[*] [:seon.test.run/id "run-1"]) (render.test/render-run-form {:seon.render/value run}))
        "the form face reads the run whole")))

(deftest reused-results-render-the-recording-basis-as-data
  (let [unit {:seon.render/value
              {:seon.test/sym 'seon.test-reaching-test/reused-results-render-the-recording-basis-as-data
               :seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0
               :seon.test/run-basis-t 42 :seon.test/recorded-basis-t 43
               :seon.test/unchanged true}}
        ai (render.test/render-ai unit)
        html (pr-str (render.test/render-html unit))]
    (is (= {:seon.test/unchanged true :seon.test/recorded-basis-t 43 :seon.test/run-basis-t 42}
           (select-keys ai [:seon.test/unchanged :seon.test/recorded-basis-t :seon.test/run-basis-t])))
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

(deftest the-long-declaration-is-indexed-onto-the-test-entity
  (let [root (doto (clojure.java.io/file "tmp" (str "long-index-" (id/id))) .mkdirs)]
    (try
      (spit (clojure.java.io/file root "probe.clj")
            (str "(ns long.index" (id/id) " (:require [clojure.test :refer [deftest is]]))\n"
                 "(deftest ^{:seon.test/long " (pr-str long-declaration) "} probe (is true))\n"))
      (let [entities (functions/program-entities {:seon.fn/roots [(.getPath root)]})
            entity (first (filter :seon.test/sym entities))]
        (is (= long-declaration (:seon.test/long entity))
            "the indexer lifts the declaration onto the entity a check queries"))
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
                (is (= [cheap-symbol] (map :seon.test/sym (:seon.test/results result))))
                ;; An excluded long obligation is unfulfilled, never green (57f02fd5b).
                (is (false? (:seon.test/passed? result)))
                (is (= [{:seon.test/sym test-symbol
                         :seon.test/long long-declaration
                         :seon.test/command ["bin/test-check" "default" "--test" (str test-symbol)]}]
                       excluded))
                ;; ec93442ee: "excluded (declared long; include-long runs it) <test>".
                (is (.contains tally (str "excluded (declared long; include-long runs it) " test-symbol)))
                (is (.contains tally long-declaration))
                (is (not (.exists marker)) "the excluded test executed nothing")
                ;; A recorded execution is a run member (runner/latest-results).
                (is (empty? (runner/latest-results (db/db connection) [test-symbol])))))))))))

(deftest the-declared-opt-in-includes-the-long-test
  (support/with-database
    (fn [connection]
      (with-long-test connection {}
        (fn [test-symbol _ marker]
          (let [result (changed-request connection [test-symbol] {:seon.test/include-long? true})]
            (is (= [test-symbol] (map :seon.test/sym (:seon.test/results result))))
            (is (true? (:seon.test/passed? result)))
            (is (nil? (:seon.test/long-excluded result)))
            (is (.exists marker) "the opted-in test executed its body")
            (is (seq (runner/latest-results (db/db connection) [test-symbol])))))))))

