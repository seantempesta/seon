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
            [seon.cluster.work :as work]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [sci.addons.future :as sci.future]
            [sci.core :as sci]
            [seon.render :as render]
            [seon.render.walk :as render.walk]
            [seon.render.web :as render.web]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- compiled-runtime-victim
  []
  :original)

(def ^:dynamic ^:private *compiled-runtime-dynamic-victim* :original)

(defn- compiled-runtime-ctx
  []
  (let [ctx (eval/build-base-ctx)]
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
   (cond-> {:seon.cluster.run.form/source source
            :seon.sci.admit/caps caps
            :seon.sci.eval/time-limit-ms time-limit-ms
            ;; development disposition: a codec hole must be loud
            ;; here of all places
            :seon.config/on-core-error :panic}
     ctx (assoc :seon.sci.eval/ctx ctx))))

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
       {:seon.cluster.run.form/source "(+ 1 1)"
        :seon.sci.admit/caps caps
        :seon.sci.eval/time-limit-ms 1000
        :seon.config/on-core-error :panic}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.sci.eval/request
            {:seon.cluster.run.form/source "(+ 1 1)"
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 1000}))
      "no dial, no evaluation"))

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

(deftest the-evaluator-remains-live-after-its-namespace-reloads
  (let [request {:seon.cluster.run.form/source "(+ 1 2)"
                 :seon.sci.admit/caps caps
                 :seon.sci.eval/time-limit-ms 10000
                 :seon.config/on-core-error :panic}
        before (eval/evaluate request)]
    (is (= 3 (:seon.sci.admit/value before))
        "the first evaluation realizes the process guard")
    (require 'seon.sci.eval :reload)
    (let [after ((requiring-resolve 'seon.sci.eval/evaluate) request)]
      (is (= 3 (:seon.sci.admit/value after))
          "ordinary arm data has no reload-sensitive class identity")
      (is (nil? (:seon.cluster.eval/error after))))))

(deftest isolated-one-off-evaluations-do-not-share-definitions
  (run "(def leaked 1)")
  (let [evaluation (run "leaked")]
    (is (failed? evaluation)
        "one evaluation's def cannot reach the next")
    (is (= :seon.sci.eval/evaluation-failed
           (:seon.error/kind (:seon.sci.admit/value evaluation))))))

(deftest a-live-context-preserves-definition-value-class-and-metadata
  (let [ctx (eval/build-base-ctx)
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
  (let [ctx-a (eval/build-base-ctx)
        ctx-b (eval/build-base-ctx)
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
          (db/transact! connection-a [{:seon.cluster/name "fork-a"}])
          (db/transact! connection-b [{:seon.cluster/name "fork-b"}])
          (let [ctx-a (test-support/fork-cluster-ctx connection-a)
                ctx-b (test-support/fork-cluster-ctx connection-b)
                query
                "(seon.db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]])"
                evaluate
                (fn [ctx]
                  (eval/evaluate
                   {:seon.cluster.run.form/source query
                    :seon.cluster.run.form/ns [:seon.ns/name 'user]
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
  (let [ctx (eval/build-base-ctx)
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
          (is (= :seon.sci.eval/evaluation-failed
                 (:seon.error/kind refusal))
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
  (let [ctx (eval/build-base-ctx)
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
  (let [parent (eval/build-base-ctx)
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
      (let [parent (eval/build-base-ctx)
            _ (sci/eval-string*
               parent
               "(defn contracted [x] x)")
            parent-var (sci/resolve parent 'contracted)
            parent-root @parent-var
            candidate (sci/fork parent)]
        (#'eval/install-function-contract!
         candidate
         {:seon.fn/sym "user/contracted"
          :seon.fn/spec "[:=> [:cat :int] :int]"}
         (seon.schema/projection-from-database @connection)
         @connection)
        (is (identical? parent-var (sci/resolve parent 'contracted)))
        (is (identical? parent-root @(sci/resolve parent 'contracted)))
        (is (not (identical? parent-var
                             (sci/resolve candidate 'contracted)))
            "contract installation copies the inherited candidate Var")
        (is (= 42 (sci/eval-string* candidate "(contracted 42)")))))))

(deftest require-context-rows-persist-namespace-lookup-refs
  (let [ctx (eval/build-base-ctx)
        evaluation (run-in ctx "(require 'clojure.set)" 2000)]
    (is (ok? evaluation))
    (is (= #{[:seon.ns/name 'clojure.set]}
           (get-in evaluation
                   [:seon.program/row :seon.ns/requires]))
        "SCI symbols become canonical lookup refs only at persistence")))

(deftest runtime-function-rows-carry-parsed-contract-facts
  (let [ctx (eval/build-base-ctx)
        evaluation
        (run-in ctx
                (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                     "parsed-at-runtime [x] x)")
                2000)
        row (:seon.program/row evaluation)]
    (is (= "user/parsed-at-runtime" (:seon.fn/sym row)))
    (is (= 1 (count (:seon.fn/arities row))))
    (is (map? (:seon.fn/ast row)))))

(deftest static-and-runtime-contracted-definitions-publish-identical-facts
  (let [root (java.nio.file.Files/createTempDirectory
              (.toPath (io/file "tmp")) "p12-runtime-parity"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source
        (str "(defn ^{:malli/schema "
             "[:=> [:cat [:map [:x :int]] [:* :string]] :int]} "
             "same-facts [{:keys [x]} & xs] x)")
        source-file (.resolve root "parity.clj")]
    (try
      (spit (.toFile source-file) (str "(ns parity)\n" source "\n"))
      (let [static-row
            (first
             (filter #(= "parity/same-facts" (:seon.fn/sym %))
                     (#'seon.fn/desired-rows
                      {:seon.fn/roots [(str root)]}
                      (fn [_phase]))))
            ctx (eval/build-base-ctx)
            runtime-row
            (:seon.program/row
             (eval/evaluate
              {:seon.sci.eval/ctx ctx
               :seon.cluster.run.form/ns [:seon.ns/name 'parity]
               :seon.cluster.run.form/source source
               :seon.sci.admit/caps caps
               :seon.sci.eval/time-limit-ms 2000
               :seon.config/on-core-error :panic}))
            p12-keys [:seon.fn/arities :seon.fn/ast
                      :seon.fn/arglists-override?]]
        (is (= "parity/same-facts" (:seon.fn/sym runtime-row)))
        (is (= (select-keys static-row p12-keys)
               (select-keys runtime-row p12-keys)))
        ;; Identical publication includes the attributes every declaration
        ;; row REQUIRES, not only the P12 contract facts. The runtime path
        ;; published rows with no admission source for as long as this test
        ;; compared only the keys it named.
        (is (= :core (:seon.schema.admission/source static-row)))
        (is (= :agent (:seon.schema.admission/source runtime-row)))
        ;; Whole-row contract validation is NOT asserted here yet: both rows
        ;; carry `:seon.fn/arities` and `:seon.fn/ast` component entities,
        ;; and `:seon.db/ref` admits no component value, so both are refused
        ;; by `:seon.program/declaration-row`. That is a different class at a
        ;; different owner and its regression belongs to it —
        ;; docs/seon/issues/a-component-value-is-refused-by-its-own-ref-shape.md
        )
      (finally
        (test-support/delete-recursively! (str root))))))

(deftest contracted-defn-renders-the-var-it-declared
  (let [def-node
        (edn/read-string
         (:seon.cluster.eval/result-edn (run "(def plain-declaration 1)")))
        defn-node
        (edn/read-string
         (:seon.cluster.eval/result-edn
          (run
           (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                "rendered-declaration [x] x)"))))]
    (is (= (:seon.print/face def-node)
           (:seon.print/face defn-node)
           :seon.print/var))
    (is (= 'user/rendered-declaration
           (symbol (:seon.print/name defn-node))))))

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
      (let [ctx (eval/cluster-ctx @connection connection)
            capability-symbols
            (sort
             (db/q '[:find [?sym ...]
                     :where
                     [?fn :seon.fn/sym ?sym]
                     [?fn :seon.fn/private? false]
                     [?fn :seon.effect/capability _]]
                   @connection))
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
        (is (contains? (set capability-symbols) "my.web/fetch")
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

(deftest schema-and-contract-declarations-have-bounded-allocation
  (test-support/with-database
    (fn [connection]
      (let [ctx (eval/cluster-ctx @connection connection)]
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
  (let [ctx (eval/build-base-ctx)
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
            "evaluation never publishes the isolated schema delta")))))

(deftest success-evaluation-assembles-every-optional-projection
  (let [printed (doto (java.io.StringWriter.) (.write "abcdef"))
        record {:seon.eval/outcome :ok}
        row {:seon.fn/sym "user/f"}
        defs [{:seon.def/id "user/x"}]
        evaluation
        (#'eval/success-evaluation
         {:seon.sci.eval/admitted
          {:seon.sci.admit/value 7
           :seon.cluster.eval/result-edn "7"
           :seon.sci.admit/capped? false
           :seon.sci.admit/record record}
          :seon.sci.admit/caps
          (assoc caps :seon.config.eval.result/max-string 3)
          :seon.sci.eval/output-prefix "restored"
          :seon.sci.eval/printed printed
          :seon.sci.eval/namespace-name 'user
          :seon.sci.eval/ending-namespace 'next
          :seon.print/options {:seon.print/length 4}
          :seon.sci.eval/defs defs
          :seon.program/row row})]
    (is (= {:seon.sci.admit/value 7
            :seon.cluster.eval/result-edn "7"
            :seon.print/options {:seon.print/length 4}
            :seon.cluster.eval/ns [:seon.ns/name 'user]
            :seon.sci.eval/ending-ns 'next
            :seon.sci.admit/capped? false
            :seon.sci.admit/record record
            :seon.program/row row
            :seon.sci.eval/defs defs
            :seon.cluster.eval/output "res"}
           evaluation))))

(deftest failed-evaluation-assembles-failure-presence-facts
  (let [printed (doto (java.io.StringWriter.) (.write "before failure"))
        interrupted-at (java.util.Date. 1785000000000)
        record {:seon.eval/outcome :time}
        value {:seon.error/kind :seon.sci.eval/time-limit
               :seon.error/message "Ran out of time."}
        admitted {:seon.sci.admit/value value
                  :seon.cluster.eval/result-edn (pr-str value)
                  :seon.sci.admit/capped? false}
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
          :seon.sci.eval/defs defs
          :seon.sci.admit/record record
          :seon.sci.admit/value value
          :seon.cluster.eval/interrupted-at interrupted-at})]
    (is (= {:seon.sci.admit/value value
            :seon.cluster.eval/result-edn (pr-str value)
            :seon.print/options {:seon.print/level 3}
            :seon.cluster.eval/ns [:seon.ns/name 'user]
            :seon.sci.eval/ending-ns 'user
            :seon.cluster.eval/error "Ran out of time."
            :seon.sci.admit/capped? false
            :seon.sci.admit/record record
            :seon.sci.eval/defs defs
            :seon.cluster.eval/interrupted-at interrupted-at
            :seon.cluster.eval/output "lost\nb"}
           evaluation))))

(deftest evaluation-projection-prefers-the-live-context
  (let [projection {:seon.schema.projection/forms {:user/x :int}}
        ctx (assoc (eval/build-base-ctx)
                   :seon.schema/projection projection)]
    (with-redefs [seon.schema/current-projection
                  (fn [] (throw (ex-info "fallback reached" {})))]
      (is (identical?
           projection
           (#'eval/evaluation-projection {:seon.sci.eval/ctx ctx}))))))

(deftest unmap-row-carries-the-exact-forked-namespace-state
  (let [ctx (eval/build-base-ctx)
        _ (sci/eval-string*
           ctx
           (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                "discarded [x] x)"))
        source "(ns-unmap 'user 'discarded)"
        event (#'eval/one-event source 'user ctx)
        execution-ctx (sci/fork ctx)
        before-interns (sci/namespace-interns execution-ctx)
        before-namespace-state (sci/namespace-state execution-ctx)
        before-reader-context (#'eval/reader-context execution-ctx 'user)
        _ (sci/binding [sci/ns (sci/create-ns 'user)]
            (sci/eval-form execution-ctx
                           (:seon.sci.reader/form event)))
        result
        (#'eval/unmap-row
         {:seon.sci.eval/execution-ctx execution-ctx
          :seon.sci.eval/before-interns before-interns
          :seon.sci.eval/before-namespace-state before-namespace-state
          :seon.sci.eval/before-reader-context before-reader-context
          :seon.sci.eval/event event
          :seon.sci.eval/base-declared-row nil
          :seon.sci.eval/live-declaration? false
          :seon.sci.eval/namespace-name 'user
          :seon.sci.eval/namespace-unmap? true
          :seon.cluster.run.form/source source})
        row (:seon.program/row result)]
    (is (true? (:seon.sci.eval/namespace-changed? result)))
    (is (= #{[:seon.fn/sym "user/discarded"]
             [:seon.test/sym "user/discarded"]}
           (set (:seon.program/delete-identities row))))
    (is (= [:seon.ns/name 'user] (:seon.program/ns row)))
    (is (= (sci/namespace-state execution-ctx)
           (:seon.sci.eval/namespace-state row)))
    (is (some? (sci/resolve ctx 'discarded))
        "the forked ns-unmap leaves the parent context unchanged")
    (is (nil? (sci/resolve execution-ctx 'discarded)))
    (is (nil? (:seon.sci.eval/context-row result)))))

(deftest declared-row-evaluates-a-schema-once-inside-its-delta
  (let [ctx (eval/build-base-ctx)
        source "(seon.schema/register! :user/direct-schema [:int {:min 0}])"
        event (#'eval/one-event source 'user ctx)
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
    (is (= {:seon.schema/key :user/direct-schema
            :seon.schema/form "[:int {:min 0}]"
            :seon.schema.admission/source :agent}
           (:seon.sci.eval/base-declared-row result)))
    (is (= :user/direct-schema (:seon.sci.eval/schema-value result)))
    (is (false? (:seon.sci.eval/live-declaration? result)))
    (is (= before (seon.schema/registered-schemas))
        "the evaluated registration remains isolated from global candidates")))

(deftest the-dispositions-are-callable-and-come-back-as-values
  (let [evaluation (run "(my.run/complete \"done\")")]
    (is (ok? evaluation))
    (is (= {:my.run/disposition :completed :my.run/result "done"}
           (:seon.sci.admit/value evaluation))
        "the loop reads its disposition out of exactly this"))
  (let [evaluation (run "(my.run/wait \"later\")")]
    (is (= :wait (:my.run/disposition (:seon.sci.admit/value evaluation))))))

(deftest an-unbound-var-remains-structured-after-production-admission
  (let [evaluation (run "(do (declare zz) zz)")
        admitted (:seon.sci.admit/value evaluation)]
    (is (= {:seon.sci.admit/opaque "sci.impl.vars.SciUnbound"} admitted)
        "the real door preserves a value-level marker; no error string is parsed")
    (is (work/unbound-value? admitted))
    (is (nil? (:seon.cluster.eval/error evaluation))
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
      (let [ctx (eval/build-base-ctx)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx
                              :seon.db/db @connection})
            projection (seon.schema/projection-from-database @connection)]
        (try
          (seon.schema/call-with-projection
           projection
           #(instrument/apply! {:seon.config/on-core-error :panic
                                :seon.sci.admit/caps caps}))
          (let [evaluation (run-in ctx "(my.message/send)" 2000)
                failure (:seon.sci.admit/value evaluation)]
            (is (= (str "Wrong number of args (0) passed to: my.message/send"
                        "; declared arglists: ([to content] [to content about])")
                   (:seon.error/message failure)
                   (:seon.cluster.eval/error evaluation)))
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind failure)))
            (is (= 0 (get-in failure
                             [:seon.error/data :seon.instrument/arity])))
            (is (= '[[to content] [to content about]]
                   (get-in failure
                           [:seon.error/data :seon.instrument/arglists])))
            (is (not (str/includes? (:seon.cluster.eval/result-edn evaluation)
                                    ":malli.core/invalid-schema"))))
          (finally
            (instrument/remove!)))))))

(deftest bare-dir-and-program-derived-doc-are-repl-native
  (test-support/with-database
    (fn [connection]
      (let [giant-schema-key :fixture.doc/giant
            giant-schema-form
            (into [:map]
                  (map (fn [index]
                         [(keyword "fixture.doc" (str "field-" index)) :int]))
                  (range 500))
            _ (db/transact!
               connection
               [{:seon.schema/key giant-schema-key
                 :seon.schema.admission/source :core
                 :seon.schema/form (pr-str giant-schema-form)}])
            _ (db/transact!
               connection
               [{:seon.fn/sym "fixture.doc/uncontracted"
                 :seon.fn/doc "An uncontracted fixture."
                 :seon.fn/arglists "([value])"
                 :seon.fn/private? false}
                {:seon.fn/sym "fixture.doc/giant"
                 :seon.fn/doc "A giant contracted fixture."
                 :seon.fn/arglists "([request])"
                 :seon.fn/private? false
                 :seon.fn/arities
                 [{:seon.fn.arity/order 0
                   :seon.fn.arity/input-refs
                   [[:seon.schema/key giant-schema-key]]}]}])
            db @connection
            ctx (eval/build-base-ctx)
            _ (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db db})
            directory (run-in ctx "(dir my.message)" 2000)
            read-doc (run-in ctx "(doc my.fs/read)" 2000)
            multi-doc (run-in ctx "(doc my.message/send)" 2000)
            uncontracted-doc
            (run-in ctx "(doc fixture.doc/uncontracted)" 2000)
            giant-doc (run-in ctx "(doc fixture.doc/giant)" 2000)
            read-output (:seon.cluster.eval/output read-doc)
            multi-output (:seon.cluster.eval/output multi-doc)
            uncontracted-output
            (:seon.cluster.eval/output uncontracted-doc)
            giant-output (:seon.cluster.eval/output giant-doc)]
        (is (= "decline\nsend\n"
               (:seon.cluster.eval/output directory)))
        (is (= ['my.message/decline 'my.message/send]
               (:seon.sci.admit/value directory))
            "dir's settled value introduces the qualified symbols it lists")
        (testing "one contracted function resolves both sides of its contract"
          (is (every? #(str/includes? read-output %)
                      ["my.fs/read"
                       "([request])"
                       "Read a bounded window of one file"
                       "  in:  :my.fs/read-request"
                       "[:my.fs/path :my.fs/path]"
                       "  out: :my.fs/read-result"
                       "[:my.fs/window-digest :my.fs/digest]"
                       "       :seon.error/value"])
              read-output)
          (is (= ["       :seon.error/value"]
                 (filterv #(str/includes? % ":seon.error/value")
                          (str/split-lines read-output)))
              "the standard error arm is exactly one bare-key line")
          (is (not-any? #(str/starts-with? % ";")
                        (str/split-lines read-output))
              "doc output is result text, never comment syntax"))
        (testing "multiple arities each retain their ordered contract block"
          (let [lines (str/split-lines multi-output)]
            (is (= 2 (count (filter #(str/starts-with? % "  in:") lines))))
            (is (= 2 (count (filter #(str/starts-with? % "  out:") lines)))))
          (is (< (str/index-of multi-output "  arity 2:")
                 (str/index-of multi-output "  arity 3:"))))
        (testing "an uncontracted function gains no empty contract labels"
          (is (every? #(str/includes? uncontracted-output %)
                      ["fixture.doc/uncontracted"
                       "([value])"
                       "An uncontracted fixture."]))
          (is (not (str/includes? uncontracted-output "  in:")))
          (is (not (str/includes? uncontracted-output "  out:"))))
        (testing "a giant schema uses the ordinary structural print floor"
          (is (str/includes? giant-output "  in:  :fixture.doc/giant"))
          (is (str/includes? giant-output "..."))
          (is (not (str/includes? giant-output ":fixture.doc/field-499")))
          (is (< (count giant-output) (count (pr-str giant-schema-form)))))
        (testing "a non-core error definition keeps its resolved form"
          (let [core-projection
                (seon.schema/projection-from-database @connection)
                projection
                (seon.schema/projection-with-schema
                 core-projection
                 :seon.error/value
                 (get-in core-projection
                         [:seon.schema.projection/forms :seon.error/value])
                 {:seon.schema.admission/source :agent})
                nonstandard-ctx (eval/build-base-ctx)
                _ (#'eval/install-program-doc!
                   nonstandard-ctx @connection projection)
                nonstandard-doc
                (run-in nonstandard-ctx "(doc my.fs/read)" 2000)]
            (is (some #(str/starts-with?
                        % "       :seon.error/value  [:map")
                      (str/split-lines
                       (:seon.cluster.eval/output nonstandard-doc))))
            (is (= 'my.fs/read
                   (get-in nonstandard-doc
                           [:seon.sci.admit/value :seon.fn/sym])))))
        (is (= ['my.fs/read 'my.message/send
                'fixture.doc/uncontracted 'fixture.doc/giant]
               (mapv #(get-in % [:seon.sci.admit/value :seon.fn/sym])
                     [read-doc multi-doc uncontracted-doc giant-doc]))
            "doc returns the same acquired facts it prints")))))

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
      (is (= :seon.sci.eval/time-limit
             (:seon.error/kind (:seon.sci.admit/value evaluation))))
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
  (let [ctx (eval/build-base-ctx)
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
    (is (= :seon.sci.eval/time-limit
           (:seon.error/kind (:seon.sci.admit/value evaluation)))
        "the wrapped sci interrupt remains a flat time-limit value")))

(deftest a-base-created-function-uses-the-invoking-threads-arm
  ;; The interpreted corpus will be installed into `base`, so its functions
  ;; capture the base's interrupt-fn when SCI creates them. Create this one on
  ;; the test thread, then invoke it through a fork on another thread: arming
  ;; must follow the invoking thread, not the thread that created the function.
  (let [base (eval/build-base-ctx)
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

(deftest an-acquired-function-uses-the-current-evaluation-limit
  (test-support/with-database
    (fn [connection]
      (let [source
            (str "(defn ^{:malli/schema [:=> [:cat] :int]} spin [] "
                 "(loop [i 0] (recur (inc i))))")
            authored-ctx (eval/build-base-ctx)
            _ (sci/add-namespace! authored-ctx 'authored.interrupt {})
            _ (sci/binding [sci/ns (sci/create-ns 'authored.interrupt)]
                (sci/eval-string* authored-ctx source))
            root-edn
            (binding [*print-meta* true]
              (pr-str
               (first
                (sci/var-root-data authored-ctx
                                   ['authored.interrupt/spin]))))
            _
            (db/transact!
             connection
             [{:seon.cluster.agent/id "interrupt-author"
               :seon.cluster.agent/namespace
               {:seon.ns/name 'authored.interrupt
                :seon.ns/source "(ns authored.interrupt)"}}
              {:seon.fn/sym "authored.interrupt/spin"
               :seon.schema.admission/source :agent
               :seon.fn/ns [:seon.ns/name 'authored.interrupt]
               :seon.fn/source source
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat] :int]"}
              {:seon.def/key
               (pr-str ["interrupt-author" "authored.interrupt/spin#root"])
               :seon.def/id "authored.interrupt/spin#root"
               :seon.def/agent
               [:seon.cluster.agent/id "interrupt-author"]
               :seon.def/ns [:seon.ns/name 'authored.interrupt]
               :seon.def/name 'spin#root
               :seon.def/value-edn root-edn
               :seon.def/ordinal 0
               :seon.schema.admission/source :agent}])
            ctx (eval/build-base-ctx)
            acquired (eval/acquire! {:seon.sci.eval/ctx ctx
                                     :seon.db/db @connection})
            evaluation
            (deadlined-in ctx "(authored.interrupt/spin)" 300)]
        (is (= 2 (:seon.sci.eval/installed acquired)))
        (is (not= ::hung evaluation))
        (is (cut? evaluation))
        (is (= :time
               (:seon.eval/outcome
                (:seon.sci.admit/record evaluation))))))))

(deftest one-unloadable-row-cannot-prevent-cold-acquisition
  (test-support/with-database
    (fn [connection]
      (let [namespace-name 'acquire.poison
            agent-id "acquire-poison-author"
            good-source
            (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                 "good [x] (inc x))")
            authored-ctx (eval/build-base-ctx)
            _ (sci/add-namespace! authored-ctx namespace-name {})
            _ (sci/binding [sci/ns (sci/create-ns namespace-name)]
                (sci/eval-string* authored-ctx good-source))
            good-root-edn
            (binding [*print-meta* true]
              (pr-str
               (first
                (sci/var-root-data authored-ctx
                                   ['acquire.poison/good]))))]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name
            :seon.ns/source "(ns acquire.poison)"}}
          {:seon.fn/sym "acquire.poison/bad"
           :seon.schema.admission/source :agent
           :seon.fn/ns [:seon.ns/name namespace-name]
           :seon.fn/source
           (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                "bad [x] x)")
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}
          {:seon.fn/sym "acquire.poison/good"
           :seon.schema.admission/source :agent
           :seon.fn/ns [:seon.ns/name namespace-name]
           :seon.fn/source good-source
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}
          {:seon.def/key
           (pr-str [agent-id "acquire.poison/good#root"])
           :seon.def/id "acquire.poison/good#root"
           :seon.def/agent [:seon.cluster.agent/id agent-id]
           :seon.def/ns [:seon.ns/name namespace-name]
           :seon.def/name 'good#root
           :seon.def/value-edn good-root-edn
           :seon.def/ordinal 0
           :seon.schema.admission/source :agent}])
        (let [ctx
              (assoc (eval/build-base-ctx)
                     :seon.sci.eval/custody
                     {:seon.db/connection connection})
              acquired
              (eval/acquire! {:seon.sci.eval/ctx ctx
                              :seon.db/db @connection})
              refusal
              (first
               (db/q '[:find [(pull ?error [*]) ...]
                       :where
                       [?error :seon.error/kind
                        :seon.sci.eval/acquisition-refused]]
                     @connection))]
          (is (= 42 (sci/eval-string* ctx "(acquire.poison/good 41)"))
              "a later valid row installs and works")
          (is (= 1 (count (:seon.sci.eval/acquisition-refusals acquired))))
          (is (true?
               (:seon.sci.eval/acquisition-refusals-recorded? acquired)))
          (is (some? refusal) "the contained agent mistake is a durable fact")
          (is (str/includes? (:seon.error/message refusal)
                             "[:seon.fn/sym \"acquire.poison/bad\"]"))
          (is (str/includes? (:seon.error/data-edn refusal)
                             "seon.sci.eval/unrestorable-function-root")
              "the fact retains the row's typed cause as queryable evidence"))))))

(deftest agent-contracts-apply-on-acquire-and-cold-recovery
  (test-support/with-database
    (fn [connection]
      (let [source
            (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                 "accept [x] x)")
            authored-ctx (eval/build-base-ctx)
            _ (sci/add-namespace! authored-ctx 'authored.contract {})
            _ (sci/binding [sci/ns (sci/create-ns 'authored.contract)]
                (sci/eval-string* authored-ctx source))
            root-edn
            (binding [*print-meta* true]
              (pr-str
               (first
                (sci/var-root-data authored-ctx
                                   ['authored.contract/accept]))))]
        (db/transact!
         connection
         [(:seon.config/desired-row
           (config/compile-manifest
            {:seon.boot/cluster-name "contract-acquire"
             :seon.config/manifest
             (assoc caps :seon.config/on-core-error :panic)}))
          {:seon.cluster.agent/id "contract-author"
           :seon.cluster.agent/namespace
           {:seon.ns/name 'authored.contract
            :seon.ns/source "(ns authored.contract)"}}
          {:seon.fn/sym "authored.contract/accept"
           :seon.schema.admission/source :agent
           :seon.fn/ns [:seon.ns/name 'authored.contract]
           :seon.fn/source source
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}
          {:seon.def/key
           (pr-str ["contract-author" "authored.contract/accept#root"])
           :seon.def/id "authored.contract/accept#root"
           :seon.def/agent [:seon.cluster.agent/id "contract-author"]
           :seon.def/ns [:seon.ns/name 'authored.contract]
           :seon.def/name 'accept#root
           :seon.def/value-edn root-edn
           :seon.def/ordinal 0
           :seon.schema.admission/source :agent}])
        (let [assert-violation
            (fn [ctx moment]
              (let [evaluation
                    (run-in ctx "(authored.contract/accept \"wrong\")" 2000)
                    failure (:seon.sci.admit/value evaluation)]
                (is (= :seon.instrument/contract-violated
                       (:seon.error/kind failure)) moment)
                (is (= "authored.contract/accept"
                       (get-in failure
                               [:seon.error/data :seon.instrument/fn]))
                    moment)))
              acquired-ctx (eval/build-base-ctx)]
          (eval/acquire! {:seon.sci.eval/ctx acquired-ctx
                          :seon.db/db @connection})
          (assert-violation acquired-ctx "boot acquire!")
          (assert-violation (eval/cluster-ctx @connection)
                            "cold crash recovery"))))))

(deftest acquisition-uses-the-effective-config-projection-when-instrumented
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [(merge {:seon.config/cluster "instrumented-acquire"
                :seon.config/on-core-error :panic
                :seon.config/applied-manifest-digest "live-proof"}
               caps)])
      (let [entering-roots
            (into {}
                  (map (fn [instrumented-var]
                         [instrumented-var @instrumented-var]))
                  (instrument/instrumented))
            projection (seon.schema/projection-from-database @connection)]
        (try
          (seon.schema/call-with-projection
           projection
           #(instrument/apply! {:seon.config/on-core-error :panic}))
          (is (map? (eval/acquire!
                     {:seon.sci.eval/ctx (eval/build-base-ctx)
                      :seon.db/db @connection})))
          (finally
            (instrument/remove!)
            (doseq [[instrumented-var root] entering-roots]
              (alter-var-root instrumented-var (constantly root)))))
        (is (= (set (keys entering-roots)) (instrument/instrumented))
            "the test restores the exact entering wrapper set")))))

(deftest acquisition-binds-loaded-first-party-compiled-vars
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "assigned-namespace-acquisition"
            agent-id "probe"
            assigned-namespace 'my.tools.demo
            _ (test-support/seed-cluster! connection cluster-name)
            _ (db/transact!
               connection
               (agent/creation-tx
                {:seon.cluster.agent/id agent-id
                 :seon.ns/name assigned-namespace
                 :seon.cluster/name cluster-name}))
            ctx (eval/cluster-ctx @connection connection)
            evaluation
            (run-in ctx
                    "(seon.sci.eval/agent-namespace (seon.db/db) \"probe\")"
                    2000)
            assigned-evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.cluster.agent/id agent-id
              :seon.cluster.run.form/source "(ns-name *ns*)"
              :seon.sci.admit/caps caps
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic})
            external (run-in ctx "(datahike.api/q '[:find ?e :where [?e]])"
                             2000)]
        (let [installed
              (get-in (sci/namespace-state ctx)
                      ['seon.sci.eval 'agent-namespace])]
          (is (identical? #'eval/agent-namespace @installed)
              "the installed SCI Var forwards to the live compiled Var"))
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
            ctx
            (with-redefs [call-preparation/hook
                          (fn [runtime-ctx _callee arguments]
                            (swap! seen conj (env/of runtime-ctx))
                            arguments)]
              (eval/cluster-ctx @connection connection))
            evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.cluster.agent/id "scoped-agent"
              :seon.cluster.run/id "scoped-run"
              :seon.cluster.run.form/ordinal 7
              :seon.cluster.run.form/source "(my.run/complete \"done\")"
              :seon.sci.admit/caps caps
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic})]
        (is (= {:my.run/disposition :completed
                :my.run/result "done"}
               (:seon.sci.admit/value evaluation)))
        (is (some #(= {:seon.cluster.agent/id "scoped-agent"
                       :seon.cluster.run/id "scoped-run"
                       :seon.cluster.run.form/ordinal 7}
                      (select-keys %
                                   [:seon.cluster.agent/id
                                    :seon.cluster.run/id
                                    :seon.cluster.run.form/ordinal]))
                  @seen)
            "SCI's actual call-preparation hook sees this form's turn members")))))

(deftest evaluation-custody-is-derived-only-from-the-cluster-context
  (test-support/with-database
    (fn [connection-a]
      (test-support/with-database
        (fn [connection-b]
          (db/transact! connection-a [{:seon.cluster/name "ambient-a"}])
          (db/transact! connection-b [{:seon.cluster/name "ambient-b"}])
          (let [uncustodied-ctx (eval/build-base-ctx)
                _ (eval/acquire! {:seon.sci.eval/ctx uncustodied-ctx
                                  :seon.db/db @connection-a})
                ctx-a (eval/cluster-ctx @connection-a connection-a)
                ctx-b (eval/cluster-ctx @connection-b connection-b)
                evaluate
                (fn [ctx source]
                  (eval/evaluate
                   {:seon.cluster.run.form/source source
                    :seon.cluster.run.form/ns [:seon.ns/name 'user]
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic}))
                cluster-names-source
                (str "(seon.db/q "
                     "'[:find [?name ...] "
                     ":where [_ :seon.cluster/name ?name]])")
                unbound
                (binding [db/*conn* connection-b]
                  (evaluate uncustodied-ctx cluster-names-source))
                read-a
                (binding [db/*conn* connection-b]
                  (evaluate ctx-a cluster-names-source))
                read-b (evaluate ctx-b cluster-names-source)
                read-a-again (evaluate ctx-a cluster-names-source)
                write
                (evaluate
                 ctx-a
                 (str "(seon.db/transact! "
                      "[{:seon.cluster.message/id \"ambient-message\"}])"))
                read-written
                (evaluate
                 ctx-a
                 (str "(seon.db/q "
                      "'[:find ?id . "
                      ":where [_ :seon.cluster.message/id ?id]])"))
                rejected
                (evaluate
                 ctx-a
                 (str "(seon.db/transact! "
                      "[{:seon.sci.eval-test/undeclared true}])"))
                unbound-after
                (binding [db/*conn* connection-b]
                  (evaluate uncustodied-ctx cluster-names-source))]
            (is (= :seon.db/missing-connection-binding
                   (get-in unbound
                           [:seon.sci.admit/value :seon.error/kind])))
            (is (= ["ambient-a"] (:seon.sci.admit/value read-a))
                "the ctx overrides a foreign binding already on the thread")
            (is (= ["ambient-b"] (:seon.sci.admit/value read-b))
                "each sibling derives custody from its own ctx")
            (is (= ["ambient-a"] (:seon.sci.admit/value read-a-again)))
            (is (nil? (:seon.cluster.eval/error write)))
            (is (= "ambient-message"
                   (:seon.sci.admit/value read-written))
                "a declared write is visible to the next evaluation")
            (is (= :seon.db/rejected
                   (get-in rejected
                           [:seon.sci.admit/value :seon.error/kind])))
            (is (= :transact/schema
                   (get-in rejected
                           [:seon.sci.admit/value
                            :seon.error/data
                            :error])))
            (is (= :seon.db/missing-connection-binding
                   (get-in unbound-after
                           [:seon.sci.admit/value :seon.error/kind]))
                "an uncustodied ctx never inherits the caller's binding")))))))

(deftest public-walk-is-callable-through-an-agent-sci-eval
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "host-walk")
      (db/transact! connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "host-walker"
                    :seon.cluster/name "host-walk"
                    :seon.ns/name 'my.agents.host-walker}))
      (let [ctx (eval/cluster-ctx @connection connection)
            request
            {:seon.db/db @connection
             :seon.db/connection connection
             :seon.cluster.agent/id "host-walker"
             :seon.render.walk/lookup
             [:seon.cluster.agent/id "host-walker"]
             :seon.render/output :seon.render/ai
             :seon.render/distance 2
             :seon.sci.admit/caps caps
             :seon.sci.eval/ctx ctx
             :seon.sci.eval/time-limit-ms 5000
             :seon.config/on-core-error :panic}
            root-selector render.walk/root-selector
            root-selectors (atom [])
            compile-plan pull-api/compile-pull-plan
            compilation-count (atom 0)
            effective-count (atom 0)]
        (with-redefs [render.walk/root-selector
                      (fn [database distance supplied-caps]
                        (let [selector
                              (root-selector database distance supplied-caps)]
                          (swap! root-selectors conj selector)
                          selector))
                      pull-api/compile-pull-plan
                      (fn
                        ([selector-or-plan]
                         (when (some #(identical? selector-or-plan %)
                                     @root-selectors)
                           (swap! compilation-count inc))
                         (compile-plan selector-or-plan))
                        ([database selector-or-plan]
                         (compile-plan database selector-or-plan)))
                      config/effective
                      (fn [_database _cluster-name]
                        (swap! effective-count inc)
                        (config/defaults))]
          (let [evaluate-walk
                #(render/call-with-walk-context
                  request
                  (fn [] (run-in ctx "(seon.render/walk)" 5000)))
                through-sci (evaluate-walk)
                direct (render.walk/root-acquisition request)
                web (#'render.web/acquire-root request ::root)
                value (:seon.sci.admit/value through-sci)
                allocations
                (mapv #(get-in % [:seon.sci.admit/record
                                  :seon.eval/allocated-bytes])
                      [through-sci])]
            (is (ok? through-sci))
            (is (string? value))
            (is (re-find
                 #"root=\[:seon\.cluster\.agent/id \"host-walker\"\]"
                 value))
            (is (false? (:seon.sci.admit/capped? through-sci))
                "the measured string cap admits the ordinary walk whole")
            (is (map? direct))
            (is (= 2 (count web)))
            (is (= 1 @compilation-count)
                "direct, web, and through-SCI paths share one generation plan")
            (is (= 1 @effective-count)
                "the through-SCI walk resolves effective config once")
            (is (every? #(and (int? %) (< % (* 1024 1024 1024)))
                        allocations)
                (str "through-SCI allocations must stay below 1 GiB: "
                     (pr-str allocations)))))))))

(deftest one-context-arms-concurrent-threads-independently
  (let [ctx (eval/build-base-ctx)
        definition
        (run-in
         ctx
         (str "(defn finite-spin [] "
              "(loop [i 0] (if (< i 100000000) (recur (inc i)) i)))")
         1000)
        start (java.util.concurrent.CountDownLatch. 1)
        submit
        (fn [time-limit-ms]
          (future
            (.await start)
            (run-in ctx "(finite-spin)" time-limit-ms)))
        cut-task (submit 200)
        complete-task (submit 10000)]
    (is (ok? definition))
    (.countDown start)
    (let [cut-evaluation (deref cut-task 15000 ::hung)
          complete-evaluation (deref complete-task 15000 ::hung)]
      (is (not= ::hung cut-evaluation))
      (is (not= ::hung complete-evaluation))
      (is (cut? cut-evaluation))
      (is (ok? complete-evaluation)
          "arming and interrupting one thread never cuts its sibling")
      (is (= 100000000
             (:seon.sci.admit/value complete-evaluation))))))

(deftest disarm-clears-the-current-threads-flag-exactly
  (let [ctx (eval/build-base-ctx)
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
                 (string? (:seon.cluster.eval/result-edn evaluation))
                 (do (edn/read-string
                      (:seon.cluster.eval/result-edn evaluation))
                     true)
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
                    :seon.fn/sym (str function-symbol)
                    :seon.sci.eval/args arguments
                    :seon.sci.eval/time-limit-ms time-limit-ms
                    :seon.sci.admit/caps caps
                    :seon.config/on-core-error :record}))))

(deftest a-re-entrant-evaluation-inherits-the-governing-arm
  ;; Before the merge this threw :seon.sci.kernel/already-armed straight out
  ;; of `evaluate`, contradicting this namespace's own "nothing throws"
  ;; contract, while `invoke` on the identical situation returned a value.
  (let [ctx (eval/build-base-ctx)
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
               (let [ctx (eval/build-base-ctx)
                     {stop! :seon.sci.kernel/stop!} (kernel/arm ctx 50)]
                 (try
                   (run-in ctx "(loop [i 0] (recur (inc i)))" 600000)
                   (finally (stop!)))))
        evaluation (deref task 15000 ::hung)]
    (future-cancel task)
    (is (not= ::hung evaluation))
    (is (cut? evaluation)
        "the outer 50ms arm stopped work that asked for ten minutes")))

(deftest a-foreign-armed-context-is-refused-as-a-value
  (let [armed-ctx (eval/build-base-ctx)
        other-ctx (eval/build-base-ctx)
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
     (let [database @connection
           ctx (eval/build-base-ctx)
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
         (is (= :seon.sci.eval/evaluation-failed
                (:seon.error/kind evaluated-throw)))
         (is (= :seon.sci.kernel/invocation-failed
                (:seon.error/kind invoked-throw))
             "only the subject differs — the kind names which entrance ran")
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
         (is (= :seon.sci.eval/time-limit (:seon.error/kind evaluated-cut)))
         (is (= :seon.sci.kernel/time-limit (:seon.error/kind invoked-cut)))
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

(deftest nested-refusal-keeps-the-throw-site-message-as-structured-evidence
  (let [failure
        (kernel/failure-value
         {::kernel/time-limit-kind :probe/time-limit
          ::kernel/failure-kind :probe/failure}
         (ex-info "result renderer exploded"
                  {:seon.error/kind :probe/inner
                   :seon.error/message "inner failure"})
         {:seon.eval/fn-entries 1
          :seon.eval/host-interop-count 0
          :seon.eval/duration-ms 1
          :seon.eval/allocated-bytes 0
          :seon.eval/outcome :error})]
    (is (= "inner failure" (:seon.error/message failure)))
    (is (= "result renderer exploded"
           (:seon.error/throw-site-message (:seon.error/data failure))))
    (is (= :nested-refusal
           (:seon.error/diagnostic-member (:seon.error/data failure))))))

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
                     {:seon.sci.eval/ctx (eval/build-base-ctx)
                      :seon.db/db @connection
                      :seon.fn/sym "user/never-defined"
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
