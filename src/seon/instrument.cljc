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
    ;; Mixed ns — skip the envelope verbs; its open-todos-* RENDER fns stay
    ;; instrumented.
    ['seon.agent.todo 'add!]
    ['seon.agent.todo 'complete!]
    ['seon.agent.todo 'reopen!]
    ['seon.agent.todo 'list-open]
    ;; Safe-by-default core write — assert-invocation-shape! returns an
    ;; envelope; tested in db-test. (seon.db has many non-verb read fns
    ;; that DO stay instrumented, so this is fn-level.)
    ['seon.db 'transact!]})

(defn skip?
  "True when `[ns-sym fn-sym]` is opted out of instrumentation — either its
   whole ns is in [[skip-syms]] or the specific pair is."
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
     []
     (let [v (some-> js/process .-env .-SEON_INSTRUMENT str str/lower-case)]
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
   (defn async-fschema
     "A malli function-schema OBJECT (not a form) for a single `:=>`
      schema whose fn is `^:async`. Delegates every `Schema`/
      `FunctionSchema` method to the real `:=>` schema EXCEPT
      `-instrument-f`, which it overrides to await-then-validate:

        - input + arity validated synchronously (throws via `report`);
        - the call's return (a Promise) gets a `.then` that validates the
          RESOLVED value against the output schema and `report`s a
          mismatch, re-resolving the value unchanged. A non-thenable
          return is passed through untouched.

      Registering this object (via `m/-register-function-schema!` with the
      `identity` transformer) makes malli's stock `mi/instrument!` reuse
      ALL its var-surgery and simply call this `-instrument-f` — no custom
      registry, no var-install code of our own."
     [inner-form]
     (let [s (m/schema inner-form)]
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
               (let [args (vec args), n (count args)]
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
                     ret)))))))))) ; non-thenable return — pass through

#?(:cljs
   (defn register-target!
     "Register ONE schema'd fn into malli's `:cljs` function-schema
      registry with the wrapper that fits its shape, then return. Three
      routes:

        - fn is in [[skip-syms]] → register NOTHING (self-validating).
        - sync fn → register the raw schema form; malli's stock wrapper
          validates input + output synchronously (the original behavior).
        - async fn that is a simple single-fixed-arity `:=>` → register an
          [[async-fschema]] object so input validates synchronously and
          output validates on Promise resolution.
        - async fn that is variadic or multi-arity (e.g. a self-validating
          fn that did NOT opt out) → register the raw form with per-fn
          `{:scope #{:input}}`, so malli's stock wrapper (which correctly
          handles the arg marshalling) validates input + arity only.
          Output validation for these shapes is deferred — a wrapper that
          returns a derived Promise across malli's variadic marshalling is
          a separate piece of work.

      `mi/instrument!` (called once afterward) reads this registry and
      installs each wrapper in place."
     [ns-sym fn-sym schema-form async?]
     (cond
       (skip? ns-sym fn-sym) nil
       (not async?)
       (m/-register-function-schema! ns-sym fn-sym schema-form {} :cljs identity)
       :else
       (let [the-fn (-find-js-var ns-sym fn-sym)
             arrow? (try (= :=> (m/type (m/schema schema-form)))
                         (catch :default _ false))]
         (if (and the-fn arrow? (-simple-fixed-arity-fn? the-fn))
           (m/-register-function-schema! ns-sym fn-sym (async-fschema schema-form)
                                         {} :cljs identity)
           (m/-register-function-schema! ns-sym fn-sym schema-form
                                         {:scope #{:input}} :cljs identity))))))


#?(:cljs
   (defn async-fn?
     "True when `f` is a JS async function (the runtime shape `^:async`
      compiles to). Lets [[instrument-from-db!]] route async wrappers
      without the analyzer's `:async` flag — the live var carries the fact."
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
      `:bad-spec` counts unreadable spec strings — both are skipped, not
      fatal."
     [db]
     (if-not (enabled?)
       {:seon.instrument/enabled?       false
        :seon.instrument/n-instrumented 0}
       (let [rows  (db/query '[:find ?sym ?spec
                               :where [?e :seon.fn/sym ?sym]
                                      [?e :seon.fn/spec ?spec]]
                             db)
             stats (volatile! {:registered 0 :skipped 0 :no-var 0 :bad-spec 0})]
         (doseq [[sym-str spec-str] rows
                 :let [slash (str/index-of (str sym-str) "/")]
                 :when slash]
           (let [ns-sym (symbol (subs sym-str 0 slash))
                 fn-sym (symbol (subs sym-str (inc slash)))
                 the-fn (-find-js-var ns-sym fn-sym)
                 schema (try (reader/read-string spec-str)
                             (catch :default _ ::bad))]
             (cond
               (nil? the-fn)    (vswap! stats update :no-var inc)
               (= ::bad schema) (vswap! stats update :bad-spec inc)
               (skip? ns-sym fn-sym) (vswap! stats update :skipped inc)
               :else (do (register-target! ns-sym fn-sym schema (async-fn? the-fn))
                         (vswap! stats update :registered inc)))))
         (mi/instrument! {:report ei/report-fn})
         (assoc @stats
                :seon.instrument/enabled? true
                :seon.instrument/n-instrumented
                (reduce + (map count (vals (m/function-schemas :cljs)))))))))
