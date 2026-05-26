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
  (:refer-clojure :exclude [run!])
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
(schema/register! ::ns   :symbol)
(schema/register! ::record? :boolean)
(schema/register! ::trigger
  [:enum ::manual ::on-fn-redef ::pre-victory ::cli])

(schema/register! ::run-id :string)

(schema/register! ::recorded-syms [:vector :string])

;; ::selector — exactly one of (::vars ::ns) for Phase 1. Future selectors
;; (::nses ::all? ::failed-only? ::tag) added in later phases — schema
;; only carries Phase 1 keys today to keep instrumentation honest.
(schema/register! ::selector
  [:and
   [:map
    [::vars {:optional true} ::vars]
    [::ns   {:optional true} ::ns]]
   [:fn {:error/message "exactly one selector key (::vars or ::ns) required"}
    (fn [m] (= 1 (count (filter #(some? (get m %)) [::vars ::ns]))))]])

(schema/register! ::run-request
  [:and
   ::selector
   [:map
    [::record? {:optional true} ::record?]
    [::trigger {:optional true} ::trigger]
    [::db/conn {:optional true} :seon.db/ref]]])

(schema/register! ::run-result
  [:map
   [::events         [:vector ::test-event]]
   [::summary        ::summary]
   [::run-id         {:optional true} ::run-id]
   [::selected-vars  {:optional true} ::vars]
   [::recorded?      {:optional true} :boolean]
   [::recorded-syms  {:optional true} ::recorded-syms]
   [::trigger        {:optional true} ::trigger]])

(schema/register! ::last-result-response
  [:maybe [:map
           [::run-id ::run-id]
           [::run-result ::run-result]]])

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
(schema/register! :seon.test/sym [:string {:seon.db/identity true}])
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

(defn- record-assertion!
  "Append an assertion event to the builder. cljs.test's report map carries
   `:message nil`, `:file nil`, `:line nil` for assertions that didn't
   supply those — only include the key when the VALUE is non-nil so the
   resulting event map satisfies `::test-event`'s string/int schemas. The
   `:expected`/`:actual` pair is always present (cljs.test populates them
   from the assertion sexpr) and we coerce to string via `safe-prstr`."
  [event-map kind]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type kind}
                    (some? (:message event-map))  (assoc :message (:message event-map))
                    (contains? event-map :expected) (assoc :expected (safe-prstr (:expected event-map)))
                    (contains? event-map :actual)   (assoc :actual (safe-prstr (:actual event-map)))
                    (some? (:file event-map))     (assoc :file (:file event-map))
                    (some? (:line event-map))     (assoc :line (:line event-map))
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
  "Look up a fully-qualified test sym and return its :test body fn.

   `deftest` defines two things on the var:
     - the public fn (`foo-test`) whose body is `(test-var <var>)`.
     - the test body itself, stored as `:test` meta on the Var (which
       cljs.test sticks under the public fn as `cljs$lang$var`).

   We want the BODY fn (`:test`), not the public fn — invoking the
   public fn re-enters `test-var`'s bracketing (begin/end-test-var,
   :test counter), which double-counts when we also bracket here.
   Driving `:test` directly also lets us await the IAsyncTest CPS
   object that `(async done …)` returns, instead of having
   `run-block` swallow it.

   Returns nil if the symbol doesn't resolve."
  [sym]
  (let [ns-part (some-> (namespace sym) (cljs.core/munge))
        nm-part (cljs.core/munge (name sym))]
    (when (and ns-part nm-part)
      (let [obj (try (js/goog.getObjectByName (str ns-part "." nm-part))
                     (catch :default _ nil))
            v   (when obj (unchecked-get obj "cljs$lang$var"))]
        ;; Prefer the :test body off the Var; fall back to the raw fn
        ;; for non-deftest callables that the caller asked us to run.
        (or (some-> v meta :test)
            obj)))))

(defn- thenable?
  "Truthy if v looks like a Promise/A+: has a callable `.then` slot."
  [v]
  (and (some? v)
       (or (instance? js/Promise v)
           (and (object? v) (fn? (unchecked-get v "then"))))))

(defn- drive-test-fn!
  "Invoke the test fn `f`; return a Promise that resolves when the test
   body (sync or async) has finished firing its `(is …)` events.

   Two async paths supported:
   1. The fn returns a thenable (a `^:async` fn returning a Promise) →
      we await it.
   2. The fn returns an `IAsyncTest`-implementing CPS continuation (the
      shape `(async done …)` produces in cljs.test). We invoke it with
      a `done` callback that resolves a fresh Promise.

   Anything else is treated as a sync test — the Promise resolves
   immediately. Uncaught exceptions inside `f` are caught and reported
   as `:error` events; the promise still resolves so the batch
   continues."
  [sym f]
  (js/Promise.
    (fn [resolve _reject]
      (let [obj (try (f)
                     (catch :default e
                       (t/do-report {:type :error
                                     :message "Uncaught exception, not in assertion."
                                     :expected nil
                                     :actual e})
                       ::sync-error))]
        (cond
          (t/async? obj)
          ;; cljs.test IAsyncTest: invoke with continuation = resolve.
          (try (obj resolve)
               (catch :default e
                 (t/do-report {:type :error
                               :message "Uncaught exception in async body."
                               :expected nil
                               :actual e})
                 (resolve nil)))

          (thenable? obj)
          (-> obj
              (.then (fn [_] (resolve nil)))
              (.catch (fn [e]
                        (t/do-report {:type :error
                                      :message "Uncaught exception in async body."
                                      :expected nil
                                      :actual e})
                        (resolve nil))))

          :else (resolve nil))))))

(defn- lookup-fixtures
  "Return the vector of fixture maps registered by `cljs.test/use-fixtures`
   for the given ns symbol + kind (`:once` or `:each`).

   The `use-fixtures` macro defs `cljs-test-once-fixtures` /
   `cljs-test-each-fixtures` (Var-less in our self-host path — they're
   plain vars on the ns object). We fetch via the munged ns object on
   globalThis. Returns nil when no fixture of that kind was registered.

   Only the MAP fixture form (`{:before fn :after fn}`) is supported.
   The fn-wrapping form (`(defn my-fixture [f] … (f) …)`) is
   incompatible with async tests per cljs.test docs and irrelevant for
   our seon-authored tests."
  [ns-sym kind]
  (let [sym  (case kind
               :once "cljs_test_once_fixtures"
               :each "cljs_test_each_fixtures")
        path (str (cljs.core/munge (str ns-sym)) "." sym)
        v    (try (js/goog.getObjectByName path)
                  (catch :default _ nil))]
    (when (and v (sequential? v))
      (vec v))))

(defn- await-maybe-thenable
  "Return a Promise that resolves when `v` (a fixture's return value)
   has settled. Sync fixtures return a non-thenable → resolved immediately."
  [v]
  (if (thenable? v)
    (js/Promise.resolve v)
    (js/Promise.resolve nil)))

(defn- run-fixture-fn!
  "Invoke a single fixture map's slot (`:before` or `:after`) if present.
   Returns a Promise that resolves after the slot has finished (sync or
   async). Errors inside the fixture surface as `:error` events and the
   promise still resolves so the batch continues."
  [fixture-map slot ns-sym]
  (js/Promise.
    (fn [resolve _reject]
      (if-let [f (get fixture-map slot)]
        (let [r (try (f)
                     (catch :default e
                       (t/do-report {:type :error
                                     :message (str "Uncaught exception in "
                                                   ns-sym " " slot " fixture.")
                                     :expected nil
                                     :actual e})
                       ::fixture-error))]
          (if (and (not= r ::fixture-error) (thenable? r))
            (-> (await-maybe-thenable r)
                (.then (fn [_] (resolve nil)))
                (.catch (fn [e]
                          (t/do-report {:type :error
                                        :message (str "Async fixture rejected in "
                                                      ns-sym " " slot ".")
                                        :expected nil
                                        :actual e})
                          (resolve nil))))
            (resolve nil)))
        (resolve nil)))))

(defn ^:async run-vars
  "Run the given fully-qualified test-var symbols, return events +
   summary as data.

   Reporter slot is bound to `::capture`; the volatile builder
   lives in the env under `::!builder` so the per-event defmethods
   close-over nothing. After the run, the builder is reified to an
   immutable vector and returned.

   Fixtures: vars are grouped by ns; for each ns we look up the
   `cljs-test-once-fixtures` and `cljs-test-each-fixtures` vectors
   that `use-fixtures` registered (see `lookup-fixtures`) and walk
   them per the cljs.test contract — `:once :before` once for the ns,
   then for each test var `:each :before` → body → `:each :after`,
   finally `:once :after`. We can't route through `cljs.test/test-vars`
   because that requires real `Var` instances which self-host CLJS
   can't synthesize; we replicate the fixture-walk ourselves and keep
   the synthetic `#js {:sym sym}` testing-var stand-in.

   `^:async` since 2026-05-25: the body awaits each test fn so that
   `(async done …)` tests AND `^:async` (Promise-returning) test bodies
   complete before the summary is emitted. Sync tests run synchronously
   through the same path (the wrapper Promise resolves on the same tick).

   Vars that fail to resolve (typo, ns not loaded) emit a synthetic
   `:error` event; the rest of the batch runs normally."
  {:malli/schema [:=> [:cat [:map [::vars ::vars]]] ::run-result]}
  [{::keys [vars]}]
  (let [!builder (volatile! (transient []))
        env      (-> (t/empty-env ::capture)
                     (assoc ::!builder !builder
                            :report-counters {:test 0 :pass 0 :fail 0 :error 0}))
        resolved (for [sym vars]
                   {:sym sym :fn (resolve-test-fn sym)
                    :ns (when (namespace sym) (symbol (namespace sym)))})
        missing  (filter (complement :fn) resolved)
        present  (filter :fn resolved)
        ;; Stable per-ns groupings preserve the input ordering of vars
        ;; within a ns (group-by is stable in Clojure).
        by-ns    (group-by :ns present)]
    (binding [t/*current-env* env]
      (doseq [{:keys [sym]} missing]
        (t/update-current-env! [:report-counters :test] inc)
        (t/do-report {:type :error
                      :message (str "Unresolved test var: " sym)
                      :expected sym
                      :actual nil}))
      (doseq [[ns-sym ns-vars] by-ns]
        (let [once-fxs (lookup-fixtures ns-sym :once)
              each-fxs (lookup-fixtures ns-sym :each)]
          ;; :once :before — once per ns, in registration order.
          (doseq [fx once-fxs]
            (await (run-fixture-fn! fx :before ns-sym)))
          (doseq [{:keys [sym fn]} ns-vars]
            ;; :each :before — registration order, before each test.
            (doseq [fx each-fxs]
              (await (run-fixture-fn! fx :before ns-sym)))
            (t/update-current-env! [:testing-vars]
                                   conj #js {:sym sym})
            (t/update-current-env! [:report-counters :test] inc)
            (t/do-report {:type :begin-test-var :var #js {:sym sym}})
            (await (drive-test-fn! sym fn))
            (t/do-report {:type :end-test-var :var #js {:sym sym}})
            (t/update-current-env! [:testing-vars] rest)
            ;; :each :after — REVERSE registration order, after each test
            ;; (matches cljs.test's wrap-map-fixtures, which `reverse`s :after).
            (doseq [fx (reverse each-fxs)]
              (await (run-fixture-fn! fx :after ns-sym))))
          ;; :once :after — reverse order, after all vars in the ns.
          (doseq [fx (reverse once-fxs)]
            (await (run-fixture-fn! fx :after ns-sym)))))
      (let [counters (:report-counters (t/get-current-env))]
        (t/do-report (assoc counters :type :summary))))
    (let [events  (persistent! @!builder)
          summary (or (->> events (filter #(= :summary (:type %))) last)
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
  {:malli/schema [:=> [:cat [:map [::vars ::vars]]] :any]}
  [{::keys [vars]}]
  (let [result    (await (run-vars {::vars vars}))
        run-id    (stash-run! {::run-result result})
        tx-report (await (record-run! {::run-result result ::run-id run-id}))]
    {::run-id     run-id
     ::run-result result
     ::tx-report  tx-report}))

;; ============================================================
;; Phase 1 universal entrypoints — vars-in-ns, run!, run-ns!, last-result
;; ============================================================

(defn vars-in-ns
  "Return the vector of fully-qualified test-var symbols defined in
   `ns-sym`.

   IMPLEMENTATION NOTE (per phase-1-probe-results-2026-05-25.md probe 1):
   the analyzer's `ns-interns` projection strips `:test` meta under
   self-host, AND in the compiled-CLJS pod context `cljs.analyzer.api`
   needs a compile-state arg we don't have. Both reasons push us to
   runtime introspection.

   cljs.test's `deftest` macro sets `cljs$lang$test` on the emitted fn
   object (and stashes the full Var, with intact :test meta, under
   `cljs$lang$var`). We walk the munged ns object on globalThis, pick
   out properties whose value has the `cljs$lang$test` marker, and
   reconstruct the FQ sym via the var's own :ns / :name meta when
   present (falling back to demunging the property key)."
  {:malli/schema [:=> [:cat [:map [::ns ::ns]]] ::vars]}
  [{::keys [ns]}]
  (let [ns-munged (cljs.core/munge (str ns))
        ns-obj    (try (js/goog.getObjectByName ns-munged)
                       (catch :default _ nil))]
    (if-not ns-obj
      []
      (let [ks (js-keys ns-obj)
            out (transient [])]
        (dotimes [i (alength ks)]
          (let [k     (aget ks i)
                v     (unchecked-get ns-obj k)
                test? (and (some? v) (unchecked-get v "cljs$lang$test"))]
            (when test?
              (let [var-obj (unchecked-get v "cljs$lang$var")
                    fq      (if (and var-obj (meta var-obj))
                              (let [m (meta var-obj)]
                                (symbol (str (:ns m)) (str (:name m))))
                              (symbol (str ns) (cljs.core/demunge k)))]
                (conj! out fq)))))
        (persistent! out)))))

(defn- resolve-selector
  "Resolve a ::selector to the concrete vector of FQ test-var symbols.
   Phase 1: handles ::vars (pass-through) and ::ns (vars-in-ns lookup).
   Future phases add ::nses, ::all?, ::failed-only?, ::tag here."
  [{::keys [vars ns] :as req}]
  (cond
    vars vars
    ns   (vars-in-ns {::ns ns})
    :else
    (throw (ex-info "No selector — expected ::vars or ::ns"
                    {:type ::no-selector :request req}))))

(defn ^:async run!
  "Universal entrypoint. Resolves the selector to a vector of FQ test
   symbols, runs them via `run-vars`, optionally records the projection
   to the DB.

   Phase 1 selectors: exactly one of `::vars` or `::ns` must be present.

   Examples:
     ;; one var
     (run! {::vars '[seon.db-test/transact!-round-trips-an-entity]})
     ;; one ns
     (run! {::ns 'seon.db-test ::record? true})

   Returns a fully-populated `::run-result` with `::selected-vars`,
   `::run-id` / `::recorded?` / `::recorded-syms` when `::record? true`,
   and `::trigger` propagated through."
  {:malli/schema [:=> [:cat ::run-request] ::run-result]}
  [{::keys [record? trigger] :as req}]
  (let [selected (vec (resolve-selector req))
        result   (await (run-vars {::vars selected}))
        base     (cond-> (assoc result ::selected-vars selected)
                   trigger (assoc ::trigger trigger))]
    (if record?
      (let [run-id (stash-run! {::run-result base})
            _      (await (record-run! (cond-> {::run-result base
                                                 ::run-id     run-id}
                                          (::db/conn req)
                                          (assoc ::db/conn (::db/conn req)))))
            ;; Reconstruct recorded-syms from events that produced a
            ;; pass/fail/error outcome (same logic as record-run!).
            per-var      (group-by :var (filter #(some? (:var %)) (::events base)))
            recorded     (vec (for [[var-sym evts] per-var
                                    :let [outcome (last (filter #(#{:pass :fail :error}
                                                                    (:type %)) evts))]
                                    :when outcome]
                                (str var-sym)))]
        (assoc base
               ::run-id        run-id
               ::recorded?     true
               ::recorded-syms recorded))
      base)))

(defn ^:async run-ns!
  "Sugar: run every test var defined in `::ns`, recording the projection."
  {:malli/schema [:=> [:cat [:map [::ns ::ns]
                                  [::record? {:optional true} ::record?]
                                  [::trigger {:optional true} ::trigger]]]
                  ::run-result]}
  [{::keys [ns record? trigger] :or {record? true}}]
  (await (run! (cond-> {::ns ns ::record? record?}
                 trigger (assoc ::trigger trigger)))))

(defn- last-run-id-from-db
  "Return the `:seon.test/last-run-id` whose `:seon.test/last-*-at` is
   the most recent. nil if no test runs have been recorded.

   Strategy: scan rows that have a :seon.test/last-run-id, max-by the
   later of last-passed-at / last-failed-at. We do this in CLJS rather
   than as a fancier datalog (max-aggregate over two fields) because
   the row count is small (one row per recorded test sym)."
  []
  (let [rows (db/query
               {::db/query '[:find ?run-id ?passed ?failed
                             :where
                             [?e :seon.test/last-run-id ?run-id]
                             [(get-else $ ?e :seon.test/last-passed-at #inst "1970-01-01") ?passed]
                             [(get-else $ ?e :seon.test/last-failed-at #inst "1970-01-01") ?failed]]})]
    (when (seq rows)
      (let [pick (apply max-key (fn [[_ p f]]
                                  (max (.getTime p) (.getTime f)))
                        rows)]
        (first pick)))))

(defn last-result
  "Return `{::run-id ::run-result}` for the most recently recorded run
   on this pod, or nil. Pulls the run-id from the DB projection, then
   fetches the full event sequence from the globalThis stash that
   `stash-run!` wrote.

   Used by humans at the REPL and by agents reading their own most-
   recent test outcome. NOT per-agent today — Phase 2+ will key by
   `:seon.agent/id`."
  {:malli/schema [:=> [:cat [:map]] ::last-result-response]}
  [_]
  (when-let [run-id (last-run-id-from-db)]
    (when-let [run-result (fetch-run run-id)]
      {::run-id     run-id
       ::run-result run-result})))
