(ns seon.eval
  "Agent eval surface. SAFE BY DEFAULT — `eval` returns
   {::ok? true ::value v} or {::ok? false :seon/error <error-map>}. A
   throw,
   compile error, or async rejection — all return as values. The agent
   session continues.

   The unadorned name `eval` is the safe one; there is no public strict
   variant. Callers that want raw throw semantics drop down to
   `cljs.js/eval-str` themselves.

   `eval` shadows clojure.core/eval inside this namespace. That's
   deliberate — agents type `(seon.eval/eval ...)` from outside this
   ns. seon internals that need clojure.core's `eval` import it as
   `core/eval`.

   ## REPL semantics

   Vars defined in one eval persist for the next (compile-state is
   process-shared, defonce'd at boot in seon.client).

   `:ns` is tracked per-call: `cljs.js/eval-str` returns the ending ns,
   which we feed back as the next call's `:ns` parameter. That's how
   `(ns other-ns)` switches affect subsequent forms. Smart REPL default:
   unqualified vars resolve in the current ns.

   ## Bootstrap gotchas (cljs.js + bootstrap target)

   - **Bare value-def reads don't resolve across eval-str calls.**
     `(def x 42)` then `x` returns nil. Use atoms instead:
     `(def !x (atom 42))` + `@!x` works. Fns are unaffected — they
     cross namespaces fine. For the agent's own id, use
     `(seon.db/current-agent-id)` (the core provides it via a
     turn-scoped ALS dynvar).

   ## REPL forms (owner rulings 2026-07-10)

   `(in-ns 'foo)` is THE movement form — state-preserving; a fresh name
   is CREATED with the canonical toolkit requires (deliberately better
   than JVM blank-slate in-ns). `(ns foo …)` declares/updates a
   namespace's requires (re-eval REPLACES the require set). A bare
   top-level `(require …)` loads now AND persists into the current
   ns's stored declaration; `(alias 'a 'the.ns)` records a require
   alias (error-as-value when the target isn't loaded). `ns-unmap`
   removes a var + its `:seon.fn` row; `ns-unalias` drops an alias.
   All handled by [[dispatch-repl-form!]] at the eval boundary."
  (:refer-clojure :exclude [eval])
  (:require [cljs.analyzer :as ana]
            [cljs.js :as cljs]
            [cljs.reader :as reader]
            [cljs.tools.reader :as tools-reader]
            [cljs.tools.reader.reader-types :as reader-types]
            [clojure.set :as set]
            [clojure.string :as str]
            [goog.object :as gobj]
            [malli.core :as m]
            [shadow.cljs.bootstrap.node :as boot]
            [seon.agent.home :as home]
            [seon.ai.tokens :as tokens]
            [seon.analyzer-info :as analyzer-info]
            [seon.config :as config]
            [seon.db :as db]
            [seon.db.id :as db.id]
            [seon.db.protocol :as db.protocol]
            [seon.db.process :as db.process]
            [seon.diffusion.grammar :as grammar]
            [seon.error :as error]
            [seon.eval.bootstrap-cache :as bootstrap-cache]
            [seon.error.instrument :as einstrument]
            [seon.instrument :as instrument]
            [seon.platform :as platform]
            [seon.render.value :as value]
            [seon.repair :as repair]
            [seon.repair.candidates :as candidates]
            [seon.repl.internal :as internal]
            [seon.schema :as schema]
            [seon.schema.internal :as schema.internal]
            [seon.test.runner :as test-runner]))


;; ============================================================
;; Per-form wall-clock timeout. Stability guard, not a security
;; boundary.
;;
;; CAVEAT: single-threaded Node only preempts **async** hangs (a form
;; awaiting a Promise that never resolves — fetch, db call, etc.). A
;; tight CPU loop blocks the event loop entirely, including the
;; timer, and can NOT be cancelled here. Real preemption needs
;; worker_thread (Phase 2) or wasmtime (Phase 3). What this DOES buy:
;; a hung fetch / never-resolving Promise no longer wedges the agent
;; loop indefinitely.
;; ============================================================

;; The immutable fallback for one form. A slow form carries its own deadline
;; through [[budget]]. A future configurable default must be resolved from the
;; form's frozen database config, not from process-global mutable state.
(def ^:private default-timeout-ms 10000)

(deftype ^:private Budgeted [ms value])

(defn budget
  "Override the auto-await wall-clock timeout for a slow form.

   Applies to the form's auto-awaited return value. Use when a form
   does a slow async op that legitimately
   needs more than the default 10000ms.

     ;; default 10s budget
     (some-fs-walk \"/Users/me/dir\")

     ;; give this form 60 seconds
     (seon.eval/budget 60000 (some-fs-walk \"/Users/me/dir\"))

   Returns an explicit runtime wrapper carrying `inner` and `ms` together.
   `eval-batch!` consumes that wrapper before recording or displaying the
   value, so agents still see only the resolved data. Keeping the deadline
   attached to its value prevents overlapping agent evals from consuming one
   another's budgets. Pattern:

   ;; turn 1 — budget applies only to THIS form
   (seon.eval/budget 60000 (slow-async-op))

   ;; turn 2 — uses the default 10s again
   (regular-op)"
  ;; `inner` + the return are `:any` on purpose: `inner` is the agent form's
  ;; already-evaluated value (a Promise / any runtime value), carried in an
  ;; opaque runtime wrapper. Only `ms` carries a data contract.
  {:malli/schema [:=> [:catn [::ms :int] [::inner :any]] :any]}
  [ms inner]
  ;; An outer budget replaces an inner budget instead of nesting wrappers.
  ;; The value at the eval boundary therefore has exactly one deadline.
  (if (instance? Budgeted inner)
    (->Budgeted ms (.-value inner))
    (->Budgeted ms inner)))

;; ============================================================
;; Deferred — explicit opt-out of auto-await. `(seon.eval/defer expr)`
;; wraps a Promise so the eval pipeline stashes the HANDLE at
;; `result/<id>` instead of blocking for the value. Same destination as
;; the auto-await TIMEOUT path: the form records a `:seon.eval/pending`
;; placeholder, the live Promise goes to `result/<id>`, and a later
;; re-reference auto-awaits it to data (top-level `(await …)` cannot —
;; the macro needs an async env; re-reference is the resolution path).
;; ============================================================

(deftype Deferred [promise])

(defn defer
  "Hand the eval pipeline a Promise WITHOUT auto-awaiting it.

   For a long-running or fire-and-forget op when you want the HANDLE, not to
   block for the value. Wrap a form that returns a Promise:

     (seon.eval/defer (some-slow-async-op))

   The form's value records as a `:seon.eval/pending` placeholder and the
   live Promise is stashed at `result/<id>`; re-reference `result/<id>`
   in a LATER eval and the normal auto-await resolves it to data. A
   non-Promise argument is returned unchanged (nothing to defer).

   NOTE: the capped live-result store is process-scoped — a pod restart
   drops it. Anything that must survive a restart should persist its RESULT to
   the DB, not rely on this in-memory handle."
  ;; `v` + the return are `:any` on purpose: `v` is the agent form's
  ;; already-evaluated value (a Promise / any runtime value) — a
  ;; runtime-value boundary, same as `budget`'s `inner`.
  {:malli/schema [:=> [:catn [::value :any]] :any]}
  [v]
  ;; Preserve composition with `(defer (budget ms promise))`: before budgets
  ;; were explicit wrappers, `defer` saw that same raw Promise and opted out of
  ;; awaiting it. A budget has no meaning once the caller explicitly defers.
  (let [v (if (instance? Budgeted v) (.-value v) v)]
    (if (instance? js/Promise v)
      (->Deferred v)
      v)))

(defn- pending-placeholder
  "The clean DATA value recorded + displayed for a form whose Promise is
   still running (auto-await timeout OR explicit `defer`). NEVER the raw
   Promise — the value renderer must never `seq` a Promise. Names
   `result/<id>` so the agent knows exactly how to await the real value."
  [eval-id]
  {:seon.eval/pending
   (str "still running — re-reference `result/" eval-id "` in a later "
        "eval to await its value")})

;; Identity-checked marker returned by `race-timeout` when the timer
;; wins. A fresh JS object so `identical?` distinguishes it from any
;; resolved eval value.
(defonce ^:private timeout-sentinel #js {:_seon_eval_timeout true})

(defn timed-out?
  "True when `v` is [[race-timeout]]'s timeout sentinel (identity check)."
  {:malli/schema [:=> [:catn [::value :any]] :boolean]}
  [v]
  (identical? v timeout-sentinel))

(defn ^:async race-timeout
  "Race `inner` (a Promise) against a wall-clock timer of `ms`.

   THE one async wall-clock bound (eval, the test runner's mirror, the
   agent loop's per-turn bound, `call-llm!`'s per-attempt bound all sit
   on this shape — never a second racer). If `inner` settles first,
   returns its resolved value and CLEARS the pending timer (no dangling
   timer on a fast settle). If the timer fires first, returns the
   timeout sentinel — check via [[timed-out?]]. The timeout does NOT
   cancel the underlying work (JS has no preemptive cancellation, one
   event loop): it frees the AWAITER; a late settler's run-scoped
   writes are aborted by the in-tx CAS work-fence."
  {:malli/schema [:=> [:catn [::inner :any] [::ms :int]] :any]}
  [inner ms]
  (let [!timer (volatile! nil)
        timer  (js/Promise.
                 (fn [resolve _]
                   (vreset! !timer
                            (js/setTimeout (fn [] (resolve timeout-sentinel)) ms))))]
    (try
      (await (js/Promise.race #js [inner timer]))
      (finally
        ;; `inner` won (value or throw) → clear the still-pending timer.
        ;; When the TIMER won this is a no-op (clearing a fired timer).
        (js/clearTimeout @!timer)))))

;; ============================================================
;; Bootstrap init — load cljs.core + cljs.core$macros from the
;; :bootstrap shadow build into a fresh compile-state. ^:async so
;; callers can `(await ...)` it from straight-line agent code.
;; ============================================================

;; Stamped at code-eval time. Hot-reload of THIS namespace produces a
;; fresh gensym; the cached compile-state in `seon.repl/!compile-state`
;; carries the old version via `seon.repl/!init-version`, so the next
;; `ensure-bootstrap!` re-runs init. Hot-reloads of unrelated nses
;; keep the version, leaving the warm state in place.
;; Use `def` (not `defonce`) so reloads of `seon.eval` rotate it.
(def init-version (gensym "seon.eval/init-v_"))

;; ============================================================
;; Per-fiber warning capture via AsyncLocalStorage
;;
;; Phase 0 item 2 of the STATUS.md migration plan. Replaces the prior
;; per-eval `set!` of `ana/*cljs-warning-handlers*` (a process-global
;; mutation that silently cross-wired warnings between concurrent
;; agents — multi-agent v1 hazard, per
;; research/eval-batch-fragility-2026-05-23.md §Option 1).
;;
;; Mechanism:
;;   - `warnings-als` is a Node AsyncLocalStorage instance, defonce'd
;;     so it survives hot-reload of seon.eval.
;;   - `install-warning-dispatcher!` installs a SINGLE root handler on
;;     `ana/*cljs-warning-handlers*`. The handler reads the active
;;     per-eval bucket from `warnings-als` via `.getStore`. Outside an
;;     `(.run warnings-als …)` scope, getStore returns nil and the
;;     handler is a no-op.
;;   - `raw-eval` wraps each cljs.js call in `(.run warnings-als <atom>)`
;;     with its OWN bucket atom. Concurrent evals get isolated buckets;
;;     ALS guarantees the bucket follows the fiber across awaits.
;;
;; D13 result (research/impl-finding-tx-context-promise-2026-05-22.md)
;; confirms Node `AsyncLocalStorage` survives Promise / await
;; boundaries; CLJS `binding` does not. Same core as
;; `seon.db/with-tx-context`.
;; ============================================================

(defonce ^:private warnings-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

;; defonce, so the recorded version survives hot-reload of seon.eval.
;; install-warning-dispatcher! reinstalls only when init-version
;; rotates (i.e. after a real reload), which keeps the dispatcher
;; in step with this ns's latest closure values.
(defonce ^:private !warning-dispatcher-version (atom nil))

(defn install-warning-dispatcher!
  "Install the per-fiber CLJS-warning dispatcher (idempotent).

   Installs on `ana/*cljs-warning-handlers*` once per init-version. Called from
   `init-bootstrap!` after the cljs.js loader's analyzer setup
   completes. Safe to call repeatedly; only reinstalls after a
   hot-reload (when `init-version` has rotated)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (when (not= @!warning-dispatcher-version init-version)
    (set! ana/*cljs-warning-handlers*
          [(fn [type _env extra]
             (when (#{:undeclared-var :undeclared-ns} type)
               (when-let [bucket (.getStore warnings-als)]
                 (swap! bucket conj
                        (assoc extra :seon.eval/warning-type type)))))])
    (reset! !warning-dispatcher-version init-version)))

;; ============================================================
;; Per-fiber print capture via AsyncLocalStorage
;;
;; Same hazard as the warning dispatcher above, one tier deeper: a
;; REPL shows println/prn output next to the result, but `*print-fn*`
;; (and `*print-err-fn*`) are PROCESS-GLOBAL. The prior eval-form-entry!
;; capture did `(set! *print-fn* cap)` BEFORE the eval+auto-await and
;; restored it AFTER — a `set!` straddling an `await`. When the captured
;; form yields (any `^:async`/awaiting function), ANOTHER fiber (a concurrent
;; agent's eval, a heartbeat) ran with THIS eval's `cap` still installed,
;; so its prints bled into this eval's bucket (and the restore clobbered
;; the concurrent capture). Live-confirmed cross-fiber bleed (#64).
;;
;; Mechanism (identical to warnings-als):
;;   - `print-als` is a Node AsyncLocalStorage, defonce'd to survive
;;     hot-reload.
;;   - `install-print-dispatcher!` installs ONE global `*print-fn*` /
;;     `*print-err-fn*` that routes to the active per-eval bucket via
;;     `.getStore`. Outside an `(.run print-als …)` scope getStore returns
;;     nil and the print falls through to the ORIGINAL fn (pod stdout /
;;     logs/pod.log) — boot/web-UI prints land in the log exactly as
;;     before. The originals are captured ONCE (defonce atom) so a
;;     hot-reload reinstall never wraps the dispatcher around itself.
;;   - `eval-form-entry!` wraps its eval+auto-await span in
;;     `(.run print-als <bucket> …)` with its OWN bucket atom. ALS carries
;;     the bucket across the form's awaits, so concurrent evals get fully
;;     isolated output — no global `set!`, no straddled restore.
;; ============================================================

;; NOT private: the eval-form-entry! `.run` capture site reads it here, and
;; the #64 regression test installs its own dispatcher over THIS instance to
;; prove per-fiber isolation in a test harness that owns `*print-fn*` itself.
(defonce print-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

;; The eval recorder is the durability boundary for schema registrations made
;; while an agent form runs. It cannot use a not-yet-committed eval id as an
;; execution marker: identity now belongs to record-eval!, after the form has
;; executed exactly once. This private ALS marker follows the form across
;; Promise/await boundaries and tells the eager schema self-tee to stand down;
;; the successful form's detect-and-tee rows then commit with its eval row.
;; It is deliberately separate from print-als because capture routing and
;; durability ownership are independent execution scopes.
(defonce ^:private record-boundary-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defn- record-boundary-active?
  []
  (true? (.getStore record-boundary-als)))

(defn- run-with-record-boundary
  [body-fn]
  (.run record-boundary-als true body-fn))

;; The ORIGINAL print fns (route to pod stdout). Captured ONCE on first
;; install; reused on every hot-reload reinstall so the dispatcher always
;; wraps the real sink, never a prior dispatcher (which would recurse).
(defonce ^:private !orig-print-fns (atom nil))
(defonce ^:private !print-dispatcher-version (atom nil))

(defn- print-dispatch
  "A `*print-fn*`-shaped fn that appends to the active `print-als` bucket
   when one is in scope, else delegates to `orig` (pod stdout)."
  [orig]
  (fn [& xs]
    (if-let [bucket (.getStore print-als)]
      (swap! bucket str (apply str xs))
      (apply orig xs))))

(defn install-print-dispatcher!
  "Install the per-fiber print dispatcher (idempotent).

   Installs on `*print-fn*` /
   `*print-err-fn*` once per init-version. Called from `init-bootstrap!`.
   Captures the originals on first call only (so reinstall after a
   hot-reload reuses the real sink, not a wrapped dispatcher)."
  {:malli/schema [:=> [:cat] :any]}
  []
  (when (not= @!print-dispatcher-version init-version)
    (when (nil? @!orig-print-fns)
      (reset! !orig-print-fns {:out *print-fn* :err *print-err-fn*}))
    (let [{:keys [out err]} @!orig-print-fns]
      (set! *print-fn* (print-dispatch out))
      (set! *print-err-fn* (print-dispatch err)))
    (reset! !print-dispatcher-version init-version)))

;; Defined after `ns-fn-members` (it reads the live ns members); declared here
;; so `init-bootstrap!` can call it.
(declare seed-toolkit-refers!)

(defn ^:async init-bootstrap!
  "Initialize a fresh compile-state from out/bootstrap/.

   Returns the
   compile-state, ready for `eval` / `eval-batch!`. Stores cljs.core
   on globalThis (via goog.globalEval inside shadow's loader); without
   that, find-ns-obj fails on the first macro form and eval-str
   throws TypeError on findInternedVar.

   Force-populates the analyzer caches for EVERY namespace shadow
   emitted into the bootstrap output — see `seon.eval.bootstrap-cache/load-all!`
   for the rationale and the alternative we rejected (hand-coded
   load list for `[cljs.core cljs.core$macros]` only, which would
   silently break the moment someone expanded `:bootstrap :entries`).

   Callers (`seon.repl/ensure-bootstrap!`, `seon.client/start-agent!`)
   pair the result with `init-version` to detect stale-after-reload
   state; see [[seon.repl/!init-version]]."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [state          (cljs/empty-state)
        ;; SEON_RUNTIME_ROOT-aware: a downstream pod running from its
        ;; own project root finds the bootstrap output in the seon
        ;; checkout; unset = "out/bootstrap" (CWD-relative) as before.
        bootstrap-path (platform/artifact-path "out/bootstrap")]
    (await (js/Promise.
             (fn [resolve _reject]
               (boot/init state
                          {:path bootstrap-path
                           :load-on-init '#{cljs.core}}
                          (fn [] (resolve nil))))))
    (bootstrap-cache/load-all! state bootstrap-path)
    (when-not (and (some? (.-cljs js/global))
                   (some? (.-core (.-cljs js/global))))
      (throw (js/Error.
               "bootstrap loader did not put cljs.core on globalThis")))
    ;; Install per-fiber warning dispatcher (Phase 0 item 2).
    ;; Idempotent + version-stamped against init-version so hot-reload
    ;; reinstalls the closure.
    (install-warning-dispatcher!)
    ;; Install the per-fiber print dispatcher (#64). Same idempotent,
    ;; version-stamped contract — gives each eval's println/prn output its
    ;; own ALS bucket instead of a process-global `set!` straddling awaits.
    (install-print-dispatcher!)
    ;; Seed the home-ns refer toolkit defs (see `seed-toolkit-refers!`) so
    ;; `setup-agent-ns!`'s `(ns … (:refer [wait complete …]))` analyzes CLEANLY.
    ;; init-bootstrap! is the ONE birthplace of a compile-state, so every
    ;; fresh/rebuilt state carries the seed.
    (seed-toolkit-refers! state)
    state))

;; ============================================================
;; Core eval — one form. Safe by default.
;; ============================================================

(defn- resolves-on-globalthis?
  "True if `path` (a munged dotted name like `seon.db.transact_BANG_`)
   walks to a non-nil JS object via `goog.getObjectByName`."
  [path]
  (some? (js/goog.getObjectByName path)))

(defn lookup-ns-object
  "The live JS namespace object for `ns-name`, or nil.

   `ns-name` is a dotted string like
   \"seon.db\" / \"acme.helpers\"; resolved on `js/globalThis`. THE ONE munge
   scheme: splits on `.`, munges each segment via `cljs.core/munge`, and walks
   `gobj/get` from `js/globalThis` — exactly the path-prefix [[lookup-value]]
   uses before its final member read, factored out so member resolution and
   member enumeration ([[ns-fn-members]]) share one scheme. Never throws."
  {:malli/schema [:=> [:catn [::ns-name :string]] [:maybe :any]]}
  [ns-name]
  (reduce (fn [obj seg]
            (when obj (gobj/get obj (cljs.core/munge seg))))
          js/globalThis
          (str/split ns-name #"\.")))

(defn lookup-value
  "Resolve a fully-qualified symbol to its runtime value, or nil.

   Nil if unresolvable. The CLJS-bootstrap equivalent of JVM's
   `clojure.core/resolve`.

   Walks `js/globalThis` segment-by-segment, munging each ns segment
   via `cljs.core/munge` to match the JS names shadow-cljs emits.
   Handles reserved-word munge (`default` → `default$`, etc.).

   Works uniformly for:

   - Core fns precompiled into `out/client/main.js` by
     shadow-cljs (live at goog-global munged paths).
   - Agent-defined fns written by `cljs.js/eval-str` (cljs.js uses
     the same munge logic; lands at the same paths).

   Never throws — `nil`, keywords, strings, unqualified symbols all
   return nil. Callers that need a never-crash floor (the render
   dispatchers) treat nil as 'fall through to default'.

   Why this lives in `seon.eval`: it's the same concern as the
   analyzer-cache management here (`truly-undeclared?`,
   `seon.eval.bootstrap-cache/load-all!`, `init-bootstrap!`). Render and any
   other consumer call `(eval/lookup-value sym)` rather than each
   maintaining its own copy."
  {:malli/schema [:=> [:cat :any] [:maybe :any]]}
  [sym]
  (when (qualified-symbol? sym)
    (when-let [ns-obj (lookup-ns-object (namespace sym))]
      (gobj/get ns-obj (cljs.core/munge (name sym))))))

(defn ns-fn-members
  "The COMPILED function members of namespace `ns-name`.

   `ns-name` a string like \"acme.helpers\"; read from its LIVE ns object
   on `js/globalThis`, as
   `{simple-symbol <compiled-fn>}`. The inverse of [[lookup-value]]: that walks
   the munged path to ONE member; this resolves the ns object the SAME way
   ([[lookup-ns-object]] — one munge scheme) and reads back EVERY own enumerable
   property that is a function, demunging each property name to its CLJS simple
   symbol (`format_count` → `format-count`).

   This surfaces UNSPECCED helpers that the `:seon.fn` index can't (the index
   holds only `:malli/schema`-carrying vars), which is why `seon.render.sci`
   unions this with the index — an aliased call to an unspecced compiled helper
   (`h/format-count`) must resolve under SCI or the canvas falls to the unbounded
   compiled path.

   Returns `{}` when the ns isn't on `globalThis` (never-loaded / core stub) or
   on any failure. Never throws."
  {:malli/schema [:=> [:catn [::ns-name :string]] :map]}
  [ns-name]
  (try
    (if-let [ns-obj (lookup-ns-object ns-name)]
      (reduce (fn [m k]
                (let [v (gobj/get ns-obj k)]
                  (if (fn? v)
                    (assoc m (symbol (cljs.core/demunge k)) v)
                    m)))
              {}
              (js/Object.keys ns-obj))
      {})
    (catch :default e
      ;; code-as-data reflection over a live globalThis ns object — the
      ;; if-let above already handles the expected absent-ns case, so a
      ;; throw walking its props is OUR machinery failing (:core); the
      ;; caller still degrades to the same empty member map.
      (error/record! {:seon.error/raw e :seon.error/fault :core})
      {})))

;; ============================================================
;; Home-ns refer toolkit seed — declare the toolkit nses' analyzer `:defs` so
;; `setup-agent-ns!`'s `(ns <home> (:refer [wait complete …]))` analyzes
;; CLEANLY. The refer'd nses are HOST-bundled (emitted into the :client bundle,
;; live on globalThis) but are NOT `:bootstrap` analyzer entries, so a fresh
;; compile-state has no analyzer metadata for them — and the analyzer's
;; refer-check (`missing-use?`) raises `:undeclared-ns-form` ("Could not parse
;; ns form"). Seeding the `:defs` makes `missing-use?` false → the refer parses
;; with `::ok? true`, which is why setup-agent-ns! no longer needs a bare-`(ns)`
;; prime or a `(fn? complete)` probe.
;; ============================================================

(def ^:private home-ns-refer-toolkit-nses
  "Host-bundled nses whose vars the agent home ns `:refer`s UNQUALIFIED (see
   `setup-agent-ns!`). Only `:refer`'d nses need seeded `:defs` — `:as` aliases
   don't validate members at parse time. `seon.agent.lifecycle` is the sole
   `:refer`; data so adding a refer'd toolkit ns is a one-line edit."
  '[seon.agent.lifecycle])

(defn- seed-toolkit-refers!
  "Declare the LIVE fn members of each [[home-ns-refer-toolkit-nses]] ns to the
   analyzer in `compile-state`, so a home-ns `:refer` of those vars analyzes
   cleanly. Members are read from the live globalThis ns object via
   [[ns-fn-members]] (code-as-data — no hardcoded var list to drift); the
   seeded `:def` is the minimal `{:name fq-sym}` that satisfies the analyzer's
   `missing-use?` check. Idempotent (merge). Called from [[init-bootstrap!]] so
   every fresh/rebuilt compile-state carries it."
  [compile-state]
  (doseq [ns-sym home-ns-refer-toolkit-nses]
    (let [members (ns-fn-members (name ns-sym))]
      (when (seq members)
        (swap! compile-state update-in
               [:cljs.analyzer/namespaces ns-sym]
               (fn [m]
                 (-> (or m {})
                     (assoc :name ns-sym)
                     (update :defs merge
                             (into {} (for [sym (keys members)]
                                        [sym {:name (symbol (name ns-sym)
                                                            (name sym))}]))))))))))

(defn ns-data-members
  "The COMPILED NON-function members of namespace `ns-name`.

   `ns-name` a string like \"acme.helpers\"; read from its LIVE ns object
   on `js/globalThis`, as
   `{simple-symbol <value>}`. The data-const twin of [[ns-fn-members]]: that
   keeps own enumerable props that ARE functions; this keeps the ones that are
   NOT — a top-level `(def grounded-dims #{:a :b :c})` data constant (set, map,
   vector, string, number, keyword), demunging each property name the SAME way
   (`grounded_dims` → `grounded-dims`) via the ONE munge scheme
   ([[lookup-ns-object]]).

   Why this exists: `seon.render.sci`'s `expose-ns` exposes a canvas's own-ns
   (and required-ns) fn members so SCI can resolve them, but a canvas that
   references an own-ns NON-fn `(def …)` data value found no entry under SCI
   → 'Unable to resolve symbol' → the canvas fell to the unbounded compiled path.
   Merging these into the SCI namespace map alongside the fns resolves the
   constant so the canvas stays interrupt-bounded.

   `nil`-valued props are dropped (a SCI namespace map shouldn't carry a nil
   binding; `nil` reads identically whether bound or absent, and absence is the
   convention here). Compiler-internal own props (none are non-fn data on a
   normal CLJS ns object) would be demunged like any other key — harmless: a
   spurious binding is never referenced by a canvas body, and the whole path is
   fail-soft (degrades to compiled on any error).

   Returns `{}` when the ns isn't on `globalThis` (never-loaded / core stub) or
   on any failure. Never throws."
  {:malli/schema [:=> [:catn [::ns-name :string]] :map]}
  [ns-name]
  (try
    (if-let [ns-obj (lookup-ns-object ns-name)]
      (reduce (fn [m k]
                (let [v (gobj/get ns-obj k)]
                  (if (or (fn? v) (nil? v))
                    m
                    (assoc m (symbol (cljs.core/demunge k)) v))))
              {}
              (js/Object.keys ns-obj))
      {})
    (catch :default e
      ;; code-as-data reflection over a live globalThis ns object — the
      ;; if-let above already handles the expected absent-ns case, so a
      ;; throw walking its props is OUR machinery failing (:core); the
      ;; caller still degrades to the same empty member map.
      (error/record! {:seon.error/raw e :seon.error/fault :core})
      {})))

(defn- truly-undeclared?
  "Decide whether an `:undeclared-var` / `:undeclared-ns` analyzer
   warning is REAL (the symbol resolves nowhere) vs. a benign
   false-positive (bundled into the host runtime but the analyzer
   didn't see it because we run with `::analyze-deps? false`).

   `::analyze-deps? false` is load-bearing — see the docstring on
   `eval`. The analyzer warns on every cross-ns ref to a bundled var
   (e.g. `seon.db/transact!`); at runtime those resolve via
   `cljs.core/munge`'d paths on globalThis. So the warning alone
   isn't a failure signal.

   Resolution strategy, in order:

   1. If the warning targets a ns already in
      `:cljs.analyzer/namespaces`, treat as analyzer false-positive
      (the analyzer DID know — something else is going on, but it's
      not the agent's typo). Don't escalate.
   2. Otherwise check globalThis at the munged path. Try the warning's
      own prefix first, then `cljs.core` as a fallback for unqualified
      refs (analyzer reports prefix=current-ns for those; `(+ 1 2)` in
      `cljs.user` warns prefix=cljs.user suffix=+, and the var lives
      at `cljs.core._PLUS_`).
   3. If neither path resolves, truly undeclared → escalate.

   Step 0 (short-circuit before the cond): when the warning carries
   `:macro-present? true`, the analyzer found the symbol as a MACRO at
   macroexpand time even though the same name isn't a runtime var. For
   example `(defonce x …)` macroexpands to a body that mentions
   `cljs.core/exists?`, which is a macro-only name — `exists?` does
   NOT exist at `cljs.core.exists_QMARK_` on globalThis. The
   macroexpansion already succeeded; the warning is the analyzer
   over-reporting. Treat as benign by construction."
  [compile-state {:keys [prefix suffix] :as warning}]
  (cond
    ;; Macro-position symbols. If `:macro-present? true`, the
    ;; expander resolved it; the warning is over-reporting. Suppress.
    ;; Without this, `(defonce x …)`, `(comment …)` macroexpansions,
    ;; and any other macro that expands to a body referencing another
    ;; macro by symbol get falsely escalated as :compile errors.
    (:macro-present? warning)
    false

    ;; Strong short-circuit: ns has real :defs in the analyzer's
    ;; compile-state. If the var is registered on a populated ns and
    ;; the analyzer still warned, that's an analyzer bug, not the
    ;; agent's typo — don't escalate. Empty-ns case (`cljs.user`
    ;; before any defs) doesn't trip this, so `Let` still routes
    ;; through the globalThis check below.
    ;;
    ;; Nil-guard `prefix` / `suffix` — some warning shapes from
    ;; instrumented call sites carry one or both as nil; bare
    ;; `(symbol nil)` throws "no conversion to symbol". When either
    ;; is nil this branch cannot decide; fall through to globalThis.
    (let [ns-defs (when prefix
                    (:defs (get-in @compile-state
                                   [:cljs.analyzer/namespaces (symbol prefix)])))]
      (and ns-defs suffix (contains? ns-defs (symbol suffix))))
    false

    :else
    (let [munged-suffix (when suffix (cljs.core/munge (str suffix)))
          prefix-path   (when (and prefix munged-suffix)
                          (str (cljs.core/munge (str prefix)) "." munged-suffix))
          core-path     (when munged-suffix (str "cljs.core." munged-suffix))]
      ;; If we can't even build a probe path, the warning carries no
      ;; symbol-shaped target — treat as benign rather than escalating.
      (and munged-suffix
           (not (or (and prefix-path (resolves-on-globalthis? prefix-path))
                    (and core-path (resolves-on-globalthis? core-path))
                    (resolves-on-globalthis? munged-suffix)))))))

(defn ns-live-on-globalthis?
  "True when `ns-sym`'s munged JS object exists on globalThis — i.e.
   its JS has actually executed in this process. Two callers, two
   views of the same fact:

   [[guarded-load]]: a ns missing from the bootstrap bundle's index but
   live here is HOST-BUNDLED (compiled into out/client/main.js — `my.kb`,
   `seon.db`, `my.kb.shared`): store-indexed and rendered into the
   agent's prompt as code, but absent from shadow's `:bootstrap
   :entries`, so a bare `boot/load` throws `ns X not available`. Live ⇒
   no-op the load (empty `:js`) — the JS is already loaded. (This branch
   takes precedence over the DB-layer branch, so a compiled-but-unindexed
   ns is never re-evaled from its display rows.)"
  {:malli/schema [:=> [:cat :symbol] :boolean]}
  [ns-sym]
  (some? (js/goog.getObjectByName (str (cljs.core/munge ns-sym)))))

(defn registration-call-source?
  "TRUE when a stored `:seon.schema/source` is an eval-able call.

   An `(…)`
   registration call (an agent's `(seon.schema/register! …)` tee row)
   rather than a boot-indexed shape literal (`[:string {…}]`, `:keyword`).
   Only call-shaped schema rows are loadable; shape literals are rebuilt
   from the live registry each boot. The ONE rule both the DB-layer load
   (reconstitution) AND `seon.client`'s core-indexer use to distinguish a
   replayable register! call from a boot-indexed shape literal."
  {:malli/schema [:=> [:catn [::source :string]] :boolean]}
  [source]
  (str/starts-with? (str/trim (str source)) "("))

(defn ns-rows-in-db?
  "TRUE when `ns-sym` has a `:seon.ns/name` row in `db`.

   `ns-sym` a symbol. The
   discriminator the DB branch of [[guarded-load]] uses to decide a
   missing ns is agent-authored (loadable from the DB) rather than
   genuinely absent. `db` is a datahike db value (third-party boundary)."
  {:malli/schema [:=> [:catn [::db :any] [::ns-sym :symbol]] :boolean]}
  [db ns-sym]
  (boolean (seq (db/query '[:find ?e
                            :in $ ?ns
                            :where [?e :seon.ns/name ?ns]]
                          db (keyword ns-sym)))))

(declare stored-require-edges)

(defn- synthesized-ns-head
  "An `(ns …)` head string rebuilt from `ns-kw`'s STORED require-edges.

   For a member-bearing ns row with NO `:seon.ns/source` — the agent's
   HOME ns, whose aliases are wired at runtime by [[setup-agent-ns!]]
   (which runs AFTER the boot replay), never by an agent-eval'd `(ns …)`
   form. Without a head the reconstituted load unit evals from
   `cljs.user`: its defns land in the WRONG ns and an auto-resolved
   `::alias/kw` in a member source cannot even READ (live-caught
   2026-07-03: the first `::db/tx-data` home-ns fn to survive the C37
   gate failed the whole unit's replay). The M4 structural store carries
   exactly the needed facts — rebuild the `(:require …)` clause from
   `:seon.ns/require-edges` datoms."
  [db ns-kw]
  (let [specs (map (fn [{:seon.ns.require/keys [target alias refers refer-all?
                                                as-alias?]}]
                     (cond-> [(symbol (name target))]
                       ;; `:as-alias` edges MUST round-trip as `:as-alias` —
                       ;; a plain `:as` would LOAD the (possibly nonexistent)
                       ;; target on resume.
                       (and (symbol? alias) as-alias?)       (conj :as-alias alias)
                       (and (symbol? alias) (not as-alias?)) (conj :as alias)
                       (seq refers)    (conj :refer (vec (sort refers)))
                       refer-all?      (conj :refer :all)))
                   (sort-by :seon.ns.require/target
                            (stored-require-edges db ns-kw)))
        n     (symbol (name ns-kw))]
    (pr-str (if (seq specs)
              (list 'ns n (apply list :require specs))
              (list 'ns n)))))

(defn reconstitute-ns-source
  "One loadable source STRING for agent-authored namespace `ns-kw`.

   Read from the DB-layer rows (db-is-the-running-system PRD, shape A):

     `:seon.ns/source`  — the agent's `(ns … (:require [x :as y] …))`
        form VERBATIM. We use the stored ns form (not a rebuilt one)
        because it carries the `:as` aliases an aliased ref like `b/bv`
        needs — a rebuilt form without `:as b` breaks with `b is not
        defined`. When the row has NO source but DOES have fn/test
        members (the agent HOME ns — its requires are wired at runtime
        by `setup-agent-ns!`, which runs after the boot replay), the
        head is SYNTHESIZED from the stored `:seon.ns/require-edges`
        ([[synthesized-ns-head]]) so members land in their ns and
        `::alias/kw` literals read. A member-less sourceless row (a
        data-ns / schema-key stub, C30) stays headless — synthesizing
        `(ns seon.fn)`-style heads for keyword-namespace stubs would
        mint junk analyzer namespaces.
     + every CURRENT member source for the ns: `:seon.fn/source` rows,
       `:seon.schema/source` rows that pass [[registration-call-source?]]
       (agent `register!` calls, not boot shape literals), and
       `:seon.test/source` rows.

   Pure string CONCATENATION — no parsing. Same-ns forward refs resolve
   in one `eval-str` pass (LIVE-PROVEN). Member rows are deduped (a batch
   eval tees the same source onto every member it defined). `cljs.js`'s
   `*load-fn*` (the DB branch of [[guarded-load]]) returns this string so
   the compiler analyzes the requires and loads each transitive dep, in
   dependency order, with cycle detection + load-once — we write no
   ordering code here. `db` is a datahike db value (third-party boundary)."
  {:malli/schema [:=> [:catn [::db :any] [::ns-kw :keyword]] :string]}
  [db ns-kw]
  (let [ns-src (-> (db/query '[:find ?src
                               :in $ ?ns
                               :where
                               [?e :seon.ns/name ?ns]
                               [?e :seon.ns/source ?src]]
                             db ns-kw)
                   first first)
        member (fn [src-attr ns-attr]
                 (->> (db/query [:find '?src
                                 :in '$ '?ns
                                 :where
                                 ['?n :seon.ns/name '?ns]
                                 ['?m ns-attr '?n]
                                 ['?m src-attr '?src]]
                                db ns-kw)
                      (map first)))
        fns     (member :seon.fn/source     :seon.fn/ns)
        schemas (filter registration-call-source?
                        (member :seon.schema/source :seon.schema/ns))
        tests   (member :seon.test/source   :seon.test/ns)
        head    (or ns-src
                    (when (seq (concat fns tests))
                      (synthesized-ns-head db ns-kw)))]
    (->> (concat [head] fns schemas tests)
         (remove str/blank?)
         (map str/trim)
         (distinct)
         (str/join "\n\n"))))

(defn- guarded-load
  "`:load` fn for cljs.js — `boot/load` plus a post-load invariant
   re-assert and a host-bundle fallback. The bootstrap bundle's per-ns
   JS is goog.globalEval'd into the SHARED host runtime, so a load can
   re-run a library namespace's top-level side effects against live
   state. Live incident (2026-06-10, logs/pod.log 15:21): an agent eval
   of `(require '[malli.core :as m])` loaded the bundle's
   `malli.core$macros.js`, whose macro-mode compile of malli/core.cljc
   re-ran `(mr/set-default-registry! …)` against the live
   `malli.registry` — stomping the registry with a
   default-schemas-only snapshot and severing every seon-registered
   schema process-wide (`m/schema :seon.db/conn` → invalid-schema; broke
   replay, record-eval!, and POST /agents/new). Relinking after every
   load is idempotent and cheap; it runs synchronously before the
   compiled form continues, so no code observes the stomped registry.

   HOST-BUNDLE FALLBACK (fix-everything B4 + downstream bug #14,
   2026-06-11): `boot/load` throws synchronously (`ns X not available`,
   from shadow's `env/get-ns-info`) for any ns absent from BOTH the
   compile-state and the bootstrap bundle's index. That made
   `(:require [my.kb :as kb])` — the move the prompt teaches, since it
   renders core namespaces as code — fail as a `:cljs/analysis-
   error`, at define time AND on every replay of a stored `(ns …)` row
   (logs/pod-events.log: `replay of ns :my.kb.instruction failed:
   Could not require my.kb`; the failed ns row then cascaded into
   `Cannot set/read properties of undefined` for every def in the ns).
   When the missing ns is live on globalThis ([[ns-live-on-globalthis?]] —
   compiled into the host bundle), its JS is ALREADY loaded: answer the
   load with an empty `:js` source. cljs.js marks the ns loaded, the
   alias map wires up from the ns form's parse, and cross-ns var refs
   resolve at runtime via the same munged globalThis paths
   `truly-undeclared?` probes. NEVER re-eval host source here — that's
   the registry-stomp/shadowing class replay's core-skip exists to
   prevent.

   DB-LAYER BRANCH (db-is-the-running-system PRD, shape B): when the
   missing ns is NOT compiled (boot/load AND globalThis both failed) but
   has agent-authored `:seon.ns` rows in the DB, answer with its
   reconstituted source (`{:lang :clj :source …}`). cljs.js analyzes the
   `(ns … (:require …))` head, sees the dep edges, and recursively asks
   this same load-fn for each transitive require — so this ONE branch
   serves BOTH cold-boot resume AND live agent evals that require an
   agent ns. `relink-registry!` still runs after the eval (the DB branch
   evals agent defn/register/deftest source, which doesn't stomp the
   malli registry the way re-running bundle macro JS did, but the relink
   is idempotent + cheap, so keep it uniform). Macro loads (`:macros` rc)
   and genuinely-absent nses rethrow, preserving the legible
   `Could not require X <- ns X not available` error."
  [compile-state rc cb]
  (let [relink-cb (fn [result] (schema/relink-registry!) (cb result))]
    (try
      (boot/load compile-state rc relink-cb)
      (catch :default e
        ;; probe/dispatcher: this is NOT a terminal swallow — it either
        ;; RECOVERS (the ns IS available on globalThis / in the DB, just
        ;; not where boot/load looked) or RE-THROWS the legible
        ;; `Could not require X` up through cljs.js into `eval`'s outer
        ;; catch, which `record!`s it (wrapper-fault). Recording here
        ;; would misfire on the recoverable branches, so no record! —
        ;; the re-throw path is where it becomes data.
        ;; `*conn*` is root-bound at session start, but a load-fn can fire
        ;; before boot completes or in a conn-less test context — bind once
        ;; and only take the DB branch when a conn is actually present;
        ;; otherwise fall through to the legible `Could not require X` throw.
        (let [nm   (:name rc)
              conn db/*conn*]
          (cond
            (or (:macros rc) (not (symbol? nm)))
            (throw e)

            (ns-live-on-globalthis? nm)
            (cb {:lang :js :source ""})

            (and (some? conn) (ns-rows-in-db? @conn nm))
            (relink-cb {:lang   :clj
                        :source (reconstitute-ns-source @conn (keyword nm))})

            :else
            (throw e)))))))

;; ============================================================
;; Transient eval-scaffolding ns names — the SINGLE defs. Every site
;; that names one of these nses (the `cljs.js/eval-str` compile target,
;; the REPL default home, the `transient-ns-syms` program-graph
;; exclusion) references these defs, so renaming a scratch/result ns
;; can never silently leak a transient ns into the program graph.
;; ============================================================

(def user-ns-sym
  "The REPL default home namespace — eval's default `:ns` target."
  'cljs.user)

(def dynamic-ns-sym
  "The reserved `cljs.js/eval-str` compilation-target namespace."
  'seon.dynamic)

(defn ^:async ^:private ensure-analyzer-ns!
  "Idempotently guarantee `ns-sym` has a COMPLETE `:cljs.analyzer/namespaces`
   entry in `compile-state` before a `def`/`defn` is evaluated into it.

   Why this exists (root-caused + LIVE-PROVEN 2026-06-17): under
   `:def-emits-var true`, `cljs.analyzer`'s `parse 'def` builds a
   `:the-var` AST node via `var-ast`, which `(when-some [var-ns (:ns var)]
   …)` — and `var-ast` returns nil when `resolve-var` yields a var with
   `:ns nil`. That happens for ANY `def` whose TARGET ns has no
   `::namespaces` entry: `get-namespace` (analyzer.cljc:592) returns the
   entry, OR a minimal `{:name 'cljs.user}` for `cljs.user` ONLY, else
   nil. A nil current-ns ⇒ `:ns nil` ⇒ `var-ast` nil ⇒ the emitted
   `:the-var` node lacks `:sym`/`:meta` ⇒ `emit* :the-var`'s
   `{:pre [(ana/ast? sym) (ana/ast? meta)]}` throws
   `Assert failed: (ana/ast? sym)`. So `cljs.user` never fails but an
   arbitrary `(ns my.new.thing)` / `(in-ns 'foo)` target that was never
   set up does — intermittently looking like a compiler bug.

   The robust, fork-free fix is to PRIME the entry the canonical way: a
   real `(ns <ns-sym>)` eval runs the analyzer's `ns-side-effects`, which
   builds the full entry. Idempotent: re-eval of `(ns x)` is
   non-destructive of x's own defs (code-as-data doctrine), and we
   no-op entirely when a complete entry already exists. A minimal hand-
   rolled `::namespaces` map is NOT sufficient (missing internal keys —
   PROVEN), so we go through the real path. `cljs.user` and any ns with a
   `:name`-bearing entry are skipped. Best-effort: a prime failure is
   swallowed (the subsequent eval surfaces the real error)."
  [compile-state ns-sym]
  (let [entry (get-in @compile-state [:cljs.analyzer/namespaces ns-sym])]
    (when-not (or (= user-ns-sym ns-sym)
                  (and entry (:name entry)))
      (await
        (js/Promise.
          (fn [resolve _reject]
            (try
              (cljs/eval-str compile-state (str "(ns " ns-sym ")") nil
                {:eval cljs/js-eval :ns user-ns-sym :context :statement}
                (fn [_] (resolve nil)))
              ;; probe: best-effort ns prime — a prime failure is expected
              ;; (the subsequent real eval surfaces the actual error); the
              ;; absence of a primed entry is not itself an error.
              (catch :default _ (resolve nil)))))))))

;; ============================================================
;; `result/<id>` vars (transcript-redesign-2026-06-18). Each
;; SUCCESSFUL eval auto-binds its value as a PLAIN VAR `result/<id>`
;; (the id shown after its `=>` in the transcript). The agent writes
;; `result/auC-2606181147` directly — this is the SOLE value-reuse
;; surface (it subsumes a `(result …)` call and REPL `*1 *2 *3`).
;;
;; Two registrations make a bare `result/<id>` resolve with ZERO
;; `:undeclared-var` warning — the SAME def-into-a-ns mechanism cljs.js
;; uses for an agent's own `(defn …)`:
;;
;;   1. Runtime value: `globalThis.result.<munged-id>` = V, where
;;      `<munged-id>` = `(cljs.core/munge <id>)` (hyphen→underscore,
;;      matching how `lookup-value`/shadow emit JS names). `lookup-value`
;;      and the analyzer's emitted `result.<munged-id>` read the same
;;      slot.
;;   2. Analyzer def: an entry at
;;      `[:cljs.analyzer/namespaces 'result :defs <id-sym>]` (plus the
;;      `result` ns's own `:name`) so `:def-emits-var`'s `var-ast`
;;      resolves the ref and the analyzer never warns.
;;
;; The `result` ns is RESERVED (no real code namespace is named
;; `result`; live-checked: `globalThis.result` is absent at boot). The
;; agent's home ns no longer defs any `result` symbol, so nothing
;; shadows the `result` NAMESPACE — a bare `result/<id>` resolves
;; top-level (`result.<id>`) with no alias setup (LIVE-PROVEN
;; 2026-06-18: a fresh agent ns reads `result/<id>` cleanly with no
;; require-alias).
;;
;; Session cap: keep the last `result-vars-cap` ids; prune older ones
;; (undef from BOTH globalThis and the analyzer) so memory stays
;; bounded. Resets on process restart — the live values and analyzer
;; handles do not survive a new process. A reference to a pruned /
;; prior-session id is a GRACEFUL MISS (see the `result/*` special-case
;; in `raw-eval`), never a raw undeclared error.
;;
;; Constants + the reference predicate + the graceful-miss message
;; live HERE (above `raw-eval`, which consumes them); the binding fns
;; (`bind-result-var!`) live below, next to the eval pipeline.
;; ============================================================

(def result-ns-sym
  "The reserved namespace that holds the `result/<id>` value vars."
  'result)

(def ^:private result-vars-cap
  "Max live `result/<id>` vars kept per session. Older ids are pruned
   (undef'd from globalThis + the analyzer) to bound memory. Override
   with SEON_EVAL_RESULT_VARS_CAP."
  (config/result-vars-cap))

(defn result-live?
  "True when the bounded runtime owns `result/<id>`."
  {:malli/schema [:=> [:catn [::id :string]] :boolean]}
  [id]
  (let [robj (js/Reflect.get js/globalThis (str result-ns-sym))]
    (boolean
      (and robj
           (js/Reflect.has robj (cljs.core/munge id))))))

(defn result-var-ref?
  "TRUE when `form-str` is a single bare `result/<id>` reference.

   The agent reading a prior eval's value var. Used to (a) eval such a
   form in `:expr` context (a bare top-level var read emits NOTHING in
   the default `:statement` context — see the file's REPL-semantics
   gotcha), and (b) recognise the graceful-miss target. NOT a call, NOT
   a multi-form string: exactly one symbol whose namespace is `result`."
  {:malli/schema [:=> [:catn [::form-str :string]] :boolean]}
  [form-str]
  (let [s (str/trim (str form-str))]
    (boolean
      (and (seq s)
           (try
             (let [rdr  (reader-types/string-push-back-reader s)
                   sym  (tools-reader/read {:eof ::eof :read-cond :allow} rdr)
                   nxt  (tools-reader/read {:eof ::eof :read-cond :allow} rdr)]
               ;; EXACTLY one form, a `result/<id>` symbol, nothing after.
               ;; `=` (not `identical?`): the reader reconstructs the eof
               ;; marker, so a value `=` to ::eof is the EOF, matching the
               ;; `defn-form?`/`form-count` convention in this file.
               (and (symbol? sym)
                    (= "result" (namespace sym))
                    (= ::eof nxt)))
             ;; probe: an unreadable string simply isn't a `result/<id>`
             ;; reference — return false (expected non-match, not a defect).
             (catch :default _ false))))))

(defn result-miss-message
  "The graceful-miss VALUE for a dead `result/<id>` reference.

   Its var is no
   longer live (pruned past the session cap, or from a PRIOR SESSION —
   the process that held it is gone). Errors-are-values: a miss reads
   this string instead of throwing a raw `:undeclared-var`."
  {:malli/schema [:=> [:catn [::ref-sym :string]] :string]}
  [ref-sym]
  (str ref-sym " isn't live (a prior session, or pruned past the last "
       result-vars-cap " results) — re-run its form to recompute it. "
       "Only recent results stay referenceable as `result/<id>` vars."))

;; Defined later; declared here so `raw-eval`'s not-defined branch can append
;; the "did you mean `plan/plan!`?" hint.
(declare home-ns-alias-hint)

;;; The eval-result ENVELOPE (in-memory, never transacted whole):
;;;   {::ok? true  ::value v ::ending-ns sym}
;;;   {::ok? false :seon/error <seon.error/->map>}
;;;   {::ok? false ::pending-promise <js/Promise>}   ; maybe-await-value only
;;; `::ok?` is registered in seon.agent (the persisted eval attr — same
;;; meaning); `::value` / `::pending-promise` carry arbitrary runtime
;;; values / a live Promise, so they are deliberately unregistered
;;; (three-tier rule: the DB stores projections, not these).
(schema/register! ::ending-ns :symbol)

;;; `eval` opts (C21) — ::starting-ns is the TARGET ns an eval runs in:
;;; a SYMBOL, the input twin of ::ending-ns. Deliberately NOT the
;;; persisted :keyword attr ::ns (same type-clash reasoning that named
;;; ::ending-ns).
(schema/register! ::starting-ns :symbol)
(schema/register! ::analyze-deps? :boolean)
(schema/register! ::timeout-ms :int)

(defn ^:async ^:private raw-eval
  "Internal — returns a Promise that resolves with
   {::value v ::ending-ns ns} or rejects with the error. The public
   `eval` catches both.

   Warning capture is per-fiber via `warnings-als` (Node
   AsyncLocalStorage). The root handler installed by
   `install-warning-dispatcher!` reads each fiber's bucket via
   `.getStore`. Concurrent `raw-eval` calls — including interleaved
   across awaits — get fully isolated warning buckets. No global
   `set!`, no restore. Multi-agent safe by construction.

   `cljs.js`'s own `:warning-handlers` option doesn't fully replace
   the analyzer chain (per the prior comment that has now been
   addressed at the dispatcher layer): we own
   `ana/*cljs-warning-handlers*` at boot and route every fired warning
   through the ALS-aware dispatcher. The analyzer therefore never
   raises an :error for undeclared-var; our captured bucket is the
   authoritative signal.

   Warning shape captured into the bucket:
     `{:prefix … :suffix … :macro-present? … :seon.eval/warning-type …}`.
   After eval, any warning whose target doesn't resolve through the
   strategy in `truly-undeclared?` (which suppresses
   `:macro-present? true`) is promoted to a `:compile`-kind error."
  [compile-state form-str ns-sym analyze-deps?]
  ;; Prime the target ns's analyzer entry FIRST (idempotent) so a `def`
  ;; into a never-set-up ns can't trip `var-ast`→nil→`(ana/ast? sym)`.
  (await (ensure-analyzer-ns! compile-state ns-sym))
  (let [warnings   (atom [])
        result-ref? (result-var-ref? form-str)
        ;; transcript-redesign-2026-06-18: a bare top-level `result/<id>`
        ;; read emits NOTHING in `:statement` context (a bare var read is
        ;; a no-op statement — the file's REPL-semantics gotcha), so the
        ;; value would never return. `:expr` context emits + returns the
        ;; value (def/ns/defn all still work under `:expr`, live-proven).
        ;; Scoped strictly to the `result/<id>` var read.
        context    (if result-ref? :expr :statement)]
    (js/Promise.
      (fn [resolve reject]
        (.run warnings-als warnings
          (fn []
            (cljs/eval-str compile-state form-str dynamic-ns-sym
              {:eval          cljs/js-eval
               :load          (partial guarded-load compile-state)
               :ns            ns-sym
               :context       context
               :def-emits-var true
               :analyze-deps  analyze-deps?}
              (fn [{:keys [error value ns]}]
                (cond
                  ;; transcript-redesign-2026-06-18: a bare `result/<id>`
                  ;; reference whose var is no longer live (pruned past
                  ;; the session cap, or from a PRIOR SESSION) is a
                  ;; GRACEFUL MISS — resolve with the helpful re-run VALUE
                  ;; (errors-are-values), never a raw `:undeclared-var`.
                  ;; A LIVE `result/<id>` has its analyzer def + globalThis
                  ;; slot, so `truly-undeclared?` never fires for it; only
                  ;; misses reach this branch.
                  (and result-ref?
                       (some (partial truly-undeclared? compile-state) @warnings))
                  (resolve {::value (result-miss-message (str/trim (str form-str)))
                            ::ending-ns ns-sym})

                  (some (partial truly-undeclared? compile-state) @warnings)
                  (let [{:keys [prefix suffix] :as w}
                        (first (filter (partial truly-undeclared? compile-state)
                                       @warnings))
                        sym  (str prefix "/" suffix)
                        kind (:seon.eval/warning-type w)
                        ;; Crystal-clear: name the EXACT symbol + the EXACT
                        ;; next action. A FRESH, confused LLM must know this
                        ;; ran NOTHING and what to do about it.
                        ;; Alias hint: a bare function that failed to resolve may
                        ;; be a library function the agent should reach through a
                        ;; home-ns alias (`plan!` → `plan/plan!`). Steer it
                        ;; back to the alias instead of toward defining/typo —
                        ;; this is the missing hint that, absent, sent a live
                        ;; agent on a destructive ns-switch detour.
                        hint (when (= kind :undeclared-var)
                               (home-ns-alias-hint (str suffix)))
                        msg  (if (= kind :undeclared-ns)
                               (str "`" sym "` — that NAMESPACE is not loaded "
                                    "(typo, or you haven't required it). This "
                                    "form ran NOTHING. Require it, or fix the "
                                    "name, then re-eval.")
                               (str "`" sym "` is not defined. This form ran "
                                    "NOTHING. "
                                    (if hint
                                      (str "Did you mean `" hint "`? — that "
                                           "home-ns function; use that form, do "
                                           "NOT switch namespace.")
                                      (str "You have not defined it (or its "
                                           "defn failed earlier, or it's a "
                                           "typo). Define it first, then this "
                                           "runs."))))]
                    (reject
                      (ex-info msg
                               {:seon.error/kind :compile
                                :seon.eval/warning-type kind
                                :seon.eval/undeclared sym
                                :seon.eval/warning (dissoc w :seon.eval/warning-type)})))

                  error
                  (reject error)

                  :else
                  (resolve {::value value ::ending-ns ns}))))))))))

(defn ^:async eval
  "Evaluate a string of CLJS in the agent's persistent compile-state.
   Returns:
     {::ok? true  ::value v ::ending-ns ns}   on success
     {::ok? false :seon/error <seon.error/->map>}  on any failure
   Never throws; never rejects.

   Opts (all optional):
     ::starting-ns    target namespace (default `cljs.user`). The
                    returned `::ending-ns` is the ENDING ns — `(ns
                    other)` forms switch it. Callers that want
                    REPL-style ns-tracking feed `::ending-ns` from one
                    call into the next call's `::starting-ns` arg.
     ::analyze-deps? whether cljs.js should recursively analyze refs
                    in the form (default `false`). The bootstrap
                    bundle only contains `cljs.core`, so any form
                    calling `seon.db/*` or other non-bundled nses
                    MUST run with this off — otherwise the analyzer
                    dies on `ns seon.db not available`. With it off,
                    the analyzer emits :undeclared-var warnings but
                    still emits JS that resolves at runtime via the
                    already-loaded globalThis vars (the `:client`
                    bundle's emission).
     ::timeout-ms    override the default timeout for this call.

   For setup forms that need cljs.core's macro refers wired up via
   `(ns …)` analysis, pass `::analyze-deps? true` explicitly.

   A form that hangs on a never-resolving Promise returns
     {::ok? false :seon/error {:seon.error/message \"eval timed out after Nms\" …}}
   The underlying form keeps running — see `race-timeout` docstring."
  {:malli/schema
   [:function
    [:=> [:catn [::compile-state :any] [::form-str :any]] :any]
    [:=> [:catn [::compile-state :any] [::form-str :any]
          [::opts [:map
                   [::starting-ns   {:optional true} ::starting-ns]
                   [::analyze-deps? {:optional true} ::analyze-deps?]
                   [::timeout-ms    {:optional true} ::timeout-ms]]]]
     :any]]}
  ([compile-state form-str]
   (eval compile-state form-str {}))
  ([compile-state form-str {::keys [starting-ns analyze-deps? timeout-ms]}]
   (try
     (let [ns      (or starting-ns user-ns-sym)
           ms      (or timeout-ms default-timeout-ms)
           raced   (await (race-timeout
                            (raw-eval compile-state form-str ns
                                      (boolean analyze-deps?))
                            ms))]
       (if (identical? raced timeout-sentinel)
         {::ok? false
          :seon/error (error/->map
                        (js/Error.
                          (str "eval timed out after " ms "ms (form still "
                               "running in background; JS has no preemption — "
                               "Phase 2 worker_thread or Phase 3 wasmtime "
                               "needed for hard cancellation)")))}
         {::ok? true ::value (::value raced) ::ending-ns (::ending-ns raced)}))
     (catch :default e
       ;; The `seon.eval` compile/eval conduit: a throw from raw-eval is
       ;; where an AGENT form's failure first surfaces, so classify by the
       ;; error's OWN content (wrapper-fault) rather than blaming the
       ;; conduit — an agent compile typo / a propagated agent-caused
       ;; violation → :agent (never gates); a genuine core compile-pipeline
       ;; bug stays :core (loud). recorded? skips an inner funnel's datom
       ;; (the async wrapper arms already record function rejections). The
       ;; return contract is byte-unchanged.
       (when-not (error/recorded? e)
         (error/record! {:seon.error/raw   e
                         :seon.error/fault (instrument/wrapper-fault e :core)}))
       {::ok? false :seon/error (error/->map e)}))))

;; ============================================================
;; Per-agent namespace setup. Run once per agent at boot. Primes the
;; agent's home ns with atoms + accessor fns. Probe-validated patterns:
;; atoms for state (bare value-def reads don't resolve cross-eval-str),
;; fns for read sugar.
;; ============================================================

;; ============================================================
;; Results store. The one runtime value lives at
;; `globalThis.result.<munged-id>`, the same slot emitted for the
;; agent-facing `result/<id>` analyzer handle. Any value type can live
;; there, including opaque Datahike objects and Promises; no
;; pr-str/read-string round trip is involved. The reserved object's own
;; enumerable property order is the oldest-first eviction order, so there is
;; no second atom mirroring which live results exist. Eval ids are valid
;; symbol names with a non-numeric prefix, so they follow JavaScript's string
;; insertion-order branch rather than the integer-index ordering branch.
;; ============================================================

(defn lookup-result
  "The live value of a prior eval, keyed by its `result/<id>`.

   The id on its value line
   in the transcript (string or keyword). Reads the same bounded
   `globalThis.result.<id>` value slot as the analyzer-emitted var, so
   any value type round-trips. INTERNAL reader — the agent's
   value-reuse surface is the `result/<id>` var;
   `lookup-result` is used by core code that needs an eval's live value
   programmatically (e.g. seon.agent.message's batch-failure check).

   ERRORS ARE VALUES: a miss never throws — it returns an error map
   that says exactly why there is no value:

   - the eval row exists but its bounded runtime slot does not (evicted or
     from a prior process);
   - the eval ERRORED (it never produced a value);
   - no such eval id exists (typo)."
  {:malli/schema [:=> [:catn [::id :any]] :any]}
  [id]
  (let [id-str (if (keyword? id) (name id) (str id))
        munged (cljs.core/munge id-str)
        robj   (js/Reflect.get js/globalThis (str result-ns-sym))]
    (if (result-live? id-str)
      (js/Reflect.get robj munged)
      (let [row (try (db/entity {:seon.db/ref [:seon.eval/id id-str]})
                     ;; probe: a lookup-ref to a NON-EXISTENT eval id
                     ;; throws in datahike — that IS the expected
                     ;; "no such id" signal (an agent typo'd an id); the
                     ;; nil row falls to the legible miss messages below.
                     (catch :default _ nil))]
        (cond
          (nil? (:seon.eval/id row))
          {:seon.eval/ok? false
           :seon.error/message
           (str "no eval " (pr-str id-str) " exists — check the id against "
                "the value lines in your transcript ((seon.agent/evals) "
                "has the full log).")}

          (false? (:seon.eval/ok? row))
          {:seon.eval/ok? false
           :seon.error/message
           (str "eval " id-str " ERRORED — it produced no value; its error "
                "text is in your transcript.")}

          :else
          {:seon.eval/ok? false
           :seon.error/message
           (str "eval " id-str " isn't live — its bounded result slot was "
                "evicted or belonged to a prior process. Re-run the form "
                "(its source is on the eval's prompt line) to recompute it.")})))))

;; ============================================================
;; `result/<id>` values + analyzer handles — binding side. Constants +
;; the reference predicate + the graceful-miss message live ABOVE
;; `raw-eval` (it needs them); the binding fns live here, near the eval
;; pipeline that calls them.
;; ============================================================

(defn- result-globalthis-obj
  "The live `globalThis.result` object, creating it on first use."
  []
  (or (js/Reflect.get js/globalThis (str result-ns-sym))
      (let [o (js/Object.create nil)]
        (js/Reflect.set js/globalThis (str result-ns-sym) o)
        o)))

(defn- replace-live-result!
  "Replace an already-live `result/<id>` value without changing its age.

   Used when a pending Promise settles. Returns true only while the bounded
   runtime slot is still live; an evicted pending result never resurrects
   itself or displaces a newer eval."
  [id value]
  (try
    (let [munged (cljs.core/munge id)
          robj   (js/Reflect.get js/globalThis (str result-ns-sym))]
      (boolean
        (when (and robj
                   (js/Reflect.has robj munged))
          (js/Reflect.set robj munged value))))
    (catch :default e
      (error/record! {:seon.error/raw e :seon.error/fault :core})
      false)))

(defn- unbind-result-runtime-key!
  "Remove one result by its emitted JavaScript property key.

   The runtime object's enumerable keys are the live-result authority. The
   analyzer marker carries that same key solely so cleanup can remove the
   corresponding compiler handle without trying to reverse CLJS munging."
  [compile-state runtime-key]
  (try
    (let [robj (js/Reflect.get js/globalThis (str result-ns-sym))]
      (when robj (js/Reflect.deleteProperty robj runtime-key)))
    (catch :default e
      (error/record! {:seon.error/raw e :seon.error/fault :core})))
  (try
    (swap! compile-state update-in
           [:cljs.analyzer/namespaces result-ns-sym :defs]
           (fn [defs]
             (into {}
                   (remove (fn [[definition-id definition]]
                             (or (= runtime-key
                                    (:seon.eval/result-runtime-key definition))
                                 ;; Hot reload can leave handles created before
                                 ;; the runtime-key marker existed. The emitted
                                 ;; symbol still derives the same exact key.
                                 (= runtime-key
                                    (cljs.core/munge (name definition-id))))))
                   (or defs {}))))
    (catch :default e
      (error/record! {:seon.error/raw e :seon.error/fault :core}))))

(defn- unbind-result-var!
  "Remove a single `result/<id>` var — undef from globalThis AND the
   analyzer `result` ns defs. Best-effort; never throws."
  [compile-state id]
  (unbind-result-runtime-key! compile-state (cljs.core/munge id)))

(defn bind-result-var!
  "Store a successful eval's live value as the var `result/<id>`.

   Sets `globalThis.result.<munged-id>` and registers `<id>` in the
   `result` ns's analyzer defs in `compile-state`, so a later bare
   `result/<id>` resolves with no undeclared-var warning and
   [[lookup-result]] reads the identical slot. The reserved runtime object's
   own enumerable keys provide insertion order for the session cap; pruning
   removes the value plus analyzer handle together.

   Failed evals never call this — there is no value to bind. Soft-fails
   (logs + ignores) so a bind hiccup never breaks the eval pipeline."
  {:malli/schema [:=> [:catn [::compile-state :any] [::id :string] [::value :any]]
                  :nil]}
  [compile-state id v]
  (try
    (let [munged (cljs.core/munge id)
          robj (result-globalthis-obj)]
      ;; 1. Runtime value at globalThis.result.<munged-id>.
      ;; Delete first so rebinding an existing id moves that property to the
      ;; newest position under JavaScript's specified own-key order.
      (when (js/Reflect.has robj munged)
        (js/Reflect.deleteProperty robj munged))
      (js/Reflect.set robj munged v)
      ;; 2. Analyzer def — same shape cljs.js writes for an agent `def`
      ;;    (`:name` is the only key `var-ast` needs to emit a clean
      ;;    `:the-var` node; we add `:result-var? true` as a marker).
      (swap! compile-state
             (fn [s]
               (-> s
                   (assoc-in [:cljs.analyzer/namespaces result-ns-sym :name]
                             result-ns-sym)
                   (assoc-in [:cljs.analyzer/namespaces result-ns-sym :defs
                             (symbol id)]
                             {:name (symbol (str result-ns-sym) id)
                              :seon.eval/result-var? true
                              :seon.eval/result-runtime-key munged}))))
      ;; 3. Prune oldest live runtime keys. The runtime object is already the
      ;;    value authority; deriving the bounded key set from it removes the
      ;;    former process-global mirror and its drift modes.
      (let [runtime-keys (vec (js/Object.keys robj))
            over (max 0 (- (count runtime-keys) result-vars-cap))]
        (doseq [runtime-key (subvec runtime-keys 0 over)]
          (unbind-result-runtime-key! compile-state runtime-key))))
    (catch :default e
      ;; OUR result-value bind (globalThis set + analyzer defs) failing is a
      ;; core defect. Roll back both local representations so a partial bind
      ;; cannot masquerade as live, then record it; the eval pipeline still
      ;; returns nil (a benign miss).
      (unbind-result-runtime-key! compile-state (cljs.core/munge id))
      (error/record! {:seon.error/raw e :seon.error/fault :core})))
  nil)

;; ============================================================
;; Config-driven agent-init CP-1 — home-ns wiring + toolkit (agent-level
;; attrs on the agent entity). Nothing reads these yet (purely additive).
;; ============================================================

;; home-requires: set-once, read WHOLE by setup-agent-ns!, never queried
;; per-element → the ONE decision-22(c) serialized-blob case. The spec's
;; `[:vector :seon.agent.home/require-spec]` does NOT bridge — the seon.db bridge maps a
;; `:vector`'s CHILD to a datahike column, and
;; `:seon.agent.home/require-spec` (a `:cat`)
;; has no column type, so it hard-rejects (`:seon.db/unbridgeable-attrs`).
;; The EDN-blob path the spec names ("stored as a pr-str'd EDN string") is
;; the bridge's MIXED-`:or` branch (db/internal `form->datahike-value-type`
;; `:or`) — the SAME mechanism `:seon.render.canvas/content` /
;; `:seon.render/ai` use. So this is a mixed `:or` (the require-spec vector
;; arm + a scalar `:symbol` arm) → `:db.type/string` EDN, decoded on read
;; via `seon.db/decode-edn-value`. Default = the live
;; [[seon.agent.home/home-ns-require-specs]] list (verbatim).
(schema/register! ::home-requires
  [:or {:default home/home-ns-require-specs}
   [:vector :seon.agent.home/require-spec]
   :symbol])

(defn home-ns-alias-hint
  "The correctly-aliased home-ns form for a bare function that failed.

   Given a bare function NAME that failed to resolve (e.g. \"plan!\",
   \"user\", \"complete\") — the form the agent SHOULD have
   written — a string like \"plan/plan!\" — or nil if no home-ns alias/refer
   exposes that name. Derived from
   [[seon.agent.home/home-ns-require-specs]] (the single source of which
   aliases/refers every agent's home ns carries) so it can never drift from
   what the agent's prompt teaches:

     - `[ns :as alias]` — if `ns`'s live publics ([[ns-fn-members]]) include
       the name, suggest `alias/<name>` (the function lives behind the alias).
     - `[ns :refer [functions…]]` — if `name` is a refer'd function, suggest the
       fully-qualified `ns/<name>` (works from ANY ns; the bare refer only
       resolves inside the home ns)."
  {:malli/schema [:=> [:catn [::short-name :string]] [:maybe :string]]}
  [short-name]
  (let [nm (symbol short-name)]
    (some (fn [spec]
            (let [ns-sym (first spec)]
              (cond
                (and (= :refer (second spec)) (some #(= % nm) (nth spec 2)))
                (str (name ns-sym) "/" short-name)

                (and (= :as (second spec))
                     (contains? (ns-fn-members (name ns-sym)) nm))
                (str (name (nth spec 2)) "/" short-name))))
          home/home-ns-require-specs)))

(def authored-ns-require-nses
  "The [[seon.agent.home/home-ns-require-specs]] NAMESPACES whose short alias an agent's
   AUTHORED (non-home) namespace also carries — the data + function namespaces
   every authored ns reaches through (`db/`, `plan/`, `message/`, `schema/`).
   A SELECTION over [[seon.agent.home/home-ns-require-specs]] (the single source of the
   alias↔ns mapping) — the alias names are never re-spelled here. Excludes
   `seon.agent` (the home orchestration alias) and the lifecycle `:refer`
   functions (home-ns only). The `my.*` toolkit stays FULL-QUALIFIED (`my.ui/…`),
   no alias."
  '#{seon.db my.plan seon.agent.message seon.schema})

(def authored-ns-require-specs
  "The `(:require …)` specs merged into an agent-authored `(ns …)` form
   ([[augment-ns-source]]) so its short aliases resolve because they are
   GENUINELY required (no magic) — the `:as` specs of
   [[seon.agent.home/home-ns-require-specs]] selected by
   [[authored-ns-require-nses]]. Derived, never a second hardcoded copy."
  (filterv (fn [spec]
             (and (vector? spec)
                  (= :as (second spec))
                  (contains? authored-ns-require-nses (first spec))))
           home/home-ns-require-specs))

(defn- authored-ns-alias-names
  "Comma-joined `:as` alias names from [[authored-ns-require-specs]]
   (e.g. \"db, plan, message, schema\") — for the real-require narration note."
  []
  (str/join ", " (map (fn [spec] (name (nth spec 2))) authored-ns-require-specs)))

;; Forward ref used by the eval tee below. Agent birth persists the durable
;; home declaration; runtime setup only reconstructs analyzer state.
(declare transient-ns-syms)

(defn ^:async setup-agent-ns!
  "Create + initialize the agent's home namespace.

   Returns the agent-ns
   symbol (same as the input). Idempotent.

   Evaluates ONE `(ns <home> (:require …))` form that aliases the function + data
   namespaces the context teaches — so the agent's reflexive
   `(message/user …)` / `(message/agent …)` / `(wait …)` / `(complete …)` /
   `(terminate …)` AND the short-aliased `(schema/register! …)` /
   `(db/query …)` / `(db/transact! …)` / `::db/…` data forms all resolve
   without fully-qualifying:

     (ns <home>
       (:require [seon.agent.message :as message]
                 [seon.agent :as agent]
                 [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                 [seon.schema :as schema]
                 [seon.db :as db]
                 [my.plan :as plan]))

   The home ns defs NOTHING beyond these requires: a `result` def would shadow
   the reserved `result` NAMESPACE holding the `result/<id>` value vars, so the
   ns stays clean and `result/<id>` resolves top-level (no alias).

   For the agent's own id, use `(seon.db/current-agent-id)` — the core provides
   it via the ALS dynvar bound at turn entry. `:current-ns` is derived at read
   time from the latest `:seon.eval/ns` datom (reactive-context).

   `::analyze-deps? true` so the `(ns …)` form analyzes cljs.core's refer map
   and wires the implicit macro refers (defn, str, atom, …) for subsequent
   forms in the new ns.

   The `:refer [wait complete …]` against host-bundled `seon.agent.lifecycle`
   analyzes CLEANLY because [[seed-toolkit-refers!]] (run in [[init-bootstrap!]])
   declared that ns's `:defs` into every compile-state. The clean emit also
   materializes the home ns's runtime JS object, so a later `(defn …)` has a
   path to write into — hence NO bare-`(ns)` prime and NO `(fn? complete)`
   probe. A non-`::ok?` result now signals a REAL failure (the seed missing, or a
   toolkit ns gone) and throws.

   This is deliberately process-local reconstruction only. Durable home source
   and require-edge facts are part of `seon.agent`'s atomic birth transaction;
   resume never writes a second projection of analyzer state."
  {:malli/schema
   [:=> [:catn [::compile-state :any] [::agent-ns-sym :any] [::agent-id :any]] :any]}
  [compile-state agent-ns-sym agent-id]
  (let [setup-src (home/home-ns-form agent-ns-sym
                                     (home/home-requires-for agent-id))
        r (await (eval compile-state setup-src
                       {::starting-ns user-ns-sym ::analyze-deps? true}))]
    (when-not (::ok? r)
      (throw (ex-info
               (str "setup-agent-ns! failed — the home-ns require/refer did not "
                    "analyze cleanly for " agent-ns-sym ". seed-toolkit-refers! "
                    "(in init-bootstrap!) must declare the refer'd toolkit "
                    "defs into the compile-state.")
               {:agent-ns agent-ns-sym :result r})))
    agent-ns-sym))

;; ============================================================
;; eval-batch! — the REPL harness primitive. Takes parsed pairs from
;; seon.repl.internal/parse-forms; evaluates each in the agent's compile-state
;; with PARTIAL-FAILURE semantics (form N+1 always runs, even if N
;; failed); persists each as a :seon.eval entity; and binds each successful
;; live value at its capped `result/<id>` handle. Returns the ordered vector
;; of eval-id strings.
;;
;; Per spec-02 §2.5: every form is safe-by-default. The eval surface
;; never throws; the agent session is never killed by a bad form.
;; ============================================================

(defn ^:async ^:private maybe-await-value
  "Agent-REPL ergonomic: if a form returns a Promise (because the form
   called a ^:async fn like `seon.db/transact!`), await it and return
   the resolved value. Agents don't write `await` — that's a
   CLJS-1.12.145 syntax they don't see. This makes calls to seon.db/*
   feel synchronous from inside agent forms.

   Bounded by the immutable default OR the explicit wrapper returned by
   [[budget]]. A Promise that exceeds the bound is NOT dropped: it is
   handed back as `::pending-promise` so the caller binds the live handle at
   `result/<id>` (re-reference auto-resolves it later). A `(defer …)`
   wrapper opts out of awaiting entirely and takes the same
   `::pending-promise` path immediately.

   Returns {::ok? true  ::value v}     on resolution OR a non-Promise value;
           {::ok? false ::pending-promise <promise>} on timeout OR `defer` —
                                          carry the still-running Promise to
                                          result/<id>;
           {::ok? false :seon/error <seon.error/->map>} on rejection."
  [runtime-value]
  (let [budgeted? (instance? Budgeted runtime-value)
        v         (if budgeted? (.-value runtime-value) runtime-value)
        ms        (if budgeted? (.-ms runtime-value) default-timeout-ms)]
    (cond
      ;; Explicit opt-out: `(defer expr)` wrapped the Promise. Don't await —
      ;; hand the raw Promise back as a pending handle.
      (instance? Deferred v)
      {::ok? false ::pending-promise (.-promise v)}

      (instance? js/Promise v)
      (try
        (let [raced (await (race-timeout v ms))]
          (if (identical? raced timeout-sentinel)
            ;; Auto-await timed out. The Promise keeps running (no JS
            ;; preemption); carry the live handle back as `::pending-promise` so the
            ;; caller binds it at result/<id> for a later re-reference —
            ;; never drop it (the agent would have no way to recover it).
            {::ok? false ::pending-promise v}
            {::ok? true ::value raced}))
        (catch :default e
          ;; The awaited value came from the AGENT form's async execution, so
          ;; a rejection is agent-fault by default (wrapper-fault refines a
          ;; propagated core-function violation back to :core). recorded? skips
          ;; the datom when an instrumented ^:async function's wrapper .catch
          ;; already recorded this same rejection. Return contract unchanged.
          (when-not (error/recorded? e)
            (error/record! {:seon.error/raw   e
                            :seon.error/fault (instrument/wrapper-fault e :agent)}))
          {::ok? false :seon/error (error/->map e)}))

      :else
      {::ok? true ::value v})))

;; ============================================================
;; Detect-and-tee (v1.md §2.2 + §7 / STATUS.md Phase B item 10)
;;
;; After a successful eval, snapshot the analyzer's :defs and the
;; schema registry's keyset before/after; every new def becomes a
;; :seon.fn entity, every new schema key becomes a :seon.schema entity.
;; An `(ns …)` form also yields a :seon.ns entity. These ride in the
;; same tx as the eval entity (via record-eval!'s ::tee arg) — the eval's
;; committed transaction IS the transaction that wrote the program-graph datom.
;;
;; Redefinition: snapshot-defs digests each var-map (B1 fix
;; 2026-05-25), so a re-defn with changed body/doc/arglists/etc.
;; surfaces in defs-since just like a brand-new def. The tee then
;; transacts the updated projection; identity-attr upsert on
;; :seon.fn/sym / :seon.schema/key replaces the prior value in place;
;; bitemporal history retains all prior values. Bulk-load resume reads
;; the latest :seon.fn/source per identity. No special-case guard
;; against re-defs — the digest + upsert path does the right thing.
;; ============================================================

(defn- instrument-tee-fns!
  "Refresh exactly the changed function targets after their tee commits.

   The analyzer tuples are converted to the namespaced target data owned by
   `seon.instrument`. Malli receives an explicit delta map; no process-global
   function list or whole-program filter is involved."
  [targets]
  (when (seq targets)
    (let [instrument-targets
          (mapv (fn [[ns-sym fn-sym schema-form _async?]]
                  {::instrument/sym (symbol (str ns-sym) (str fn-sym))
                   ::instrument/schema-form schema-form})
                targets)]
      (instrument/instrument-delta!
        {::instrument/changed-syms
         (into #{} (map ::instrument/sym) instrument-targets)
         ::instrument/targets instrument-targets}))))

(defn- deftest-def?
  "True when an analyzer var-map came from a `(deftest …)` form.

   The marker is TOP-LEVEL `:test true` on the var-map — NOT
   `(:test (:meta var-map))`. cljs.analyzer's `parse 'def` (analyzer.cljc
   ~1958, 1.11.x) stores `(assoc :test true)` on the var-map itself and
   explicitly `(dissoc :test)` from `:meta` (\"remove actual test
   metadata, as it includes non-valid EDN and cannot be present in
   analysis cached to disk\"). So the `:meta` check is nil on EVERY CLJS
   build, not just self-host — the live-resume bug where agent deftests
   teed only `:seon.fn` rows, never `:seon.test` rows (board #33 part 1).
   REPL-verified on the bootstrap compile-state 2026-06-10:
   deftest var-map → `{:test true …}`, `:meta` has no `:test`;
   plain defn var-map → no `:test` key at all.

   Uniform with how defn detection reads the var-map: `:fn-var` is also
   a top-level analyzer key, not meta. (The runtime `cljs$lang$test`
   marker the test runner walks is the same fact post-emit; the analyzer
   key is available synchronously from the snapshot diff the tee already
   holds, so no globalThis walk is needed.)"
  [var-map]
  (true? (:test var-map)))

(declare changed-defs)

(defn- collect-new-test-targets
  "Return the FQ test syms newly defined by this successful eval.

   The analyzer snapshot gives this relationship exactly. Existing tests are
   not guessed from source substrings when an ordinary fn changes; dependency-
   based reruns resume only when the analyzer emits durable per-test reference
   facts that the database can query."
  [compile-state defs-before source eval-ns]
  (let [new-defs    (changed-defs compile-state defs-before source eval-ns)
        new-tests   (for [{:seon.analyzer-info/keys [var-map]} new-defs
                          :when (deftest-def? var-map)]
                      (symbol (str (:name var-map))))]
    (set new-tests)))

(defn- collect-instrument-targets
  "From the snapshot diff used by `build-tee-entities`, return the seq of
   `[ns-sym fn-sym schema-form async?]` tuples for newly-defined fns whose
   `:malli/schema` metadata parsed cleanly (Phase 3). `async?` (from the
   var-map's `:async` meta) routes the fn to the await-then-validate
   wrapper in [[instrument-tee-fns!]] instead of malli's stock synchronous
   wrapper, which would false-fail on the Promise return. Uses
   [[changed-defs]] so a BODY-ONLY redefinition (digest unchanged) still
   re-instruments — the redef replaced the wrapped var with a fresh
   unwrapped fn, so skipping it would silently drop validation+injection."
  [compile-state defs-before source eval-ns]
  (for [{:seon.analyzer-info/keys [ns sym var-map]} (changed-defs compile-state defs-before source eval-ns)
        :let [schema-form (:malli/schema (:meta var-map))
              async?      (boolean (:async (:meta var-map)))]
        ;; Structural async opt-out is applied while the exact target data is
        ;; prepared in seon.instrument.
        :when (and schema-form
                   ;; probe: an unparseable agent `:malli/schema` is
                   ;; expected — it simply isn't an instrument target (the
                   ;; parse failure is captured as `:seon.fn/schema-error`
                   ;; on the tee row, the agent's own signal).
                   (try (m/schema schema-form) true
                        (catch :default _ false)))]
    [ns sym schema-form async?]))

(defn- ns-form-name
  "If `source` parses as an `(ns NAME …)` form, return NAME as a
   symbol; otherwise nil. Tolerates leading metadata: `(ns ^:foo bar)`
   — cljs.reader handles metadata on the name slot."
  [source]
  (try
    (let [form (reader/read-string source)]
      (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
        (second form)))
    ;; probe: an unreadable / non-(ns …) source simply has no ns name —
    ;; nil is the expected answer, not a defect.
    (catch :default _ nil)))

(defn- schema-tee-row
  "ONE builder for the `:seon.schema` program-graph row both tee paths
   write (detect-and-tee in [[build-tee-entities]]; the register!
   self-tee in [[tee-registered-schema!]]). Identity-upsert on
   `:seon.schema/key` keeps the two idempotent.

   `:seon.schema/ns` uses the NESTED-MAP upsert form and is included
   ONLY when `k` HAS a keyword namespace — matching
   `seon.client/index-schemas`. An ENTITY-KIND key (`:my.garden.watering`
   — a `:map {:seon.db/entity true}` registration) is a single-segment
   keyword: `(namespace k)` is nil, and the old unconditional
   `{:seon.ns/name (keyword nil)}` put a LITERAL nil through Malli
   (`:seon.ns/name … got nil`), failing the WHOLE record-eval! tx and
   silently dropping the tee row (opus run2 live stack,
   open-issues-prd-2026-06-11 — resume-durability loss for every
   agent-authored entity schema)."
  [k form source at]
  (let [properties (schema.internal/attr-form-properties form)]
    (cond-> {:seon.schema/key        k
             :seon.schema/source     source
             :seon.schema/created-at at}
      (contains? properties :seon.db.id/generator)
      (assoc :seon.db.id/generator
             (:seon.db.id/generator properties))
      (namespace k)
      (assoc :seon.schema/ns {:seon.ns/name (keyword (namespace k))}))))

;; ============================================================
;; Strict persistence policy (#7) — classify on the FORM HEAD.
;;
;; A `:seon.fn` row is created (and later replayed) ONLY for a literal
;; single `(defn …)`/`(defn- …)`. A bare `(def …)`, `(def f (fn …))`, a
;; `(do …)`-wrapped defn, or a multi-form source RUNS as scratch but is
;; never teed/replayed — so re-evaling on boot/mint can never re-fire a
;; side effect (the #29 ghost-message class). This subsumes the old
;; `effectful-bare-def?` heuristic entirely: under strict-head gating an
;; effectful bare def is simply never persisted, so there is nothing to
;; scan. See docs/prds/agent-runtime/research/
;; simplification-audit-2026-06-17.md (Findings 1, 2, 4, 5).
;; ============================================================

(defn- read-all-forms
  "All top-level forms in `source` (an eval text may carry several).
   Returns nil on a read error — callers treat unreadable source as
   classifying false (a parse failure yields zero forms, so the strict
   persistence gate fails closed: no row is created/replayed).

   Delegates to [[internal/read-forms]] (rewrite-clj, the one
   whole-source structural read) so `::kw`/`::alias/kw` auto-resolved
   keywords never fail the read (C37 — cljs.tools.reader threw on them,
   silently exempting the defn from the whole persist/instrument/resume
   flywheel). Structural callers use the 1-arity (visible placeholder
   resolution); a value consumer ([[source-qualified-kws]]) threads the
   real `{:seon.repl/current-ns … :seon.repl/aliases …}` context."
  ([source] (internal/read-forms source))
  ([source resolve-opts] (internal/read-forms source resolve-opts)))

(defn defn-form?
  "TRUE iff `source` is exactly ONE top-level `defn`/`defn-` form.

   Strict persistence gate (#7). A bare `(def …)`,
   `(def f (fn …))`, a `(do …)`-wrapped defn, a macro, or a multi-form
   source is FALSE — it RUNS as scratch but is never teed/replayed as a
   `:seon.fn` row. Read-only; fail-closed on unreadable source (false).
   See docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [source]
  (let [forms (read-all-forms source)]
    (boolean (and (= 1 (count forms))
                  (seq? (first forms))
                  (contains? '#{defn defn-} (first (first forms)))))))

(defn- source-def-syms
  "The NAME symbols `source`'s top-level defining forms would define.

   Every top-level `(def …)`/`(defn …)`/`(defn- …)`/`(deftest …)` (bare
   or qualified `cljs.test/deftest`) with a symbol name slot, as a
   distinct vector in source order. The ONE 'what does this source
   define' walk — the body-redef rescue in [[changed-defs]] and the
   false-confidence guard ([[failed-def-syms]]) both read it. deftest
   is here because a BODY-ONLY deftest redefinition is digest-invisible
   exactly like a defn's (real-REPL C9, 2026-07-10: the re-eval'd test
   never re-teed, so the auto-test pass kept running the OLD version).
   Fail-closed on unreadable source (empty)."
  [source]
  (into []
        (comp (keep (fn [f]
                      (when (and (seq? f)
                                 (symbol? (first f))
                                 (or (contains? '#{def defn defn-} (first f))
                                     (= "deftest" (name (first f))))
                                 (symbol? (second f)))
                        (second f))))
              (distinct))
        (or (read-all-forms source) [])))

(defn- changed-defs
  "`analyzer-info/defs-since` PLUS the BODY-ONLY-REDEF rescue.

   `var-digest` covers only the load-bearing META (arglists/doc/schema/
   private) — a redefinition that changes ONLY the body produces an
   IDENTICAL digest, so `defs-since` reports nothing, the tee never
   refreshes `:seon.fn/source`, and the SCI cage renders the OLD body
   forever (live-caught 2026-07-02: an agent fixed its broken render fn
   and the fix never took). A body-sensitive `var-digest` is NOT
   available as the root fix: the analyzer var-map carries no body, and
   `snapshot-defs` has no source in scope — so the rescue is the
   mechanism, generalized (C24): for EVERY sym `source`'s top-level
   `def`/`defn`/`defn-` forms define ([[source-def-syms]] — single defn,
   multi-form batch entries, and `(def f (fn …))` alike) that produced
   no diff row, synthesize its def entry from the live analyzer state
   (`eval-ns` = the ns the form ran in) so the tee, instrumentation,
   and auto-test passes all see the redefinition. Idempotent with a
   real diff row (guarded by sym); a sym absent from the analyzer
   (failed eval) synthesizes nothing."
  [compile-state defs-before source eval-ns]
  (let [new-defs (analyzer-info/defs-since defs-before compile-state)
        diffed   (into #{}
                       (keep #(when (= eval-ns (:seon.analyzer-info/ns %))
                                (:seon.analyzer-info/sym %)))
                       new-defs)
        rescued  (when (symbol? eval-ns)
                   (for [nm (source-def-syms source)
                         :when (not (contains? diffed nm))
                         :let [vm (get-in @compile-state
                                          [:cljs.analyzer/namespaces
                                           eval-ns :defs nm])]
                         :when (some? vm)]
                     {:seon.analyzer-info/ns      eval-ns
                      :seon.analyzer-info/sym     nm
                      :seon.analyzer-info/var-map vm}))]
    (into (vec new-defs) rescued)))

;; ============================================================
;; Prose-in-parens demotion (#88). When an agent writes English prose
;; wrapped in parens — `(June 3 before June 14)`, `(results look fine)` —
;; the reader hands it to eval, the head throws "not defined", the live
;; agent errors, and the eval-error rate inflates with non-errors. These
;; forms are DEMOTED to prose: recorded ok?, never eval'd, counted as
;; neither ok nor fail (like a `;` comment). The gate is deliberately
;; tight (it errs toward KEEP) — a wrongly-demoted real call is a SILENT
;; bug, a missed prose demotion is only mild noise.
;; ============================================================

(def ^:private code-head-syms
  "Head symbols that ALWAYS signal real code — cljs special forms plus the
   common cljs.core macros. A `(…)` whose HEAD is one of these is NEVER
   demoted to prose: `(when x …)`, `(let [a 1] …)`, `(-> x f)`, `(and a b)`
   are code even when their operands don't resolve. Macros are NOT runtime
   vars (absent from globalThis), so they need this explicit set; resolvable
   VARS are caught by the globalThis/analyzer probe in
   [[symbol-resolves-as-var?]]. Consulted ONLY for the HEAD — a macro name
   sitting in ARGUMENT position (the `and` in `(Abk and fvV …)`) is a naked
   operator word, i.e. prose, and must NOT block demotion."
  '#{;; special forms
     if do let* loop* recur throw try catch finally def fn* letfn* quote var
     set! ns ns* deftype* defrecord* & case* js* . this-as
     ;; loader / REPL-form forms (real-REPL semantics 2026-07-10 — a bare
     ;; `(require '[x :as y])` was demoted to prose because `require` is a
     ;; repl special, not a var; these are ALWAYS code)
     require require-macros use use-macros import refer refer-clojure
     in-ns alias ns-unmap ns-unalias
     ;; control-flow / binding / def macros
     fn let letfn loop when when-not when-let when-some when-first if-let
     if-not if-some cond condp case and or -> ->> as-> some-> some->>
     cond-> cond->> doto .. for doseq dotimes while binding with-redefs
     with-open with-out-str with-local-vars locking lazy-seq delay assert
     comment declare definline defmulti defmethod defprotocol defrecord
     deftype definterface reify extend-type extend-protocol extend defn
     defn- defmacro time dosync})

(defn- symbol-resolves-as-var?
  "True when UNQUALIFIED `sym` resolves to a runtime VAR visible in
   `ns-sym`'s scope: an analyzer `:def` in ns-sym, a `cljs.core` var, or a
   var on ns-sym's own globalThis object. Mirrors the globalThis/analyzer
   probing `truly-undeclared?` uses, so the prose gate agrees with what would
   actually error. Macros (absent from globalThis) and special forms are NOT
   covered here — they live in [[code-head-syms]]. Qualified symbols never
   reach this fn (the caller treats namespaced as a code signal outright).
   Never throws."
  [compile-state ns-sym sym]
  (let [m (cljs.core/munge (str sym))]
    (boolean
      (or (get-in @compile-state
                  [:cljs.analyzer/namespaces (symbol (str ns-sym)) :defs sym])
          (resolves-on-globalthis? (str "cljs.core." m))
          (resolves-on-globalthis? (str (cljs.core/munge (str ns-sym)) "." m))))))

(defn- core-macro-head?
  "True when `sym` names a macro the self-host analyzer knows.

   A `:defs` entry in `cljs.core$macros` — every core macro lands there in
   the bootstrap compile-state. The COMPUTED complement to
   [[code-head-syms]]' literal set: macro heads the list missed
   (`ns-interns`, `ns-publics`, `ns-aliases`, …) are absent from globalThis
   so [[symbol-resolves-as-var?]] can't see them, and the prose gate was
   demoting their calls to prose — recording a false-confidence `ok? nil`
   row for a form that never ran (namespaces-milestone rung-1 introspection smell, 2026-07-10).
   Consulted for the HEAD only, like the literal set."
  [compile-state sym]
  (some? (get-in @compile-state
                 [:cljs.analyzer/namespaces 'cljs.core$macros :defs sym])))

(defn- collect-symbols
  "Every executable symbol appearing anywhere in `x`, in encounter order.

   Recurses through lists, vectors, maps, and sets, but treats `(quote ...)`
   as data. Symbols inside quoted data are not references and must not make an
   undeclared call look like prose to [[prose-paren?]]."
  [x]
  (let [acc  (volatile! [])
        walk (fn walk [y]
               (cond
                 (symbol? y)
                 (vswap! acc conj y)

                 (and (seq? y) (= 'quote (first y)))
                 nil

                 (coll? y)
                 (doseq [z y] (walk z))))]
    (walk x)
    @acc))

(defn- prose-paren?
  "TRUE when `source` is a single `(…)` list that is English PROSE wrapped in
   parens (`(June 3 before June 14)`, `(results look fine)`), NOT code.
   Eval'ing such a form throws \"not defined\" and inflates the eval-error
   rate; the batch DEMOTES it to a prose row (recorded ok?, never evaluated,
   counted as neither ok nor fail) so commentary survives in the transcript
   without registering as an error (#88).

   DEMOTE iff ALL hold — else KEEP (treat as real code, eval + count):

     1. `source` reads as exactly ONE non-empty list whose HEAD is a symbol.
     2. HEAD is unqualified, NOT in [[code-head-syms]] (special form / core
        macro), NOT a macro the analyzer knows ([[core-macro-head?]] — the
        computed complement covering macros the literal set missed), and
        does NOT resolve to a var.
     3. NO symbol ANYWHERE in the form is qualified, AND none resolves to a
        var — the instant one does (head or arg) it is real code.
     4. There are AT LEAST TWO symbols in ARGUMENT position. A lone undefined
        head with literal-only args (`(undefined-fn 1 2)`) or a single
        undefined arg (`(parse-it x)`) is KEPT as a plausible typo'd call: we
        err toward KEEP, since a wrongly-demoted real call is a SILENT bug
        while a missed prose demotion is only mild noise.

   Macros/special forms in ARGUMENT position do NOT count as code signals (a
   naked `and`/`look` is a prose word); only a qualified or var-resolving
   symbol does. Number/string/keyword literals are allowed in a prose group.

   KNOWN over-demotion edge: a real call whose head AND ≥2 args are ALL
   undefined bare symbols (`(merge-maps a b)`) is structurally identical to
   prose and WILL be demoted. This is the accepted cost of the gate; the
   ≥2-arg-symbol threshold is the maximal err-toward-keep that still demotes
   every prose case. Never throws — any read error or exception ⇒ not prose ⇒
   KEEP (fail-closed)."
  [compile-state ns-sym source]
  (boolean
    (try
      (let [forms (read-all-forms source)
            form  (first forms)]
        (and (= 1 (count forms))
             (seq? form)
             (seq form)
             (symbol? (first form))
             (let [head     (first form)
                   arg-syms (collect-symbols (rest form))
                   all-syms (cons head arg-syms)
                   code-signal?
                   (fn [s] (or (qualified-symbol? s)
                               (symbol-resolves-as-var? compile-state ns-sym s)))]
               (and (not (contains? code-head-syms head))
                    ;; computed macro-head check — the analyzer's own
                    ;; cljs.core$macros defs; macros aren't runtime vars,
                    ;; so the var probe can't vouch for them
                    (not (core-macro-head? compile-state head))
                    (>= (count arg-syms) 2)
                    (not-any? code-signal? all-syms)))))
      ;; probe: any read error ⇒ NOT prose ⇒ KEEP (fail-closed, per the
      ;; docstring's err-toward-keep contract); absence of a clean parse
      ;; is the expected "treat as code" signal, not a defect.
      (catch :default _ false))))

(defn scratch-def-note
  "A reactive 'won't persist' note DERIVED from an eval's source.

   (#7) Pure, no stored attr, re-computed every render so it FOLLOWS the
   form. Returns a one-line `;;`-comment string when `source` is a bare
   single `(def …)` (the run-but-don't-tee scratch case the strict
   persistence policy never tees). Returns \"\" otherwise: a clean
   `(defn …)`/`(defn- …)`/`(deftest …)`/`(seon.schema/register! …)`
   PERSISTS (no note), and a non-defining expression defined nothing.
   \"\" (not nil) so callers blank-check like :seon.eval/output.
   See docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md."
  {:malli/schema [:=> [:cat :string] :string]}
  [source]
  (let [forms (read-all-forms source)
        f1    (first forms)
        one?  (= 1 (count forms))
        def?  (and one? (seq? f1) (= 'def (first f1)) (symbol? (second f1)))
        sym   (when def? (second f1))
        init  (when (and def? (>= (count f1) 3)) (nth f1 2))
        fn-init? (and (seq? init) (= 'fn (first init)))]
    (cond
      fn-init?
      (str ";; won't persist across reboots: `(def " sym " (fn …))` is a "
           "value binding, not a defn — write `(defn " sym " …)` to record "
           "it as a program-graph fn")
      def?
      (str ";; won't persist across reboots: `(def " sym " …)` is runtime "
           "state, not a function — store it with `db/transact!` if it must "
           "survive")
      :else "")))

(defn- form-shape
  "A short structural description of a read top-level `form`, for the
   A.2 repair note's `… → <shape>` clause so the agent can sanity-check
   that a parinfer repair produced the structure it intended. Cheap,
   best-effort; returns a human phrase like \"2-key map\", \"(defn …)\",
   \"vector\". `form` may be nil (unreadable) → \"a form\"."
  [form]
  (cond
    (map? form)    (str (count form) "-key map")
    (vector? form) "vector"
    (set? form)    "set"
    (and (seq? form) (symbol? (first form)))
    (str "(" (first form) " …)")
    (seq? form)    "list"
    (nil? form)    "a form"
    :else          (str (type form))))

(defn read-error-message
  "Sharpen a rewrite-clj read-error message for an UNREPAIRABLE form.

   (A.3) The form did not parse, so it DEFINED NOTHING — say so, name the
   offending CLOSER + line:col (rewrite-clj's message carries the closer,
   NOT the unmatched opener — we do NOT promise the opener), slice the
   offending source line out and underline the column with a caret, and
   give the one honest next action.

   `raw` is rewrite-clj's message, e.g.
   \"Unmatched delimiter: ] [at line 25, column 76]\". `source` is the
   bad span. Falls back to the raw message when no `[at line N, column C]`
   coordinate is present."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [raw source]
  (let [m (re-find #"\[at line (\d+),?\s*column (\d+)\]" (str raw))
        ;; An "Unexpected EOF" read error is the OUTPUT-CAP TRUNCATION
        ;; signature: a form (often a giant inline (str "…report…")) ran
        ;; past the LLM output budget, so the literal/list ends with no
        ;; closer. The fix is NOT a delimiter — it is to STORE the large
        ;; content as data and SEND A POINTER, so the message fits.
        eof?    (re-find #"(?i)\bEOF\b" (str raw))
        instruction
        (if eof?
          (str "This form was likely TRUNCATED because it was too large to "
               "emit — it ran past your output budget and ended mid-form, so "
               "it DEFINED NOTHING and sent NOTHING. Don't try to re-emit the "
               "whole thing: STORE the long content as data (a my.kb.* entity "
               "or a :seon.items envelope), then send a SHORT pointer — the id "
               "+ a one-line summary. Report = data, message = pointer. For "
               "large LITERAL text, my.blob/put! it in ~2K-token chunks, then "
               "my.blob/concat! the chunk hashes into ONE canonical blob.")
          (str "This form did not parse, so it DEFINED NOTHING — do not "
               "call or wire anything that depended on it; it does not "
               "exist. Fix the delimiter and re-eval the whole form."))]
    (if-not m
      (str "READ ERROR — " raw "\n" instruction)
      (let [line-no (js/parseInt (nth m 1) 10)
            col-no  (js/parseInt (nth m 2) 10)
            lines   (str/split-lines (str source))
            ;; rewrite-clj line/col are 1-based.
            src-ln  (when (and (pos? line-no) (<= line-no (count lines)))
                      (nth lines (dec line-no)))
            caret   (when (and src-ln (pos? col-no))
                      (str (apply str (repeat (dec col-no) " ")) "^"))
            ;; The leading token of the raw message ("Unmatched delimiter:
            ;; ]") — keep it; it names the closer.
            headline (-> (str raw)
                         (str/replace #"\s*\[at line \d+,?\s*column \d+\]\s*$" ""))]
        (str "READ ERROR — this form did not parse, so it DEFINED NOTHING.\n"
             headline " at line " line-no ", col " col-no ":\n"
             (when src-ln (str "    " src-ln "\n"))
             (when caret  (str "    " caret "\n"))
             instruction)))))

(defn- build-tee-entities
  "Return a vector of program-graph entity maps for everything `source`
   newly defined (def/defn → :seon.fn; schema/register! → :seon.schema;
   (ns …) → :seon.ns). `defs-before` and `schemas-before` are snapshots
   taken before the eval ran. `ns-kw` is the form's ending ns
   (`(:seon.eval/ns eval-entity)`, per STATUS.md heads-up (b)).

   Tee-entities reference their owning ns via NESTED-MAP upsert
   `{:seon.ns/name <kw>}` (identity-attr upsert: merges onto an
   existing `:seon.ns` entity or creates a minimal one). NOT a
   lookup-ref — a lookup-ref to a not-yet-existing `:seon.ns` entity
   throws and sinks the WHOLE record-eval! tx (the run-4 silent
   data-loss bug; data namespaces like `:workout` have no `:seon.ns`
   row)."
  [{::keys [compile-state defs-before schemas-before source at eval-ns]}]
  (let [;; changed-defs = defs-since + the body-only-redef rescue (a body
        ;; edit with unchanged meta is digest-invisible; without the rescue
        ;; the stored :seon.fn/source goes permanently stale).
        new-defs    (changed-defs compile-state defs-before source eval-ns)
        ;; C14 (owner RULED 2026-07-05: transient stays transient): a def
        ;; in a transient scratch ns (cljs.user / seon.dynamic / result —
        ;; the SAME [[transient-ns-syms]] rule the requires-tee uses)
        ;; mints NO program-graph rows: no :seon.fn/:seon.test/:seon.ns
        ;; persistence, so no instrumentation and no resume. The def
        ;; still RAN and returned its value — scratch is scratch.
        new-defs    (remove (fn [{:seon.analyzer-info/keys [ns]}]
                              (contains? transient-ns-syms ns))
                            new-defs)
        new-schemas (set/difference (schema/current-keys) schemas-before)
        fn-entities (for [{:seon.analyzer-info/keys [ns var-map]} new-defs
                          ;; Classify on the FORM HEAD, not the analyzer's
                          ;; `:test` marker (B9, db-is-the-running-system PRD).
                          ;; The analyzer collapses BOTH a `(deftest …)` AND a
                          ;; usage-example `(defn f {:test (fn [] …)} …)` into
                          ;; top-level `:test true`, so `deftest-def?` is TRUE
                          ;; for both. The old gate `(not (deftest-def? …))`
                          ;; therefore DROPPED a `:test`-bearing defn's
                          ;; :seon.fn row (lost!) AND mis-filed it as a
                          ;; :seon.test row. `defn-form?` ALONE is the correct
                          ;; gate: a `(defn …)`/`(defn- …)` (with or without
                          ;; `:test`) → :seon.fn row; a `(deftest …)`
                          ;; (defn-form? FALSE) → :seon.test row (below), never
                          ;; both — keeping resume single-lane.
                          ;;
                          ;; Strict persistence (#7): create a :seon.fn row
                          ;; ONLY when `source` is a literal single `(defn …)`
                          ;; (classify on the form HEAD, not on `:fn-var?`).
                          ;; A bare `(def …)`, `(def f (fn …))`, a do-wrapped
                          ;; defn, or a multi-form source RAN as scratch but
                          ;; is never teed — so re-evaling on boot/mint can
                          ;; never re-fire a side effect (subsumes the old
                          ;; #29 effectful-bare-def heuristic). `:fn-var?`
                          ;; stays a RENDER attr below; it no longer gates
                          ;; row creation.
                          :when (defn-form? source)
                          :let [{:seon.analyzer-info/keys
                                 [sym fn-var? arglists doc private? spec]}
                                (analyzer-info/var-projection var-map)
                                ;; Phase 3 (mvp-completion-plan 2026-05-27): if
                                ;; `:malli/schema` metadata is present, validate
                                ;; it parses via `m/schema`. Unparseable schemas
                                ;; yield NO `:spec` (var-projection already
                                ;; omitted it) and stamp `:seon.fn/schema-error`
                                ;; with the failure reason. This prevents
                                ;; instrumenting a fn with a garbage schema
                                ;; (would either throw at instrument! time or
                                ;; silently no-op).
                                schema-meta  (:malli/schema (:meta var-map))
                                schema-error (when (some? schema-meta)
                                               ;; probe: an unparseable agent
                                               ;; `:malli/schema` is expected and
                                               ;; ALREADY becomes data — the reason
                                               ;; string is stored as
                                               ;; `:seon.fn/schema-error` (the agent's
                                               ;; own signal); no separate datom.
                                               (try (m/schema schema-meta) nil
                                                    (catch :default e
                                                      (or (.-message e) (str e)))))
                                ;; A parseable schema yields `spec`; an
                                ;; unparseable one yields schema-error and no
                                ;; spec. Belt-and-suspenders: drop spec if an
                                ;; error somehow co-occurs.
                                effective-spec (when (nil? schema-error) spec)]]
                      ;; var-projection's `:sym` is already the FQ string
                      ;; (`pr-str` of analyzer's `:name` which carries the
                      ;; ns). v1.md §7 pseudocode shows
                      ;; `(str ns "/" sym)` here — that's stale (would
                      ;; double-prefix to "probe.tee/probe.tee/f1"). The
                      ;; spec language is the dumbass-trap; var-projection
                      ;; is canonical.
                      (cond-> {:seon.fn/sym        sym
                               ;; nested-map upsert, NOT a lookup-ref — same
                               ;; data-loss class as the :seon.schema/ns run-4
                               ;; bug: a lookup-ref to a missing :seon.ns
                               ;; entity throws and sinks the whole tx.
                               :seon.fn/ns         {:seon.ns/name (keyword (str ns))}
                               :seon.fn/source     source
                               :seon.fn/fn-var?    fn-var?
                               :seon.fn/arglists   arglists
                               :seon.fn/doc        doc
                               :seon.fn/private?   private?
                               :seon.fn/created-at at}
                        ;; PRESENT ⇒ specced; ABSENT ⇒ unspecced. Omit
                        ;; entirely when the schema is missing or errored.
                        (some? effective-spec) (assoc :seon.fn/spec effective-spec)
                        schema-error (assoc :seon.fn/schema-error schema-error)
                        ;; Renderer dispatch comes from the entity-schema's
                        ;; `:seon.render/ai` / `:seon.render/html` props
                        ;; (see seon.agent's :seon.fn registration). The
                        ;; renderer's discovery walks AEVT for `:seon.fn/sym`
                        ;; and resolves through the schema; no per-row stamp.
                        ))
        ;; :seon.schema/ns uses the NESTED-MAP upsert form, NOT a
        ;; lookup-ref. Schemas are routinely registered for DATA
        ;; namespaces (e.g. `:workout/duration-seconds` → keyword-ns
        ;; `:workout`) that have no `(ns …)` form and therefore no
        ;; pre-existing `:seon.ns` entity. A lookup-ref
        ;; `[:seon.ns/name :workout]` made datahike throw "Nothing
        ;; found for entity id …", failing the WHOLE record-eval! tx
        ;; and silently dropping both the schema row AND the eval row
        ;; (run-4 root cause, e2e-demo-findings-2026-06-08 §Run 4
        ;; CORRECTION). The nested map `{:seon.ns/name <kw>}` upserts:
        ;; identity-attr resolution links to the existing `:seon.ns`
        ;; entity when one exists (core or `(ns …)`-created) and
        ;; creates a minimal one otherwise — so handlers.ns's
        ;; `[?s :seon.schema/ns ?n]` join stays coherent for data
        ;; namespaces too. REPL-verified on a scratch conn 2026-06-09:
        ;; create+link for a fresh ns, no-dup upsert for an existing
        ;; one. Entity-kind keys (nil keyword namespace) carry NO ns
        ;; link at all — see [[schema-tee-row]].
        schema-entities (for [k new-schemas]
                          (schema-tee-row k (schema/schema-definition k)
                                          source at))
        ;; Deftest defs carry
        ;; the analyzer's top-level `:test true` marker (see
        ;; [[deftest-def?]] — the old `(:test (:meta var-map))` check was
        ;; ALWAYS nil, the live-resume bug where agent deftests never got
        ;; a :seon.test row). Each gets a `:seon.test` row keyed on the
        ;; FQ sym (identity attr). Source is retained for reconstruction and
        ;; human inspection, never parsed as a dependency index.
        test-entities (for [{:seon.analyzer-info/keys [ns var-map]} new-defs
                            :let [{:seon.analyzer-info/keys [sym]}
                                  (analyzer-info/var-projection var-map)]
                            ;; A :seon.test row ONLY for a real `(deftest …)`:
                            ;; deftest-def? TRUE *and* defn-form? FALSE. A
                            ;; usage-example `(defn f {:test …} …)` also carries
                            ;; the analyzer's `:test true` marker (deftest-def?
                            ;; TRUE) but is a defn (defn-form? TRUE) → it gets a
                            ;; :seon.fn row above, NOT a :seon.test row (B9,
                            ;; db-is-the-running-system PRD). The form head is
                            ;; the disambiguator the analyzer's marker can't be.
                            :when (and (deftest-def? var-map)
                                       (not (defn-form? source)))]
                        {:seon.test/sym        sym
                         ;; nested-map upsert — see :seon.fn/ns note above.
                         :seon.test/ns         {:seon.ns/name (keyword (str ns))}
                         :seon.test/source     source
                         :seon.test/created-at at})
        ns-sym      (ns-form-name source)
        ;; Same C14 gate: `(ns cljs.user)` is eval scaffolding, never a
        ;; program-graph `:seon.ns` row.
        ns-entities (when (and ns-sym
                               (not (contains? transient-ns-syms ns-sym)))
                      [{:seon.ns/name   (keyword (str ns-sym))
                        :seon.ns/source source}])]
    (vec (concat ns-entities fn-entities schema-entities test-entities))))

;; ----------------------------------------------------------------------------
;; Reified require edges + declared fn read-sets (M4 + C28 structural
;; store). The alias/refer facts and a fn's qualified-keyword literals
;; were ALWAYS known structurally when the tee wrote the row (the
;; analyzer's :requires/:uses maps; the already-read defn form) — but
;; were stored only as SOURCE TEXT and re-derived by a reader/regex at
;; render time (seon.render.sci ns env rebuild; the canvas-default
;; derivation in seon.agent.ctx.render-fns). Store them at tee time;
;; render-time consumers read datoms, with the text parse kept ONLY as
;; the documented fallback for pre-existing rows (which self-backfill:
;; every replay/re-eval writes the structural attrs).
;; ----------------------------------------------------------------------------

;; One component row per required ns, carrying the `:as` alias +
;; `:refer` set (`:seon.ns.require/*` attrs + the edge shape live in
;; seon.analyzer-info, the ns that derives them from the analyzer).
;; Replaced WHOLESALE on change ([[ns-require-edges-tx]]) — component
;; retractEntity cascades, so no orphan edge rows. `[:vector …]` like
;; every component-ref attr (tx values are vectors).
(schema/register! :seon.ns/require-edges
                  [:vector {:seon.db/component true} :seon.db/ref])

;; The attrs a fn's source names as QUALIFIED keyword literals — its
;; declared read-set, extracted from the ALREADY-READ defn form at tee
;; time (never from text). ABSENT = no keyword literals OR a
;; pre-structural row (consumers fall back to the regex scan).
;; `[:vector …]` (not `[:set …]`): the transact validator checks the
;; tx VALUE against the registered container, and diff-upserts
;; transact vectors.
(schema/register! :seon.fn/read-attrs [:vector :qualified-keyword])

(defn- defn-read-forms
  "The subforms of a top-level `form` whose keyword literals count as
   READS. For a `(defn …)`/`(defn- …)` that is the params + body: the
   docstring and the attr-map are code ANNOTATIONS (`:malli/schema`,
   schema refs), not data reads — C38: the tee was recording the
   `:malli/schema` metadata key itself as a \"read attribute\".
   Structural (position, not a keyword name-list): everything between
   the name and the first non-string/non-map element is annotation. A
   non-defn form passes through whole."
  [form]
  (if (and (seq? form)
           (symbol? (first form))
           (contains? #{"defn" "defn-"} (name (first form))))
    (drop-while #(or (string? %) (map? %)) (drop 2 form))
    [form]))

(defn- source-qualified-kws
  "Every QUALIFIED keyword literal in `source`'s top-level forms, as a
   set. Walks the READ forms (strings/comments can't false-positive the
   way a text regex does); `#{}` when the source doesn't read — but a
   `:seon.fn` row only exists for sources [[defn-form?]] read cleanly.
   A defn's docstring/attr-map annotations are excluded
   ([[defn-read-forms]]).

   `resolve-opts` (`{:seon.repl/current-ns … :seon.repl/aliases …}`,
   from the tee's analyzer context) resolves `::kw`/`::alias/kw`
   literals to their REAL namespaces (C37 — the stored read-set must
   carry the resolved attr, not a placeholder). A keyword whose alias
   did NOT resolve keeps the visible `?`-prefixed placeholder namespace
   and is DROPPED here — absent beats storing a garbage watch attr."
  [source resolve-opts]
  (into #{}
        (comp (mapcat defn-read-forms)
              (mapcat #(tree-seq coll? seq %))
              (filter #(and (keyword? %)
                            (some? (namespace %))
                            (not (str/starts-with? (namespace %) "?")))))
        (or (read-all-forms source resolve-opts) [])))

(schema/register! ::aliases   [:map-of :symbol :symbol])
(schema/register! ::nses      [:set :symbol])
(schema/register! ::refers    [:map-of :symbol [:set :symbol]])
(schema/register! ::refer-all [:set :symbol])
(schema/register! ::require-info
                  [:map
                   [::aliases ::aliases]
                   [::nses ::nses]
                   [::refers ::refers]
                   [::refer-all ::refer-all]])

(defn edges->require-info
  "Fold require-edge maps into the lexical-env shape the SCI cage wants.

   `{::aliases {alias target-sym} ::nses #{target-sym} ::refers
   {target-sym #{sym}} ::refer-all #{target-sym}}` — targets as ns
   SYMBOLS. An `:as-alias?` edge contributes its ALIAS only (keyword
   resolution), never a ::nses entry — its target is NOT loaded.
   Total: `#{}` of edges folds to the empty info."
  {:malli/schema [:=> [:cat :seon.analyzer-info/require-edges] ::require-info]}
  [edges]
  (reduce
    (fn [acc {:seon.ns.require/keys [target alias refers refer-all? as-alias?]}]
      (let [tsym (symbol (name target))]
        (cond-> acc
          (not as-alias?) (update ::nses conj tsym)
          alias        (assoc-in [::aliases alias] tsym)
          (seq refers) (assoc-in [::refers tsym] (set refers))
          refer-all?   (update ::refer-all conj tsym))))
    {::aliases {} ::nses #{} ::refers {} ::refer-all #{}}
    edges))

(defn stored-require-edges
  "The stored `:seon.ns/require-edges` maps for `ns-kw`, as a set.

   Pulled off the `:seon.ns` row and normalized back to the
   `::analyzer-info/require-edge` shape (refers vector → set, `:db/id`
   dropped) so it compares `=` against a freshly-derived edge set.
   `#{}` when the ns row or the attr is absent (pre-structural rows —
   callers fall back to parsing `:seon.ns/source`). Never throws."
  {:malli/schema [:=> [:catn [::db :any] [::ns-kw :keyword]]
                  :seon.analyzer-info/require-edges]}
  [db ns-kw]
  (try
    ;; Existence probe FIRST — a pull on a missing lookup-ref makes
    ;; datahike LOG an :error before throwing (a fresh home-ns setup
    ;; reads before its ns row exists), so probe cheaply and pull only
    ;; a real row.
    (if (nil? (ffirst (db/query '[:find ?e :in $ ?ns
                                  :where [?e :seon.ns/name ?ns]]
                                db ns-kw)))
      #{}
      (into #{}
            (map (fn [e]
                   (let [refers (:seon.ns.require/refers e)]
                     (cond-> (dissoc e :db/id :seon.ns.require/refers)
                       (seq refers) (assoc :seon.ns.require/refers
                                           (set refers))))))
            (:seon.ns/require-edges
              (db/pull db
                       '[{:seon.ns/require-edges
                          [:db/id :seon.ns.require/target :seon.ns.require/alias
                           :seon.ns.require/refers :seon.ns.require/refer-all?
                           :seon.ns.require/as-alias?]}]
                       [:seon.ns/name ns-kw]))))
    (catch :default e
      ;; the existence-probe above already returns #{} for the expected
      ;; missing-row case, so a throw reading OUR stored require edges is a
      ;; core defect (:core) — the caller still degrades to the empty set.
      (error/record! {:seon.error/raw e :seon.error/fault :core})
      #{})))

(defn stored-require-targets
  "The ns-name keywords `ns-kw`'s stored require-edges point at, as a set.

   The flat \"what does this ns require\" view, DERIVED from the ONE
   stored representation (`:seon.ns/require-edges` — C36; the parallel
   flat `:seon.ns/requires` attr is deleted). `#{}` when the ns row or
   its edges are absent."
  {:malli/schema [:=> [:catn [::db :any] [::ns-kw :keyword]]
                  [:set :keyword]]}
  [db ns-kw]
  (into #{}
        (map :seon.ns.require/target)
        (stored-require-edges db ns-kw)))

(defn ns-require-edges-tx
  "Tx ops making `:seon.ns/require-edges` for `ns-kw` EXACTLY `new-edges`.

   `[]` when the stored edge set already equals `new-edges` (set
   compare over the normalized maps — no spurious tx ops). On change
   the old COMPONENT rows are `[:db/retractEntity …]`'d (cascade
   removes the parent ref datoms too; REPL-verified, no orphans) and
   the new set is asserted via the `:seon.ns/name` identity upsert
   (creates the ns row when absent)."
  {:malli/schema
   [:=> [:catn [::db :any] [::ns-kw :keyword]
         [::new-edges :seon.analyzer-info/require-edges]]
        [:vector :any]]}
  [db ns-kw new-edges]
  (if (= (stored-require-edges db ns-kw) new-edges)
    []
    (let [old-eids (map first
                        (db/query '[:find ?e
                                    :in $ ?ns
                                    :where
                                    [?n :seon.ns/name ?ns]
                                    [?n :seon.ns/require-edges ?e]]
                                  db ns-kw))]
      (vec (concat (for [e old-eids] [:db/retractEntity e])
                   (when (seq new-edges)
                     [{:seon.ns/name         ns-kw
                       :seon.ns/require-edges (vec new-edges)}]))))))

(defn fn-read-attrs-tx
  "Diff-upsert tx ops making `:seon.fn/read-attrs` for `sym-str` EXACTLY
   `new-kws`.

   Diff discipline (cardinality-many
   accumulates on plain upsert): additions and removals are explicit
   scalar ops, `[]` when unchanged. Scalar adds avoid Datahike's entity-map
   ambiguity where an exactly-two-value collection beginning with an identity
   attr is read as ONE lookup ref rather than two cardinality-many values.
   Emitted after the owning fn entity at the tee site, so the lookup-ref is
   resolvable even on the first definition. A REDEF that drops a keyword
   literal sheds the stale watch, and a legacy row self-backfills on its first
   replay/re-eval."
  {:malli/schema
   [:=> [:catn [::db :any] [::sym-str :string]
         [::new-kws [:set :qualified-keyword]]]
        [:vector :any]]}
  [db sym-str new-kws]
  (let [current   (into #{}
                        (map first)
                        (db/query '[:find ?k
                                    :in $ ?s
                                    :where
                                    [?f :seon.fn/sym ?s]
                                    [?f :seon.fn/read-attrs ?k]]
                                  db sym-str))
        additions (set/difference new-kws current)
        removals  (set/difference current new-kws)]
    (vec (concat (for [k (sort-by str additions)]
                   [:db/add [:seon.fn/sym sym-str]
                    :seon.fn/read-attrs k])
                 (for [k (sort-by str removals)]
                   [:db/retract [:seon.fn/sym sym-str]
                    :seon.fn/read-attrs k])))))

;; Namespaces the requires-tee SKIPS — transient eval scaffolding, never
;; a real program-graph ns: the REPL default home ([[user-ns-sym]]), the
;; `cljs.js/eval-str` target ([[dynamic-ns-sym]]), and the reserved ns
;; holding the synthetic `result/<id>` value vars from bind-result-var!
;; ([[result-ns-sym]] — belt-and-suspenders alongside the
;; analyzer-info/defs-since `:seon.eval/result-var?` filter). DERIVED
;; from the single defs — never restate an ns name here — so renaming a
;; scratch/result ns can't leak a transient ns into the program graph.
;; A real agent/core ns (`seon.*`, `my.*`, a data ns) gets a `:seon.ns`
;; row; these do not.
(def ^:private transient-ns-syms
  #{user-ns-sym dynamic-ns-sym result-ns-sym})

;; ----------------------------------------------------------------------------
;; Agent-no-override-core guard (db-is-the-running-system PRD; Sean: agents
;; must NOT override compiled core/third-party fns). An agent eval that
;; REDEFINES an EXISTING compiled-core fn must not persist a :seon.fn override
;; row — it would clobber the core display row and take ephemeral live effect.
;; Detect by process: a sym whose CURRENT `:seon.fn/source` datom's tx came
;; through `:seon.db.process/boot` is compiled core/third-party (same provenance
;; rule [[tee-registered-schema!]] uses for the schema self-tee). A NEW sym
;; (no row) or an agent-authored sym is NOT blocked — agents freely define and
;; redefine in their OWN namespaces; only redefining an existing boot-authored
;; sym is denied.
;; ----------------------------------------------------------------------------

(defn core-boot-fn-syms
  "The `syms` subset whose source was written through boot.

   FQ `:seon.fn/sym` strings whose CURRENT
   `:seon.fn/source` datom's tx refs `:seon.db.process/boot` —
   i.e. compiled core/third-party fns the agent must not override. A sym
   with no `:seon.fn` row, or whose latest source was written under any
   non-boot process is NOT included (it is the
   agent's own / a free new def). Returns a set. `db` is a datahike db
   value (third-party boundary)."
  {:malli/schema
   [:=> [:catn [::db :any] [::syms [:sequential :string]]]
        [:set :string]]}
  [db syms]
  (let [want (set syms)]
    (into #{}
          (comp (map first) (filter want))
          (db/query '[:find ?sym
                      :where
                      [?e :seon.fn/sym ?sym]
                      [?e :seon.fn/source _ ?tx]
                      [?tx :seon.db/process ?process]
                      [?process :seon.db.process/id :seon.db.process/boot]]
                    db))))

(defn reject-core-overrides
  "Drop `tee-entities` rows that override a `blocked` core sym.

   The override guard: drop any `:seon.fn` row
   whose `:seon.fn/sym` is in `blocked` (a set of core-boot syms from
   [[core-boot-fn-syms]]) and, for each dropped sym, `js/console.warn`
   a specific, actionable one-liner. Non-`:seon.fn` rows (`:seon.ns`,
   `:seon.schema`, `:seon.test`, the diff-tx retract vectors)
   pass through untouched. Returns the filtered vector. Pure except for
   the warn side effect; never throws."
  {:malli/schema
   [:=> [:catn [::tee-entities [:vector :any]] [::blocked [:set :string]]]
        [:vector :any]]}
  [tee-entities blocked]
  (if (empty? blocked)
    tee-entities
    (vec
      (remove
        (fn [entity]
          (let [sym (and (map? entity) (:seon.fn/sym entity))]
            (when (and sym (contains? blocked sym))
              (js/console.warn
                (str "[seon.eval] agent cannot override compiled core fn "
                     sym " — its :seon.fn row is ignored (not persisted). "
                     "Agents define/redefine in their OWN namespaces; core "
                     "is changed via a build-time third-party override "
                     "(see examples/third-party-override)."))
              true)))
        tee-entities))))

;; ============================================================
;; register! self-tee (open-issues 2026-06-12, task #24 symptom 1).
;;
;; Detect-and-tee only covers AGENT evals (record-eval!'s ::tee arg).
;; A REPL-scope `(seon.schema/register! …)` — the MCP eval surface,
;; any non-turn caller — registered in-memory only: NO :seon.schema
;; row, so the attr VANISHED from the registry on restart while its
;; datoms stayed readable (new transacts rejected as unregistered —
;; silently write-dead; orchestrator-verified live 2026-06-12).
;;
;; register! now tees its OWN row through a hook this ns installs
;; (seon.schema can't require seon.db — cycle). One mechanism with
;; the eval tee: same row shape ([[schema-tee-row]]), identity-upsert
;; on :seon.schema/key, so an agent-eval registration writing through
;; BOTH paths still yields exactly one row.
;; ============================================================

;; Stamped on a partially-recorded eval row when its program-graph tee
;; rows could not be persisted (record-eval! stage-2 recovery) — the
;; honest record of the dropped tee. Registered here: seon.eval owns
;; the :seon.eval keyword namespace. Transacted TOP-LEVEL (identity
;; upsert on :seon.eval/id), so lazy attr-install covers it without a
;; boot-schema entry.
(schema/register! :seon.eval/record-error :string)

(defn- tee-registered-schema!
  "The register! self-tee hook body (installed via
   `seon.schema/set-tee-fn!` at load). Conn-gated and boot-composed:

   - NO bound `seon.db/*conn*` → nil (pure-registry contexts, JVM-side
     compile, the ~500 boot ns-load registrations before a conn
     exists — boot semantics unchanged; `seon.client/index-schemas`
     remains the owner of core rows).
   - REPLAY scope (`:seon.eval/replay? true` in execution context) → nil.
     Replayed `(seon.schema/register! …)` sources re-run register!;
     re-teeing them would write a no-op upsert per schema per boot,
     re-anchoring row tx-ids (the exact churn the replay design's
     'detect-and-tee doesn't re-fire' invariant exists to avoid).
   - EVAL RECORD scope ([[record-boundary-active?]]) → nil. `eval-batch!`
     wraps each form in the private ALS boundary before it executes. The
     gated detect-and-tee path writes the :seon.schema row only with a
     SUCCESSFUL eval. The self-tee must stand down there or a `register!`
     in a later-failing form would persist its schema/`:seon.ns` rows
     anyway (#39 — the eval is the transaction boundary). The self-tee
     is the durability path only for bare eval/REPL scope outside that
     boundary.
   - CORE-CLAIMED row (current `:seon.schema/source` datom's tx
     was written through `:seon.db.process/boot`) → nil. The bootstrap
     self-host compiler can re-execute compiled-bundle registrations
     at runtime (an agent's `(require …)` goog.globalEvals bundle JS,
     the relink-registry! incident class); without this guard those
     re-registrations would convert boot-indexed rows into never-
     core-managed, replayable `(…)` call rows. Same current-source
     provenance rule as `seon.client/core-program-tx`.
   - IDENTICAL stored source → nil (idempotent re-registration; no
     no-op upsert churn).

   Otherwise transacts ONE [[schema-tee-row]] whose source is the
   replayable call form `(seon.schema/register! <k> <form>)` — the
   discriminator [[registration-call-source?]] selects it for the
   DB-layer load (reconstitution), closing the registry/store disagreement.

   Never throws; a tee tx failure is surfaced via console.error (the
   in-memory registration already succeeded — durability failed, and
   that fact must be loud, not fatal). Returns the transact Promise,
   or nil when skipped — register! stashes it in `seon.schema/!last-tee`
   for deterministic test/proof awaiting."
  [k form]
  (when-some [conn db/*conn*]
    (when-not (or (::replay? (db/current-tx-context))
                  ;; #39: inside the private per-form record boundary the
                  ;; gated detect-and-tee path owns this schema row and writes
                  ;; it only with a successful eval. No pre-minted eval id is
                  ;; required merely to mark execution scope.
                  (record-boundary-active?))
      (let [source (pr-str (list 'seon.schema/register! k form))
            stored-src
            (db/query {:seon.db/query
                       '[:find ?src .
                         :in $ ?k
                         :where
                         [?s :seon.schema/key ?k]
                         [?s :seon.schema/source ?src]]
                       :seon.db/conn conn
                       :seon.db/args [k]})
            boot-authored?
            (boolean
              (db/query {:seon.db/query
                         '[:find ?s .
                           :in $ ?k
                           :where
                           [?s :seon.schema/key ?k]
                           [?s :seon.schema/source _ ?tx]
                           [?tx :seon.db/process ?process]
                           [?process :seon.db.process/id
                            :seon.db.process/boot]]
                         :seon.db/conn conn
                         :seon.db/args [k]}))]
        (when-not (or boot-authored?
                      (= stored-src source))
          (-> (db/transact!
                {:seon.db/tx-data [(schema-tee-row k form source (js/Date.))]
                 :seon.db/conn    conn})
              (.then
                (fn [r]
                  (when-not (:seon.db/ok? r)
                    (js/console.error
                      "[seon.eval/tee-registered-schema!] self-tee tx FAILED —"
                      "registration of" (str k) "is IN-MEMORY ONLY (will not"
                      "survive a restart):"
                      (-> r :seon.db/error :seon.error/message)))
                  r))))))))

;; Install at load — idempotent (a bundle re-execution re-installs the
;; same fn). At THIS point in load order seon.schema is long loaded and
;; no conn is bound yet, so nothing tees during boot ns-loads.
(schema/set-tee-fn! tee-registered-schema!)

(def store-edn-cap
  "Store-time char cap for any pr-str'd string persisted as a datom
   (`:seon.eval/result-edn`, `:seon.eval/error`). Override with
   SEON_RENDER_STORE_EDN_CAP.

   MEMORY-SAFETY invariant: the DB must never hold a multi-MB blob in a
   single datom. A 9.7M-char `pull [*]` result once landed verbatim as
   `:seon.eval/result-edn`; a later whole-DB `[?e ?a ?v]` scan
   materialized every bloated datom at once and OOM-killed the Node pod
   (losing the in-RAM `:memory` DB). This cap bounds each persisted
   string so a whole-DB scan stays bounded by `N * store-edn-cap`.

   16k is generous headroom for direct datom inspection/debugging while
   staying ~600x below the 9.7M blob that caused the OOM. The FULL value
   remains available in-session as the live var `result/<id>` in the bounded
   process store — its VALUE is not clipped."
  (config/store-edn-cap))

(defn cap-edn
  "Truncate a pr-str'd value string to `store-edn-cap`.

   Appends an elision marker reporting the estimated tokens omitted.
   Nil-safe. Mirrors `seon.agent/cap-result` but applies the larger
   store-time cap at the persistence boundary."
  {:malli/schema
   [:function
    [:=> [:catn [::s :any]] :string]
    [:=> [:catn [::s :any] [::limit :int]] :string]]}
  ([s] (cap-edn s store-edn-cap))
  ([s limit]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit) " …⟨"
            (tokens/chars->tokens (- n limit)) " tokens elided⟩")
       s))))

(def ^:private known-error-kinds
  "Error kinds whose `:seon.error/message` is already crystal-clear
   guidance built by the thrower (db `:user-input`, compile, read,
   schema-validation). For these, the message stands alone — no runtime
   'errors are values' framing is added (it would be wrong: these are
   not thrown values the agent adapts to, they are defects to FIX).

   Same set as the fault-classification agent-population (an agent-fixable
   input defect is `:agent`, never `:core`) — references the ONE source of
   truth `seon.error/agent-fault-kinds`."
  error/agent-fault-kinds)

(defn render-error-string
  "The edn-SAFE `:seon.eval/error` string for a failed eval.

   CRYSTAL-CLEAR — what the agent reads in the
   transcript (rendered as `;; ⚠` lines by `seon.agent.ctx/format-eval-row`).

   It must tell a fresh, confused LLM the EXACT defect AND the exact next
   action — never a stack trace, never a raw EDN dump. So we keep ONLY
   the deepest real `:seon.error/message` (the throwers — `read-error-message`,
   the undeclared-var message, the db user-input messages — build that
   to be self-contained) and, for a GENUINE RUNTIME throw (no known
   compile/read/db kind in `:seon.error/data`), append the standing
   'errors are values — read it and adapt; nothing threw' framing so the
   agent treats the failure as data, not a crash.

   The raw `:seon.error/data` EDN is NOT dumped here (it merely duplicated
   the prose and read like a stack trace); the structured data still
   lands separately in `:seon.eval/error-data` for the Malli-instrument
   path. `:seon.error/raw`/`stack` are dropped (opaque + unreadable to the
   agent-side reader)."
  {:malli/schema [:=> [:catn [::err :any]] :string]}
  [err]
  (let [msg  (error/deepest-message err)
        ;; ONE position (C45): synthesized maps build the kind at the
        ;; envelope top; `error/->map` LIFTS a thrown ex-data kind there.
        kind (:seon.error/kind err)
        ;; A runtime throw is anything NOT a known fix-this defect kind:
        ;; the agent's own (throw …), a JS TypeError from calling a
        ;; non-fn, etc. errors-are-values applies — frame it so.
        runtime? (not (contains? known-error-kinds kind))]
    (if runtime?
      (str msg
           "\n;;   errors are values — read it and adapt; nothing threw "
           "at you (the failure is a value you can inspect and handle).")
      msg)))

(defn clip-result-body
  "Clip a rendered result-body STRING to the result-body cap's tokens.

   The clip applied to the projected/pr-str'd value string before it is
   persisted: any value (a giant scalar, a wide map, a long string)
   clips to a well-formed string that names `result/<id>` for the full
   live value. The cap's ONE owner is
   `seon.config/result-body-render-cap` (chars — the same knob
   `seon.agent.ctx/result-body-render-cap` reads for the read-time
   render; C32), converted to this clip's TOKEN budget at the boundary
   (chars/4, default 16384 chars = 4096 tokens); the transcript's
   age-keyed `:seon.agent.ctx.transcript/result-decay` schedule bands it
   per result age.

   Delegates the cut to `seon.ai.tokens/clip-str` (the ONE bounded-print)
   with a loud, token-denominated marker: a one-line pointer to the full
   value's `result/<id>` live var. Under the cap → returned unchanged.
   Names the id so the agent always knows where the untruncated value
   lives. Pure; nil-safe."
  {:malli/schema [:=> [:catn [::eval-id :string] [::body :string]] :string]}
  [eval-id body]
  (tokens/clip-str
    body
    (tokens/chars->tokens (config/result-body-render-cap))
    (fn [budget total]
      (str "\n; … +" (- total budget) " tokens clipped (of " total "); the "
           "full value is the live var result/" eval-id " — drill it with "
           "get-in/filter/subs."))))

;; --- agent-safe projection ---------------------------------------------
;; The transcript shows a CLIPPED, READER-SAFE summary of each eval value,
;; never a raw dump. A datahike DB/Datom/Entity (or any runtime handle,
;; record, or raw JS object) dumped verbatim into the transcript is both
;; noise (a multi-KB index blob) AND a self-inflicted trap: the agent reads
;; its OWN committed work back as `#datahike/DB {…}` / `[object Object]`
;; instead of data. The opaque-detection + plain-data projection that fixes
;; this lives in ONE place — `seon.render.value` (`render-ai` for the
;; bounded display skeleton, `project-plain` for the read-side net below).

(defn sanitize-result-edn
  "READ-SIDE net for the value projection.

   A row written BEFORE the
   write-side projection landed still holds a raw `#datahike/DB {…}` /
   `#datahike/Datom […]` (or other opaque-tagged) dump in its stored
   `:seon.eval/result-edn` string. Re-read it with `cljs.reader` (whose
   tag table reconstructs the datahike/record handles as real objects),
   re-project via `seon.render.value/project-plain`, and re-pr-str — so
   legacy transcript rows sanitize on render WITHOUT a cluster reset.

   Cheap-cases-fast: only the substring-screen (`#datahike/` / `#js ` /
   `#object`) triggers the read+reproject; an already-clean string (the
   common case, and every row written post-fix) is returned untouched.
   Never throws — an unreadable string is returned verbatim (the value the
   agent already sees today)."
  {:malli/schema [:=> [:catn [::s :any]] :any]}
  [s]
  (if (and (string? s)
           (or (str/includes? s "#datahike/")
               (str/includes? s "#js ")
               (str/includes? s "#object")))
    (try
      ;; probe: an unreadable legacy stored string is expected (the reason
      ;; the substring-screened re-read exists) — return it verbatim, the
      ;; same value the agent already sees; absence of a clean re-read is
      ;; not a defect.
      (pr-str (value/project-plain (reader/read-string s)))
      (catch :default _ s))
    s))

(defn render-result-edn
  "Stringify an eval's success VALUE for `:seon.eval/result-edn`.

   The agent-facing text.

   Delegates to `seon.render.value/render-ai`: a DEPTH- and BREADTH-bounded
   structure-revealing SKELETON of the value. Opaque runtime handles
   (datahike DB/Datom/Entity, records, raw JS objects, fns) become compact
   markers, wide/deep collections bound with elision markers (+ a shared
   key-set for homogeneous rows), and a partial view appends ONE trailing
   `; ‹partial view…›` hint naming `result/<id>`. The agent navigates the
   skeleton with ordinary Clojure (`get-in`/`filter`/`count`) against the
   full live value `result/<id>` — never reads its own work back as a
   `#datahike/DB {…}` blob.

   `clip-result-body` is the final token-cap backstop: `render-ai` is
   bounded so it rarely fires, but a pathological deep/wide value still
   clips to a well-formed string that names `result/<id>`. Operates on the
   RAW value (pre-pr-str). Pure: stores nothing, does not touch the
   live result store. Never throws."
  {:malli/schema [:=> [:catn [::eval-id :string] [::value :any]] :string]}
  [eval-id value]
  (clip-result-body
    eval-id
    (try
      (value/format-ai
        {::value/eval-id eval-id
         ::value/prepared (value/prepare-ai {::value/value value})})
      (catch :default _
        (str "; <value could not be rendered as data; the live value is "
             "result/" eval-id ">")))))

(defn- render-prepared-result-edn
  "Format one already-prepared eval value under its final candidate id."
  [eval-id prepared]
  (clip-result-body
    eval-id
    (try
      (value/format-ai {::value/eval-id eval-id
                        ::value/prepared prepared})
      (catch :default _
        ;; Formatting runs inside the allocator's retryable transaction builder,
        ;; so it must be total and side-effect free. The raw value was already
        ;; prepared outside this function and is never revisited here.
        (str "; <value could not be rendered as data; the live value is "
             "result/" eval-id ">")))))

(defn- unsafe-to-reallocate?
  [envelope]
  (let [data (get-in envelope [:seon.db/error :seon.error/data])
        error-tag (::db.id/error data)
        transaction-status (::db.protocol/status data)]
    (or (= "seon.db.id.error" (some-> error-tag namespace))
        (contains? #{db.protocol/unknown-status
                     db.protocol/committed-status}
                   transaction-status))))

(defn ^:async record-eval!
  "Allocate and transact one eval as a component child of its turn.

   The caller supplies a committed turn id and a frozen eval outcome—never an
   eval id. The allocator may retry only the pure transaction builder that
   associates a candidate id into that frozen data. Accepted program-graph tee
   operations ride in the same transaction as the eval identity and turn
   component assertion.

   A non-allocator transaction failure with a nonempty tee gets one transcript-
   first recovery allocation using the same frozen outcome and no tee. That
   fallback may commit a different id and stamps it with
   `:seon.eval/record-error`. Allocator/protocol failures never enter the
   fallback. Returns an error envelope or a success carrying the one committed
   `:seon.eval/id`; no result handle is bound here."
  {:malli/schema [:=> [:catn [::record-request :map]] :any]}
  [{::keys [at narration source result duration-ms tee output pending?]
    turn-id :seon.agent.turn/id-of-turn
    ns      ::ending-ns}]
  (let [conn db/*conn*
        aid  (db/current-agent-id)
        stable-eval-row
        (cond-> {:seon.eval/at          at
                 :seon.eval/duration-ms (or duration-ms 0)
                 :seon.eval/narration   (or narration "")
                 :seon.eval/source      (or source "")
                 :seon.eval/ok?         (boolean (::ok? result))
                 :seon.eval/ns          (if (keyword? ns)
                                          ns
                                          (keyword (str ns)))}
          aid
          (assoc :seon.eval/agent [:seon.agent/id aid])

          (and (string? output) (not (str/blank? output)))
          (assoc :seon.eval/output (cap-edn output))

          (not (::ok? result))
          (assoc :seon.eval/error
                 (cap-edn
                   (try (render-error-string (:seon/error result))
                        (catch :default e
                          (error/record! {:seon.error/raw e
                                          :seon.error/fault :core})
                          (str (:seon/error result))))))

          (and (not (::ok? result))
               (einstrument/instrument-error?
                 (some-> result :seon/error :seon.error/data)))
          (assoc :seon.eval/error-data
                 (einstrument/pr-str-readable
                   (-> result :seon/error :seon.error/data))))
        prepared-value
        (when (and (::ok? result) (not pending?))
          (value/prepare-ai {::value/value (::value result)}))
        allocate-record!
        (fn ^:async allocate-record! [accepted-tee]
          (await
            (db.id/allocate!
              {::db.id/allocations
               [{::db.id/key ::eval-allocation
                 ::db.id/identity-attr :seon.eval/id}]
               ::db.id/transaction-builder
               (fn [{eval-id ::eval-allocation}]
                 (let [stored-value (if pending?
                                      (pending-placeholder eval-id)
                                      (::value result))
                       eval-row (cond->
                                  (assoc stable-eval-row :seon.eval/id eval-id)
                                  (::ok? result)
                                  (assoc :seon.eval/result-edn
                                         (cap-edn
                                           (if pending?
                                             (render-result-edn eval-id
                                                                stored-value)
                                             (render-prepared-result-edn
                                               eval-id prepared-value)))))]
                   {:seon.db/tx-data
                    (into [{:seon.agent.turn/id turn-id
                            :seon.agent.turn/evals [eval-row]}]
                          accepted-tee)}))
               :seon.db/conn conn})))
        primary (await (allocate-record! (vec (or tee []))))]
    (if (:seon.db/ok? primary)
      {:seon.db/ok? true
       :seon.eval/id (get-in primary [::db.id/ids ::eval-allocation])
       ::tee-recorded? true}
      (do
        (js/console.error "[seon.eval/record-eval!] tx FAILED:"
                          (-> primary :seon.db/error :seon.error/message)
                          "— source:" source)
        (if (and (seq tee) (not (unsafe-to-reallocate? primary)))
          (let [fallback (await (allocate-record! []))]
            (if (:seon.db/ok? fallback)
              (let [eval-id (get-in fallback
                                    [::db.id/ids ::eval-allocation])
                    reason (cap-edn
                             (str (count tee) " program-graph tee row(s) "
                                  "DROPPED (will not survive a restart) — "
                                  "tee tx failed: "
                                  (-> primary :seon.db/error
                                      :seon.error/message)))
                    stamped (await
                              (db/transact!
                                (cond-> {:seon.db/tx-data
                                         [{:seon.eval/id eval-id
                                           :seon.eval/record-error reason}]}
                                  conn (assoc :seon.db/conn conn))))]
                (js/console.error
                  "[seon.eval/record-eval!] eval row RECOVERED without tee —"
                  (count tee) "program-graph tee row(s) DROPPED for eval"
                  eval-id)
                (when-not (:seon.db/ok? stamped)
                  (js/console.error
                    "[seon.eval/record-eval!] could not stamp"
                    ":seon.eval/record-error on eval" eval-id ":"
                    (-> stamped :seon.db/error :seon.error/message)))
                {:seon.db/ok? true
                 :seon.eval/id eval-id
                 ::tee-recorded? false})
              (do
                (js/console.error
                  "[seon.eval/record-eval!] DATA LOSS — eval row could not be"
                  "persisted even without tee:"
                  (-> fallback :seon.db/error :seon.error/message)
                  "— source:" source)
                fallback)))
          primary)))))

;; ============================================================
;; REPL-parity intercepts (unit #23 fix d, per the plan's REPL-PARITY
;; CONTRACT). The agent's context mimics a real Clojure REPL, so its
;; reflexive moves must work — or fail with a translation that teaches
;; the core equivalent. One form gets a form-level pre-check BEFORE
;; eval (probed live 2026-06-09: bare `*ns*` SILENTLY evals to nil —
;; a silent wrong answer, the worst kind):
;;
;;   *ns*         → INTERCEPTED VALUE: the current ns symbol (honest —
;;                  it IS the ns this form runs in; teaching-only would
;;                  leave the silent nil in place).
;;
;; `in-ns` / `alias` / `ns-unmap` / `ns-unalias` are REAL forms now —
;; see [[dispatch-repl-form!]] (owner rulings 2026-07-10).
;;
;; There is NO `*1 *2 *3` intercept: every successful eval's value is a
;; live, addressable `result/<id>` var (the id is on its `=>` line in the
;; transcript), which subsumes REPL history. A bare `*1` is no longer
;; intercepted — it falls through to a normal eval (and reads as an
;; ordinary undeclared var if used).
;; ============================================================

(defn parity-intercept
  "Form-level REPL-parity pre-check.

   Given a form's source string and the
   current ns symbol, returns nil (no intercept — eval normally) or

     {:seon.eval/parity :value :seon.eval/value <substituted value>}

   Pure string check on the TRIMMED whole form — embedded uses (e.g.
   a `(do *ns*)` wrapping) are NOT intercepted; they silently nil out
   on their own (known parity boundary)."
  {:malli/schema [:=> [:catn [::source :any] [::current-ns :any]] :any]}
  [source current-ns]
  (let [s (str/trim (or source ""))]
    (when (= s "*ns*")
      {:seon.eval/parity :value
       :seon.eval/value  current-ns})))

(defn- ns-spec-opts
  "The `{:as alias :refer [names…]}` option map of a `(:require …)` spec.
   A spec is a bare ns symbol (no opts → nil) or `[ns & opts]` where opts
   are keyword/value pairs. Returns nil when there are no (or malformed,
   odd-count) opts."
  [spec]
  (when (and (vector? spec) (even? (count (rest spec))))
    (apply hash-map (rest spec))))

(defn augment-ns-source
  "Inject real `:require`s into an agent's `(ns …)` SOURCE.

   Real requires (#73/#56): given an agent-eval'd SOURCE that is a single
   `(ns NAME …)` form for an agent-authored namespace, return the source
   rewritten so NAME's `:require` clause carries the canonical short aliases
   ([[authored-ns-require-specs]]) — `db`→seon.db, `plan`→my.plan,
   `message`→seon.agent.message, `schema`→seon.schema. NO magic injection: the
   aliases resolve because they are REALLY `:require`d, in the source the agent
   sees, eval'd, and persisted as `:seon.ns/source`. The `my.*` toolkit stays
   FULL-QUALIFIED (`my.ui/…`) — it is not aliased here.

   The footgun this closes: those aliases are established ONLY in the agent's
   home ns ([[setup-agent-ns!]]). When the agent authors a NEW `my.*` ns and a
   fn there reaches for the `db/`/`message/`/`plan/` aliases it SEES in its
   home-ns workspace, they don't resolve (`db/transact! is not defined`).
   Writing the real requires into every agent-authored ns makes agent code
   portable across namespaces — and makes the stored `:seon.ns/source`
   self-consistent for resume replay (the re-eval'd `(ns …)` form carries the
   deps its fns need). Full-qualification (`seon.db/query`) is the
   always-correct floor; this just makes the short alias work too.

   Only canonical specs whose ns/alias the agent did NOT already claim are
   added (the agent's own requires win; no duplicate-alias analyzer
   error). Returns the original source UNCHANGED (identical?) when it isn't
   a single agent `(ns …)` form, when NAME is transient scaffolding, or
   when nothing needs adding — so a complete home-ns form, or a re-eval, is
   a no-op and its formatting is preserved."
  {:malli/schema [:=> [:catn [::source [:maybe :string]]] [:maybe :string]]}
  [source]
  (let [forms (read-all-forms source)
        form  (when (= 1 (count forms)) (first forms))]
    (if-not (and (seq? form)
                 (= 'ns (first form))
                 (symbol? (second form))
                 (not (contains? transient-ns-syms (second form))))
      source
      (let [name-sym (second form)
            clauses  (drop 2 form)
            req      (some (fn [c] (when (and (seq? c) (= :require (first c))) c))
                           clauses)
            specs    (vec (rest req))
            req-nses (set (map (fn [s] (if (vector? s) (first s) s)) specs))
            aliases  (set (keep #(:as (ns-spec-opts %)) specs))
            refers   (set (mapcat #(:refer (ns-spec-opts %)) specs))
            added    (reduce
                       (fn [acc spec]
                         (let [cns  (first spec)
                               opts (ns-spec-opts spec)]
                           (cond
                             (contains? req-nses cns) acc
                             (:as opts)    (if (contains? aliases (:as opts))
                                             acc
                                             (conj acc spec))
                             (:refer opts) (let [missing (vec (remove refers
                                                                     (:refer opts)))]
                                             (if (seq missing)
                                               (conj acc [cns :refer missing])
                                               acc))
                             :else         acc)))
                       []
                       authored-ns-require-specs)]
        (if (empty? added)
          source
          (let [new-req     (apply list :require (concat specs added))
                other       (remove (fn [c] (= c req)) clauses)
                new-clauses (concat other [new-req])]
            (pr-str (apply list 'ns name-sym new-clauses))))))))

;; ============================================================
;; REPL movement/update forms (owner rulings 2026-07-10, settled):
;; `in-ns` = THE movement function; `(ns foo …)` = the declare/overwrite
;; function; a bare top-level `(require …)` works AND persists into the
;; namespace's stored declaration (durable-by-default — the DB is the
;; source of truth, resume replays it); `alias` records a require
;; alias; redefinition IS update; `ns-unmap`/`ns-unalias` remove.
;; The pure helpers live here; [[dispatch-repl-form!]] (below, next to
;; the eval pipeline it feeds) executes them.
;; ============================================================

(def repl-form-heads
  "Form heads [[dispatch-repl-form!]] owns — the REPL movement/update
   functions implemented at the eval boundary rather than by the compiler."
  '#{in-ns alias ns-unmap ns-unalias})

(defn repl-form-of
  "The parsed REPL form of `source` when it is a single top-level list
   whose head is one of [[repl-form-heads]]; nil otherwise (the caller
   evals normally). Fail-soft on unreadable source."
  {:malli/schema [:=> [:catn [::source [:maybe :string]]] :any]}
  [source]
  (let [forms (try (read-all-forms source) (catch :default _ nil))
        form  (when (= 1 (count forms)) (first forms))]
    (when (and (seq? form) (symbol? (first form))
               (contains? repl-form-heads (first form)))
      form)))

(defn- quoted-sym
  "`x` as a symbol: a bare symbol or a `(quote sym)` form; nil otherwise."
  [x]
  (cond
    (symbol? x) x
    (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x)))

(defn- analyzer-ns-entry?
  "True when `ns-sym` has a COMPLETE analyzer entry in `compile-state`
   (a `:name`-bearing `:cljs.analyzer/namespaces` row — the loaded/
   primed state, not a bare skeleton)."
  [compile-state ns-sym]
  (some? (:name (get-in @compile-state [:cljs.analyzer/namespaces ns-sym]))))

(defn- normalize-spec
  "A require libspec as `[target-sym opts-map]` — a bare ns symbol has
   `{}` opts."
  [spec]
  (if (vector? spec)
    [(first spec) (or (ns-spec-opts spec) {})]
    [spec {}]))

(defn- opts->spec
  "Rebuild a libspec from `target` + `opts` — a bare symbol when opts
   are empty, else `[target :as … :refer …]` (keys sorted, stable)."
  [target opts]
  (if (empty? opts)
    target
    (into [target] (mapcat identity (sort-by key opts)))))

(defn- merge-spec-opts
  "Merge a NEW libspec's opts onto an OLD spec's for the same target:
   the new `:as` wins, `:refer` lists union (`:all` absorbs), and a
   real `:as` supersedes a reader-only `:as-alias`."
  [old new]
  (let [refers (let [o (:refer old) n (:refer new)]
                 (cond
                   (or (= :all o) (= :all n)) :all
                   (or o n) (vec (sort (distinct (concat o n))))))
        m      (merge old new)
        m      (if refers (assoc m :refer refers) (dissoc m :refer))]
    (if (and (:as m) (:as-alias m)) (dissoc m :as-alias) m)))

(defn merge-requires-into-ns-source
  "Rewrite `(ns …)` source so its `:require` clause carries `new-specs`.

   Durable-by-default (owner ruling 2026-07-10): a REPL-issued bare
   `(require …)` persists into the namespace's STORED declaration.
   Appends specs whose target ns is absent; merges opts into an
   existing spec for the same target ([[merge-spec-opts]]). Returns nil
   when `ns-source` isn't a single `(ns …)` form or when nothing
   changed — so re-requiring the same spec is a no-op (idempotent)."
  {:malli/schema [:=> [:catn [::ns-source [:maybe :string]]
                       [::new-specs [:sequential :any]]]
                  [:maybe :string]]}
  [ns-source new-specs]
  (let [forms (when ns-source
                (try (read-all-forms ns-source) (catch :default _ nil)))
        form  (when (= 1 (count forms)) (first forms))]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      (let [name-sym (second form)
            clauses  (drop 2 form)
            req      (some (fn [c] (when (and (seq? c) (= :require (first c))) c))
                           clauses)
            specs    (vec (rest req))
            upsert   (fn [acc nspec]
                       (let [[t nopts] (normalize-spec nspec)
                             idx (some (fn [[i s]]
                                         (when (= t (first (normalize-spec s))) i))
                                       (map-indexed vector acc))]
                         (if idx
                           (update acc idx
                                   (fn [old]
                                     (let [[_ oopts] (normalize-spec old)]
                                       (opts->spec t (merge-spec-opts oopts nopts)))))
                           (conj acc (opts->spec t nopts)))))
            new-vec  (reduce upsert specs new-specs)]
        (when (not= new-vec specs)
          (let [new-req (apply list :require new-vec)
                other   (remove (fn [c] (= c req)) clauses)]
            (pr-str (apply list 'ns name-sym (concat other [new-req])))))))))

(defn- require-form-specs
  "The libspecs of a top-level `(require …)` form, quotes stripped and
   flag keywords (`:reload` …) dropped. nil when `form` isn't a require."
  [form]
  (when (and (seq? form) (symbol? (first form)) (= 'require (first form)))
    (into []
          (keep (fn [arg]
                  (let [a (if (and (seq? arg) (= 'quote (first arg)))
                            (second arg)
                            arg)]
                    (when (or (symbol? a)
                              (and (vector? a) (symbol? (first a))))
                      a))))
          (rest form))))

(defn- ns-source-core-boot?
  "True when `ns-kw`'s CURRENT `:seon.ns/source` datom was written by
   the core seed — a REPL-issued require must never rewrite a core ns's
   stored declaration (the [[reject-core-overrides]] symmetry)."
  [db ns-kw]
  (some? (ffirst (db/query '[:find ?e :in $ ?ns
                             :where
                             [?e :seon.ns/name ?ns]
                             [?e :seon.ns/source _ ?tx]
                             [?tx :seon.db/process ?process]
                             [?process :seon.db.process/id
                              :seon.db.process/boot]]
                           db ns-kw))))

(defn- stored-ns-source
  "The stored `:seon.ns/source` string for `ns-kw`, or nil."
  [db ns-kw]
  (ffirst (db/query '[:find ?src :in $ ?ns
                      :where
                      [?e :seon.ns/name ?ns]
                      [?e :seon.ns/source ?src]]
                    db ns-kw)))

(defn require-decl-tx
  "Tx upserting `ns-kw`'s stored `:seon.ns/source` with the specs of a
   bare `(require …)` `source` merged in — the durable-by-default rule.

   `[]` when `source` isn't a single require form, the ns has no stored
   source (a sourceless ns — the home ns — already persists via its
   require-edges + [[synthesized-ns-head]]), the stored declaration is
   core-seeded, or the merge is a no-op (idempotent)."
  {:malli/schema [:=> [:catn [::db :any] [::ns-kw :keyword]
                       [::source [:maybe :string]]]
                  [:vector :any]]}
  [db ns-kw source]
  (let [form  (first (or (try (read-all-forms source) (catch :default _ nil)) []))
        specs (require-form-specs form)]
    (if-not (seq specs)
      []
      (let [ns-src (stored-ns-source db ns-kw)]
        (if (or (nil? ns-src) (ns-source-core-boot? db ns-kw))
          []
          (if-some [new-src (merge-requires-into-ns-source ns-src specs)]
            [{:seon.ns/name ns-kw :seon.ns/source new-src}]
            []))))))

(defn- unalias-decl-tx
  "Tx upserting `ns-kw`'s stored `:seon.ns/source` with `alias-sym`
   removed: an `:as`/`:as-alias` opt naming it is dropped (the ns stays
   required — unalias never unloads); a spec that ONLY carried the
   reader alias is dropped whole. `[]` when there's no stored source,
   the declaration is core-seeded, or nothing changed."
  [db ns-kw alias-sym]
  (let [ns-src (stored-ns-source db ns-kw)
        forms  (when (and ns-src (not (ns-source-core-boot? db ns-kw)))
                 (try (read-all-forms ns-src) (catch :default _ nil)))
        form   (when (= 1 (count forms)) (first forms))]
    (if-not (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      []
      (let [name-sym (second form)
            clauses  (drop 2 form)
            req      (some (fn [c] (when (and (seq? c) (= :require (first c))) c))
                           clauses)
            specs    (vec (rest req))
            new-vec  (into []
                           (keep (fn [s]
                                   (let [[t opts] (normalize-spec s)
                                         hit-keys (into []
                                                        (keep (fn [[k v]]
                                                                (when (and (#{:as :as-alias} k)
                                                                           (= v alias-sym))
                                                                  k)))
                                                        opts)
                                         opts'    (apply dissoc opts hit-keys)]
                                     (cond
                                       (empty? hit-keys) s
                                       ;; a reader-only alias spec does nothing
                                       ;; once unaliased — drop it whole.
                                       (and (= hit-keys [:as-alias]) (empty? opts')) nil
                                       :else (opts->spec t opts')))))
                           specs)]
        (if (= new-vec specs)
          []
          (let [new-req (when (seq new-vec) (apply list :require new-vec))
                other   (remove (fn [c] (= c req)) clauses)
                decl    (apply list 'ns name-sym
                               (concat other (when new-req [new-req])))]
            [{:seon.ns/name ns-kw :seon.ns/source (pr-str decl)}]))))))

;; ============================================================
;; Pre-flight form autofix (owner rulings 2026-07-05; design:
;; docs/prds/agent-ctx/research/form-autofix-system-2026-07-05.md).
;;
;; At `:symbols`+ every eligible form is COMPILE-GATED before execution
;; (compile-only — the `:eval` hook is a no-op, so trials can never fire
;; side effects). A compile failure with a provable near-miss — a
;; def-vs-defn typo, or an undeclared var with a UNIQUE compile-proven
;; near match — is FIXED; the fixed form's real eval below is then the
;; form's FIRST run. Ambiguity ALWAYS refuses: the error gains the
;; did-you-mean candidates instead. One mechanism, two consumers: the
;; candidate/distance/threshold/tier logic is the SHARED
;; `seon.repair.candidates` (the worker-eval bundle's op:"repair" rides
;; the same code).
;; ============================================================

(defn- repair-class-on?
  "Is fix class `class` enabled under the live repair config? The
   computed rule: `seon.repair/class-enabled?` over the config level +
   per-class kill-switch map."
  [class]
  (repair/class-enabled? {:seon.repair/level   (config/repair-level)
                          :seon.repair/classes (config/repair-classes)
                          :seon.repair/class   class}))

(defn- qualified-sym-misses
  "Qualified symbol references in `source` that provably resolve NOWHERE.

   The analyzer does NOT warn `:undeclared-var` for a missing member of
   a cache-known ns (live-observed 2026-07-05: `(my.plan/nxt {})`
   compiles silently and throws 'not a function' at runtime), so the
   pre-flight gate computes the miss itself with the SAME proof surface
   `truly-undeclared?` uses: alias-resolve the prefix via the eval ns's
   require-edges, then require the name to be absent from the resolved
   ns's analyzer `:defs`, its `$macros` defs (a qualified MACRO call is
   legal and has no runtime var), AND its globalThis munged path
   ([[lookup-value]]). Only nses that EXIST (analyzer entry or live ns
   object) are considered — a bogus ns is the `:undeclared-ns` class,
   not this one. Quoted subtrees, the reserved `result` ns, and `js`
   interop are skipped. Returns `[{:prefix s :suffix s} …]` (the
   warning shape, so the repair loop treats both sources identically)."
  [compile-state source ns-sym]
  (let [aliases (try
                  (::aliases (edges->require-info
                               (analyzer-info/ns-require-edges
                                 compile-state ns-sym)))
                  (catch :default _ {}))
        nses    (get @compile-state :cljs.analyzer/namespaces)
        syms    (volatile! [])
        walk    (fn walk [x]
                  (cond
                    (and (seq? x) (= 'quote (first x))) nil
                    (symbol? x) (when (qualified-symbol? x)
                                  (vswap! syms conj x))
                    (coll? x)   (run! walk x)))]
    (run! walk (or (read-all-forms source) []))
    (into []
          (comp
            (distinct)
            (keep (fn [s]
                    (let [n  (symbol (namespace s))
                          n' (get aliases n n)
                          m  (name s)]
                      (when (and (not= 'result n')
                                 (not= 'js n)
                                 ;; ctor sugar `(Ns/Type. …)` — the trailing
                                 ;; dot is never a var name; substituting it
                                 ;; away would silently turn a ctor call
                                 ;; into a fn call.
                                 (not (str/ends-with? m "."))
                                 (or (seq (:defs (get nses n')))
                                     (ns-live-on-globalthis? n'))
                                 (not (contains? (:defs (get nses n'))
                                                 (symbol m)))
                                 (not (contains? (:defs (get nses
                                                           (symbol (str n' "$macros"))))
                                                 (symbol m)))
                                 (nil? (lookup-value (symbol (str n') m))))
                        {:prefix (str n') :suffix m})))))
          @syms)))

(defn- compile-check
  "COMPILE-ONLY pass over `source` in `ns-sym` — analyzer + emitter run,
   NOTHING executes (the `:eval` hook is a no-op; the pod twin of the
   worker-eval repair trial). Resolves a plain map:

     {::check-ok?         bool   ; no thrown error, no real undeclared
      ::check-error       e|nil  ; a THROWN analysis error (bad def …)
      ::check-undeclared  [w …]} ; truly-undeclared refs
                                 ; ({:prefix :suffix …})

   `::check-undeclared` unions TWO detectors: the captured
   `:undeclared-var` analyzer warnings (filtered by `truly-undeclared?`
   — the benign `::analyze-deps? false` false-positives suppressed
   exactly as the real path does), and [[qualified-sym-misses]] (the
   qualified member-of-a-known-ns misses the analyzer never warns
   about). Warning capture rides the same per-fiber `warnings-als`
   bucket as `raw-eval`. Analyzer state DOES accumulate trial defs —
   the caller ([[preflight-repair!]]) rolls those back via
   remove-phantom-defs!."
  [compile-state source ns-sym]
  (js/Promise.
    (fn [resolve _reject]
      (let [warnings (atom [])]
        (.run warnings-als warnings
          (fn []
            (try
              (cljs/eval-str compile-state source dynamic-ns-sym
                {:eval          (fn [_] nil)   ; trial: compile, execute NOTHING
                 :load          (partial guarded-load compile-state)
                 :ns            ns-sym
                 :context       :statement
                 :def-emits-var true
                 :analyze-deps  false}
                (fn [{:keys [error]}]
                  (let [warned (filterv
                                 (fn [w]
                                   (and (= :undeclared-var
                                           (:seon.eval/warning-type w))
                                        (truly-undeclared? compile-state w)))
                                 @warnings)
                        misses (when (nil? error)
                                 (qualified-sym-misses
                                   compile-state source ns-sym))
                        seen   (into #{} (map #(str (:suffix %))) warned)
                        undecl (into warned
                                     (remove #(contains? seen
                                                         (str (:suffix %))))
                                     (or misses []))]
                    (resolve {::check-ok?        (and (nil? error)
                                                      (empty? undecl))
                              ::check-error      error
                              ::check-undeclared undecl}))))
              (catch :default e
                (resolve {::check-ok?        false
                          ::check-error      e
                          ::check-undeclared []})))))))))

(defn- source-token-for
  "The symbol TOKEN (as written in `source`) whose simple name is
   `suffix` — the substitution target for a symbol fix. An
   alias-qualified reference (`plan/addd!`) warns with the RESOLVED
   prefix (`my.plan`), so only the as-written token can be substituted
   back into the source. nil when no such symbol is found."
  [source suffix]
  (let [want  (str suffix)
        found (volatile! nil)
        walk  (fn walk [x]
                (when (nil? @found)
                  (cond
                    (symbol? x) (when (= (name x) want)
                                  (vreset! found (str x)))
                    (coll? x)   (run! walk x))))]
    (run! walk (or (read-all-forms source) []))
    @found))

(defn- analyzer-def-names
  "Simple-symbol def NAMES registered in `ns-sym`'s analyzer entry."
  [compile-state ns-sym]
  (->> (get-in @compile-state [:cljs.analyzer/namespaces ns-sym :defs])
       keys
       (filter simple-symbol?)
       (mapv str)))

(defn- graph-fn-names-in-ns
  "Program-graph `:seon.fn/sym` NAME parts scoped to namespace `ns-str`
   — the AR win: an agent's typo'd function name (`my.plan/addd!`) resolves
   against REAL fns. Empty when no conn."
  [ns-str]
  (if db/*conn*
    (into []
          (keep (fn [fq]
                  (when (= ns-str (candidates/ns-part fq))
                    (candidates/name-part fq))))
          (db/query '[:find [?s ...] :where [?e :seon.fn/sym ?s]]
                    @db/*conn*))
    []))

(defn- repair-candidate-names
  "The candidate NAME pool for one unresolved `token`.

   QUALIFIED token → same-ns sources only: the RESOLVED ns's analyzer
   defs, its live compiled members ([[ns-fn-members]]), and its
   program-graph fns. BARE token → session defs (eval ns + `cljs.user`)
   plus `cljs.core` publics. Graph names are NOT candidates for a bare
   token — substituting an unqualified name for a fn that needs
   qualification can never compile-prove (qualifier fixes are the
   unimplemented `:aggressive` tier)."
  [compile-state eval-ns token resolved-prefix]
  (if (candidates/ns-part token)
    (-> #{}
        (into (analyzer-def-names compile-state (symbol resolved-prefix)))
        (into (map str (keys (ns-fn-members resolved-prefix))))
        (into (graph-fn-names-in-ns resolved-prefix))
        vec)
    (-> #{}
        (into (analyzer-def-names compile-state eval-ns))
        (into (analyzer-def-names compile-state user-ns-sym))
        (into (analyzer-def-names compile-state 'cljs.core))
        vec)))

(defn- ^:async preflight-repair-run!
  "The detect → candidates → compile-only trials → apply-or-hint loop
   over ONE form's source (see [[preflight-repair!]], which owns the
   analyzer cleanup around this). Returns nil / a fix map / a
   suggestions map — never throws to the caller (the wrapper records)."
  [compile-state source ns-sym]
  (let [budget    (config/repair-budget-ms)
        start     (.now js/Date)
        over?     #(> (- (.now js/Date) start) budget)
        max-fixes (config/repair-max-fixes)]
    (loop [src source fixes [] cls nil]
      (let [{::keys [check-ok? check-error check-undeclared]}
            (await (compile-check compile-state src ns-sym))
            w     (first check-undeclared)
            token (when w (source-token-for src (:suffix w)))]
        (cond
          ;; Clean — a fix chain that ends clean APPLIES; no fixes = the
          ;; form was fine (or only fails in ways this gate doesn't own).
          check-ok?
          (when (seq fixes)
            {:seon.repair/source        src
             :seon.repair/fixes         fixes
             :seon.repair/applied-class (or cls :seon.repair/undeclared-var)})

          (over?) nil

          ;; A THROWN analysis error — repairable only as the
          ;; def-vs-defn class (`(def f [x] …)` = a dropped `n`;
          ;; detection is the AST-only grammar/malformed-def?).
          (some? check-error)
          (let [form  (first (or (read-all-forms src) []))
                fixed (when (and (empty? fixes)
                                 (repair-class-on? :seon.repair/def-vs-defn)
                                 (grammar/malformed-def? form))
                        (str/replace-first src #"\(\s*def\s+" "(defn "))]
            (when (and fixed (not= fixed src))
              (recur fixed
                     (conj fixes {:seon.repair/from "def"
                                  :seon.repair/to   "defn"})
                     :seon.repair/def-vs-defn)))

          ;; Undeclared var(s). Unique-winner substitution, else hint.
          :else
          (let [class-on? (repair-class-on? :seon.repair/undeclared-var)]
            (if (or (nil? token) (not class-on?)
                    (>= (count fixes) max-fixes))
              ;; Won't fix (cap hit / no token) — still surface the
              ;; did-you-mean pool when the class is on and we have one.
              (when (and token class-on? (empty? fixes))
                (let [names (repair-candidate-names
                              compile-state ns-sym token (str (:prefix w)))
                      cands (candidates/rank-candidates
                              (candidates/name-part token) names)]
                  (when (seq cands)
                    {:seon.repair/from        token
                     :seon.repair/suggestions cands
                     :seon.repair/ambiguous?  false})))
              (let [qpart       (candidates/ns-part token)
                    from-nm     (candidates/name-part token)
                    replacement (fn [to-nm]
                                  (if qpart (str qpart "/" to-nm) to-nm))
                    names       (repair-candidate-names
                                  compile-state ns-sym token (str (:prefix w)))
                    cands       (candidates/rank-candidates from-nm names)
                    pick        (await
                                  (candidates/pick-winner
                                    {:seon.repair/cands cands
                                     :seon.repair/over? over?
                                     :seon.repair/passes?
                                     ;; PROOF: the substituted source must
                                     ;; compile with no thrown error and no
                                     ;; remaining undeclared hit on either
                                     ;; side of the swap (other names may
                                     ;; remain — the chained-typo case).
                                     (fn ^:async candidate-passes? [c]
                                       (let [to-nm (:seon.repair/to c)
                                             code' (candidates/substitute-symbol
                                                     src token (replacement to-nm))
                                             {err2 ::check-error
                                              und2 ::check-undeclared}
                                             (await (compile-check
                                                      compile-state code' ns-sym))]
                                         (and (nil? err2)
                                              (not-any?
                                                #(contains? #{from-nm to-nm}
                                                            (str (:suffix %)))
                                                und2))))}))]
                (cond
                  (:seon.repair/winner pick)
                  (let [to-tok (replacement
                                 (:seon.repair/to (:seon.repair/winner pick)))]
                    (recur (candidates/substitute-symbol src token to-tok)
                           (conj fixes {:seon.repair/from token
                                        :seon.repair/to   to-tok})
                           (or cls :seon.repair/undeclared-var)))

                  (:seon.repair/ambiguous pick)
                  {:seon.repair/from        token
                   :seon.repair/suggestions (mapv #(update % :seon.repair/to
                                                           replacement)
                                                  (:seon.repair/ambiguous pick))
                   :seon.repair/ambiguous?  true}

                  (seq cands)
                  {:seon.repair/from        token
                   :seon.repair/suggestions (mapv #(update % :seon.repair/to
                                                           replacement)
                                                  cands)
                   :seon.repair/ambiguous?  false}

                  :else nil)))))))))

(def ^:private preflight-skip-heads
  "Form heads the pre-flight gate never touches: loader-class forms do
   real work during ANALYSIS (a trial would not be side-effect-free) and
   are never symbol typo-fix targets. The [[repl-form-heads]] forms are
   handled by [[dispatch-repl-form!]] upstream; kept here for the
   direct-eval callers."
  (into '#{ns require require-macros use import} repl-form-heads))

(defn- preflight-eligible?
  "Should `source` get the pre-flight compile gate? A symbol-tier class
   must be enabled, the source must be a real form (non-blank, not a
   bare `result/<id>` read), and the head must not be a loader form."
  [source]
  (and (or (repair-class-on? :seon.repair/undeclared-var)
           (repair-class-on? :seon.repair/def-vs-defn))
       (not (str/blank? (str source)))
       (not (result-var-ref? source))
       (let [form (first (or (read-all-forms source) []))]
         (not (and (seq? form)
                   (symbol? (first form))
                   (contains? preflight-skip-heads (first form)))))))

(defn- ^:async preflight-repair!
  "Pre-execution repair gate for ONE parsed form's source.

   Returns nil (compiles clean / not repairable / over budget — the
   caller proceeds with the ORIGINAL source), a FIX map
   (`:seon.repair/source` = the proven fixed source to eval INSTEAD,
   `:seon.repair/fixes`, `:seon.repair/applied-class`), or a SUGGESTIONS
   map (`:seon.repair/from` + `:seon.repair/suggestions` [+
   `:seon.repair/ambiguous?`] — the fix was REFUSED; the caller appends
   the did-you-mean to the eval error). Errors-as-values: a throw inside
   the gate records a `:core` fault and returns nil (the form just evals
   un-gated).

   Analyzer hygiene: compile-only trials register phantom defs
   (`:def-emits-var true` writes `:defs` during analysis), so every
   trial's phantoms are removed before returning — the real eval's
   `defs-before` snapshot and the detect-and-tee diff stay
   byte-identical to a run without preflight."
  [compile-state source ns-sym]
  (await (ensure-analyzer-ns! compile-state ns-sym))
  (let [defs-before (analyzer-info/snapshot-defs compile-state)
        outcome     (try
                      (await (preflight-repair-run! compile-state source ns-sym))
                      (catch :default e
                        ;; OUR repair machinery throwing is a core defect
                        ;; (:core) — record it; the form still evals
                        ;; un-gated (best-effort, never blocks the loop).
                        (error/record! {:seon.error/raw e
                                        :seon.error/fault :core})
                        nil))]
    (analyzer-info/remove-phantom-defs! compile-state defs-before ns-sym)
    outcome))

(defn- failed-def-syms
  "The symbols a `(def …)`/`(defn …)`/`(defn- …)` form would DEFINE,
   given its source string. Used by the false-confidence guard (A.4):
   when such a form's eval returns `ok? false`, its target symbol must
   NOT be treated as resolvable for the rest of the batch — a later
   `(get x :k)` against a failed-def `x` silently reads nil with
   `ok? true` (the analyzer registered `x` in `:defs` even though its
   init errored, so no `:undeclared-var` warning fires; live-falsified
   2026-06-18). Returns a set of unqualified symbols (possibly empty).
   Fail-closed on unreadable source (empty set)."
  [source]
  (set (source-def-syms source)))

(defn- references-failed-def
  "When `source` is a NON-defining form that references one of the
   `failed-defs` symbols (unqualified or `<ns>/<sym>`-qualified), return
   that symbol; else nil. The reference is escalated to an honest error
   instead of the silent nil/`ok? true` of the episode (A.4). We skip
   defining forms — redefining `x` after a failed def is legitimate
   self-correction, not a stale read."
  [source failed-defs]
  (when (seq failed-defs)
    (let [forms (read-all-forms source)
          defining? (some (fn [f]
                            (and (seq? f)
                                 (contains? '#{def defn defn-} (first f))))
                          forms)]
      (when-not defining?
        (let [names (->> failed-defs (map name) set)
              all-syms (atom #{})
              walk (fn walk [x]
                     (cond
                       (symbol? x) (swap! all-syms conj x)
                       (coll? x)   (doseq [y x] (walk y))))]
          (doseq [f forms] (walk f))
          (some (fn [s] (when (contains? names (name s)) s))
                @all-syms))))))

(defn- ^:async eval-form-entry!
  "The normal single-form eval path, extracted from `eval-batch!`'s
   `:else` branch so a parinfer-REPAIRED form (A.2) can reuse the exact
   same eval → auto-await → detect-and-tee → record → live-result bind →
   auto-instrument → auto-test-run pipeline. Behavior-preserving.

   Mutates the caller's fold volatiles in place (a transient impl
   detail inside one `eval-batch!` invocation, not shared state):
   advances `current-ns` on a successful `(ns …)` switch, increments
   `n-ok`/`n-fail`. Records failed-def provenance into `failed-defs`
   (A.4): a `(def …)`/`(defn …)` whose eval returns `ok? false` adds its
   target symbol so a LATER reference escalates instead of reading nil.

   Map keys:
     ::compile-state   — the bootstrap compile-state.
     :seon.agent.turn/id-of-turn — committed owning turn id.
     ::current-ns      — volatile<symbol>, the fold accumulator ns.
     ::n-ok ::n-fail    — volatile<int> counters.
     ::failed-defs     — volatile<set> of failed-def symbols this batch.
     ::outer-test-run? — skip auto-test-run when already inside one.
     ::narration       — narration to record (repaired forms prepend the
                        repair note here so the diff is always visible).
     ::source          — the source string to eval (repaired or original)."
  [{::keys [compile-state current-ns n-ok n-fail
            failed-defs outer-test-run? narration source]
    turn-id :seon.agent.turn/id-of-turn}]
  (let [;; Real requires (#73/#56): if this is a NEW agent-authored `(ns …)`
        ;; form, write the canonical short aliases into its REAL `:require`
        ;; clause so `db/`/`plan/`/`message/`/`schema/` resolve in the new ns
        ;; exactly as they do in the agent's home ns — no magic injection. A
        ;; no-op (identical source) for non-ns forms / a complete home ns.
        aug-source  (augment-ns-source source)
        augmented?  (not (identical? aug-source source))
        source      aug-source
        narration   (if augmented?
                      (str (when (seq narration) (str narration "\n"))
                           "; added (:require …) for " (authored-ns-alias-names)
                           " so those aliases resolve in this ns")
                      narration)
        at          (js/Date.)
        start-ms    (.now js/Date)
        ;; A.4 false-confidence guard: BEFORE eval, if this NON-defining
        ;; form references a symbol whose def failed earlier this batch,
        ;; escalate to an honest error instead of letting it read nil.
        stale-ref   (references-failed-def source @failed-defs)]
    (if stale-ref
      (let [result {::ok? false
                    :seon/error {:seon.error/kind :compile
                                 :seon.error/message
                                 (str "`" stale-ref "` does not exist — the def that "
                                      "would create it failed to evaluate earlier this "
                                      "turn (it defined NOTHING). A reference to it "
                                      "reads nil, NOT a usable value. Fix and re-eval "
                                      "the def first, then re-run this form.")}}
            recorded (await
                       (record-eval!
                         {:seon.agent.turn/id-of-turn turn-id
                          ::at          at
                          ::duration-ms 0
                          ::narration   narration
                          ::source      source
                          ::ending-ns   @current-ns
                          ::result      result}))]
        (vswap! n-fail inc)
        recorded)
      (let [;; Pre-flight symbol repair (form-autofix, owner rulings
            ;; 2026-07-05): compile-gate the form BEFORE any execution. A
            ;; provable unique near-miss fix is applied here, so the real
            ;; eval below runs the FIXED source as the form's FIRST run —
            ;; side effects can never double-fire. The fixed source is
            ;; what evals, records, AND tees (`:seon.fn/source` = fixed);
            ;; the visible `↻ fixed:` note rides the narration. A REFUSED
            ;; fix (ambiguous / unproven) surfaces as did-you-mean on the
            ;; eval error below.
            pre        (when (preflight-eligible? source)
                         (await (preflight-repair!
                                  compile-state source @current-ns)))
            fixed?     (some? (:seon.repair/source pre))
            source     (if fixed? (:seon.repair/source pre) source)
            narration  (if fixed?
                         (str (when (seq narration) (str narration "\n"))
                              (repair/fix-note
                                {:seon.repair/fixes (:seon.repair/fixes pre)}))
                         narration)
            ;; Snapshot analyzer + schema registry BEFORE eval
            ;; so detect-and-tee (v1.md §2.2 / Phase B item 10)
            ;; can diff after. Cheap reads — keyset extraction.
            defs-before    (analyzer-info/snapshot-defs compile-state)
            schemas-before (schema/current-keys)
            ;; (fix f) println/prn capture — a REPL shows print output next
            ;; to the result; `*print-fn*` otherwise routes to the pod's
            ;; stdout (logs/pod.log), invisible to the agent. Capture the
            ;; span of eval + auto-await, persist as :seon.eval/output.
            ;;
            ;; #64: this MUST be per-fiber. `*print-fn*` is process-global,
            ;; so the old `set! cap` … await … `set! prev` straddled an
            ;; await and bled a concurrent eval's prints into this bucket.
            ;; Now the global dispatcher (install-print-dispatcher!) routes
            ;; to whatever bucket is active in `print-als`, and we open our
            ;; OWN bucket here via `.run`. ALS carries the bucket across
            ;; this form's awaits; concurrent evals stay isolated. The
            ;; `.run` callback is an `^:async` iife so the store propagates
            ;; through eval + maybe-await-value; we await its result.
            out-bucket  (atom "")
            captured    (await
                          (.run print-als out-bucket
                            (fn ^:async run-with-capture! []
                              (let [raw (await (eval compile-state source
                                                     {::starting-ns @current-ns
                                                      ::analyze-deps? false}))]
                                {::raw raw
                                 ::awaited (when (::ok? raw)
                                             (await (maybe-await-value
                                                      (::value raw))))}))))
            raw-result  (::raw captured)
            awaited     (::awaited captured)
            ;; A still-running Promise — auto-await timeout OR an explicit
            ;; `(defer …)`. The form records a clean PLACEHOLDER value and
            ;; the live Promise is bound at result/<id> (see binding site
            ;; below) for a later re-reference that auto-awaits it to data.
            ;; The raw Promise NEVER becomes the displayed value — the value
            ;; renderer must not `seq` a Promise.
            pending-promise (::pending-promise awaited)
            ;; `pending-promise` is a Promise-or-nil, so `(some? …)` is
            ;; behaviorally identical to a bare test (only nil is falsey) — it
            ;; just states the present-or-absent intent. CLJS has NO
            ;; implicit-await pass: a Promise in an `if`/`or`/`cond` TEST is a
            ;; plain truthy object, NEVER awaited (the only await-emitting sites
            ;; are the `await` macro and the iife-open). So neither form would
            ;; resolve the handle; the Promise stays live for the later bind.
            pending?        (some? pending-promise)
            result
            (cond
              (not (::ok? raw-result)) raw-result
              pending?               {::ok? true
                                      ::value nil
                                      ::ending-ns (::ending-ns raw-result)}
              (::ok? awaited)        {::ok? true
                                      ::value (::value awaited)
                                      ::ending-ns (::ending-ns raw-result)}
              :else                  awaited)
            ;; Did-you-mean (form-autofix): a REFUSED fix (ambiguous /
            ;; no compile-proven winner) sharpens the failing eval's
            ;; error with the candidates, so the agent's fix-turn is
            ;; one-shot. Appended only when the failure names the same
            ;; symbol the gate analyzed (the message is the deepest —
            ;; the undeclared ex-info has no cause chain).
            result
            (let [sugg (:seon.repair/suggestions pre)
                  msg  (get-in result [:seon/error :seon.error/message])]
              (if (and (not (::ok? result))
                       (seq sugg)
                       (string? msg)
                       (str/includes?
                         msg (candidates/name-part (:seon.repair/from pre))))
                (update result :seon/error assoc :seon.error/message
                        (str msg "\n"
                             (repair/suggestion-note
                               {:seon.repair/from (:seon.repair/from pre)
                                :seon.repair/suggestions sugg
                                :seon.repair/ambiguous?
                                (boolean (:seon.repair/ambiguous? pre))})))
                result))
            ;; No restore needed — capture was scoped to the `.run print-als`
            ;; span above. Record/tee/auto-test prints below run OUTSIDE that
            ;; scope, so the global dispatcher routes them to the pod log, not
            ;; this eval's record.
            output      @out-bucket
            duration-ms (- (.now js/Date) start-ms)]
        ;; A FAILED eval leaves a PHANTOM analyzer def: under
        ;; `:def-emits-var true`, `parse 'def` registers the var-map into
        ;; `:defs` BEFORE the body error and does not roll it back. When
        ;; the body analyzes cleanly but the eval is FAILED post-eval (a
        ;; warning-promoted `:undeclared-var`, or a runtime/emit throw),
        ;; that registration is the FULL var-map — whose digest equals a
        ;; SUCCESSFUL same-signature retry's, so `defs-since` sees no
        ;; change and the tee SILENTLY SKIPS the `:seon.fn` row (the fn
        ;; works in-session but never persists). Drop the syms THIS form
        ;; newly registered in its eval ns so the retry is genuinely-new
        ;; and tees — the REPL invariant that a failed defn defines
        ;; nothing. Pre-existing defs (in defs-before) are untouched.
        (when-not (::ok? result)
          (analyzer-info/remove-phantom-defs! compile-state defs-before @current-ns)
          ;; #39: the schema analog of the phantom-def rollback. A failed
          ;; eval that ran `schema/register!` must define NOTHING. The
          ;; self-tee already DEFERRED its DB write (record boundary in scope), so
          ;; nothing persisted; drop the in-memory registry entries too —
          ;; diff is THIS form's newly-registered keys only (current-keys
          ;; minus the pre-eval snapshot) — so a re-eval of the fixed form
          ;; registers cleanly and a pre-existing schema is never touched.
          (schema/discard-registrations!
            (set/difference (schema/current-keys) schemas-before)))
        ;; Advance the accumulator on successful ns switch.
        ;; Failed evals leave the accumulator untouched —
        ;; the form ran in @current-ns and we record that
        ;; value as the form's :seon.eval/ns.
        (when (and (::ok? result) (::ending-ns raw-result))
          (vreset! current-ns (::ending-ns raw-result)))
        ;; A.4: a DEFINING form whose eval failed registers its target
        ;; symbol so a later reference escalates (see references-failed-def);
        ;; a DEFINING form that SUCCEEDS clears its symbol (a redefinition
        ;; that now works self-heals the guard).
        (let [def-syms (failed-def-syms source)]
          (when (seq def-syms)
            (if (::ok? result)
              (vswap! failed-defs #(reduce disj % def-syms))
              (vswap! failed-defs into def-syms))))
        ;; Detect-and-tee — only on success. Failed evals roll
        ;; back analyzer defs and never touch the schema registry,
        ;; so diff would be empty anyway; we still skip
        ;; explicitly to keep the contract obvious.
        (let [tee-entities (when (::ok? result)
                             (build-tee-entities
                               {::compile-state  compile-state
                                ::defs-before    defs-before
                                ::schemas-before schemas-before
                                ::source         source
                                ::at             at
                                ::eval-ns        @current-ns}))
              ;; Agent-no-override-core guard (db-is-the-running-
              ;; system PRD; Sean): drop any tee'd :seon.fn row that
              ;; would REDEFINE an existing compiled-core fn (a sym
              ;; whose current source datom's tx is `:core-seed`), so
              ;; the core display row stays intact and the override
              ;; takes no ephemeral live effect. A NEW sym or an
              ;; agent-origin sym is NOT removed — agents define and
              ;; redefine freely in their OWN namespaces. `@db/*conn*`
              ;; is the live db value here (same as the edge tee below).
              tee-entities
              (let [fn-syms (->> tee-entities
                                 (keep #(when (map? %) (:seon.fn/sym %)))
                                 vec)]
                (if (and db/*conn* (seq fn-syms))
                  (reject-core-overrides
                    (vec tee-entities)
                    (core-boot-fn-syms @db/*conn* fn-syms))
                  tee-entities))
              ;; Capture the `:seon.ns/require-edges` for the ENDING ns
              ;; on EVERY successful eval — not only `(ns …)` forms —
              ;; so a re-eval'd ns form or a bare `(require '[x])`
              ;; keeps the ONE dep-edge store current (the M4
              ;; structural store; flat views derive from it via
              ;; [[stored-require-targets]] — C36). Skip the transient
              ;; eval-scaffolding nses (`cljs.user` / `seon.dynamic`)
              ;; so we never mint a `:seon.ns` row for them.
              ;; Diff'd against the live db value ([] when unchanged);
              ;; rides in record-eval!'s atomic tee tx.
              ending-ns (when (::ok? result) @current-ns)
              req-tx    (when (and ending-ns
                                   (symbol? ending-ns)
                                   (not (contains? transient-ns-syms
                                                   ending-ns))
                                   db/*conn*)
                          (ns-require-edges-tx
                            @db/*conn*
                            (keyword (str ending-ns))
                            (analyzer-info/ns-require-edges
                              compile-state ending-ns)))
              ;; Declared read-set diff (C28) for every teed :seon.fn
              ;; row — scalar additions and stale-keyword retractions
              ;; follow the owning fn entity in this same ordered tx.
              ;; Scalar adds avoid Datahike's two-item collection /
              ;; lookup-ref ambiguity; diffing avoids cardinality-many
              ;; accumulation.
              ;; `::kw`/`::alias/kw` literals resolve against the ENDING
              ;; ns's analyzer require-edges (C37) — the same facts the
              ;; M4 structural store tees, read once per entry.
              kw-resolve
              (when (symbol? ending-ns)
                {:seon.repl/current-ns ending-ns
                 :seon.repl/aliases
                 (::aliases (edges->require-info
                              (analyzer-info/ns-require-edges
                                compile-state ending-ns)))})
              read-attr-tx
              (when db/*conn*
                (into []
                      (mapcat (fn [ent]
                                (when-let [s (and (map? ent)
                                                  (:seon.fn/sym ent))]
                                  (fn-read-attrs-tx
                                    @db/*conn* s
                                    (source-qualified-kws
                                      (:seon.fn/source ent)
                                      kw-resolve)))))
                      tee-entities))
              ;; Durable-by-default (owner ruling 2026-07-10): a bare
              ;; top-level `(require …)` — incl. the `alias` rewrite —
              ;; persists its specs into the CURRENT ns's STORED
              ;; declaration so a resume replay carries them. `[]` for
              ;; every non-require form (one read, cheap).
              req-decl-tx (when (and (::ok? result) ending-ns db/*conn*
                                     (not (contains? transient-ns-syms
                                                     ending-ns)))
                            (require-decl-tx @db/*conn*
                                             (keyword (str ending-ns))
                                             source))
              tee (vec (concat tee-entities req-tx req-decl-tx read-attr-tx))
              ;; Durable record — always. Identity allocation and accepted tee
              ;; commit before a process-local result handle can exist.
              recorded (await
                         (record-eval!
                           {:seon.agent.turn/id-of-turn turn-id
                            ::at           at
                            ::duration-ms  duration-ms
                            ::narration    narration
                            ::source       source
                            ::ending-ns    @current-ns
                            ::result       result
                            ::pending?     pending?
                            ::output       output
                            ::tee          tee}))
              eval-id (:seon.eval/id recorded)]
          ;; Bind only the committed id. A failed recorder leaves no orphaned
          ;; result slot; a rejected allocation candidate is never observable.
          (when (and (:seon.db/ok? recorded) (::ok? result))
            (let [live-value (if pending? pending-promise (::value result))]
              (bind-result-var! compile-state eval-id live-value)
              (when pending?
                (-> pending-promise
                    (.then (fn [v]
                             (replace-live-result! eval-id v)))
                    (.catch (fn [_] nil))))))
          ;; Queryable fix datoms (the A/B substrate — fix volume / class
          ;; mix / revert rate = one Datalog query). A SEPARATE top-level
          ;; tx: nested attrs need a boot-schema entry, top-level attrs
          ;; lazy-install (the :seon.eval/record-error precedent).
          (when (and (:seon.db/ok? recorded) fixed? db/*conn*)
            (let [fixes (:seon.repair/fixes pre)
                  r     (await
                          (db/transact!
                            {:seon.db/tx-data
                             [{:seon.eval/id eval-id
                               :seon.repair/applied-class
                               (:seon.repair/applied-class pre)
                               :seon.repair/from
                               (str/join " ; " (map :seon.repair/from fixes))
                               :seon.repair/to
                               (str/join " ; " (map :seon.repair/to fixes))}]}))]
              (when-not (:seon.db/ok? r)
                (js/console.error
                  "[seon.eval/preflight-repair] fix datoms failed for eval"
                  eval-id ":" (-> r :seon.db/error :seon.error/message)))))
          ;; Phase 3 (mvp-completion-plan 2026-05-27) —
          ;; auto-instrument any newly-defined fn whose
          ;; `:malli/schema` parsed cleanly. Runs AFTER the tee
          ;; tx so the `:seon.fn` row is durable before we
          ;; mutate the live var. Best-effort: a thrown
          ;; instrument! aborts only this fn, not the batch.
          (when (and (:seon.db/ok? recorded)
                     (::tee-recorded? recorded)
                     (::ok? result))
            (try
              (instrument-tee-fns!
                (collect-instrument-targets compile-state defs-before
                                            source @current-ns))
              (catch :default e
                ;; OUR instrumentation machinery (an exact Malli delta over a
                ;; newly-tee'd fn whose schema already parsed) throwing is a
                ;; core defect (:core) — record it; best-effort stays: only
                ;; this fn's instrument aborts, the batch continues.
                (error/record! {:seon.error/raw e :seon.error/fault :core})))
            ;; Phase 4 (mvp-completion-plan 2026-05-27) —
            ;; auto-test-run. After the tee tx, newly-defined tests run once.
            ;; Existing tests are never selected by source-substring guessing.
            ;; Wrapped in :origin :test-run so the
            ;; loop guard below short-circuits if a test body
            ;; itself calls `eval-batch!`. Best-effort: thrown
            ;; runner errors don't abort the batch.
            (when-not outer-test-run?
              (let [targets (collect-new-test-targets
                              compile-state defs-before source @current-ns)]
                (when (seq targets)
                  (try
                    (await
                      (db/with-tx-context
                        {:seon.test.runner/running? true}
                        (fn ^:async run-auto-tests! []
                          (await (test-runner/run!
                                   {:seon.test.runner/vars    (vec targets)
                                    :seon.test.runner/record? true
                                    :seon.test.runner/trigger
                                    :seon.test.runner/on-test-definition})))))
                    (catch :default e
                      ;; The test RUNNER escaping (individual test failures
                      ;; are captured as :seon.test data upstream) is a core
                      ;; defect (:core) — record it; best-effort stays: the
                      ;; batch continues.
                      (error/record! {:seon.error/raw e :seon.error/fault :core})))))))
          (if (::ok? result)
            (vswap! n-ok   inc)
            (vswap! n-fail inc))
          recorded)))))

(defn- ^:async record-form-result!
  "Record one REPL-form outcome through the SAME record/result/counter
   path a normal form takes.

   `::error` (a message string) makes a failed row (errors-as-values,
   kind `:seon.eval/repl-form`); otherwise `::value` is bound at the
   committed `result/<id>` after its row records. `::tee` tx ops ride the same
   tx. Mutates the caller's fold counters like eval-form-entry!."
  [{::keys [compile-state current-ns n-ok n-fail narration source
            value error tee]
    turn-id :seon.agent.turn/id-of-turn}]
  (let [result (if error
                 {::ok? false
                  :seon/error {:seon.error/kind    :seon.eval/repl-form
                               :seon.error/message error}}
                 {::ok? true ::value value})
        recorded (await
                   (record-eval!
                     {:seon.agent.turn/id-of-turn turn-id
                      ::at          (js/Date.)
                      ::duration-ms 0
                      ::narration   narration
                      ::source      source
                      ::ending-ns   @current-ns
                      ::result      result
                      ::tee         (vec (or tee []))}))]
    (when (and (:seon.db/ok? recorded) (::ok? result))
      (bind-result-var! compile-state (:seon.eval/id recorded) value))
    (if (::ok? result) (vswap! n-ok inc) (vswap! n-fail inc))
    recorded))

(defn ^:async dispatch-repl-form!
  "Execute ONE REPL movement/update form (owner rulings 2026-07-10).

   - `(in-ns 'foo)` — THE movement function: switches the current-ns
     accumulator, state-preserving (nothing overwritten). A DB-known-
     but-unloaded ns loads first (the one load-fn); a genuinely FRESH
     name is CREATED via the augmented-ns path — core referred + the
     canonical toolkit requires — deliberately richer than the JVM's
     blank-slate `in-ns`. Never a blank slate, never an error for a
     fresh name.
   - `(alias 'a 'the.ns)` — records a require alias: rewritten to
     `(require '[the.ns :as a])` and run through the NORMAL eval path,
     so it loads + persists into the stored declaration like any bare
     require. Error-as-value when the target exists nowhere (Clojure
     parity: alias requires the target to exist).
   - `(ns-unmap 'ns 'sym)` / `(ns-unmap 'sym)` (current ns) — the real
     `cljs.core/ns-unmap` macro removes the analyzer def + live var;
     the `:seon.fn`/`:seon.test` projection row is retracted in the
     same record tx so resume + instrumentation forget it. Error-as-
     value for unknown names and for compiled-core fns (the
     [[reject-core-overrides]] symmetry).
   - `(ns-unalias 'ns 'a)` / `(ns-unalias 'a)` — drops the alias from
     the analyzer entry, the stored declaration ([[unalias-decl-tx]]),
     and the require-edges. The target ns stays loaded/required.

   `form` is the [[repl-form-of]] parse of `::source`. Mutates the
   caller's fold volatiles exactly as eval-form-entry! does."
  {:malli/schema [:=> [:catn [::request :map]] :any]}
  [{::keys [compile-state current-ns form narration] :as m}]
  (let [db (some-> db/*conn* deref)]
    (case (first form)
      in-ns
      (let [target (quoted-sym (second form))]
        (cond
          (nil? target)
          (await (record-form-result!
                   (assoc m ::error (str "in-ns takes a namespace symbol: "
                                         "(in-ns 'my.domain.thing)"))))

          ;; loaded (analyzer entry or live on globalThis) → pure
          ;; movement; nothing re-eval'd, nothing overwritten.
          (or (analyzer-ns-entry? compile-state target)
              (ns-live-on-globalthis? target))
          (do (await (ensure-analyzer-ns! compile-state target))
              (vreset! current-ns target)
              (await (record-form-result! (assoc m ::value target))))

          ;; known to the DB but not loaded (post-restart) → load it
          ;; through the ONE load-fn, then move.
          (and db (ns-rows-in-db? db target))
          (let [r (await (eval compile-state (str "(require '" target ")")
                               {::starting-ns @current-ns
                                ::analyze-deps? true}))]
            (if (::ok? r)
              (do (vreset! current-ns target)
                  (await (record-form-result! (assoc m ::value target))))
              (await (record-form-result!
                       (assoc m ::error
                              (str "in-ns could not load " target
                                   " from the db: "
                                   (or (some-> r :seon/error
                                               :seon.error/message)
                                       "unknown error")))))))

          ;; genuinely fresh → CREATE with the toolkit (the augmented-ns
          ;; path); the normal pipeline tees the :seon.ns row + edges and
          ;; advances the accumulator.
          :else
          (await (eval-form-entry!
                   (assoc m ::source (str "(ns " target ")")
                          ::narration
                          (str (when (seq narration) (str narration "\n"))
                               "; in-ns: " target " was new — created it "
                               "with the standard requires (in-ns preserves "
                               "existing state; a fresh name gets your "
                               "toolkit)"))))))

      alias
      (let [a (quoted-sym (second form))
            t (quoted-sym (nth form 2 nil))]
        (cond
          (or (nil? a) (nil? t))
          (await (record-form-result!
                   (assoc m ::error (str "alias takes two symbols: "
                                         "(alias 'a 'the.target.ns) — the "
                                         "alias, then the namespace"))))

          (not (or (analyzer-ns-entry? compile-state t)
                   (ns-live-on-globalthis? t)
                   (and db (ns-rows-in-db? db t))))
          (await (record-form-result!
                   (assoc m ::error
                          (str "No namespace " t " is loaded — nothing to "
                               "alias. (require '[" t " :as " a "]) loads "
                               "and aliases in one step."))))

          :else
          (await (eval-form-entry!
                   (assoc m ::source (str "(require '[" t " :as " a "])")
                          ::narration
                          (str (when (seq narration) (str narration "\n"))
                               "; alias — recorded as a require alias "
                               "(persisted in your ns declaration)"))))))

      ns-unmap
      (let [[ns-arg sym-arg] (if (>= (count form) 3)
                               [(quoted-sym (second form))
                                (quoted-sym (nth form 2))]
                               [@current-ns (quoted-sym (second form))])
            sym-str (when (and ns-arg sym-arg) (str ns-arg "/" sym-arg))
            adef?   (some? (get-in @compile-state
                                   [:cljs.analyzer/namespaces ns-arg
                                    :defs sym-arg]))
            fn-row? (when (and db sym-str)
                      (some? (ffirst (db/query '[:find ?e :in $ ?s
                                                 :where [?e :seon.fn/sym ?s]]
                                               db sym-str))))
            test-row? (when (and db sym-str)
                        (some? (ffirst (db/query '[:find ?e :in $ ?s
                                                   :where [?e :seon.test/sym ?s]]
                                                 db sym-str))))]
        (cond
          (or (nil? ns-arg) (nil? sym-arg))
          (await (record-form-result!
                   (assoc m ::error (str "ns-unmap takes symbols: "
                                         "(ns-unmap 'the.ns 'name), or "
                                         "(ns-unmap 'name) for the current "
                                         "ns"))))

          (not (or adef? fn-row? test-row?))
          (await (record-form-result!
                   (assoc m ::error (str "`" sym-str "` is not defined — "
                                         "nothing to remove."))))

          (and db (seq (core-boot-fn-syms db [sym-str])))
          (await (record-form-result!
                   (assoc m ::error
                          (str "`" sym-str "` is a compiled core fn — agents "
                               "cannot remove core. Define and remove in "
                               "your OWN namespaces."))))

          :else
          (let [r (await (eval compile-state
                               (str "(ns-unmap '" ns-arg " '" sym-arg ")")
                               {::starting-ns @current-ns}))]
            (if-not (::ok? r)
              (await (record-form-result!
                       (assoc m ::error
                              (or (some-> r :seon/error :seon.error/message)
                                  (str "ns-unmap failed for " sym-str)))))
              (await (record-form-result!
                       (assoc m ::value true
                              ::tee (cond-> []
                                      fn-row?
                                      (conj [:db/retractEntity
                                             [:seon.fn/sym sym-str]])
                                      test-row?
                                      (conj [:db/retractEntity
                                             [:seon.test/sym sym-str]])))))))))

      ns-unalias
      (let [[ns-arg a] (if (>= (count form) 3)
                         [(quoted-sym (second form))
                          (quoted-sym (nth form 2))]
                         [@current-ns (quoted-sym (second form))])
            entry      (get-in @compile-state
                               [:cljs.analyzer/namespaces ns-arg])
            req-alias? (let [t (get (:requires entry) a)]
                         (and (some? t) (not= t a)))
            as-alias?  (contains? (:as-aliases entry) a)]
        (cond
          (or (nil? ns-arg) (nil? a))
          (await (record-form-result!
                   (assoc m ::error (str "ns-unalias takes symbols: "
                                         "(ns-unalias 'the.ns 'a), or "
                                         "(ns-unalias 'a) for the current "
                                         "ns"))))

          (not (or req-alias? as-alias?))
          (await (record-form-result!
                   (assoc m ::error (str "`" a "` is not an alias in " ns-arg
                                         " — nothing to remove."))))

          :else
          (do
            (swap! compile-state update-in
                   [:cljs.analyzer/namespaces ns-arg]
                   (fn [e] (-> e
                               (update :requires dissoc a)
                               (update :as-aliases dissoc a))))
            (let [ns-kw (keyword (str ns-arg))
                  tee   (when (and db (not (contains? transient-ns-syms
                                                      ns-arg)))
                          (vec (concat
                                 (unalias-decl-tx db ns-kw a)
                                 (ns-require-edges-tx
                                   db ns-kw
                                   (analyzer-info/ns-require-edges
                                     compile-state ns-arg)))))]
              (await (record-form-result!
                       (assoc m ::value nil ::tee tee))))))))))

(defn- ^:async dispatch-eval-entry!
  "Dispatch ONE non-`:read` parsed entry through the per-form mechanism:
   comment-only → REPL-parity intercept (`*ns*`) → REPL form
   ([[dispatch-repl-form!]]: `in-ns`/`alias`/`ns-unmap`/`ns-unalias`) →
   normal `eval-form-entry!`. The SINGLE per-entry mechanism shared by
   `eval-batch!`'s main loop AND its parinfer-repair sub-loop, so a
   REPAIRED form is handled IDENTICALLY to a normal one — same parity
   teaching, same comment recording, same result / tee / result-var
   binding. Before this, the repair sub-loop called `eval-form-entry!`
   directly, so a repaired `(in-ns 'foo` (a plausible missing-paren the
   repair fixes) bypassed the form handling the main loop gives, and a
   repaired trailing `:comment` (no `:source`) reached eval with nil.

   `:read` entries never reach here — the main loop OWNS the repair
   sub-loop (it is the trigger), and a repaired span re-parses to
   non-`:read` entries (the repair accept-gate requires it). Mutates the
   caller's fold volatiles in place exactly as `eval-form-entry!` does.

   Map keys: the `eval-form-entry!` set, plus `::entry` (the parsed entry
   — supplies `:seon.repl/kind`/`:seon.repl/source`) and `::narration`
   (the final narration, repair note already prepended by the caller for
   a repaired form)."
  [{::keys [compile-state current-ns n-ok n-fail
            failed-defs outer-test-run? entry narration]
    turn-id :seon.agent.turn/id-of-turn}]
  ;; A `#code` heredoc form carries `:seon.repl/eval-source` — the
  ;; machine-escaped, cljs-READABLE rewrite of the byte-faithful (but not
  ;; cljs-readable) `:seon.repl/source`. Eval MUST run the readable form;
  ;; absent (the common case) it equals the source.
  (let [source (or (:seon.repl/eval-source entry) (:seon.repl/source entry))]
    (cond
      ;; Comment-only entry — no source to eval. Record a comment-only row
      ;; (blank source, ok? true) so trailing `;;` thinking renders in the
      ;; transcript and is never lost. Not counted in n-ok/n-fail.
      (= :comment (:seon.repl/kind entry))
      (await (record-eval! {:seon.agent.turn/id-of-turn turn-id
                            ::at          (js/Date.)
                            ::duration-ms 0
                            ::narration   narration
                            ::source      ""
                            ::ending-ns          @current-ns
                            ::result      {::ok? true ::value nil}}))

      ;; REPL-parity intercept (fix d) — bare `*ns*` gets the honest
      ;; VALUE instead of a silent nil. No eval runs.
      (some? (parity-intercept source @current-ns))
      (let [pc     (parity-intercept source @current-ns)
            result (if (= :error (:seon.eval/parity pc))
                     {::ok? false
                      :seon/error {:seon.error/kind    :seon.eval/repl-parity
                                   :seon.error/message (:seon.error/message pc)}}
                     {::ok? true ::value (:seon.eval/value pc)})
            recorded (await
                       (record-eval!
                         {:seon.agent.turn/id-of-turn turn-id
                          ::at          (js/Date.)
                          ::duration-ms 0
                          ::narration   narration
                          ::source      source
                          ::ending-ns   @current-ns
                          ::result      result}))]
        (when (and (:seon.db/ok? recorded) (::ok? result))
          (bind-result-var! compile-state (:seon.eval/id recorded)
                            (::value result)))
        (if (::ok? result)
          (vswap! n-ok   inc)
          (vswap! n-fail inc))
        recorded)

      ;; REPL movement/update forms (owner rulings 2026-07-10) —
      ;; in-ns / alias / ns-unmap / ns-unalias are REAL forms handled at
      ;; the eval boundary; see [[dispatch-repl-form!]].
      (some? (repl-form-of source))
      (await (dispatch-repl-form!
               {::compile-state   compile-state
                :seon.agent.turn/id-of-turn turn-id
                ::current-ns      current-ns
                ::n-ok            n-ok
                ::n-fail          n-fail
                ::failed-defs     failed-defs
                ::outer-test-run? outer-test-run?
                ::narration       narration
                ::source          source
                ::form            (repl-form-of source)}))

      ;; Prose-in-parens demotion (#88) — a `(…)` that is English prose, not
      ;; code (undefined bare head + ≥2 undefined bare words, no qualified or
      ;; var-resolving symbol anywhere). Eval'ing it throws "not defined" and
      ;; inflates the eval-error rate; DEMOTE to a prose row instead: record
      ;; ok? with the text preserved + a note, never eval, count as NEITHER
      ;; n-ok nor n-fail (exactly like a `;` comment — it ran nothing).
      (prose-paren? compile-state @current-ns source)
      (await (record-eval!
               {:seon.agent.turn/id-of-turn turn-id
                ::at          (js/Date.)
                ::duration-ms 0
                ::narration   (str (when (seq narration) (str narration "\n"))
                                  "; read as PROSE, not code — every word is an "
                                  "undefined bare symbol, so this was NOT "
                                  "evaluated (it would only have thrown 'not "
                                  "defined'). For real code, make the head or an "
                                  "argument resolve; keep prose in `;` comments.")
                ::source      source
                ::ending-ns          @current-ns
                ::result      {::ok? true ::value nil}}))

      ;; Normal eval path.
      :else
      (await (eval-form-entry!
               {::compile-state   compile-state
                :seon.agent.turn/id-of-turn turn-id
                ::current-ns      current-ns
                ::n-ok            n-ok
                ::n-fail          n-fail
                ::failed-defs     failed-defs
                ::outer-test-run? outer-test-run?
                ::narration       narration
                ::source          source})))))

(defn ^:async eval-batch!
  "Execute a sequence of parsed entries as a REPL batch.

   Partial-failure: every entry gets its own try + record + result binding; entry
   N+1 always runs even if N failed.

   Per entry, three kinds (`:form` / `:read` / `:comment`, below):

   The per-form loop is a fold over `parsed`, carrying `current-ns`
   as the accumulator. Each successful eval that switches ns (via
   `(ns …)`) updates the accumulator to the eval's `:ns`. Failed
   forms (parse OR eval) leave the accumulator unchanged — the
   last-known-good ns naturally propagates to the next form. The
   final `:seon.eval/ns` written for each form is the accumulator's
   value at write time (post-update on success; unchanged on failure).
   See docs/seon/concepts/reactive-context.

   `:seon.repl/kind :form` (the normal path):
     1. Eval in the accumulator's current-ns.
     2. Auto-await Promise return values.
     3. Compute duration-ms = (now - start).
     4. On success: advance accumulator to (::ending-ns raw-result); bind
        the live value at the capped `result/<id>` process slot.
     5. Transact a :seon.eval entity carrying :seon.eval/ns = the
        post-update accumulator value.

   `:seon.repl/kind :read` (a parse-forms failure, see seon.repl.internal):
     1. Skip the eval (no source to evaluate).
     2. Record as a failed :seon.eval with :seon.eval/ns = the
        unchanged accumulator value (the ns the form WOULD have
        run in). Agent sees its own broken text in the next turn's
        ctx and self-corrects.
     3. duration-ms = 0 (no eval happened).

   `:seon.repl/kind :comment` (trailing comment-preamble with no following form):
     1. No source to eval — record a comment-only :seon.eval row
        (blank source, ok? true) carrying just `:seon.eval/narration`,
        so the agent's trailing `;;` thinking renders in the transcript
        and is never lost. Counts as neither n-ok nor n-fail (no eval
        happened); duration-ms = 0.

   Per-form work is wrapped in the private record boundary plus
   `(db/with-tx-context {…} f)`. Transactions keep only agent-user/REPL
   provenance; eval identity is allocated only when the frozen outcome records.
   Turn/eval/test control remains runtime context and is never copied to
   transaction metadata.

   Args:
     compile-state — the bootstrap compile-state (defonce'd at boot)
     parsed        — vector from `seon.repl.internal/parse-forms`
                     (mix of `:seon.repl/kind` :form and :read entries)
     agent-ns-sym  — agent's home ns (e.g. 'my.agent.seon)
     agent-id      — the owning agent's id
     turn-id       — the owning :seon.agent.turn/id string (eval lands as a
                     component child of this turn via :seon.agent.turn/evals)
     run-id        — the OPEN run this turn belongs to (§8b WORK FENCE), or
                     nil. When present, the batch LEADS with one in-tx CAS
                     ([[seon.db/cas-assert]]) asserting the agent still owns
                     run-id; a superseded/watchdog-closed run (the pointer
                     moved or was retracted DURING the LLM call, before the
                     batch) is rejected — the whole batch is SKIPPED, no
                     zombie eval rows land. The fence is at batch START (not
                     per-form): a run's own lifecycle function (wait/complete/
                     terminate) retracts the pointer MID-batch, and that
                     function's eval must still record — the start-fence has
                     already passed by then, so self-close records honestly.
                     Mid-batch supersession is caught by the loop's next-turn
                     re-read (§8c); full per-write atomic isolation is the
                     Phase-2 worker-buffer keystone.

   Returns `{:seon.eval/ids    [<id> ...]   ; ordered, one per entry
             :seon.eval/n-ok   <int>        ; successful evals
             :seon.eval/n-fail <int>         ; failed (eval-throw + read)
             :seon.eval/fenced? true}`      ; ONLY when the start-fence lost
                                            ; (batch skipped — run superseded)

   The caller reads ::n-ok for 'progress made this turn' and ::n-fail to
   surface to the agent's warnings surface."
  {:malli/schema
   [:=> [:catn [::compile-state :any]
               [::parsed :any]
               [::agent-ns-sym :any]
               [::agent-id :string]
               [::turn-id :string]
               [::run-id :any]]
        :map]}
  [compile-state parsed agent-ns-sym agent-id turn-id run-id]
  (let [;; §8b WORK FENCE — assert, in ONE atomic tx at the writer, that the
        ;; agent STILL owns run-id BEFORE any work. A supersede/watchdog-close
        ;; that landed DURING the LLM call moved/retracted the pointer, so this
        ;; CAS aborts → fence-lost? true → the doseq is skipped (no zombie eval
        ;; rows) and the return carries :seon.eval/fenced?. Skipped (nil) when
        ;; run-id is absent (a runless eval path) or there's no conn.
        fence-lost?
        (when (and run-id db/*conn*)
          (false? (:seon.db/ok?
                    (await (db/transact!
                             {:seon.db/tx-data
                              [(db/cas-assert [:seon.agent/id agent-id]
                                              :seon.agent/run
                                              [:seon.agent.run/id run-id])]})))))
        ;; Fold-step local accumulators. Volatile! is a transient
        ;; mutation impl detail inside this one fn; not shared state.
        ;; current-ns is the per-form fold value: starts at agent
        ;; home-ns, advances on each successful `(ns …)` eval, stays
        ;; unchanged on any failure (last-known-good propagates).
        eids       (volatile! [])
        n-ok       (volatile! 0)
        n-fail     (volatile! 0)
        current-ns (volatile! agent-ns-sym)
        ;; A.4 false-confidence guard: symbols whose `(def …)`/`(defn …)`
        ;; eval FAILED this batch. A later non-defining reference to one
        ;; of them escalates to an honest error instead of reading nil
        ;; with `ok? true` (the episode's false-confidence trap).
        failed-defs (volatile! #{})
        ;; Capture the runtime test-running flag before the per-entry
        ;; provenance scope. If an outer auto-test-run wrapper around a body
        ;; that itself calls `eval-batch!` already established the flag, the
        ;; inner batch must skip auto-test-run to avoid recursion.
        outer-test-run? (true? (:seon.test.runner/running?
                                 (db/current-tx-context)))
        run-entry!
        (fn ^:async run-entry! [body-fn]
          (await
            (db/with-tx-context
              {::db/user [:seon.agent/id agent-id]
               ::db/process (db.process/lookup-ref ::db.process/repl)}
              (fn ^:async run-in-record-boundary! []
                (await (run-with-record-boundary body-fn))))))
        append-record!
        (fn [recorded]
          (when (:seon.db/ok? recorded)
            (vswap! eids conj (:seon.eval/id recorded)))
          recorded)]
    ;; Fence lost ⇒ iterate over nil (zero entries) — the batch is skipped, the
    ;; volatiles stay at their empty seed, and the return below flags :fenced?.
    (doseq [entry (when-not fence-lost? parsed)]
      (await
        (run-entry!
          (fn ^:async run-one-entry! []
            (cond
                ;; Read-failure entry from seon.repl.internal. A.2: attempt
                ;; a PER-FORM parinfer indent-mode repair on the bad span
                ;; (never the whole reply — that would mangle good forms
                ;; around it). If the repaired span re-reads cleanly, eval
                ;; the repaired form(s) through the normal path and record
                ;; them ok? with a transparency note carrying the diff +
                ;; structural-shape, so the agent always sees what changed.
                ;; If repair fails, fall through to the sharpened :read
                ;; error (A.3) — the form defined NOTHING.
                (and (= :read (:seon.repl/kind entry))
                     (false? (:seon.repl/ok? entry)))
                (let [reads? (fn [s]
                               (let [es (internal/parse-forms s)]
                                 (and (seq es)
                                      (every? #(not= :read (:seon.repl/kind %))
                                              es))))
                      ;; The delimiters class is level-gated too (`:off`
                      ;; = no repair anywhere — the pure A/B control arm;
                      ;; `:safe-syntax`+ = today's shipped behavior).
                      ;; CONSTRAINT: parinfer delimiter-repair must NEVER see
                      ;; `#code` heredoc payload lines — it would try to balance
                      ;; the raw payload's delimiters and corrupt it. A bad span
                      ;; holding a heredoc opener (an UNTERMINATED heredoc, whose
                      ;; `:read` names the awaited sentinel) is REFUSED repair
                      ;; here, so that error surfaces intact instead.
                      rep    (if (and (repair-class-on? :seon.repair/delimiters)
                                      (not (internal/contains-heredoc-opener?
                                             (:seon.repl/source entry))))
                               (repair/repair-source
                                 {:seon.repair/source (:seon.repl/source entry)
                                  :seon.repair/reads? reads?})
                               {:seon.repair/repaired? false
                                :seon.repair/source    (:seon.repl/source entry)
                                :seon.repair/changes   []})]
                  (if (:seon.repair/repaired? rep)
                    ;; Repaired → re-parse the repaired span and run each
                    ;; resulting entry through `dispatch-eval-entry!` — the
                    ;; SAME per-entry mechanism the main loop's `:else` uses,
                    ;; so a repaired form gets identical comment/parity/normal
                    ;; handling. The repair note rides on the FIRST entry so
                    ;; the diff is visible.
                    (let [repaired-entries (internal/parse-forms
                                             (:seon.repair/source rep))]
                      (loop [es repaired-entries first? true]
                        (when (seq es)
                          (let [e    (first es)
                                shape (form-shape (:seon.repl/form e))
                                note (when first?
                                       (repair/repair-note
                                         {:seon.repair/changes
                                          (:seon.repair/changes rep)
                                          :seon.repair/shape shape}))
                                narr (if (and first? note)
                                       (str (when (seq (:seon.repl/narration entry))
                                              (str (:seon.repl/narration entry) "\n"))
                                            note)
                                       (:seon.repl/narration e))
                                recorded
                                (await
                                  (dispatch-eval-entry!
                                    {::compile-state   compile-state
                                     :seon.agent.turn/id-of-turn turn-id
                                     ::current-ns      current-ns
                                     ::n-ok            n-ok
                                     ::n-fail          n-fail
                                     ::failed-defs     failed-defs
                                     ::outer-test-run? outer-test-run?
                                     ::entry           e
                                     ::narration       narr}))]
                            (append-record! recorded)
                            (recur (rest es) false)))))
                    ;; Not repairable → sharpened read error (A.3).
                    (let [recorded
                          (await
                            (record-eval!
                              {:seon.agent.turn/id-of-turn turn-id
                               ::at          (js/Date.)
                               ::duration-ms 0
                               ::narration   (:seon.repl/narration entry)
                               ::source      (:seon.repl/source entry)
                               ::ending-ns   @current-ns
                               ::result
                               {::ok? false
                                :seon/error
                                {:seon.error/kind :read
                                 :seon.error/message
                                 (read-error-message
                                   (-> entry :seon/error :seon.error/message)
                                   (:seon.repl/source entry))}}}))]
                      (append-record! recorded)
                      (vswap! n-fail inc))))

                ;; Every non-`:read` entry — comment / parity-intercept /
                ;; normal — flows through the ONE per-entry mechanism, the
                ;; exact same `dispatch-eval-entry!` the repair sub-loop
                ;; above calls. One mechanism, no parallel path.
                :else
                (append-record!
                  (await
                    (dispatch-eval-entry!
                      {::compile-state   compile-state
                       :seon.agent.turn/id-of-turn turn-id
                       ::current-ns      current-ns
                       ::n-ok            n-ok
                       ::n-fail          n-fail
                       ::failed-defs     failed-defs
                       ::outer-test-run? outer-test-run?
                       ::entry           entry
                       ::narration       (:seon.repl/narration entry)}))))))))
    (cond-> {:seon.eval/ids    @eids
             :seon.eval/n-ok   @n-ok
             :seon.eval/n-fail @n-fail}
      fence-lost? (assoc :seon.eval/fenced? true))))
