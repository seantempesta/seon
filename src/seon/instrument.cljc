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
        :cljs [[clojure.string :as str]
               [goog.object :as gobj]
               [malli.core :as m]
               [malli.instrument :as mi]
               [seon.error.instrument :as ei]])))

(def skip-syms
  "FQ `[ns-sym fn-sym]` pairs whose fns OPT OUT of instrumentation: they
   validate their own args and return errors AS DATA, so an instrumentation
   THROW on bad input would break a documented/tested contract.

   `seon.db/transact!` is SAFE BY DEFAULT — `assert-invocation-shape!`
   returns an error ENVELOPE (`{::db/ok? false}`) for a bad call, never
   throws (tested in `db-test`). Its schema stays the discoverable
   contract; its own guards enforce.

   The opt-out lives here (a symbol set), NOT as fn metadata or a schema
   property, because the CLJS analyzer strips both from the `:malli/schema`
   value the `collect!` macro reads. A FQ-symbol match needs nothing from
   the analyzer. The fn carries a comment pointing back to this set.
   Plain `.cljc` so the compile-time macro and the runtime tee path agree."
  #{['seon.db 'transact!]})

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
       (contains? skip-syms [ns-sym fn-sym]) nil
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
   (defn install!
     "Boot-time call. Runs the compile-time-expanded `collect!` to
      register every first-party `:malli/schema` fn (via
      [[register-target!]], which picks the right wrapper per fn), then
      calls `malli.instrument/instrument!` to install the wrappers in
      place. Validates inputs + outputs on all public fns (async fns
      validate output on Promise resolution).

      No-op when [[enabled?]] is false (`SEON_INSTRUMENT` kill-switch).

      Returns
        {:seon.instrument/enabled? <bool>
         :seon.instrument/n-registered <int>
         :seon.instrument/n-instrumented <int>}.

      Idempotent: re-calling re-registers (no-op; same keys + values)
      and re-instruments (replaces the wrapper)."
     []
     (if-not (enabled?)
       {:seon.instrument/enabled?        false
        :seon.instrument/n-registered    0
        :seon.instrument/n-instrumented  0}
       ;; ONE structural collect (V3-C): every first-party def — the
       ;; core plus the compiled my.* scaffold — by source-file
       ;; location, no name prefixes. Runtime-authored my.* fns aren't on
       ;; the compile-time roster (the analyzer can't see them); they
       ;; validate through the eval path instead.
       ;; `collect!` returns its roster size, but [[register-target!]]
       ;; skips [[skip-syms]] fns, so the ACTUAL registry is smaller —
       ;; count it directly for an honest number.
       (let [_      (collect!)
             n-inst (reduce + (map count (vals (m/function-schemas :cljs))))]
         ;; `mi/instrument!` returns nil (not a count) — it mutates each
         ;; registered fn's var-binding in place to install the validating
         ;; wrapper. n == the registry size since instrument! reads the
         ;; same atom collect! just populated.
         ;;
         ;; Reporter — default is `m/-fail!` (a generic ex-info); we hand
         ;; in `ei/report-fn` so failures throw with the structured
         ;; envelope as ex-data, which seon.error/->map flattens into
         ;; :seon.error/data and record-eval! persists as
         ;; :seon.eval/error-data.
         (mi/instrument! {:report ei/report-fn})
         {:seon.instrument/enabled?       true
          :seon.instrument/n-registered   n-inst
          :seon.instrument/n-instrumented n-inst}))))
