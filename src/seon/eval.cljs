(ns seon.eval
  "Agent eval surface (spec-02 §2.5 / spec-03 H-1a). SAFE BY DEFAULT —
   `eval` returns {:ok true :value v} or {:ok false :error <error-map>}.
   A throw, compile error, or async rejection — all return as values.
   The agent session continues.

   The unadorned name `eval` is the safe one; we do NOT ship a public
   strict variant. Callers that want raw throw semantics can drop down
   to `cljs.js/eval-str` themselves.

   `eval` shadows clojure.core/eval inside this namespace. That's
   deliberate — agents type `(seon.eval/eval ...)` from outside this
   ns. seon internals that need clojure.core's `eval` should import as
   `core/eval`.

   ## REPL semantics

   Vars defined in one eval persist for the next (compile-state is
   process-shared, defonce'd at boot in seon.client).

   `:ns` is tracked per-call: `cljs.js/eval-str` returns the ending ns,
   which we feed back as the next call's `:ns` parameter. That's how
   `(ns other-ns)` switches affect subsequent forms. Smart REPL default:
   unqualified vars resolve in the current ns.

   ## Probe-confirmed gotchas (cljs.js + bootstrap target)

   - **Bare value-def reads don't resolve across eval-str calls.**
     `(def x 42)` then `x` returns nil. Use atoms instead:
     `(def !x (atom 42))` + `@!x` works. Fns are unaffected — they
     cross namespaces fine. The agent's home ns is set up with atoms
     for `!session-id` / `!results` / `!current-ns` exactly because of
     this.
   - **`(in-ns 'foo)` is not bootstrapped.** Use `(ns foo)` to switch."
  (:refer-clojure :exclude [eval])
  (:require [cljs.analyzer :as ana]
            [cljs.js :as cljs]
            [clojure.string :as str]
            [goog.object :as gobj]
            [shadow.cljs.bootstrap.node :as boot]
            [seon.db :as db]
            [seon.error :as error]))

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
  []
  (let [state (cljs/empty-state)]
    (await (js/Promise.
             (fn [resolve _reject]
               (boot/init state
                          {:path "out/bootstrap"
                           :load-on-init '#{cljs.core}}
                          (fn [] (resolve nil))))))
    (load-all-analysis-caches! state "out/bootstrap")
    (when-not (and (some? (.-cljs js/global))
                   (some? (.-core (.-cljs js/global))))
      (throw (js/Error.
               "bootstrap loader did not put cljs.core on globalThis")))
    state))

;; ============================================================
;; Core eval — one form. Safe by default.
;; ============================================================

(defn- resolves-on-globalthis?
  "True if `path` (a munged dotted name like `seon.db.transact_BANG_`)
   walks to a non-nil JS object via `goog.getObjectByName`."
  [path]
  (some? (js/goog.getObjectByName path)))

(defn lookup-value
  "Resolve a fully-qualified symbol to its runtime value, or nil if
   unresolvable. The CLJS-bootstrap equivalent of JVM's
   `clojure.core/resolve`.

   Walks `js/globalThis` segment-by-segment, munging each ns segment
   via `cljs.core/munge` to match the JS names shadow-cljs emits.
   Handles reserved-word munge (`default` → `default$`, etc.).

   Works uniformly for:

   - Substrate fns precompiled into `out/client/main.js` by
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
    (let [ns-parts (str/split (namespace sym) #"\.")
          ns-obj   (reduce (fn [obj seg]
                             (when obj (gobj/get obj (cljs.core/munge seg))))
                           js/globalThis
                           ns-parts)]
      (when ns-obj
        (gobj/get ns-obj (cljs.core/munge (name sym)))))))

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
   3. If neither path resolves, truly undeclared → escalate."
  [compile-state {:keys [prefix suffix] :as _warning}]
  (cond
    ;; Strong short-circuit: ns has real :defs in the analyzer's
    ;; compile-state. If the var is registered on a populated ns and
    ;; the analyzer still warned, that's an analyzer bug, not the
    ;; agent's typo — don't escalate. Empty-ns case (`cljs.user`
    ;; before any defs) doesn't trip this, so `Let` still routes
    ;; through the globalThis check below.
    (let [ns-defs (:defs (get-in @compile-state
                                 [:cljs.analyzer/namespaces (symbol prefix)]))]
      (and ns-defs (contains? ns-defs (symbol suffix))))
    false

    :else
    (let [munged-suffix (cljs.core/munge (str suffix))
          prefix-path   (when prefix
                          (str (cljs.core/munge (str prefix)) "." munged-suffix))
          core-path     (str "cljs.core." munged-suffix)]
      (not (or (and prefix-path (resolves-on-globalthis? prefix-path))
               (resolves-on-globalthis? core-path)
               ;; Bare lookup for nil-prefix shapes.
               (resolves-on-globalthis? munged-suffix))))))

