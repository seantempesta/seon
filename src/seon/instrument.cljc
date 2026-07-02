(ns seon.instrument
  "Phase A item 7 — collect + install Malli instrumentation for every
   seon.* fn with `:malli/schema` metadata. (T15 positional read ops.)

   Why this exists: `malli.instrument/collect!` is JVM-only — it reads
   source files at JVM build time. CLJS has no built-in equivalent;
   `:malli/schema` metadata does NOT auto-populate
   `malli.core/-function-schemas*` at namespace load. So at boot,
   `malli.instrument/instrument!` finds no schemas and instruments
   nothing.

   The seon-native fix: a compile-time macro that reads the CLJS
   analyzer's view of every loaded namespace via
   `cljs.analyzer.api/all-ns` + `ns-publics`, filters to `seon.*`,
   walks each ns's defs, and for every def whose metadata carries
   `:malli/schema` emits one
   `(malli.core/-register-function-schema! ns name schema {})` call.
   The macro expands to a flat `(do …)` of registration calls —
   evaluated at runtime, populates the atom that `instrument!` reads.

   The runtime `install!` fn calls the macro then
   `malli.instrument/instrument!`. Idempotent — re-registering is a
   no-op (same key, last-write-wins, same value).

   Per CLAUDE.md: we don't have to use packages the way the original
   author intended. The end goal is `:malli/schema`-annotated fns get
   their inputs+outputs validated at runtime; the original `collect!`
   path is JVM-only; we ship the CLJS path that achieves the same end."
  #?(:cljs (:require-macros [seon.instrument :refer [collect!]]))
  (:require
    #?@(:clj  [[cljs.analyzer.api :as ana]
               [clojure.java.io :as io]
               [clojure.string :as str]]
        :cljs [[cljs.reader :as reader]
               [clojure.string :as str]
               [goog.object :as gobj]
               [malli.core :as m]
               [malli.instrument :as mi]
               [seon.config :as config]
               [seon.db :as db]
               [seon.error.instrument :as ei]])))

(def skip-syms
  "Fns that OPT OUT of instrumentation because they are the agent-facing
   'errors are values' surface: every call RESOLVES to an envelope
   (`{::ok? true/false …}`), never throws. A throwing validator on their
   input would break that contract — agents are taught to branch on
   `::ok?`, so a throw (even one the eval boundary catches as data) aborts
   their in-eval code instead of returning the envelope it expects.

   These fns own their own validation / degrade-to-envelope; the
   instrumentation is redundant for them. Instrumentation stays ON for
   INTERNAL fns (where it earns its keep — it caught `result-var-ref?`).

   Two entry shapes:
     - a bare `ns-sym` skips EVERY public fn in that ns (use for the pure
       capability-wrapper namespaces — by the wrapper doctrine, all their
       public fns are envelope verbs).
     - a `[ns-sym fn-sym]` pair skips one fn (use in a MIXED ns that also
       has internal/render fns which SHOULD stay instrumented).

   The opt-out lives here (a symbol set), NOT as fn metadata or a schema
   property: the CLJS analyzer strips both from the `:malli/schema` value
   the `collect!` macro reads, so only a FQ-symbol match is reliable. Each
   verb fn/ns carries a comment pointing back here. New capability wrappers
   add themselves (see the wrapper doctrine in `seon.agent.search`).
   Plain `.cljc` so the compile-time macro and the runtime tee path agree."
  #{;; Pure capability-wrapper namespaces — all public fns are verbs.
    'seon.agent.search                 ; grep
    'seon.agent.fs                     ; read-file/write-file/list-dir/stat/…
    'seon.agent.message                ; message!/user/agent
    ;; Mixed ns — skip the envelope verbs; its plan-block/plan-body RENDER fns stay
    ;; instrumented.
    ['my.plan 'step!]
    ['my.plan 'done!]
    ['my.plan 'reopen!]
    ['my.plan 'list-open]
    ;; Safe-by-default core write — assert-invocation-shape! returns an
    ;; envelope; tested in db-test. (seon.db has many non-verb read fns
    ;; that DO stay instrumented, so this is fn-level.)
    ['seon.db 'transact!]})

