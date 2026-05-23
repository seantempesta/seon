(ns seon.test.runner
  "Phase 2 — test capture as data.

   `run-vars` runs a set of cljs.test vars and returns the events +
   summary as data, not stdout. `record-run!` transacts the run
   onto `:seon.test/*` entities so the warnings / recent-evals
   tiles can read test state via Datalog.

   Both fns map-in / map-out with registered Malli schemas. No
   globals; no install! mutation of `cljs.test/report` at the var
   root — instead we use `cljs.test`'s own per-call `:reporter`
   slot via `(empty-env ::seon.test.runner/capture)` and per-event
   defmethods that append to a volatile builder living in the
   env. The builder is fn-local; the API stays pure data-in,
   data-out.

   See docs/prds/agent-runtime/platform.md §Phase 2 for the
   roadmap context."
  (:require [cljs.test :as t]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]))

;; ============================================================
;; Schema — events, request, result, persisted test entity
;; ============================================================

;; cljs.test passes `:expected` and `:actual` as the raw values from
;; the assertion. Persisting them as strings (pr-str at the boundary)
;; gives us a fully-typed schema AND lets renderers display them
;; without further coercion. Structural query of inner values isn't
;; useful in test results; the loss is acceptable.
(schema/register! ::test-event
  [:map
   [:type     :keyword]
   [:expected {:optional true} :string]
   [:actual   {:optional true} :string]
   [:message  {:optional true} :string]
   [:file     {:optional true} :string]
   [:line     {:optional true} :int]
   [:var      {:optional true} :symbol]
   [:ns       {:optional true} :symbol]
   [:test     {:optional true} :int]
   [:pass     {:optional true} :int]
   [:fail     {:optional true} :int]
   [:error    {:optional true} :int]])

(schema/register! ::summary
  [:map
   [:test :int] [:pass :int] [:fail :int] [:error :int]])

(schema/register! ::vars [:vector :symbol])

(schema/register! ::run-request
  [:map [::vars ::vars]])

(schema/register! ::run-result
  [:map
   [::events  [:vector ::test-event]]
   [::summary ::summary]])

(schema/register! ::run-id :string)

;; record-run! takes a run-result + the run-id that stash-run! issued
;; (so the DB row points at the agent-ns stash). Conn is optional —
;; falls back on db's dynamic *conn*.
(schema/register! ::record-request
  [:map
   [::run-result ::run-result]
   [::run-id     ::run-id]
   [::db/conn    {:optional true} :seon.db/ref]])

;; The persisted test entity's schema. Datahike valueTypes live in
;; seon.client/agent-bootstrap-schema; the Malli shapes here let
;; db/transact!'s validation gate accept the tx.
;;
;; NOTE: the FULL event sequence is NOT a DB field. It lives in the
;; agent's ns (via stash-run! below) and the DB carries only the
;; minimal projection the warnings / recent-evals tiles render.
;; Per the user's design directive: "only put data in the database
;; schema that we want to surface in the agent's context."
(schema/register! :seon.test/sym :string)
(schema/register! :seon.test/last-passed-at :inst)
(schema/register! :seon.test/last-failed-at :inst)
(schema/register! :seon.test/last-failure-summary :string)
(schema/register! :seon.test/last-run-id :string)

;; ============================================================
;; Reporter — defmethods append to the env's volatile builder.
;;
;; The `report` multimethod dispatches on `[(:reporter env) :type]`.
;; We claim the `::capture` keyword. Every method scrapes the
;; testing-vars from the env so we know which var produced the event,
;; pr-str's the user-data fields (`:expected` / `:actual`) for
;; schema-friendly persistence, and conjs onto the builder. We also
;; call `t/inc-report-counter!` for :pass/:fail/:error so the env's
;; `:report-counters` accumulates correctly and the :summary event
;; cljs.test emits at end of run carries the right numbers.
;; ============================================================

