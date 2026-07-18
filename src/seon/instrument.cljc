(ns seon.instrument
  "Exact-data Malli instrumentation for the canonical program graph.

   Cold reconstruction supplies the complete validated DB-backed projection
   once to [[instrument-projection!]]. Later accepted program transitions
   supply only their changed symbols and new contracts to
   [[instrument-delta!]]. Both paths pass explicit `:data` to Malli; Seon
   never populates Malli's process-global function-schema registry and never
   scans it as a second authority.

   The injecting wrapper, async callable-shape detection, and coverage census
   remain colocated here because they operate on the same live function
   objects."
  (:require
    #?@(:cljs [[cljs.reader :as reader]
               [clojure.set :as set]
               [clojure.string :as str]
               [goog.object :as gobj]
               [malli.core :as m]
               [malli.instrument :as mi]
               [seon.config :as config]
               [seon.db :as db]
               [seon.error :as error]
               [seon.error.instrument :as ei]
               [seon.schema :as schema]])))

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
   (defn find-js-var
     "Resolve the live JS fn object for `[ns-sym fn-sym]`, or nil.

      Resolves the namespace through ClojureScript's bootstrap namespace
      owner, which covers development globals and simple-optimized Node module
      scope. THE one instrumentation symbol→live-fn mechanism
      (`seon.ai.typeahead`'s prefill-fn resolution reuses it); nil when the
      fn is not loaded. The result is a raw JS object — a third-party
      boundary, hence `:any`."
     {:malli/schema [:=> [:catn [::ns-sym :symbol] [::fn-sym :symbol]] :any]}
     [ns-sym fn-sym]
     (try
       (when-let [ns-object (cljs.core/find-ns-obj ns-sym)]
         (gobj/get ns-object (munge (name fn-sym))))
       (catch :default _ nil))))

#?(:cljs
   (defn- set-js-var!
     "Replace one live namespace var at the same JS path as `find-js-var`."
     [ns-sym fn-sym f]
     (when-let [ns-object (cljs.core/find-ns-obj ns-sym)]
       (gobj/set ns-object (munge (name fn-sym)) f)
       f)))

#?(:cljs
   (defn- -original-fn
     "See through malli's per-var instrumentation record: a wrapped var's
      live fn carries the ORIGINAL under `malli$instrument$original`
      (set by `malli.instrument/-replace-fn` at wrap time — the same
      record `mi/instrument!` itself re-wraps from). Returns the original
      when `f` is a wrapper, `f` unchanged otherwise. Every shape/async
      detection in this ns goes through this, so re-detection on an
      already-instrumented var reads the REAL fn, never the wrapper —
      the root fix for the old \"wrappers erase asyncness\" class (a
      wrapper is a plain variadic `Function`, so ctor-name async
      detection and `-simple-fixed-arity-fn?` both mis-read it)."
     [f]
     (or (when f (gobj/get f "malli$instrument$original")) f)))

#?(:cljs
   (defn- -simple-fixed-arity-fn?
     "True when `f` is a plain single-fixed-arity fn — NOT variadic, NOT
      multi-arity. CLJS only sets `cljs$lang$maxFixedArity` on
      variadic/multi-arity fns, so its absence is the marker. This is
      exactly the case where `malli.instrument` takes its simple `:else`
      replace path and hands the wrapper RAW args (no `[fixed… rest]`
      marshalling), which is the only shape [[injecting-fschema]]'s wrapper
      validates correctly."
     [f]
     (and (fn? f) (nil? (gobj/get f "cljs$lang$maxFixedArity")))))

#?(:cljs
   (defn- -arrow-schema?
     "True when `schema-form` resolves to a single-arity `:=>` fn schema
      (as opposed to a multi-arity `:function`). Errors-as-values: an
      unresolvable form is simply not an arrow."
     ([schema-form] (-arrow-schema? schema-form {}))
     ([schema-form schema-options]
      (try (= :=> (m/type (m/schema schema-form schema-options)))
           (catch :default _ false)))))

#?(:cljs
   (def injectables
     "The injectable registry for one eval operation.

      The ONE extension surface for explicit dependency injection at the eval
      boundary (`docs/seon/architecture/context.md` §\"Explicit dependencies\").
      A map-in fn declares an injectable as an `{:optional true}` request key;
      [[injecting-fschema]]'s wrapper fills every DECLARED-BUT-ABSENT key from
      the current eval context. Each entry owns its resolver and whether an
      agent may provide a different value. Adding a dependency is ONE entry
      here + fns declaring the key.

      The agent id and operation configuration come from the child fiber's
      existing transaction context. Database values and basis transactions are
      deliberately not injectable: the authority owner acquires ordinary data
      at an explicit database value and passes it in."
     {:seon.agent/id
      {::resolver (fn [_] (db/current-agent-id))
       ::caller-policy ::caller-provided}
      :seon.config/configuration
      {::resolver (fn [_]
                    (:seon.config/configuration (db/current-tx-context)))
       ::caller-policy ::context-only}}))