(defn ^:async ^:private raw-eval
  "Internal — returns a Promise that resolves with {:value v :ns ns}
   or rejects with the error. The public `eval` catches both.

   `cljs.js`'s `:warning-handlers` option doesn't actually replace the
   analyzer's `*cljs-warning-handlers*` chain — the default handlers
   still fire and surface an `:error` to the callback for
   `:undeclared-var` before our hook runs. To gate that path we
   `set!` the dynamic var directly and restore it on callback.

   Warning shape captured: `{:prefix … :suffix … :seon.eval/warning-type
   …}`. After eval, any warning whose target doesn't resolve through
   the strategy in `truly-undeclared?` is promoted to a `:compile`-
   kind error. Warning check runs BEFORE the `error` branch — when
   our handler IS the only one installed, the analyzer no longer
   raises an :error for undeclared-var, so the cond ordering decides
   which shape we surface."
  [compile-state form-str ns-sym analyze-deps?]
  (let [warnings (atom [])
        prev-h  ana/*cljs-warning-handlers*]
    (js/Promise.
      (fn [resolve reject]
        (set! ana/*cljs-warning-handlers*
              [(fn [type _env extra]
                 (when (#{:undeclared-var :undeclared-ns} type)
                   (swap! warnings conj
                          (assoc extra :seon.eval/warning-type type))))])
        (cljs/eval-str compile-state form-str 'seon.dynamic
          {:eval          cljs/js-eval
           :load          (partial boot/load compile-state)
           :ns            ns-sym
           :context       :statement
           :def-emits-var true
           :analyze-deps  analyze-deps?}
          (fn [{:keys [error value ns]}]
            ;; Restore FIRST so a thrown reject doesn't leak the binding.
            (set! ana/*cljs-warning-handlers* prev-h)
            (cond
              ;; Truly-undeclared check runs before :error — with the
              ;; analyzer's default handler chain swapped out, the
              ;; analyzer no longer fires an :error for undeclared-var,
              ;; so our captured warnings are the authoritative signal.
              (some (partial truly-undeclared? compile-state) @warnings)
              (let [{:keys [prefix suffix] :as w}
                    (first (filter (partial truly-undeclared? compile-state)
                                   @warnings))]
                (reject
                  (ex-info (str "undeclared " (name (:seon.eval/warning-type w))
                                ": " prefix "/" suffix)
                           {:seon.error/kind :compile
                            :seon.eval/warning-type (:seon.eval/warning-type w)
                            :seon.eval/undeclared (str prefix "/" suffix)
                            :seon.eval/warning (dissoc w :seon.eval/warning-type)})))

              error
              (reject error)

              :else
              (resolve {:value value :ns ns}))))))))

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
;; Agent reads via `(seon.agent.<id>/result :abc123)` which is
;; set up by setup-agent-ns! to do the same js/Reflect.get lookup.
;; ============================================================

(def ^:private results-key-prefix "__seon_results_")

(defn- result-key [eval-id]
  (str results-key-prefix eval-id))

(defn stash-result-raw!
  "Stash a raw value (any type) on globalThis keyed by the eval-id.
   No pr-str round-trip — value-type-agnostic. Soft-fails on impossible
   sets (logs + ignores)."
  [eval-id value]
  (try
    (js/Reflect.set js/globalThis (result-key eval-id) value)
    (catch :default e
      (js/console.warn "[seon.eval/stash-result-raw!] failed for"
                       (pr-str eval-id) "—"
                       (error/->message e)))))

(defn ^:async setup-agent-ns!
  "Create + initialize the agent's home namespace. Returns the agent-ns
   symbol (for convenience — same as the input). Idempotent: re-running
   resets atoms to initial values.

   After setup, agent code running in this ns has access to:
     !session-id  — atom holding the agent-id string (preserves the
                    `(session-id)` accessor name per spec-05 §21.1)
     !current-ns  — atom holding the agent's current ns symbol
     (session-id) — sugar for @!session-id
     (result id)  — looks up the live value of a prior eval, keyed by
                    its 10-char id (string or keyword). Backed by
                    globalThis so any value type round-trips.

   Uses `:analyze-deps? true` so the `(ns …)` form analyzes cljs.core's
   refer map and wires up implicit macro refers (defn, str, atom, etc.)
   for subsequent forms in the new ns."
  [compile-state agent-ns-sym agent-id]
  (let [;; `!current-ns` removed 2026-05-23 — was a process-global
        ;; cache forcing two extra cljs.js/eval-str round-trips per
        ;; form. The agent's current ns is now derived at read time
        ;; from the latest :seon.eval/ns datom (see
        ;; seon.agent/current-ns + docs/seon/concepts/reactive-context).
        setup-src
        (str "(ns " agent-ns-sym ")"
             "(def !session-id (atom " (pr-str agent-id) "))"
             "(defn session-id [] @!session-id)"
             "(defn result [id]"
             "  (js/Reflect.get js/globalThis"
             "    (str " (pr-str results-key-prefix)
             "         (if (keyword? id) (name id) (str id)))))"
             ":seon.eval/setup-ok")
        r (await (eval compile-state setup-src
                       {:ns 'cljs.user
                        :analyze-deps? true}))]
    (when-not (:ok r)
      (throw (ex-info "setup-agent-ns! failed"
                      {:agent-ns agent-ns-sym
                       :error    (:error r)})))
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

(defn ^:async record-eval!
  "Transact one :seon.eval entity as a component child of its owning
   turn (per v1.md §2.1 — `:seon.turn/evals` is component-many). The
   nested-map shorthand creates the eval inline; datahike's component
   semantics mean a one-pull on the turn returns its evals without
   needing a back-ref query.

   Soft-fails — a DB write failure is logged but doesn't abort the
   batch. The tx auto-tags with whatever causality bundle is in
   `(seon.db/current-tx-context)` (eval-batch! opens the per-eval
   scope with `:seon.db/agent-id` + `:seon.db/eval-id` + `:seon.db/
   origin :agent`, plus whatever the caller layered above)."
  [{:keys [eval-id turn-id at narration source result duration-ms ns]}]
  (let [eval-map (cond-> {:seon.eval/id          eval-id
                          :seon.eval/at          at
                          :seon.eval/duration-ms (or duration-ms 0)
                          :seon.eval/narration   (or narration "")
                          :seon.eval/source      source
                          :seon.eval/ok?         (boolean (:ok result))
                          ;; v1.md:236 — ending ns. From the eval result
                          ;; on success; from the fold accumulator on
                          ;; failure (last-known-good). Always populated.
                          ;; Stored as :keyword per spec; eval-batch
                          ;; holds it as a symbol for cljs.js, coerce
                          ;; at this boundary.
                          :seon.eval/ns          (if (keyword? ns)
                                                   ns
                                                   (keyword (str ns)))}
                   (:ok result)
                   (assoc :seon.eval/result-edn
                          (try (pr-str (:value result))
                               (catch :default _ (str (:value result)))))

                   (not (:ok result))
                   (assoc :seon.eval/error
                          (try (pr-str (:error result))
                               (catch :default _ (str (:error result))))))
        ;; Attach the eval as a component child of the turn. Datahike's
        ;; cardinality-many ref accumulates on upsert — each record-eval!
        ;; call appends one eval to the turn's evals set.
        tx-data  [{:seon.turn/id    turn-id
                   :seon.turn/evals [eval-map]}]
        r (await (db/transact! {:seon.db/tx-data tx-data}))]
    (when-not (:seon.db/ok? r)
      (js/console.warn "[seon.eval/eval-batch!] record-eval! tx failed:"
                       (-> r :seon.db/error :seon.error/message)
                       "— source:" source))))

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

   `:kind :read` (a parse-forms failure, see seon.parse):
     1. Skip the eval (no source to evaluate).
     2. Record as a failed :seon.eval with :seon.eval/ns = the
        unchanged accumulator value (the ns the form WOULD have
        run in). Agent sees its own broken text in the next turn's
        ctx and self-corrects.
     3. duration-ms = 0 (no eval happened).

   Per-form work is wrapped in `(db/with-tx-context {…} f)` so every
   transact inside auto-tags with the causality bundle (agent-id +
   eval-id + origin). Callers that establish a wider scope first
   (e.g. agent.cljs/run-turn! adding turn-id) get those keys layered
   in via with-tx-context's merge.

   Args:
     compile-state — the bootstrap compile-state (defonce'd at boot)
     parsed        — vector from `seon.parse/parse-forms`
                     (mix of `:kind :form` and `:kind :read` entries)
     agent-ns-sym  — agent's home ns (e.g. 'seon.agent.seon)
     agent-id      — the owning agent's id
     turn-id       — the owning :seon.turn/id string (eval lands as a
                     component child of this turn via :seon.turn/evals)

   Returns `{:seon.eval/ids    [<id> ...]   ; ordered, one per entry
             :seon.eval/n-ok   <int>        ; successful evals
             :seon.eval/n-fail <int>}`     ; failed (eval-throw + read)

   The caller (run-agentic-loop! stop-policy logic) reads :n-ok for
   'progress made this turn' and :n-fail to surface to the agent's
   warnings tile."
  [compile-state parsed agent-ns-sym agent-id turn-id]
  (let [;; Fold-step local accumulators. Volatile! is a transient
        ;; mutation impl detail inside this one fn; not shared state.
        ;; current-ns is the per-form fold value: starts at agent
        ;; home-ns, advances on each successful `(ns …)` eval, stays
        ;; unchanged on any failure (last-known-good propagates).
        eids       (volatile! [])
        n-ok       (volatile! 0)
        n-fail     (volatile! 0)
        current-ns (volatile! agent-ns-sym)]
    (doseq [entry parsed]
      (let [eval-id    (db/new-id!)
            tx-context {:seon.db/agent-id agent-id
                        :seon.db/eval-id  eval-id
                        :seon.db/origin   :agent}]
        (await
          (db/with-tx-context tx-context
            (fn ^:async run-one-entry! []
              (cond
                ;; Read-failure entry from seon.parse — no eval, record
                ;; directly as a failed :seon.eval. :seon.eval/ns =
                ;; the unchanged accumulator (last-known-good ns).
                (and (= :read (:kind entry)) (false? (:ok? entry)))
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
                                                  :seon.error/message (:error entry)}}}))
                  (vswap! n-fail inc))

                ;; Normal eval path.
                :else
                (let [{:keys [narration source]} entry
                      at          (js/Date.)
                      start-ms    (.now js/Date)
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
                      duration-ms (- (.now js/Date) start-ms)]
                  ;; Advance the accumulator on successful ns switch.
                  ;; Failed evals leave the accumulator untouched —
                  ;; the form ran in @current-ns and we record that
                  ;; value as the form's :seon.eval/ns.
                  (when (and (:ok result) (:ns raw-result))
                    (vreset! current-ns (:ns raw-result)))
                  ;; Live-value stash — direct js/Reflect.set on globalThis,
                  ;; no eval-str round-trip (opaque values like datahike DB
                  ;; tagged literals don't break the stash). Agent reads
                  ;; via (result :id).
                  (when (:ok result)
                    (stash-result-raw! eval-id (:value result)))
                  ;; Durable record — always. :seon.eval/ns is the
                  ;; post-update accumulator (ending ns on success;
                  ;; unchanged ns on failure).
                  (await (record-eval! {:eval-id     eval-id
                                        :turn-id     turn-id
                                        :at          at
                                        :duration-ms duration-ms
                                        :narration   narration
                                        :source      source
                                        :ns          @current-ns
                                        :result      result}))
                  (if (:ok result)
                    (vswap! n-ok   inc)
                    (vswap! n-fail inc)))))))
        (vswap! eids conj eval-id)))
    {:seon.eval/ids    @eids
     :seon.eval/n-ok   @n-ok
     :seon.eval/n-fail @n-fail}))
