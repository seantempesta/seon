(ns seon.error-record-test
  "Tests + worked examples for `seon.error/record!` (error-blame-strict-gate
   phase 1): fault classification, EDN stack frames, fire-and-forget
   persistence (+ the no-conn buffer), the wrapper rejection/output arms,
   and one-error-one-datom dedup.

   DELIBERATELY exercises `:agent` faults only — an UN-expected `:core` fault
   prints the `SEON-CORE-FAULT` marker that bin/test-cljs's strict gate greps
   for, so a passing suite must not emit one. (A deliberately-provoked `:core`
   fault in an error-path fixture is bracketed by
   `seon.error/expecting-core-fault!`, which prints the DISTINCT
   `SEON-EXPECTED-CORE-FAULT` marker the gate does not count — the invariant
   is now \"no UN-expected marker\".) The `:core` escalation path (marker +
   dial) is live-proven against the pod (see the phase-1 report), and the
   classification fns are pure — tested directly here."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [malli.core :as m]
    [seon.agent]
    [seon.agent.message]
    [seon.config :as config]
    [seon.error :as error]
    [seon.error.instrument :as ei]
    [seon.instrument :as si]))

(declare tick install-capture-hooks! clear-error-hooks!)

(deftest operation-configuration-is-isolated-across-async-fibers
  (async done
    (let [configuration-a (assoc (config/resolve-config-singleton {})
                                 :seon.config/on-core-error :log)
          configuration-b (assoc (config/resolve-config-singleton {})
                                 :seon.config/on-core-error :gate)
          observed (atom [])
          original-accessor config/on-core-error
          original-console-error (.-error js/console)
          escalate! (deref #'error/escalate!)]
      (set! (.-error js/console) (fn [& _] nil))
      (set! config/on-core-error
            (fn [configuration]
              (swap! observed conj (:seon.config/on-core-error configuration))
              (original-accessor configuration)))
      (-> (js/Promise.all
              #js [(error/with-configuration
                     configuration-a
                     #(-> (tick 10)
                          (.then (fn [_]
                                   (escalate!
                                     {:seon.error/message "fiber a"} nil)))))
                   (error/with-configuration
                     configuration-b
                     #(-> (tick 0)
                          (.then (fn [_]
                                   (escalate!
                                     {:seon.error/message "fiber b"} nil)))))])
            (.then
              (fn [_]
                (is (= #{:log :gate} (set @observed))
                    "each async fault reads the configuration of its operation")))
            (.catch (fn [error] (is false (str error))))
            (.finally
              (fn []
                (set! config/on-core-error original-accessor)
                (set! (.-error js/console) original-console-error)
                (done)))))))

;; ---------------------------------------------------------------------------
;; Pure pieces — no conn needed.
;; ---------------------------------------------------------------------------

