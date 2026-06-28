(ns seon.eval
  "Agent eval surface. SAFE BY DEFAULT — `eval` returns
   {:ok true :value v} or {:ok false :error <error-map>}. A throw,
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
            [seon.analyzer-info :as analyzer-info]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.instrument :as einstrument]
            [seon.instrument :as instrument]
            [seon.platform :as platform]
            [seon.render.value :as value]
            [seon.repair :as repair]
            [seon.repl.internal :as internal]
            [seon.schema :as schema]
            [seon.test.runner :as test-runner]))

(defn- env-int
  "Read `env-name` from `process.env` as a positive int, or `default`
   when unset/blank/non-numeric. The ONE knob reader for this ns's
   SEON_* caps — every clip/limit below is overridable this way."
  [env-name default]
  (let [v (some-> (platform/env-val env-name) js/parseInt)]
    (if (and (number? v) (not (js/isNaN v)) (pos? v)) v default)))

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
  "Override the default wall-clock timeout for the form's auto-awaited
   return value. Use when a form does a slow async op that legitimately
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

;; Identity-checked marker returned by `race-timeout` when the timer
;; wins. A fresh JS object so `identical?` distinguishes it from any
;; resolved eval value.
(defonce ^:private timeout-sentinel #js {:_seon_eval_timeout true})

(defn ^:async ^:private race-timeout
  "Race `inner` (a Promise) against a wall-clock timer of `ms`. If
   `inner` settles first, returns its resolved value. If the timer
   fires first, returns `timeout-sentinel`. Even when the timer wins,
   the underlying eval keeps running — JS has no preemptive
   cancellation. Caller MUST identity-check the sentinel."
  [inner ms]
  (let [timer (js/Promise.
                (fn [resolve _]
                  (js/setTimeout (fn [] (resolve timeout-sentinel)) ms)))]
    (await (js/Promise.race #js [inner timer]))))

;; ============================================================
;; Bootstrap init — load cljs.core + cljs.core$macros from the
;; :bootstrap shadow build into a fresh compile-state. ^:async so
;; callers can `(await ...)` it from straight-line agent code.
;; ============================================================

(defn- bootstrap-cache-files
  "Enumerate `<bootstrap>/ana/*.transit.json` files. Returns a vector
   of `[ns-sym path]` pairs. cljs.core + cljs.core$macros are sorted
   first so they land in the analyzer state before anything that
   references them — order doesn't strictly matter (load-analysis-
   cache! is just a swap), but cosmetic ordering helps when debugging
   the @compile-state map."
  [bootstrap-path]
  (let [fs       (js/require "fs")
        path-mod (js/require "path")
        ana-dir  (.resolve path-mod bootstrap-path "ana")
        names    (.readdirSync fs ana-dir)
        suffix   ".transit.json"]
    (->> (array-seq names)
         (filter #(str/ends-with? % suffix))
         (map (fn [filename]
                (let [ns-name (subs filename 0 (- (count filename) (count suffix)))]
                  [(symbol ns-name) (.resolve path-mod ana-dir filename)])))
         (sort-by (fn [[ns-sym _]]
                    (case (str ns-sym)
                      "cljs.core"        0
                      "cljs.core$macros" 1
                      2))))))

(defn- load-all-analysis-caches!
  "Read every `*.transit.json` under `<bootstrap>/ana/` and call
   `cljs.js/load-analysis-cache!` on each. Solves a class of fragility:
   any namespace listed in `shadow-cljs.edn :bootstrap :entries`
   automatically lands in the analyzer state, so agent code can
   `(require ...)` and reference it from inside cljs.js/eval-str
   without manual maintenance of a load-list.

   Why this is needed: shadow's `boot/init` only auto-loads the
   analyzer cache for entries whose `[:cljs.analyzer/namespaces ns
   :name]` is nil (`bootstrap/node.cljs:104`). `(cljs/empty-state)`
   calls `(dump-core)` which leaves stubs with `:name` set for many
   nses, so the filter short-circuits. Loading unconditionally
   here is the robust answer."
  [state bootstrap-path]
  (let [fs (js/require "fs")]
    (doseq [[ns-sym path] (bootstrap-cache-files bootstrap-path)]
      (let [txt  (.readFileSync fs path "utf8")
            data (boot/transit-read txt)]
        (cljs/load-analysis-cache! state ns-sym data)))))

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
  "Idempotent: installs the per-fiber dispatcher on
   `ana/*cljs-warning-handlers*` once per init-version. Called from
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

(defn ^:async init-bootstrap!
  "Initialize a fresh compile-state from out/bootstrap/. Returns the
   compile-state, ready for `eval` / `eval-batch!`. Stores cljs.core
   on globalThis (via goog.globalEval inside shadow's loader); without
   that, find-ns-obj fails on the first macro form and eval-str
   throws TypeError on findInternedVar.

   Force-populates the analyzer caches for EVERY namespace shadow
   emitted into the bootstrap output — see `load-all-analysis-caches!`
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
    (load-all-analysis-caches! state bootstrap-path)
    (when-not (and (some? (.-cljs js/global))
                   (some? (.-core (.-cljs js/global))))
      (throw (js/Error.
               "bootstrap loader did not put cljs.core on globalThis")))
    ;; Install per-fiber warning dispatcher (Phase 0 item 2).
    ;; Idempotent + version-stamped against init-version so hot-reload
    ;; reinstalls the closure.
    (install-warning-dispatcher!)
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
  "The live JS namespace object for `ns-name` (a dotted string like
   \"seon.db\" / \"acme.helpers\") on `js/globalThis`, or nil. THE ONE munge
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
  "Resolve a fully-qualified symbol to its runtime value, or nil if
   unresolvable. The CLJS-bootstrap equivalent of JVM's
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
   `load-all-analysis-caches!`, `init-bootstrap!`). Render and any
   other consumer call `(eval/lookup-value sym)` rather than each
   maintaining its own copy."
  {:malli/schema [:=> [:cat :any] [:maybe :any]]}
  [sym]
  (when (qualified-symbol? sym)
    (when-let [ns-obj (lookup-ns-object (namespace sym))]
      (gobj/get ns-obj (cljs.core/munge (name sym))))))

(defn ns-fn-members
  "Enumerate the COMPILED function members of namespace `ns-name` (a string
   like \"acme.helpers\") from its LIVE ns object on `js/globalThis`, as
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
    (catch :default _ {})))

(defn ns-data-members
  "Enumerate the COMPILED NON-function members of namespace `ns-name` (a string
   like \"acme.helpers\") from its LIVE ns object on `js/globalThis`, as
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
    (catch :default _ {})))

(defn- truly-undeclared?
  "Decide whether an `:undeclared-var` / `:undeclared-ns` analyzer
   warning is REAL (the symbol resolves nowhere) vs. a benign
   false-positive (bundled into the host runtime but the analyzer
   didn't see it because we run with `:analyze-deps? false`).

   `:analyze-deps? false` is load-bearing — see the docstring on
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
  "TRUE when a stored `:seon.schema/source` is an eval-able `(…)`
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
  "TRUE when `ns-sym` (a symbol) has a `:seon.ns/name` row in `db` — the
   discriminator the DB branch of [[guarded-load]] uses to decide a
   missing ns is agent-authored (loadable from the DB) rather than
   genuinely absent. `db` is a datahike db value (third-party boundary)."
  {:malli/schema [:=> [:catn [::db :any] [::ns-sym :symbol]] :boolean]}
  [db ns-sym]
  (boolean (seq (db/query '[:find ?e
                            :in $ ?ns
                            :where [?e :seon.ns/name ?ns]]
                          db (keyword ns-sym)))))

(defn reconstitute-ns-source
  "One loadable source STRING for the agent-authored namespace `ns-kw`,
   read from the DB-layer rows (db-is-the-running-system PRD, shape A):

     `:seon.ns/source`  — the agent's `(ns … (:require [x :as y] …))`
        form VERBATIM. We use the stored ns form (not a rebuilt one)
        because it carries the `:as` aliases an aliased ref like `b/bv`
        needs — a rebuilt form without `:as b` breaks with `b is not
        defined`.
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
        tests   (member :seon.test/source   :seon.test/ns)]
    (->> (concat [ns-src] fns schemas tests)
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
    (when-not (or (= 'cljs.user ns-sym)
                  (and entry (:name entry)))
      (await
        (js/Promise.
          (fn [resolve _reject]
            (try
              (cljs/eval-str compile-state (str "(ns " ns-sym ")") nil
                {:eval cljs/js-eval :ns 'cljs.user :context :statement}
                (fn [_] (resolve nil)))
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
   with SEON_RESULT_VARS_CAP."
  (env-int "SEON_RESULT_VARS_CAP" 200))

;; Insertion-ordered vector of bound result ids (oldest first). Process-
;; shared, defonce'd so it survives hot-reload of `seon.eval`.
(defonce ^:private !result-var-ids (atom []))

(defn result-var-ref?
  "TRUE when `form-str` is a single bare `result/<id>` symbol reference —
   the agent reading a prior eval's value var. Used to (a) eval such a
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
             (catch :default _ false))))))

(defn result-miss-message
  "The graceful-miss VALUE for a `result/<id>` reference whose var is no
   longer live (pruned past the session cap, or from a PRIOR SESSION —
   the process that held it is gone). Errors-are-values: a miss reads
   this string instead of throwing a raw `:undeclared-var`."
  {:malli/schema [:=> [:catn [::ref-sym :string]] :string]}
  [ref-sym]
  (str ref-sym " isn't live (a prior session, or pruned past the last "
       result-vars-cap " results) — re-run its form to recompute it. "
       "Only recent results stay referenceable as `result/<id>` vars."))

(defn ^:async ^:private raw-eval
  "Internal — returns a Promise that resolves with {:value v :ns ns}
   or rejects with the error. The public `eval` catches both.

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
            (cljs/eval-str compile-state form-str 'seon.dynamic
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
                  (resolve {:value (result-miss-message (str/trim (str form-str)))
                            :ns    ns-sym})

                  (some (partial truly-undeclared? compile-state) @warnings)
                  (let [{:keys [prefix suffix] :as w}
                        (first (filter (partial truly-undeclared? compile-state)
                                       @warnings))
                        sym  (str prefix "/" suffix)
                        kind (:seon.eval/warning-type w)
                        ;; Crystal-clear: name the EXACT symbol + the EXACT
                        ;; next action. A FRESH, confused LLM must know this
                        ;; ran NOTHING and what to do about it.
                        msg  (if (= kind :undeclared-ns)
                               (str "`" sym "` — that NAMESPACE is not loaded "
                                    "(typo, or you haven't required it). This "
                                    "form ran NOTHING. Require it, or fix the "
                                    "name, then re-eval.")
                               (str "`" sym "` is not defined — you have not "
                                    "defined it (or its defn failed earlier, "
                                    "or it's a typo). This form ran NOTHING. "
                                    "Define it first, then this runs."))]
                    (reject
                      (ex-info msg
                               {:seon.error/kind :compile
                                :seon.eval/warning-type kind
                                :seon.eval/undeclared sym
                                :seon.eval/warning (dissoc w :seon.eval/warning-type)})))

                  error
                  (reject error)

                  :else
                  (resolve {:value value :ns ns}))))))))))

