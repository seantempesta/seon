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
   - **`(in-ns 'foo)` is not bootstrapped.** Use `(ns foo)` to switch."
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
            [malli.instrument :as mi]
            [shadow.cljs.bootstrap.node :as boot]
            [seon.ai.tokens :as tokens]
            [seon.analyzer-info :as analyzer-info]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval.bootstrap-cache :as bootstrap-cache]
            [seon.error.instrument :as einstrument]
            [seon.instrument :as instrument]
            [seon.platform :as platform]
            [seon.render.value :as value]
            [seon.repair :as repair]
            [seon.repl.internal :as internal]
            [seon.schema :as schema]
            [seon.test.runner :as test-runner]))


;; ============================================================
;; Per-form wall-clock timeout. Stability guard, not a security
;; boundary — the agent can mutate `!timeout-ms` from inside eval.
;;
;; CAVEAT: single-threaded Node only preempts **async** hangs (a form
;; awaiting a Promise that never resolves — fetch, db call, etc.). A
;; tight CPU loop blocks the event loop entirely, including the
;; timer, and can NOT be cancelled here. Real preemption needs
;; worker_thread (Phase 2) or wasmtime (Phase 3). What this DOES buy:
;; a hung fetch / never-resolving Promise no longer wedges the agent
;; loop indefinitely.
;; ============================================================

;; Per-form wall-clock timeout in milliseconds. Default 10000.
;; Replace via [[set-timeout-ms!]] (persistent) or [[budget]] (one-shot
;; override that agents call from inside a form).
(defonce !timeout-ms (atom 10000))

(defn set-timeout-ms!
  "Replace the per-form wall-clock timeout. Returns the new value."
  {:malli/schema [:=> [:catn [::ms :int]] :int]}
  [ms]
  (reset! !timeout-ms ms))

;; Side-channel: when set, applies to exactly the next auto-await
;; in `maybe-await-value` (then resets to nil so it doesn't leak to
;; subsequent forms). Agents set this via [[budget]].
(defonce ^:private !next-budget-ms (atom nil))

