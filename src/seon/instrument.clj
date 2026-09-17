(ns seon.instrument
  "JVM-owned host wrappers with contracts compiled in the caller's projection.

  A loaded Var has one wrapper for its contract and referenced declarations.
  Repeated arming preserves unchanged wrappers; teardown cannot remove them. Changed roots
  or contract declarations are armed on the next apply!.
  Compiled validators belong to the immutable projection that defines them.
  Host calls without cluster custody use the packaged JVM program captured at
  arming; they never consult Malli's global registry."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [malli.util :as mu]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.fn.schema-shape :as schema-shape]
            [seon.error :as error]
            [seon.id]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as sci.kernel]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; What is instrumented, as a question anybody can ask
;;; ---------------------------------------------------------------------------

(defn instrumented
  "Loaded Vars bearing their own host wrapper, derived without a registry."
  {:malli/schema [:=> [:cat] [:set [:fn clojure.core/var?]]]}
  []
  (into #{}
        (comp (mapcat ns-interns)
              (map val)
              (filter (fn [candidate]
                        (and (bound? candidate)
                             (identical? candidate
                                         (::var (meta @candidate)))))))
        (all-ns)))

(defn- primitive-fn?
  "Malli's own exclusion rule, read from its source rather than remembered.

  `malli.instrument/-primitive-fn?` refuses to wrap a fn implementing one of
  the `clojure.lang.IFn$` primitive-arity interfaces and prints a warning
  instead (`reference-code/malli/src/malli/instrument.clj:16,24`). A var with
  a `^long` hint therefore carries a declared contract that NOTHING can arm,
  on a live cluster exactly as here. Asking the question with malli's rule is
  what keeps `armable` and what `apply!` installs from ever disagreeing."
  [candidate]
  (and (fn? candidate)
       (boolean
        (some (fn [^Class interface]
                (.startsWith (.getName interface) "clojure.lang.IFn$"))
              (supers (class candidate))))))

(defn armable
  "The vars in `namespaces` malli WOULD instrument, derived from its own rules.

  Two questions, both malli's: does the var carry a declared function schema
  (`mi/-schema` — `:malli/schema`, or a complete set of arglist schemas), and
  is its current value non-primitive. Nothing here is a list, a prefix, or a
  count somebody kept, so this set and the set `apply!` installs cannot drift.

  This is the parity question the gate asks: a worker whose armed set does not
  cover this one is arming a smaller world than the cluster it claims to
  reproduce, and every contract in the difference is enforced in production
  and checked by nothing."
  {:malli/schema [:=> [:cat [:sequential :symbol]] [:set [:fn clojure.core/var?]]]}
  [namespaces]
  (into #{}
        (comp (keep find-ns)
              (mapcat ns-interns)
              (map val)
              (filter (fn [candidate]
                        (and (mi/-schema candidate)
                             (bound? candidate)
                             (not (primitive-fn? (deref candidate)))))))
        namespaces))

;;; ---------------------------------------------------------------------------
;;; The reporter
;;; ---------------------------------------------------------------------------

(defn- flat-error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn- buried-error
  "The flat error value a contract report would otherwise bury, if any.

   Seon's boundaries answer with `:seon.error` values, so one arriving at a
   contract seam is the real answer, already in the honest shape. Reporting it
   as a schema problem replaces a one-line cause with a wall of humanized
   Malli text — reported 2026-08-07, where `seon.config/effective` surfaced
   `... violated its contract (invalid-input)` instead of the inner
   `seon.db/missing-connection-binding` and its remedy."
  [kind data]
  (case kind
    :malli.core/invalid-input (first (filter flat-error-value? (:args data)))
    :malli.core/invalid-output (when (flat-error-value? (:value data))
                                 (:value data))
    nil))

(def ^:private non-caller-namespace-prefixes
  ;; DERIVED FROM WHAT THESE FRAMES ARE, not from a hand list of ours: the
  ;; host, the language, the contract library's own wrapper, and this
  ;; reporter are the machinery that CAUGHT the violation. None of them is a
  ;; place to go and edit, and naming one is how a refusal ends up pointing
  ;; the reader at the checker instead of the caller.
  ["malli." "clojure." "java." "jdk." "sun." "seon.instrument"])

(defn- caller-frame
  "The first stack frame that is neither the contract machinery nor the host.

  A refusal must name the member AND the frame that supplied it. With
  contracts armed, malli's instrumented wrapper sits between the caller and
  the refusal, so the nearest outside frame is `malli.core` and a reader
  following it lands in a dependency."
  []
  (some (fn [^StackTraceElement frame]
          (let [demunged (clojure.lang.Compiler/demunge (.getClassName frame))
                separator (.indexOf demunged "/")
                frame-ns (if (neg? separator)
                           demunged
                           (subs demunged 0 separator))]
            (when-not (some #(.startsWith ^String frame-ns ^String %)
                            non-caller-namespace-prefixes)
              (str frame-ns " (" (.getFileName frame)
                   ":" (.getLineNumber frame) ")"))))
        (.getStackTrace (Thread/currentThread))))

(defn- problem-path
  "One Malli problem's path INTO THE ARGUMENT, as ordinary data.

  Input explanations begin with the positional argument index, which the
  surrounding args vector already represents. For a missing required key
  this path IS the key: naming it is the difference between \"missing
  required key\" and a refusal a reader can act on."
  [kind problem]
  (vec (cond-> (:in problem)
         (= :malli.core/invalid-input kind) next)))