(defn- builder! [] (::!builder (t/get-current-env)))

(defn- current-var-sym
  "First testing-var in the env's stack (innermost). nil outside a
   var's execution (e.g. :begin-test-ns). The 'testing-var' shape we
   push is a #js {:sym <symbol>} — minimal stand-in for cljs.test's
   Var since self-host can't synthesize real Var instances."
  []
  (when-let [v (first (:testing-vars (t/get-current-env)))]
    (unchecked-get v "sym")))

(defn- current-ns-sym
  "ns of the innermost testing-var, or the env's :testing-ns."
  []
  (or (some-> (current-var-sym) namespace symbol)
      (:testing-ns (t/get-current-env))))

(defn- safe-prstr [v]
  (try (pr-str v)
       (catch :default e
         (str "<unprintable: " (error/->message e) ">"))))

(defn- record-assertion! [event-map kind]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type kind}
                    (contains? event-map :message)  (assoc :message (:message event-map))
                    (contains? event-map :expected) (assoc :expected (safe-prstr (:expected event-map)))
                    (contains? event-map :actual)   (assoc :actual (safe-prstr (:actual event-map)))
                    (contains? event-map :file)     (assoc :file (:file event-map))
                    (contains? event-map :line)     (assoc :line (:line event-map))
                    (current-var-sym)               (assoc :var (current-var-sym))
                    (current-ns-sym)                (assoc :ns  (current-ns-sym))))))

(defmethod t/report [::capture :pass] [m]
  (record-assertion! m :pass)
  (t/inc-report-counter! :pass))

(defmethod t/report [::capture :fail] [m]
  (record-assertion! m :fail)
  (t/inc-report-counter! :fail))

(defmethod t/report [::capture :error] [m]
  (record-assertion! m :error)
  (t/inc-report-counter! :error))

(defmethod t/report [::capture :summary] [m]
  (some-> (builder!)
          (vswap! conj!
                  {:type  :summary
                   :test  (or (:test m) 0)
                   :pass  (or (:pass m) 0)
                   :fail  (or (:fail m) 0)
                   :error (or (:error m) 0)})))

(defmethod t/report [::capture :begin-test-ns] [m]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type :begin-test-ns}
                    (:ns m) (assoc :ns (.-name (:ns m)))))))

(defmethod t/report [::capture :end-test-ns] [m]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type :end-test-ns}
                    (:ns m) (assoc :ns (.-name (:ns m)))))))

(defmethod t/report [::capture :begin-test-var] [m]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type :begin-test-var}
                    (current-var-sym) (assoc :var (current-var-sym))
                    (current-ns-sym)  (assoc :ns (current-ns-sym))))))

(defmethod t/report [::capture :end-test-var] [m]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type :end-test-var}
                    (current-var-sym) (assoc :var (current-var-sym))
                    (current-ns-sym)  (assoc :ns (current-ns-sym))))))

;; Silently drop events we don't care about (`:begin-test-vars`,
;; `:end-test-vars`, async lifecycle events). The default dispatch
;; covers them with a no-op since we don't `defmethod` for them.

;; ============================================================
;; Public surface — pure functions
;; ============================================================

(defn- resolve-test-fn
  "Look up a fully-qualified symbol's compiled fn via the munged
   globalThis path — the same strategy `seon.eval/truly-undeclared?`
   uses. Returns nil if the symbol doesn't resolve.

   cljs.test/test-vars wants `Var` instances, which self-hosted CLJS
   can't synthesize from runtime symbol values. We sidestep that by
   driving the test fn directly: every `(is …)` inside the body still
   calls `cljs.test/do-report`, which dispatches through our `::capture`
   defmethods — so the captured events come out the same shape as a
   `t/test-vars` run minus the env's :testing-vars stack."
  [sym]
  (let [ns-part (some-> (namespace sym) (cljs.core/munge))
        nm-part (cljs.core/munge (name sym))]
    (when (and ns-part nm-part)
      (try
        (js/goog.getObjectByName (str ns-part "." nm-part))
        (catch :default _ nil)))))