(defn skip?
  "True when `[ns-sym fn-sym]` is opted out of instrumentation.

   Either its whole ns is in [[skip-syms]] or the specific pair is."
  {:malli/schema [:=> [:cat :symbol :symbol] :boolean]}
  [ns-sym fn-sym]
  (or (contains? skip-syms ns-sym)
      (contains? skip-syms [ns-sym fn-sym])))

#?(:clj
   (defn- first-party-file?
     "STRUCTURAL first/third-party boundary (V3-C, 2026-06-10 — same
      rule as `seon.indexing/first-party-file?`): a def is FIRST-PARTY
      iff its analyzer `:file` resolves to a file under the repo root
      (the macroexpanding JVM's working dir). Jars (`jar:` URLs) and
      gitlibs checkouts (file URLs outside the root) are third-party.
      Replaces the two name-prefix `collect!` calls (\"seon\" +
      \"my.\")."
     [file]
     (boolean
       (when (and file (string? file))
         (let [root (System/getProperty "user.dir")]
           (if (str/starts-with? file "/")
             (str/starts-with? file root)
             (when-let [url (io/resource file)]
               (and (= "file" (.getProtocol url))
                    (str/starts-with? (.getPath url) root)))))))))

#?(:clj
   (defn- collect-registrations
     "Compile-time scan: walk every FIRST-PARTY CLJS namespace
      ([[first-party-file?]] over each def's analyzer `:file` — the
      structural boundary, not a name prefix), find every def whose
      metadata carries `:malli/schema`, and return a vector of
      [ns-sym fn-sym schema-form async?] tuples. Run at macroexpand time.

      `^:async` fns are INCLUDED (the `async?` flag rides along). Their
      `:malli/schema` describes the RESOLVED value, but the compiled fn
      returns a Promise — so the runtime [[register-target!]] routes them
      to an await-then-validate wrapper (simple fixed-arity fns get
      input+output; variadic/multi-arity get input+arity only) rather
      than malli's stock synchronous wrapper, which would trip
      `:malli.core/invalid-output` on the Promise itself."
     []
     (vec
       (for [ns-sym  (ana/all-ns)
             [fn-sym ana-info] (ana/ns-publics ns-sym)
             :let    [meta-map (:meta ana-info)
                      schema   (:malli/schema meta-map)
                      async?   (boolean (:async meta-map))
                      file     (or (:file ana-info) (:file meta-map))]
             ;; Opt-out ([[skip-syms]], e.g. `seon.db/transact!`) is applied
             ;; downstream in [[register-target!]] — a FQ-symbol match, since
             ;; the analyzer strips schema/metadata markers from this view.
             :when   (and schema (first-party-file? file))]
         [ns-sym fn-sym schema async?]))))

#?(:clj
   (defmacro collect!
     "Expand at compile time to a `(do …)` of
      `(seon.instrument/register-target! 'ns 'name <schema> async?)`
      calls — one per discovered `:malli/schema`-annotated fn in a
      FIRST-PARTY namespace (structural boundary — every def whose
      source file lives under the repo root; see [[first-party-file?]]).
      [[register-target!]] decides the wrapper per fn (sync vs async,
      simple vs variadic) at runtime.

      Re-runs on every CLJS rebuild — picks up new fns as they're
      added. Returns the registration count.

      Example expansion (one of several inner forms):
        (seon.instrument/register-target!
          'seon.db 'transact!
          [:function [:=> [:cat ::transact-request] ::transact-response] …]
          true)"
     []
     (let [registrations (collect-registrations)]
        `(do
           ~@(for [[ns-sym fn-sym schema async?] registrations]
               `(register-target! '~ns-sym '~fn-sym ~schema ~async?))
           ~(count registrations)))))