#?(:cljs
   (defn inject-request
     "Fill one request's declared dependencies from the eval operation.

      Caller-provided dependencies retain their explicit value. A context-only
      dependency may be prefilled by trusted core code, but an agent cannot
      substitute a different value for the operation's immutable value. A
      missing context-only value is a core operation error, never a default."
     {:malli/schema [:=> [:cat :map [:set :keyword]] :map]}
     [request injectable-keys]
     (reduce
       (fn [resolved k]
         (let [{::keys [resolver caller-policy]} (get injectables k)
               operation-value (resolver nil)
               provided? (contains? resolved k)
               provided-value (get resolved k)
               agent-call? (some? (db/current-agent-id))]
           (cond
             (and provided?
                  (= ::context-only caller-policy)
                  agent-call?
                  (not= provided-value operation-value))
             (throw
               (ex-info
                 (str (pr-str k) " is supplied by the current operation and "
                      "cannot be overridden by an agent.")
                 {::injectable-key k
                  :seon.error/kind :agent}))

             provided?
             resolved

             (some? operation-value)
             (assoc resolved k operation-value)

             (= ::context-only caller-policy)
             (throw
               (ex-info
                 (str "The current operation did not supply " (pr-str k) ".")
                 {::injectable-key k
                  :seon.error/kind :core-bug}))

             :else
             resolved)))
       request injectable-keys)))

