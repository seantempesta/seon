(ns seon.instrument
  "Exact-data Malli instrumentation for the canonical program graph.

   Cold reconstruction supplies the complete DB-backed function snapshot
   once to [[instrument-from-db!]]. Later accepted program transitions
   supply only their changed symbols and new contracts to
   [[instrument-delta!]]. Both paths pass explicit `:data` to Malli; Seon
   never populates Malli's process-global function-schema registry and never
   scans it as a second authority.

   The injecting wrapper, structural async opt-out, and coverage census
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

      Walks the munged goog.global path — the same lookup malli.instrument
      uses to find the var it wraps. THE one symbol→live-fn mechanism
      (`seon.ai.typeahead`'s prefill-fn resolution reuses it); nil when the
      fn is not loaded. The result is a raw JS object — a third-party
      boundary, hence `:any`."
     {:malli/schema [:=> [:catn [::ns-sym :symbol] [::fn-sym :symbol]] :any]}
     [ns-sym fn-sym]
     (try
       (gobj/getValueByKeys
         js/goog.global
         (into-array (map munge (conj (str/split (str ns-sym) #"\.")
                                      (name fn-sym)))))
       (catch :default _ nil))))

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
      marshalling), which is the only shape [[async-fschema]]'s wrapper
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
   (defn async-unwrappable?
     "True when an `^:async` fn has NO correct wrapper today — the
      STRUCTURAL instrumentation opt-out (computed from real properties;
      never a name list).

      An async fn returns a `js/Promise`, so only the Promise-aware
      [[injecting-fschema]] wrapper validates it correctly — and that
      wrapper handles exactly the simple single-fixed-arity `:=>` shape.
      For any OTHER async shape (variadic / multi-arity / `:function`
      schema / live var unresolvable) every available wrapper is wrong:

        - malli's stock SYNC wrapper validates the returned Promise
          against the output schema → `:malli.core/invalid-output` on
          EVERY call;
        - the input-only stock wrapper (`{:scope #{:input}}`) THROWS on a
          shape-invalid call, which breaks the errors-as-values contract
          async envelope functions carry — the canonical instance is
          `seon.db/transact!`, whose bad-invocation-shape → `{::ok?
          false}` ENVELOPE behavior is documented and pinned by
          `db_test/transact!-returns-envelope-on-bad-invocation-shape`,
          and which core internals (boot, tickers, the loop) call under
          the never-throw-into-the-loop invariant.

      So: async ∧ not(injecting-wrappable) ⇒ register NOTHING. The
      `:malli/schema` stays the discoverable contract; the fn's own body
      is the validation boundary. When a Promise-aware wrapper for
      variadic/multi-arity shapes exists, this rule collapses to false
      and those fns instrument like everything else."
     {:malli/schema [:=> [:cat :boolean :any :any] :boolean]}
     [async? the-fn schema-form]
     (boolean
       (and async?
            (not (and the-fn
                      (-arrow-schema? schema-form)
                      (-simple-fixed-arity-fn? the-fn)))))))

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
     {:seon.db/db     (fn [_] (some-> db/*conn* deref))
      :seon.agent/id  (fn [_] (db/current-agent-id))
      ;; \"now\" — the basis-t (tx-id int) of the current db value, the turn's
      ;; reproducible time coordinate (replay = pass a past t explicitly;
      ;; explicit wins). Schema registered in `seon.render`.
      :seon.render/at (fn [_] (some-> db/*conn* deref db/basis-t))}))

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
   (defn wrapper-fault
     "Refine a wrapper-arm `coarse` fault by the ERROR'S OWN content.

      An AGENT form's failure first surfaces as a rejection inside the
      WRAPPED fns of the eval path (`seon.eval/eval-batch!`,
      `record-eval!`, …) — the outer conduits themselves are NOT
      instrumented (`seon.eval/eval` is a structural [[async-unwrappable?]]
      opt-out; `raw-eval` is private, never in the instrumentation targets). So the wrapped
      fn's symbol alone ('what were we calling') misclassifies agent typos
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
      [[prepare-targets]] routes every simple fixed-arity `:=>` fn here. This
      is the ONE injecting boundary — no second wrapper. The resulting object
      rides in Malli's explicit instrumentation data, so stock
      `mi/instrument!` reuses all its var surgery without a second registry.

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
                                  (when (and wrap-out (not (vout v)))
                                    ;; An async invalid-output becomes a
                                    ;; REJECTION, not a sync throw — visible
                                    ;; only if the caller awaits/catches. So
                                    ;; record! runs HERE, before the report
                                    ;; throw rides the rejected Promise.
                                    ;; No refinement: the wrapped fn ITSELF
                                    ;; broke its output contract, so its own
                                    ;; population is the right fault.
                                    (try (report :malli.core/invalid-output
                                                 {:output output :value v
                                                  :args args :schema s})
                                         (catch :default e
                                           (error/record! {:seon.error/raw   e
                                                           :seon.error/fault fault})
                                           (throw e))))
                                  v)))
                     (do (when (and wrap-out (not (vout ret)))
                           (report :malli.core/invalid-output
                                   {:output output :value ret
                                    :args args :schema s}))
                         ret))))))))))))

#?(:cljs
   (defn async-fn?
     "True when `f` is (or wraps) a JS async function — the runtime shape
      `^:async` compiles to. Lets [[instrument-from-db!]] route async
      wrappers without the analyzer's `:async` flag. Sees THROUGH a malli
      instrumentation wrapper ([[-original-fn]]): the ctor-name check is
      only ever applied to the real fn, so an already-instrumented
      `^:async` fn (whose wrapper is a plain `Function` returning a
      Promise) is still detected async — re-instrumentation is
      detection-safe by construction."
     {:malli/schema [:=> [:cat :any] :boolean]}
     [f]
     (let [f (-original-fn f)]
       (and (fn? f) (= "AsyncFunction" (.. f -constructor -name))))))

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
             (let [async? (async-fn? the-fn)]
               (if (async-unwrappable? async? the-fn schema-form)
                 {::sym sym ::reason ::skipped}
                 (let [compiled
                       (if (and (-arrow-schema? schema-form options)
                                (-simple-fixed-arity-fn? the-fn))
                         (injecting-fschema schema-form sym options)
                         ;; Malli's CLJS multi-arity surgery calls `rest` on
                         ;; the `:function` FORM (`-arity->schema`), so this
                         ;; entry must remain the raw form. Compile it first
                         ;; to reject unresolved contracts before mutation.
                         (do (m/function-schema schema-form options)
                             schema-form))]
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
                           (map #(select-keys % [::sym ::reason])))
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
       {::enabled? false ::n-instrumented 0 ::accepted-syms #{} ::rejected []}
       (let [{::keys [data accepted-syms rejected] :as prepared}
             (prepare-targets targets)]
         (when (seq data)
           (mi/instrument! {:data data :report ei/report-fn}))
         (assoc prepared
                ::enabled? true
                ::n-instrumented (count accepted-syms))))))

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
             fatal-rejections (remove #(= ::skipped (::reason %)) rejected)]
         ;; Compile the complete replacement set before touching a live var.
         ;; A bad target must not remove its still-valid old wrapper and leave
         ;; the runtime in a mixed generation. Structural async opt-outs are
         ;; intentional removals and therefore are not fatal.
         (if (seq fatal-rejections)
           (assoc prepared
                  ::enabled? true
                  ::ok? false
                  ::n-unstrumented 0
                  ::n-instrumented 0)
           (let [old-data (symbol-data changed-syms)]
             (when (seq old-data)
               (mi/unstrument! {:data old-data}))
             (when (seq data)
               (mi/instrument! {:data data :report ei/report-fn}))
             (assoc prepared
                    ::enabled? true
                    ::ok? true
                    ::n-unstrumented (count changed-syms)
                    ::n-instrumented (count accepted-syms))))))))

#?(:cljs
   (defn instrument-from-db!
     "Instrument the complete canonical DB function snapshot once at boot.

      Persisted specs are parsed into exact targets and handed to
      [[instrument-targets!]]. Unreadable, unresolved, dead, and structural
      opt-out rows degrade as namespaced counts without entering Malli's
      instrumentation data. Runtime mint/resume and hot reload must use
      [[instrument-delta!]], never this complete query."
     {:malli/schema [:=> [:cat :any] :map]}
     [db]
     (if-not (enabled?)
       {::enabled? false ::n-instrumented 0}
       (let [projection (schema/current-projection)
             registry (:seon.schema.projection/registry projection)
             rows (db/query '[:find ?sym ?spec
                              :where [?e :seon.fn/sym ?sym]
                                     [?e :seon.fn/spec ?spec]]
                            db)
             parsed
             (reduce
               (fn [{::keys [targets] :as acc} [sym-str spec-str]]
                 (try
                   (let [sym (symbol sym-str)
                         form (reader/read-string spec-str)]
                     (if (qualified-target-parts sym)
                       (update acc ::targets conj
                               (cond-> {::sym sym ::schema-form form}
                                 registry (assoc ::registry registry)))
                       (update acc ::bad-spec inc)))
                   (catch :default _
                     (update acc ::bad-spec inc))))
               {::targets [] ::bad-spec 0}
               rows)
             result (instrument-targets! (::targets parsed))
             rejected-counts (frequencies (map ::reason (::rejected result)))]
         (assoc result
                ::registered (::n-instrumented result)
                ::bad-spec (::bad-spec parsed)
                ::skipped (get rejected-counts ::skipped 0)
                ::no-var (get rejected-counts ::no-var 0)
                ::unresolvable-schema
                (get rejected-counts ::unresolvable-schema 0))))))

#?(:cljs
   (defn- program-schema-rows
     "The canonical qualified-symbol/spec rows, in stable order."
     [db]
     (sort-by first
              (db/query '[:find ?sym ?spec
                          :where [?e :seon.fn/sym ?sym]
                                 [?e :seon.fn/spec ?spec]]
                        db))))

#?(:cljs
   (defn instrument-schema-dependents-from-db!
     "Refresh functions affected by one accepted schema-generation change.

      The database supplies canonical function contracts. Malli's dependency
      graph supplies the affected closure in both the old and new projections,
      so removing an old edge still refreshes its former dependents. Only
      selected functions become delta targets; unrelated live wrappers retain
      object identity. Replacement contracts compile against the exact new
      immutable registry before any live wrapper is touched."
     {:malli/schema
      [:=>
       [:cat
        [:map
         [::db :any]
         [::old-projection :map]
         [::new-projection :map]
         [::changed-schema-keys [:set :keyword]]]]
       :map]}
     [{::keys [db old-projection new-projection changed-schema-keys]}]
     (if (or (not (enabled?)) (empty? changed-schema-keys))
       {::enabled? (enabled?) ::ok? true ::n-inspected 0 ::n-dependent 0
        ::n-unstrumented 0 ::n-instrumented 0 ::accepted-syms #{}
        ::rejected []}
       (let [affected
             (into
               (schema/dependent-schema-keys old-projection
                                             changed-schema-keys)
               (schema/dependent-schema-keys new-projection
                                             changed-schema-keys))
             registry (:seon.schema.projection/registry new-projection)
             rows (program-schema-rows db)
             selected
             (reduce
               (fn [acc [sym-str spec-str]]
                 (try
                   (let [sym (symbol sym-str)
                         form (reader/read-string spec-str)
                         old-refs (try
                                    (schema/direct-references
                                      old-projection form)
                                    (catch :default _ #{}))
                         new-refs (try
                                    (schema/direct-references
                                      new-projection form)
                                    (catch :default _ #{}))]
                     (if (and (qualified-target-parts sym)
                              (seq (set/intersection
                                     affected (into old-refs new-refs))))
                       (conj acc {::sym sym
                                  ::schema-form form
                                  ::registry registry})
                       acc))
                   (catch :default _ acc)))
               []
               rows)
             changed-syms (into #{} (map ::sym) selected)
             result
             (if (seq selected)
               (instrument-delta!
                 {::changed-syms changed-syms ::targets selected})
               {::enabled? true ::ok? true ::n-unstrumented 0
                ::n-instrumented 0 ::accepted-syms #{} ::rejected []})]
         (assoc result
                ::n-inspected (count rows)
                ::n-dependent (count selected))))))

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

               ;; The function body is the validation boundary for the one
               ;; async shape Malli cannot wrap correctly.
               (async-unwrappable? (async-fn? f) f schema)
               nil

               :else
               {::sym sym-str ::target-sym target-sym
                ::schema-form schema ::reason ::unwrapped})))))))

#?(:cljs
   (defn- coverage-gaps-for-rows [registry rows]
     (into [] (keep #(coverage-gap registry %)) rows)))

#?(:cljs
   (defn instrument-namespaces-from-db!
     "Restore wrappers lost by one Shadow namespace reload.

      `::namespace-syms` is the exact resource set from Shadow's build
      notification. The database query is restricted to those namespaces;
      [[coverage-gap]] then reduces their persisted contracts to the exact
      live function vars whose wrappers were replaced. A build that changed
      no function definitions performs no Malli mutation, and unrelated
      program rows are never read."
     {:malli/schema
      [:=>
       [:cat
        [:map
         [::db :any]
         [::namespace-syms [:set :symbol]]]]
       :map]}
     [{::keys [db namespace-syms]}]
     (if-not (enabled?)
       {::enabled? false ::n-namespaces (count namespace-syms)
        ::n-inspected 0 ::n-gaps 0
        ::n-unstrumented 0 ::n-instrumented 0}
       (let [registry
             (:seon.schema.projection/registry
               (schema/current-projection))
             namespace-names (mapv keyword (sort namespace-syms))
             rows
             (if (seq namespace-names)
               (db/query
                 '[:find ?sym ?spec
                   :in $ [?ns-name ...]
                   :where
                   [?n :seon.ns/name ?ns-name]
                   [?e :seon.fn/ns ?n]
                   [?e :seon.fn/sym ?sym]
                   [?e :seon.fn/spec ?spec]]
                 db namespace-names)
               #{})
             gaps (coverage-gaps-for-rows registry rows)
             changed-syms (into #{} (map ::target-sym) gaps)
             targets
             (into []
                   (keep (fn [{::keys [target-sym schema-form]}]
                           (when schema-form
                             (cond-> {::sym target-sym
                                      ::schema-form schema-form}
                               registry (assoc ::registry registry)))))
                   gaps)
             result
             (if (seq changed-syms)
               (instrument-delta!
                 {::changed-syms changed-syms ::targets targets})
               {::enabled? true ::n-unstrumented 0 ::n-instrumented 0
                ::accepted-syms #{} ::rejected []})]
         (assoc result
                ::n-namespaces (count namespace-syms)
                ::n-inspected (count rows)
                ::n-gaps (count gaps))))))

#?(:cljs
   (defn coverage-gaps
     "Specced program-graph fns whose live var has no Malli wrapper.

      This is a derived invariant over canonical DB rows plus live function
      objects; it stores no registry or dirty flag. Rows with no live var and
      structural async opt-outs are not gaps. A result names an unwrapped,
      unreadable, or currently unresolvable contract."
     {:malli/schema [:=> [:catn [::db :any]]
                     [:vector [:map
                               [::sym :string]
                               [::reason :keyword]]]]}
     [db]
     (if-not (enabled?)
       []
       (let [registry
             (:seon.schema.projection/registry
               (schema/current-projection))]
         (mapv #(select-keys % [::sym ::reason])
               (coverage-gaps-for-rows registry
                                       (program-schema-rows db)))))))
