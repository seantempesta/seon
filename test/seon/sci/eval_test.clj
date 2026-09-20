(ns seon.sci.eval-test
  "Acceptance for the guarded eval (N3, C7).

  DRAFT FOR ORCHESTRATOR SEAL REVIEW (drafted 2026-07-27). Every test
  runs a REAL sci evaluation with a REAL armed boundary — there is no
  fake interrupt-fn here, because the one thing worth proving is that
  the mechanism stops what it claims to stop.

  The deadlines are short (a few hundred ms) and the runaway cases are
  genuinely unbounded, so a regression does not slow the suite: it
  fails it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.pull-api :as pull-api]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [seon.id]
            [seon.program :as program]
            [sci.addons.future :as sci.future]
            [sci.core :as sci]
            [seon.render :as render]
            [seon.render.walk :as render.walk]
            [seon.render.web :as render.web]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(deftest overrides-follow-current-admission-and-survive-lost-file-coordinates
  (test-support/with-database
   (fn [connection]
     (let [before (db/db connection)
           target (test-support/program-fn-row 'seon.id/symbol-in)
           test-definition (test-support/program-fn-row
                            'seon.sci.eval-test/compiled-runtime-victim)
           target-name (:seon.fn/sym target)
           indexed-members
           (db/q '[:find ?function ?file
                   :in $ ?name
                   :where [?namespace :seon.ns/name ?name]
                          [?function :seon.fn/ns ?namespace]
                          [?function :seon.fn/file ?file]]
                 before 'seon.id)]
       (is (seq indexed-members) "the canonical population supplies indexed src declarations")
       (is (not (some #{target-name} (program/overrides before))))
       (test-support/transacted!
        connection
        (into [(assoc target :seon.schema.admission/source :agent)
               (assoc test-definition :seon.schema.admission/source :agent)]
              (map (fn [[function file]] [:db/retract function :seon.fn/file file]))
              indexed-members))
       (let [overridden (db/db connection)
             projection (schema/projection-from-database overridden)]
         (is (= :agent (get-in projection
                              [:seon.schema.projection/function-admissions
                               'seon.id/symbol-in :seon.schema.admission/source])))
         (is (= :core (get-in projection
                             [:seon.schema.projection/function-admissions
                              'seon.id/valid? :seon.schema.admission/source]))
             "an unrelated core declaration cannot inherit another identity's agent provenance")
         (is (some #{target-name} (program/overrides overridden))
             "the last lost file coordinate does not hide an accepted override")
         (is (not (some #{(:seon.fn/sym test-definition)}
                        (program/overrides overridden)))
             "an agent-admitted definition under the test root is not a src override")
         (is (not (some #{target-name} (program/overrides before)))
             "a supplied older database keeps its own answer")
         (test-support/transacted! connection [target])
         (is (not (some #{target-name} (program/overrides (db/db connection))))
             "restoring core provenance removes the identity from the override set")
         (is (some #{target-name} (program/overrides overridden))
             "restoration does not change a previously supplied database value"))))))

(deftest absent-program-function-is-reported-before-namespace-lookup
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        function-symbol 'missing.program/function
        failure
        (with-redefs [kernel/program-namespace
                      (fn [_ _]
                        (throw (ex-info "Unexpected namespace lookup" {})))]
          (try
            (#'eval/install-function-from-database! ctx nil function-symbol)
            nil
            (catch clojure.lang.ExceptionInfo error (ex-data error))))]
    (is (= :seon.sci.eval/missing-function-row (:seon.error/kind failure)))
    (is (= function-symbol (:seon.fn/sym failure)))))

(defn- compiled-runtime-victim
  []
  :original)

(def ^:dynamic ^:private *compiled-runtime-dynamic-victim* :original)

(defn- compiled-runtime-ctx
  []
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))]
    (sci/add-namespace!
     ctx
     'stability.host
     {'victim #'compiled-runtime-victim
      '*dynamic-victim* #'*compiled-runtime-dynamic-victim*})
    ctx))

(def ^:private concurrency-capability-classes
  [java.lang.Thread
   java.util.concurrent.Executor
   java.util.concurrent.Future
   java.util.concurrent.CompletionStage
   java.util.concurrent.ThreadFactory])

(defn- exposed-class
  [candidate]
  (if (map? candidate) (:class candidate) candidate))

(defn- concurrency-capability-class?
  [candidate]
  (let [candidate (exposed-class candidate)]
    (and (class? candidate)
         (some #(.isAssignableFrom ^Class % ^Class candidate)
               concurrency-capability-classes))))

(defn- run-in
  [ctx source time-limit-ms]
  (eval/evaluate
   (cond-> {:seon.schema/projection (schema/handed-projection)
            :seon.cluster.eval/source source
            :seon.sci.admit/caps caps
            :seon.sci.eval/time-limit-ms time-limit-ms
            ;; development disposition: a codec hole must be loud
            ;; here of all places
            :seon.config/on-core-error :panic}
     ctx (assoc :seon.sci.eval/ctx ctx))))

(deftest database-provenance-regenerates-the-base-and-reverts-for-retained-forks
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "default")
     (test-support/transacted!
      connection
      (agent/creation-tx {:seon.cluster/name "default"
                          :seon.agent/id "s3-fixture"
                          :seon.ns/name 'my.agents.s3-fixture}))
     (let [before (db/db connection)
           original (merge (db/pull before '[*] [:seon.fn/sym 'seon.id/valid?])
                           (test-support/program-fn-row 'seon.id/valid?))
           _ (with-redefs [schema.edn/packaged-forms
                           (fn [] (throw (ex-info "A database-derived base must not read schema files." {})))]
               (eval/base-ctx before))
           initial (eval/base-ctx before)
           fork (fn [base previous database]
                  (:seon.sci.eval/ctx
                   (eval/fork-for-turn
                    (cond-> {:seon.sci.eval/ctx base :seon.db/db database
                             :seon.agent/id "s3-fixture"}
                      previous (assoc :seon.sci.eval/agent-ctx previous)))))
           b (fork initial nil before)
           private-result (run-in b "(def s3-private (atom 7))" 2000)
           private-var (sci/resolve b 'user/s3-private)
           private-object @private-var
           private-reader (run-in b "(do (def s3-private-value 1) (def s3-private-reader (fn [] s3-private-value)))" 2000)
           result-handle (seon.id/symbol-in "result" \e
                                           (seon.id/evaluation "s3-fixture" 0))
           _ (eval/bind-result! b result-handle private-object)
           _ (is (not (:seon.cluster.eval/error private-result)))
           _ (is (not (:seon.cluster.eval/error private-reader)))
           _ (is (identical? @#'seon.id/valid? @(sci/resolve initial 'seon.id/valid?)))
           _ (is (nil? (sci/resolve initial 'seon.sci.eval/absent-intern))
                 "a private sentinel is not a callable program declaration")
           admitted (assoc original :seon.schema.admission/source :agent
                           :seon.fn/source
                           "(defn valid? {:malli/schema [:=> [:cat [:int {:min 1}] :string] :boolean]} [length id] true)")]
       (test-support/transacted! connection [admitted])
       (let [after (db/db connection)
             regenerated (eval/base-ctx after)
             installed (eval/install-row! {:seon.sci.eval/ctx initial
                                            :seon.db/db after
                                            :seon.program/row
                                            (assoc (test-support/program-fn-row 'seon.id/valid?)
                                                   :seon.schema.admission/source :agent
                                                   :seon.fn/source (:seon.fn/source admitted))})
             b-next (fork initial b after)
             c (fork regenerated nil after)
             private-regenerated (#'eval/regenerate-agent-context! b regenerated)]
         (is (= :interpreted (:seon.sci.eval/load-state installed)))
         (is (str/includes?
              (:seon.schema.admission/note
               (eval/documentation-value after 'seon.id/valid? 'seon.id/valid?))
              "Accepted database override"))
         (is (= (set (keys (#'eval/base-bindings regenerated)))
                (set (keys (#'eval/base-bindings initial))))
             "incremental installation and regeneration resolve the same names")
         (let [core-symbols
               (db/q '[:find [?sym ...] :where
                       [?f :seon.fn/sym ?sym]
                       [?f :seon.schema.admission/source :core]
                       [?f :seon.fn/file ?file]
                       [?file :seon.fn.file/relative-root "src"]] after)]
           (is (seq core-symbols))
           (is (empty? (filterv (fn [name]
                                 (let [left (sci/resolve initial (symbol name))
                                       right (sci/resolve regenerated (symbol name))]
                                   (not (and left right (identical? @left @right)))))
                               core-symbols))
               "all src core roots agree between incremental installation and regeneration"))
         (doseq [ctx [initial b-next c private-regenerated]]
           (is (true? (:seon.sci.admit/value
                       (run-in ctx "(seon.id/valid? 8 \"s3-probe\")" 2000)))))
         (is (identical? private-var (sci/resolve b-next 'user/s3-private)))
         (is (identical? private-object @(sci/resolve b-next 'user/s3-private)))
         (is (= 2 (:seon.sci.admit/value
                   (run-in b-next "(do (def s3-private-value 2) (s3-private-reader))" 2000)))
             "a preserved private closure still reads its continuing private Var")
         (is (identical? private-object @(sci/resolve private-regenerated 'user/s3-private)))
         (is (nil? (sci/resolve c 'user/s3-private)))
         (is (identical? private-object @(sci/resolve b-next result-handle)))
         (is (identical? private-object @(sci/resolve private-regenerated result-handle)))
         (is (nil? (sci/resolve c result-handle)))
         (is (= (:seon.fn/source admitted)
                (:seon.sci.eval/function-source
                 (kernel/program-function regenerated 'seon.id/valid?)))))
       (test-support/transacted! connection [original])
       (let [restored (db/db connection)
             regenerated (eval/base-ctx restored)]
         (eval/install-row! {:seon.sci.eval/ctx initial :seon.db/db restored
                             :seon.program/row
                             (assoc (test-support/program-fn-row 'seon.id/valid?)
                                    :seon.fn/source (:seon.fn/source original))})
         (is (nil? (:seon.schema.admission/note
                    (eval/documentation-value restored 'seon.id/valid? 'seon.id/valid?))))
         (doseq [ctx [initial (fork initial b restored) regenerated]]
           (is (identical? @#'seon.id/valid? @(sci/resolve ctx 'seon.id/valid?)))))))))

(deftest non-evaluable-agent-source-reports-its-jvm-fallback
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
      connection
      [(assoc (test-support/program-fn-row 'seon.id/valid?)
              :seon.schema.admission/source :agent
              :seon.fn/source
              "(defn valid? [length id] (s3.missing/function length id))")])
     (let [ctx (eval/base-ctx (db/db connection))
           results (get-in ctx [:seon.sci.eval/acquisition :seon.sci.eval/load-results])]
       (is (some #(and (= 'seon.id/valid? (:seon.fn/sym %))
                       (= :jvm-fallback (:seon.sci.eval/load-state %))
                       (seq (:seon.error/message %))) results))
       (is (identical? @#'seon.id/valid? @(sci/resolve ctx 'seon.id/valid?)))))))

(defn- run
  ([source] (run source 2000))
  ([source time-limit-ms]
   (run-in nil source time-limit-ms)))

(defn- deadlined-in
  "Evaluate on another thread so a runaway FAILS the suite rather than
  hanging it — the guard being tested is exactly the one that should
  make this unnecessary."
  [ctx source time-limit-ms]
  (let [task (future (run-in ctx source time-limit-ms))]
    (or (deref task 10000 nil)
        (do (future-cancel task) ::hung))))

(defn- deadlined
  [source time-limit-ms]
  (deadlined-in nil source time-limit-ms))

;;; PRESENCE IS THE STATE (owner ruling 2026-07-28): there is no
;;; status enum on an evaluation. These three disjoint readers ARE the
;;; state model this suite asserts.

(defn- cut?
  "The time limit fired: the evaluation carries its cut instant."
  [evaluation]
  (some? (:seon.cluster.eval/interrupted-at evaluation)))

(defn- failed?
  "The form failed on its own: an error with no cut instant."
  [evaluation]
  (and (some? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

(defn- ok?
  "The form produced a value: no error and no cut instant."
  [evaluation]
  (and (nil? (:seon.cluster.eval/error evaluation))
       (not (cut? evaluation))))

;;; ---------------------------------------------------------------------------
;;; The ordinary path
;;; ---------------------------------------------------------------------------

(deftest the-request-is-what-the-contract-says-it-is
  ;; the dial is REQUIRED, so a caller cannot forget to decide
  (is (seon.schema/valid-candidate-value?
       :seon.sci.eval/request
       {:seon.cluster.eval/source "(+ 1 1)"
        :seon.sci.admit/caps caps
        :seon.sci.eval/time-limit-ms 1000
        :seon.config/on-core-error :panic}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.sci.eval/request
            {:seon.cluster.eval/source "(+ 1 1)"
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 1000}))
      "no dial, no evaluation"))

(deftest
  instrumented-generated-form-does-not-advise-an-absent-program-row
  (test-support/preserving-instrumentation-state
    (fn []
      (test-support/with-database
        (fn [connection]
          (let [database (db/db connection)
                ctx (test-support/fork-cluster-ctx connection)
                projection (db/carried-projection database)]
            (instrument/apply!
              {:seon.config/on-core-error :panic, :seon.schema/projection projection})
            (let [evaluation (eval/evaluate
                               {:seon.sci.eval/ctx ctx,
                                :seon.db/db database,
                                :seon.db/connection connection,
                                :seon.agent/id "root",
                                :seon.cluster.eval/source
                                "; generated opening non-declaration\n:opening-probe",
                                :seon.sci.admit/caps caps,
                                :seon.sci.eval/time-limit-ms 2000,
                                :seon.config/on-core-error :panic})]
              (is (nil? (:seon.program/row evaluation)))
              (is (ok? evaluation) (pr-str evaluation))
              (is
                (not=
                  :seon.instrument/contract-violated
                  (get-in evaluation [:seon.sci.admit/value :seon.error/kind]))))))))))

(deftest the-diagnostics-are-recorded-and-are-not-limits
  (let [evaluation (run "(reduce + (map inc (range 500)))")
        record (:seon.sci.admit/record evaluation)]
    (is (ok? evaluation))
    (is (= 125250 (:seon.sci.admit/value evaluation)))
    (testing "fn-entries counted the interpreted work"
      (is (pos? (:seon.eval/fn-entries record))))
    (is (zero? (:seon.eval/host-interop-count record)))
    (is (= :ok (:seon.eval/outcome record)))
    (is (int? (:seon.eval/duration-ms record)))
    (is (int? (:seon.eval/allocated-bytes record))
        "-1 is honest when the platform cannot measure; nil is not")))

(deftest host-interop-is-observed-during-analysis
  (let [plain (run "(.toUpperCase \"x\")" 10000)
        macro-expanded
        (run "(do (defmacro host-call [x] (list '.toUpperCase x))
                  (def f (fn [] (host-call \"x\"))))"
             10000)]
    (is (= 1 (get-in plain
                     [:seon.sci.admit/record
                      :seon.eval/host-interop-count])))
    (is (= 1 (get-in macro-expanded
                     [:seon.sci.admit/record
                      :seon.eval/host-interop-count]))
        "the fact follows SCI macro expansion rather than source syntax")))

(deftest store-faithful-is-class-metadata-and-value-exact
  (let [tagged (with-meta [1 2] {:session true})
        ordered (sorted-set-by > 1 2 3)
        function-map {:f (fn [] 1)}
        lazy-value (map inc [1 2])]
    (is (blob/store-faithful? tagged))
    (is (= tagged (edn/read-string (blob/store-faithful-edn tagged))))
    (is (not (blob/store-faithful? ordered))
        "a comparator-losing set is = but its restored class differs")
    (is (not (blob/store-faithful? function-map))
        "a function nested in otherwise ordinary data refuses the value tier")
    (is (not (blob/store-faithful? lazy-value))
        "a lazy sequence must not silently become a list")
    (is (not (blob/store-faithful? (fn [] 1)))
        "an opaque closure has no faithful stored representation")))

(deftest agent-print-vars-are-captured-before-sci-bindings-unwind
  (let [evaluation
        (run (str "(do (set! *print-length* 3) "
                  "(set! *print-level* 2) :captured)"))]
    (is (ok? evaluation))
    (is (= {:seon.print/length 3
            :seon.print/level 2}
           (:seon.print/options evaluation)))))

(deftest
  the-evaluator-remains-live-after-its-namespace-reloads
  (test-support/preserving-instrumentation-state
    (fn []
      (let [projection (schema/handed-projection)
            request {:seon.schema/projection projection
                     :seon.cluster.eval/source "(+ 1 2)"
                     :seon.sci.admit/caps caps
                     :seon.sci.eval/time-limit-ms 10000
                     :seon.config/on-core-error :panic}
            before (eval/evaluate request)]
        (try
          (is (= 3 (:seon.sci.admit/value before))
              "the first evaluation realizes the process guard")
          (require 'seon.sci.eval :reload)
          (let [after ((requiring-resolve 'seon.sci.eval/evaluate) request)]
            (is (= 3 (:seon.sci.admit/value after))
                "ordinary arm data has no reload-sensitive class identity")
            (is (nil? (:seon.cluster.eval/error after))))
          (finally
            ;; Restoration must not reinstall the superseded roots. Arm the
            ;; definitions the loader just installed, under the handed world.
            (let [report (instrument/apply!
                          {:seon.config/on-core-error :panic
                           :seon.schema/projection projection})]
              (is (nil? (:seon.error/kind report)) (pr-str report)))))))))

(deftest isolated-one-off-evaluations-do-not-share-definitions
  (run "(def leaked 1)")
  (let [evaluation (run "leaked")]
    (is (failed? evaluation)
        "one evaluation's def cannot reach the next")
    (is (schema/valid-candidate-value? :seon.sci.kernel/error
                                      (:seon.sci.admit/value evaluation)))))

(deftest a-live-context-preserves-definition-value-class-and-metadata
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        definition
        (run-in ctx
                (str "(def kept "
                     "(with-meta (sorted-set-by > 1 2) {:proof :kept}))")
                2000)
        value (sci.core/eval-string* ctx "kept")]
    (is (ok? definition))
    (is (instance? clojure.lang.PersistentTreeSet value)
        "live sharing preserves the concrete sorted-set representation")
    (is (= {:proof :kept} (meta value))
        "live sharing preserves metadata rather than merely `=` values")
    (is (= 2 (first value)))))

(deftest cluster-contexts-share-no-writable-sci-stock-vars
  (let [ctx-a (eval/build-base-ctx (seon.schema/handed-projection))
        ctx-b (eval/build-base-ctx (seon.schema/handed-projection))
        shared-writable
        (for [[ns-sym ns-map] (:namespaces @(:env ctx-a))
              [sym var-a] ns-map
              :let [var-b (get-in @(:env ctx-b)
                                  [:namespaces ns-sym sym])]
              :when (and (instance? sci.lang.Var var-a)
                         (identical? var-a var-b)
                         (not (:sci/built-in (meta var-a))))]
          (symbol (str ns-sym) (str sym)))
        before (sci/eval-string*
                ctx-b
                "(clojure.walk/macroexpand-all '(when true :ok))")
        attempt
        (try
          (sci/eval-string*
           ctx-a
           "(alter-var-root #'clojure.walk/macroexpand-all identity)")
          ::root-rebound
          (catch Throwable failure
            failure))]
    (is (empty? shared-writable) (pr-str (sort shared-writable)))
    (is (instance? Throwable attempt))
    (is (re-find #"read-only" (ex-message attempt)))
    (is (= before
           (sci/eval-string*
            ctx-b
            "(clojure.walk/macroexpand-all '(when true :ok))")))))

(deftest acquired-source-context-forks-have-branch-custody-and-private-defs
  (test-support/with-database
    (fn [connection-a]
      (test-support/with-database
        (fn [connection-b]
          (test-support/seed-cluster! connection-a "fork-a")
          (test-support/seed-cluster! connection-b "fork-b")
          (let [ctx-a (test-support/fork-cluster-ctx connection-a)
                ctx-b (test-support/fork-cluster-ctx connection-b)
                query
                "(seon.db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]])"
                evaluate
                (fn [ctx]
                  (eval/evaluate
                   {:seon.cluster.eval/source query
                    :seon.cluster.eval/ns [:seon.ns/name 'user]
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic}))]
            (sci/eval-string* ctx-a "(def fork-private :only-a)")
            (is (= :only-a (sci/eval-string* ctx-a "fork-private")))
            (is (thrown? Throwable
                         (sci/eval-string* ctx-b "fork-private"))
                "a definition in one fork cannot mutate its sibling")
            (is (= ["fork-a"] (:seon.sci.admit/value (evaluate ctx-a))))
            (is (= ["fork-b"] (:seon.sci.admit/value (evaluate ctx-b)))
                "each fork derives database custody from its branch")))))))

(deftest acquired-source-context-forks-own-their-lazy-program-state
  (test-support/with-database
    (fn [connection]
      (let [ctx-a (test-support/fork-cluster-ctx connection)
            ctx-b (test-support/fork-cluster-ctx connection)
            installed-a (::kernel/installed-functions ctx-a)
            installed-b (::kernel/installed-functions ctx-b)
            snapshot-a (::kernel/program-snapshot ctx-a)
            snapshot-b (::kernel/program-snapshot ctx-b)
            function-symbol 'fork-private/lazy-function
            function-row {:seon.fn/sym function-symbol}]
        (is (not (identical? installed-a installed-b)))
        (is (not (identical? snapshot-a snapshot-b)))
        (kernel/cache-function! ctx-a function-symbol function-row)
        (kernel/mark-installed! ctx-a function-symbol)
        (is (= function-row (kernel/program-function ctx-a function-symbol)))
        (is (contains? @installed-a function-symbol))
        (is (nil? (kernel/program-function ctx-b function-symbol))
            "a sibling must retain its own acquired program snapshot")
        (is (not (contains? @installed-b function-symbol))
            "a sibling must not skip installation because another fork installed the symbol")))))

(deftest agent-context-exposes-no-concurrency-capability
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        env @(:env ctx)
        future-addon-symbols
        (set (keys (get-in (sci.future/install {})
                           [:namespaces 'clojure.core])))
        exposed-core-symbols
        (set (keys (get-in env [:namespaces 'clojure.core])))
        exposed-classes (vals (:raw-classes env))]
    (is (seq future-addon-symbols)
        "SCI's optional future add-on must remain a real test subject")
    (is (empty? (set/intersection future-addon-symbols
                                  exposed-core-symbols))
        "the actual ctx excludes every primitive from SCI's concurrency add-on")
    (is (seq exposed-classes)
        "the class-gate assertion must not pass over a missing class surface")
    (is (empty? (filter concurrency-capability-class? exposed-classes))
        "no class exposed by the actual ctx can create or carry thread work")))

(deftest compiled-runtime-roots-cannot-be-redefined-by-agent-code
  (let [ctx (compiled-runtime-ctx)
        victim-root (var-get #'compiled-runtime-victim)
        dynamic-root (var-get #'*compiled-runtime-dynamic-victim*)
        mutation-forms
        ["(alter-var-root #'stability.host/victim (constantly (fn [] :changed)))"
         (str "(with-redefs [stability.host/victim (fn [] :changed)] "
              "(stability.host/victim))")
         "(var-set #'stability.host/victim (fn [] :changed))"
         "(intern 'stability.host 'victim (fn [] :changed))"
         (str "(binding [stability.host/*dynamic-victim* :changed] "
              "stability.host/*dynamic-victim*)")
         (str "(do (push-thread-bindings "
              "{#'stability.host/*dynamic-victim* :changed}) "
              "(try :changed (finally (pop-thread-bindings))))")]]
    (try
      (doseq [form mutation-forms]
        (let [evaluation (run-in ctx form 2000)]
          (is (failed? evaluation) form)
          (is (identical? victim-root
                          (var-get #'compiled-runtime-victim))
              form)
          (is (identical? dynamic-root
                          (var-get #'*compiled-runtime-dynamic-victim*))
              form)))
      (finally
        (alter-var-root #'compiled-runtime-victim (constantly victim-root))
        (alter-var-root #'*compiled-runtime-dynamic-victim*
                        (constantly dynamic-root))))))

(deftest compiled-runtime-metadata-cannot-be-changed-by-agent-code
  (let [ctx (compiled-runtime-ctx)
        before (meta #'compiled-runtime-victim)
        mutation-forms
        ["(alter-meta! #'stability.host/victim assoc :arglists '([poisoned]))"
         "(reset-meta! #'stability.host/victim {:arglists '([poisoned])})"]]
    (try
      (doseq [source mutation-forms]
        (let [evaluation (run-in ctx source 2000)
              refusal (:seon.sci.admit/value evaluation)
              instrumentation-read
              (#'instrument/violation
               nil :malli.core/invalid-arity
               {:fn-name 'seon.sci.eval-test/compiled-runtime-victim
                :arity 9})]
          (is (failed? evaluation) source)
          (is (schema/valid-candidate-value? :seon.sci.kernel/error refusal)
              source)
          (is (str/includes? (:seon.error/message refusal)
                             "metadata is read-only from SCI")
              source)
          (is (= before (meta #'compiled-runtime-victim)) source)
          (is (= (:arglists before)
                 (::instrument/arglists
                  (:seon.error/data instrumentation-read)))
              "instrumentation reads only the unpoisoned compiled metadata")))
      (finally
        (reset-meta! #'compiled-runtime-victim before)))))

(deftest agent-owned-sci-var-metadata-remains-mutable
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        altered
        (run-in
         ctx
         (str "(do (defn local-meta \"Original doc.\" [] :ok) "
              "(alter-meta! #'local-meta assoc :agent-owned true) "
              "[(:doc (meta #'local-meta)) "
              "(:agent-owned (meta #'local-meta))])")
         2000)
        reset
        (run-in
         ctx
         (str "(do (reset-meta! #'local-meta "
              "(assoc (meta #'local-meta) :doc \"Reset doc.\" :reset true)) "
              "[(:doc (meta #'local-meta)) (:reset (meta #'local-meta))])")
         2000)]
    (is (ok? altered))
    (is (= ["Original doc." true]
           (:seon.sci.admit/value altered))
        "defn doc metadata and explicit SCI-local mutation remain ordinary REPL behavior")
    (is (ok? reset))
    (is (= ["Reset doc." true]
           (:seon.sci.admit/value reset)))))

(deftest sci-fork-copies-existing-var-roots-on-write
  (let [parent (eval/build-base-ctx (seon.schema/handed-projection))
        _ (sci/eval-string* parent
                            "(def shared :parent) (def bound :parent) (def untouched :parent)")
        forked (sci/fork parent)
        untouched-parent-var (sci/resolve parent 'untouched)
        untouched-fork-var (sci/resolve forked 'untouched)
        _ (sci/eval-string* forked "(def fork-only :fork-only)")
        _ (sci/eval-string* forked "(def shared :fork-redefinition)")
        parent-var (sci/resolve parent 'shared)
        fork-var (sci/resolve forked 'shared)
        bound-var (sci/bind-root! forked
                                  (sci/resolve forked 'bound)
                                  :fork-bind-root)]
    (is (nil? (sci/resolve parent 'fork-only))
        "a new fork name changes only the fork's env map")
    (is (identical? untouched-parent-var untouched-fork-var)
        "an untouched name retains the structurally shared Var")
    (is (not (identical? parent-var fork-var))
        "a redefinition creates a generation-owned Var")
    (is (= :parent (sci/eval-string* parent "shared"))
        "eval-def leaves the parent root unchanged")
    (is (= :fork-redefinition (sci/eval-string* forked "shared")))
    (is (identical? bound-var (sci/resolve forked 'bound)))
    (is (= :parent (sci/eval-string* parent "bound"))
        "context-aware root binding leaves the parent root unchanged")
    (is (= :fork-bind-root (sci/eval-string* forked "bound")))))

(deftest sci-fork-preserves-compiled-var-hot-reload
  (let [parent (compiled-runtime-ctx)
        forked (sci/fork parent)
        entering-root (var-get #'compiled-runtime-victim)]
    (try
      (is (identical? #'compiled-runtime-victim
                      (get-in (sci/namespace-state forked)
                              ['stability.host 'victim])))
      (is (= :original (sci/eval-string* forked "(stability.host/victim)")))
      (alter-var-root #'compiled-runtime-victim
                      (constantly (fn [] :hot-reloaded)))
      (is (= :hot-reloaded
             (sci/eval-string* forked "(stability.host/victim)"))
          "the next host call dereferences the live compiled Var")
      (finally
        (alter-var-root #'compiled-runtime-victim
                        (constantly entering-root))))))

(deftest contract-installation-in-a-fork-leaves-the-parent-var-unchanged
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "fork-contract")
      (let [parent (eval/build-base-ctx (seon.schema/handed-projection))
            _ (sci/eval-string*
               parent
               "(defn contracted [x] x)")
            parent-var (sci/resolve parent 'contracted)
            parent-root @parent-var
            candidate (sci/fork parent)]
        (#'eval/install-function-contract!
         candidate
         {:seon.fn/sym 'user/contracted
          :seon.fn/spec "[:=> [:cat :int] :int]"}
         (seon.schema/projection-from-database (db/db connection))
         (db/db connection))
        (is (identical? parent-var (sci/resolve parent 'contracted)))
        (is (identical? parent-root @(sci/resolve parent 'contracted)))
        (is (not (identical? parent-var
                             (sci/resolve candidate 'contracted)))
            "contract installation copies the inherited candidate Var")
        (is (= 42 (sci/eval-string* candidate "(contracted 42)")))))))

(deftest a-configless-database-refuses-contract-installation
  ;; THE CLASS: an absence handed into a contract that forbids it.
  ;; `database-effective-config` answered nil for a database carrying no
  ;; config singleton, and `instrumentation-config` passed that straight to
  ;; `seon.config/result-caps`, whose declared input is the effective config
  ;; OR the missing-effective refusal. Under armed contracts every SCI
  ;; contract install against such a database died inside the installer.
  ;;
  ;; Neither a missing recorder nor unavailable caps permits an unarmed
  ;; record-mode installation.
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            configured
            (db/q '[:find ?cluster .
                    :where
                    [?config :seon.config/cluster ?cluster]
                    [?config :seon.config/on-core-error _]]
                  database)
            {mode :seon.config/on-core-error caps :seon.sci.admit/caps}
            (#'eval/instrumentation-config database)]
        (is (nil? configured)
            "this database genuinely carries no config singleton, which is
             the case that produced the nil")
        (is (= :record mode))
        (is (= :seon.config/missing-result-cap (:seon.error/kind caps))
            "the caps are the refusal NAMING the key, not an absence")
        (is (= :seon.config.eval.result/max-bytes
               (:seon.config/key (:seon.error/data caps)))
            "and the key it names is the one a caller has to supply")
        (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))]
          (sci/eval-string* ctx "(defn configless-contracted [x] x)")
          (let [refusal (test-support/refusal-data
                         #(#'eval/install-function-contract!
                           ctx
                           {:seon.fn/sym 'user/configless-contracted
                            :seon.fn/spec "[:=> [:cat :int] :int]"}
                           (schema/projection-from-database database)
                           database))]
            (is (= :seon.instrument/missing-recorder (:seon.error/kind refusal)))
            (is (= :seon.flow/commit-fault! (:seon.error/expected-key refusal)))))))))

(deftest require-context-rows-carry-namespace-symbols
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        evaluation (run-in ctx "(require 'clojure.set)" 2000)]
    (is (ok? evaluation))
    (is (= #{'clojure.set}
           (get-in evaluation
                   [:seon.program/row :seon.ns/requires]))
        "namespace dependencies observe their symbol values")))

(deftest base-context-injections-have-program-rows
  (let [injected (set (program/base-context-injected-symbols))
        rows (#'seon.fn/desired-rows
              {:seon.fn/roots ["src" "test"]} nil)
        published (set (keep :seon.fn/sym rows))
        ctx (eval/build-base-ctx (seon.schema/handed-projection))]
    (is (empty? (set/difference injected published))
        "the context declaration is the population's binding authority")
    (is (every? #(sci/resolve ctx %) (program/base-context-injected-symbols))
        "every declared injection resolves in the constructed context")))

(deftest
  runtime-function-rows-carry-parsed-contract-facts
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            evaluation (run-in
                         ctx
                         (str
                           "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                           "parsed-at-runtime [x] x)")
                         2000)
            row (:seon.program/row evaluation)]
        (is (= 'user/parsed-at-runtime (:seon.fn/sym row)))
        (is (= 1 (count (:seon.fn/arities row))))
        (is (every? :seon.fn.arity/return-schema (:seon.fn/arities row)))))))

(deftest
  static-and-runtime-contracted-definitions-publish-identical-facts
  (test-support/with-database
    (fn [connection]
      (let [root (java.nio.file.Files/createTempDirectory
                   (.toPath (io/file "tmp"))
                   "p12-runtime-parity"
                   (make-array java.nio.file.attribute.FileAttribute 0))
            source (str
                     "(defn ^{:malli/schema "
                     "[:=> [:cat [:map [:x :int]] [:* :string]] :int]} "
                     "same-facts [{:keys [x]} & xs] x)")
            source-file (.resolve root "parity.clj")]
        (try
          (spit (.toFile source-file) (str "(ns parity)\n" source "\n"))
          (let [static-row (first
                             (filter
                               #(= 'parity/same-facts (:seon.fn/sym %))
                               (#'seon.fn/desired-rows
                                 {:seon.fn/roots ["src" (str root)]}
                                 (fn [_phase]))))
                ctx (test-support/fork-cluster-ctx connection)
                runtime-row (:seon.program/row
                              (eval/evaluate
                                {:seon.sci.eval/ctx ctx,
                                 :seon.cluster.eval/ns [:seon.ns/name 'parity],
                                 :seon.cluster.eval/source source,
                                 :seon.sci.admit/caps caps,
                                 :seon.sci.eval/time-limit-ms 2000,
                                 :seon.config/on-core-error :panic}))
                p12-keys [:seon.fn/arities :seon.fn/arglists-override?]]
            (is (= 'parity/same-facts (:seon.fn/sym runtime-row)))
            (is (= (select-keys static-row p12-keys) (select-keys runtime-row p12-keys)))
            (is (= :core (:seon.schema.admission/source static-row)))
            (is (= :agent (:seon.schema.admission/source runtime-row))))
          (finally (test-support/delete-recursively! (str root))))))))

(deftest contracted-defn-renders-the-var-it-declared
  (let [plain (run "(def plain-declaration 1)")
        contracted (run "(defn ^{:malli/schema [:=> [:cat :int] :int]} rendered-declaration [x] x)")]
    (is (instance? sci.lang.Var (:seon.sci.admit/value plain)))
    (is (instance? sci.lang.Var (:seon.sci.admit/value contracted)))
    (is (= "#'user/plain-declaration" (:seon.eval/shown plain)))
    (is (= "#'user/rendered-declaration" (:seon.eval/shown contracted)))))

(deftest every-public-capability-function-in-the-graph-resolves-in-the-ctx
  ;; The class: ctx membership derived from what something else HAPPENED to
  ;; load. `my.fs`, `my.shell`, and `my.edit` are loaded as a side effect of
  ;; resolving the core predicates they register; `my.web` registers none, so
  ;; it was never in `all-ns` when the install ran and the install silently
  ;; skipped it. `my.web/fetch` and `my.web/search` were public, contracted,
  ;; in the program graph, and unreachable from agent code. Membership is now
  ;; the graph's, so a namespace cannot fall off by registering no predicate.
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/cluster-ctx (db/db connection) connection)
            capability-symbols
            (sort
             (db/q '[:find [?sym ...]
                     :where
                     [?fn :seon.fn/sym ?sym]
                     [?fn :seon.fn/private? false]
                     [?fn :seon.effect/capability _]]
                   (db/db connection)))
            resolved
            (:seon.sci.admit/value
             (run-in ctx
                     (pr-str (list 'mapv
                                   '(fn [s] [s (some? (resolve s))])
                                   (list 'quote
                                         (mapv symbol capability-symbols))))
                     10000))]
        (is (seq capability-symbols)
            "the fixture graph carries the capability surface")
        (is (contains? (set capability-symbols) 'my.web/fetch)
            "my.web is in the program graph")
        (is (= (mapv (fn [s] [(symbol s) true]) capability-symbols)
               resolved)
            "every public capability function in the graph resolves in the ctx")))))

(deftest the-context-binds-only-the-graph-this-process-can-serve
  ;; The other half of the same seam, and the one a test-runner JVM cannot
  ;; see by accident: the program graph is indexed from BOTH source roots, so
  ;; `test/` namespaces are ordinary core-provenanced rows, while a cluster
  ;; JVM runs -M:dev with no test/ on its classpath. Requiring every graph row
  ;; refused every cluster boot on 2026-08-08. Graph membership and PROCESS
  ;; membership are two facts, and the classpath is the one that answers the
  ;; second — a computed fact, never a path convention or a maintained list.
  (let [locatable? (ns-resolve 'seon.sci.eval 'classpath-locatable?)
        host-namespace! (ns-resolve 'seon.sci.eval 'host-namespace!)]
    (is (true? (locatable? 'my.web))
        "a capability namespace this process can serve is servable")
    (is (false? (locatable? 'seon.sci.eval-test.absent-from-every-classpath)))
    (is (nil? (host-namespace!
               'seon.sci.eval-test.absent-from-every-classpath))
        "a row this process cannot serve is nil, never a refused boot")
    (is (some? (host-namespace! 'my.web))
        "a row it can serve is loaded rather than skipped")))

(deftest unloadable-host-namespace-carries-the-cause-message-and-location
  (let [host-namespace! (ns-resolve 'seon.sci.eval 'host-namespace!)
        probe-namespace 'seon.sci.eval-test.unresolved-var-probe
        test-root (-> (io/resource "seon/sci/eval_test.clj")
                      .toURI io/file .getParentFile)
        source (io/file test-root "eval_test" "unresolved_var_probe.clj")]
    (try
      (io/make-parents source)
      (spit source
            (str "(ns " probe-namespace ")\n"
                 "(def value absent.namespace/value)\n"))
      (let [failure (try
                      (host-namespace! probe-namespace)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))
            data (ex-data failure)
            diagnostic-data (:seon.error/data data)
            evidence (:seon.error/diagnostic-evidence diagnostic-data)
            location (:seon.sci.eval/cause-location evidence)
            cause-message (:seon.error/diagnostic-cause diagnostic-data)]
        (is (= :seon.sci.eval/namespace-unloadable (:seon.error/kind data)))
        (is (str/includes? cause-message "absent.namespace"))
        (is (str/ends-with? (:clojure.error/source location)
                            "unresolved_var_probe.clj"))
        (is (pos-int? (:clojure.error/line location)))
        (is (str/includes? (:seon.error/message data) cause-message))
        (is (str/includes? (:seon.error/message data)
                           (str "unresolved_var_probe.clj:"
                                (:clojure.error/line location)))))
      (finally
        (when (find-ns probe-namespace) (remove-ns probe-namespace))
        (io/delete-file source true)))))

(deftest process-membership-ignores-a-thread-context-classloader
  ;; The same seam, one level down: "what can this process serve" must be a
  ;; property of the PROCESS, not of whoever is on the stack. `io/resource`'s
  ;; one-argument arity asks `clojure.lang.RT/baseLoader`, i.e. the CURRENT
  ;; THREAD's context classloader, so a caller that binds one silently
  ;; redefines membership. `seon.test/with-test-loader` binds exactly such a
  ;; loader over the `:test` source paths, and acquiring an evaluation context
  ;; inside its extent made every `test/` namespace row locatable in a
  ;; development JVM; the install then died requiring the first row whose own
  ;; dependency is an alias extra-dep rather than a source path
  ;; (`seon.dev.dependency-cache-test` -> `dev-cache` ->
  ;; `clojure.tools.build.api`, 2026-09-16) and poisoned the shared fixture
  ;; base for every lane in that JVM. A namespace whose require would fail
  ;; must never be able to fail acquisition by being reachable only through a
  ;; caller's loader.
  ;;
  ;; The probe namespace has NO file on any classpath: it exists only inside
  ;; the temporary directory this test adds to a `DynamicClassLoader`, so the
  ;; property holds by construction and nothing can be left loaded.
  (let [locatable? (ns-resolve 'seon.sci.eval 'classpath-locatable?)
        host-namespace! (ns-resolve 'seon.sci.eval 'host-namespace!)
        probe-namespace 'seon.sci.eval-test.loader-only-probe
        root (.toFile (java.nio.file.Files/createTempDirectory
                       "seon-loader-only-probe"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [source (io/file root "seon" "sci" "eval_test" "loader_only_probe.clj")]
        (io/make-parents source)
        ;; Unloadable for the same reason the real row was: it requires a
        ;; namespace this process's classpath does not carry.
        (spit source
              (str "(ns " probe-namespace
                   " (:require [seon.sci.eval-test.absent-dependency]))\n"))
        (let [loader (doto (clojure.lang.DynamicClassLoader.
                            (clojure.lang.RT/baseLoader))
                       (.addURL (.toURL (.toURI root))))
              thread (Thread/currentThread)
              previous (.getContextClassLoader thread)
              answers (try
                        (.setContextClassLoader thread loader)
                        (with-bindings {clojure.lang.Compiler/LOADER loader}
                          {:reachable-through-the-caller-loader
                           (boolean (io/resource
                                     "seon/sci/eval_test/loader_only_probe.clj"))
                           :locatable? (locatable? probe-namespace)
                           :host-namespace (host-namespace! probe-namespace)})
                        (finally
                          (.setContextClassLoader thread previous)))]
          (is (true? (:reachable-through-the-caller-loader answers))
              "the bound loader genuinely serves the probe source")
          (is (false? (:locatable? answers))
              "process membership does not follow the caller's loader")
          (is (nil? (:host-namespace answers))
              "an unloadable row reachable only through a caller's loader is
               not this process's callable surface, never a refused
               acquisition")
          (is (nil? (find-ns probe-namespace))
              "and it was never required into this JVM")))
      (finally
        (run! io/delete-file (reverse (file-seq root)))))))

(deftest schema-and-contract-declarations-have-bounded-allocation
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/cluster-ctx (db/db connection) connection)]
        ;; Warm the guarded evaluator so this measures declaration work on one
        ;; cluster-owned projection rather than context acquisition.
        (run-in ctx "(+ 1 1)" 2000)
        (let [schema-evaluation
              (run-in
               ctx
               (str "(seon.schema/register! "
                    ":seon.sci.eval-test.allocation/score "
                    "[:int {:min 0 :max 100}])")
               5000)
              function-evaluation
              (run-in
               ctx
               (str "(defn ^{:malli/schema [:=> [:cat :string] :string]} "
                    "allocation-contract [x] x)")
               5000)
              allocation-limit (* 64 1024 1024)]
          (is (= :seon.sci.eval-test.allocation/score
                 (:seon.sci.admit/value schema-evaluation)))
          (is (= :ok
                 (get-in schema-evaluation
                         [:seon.sci.admit/record :seon.eval/outcome])))
          (is (= :ok
                 (get-in function-evaluation
                         [:seon.sci.admit/record :seon.eval/outcome])))
          (is (< (get-in schema-evaluation
                         [:seon.sci.admit/record :seon.eval/allocated-bytes])
                 allocation-limit)
              "one schema declaration stays below 64 MiB at registry size")
          (is (< (get-in function-evaluation
                         [:seon.sci.admit/record :seon.eval/allocated-bytes])
                 allocation-limit)
              "one contracted defn stays below 64 MiB at registry size"))))))

(deftest evaluate-invokes-eval-form-exactly-once-on-every-path
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        _ (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)})
        eval-form sci/eval-form
        call-with-registration-delta
        seon.schema/call-with-registration-delta
        calls (atom [])
        delta-observations (atom [])
        run-counted
        (fn [source]
          (reset! calls [])
          (let [evaluation (run-in ctx source 10000)]
            {:evaluation evaluation
             :calls (count @calls)}))]
    (sci/eval-string* ctx "(def plain-count 0) (def schema-count 0)")
    (with-redefs
      [sci/eval-form
       (fn [execution-ctx form]
         (swap! calls conj form)
         (eval-form execution-ctx form))
       seon.schema/call-with-registration-delta
       (fn
         ([delta body]
          (let [before
                (seon.schema/registration-delta-form
                 delta :user/once-schema)
                value (call-with-registration-delta delta body)
                after
                (seon.schema/registration-delta-form
                 delta :user/once-schema)]
            (swap! delta-observations conj
                   {:before before :after after :value value})
            value))
         ([delta admission body]
          (call-with-registration-delta delta admission body)))]
      (let [plain
            (run-counted "(def plain-count (inc plain-count))")
            contracted
            (run-counted
             (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                  "once-function [x] x)"))
            schema
            (run-counted
             (str "(seon.schema/register! :user/once-schema "
                  "(do (def schema-count (inc schema-count)) "
                  "[:int {:min 0}]))"))
            intern-values (#'eval/intern-values ctx)]
        (is (= 1 (:calls plain)) "the plain call site fires once")
        (is (= 1 (:calls contracted))
            "the live-declaration call site fires once")
        (is (= 1 (:calls schema))
            "the registration-delta call site fires once")
        (is (= 1 (get intern-values 'user/plain-count)))
        (is (ifn? (get intern-values 'user/once-function)))
        (is (= 1 (get intern-values 'user/schema-count))
            "the schema expression's side effect occurs once")
        (is (= [{:before nil
                 :after [:int {:min 0}]
                 :value :user/once-schema}]
               @delta-observations)
            "the schema form becomes visible only inside its delta")
        (is (nil? (get (seon.schema/registered-schemas)
                       :user/once-schema))
            "evaluation never publishes the isolated schema delta")))))))

(deftest success-evaluation-assembles-every-optional-projection
  (let [printed (doto (java.io.StringWriter.) (.write "abcdef"))
        record {:seon.eval/outcome :ok}
        row {:seon.fn/sym 'user/f}
        defs [{:seon.def/id "user/x"}]
        evaluation
        (#'eval/success-evaluation
         {:seon.sci.eval/admitted
          {:seon.sci.admit/value 7
           :seon.eval/shown "7"
           :seon.sci.admit/record record}
          :seon.sci.admit/caps
          (assoc caps :seon.config.eval.result/max-string 3)
          :seon.sci.eval/output-prefix "restored"
          :seon.sci.eval/printed printed
          :seon.sci.eval/namespace-name 'user
          :seon.sci.eval/ending-namespace 'next
          :seon.print/options {:seon.print/length 4}
          :seon.sci.eval/bindings defs
          :seon.program/row row})]
    (is (= {:seon.sci.admit/value 7
            :seon.eval/shown "7"
            :seon.print/options {:seon.print/length 4}
            :seon.cluster.eval/ns [:seon.ns/name 'user]
            :seon.sci.eval/ending-ns 'next
            :seon.sci.admit/record record
            :seon.program/row row
            :seon.sci.eval/bindings defs
            :seon.cluster.eval/output "restored\nabcdef"}
           evaluation))))

(deftest failed-evaluation-assembles-failure-presence-facts
  (let [printed (doto (java.io.StringWriter.) (.write "before failure"))
        interrupted-at (java.util.Date. 1785000000000)
        record {:seon.eval/outcome :time}
        value {:seon.error/kind :seon.sci.eval/time-limit
               :seon.error/message "Ran out of time."}
        admitted {:seon.sci.admit/value value
                  :seon.eval/shown (pr-str value)}
        defs [{:seon.def/id "user/x"}]
        evaluation
        (#'eval/failed-evaluation
         {:seon.sci.eval/admitted admitted
          :seon.sci.admit/caps
          (assoc caps :seon.config.eval.result/max-string 6)
          :seon.sci.eval/output-prefix "lost"
          :seon.sci.eval/printed printed
          :seon.sci.eval/namespace-name 'user
          :seon.print/options {:seon.print/level 3}
          :seon.sci.eval/bindings defs
          :seon.sci.admit/record record
          :seon.sci.admit/value value
          :seon.cluster.eval/interrupted-at interrupted-at})]
    (is (= {:seon.sci.admit/value value
            :seon.eval/shown (pr-str value)
            :seon.print/options {:seon.print/level 3}
            :seon.cluster.eval/ns [:seon.ns/name 'user]
            :seon.sci.eval/ending-ns 'user
            :seon.cluster.eval/error "Ran out of time."
            :seon.sci.admit/record record
            :seon.sci.eval/bindings defs
            :seon.cluster.eval/interrupted-at interrupted-at
            :seon.cluster.eval/output "lost\nbefore failure"}
           evaluation))))

(deftest evaluation-projection-prefers-the-live-context
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            projection (db/carried-projection database)
            fallback db/projection-fallback
            missing (atom [])]
        (with-redefs [schema/build-projection
                      (fn [& _] (throw (ex-info "Unexpected projection rebuild" {})))
                      db/projection-fallback
                      (fn [operation]
                        (swap! missing conj operation)
                        (fallback operation))]
          (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))]
            (is (identical? projection (#'eval/evaluation-projection
                                       {:seon.sci.eval/ctx ctx})))
            (is (identical? projection (#'eval/evaluation-projection
                                       {:seon.db/db database})))
            (is (identical? projection (#'eval/evaluation-projection
                                       {:seon.schema/projection projection})))
            (is (= 2 (:seon.sci.admit/value (run-in ctx "(+ 1 1)" 2000))))
            (is (empty? @missing))
            (let [refusal (test-support/refusal-data
                           #(#'eval/evaluation-projection {}))]
              (is (schema/valid-candidate-value? :seon.schema/validation-refusal refusal))
              (is (= :seon.schema/projection (:seon.schema/expected-value refusal)))
              (is (= {:seon.db/operation 'seon.sci.eval/evaluate}
                     (:seon.schema/refused-value refusal)))
              (is (= ['seon.sci.eval/evaluate] @missing)))))))))

(deftest
 unmap-row-carries-the-exact-forked-namespace-state
 (let
  [ctx
   (eval/build-base-ctx (seon.schema/handed-projection))
   _
   (sci/eval-string*
    ctx
    (str
     "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
     "discarded [x] x)"))
   source
   "(ns-unmap 'user 'discarded)"
   event
   (#'eval/one-event source 'user ctx (count source))
   execution-ctx
   (sci/fork ctx)
   before-interns
   (sci/namespace-interns execution-ctx)
   before-namespace-state
   (sci/namespace-state execution-ctx)
   before-reader-context
   (#'eval/reader-context execution-ctx 'user)
   _
   (sci/binding
    [sci/ns (sci/create-ns 'user)]
    (sci/eval-form execution-ctx (:seon.sci.reader/form event)))
   result
   (#'eval/unmap-row
    {:seon.cluster.eval/source source,
     :seon.sci.eval/event event,
     :seon.sci.eval/namespace-unmap? true,
     :seon.sci.eval/live-declaration? false,
     :seon.sci.eval/before-reader-context before-reader-context,
     :seon.sci.eval/before-interns before-interns,
     :seon.sci.eval/execution-ctx execution-ctx,
     :seon.sci.eval/namespace-name 'user,
     :seon.sci.eval/before-namespace-state before-namespace-state,
     :seon.sci.eval/base-declared-row nil})
   row
   (:seon.program/row result)]
  (is (true? (:seon.sci.eval/namespace-changed? result)))
  (is
   (=
    #{[:seon.fn/sym 'user/discarded]
      [:seon.test/sym 'user/discarded]}
    (set (:seon.program/delete-identities row))))
  (is (= [:seon.ns/name 'user] (:seon.program/ns row)))
  (is
   (=
    #{['user 'discarded]}
    (into
     #{}
     (keep
      (fn
       [[namespace-name binding-name value]]
       (when
        (identical? value @#'eval/absent-intern)
        [namespace-name binding-name])))
     (:seon.sci.eval/namespace-state row))))
  (is
   (some? (sci/resolve ctx 'discarded))
   "the forked ns-unmap leaves the parent context unchanged")
  (is (nil? (sci/resolve execution-ctx 'discarded)))
  (is (nil? (:seon.sci.eval/context-row result)))))

(deftest declared-row-evaluates-a-schema-once-inside-its-delta
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        source "(seon.schema/register! :user/direct-schema [:int {:min 0}])"
        event (#'eval/one-event source 'user ctx (count source))
        projection
        (#'eval/evaluation-projection {:seon.sci.eval/ctx ctx})
        calls (atom 0)
        before (seon.schema/registered-schemas)
        result
        (#'eval/declared-row
         {:seon.sci.eval/event event
          :seon.sci.eval/eval-form!
          (fn []
            (swap! calls inc)
            (seon.schema/register! :user/direct-schema [:int {:min 0}]))
          :seon.schema/projection projection})]
    (is (= 1 @calls))
    (let [row (:seon.sci.eval/base-declared-row result)]
      (is (= {:seon.schema/key :user/direct-schema
              :seon.schema/ns [:seon.ns/name 'user]
              :seon.schema/form "[:int {:min 0}]"
              :seon.schema.admission/source :agent
              :seon.schema/generatable? true}
             (dissoc row :seon.schema/shape)))
      (is (= {:seon.schema.shape/type :int
              :seon.schema.shape/form "[:int {:min 0}]"
              :seon.schema.shape/properties "{:min 0}"
              :seon.schema.shape/comparison :exact}
             (select-keys (:seon.schema/shape row)
                          [:seon.schema.shape/type :seon.schema.shape/form
                           :seon.schema.shape/properties
                           :seon.schema.shape/comparison]))))
    (is (= :user/direct-schema (:seon.sci.eval/schema-value result)))
    (is (false? (:seon.sci.eval/live-declaration? result)))
    (is (= before (seon.schema/registered-schemas))
        "the evaluated registration remains isolated from global candidates")))

(deftest the-dispositions-are-callable-and-come-back-as-values
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            completed (run-in ctx "(my.turn/complete {:my.turn/result \"done\"})" 2000)
            waiting (run-in ctx "(my.turn/wait {:my.turn/note \"later\"})" 2000)]
        (is (ok? completed) (pr-str completed))
        (is (= {:my.turn/disposition :completed :my.turn/result "done"}
               (:seon.sci.admit/value completed)))
        (is (ok? waiting) (pr-str waiting))
        (is (= {:my.turn/disposition :wait :my.turn/note "later"}
               (:seon.sci.admit/value waiting)))))))

(deftest an-unbound-var-remains-structured-after-production-admission
  (let [bare (run "(do (declare zz) zz)")
        nested (run "(do (declare zy) {:unbound zy})")
        admitted (:seon.sci.admit/value nested)]
    (is (some? (:seon.sci.admit/value bare)))
    (is (string? (:seon.eval/shown bare)))
    (is (some? (:unbound admitted)))
    (is (turn/unbound-value? admitted))
    (is (nil? (:seon.cluster.eval/error bare))
        "sci produced a value; E2-PRIME, not the evaluator, classifies it red")))

(deftest a-failed-evaluation-records-reconstructable-throwable-data
  (let [evaluation (run "(/ 1 0)")
        triage-data
        (edn/read-string (:seon.cluster.eval/triage-edn evaluation))]
    (is (= "Divide by zero" (:clojure.error/cause triage-data)))
    (is (= :execution (:clojure.error/phase triage-data)))
    (is (= 'java.lang.ArithmeticException
           (:clojure.error/class triage-data)))
    (is (= "Divide by zero" (:seon.cluster.eval/error evaluation)))))

(deftest an-instrumented-multi-arity-miss-reads-like-clojure
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            arglists (:arglists (meta #'db/as-of))
            evaluation (run-in ctx "(seon.db/as-of)" 2000)
            failure (:seon.sci.admit/value evaluation)]
        (is (> (count arglists) 1))
        (is (contains? (instrument/instrumented) #'db/as-of))
        ;; NAME THE CAUSE, NOT THE SYMPTOM. `sci/copy-var*` derefs the Var
        ;; ONCE (reference-code/sci/src/sci/core.cljc:137), so a context
        ;; acquired before `seon.instrument/apply!` re-roots the Var keeps the
        ;; UNINSTRUMENTED original for the JVM's life and this evaluation
        ;; throws Clojure's own "Wrong number of args (0) passed to:
        ;; seon.db/as-of" with no contract evidence at all — measured in the
        ;; cold gate, batches 119/120/123, while every fast run binds the
        ;; armed root. Without this assertion the four downstream failures
        ;; read as a message drift.
        (is (identical? @#'db/as-of (sci/eval-string* ctx "seon.db/as-of"))
            "the fork calls the armed root, not a pre-arming copy")
        (is (schema/valid-candidate-value? :seon.instrument/arity-error failure))
        (is (= 0 (get-in failure [:seon.error/data :seon.instrument/arity])))
        (is (= arglists (get-in failure [:seon.error/data :seon.instrument/arglists])))
        (is (= (:seon.error/message failure) (:seon.cluster.eval/error evaluation)))
        (is (str/includes? (:seon.eval/shown evaluation) (pr-str arglists)))
        (is (not (str/includes? (:seon.eval/shown evaluation) "projection failed")))
        ;; ONE composer, so the count appears ONCE in each sentence: the
        ;; description is prose and `seon.error/problem-sentence` prints the
        ;; offending value after it. Carrying the count in both rendered
        ;; "got an argument count of 0 0" (3e41a5d22, measured 2026-09-17).
        (is (str/includes? (:seon.eval/shown evaluation)
                           "got an argument count of 0."))
        (is (not (str/includes? (:seon.eval/shown evaluation)
                                "an argument count of 0 0")))
        (is (str/includes? (:seon.error/message failure)
                           "got an argument count of 0."))))))

(deftest bare-dir-and-program-derived-doc-are-repl-native
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            ctx (test-support/fork-cluster-ctx connection)
            directory (run-in ctx "(dir my.message)" 2000)
            read-doc (run-in ctx "(doc my.fs/read)" 2000)
            multi-doc (run-in ctx "(doc seon.db/as-of)" 2000)
            functions (:functions (:seon.sci.admit/value directory))
            read-value (:seon.sci.admit/value read-doc)
            multi-value (:seon.sci.admit/value multi-doc)
            declared (db/q '[:find [?symbol ...] :where
                             [?n :seon.ns/name my.message]
                             [?f :seon.fn/ns ?n]
                             [?f :seon.fn/sym ?symbol]
                             [?f :seon.fn/private? false]] database)]
        (is (seq declared))
        (is (= (set (map symbol declared)) (set (map :sym functions))))
        (is (every? #(and (:arglists %) (:in %) (:out %)) functions))
        (is (seq (get-in directory [:seon.sci.admit/value :schemas])))
        (is (= [:cat :my.fs/read-request] (:in read-value)))
        (is (= [:or :my.fs/read-result :seon.error/value] (:out read-value)))
        (is (seq (:summary read-value)))
        (is (seq (:example read-value)))
        (is (= (:arglists (meta #'db/as-of)) (:arglists multi-value)))
        (is (= (count (:arglists multi-value))
               (count (:in multi-value)) (count (:out multi-value))))
        (doseq [result [directory read-doc multi-doc]]
          (is (ok? result) (pr-str result))
          (is (string? (:seon.eval/shown result)))
          (is (nil? (:seon.cluster.eval/output result))
              "doc and dir return data; the value renderer owns shown text"))))))

(deftest a-turn-fork-registers-an-empty-assigned-agent-namespace
  (test-support/with-database
    (fn [connection]
      (let [agent-id "empty-namespace-agent"
            namespace-name 'fixture.empty-agent
            _ (test-support/transacted!
                           connection
                           [{:seon.agent/id agent-id
                             :seon.agent/namespace
                             {:seon.ns/name namespace-name}}])
            base (test-support/fork-cluster-ctx connection)
            _ (eval/acquire! {:seon.sci.eval/ctx base
                              :seon.db/db (db/db connection)})
            fork-result
            (eval/fork-for-turn
             {:seon.sci.eval/ctx base
              :seon.db/db (db/db connection)
              :seon.db/connection connection
              :seon.agent/id agent-id})
            turn-ctx (:seon.sci.eval/ctx fork-result)
            evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx turn-ctx
              :seon.agent/id agent-id
              :seon.cluster.eval/ns [:seon.ns/name namespace-name]
              :seon.cluster.eval/source "(dir fixture.empty-agent)"
              :seon.sci.admit/caps caps
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic})]
        (is (nil? (sci/find-ns base namespace-name))
            "the acquired base remains program-only")
        (is (some? (sci/find-ns turn-ctx namespace-name))
            "the turn fork contains its assigned namespace without defs")
        (is (contains? evaluation :seon.sci.admit/value))
        (is (= {:functions [] :schemas {}} (:seon.sci.admit/value evaluation))
            "An empty namespace has no function or schema declarations")
        (is (not (contains? evaluation :seon.cluster.eval/output))
            "an empty directory prints no output")
        (is (nil? (:seon.cluster.eval/error evaluation)))))))

;;; ---------------------------------------------------------------------------
;;; The armed boundary — time is the only limit
;;; ---------------------------------------------------------------------------

(deftest an-interpreted-infinite-loop-dies-at-the-limit
  (let [evaluation (deadlined "(loop [] (recur))" 300)]
    (is (not= ::hung evaluation) "the limit is the limit")
    (is (cut? evaluation))
    (is (inst? (:seon.cluster.eval/interrupted-at evaluation))
        "the cut instant is the one fact — presence is the state")
    (is (= :time (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (testing "and the agent is told what happened, as a value"
      (is (schema/valid-candidate-value? :seon.sci.kernel/error
                                        (:seon.sci.admit/value evaluation)))
      (is (re-find #"(?i)time"
                   (:seon.cluster.eval/error evaluation))))))

(deftest an-agent-cannot-catch-the-interrupt
  ;; sci's try refuses to hand the interrupt to a user catch clause, and
  ;; sandboxed code cannot forge the marker
  (let [evaluation (deadlined
                    "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
                    300)]
    (is (not= ::hung evaluation))
    (is (cut? evaluation))
    (is (not= :swallowed (:seon.sci.admit/value evaluation)))))

(deftest a-previously-defined-function-uses-the-current-evaluation-limit
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        definition
        (run-in ctx
                "(defn spin [] (loop [i 0] (recur (inc i))))"
                1000)
        evaluation (deadlined-in ctx "(spin)" 300)]
    (is (ok? definition))
    (is (not= ::hung evaluation))
    (is (cut? evaluation))
    (is (= :time
           (:seon.eval/outcome (:seon.sci.admit/record evaluation))))
    (is (schema/valid-candidate-value? :seon.sci.kernel/error
                                      (:seon.sci.admit/value evaluation))
        "the wrapped sci interrupt remains a flat time-limit value")))

(deftest a-base-created-function-uses-the-invoking-threads-arm
  ;; The interpreted corpus will be installed into `base`, so its functions
  ;; capture the base's interrupt-fn when SCI creates them. Create this one on
  ;; the test thread, then invoke it through a fork on another thread: arming
  ;; must follow the invoking thread, not the thread that created the function.
  (let [base (eval/build-base-ctx (seon.schema/handed-projection))
        definition
        (sci/eval-string*
         base
         (str "(defn substrate-base-spin [] "
              "(loop [i 0] (recur (inc i))))"))
        ctx (sci/fork base)
        evaluation (deadlined-in ctx "(substrate-base-spin)" 300)]
    (is (ifn? definition))
    (is (identical? (:interrupt-fn base) (:interrupt-fn ctx))
        "the base and every fork share the one process guard")
    (is (not= ::hung evaluation))
    (is (cut? evaluation)
        "the caller thread's arm cuts a function created on another thread")
    (is (= :time
           (:seon.eval/outcome (:seon.sci.admit/record evaluation))))))

(deftest
 an-acquired-function-uses-the-current-evaluation-limit
 (test-support/with-database
  (fn
   [connection]
   (test-support/seed-cluster! connection "interrupt-acquire")
   (let
    [source
     (str
      "(defn ^{:malli/schema [:=> [:cat] :int]} spin [] "
      "(loop [i 0] (recur (inc i))))")
     _
     (test-support/transacted!
                  connection
                  [#:seon.agent{:id "interrupt-author",
                                :namespace
                                #:seon.ns{:name 'authored.interrupt,
                                          :source "(ns authored.interrupt)"}}
                   (test-support/program-fn-row (db/db connection)
                                                 'authored.interrupt/spin source)])
     ctx
     (eval/build-base-ctx (seon.schema/handed-projection))
     acquired
     (eval/acquire!
      {:seon.sci.eval/ctx ctx, :seon.db/db (db/db connection)})
     evaluation
     (deadlined-in ctx "(authored.interrupt/spin)" 300)]
    (is (pos? (:seon.sci.eval/installed acquired)))
    (is (ifn? @(sci/resolve ctx 'authored.interrupt/spin)))
    (is (not= :seon.sci.eval-test/hung evaluation))
    (is (cut? evaluation))
    (is
     (=
      :time
      (:seon.eval/outcome (:seon.sci.admit/record evaluation))))))))


(deftest
 one-unloadable-row-cannot-prevent-cold-acquisition
 (test-support/with-database
  (fn
   [connection]
   (test-support/seed-cluster! connection "poison-acquire")
   (let
    [namespace-name
     'acquire.poison
     agent-id
     "acquire-poison-author"
     good-source
     (str
      "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
      "good [x] (inc x))")]
    (test-support/transacted!
                 connection
                 [#:seon.agent{:id agent-id,
                               :namespace
                               #:seon.ns{:name namespace-name,
                                         :source "(ns acquire.poison)"}}
                  (test-support/program-fn-row
                   (db/db connection) 'acquire.poison/bad
                   "(defn ^{:malli/schema [:=> [:cat :int] :int]} bad [x] (missing-dependency x))")
                  (test-support/program-fn-row (db/db connection)
                                                'acquire.poison/good good-source)])
    (let
     [ctx
      (assoc
       (eval/build-base-ctx (seon.schema/handed-projection))
       :seon.sci.eval/custody
       #:seon.db{:connection connection})
      acquired
      (eval/acquire!
       {:seon.sci.eval/ctx ctx, :seon.db/db (db/db connection)})
      refusal
      (error/latest-fact
       (first
       (db/q
        '[:find
          [(pull ?error [* {:seon.error/occurrences [*]}]) ...]
          :where
          [?error :seon.error/kind :seon.sci.eval/acquisition-refused]]
        (db/db connection))))]
     (is
      (= 42 (sci/eval-string* ctx "(acquire.poison/good 41)"))
      "a later valid row installs and works")
     (is (= 1 (count (:seon.sci.eval/acquisition-refusals acquired))))
     (is
      (true? (:seon.sci.eval/acquisition-refusals-recorded? acquired)))
     (is
      (some? refusal)
      "the contained agent mistake is a durable fact")
     (is
      (str/includes?
       (:seon.error/message refusal)
       "[:seon.fn/sym acquire.poison/bad]"))
     (is
      (str/includes?
       (:seon.error/data-edn refusal)
       "missing-dependency")
      "the fact retains the row's typed cause as queryable evidence"))))))


(deftest
 agent-contracts-apply-on-acquire-and-cold-recovery
 (test-support/with-database
  (fn
   [connection]
   (test-support/seed-cluster! connection "contract-acquire")
   (let
    [source
     (str
      "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
      "accept [x] x)")]
    (test-support/transacted!
                 connection
                 [#:seon.agent{:id "contract-author",
                               :namespace
                               #:seon.ns{:name 'authored.contract,
                                         :source "(ns authored.contract)"}}
                  (test-support/program-fn-row (db/db connection)
                                                'authored.contract/accept source)])
    (let
     [assert-violation
      (fn
       [ctx moment]
       (let
        [evaluation
         (run-in ctx "(authored.contract/accept \"wrong\")" 2000)
         failure
         (:seon.sci.admit/value evaluation)]
        (is
         (schema/valid-candidate-value? :seon.instrument/contract-error failure)
         moment)
        (is
         (=
          'authored.contract/accept
          (get-in
           failure
           [:seon.error/data :seon.error/diagnostic-operation]))
         moment)))
      acquired-ctx
      (eval/build-base-ctx (seon.schema/handed-projection))]
     (eval/acquire!
      {:seon.sci.eval/ctx acquired-ctx,
       :seon.db/db (db/db connection)})
     (assert-violation acquired-ctx "boot acquire!")
     (assert-violation
      (eval/cluster-ctx (db/db connection))
      "cold crash recovery"))))))

(deftest acquisition-uses-the-effective-config-projection-when-instrumented
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "instrumented-acquire")
      (let [database (db/db connection)
            projection (db/carried-projection database)
            entering (instrument/instrumented)]
        (test-support/preserving-instrumentation-state
          (fn []
            (schema/call-with-projection
              projection
              #(instrument/apply!
                {:seon.config/on-core-error :panic
                 :seon.schema/projection projection}))
            (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
                  acquired (eval/acquire! {:seon.sci.eval/ctx ctx
                                           :seon.db/db database
                                           :seon.schema/projection projection})]
              (is (map? acquired))
              (is (some? (sci/resolve ctx 'seon.db/pull)))
              (is (contains? (instrument/instrumented) #'db/pull)))))
        (is (= entering (instrument/instrumented))
            "the canonical fixture restores the entering wrapper set")))))

(deftest acquisition-binds-loaded-first-party-compiled-vars
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "assigned-namespace-acquisition"
            agent-id "probe"
            assigned-namespace 'my.tools.demo
            _ (test-support/seed-cluster! connection cluster-name)
            _ (test-support/transacted!
                           connection
                           (agent/creation-tx
                            {:seon.agent/id agent-id
                             :seon.ns/name assigned-namespace
                             :seon.cluster/name cluster-name}))
            ctx (eval/cluster-ctx (db/db connection) connection)
            evaluation
            (run-in ctx
                    "(seon.sci.eval/agent-namespace (seon.db/db) \"probe\")"
                    2000)
            assigned-evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.agent/id agent-id
              :seon.cluster.eval/source "(ns-name *ns*)"
              :seon.sci.admit/caps caps
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic})
            external (run-in ctx "(datahike.api/q '[:find ?e :where [?e]])"
                             2000)]
        (let [installed
              (get-in (sci/namespace-state ctx)
                      ['seon.sci.eval 'agent-namespace])]
          (is (identical? @#'eval/agent-namespace @installed)
              "the installed SCI Var copies the current compiled root"))
        (is (ok? evaluation))
        (is (= assigned-namespace (:seon.sci.admit/value evaluation)))
        (is (= assigned-namespace
               (:seon.sci.admit/value assigned-evaluation)))
        (is (= [:seon.ns/name assigned-namespace]
               (:seon.cluster.eval/ns assigned-evaluation))
            "an evaluation without an explicit form namespace opens at the assignment")
        (is (failed? external)
            "loaded dependencies are not first-party merely because loaded")))))

(deftest call-preparation-receives-the-form-scoped-environment
  (test-support/with-database
    (fn [connection]
      (let [seen (atom [])
            ctx (eval/cluster-ctx (db/db connection) connection)
            evaluation
            (with-redefs [call-preparation/hook
                          (fn [runtime-ctx _callee arguments]
                            (swap! seen conj (env/of runtime-ctx))
                            arguments)]
              (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.agent/id "scoped-agent"
              :seon.turn/id "scoped-run"
              :seon.cluster.eval/ordinal 7
              :seon.cluster.eval/source "(seon.run/complete \"done\")"
              :seon.sci.admit/caps caps
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic}))]
        (is (= {:my.turn/disposition :completed
                :my.turn/result "done"}
               (:seon.sci.admit/value evaluation)))
        (is (some #(= {:seon.agent/id "scoped-agent"
                       :seon.turn/id "scoped-run"
                       :seon.cluster.eval/ordinal 7}
                      (select-keys %
                                   [:seon.agent/id
                                    :seon.turn/id
                                    :seon.cluster.eval/ordinal]))
                  @seen)
            "SCI's actual call-preparation hook sees this form's turn members")))))

(deftest
  evaluation-custody-is-derived-only-from-the-cluster-context
  (test-support/with-database
    (fn [connection-a]
      (test-support/with-database
        (fn [connection-b]
          (test-support/seed-cluster! connection-a "ambient-a")
          (test-support/seed-cluster! connection-b "ambient-b")
          (let [uncustodied-ctx (eval/build-base-ctx (seon.schema/handed-projection))
                _ (eval/acquire!
                    {:seon.sci.eval/ctx uncustodied-ctx, :seon.db/db (db/db connection-a)})
                ctx-a (eval/cluster-ctx (db/db connection-a) connection-a)
                ctx-b (eval/cluster-ctx (db/db connection-b) connection-b)
                evaluate (fn [ctx source]
                           (eval/evaluate
                             {:seon.cluster.eval/source source,
                              :seon.cluster.eval/ns [:seon.ns/name 'user],
                              :seon.sci.eval/ctx ctx,
                              :seon.sci.admit/caps caps,
                              :seon.sci.eval/time-limit-ms 5000,
                              :seon.config/on-core-error :panic}))
                cluster-names-source (str
                                       "(seon.db/q "
                                       "'[:find [?name ...] "
                                       ":where [_ :seon.cluster/name ?name]])")
                unbound (binding [db/*conn* connection-b]
                          (evaluate uncustodied-ctx cluster-names-source))
                read-a (binding [db/*conn* connection-b]
                         (evaluate ctx-a cluster-names-source))
                read-b (evaluate ctx-b cluster-names-source)
                read-a-again (evaluate ctx-a cluster-names-source)
                write (evaluate
                        ctx-a
                        (str
                          "(seon.db/transact! "
                          "[{:seon.message/id \"ambient-message\" :seon.message/to [:seon.cluster/name \"ambient-a\"] :seon.message/content \"custody probe\"}])"))
                read-written (evaluate
                               ctx-a
                               (str
                                 "(seon.db/q "
                                 "'[:find ?id . "
                                 ":where [_ :seon.message/id ?id]])"))
                rejected (evaluate
                           ctx-a
                           (str
                             "(seon.db/transact! "
                             "[{:seon.sci.eval-test/undeclared true}])"))
                unbound-after (binding [db/*conn* connection-b]
                                (evaluate uncustodied-ctx cluster-names-source))]
            (is
              (=
                :seon.db/missing-connection-binding
                (get-in unbound [:seon.sci.admit/value :seon.error/kind])))
            (is
              (= ["ambient-a"] (:seon.sci.admit/value read-a))
              "the ctx overrides a foreign binding already on the thread")
            (is
              (= ["ambient-b"] (:seon.sci.admit/value read-b))
              "each sibling derives custody from its own ctx")
            (is (= ["ambient-a"] (:seon.sci.admit/value read-a-again)))
            (is (nil? (:seon.cluster.eval/error write)))
            (is
              (= "ambient-message" (:seon.sci.admit/value read-written))
              "a declared write is visible to the next evaluation")
            (is
              (=
                :seon.db/invalid-write
                (get-in rejected [:seon.sci.admit/value :seon.error/kind])))
            (is
              (=
                :seon.db/attribute-not-installed
                (get-in
                  rejected
                  [:seon.sci.admit/value :seon.error/data :seon.error/diagnostic-cause])))
            (is
              (=
                :seon.db/missing-connection-binding
                (get-in unbound-after [:seon.sci.admit/value :seon.error/kind]))
              "an uncustodied ctx never inherits the caller's binding")))))))

(deftest
  public-walk-is-callable-through-an-agent-sci-eval
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "host-walk")
      (test-support/transacted!
                    connection
                    (agent/creation-tx
                      {:seon.agent/id "host-walker",
                       :seon.cluster/name "host-walk",
                       :seon.ns/name 'my.agents.host-walker}))
      (let [ctx (eval/cluster-ctx (db/db connection) connection)
            request {:seon.render.walk/lookup [:seon.agent/id "host-walker"],
                     :seon.sci.eval/time-limit-ms 5000,
                     :seon.agent/id "host-walker",
                     :seon.render/distance 2,
                     :seon.db/db (db/db connection),
                     :seon.sci.eval/ctx ctx,
                     :seon.config/on-core-error :panic,
                     :seon.sci.admit/caps caps,
                     :seon.db/connection connection,
                     :seon.render/output :seon.render/ai}
            root-selector render.walk/root-selector
            root-selectors (atom [])
            compile-plan pull-api/compile-pull-plan
            compilation-count (atom 0)]
        (with-redefs
          [render.walk/root-selector
           (fn [database distance supplied-caps]
             (let [selector (root-selector database distance supplied-caps)]
               (swap! root-selectors conj selector)
               selector))
           pull-api/compile-pull-plan
           (fn ([selector-or-plan]
                 (when (some #(identical? selector-or-plan %) @root-selectors)
                   (swap! compilation-count inc))
                 (compile-plan selector-or-plan))
             ([database selector-or-plan] (compile-plan database selector-or-plan)))]
          (let [evaluate-walk #(render/call-with-walk-context
                                request
                                (fn [] (run-in ctx "(seon.render/walk)" 5000)))
                through-sci (evaluate-walk)
                direct (render.walk/root-acquisition request)
                web (#'render.web/acquire-root request :seon.sci.eval-test/root)
                value (:seon.sci.admit/value through-sci)
                allocations (mapv
                              #(get-in % [:seon.sci.admit/record :seon.eval/allocated-bytes])
                              [through-sci])
                bounded-plan (render.walk/root-pull-plan
                               (assoc
                                 request
                                 :seon.render/distance
                                 0
                                 :seon.sci.admit/caps
                                 (assoc caps :seon.config.eval.result/max-nodes 1)))]
            (is (ok? through-sci))
            (is (string? value))
            (is
              (re-find #":seon\.agent/id \"host-walker\"" value)
              "the printed walk value names its root agent identity")
            (is
              (not (contains? through-sci :seon.eval/missing))
              "the ordinary walk is stored whole, never missing")
            (is (map? direct))
            (is (= 2 (count web)))
            (is
              (and (seq @root-selectors) (<= @compilation-count 1))
              "direct, web, and through-SCI reuse a plan, including an already warm plan")
            (is
              (every? #(and (int? %) (< % (* 1024 1024 1024))) allocations)
              (str "through-SCI allocations must stay below 1 GiB: " (pr-str allocations)))
            (is (identical? (:datahike.pull/plan direct) (:datahike.pull/plan bounded-plan)))
            (is (= 0 (:seon.render/distance bounded-plan)))
            (is
              (=
                1
                (get-in
                  bounded-plan
                  [:seon.sci.admit/caps :seon.config.eval.result/max-nodes])))))))))

(deftest one-context-arms-concurrent-threads-independently
  ;; The class is arm identity, not interpreter throughput. Both threads arm
  ;; the SAME ctx before either proceeds. One waits for its 30ms latch; only
  ;; after that interrupt is observed does its still-armed sibling call the
  ;; shared interrupt function. A process-wide arm or context-wide arm makes
  ;; the sibling observe the cut. A ThreadLocal arm cannot.
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        ready (java.util.concurrent.CountDownLatch. 2)
        begin (java.util.concurrent.CountDownLatch. 1)
        cut-observed (java.util.concurrent.CountDownLatch. 1)
        interrupt-fn (:interrupt-fn ctx)
        cut-task
        (future
          (let [{stop! :seon.sci.kernel/stop!} (kernel/arm ctx 30)]
            (.countDown ready)
            (.await begin)
            (try
              (let [backstop
                    (+ (System/nanoTime)
                       (* test-support/event-backstop-seconds 1000000000))]
                (loop []
                  (if (> (System/nanoTime) backstop)
                    ::cut-not-observed
                    (let [interrupted-now?
                          (try
                            (interrupt-fn)
                            false
                            (catch Throwable failure
                              (if (kernel/interrupted? failure)
                                true
                                (throw failure))))]
                      (if interrupted-now?
                        (do (.countDown cut-observed) ::cut)
                        (do (Thread/onSpinWait) (recur)))))))
              (finally (stop!)))))
        sibling-task
        (future
          (let [{stop! :seon.sci.kernel/stop!} (kernel/arm ctx 30000)]
            (.countDown ready)
            (.await begin)
            (try
              (test-support/await-event! cut-observed ::sibling-observed-cut)
              (try
                (interrupt-fn)
                ::sibling-live
                (catch Throwable failure
                  (if (kernel/interrupted? failure)
                    ::sibling-cut
                    (throw failure))))
              (finally (stop!)))))]
    (is (test-support/await-event! ready ::both-arms-ready))
    (.countDown begin)
    (let [cut-result (test-support/await-event! cut-task ::cut-task-settled)
          sibling-result
          (test-support/await-event! sibling-task ::sibling-task-settled)]
      (is (= ::cut cut-result))
      (is (= ::sibling-live sibling-result)
          "arming and interrupting one thread never cuts its sibling"))))

(deftest disarm-clears-the-current-threads-flag-exactly
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        {stop! :seon.sci.kernel/stop!} (kernel/arm ctx 30)
        interrupt-fn (:interrupt-fn ctx)
        backstop (+ (System/nanoTime) 1000000000)
        reached
        (loop []
          (if (> (System/nanoTime) backstop)
            ::hung
            (let [interrupted
                  (try
                    (interrupt-fn)
                    false
                    (catch Throwable failure
                      (if (kernel/interrupted? failure)
                        true
                        (throw failure))))]
              (if interrupted
                true
                (do
                  (Thread/onSpinWait)
                  (recur))))))]
    (is (= true reached)
        "the scheduled task published the observable interrupt event")
    (stop!)
    (is (nil? (interrupt-fn))
        "the stable hook has no stale armed state after stop!")
    (let [later (run-in ctx "(+ 1 2)" 1000)]
      (is (ok? later))
      (is (= 3 (:seon.sci.admit/value later))))))

(def ^:private ordinary-source-value-generator
  (gen/one-of
   [gen/small-integer
    gen/boolean
    gen/string-alphanumeric
    gen/keyword
    (gen/return nil)
    (gen/vector gen/small-integer 0 8)]))

(def ^:private failing-source-generator
  (gen/elements
   ["(throw (ex-info \"x\" {:probe true}))"
    "(/ 1 0)"
    "(no-such-fn 1)"
    "(recur)"
    "#{"
    "(let [x])"
    "#foo/bar [1]"
    "#=(System/exit 1)"
    "(java.io.File. \"/etc/passwd\")"]))

(deftest generated-sources-compose-fork-guard-and-admission
  (let [time-limit-ms (:seon.config.eval/time-limit-ms (config/defaults))
        check
        (tc/quick-check
         100
         (prop/for-all
          [ordinary ordinary-source-value-generator
           failing-source failing-source-generator]
          (let [ordinary-evaluation (deadlined (pr-str ordinary) time-limit-ms)
                failed-evaluation (deadlined failing-source time-limit-ms)
                evaluations [ordinary-evaluation failed-evaluation]]
            (and
             (ok? ordinary-evaluation)
             (= ordinary (:seon.sci.admit/value ordinary-evaluation))
             (failed? failed-evaluation)
             (every?
              (fn [evaluation]
                (and
                 (not= ::hung evaluation)
                 (map? evaluation)
                 ;; Presence is the state: exactly one of these facts
                 ;; describes every completed guarded composition.
                 (= 1 (count (filter true?
                                     [(ok? evaluation)
                                      (failed? evaluation)
                                      (cut? evaluation)])))
                 (string? (:seon.eval/shown evaluation))
                 (seon.schema/valid-candidate-value?
                  :seon.sci.eval/evaluation evaluation)))
              evaluations))))
         :seed 202607280802)]
    (test-support/assert-check! check
                                "Guarded evaluation composition failed.")))

;;; ---------------------------------------------------------------------------
;;; The honest ceiling — stated, not papered over
;;; ---------------------------------------------------------------------------

(deftest a-blocking-host-call-is-NOT-stopped-by-the-time-limit
  ;; Found by the totality property above, and it is not a defect: the
  ;; interrupt-fn fires on interpreted fn body entrances, and a thread
  ;; parked inside a HOST call never enters one. sci says so itself
  ;; (reference-code/sci/doc/interrupt.md, closing note: for hard
  ;; guarantees run untrusted code in a separate process).
  ;;
  ;; This test exists so the ceiling is a KNOWN, RECURRING fact rather
  ;; than a docstring claim: what covers this case is the caller's
  ;; submission backstop (whose firing IS a bug report, n3-plan §4.4)
  ;; and the process boundary — never this deadline.
  (let [task (future (run "(deref (promise))" 200))
        outcome (deref task 1500 ::still-running)]
    (is (= ::still-running outcome)
        "the time limit did NOT stop it — if this ever passes by
         returning, the mechanism changed and the ceiling moved")
    (future-cancel task)))

;;; ---------------------------------------------------------------------------
;;; The single owner of the interrupt question
;;; ---------------------------------------------------------------------------

(deftest interrupted?-recognises-only-the-real-marker
  (is (false? (kernel/interrupted? (ex-info "ordinary" {}))))
  (is (false? (kernel/interrupted? (RuntimeException. "ordinary"))))
  (is (false?
       (kernel/interrupted? (ex-info "forged" {:sci.impl/interrupt false})))
      "sci's private marker identity, not key presence, owns the answer")
  (let [interrupt
        (try ((requiring-resolve 'sci.interrupt/interrupt!) "x")
             (catch Throwable failure failure))]
    (is (true? (kernel/interrupted? interrupt)))
    (is (true? (kernel/interrupted?
                (ex-info "location wrapper" {:sci/error true} interrupt))))
    (is (false? (kernel/interrupted?
                 (ex-info "ordinary wrapper" {}
                          (RuntimeException. "ordinary")))))))

;;; ---------------------------------------------------------------------------
;;; One guarded owner, two entrances
;;;
;;; `evaluate` (a form) and `kernel/invoke` (a named live Var, which is how
;;; every renderer runs) must not carry two copies of the guard's semantics.
;;; Each test below fixes one semantic and asserts it at BOTH entrances, so a
;;; future divergence fails here rather than in production.
;;; ---------------------------------------------------------------------------

(defn- invoked-value
  "Invoke one already-live symbol through the guarded kernel entrance."
  ([ctx database function-symbol] (invoked-value ctx database function-symbol [] 2000))
  ([ctx database function-symbol arguments time-limit-ms]
   ;; the database installer is never reached: the definition is already
   ;; live in this context, which is exactly the renderer's cache-hit path
   (kernel/mark-installed! ctx function-symbol)
   (:seon.sci.admit/value
    (kernel/invoke {:seon.sci.eval/ctx ctx
                    :seon.db/db database
                    :seon.fn/sym function-symbol
                    :seon.sci.eval/args arguments
                    :seon.sci.eval/time-limit-ms time-limit-ms
                    :seon.sci.admit/caps caps
                    :seon.config/on-core-error :record}))))

(deftest a-re-entrant-evaluation-inherits-the-governing-arm
  ;; Before the merge this threw :seon.sci.kernel/already-armed straight out
  ;; of `evaluate`, contradicting this namespace's own "nothing throws"
  ;; contract, while `invoke` on the identical situation returned a value.
  (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
        {stop! :seon.sci.kernel/stop!} (kernel/arm ctx 30000)]
    (try
      (let [evaluation (run-in ctx "(+ 1 2)" 1000)]
        (is (ok? evaluation) "the inherited arm evaluates, it does not throw")
        (is (= 3 (:seon.sci.admit/value evaluation))))
      (finally (stop!)))
    (is (ok? (run-in ctx "(+ 2 2)" 1000))
        "the inherited arm left the outer owner's disarm intact")))

(deftest an-inherited-arm-keeps-the-governing-deadline
  ;; The reason inheritance is the rule and not a convenience: nested work
  ;; must never restart the clock and outlive the limit that admitted it.
  ;; Arm and evaluate on ONE thread, because inheritance is per-thread by
  ;; construction. The future is only the suite's backstop: if the deadline
  ;; ever stops governing nested work, this FAILS rather than hangs.
  (let [task (future
               (let [ctx (eval/build-base-ctx (seon.schema/handed-projection))
                     {stop! :seon.sci.kernel/stop!} (kernel/arm ctx 50)]
                 (try
                   (run-in ctx "(loop [i 0] (recur (inc i)))" 600000)
                   (finally (stop!)))))
        evaluation (deref task 15000 ::hung)]
    (future-cancel task)
    (is (not= ::hung evaluation))
    (is (cut? evaluation)
        "the outer 50ms arm stopped work that asked for ten minutes")))

(deftest
  a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            ctx (eval/cluster-ctx database connection)
            wrapped-ctx (env/carry-state ctx (env/environment-state (env/of ctx)))
            _ (is (not (identical? ctx wrapped-ctx)))
            _ (is (identical? (:env ctx) (:env wrapped-ctx)))
            _ (is
                (ok?
                  (run-in
                    ctx
                    (str
                      "(defn probe-render [unit]"
                      " (str \"rendered:\""
                      "      (:seon.render/value unit)))")
                    2000)))
            _ (is
                (ok?
                  (run-in
                    ctx
                    (str "(defn probe-render-spin [unit]" " (loop [i 0] (recur (inc i))))")
                    2000)))
            _ (kernel/mark-installed! ctx 'user/probe-render)
            _ (kernel/mark-installed! ctx 'user/probe-render-spin)
            request {:seon.db/db database,
                     :seon.sci.eval/ctx ctx,
                     :seon.render/value "value",
                     :seon.render/ai 'user/probe-render,
                     :seon.sci.admit/caps caps,
                     :seon.sci.eval/time-limit-ms 5000,
                     :seon.config/on-core-error :record}
            unarmed (render/render-ai request)
            armed (let [{stop! :seon.sci.kernel/stop!} (kernel/arm wrapped-ctx 5000)]
                    (try (render/render-ai request) (finally (stop!))))
            deadline-task (future
                            (let [{stop! :seon.sci.kernel/stop!} (kernel/arm wrapped-ctx 50)]
                              (try
                                (render/render-ai
                                  (assoc
                                    request
                                    :seon.render/ai
                                    'user/probe-render-spin
                                    :seon.sci.eval/time-limit-ms
                                    600000))
                                (finally (stop!)))))
            deadline-result (deref deadline-task 15000 :seon.sci.eval-test/hung)]
        (future-cancel deadline-task)
        (is (= "rendered:value" unarmed) "an unarmed selected render owns an ordinary arm")
        (is
          (= "rendered:value" armed)
          "the same selected render inherits the live interpreter arm")
        (is (not= :seon.sci.eval-test/hung deadline-result))
        (is
          (and
            (= :seon.render/unknown (:seon.error/kind deadline-result))
            (= :time-limit (:seon.render.unknown/reason deadline-result))
            (= :seon.sci.kernel/time-limit (:seon.render.unknown/refusal deadline-result)))
          "nested render work keeps the outer 50ms time limit")))))

(deftest a-foreign-armed-context-is-refused-as-a-value
  (let [armed-ctx (eval/build-base-ctx (seon.schema/handed-projection))
        other-ctx (eval/build-base-ctx (seon.schema/handed-projection))
        {stop! :seon.sci.kernel/stop!} (kernel/arm armed-ctx 30000)]
    (try
      (let [evaluation (run-in other-ctx "(+ 1 2)" 1000)]
        (is (= :seon.sci.kernel/already-armed
               (:seon.error/kind (:seon.sci.admit/value evaluation)))
            "a refusal at an agent-facing operation is a value, never a throw")
        (is (some? (:seon.cluster.eval/error evaluation))
            "presence is the state, so a preserved refusal still carries the
             message the loop reads — it must never store a nil there"))
      ;; `sci/fork` is `(update ctx :env …)`, so it PRESERVES the guard key
      ;; and shares the parent's ThreadLocal
      ;; (reference-code/sci/src/sci/core.cljc:318-323). Two contexts sharing
      ;; one arm on one thread cannot both be honoured, so the fork is refused
      ;; loudly rather than silently borrowing the parent's deadline.
      (let [forked (run-in (sci/fork armed-ctx) "(+ 1 2)" 1000)]
        (is (= :seon.sci.kernel/already-armed
               (:seon.error/kind (:seon.sci.admit/value forked)))))
      (finally (stop!)))))

(deftest both-entrances-classify-one-failure-identically
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (eval/build-base-ctx (seon.schema/handed-projection))
           _ (is (ok? (run-in ctx (str "(defn probe-throw [x]"
                                       " (throw (ex-info \"boom\" {:a x})))")
                              2000)))
           _ (is (ok? (run-in ctx (str "(defn probe-spin [x]"
                                       " (loop [i x] (recur (inc i))))")
                              2000)))
           evaluated-throw (:seon.sci.admit/value
                            (run "(throw (ex-info \"boom\" {:a 1}))"))
           invoked-throw (invoked-value ctx database 'user/probe-throw [1] 2000)
           evaluated-cut (:seon.sci.admit/value
                          (deadlined-in nil "(loop [i 0] (recur (inc i)))" 50))
           invoked-cut (invoked-value ctx database 'user/probe-spin [0] 50)]
       (testing "an agent mistake"
         (is (schema/valid-candidate-value? :seon.sci.kernel/error evaluated-throw))
         (is (schema/valid-candidate-value? :seon.sci.kernel/error invoked-throw))
         (is (= 'user/probe-throw (get-in invoked-throw [:seon.error/data :seon.fn/sym])))
         (is (= "boom" (:seon.error/message evaluated-throw)))
         (is (= "Invocation of user/probe-throw failed: boom"
                (:seon.error/message invoked-throw)))
         (is (= "clojure.lang.ExceptionInfo"
                (:seon.sci.eval/throwable (:seon.error/data evaluated-throw))
                (:seon.sci.eval/throwable (:seon.error/data invoked-throw)))
             "one classifier, so the same evidence rides both faces")
         (is (every? #(contains? (:seon.error/data evaluated-throw) %)
                     [:seon.sci.eval/throwable :seon.sci.admit/record]))
         (is (every? #(contains? (:seon.error/data invoked-throw) %)
                     [:seon.sci.eval/throwable :seon.sci.admit/record
                      :seon.fn/sym])
             "the invocation entrance adds only its subject"))
       (testing "the one deadline"
         (doseq [failure [evaluated-cut invoked-cut]]
           (is (not-any? #(and (map? %) (contains? % :sci.impl/interrupt))
                         (tree-seq coll? seq failure))
               "SCI's private interrupt marker never becomes outward evidence"))
         (is (schema/valid-candidate-value? :seon.sci.kernel/error evaluated-cut))
         (is (schema/valid-candidate-value? :seon.sci.kernel/error invoked-cut))
         (is (str/starts-with? (:seon.error/message evaluated-cut)
                               "Ran out of time after"))
         (is (str/starts-with?
              (:seon.error/message invoked-cut)
              "Invocation of user/probe-spin failed: Ran out of time after")
             "one message shape, prefixed only by the subject")
         (is (= :time (:seon.eval/outcome
                       (:seon.sci.admit/record
                        (:seon.error/data evaluated-cut)))))
         (is (= :time (:seon.eval/outcome
                       (:seon.sci.admit/record
                        (:seon.error/data invoked-cut))))))))))

(deftest an-existing-refusal-is-not-wrapped-or-duplicated
  (test-support/with-database
   (fn [_]
    (let [failure
        (kernel/failure-value
         {::kernel/time-limit-kind :probe/time-limit
          ::kernel/failure-kind :probe/failure}
         (ex-info "result renderer exploded"
                  {:seon.error/at #inst "2026-09-20T00:00:00Z"
                   :seon.error/layer :seon.agent/lifecycle
                   :seon.error/operation 'seon.agent/by-id
                   :seon.agent/error-agent-id "inner-observed"
                   :seon.error/message "inner failure"})
         {:seon.eval/fn-entries 1
          :seon.eval/host-interop-count 0
          :seon.eval/duration-ms 1
          :seon.eval/allocated-bytes 0
          :seon.eval/outcome :error})]
    (is (= "inner failure" (:seon.error/message failure)))
    (is (schema/valid-candidate-value? :seon.agent/error failure))
    (is (= "inner-observed" (:seon.agent/error-agent-id failure)))
    (is (= :error
           (get-in failure [:seon.error/data
                            :seon.sci.admit/record
                            :seon.eval/outcome])))
    (is (not (contains? (:seon.error/data failure) :seon.sci.eval/data))
        "the refusal is not copied back into itself as throwable ex-data")
    (is (not= :nested-refusal
              (:seon.error/diagnostic-member (:seon.error/data failure))))))))

(deftest analysis-failure-exposes-scis-unresolved-symbol-as-data
  (let [failure (:seon.sci.admit/value
                 (run "unresolved-diagnostic-member"))]
    (is (= 'unresolved-diagnostic-member
           (:seon.sci.eval/symbol (:seon.error/data failure))))
    (is (= 'unresolved-diagnostic-member
           (:seon.error/diagnostic-offending (:seon.error/data failure))))))

(deftest a-refusal-keeps-its-own-kind-at-both-entrances
  ;; A refusal our own guarded machinery raised already says what went
  ;; wrong. One classifier means neither entrance can flatten it into a
  ;; generic failure while the other preserves it.
  (test-support/with-database
   (fn [connection]
     (let [evaluated (:seon.sci.admit/value (run "(+ 1 2) (+ 3 4)"))
           invoked (:seon.sci.admit/value
                    (kernel/invoke
                     {:seon.sci.eval/ctx (eval/build-base-ctx (seon.schema/handed-projection))
                      :seon.db/db (db/db connection)
                      :seon.fn/sym 'user/never-defined
                      :seon.sci.eval/args []
                      :seon.sci.eval/time-limit-ms 1000
                      :seon.sci.admit/caps caps
                      :seon.config/on-core-error :record}))]
       (is (= :seon.sci.eval/reader-event-count (:seon.error/kind evaluated))
           "the reader's refusal is not flattened into evaluation-failed")
       (is (= :seon.sci.kernel/missing-function-installer
              (:seon.error/kind invoked))
           "nor is the kernel's own refusal flattened into invocation-failed")
       (is (some? (:seon.sci.admit/record (:seon.error/data invoked)))
           "a preserved refusal still gains the boundary's own evidence")))))

(deftest
  a-set-print-length-survives-to-the-turns-next-form
  (test-support/with-database
    (fn [connection]
      (let [{ctx :seon.sci.eval/ctx} (eval/fork-for-turn
                                       {:seon.sci.eval/ctx
                                        (test-support/fork-cluster-ctx connection),
                                        :seon.db/db (db/db connection),
                                        :seon.db/connection connection,
                                        :seon.agent/id "print-session-agent"})
            setting (run-in ctx "(set! *print-length* 2)" 5000)
            following (run-in ctx "(vec (range 40))" 5000)
            unrelated (run-in ctx "(+ 1 1)" 5000)]
        (is (= 2 (get-in setting [:seon.print/options :seon.print/length])))
        (is
          (= 2 (get-in following [:seon.print/options :seon.print/length]))
          "the next form of the same turn prints the way the agent asked")
        (is
          (= 2 (get-in unrelated [:seon.print/options :seon.print/length]))
          "and so does every form after it, until one sets another value")
        (let [reset (run-in ctx "(set! *print-length* nil)" 5000)]
          (is (nil? (get-in reset [:seon.print/options :seon.print/length])))
          (is
            (nil?
              (get-in
                (run-in ctx "(vec (range 40))" 5000)
                [:seon.print/options :seon.print/length]))
            "clearing the bound carries forward exactly as setting one does"))))))

(deftest a-storable-declarations-committed-row-is-recognised-by-its-value
  ;; THE WRITER CANONICALIZES BEFORE IT COMMITS. `seon.turn/row-tx` runs every
  ;; reader row through `seon.program/declaration-row`, which rebuilds a schema
  ;; row and RE-PRINTS its `:seon.schema/form`; a namespaced property map —
  ;; carried by exactly the STORABLE declarations — prints there as
  ;; `#:seon.db{:identity true}` and in the reader's row as
  ;; `{:seon.db/identity true}`. Comparing those BYTES answered "not ours" for
  ;; the cluster's own committed declaration, so `install-evaluated-rows!`
  ;; skipped it and the live projection lost every storable declaration while
  ;; its facts were intact (2026-09-17). A declaration is a VALUE.
  (test-support/with-database
    (fn [connection]
      (let [reader-row {:seon.schema/key :example.storable/order
                        :seon.schema/form "[:string {:seon.db/identity true}]"
                        :seon.schema.admission/source :agent}
            canonical (program/declaration-row reader-row :all :agent)]
        (test-support/transacted! connection [canonical])
        (is (not= (:seon.schema/form reader-row) (:seon.schema/form canonical))
            "the writer's canonical printing genuinely differs from the reader's")
        (is (true? (eval/committed-row? (db/db connection) reader-row))
            "the committed declaration IS this reader row's declaration")
        (is (false? (eval/committed-row?
                     (db/db connection)
                     (assoc reader-row :seon.schema/form "[:int {:seon.db/identity true}]")))
            "a genuinely different declaration is still not the committed one")))))

(deftest a-returned-values-non-string-error-message-is-still-the-declared-string
  ;; `:seon.cluster.eval/error` is declared `:string`
  ;; (`resources/seon/schemas/seon.cluster.eval.edn:3`), and the value it
  ;; projects is ARBITRARY: any form may return a map carrying
  ;; `:seon.error/kind` whose `:seon.error/message` is not a string. Reading
  ;; that key verbatim handed `evaluate`'s own output contract a lookup-ref
  ;; vector, so the diagnostic of the evaluation became a contract violation
  ;; naming `seon.sci.eval/evaluate` instead of the evaluation naming its own
  ;; failure (fault `7710efbc…`, default pid 66052, 2026-09-17 04:22:54Z).
  ;; The projection is DERIVED here; the value's own shape is never the
  ;; evaluation's declared text.
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            evaluation
            (run-in ctx
                    (str "{:seon.error/kind :probe/refused"
                         " :seon.error/message [:seon.ns/name (quote user)]}")
                    5000)]
        (is (string? (:seon.cluster.eval/error evaluation))
            "a failed evaluation names its failure with the string it declares")
        (is (nil? (schema/explain-candidate-value
                   :seon.sci.eval/evaluation evaluation))
            "and the whole evaluation satisfies the contract it declares")))))