(defn run-vars
  "Run the given fully-qualified test-var symbols, return events +
   summary as data.

   Reporter slot is bound to `::capture`; the volatile builder
   lives in the env under `::!builder` so the per-event defmethods
   close-over nothing. After the run, the builder is reified to an
   immutable vector and returned.

   Vars that fail to resolve (typo, ns not loaded) emit a synthetic
   `:error` event; the rest of the batch runs normally."
  {:malli/schema [:=> [:cat ::run-request] ::run-result]}
  [{::keys [vars]}]
  (let [!builder (volatile! (transient []))
        env      (-> (t/empty-env ::capture)
                     (assoc ::!builder !builder
                            :report-counters {:test 0 :pass 0 :fail 0 :error 0}))
        resolved (for [sym vars]
                   {:sym sym :fn (resolve-test-fn sym)})
        ;; Unresolved vars get a synthetic :error and bump the
        ;; :error counter, so the eventual :summary reflects reality.
        missing  (filter (complement :fn) resolved)
        present  (filter :fn resolved)]
    (binding [t/*current-env* env]
      ;; First flush :error events for unresolved symbols.
      (doseq [{:keys [sym]} missing]
        (t/update-current-env! [:report-counters :test] inc)
        (t/do-report {:type :error
                      :message (str "Unresolved test var: " sym)
                      :expected sym
                      :actual nil}))
      ;; Then drive each resolved fn with begin/end-test-var bracketing
      ;; so :testing-vars carries the right symbol when the body's
      ;; (is …) calls go through do-report → our defmethods.
      (doseq [{:keys [sym fn]} present]
        (t/update-current-env! [:testing-vars]
                               conj #js {:sym sym})
        (t/update-current-env! [:report-counters :test] inc)
        (t/do-report {:type :begin-test-var :var #js {:sym sym}})
        (try
          (fn)
          (catch :default e
            (t/do-report {:type :error
                          :message "Uncaught exception, not in assertion."
                          :expected nil
                          :actual e})))
        (t/do-report {:type :end-test-var :var #js {:sym sym}})
        (t/update-current-env! [:testing-vars] rest))
      ;; Emit a final :summary so renderers don't have to recompute.
      (let [counters (:report-counters (t/get-current-env))]
        (t/do-report (assoc counters :type :summary))))
    (let [events  (persistent! @!builder)
          summary (or (->> events (filter #(= :summary (:type %))) last)
                      ;; Last-resort fallback — shouldn't fire given
                      ;; the explicit :summary above.
                      {:test (count vars) :pass 0 :fail 0
                       :error (count missing)})]
      {::events events ::summary summary})))

;; ============================================================
;; Stash — full run-result lives in the agent's ns, NOT the DB.
;; The DB carries a pointer (:seon.test/last-run-id) the agent uses
;; to fetch the blob via the existing (result <id>) helper that
;; setup-agent-ns! defines.
;; ============================================================

(def ^:private stash-key-prefix "__seon_test_run_")

(defn- new-run-id
  "10-char base62 id — same shape as eval-id. Local copy to avoid a
   require cycle with seon.eval (test.runner shouldn't depend on the
   eval namespace; both stash on globalThis under different prefixes)."
  []
  (let [alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"]
    (apply str (repeatedly 10 #(nth alphabet (rand-int 62))))))

(defn stash-run!
  "Stash the full run-result on globalThis keyed by a fresh run-id.
   Returns the run-id. The agent reaches the blob through the
   `(result <run-id>)` helper that `seon.eval/setup-agent-ns!` wires
   into the agent's home ns — which reads any `__seon_results_*` /
   `__seon_test_run_*` key off globalThis.

   Storing on globalThis (instead of the DB) is deliberate: huge
   event sequences would balloon datahike's transit cost on every
   read, and the agent typically only needs the latest run + a small
   surfaced summary. The DB row points here via `:seon.test/last-run-id`."
  {:malli/schema [:=> [:cat [:map [::run-result ::run-result]]]
                  ::run-id]}
  [{::keys [run-result]}]
  (let [id (new-run-id)]
    (try
      (js/Reflect.set js/globalThis (str stash-key-prefix id) run-result)
      (catch :default e
        (js/console.warn "[seon.test.runner/stash-run!] failed —"
                         (error/->message e))))
    id))

(defn fetch-run
  "Look up a stashed run-result by id. Returns nil if absent."
  [run-id]
  (js/Reflect.get js/globalThis (str stash-key-prefix run-id)))

;; ============================================================
;; Record — minimal projection to the DB. NEVER transacts events.
;; ============================================================

(def ^:private failure-summary-cap
  "Truncation cap for :seon.test/last-failure-summary. Keeps the
   warnings tile renderable; the full event is on the agent's ns
   via the run-id stash."
  200)

(defn- failure-summary
  "Build a short renderable string from the last :fail/:error event."
  [evt]
  (let [src (cond
              (:message evt)  (str (:message evt))
              :else           (str (when (:expected evt)
                                     (str "expected " (:expected evt) " "))
                                   (when (:actual evt)
                                     (str "actual " (:actual evt)))))]
    (if (> (count src) failure-summary-cap)
      (str (subs src 0 failure-summary-cap) "…")
      src)))

(defn ^:async record-run!
  "Transact the SURFACED projection for each test in `run-result`.
   The full data is NOT here — `stash-run!` put it on globalThis;
   the row carries `:seon.test/last-run-id` so the agent can fetch
   the blob.

   Per-var DB fields:
     :seon.test/sym                    — \"agent.ns/my-test\"
     :seon.test/last-passed-at         — when the var most recently passed
     :seon.test/last-failed-at         — when the var most recently failed
     :seon.test/last-failure-summary   — ≤200-char rendered failure (truncated)
     :seon.test/last-run-id            — pointer to the full blob in the agent ns

   Returns the `db/transact!` Promise's value."
  {:malli/schema [:=> [:cat ::record-request] :any]}
  [{::keys [run-result run-id conn]}]
  (let [now     (js/Date.)
        events  (::events run-result)
        per-var (group-by :var (filter #(some? (:var %)) events))
        tx-data
        (vec
          (for [[var-sym evts] per-var
                :let [outcome (last (filter #(#{:pass :fail :error} (:type %)) evts))]
                :when outcome
                :let [failed? (#{:fail :error} (:type outcome))]]
            (cond-> {:seon.test/sym         (str var-sym)
                     :seon.test/last-run-id run-id}
              failed?       (assoc :seon.test/last-failed-at        now
                                   :seon.test/last-failure-summary  (failure-summary outcome))
              (not failed?) (assoc :seon.test/last-passed-at now))))]
    (if (seq tx-data)
      (await (db/transact!
               (cond-> {:seon.db/tx-data tx-data}
                 conn (assoc :seon.db/conn conn))))
      {:seon.db/ok? true :seon.db/tx-report {:tx-data []}})))

(defn ^:async run-and-record!
  "Convenience: run vars, stash the full result, record the projection.
   Returns `{::run-id ::run-result ::tx-report}`. This is the surface
   the agent's eval-batch will typically call after a `(defn …)` that
   touches a `:seon.fn` — see spec §D4 (targeted test auto-run)."
  {:malli/schema [:=> [:cat ::run-request] :any]}
  [{::keys [vars]}]
  (let [result    (run-vars {::vars vars})
        run-id    (stash-run! {::run-result result})
        tx-report (await (record-run! {::run-result result ::run-id run-id}))]
    {::run-id     run-id
     ::run-result result
     ::tx-report  tx-report}))
