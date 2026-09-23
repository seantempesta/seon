(ns seon.instrument
  "JVM-owned host wrappers with contracts compiled in the caller's projection.

  A loaded Var has one wrapper for its contract and referenced declarations.
  Repeated arming preserves unchanged wrappers; teardown cannot remove them. Changed roots
  or contract declarations are armed on the next apply!.
  Compiled validators belong to the immutable projection that defines them.
  Host calls without cluster custody use the supplied program captured at
  arming; they never consult Malli's global registry."
  (:require [seon.error.refusal]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [malli.util :as mu]
            [clojure.test.check.generators :as gen]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.fn.schema-shape :as schema-shape]
            [seon.error :as error]
            [seon.id]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; What is instrumented, as a question anybody can ask
;;; ---------------------------------------------------------------------------

(defn instrumented
  "Loaded Vars bearing their own host wrapper, derived without a registry."
  {:malli/schema [:=> [:cat] [:set :seon.instrument/loaded-var]]}
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
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
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
  {:malli/schema [:=> [:cat [:sequential :symbol]] [:set :seon.instrument/loaded-var]]}
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

(def ^:private non-caller-namespace-prefixes
  ;; DERIVED FROM WHAT THESE FRAMES ARE, not from a hand list of ours: the
  ;; host, the language, the contract library's own wrapper, and this
  ;; reporter are the machinery that CAUGHT the violation. None of them is a
  ;; place to go and edit, and naming one is how a refusal ends up pointing
  ;; the reader at the checker instead of the caller.
  ["malli." "clojure." "java." "jdk." "sun."])

(defn- caller-frame
  "The first stack frame that is neither the contract machinery nor the host.

  A refusal must name the member AND the frame that supplied it. With
  contracts armed, malli's instrumented wrapper sits between the caller and
  the refusal, so the nearest outside frame is `malli.core` and a reader
  following it lands in a dependency."
  {:malli/schema [:=> [:cat] [:or :nil :string]]}
  []
  (some (fn [^StackTraceElement frame]
          (let [demunged (clojure.lang.Compiler/demunge (.getClassName frame))
                separator (.indexOf demunged "/")
                frame-ns (if (neg? separator)
                           demunged
                           (subs demunged 0 separator))]
            (when-not (or (= frame-ns "seon.instrument")
                          (some #(.startsWith ^String frame-ns ^String %)
                                non-caller-namespace-prefixes))
              (str frame-ns " (" (.getFileName frame)
                   ":" (.getLineNumber frame) ")"))))
        (.getStackTrace (Thread/currentThread))))

(defn- problem-path
  "One Malli problem's path INTO THE ARGUMENT, as ordinary data.

  Input explanations begin with the positional argument index, which the
  surrounding args vector already represents. For a missing required key
  this path IS the key: naming it is the difference between \"missing
  required key\" and a refusal a reader can act on."
  {:malli/schema [:=> [:cat :qualified-keyword :map] [:vector :seon.schema/value]]}
  [kind problem]
  (vec (cond-> (:in problem)
         (= :malli.core/invalid-input kind) next)))

(defn- failure-cause
  "A non-empty description of `failure`, for a refusal's evidence.

   `ex-message` is nil for a throwable carrying no message, and a nil is not
   storable evidence (AGENTS §3: absent is no key, never a stored nil). The
   class name is what is genuinely known in that case, so this key is always
   present and always says something."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:string {:min 1}]]}
  [^Throwable failure]
  (let [message (ex-message failure)]
    (if (or (nil? message) (= "" (.trim ^String message)))
      (.getName (class failure))
      message)))

(defn- program-graph-arglists
  {:malli/schema [:=> [:cat :symbol] [:map
     [:seon.instrument.lookup/status [:enum :found :missing :failed :no-program-graph]]
     [:seon.instrument.lookup/cause {:optional true} [:string {:min 1}]]
     [:seon.fn/arglists {:optional true} :string]
     [:seon.instrument/arglists {:optional true} [:sequential [:sequential :seon.schema/value]]]]]}
  [function-symbol]
  (try
    (if-let [environment (env/of effect/*request-context*)]
      (if-let [connection (:seon.db/connection environment)]
        (let [database (db/db connection)]
          (if-not (db/database-value? database)
            {:seon.instrument.lookup/status :failed}
            (let [result
                  (db/q '[:find ?arglists .
                          :in $ ?function-symbol
                          :where
                          [?function :seon.fn/sym ?function-symbol]
                          [?function :seon.fn/arglists ?arglists]]
                        database function-symbol)]
              (cond
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
  {:malli/schema [:=> [:cat :symbol] [:map
     [:seon.instrument.lookup/status [:enum :found :missing :failed :no-program-graph]]
     [:seon.instrument.lookup/cause {:optional true} [:string {:min 1}]]
     [:seon.fn/arglists {:optional true} :string]
     [:seon.instrument/arglists {:optional true} [:sequential [:sequential :seon.schema/value]]]]]}
  [function-symbol]
  (try
    (if-let [arglists (when (or (not (qualified-symbol? function-symbol))
                               (find-ns (symbol (namespace function-symbol))))
                       (some-> function-symbol find-var meta :arglists))]
      {:seon.instrument.lookup/status :found ::arglists arglists}
      {:seon.instrument.lookup/status :missing})
    (catch Throwable failure
      {:seon.instrument.lookup/status :failed
       :seon.instrument.lookup/cause (failure-cause failure)})))

(defn- diagnostic-arglists
  "The loaded Var owns host arglists; the program row describes SCI-only code."
  {:malli/schema [:=> [:cat :symbol] [:map
     [:seon.instrument.lookup/status [:enum :found :missing :failed :no-program-graph]]
     [:seon.instrument.lookup/cause {:optional true} [:string {:min 1}]]
     [:seon.fn/arglists {:optional true} :string]
     [:seon.instrument/arglists {:optional true} [:sequential [:sequential :seon.schema/value]]]]]}
  [function-symbol]
  (let [loaded (jvm-arglists function-symbol)]
    (if (not= :missing (:seon.instrument.lookup/status loaded))
      loaded
      (let [{status :seon.instrument.lookup/status
             stored :seon.fn/arglists :as lookup}
            (program-graph-arglists function-symbol)]
        (if (= :found status)
          (try
            {:seon.instrument.lookup/status :found
             ::arglists (edn/read-string stored)}
            (catch Throwable failure
              {:seon.instrument.lookup/status :failed
               :seon.instrument.lookup/cause (failure-cause failure)}))
          lookup)))))

(defn- supplied-entry-problems
  {:malli/schema [:=> [:cat :qualified-symbol] [:or :nil [:set [:tuple :int :keyword]]]]}
  [function-symbol]
  (when-let [environment (env/of effect/*request-context*)]
    (when-let [connection (:seon.db/connection environment)]
      (let [entries (call-preparation/supplied-map-entries (db/db connection)
                                                           function-symbol)]
        (if (vector? entries)
          (into #{} (map (fn [[_ position entry-key]] [position entry-key])) entries)
          (throw (ex-info (:seon.error/message entries) entries)))))))

(defn- actionable-problem
  {:malli/schema [:=> [:cat :seon.error/problem-description :map [:or :nil :boolean]] :seon.error/problem-description]}
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
  {:malli/schema [:=> [:cat [:or :nil :seon.sci.admit/caps] :qualified-keyword :map]  :seon.error/base]}
  [_caps kind data]
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
                       (-> problem (assoc :schema (:schema union))
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
                              (when (integer? position)
                                (first (nth (m/children offended) position nil))))
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
       (merge {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.instrument/invocation
         :seon.error/operation function-symbol
         :seon.error/message (str (error/problem-sentence
               function-symbol first-problem nil
               (error/scalar-text (:seon.error/offending first-problem)))
              (when (qualified-keyword? expected)
                (str " Contract: " expected "."))
              (when caller (str " Called from " caller ".")))
         :seon.error/expected expected
         :seon.error/offending offending
         :seon.error/member (case arm :output :seon.fn.arity/output :guard :seon.fn.arity/guard
                                  (if arity? :seon.instrument/arity :seon.fn.arity/input))}
        (cond-> {::malli kind ::arm arm ::fn function-symbol
                  ::problem-count (count problems)
                  :seon.error/problems problems}
           arity? (assoc ::arity (:arity data))
           arglists (assoc ::arglists arglists)
           (seq paths) (assoc ::problem-paths paths)
           caller (assoc ::caller caller))
          (when arity?
            (select-keys lookup [:seon.instrument.lookup/status
                                 :seon.instrument.lookup/cause])))))

;;; ---------------------------------------------------------------------------
;;; Interpreted function contracts
;;; ---------------------------------------------------------------------------

(def ^:private interpreted-original ::interpreted-original)

(defn- original-interpreted
  {:malli/schema [:=> [:cat :seon.instrument/callable] :seon.instrument/callable]}
  [f]
  (or (some-> f meta interpreted-original) f))

(defn- predicate-callable
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] [:or :nil :seon.instrument/callable]]}
  [projection predicate]
  (or (get ((mi/-f->original schema/predicate-functions-in) projection)
           predicate)
      (when (qualified-symbol? predicate)
        (some-> predicate requiring-resolve deref))))

(defn- bind-contract-predicates
  "Bind named Malli predicates without opening Malli's code evaluator."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] :seon.schema/value]}
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
              {:seon.error/at (java.util.Date.)
               :seon.error/layer :seon.instrument/registration
               :seon.error/operation 'seon.instrument/bind-contract-predicates
               :seon.schema/unresolved-predicate predicate
               :seon.error/message "The schema predicate is unresolved."
               :seon.schema/predicate predicate}))))
       :else value))
   contract))

(declare compiled-wrapper registration-error ^:dynamic *compiling-contract*)

(defn wrap-interpreted
  "Apply one committed agent function contract under the core-error dial.

  Both dials use the host boundary's per-arity enforcement. Record mode
  requires a recording operation acquired by the caller before installation."
  {:malli/schema
   [:function
    [:=> [:cat :qualified-symbol :string :map :seon.config/on-core-error
          [:or :seon.sci.admit/caps :seon.config/error :seon.error/base] :seon.instrument/callable]
     :seon.instrument/callable]
    [:=> [:cat :qualified-symbol :string :map :seon.config/on-core-error
          [:or :seon.sci.admit/caps :seon.config/error :seon.error/base] :seon.instrument/callable
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]
           [:seon.config.error/max-evidence-bytes {:optional true}
            :seon.config.error/max-evidence-bytes]
           [:seon.program/definition-digest {:optional true} :seon.program/definition-digest]
           [:seon.profile/context {:optional true} :seon.profile/context]]]
     :seon.instrument/callable]]}
  ([function-symbol spec-edn projection mode caps f]
   (wrap-interpreted function-symbol spec-edn projection mode caps f {}))
  ([function-symbol spec-edn projection mode caps f arm-request]
  (let [original (original-interpreted f)
        callable-identity (merge {:seon.profile/sym function-symbol
                                  :seon.profile/scope :seon.profile/context}
                                 (when-let [digest (:seon.program/definition-digest arm-request)]
                                   {:seon.profile/digest digest})
                                 (select-keys arm-request [:seon.profile/context]))
        prior (::cell (meta f))
        cell (if (profile/reusable? prior callable-identity
                                    (some-> f meta interpreted-original) original)
               prior
               (profile/cell callable-identity))
        arm-request (dissoc arm-request :seon.program/definition-digest :seon.profile/context)]
    (when (and (= :record mode) (not (:seon.flow/commit-fault! arm-request)))
      (let [failure
            (registration-error
             function-symbol
             {:seon.error/message "Record-mode SCI instrumentation requires an acquired fault recorder."
              :seon.error/layer :instrumentation
              :seon.error/operation 'seon.instrument/wrap-interpreted
              :seon.error/member :seon.flow/commit-fault!
              :seon.error/expected :seon.flow/commit-fault!
              :seon.error/offending ::absent})]
        (throw (ex-info (:seon.error/message failure) failure))))
    (when-not (and (map? caps)
                   (pos-int? (:seon.config.eval.result/max-bytes caps))
                   (pos-int? (:seon.config.eval.result/max-source caps)))
      (let [failure
            (registration-error
             function-symbol
             {:seon.error/message (str "Cannot arm the contract of " function-symbol
                                       ": admission caps were not acquired.")
              :seon.error/layer :instrumentation
              :seon.error/operation 'seon.instrument/wrap-interpreted
              :seon.error/member :seon.sci.admit/caps
              :seon.error/expected :seon.sci.admit/caps
              :seon.error/offending caps})]
        (throw (ex-info (:seon.error/message failure) failure))))
      (let [wrapped (binding [*compiling-contract* true]
                      (compiled-wrapper projection function-symbol
                                        (edn/read-string spec-edn) original caps
                                        (assoc arm-request
                                               :seon.config/on-core-error mode
                                               :seon.sci.admit/caps caps
                                               :seon.config.error/max-evidence-bytes
                                               (or (:seon.config.error/max-evidence-bytes arm-request)
                                                   (:seon.config.error/max-evidence-bytes
                                                    config/defaults)))))]
        ;; The compiled wrapper is shared through the projection cache; the
        ;; timing layer is this installation's own, so its cell is too.
        (profile/with-cell cell
          (with-meta (fn [& arguments] (profile/timed (apply wrapped arguments)))
            (assoc (meta wrapped) interpreted-original original ::cell cell)))))))

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn- var-symbol
  {:malli/schema [:=> [:cat :seon.instrument/loaded-var] :qualified-symbol]}
  [candidate-var]
  (let [{namespace-object :ns var-name :name} (meta candidate-var)]
    (symbol (str (ns-name namespace-object)) (str var-name))))

(defn- registration-cause-data
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.schema/value]}
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
  {:malli/schema [:=> [:cat :seon.schema/value :qualified-keyword] :seon.schema/value]}
  [value member]
  (when (map? value)
    (try (get value member)
         (catch ClassCastException _ nil))))

(defn- supplied-projection
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] [:or :nil :seon.schema/projection]]}
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
                      (:seon.schema.shape/fingerprint
                       (schema-shape/normalized-form (:schema problem)
                         (:seon.schema.projection/forms projection)
                         (schema/predicate-functions-in projection)))
                      :seon.instrument.explanation/actual
                      (error/project-observation caps (:value problem))
                      :seon.instrument.explanation/humanization-unavailable
                      "The original problem is represented by its schema and value paths."})
                   (:errors explanation))]
        (assoc base :seon.instrument/check check
               :seon.error/expected-shape
               (:seon.schema.shape/fingerprint
                (schema-shape/normalized-form contract
                  (:seon.schema.projection/forms projection)
                  (schema/predicate-functions-in projection)))
               :seon.error/location (observation-location caps [])
               :seon.instrument/explanations
               {:seon.instrument.explanations/count (count items)
                :seon.instrument.explanations/items (set items)})))))

(defn- compiled-wrapper
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :seon.schema/value :seon.instrument/callable :seon.sci.admit/caps [:? [:or :nil :map]]] :seon.instrument/callable]}
  [projection function-symbol authored original caps & [policy]]
  (let [contract (get (:seon.schema.projection/function-contracts projection)
                         function-symbol authored)
           retained (mr/schema (:seon.schema.projection/registry projection) function-symbol)
           bound (when-not retained
                   (bind-contract-predicates
                    projection
                    ((mi/-f->original schema/compilable-form)
                     contract
                     ((mi/-f->original schema/predicate-functions-in) projection))))
           caps (assoc caps
                       :seon.config.eval.result/max-bytes
                       (:seon.config.error/max-evidence-bytes policy))
           options {:registry (mr/composite-registry
                               (:seon.schema.projection/registry projection)
                               (mr/var-registry))}
           compiled (or retained (m/schema bound options))
           arities (mapv m/-function-info (m/-function-schema-arities compiled))
           refusal? ((mi/-f->original schema/projection-cache-value)
                     projection ::refusal-validator
                     #((mi/-f->original schema/projection-validator) projection :seon.instrument/refusal-result))
           marker (Object.)
           reject! (fn [declared-schema value]
                     (when-not (refusal? value)
                       (throw (ex-info "Instrumentation constructed an invalid refusal." value)))
                     (throw (ex-info (:seon.error/message value)
                                     (with-meta value {::boundary marker
                                                       :seon.error/declared-schema declared-schema}))))
           wrapped
           (m/-instrument
            {:schema compiled :scope #{:input :output :guard}
             :report (fn [kind data]
                       (binding [*compiling-contract* true]
                         (reject! (if (= :malli.core/invalid-arity kind)
                                    :seon.instrument/arity-error :seon.instrument/contract-error)
                                  (boundary-refusal projection caps kind
                                                    (assoc data :fn-name function-symbol) arities))))}
            original options)]
       (fn [& arguments]
         (try (apply wrapped arguments)
              (catch clojure.lang.ExceptionInfo failure
                (if (and (= :record (:seon.config/on-core-error policy))
                         (identical? marker (::boundary (meta (ex-data failure)))))
                  (let [value (ex-data failure)
                        outcome ((:seon.flow/commit-fault! policy)
                                 {:seon.error/source value
                                  :seon.error/declared-schema (:seon.error/declared-schema (meta value))})]
                    (if (= :seon.flow/committed (second outcome)) value
                        (throw (ex-info "Recording the instrumentation refusal failed." value failure))))
                  (throw failure)))))))

(defn- contract-definitions
  "Canonical declarations closed over by a function contract, following Malli refs."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] [:map-of :keyword :seon.schema/value]]}
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
  {:malli/schema [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.schema/value [:? [:or :nil :map]]] :boolean]}
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

(defn- validates-loaded-contract?
  "True when `projection` can validate the loaded function's own contract.

  It must carry the same function contract the Var was armed with (a stored
  row of a cluster that retains an older program may declare another) and
  declare every schema key of `definitions`."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :seon.schema/value
                       [:map-of :keyword :seon.schema/value]] :boolean]}
  [projection function-symbol contract definitions]
  (let [forms (:seon.schema.projection/forms projection)]
    (and (= contract (get (:seon.schema.projection/function-contracts projection)
                          function-symbol contract))
         (every? #(some? (find forms %)) (keys definitions)))))

(defn- arm-var!
  {:malli/schema
   [:function
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.schema/projection :seon.sci.admit/caps] :seon.instrument/callable]
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.schema/projection :seon.sci.admit/caps [:or :nil :map]] :seon.instrument/callable]
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.schema/projection :seon.sci.admit/caps [:or :nil :map] [:or :nil :seon.program/definition-digest]] :seon.instrument/callable]]}
  ([candidate authored projection bootstrap caps]
   (arm-var! candidate authored projection bootstrap caps nil nil))
  ([candidate authored projection bootstrap caps policy]
   (arm-var! candidate authored projection bootstrap caps policy nil))
  ([candidate authored projection bootstrap caps policy digest]
  (alter-var-root
   candidate
   (fn [current]
     (if (current-wrapper? candidate authored projection current policy)
       current
       (let [original (mi/-f->original current)
             function-symbol (var-symbol candidate)
             callable-identity (cond-> {:seon.profile/sym function-symbol
                                        :seon.profile/scope :seon.profile/host}
                                 digest (assoc :seon.profile/digest digest))
             prior (::cell (meta current))
             cell (if (profile/reusable? prior callable-identity
                                         (:malli.instrument/original (meta current)) original)
                    prior
                    (profile/cell callable-identity))
             contract (get (:seon.schema.projection/function-contracts projection)
                           function-symbol authored)
             definitions (contract-definitions projection contract)
             boot-wrapper (delay
                            (binding [*compiling-contract* true]
                              (compiled-wrapper bootstrap function-symbol
                                                authored original caps policy)))
             supplied-wrapper (atom nil)]
         (profile/with-cell cell
          (with-meta
           (fn [& arguments]
             (if *compiling-contract*
               (apply original arguments)
               (profile/timed
                (let [wrapped
                      (binding [*compiling-contract* true]
                        (or (when-let [projection (supplied-projection arguments)]
                              ;; A supplied projection validates this call only
                              ;; when it carries the loaded contract and declares
                              ;; every schema it closes over. A cluster whose
                              ;; stored program predates the loaded files lacks
                              ;; them; the files' own declarations then decide.
                              (when (validates-loaded-contract? projection function-symbol
                                                                contract definitions)
                                (if (identical? projection (first @supplied-wrapper))
                                  (second @supplied-wrapper)
                                  (let [wrapper (compiled-wrapper projection function-symbol
                                                                  authored original caps policy)]
                                    (reset! supplied-wrapper [projection wrapper])
                                    wrapper))))
                            @boot-wrapper))]
                  (apply wrapped arguments)))))
           {:malli.instrument/original original
            ::cell cell
            ::policy policy
            :seon.instrument/var candidate
            :seon.instrument/authored authored
            :seon.instrument/contract contract
            :seon.instrument/definitions definitions}))))))))

(defn- collect-contracts!
  "Read declarations from the program loaded into this JVM, without Malli's registry."
  {:malli/schema [:=> [:cat :seon.sci.admit/caps] [:map-of :seon.instrument/loaded-var :seon.schema/value]]}
  [_caps]
  (into {}
        (keep (fn [candidate]
                (when-let [authored (mi/-schema candidate)]
                  (when (and (bound? candidate)
                             (not (primitive-fn? @candidate)))
                    [candidate authored]))))
        (mapcat (comp vals ns-interns) (all-ns))))

(defn- registration-error
  "Describe the particular declaration or acquisition that could not be armed."
  {:malli/schema
   [:=> [:cat :qualified-symbol
         [:map [:seon.error/member [:or :qualified-keyword :qualified-symbol]]
          [:seon.error/operation :qualified-symbol]]]
    :seon.instrument/registration-error]}
  [function-symbol request]
  (assoc (seon.error.refusal/diagnostic
          (assoc request :seon.error/at (java.util.Date.)
                         :seon.error/layer :seon.instrument/registration
                         :seon.error/operation (:seon.error/operation request)))
         :seon.instrument/fn function-symbol
         :seon.instrument/registration-observation
         {:seon.error.evidence/attribute :seon.error/member
          :seon.error.evidence/value (:seon.error/member request)}))

(defn apply!
  "Arm only the supplied changed function identities on adoption.
  A missing changed-identities member collects the complete loaded program.
  An identity without a retained contract is disarmed on adoption.
  Unrelated contracts are neither inspected nor re-armed.

  Host wrappers capture the supplied disposition and recording operation at
  arm time. Calls validate through their supplied projection or the captured
  bootstrap declarations. Record-mode acquisition requires a recorder."
  {:malli/schema
   [:=> [:cat :seon.instrument/request]
    [:or :seon.instrument/applied :seon.instrument/registration-error]]}
  [{mode :seon.config/on-core-error
    caps :seon.sci.admit/caps
    max-evidence-bytes :seon.config.error/max-evidence-bytes
    commit-fault! :seon.flow/commit-fault!
    supplied-projection :seon.schema/projection
    digests :seon.profile/definition-digests
    :as request}]
  (cond
    (and (= :record mode) (not (fn? commit-fault!)))
    (registration-error 'seon.instrument/apply!
     {:seon.error/message "Record-mode instrumentation requires an acquired fault recorder."
      :seon.error/layer :instrumentation
      :seon.error/operation 'seon.instrument/apply!
      :seon.error/member :seon.flow/commit-fault!
      :seon.error/expected :seon.flow/commit-fault!
      :seon.error/offending ::absent})

    (not (#{:panic :record} mode))
    (registration-error 'seon.instrument/apply!
       {:seon.error/message "Instrumentation requires :panic or :record core-error mode."
        :seon.error/layer :instrumentation
        :seon.error/operation 'seon.instrument/apply!
        :seon.error/member :seon.config/on-core-error
        :seon.error/expected [:enum :panic :record]
        :seon.error/offending (if (nil? mode) ::nil mode)
        :seon.error/data {:seon.instrument/accepted-modes [:panic :record]}})

    :else
    (let [projection (or supplied-projection (schema/handed-projection))]
      (if-not projection
        (registration-error 'seon.instrument/apply!
         {:seon.error/message "Instrumentation requires a handed schema projection."
          :seon.error/layer :instrumentation
          :seon.error/operation 'seon.instrument/apply!
          :seon.error/member :seon.schema/projection
          :seon.error/expected :seon.schema/projection
          :seon.error/offending :seon.instrument/missing-projection})
        (let [defaults config/defaults
              caps (or caps (config/result-caps defaults))
              policy (cond-> {:seon.config/on-core-error mode
                              :seon.sci.admit/caps caps
                              :seon.config.error/max-evidence-bytes
                              (or max-evidence-bytes
                                  (:seon.config.error/max-evidence-bytes defaults))}
                       (and (= :record mode) commit-fault!)
                       (assoc :seon.flow/commit-fault! commit-fault!))
              changed (find request :seon.instrument/changed-identities)
              candidates (when changed
                           (into #{}
                                 (keep (fn [[_ function-symbol]]
                                         (when (find-ns (symbol (namespace function-symbol)))
                                           (find-var function-symbol))))
                                 (val changed)))
              contracts (if changed
                          (into {}
                                (keep (fn [candidate]
                                        (when (and (bound? candidate)
                                                   (not (primitive-fn? @candidate))
                                                   (mr/schema (:seon.schema.projection/registry projection)
                                                              (var-symbol candidate)))
                                          (when-let [authored (mi/-schema candidate)]
                                            [candidate authored]))))
                                candidates)
                          (collect-contracts! caps))
              pending (if changed
                        contracts
                        (remove (fn [[candidate authored]]
                                  (current-wrapper? candidate authored projection @candidate policy))
                                contracts))]
          (doseq [candidate candidates
                  :when (and (bound? candidate) (not (find contracts candidate)))]
            (alter-var-root candidate mi/-f->original))
          (doseq [[candidate authored] pending]
            (try
              (binding [*compiling-contract* true]
                (compiled-wrapper projection (var-symbol candidate)
                                  authored (mi/-f->original @candidate) caps policy))
              (catch Throwable failure
                (let [data (registration-cause-data failure)
                      diagnostic
                      (registration-error (var-symbol candidate)
                       {:seon.error/message (str "The loaded function contract " (var-symbol candidate)
                                                " cannot compile: " (ex-message failure))
                        :seon.error/layer :instrumentation
                        :seon.error/operation 'seon.instrument/apply!
                        :seon.error/expected authored
                        :seon.error/offending (or (:schema data) (get-in data [:data :ref])
                            (get-in data [:data :schema]))
                        :seon.error/member (var-symbol candidate)})]
                  (throw (ex-info (:seon.error/message diagnostic)
                                  diagnostic failure)))))
            (when changed (alter-var-root candidate mi/-f->original))
            (arm-var! candidate authored projection projection caps policy
                      (get digests (var-symbol candidate))))
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
      [:map-of :seon.instrument/loaded-var :seon.instrument/callable]]
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
           [:map-of :seon.instrument/loaded-var :seon.instrument/callable]]
          [:seon.instrument/function-schemas :map]]]
    [:set :seon.instrument/loaded-var]]}
  [state]
  (into #{}
        (keep (fn [[candidate captured]]
                (when (and (bound? candidate)
                           (not (identical? (mi/-f->original captured)
                                            (mi/-f->original @candidate))))
                  candidate)))
        (:seon.instrument/roots state)))

(defn restore!
  "Restore `state`, arming replaced definitions without restoring old closures.

  Unarm the wrappers this JVM now holds, put Malli's function-schema registry
  and the captured roots back, and skip both steps for the Vars
  `replaced-definitions` names: reinstalling one of those closures leaves the
  Var emitting values the current protocol no longer recognises. The measured
  case is `seon.print/text-sink` handing back a superseded `seon.print.TextSink`
  that the reloaded `seon.print/sink?` (`src/seon/print.cljc:27`) refuses, so
  the Var's own armed output contract refused every later call in that JVM.

  Replaced definitions with captured arming policy are compiled against current
  declarations and armed around their new roots. No old closure is restored.

  Returns the Vars whose definitions changed; an empty set — the
  ordinary case — says nothing was replaced, and a non-empty set is the
  evidence that something reloaded inside the scope."
  {:malli/schema
   [:=> [:cat
         [:map
          [:seon.instrument/roots
           [:map-of :seon.instrument/loaded-var :seon.instrument/callable]]
          [:seon.instrument/function-schemas :map]]]
    [:set :seon.instrument/loaded-var]]}
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
    (let [pending (keep (fn [candidate]
                          (when-let [policy (::policy (meta (get roots candidate)))]
                            (when-let [authored (:malli/schema (meta candidate))]
                              [candidate authored policy])))
                        replaced)]
      (when (seq pending)
        (let [projection ((mi/-f->original schema/declaration-projection)
                          (schema.edn/packaged-forms))]
          ;; Compile the complete replacement set before installing any wrapper.
          (doseq [[candidate authored policy] pending]
            (binding [*compiling-contract* true]
              (compiled-wrapper projection (var-symbol candidate) authored
                                (mi/-f->original @candidate)
                                (:seon.sci.admit/caps policy) policy)))
          (doseq [[candidate authored policy] pending]
            (arm-var! candidate authored projection projection
                      ;; A replaced definition's digest is unknown here.
                      (:seon.sci.admit/caps policy) policy nil)))))
    replaced))


(defn replaced-roots
  "First-party Vars whose current root is not their own namespace's compile.

  A `defn` compiles its root as the class `<munged ns>$<munged name>` and a
  load records its file as the Var's `:file`. A root replaced outside a reload
  (`alter-var-root`, `with-redefs`, `intern`, an evaluation in another
  namespace) has another class; a redefinition loaded from another file keeps
  the class name and records that file. `rows` pairs each `defn`/`defn-`
  row's symbol with its relative source path; a Var this JVM has not loaded,
  or whose root is not a compiled function, is not reported. The armed
  wrapper is looked through to the function it wraps."
  {:malli/schema [:=> [:cat [:sequential [:tuple :qualified-symbol :string]]]
                  [:vector :seon.instrument/replaced-root]]}
  [rows]
  (into []
        (keep (fn [[function-symbol relative-path]]
                (when-let [candidate (when (find-ns (symbol (namespace function-symbol)))
                                       (find-var function-symbol))]
                  (let [root (when (bound? candidate) (mi/-f->original @candidate))
                        expected (str (clojure.lang.Compiler/munge (namespace function-symbol))
                                      "$" (clojure.lang.Compiler/munge (name function-symbol)))
                        loaded-file (:file (meta candidate))
                        class-name (when (instance? clojure.lang.AFunction root)
                                     (.getName (class root)))]
                    (when (and class-name
                               (or (not= expected class-name)
                                   ;; A classpath load records `seon/x.clj`; a
                                   ;; `load-file` records the absolute path.
                                   (not (and (string? loaded-file)
                                             (or (.endsWith ^String relative-path ^String loaded-file)
                                                 (.endsWith ^String loaded-file ^String relative-path))))))
                      (cond-> {:seon.instrument/replaced-var function-symbol
                               :seon.instrument/root-class class-name
                               :seon.instrument/expected-class expected
                               :seon.instrument/expected-file relative-path}
                        (string? loaded-file) (assoc :seon.instrument/loaded-file loaded-file)))))))
        rows))

(def loaded-var-generator
  "A real Var from this owner for the loaded-Var contract."
  (gen/return #'apply!))