(defn ^:async eval
  "Evaluate a string of CLJS in the agent's persistent compile-state.
   Returns:
     {:ok true  :value v :ns ns}              on success
     {:ok false :error <seon.error/->map>}    on any failure
   Never throws; never rejects.

   Opts (all optional):
     :ns            target namespace (default `cljs.user`). The
                    returned `:ns` is the ENDING ns — `(ns other)`
                    forms switch it. Callers that want REPL-style
                    ns-tracking feed `:ns` from one call into the
                    next call's `:ns` arg.
     :analyze-deps? whether cljs.js should recursively analyze refs
                    in the form (default `false`). The bootstrap
                    bundle only contains `cljs.core`, so any form
                    calling `seon.db/*` or other non-bundled nses
                    MUST run with this off — otherwise the analyzer
                    dies on `ns seon.db not available`. With it off,
                    the analyzer emits :undeclared-var warnings but
                    still emits JS that resolves at runtime via the
                    already-loaded globalThis vars (the `:client`
                    bundle's emission).
     :timeout-ms    override the default `@!timeout-ms` per-call.

   For setup forms that need cljs.core's macro refers wired up via
   `(ns …)` analysis, pass `:analyze-deps? true` explicitly.

   A form that hangs on a never-resolving Promise returns
     {:ok false :error {:seon.error/message \"eval timed out after Nms\" …}}
   The underlying form keeps running — see `race-timeout` docstring."
  {:malli/schema
   [:function
    [:=> [:catn [::compile-state :any] [::form-str :any]] :any]
    [:=> [:catn [::compile-state :any] [::form-str :any] [::opts :any]] :any]]}
  ([compile-state form-str]
   (eval compile-state form-str nil))
  ([compile-state form-str {:keys [ns analyze-deps? timeout-ms]
                            :or   {ns            'cljs.user
                                   analyze-deps? false}}]
   (try
     (let [ms      (or timeout-ms @!timeout-ms)
           raced   (await (race-timeout
                            (raw-eval compile-state form-str ns analyze-deps?)
                            ms))]
       (if (identical? raced timeout-sentinel)
         {:ok false
          :error (error/->map
                   (js/Error.
                     (str "eval timed out after " ms "ms (form still "
                          "running in background; JS has no preemption — "
                          "Phase 2 worker_thread or Phase 3 wasmtime "
                          "needed for hard cancellation)")))}
         {:ok true :value (:value raced) :ns (:ns raced)}))
     (catch :default e
       {:ok false :error (error/->map e)}))))

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
      (js/console.warn "[seon.eval/stash-result-raw!] failed for"
                       (pr-str eval-id) "—"
                       (error/->message e)))))