(deftest fault-discriminator-is-what-were-we-calling
  (testing "agent-authored namespaces → :agent"
    (is (= :agent (error/fault-for 'my.plan/add!)))
    (is (= :agent (error/fault-for 'my.agent.root/tile))))
  (testing "core/lib namespaces → :core (unclassified = loud)"
    (is (= :core (error/fault-for 'seon.eval/raw-eval)))
    (is (= :core (error/fault-for 'seon.db/transact!)))
    (is (= :core (error/fault-for 'cljs.core/map)))
    (is (= :core (error/fault-for 'unqualified)))))

(deftest error-data-flatten-is-deepest-wins
  ;; C43: `:seon.error/data` flattens the cause chain DEEPEST-wins — the
  ;; original throw's ex-data is the real cause; outer wrappers (cljs.js
  ;; etc.) are conduit noise. Was shallowest-wins, contradicting the
  ;; docstring; pinned here so the precedence never silently flips back.
  (let [deep  (ex-info "deep" {:seon.error/kind :user-input
                               :my.probe/deep-only 1})
        outer (ex-info "outer" {:seon.error/kind :core-bug
                                :my.probe/outer-only 2}
                       deep)
        env   (error/->map outer)
        data  (:seon.error/data env)]
    (is (= :user-input (:seon.error/kind data))
        "on key collision the DEEPEST level's value survives")
    (is (= 1 (:my.probe/deep-only data)))
    (is (= 2 (:my.probe/outer-only data))
        "non-colliding wrapper keys still merge in")
    (testing "C45: the deepest kind is LIFTED to the envelope TOP — the
              ONE position every consumer reads (no `or` over two)"
      (is (= :user-input (:seon.error/kind env)))
      (is (not (contains? (error/->map (js/Error. "kindless"))
                          :seon.error/kind))
          "a kindless throw lifts nothing — optional = absent"))))

(deftest wrapper-fault-classification-matrix
  ;; THE pinned fault-classification matrix (C42 + C43). Under the
  ;; :seon.config/on-core-error :crash dial a misclassification to :core
  ;; CRASHES the pod on an agent mistake — every agent-mistake row below
  ;; must classify :agent, forever. Extend this matrix (don't re-derive
  ;; it) when classification changes.
  (testing "cljs.js self-host analysis error (undeclared var, bad require) → :agent"
    (is (= :agent (si/wrapper-fault
                    (ex-info "ERROR" {:tag :cljs/analysis-error}) :core))))
  (testing "agent-form eval diagnostic (warning-type) → :agent"
    (is (= :agent (si/wrapper-fault
                    (ex-info "Use of undeclared Var"
                             {:seon.eval/warning-type :undeclared-var})
                    :core))))
  (testing "every agent-input kind, FLAT in ex-data (the ONE convention) → :agent"
    (doseq [k error/agent-fault-kinds]
      (is (= :agent (si/wrapper-fault (ex-info "kind" {:seon.error/kind k})
                                      :core))
          (str k))))
  (testing "malli contract violation on an AGENT-authored fn → :agent"
    (is (= :agent (si/wrapper-fault
                    (ex-info ":malli.core/invalid-input"
                             {:seon.error/kind
                              :seon.error.kind/malli-instrument-input
                              :seon.error.malli/fn-sym 'my.probe/f})
                    :core))))
  (testing "malli violation on a CORE fn, no agent turn in scope → coarse"
    (is (= :core (si/wrapper-fault
                   (ex-info ":malli.core/invalid-input"
                            {:seon.error/kind
                             :seon.error.kind/malli-instrument-input
                             :seon.error.malli/fn-sym 'seon.db/transact!})
                   :core))))
  (testing "NESTED kinds classify from the DEEPEST kind (the real cause)"
    (let [deep  (ex-info "agent typo" {:seon.error/kind :user-input})
          outer (ex-info "core conduit re-wrap"
                         {:seon.error/kind :core-bug} deep)]
      (is (= :agent (si/wrapper-fault outer :core))
          "deep agent-blamed cause re-wrapped by a core wrapper → :agent"))
    (let [deep  (ex-info "core cause" {:seon.error/kind :core-bug})
          outer (ex-info "outer user-input wrapper"
                         {:seon.error/kind :user-input} deep)]
      (is (= :core (si/wrapper-fault outer :core))
          "a deep :core cause is NOT masked by an outer agent-ish wrapper")))
  (testing "unclassified runtime errors stay coarse (loud by default)"
    (is (= :core  (si/wrapper-fault (js/Error. "boom") :core)))
    (is (= :agent (si/wrapper-fault (js/Error. "boom") :agent))))
  (testing "DEV-eval scope (C50): a dev/MCP REPL caller is the :agent population"
    (let [malli-e (fn [kind]
                    (ex-info (str kind)
                             {:seon.error/kind kind
                              :seon.error.malli/fn-sym 'seon.db/pull}))]
      (error/dev-eval!
        (fn []
          (is (true? (error/in-dev-eval?)))
          (testing "input-contract violations on a CORE fn → :agent (caller's fault)"
            (is (= :agent (si/wrapper-fault
                            (malli-e :seon.error.kind/malli-instrument-input) :core)))
            (is (= :agent (si/wrapper-fault
                            (malli-e :seon.error.kind/malli-instrument-arity) :core))))
          (testing "invalid OUTPUT stays :core — our fn broke; dev presence doesn't excuse it"
            (is (= :core (si/wrapper-fault
                           (malli-e :seon.error.kind/malli-instrument-output) :core))))
          (testing "a genuine internal core throw in dev scope stays :core"
            (is (= :core (si/wrapper-fault (js/Error. "internal core bug") :core))))))
      (is (false? (error/in-dev-eval?))
          "sync bracket closes synchronously — no scope leak into later tests"))))

(deftest ambient-error-scopes-propagate-without-cross-fiber-leaks
  ;; Both faces share AsyncLocalStorage: work spawned inside a scope inherits
  ;; it through async hops, but a second scope and the test's caller fiber do
  ;; not inherit one another. The retired process-global depth counters failed
  ;; every isolation assertion below while either Promise was pending.
  (async done
    (let [dev-p
          (error/dev-eval!
            (fn []
              (is (true? (error/in-dev-eval?)))
              (is (false? (error/expecting-a-core-fault?)))
              (js/Promise.
                (fn [resolve _]
                  (js/setTimeout
                    (fn []
                      (is (true? (error/in-dev-eval?))
                          "dev scope crosses the async hop it spawned")
                      (is (false? (error/expecting-a-core-fault?))
                          "the concurrent test scope does not leak into dev")
                      (resolve :dev))
                    10)))))
          expected-p
          (error/expecting-core-fault!
            (fn []
              (is (true? (error/expecting-a-core-fault?)))
              (is (false? (error/in-dev-eval?)))
              (js/Promise.
                (fn [resolve _]
                  (js/setTimeout
                    (fn []
                      (is (true? (error/expecting-a-core-fault?))
                          "expected-fault scope crosses its own async hop")
                      (is (false? (error/in-dev-eval?))
                          "the concurrent dev scope does not leak into the test")
                      (resolve :expected))
                    5)))))]
      (is (false? (error/in-dev-eval?))
          "the caller does not inherit a pending dev-eval scope")
      (is (false? (error/expecting-a-core-fault?))
          "the caller does not inherit a pending expected-fault scope")
      (-> (js/Promise.all #js [dev-p expected-p])
          (.then
            (fn [values]
              (is (= :dev (aget values 0)))
              (is (= :expected (aget values 1)))
              (is (false? (error/in-dev-eval?)))
              (is (false? (error/expecting-a-core-fault?)))
              (done))
            (fn [e]
              (is false (str "scope test rejected — " e))
              (done)))))))

(deftest dev-eval-settlement-records-agent-fault
  ;; R1 (2026-07-20): Bun drops AsyncLocalStorage inside the process
  ;; `unhandledRejection` listener, so a dev form whose value is a rejecting
  ;; Promise (`((fn ^:async …))` — the live pod-killer probe shape) lost the
  ;; dev-eval scope there and classified :core. The bracket now settles its
  ;; own returned Promise: the rejection records :agent IN-FIBER and never
  ;; reaches the process net.
  (async done
    (let [console-warn (.-warn js/console)]
      (set! (.-warn js/console) (fn [& _] nil))
      (with-captured-errors
        nil
        (fn [batches]
          (let [e (js/Error. "dev typo settles")
                value (error/dev-eval! (fn [] (js/Promise.reject e)))]
            (is (fn? (.-then value))
                "the bracket returns the form's own Promise unchanged")
            (-> (tick 50)
                (.then
                  (fn []
                    (is (= [:agent]
                           (mapv :seon.error/fault
                                 (captured-errors batches "dev typo settles")))
                        "an unrecorded dev-eval rejection records ONE :agent datom")
                    (is (true? (error/recorded? e))
                        "settlement tags the raw error for outer-funnel dedup"))))))
        (fn []
          (set! (.-warn js/console) console-warn)
          (done))))))

(deftest dev-eval-settlement-defers-to-a-wrapper-recorded-fault
  ;; ONE error → ONE datom: a rejection an instrumented wrapper arm already
  ;; recorded (e.g. a core fn's own output breach — the wrapper's :core datom)
  ;; must NOT gain a second :agent datom at the bracket.
  (async done
    (with-captured-errors
      nil
      (fn [batches]
        (let [e (js/Error. "already recorded upstream")]
          ;; A deliberately-provoked :core fixture — the EXPECTED marker, so
          ;; bin/test-cljs's unexpected-core gate does not count it.
          (error/expecting-core-fault!
            (fn [] (error/record! {:seon.error/raw e :seon.error/fault :core})))
          (error/dev-eval! (fn [] (js/Promise.reject e)))
          (-> (tick 50)
              (.then
                (fn []
                  (is (= [:core]
                         (mapv :seon.error/fault
                               (captured-errors batches
                                                "already recorded upstream")))
                      "exactly the wrapper's datom — settlement skipped it"))))))
      done)))

(deftest parse-frames-nodejs-stack
  (let [stack (str "Error: boom\n"
                   "    at myFn (/Users/x/seon/out/client/main.js:106:10)\n"
                   "    at /Users/x/seon/out/client/cljs-runtime/seon.eval.js:22:5\n")
        frames (error/parse-frames stack)]
    (is (vector? frames))
    (is (= 0 (:seon.error.frame/index (first frames))))
    (is (= "myFn" (:seon.error.frame/fn (first frames))))
    (is (= 106 (:seon.error.frame/line (first frames))))
    (is (= 10 (:seon.error.frame/column (first frames))))
    (testing "nil-valued slots are ABSENT (optional = absent)"
      (is (not (contains? (second frames) :seon.error.frame/fn))))
    (testing "garbage → nil, never a throw (absent ≠ nil: a stackless
              error never reaches the fn — callers some->)"
      (is (nil? (error/parse-frames "no frames here"))))))

(deftest parse-frames-drops-exception-info-construction-noise
  ;; The live Bun/V8 shape (captured 2026-07-20 on the default pod): the
  ;; ExceptionInfo stack opens inside its own constructor, with anonymous
  ;; cljs.core frames and the ex-info call before the real throw site.
  (let [stack (str "Error: probe boom\n"
                   "    at new cljs$core$ExceptionInfo (/x/cljs/core.cljs:11771:13)\n"
                   "    at undefined.<anonymous> (/x/cljs.core.js:37368:8)\n"
                   "    at undefined.<anonymous> (/x/cljs.core.js:37364:54)\n"
                   "    at undefined.cljs$core$ex_info (/x/cljs.core.js:37350:54)\n"
                   "    at undefined.<anonymous> (<eval>:2:53)\n"
                   "    at undefined.cljsEval (<eval>:5:3)\n")
        frames (error/parse-frames stack)]
    (is (vector? frames))
    (testing "the top frame is the throw site, not constructor noise"
      (is (= 0 (:seon.error.frame/index (first frames))))
      (is (= "<anonymous>" (:seon.error.frame/fn (first frames))))
      (is (= "<eval>" (:seon.error.frame/file (first frames))))
      (is (= 2 (:seon.error.frame/line (first frames)))))
    (testing "Bun's `undefined.` receiver prefix is stripped"
      (is (= "cljsEval" (:seon.error.frame/fn (second frames)))))
    (testing "no frame is the `at new …` parse garbage"
      (is (not-any? #(= "new" (:seon.error.frame/file %)) frames)))))

(deftest record-frames-come-from-the-deepest-cause
  ;; cljs.js wraps the original throw; the datom's frames must name the
  ;; ORIGINAL capture, not the wrapper's.
  (let [batches (atom [])]
    (try
      (install-capture-hooks! batches nil)
      (let [original (js/Error. "deep boom")
            _ (set! (.-stack original)
                    (str "Error: deep boom\n"
                         "    at deepThrowSite (/x/my.agent.js:10:5)\n"))
            wrapper (ex-info "ERROR" {} original)
            _ (set! (.-stack wrapper)
                    (str "Error: ERROR\n"
                         "    at new cljs$core$ExceptionInfo (/x/core.cljs:11771:13)\n"
                         "    at wrapLayer (/x/cljs/js.cljs:99:1)\n"))
            envelope (error/record! {:seon.error/raw wrapper
                                     :seon.error/fault :agent})]
        (is (= "deepThrowSite"
               (:seon.error.frame/fn (first (:seon.error/frames envelope))))
            "frames parse the deepest cause's stack — the real throw site"))
      (finally (clear-error-hooks!)))))

(defn- tick
  "Promise resolving after `ms` — lets a fire-and-forget persist settle."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- clear-error-hooks! []
  (error/set-db-hooks! {})
  nil)

(defn- install-capture-hooks! [batches branch-head]
  (error/set-db-hooks!
    {:seon.error/transact!
     (fn [tx-data]
       (swap! batches conj tx-data)
       (js/Promise.resolve {:seon.db/ok? true}))
     :seon.error/branch-head (constantly branch-head)}))

(defn- captured-errors [batches message]
  (filter #(= message (:seon.error/message %)) (mapcat identity @batches)))

(defn- with-captured-errors [branch-head body done]
  (let [batches (atom [])]
    (install-capture-hooks! batches branch-head)
    (-> (body batches)
        (.catch (fn [e] (is false (str "test chain rejected — " e))))
        (.finally (fn [] (clear-error-hooks!) (done))))))

(deftest record-returns-envelope-and-never-throws
  (let [batches (atom [])]
    (try
      (install-capture-hooks! batches nil)
      (let [env (error/record! {:seon.error/raw (js/Error. "recorded one")
                                :seon.error/fault :agent})]
        (is (= :agent (:seon.error/fault env)))
        (is (= "recorded one" (:seon.error/message env)))
        (is (= 1 (count (captured-errors batches "recorded one"))))
        (is (map? (error/record! {:seon.error/raw nil
                                  :seon.error/fault :agent}))
            "nil raw still yields an envelope"))
      (finally (clear-error-hooks!)))))

(deftest recorded-tag-dedup
  (let [batches (atom [])
        e (js/Error. "tag me")]
    (try
      (install-capture-hooks! batches nil)
      (is (false? (error/recorded? e)))
      (error/record! {:seon.error/raw e :seon.error/fault :agent})
      (is (true? (error/recorded? e))
          "record! tags the raw error so outer funnels skip it")
      (is (false? (error/recorded? nil)))
      (is (false? (error/recorded? "a string reason")))
      (finally (clear-error-hooks!)))))

(deftest record-persists-projection-with-complete-branch-head
  (let [batches (atom [])
        branch-head {:seon.db.branch/store-id (random-uuid)
                     :seon.db.branch/name :main
                     :seon.db.branch/commit-id (random-uuid)
                     :seon.db.branch/basis-t 42}]
    (try
      (install-capture-hooks! batches branch-head)
      (let [env (error/record! {:seon.error/raw (js/Error. "persisted one")
                                :seon.error/fault :agent})
            projection (first (captured-errors batches "persisted one"))]
        (is (= branch-head (error/recorded-branch-head env)))
        (is (= :agent (:seon.error/fault projection)))
        (is (seq (:seon.error/frames projection))
            "stack frames remain ordinary component transaction data"))
      (finally (clear-error-hooks!)))))

;; ---------------------------------------------------------------------------
;; The wrapper arms — async rejection + output violation become datoms.
;; ---------------------------------------------------------------------------

(defn- wrap
  "Instrument `f` through the injecting wrapper under symbol `sym`."
  [sym f]
  (m/-instrument-f (si/injecting-fschema [:=> [:cat :map] :map] sym)
                   {:report ei/report-fn} f nil))

(deftest async-rejection-arm-records-and-re-rejects
  (async done
    (with-captured-errors
      nil
      (fn [batches]
        (let [wrapped (wrap 'my.probe/reject-fn
                            (fn [_] (js/Promise.reject (js/Error. "reject-arm"))))]
          (-> (wrapped {})
              (.then (fn [_] (is false "must reject"))
                     (fn [e] (is (= "reject-arm" (.-message e))
                                 "caller sees the ORIGINAL rejection unchanged")))
              (.then (fn [] (tick 100)))
              (.then (fn []
                       (is (= [:agent]
                              (mapv :seon.error/fault
                                    (captured-errors batches "reject-arm")))
                           "one projection, my.* sym is agent-fault"))))))
      done)))

(defn- assert-args-edn-projection! [batches]
  (let [{args-edn :seon.error/args-edn
         data-edn :seon.error/data-edn}
        (first
          (filter :seon.error/args-edn (mapcat identity @batches)))]
    (is (= "[{:my.probe/arg 42}]" args-edn)
        "the FULL args vector persisted, read-string-able")
    (is (re-find #"malli-instrument-output" (str data-edn)))
    (is (not (re-find #"seon.error.malli/errors" (str data-edn)))
        "live-Schema explain leafs dropped from the projection")))

(deftest async-output-violation-records-with-full-args
  (async done
    (with-captured-errors
      nil
      (fn [batches]
        (let [wrapped (wrap 'my.probe/bad-output
                            (fn [_] (js/Promise.resolve :not-a-map)))]
          (-> (wrapped {:my.probe/arg 42})
              (.then (fn [_] (is false "must reject on output violation"))
                     (fn [e] (is (= ":malli.core/invalid-output" (.-message e)))))
              (.then (fn [] (tick 100)))
              (.then (fn [] (assert-args-edn-projection! batches))))))
      done)))

(deftest propagated-rejection-is-recorded-once-with-refined-fault
  (async done
    (with-captured-errors
      nil
      (fn [batches]
        ;; An agent-diagnostic error rejecting through TWO nested
        ;; core-population conduits (the seon.eval shape): the dedup tag
        ;; yields ONE datom, and wrapper-fault refines :core → :agent.
        (let [diag  (ex-info "propagated diag"
                             {:seon.eval/warning-type :undeclared-var})
              inner (wrap 'seon.probe/conduit-inner
                          (fn [_] (js/Promise.reject diag)))
              outer (wrap 'seon.probe/conduit-outer
                          (fn [m] (inner m)))]
          (-> (outer {})
              (.then (fn [_] (is false "must reject"))
                     (fn [e] (is (= "propagated diag" (.-message e)))))
              (.then (fn [] (tick 100)))
              (.then (fn []
                       (is (= [:agent]
                              (mapv :seon.error/fault
                                    (captured-errors batches
                                                     "propagated diag")))
                           "exactly one projection, refined to agent-fault"))))))
      done)))

;; ---------------------------------------------------------------------------
;; Persistence-hook isolation.
;; ---------------------------------------------------------------------------

(deftest partial-branch-head-hook-is-omitted-as-a-unit
  (try
    (error/set-db-hooks!
      {:seon.error/transact! (fn [_]
                               (js/Promise.resolve {:seon.db/ok? true}))
       :seon.error/branch-head
       (constantly {:seon.db.branch/store-id (random-uuid)
                    :seon.db.branch/basis-t 536870912})})
    (let [env (error/record! {:seon.error/raw (js/Error. "partial point")
                              :seon.error/fault :agent})]
      (is (= {}
             (select-keys env [:seon.error/store-id :seon.error/branch-name
                               :seon.error/commit-id :seon.error/basis-t]))
          "a malformed hook cannot create a partial persisted identity"))
    (finally
      (clear-error-hooks!))))

(deftest persist-recursion-fence-is-local-to-the-persist-fiber
  ;; A contract failure caused by the error-persistence write itself must not
  ;; recursively call the same write forever. Capture the deliberate marker so
  ;; this structural fixture does not trip bin/test-cljs's unexpected-core gate.
  (let [calls         (atom 0)
        console-error (.-error js/console)]
    (try
      (set! (.-error js/console) (fn [& _] nil))
      (error/set-db-hooks!
        {:seon.error/transact!
         (fn [_]
           (swap! calls inc)
           (error/record!
             {:seon.error/raw
              (ex-info "persist contract"
                       {:seon.error.malli/fn-sym 'seon.db/transact!})
              :seon.error/fault :agent})
           (js/Promise.resolve {:seon.db/ok? true}))
         :seon.error/branch-head (constantly nil)})
      (error/record! {:seon.error/raw (js/Error. "outer error")
                      :seon.error/fault :agent})
      (is (= 1 @calls)
          "the nested persist-contract error is console-only, not recursive")
      (finally
        (set! (.-error js/console) console-error)
        (clear-error-hooks!)))))

(deftest pending-persist-does-not-suppress-an-unrelated-fiber
  ;; While one error write is pending, another agent may independently hit a
  ;; transact!-shaped error. A process-global in-flight counter suppressed that
  ;; second error; the persist marker must belong only to the first async fiber.
  (async done
    (let [calls         (atom 0)
          resolve-first (atom nil)
          first-p       (js/Promise. (fn [resolve _]
                                       (reset! resolve-first resolve)))]
      (error/set-db-hooks!
        {:seon.error/transact!
         (fn [_]
           (if (= 1 (swap! calls inc))
             first-p
             (js/Promise.resolve {:seon.db/ok? true})))
         :seon.error/branch-head (constantly nil)})
      (error/record! {:seon.error/raw (js/Error. "first pending write")
                      :seon.error/fault :agent})
      (error/record!
        {:seon.error/raw
         (ex-info "unrelated transact error"
                  {:seon.error.malli/fn-sym 'seon.db/transact!})
         :seon.error/fault :agent})
      (is (= 2 @calls)
          "the unrelated fiber still attempts its own persistence write")
      (@resolve-first {:seon.db/ok? true})
      (-> (tick 0)
          (.then
            (fn []
              (clear-error-hooks!)
              (done))
            (fn [e]
              (clear-error-hooks!)
              (is false (str "persist-isolation cleanup rejected — " e))
              (done)))))))