#?(:cljs
   (defn declared-injectables
     "The set of injectable request-keys a fn's `:=>` `schema-obj` DECLARES on
      its FIRST (map) argument — the intersection of that arg map's keys with
      the [[injectables]] registry. Only optional request entries are
      injectable; required configuration arguments remain ordinary explicit
      function arguments. Computed ONCE per fn at register time
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
           (into #{}
                 (keep (fn [entry]
                         (let [k (key entry)]
                           (when (and (contains? injectables k)
                                      (true? (:optional
                                               (m/properties (val entry)))))
                             k))))
                 (m/entries d))
           #{}))
       (catch :default _ #{}))))

#?(:cljs
   (defn wrapper-fault
     "Refine a wrapper-arm `coarse` fault by the ERROR'S OWN content.

      An AGENT form's failure first surfaces as a rejection inside the
      WRAPPED fns of the eval path (`seon.eval/eval-batch!`,
      `record-eval!`, …) — the outer conduits themselves are NOT
      instrumented (`raw-eval` is private, never in the instrumentation
      targets). So the wrapped fn's symbol alone ('what were we calling')
      misclassifies agent typos
      as `:core` and reds the strict gate forever. Content wins when it
      identifies the population:

        - agent-form eval diagnostics → `:agent` — the agent's own error,
          already enveloped for it downstream (a mistyped attr / bad form /
          undeclared var must NEVER crash the pod). Detected by ANY of:
          `:seon.eval/warning-type`; a `:cljs/analysis-error` `:tag` (a
          cljs.js self-host analysis/compile failure ONLY ever arises from an
          AGENT-submitted form — the core is AOT-compiled by shadow, never
          self-host); or an agent-input `:seon.error/kind`
          (`:user-input`/`:compile`/`:read`/`:seon.eval/repl-parity`, i.e.
          `seon.error/agent-fault-kinds`) at the envelope TOP — every
          kind-bearing throw puts the kind FLAT in its ex-data (one
          convention, C43), `seon.error/->map` flattens deepest-wins and
          LIFTS the kind to the envelope top (the ONE read position,
          C45), so the DEEPEST kind (the real cause) decides;
        - a PROPAGATED malli contract violation → the VIOLATED fn's
          population when agent-authored; else `:agent` when an agent
          turn is in scope (the agent was the caller); else `:agent`
          when a dev/MCP REPL eval is in scope
          (`seon.error/in-dev-eval?`) AND the violation is an INPUT
          contract (`seon.error.instrument/caller-fault-kinds` —
          invalid input/arity is the caller's fault by construction; an
          invalid OUTPUT or a non-contract internal throw stays
          `coarse` — dev presence never excuses our fn breaking); else
          `coarse`. Coarse at the boundary by design —
          misclassifications surface as data (a `:core` datom whose
          frames are all `my.*`) and get re-blamed as follow-up, not
          argued up front;
        - anything else → `coarse` (unclassified bugs stay loud)."
     {:malli/schema [:=> [:cat :any :seon.error/fault] :seon.error/fault]}
     [e coarse]
     (try
       (let [env  (error/->map e)
             data (:seon.error/data env)
             kind (:seon.error/kind env)]
         (cond
           (or (some? (:seon.eval/warning-type data))
               (= :cljs/analysis-error (:tag data))
               (contains? error/agent-fault-kinds kind))
           :agent

           (ei/instrument-error? data)
           (let [violated (:seon.error.malli/fn-sym data)]
             (cond
               (and violated (error/agent-authored-sym? violated)) :agent
               (some? (db/current-agent-id))                       :agent
               (and (error/in-dev-eval?)
                    (contains? ei/caller-fault-kinds kind))        :agent
               :else coarse))

           :else coarse))
       (catch :default _ coarse))))

#?(:cljs
   (defn injecting-fschema
     "A Malli function-schema object for one `:=>` contract.

      Delegates every `Schema`/`FunctionSchema` method to the real `:=>`
      schema EXCEPT `-instrument-f`, which it overrides to
      INJECT-then-validate:

        - INJECT: every declared injectable key ([[injectables]], pre-computed
          via [[declared-injectables]]) is resolved against the first map arg
          BEFORE input validation, so caller-provided dependencies remain
          explicit while context-only dependencies cannot be substituted;
        - input + arity validated synchronously (throws via `report`);
        - the call's return, if a Promise, gets a `.then` validating the
          RESOLVED value against the output schema; a non-thenable return
          validates output synchronously (the sync path). Either way the
          value re-resolves unchanged.

      Handles sync and async map-in functions across fixed, variadic, and
      multi-arity callable shapes. [[prepare-targets]] places one instance at
      every async arrow in Malli's raw `:function` dispatch form, so Malli
      retains all live-var/accessor surgery while this one owner validates the
      resolved domain value. A function declaring no injectable key is
      behavior-identical to the stock wrapper. The resulting object rides in
      Malli's explicit instrumentation data; there is no second registry.

      `fn-sym` (the wrapped fn's QUALIFIED symbol) decides the
      `:seon.error/fault` population once, at register time — \"what were
      we calling\": `my.*`/agent-authored → `:agent`, everything else →
      `:core`. The async arms below call `seon.error/record!` with it so
      both async failure modes become datoms regardless of caller
      behavior (research: malli-instrument-error-data-2026-07-04 §4)."
     {:malli/schema [:function
                     [:=> [:cat :any :qualified-symbol] :any]
                     [:=> [:cat :any :qualified-symbol :map] :any]]}
     ([inner-form fn-sym]
      (injecting-fschema inner-form fn-sym {}))
     ([inner-form fn-sym schema-options]
      (let [s        (m/function-schema inner-form schema-options)
            inj-keys (declared-injectables s)
            fault    (error/fault-for fn-sym)]
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
         (-instrument-f [_ {:keys [scope report gen] :as _props} f _opts]
           (let [{:keys [min max input output]} (m/-function-info s)
                 guard    (:guard (m/-function-info s))
                 scope    (or scope #{:input :output :guard})
                 report   (or report m/-fail!)
                 vin      (m/-validator input)
                 vout     (m/-validator output)
                 vguard   (or (some-> guard m/-validator) any?)
                 wrap-in  (contains? scope :input)
                 wrap-out (contains? scope :output)
                 wrap-guard (contains? scope :guard)
                 f        (or (when gen (gen s)) f
                              (m/-fail! :malli.core/missing-function
                                        {:schema s}))]
             (fn [& args]
               (let [args (vec args)
                     ;; INJECT declared dependencies into the request map before
                     ;; validation; the registry enforces caller policy once.
                     args (if (and (seq inj-keys) (map? (first args)))
                            (assoc args 0 (inject-request (first args) inj-keys))
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
                     ;; REJECTION arm FIRST (upstream of the .then, so an
                     ;; output-violation throw below is not double-recorded):
                     ;; a rejected Promise from an instrumented `^:async` fn
                     ;; was previously observed by NO instrumentation layer.
                     ;; Class rule for the wrapped fns: docs/conventions.md
                     ;; "Errors Are Values" consequence 3 (never reject with
                     ;; an expected error — it records as :core here).
                     ;; record! persists the datom; the re-throw re-rejects
                     ;; the chained Promise with the SAME reason, preserving
                     ;; caller semantics (eval's auto-await / .catch still
                     ;; see the original error).
                     (-> ret
                         (.catch (fn [e]
                                   ;; ONE error → ONE datom: skip when an
                                   ;; inner wrapper already recorded this
                                   ;; propagating rejection.
                                   (when-not (error/recorded? e)
                                     (error/record!
                                       {:seon.error/raw   e
                                        :seon.error/fault (wrapper-fault e fault)}))
                                   (throw e)))
                         (.then (fn [v]
                                  ;; Async output/guard failures become
                                  ;; rejections. Record before the report throw
                                  ;; rides that rejected Promise; the upstream
                                  ;; rejection arm cannot see errors created in
                                  ;; this downstream continuation.
                                  (when (and wrap-out (not (vout v)))
                                    (try (report :malli.core/invalid-output
                                                 {:output output :value v
                                                  :args args :schema s})
                                         (catch :default e
                                           (error/record!
                                             {:seon.error/raw e
                                              :seon.error/fault fault})
                                           (throw e))))
                                  (when (and wrap-guard
                                             (not (vguard [args v])))
                                    (try (report :malli.core/invalid-guard
                                                 {:guard guard :value v
                                                  :args args :schema s})
                                         (catch :default e
                                           (error/record!
                                             {:seon.error/raw e
                                              :seon.error/fault fault})
                                           (throw e))))
                                  v)))
                     (do (when (and wrap-out (not (vout ret)))
                           (report :malli.core/invalid-output
                                   {:output output :value ret
                                    :args args :schema s}))
                         (when (and wrap-guard
                                    (not (vguard [args ret])))
                           (report :malli.core/invalid-guard
                                   {:guard guard :value ret
                                    :args args :schema s}))
                         ret))))))))))))

#?(:cljs
   (defn async-fn?
     "True when `f` or one of its original accessors is async.

      ClojureScript emits native `AsyncFunction` objects for the outer
      callable plus fixed and variadic accessors. Malli's multi-arity
      `meta-fn` makes the outer callable an ordinary `Function`, but its copied
      accessors retain `malli$instrument$original` links to those async
      objects. Inspecting the original outer plus every original accessor
      therefore survives cold wrapping, delta refresh, and reconciliation
      without a persisted async flag."
     {:malli/schema [:=> [:cat :any] :boolean]}
     [f]
     (let [f (-original-fn f)
           max-fixed (when (fn? f) (gobj/get f "cljs$lang$maxFixedArity"))
           accessors
           (when (number? max-fixed)
             (cond->
               (keep (fn [arity]
                       (gobj/get
                         f
                         (str "cljs$core$IFn$_invoke$arity$" arity)))
                     (range (inc max-fixed)))
               (fn? (gobj/get f "cljs$core$IFn$_invoke$arity$variadic"))
               (conj (gobj/get f "cljs$core$IFn$_invoke$arity$variadic"))))
           native-async?
           (fn [candidate]
             (let [candidate (-original-fn candidate)]
               (and (fn? candidate)
                    (= "AsyncFunction"
                       (.. candidate -constructor -name)))))]
       (boolean (or (native-async? f) (some native-async? accessors))))))

