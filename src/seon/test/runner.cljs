(ns seon.test.runner
  "Phase 2 — test capture as data.

   `run-vars` runs a set of cljs.test vars and returns the events +
   summary as data, not stdout. `record-run!` transacts the run
   onto `:seon.test/*` entities so the warnings / recent-evals
   tiles can read test state via Datalog.

   Both fns map-in / map-out with registered Malli schemas. Reporter
   capture never mutates `cljs.test/report` at the var root — instead
   we use `cljs.test`'s own per-call `:reporter`
   slot via `(empty-env ::seon.test.runner/capture)` and per-event
   defmethods that append to a volatile builder living in the
   env. The builder is fn-local; the API stays pure data-in,
   data-out.

   See docs/prds/agent-runtime/platform.md §Phase 2 for the
   roadmap context."
  (:refer-clojure :exclude [run!])
  (:require [cljs.test :as t]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
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
   ;; :var is a CLJS Symbol (cljs.core/Symbol). It is NOT a JS object —
   ;; `(.-sym v)` returns nil. Use `(name v)` / `(namespace v)` / `(str v)`.
   ;; The verifier's `(some-> e :var (.-sym) str)` returns nil for this
   ;; reason; that's a consumer bug, not a schema bug.
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

(schema/register! ::recorded-syms [:vector :string])

;; ::selector — "at least one of (::vars ::ns)" expressed as PURE DATA
;; (an :or of maps — same pattern as :seon.render/ai-response; registered
;; forms must not embed fns, the previous [:fn ...] arm couldn't survive
;; the form round-trip). The Phase 1 EXCLUSIVITY rule (exactly one
;; selector key) is enforced in `run!`'s body, where a violation throws
;; a legible error envelope instead of an opaque schema failure. Future
;; selectors (::nses ::all? ::failed-only? ::tag) added in later phases —
;; schema only carries Phase 1 keys today to keep instrumentation honest.
(schema/register! ::selector
  [:or
   [:map
    [::vars ::vars]
    [::ns   {:optional true} ::ns]]
   [:map
    [::ns   ::ns]
    [::vars {:optional true} ::vars]]])

(schema/register! ::run-request
  [:and
   ::selector
   [:map
    [::record? {:optional true} ::record?]
    [::trigger {:optional true} ::trigger]
    [::db/conn {:optional true} ::db/conn]]])

(schema/register! ::run-result
  [:map
   [::events         [:vector ::test-event]]
   [::summary        ::summary]
   [::selected-vars  {:optional true} ::vars]
   [::recorded?      {:optional true} :boolean]
   [::recorded-syms  {:optional true} ::recorded-syms]
   [::trigger        {:optional true} ::trigger]
   ;; Present ONLY on a selector-violation envelope (see `run!`): a caller
   ;; mistake rides the VALUE channel — a specced ^:async fn must never
   ;; reject with an expected error (the instrument wrapper records a
   ;; rejection as a :core fault, which crashes the dev pod).
   [:seon/error      {:optional true}
    [:map
     [:seon.error/kind    :seon.error/kind]
     [:seon.error/message :seon.error/message]]]])

(schema/register! ::last-result-response
  [:maybe ::run-result])

;; record-run! takes a run result. Conn is optional — falls back on db's
;; dynamic *conn*.
(schema/register! ::record-request
  [:map
   [::run-result ::run-result]
   [::db/conn    {:optional true} ::db/conn]])

(schema/register! ::record-tx-response
  [:or
   ::db/transact-response
   [:map
    [::db/ok?        [:= true]]
    [::db/tempids    [:map-of :any :int]]
    [::db/tx-count   [:= 0]]
    [::db/added      [:= 0]]
    [::db/retracted  [:= 0]]]])

(schema/register! ::tx-report ::record-tx-response)
(schema/register! ::record-response
  [:map
   [::run-result ::run-result]
   [::tx-report  ::tx-report]])

;; The persisted test entity's schema. Datahike valueTypes live in
;; seon.client/agent-bootstrap-schema; the Malli shapes here let
;; db/transact!'s validation gate accept the tx.
;;
;; NOTE: the FULL event sequence is NOT a DB field. It lives in the
;; bounded process stash below and the DB carries only the
;; minimal projection the warnings / recent-evals tiles render.
;; Per the user's design directive: "only put data in the database
;; schema that we want to surface in the agent's context."
(schema/register! :seon.test/sym [:string {:seon.db/identity true}])
(schema/register! :seon.test/last-passed-at :inst)
(schema/register! :seon.test/last-failed-at :inst)
(schema/register! :seon.test/last-failure-summary :string)
;; Phase 4 (mvp-completion-plan 2026-05-27): persist the deftest source
;; so we can later scan `:seon.test/source` to find tests that reference
;; a redefined fn (auto-test-on-fn-redef). `:seon.test/ns` is a lookup-ref
;; for parity with `:seon.fn/ns` and `:seon.schema/ns`.
(schema/register! :seon.test/source :string)
(schema/register! :seon.test/ns :seon.db/ref)
(schema/register! :seon.test/created-at :inst)

;; Entity-kind `:map` schema — promotes `:seon.test` to a real renderable
;; KIND (mirrors the `:seon.fn` / `:seon.schema` registrations in
;; seon.agent). The `:seon.render/ai` / `:seon.render/html` symbols are
;; resolved at render time via seon.eval/lookup-value — quoted here, NOT
;; required, so there's no cycle on seon.handlers.test.
;;
;; `:seon.test/sym` is the identity attr (the only required entry); every
;; other attr is `{:optional true}` so BOTH shapes validate + merge on the
;; sym identity: detect-and-tee source rows (sym+ns+source+created-at) and
;; runner result rows (sym+last-*). Once registered, `:seon.test`
;; lands in `entity-schema-keys`, decomposes into a `:seon.schema` row at
;; boot, and renders per-kind in render-namespace + the debug view panes.
(schema/register! :seon.test
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.test/render-ai
         :seon.render/html 'seon.handlers.test/render-html}
   [:seon.test/sym :seon.test/sym]
   [:seon.test/ns                   {:optional true} :seon.test/ns]
   [:seon.test/source               {:optional true} :seon.test/source]
   [:seon.test/last-passed-at       {:optional true} :seon.test/last-passed-at]
   [:seon.test/last-failed-at       {:optional true} :seon.test/last-failed-at]
   [:seon.test/last-failure-summary {:optional true} :seon.test/last-failure-summary]
   [:seon.test/created-at           {:optional true} :seon.test/created-at]])

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
   supply those — only include the key when the VALUE satisfies
   `::test-event`'s string/int schemas. NB `:line` can also arrive as
   `##NaN` (an assertion inside an async `.then` callback, where cljs.test
   reads line info off a JS stack frame that has none) — `some?` passes NaN
   but `:int` rejects it, which crashed the pod as a :core fault
   (2026-07-10); guard with `int?`, dropping the key exactly like nil. The
   `:expected`/`:actual` pair is always present (cljs.test populates them
   from the assertion sexpr) and we coerce to string via `safe-prstr`."
  [event-map kind]
  (some-> (builder!)
          (vswap! conj!
                  (cond-> {:type kind}
                    (some? (:message event-map))  (assoc :message (:message event-map))
                    (contains? event-map :expected) (assoc :expected (safe-prstr (:expected event-map)))
                    (contains? event-map :actual)   (assoc :actual (safe-prstr (:actual event-map)))
                    (string? (:file event-map))   (assoc :file (:file event-map))
                    (int? (:line event-map))      (assoc :line (:line event-map))
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
  "Look up a fully-qualified test sym and return its `:test` body thunk —
   the zero-arg fn that fires the example's `(is …)` assertions.

   TWO authoring forms produce a `:test` thunk, emitted to DIFFERENT
   runtime slots on the munged global (both LIVE-PROVEN 2026-06-17):

     - `(deftest foo …)` → `cljs.test/deftest` expands to
       `(def (vary-meta foo assoc :test (fn [] …)) (fn [] (test-var …)))`
       (cljs.test.cljc:250). The compiler emits the test body as `:test`
       META on the Var, which `:def-emits-var` exposes under the public
       fn as `cljs$lang$var`. Path: `obj.cljs$lang$var → meta :test`.

     - `(defn foo {:test (fn [] …)} […] …)` — a B9 usage example. The
       compiler emits the var-meta `:test` thunk DIRECTLY onto the fn
       object as the `cljs$lang$test` slot (NO `cljs$lang$var` on a plain
       defn). Path: `obj.cljs$lang$test`.

   INSTRUMENTATION UNWRAP (the live bug, 2026-06-17): a B9 example fn
   carries `:malli/schema`, so the eval auto-instruments it — malli
   REPLACES the global with a validating wrapper and stashes the real fn
   at `malli$instrument$original` (malli/instrument.cljs:13,57). The
   wrapper does NOT carry `cljs$lang$test`; the ORIGINAL does. So we must
   unwrap to the malli original before reading the thunk — otherwise the
   fallback runs the INSTRUMENTED IMPL at arity 0 (`(foo)`), which throws
   `:malli.core/invalid-arity` instead of firing the example's `(is …)`.

   We want the BODY thunk, NOT the public fn (`obj`): invoking the
   public fn either re-enters `test-var`'s bracketing (deftest, double
   counts) or runs the IMPLEMENTATION with wrong arity (a defn example).
   Driving the `:test` thunk directly also lets us await the IAsyncTest
   CPS object that `(async done …)` returns, instead of having
   `run-block` swallow it.

   `obj` itself is the last-resort fallback (a non-deftest, non-example
   callable the caller explicitly asked us to run). Returns nil if the
   symbol doesn't resolve to any global."
  [sym]
  (let [ns-part (some-> (namespace sym) (cljs.core/munge))
        nm-part (cljs.core/munge (name sym))]
    (when (and ns-part nm-part)
      (let [obj  (try (js/goog.getObjectByName (str ns-part "." nm-part))
                      (catch :default _ nil))
            ;; Unwrap malli instrumentation: the real fn (carrying the
            ;; `cljs$lang$test` thunk) is stashed under the wrapper.
            base (or (when obj (unchecked-get obj "malli$instrument$original"))
                     obj)
            v    (when base (unchecked-get base "cljs$lang$var"))]
        ;; Prefer the deftest Var's `:test` meta, then the defn-example's
        ;; `cljs$lang$test` slot, then the raw fn as a last resort.
        (or (some-> v meta :test)
            (when base (unchecked-get base "cljs$lang$test"))
            base)))))

(defn- thenable?
  "Truthy if v looks like a Promise/A+: has a callable `.then` slot."
  [v]
  (and (some? v)
       (or (instance? js/Promise v)
           (and (object? v) (fn? (unchecked-get v "then"))))))

;; ============================================================
;; Per-test wall-clock bound. The async driver below (`drive-test-fn!`,
;; `run-fixture-fn!`) returns a Promise that resolves only when the body
;; SETTLES. A never-resolving body — a `^:async` test awaiting a Promise that
;; never resolves, or an `(async done …)` test that never calls `done` — would
;; otherwise park `run-vars` forever, and through it the agent turn /
;; `run-loop!` that awaits the auto-test-run (see
;; docs/prds/agent-fsm/research/pod-wedge-root-cause-2026-06-28.md). The bound
;; converts "parked forever" into "one timed-out `:error` event, run
;; continues." Same no-preemption caveat as every seon timeout (seon.eval): JS
;; is single-threaded, so the body keeps running in the background; this only
;; frees the awaiter.
;; ============================================================

(defn- report-timeout!
  "Emit a timeout `:error` report event (read by the active reporter via
   `t/*current-env*`). `what` is :test or :fixture; `label` names the offending
   sym/ns. Fired from inside `with-test-timeout`'s timer callback, which runs
   while `run-vars` is still suspended inside its `binding [t/*current-env* …]`,
   so the event lands in the right builder."
  [what label ms]
  (t/do-report
    {:type    :error
     :message (str (case what :test "Test" :fixture "Fixture") " `" label
                   "` timed out after " ms "ms — its body never settled "
                   "(a never-resolving async body, or an `(async done …)` that "
                   "never called done). The body keeps running (JS has no "
                   "preemption); the runner moves on so the agent turn/run-loop "
                   "awaiting it is never parked.")
     :expected nil
     :actual   :seon.test/timeout}))

(defn- with-test-timeout
  "Race `make-promise` (a thunk returning a drive Promise) against a wall-clock
   timer of `ms`. The returned Promise ALWAYS resolves nil: when the drive
   settles first → resolve; when the timer wins → call `on-timeout` then
   resolve. Clears the timer on settle so a fast test leaks no pending timer.
   `make-promise` is invoked eagerly (the body starts running immediately)."
  [ms make-promise on-timeout]
  (js/Promise.
    (fn [resolve _reject]
      (let [settled (volatile! false)
            fin     (fn [] (when-not @settled (vreset! settled true) (resolve nil)))
            timer   (js/setTimeout (fn [] (when-not @settled (on-timeout) (fin))) ms)]
        (-> (make-promise)
            (.then  (fn [_] (js/clearTimeout timer) (fin)))
            (.catch (fn [_] (js/clearTimeout timer) (fin))))))))

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
  "Run the given fully-qualified test-var symbols, return data.

   Returns events + summary as data.

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
        ;; group-by preserves WITHIN-ns ordering (vars stay in input
        ;; order inside each bucket). ACROSS-ns ordering is stable only
        ;; up to ~8 nses — CLJS group-by returns a PersistentArrayMap
        ;; that flips to PersistentHashMap (hash-iteration order) past
        ;; that threshold. Single-ns is the common case via `run-ns!`;
        ;; multi-ns batches with >8 nses will iterate nses in
        ;; non-deterministic order. Fix when a use case demands it
        ;; (reduce into a sorted-map or insertion-ordered accumulator).
        by-ns    (group-by :ns present)
        ;; Per-test/-fixture wall-clock bound — see `with-test-timeout`. Read
        ;; once per run so a never-settling body can't park the whole run-loop.
        ms       (config/test-timeout-ms)]
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
            (await (with-test-timeout ms
                                      #(run-fixture-fn! fx :before ns-sym)
                                      #(report-timeout! :fixture ns-sym ms))))
          (doseq [{:keys [sym fn]} ns-vars]
            ;; :each :before — registration order, before each test.
            (doseq [fx each-fxs]
              (await (with-test-timeout ms
                                        #(run-fixture-fn! fx :before ns-sym)
                                        #(report-timeout! :fixture ns-sym ms))))
            (t/update-current-env! [:testing-vars]
                                   conj #js {:sym sym})
            (t/update-current-env! [:report-counters :test] inc)
            (t/do-report {:type :begin-test-var :var #js {:sym sym}})
            (await (with-test-timeout ms
                                      #(drive-test-fn! sym fn)
                                      #(report-timeout! :test sym ms)))
            (t/do-report {:type :end-test-var :var #js {:sym sym}})
            (t/update-current-env! [:testing-vars] rest)
            ;; :each :after — REVERSE registration order, after each test
            ;; (matches cljs.test's wrap-map-fixtures, which `reverse`s :after).
            (doseq [fx (reverse each-fxs)]
              (await (with-test-timeout ms
                                        #(run-fixture-fn! fx :after ns-sym)
                                        #(report-timeout! :fixture ns-sym ms)))))
          ;; :once :after — reverse order, after all vars in the ns.
          (doseq [fx (reverse once-fxs)]
            (await (with-test-timeout ms
                                      #(run-fixture-fn! fx :after ns-sym)
                                      #(report-timeout! :fixture ns-sym ms))))))
      (let [counters (:report-counters (t/get-current-env))]
        (t/do-report (assoc counters :type :summary))))
    (let [events  (persistent! @!builder)
          summary (or (->> events (filter #(= :summary (:type %))) last)
                      {:test (count vars) :pass 0 :fail 0
                       :error (count missing)})]
      {::events events ::summary summary})))

;; ============================================================
;; Live details — one bounded process-local store for full recorded results.
;; Entries are kept oldest-first in recording order. `last-result` reads the
;; newest entry directly; the store deliberately disappears on restart.
;; ============================================================

(def ^:private run-stash-cap
  "Maximum full test-run results retained in this process. The database keeps
   summaries; this bounded runtime detail store is only for recent drill-down."
  32)

(defonce ^:private !run-stash
  ;; Oldest first. Each entry is one complete recorded ::run-result.
  (atom []))

(defn- remember-recorded-run!
  "Append one recorded result to the bounded live detail store."
  [run-result]
  (try
    (swap! !run-stash
           (fn [entries]
             (let [entries' (conj entries run-result)
                   excess   (max 0 (- (count entries') run-stash-cap))]
               (subvec entries' excess))))
    (catch :default e
      (error/record! {:seon.error/raw e :seon.error/fault :core})))
  run-result)

;; ============================================================
;; Record — minimal projection to the DB. NEVER transacts events.
;; ============================================================

(def ^:private failure-summary-max-tokens
  "Token cap for :seon.test/last-failure-summary. Keeps the warnings tile
   renderable while the full event remains in the bounded live detail store."
  50)

(defn- failure-summary
  "Build a short renderable string from the last :fail/:error event."
  [evt]
  (let [src (cond
              (:message evt)  (str (:message evt))
              :else           (str (when (:expected evt)
                                     (str "expected " (:expected evt) " "))
                                   (when (:actual evt)
                                     (str "actual " (:actual evt)))))]
    (tokens/clip-str src failure-summary-max-tokens)))

(defn- recorded-syms
  "Test symbols whose latest event in this result produced an outcome."
  [run-result]
  (let [per-var (group-by :var (filter #(some? (:var %)) (::events run-result)))]
    (vec (for [[var-sym evts] per-var
               :let [outcome (last (filter #(#{:pass :fail :error} (:type %)) evts))]
               :when outcome]
           (str var-sym)))))

(defn ^:async record-run!
  "Transact the SURFACED projection for each test in `run-result`.
   Successful recordings append the full result to the bounded process store;
   `(seon.test.runner/last-result {})` reads its newest live entry directly.

   Per-var DB fields:
     :seon.test/sym                    — \"agent.ns/my-test\"
     :seon.test/last-passed-at         — when the var most recently passed
     :seon.test/last-failed-at         — when the var most recently failed
     :seon.test/last-failure-summary   — ≤50-token rendered failure

   Returns the full result with structural recording fields plus the compact
   database transaction envelope."
  {:malli/schema [:=> [:cat ::record-request] ::record-response]}
  [{::keys [run-result conn]}]
  (let [now     (js/Date.)
        events  (::events run-result)
        per-var (group-by :var (filter #(some? (:var %)) events))
        tx-data
        (vec
          (for [[var-sym evts] per-var
                :let [outcome (last (filter #(#{:pass :fail :error} (:type %)) evts))]
                :when outcome
                :let [failed? (#{:fail :error} (:type outcome))]]
            (cond-> {:seon.test/sym (str var-sym)}
              failed?       (assoc :seon.test/last-failed-at        now
                                   :seon.test/last-failure-summary  (failure-summary outcome))
              (not failed?) (assoc :seon.test/last-passed-at now))))
        tx-report (if (seq tx-data)
                    (await (db/transact!
                             (cond-> {:seon.db/tx-data tx-data}
                               conn (assoc :seon.db/conn conn))))
                    ;; No test outcomes to record — return a no-op compact
                    ;; envelope without inventing a transaction.
                    {:seon.db/ok? true :seon.db/tempids {}
                     :seon.db/tx-count 0 :seon.db/added 0
                     :seon.db/retracted 0})
        ok?       (true? (::db/ok? tx-report))
        result'   (assoc run-result
                         ::recorded? ok?
                         ::recorded-syms (if ok?
                                           (recorded-syms run-result)
                                           []))]
    (when ok?
      (remember-recorded-run! result'))
    {::run-result result'
     ::tx-report  tx-report}))

(defn ^:async run-and-record!
  "Run vars, record summary facts, and retain the full live result.

   Returns `{::run-result ::tx-report}`. This is the surface the agent's
   eval-batch will typically call after a `(defn …)` that touches a
   `:seon.fn` — see spec §D4 (targeted test auto-run)."
  {:malli/schema [:=> [:cat [:map [::vars ::vars]]] ::record-response]}
  [{::keys [vars]}]
  (let [result (await (run-vars {::vars vars}))]
    (await (record-run! {::run-result result}))))

;; ============================================================
;; Phase 1 universal entrypoints — vars-in-ns, run!, run-ns!, last-result
;; ============================================================

(defn vars-in-ns
  "The fully-qualified test-var symbols defined in `ns-sym`.

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
   Future phases add ::nses, ::all?, ::failed-only?, ::tag here.
   `run!` guards the no-selector case BEFORE calling this — the :else
   throw is a genuine-bug guard, never an expected-error path."
  [{::keys [vars ns] :as req}]
  (cond
    vars vars
    ns   (vars-in-ns {::ns ns})
    :else
    (throw (ex-info "No selector — expected ::vars or ::ns"
                    {:type ::no-selector :request req}))))

(defn- selector-error
  "A schema-valid `::run-result` ERROR envelope for a selector violation.
   Errors are values: `run!` is a specced ^:async fn, so an expected
   caller mistake must resolve (never reject — a rejection is recorded
   as a :core fault by the instrument wrapper and crashes the dev pod).
   Zero tests ran; the summary counts the violation as 1 :error."
  [msg]
  {::events  []
   ::summary {:test 0 :pass 0 :fail 0 :error 1}
   :seon/error {:seon.error/kind    :user-input
                :seon.error/message msg}})

(defn ^:async run!
  "Universal entrypoint.

   Resolves the selector to a vector of FQ test
   symbols, runs them via `run-vars`, optionally records the projection
   to the DB.

   Phase 1 selectors: exactly one of `::vars` or `::ns` must be present.
   The schema (`::selector`) carries the pure-data \"at least one\"
   shape; the exactly-one rule lives HERE so a violation RESOLVES to a
   legible `::run-result` error envelope (`:seon/error` alongside an
   empty run — see [[selector-error]]), not a schema dump and never a
   rejected Promise.

   Examples:
     ;; one var
     (run! {::vars '[seon.db-test/transact!-round-trips-an-entity]})
     ;; one ns
     (run! {::ns 'seon.db-test ::record? true})

   Returns a fully-populated `::run-result` with `::selected-vars`,
   `::recorded?` / `::recorded-syms` when `::record? true`, and `::trigger`
   propagated through."
  {:malli/schema [:=> [:cat ::run-request] ::run-result]}
  [{::keys [record? trigger] :as req}]
  (cond
    (and (some? (::vars req)) (some? (::ns req)))
    (selector-error (str "Ambiguous selector — provide exactly one of "
                         ":seon.test.runner/vars or :seon.test.runner/ns, "
                         "not both. Got ::vars " (pr-str (::vars req))
                         " AND ::ns " (pr-str (::ns req)) "."))

    (and (nil? (::vars req)) (nil? (::ns req)))
    (selector-error (str "No selector — provide exactly one of "
                         ":seon.test.runner/vars or :seon.test.runner/ns."))

    :else
    (let [selected (vec (resolve-selector req))
          result   (await (run-vars {::vars selected}))
          base     (cond-> (assoc result ::selected-vars selected)
                     trigger (assoc ::trigger trigger))]
      (if record?
        (::run-result
          (await (record-run! (cond-> {::run-result base}
                                (::db/conn req)
                                (assoc ::db/conn (::db/conn req))))))
        base))))

(defn ^:async run-ns!
  "Sugar: run every test var defined in `::ns`, recording the projection."
  {:malli/schema [:=> [:cat [:map [::ns ::ns]
                                  [::record? {:optional true} ::record?]
                                  [::trigger {:optional true} ::trigger]
                                  [::db/conn {:optional true} ::db/conn]]]
                  ::run-result]}
  [{::keys [ns record? trigger] :or {record? true} :as request}]
  (await (run! (cond-> {::ns ns ::record? record?}
                 trigger (assoc ::trigger trigger)
                 (::db/conn request) (assoc ::db/conn (::db/conn request))))))

(defn last-result
  "The most recent live recorded `::run-result`, or nil.

   Scoped to this process. Reads the newest entry directly from the bounded
   recorded-run store; an empty or restarted process returns nil. Durable
   per-test summaries remain independently queryable from the database.

   Used by humans at the REPL and by agents reading their own most-
   recent test outcome. NOT per-agent today — Phase 2+ will key by
   `:seon.agent/id`."
  {:malli/schema [:=> [:cat [:map]] ::last-result-response]}
  [_]
  (peek @!run-stash))

;; ============================================================
;; Phase 4 (mvp-completion-plan 2026-05-27) — auto-test-on-fn-redef.
;; Find every `:seon.test` row whose source mentions `fn-sym`. Used
;; by `seon.eval/eval-batch!` after a `:seon.fn` is teed: any test
;; that references the just-redefined fn re-runs.
;;
;; v0 heuristic: substring match on the source string. Fragile (a fn
;; named `foo` will match comments / docstrings / unrelated symbols
;; that share the suffix), but the analyzer doesn't carry per-deftest
;; body fn-ref data we could query, and writing a fresh walker is more
;; than this MVP slice. Tighter matching is a Phase 4.1 follow-up.
;; ============================================================

(defn tests-referring-to
  "The fully-qualified test syms whose `:seon.test/source` mentions `fn-sym`.

   Accepts `fn-sym` as string or symbol. Pure DB
   read — safe to call inside an eval-batch! tx scope."
  {:malli/schema [:=> [:catn [::fn-sym [:or :string :symbol]]] [:vector :symbol]]}
  [fn-sym]
  (let [needle (str fn-sym)
        rows   (db/query
                 {::db/query '[:find ?sym ?source
                               :where
                               [?e :seon.test/sym ?sym]
                               [?e :seon.test/source ?source]]})]
    (vec (for [[sym source] rows
               :when (and source (str/includes? source needle))]
           (symbol sym)))))
