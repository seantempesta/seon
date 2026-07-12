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
    [datahike.api :as d]
    [malli.core :as m]
    [seon.db :as db]
    [seon.error :as error]
    [seon.error.instrument :as ei]
    [seon.instrument :as si]))

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

(deftest dev-eval-bracket-covers-promise-settlement
  ;; The dev-eval scope must stay open across the async hop (CLJS binding
  ;; would not) AND through the settle tick — the close is DEFERRED one
  ;; macrotask so the end-of-tick unhandledRejection net still observes it.
  (async done
    (let [p (error/dev-eval!
              (fn [] (js/Promise. (fn [resolve _]
                                    (js/setTimeout #(resolve :ok) 10)))))]
      (is (true? (error/in-dev-eval?)) "scope open while the Promise is pending")
      (-> p
          (.then (fn [v]
                   (is (= :ok v) "the resolved value passes through unchanged")
                   (is (true? (error/in-dev-eval?))
                       "same-tick observers (the net) still see the scope at settle")
                   (js/Promise. (fn [resolve _] (js/setTimeout resolve 0)))))
          (.then (fn []
                   (is (false? (error/in-dev-eval?))
                       "closed after the deferred macrotask")
                   (done)))))))

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

(deftest record-returns-envelope-and-never-throws
  ;; No conn bound in this test process yet — record! must still return the
  ;; envelope (the projection lands in the in-memory buffer, flushed by the
  ;; next conn-backed record!).
  (let [env (error/record! {:seon.error/raw (js/Error. "buffered one")
                            :seon.error/fault :agent})]
    (is (= :agent (:seon.error/fault env)))
    (is (= "buffered one" (:seon.error/message env)))
    (is (map? (error/record! {:seon.error/raw nil :seon.error/fault :agent}))
        "nil raw still yields an envelope")))

(deftest recorded-tag-dedup
  (let [e (js/Error. "tag me")]
    (is (false? (error/recorded? e)))
    (error/record! {:seon.error/raw e :seon.error/fault :agent})
    (is (true? (error/recorded? e))
        "record! tags the raw error so outer funnels skip it")
    (is (false? (error/recorded? nil)))
    (is (false? (error/recorded? "a string reason")))))

;; ---------------------------------------------------------------------------
;; Persistence — fresh :memory conn, root set! of db/*conn* (CLJS has no
;; binding across awaits; the persist hook closes over the var root).
;; ---------------------------------------------------------------------------

(defn- fresh-conn
  "Fresh :memory datahike conn (history ON — as-of needs the temporal
   index). Returns a Promise of the conn."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(defn- tick
  "Promise resolving after `ms` — lets a fire-and-forget persist settle."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- with-fresh-conn
  "Run `f` (conn → Promise) with db/*conn* set! to a fresh conn; restore
   the prior root either way, then `done`."
  [f done]
  (let [prior db/*conn*
        finish (fn [] (set! db/*conn* prior) (done))]
    (-> (fresh-conn)
        (.then (fn [conn] (set! db/*conn* conn) (f conn)))
        (.catch (fn [e] (is false (str "test chain threw/rejected — " e))))
        (.then finish))))

(defn- assert-persisted-row!
  "Assertions over the row `record-persists…` wrote — split out to keep
   the async chain shallow."
  [env]
  (let [row (first (db/query {:seon.db/query
                              '[:find ?e ?at
                                :where
                                [?e :seon.error/message "persisted one"]
                                [?e :seon.error/fault :agent]
                                [?e :seon.error/at ?at]]}))
        eid (first row)
        at  (second row)]
    (is (some? row) "the datom projection landed")
    (is (= at (:seon.error/at env)))
    (testing "frames are reified component entities"
      (is (pos? (count (db/query {:seon.db/query
                                  '[:find ?f
                                    :in $ ?e
                                    :where [?e :seon.error/frames ?f]]
                                  :seon.db/args [eid]})))))
    (testing "as-of the stored :seon.error/at = the db the failing code saw"
      (is (nil? (db/pull {:seon.db/db (db/as-of at)
                          :seon.db/pull-pattern '[:seon.error/message]
                          :seon.db/ref eid}))))
    (testing "the earlier no-conn record! was buffered and FLUSHED here"
      (is (some? (db/query {:seon.db/query
                            '[:find ?e .
                              :where [?e :seon.error/message "buffered one"]]}))))))

(deftest query-missing-attr-throw-classifies-agent
  ;; The REAL seon.db/query typo throw (flat :user-input ex-data after
  ;; C43) through the classifier — the concrete agent-mistake path that
  ;; used to pre-build a nested envelope.
  (async done
    (with-fresh-conn
      (fn [_conn]
        (try
          (db/query '[:find ?e :where [?e :no.such.attr/typo ?v]])
          (is false "the typo guard must throw")
          (catch :default e
            (is (= :user-input (:seon.error/kind (ex-data e)))
                "kind is FLAT in ex-data")
            (is (= [:no.such.attr/typo] (:seon.db/missing-attrs (ex-data e))))
            (is (= :agent (si/wrapper-fault e :core))
                "a mistyped query attr is the AGENT's mistake")
            (is (= :user-input
                   (:seon.error/kind (error/->map e)))
                "the envelope carries the kind at the TOP (C45 lift)")))
        (js/Promise.resolve nil))
      done)))

(deftest record-persists-fault-at-frames-and-buffer-flush
  (async done
    (with-fresh-conn
      (fn [_conn]
        (let [env (error/record! {:seon.error/raw (js/Error. "persisted one")
                                  :seon.error/fault :agent})]
          (is (= :agent (:seon.error/fault env)))
          (is (int? (:seon.error/at env)) "basis-t stamped when a conn is live")
          (-> (tick 100)
              (.then (fn [] (assert-persisted-row! env))))))
      done)))

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
    (with-fresh-conn
      (fn [_conn]
        (let [wrapped (wrap 'my.probe/reject-fn
                            (fn [_] (js/Promise.reject (js/Error. "reject-arm"))))]
          (-> (wrapped {})
              (.then (fn [_] (is false "must reject"))
                     (fn [e] (is (= "reject-arm" (.-message e))
                                 "caller sees the ORIGINAL rejection unchanged")))
              (.then (fn [] (tick 100)))
              (.then (fn []
                       (is (= #{[:agent]}
                              (db/query {:seon.db/query
                                         '[:find ?fault
                                           :where
                                           [?e :seon.error/message "reject-arm"]
                                           [?e :seon.error/fault ?fault]]}))
                           "ONE datom, my.* sym → :agent"))))))
      done)))

(defn- assert-args-edn-row! []
  (let [row (first (db/query {:seon.db/query
                              '[:find ?args ?data
                                :where
                                [?e :seon.error/args-edn ?args]
                                [?e :seon.error/data-edn ?data]
                                [?e :seon.error/fault :agent]]}))
        args-edn (first row)
        data-edn (second row)]
    (is (= "[{:my.probe/arg 42}]" args-edn)
        "the FULL args vector persisted, read-string-able")
    (is (re-find #"malli-instrument-output" (str data-edn)))
    (is (not (re-find #"seon.error.malli/errors" (str data-edn)))
        "live-Schema explain leafs dropped from the projection")))

(deftest async-output-violation-records-with-full-args
  (async done
    (with-fresh-conn
      (fn [_conn]
        (let [wrapped (wrap 'my.probe/bad-output
                            (fn [_] (js/Promise.resolve :not-a-map)))]
          (-> (wrapped {:my.probe/arg 42})
              (.then (fn [_] (is false "must reject on output violation"))
                     (fn [e] (is (= ":malli.core/invalid-output" (.-message e)))))
              (.then (fn [] (tick 100)))
              (.then (fn [] (assert-args-edn-row!))))))
      done)))

(deftest propagated-rejection-is-recorded-once-with-refined-fault
  (async done
    (with-fresh-conn
      (fn [_conn]
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
                       (is (= #{[:agent]}
                              (db/query {:seon.db/query
                                         '[:find ?fault
                                           :where
                                           [?e :seon.error/message "propagated diag"]
                                           [?e :seon.error/fault ?fault]]}))
                           "exactly ONE datom, refined to :agent"))))))
      done)))