#?(:cljs
   (defn- async-function-form
     "Raw Malli `:function` form with one Seon schema object per arity."
     [function-schema sym options]
     (into [:function]
           (map #(injecting-fschema % sym options))
           (m/-function-schema-arities function-schema))))

#?(:cljs
   (defn- qualified-target-parts
     "The namespace/name pair for qualified `sym`, or nil."
     [sym]
     (when (and (symbol? sym) (namespace sym))
       [(symbol (namespace sym)) (symbol (name sym))])))

#?(:cljs
   (defn- malli-entry
     "Malli's required third-party entry for one compiled function schema."
     [ns-sym fn-sym function-schema]
     ;; `:schema`, `:ns`, and `:name` are Malli's API keys, not Seon data.
     {:schema function-schema :ns ns-sym :name fn-sym}))

#?(:cljs
   (defn- live-arity-profile
     "Exact callable arities advertised by one original CLJS function."
     [f]
     (let [max-fixed (gobj/get f "cljs$lang$maxFixedArity")
           variadic? (fn? (gobj/get f "cljs$core$IFn$_invoke$arity$variadic"))
           fixed-arities
           (if (number? max-fixed)
             (into (sorted-set)
                   (filter
                     (fn [arity]
                       (let [accessor
                             (gobj/get
                               f
                               (str "cljs$core$IFn$_invoke$arity$" arity))]
                         (and
                           (fn? accessor)
                           (not
                             (true?
                               (gobj/get
                                 accessor
                                 "seon$instrument$variadicMaxBridge")))))))
                   (range (inc max-fixed)))
             (sorted-set (.-length f)))]
       (cond-> {::fixed-arities fixed-arities}
         variadic?
         (assoc ::variadic {::min max-fixed})))))

#?(:cljs
   (defn- schema-arity-profile
     "Exact fixed and ranged arities described by a compiled function schema."
     [function-schema]
     (let [infos (mapv m/-function-info
                       (m/-function-schema-arities function-schema))
           fixed-arities
           (into (sorted-set)
                 (keep (fn [{:keys [arity]}]
                         (when (int? arity) arity)))
                 infos)
           variadic-info (some #(when (= :varargs (:arity %)) %) infos)]
       (cond-> {::fixed-arities fixed-arities}
         variadic-info
         (assoc ::variadic
                (cond-> {::min (:min variadic-info)}
                  (some? (:max variadic-info))
                  (assoc ::max (:max variadic-info))))))))

#?(:cljs
   (defn- pure-variadic-profile?
     "True when Malli instruments the whole variadic function, not accessors."
     [live-profile]
     (and (empty? (::fixed-arities live-profile))
          (some? (::variadic live-profile)))))

#?(:cljs
   (defn- pure-variadic-schema-compatible?
     "True when every contract arity is callable by a pure variadic function."
     [live-profile schema-profile]
     (let [live-min (get-in live-profile [::variadic ::min])
           schema-min (get-in schema-profile [::variadic ::min])]
       (and (every? #(<= live-min %) (::fixed-arities schema-profile))
            (or (nil? schema-min) (<= live-min schema-min))))))

#?(:cljs
   (defn- arity-mismatch
     "A structured unsafe live/schema arity mismatch, or nil when compatible."
     [sym the-fn function-schema]
     (let [live-profile (live-arity-profile the-fn)
           schema-profile (schema-arity-profile function-schema)]
       (when-not (if (pure-variadic-profile? live-profile)
                   (pure-variadic-schema-compatible?
                     live-profile schema-profile)
                   (= live-profile schema-profile))
         {::sym sym
          ::reason ::arity-mismatch
          ::live-arities live-profile
          ::schema-arities schema-profile}))))