(defn lookup-result
  "The live value of a prior eval, keyed by the id on its value line
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
      (catch :default _ nil))
    (try
      (swap! compile-state update-in
             [:cljs.analyzer/namespaces result-ns-sym :defs]
             dissoc (symbol id))
      (catch :default _ nil))))

(defn bind-result-var!
  "Bind a successful eval's value `v` as the plain var `result/<id>`:
   set `globalThis.result.<munged-id>` and register `<id>` in the
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
      (js/console.warn "[seon.eval/bind-result-var!] failed for"
                       (pr-str id) "—" (error/->message e))))
  nil)

(defn ^:async setup-agent-ns!
  "Create + initialize the agent's home namespace. Returns the agent-ns
   symbol (for convenience — same as the input). Idempotent.

   The home ns is set up with the verb + data aliases the context
   teaches, so the agent's reflexive `(message/user …)` /
   `(message/agent …)` / `(wait …)` / `(complete …)` /
   `(terminate …)` forms — AND the `(schema/register! …)`
   / `(db/query …)` / `(db/transact! …)` / `::db/…` data forms the
   namespaces section + fn docstrings teach in their SHORT-aliased shape —
   all resolve without fully-qualifying:

     (ns <home>
       (:require [seon.agent.message :as message]
                 [seon.agent :as agent]
                 [seon.agent.lifecycle :refer [wait complete pause resume terminate]]
                 [seon.schema :as schema]
                 [seon.db :as db]
                 [seon.agent.todo :as todo]))

   The home ns defs NOTHING beyond these requires: a `result` def would
   shadow the reserved `result` NAMESPACE that holds the `result/<id>`
   value vars, so the ns stays clean and `result/<id>` resolves
   top-level (a fresh agent ns reads `result/<id>` with no alias).

   For the agent's own id, use `(seon.db/current-agent-id)` — the core
   provides it via the ALS dynvar bound at turn entry.

   `:current-ns` is derived at read time from the latest :seon.eval/ns
   datom (seon.agent/current-ns + reactive-context principle).

   Uses `:analyze-deps? true` so the `(ns …)` form analyzes cljs.core's
   refer map and wires up implicit macro refers (defn, str, atom, etc.)
   for subsequent forms in the new ns.

   `seon.agent.lifecycle` / `seon.agent.message` are host-bundled, so a
   `:refer` against them produces a benign `:undeclared-var` warning that flips
   the eval `:ok` to false EVEN THOUGH the alias/refer map wires up and
   the vars resolve at runtime (live-proven). So we do NOT trust the
   eval `:ok`; we verify success by PROBING that the refer'd `complete`
   resolves to a fn in the home ns, and only throw when that probe fails.

   The home ns's runtime JS object is materialized by a BARE `(ns <home>)`
   prime BEFORE the require/refer form. Why (root-caused + LIVE-PROVEN
   2026-06-26): a `(defn foo)` in `my.agent.X` emits `my.agent.X.foo = …`,
   which assumes the nested object path already exists — the `:def` emit
   does NOT create it (cljs.compiler), and `goog.provide` is unreliable in
   self-host (cljs forces `goog.isProvided_`→false). Object creation rides
   on the ns-form's emit COMPLETING. But the host-bundled
   `:refer [wait complete …]` raises an `:undeclared-var` that ABORTS the
   emit before the object is provided — so the require/refer form alone
   wires the analyzer entry + refers but leaves NO runtime object, and the
   agent's first `(defn …)` writes into `undefined`. A bare `(ns <home>)`
   has no refer to abort on, so it provides the object cleanly; the
   subsequent require/refer form then layers the aliases on the existing
   object (its own emit still aborts, harmlessly — the object persists)."
  {:malli/schema
   [:=> [:catn [::compile-state :any] [::agent-ns-sym :any] [::agent-id :any]] :any]}
  [compile-state agent-ns-sym _agent-id]
  (let [setup-src
        (str "(ns " agent-ns-sym
             " (:require [seon.agent.message :as message]"
             " [seon.agent :as agent]"
             " [seon.agent.lifecycle :refer [wait complete pause resume terminate]]"
             " [seon.schema :as schema]"
             " [seon.db :as db]"
             " [seon.agent.todo :as todo]))")]
    ;; Materialize the home ns's runtime JS object FIRST via a bare ns
    ;; prime (no refer to abort the emit), so a later `(defn …)` has a path
    ;; to write into. The require/refer form below then wires the aliases.
    (await (eval compile-state (str "(ns " agent-ns-sym ")")
                 {:ns 'cljs.user :analyze-deps? true}))
    (await (eval compile-state setup-src
                 {:ns 'cljs.user :analyze-deps? true}))
    ;; The benign refer-warning makes the ns form report :ok false, so the
    ;; home ns is verified by probing the wiring directly: `complete` (a
    ;; refer'd verb) must resolve to a fn in the new ns.
    (let [probe (await (eval compile-state "(fn? complete)"
                             {:ns agent-ns-sym :analyze-deps? false}))]
      (when-not (and (:ok probe) (true? (:value probe)))
        (throw (ex-info "setup-agent-ns! failed — home ns verb aliases did not wire"
                        {:agent-ns agent-ns-sym
                         :probe    probe}))))
    agent-ns-sym))

;; ============================================================
;; eval-batch! — the REPL harness primitive. Takes parsed pairs from
;; seon.repl/parse-forms; evaluates each in the agent's compile-state
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

   Bounded by `@!timeout-ms` (default) OR the one-shot override left
   by [[budget]]. A Promise that never resolves returns
   `{:ok false :error <timeout>}` instead of wedging the agent loop.

   Returns {:ok true :value v} on resolution OR a non-Promise value;
           {:ok false :error <seon.error/->map>} on rejection or timeout."
  [v]
  (if (instance? js/Promise v)
    (try
      (let [override (let [m @!next-budget-ms]
                       (reset! !next-budget-ms nil)
                       m)
            ms       (or override @!timeout-ms)
            raced    (await (race-timeout v ms))]
        (if (identical? raced timeout-sentinel)
          {:ok false
           :error (error/->map
                    (js/Error.
                      (str "auto-await timed out after " ms "ms"
                           (when override " (explicit (budget) override)"))))}
          {:ok true :value raced}))
      (catch :default e
        {:ok false :error (error/->map e)}))
    (do
      ;; Even for non-Promise values, consume any pending budget so it
      ;; doesn't leak into the NEXT form's auto-await.
      (reset! !next-budget-ms nil)
      {:ok true :value v})))

;; ============================================================
;; Detect-and-tee (v1.md §2.2 + §7 / STATUS.md Phase B item 10)
;;
;; After a successful eval, snapshot the analyzer's :defs and the
;; schema registry's keyset before/after; every new def becomes a
;; :seon.fn entity, every new schema key becomes a :seon.schema entity.
;; An `(ns …)` form also yields a :seon.ns entity. These ride in the
;; same tx as the eval entity (via record-eval!'s :tee arg), sharing
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

(defn- collect-auto-test-targets
  "Phase 4 (mvp-completion-plan 2026-05-27): return the set of FQ test
   syms to run after a successful eval. Two sources:

   - Tests newly defined in THIS eval (a fresh `(deftest …)` form). The
     symbol comes from `defs-since` filtered via [[deftest-def?]].
   - Tests in the DB whose `:seon.test/source` mentions any fn newly
     defined in THIS eval. Substring match — v0 heuristic, see
     `seon.test.runner/tests-referring-to`.

   Result is a set so a deftest that also matches the substring scan
   (the test source mentions its own sym) only runs once."
  [compile-state defs-before]
  (let [new-defs    (analyzer-info/defs-since defs-before compile-state)
        new-tests   (for [{:keys [var-map]} new-defs
                          :when (deftest-def? var-map)]
                      (symbol (str (:name var-map))))
        new-fn-syms (for [{:keys [var-map]} new-defs
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
   wrapper, which would false-fail on the Promise return."
  [compile-state defs-before]
  (for [{:keys [ns sym var-map]} (analyzer-info/defs-since defs-before compile-state)
        :let [schema-form (:malli/schema (:meta var-map))
              async?      (boolean (:async (:meta var-map)))]
        ;; Opt-out (seon.instrument/skip-syms) is applied in register-target!.
        :when (and schema-form
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
   persistence gate fails closed: no row is created/replayed)."
  [source]
  (try
    (let [rdr (reader-types/string-push-back-reader (str source))]
      (loop [acc []]
        (let [f (tools-reader/read {:eof ::eof :read-cond :allow} rdr)]
          (if (= f ::eof) acc (recur (conj acc f))))))
    (catch :default _ nil)))

(defn defn-form?
  "Strict persistence gate (#7): TRUE iff `source` is exactly ONE
   top-level form whose head is `defn`/`defn-`. A bare `(def …)`,
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

(defn scratch-def-note
  "Reactive 'won't persist' note (#7), DERIVED from an eval's source —
   pure, no stored attr, re-computed every render so it FOLLOWS the
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
  "Sharpen a rewrite-clj read-error message (A.3) for an UNREPAIRABLE
   form. The form did not parse, so it DEFINED NOTHING — say so, name the
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
        instruction
        (str "This form did not parse, so it DEFINED NOTHING — do not "
             "call or wire anything that depended on it; it does not "
             "exist. Fix the delimiter and re-eval the whole form.")]
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
             "Do NOT call or wire anything that depended on this form — it "
             "does not exist. Fix the delimiter and re-eval the whole form.")))))

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
  [{:keys [compile-state defs-before schemas-before source at]}]
  (let [new-defs    (analyzer-info/defs-since defs-before compile-state)
        new-schemas (set/difference (schema/current-keys) schemas-before)
        fn-entities (for [{:keys [ns var-map]} new-defs
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
                          :let [{:keys [sym fn-var? arglists doc private? spec]}
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
        test-entities (for [{:keys [ns var-map]} new-defs
                            :let [{:keys [sym]} (analyzer-info/var-projection var-map)]
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
  "Diff-upsert tx ops so `:seon.ns/requires` for `ns-kw` becomes EXACTLY
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

;; Namespaces the requires-tee SKIPS — transient eval scaffolding, never
;; a real program-graph ns: `cljs.user` (REPL default home),
;; `seon.dynamic` (the `cljs.js/eval-str` target), and `result` (the
;; reserved ns holding the synthetic `result/<id>` value vars from
;; bind-result-var! — belt-and-suspenders alongside the
;; analyzer-info/defs-since `:seon.eval/result-var?` filter). A real
;; agent/core ns (`seon.*`, `my.*`, a data ns) gets a `:seon.ns` row;
;; these do not.
(def ^:private transient-ns-syms #{'cljs.user 'seon.dynamic 'result})

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
  "Of `syms` (FQ `:seon.fn/sym` strings), the subset whose CURRENT
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
  "Filter `tee-entities` for the override guard: drop any `:seon.fn` row
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
;; Detect-and-tee only covers AGENT evals (record-eval!'s :tee arg).
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
   SEON_STORE_EDN_CAP.

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
  (env-int "SEON_STORE_EDN_CAP" 16384))

(defn cap-edn
  "Truncate an already-stringified (pr-str'd) value to `store-edn-cap`,
   appending an elision marker reporting how many chars were dropped.
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

(defn- deepest-error-message
  "Walk a `seon.error/->map` map's `:seon.error/cause` chain and return
   the deepest non-blank, non-generic `:seon.error/message`. cljs.js wraps
   an agent throw so the TOP message is a useless wrapper (`\"ERROR\"`,
   `\"Could not eval …\"`); the real message (the malli/db failure string,
   the agent's `(throw (js/Error. \"boom\"))` text) lives a level or two
   down. Falls back to the top message. Bounded depth 6."
  [err]
  (loop [e err depth 0 best nil]
    (let [msg     (:seon.error/message e)
          useful? (and (string? msg)
                       (not (str/blank? msg))
                       (not (#{"ERROR" "Could not eval"} (str/trim msg))))
          best'   (if useful? msg best)]
      (if (or (nil? (:seon.error/cause e)) (>= depth 6))
        (or best' (:seon.error/message err) "")
        (recur (:seon.error/cause e) (inc depth) best')))))

(def ^:private known-error-kinds
  "Error kinds whose `:seon.error/message` is already crystal-clear
   guidance built by the thrower (db `:user-input`, compile, read,
   schema-validation). For these, the message stands alone — no runtime
   'errors are values' framing is added (it would be wrong: these are
   not thrown values the agent adapts to, they are defects to FIX)."
  #{:user-input :compile :read :seon.eval/repl-parity})

(defn render-error-string
  "Produce the CRYSTAL-CLEAR, edn-SAFE string persisted as
   `:seon.eval/error` for a failed eval — what the agent reads in the
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
  (let [msg  (deepest-error-message err)
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

(def result-body-render-cap
  "Char cap for the rendered `:seon.eval/result-edn` BODY of one eval —
   the clip applied to the projected/pr-str'd value string before it is
   persisted. Any value (a giant scalar, a wide map, a long string)
   clips here to a well-formed string that names `result/<id>` for the
   full live value. Override with SEON_RESULT_BODY_RENDER_CAP."
  (env-int "SEON_RESULT_BODY_RENDER_CAP" 16384))

(defn clip-result-body
  "Clip a rendered result-body STRING to `result-body-render-cap`,
   appending a one-line pointer to the full value's `result/<id>` live
   var. Under the cap → returned unchanged. Names the id so the agent
   always knows where the untruncated value lives. Pure; nil-safe."
  {:malli/schema [:=> [:catn [::eval-id :string] [::body :string]] :string]}
  [eval-id body]
  (let [s (str body)
        n (count s)]
    (if (> n result-body-render-cap)
      (str (subs s 0 result-body-render-cap)
           "\n; … +" (- n result-body-render-cap) " chars clipped; the full "
           "value is the live var result/" eval-id " — drill it with "
           "get-in/filter/subs.")
      s)))

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
  "READ-SIDE net for the value projection. A row written BEFORE the
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
      (pr-str (value/project-plain (reader/read-string s)))
      (catch :default _ s))
    s))

(defn render-result-edn
  "Stringify an eval's success VALUE for `:seon.eval/result-edn` — the
   agent-facing text.

   Delegates to `seon.render.value/render-ai`: a DEPTH- and BREADTH-bounded
   structure-revealing SKELETON of the value. Opaque runtime handles
   (datahike DB/Datom/Entity, records, raw JS objects, fns) become compact
   markers, wide/deep collections bound with elision markers (+ a shared
   key-set for homogeneous rows), and a partial view appends ONE trailing
   `; ‹partial view…›` hint naming `result/<id>`. The agent navigates the
   skeleton with ordinary Clojure (`get-in`/`filter`/`count`) against the
   full live value `result/<id>` — never reads its own work back as a
   `#datahike/DB {…}` blob.

   `clip-result-body` is the final char-cap backstop: `render-ai` is
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
      (catch :default _
        ;; Unprintable value — name where the live value lives instead of
        ;; a bare (str value) that could itself be a giant/opaque blob.
        (str "; <value could not be rendered as data; the live value is "
             "result/" eval-id ">")))))

(defn ^:async record-eval!
  "Transact one :seon.eval entity as a component child of its owning
   turn (per v1.md §2.1 — `:seon.agent.turn/evals` is component-many). The
   nested-map shorthand creates the eval inline; datahike's component
   semantics mean a one-pull on the turn returns its evals without
   needing a back-ref query.

   When `:tee` is non-empty, the detect-and-tee program-graph entities
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
   origin :agent`, plus whatever the caller layered above)."
  {:malli/schema [:=> [:catn [::record-request :map]] :any]}
  [{:keys [eval-id turn-id at narration source result duration-ms ns tee output]}]
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
                          :seon.eval/ok?         (boolean (:ok result))
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

                   ;; `render-result-edn` already clips the body to
                   ;; `result-body-render-cap` and names `result/<id>` for the
                   ;; full value. `cap-edn` (store-edn-cap) is the additional
                   ;; MEMORY-SAFETY backstop so the DB never holds a multi-MB
                   ;; blob even if the render cap is raised; a no-op when the
                   ;; render cap ≤ the store cap. The FULL value is in the
                   ;; globalThis live-result stash (set before this call).
                   (:ok result)
                   (assoc :seon.eval/result-edn
                          (cap-edn
                            (render-result-edn eval-id (:value result))))

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
                   (not (:ok result))
                   (assoc :seon.eval/error
                          (cap-edn
                            (try (render-error-string (:error result))
                                 (catch :default _ (str (:error result))))))

                   ;; Phase A item 8 — when the error carries a Malli
                   ;; instrumentation envelope (flattened into
                   ;; :seon.error/data by seon.error/->map), persist the
                   ;; envelope as `:seon.eval/error-data` (pr-str round-
                   ;; trip — see attr docstring). Renderers branch on
                   ;; this to produce the structured ;; ERROR block.
                   (and (not (:ok result))
                        (einstrument/instrument-error?
                          (some-> result :error :seon.error/data)))
                   (assoc :seon.eval/error-data
                          ;; Use the fn-stubbing serializer — envelope
                          ;; embeds Malli schemas whose forms contain
                          ;; unreadable #object[…] fn refs. See
                          ;; seon.error.instrument/pr-str-readable.
                          (einstrument/pr-str-readable
                            (-> result :error :seon.error/data))))
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
  "Form-level REPL-parity pre-check. Given a form's source string and the
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
      (let [target (second (re-find #"^\(in-ns\s+'?([^\s\)]+)" s))]
        {:seon.eval/parity :error
         :seon.error/message
         (str "in-ns is not available in this runtime — use (ns "
              (or target "the.target.ns") "), same effect: it switches "
              "your namespace and your prompt follows.")})

      (= s "*ns*")
      {:seon.eval/parity :value
       :seon.eval/value  current-ns})))

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
  (let [forms (read-all-forms source)]
    (->> forms
         (keep (fn [f]
                 (when (and (seq? f)
                            (contains? '#{def defn defn-} (first f))
                            (symbol? (second f)))
                   (second f))))
         set)))

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
     :compile-state   — the bootstrap compile-state.
     :eval-id         — pre-minted id for this entry's :seon.eval row.
     :turn-id         — owning turn id (component parent).
     :current-ns      — volatile<symbol>, the fold accumulator ns.
     :n-ok :n-fail    — volatile<int> counters.
     :failed-defs     — volatile<set> of failed-def symbols this batch.
     :outer-test-run? — skip auto-test-run when already inside one.
     :narration       — narration to record (repaired forms prepend the
                        repair note here so the diff is always visible).
     :source          — the source string to eval (repaired or original)."
  [{:keys [compile-state eval-id turn-id current-ns n-ok n-fail
           failed-defs outer-test-run? narration source]}]
  (let [at          (js/Date.)
        start-ms    (.now js/Date)
        ;; A.4 false-confidence guard: BEFORE eval, if this NON-defining
        ;; form references a symbol whose def failed earlier this batch,
        ;; escalate to an honest error instead of letting it read nil.
        stale-ref   (references-failed-def source @failed-defs)]
    (if stale-ref
      (let [result {:ok false
                    :error {:seon.error/kind :compile
                            :seon.error/message
                            (str "`" stale-ref "` does not exist — the def that "
                                 "would create it failed to evaluate earlier this "
                                 "turn (it defined NOTHING). A reference to it "
                                 "reads nil, NOT a usable value. Fix and re-eval "
                                 "the def first, then re-run this form.")}}]
        (await (record-eval! {:eval-id     eval-id
                              :turn-id     turn-id
                              :at          at
                              :duration-ms 0
                              :narration   narration
                              :source      source
                              :ns          @current-ns
                              :result      result}))
        (vswap! n-fail inc))
      (let [;; Snapshot analyzer + schema registry BEFORE eval
            ;; so detect-and-tee (v1.md §2.2 / Phase B item 10)
            ;; can diff after. Cheap reads — keyset extraction.
            defs-before    (analyzer-info/snapshot-defs compile-state)
            schemas-before (schema/current-keys)
            ;; (fix f) println/prn capture — a REPL shows print
            ;; output next to the result; *print-fn* routes to the
            ;; pod's stdout (logs/pod.log), invisible to the agent.
            ;; Capture for the span of eval + auto-await, persist
            ;; as :seon.eval/output. KNOWN LIMIT: prints from other
            ;; interleaved async work during this form's awaits land
            ;; here too (single-agent: non-issue; multi-agent needs
            ;; per-agent ALS print routing).
            !out               (volatile! "")
            prev-print-fn      *print-fn*
            prev-print-err-fn  *print-err-fn*
            _ (let [cap (fn [& xs] (vswap! !out str (apply str xs)))]
                (set! *print-fn* cap)
                (set! *print-err-fn* cap))
            raw-result  (await (eval compile-state source
                                     {:ns @current-ns
                                      :analyze-deps? false}))
            result
            (cond
              (not (:ok raw-result)) raw-result
              :else (let [r2 (await (maybe-await-value (:value raw-result)))]
                      (if (:ok r2)
                        {:ok true :value (:value r2) :ns (:ns raw-result)}
                        r2)))
            ;; Restore BEFORE any further awaits — record/tee/
            ;; auto-test prints belong to the pod log, not this
            ;; eval's record. (`eval`/`maybe-await-value` never
            ;; throw — A4 envelope — so this line always runs.)
            _ (do (set! *print-fn* prev-print-fn)
                  (set! *print-err-fn* prev-print-err-fn))
            output      @!out
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
        (when-not (:ok result)
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
        (when (and (:ok result) (:ns raw-result))
          (vreset! current-ns (:ns raw-result)))
        ;; A.4: a DEFINING form whose eval failed registers its target
        ;; symbol so a later reference escalates (see references-failed-def);
        ;; a DEFINING form that SUCCEEDS clears its symbol (a redefinition
        ;; that now works self-heals the guard).
        (let [def-syms (failed-def-syms source)]
          (when (seq def-syms)
            (if (:ok result)
              (vswap! failed-defs #(reduce disj % def-syms))
              (vswap! failed-defs into def-syms))))
        ;; Live-value stash — direct js/Reflect.set on globalThis,
        ;; no eval-str round-trip (opaque values like datahike DB
        ;; tagged literals don't break the stash). Backs the `result/<id>`
        ;; value var AND the internal `lookup-result` reader.
        (when (:ok result)
          (stash-result-raw! eval-id (:value result))
          ;; transcript-redesign-2026-06-18: bind the value as the plain
          ;; var `result/<id>` (globalThis + analyzer def) so the agent
          ;; references it directly — the SOLE value-reuse surface. Failed
          ;; evals bind nothing — no value to retrieve.
          (bind-result-var! compile-state eval-id (:value result)))
        ;; Detect-and-tee — only on success. Failed evals roll
        ;; back analyzer defs and never touch the schema registry,
        ;; so diff would be empty anyway; we still skip
        ;; explicitly to keep the contract obvious.
        (let [tee-entities (when (:ok result)
                             (build-tee-entities
                               {:compile-state  compile-state
                                :defs-before    defs-before
                                :schemas-before schemas-before
                                :source         source
                                :at             at}))
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
              ending-ns (when (:ok result) @current-ns)
              req-tx    (when (and ending-ns
                                   (symbol? ending-ns)
                                   (not (contains? transient-ns-syms
                                                   ending-ns))
                                   db/*conn*)
                          (ns-requires-tx
                            @db/*conn*
                            (keyword (str ending-ns))
                            (analyzer-info/ns-requires
                              compile-state ending-ns)))
              tee (vec (concat tee-entities req-tx))]
          ;; Durable record — always. :seon.eval/ns is the
          ;; post-update accumulator (ending ns on success;
          ;; unchanged ns on failure). Tee rides in the same tx.
          (await (record-eval! {:eval-id      eval-id
                                :turn-id      turn-id
                                :at           at
                                :duration-ms  duration-ms
                                :narration    narration
                                :source       source
                                :ns           @current-ns
                                :result       result
                                :output       output
                                :tee          tee}))
          ;; Phase 3 (mvp-completion-plan 2026-05-27) —
          ;; auto-instrument any newly-defined fn whose
          ;; `:malli/schema` parsed cleanly. Runs AFTER the tee
          ;; tx so the `:seon.fn` row is durable before we
          ;; mutate the live var. Best-effort: a thrown
          ;; instrument! aborts only this fn, not the batch.
          (when (:ok result)
            (try
              (instrument-tee-fns!
                (collect-instrument-targets compile-state defs-before))
              (catch :default e
                (js/console.warn
                  "[seon.eval/eval-batch!] auto-instrument failed:"
                  (or (.-message e) (str e)))))
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
                              compile-state defs-before)]
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
                      (js/console.warn
                        "[seon.eval/eval-batch!] auto-test-run failed:"
                        (or (.-message e) (str e))))))))))
        (if (:ok result)
          (vswap! n-ok   inc)
          (vswap! n-fail inc))))))

(defn ^:async eval-batch!
  "Execute a sequence of parsed entries as a REPL batch. Partial-
   failure: every entry gets its own try + record + stash; entry
   N+1 always runs even if N failed.

   Per entry, two kinds:

   The per-form loop is a fold over `parsed`, carrying `current-ns`
   as the accumulator. Each successful eval that switches ns (via
   `(ns …)`) updates the accumulator to the eval's `:ns`. Failed
   forms (parse OR eval) leave the accumulator unchanged — the
   last-known-good ns naturally propagates to the next form. The
   final `:seon.eval/ns` written for each form is the accumulator's
   value at write time (post-update on success; unchanged on failure).
   See docs/seon/concepts/reactive-context.

   `:kind :form` (the normal path):
     1. Eval in the accumulator's current-ns.
     2. Auto-await Promise return values.
     3. Compute duration-ms = (now - start).
     4. On success: advance accumulator to (:ns raw-result); stash
        the live value in globalThis under the eval-id kw.
     5. Transact a :seon.eval entity carrying :seon.eval/ns = the
        post-update accumulator value.

   `:kind :read` (a parse-forms failure, see seon.repl.internal):
     1. Skip the eval (no source to evaluate).
     2. Record as a failed :seon.eval with :seon.eval/ns = the
        unchanged accumulator value (the ns the form WOULD have
        run in). Agent sees its own broken text in the next turn's
        ctx and self-corrects.
     3. duration-ms = 0 (no eval happened).

   `:kind :comment` (trailing comment-preamble with no following form):
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
                     (mix of `:kind :form` and `:kind :read` entries)
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

   The caller reads :n-ok for 'progress made this turn' and :n-fail to
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
                (and (= :read (:kind entry)) (false? (:ok? entry)))
                (let [reads? (fn [s]
                               (let [es (internal/parse-forms s)]
                                 (and (seq es)
                                      (every? #(not= :read (:kind %)) es))))
                      rep    (repair/repair-source
                               {:seon.repair/source (:source entry)
                                :seon.repair/reads? reads?})]
                  (if (:seon.repair/repaired? rep)
                    ;; Repaired → re-parse the repaired span and eval each
                    ;; resulting form through eval-form-entry!, recording the
                    ;; repair note on the FIRST form so the diff is visible.
                    (let [repaired-entries (internal/parse-forms
                                             (:seon.repair/source rep))]
                      (loop [es repaired-entries first? true]
                        (when (seq es)
                          (let [e    (first es)
                                shape (form-shape (:form e))
                                note (when first?
                                       (repair/repair-note
                                         {:seon.repair/changes
                                          (:seon.repair/changes rep)
                                          :seon.repair/shape shape}))
                                narr (if (and first? note)
                                       (str (when (seq (:narration entry))
                                              (str (:narration entry) "\n"))
                                            note)
                                       (:narration e))
                                ;; A fresh eval-id per repaired form after the
                                ;; first (the first reuses this entry's id).
                                eid  (if first? eval-id (db/new-id!))]
                            (await
                              (db/with-tx-context
                                {:seon.db/agent-id agent-id
                                 :seon.db/eval-id  eid
                                 :seon.db/origin   :agent}
                                (fn ^:async run-repaired! []
                                  (await (eval-form-entry!
                                           {:compile-state   compile-state
                                            :eval-id         eid
                                            :turn-id         turn-id
                                            :current-ns      current-ns
                                            :n-ok            n-ok
                                            :n-fail          n-fail
                                            :failed-defs     failed-defs
                                            :outer-test-run? outer-test-run?
                                            :narration       narr
                                            :source          (:source e)})))))
                            (when-not first? (vswap! eids conj eid))
                            (recur (rest es) false)))))
                    ;; Not repairable → sharpened read error (A.3).
                    (do
                      (await (record-eval!
                               {:eval-id     eval-id
                                :turn-id     turn-id
                                :at          (js/Date.)
                                :duration-ms 0
                                :narration   (:narration entry)
                                :source      (:source entry)
                                :ns          @current-ns
                                :result      {:ok false
                                              :error {:seon.error/kind    :read
                                                      :seon.error/message
                                                      (read-error-message
                                                        (:error entry)
                                                        (:source entry))}}}))
                      (vswap! n-fail inc))))

                ;; Comment-only entry (trailing `;;` lines / bare prose with
                ;; no following form) — no source to eval. Record a
                ;; comment-only row (blank source, ok? true) so the agent's
                ;; trailing thinking renders in the transcript and is never
                ;; lost. Not counted in n-ok/n-fail (nothing was evaluated).
                (= :comment (:kind entry))
                (await (record-eval! {:eval-id     eval-id
                                      :turn-id     turn-id
                                      :at          (js/Date.)
                                      :duration-ms 0
                                      :narration   (:narration entry)
                                      :source      ""
                                      :ns          @current-ns
                                      :result      {:ok true :value nil}}))

                ;; REPL-parity intercept (fix d) — in-ns / *ns* get a
                ;; legible translation INSTEAD of an opaque error or a
                ;; silent nil. No eval runs; the record is the teaching.
                (some? (parity-intercept (:source entry) @current-ns))
                (let [{:keys [narration source]} entry
                      pc     (parity-intercept source @current-ns)
                      result (if (= :error (:seon.eval/parity pc))
                               {:ok false
                                :error {:seon.error/kind    :seon.eval/repl-parity
                                        :seon.error/message (:seon.error/message pc)}}
                               {:ok true :value (:seon.eval/value pc)})]
                  (when (:ok result)
                    (stash-result-raw! eval-id (:value result))
                    (bind-result-var! compile-state eval-id (:value result)))
                  (await (record-eval! {:eval-id     eval-id
                                        :turn-id     turn-id
                                        :at          (js/Date.)
                                        :duration-ms 0
                                        :narration   narration
                                        :source      source
                                        :ns          @current-ns
                                        :result      result}))
                  (if (:ok result)
                    (vswap! n-ok   inc)
                    (vswap! n-fail inc)))

                ;; Normal eval path — delegate to the extracted helper so
                ;; the parinfer-repaired path (above) shares the exact same
                ;; eval → await → stash → tee → record → instrument → test
                ;; pipeline.
                :else
                (await (eval-form-entry!
                         {:compile-state   compile-state
                          :eval-id         eval-id
                          :turn-id         turn-id
                          :current-ns      current-ns
                          :n-ok            n-ok
                          :n-fail          n-fail
                          :failed-defs     failed-defs
                          :outer-test-run? outer-test-run?
                          :narration       (:narration entry)
                          :source          (:source entry)}))))))
        (vswap! eids conj eval-id)))
    (cond-> {:seon.eval/ids    @eids
             :seon.eval/n-ok   @n-ok
             :seon.eval/n-fail @n-fail}
      fence-lost? (assoc :seon.eval/fenced? true))))