(defn- failure-cause
  "A non-empty description of `failure`, for a refusal's evidence.

   `ex-message` is nil for a throwable carrying no message, and a nil is not
   storable evidence (AGENTS §3: absent is no key, never a stored nil). The
   class name is what is genuinely known in that case, so this key is always
   present and always says something."
  [^Throwable failure]
  (let [message (ex-message failure)]
    (if (or (nil? message) (= "" (.trim ^String message)))
      (.getName (class failure))
      message)))

(defn- program-graph-arglists
  [function-symbol]
  (try
    (if-let [environment (env/of effect/*request-context*)]
      (if-let [connection (:seon.db/connection environment)]
        (let [database (db/db connection)]
          (if (flat-error-value? database)
            {:seon.instrument.lookup/status :failed}
            (let [result
                  (db/q '[:find ?arglists .
                          :in $ ?function-symbol
                          :where
                          [?function :seon.fn/sym ?function-symbol]
                          [?function :seon.fn/arglists ?arglists]]
                        database function-symbol)]
              (cond
                (flat-error-value? result)
                {:seon.instrument.lookup/status :failed}

                (string? result)
                {:seon.instrument.lookup/status :found
                 :seon.fn/arglists result}

                (nil? result)
                {:seon.instrument.lookup/status :missing}

                :else
                {:seon.instrument.lookup/status :failed}))))
        {:seon.instrument.lookup/status :failed})
      {:seon.instrument.lookup/status :no-program-graph})
    (catch Throwable failure
      {:seon.instrument.lookup/status :failed
       :seon.instrument.lookup/cause (failure-cause failure)})))

(defn- jvm-arglists
  [function-symbol]
  (try
    (if-let [arglists (some-> function-symbol find-var meta :arglists)]
      {:seon.instrument.lookup/status :found ::arglists arglists}
      {:seon.instrument.lookup/status :missing})
    (catch Throwable failure
      {:seon.instrument.lookup/status :failed
       :seon.instrument.lookup/cause (failure-cause failure)})))

(defn- diagnostic-arglists
  [function-symbol]
  (let [{status :seon.instrument.lookup/status
         stored :seon.fn/arglists :as lookup}
        (program-graph-arglists function-symbol)]
    (case status
      :found (try
               {:seon.instrument.lookup/status :found
                ::arglists (edn/read-string stored)}
               (catch Throwable failure
                 {:seon.instrument.lookup/status :failed
                  :seon.instrument.lookup/cause (failure-cause failure)}))
      ;; With a graph, only an established miss reaches JVM metadata. Outside
      ;; an evaluation there is no program graph to consult; that is the
      ;; system-side, compiled-function case this fallback exists for.
      (:missing :no-program-graph)
      (let [result (jvm-arglists function-symbol)]
        (if (= :missing (:seon.instrument.lookup/status result))
          {:seon.instrument.lookup/status status}
          result))
      lookup)))

(defn- minimal-violation
  [kind data]
  (let [function-symbol (:fn-name data)
        arity? (= :malli.core/invalid-arity kind)]
    (error/diagnostic
     {:seon.error/kind ::contract-violated
      :seon.instrument/contract-violated function-symbol
      :seon.error/message
      (if arity?
        (str "Wrong number of args (" (:arity data) ") passed to: "
             function-symbol)
        (str function-symbol " violated its contract (" kind ")."))
      :seon.error/diagnostic-layer :instrumentation
      :seon.error/diagnostic-operation function-symbol
      :seon.error/diagnostic-member
      (if (= :malli.core/invalid-output kind) :return :arguments)
      :seon.error/diagnostic-expected
      (if arity? ::declared-arglists (or (:guard data) (:output data) (:input data)))
      :seon.error/diagnostic-offending
      (if arity? (:arity data) (or (:value data) (:args data)))
      :seon.error/diagnostic-cause kind
      :seon.error/diagnostic-evidence nil
      :seon.error/data
      (cond-> {::malli kind
               ::arm (if (= :malli.core/invalid-output kind) :output :input)}
        arity? (assoc ::arity (:arity data))
        function-symbol (assoc ::fn function-symbol))})))

(defn- supplied-entry-problems
  [function-symbol]
  (when-let [environment (env/of effect/*request-context*)]
    (when-let [connection (:seon.db/connection environment)]
      (let [entries (call-preparation/supplied-map-entries (db/db connection)
                                                           function-symbol)]
        (when-not (:seon.error/kind entries)
          (into #{} (map (fn [[_ position entry-key]] [position entry-key])) entries))))))

(defn- actionable-problem
  [description problem supplied?]
  (let [value (:value problem)
        entry-key (last (:seon.error/path description))
        missing? (= :malli.core/missing-key (:type problem))
        lookup-ref? (and (vector? value) (= 2 (count value))
                         (keyword? (first value)))
        expects-string? (= :string (m/type (:schema problem)))]
    (cond
      supplied?
      (assoc description
             :seon.error/expected-description (str entry-key " supplied by the runtime")
             :seon.error/fix
             (if missing?
               (str "Runtime fault: " entry-key
                    " is supplied by the runtime but is missing. Report the call-preparation fault; do not pass it.")
               (str entry-key " is supplied by the runtime; do not pass it. Remove it from the request map.")))

      lookup-ref?
      (cond-> (assoc description :seon.error/actual-description "a lookup-ref vector")
        expects-string?
        (assoc :seon.error/fix
               (str (if (string? (second value))
                      "Pass the string id (the second element of the lookup-ref vector) at "
                      "Pass a string id at ")
                    (pr-str (:seon.error/path description)) ".")))

      :else description)))

(defn- violation
  "Retain the actual offending values; the error render pair owns projection."
  [_caps kind data]
  (try
    (or
     (when-not (::boundary? data) (buried-error kind data))
     (let [function-symbol (:fn-name data)
           {arglists ::arglists :as lookup} (diagnostic-arglists function-symbol)
           arguments (:args data)
           arglist (some #(when (or (= (count %) (count arguments))
                                    (some #{'&} %)) %) arglists)
           arity? (= :malli.core/invalid-arity kind)
           [offended value]
           (case kind
             :malli.core/invalid-output [(:output data) (:value data)]
             :malli.core/invalid-guard [(:guard data) [arguments (:value data)]]
             [(:input data) arguments])
           explanation (when-not arity? (m/explain offended value))
           described-unions (when explanation
                              (filter #(and (= :or (m/type (:schema %)))
                                            (:error/message (m/properties (:schema %))))
                                      (mu/subschemas offended)))
           explained-problems
           (distinct
            (map (fn [problem]
                   (let [union (some #(when (= (:in problem) (:in %)) %) described-unions)]
                     (if union
                       (-> problem (assoc :path (:path union) :schema (:schema union))
                           (dissoc :type))
                       problem)))
                 (remove (fn [problem]
                           (and (not (:check problem))
                                (some #(and (:check %) (= (:in %) (:in problem)))
                                      (:errors explanation))))
                         (:errors explanation))))
           arm (case kind :malli.core/invalid-output :output
                          :malli.core/invalid-guard :guard :input)
           supplied-entries (when (= :input arm) (supplied-entry-problems function-symbol))
           problems
           (if arity?
             [{:seon.error/argument "argument count"
               :seon.error/path []
               :seon.error/expected arglists
               :seon.error/expected-description "the declared arglists"
               :seon.error/offending (:arity data)
               ;; PROSE ONLY: `seon.error/problem-sentence` prints the
               ;; offending value after this description. Carrying the count
               ;; here as well rendered "got an argument count of 0 0"
               ;; (3e41a5d22, measured 2026-09-17); the flat message below
               ;; prints the same scalar, so neither sentence dangles.
               :seon.error/actual-description "an argument count of"
               :seon.error/fix "Call one of the declared arglists."}]
             (mapv
              (fn [problem]
                (let [position (first (:in problem))
                      label (when (and (= :input arm) (= :catn (m/type offended)))
                              (first (:path problem)))
                      binding (when (and (= :input arm) (integer? position))
                                (nth arglist position nil))
                      argument (case arm
                                 :output "return value"
                                 :guard "arguments and return value"
                                 (str (or label (when (and (symbol? binding) (not= '_ binding)) binding)
                                          (str "argument " position " (0-based)"))))]
                  (cond-> (assoc
                   (actionable-problem
                    (error/explain-problem
                    {:seon.error/problem problem
                     :seon.error/path (problem-path kind problem)
                     :seon.error/parent
                     (when (and (= :input arm) (seq (:in problem)))
                       (get-in (vec arguments) (pop (vec (:in problem)))))
                     :seon.error/argument argument})
                    problem
                    (and (= 2 (count (:in problem)))
                         (contains? supplied-entries (vec (:in problem)))))
                   :seon.error/schema-path (vec (:path problem)))
                    (= :guard arm)
                    (assoc :seon.error/input arguments
                           :seon.error/result-contract
                           (m/form (:output (m/-function-info (:schema data))))))))
              explained-problems))
           first-problem (first problems)
           expected (if arity? arglists (m/form offended))
           expected (if (and (vector? expected) (= :cat (first expected))
                             (= 2 (count expected)))
                      (second expected) expected)
           ;; The offending value is the value the contract CHECKED: the
           ;; caller's arguments for an input arm, the returned value for an
           ;; output, the pair for a guard, the count for an arity. Naming one
           ;; problem's leaf here instead dropped both the caller's own call
           ;; and the position it failed at — `probe/g "a" 1` reported a bare
           ;; `[1]` at path `[]`, with nothing but the message's prose saying
           ;; which argument — while a second problem went unnamed. Every leaf
           ;; value and its path are already carried per problem in
           ;; `:seon.error/problems`, so this loses no evidence.
           offending (if arity? (:arity data) value)
           paths (into [] (comp (map :seon.error/path) (remove empty?)) problems)
           caller (caller-frame)]
       (error/diagnostic
        {:seon.error/kind (if (some #(and (= :malli.core/missing-key (:type %))
                                          (contains? supplied-entries (vec (:in %))))
                                    (:errors explanation))
                            ::missing-supplied-key ::contract-violated)
         :seon.instrument/contract-violated function-symbol
         :seon.error/message
         (str (error/problem-sentence
               function-symbol first-problem nil
               (error/scalar-text (:seon.error/offending first-problem)))
              (when (qualified-keyword? expected)
                (str " Contract: " expected ".")))
         :seon.error/diagnostic-layer :instrumentation
         :seon.error/diagnostic-operation function-symbol
         :seon.error/diagnostic-member (case arm :output :return :guard :guard
                                              (if arity? :arity :arguments))
         :seon.error/diagnostic-expected expected
         :seon.error/diagnostic-offending offending
         :seon.error/diagnostic-cause kind
         :seon.error/diagnostic-evidence
         (if arity?
           (select-keys lookup [:seon.instrument.lookup/status
                                :seon.instrument.lookup/cause ::arglists])
           (cond-> {::problem-count (count problems)}
             caller (assoc ::caller caller)))
         :seon.error/data
         (cond-> {::malli kind ::arm arm ::fn function-symbol
                  ::problem-count (count problems)
                  :seon.error/problems problems}
           arity? (assoc ::arity (:arity data))
           arglists (assoc ::arglists arglists)
           (seq paths) (assoc ::problem-paths paths)
           caller (assoc ::caller caller))})))
    (catch Throwable failure
      ;; A BOUND FIRING IS ITS OWN REPORT. Composing this value runs the
      ;; contract's own predicates again (`m/explain` re-checks the value
      ;; that failed), so an evaluation's deadline can close while the
      ;; reporter is mid-sentence. Converting that interrupt into
      ;; `minimal-violation` gave the interrupt a `:seon.error/kind` of
      ;; `::contract-violated`, which `seon.sci.kernel/failure-value` then
      ;; keeps as the boundary's own answer (`src/seon/sci/kernel.clj:486`)
      ;; — so a 2000 ms bound firing around a 10 ms refusal was read as
      ;; "Wrong number of args … passed to: …", naming the wrong defect for
      ;; whoever read the fault. The interrupt is uncatchable by design
      ;; (`reference-code/sci/src/sci/interrupt.cljc`); it leaves here
      ;; unchanged so the bound reports what never arrived.
      (when (sci.kernel/interrupted? failure)
        (throw failure))
      (assoc-in (minimal-violation kind data)
                [:seon.error/data :seon.instrument.lookup/cause]
                (failure-cause failure)))))

;;; ---------------------------------------------------------------------------
;;; Interpreted function contracts
;;; ---------------------------------------------------------------------------

(def ^:private interpreted-original ::interpreted-original)

(defn- original-interpreted
  [f]
  (or (some-> f meta interpreted-original) f))

(defn- predicate-callable
  [projection predicate]
  (or (get ((mi/-f->original schema/predicate-functions-in) projection)
           predicate)
      (when (qualified-symbol? predicate)
        (some-> predicate requiring-resolve deref))))

(defn- bind-contract-predicates
  "Bind named Malli predicates without opening Malli's code evaluator."
  [projection contract]
  (walk/postwalk
   (fn [value]
     (cond
       (and (map? value) (qualified-symbol? (:error/fn value)))
       (assoc value :error/fn (predicate-callable projection (:error/fn value)))

       (and (vector? value) (= :fn (first value)))
       (let [predicate-index (if (map? (second value)) 2 1)
             predicate (get value predicate-index)
             callable (when (symbol? predicate)
                        (predicate-callable projection predicate))]
         (cond
           (and (ifn? predicate) (not (symbol? predicate))) value
           (ifn? callable) (assoc value predicate-index callable)
           :else
           (throw
            (ex-info
             (str "Contract predicate " (pr-str predicate)
                  " has no active callable.")
              {:seon.error/kind :seon.schema/unresolved-predicate
               :seon.schema/unresolved-predicate predicate
               :seon.error/message "The schema predicate is unresolved."
               :seon.schema/predicate predicate}))))
       :else value))
   contract))

(declare compiled-wrapper ^:dynamic *compiling-contract*)

(defn wrap-interpreted
  "Apply one committed agent function contract under the core-error dial.

  Both dials use the host boundary's per-arity enforcement. Record mode
  requires a recording operation acquired by the caller before installation."
  {:malli/schema
   [:function
    [:=> [:cat :symbol :string :map :seon.config/on-core-error
          [:or :seon.sci.admit/caps :seon.error/value] [:fn clojure.core/ifn?]]
     [:fn clojure.core/ifn?]]
    [:=> [:cat :symbol :string :map :seon.config/on-core-error
          [:or :seon.sci.admit/caps :seon.error/value] [:fn clojure.core/ifn?]
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
     [:fn clojure.core/ifn?]]]}
  ([function-symbol spec-edn projection mode caps f]
   (wrap-interpreted function-symbol spec-edn projection mode caps f {}))
  ([function-symbol spec-edn projection mode caps f arm-request]
  (let [original (original-interpreted f)]
    (when (and (= :record mode) (not (:seon.flow/commit-fault! arm-request)))
      (throw (ex-info "Record-mode SCI instrumentation requires an acquired fault recorder."
                      {:seon.error/kind ::missing-recorder
                       :seon.error/message "Record-mode SCI instrumentation requires an acquired fault recorder."
                       :seon.instrument/registration-failed true
                       :seon.instrument/fn function-symbol
                       :seon.error/expected-key :seon.flow/commit-fault!})))
    (when (:seon.error/kind caps)
      (throw
       (ex-info
        (str "Cannot arm the contract of " function-symbol
             " under " mode ": " (:seon.error/message caps))
        (assoc caps :seon.instrument/fn function-symbol
                    :seon.instrument/registration-failed true))))
      (let [wrapped (binding [*compiling-contract* true]
                      (compiled-wrapper projection function-symbol
                                        (edn/read-string spec-edn) original caps
                                        (assoc arm-request :seon.config/on-core-error mode)))]
        (with-meta wrapped
          (assoc (meta wrapped) interpreted-original original))))))

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn- var-symbol
  [candidate-var]
  (let [{namespace-object :ns var-name :name} (meta candidate-var)]
    (symbol (str (ns-name namespace-object)) (str var-name))))

(defn- registration-cause-data
  [failure]
  (loop [deepest (ex-data failure)]
    (if-let [nested (and (map? deepest)
                         (or (:exception deepest)
                             (get-in deepest [:data :exception])))]
      (let [nested-data (ex-data nested)]
        (recur (or nested-data deepest)))
      deepest)))

(def ^:dynamic ^:private *compiling-contract* false)

(defn- request-member
  [value member]
  (when (map? value)
    (try (get value member)
         (catch ClassCastException _ nil))))

(defn- supplied-projection
  [arguments]
  (or (some (fn [argument]
              (some (fn [candidate]
                      (when (request-member candidate :seon.schema.projection/registry)
                        candidate))
                    [(when (instance? clojure.lang.IMeta argument)
                       (:seon.schema/projection (meta argument)))
                     (request-member argument :seon.schema/projection)
                     (request-member
                      (request-member argument :seon.env/environment)
                      :seon.schema/projection)]))
            arguments)
      (let [projection ((mi/-f->original schema/handed-projection))]
        (when (request-member projection :seon.schema.projection/registry)
          projection))))

(defn- declared-result
  "Derive result-position permissions without inspecting nested payloads."
  {:malli/schema [:=> [:cat :map :seon.schema/value] :map]}
  [projection output]
  (let [forms (:seon.schema.projection/forms projection)
        facets ((mi/-f->original error/facet-keys) projection)]
    (letfn [(walk-result [node seen]
              (cond
                (= node :seon.error/base) #{:seon.error/base}
                (and (keyword? node) (find forms node))
                (if (seen node)
                  (throw (ex-info "Cyclic error result declaration."
                                  {:seon.error/kind ::error-facet-analysis-unavailable
                                   :seon.error/expected-key node}))
                  (cond-> (walk-result (get forms node) (conj seen node))
                    (facets node) (conj node)))
                (vector? node)
                (let [tag (first node)
                      children (if (map? (second node)) (nnext node) (next node))]
                  (cond
                    (#{:or :and :maybe :schema :ref} tag)
                    (into #{} (mapcat #(walk-result % seen)) children)
                    (#{:orn :multi} tag)
                    (into #{} (mapcat #(walk-result (last %) seen)) children)
                    (= :merge tag)
                    (throw (ex-info "Unsupported error result declaration."
                                    {:seon.error/kind ::error-facet-analysis-unavailable
                                     :seon.error/expected node}))
                    :else #{}))
                :else #{}))]
      (let [declared (walk-result output #{})]
        {::declared (disj declared :seon.error/base)
         ::base? (contains? declared :seon.error/base)
         ::digest ((mi/-f->original seon.id/digest) 64 (sort declared))}))))

(defn- observation-location
  "Ordered schema or value path, preserving arbitrary observed keys."
  {:malli/schema [:=> [:cat :seon.sci.admit/caps [:sequential :seon.schema/value]] :map]}
  [caps path]
  (cond-> {:seon.error.location/length (count path)}
    (seq path)
    (assoc :seon.error.location/segments
           (set (map-indexed
                 (fn [ordinal key-value]
                   (let [projected (error/project-observation caps key-value)]
                     {:seon.error.location.segment/ordinal ordinal
                      :seon.error.location.segment/key
                      {:seon.error.key/projection (:seon.instrument/actual projected)
                       :seon.error.key/capped? (:seon.error/capped? projected)
                       :seon.error.key/bound-bytes
                       (:seon.error.projection/bound-bytes projected)}})) path)))))

(defn- boundary-refusal
  "Construct boundary evidence independently of the body's output contract."
  {:malli/schema [:=> [:cat :map :seon.sci.admit/caps :qualified-keyword :map [:vector :map]] :seon.instrument/refusal-result]}
  [projection caps kind data arities]
  (let [base (merge (violation caps kind (assoc data ::boundary? true))
                    {:seon.error/at (java.util.Date.)
                     :seon.error/layer :seon.instrument/invocation
                     :seon.error/operation (:fn-name data)
                     :seon.instrument/fn (:fn-name data)
                     :seon.instrument/arity (count (:args data))})]
    (if (= :malli.core/invalid-arity kind)
      (assoc base :seon.instrument/declared-arity-count (count arities)
             :seon.instrument/declared-arities
             (set (map-indexed
                   (fn [ordinal info]
                     (cond-> {:seon.instrument.arity/ordinal ordinal
                              :seon.instrument.arity/min (:min info)}
                       (:max info) (assoc :seon.instrument.arity/max (:max info)))) arities)))
      (let [check (case kind :malli.core/invalid-input :input
                            :malli.core/invalid-output :output :guard)
            contract (get data check)
            checked (case check :input (:args data) :output (:value data)
                               [(:args data) (:value data)])
            explanation (m/explain contract checked)
            items (map-indexed
                   (fn [ordinal problem]
                     {:seon.instrument.explanation/ordinal ordinal
                      :seon.instrument.explanation/schema-location
                      (observation-location caps (:path problem))
                      :seon.instrument.explanation/value-location
                      (observation-location caps (:in problem))
                      :seon.instrument.explanation/expected-shape
                      (schema-shape/fingerprint
                       (:seon.schema.shape/form
                        (schema-shape/normalized-form (:schema problem)
                          (:seon.schema.projection/forms projection)
                          (schema/predicate-functions-in projection))))
                      :seon.instrument.explanation/actual
                      (error/project-observation caps (:value problem))
                      :seon.instrument.explanation/humanization-unavailable
                      "The original problem is represented by its schema and value paths."})
                   (:errors explanation))]
        (assoc base :seon.instrument/check check
               :seon.error/expected-shape
               (schema-shape/fingerprint
                (:seon.schema.shape/form
                 (schema-shape/normalized-form contract
                   (:seon.schema.projection/forms projection)
                   (schema/predicate-functions-in projection))))
               :seon.error/location (observation-location caps [])
               :seon.instrument/explanations
               {:seon.instrument.explanations/count (count items)
                :seon.instrument.explanations/items (set items)})))))

(defn- compiled-wrapper
  [projection function-symbol authored original caps & [policy]]
  ((mi/-f->original schema/projection-cache-value)
   projection [::wrapper function-symbol authored original policy]
   (fn []
     (let [contract (get (:seon.schema.projection/function-contracts projection)
                         function-symbol authored)
           bound (bind-contract-predicates
                  projection
                  ((mi/-f->original schema/compilable-form)
                   contract
                   ((mi/-f->original schema/predicate-functions-in) projection)))
           caps (assoc (or caps (config/result-caps (config/defaults)))
                       :seon.config.eval.result/max-bytes
                       (or (:seon.config.error/max-evidence-bytes policy)
                           (:seon.config.error/max-evidence-bytes (config/defaults))))
           options {:registry (mr/composite-registry
                               (:seon.schema.projection/registry projection)
                               (mr/var-registry))}
           compiled (m/schema bound options)
           arities (mapv m/-function-info (m/-function-schema-arities compiled))
           permissions (mapv #(declared-result projection (m/form (:output %))) arities)
           base? ((mi/-f->original schema/projection-cache-value)
                  projection ::base-validator
                  #((mi/-f->original schema/projection-validator) projection :seon.error/base))
           refusal? ((mi/-f->original schema/projection-cache-value)
                     projection ::refusal-validator
                     #((mi/-f->original schema/projection-validator) projection :seon.instrument/refusal-result))
           marker (Object.)
           reject! (fn [value]
                     (when-not (refusal? value)
                       (throw (ex-info "Instrumentation constructed an invalid refusal." value)))
                     (throw (ex-info (:seon.error/message value)
                                     (with-meta value {::boundary marker}))))
           wrapped
           (m/-instrument
            {:schema compiled :scope #{:input :output :guard}
             :report (fn [kind data]
                       (binding [*compiling-contract* true]
                         (reject! (boundary-refusal projection caps kind
                                                    (assoc data :fn-name function-symbol) arities))))}
            (fn [& arguments]
              (let [value (apply original arguments)]
                (when (and (map? value) (base? value))
                  (let [arity (count arguments)
                        index (or (first (keep-indexed
                                          #(when (= arity (:arity %2)) %1) arities))
                                  (first (keep-indexed
                                      #(when (and (<= (:min %2) arity)
                                                  (or (nil? (:max %2)) (<= arity (:max %2)))) %1)
                                      arities)))
                        permission (nth permissions index)
                        declared (::declared permission)
                        actual ((mi/-f->original error/facets) projection value)]
                    (when (or (not (::base? permission))
                              (seq (set/difference actual declared)))
                      (reject!
                       (cond->
                        {:seon.error/kind ::undeclared-error
                         :seon.error/message (str function-symbol " returned undeclared error facets "
                                                  (pr-str (set/difference actual declared)) ".")
                         :seon.error/at (java.util.Date.)
                         :seon.error/layer :seon.instrument/invocation
                         :seon.error/operation function-symbol
                         :seon.instrument/fn function-symbol
                         :seon.instrument/arity arity
                         :seon.instrument/returned-error (error/project-observation caps value)
                         :seon.instrument/declared-facet-digest (::digest permission)
                         :seon.instrument/declared-facet-count (count declared)
                         :seon.instrument/actual-facet-count (count actual)}
                         (seq declared) (assoc :seon.instrument/declared-facets declared)
                         (seq actual) (assoc :seon.instrument/actual-facets actual))))))
                value)) options)]
       (fn [& arguments]
         (try (apply wrapped arguments)
              (catch clojure.lang.ExceptionInfo failure
                (if (and (= :record (:seon.config/on-core-error policy))
                         (identical? marker (::boundary (meta (ex-data failure)))))
                  (let [value (ex-data failure)
                        outcome ((:seon.flow/commit-fault! policy) value)]
                    (if (= :seon.flow/committed (second outcome)) value
                        (throw (ex-info "Recording the instrumentation refusal failed." value failure))))
                  (throw failure)))))))))

(defn- contract-definitions
  "Canonical declarations closed over by a function contract, following Malli refs."
  [projection contract]
  (let [projection (assoc projection :seon.schema.projection/compile-options
                          {:registry (mr/composite-registry
                                      (:seon.schema.projection/registry projection)
                                      (mr/var-registry))})
        forms (:seon.schema.projection/forms projection)
        dependencies (:seon.schema.projection/schema-dependencies projection)]
    (loop [pending ((mi/-f->original schema/direct-references) projection contract)
           definitions (sorted-map)]
      (if-let [schema-key (first pending)]
        (if (find definitions schema-key)
          (recur (disj pending schema-key) definitions)
          (let [definition (get forms schema-key)
                references (or (get dependencies schema-key)
                               ((mi/-f->original schema/direct-references)
                                projection definition))]
            (recur (into (disj pending schema-key) references)
                   (assoc definitions schema-key definition))))
        definitions))))

(defn- current-wrapper?
  [candidate authored projection current & [policy]]
  (let [metadata (meta current)
        contract (get (:seon.schema.projection/function-contracts projection)
                      (var-symbol candidate) authored)
        definitions (:seon.instrument/definitions metadata)]
    (and (identical? candidate (:seon.instrument/var metadata))
         (= policy (::policy metadata))
         (= authored (:seon.instrument/authored metadata))
         (= contract (:seon.instrument/contract metadata))
         (map? definitions)
         (= definitions
            (select-keys (:seon.schema.projection/forms projection)
                         (keys definitions))))))

(defn- arm-var!
  [candidate authored projection bootstrap caps & [policy]]
  (alter-var-root
   candidate
   (fn [current]
     (if (current-wrapper? candidate authored projection current policy)
       current
       (let [original (mi/-f->original current)
             function-symbol (var-symbol candidate)
             contract (get (:seon.schema.projection/function-contracts projection)
                           function-symbol authored)
             definitions (contract-definitions projection contract)
             contract-digest ((mi/-f->original seon.id/digest)
                              64 [contract (schema/canonical-data-string definitions)])
             boot-wrapper (delay
                            (binding [*compiling-contract* true]
                              (compiled-wrapper bootstrap function-symbol
                                                authored original caps policy)))]
         (with-meta
           (fn [& arguments]
             (if *compiling-contract*
               (apply original arguments)
               (let [wrapped
                     (binding [*compiling-contract* true]
                       (if-let [projection (supplied-projection arguments)]
                         (compiled-wrapper projection function-symbol
                                           authored original caps policy)
                         @boot-wrapper))]
                 (apply wrapped arguments))))
           {:malli.instrument/original original
            ::policy policy
            :seon.instrument/var candidate
            :seon.instrument/authored authored
            :seon.instrument/contract contract
            :seon.instrument/definitions definitions
            :seon.instrument/contract-digest contract-digest}))))))

(defn- collect-contracts!
  "Read declarations from the program loaded into this JVM, without Malli's registry."
  [_caps]
  (into {}
        (keep (fn [candidate]
                (when-let [authored (mi/-schema candidate)]
                  (when (and (bound? candidate)
                             (not (primitive-fn? @candidate)))
                    [candidate authored]))))
        (mapcat (comp vals ns-interns) (all-ns))))

(defn apply!
  "Arm loaded Vars whose contract or referenced declarations changed.
  Compare each wrapper's captured definitions with the supplied projection.
  Unrelated declaration changes preserve wrapper identity.

  Host wrappers capture the supplied disposition and recording operation at
  arm time. Calls validate through their supplied projection or the captured
  bootstrap declarations. Record-mode acquisition requires a recorder."
  {:malli/schema
   [:=> [:cat :seon.instrument/request]
    [:or :seon.instrument/applied :seon.error/value]]}
  [{mode :seon.config/on-core-error
    caps :seon.sci.admit/caps
    commit-fault! :seon.flow/commit-fault!
    supplied-projection :seon.schema/projection}]
  (cond
    (and (= :record mode) (not (fn? commit-fault!)))
    (error/diagnostic
     {:seon.error/kind ::missing-recorder
      :seon.instrument/registration-failed true
      :seon.error/message "Record-mode instrumentation requires an acquired fault recorder."
      :seon.error/diagnostic-layer :instrumentation
      :seon.error/diagnostic-operation 'seon.instrument/apply!
      :seon.error/diagnostic-member :seon.flow/commit-fault!
      :seon.error/diagnostic-expected :seon.flow/commit-fault!
      :seon.error/diagnostic-offending ::absent
      :seon.error/diagnostic-cause ::missing-recorder
      :seon.error/diagnostic-evidence nil})

    (not (#{:panic :record} mode))
    (error/diagnostic
       {:seon.error/kind ::invalid-mode
        :seon.error/message
        "Instrumentation requires :panic or :record core-error mode."
        :seon.error/diagnostic-layer :instrumentation
        :seon.error/diagnostic-operation 'seon.instrument/apply!
        :seon.error/diagnostic-member :seon.config/on-core-error
        :seon.error/diagnostic-expected [:enum :panic :record]
        :seon.error/diagnostic-offending
        (if (nil? mode) ::nil mode)
        :seon.error/diagnostic-cause ::invalid-mode
        :seon.error/diagnostic-evidence
        {:seon.instrument/accepted-modes [:panic :record]}})

    :else
    (let [projection (or supplied-projection (schema/handed-projection))]
      (if-not projection
        (error/diagnostic
         {:seon.error/kind ::missing-projection
          :seon.error/message
          "Instrumentation requires a handed schema projection."
          :seon.error/diagnostic-layer :instrumentation
          :seon.error/diagnostic-operation 'seon.instrument/apply!
          :seon.error/diagnostic-member :seon.schema/projection
          :seon.error/diagnostic-expected :seon.schema/projection
          :seon.error/diagnostic-offending :seon.instrument/missing-projection
          :seon.error/diagnostic-cause ::missing-projection
          :seon.error/diagnostic-evidence nil})
        (let [caps (or caps (config/result-caps (config/defaults)))
              policy (cond-> {:seon.config/on-core-error mode}
                       (and (= :record mode) commit-fault!)
                       (assoc :seon.flow/commit-fault! commit-fault!))
              contracts (collect-contracts! caps)
              pending (remove (fn [[candidate authored]]
                                (current-wrapper? candidate authored projection @candidate policy))
                              contracts)
              bootstrap (when (seq pending)
                          ((mi/-f->original schema/declaration-projection)
                           (schema.edn/packaged-forms)))]
          (doseq [[candidate authored] pending]
            (try
              (binding [*compiling-contract* true]
                (compiled-wrapper projection (var-symbol candidate)
                                  authored (mi/-f->original @candidate) caps policy))
              (catch Throwable failure
                (let [data (registration-cause-data failure)
                      diagnostic
                      (error/diagnostic
                       {:seon.error/kind ::registration-failed
                        :seon.instrument/registration-failed true
                        :seon.error/message "The loaded function contract cannot compile."
                        :seon.error/diagnostic-layer :instrumentation
                        :seon.error/diagnostic-operation 'seon.instrument/apply!
                        :seon.error/diagnostic-member (var-symbol candidate)
                        :seon.error/diagnostic-expected authored
                        :seon.error/diagnostic-offending
                        (or (:schema data) (get-in data [:data :ref])
                            (get-in data [:data :schema]))
                        :seon.error/diagnostic-cause (:type data)
                        :seon.error/diagnostic-evidence nil})]
                  (throw (ex-info (:seon.error/message diagnostic)
                                  diagnostic failure)))))
            (arm-var! candidate authored projection bootstrap caps policy))
          {:seon.instrument/registered (count contracts)
           :seon.instrument/instrumented (count (instrumented))})))))

(defn remove!
  "Return the shared JVM wrapper count. Cluster teardown cannot remove host contracts."
  {:malli/schema [:=> [:cat] :seon.instrument/instrumented]}
  []
  (count (instrumented)))

;;; ---------------------------------------------------------------------------
;;; Scoping one caller's arming without reinstalling a superseded definition
;;; ---------------------------------------------------------------------------

(def ^:private function-schemas*
  "Malli's own function-schema registry atom — the one JVM-wide declaration
  state arming reads (`reference-code/malli/src/malli/core.cljc:3061`)."
  @#'m/-function-schemas*)

(defn state
  "This JVM's instrumentation state as one value: every armed Var's current
  root and Malli's function-schema registry.

  A scope that is about to run something which arms, unarms or reloads hands
  the value it captured here back to `restore!`."
  {:malli/schema
   [:=> [:cat]
    [:map
     [:seon.instrument/roots
      [:map-of [:fn clojure.core/var?] [:fn clojure.core/ifn?]]]
     [:seon.instrument/function-schemas :map]]]}
  []
  {:seon.instrument/roots (into {} (map (juxt identity deref)) (instrumented))
   :seon.instrument/function-schemas @function-schemas*})

(defn replaced-definitions
  "Vars in `state` whose LOADED DEFINITION this JVM has replaced since it was
  captured, derived by comparing each captured root's original with the Var's
  current original.

  Development adoption reloads the program's namespaces in the hosting JVM
  (`src/seon/cluster.clj:2247`), and a reload replaces the protocols, types and
  classes a captured closure builds from as well as the Var's root. A captured
  root is therefore a mirror the loader has re-decided: it is restorable only
  while the definition under it is still the one it was compiled against."
  {:malli/schema
   [:=> [:cat
         [:map
          [:seon.instrument/roots
           [:map-of [:fn clojure.core/var?] [:fn clojure.core/ifn?]]]
          [:seon.instrument/function-schemas :map]]]
    [:set [:fn clojure.core/var?]]]}
  [state]
  (into #{}
        (keep (fn [[candidate captured]]
                (when (and (bound? candidate)
                           (not (identical? (mi/-f->original captured)
                                            (mi/-f->original @candidate))))
                  candidate)))
        (:seon.instrument/roots state)))

(defn restore!
  "Restore `state`, leaving every replaced definition as the loader left it.

  Unarm the wrappers this JVM now holds, put Malli's function-schema registry
  and the captured roots back, and skip both steps for the Vars
  `replaced-definitions` names: reinstalling one of those closures leaves the
  Var emitting values the current protocol no longer recognises. The measured
  case is `seon.print/text-sink` handing back a superseded `seon.print.TextSink`
  that the reloaded `seon.print/sink?` (`src/seon/print.cljc:27`) refuses, so
  the Var's own armed output contract refused every later call in that JVM.

  Returns the Vars left as the loaded program has them; an empty set — the
  ordinary case — says nothing was replaced, and a non-empty set is the
  evidence that something reloaded inside the scope."
  {:malli/schema
   [:=> [:cat
         [:map
          [:seon.instrument/roots
           [:map-of [:fn clojure.core/var?] [:fn clojure.core/ifn?]]]
          [:seon.instrument/function-schemas :map]]]
    [:set [:fn clojure.core/var?]]]}
  [state]
  (let [roots (:seon.instrument/roots state)
        replaced (replaced-definitions state)]
    (try
      (doseq [candidate (instrumented)
              :when (not (contains? replaced candidate))]
        (alter-var-root candidate mi/-f->original))
      (finally
        (reset! function-schemas* (:seon.instrument/function-schemas state))
        (doseq [[candidate captured] roots
                :when (and (bound? candidate) (not (contains? replaced candidate)))]
          (alter-var-root candidate (constantly captured)))))
    replaced))