#?(:cljs
   (defn- prepare-target
     "Compile one Seon target into Malli data or a namespaced rejection."
     [{::keys [sym schema-form registry]}]
     (if-let [[ns-sym fn-sym] (qualified-target-parts sym)]
       (let [the-fn (-original-fn (find-js-var ns-sym fn-sym))
             options (cond-> {} registry (assoc :registry registry))]
         (cond
           (nil? the-fn)
           {::sym sym ::reason ::no-var}

           :else
           (try
             (let [async? (async-fn? the-fn)
                   arrow? (-arrow-schema? schema-form options)
                   function-schema (m/function-schema schema-form options)]
               (if-let [mismatch
                        (arity-mismatch sym the-fn function-schema)]
                 mismatch
                 (let [compiled
                       (cond
                         (and async? arrow?)
                         (injecting-fschema schema-form sym options)

                         async?
                         ;; Malli's CLJS multi-arity surgery calls `rest` on
                         ;; this raw outer form. Each child may itself be a
                         ;; compiled FunctionSchema object, so all accessor
                         ;; replacement stays inside Malli while Seon's one
                         ;; wrapper owns Promise resolution.
                         (async-function-form function-schema sym options)

                         (and arrow? (-simple-fixed-arity-fn? the-fn))
                         (injecting-fschema schema-form sym options)

                         :else schema-form)]
                   {::sym sym
                    ::ns ns-sym
                    ::name fn-sym
                    ::entry (malli-entry ns-sym fn-sym compiled)})))
             (catch :default _
               {::sym sym ::reason ::unresolvable-schema}))))
       {::sym sym ::reason ::invalid-symbol})))

#?(:cljs
   (defn prepare-targets
     "Build exact Malli data for the supplied canonical function targets.

      Each target is a namespaced map containing `::sym` (a qualified
      symbol), `::schema-form`, and optionally `::registry` (an immutable
      Malli registry projection). The result separates accepted target data
      from rejected rows; it never mutates Malli's function-schema registry."
     {:malli/schema
      [:=>
       [:cat [:sequential
              [:map
               [::sym :qualified-symbol]
               [::schema-form :any]
               [::registry {:optional true} :any]]]]
       [:map
        [::data :any]
        [::accepted-syms [:set :qualified-symbol]]
        [::rejected [:vector
                     [:map [::sym :qualified-symbol]
                      [::reason :keyword]]]]]]}
     [targets]
     (let [prepared (mapv prepare-target targets)
           accepted (filter ::entry prepared)]
       {::data
        (reduce (fn [data {::keys [ns name entry]}]
                  (assoc-in data [ns name] entry))
                {}
                accepted)
        ::accepted-syms (into #{} (map ::sym) accepted)
        ::rejected (into []
                         (comp
                           (filter ::reason)
                           (map #(select-keys % [::sym ::reason
                                                 ::live-arities
                                                 ::schema-arities])))
                         prepared)})))

#?(:cljs
   (defn- symbol-data
     "Exact Malli selection data for qualified symbols being unstrumented."
     [syms]
     (reduce
       (fn [data sym]
         (if-let [[ns-sym fn-sym] (qualified-target-parts sym)]
           (assoc-in data [ns-sym fn-sym]
                     ;; Unstrument ignores the entry body. Retain Malli's
                     ;; canonical ns/name shape for diagnostic filters.
                     (malli-entry ns-sym fn-sym nil))
           data))
       {}
       syms)))

#?(:cljs
   (defn- install-variadic-max-bridges!
     "Make minimum-variadic direct calls use Malli's replaced accessor."
     [syms]
     (doseq [sym syms
             :let [[ns-sym fn-sym] (qualified-target-parts sym)
                   f (when ns-sym (find-js-var ns-sym fn-sym))
                   max-fixed (when f (gobj/get f "cljs$lang$maxFixedArity"))
                   fixed-key (when (number? max-fixed)
                               (str "cljs$core$IFn$_invoke$arity$" max-fixed))
                   fixed (when fixed-key (gobj/get f fixed-key))
                   variadic (when f
                              (gobj/get
                                f
                                "cljs$core$IFn$_invoke$arity$variadic"))]
             :when (and fixed-key (not (fn? fixed)) (fn? variadic))]
       ;; Exact CLJS 1.12.145 emits no fixed accessor at a variadic method's
       ;; minimum arity. A compiled direct call at exactly that arity therefore
       ;; falls back to the async outer dispatcher and bypasses Malli's replaced
       ;; variadic accessor. Add the missing callable bridge to Malli's live
       ;; wrapper, never the original function. Malli's own unstrument walk
       ;; clears it because the bridge deliberately has no `original` link.
       (let [bridge (fn [& args] (apply variadic args))]
         (gobj/set bridge "seon$instrument$variadicMaxBridge" true)
         (gobj/set f fixed-key bridge)))))

#?(:cljs
   (def ^:private original-multi-wrapper-property
     "seon$instrument$originalMultiWrapper"))