#?(:cljs
   (defn enabled?
     "Whether runtime instrumentation is active. ON by default; the
      `SEON_INSTRUMENT` env var is a kill-switch — set it to
      `0`/`false`/`off`/`no` (case-insensitive) to disable every wrapper
      (boot-time AND the eval-tee path). Validation is cheap and the
      structured errors are valuable, so default-on is the intended
      posture; the var exists only to bail out if a wrapper ever
      destabilizes the pod."
     {:malli/schema [:=> [:cat] :boolean]}
     []
     (let [v (some-> (config/env-string "SEON_INSTRUMENT") str/lower-case)]
       (not (contains? #{"0" "false" "off" "no"} v)))))

#?(:cljs
   (defn- -find-js-var
     "Resolve the live JS fn object for `[ns-sym fn-sym]` by walking the
      munged goog.global path — same lookup malli.instrument uses to find
      the var it wraps. nil if not found (e.g. fn not yet loaded)."
     [ns-sym fn-sym]
     (try
       (gobj/getValueByKeys
         js/goog.global
         (into-array (map munge (conj (str/split (str ns-sym) #"\.")
                                      (name fn-sym)))))
       (catch :default _ nil))))

#?(:cljs
   (defn- -simple-fixed-arity-fn?
     "True when `f` is a plain single-fixed-arity fn — NOT variadic, NOT
      multi-arity. CLJS only sets `cljs$lang$maxFixedArity` on
      variadic/multi-arity fns, so its absence is the marker. This is
      exactly the case where `malli.instrument` takes its simple `:else`
      replace path and hands the wrapper RAW args (no `[fixed… rest]`
      marshalling), which is the only shape [[async-fschema]]'s wrapper
      validates correctly."
     [f]
     (and (fn? f) (nil? (gobj/get f "cljs$lang$maxFixedArity")))))

#?(:cljs
   (def injectables
     "The injectable REGISTRY — `injectable-key → (fn [eval-ctx] value)`.

      The ONE extension surface for explicit dependency injection at the eval
      boundary (`docs/seon/architecture/context.md` §\"Explicit dependencies\").
      A map-in fn declares an injectable as an `{:optional true}` request key;
      [[injecting-fschema]]'s wrapper fills every DECLARED-BUT-ABSENT key from
      the current eval context — explicit caller args always win. Adding a
      dependency is ONE entry here + fns declaring the key.

      The eval-context SOURCE is the ALS/`*conn*` the loop already establishes
      (`seon.agent.turn/run-turn!` wraps eval in `db/with-agent id`), so the
      fn body never reads an invisible dynamic var — the boundary does it once,
      visibly, and the fn's spec is the honest statement of what it needs. A
      provider yielding nil leaves the key ABSENT (never store nil — optional =
      absent). `eval-ctx` is reserved for future per-call context; today the
      providers read ALS/`*conn*` directly and it is passed `nil`."
     {:seon.db/db    (fn [_] (some-> db/*conn* deref))
      :seon.agent/id (fn [_] (db/current-agent-id))}))

#?(:cljs
   (defn- inject-into
     "Fill each DECLARED injectable `k` ABSENT from request map `m` with its
      [[injectables]] value from the eval context. Explicit keys WIN (a
      present key is untouched); a provider yielding nil leaves the key
      absent. `inj-keys` is the pre-computed declared∩registry set."
     [m inj-keys]
     (reduce (fn [acc k]
               (if (contains? acc k)
                 acc
                 (let [v ((injectables k) nil)]
                   (if (some? v) (assoc acc k v) acc))))
             m inj-keys)))

#?(:cljs
   (defn declared-injectables
     "The set of injectable request-keys a fn's `:=>` `schema-obj` DECLARES on
      its FIRST (map) argument — the intersection of that arg map's keys with
      the [[injectables]] registry. Computed ONCE per fn at register time
      (rides on [[injecting-fschema]]'s closure), never per call.

      Reads the arg schema via `m/-function-info` → first `:input` child →
      `m/deref` (resolves a registered `::req` ref OR passes an inline `:map`
      through) → `m/entries` (all entries, optional included). Empty for a fn
      whose first arg is not a `:map` or declares no injectable key.
      Errors-as-values: any malli mishap yields `#{}` (the fn is simply not
      injected)."
     {:malli/schema [:=> [:cat :any] [:set :keyword]]}
     [schema-obj]
     (try
       (let [info (m/-function-info schema-obj)
             arg0 (first (m/children (:input info)))
             d    (when arg0 (m/deref arg0))]
         (if (and d (= :map (m/type d)))
           (into #{} (comp (map first) (filter injectables)) (m/entries d))
           #{}))
       (catch :default _ #{}))))

#?(:cljs
   (defn injecting-fschema
     "A malli function-schema OBJECT (not a form) for a single simple
      fixed-arity `:=>` schema — SYNC or `^:async`. Delegates every `Schema`/
      `FunctionSchema` method to the real `:=>` schema EXCEPT `-instrument-f`,
      which it overrides to INJECT-then-validate:

        - INJECT: every DECLARED-BUT-ABSENT injectable key ([[injectables]],
          pre-computed via [[declared-injectables]]) is filled into the first
          (map) arg from the eval context BEFORE input validation, so the
          filled map satisfies the `:map` and explicit caller args win;
        - input + arity validated synchronously (throws via `report`);
        - the call's return, if a Promise, gets a `.then` validating the
          RESOLVED value against the output schema; a non-thenable return
          validates output synchronously (the sync path). Either way the
          value re-resolves unchanged.

      Replaces the older async-only wrapper: it handles BOTH sync and async
      map-in fns (a fn declaring no injectable key is behavior-identical to
      the stock wrapper — no injection, same input+output validation), so
      `register-target!` routes every simple fixed-arity `:=>` fn here. This
      is the ONE injecting boundary — no second wrapper. Registering it (via
      `m/-register-function-schema!` with the `identity` transformer) makes
      malli's stock `mi/instrument!` reuse ALL its var-surgery and simply call
      this `-instrument-f`."
     {:malli/schema [:=> [:cat :any] :any]}
     [inner-form]
     (let [s        (m/schema inner-form)
           inj-keys (declared-injectables s)]
       (reify
         m/Schema
         (-validator [_] (m/-validator s))
         (-explainer [_ path] (m/-explainer s path))
         (-parser [_] (m/-parser s))
         (-unparser [_] (m/-unparser s))
         (-transformer [_ a b c] (m/-transformer s a b c))
         (-walk [_ w path opts] (m/-walk s w path opts))
         (-properties [_] (m/-properties s))
         (-options [_] (m/-options s))
         (-children [_] (m/-children s))
         (-parent [_] (m/-parent s))
         (-form [_] (m/-form s))
         m/FunctionSchema
         (-function-schema? [_] true)
         (-function-schema-arities [_] (m/-function-schema-arities s))
         (-function-info [_] (m/-function-info s))
         (-instrument-f [_ {:keys [scope report] :as _props} f _opts]
           (let [{:keys [min max input output]} (m/-function-info s)
                 scope    (or scope #{:input :output})
                 report   (or report m/-fail!)
                 vin      (m/-validator input)
                 vout     (m/-validator output)
                 wrap-in  (contains? scope :input)
                 wrap-out (contains? scope :output)]
             (fn [& args]
               (let [args (vec args)
                     ;; INJECT declared-absent deps into the request map before
                     ;; validation; explicit args win, nil providers no-op.
                     args (if (and (seq inj-keys) (map? (first args)))
                            (assoc args 0 (inject-into (first args) inj-keys))
                            args)
                     n    (count args)]
                 (when wrap-in
                   (when-not (<= min n (or max js/Number.MAX_SAFE_INTEGER))
                     (report :malli.core/invalid-arity
                             {:arity n :arities #{{:min min :max max}}
                              :args args :input input :schema s}))
                   (when-not (vin args)
                     (report :malli.core/invalid-input
                             {:input input :args args :schema s})))
                 (let [ret (apply f args)]
                   (if (and ret (fn? (.-then ret)))
                     (.then ret (fn [v]
                                  (when (and wrap-out (not (vout v)))
                                    (report :malli.core/invalid-output
                                            {:output output :value v
                                             :args args :schema s}))
                                  v))
                     (do (when (and wrap-out (not (vout ret)))
                           (report :malli.core/invalid-output
                                   {:output output :value ret
                                    :args args :schema s}))
                         ret)))))))))))

#?(:cljs
   (defn register-target!
     "Register ONE schema'd fn into malli's `:cljs` function-schema
      registry with the wrapper that fits its shape, then return. Routes:

        - fn is in [[skip-syms]] → register NOTHING (self-validating).
        - simple single-fixed-arity `:=>` (SYNC or `^:async`) → register an
          [[injecting-fschema]] object: it injects declared-absent deps into
          the request map, validates input synchronously, and validates
          output synchronously (sync return) or on Promise resolution (async
          return). This is the map-in case where dependency injection applies.
        - async fn that is variadic or multi-arity (e.g. a self-validating
          fn that did NOT opt out) → register the raw form with per-fn
          `{:scope #{:input}}`, so malli's stock wrapper (which correctly
          handles the arg marshalling) validates input + arity only.
          Output validation for these shapes is deferred — a wrapper that
          returns a derived Promise across malli's variadic marshalling is
          a separate piece of work.
        - sync variadic / multi-arity (or a fn whose live var isn't
          resolvable yet) → register the raw schema form; malli's stock
          wrapper validates input + output synchronously (no injection —
          injection is the single-map-arg case only).

      `mi/instrument!` (called once afterward) reads this registry and
      installs each wrapper in place."
     {:malli/schema [:=> [:cat :symbol :symbol :any :boolean] :any]}
     [ns-sym fn-sym schema-form async?]
     (cond
       (skip? ns-sym fn-sym) nil
       :else
       (let [the-fn (-find-js-var ns-sym fn-sym)
             arrow? (try (= :=> (m/type (m/schema schema-form)))
                         (catch :default _ false))]
         (cond
           ;; simple fixed-arity :=> (sync OR async) → the injecting wrapper.
           (and the-fn arrow? (-simple-fixed-arity-fn? the-fn))
           (m/-register-function-schema! ns-sym fn-sym (injecting-fschema schema-form)
                                         {} :cljs identity)
           ;; async variadic/multi-arity → stock wrapper, input + arity only.
           async?
           (m/-register-function-schema! ns-sym fn-sym schema-form
                                         {:scope #{:input}} :cljs identity)
           ;; sync variadic/multi-arity (or unresolvable var) → stock wrapper.
           :else
           (m/-register-function-schema! ns-sym fn-sym schema-form {} :cljs identity))))))


#?(:cljs
   (defn async-fn?
     "True when `f` is a JS async function (the runtime shape `^:async`
      compiles to). Lets [[instrument-from-db!]] route async wrappers
      without the analyzer's `:async` flag — the live var carries the fact."
     {:malli/schema [:=> [:cat :any] :boolean]}
     [f]
     (and (fn? f) (= "AsyncFunction" (.. f -constructor -name)))))

#?(:cljs
   (defn instrument-from-db!
     "Instrument every fn the PROGRAM GRAPH knows about — the robust,
      ordering-independent replacement for the compile-time `collect!`
      scan (issue instrumentation-collect-clean-build-empty).

      Queries `db` for all `:seon.fn/sym` + `:seon.fn/spec` rows (the
      canonical index of EVERY core + agent-authored fn — `index-core!`
      seeds core fns, the eval-tee seeds agent fns), resolves each live JS
      var, reads its spec, and routes it through [[register-target!]]
      (async detected from the var via [[async-fn?]]; [[skip-syms]]
      honored), then `mi/instrument!` once.

      Runs at boot AFTER the core is indexed. The eval-tee path keeps
      instrumenting newly-defined fns inline between boots. No-op when
      [[enabled?]] is false. Returns a stats map.

      `:no-var` counts rows whose var isn't live (a prior session's fn);
      `:bad-spec` counts unreadable spec strings; `:unresolvable-schema`
      counts rows whose spec READS but references a schema name that no
      longer resolves in the registry (a renamed/pruned schema ghost) —
      ALL THREE are skipped, never fatal. The last is the boot-resilience
      invariant: a single stale persisted fn row must DEGRADE (left
      uninstrumented) rather than crash the whole pod boot. We surface
      the dangling ref as a value HERE — building the schema before
      registering it — so it never reaches `mi/instrument!`, which would
      otherwise throw `:malli.core/invalid-schema` and abort boot."
     {:malli/schema [:=> [:cat :any] :map]}
     [db]
     (if-not (enabled?)
       {:seon.instrument/enabled?       false
        :seon.instrument/n-instrumented 0}
       (let [rows  (db/query '[:find ?sym ?spec
                               :where [?e :seon.fn/sym ?sym]
                                      [?e :seon.fn/spec ?spec]]
                             db)
             stats (volatile! {:registered 0 :skipped 0 :no-var 0
                               :bad-spec 0 :unresolvable-schema 0})]
         (doseq [[sym-str spec-str] rows
                 :let [slash (str/index-of (str sym-str) "/")]
                 :when slash]
           (let [ns-sym (symbol (subs sym-str 0 slash))
                 fn-sym (symbol (subs sym-str (inc slash)))
                 the-fn (-find-js-var ns-sym fn-sym)
                 schema (try (reader/read-string spec-str)
                             (catch :default _ ::bad))
                 ;; Resolve-check (errors-as-values): build the schema
                 ;; against the live registry NOW. A spec that references
                 ;; a renamed/pruned schema throws `:malli.core/invalid-schema`
                 ;; here, caught and turned into `false`, so the row is
                 ;; degraded below instead of crashing `mi/instrument!`.
                 ok?    (when-not (= ::bad schema)
                          (try (m/schema schema) true
                               (catch :default _ false)))]
             (cond
               (nil? the-fn)    (vswap! stats update :no-var inc)
               (= ::bad schema) (vswap! stats update :bad-spec inc)
               (not ok?)        (do (js/console.warn
                                      (str "seon.instrument/instrument-from-db!: "
                                           sym-str " has a persisted :malli/schema that "
                                           "no longer resolves (renamed/pruned schema) — "
                                           "leaving it UNINSTRUMENTED so boot proceeds: "
                                           spec-str))
                                     (vswap! stats update :unresolvable-schema inc))
               (skip? ns-sym fn-sym) (vswap! stats update :skipped inc)
               :else (do (register-target! ns-sym fn-sym schema (async-fn? the-fn))
                         (vswap! stats update :registered inc)))))
         (mi/instrument! {:report ei/report-fn})
         (assoc @stats
                :seon.instrument/enabled? true
                :seon.instrument/n-instrumented
                (reduce + (map count (vals (m/function-schemas :cljs)))))))))

#?(:cljs
   ;; P0 (double-instrument async wedge) — instrument the program graph ONCE
   ;; per process. The 1st pass (boot, after index + replay) wraps core +
   ;; every replayed agent fn from its FRESH var. A 2nd pass (a later POST
   ;; /agents/new → start-agent!) would re-read the 1st pass's WRAPPER var,
   ;; whose constructor is `Function` not `AsyncFunction`, so `async-fn?`
   ;; mis-detects every `^:async` fn as sync and re-routes its Promise return
   ;; through malli's SYNC output validator → `:malli.core/invalid-output`
   ;; and the pod wedges (ticker + wake loop throw every agent). Agent fns
   ;; defined AFTER boot are wrapped inline by the eval-tee, so the once-gate
   ;; loses nothing. Resets on a fresh process (a cluster reset re-boots).
   (defonce ^:private !instrumented? (atom false)))

#?(:cljs
   (defn instrument-from-db-once!
     "Idempotent [[instrument-from-db!]] — the `start-agent!` entry point.
      Runs the full pass the FIRST time per process, then short-circuits so a
      later agent-creation never re-instruments already-wrapped vars (which
      would mis-route every async fn and wedge the pod — see [[!instrumented?]])."
     {:malli/schema [:=> [:cat :any] :map]}
     [db]
     (if @!instrumented?
       {:seon.instrument/enabled?       (enabled?)
        :seon.instrument/already-done?  true
        :seon.instrument/n-instrumented (reduce + (map count (vals (m/function-schemas :cljs))))}
       (let [stats (instrument-from-db! db)]
         (reset! !instrumented? true)
         stats))))