(defn budget
  "Override the auto-await wall-clock timeout for a slow form.

   Applies to the form's auto-awaited return value. Use when a form
   does a slow async op that legitimately
   needs more than `@!timeout-ms` (default 10000ms).

     ;; default 10s budget
     (some-fs-walk \"/Users/me/dir\")

     ;; give this form 60 seconds
     (seon.eval/budget 60000 (some-fs-walk \"/Users/me/dir\"))

   `inner` is returned unchanged; `ms` is recorded as a one-shot hint
   that `eval-batch!`'s auto-await reads after the form returns,
   consumes once, and resets. Pattern:

   ;; turn 1 — budget applies only to THIS form
   (seon.eval/budget 60000 (slow-async-op))

   ;; turn 2 — uses the default 10s again
   (regular-op)"
  ;; `inner` + the return are `:any` on purpose: `inner` is the agent form's
  ;; already-evaluated value (a Promise / any runtime value), returned
  ;; unchanged — a runtime-value boundary. Only `ms` carries a data contract.
  {:malli/schema [:=> [:catn [::ms :int] [::inner :any]] :any]}
  [ms inner]
  (reset! !next-budget-ms ms)
  inner)

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

   NOTE: the stash is process-scoped (globalThis) — a pod restart drops
   it. Anything that must survive a restart should persist its RESULT to
   the DB, not rely on this in-memory handle."
  ;; `v` + the return are `:any` on purpose: `v` is the agent form's
  ;; already-evaluated value (a Promise / any runtime value) — a
  ;; runtime-value boundary, same as `budget`'s `inner`.
  {:malli/schema [:=> [:catn [::value :any]] :any]}
  [v]
  (if (instance? js/Promise v)
    (->Deferred v)
    v))

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
;; form yields (any `^:async`/awaiting verb), ANOTHER fiber (a concurrent
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
;;     logs/pod.log) — boot/inspector prints land in the log exactly as
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
        ;; own world root finds the bootstrap output in the seon
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
   (`h/format-count`) must resolve under SCI or the tile falls to the unbounded
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

   Why this exists: `seon.render.sci`'s `expose-ns` exposes a tile's own-ns
   (and required-ns) FN members so SCI can resolve them, but a tile that
   references an own-ns NON-fn `(def …)` data value found no entry under SCI
   → 'Unable to resolve symbol' → the tile fell to the UNBOUNDED compiled path.
   Merging these into the SCI namespace map alongside the fns resolves the
   constant so the tile stays interrupt-bounded.

   `nil`-valued props are dropped (a SCI namespace map shouldn't carry a nil
   binding; `nil` reads identically whether bound or absent, and absence is the
   convention here). Compiler-internal own props (none are non-fn data on a
   normal CLJS ns object) would be demunged like any other key — harmless: a
   spurious binding is never referenced by a tile body, and the whole path is
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
  (let [specs (map (fn [{:seon.ns.require/keys [target alias refers refer-all?]}]
                     (cond-> [(symbol (name target))]
                       (symbol? alias) (conj :as alias)
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
;; bounded. Resets on process restart — like the globalThis stash, the
;; vars do not survive a new process. A reference to a pruned /
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

;; Insertion-ordered vector of bound result ids (oldest first). Process-
;; shared, defonce'd so it survives hot-reload of `seon.eval`.
(defonce ^:private !result-var-ids (atom []))

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

;; Defined after `home-ns-require-specs` (the single source of home-ns
;; aliases it derives from); declared here so `raw-eval`'s not-defined
;; branch can append the "did you mean `plan/plan!`?" hint.
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
                        ;; Alias hint: a bare verb that failed to resolve may
                        ;; be a library verb the agent should reach through a
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
                                           "home-ns verb; use that form, do "
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
     ::timeout-ms    override the default `@!timeout-ms` per-call.

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
           ms      (or timeout-ms @!timeout-ms)
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
       ;; (the async wrapper arms already record verb rejections). The
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
;; Results store. Lives on globalThis so any value (including
;; non-readable CLJS objects like datahike DB tagged literals) can
;; be stashed and looked up. We don't go through pr-str/read-string
;; here — the value is the raw object.
;;
;; Key shape: "__seon_results_<eval-id>"
;; This stash is the runtime VALUE backing each `result/<id>` var (the
;; agent's value-reuse surface) and the internal `lookup-result` reader
;; (e.g. seon.agent.message's batch-failure check). It is NOT itself an
;; agent-facing call.
;; ============================================================

(def ^:private results-key-prefix "__seon_results_")

(defn- result-key [eval-id]
  (str results-key-prefix eval-id))

(defn stash-result-raw!
  "Stash a raw value (any type) on globalThis keyed by the eval-id.
   No pr-str round-trip — value-type-agnostic. Soft-fails on impossible
   sets (logs + ignores)."
  {:malli/schema [:=> [:catn [::eval-id :any] [::value :any]] :any]}
  [eval-id value]
  (try
    (js/Reflect.set js/globalThis (result-key eval-id) value)
    (catch :default e
      ;; OUR result-stash machinery (a globalThis set) failing is a core
      ;; defect — record it (:core); the value just won't be
      ;; re-referenceable and lookup-result reports the honest miss. Same
      ;; `:any` return as the prior console-only swallow.
      (error/record! {:seon.error/raw e :seon.error/fault :core}))))

(defn lookup-result
  "The live value of a prior eval, keyed by its `result/<id>`.

   The id on its value line
   in the transcript (string or keyword). Backed by the globalThis
   stash, so any value type round-trips. INTERNAL reader — the agent's
   value-reuse surface is the `result/<id>` var (same stash backing);
   `lookup-result` is used by core code that needs an eval's live value
   programmatically (e.g. seon.agent.message's batch-failure check).

   ERRORS ARE VALUES: a miss never throws — it returns an error map
   that says exactly why there is no value:

   - the eval ran in a PRIOR SESSION (the row is in the db but the
     process that held its value is gone) → \"prior session\" — the
     resume boundary in the transcript marks where that history ends;
   - the eval ERRORED (it never produced a value);
   - no such eval id exists (typo)."
  {:malli/schema [:=> [:catn [::id :any]] :any]}
  [id]
  (let [id-str (if (keyword? id) (name id) (str id))
        k      (result-key id-str)]
    (if (js/Reflect.has js/globalThis k)
      (js/Reflect.get js/globalThis k)
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
           (str "eval " id-str " is from a prior session — its live value "
                "did not survive the process restart (the transcript's "
                "resume marker shows the boundary). Re-run the form (its "
                "source is on the eval's prompt line) to recompute it.")})))))

;; ============================================================
;; `result/<id>` vars — binding side. Constants + the reference
;; predicate + the graceful-miss message live ABOVE `raw-eval` (it
;; needs them); the actual binding fns live here, near the eval
;; pipeline that calls them.
;; ============================================================

(defn- result-globalthis-obj
  "The live `globalThis.result` object, creating it on first use."
  []
  (or (js/Reflect.get js/globalThis (str result-ns-sym))
      (let [o #js {}]
        (js/Reflect.set js/globalThis (str result-ns-sym) o)
        o)))

(defn- unbind-result-var!
  "Remove a single `result/<id>` var — undef from globalThis AND the
   analyzer `result` ns defs. Best-effort; never throws."
  [compile-state id]
  (let [munged (cljs.core/munge id)]
    (try
      (let [robj (js/Reflect.get js/globalThis (str result-ns-sym))]
        (when robj (js/Reflect.deleteProperty robj munged)))
      ;; OUR result-var unbind (globalThis delete) — a throw here is a
      ;; core defect (these Reflect ops don't throw on ordinary props);
      ;; record it, cleanup stays best-effort (a stale var is benign).
      (catch :default e
        (error/record! {:seon.error/raw e :seon.error/fault :core})))
    (try
      (swap! compile-state update-in
             [:cljs.analyzer/namespaces result-ns-sym :defs]
             dissoc (symbol id))
      ;; OUR analyzer-defs unbind — a throw is a core defect (swap!/dissoc
      ;; don't throw); record it, cleanup stays best-effort.
      (catch :default e
        (error/record! {:seon.error/raw e :seon.error/fault :core})))))

(defn bind-result-var!
  "Bind a successful eval's value `v` as the var `result/<id>`.

   Sets `globalThis.result.<munged-id>` and registers `<id>` in the
   `result` ns's analyzer defs in `compile-state`, so a later bare
   `result/<id>` resolves with no undeclared-var warning. Track the id
   for the session cap and prune the oldest beyond `result-vars-cap`.

   Failed evals never call this — there is no value to bind. Soft-fails
   (logs + ignores) so a bind hiccup never breaks the eval pipeline."
  {:malli/schema [:=> [:catn [::compile-state :any] [::id :string] [::value :any]]
                  :nil]}
  [compile-state id v]
  (try
    (let [munged (cljs.core/munge id)]
      ;; 1. Runtime value at globalThis.result.<munged-id>.
      (js/Reflect.set (result-globalthis-obj) munged v)
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
                              :seon.eval/result-var? true}))))
      ;; 3. Track + prune. Re-binding an existing id moves it to the
      ;;    front (most-recent); the cap drops the oldest excess.
      (let [pruned (volatile! [])]
        (swap! !result-var-ids
               (fn [ids]
                 (let [ids* (conj (vec (remove #(= % id) ids)) id)
                       over (max 0 (- (count ids*) result-vars-cap))]
                   (vreset! pruned (subvec ids* 0 over))
                   (subvec ids* over))))
        (doseq [old @pruned]
          (unbind-result-var! compile-state old))))
    (catch :default e
      ;; OUR result-var bind (globalThis set + analyzer defs) failing is a
      ;; core defect — record it (:core); the eval pipeline still returns
      ;; nil (the value is unreachable via result/<id>, a benign miss).
      (error/record! {:seon.error/raw e :seon.error/fault :core})))
  nil)

(def home-ns-require-specs
  "THE canonical require list every agent's home namespace is wired with —
   the single source of truth, shared by [[setup-agent-ns!]] (which INSTALLS
   it) and `seon.agent.ctx.namespaces/cur-ns-workspace-stub` (which RENDERS it
   VERBATIM into the agent's workspace block). No parallel reconstruction, no
   hidden aliasing: the agent SEES the exact aliases/refers its reflexive
   `(message/user …)` / `(wait …)` / `(schema/register! …)` / `(db/transact! …)`
   forms resolve against.

   Each entry is a `(require …)`-style spec — `[ns :as alias]` or
   `[ns :refer [verbs…]]` — `pr-str`'d straight into the `(ns … (:require …))`
   head by [[home-ns-form]]."
  '[[seon.agent.message :as message]
    [seon.agent :as agent]
    [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
    [seon.schema :as schema]
    [seon.db :as db]
    [my.plan :as plan]])

;; ============================================================
;; Config-driven agent-init CP-1 — home-ns wiring + toolkit (agent-level
;; attrs on the agent entity). Nothing reads these yet (purely additive).
;; ============================================================

;; SHARED shape, register-once (decision 5): one `(require …)`-style spec —
;; `[ns :as alias]` or `[ns :refer [verbs…]]`.
(schema/register! ::require-spec
  [:cat :symbol [:enum :as :refer] [:or :symbol [:vector :symbol]]])

;; home-requires: set-once, read WHOLE by setup-agent-ns!, never queried
;; per-element → the ONE decision-22(c) serialized-blob case. The spec's
;; `[:vector ::require-spec]` does NOT bridge — the seon.db bridge maps a
;; `:vector`'s CHILD to a datahike column, and `::require-spec` (a `:cat`)
;; has no column type, so it hard-rejects (`:seon.db/unbridgeable-attrs`).
;; The EDN-blob path the spec names ("stored as a pr-str'd EDN string") is
;; the bridge's MIXED-`:or` branch (db/internal `form->datahike-value-type`
;; `:or`) — the SAME mechanism `:seon.render.live-tile/content` /
;; `:seon.render/ai` use. So this is a mixed `:or` (the require-spec vector
;; arm + a scalar `:symbol` arm) → `:db.type/string` EDN, decoded on read
;; via `seon.db/decode-edn-value`. Default = the live [[home-ns-require-specs]]
;; list (verbatim).
(schema/register! ::home-requires
  [:or {:default '[[seon.agent.message :as message]
                   [seon.agent :as agent]
                   [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                   [seon.schema :as schema]
                   [seon.db :as db]
                   [my.plan :as plan]]}
   [:vector ::require-spec]
   :symbol])

(defn- home-ns-alias-names
  "Comma-joined `:as` alias names from [[home-ns-require-specs]]
   (e.g. \"message, agent, schema, db, plan\") — the short prefixes an
   agent calls library verbs through from its home ns."
  []
  (str/join ", " (keep (fn [spec] (when (= :as (second spec)) (name (nth spec 2))))
                       home-ns-require-specs)))

(defn- home-ns-refer-names
  "Comma-joined `:refer`'d verb names from [[home-ns-require-specs]]
   (e.g. \"wait, complete, pause, resume, terminate\") — the bare verbs that
   resolve ONLY while the agent sits in its home ns."
  []
  (str/join ", " (mapcat (fn [spec] (when (= :refer (second spec))
                                       (map name (nth spec 2))))
                         home-ns-require-specs)))

(defn- home-ns-alias-for-ns
  "The `:as` alias a home ns gives `ns-name` (a dotted string), or nil if
   `ns-name` isn't aliased there. Derived from [[home-ns-require-specs]]."
  [ns-name]
  (some (fn [spec]
          (when (and (= :as (second spec)) (= (name (first spec)) ns-name))
            (name (nth spec 2))))
        home-ns-require-specs))

(defn home-ns-alias-hint
  "The correctly-aliased home-ns form for a bare verb that failed.

   Given a bare verb NAME that failed to resolve (e.g. \"plan!\",
   \"user\", \"complete\") — the form the agent SHOULD have
   written — a string like \"plan/plan!\" — or nil if no home-ns alias/refer
   exposes that name. Derived from [[home-ns-require-specs]] (the single
   source of which aliases/refers every agent's home ns carries) so it can
   never drift from what the agent's prompt teaches:

     - `[ns :as alias]` — if `ns`'s live publics ([[ns-fn-members]]) include
       the name, suggest `alias/<name>` (the verb lives behind the alias).
     - `[ns :refer [verbs…]]` — if `name` is a refer'd verb, suggest the
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
          home-ns-require-specs)))

(defn home-requires-for
  "The require specs for agent `id`'s home ns.

   REACTIVE config-on-record
   (decision 2), resolved in precedence:

     1. the agent's `:seon.eval/home-requires` DATOM, when present — the
        re-arm case (the entity exists). A live
        `(db/transact! {:seon.agent/id id :seon.eval/home-requires […]})`
        drives the next `setup-agent-ns!`, so the dial is reactive, not
        write-only. (Mixed-`:or` schema → stored `pr-str`'d → decode on read.)
        The attr is NOT in the boot schema; it self-installs on that first
        override transact (`ensure-datahike-attrs!` runs inside `transact!`),
        so by read time the `installed-schema` gate below is TRUE whenever a
        datom exists. The gate is not a no-op: on a fresh pod with no override
        yet, the attr is uninstalled and querying it would THROW — the gate
        makes the read fall to (2) instead. VERIFIED live (scratch conn):
        transact → attr installs, value round-trips through decode.
     2. else the `:seon.eval/home-requires` from `resolve-agent-context` — the
        fresh-MINT case, before the datom is written (the config/manifest value).
     3. else the [[home-ns-require-specs]] const (= byte-parity for a no-config
        agent). The const is the DEFAULT VALUE only."
  {:malli/schema [:=> [:catn [::id [:maybe :string]]] [:vector :any]]}
  [id]
  (or (when id
        ;; (1) the persisted datom, if the entity carries it (re-arm).
        (let [db (some-> db/*conn* deref)]
          (when (and db (contains? (db/installed-schema db) :seon.eval/home-requires))
            (some->> (:seon.eval/home-requires
                       (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))
                     (db/decode-edn-value :seon.eval/home-requires)
                     seq
                     vec))))
      ;; (2) the config/manifest value (fresh mint — datom not yet written).
      (when id
        (let [reqs (:seon.eval/home-requires (config/resolve-agent-context id nil))]
          (when (seq reqs) (vec reqs))))
      ;; (3) the const default.
      home-ns-require-specs))

(defn home-ns-form
  "The exact `(ns …)` SOURCE wired into an agent's home ns.

   The one form [[setup-agent-ns!]] evaluates AND the one the
   workspace block renders verbatim, with every alias/refer visible (no
   bare-name reconstruction). `home-ns` is the home-ns symbol/string/keyword
   (e.g. `my.agent.<id>`).

   Two arities: the 1-arg renders the DEFAULT [[home-ns-require-specs]] (the
   stub/preview shape); the 2-arg takes the resolved `specs` for a specific
   agent ([[home-requires-for]]) — `setup-agent-ns!` passes the per-agent list
   so a `:seon.eval/home-requires` override actually wires the agent's ns."
  {:malli/schema [:function
                  [:=> [:catn [::home-ns [:or :symbol :string :keyword]]] :string]
                  [:=> [:catn [::home-ns [:or :symbol :string :keyword]]
                              [::specs [:vector :any]]] :string]]}
  ([home-ns] (home-ns-form home-ns home-ns-require-specs))
  ([home-ns specs]
   (str "(ns " (name home-ns) "\n  (:require "
        (str/join "\n            " (map pr-str specs))
        "))")))

(def authored-ns-require-nses
  "The [[home-ns-require-specs]] NAMESPACES whose short alias an agent's
   AUTHORED (non-home) namespace also carries — the data + verb namespaces
   every authored ns reaches through (`db/`, `plan/`, `message/`, `schema/`).
   A SELECTION over [[home-ns-require-specs]] (the single source of the
   alias↔ns mapping) — the alias names are never re-spelled here. Excludes
   `seon.agent` (the home orchestration alias) and the lifecycle `:refer`
   verbs (home-ns only). The `my.*` toolkit stays FULL-QUALIFIED (`my.ui/…`),
   no alias."
  '#{seon.db my.plan seon.agent.message seon.schema})

(def authored-ns-require-specs
  "The `(:require …)` specs merged into an agent-authored `(ns …)` form
   ([[augment-ns-source]]) so its short aliases resolve because they are
   GENUINELY required (no magic) — the `:as` specs of [[home-ns-require-specs]]
   selected by [[authored-ns-require-nses]]. Derived, never a second hardcoded
   copy."
  (filterv (fn [spec]
             (and (vector? spec)
                  (= :as (second spec))
                  (contains? authored-ns-require-nses (first spec))))
           home-ns-require-specs))

(defn- authored-ns-alias-names
  "Comma-joined `:as` alias names from [[authored-ns-require-specs]]
   (e.g. \"db, plan, message, schema\") — for the real-require narration note."
  []
  (str/join ", " (map (fn [spec] (name (nth spec 2))) authored-ns-require-specs)))

;; Forward refs — the requires-edge tee (defined with the eval-batch machinery
;; below) is reused here so a fresh agent records its home-ns requires at setup.
(declare ns-requires-tx ns-require-edges-tx transient-ns-syms)

(defn ^:async setup-agent-ns!
  "Create + initialize the agent's home namespace.

   Returns the agent-ns
   symbol (same as the input). Idempotent.

   Evaluates ONE `(ns <home> (:require …))` form that aliases the verb + data
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
   toolkit ns gone) and throws."
  {:malli/schema
   [:=> [:catn [::compile-state :any] [::agent-ns-sym :any] [::agent-id :any]] :any]}
  [compile-state agent-ns-sym agent-id]
  (let [setup-src (home-ns-form agent-ns-sym (home-requires-for agent-id))
        r (await (eval compile-state setup-src
                       {::starting-ns user-ns-sym ::analyze-deps? true}))]
    (when-not (::ok? r)
      (throw (ex-info
               (str "setup-agent-ns! failed — the home-ns require/refer did not "
                    "analyze cleanly for " agent-ns-sym ". seed-toolkit-refers! "
                    "(in init-bootstrap!) must declare the refer'd toolkit "
                    "defs into the compile-state.")
               {:agent-ns agent-ns-sym :result r})))
    ;; Record the home ns's `:seon.ns/requires` edges at SETUP time, so a
    ;; genuinely fresh agent renders its required-ns cards on turn 0 (before
    ;; its first eval). The eval-batch path tees these on every eval, but a
    ;; brand-new agent has no eval yet — reuse [[ns-requires-tx]] (the ONE
    ;; requires-edge path) against the analyzer's require set for the home ns.
    (when (and db/*conn*
               (not (contains? transient-ns-syms agent-ns-sym)))
      (let [ns-kw  (keyword (str agent-ns-sym))
            req-tx (into (ns-requires-tx
                           @db/*conn* ns-kw
                           (analyzer-info/ns-requires compile-state agent-ns-sym))
                         ;; the reified alias/refer edges too (M4) — a
                         ;; fresh home ns renders its SCI cage env from
                         ;; datoms on turn 0, before any eval tees.
                         (ns-require-edges-tx
                           @db/*conn* ns-kw
                           (analyzer-info/ns-require-edges compile-state
                                                           agent-ns-sym)))]
        (when (seq req-tx)
          (await (db/transact! {:seon.db/tx-data req-tx})))))
    agent-ns-sym))

;; ============================================================
;; eval-batch! — the REPL harness primitive. Takes parsed pairs from
;; seon.repl.internal/parse-forms; evaluates each in the agent's compile-state
;; with PARTIAL-FAILURE semantics (form N+1 always runs, even if N
;; failed); persists each as a :seon.eval entity; stashes the live
;; result in the agent's !results atom. Returns the ordered vector of
;; eval-id strings.
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

   Bounded by `@!timeout-ms` (default) OR the one-shot override left by
   [[budget]]. A Promise that exceeds the bound is NOT dropped: it is
   handed back as `::pending-promise` so the caller stashes the live handle at
   `result/<id>` (re-reference auto-resolves it later). A `(defer …)`
   wrapper opts out of awaiting entirely and takes the same
   `::pending-promise` path immediately.

   Returns {::ok? true  ::value v}     on resolution OR a non-Promise value;
           {::ok? false ::pending-promise <promise>} on timeout OR `defer` —
                                          carry the still-running Promise to
                                          result/<id>;
           {::ok? false :seon/error <seon.error/->map>} on rejection."
  [v]
  (cond
    ;; Explicit opt-out: `(defer expr)` wrapped the Promise. Don't await —
    ;; hand the raw Promise back as a pending handle. Consume any one-shot
    ;; budget so it doesn't leak into the NEXT form's auto-await.
    (instance? Deferred v)
    (do (reset! !next-budget-ms nil)
        {::ok? false ::pending-promise (.-promise v)})

    (instance? js/Promise v)
    (try
      (let [override (let [m @!next-budget-ms]
                       (reset! !next-budget-ms nil)
                       m)
            ms       (or override @!timeout-ms)
            raced    (await (race-timeout v ms))]
        (if (identical? raced timeout-sentinel)
          ;; Auto-await timed out. The Promise keeps running (no JS
          ;; preemption); carry the live handle back as `::pending-promise` so the
          ;; caller stashes it at result/<id> for a later re-reference —
          ;; never drop it (the agent would have no way to recover it).
          {::ok? false ::pending-promise v}
          {::ok? true ::value raced}))
      (catch :default e
        ;; The awaited value came from the AGENT form's async execution, so
        ;; a rejection is agent-fault by default (wrapper-fault refines a
        ;; propagated core-verb violation back to :core). recorded? skips
        ;; the datom when an instrumented ^:async verb's wrapper .catch
        ;; already recorded this same rejection. Return contract unchanged.
        (when-not (error/recorded? e)
          (error/record! {:seon.error/raw   e
                          :seon.error/fault (instrument/wrapper-fault e :agent)}))
        {::ok? false :seon/error (error/->map e)}))

    :else
    (do
      ;; Even for non-Promise values, consume any pending budget so it
      ;; doesn't leak into the NEXT form's auto-await.
      (reset! !next-budget-ms nil)
      {::ok? true ::value v})))

;; ============================================================
;; Detect-and-tee (v1.md §2.2 + §7 / STATUS.md Phase B item 10)
;;
;; After a successful eval, snapshot the analyzer's :defs and the
;; schema registry's keyset before/after; every new def becomes a
;; :seon.fn entity, every new schema key becomes a :seon.schema entity.
;; An `(ns …)` form also yields a :seon.ns entity. These ride in the
;; same tx as the eval entity (via record-eval!'s ::tee arg), sharing
;; the :seon.db/eval-id tx-meta — the eval IS the tx that wrote the
;; program-graph datom.
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
  "Phase 3 (mvp-completion-plan 2026-05-27): auto-instrument every
   newly-tee'd fn whose `:malli/schema` parsed cleanly (i.e. the entity
   has a `:seon.fn/spec` and NO `:seon.fn/schema-error`).

   For each, register the function schema in
   `malli.core/-function-schemas*` (the atom `mi/instrument!` reads),
   then run `mi/instrument!` filtered to those (ns, sym) pairs only.
   Calls `(seon.error.instrument/report-fn ...)` on validation failure
   so the envelope flows through the same record-eval! path that the
   boot-time instrumentation uses.

   Idempotent: re-registering same key is last-write-wins; re-running
   `mi/instrument!` against an already-wrapped var replaces the wrapper.

   Async fns route through [[seon.instrument/register-target!]] — simple
   fixed-arity fns get input+output (output on Promise resolution),
   variadic/multi-arity get input+arity. No-op when instrumentation is
   disabled via the `SEON_INSTRUMENT` kill-switch.

   `targets` — seq of `[ns-sym fn-sym schema-form async?]` tuples."
  [targets]
  (when (and (seq targets) (instrument/enabled?))
    (doseq [[ns-sym fn-sym schema-form async?] targets]
      (instrument/register-target! ns-sym fn-sym schema-form async?))
    (let [target-set (set (map (fn [[n s _ _]] [n s]) targets))]
      (mi/instrument! {:report  einstrument/report-fn
                       :filters [(fn [n s _d] (contains? target-set [n s]))]}))))

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

(defn- collect-auto-test-targets
  "Phase 4 (mvp-completion-plan 2026-05-27): return the set of FQ test
   syms to run after a successful eval. Two sources:

   - Tests newly defined in THIS eval (a fresh `(deftest …)` form). The
     symbol comes from the [[changed-defs]] diff filtered via
     [[deftest-def?]].
   - Tests in the DB whose `:seon.test/source` mentions any fn newly
     defined in THIS eval. Substring match — v0 heuristic, see
     `seon.test.runner/tests-referring-to`.

   Result is a set so a deftest that also matches the substring scan
   (the test source mentions its own sym) only runs once."
  [compile-state defs-before source eval-ns]
  (let [new-defs    (changed-defs compile-state defs-before source eval-ns)
        new-tests   (for [{:seon.analyzer-info/keys [var-map]} new-defs
                          :when (deftest-def? var-map)]
                      (symbol (str (:name var-map))))
        new-fn-syms (for [{:seon.analyzer-info/keys [var-map]} new-defs
                          :when (and (:fn-var var-map)
                                     ;; deftest's public fn ALSO has
                                     ;; :fn-var true; skip — already
                                     ;; in new-tests above.
                                     (not (deftest-def? var-map)))]
                      (symbol (str (:name var-map))))
        referring   (mapcat test-runner/tests-referring-to new-fn-syms)]
    (set (concat new-tests referring))))

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
        ;; Opt-out (seon.instrument/async-unwrappable? — structural, computed
        ;; from async flag + fn shape + schema form) is applied in register-target!.
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
  [k source at]
  (cond-> {:seon.schema/key        k
           :seon.schema/source     source
           :seon.schema/created-at at}
    (namespace k)
    (assoc :seon.schema/ns {:seon.ns/name (keyword (namespace k))})))

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

   Every top-level `(def …)`/`(defn …)`/`(defn- …)` with a symbol name
   slot, as a distinct vector in source order. The ONE 'what does this
   source define' walk — the body-redef rescue in [[changed-defs]] and
   the false-confidence guard ([[failed-def-syms]]) both read it.
   Fail-closed on unreadable source (empty)."
  [source]
  (into []
        (comp (keep (fn [f]
                      (when (and (seq? f)
                                 (contains? '#{def defn defn-} (first f))
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

(defn- collect-symbols
  "Every symbol appearing anywhere in `x` (recursively through lists,
   vectors, maps, sets), as a vector in encounter order."
  [x]
  (let [acc  (volatile! [])
        walk (fn walk [y]
               (cond
                 (symbol? y) (vswap! acc conj y)
                 (coll? y)   (doseq [z y] (walk z))))]
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
        macro), and does NOT resolve to a var.
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
                          (schema-tee-row k source at))
        ;; Phase 4 (mvp-completion-plan 2026-05-27): deftest defs carry
        ;; the analyzer's top-level `:test true` marker (see
        ;; [[deftest-def?]] — the old `(:test (:meta var-map))` check was
        ;; ALWAYS nil, the live-resume bug where agent deftests never got
        ;; a :seon.test row). Each gets a `:seon.test` row keyed on the
        ;; FQ sym (identity attr). Source is the same form text —
        ;; `tests-referring-to` later substring-scans it to find tests
        ;; that mention a redefined fn.
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
        ns-entities (when ns-sym
                      [{:seon.ns/name   (keyword (str ns-sym))
                        :seon.ns/source source}])]
    (vec (concat ns-entities fn-entities schema-entities test-entities))))

;; ----------------------------------------------------------------------------
;; :seon.ns/requires diff-upsert (the one fix that unblocks DB-layer load —
;; db-is-the-running-system PRD). :seon.ns/requires is CARDINALITY-MANY
;; (a queryable dep-edge set), so a plain entity-map upsert ACCUMULATES the
;; vector instead of replacing it. To keep the stored set EXACTLY equal to the
;; analyzer's current requires for the ns, the tee emits a DIFF: an entity-map
;; upsert for the ADDED names plus an explicit `[:db/retract …]` for each
;; REMOVED name. A brand-new ns (no current requires) is additions-only — the
;; entity-map upsert creates the :seon.ns row. Captured on EVERY successful
;; eval's ending ns (a `(ns … (:require …))`, a re-eval'd ns form, or a bare
;; `(require '[x])` at the REPL all keep the index current), gated to real
;; (non-transient) namespaces by the caller.
;; ----------------------------------------------------------------------------

(defn ns-requires-tx
  "Diff-upsert tx ops setting `:seon.ns/requires` for `ns-kw`.

   Becomes EXACTLY
   `new-req-set` (a set of ns-name keywords). Reads the ns's CURRENT
   stored requires from the `db` value and returns:

   - `[{:seon.ns/name ns-kw :seon.ns/requires (vec additions)}]` for
     names in `new-req-set` not already stored (identity-attr upsert;
     creates the `:seon.ns` row if absent), AND
   - one `[:db/retract [:seon.ns/name ns-kw] :seon.ns/requires r]` per
     stored name no longer in `new-req-set`.

   Returns `[]` when nothing changed (no spurious tx ops). The retract
   lookup-ref is safe: removed names are read from the db, so the entity
   already exists. `db` is a datahike db value (third-party boundary)."
  {:malli/schema
   [:=> [:catn [::db :any] [::ns-kw :keyword] [::new-req-set [:set :keyword]]]
        [:vector :any]]}
  [db ns-kw new-req-set]
  (let [current   (into #{}
                        (map first)
                        (db/query '[:find ?r
                                    :in $ ?ns
                                    :where
                                    [?e :seon.ns/name ?ns]
                                    [?e :seon.ns/requires ?r]]
                                  db ns-kw))
        additions (set/difference new-req-set current)
        removals  (set/difference current new-req-set)
        upsert    (when (seq additions)
                    [{:seon.ns/name     ns-kw
                      :seon.ns/requires (vec additions)}])
        retracts  (for [r removals]
                    [:db/retract [:seon.ns/name ns-kw] :seon.ns/requires r])]
    (vec (concat upsert retracts))))

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
;; `[:vector …]` (not `[:set …]`) matching `:seon.ns/requires`: the
;; transact validator checks the tx VALUE against the registered
;; container, and diff-upserts transact vectors.
(schema/register! :seon.fn/read-attrs [:vector :qualified-keyword])

(defn- source-qualified-kws
  "Every QUALIFIED keyword literal in `source`'s top-level forms, as a
   set. Walks the READ forms (strings/comments can't false-positive the
   way a text regex does); `#{}` when the source doesn't read — but a
   `:seon.fn` row only exists for sources [[defn-form?]] read cleanly.

   `resolve-opts` (`{:seon.repl/current-ns … :seon.repl/aliases …}`,
   from the tee's analyzer context) resolves `::kw`/`::alias/kw`
   literals to their REAL namespaces (C37 — the stored read-set must
   carry the resolved attr, not a placeholder). A keyword whose alias
   did NOT resolve keeps the visible `?`-prefixed placeholder namespace
   and is DROPPED here — absent beats storing a garbage watch attr."
  [source resolve-opts]
  (into #{}
        (comp (mapcat #(tree-seq coll? seq %))
              (filter #(and (keyword? %)
                            (some? (namespace %))
                            (not (str/starts-with? (namespace %) "?")))))
        (or (read-all-forms source resolve-opts) [])))

(defn require-edges-from-source
  "Parse an `(ns …)` `source` string into the reified require-edge set.

   The SOURCE-side counterpart of
   `seon.analyzer-info/ns-require-edges` — same `::analyzer-info/
   require-edge` maps, derived by reading the form's `:require` clause.
   Used where no analyzer state exists: the boot indexer's full-source
   ns rows and the SCI cage's legacy fallback for pre-structural rows.
   Also carries `:seon.ns.require/refer-all? true` for a
   `:refer :all` clause (legacy text only — CLJS can't compile one).
   Fail-soft → `#{}` on any read error or a non-`(ns …)` form."
  {:malli/schema [:=> [:cat :string] :seon.analyzer-info/require-edges]}
  [source]
  (try
    (let [form (reader/read-string source)]
      (if (and (seq? form) (= 'ns (first form)))
        (let [reqs (->> form
                        (filter seq?)
                        (some #(when (= :require (first %)) (rest %))))]
          (into #{}
                (keep (fn [r]
                        (cond
                          (symbol? r)
                          {:seon.ns.require/target (keyword (str r))}

                          (and (vector? r) (symbol? (first r)))
                          (let [tns  (first r)
                                opts (try (apply hash-map (rest r))
                                          ;; probe: a malformed require clause
                                          ;; (odd-count opts) yields no opts —
                                          ;; expected for agent-authored source,
                                          ;; not a defect.
                                          (catch :default _ {}))
                                as   (:as opts)
                                refr (:refer opts)]
                            (cond-> {:seon.ns.require/target (keyword (str tns))}
                              (symbol? as)       (assoc :seon.ns.require/alias as)
                              (sequential? refr) (assoc :seon.ns.require/refers
                                                        (set refr))
                              (= :all refr)      (assoc :seon.ns.require/refer-all?
                                                        true)))
                          :else nil)))
                (or reqs [])))
        #{}))
    ;; probe: fail-soft over agent-authored source — an unreadable /
    ;; non-(ns …) form has no require edges; #{} is the expected answer.
    (catch :default _ #{})))

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
   SYMBOLS. Total: `#{}` of edges folds to the empty info."
  {:malli/schema [:=> [:cat :seon.analyzer-info/require-edges] ::require-info]}
  [edges]
  (reduce
    (fn [acc {:seon.ns.require/keys [target alias refers refer-all?]}]
      (let [tsym (symbol (name target))]
        (cond-> (update acc ::nses conj tsym)
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
                           :seon.ns.require/refers :seon.ns.require/refer-all?]}]
                       [:seon.ns/name ns-kw]))))
    (catch :default e
      ;; the existence-probe above already returns #{} for the expected
      ;; missing-row case, so a throw reading OUR stored require edges is a
      ;; core defect (:core) — the caller still degrades to the empty set.
      (error/record! {:seon.error/raw e :seon.error/fault :core})
      #{})))

(defn ns-require-edges-tx
  "Tx ops making `:seon.ns/require-edges` for `ns-kw` EXACTLY `new-edges`.

   `[]` when the stored edge set already equals `new-edges` (set
   compare over the normalized maps — no spurious tx ops). On change
   the old COMPONENT rows are `[:db/retractEntity …]`'d (cascade
   removes the parent ref datoms too; REPL-verified, no orphans) and
   the new set is asserted via the `:seon.ns/name` identity upsert
   (creates the ns row when absent — same shape as [[ns-requires-tx]])."
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

   Same diff discipline as [[ns-requires-tx]] (cardinality-many
   accumulates on plain upsert): additions ride an identity upsert,
   removals get explicit retracts, `[]` when unchanged. Emitted at the
   tee site for every teed `:seon.fn` row, so a REDEF that drops a
   keyword literal sheds the stale watch — and a legacy row self-
   backfills on its first replay/re-eval."
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
    (vec (concat (when (seq additions)
                   [{:seon.fn/sym        sym-str
                     :seon.fn/read-attrs (vec additions)}])
                 (for [k removals]
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
;; Detect by ORIGIN: a sym whose CURRENT `:seon.fn/source` datom's tx carries
;; `:seon.db/origin :core-seed` is compiled core/third-party (same provenance
;; rule [[tee-registered-schema!]] uses for the schema self-tee). A NEW sym
;; (no row) or an agent-origin sym is NOT blocked — agents freely define and
;; redefine in their OWN namespaces; only redefining an existing :core-seed
;; sym is denied.
;; ----------------------------------------------------------------------------

(defn core-origin-fn-syms
  "The `syms` subset whose source is `:core-seed` origin.

   FQ `:seon.fn/sym` strings whose CURRENT
   `:seon.fn/source` datom's tx carries `:seon.db/origin :core-seed` —
   i.e. compiled core/third-party fns the agent must not override. A sym
   with no `:seon.fn` row, or whose latest source was written under any
   non-core origin (`:agent`, `:replay`, …), is NOT included (it is the
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
                      [?tx :seon.db/origin :core-seed]]
                    db))))

(defn reject-core-overrides
  "Drop `tee-entities` rows that override a `blocked` core sym.

   The override guard: drop any `:seon.fn` row
   whose `:seon.fn/sym` is in `blocked` (a set of core-origin syms from
   [[core-origin-fn-syms]]) and, for each dropped sym, `js/console.warn`
   a specific, actionable one-liner. Non-`:seon.fn` rows (`:seon.ns`,
   `:seon.schema`, `:seon.test`, the `ns-requires-tx` retract vectors)
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
   - REPLAY scope (`:seon.db/replay? true` in the tx-context) → nil.
     Replayed `(seon.schema/register! …)` sources re-run register!;
     re-teeing them would write a no-op upsert per schema per boot,
     re-anchoring row tx-ids (the exact churn the replay design's
     'detect-and-tee doesn't re-fire' invariant exists to avoid).
   - EVAL-BATCH scope (`:seon.db/eval-id` in the tx-context) → nil. An
     agent turn's `eval-batch!` wraps each form in a tx-context carrying
     its eval-id, and the GATED detect-and-tee (`build-tee-entities` in
     `record-eval!`) writes the :seon.schema row only on a SUCCESSFUL
     eval. The self-tee must stand down there or a `register!` in a
     later-failing form would persist its schema/`:seon.ns` rows anyway
     (#39 — the eval is the transaction boundary). The self-tee is the
     durability path ONLY for the bare-eval/REPL scope (no eval-id, no
     detect-and-tee).
   - CORE-CLAIMED row (current `:seon.schema/source` datom's tx
     carries `:seon.db/origin :core-seed`) → nil. The bootstrap
     self-host compiler can re-execute compiled-bundle registrations
     at runtime (an agent's `(require …)` goog.globalEvals bundle JS,
     the relink-registry! incident class); without this guard those
     re-registrations would convert boot-indexed rows into never-
     prunable, replayable `(…)` call rows. Same provenance rule as
     `seon.client/prune-core-ghosts!`.
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
    (when-not (or (:seon.db/replay? (db/current-tx-context))
                  ;; #39: inside an eval-batch! per-form scope (an eval-id is
                  ;; in the tx-context) the GATED detect-and-tee
                  ;; (`build-tee-entities` in `record-eval!`) owns this
                  ;; schema's :seon.schema row and writes it ONLY on a
                  ;; successful eval. Deferring here is what makes "a
                  ;; register! in a FAILED form persists nothing" true — the
                  ;; eager self-tee is the durability path ONLY for the
                  ;; bare-eval/REPL scope (no eval-id, no detect-and-tee).
                  (:seon.db/eval-id (db/current-tx-context)))
      (let [source (pr-str (list 'seon.schema/register! k form))
            [stored-src origin]
            (first (db/query
                     {:seon.db/query
                      '[:find ?src ?origin
                        :in $ ?k
                        :where
                        [?s :seon.schema/key ?k]
                        [?s :seon.schema/source ?src ?tx]
                        [(get-else $ ?tx :seon.db/origin :seon.db/untagged)
                         ?origin]]
                      :seon.db/conn conn
                      :seon.db/args [k]}))]
        (when-not (or (= origin :core-seed)
                      (= stored-src source))
          (-> (db/transact!
                {:seon.db/tx-data [(schema-tee-row k source (js/Date.))]
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
   remains available in-session as the live var `result/<id>` (globalThis
   live-result stash, `stash-result-raw!`) — that path is NOT capped."
  (config/store-edn-cap))

(defn cap-edn
  "Truncate a pr-str'd value string to `store-edn-cap`.

   Appends an elision marker reporting how many chars were dropped.
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
       (str (subs s 0 limit) " …⟨" (- n limit) " chars elided⟩")
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
        ;; The kind may sit at the top level (the synthesized read/
        ;; compile/parity error maps eval-batch! builds) OR in the
        ;; flattened `:seon.error/data` (a thrown ex-info's ex-data).
        kind (or (:seon.error/kind err)
                 (:seon.error/kind (:seon.error/data err)))
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
   live-result stash. Never throws."
  {:malli/schema [:=> [:catn [::eval-id :string] [::value :any]] :string]}
  [eval-id value]
  (clip-result-body
    eval-id
    (try
      (value/render-ai eval-id value)
      (catch :default e
        ;; OUR bounded value renderer throwing is a core defect (:core) —
        ;; it is designed to handle any value; the caller still degrades to
        ;; a fallback that names where the live value lives instead of a
        ;; bare (str value) that could itself be a giant/opaque blob.
        (error/record! {:seon.error/raw e :seon.error/fault :core})
        (str "; <value could not be rendered as data; the live value is "
             "result/" eval-id ">")))))

(defn ^:async record-eval!
  "Transact one `:seon.eval` as a component child of its turn.

   Per v1.md §2.1 — `:seon.agent.turn/evals` is component-many. The
   nested-map shorthand creates the eval inline; datahike's component
   semantics mean a one-pull on the turn returns its evals without
   needing a back-ref query.

   When `::tee` is non-empty, the detect-and-tee program-graph entities
   (`:seon.fn` / `:seon.schema` / `:seon.ns` — see v1.md §2.2 / Phase
   B item 10) land in the SAME tx as the eval entity. Identity-attr
   upserts handle redefinition.

   NEVER silently loses the eval row (run-4 root cause,
   e2e-demo-findings-2026-06-08 §Run 4 CORRECTION). A nil `source` is
   coerced to \"\" before the tx (the attr is `:string`; a nil would fail
   Malli and sink the whole tx) so a comment-only / repaired entry still
   records. A DB write failure doesn't abort the batch, but it is handled
   in two LOUD stages:

   1. Full tx (eval + tee) fails → `console.error` with the message +
      source, then RETRY without the tee rows. The transcript is the
      agent's memory — a dropped tee row is recoverable (re-derivable
      from source), a dropped eval row is not. The recovered eval row
      is then stamped `:seon.eval/record-error` (separate top-level
      tx) — the PARTIAL record is honest, queryable, and surfaces via
      `seon.warn/check-record-errors` in every agent's context.
   2. Even the bare eval-row retry fails → `console.error` marked
      DATA LOSS. Nothing softer than error for either stage.

   The conn is captured from `seon.db/*conn*` at (synchronous) entry
   and passed explicitly to BOTH transacts — the retry runs after an
   await, where a CLJS `binding` of `*conn*` (test fixtures) has
   already unwound.

   The tx auto-tags with whatever causality bundle is in
   `(seon.db/current-tx-context)` (eval-batch! opens the per-eval
   scope with `:seon.db/agent-id` + `:seon.db/eval-id` + `:seon.db/
   origin :agent`, plus whatever the caller layered above).

   Request keys (C21): `::id-of-eval` / `:seon.agent.turn/id-of-turn`
   are REFERENCE ids (the open-turn! `id-of-*` request idiom); `::at` /
   `::narration` / `::source` / `::duration-ms` / `::output` reuse the
   persisted-attr keys (same meaning, same type); `::ending-ns` is the
   fold-accumulator ns (a SYMBOL — the registered `::ending-ns`, not the
   persisted :keyword `::ns`, coerced at the write boundary below);
   `::result` (the eval-result envelope) and `::tee` (tx maps) carry
   runtime data and stay unregistered."
  {:malli/schema [:=> [:catn [::record-request :map]] :any]}
  [{::keys [at narration source result duration-ms tee output]
    eval-id ::id-of-eval
    turn-id :seon.agent.turn/id-of-turn
    ns      ::ending-ns}]
  (let [conn     db/*conn*
        ;; Whose scope produced this eval — the agent turn loop runs each batch
        ;; inside `(db/with-agent id …)`, so this is the owning agent. nil for
        ;; agent-less evals (boot index, inspector REPL); then the ref is omitted
        ;; (optional = absent), never stored nil.
        aid      (db/current-agent-id)
        eval-map (cond-> {:seon.eval/id          eval-id
                          :seon.eval/at          at
                          :seon.eval/duration-ms (or duration-ms 0)
                          :seon.eval/narration   (or narration "")
                          ;; `:seon.eval/source` is registered `:string`, so a
                          ;; nil source (a comment-only / repaired entry whose
                          ;; span carries no readable form) would fail Malli
                          ;; and SINK THE WHOLE TX — dropping the agent's eval
                          ;; row, the one thing this fn must never lose. Coerce
                          ;; nil→"" at the write boundary (same as narration
                          ;; just above): an empty-source row is honest and
                          ;; queryable; a missing row is data loss.
                          :seon.eval/source      (or source "")
                          :seon.eval/ok?         (boolean (::ok? result))
                          ;; Renderer dispatch via entity-schema props
                          ;; (`:seon.eval` map registration). No per-row
                          ;; stamp — the renderer enumerates evals by
                          ;; walking the AEVT index for `:seon.eval/id`
                          ;; and resolves `:seon.render/ai` through
                          ;; `(m/schema :seon.eval)`'s properties.
                          ;; v1.md:236 — ending ns. From the eval result
                          ;; on success; from the fold accumulator on
                          ;; failure (last-known-good). Always populated.
                          ;; Stored as :keyword per spec; eval-batch
                          ;; holds it as a symbol for cljs.js, coerce
                          ;; at this boundary.
                          :seon.eval/ns          (if (keyword? ns)
                                                   ns
                                                   (keyword (str ns)))}
                   ;; Denormalized agent link — a ref to the owning agent so an
                   ;; eval is found in one hop (e.g. `/clear`'s eval query).
                   ;; Lookup-ref resolves at tx time (the agent pre-exists).
                   aid (assoc :seon.eval/agent [:seon.agent/id aid])

                   ;; `render-result-edn` already clips the body to the
                   ;; result-body cap (`seon.config/result-body-render-cap`
                   ;; as a token budget) and names `result/<id>` for the
                   ;; full value. `cap-edn` (store-edn-cap) is the additional
                   ;; MEMORY-SAFETY backstop so the DB never holds a multi-MB
                   ;; blob even if the render cap is raised; a no-op when the
                   ;; render cap ≤ the store cap. The FULL value is in the
                   ;; globalThis live-result stash (set before this call).
                   (::ok? result)
                   (assoc :seon.eval/result-edn
                          (cap-edn
                            (render-result-edn eval-id (::value result))))

                   ;; (fix f) print output captured during the eval span —
                   ;; persisted so the transcript can show it next to the
                   ;; result, like a real REPL. Absent when nothing printed.
                   (and (string? output) (not (str/blank? output)))
                   (assoc :seon.eval/output (cap-edn output))

                   ;; Store the LEGIBLE, edn-safe error string (deepest
                   ;; real message + structured `:seon.error/data`), NOT
                   ;; the raw `pr-str` of the whole `->map` — that buried
                   ;; the useful line under the opaque `#error` + stack and
                   ;; was unreadable by the agent-side renderer. The full
                   ;; value remains in the live result stash.
                   (not (::ok? result))
                   (assoc :seon.eval/error
                          (cap-edn
                            (try (render-error-string (:seon/error result))
                                 ;; OUR error renderer throwing is a core
                                 ;; defect (:core); the caller still degrades
                                 ;; to (str error) so the eval row records.
                                 (catch :default e
                                   (error/record! {:seon.error/raw e
                                                   :seon.error/fault :core})
                                   (str (:seon/error result))))))

                   ;; Phase A item 8 — when the error carries a Malli
                   ;; instrumentation envelope (flattened into
                   ;; :seon.error/data by seon.error/->map), persist the
                   ;; envelope as `:seon.eval/error-data` (pr-str round-
                   ;; trip — see attr docstring). Renderers branch on
                   ;; this to produce the structured ;; ERROR block.
                   (and (not (::ok? result))
                        (einstrument/instrument-error?
                          (some-> result :seon/error :seon.error/data)))
                   (assoc :seon.eval/error-data
                          ;; Use the fn-stubbing serializer — envelope
                          ;; embeds Malli schemas whose forms contain
                          ;; unreadable #object[…] fn refs. See
                          ;; seon.error.instrument/pr-str-readable.
                          (einstrument/pr-str-readable
                            (-> result :seon/error :seon.error/data))))
        ;; Attach the eval as a component child of the turn. Datahike's
        ;; cardinality-many ref accumulates on upsert — each record-eval!
        ;; call appends one eval to the turn's evals set.
        ;;
        ;; Tee entities (`:seon.fn` / `:seon.schema` / `:seon.ns` from
        ;; detect-and-tee, v1.md §2.2 / Phase B item 10) ride in the same
        ;; tx so they share `:seon.db/eval-id` tx-meta and either all
        ;; land or none do.
        eval-tx  [{:seon.agent.turn/id    turn-id
                   :seon.agent.turn/evals [eval-map]}]
        tx-data  (into eval-tx tee)
        ;; Explicit conn on both transacts — see docstring (retry runs
        ;; after an await, outside any CLJS `binding` of *conn*).
        request  (cond-> {:seon.db/tx-data tx-data}
                   conn (assoc :seon.db/conn conn))
        r (await (db/transact! request))]
    (when-not (:seon.db/ok? r)
      (js/console.error "[seon.eval/record-eval!] tx FAILED:"
                        (-> r :seon.db/error :seon.error/message)
                        "— source:" source)
      (if (seq tee)
        ;; Stage 2: the eval row is the agent's memory — retry WITHOUT
        ;; the tee rows so the transcript survives a bad tee entity.
        (let [retry (cond-> {:seon.db/tx-data eval-tx}
                      conn (assoc :seon.db/conn conn))
              r2    (await (db/transact! retry))]
          (if (:seon.db/ok? r2)
            ;; HONEST RECORDS (task #24 symptom 3): a recovered-without-
            ;; tee eval is a PARTIAL record — the dropped tee means that
            ;; registration/def will NOT resume after a restart. The old
            ;; behavior was a console.error and nothing else: the log
            ;; lied by omission. Stamp :seon.eval/record-error on the
            ;; eval row (a SEPARATE top-level tx — nested attrs need a
            ;; boot-schema entry, top-level attrs lazy-install) so the
            ;; failure is queryable and seon.warn/check-record-errors
            ;; derives the warning into every agent's context until the
            ;; next user message scopes it out.
            (let [reason (cap-edn
                           (str (count tee) " program-graph tee row(s) "
                                "DROPPED (will not survive a restart) — "
                                "tee tx failed: "
                                (-> r :seon.db/error :seon.error/message)))
                  r3     (await (db/transact!
                                  (cond-> {:seon.db/tx-data
                                           [{:seon.eval/id eval-id
                                             :seon.eval/record-error reason}]}
                                    conn (assoc :seon.db/conn conn))))]
              (js/console.error
                "[seon.eval/record-eval!] eval row RECOVERED without tee —"
                (count tee) "program-graph tee row(s) DROPPED for eval"
                eval-id)
              (when-not (:seon.db/ok? r3)
                (js/console.error
                  "[seon.eval/record-eval!] could not stamp"
                  ":seon.eval/record-error on eval" eval-id ":"
                  (-> r3 :seon.db/error :seon.error/message))))
            (js/console.error
              "[seon.eval/record-eval!] DATA LOSS — eval row" eval-id
              "could not be persisted even without tee:"
              (-> r2 :seon.db/error :seon.error/message)
              "— source:" source)))
        (js/console.error
          "[seon.eval/record-eval!] DATA LOSS — bare eval row" eval-id
          "failed with no tee rows to drop — source:" source)))))

;; ============================================================
;; REPL-parity intercepts (unit #23 fix d, per the plan's REPL-PARITY
;; CONTRACT). The agent's context mimics a real Clojure REPL, so its
;; reflexive moves must work — or fail with a translation that teaches
;; the core equivalent. Two forms get a form-level pre-check
;; BEFORE eval (probed live 2026-06-09: `(in-ns 'foo)` fails with an
;; opaque undeclared-var error; bare `*ns*` SILENTLY evals to nil —
;; a silent wrong answer, the worst kind):
;;
;;   (in-ns 'foo) → legible ERROR teaching (ns foo) — same effect.
;;   *ns*         → INTERCEPTED VALUE: the current ns symbol (honest —
;;                  it IS the ns this form runs in; teaching-only would
;;                  leave the silent nil in place).
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
   current ns symbol, returns nil (no intercept — eval normally) or one of

     {:seon.eval/parity :error :seon.error/message <teaching string>}
     {:seon.eval/parity :value :seon.eval/value    <substituted value>}

   Pure string check on the TRIMMED whole form — embedded uses (e.g.
   `*1` inside a larger form, or `(do *ns*)` wrapping) are NOT
   intercepted; they fail or silently nil out on their own (known
   parity boundary) and the taught replacement covers them too."
  {:malli/schema [:=> [:catn [::source :any] [::current-ns :any]] :any]}
  [source current-ns]
  (let [s (str/trim (or source ""))]
    (cond
      (re-find #"^\(in-ns[\s)]" s)
      (let [target (second (re-find #"^\(in-ns\s+'?([^\s\)]+)" s))
            alias  (when target (home-ns-alias-for-ns target))]
        {:seon.eval/parity :error
         :seon.error/message
         (str "in-ns is not available — and to CALL a verb you do NOT need to "
              "switch namespace. "
              (if alias
                (str "Verbs in " target " are reachable from your home ns as `"
                     alias "/<verb>` (e.g. `" alias "/plan!`) — use that. ")
                "Reach another ns's verbs through their home-ns alias (e.g. `plan/plan!`, `message/user`). ")
              "Stay in your home ns so your aliases (" (home-ns-alias-names)
              ") and refers (" (home-ns-refer-names) ") keep resolving. "
              "Only `(ns " (or target "the.target.ns") ")`-switch to DEFINE a "
              "fn IN that ns — and that switch REPLACES your home aliases until "
              "you switch back.")})

      (= s "*ns*")
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
   same eval → auto-await → stash → detect-and-tee → record →
   auto-instrument → auto-test-run pipeline. Behavior-preserving.

   Mutates the caller's fold volatiles in place (a transient impl
   detail inside one `eval-batch!` invocation, not shared state):
   advances `current-ns` on a successful `(ns …)` switch, increments
   `n-ok`/`n-fail`. Records failed-def provenance into `failed-defs`
   (A.4): a `(def …)`/`(defn …)` whose eval returns `ok? false` adds its
   target symbol so a LATER reference escalates instead of reading nil.

   Map keys:
     ::compile-state   — the bootstrap compile-state.
     ::id-of-eval         — pre-minted id for this entry's :seon.eval row.
     :seon.agent.turn/id-of-turn         — owning turn id (component parent).
     ::current-ns      — volatile<symbol>, the fold accumulator ns.
     ::n-ok ::n-fail    — volatile<int> counters.
     ::failed-defs     — volatile<set> of failed-def symbols this batch.
     ::outer-test-run? — skip auto-test-run when already inside one.
     ::narration       — narration to record (repaired forms prepend the
                        repair note here so the diff is always visible).
     ::source          — the source string to eval (repaired or original)."
  [{::keys [compile-state current-ns n-ok n-fail
            failed-defs outer-test-run? narration source]
    eval-id ::id-of-eval
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
                                 "the def first, then re-run this form.")}}]
        (await (record-eval! {::id-of-eval     eval-id
                              :seon.agent.turn/id-of-turn     turn-id
                              ::at          at
                              ::duration-ms 0
                              ::narration   narration
                              ::source      source
                              ::ending-ns          @current-ns
                              ::result      result}))
        (vswap! n-fail inc))
      (let [;; Snapshot analyzer + schema registry BEFORE eval
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
            ;; the live Promise is stashed at result/<id> (see stash site
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
            ;; resolve the handle; the Promise stays live for the later stash.
            pending?        (some? pending-promise)
            result
            (cond
              (not (::ok? raw-result)) raw-result
              pending?               {::ok? true
                                      ::value (pending-placeholder eval-id)
                                      ::ending-ns (::ending-ns raw-result)}
              (::ok? awaited)        {::ok? true
                                      ::value (::value awaited)
                                      ::ending-ns (::ending-ns raw-result)}
              :else                  awaited)
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
          ;; self-tee already DEFERRED its DB write (eval-id in scope), so
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
        ;; Live-value stash — direct js/Reflect.set on globalThis,
        ;; no eval-str round-trip (opaque values like datahike DB
        ;; tagged literals don't break the stash). Backs the `result/<id>`
        ;; value var AND the internal `lookup-result` reader.
        ;;
        ;; PENDING case (auto-await timeout / `defer`): stash the live
        ;; PROMISE handle (`pending-promise`), NOT the placeholder that was
        ;; recorded as the displayed value — so a later bare `result/<id>`
        ;; returns the Promise and the eval-batch auto-await resolves it to
        ;; data. The normal case stashes the resolved value.
        ;; `(if pending? pending-promise …)`, NOT `(or pending-promise …)`:
        ;; `or` expands to `(if pending-promise pending-promise …)`, putting
        ;; the Promise in the TEST position, which a CLJS `^:async` fn
        ;; AUTO-AWAITS — that would resolve the handle (and block) instead of
        ;; stashing it. A boolean test keeps the Promise unawaited; it reaches
        ;; the stash as a live handle (a fn arg never awaits).
        (when (::ok? result)
          (let [stash-val (if pending? pending-promise (::value result))]
            (stash-result-raw! eval-id stash-val)
            ;; transcript-redesign-2026-06-18: bind the value as the plain
            ;; var `result/<id>` (globalThis + analyzer def) so the agent
            ;; references it directly — the SOLE value-reuse surface. Failed
            ;; evals bind nothing — no value to retrieve.
            (bind-result-var! compile-state eval-id stash-val)
            ;; PENDING self-heal: the stash above holds a RAW js/Promise, and
            ;; only a BARE `result/<id>` reference auto-awaits it — any IN-FORM
            ;; use ((first result/<id>), (group-by k result/<id>), (let [xs
            ;; result/<id>] …)) operates on the un-awaited Promise and returns
            ;; garbage. Re-stash + re-bind the RESOLVED value the instant the
            ;; Promise settles, so EVERY reference (bare or in-form) reads real
            ;; data. errors-as-values: a rejected Promise no-ops (the placeholder
            ;; stays honest) — never throws into the eval loop.
            (when pending?
              (-> pending-promise
                  (.then (fn [v]
                           (stash-result-raw! eval-id v)
                           (bind-result-var! compile-state eval-id v)))
                  (.catch (fn [_] nil))))))
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
              ;; is the live db value here (same as `ns-requires-tx`).
              tee-entities
              (let [fn-syms (->> tee-entities
                                 (keep #(when (map? %) (:seon.fn/sym %)))
                                 vec)]
                (if (and db/*conn* (seq fn-syms))
                  (reject-core-overrides
                    (vec tee-entities)
                    (core-origin-fn-syms @db/*conn* fn-syms))
                  tee-entities))
              ;; Capture `:seon.ns/requires` for the ENDING ns on
              ;; EVERY successful eval — not only `(ns …)` forms — so
              ;; a re-eval'd ns form or a bare `(require '[x])` keeps
              ;; the dep-edge index current (the one fix that unblocks
              ;; DB-layer load; db-is-the-running-system PRD). Skip the
              ;; transient eval-scaffolding nses (`cljs.user` /
              ;; `seon.dynamic`) so we never mint a `:seon.ns` row for
              ;; them. Diff-upsert against the live db value so the
              ;; cardinality-many set tracks EXACTLY (add + retract);
              ;; rides in record-eval!'s atomic tee tx.
              ending-ns (when (::ok? result) @current-ns)
              req-tx    (when (and ending-ns
                                   (symbol? ending-ns)
                                   (not (contains? transient-ns-syms
                                                   ending-ns))
                                   db/*conn*)
                          ;; Flat dep-edge set + the reified edges
                          ;; (alias/refer facts) — the M4 structural
                          ;; store; SAME gating, one atomic tee tx.
                          (into (ns-requires-tx
                                  @db/*conn*
                                  (keyword (str ending-ns))
                                  (analyzer-info/ns-requires
                                    compile-state ending-ns))
                                (ns-require-edges-tx
                                  @db/*conn*
                                  (keyword (str ending-ns))
                                  (analyzer-info/ns-require-edges
                                    compile-state ending-ns))))
              ;; Declared read-set diff (C28) for every teed :seon.fn
              ;; row — additions ride an identity upsert, stale
              ;; keywords are retracted (a plain cardinality-many
              ;; upsert would accumulate forever).
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
              tee (vec (concat tee-entities req-tx read-attr-tx))]
          ;; Durable record — always. :seon.eval/ns is the
          ;; post-update accumulator (ending ns on success;
          ;; unchanged ns on failure). Tee rides in the same tx.
          (await (record-eval! {::id-of-eval      eval-id
                                :seon.agent.turn/id-of-turn      turn-id
                                ::at           at
                                ::duration-ms  duration-ms
                                ::narration    narration
                                ::source       source
                                ::ending-ns           @current-ns
                                ::result       result
                                ::output       output
                                ::tee          tee}))
          ;; Phase 3 (mvp-completion-plan 2026-05-27) —
          ;; auto-instrument any newly-defined fn whose
          ;; `:malli/schema` parsed cleanly. Runs AFTER the tee
          ;; tx so the `:seon.fn` row is durable before we
          ;; mutate the live var. Best-effort: a thrown
          ;; instrument! aborts only this fn, not the batch.
          (when (::ok? result)
            (try
              (instrument-tee-fns!
                (collect-instrument-targets compile-state defs-before
                                            source @current-ns))
              (catch :default e
                ;; OUR instrumentation machinery (mi/instrument! over a
                ;; newly-tee'd fn whose schema already parsed) throwing is a
                ;; core defect (:core) — record it; best-effort stays: only
                ;; this fn's instrument aborts, the batch continues.
                (error/record! {:seon.error/raw e :seon.error/fault :core})))
            ;; Phase 4 (mvp-completion-plan 2026-05-27) —
            ;; auto-test-run. After the tee tx, any new
            ;; `:seon.test` rows + any existing `:seon.test`
            ;; rows whose source mentions a newly-tee'd fn-sym
            ;; are re-run. Wrapped in :origin :test-run so the
            ;; loop guard below short-circuits if a test body
            ;; itself calls `eval-batch!`. Best-effort: thrown
            ;; runner errors don't abort the batch.
            (when-not outer-test-run?
              (let [targets (collect-auto-test-targets
                              compile-state defs-before source @current-ns)]
                (when (seq targets)
                  (try
                    (await
                      (db/with-tx-context
                        {::db/origin :test-run}
                        (fn ^:async run-auto-tests! []
                          (await (test-runner/run!
                                   {:seon.test.runner/vars    (vec targets)
                                    :seon.test.runner/record? true
                                    :seon.test.runner/trigger
                                    :seon.test.runner/on-fn-redef})))))
                    (catch :default e
                      ;; The test RUNNER escaping (individual test failures
                      ;; are captured as :seon.test data upstream) is a core
                      ;; defect (:core) — record it; best-effort stays: the
                      ;; batch continues.
                      (error/record! {:seon.error/raw e :seon.error/fault :core}))))))))
        (if (::ok? result)
          (vswap! n-ok   inc)
          (vswap! n-fail inc))))))

(defn- ^:async dispatch-eval-entry!
  "Dispatch ONE non-`:read` parsed entry through the per-form mechanism:
   comment-only → REPL-parity intercept (`in-ns`/`*ns*`) → normal
   `eval-form-entry!`. The SINGLE per-entry mechanism shared by
   `eval-batch!`'s main loop AND its parinfer-repair sub-loop, so a
   REPAIRED form is handled IDENTICALLY to a normal one — same parity
   teaching, same comment recording, same stash / tee / result-var
   binding. Before this, the repair sub-loop called `eval-form-entry!`
   directly, so a repaired `(in-ns 'foo` (a plausible missing-paren the
   repair fixes) bypassed the parity teaching the main loop gives, and a
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
    eval-id ::id-of-eval
    turn-id :seon.agent.turn/id-of-turn}]
  (let [source (:seon.repl/source entry)]
    (cond
      ;; Comment-only entry — no source to eval. Record a comment-only row
      ;; (blank source, ok? true) so trailing `;;` thinking renders in the
      ;; transcript and is never lost. Not counted in n-ok/n-fail.
      (= :comment (:seon.repl/kind entry))
      (await (record-eval! {::id-of-eval     eval-id
                            :seon.agent.turn/id-of-turn     turn-id
                            ::at          (js/Date.)
                            ::duration-ms 0
                            ::narration   narration
                            ::source      ""
                            ::ending-ns          @current-ns
                            ::result      {::ok? true ::value nil}}))

      ;; REPL-parity intercept (fix d) — in-ns / *ns* get a legible
      ;; translation INSTEAD of an opaque error or a silent nil. No eval
      ;; runs; the record is the teaching.
      (some? (parity-intercept source @current-ns))
      (let [pc     (parity-intercept source @current-ns)
            result (if (= :error (:seon.eval/parity pc))
                     {::ok? false
                      :seon/error {:seon.error/kind    :seon.eval/repl-parity
                                   :seon.error/message (:seon.error/message pc)}}
                     {::ok? true ::value (:seon.eval/value pc)})]
        (when (::ok? result)
          (stash-result-raw! eval-id (::value result))
          (bind-result-var! compile-state eval-id (::value result)))
        (await (record-eval! {::id-of-eval     eval-id
                              :seon.agent.turn/id-of-turn     turn-id
                              ::at          (js/Date.)
                              ::duration-ms 0
                              ::narration   narration
                              ::source      source
                              ::ending-ns          @current-ns
                              ::result      result}))
        (if (::ok? result)
          (vswap! n-ok   inc)
          (vswap! n-fail inc)))

      ;; Prose-in-parens demotion (#88) — a `(…)` that is English prose, not
      ;; code (undefined bare head + ≥2 undefined bare words, no qualified or
      ;; var-resolving symbol anywhere). Eval'ing it throws "not defined" and
      ;; inflates the eval-error rate; DEMOTE to a prose row instead: record
      ;; ok? with the text preserved + a note, never eval, count as NEITHER
      ;; n-ok nor n-fail (exactly like a `;` comment — it ran nothing).
      (prose-paren? compile-state @current-ns source)
      (await (record-eval!
               {::id-of-eval     eval-id
                :seon.agent.turn/id-of-turn     turn-id
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
                ::id-of-eval         eval-id
                :seon.agent.turn/id-of-turn         turn-id
                ::current-ns      current-ns
                ::n-ok            n-ok
                ::n-fail          n-fail
                ::failed-defs     failed-defs
                ::outer-test-run? outer-test-run?
                ::narration       narration
                ::source          source})))))

(defn ^:async eval-batch!
  "Execute a sequence of parsed entries as a REPL batch.

   Partial-failure: every entry gets its own try + record + stash; entry
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
     4. On success: advance accumulator to (::ending-ns raw-result); stash
        the live value in globalThis under the eval-id kw.
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

   Per-form work is wrapped in `(db/with-tx-context {…} f)` so every
   transact inside auto-tags with the causality bundle (agent-id +
   eval-id + origin). Callers that establish a wider scope first
   (the turn runner adding turn-id) get those keys layered in via
   with-tx-context's merge.

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
                     per-form): a run's own lifecycle verb (wait/complete/
                     terminate) retracts the pointer MID-batch, and that
                     verb's eval must still record — the start-fence has
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
   surface to the agent's warnings tile."
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
        ;; Phase 4 (mvp-completion-plan 2026-05-27): capture origin
        ;; BEFORE the per-entry `with-tx-context` overwrites it with
        ;; `:agent`. If an outer scope (an auto-test-run's
        ;; `:origin :test-run` wrapper around a test body that itself
        ;; calls `eval-batch!`) already established `:test-run`, the
        ;; inner batch must skip auto-test-run to avoid recursion.
        outer-test-run? (= :test-run (::db/origin (db/current-tx-context)))]
    ;; Fence lost ⇒ iterate over nil (zero entries) — the batch is skipped, the
    ;; volatiles stay at their empty seed, and the return below flags :fenced?.
    (doseq [entry (when-not fence-lost? parsed)]
      (let [eval-id    (db/new-id!)
            tx-context {:seon.db/agent-id agent-id
                        :seon.db/eval-id  eval-id
                        :seon.db/origin   :agent}]
        (await
          (db/with-tx-context tx-context
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
                      rep    (repair/repair-source
                               {:seon.repair/source (:seon.repl/source entry)
                                :seon.repair/reads? reads?})]
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
                                ;; A fresh eval-id per repaired form after the
                                ;; first (the first reuses this entry's id).
                                eid  (if first? eval-id (db/new-id!))]
                            (await
                              (db/with-tx-context
                                {:seon.db/agent-id agent-id
                                 :seon.db/eval-id  eid
                                 :seon.db/origin   :agent}
                                (fn ^:async run-repaired! []
                                  (await (dispatch-eval-entry!
                                           {::compile-state   compile-state
                                            ::id-of-eval         eid
                                            :seon.agent.turn/id-of-turn         turn-id
                                            ::current-ns      current-ns
                                            ::n-ok            n-ok
                                            ::n-fail          n-fail
                                            ::failed-defs     failed-defs
                                            ::outer-test-run? outer-test-run?
                                            ::entry           e
                                            ::narration       narr})))))
                            (when-not first? (vswap! eids conj eid))
                            (recur (rest es) false)))))
                    ;; Not repairable → sharpened read error (A.3).
                    (do
                      (await (record-eval!
                               {::id-of-eval     eval-id
                                :seon.agent.turn/id-of-turn     turn-id
                                ::at          (js/Date.)
                                ::duration-ms 0
                                ::narration   (:seon.repl/narration entry)
                                ::source      (:seon.repl/source entry)
                                ::ending-ns          @current-ns
                                ::result      {::ok? false
                                              :seon/error {:seon.error/kind    :read
                                                           :seon.error/message
                                                           (read-error-message
                                                             (-> entry :seon/error
                                                                 :seon.error/message)
                                                             (:seon.repl/source entry))}}}))
                      (vswap! n-fail inc))))

                ;; Every non-`:read` entry — comment / parity-intercept /
                ;; normal — flows through the ONE per-entry mechanism, the
                ;; exact same `dispatch-eval-entry!` the repair sub-loop
                ;; above calls. One mechanism, no parallel path.
                :else
                (await (dispatch-eval-entry!
                         {::compile-state   compile-state
                          ::id-of-eval         eval-id
                          :seon.agent.turn/id-of-turn         turn-id
                          ::current-ns      current-ns
                          ::n-ok            n-ok
                          ::n-fail          n-fail
                          ::failed-defs     failed-defs
                          ::outer-test-run? outer-test-run?
                          ::entry           entry
                          ::narration       (:seon.repl/narration entry)}))))))
        (vswap! eids conj eval-id)))
    (cond-> {:seon.eval/ids    @eids
             :seon.eval/n-ok   @n-ok
             :seon.eval/n-fail @n-fail}
      fence-lost? (assoc :seon.eval/fenced? true))))