#?(:cljs
   (defn- restore-multi-wrapper-vars!
     "Restore multi/variadic vars before Malli walks the remaining wrappers.

      Malli's CLJS `meta-fn` binds a new outer function on every instrumentation
      pass, but its multi-arity unstrument path restores only copied accessors;
      it leaves that bound outer function installed. Retaining one outer
      function per publication also retains the complete compiled schema graph.
      The marker is carried by the live Malli wrapper itself, not a second
      registry, and points at the one prior callable that must be restored."
     [syms]
     (doseq [sym syms
             :let [[ns-sym fn-sym] (qualified-target-parts sym)
                   live (when ns-sym (find-js-var ns-sym fn-sym))
                   original (when live
                              (gobj/get live
                                        original-multi-wrapper-property))]
             :when original]
       (gobj/remove live original-multi-wrapper-property)
       (gobj/remove original "malli$instrument$instrumented?")
       (set-js-var! ns-sym fn-sym original))))

#?(:cljs
   (defn- original-accessor
     "Follow Malli's accessor links to the one uninstrumented callable."
     [f]
     (loop [current f
            seen #{}]
       (let [original (when current
                        (gobj/get current "malli$instrument$original"))]
         (if (and original (not (contains? seen original)))
           (recur original (conj seen original))
           current)))))

#?(:cljs
   (defn- restore-original-accessors!
     "Collapse multi/variadic accessors after Malli removes its wrappers.

      Malli restores only the immediately preceding variadic wrapper. Repeated
      publications otherwise retain one compiled schema generation per link."
     [syms]
     (doseq [sym syms
             :let [[ns-sym fn-sym] (qualified-target-parts sym)
                   f (when ns-sym (find-js-var ns-sym fn-sym))]
             :when f
             property (js->clj (js/Object.keys f))
             :when (str/starts-with?
                     property "cljs$core$IFn$_invoke$arity$")
             :let [accessor (gobj/get f property)
                   original (when (fn? accessor)
                              (original-accessor accessor))]
             :when (and original (not (identical? accessor original)))]
       (gobj/set f property original))))

#?(:cljs
   (defn- clear-instrumentation-markers!
     "Clear Malli's stale in-place marker and any Seon variadic bridge."
     [syms]
     (doseq [sym syms
             :let [[ns-sym fn-sym] (qualified-target-parts sym)
                   f (when ns-sym (find-js-var ns-sym fn-sym))]
             :when f]
       ;; Malli 0.20.0 restores multi/variadic accessors but leaves this flag
       ;; on the live function object. That makes removed targets look wrapped
       ;; to coverage/reconciliation. Seon's exact-data owner completes the
       ;; removal instead of teaching every reader to reinterpret stale state.
       (gobj/remove f "malli$instrument$instrumented?")
       (when-let [max-fixed (gobj/get f "cljs$lang$maxFixedArity")]
         (let [fixed-key (str "cljs$core$IFn$_invoke$arity$" max-fixed)
               fixed (gobj/get f fixed-key)]
           (when (true? (some-> fixed
                                (gobj/get "seon$instrument$variadicMaxBridge")))
             (gobj/remove f fixed-key)))))))

#?(:cljs
   (defn- unstrument-data!
     "Remove Malli data and complete its exact unwrapped callable state."
     [data syms]
     (when (seq data)
       (restore-multi-wrapper-vars! syms)
       (mi/unstrument! {:data data})
       (restore-original-accessors! syms)
       (clear-instrumentation-markers! syms))))

#?(:cljs
   (defn- instrument-data!
     "Apply Malli data and complete its exact variadic callable shape."
     [data accepted-syms]
     (when (seq data)
       (let [originals
             (into {}
                   (keep (fn [sym]
                           (let [[ns-sym fn-sym]
                                 (qualified-target-parts sym)]
                             (when-let [f (and ns-sym
                                               (find-js-var ns-sym fn-sym))]
                               [sym f]))))
                   accepted-syms)]
         (mi/instrument! {:data data :report ei/report-fn})
         (doseq [[sym original] originals
                 :when (some? (gobj/get original
                                         "cljs$lang$maxFixedArity"))
                 :let [[ns-sym fn-sym] (qualified-target-parts sym)
                       live (find-js-var ns-sym fn-sym)]
                 :when (and live (not (identical? live original)))]
           (gobj/set live original-multi-wrapper-property original)))
       (install-variadic-max-bridges! accepted-syms))))

#?(:cljs
   (defn instrument-targets!
     "Instrument the complete supplied target set exactly once.

      This is the cold-reconstruction primitive. It calls Malli once with
      explicit `:data`; the process-global function-schema registry is neither
      read nor written. Returns preparation and instrumentation counts."
     {:malli/schema
      [:=>
       [:cat [:sequential
              [:map
               [::sym :qualified-symbol]
               [::schema-form :any]
               [::registry {:optional true} :any]]]]
       :map]}
     [targets]
     (if-not (enabled?)
       {::enabled? false ::ok? true ::n-instrumented 0
        ::accepted-syms #{} ::rejected []}
       (let [{::keys [data accepted-syms rejected] :as prepared}
             (prepare-targets targets)
             fatal-rejections (remove #(= ::no-var (::reason %)) rejected)]
         ;; Malli mutates each target as it walks `:data`. Reject the complete
         ;; candidate before that walk so one invalid arity contract cannot
         ;; leave an earlier target wrapped and a later target corrupted.
         (if (seq fatal-rejections)
           (assoc prepared
                  ::enabled? true
                  ::ok? false
                  ::n-instrumented 0)
           (do
             (instrument-data! data accepted-syms)
             (assoc prepared
                    ::enabled? true
                    ::ok? true
                    ::n-instrumented (count accepted-syms))))))))

#?(:cljs
   (defn instrument-delta!
     "Refresh only symbols accepted by one committed program transition.

      `::changed-syms` includes definitions whose new contract is absent
      (spec removal or deletion). `::targets` contains the new contracts for
      the surviving specced functions. Malli first restores wrappers for the
      exact changed set, then instruments only accepted new targets. An
      unaffected live function is never inspected or mutated."
     {:malli/schema
      [:=>
       [:cat
        [:map
         [::changed-syms [:set :qualified-symbol]]
         [::targets [:sequential
                     [:map
                      [::sym :qualified-symbol]
                      [::schema-form :any]
                      [::registry {:optional true} :any]]]]]]
       :map]}
     [{::keys [changed-syms targets]}]
     (if-not (enabled?)
       {::enabled? false ::n-unstrumented 0 ::n-instrumented 0
        ::accepted-syms #{} ::rejected []}
       (let [{::keys [data accepted-syms rejected] :as prepared}
             (prepare-targets targets)
             fatal-rejections (remove #(= ::no-var (::reason %)) rejected)]
         ;; Compile the complete replacement set before touching a live var.
         ;; A bad target must not remove its still-valid old wrapper and leave
         ;; the runtime in a mixed generation.
         (if (seq fatal-rejections)
           (assoc prepared
                  ::enabled? true
                  ::ok? false
                  ::n-unstrumented 0
                  ::n-instrumented 0)
           (let [old-data (symbol-data changed-syms)]
             (unstrument-data! old-data changed-syms)
             (instrument-data! data accepted-syms)
             (assoc prepared
                    ::enabled? true
                    ::ok? true
                    ::n-unstrumented (count changed-syms)
                    ::n-instrumented (count accepted-syms))))))))

#?(:cljs
   (defn instrument-projection!
     "Instrument one complete validated runtime projection at cold boot.

      The candidate already owns canonical function forms and the exact Malli
      registry that validated them. No database rescan, EDN reparse, or global
      function-schema registry participates in this publication step."
     {:malli/schema [:=> [:catn [::projection :map]] :map]}
     [projection]
     (if-not (enabled?)
       {::enabled? false ::n-instrumented 0}
       (let [registry (:seon.schema.projection/registry projection)
             targets
             (mapv (fn [[sym form]]
                     {::sym sym ::schema-form form ::registry registry})
                   (:seon.schema.projection/function-contracts projection))
             result (instrument-targets! targets)
             rejected-counts (frequencies (map ::reason (::rejected result)))]
         (assoc result
                ::registered (::n-instrumented result)
                ::bad-spec 0
                ::no-var (get rejected-counts ::no-var 0)
                ::unresolvable-schema
                (get rejected-counts ::unresolvable-schema 0))))))

#?(:cljs
   (defn- live-wrapper?
     "True when the qualified symbol currently carries one Malli wrapper."
     [sym]
     (when-let [[ns-sym fn-sym] (qualified-target-parts sym)]
       (when-let [f (find-js-var ns-sym fn-sym)]
         (or (some? (gobj/get f "malli$instrument$original"))
             (and (some? (gobj/get f "malli$instrument$instrumented?"))
                  (not (-simple-fixed-arity-fn? f))))))))

#?(:cljs
   (defn reconcile-projection!
     "Rebuild all committed wrappers behind a closed runtime-admission gate.

      Preparation validates the complete new target population before Malli
      mutates a var. Reconciliation then unstruments the union of old and new
      symbols, instruments the complete accepted generation, and verifies both
      positive and removed wrapper state. The caller owns
      admission, retry, projection publication, and fault recording."
     {:malli/schema
      [:=>
       [:cat
        [:map
         [::old-projection {:optional true} [:maybe :map]]
         [::new-projection :map]]]
       :map]}
     [{::keys [old-projection new-projection]}]
     (if-not (enabled?)
       {::enabled? false ::ok? true ::n-unstrumented 0
        ::n-instrumented 0 ::accepted-syms #{} ::rejected []
        ::verification-gaps []}
       (let [registry (:seon.schema.projection/registry new-projection)
             old-syms
             (set (keys
                    (:seon.schema.projection/function-contracts
                      old-projection)))
             new-contracts
             (:seon.schema.projection/function-contracts new-projection)
             new-syms (set (keys new-contracts))
             all-syms (into old-syms new-syms)
             targets
             (mapv (fn [[sym form]]
                     {::sym sym ::schema-form form ::registry registry})
                   new-contracts)
             {::keys [data accepted-syms rejected] :as prepared}
             (prepare-targets targets)
             fatal-rejections (remove #(= ::no-var (::reason %)) rejected)]
         (if (seq fatal-rejections)
           (assoc prepared
                  ::enabled? true
                  ::ok? false
                  ::n-unstrumented 0
                  ::n-instrumented 0
                  ::verification-gaps [])
           (let [old-data (symbol-data all-syms)
                 _ (unstrument-data! old-data all-syms)
                 _ (instrument-data! data accepted-syms)
                 verification-gaps
                 (into []
                       (keep
                         (fn [sym]
                           (let [expected? (contains? accepted-syms sym)
                                 actual? (boolean (live-wrapper? sym))]
                             (when (not= expected? actual?)
                               {::sym sym
                                ::expected-wrapped? expected?
                                ::actual-wrapped? actual?}))))
                       all-syms)]
             (assoc prepared
                    ::enabled? true
                    ::ok? (empty? verification-gaps)
                    ::n-unstrumented (count all-syms)
                    ::n-instrumented (count accepted-syms)
                    ::verification-gaps verification-gaps)))))))

#?(:cljs
   (defn instrument-projection-delta!
     "Publish exactly one validated program/schema projection delta.

      Directly changed definitions are unioned with function contracts whose
      old/new schema dependencies intersect the changed schema closure. The
      candidate already contains parsed, validated forms and indexes, so this
      performs no database query and no whole-program schema walk."
     {:malli/schema
      [:=>
       [:cat
        [:map
         [::old-projection :map]
         [::new-projection :map]
         [::changed-schema-keys [:set :keyword]]
         [::changed-syms [:set :qualified-symbol]]]]
       :map]}
     [{::keys [old-projection new-projection changed-schema-keys changed-syms]}]
     (if-not (enabled?)
       {::enabled? false ::ok? true ::n-dependent 0
        ::n-unstrumented 0 ::n-instrumented 0 ::accepted-syms #{} ::rejected []}
       (let [affected
             (into
               (schema/dependent-schema-keys old-projection
                                             changed-schema-keys)
               (schema/dependent-schema-keys new-projection
                                             changed-schema-keys))
             old-function-dependencies
             (:seon.schema.projection/function-dependencies old-projection)
             new-function-dependencies
             (:seon.schema.projection/function-dependencies new-projection)
             dependent-syms
             (into #{}
                   (keep
                     (fn [sym]
                       (let [references
                             (into (get old-function-dependencies sym #{})
                                   (get new-function-dependencies sym #{}))]
                         (when (seq (set/intersection affected references))
                           sym))))
                   (into (set (keys old-function-dependencies))
                         (keys new-function-dependencies)))
             selected-syms (into (set changed-syms) dependent-syms)
             registry (:seon.schema.projection/registry new-projection)
             contracts
             (:seon.schema.projection/function-contracts new-projection)
             targets
             (into []
                   (keep (fn [sym]
                           (when-let [form (get contracts sym)]
                             {::sym sym ::schema-form form
                              ::registry registry})))
                   selected-syms)
             result (instrument-delta!
                      {::changed-syms selected-syms ::targets targets})]
         (assoc result
                ::n-dependent (count dependent-syms))))))

#?(:cljs
   (defn- coverage-gap
     "A derived live-wrapper gap for one canonical row, or nil."
     [registry [sym-str spec-str]]
     (when-let [slash (str/index-of (str sym-str) "/")]
       (let [ns-sym (symbol (subs sym-str 0 slash))
             fn-sym (symbol (subs sym-str (inc slash)))
             target-sym (symbol sym-str)
             f (find-js-var ns-sym fn-sym)
             ;; A simple wrapper records its original on the wrapper. Malli
             ;; instruments multi-arity fns in place, so their live original
             ;; carries the instrumentation flag instead.
             wrapped? (and f
                           (or (some? (gobj/get f "malli$instrument$original"))
                               (and (some? (gobj/get f "malli$instrument$instrumented?"))
                                    (not (-simple-fixed-arity-fn? f)))))]
         (when (and f (not wrapped?))
           (let [schema (try (reader/read-string spec-str)
                             (catch :default _ ::bad))
                 resolves? (when-not (= ::bad schema)
                             (try (m/schema schema
                                            (cond-> {}
                                              registry
                                              (assoc :registry registry)))
                                  true
                                  (catch :default _ false)))]
             (cond
               (= ::bad schema)
               {::sym sym-str ::target-sym target-sym ::reason ::bad-spec}

               (not resolves?)
               {::sym sym-str ::target-sym target-sym
                ::schema-form schema ::reason ::unresolvable-schema}

               :else
               {::sym sym-str ::target-sym target-sym
                ::schema-form schema ::reason ::unwrapped})))))))

#?(:cljs
   (defn- coverage-gaps-for-rows [registry rows]
     (into [] (keep #(coverage-gap registry %)) rows)))

#?(:cljs
   (defn coverage-gaps
     "Specced program-graph rows whose live var has no Malli wrapper.

      This is a derived invariant over canonical DB rows plus live function
      objects; it stores no registry or dirty flag. Rows with no live var are
      not gaps. Every live canonical contract is wrapped or appears here as an
      unwrapped, unreadable, or currently unresolvable contract."
     {:malli/schema [:=> [:cat [:coll [:tuple :string :string]]]
                     [:vector [:map
                               [::sym :string]
                               [::reason :keyword]]]]}
     [rows]
     (if-not (enabled?)
       []
       (let [registry
             (:seon.schema.projection/registry
               (schema/current-projection))]
         (mapv #(select-keys % [::sym ::reason])
               (coverage-gaps-for-rows registry (sort-by first rows)))))))
