(ns seon.instrument
  "JVM-owned host wrappers with contracts compiled in the caller's projection.

  A loaded Var has one wrapper for its authored contract. Repeated arming
  preserves unchanged wrappers; teardown cannot remove them. Changed roots
  or authored contracts are armed on the next apply!.
  Compiled validators belong to the immutable projection that defines them.
  Host calls without cluster custody use the packaged JVM program captured at
  arming; they never consult Malli's global registry."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [seon.call-preparation :as call-preparation]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
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
                        database (str function-symbol))]
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
    (catch Throwable _
      {:seon.instrument.lookup/status :failed})))

(defn- jvm-arglists
  [function-symbol]
  (try
    (some-> function-symbol find-var meta :arglists)
    (catch Throwable _ nil)))

(defn- diagnostic-arglists
  [function-symbol]
  (let [{status :seon.instrument.lookup/status
         stored :seon.fn/arglists}
        (program-graph-arglists function-symbol)]
    (case status
      :found (try
               {:seon.instrument.lookup/status :found
                ::arglists (edn/read-string stored)}
               (catch Throwable failure
                 {:seon.instrument.lookup/status :failed
                  :seon.instrument.lookup/cause (ex-message failure)}))
      ;; With a graph, only an established miss reaches JVM metadata. Outside
      ;; an evaluation there is no program graph to consult; that is the
      ;; system-side, compiled-function case this fallback exists for.
      (:missing :no-program-graph)
      (if-let [arglists (jvm-arglists function-symbol)]
        {:seon.instrument.lookup/status :found
         ::arglists arglists}
        {:seon.instrument.lookup/status status})
      {:seon.instrument.lookup/status :failed})))

(defn- minimal-violation
  [kind data]
  (let [function-symbol (:fn-name data)
        arity? (= :malli.core/invalid-arity kind)]
    (error/diagnostic
     {:seon.error/kind ::contract-violated
      :seon.instrument/contract-violated (str function-symbol)
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
        function-symbol (assoc ::fn (str function-symbol)))})))

(defn- supplied-entry-problems
  [function-symbol]
  (when-let [environment (env/of effect/*request-context*)]
    (when-let [connection (:seon.db/connection environment)]
      (let [entries (call-preparation/supplied-map-entries (db/db connection)
                                                           (str function-symbol))]
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
     (buried-error kind data)
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
                  (assoc
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
                   :seon.error/schema-path (vec (:path problem)))))
              (:errors explanation)))
           first-problem (first problems)
           expected (if arity? arglists (m/form offended))
           expected (if (and (vector? expected) (= :cat (first expected))
                             (= 2 (count expected)))
                      (second expected) expected)
           offending (if arity? (:arity data)
                         (if (= :input arm)
                           [(:value (first (:errors explanation)))]
                           (:value (first (:errors explanation)))))
           paths (into [] (comp (map :seon.error/path) (remove empty?)) problems)
           caller (caller-frame)]
       (error/diagnostic
        {:seon.error/kind (if (some #(and (= :malli.core/missing-key (:type %))
                                          (contains? supplied-entries (vec (:in %))))
                                    (:errors explanation))
                            ::missing-supplied-key ::contract-violated)
         :seon.instrument/contract-violated (str function-symbol)
         :seon.error/message
         (str function-symbol " refused " (:seon.error/argument first-problem)
              " at " (pr-str (:seon.error/path first-problem))
              ": expected " (:seon.error/expected-description first-problem)
              ", got " (:seon.error/actual-description first-problem)
              ". Fix: " (:seon.error/fix first-problem)
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
           (select-keys lookup [:seon.instrument.lookup/status ::arglists])
           (cond-> {::problem-count (count problems)}
             caller (assoc ::caller caller)))
         :seon.error/data
         (cond-> {::malli kind ::arm arm ::fn (str function-symbol)
                  ::problem-count (count problems)
                  :seon.error/problems problems}
           arity? (assoc ::arity (:arity data))
           arglists (assoc ::arglists arglists)
           (seq paths) (assoc ::problem-paths paths)
           caller (assoc ::caller caller))})))
    (catch Throwable _
      (minimal-violation kind data))))

(defn- throwing-report
  "The `:panic` reporter: raise the violation as our own flat error.
  Deliberately NOT `m/-fail!`. The ex-data carries `:seon.error/kind`,
  so when this throw escapes a flow proc the fault path classifies it
  from the cause chain like any other refusal and the durable fact says
  `::contract-violated` rather than naming malli."
  [caps]
  (fn [kind data]
    (let [value (violation caps kind data)]
      (throw (ex-info (:seon.error/message value) value)))))

;;; ---------------------------------------------------------------------------
;;; Interpreted function contracts
;;; ---------------------------------------------------------------------------

(def ^:private interpreted-original ::interpreted-original)

(defn- original-interpreted
  [f]
  (or (some-> f meta interpreted-original) f))

(defn- predicate-callable
  [projection predicate]
  (or (get (:seon.schema.projection/predicate-functions projection)
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

(defn wrap-interpreted
  "Apply one committed agent function contract under the core-error dial.

  `caps` is EITHER the admission caps or the bounded refusal
  `seon.config/result-caps` builds when a database carries no config the
  caps could be derived from. `:record` — which instruments nothing and
  undoes what is there — never reads them, so a database with no config
  still gets its uninstrumented function. `:panic` DOES read them, and
  arming a panic contract whose violation reporter has no bound is the
  unbounded-execution shape this project refuses: the refusal is raised
  here, naming the function and the config key that was missing."
  {:malli/schema
   [:=>
    [:cat :symbol :string :map :seon.config/on-core-error
     [:or :seon.sci.admit/caps :seon.error/value] [:fn clojure.core/ifn?]]
    [:fn clojure.core/ifn?]]}
  [function-symbol spec-edn projection mode caps f]
  (let [original (original-interpreted f)]
    (when (and (= :panic mode) (:seon.error/kind caps))
      (throw
       (ex-info
        (str "Cannot arm the contract of " function-symbol
             " under :panic: " (:seon.error/message caps))
        (assoc caps :seon.instrument/fn (str function-symbol)))))
    (case mode
      :panic
      (let [contract (->> (edn/read-string spec-edn)
                          (bind-contract-predicates projection))
            report (throwing-report caps)
            wrapped
            (m/-instrument
             {:schema contract
              :scope #{:input :output :guard}
              :report (fn [kind data]
                        (report kind (assoc data :fn-name function-symbol)))}
             original
             (:seon.schema.projection/compile-options projection))]
        (with-meta wrapped
          (assoc (meta wrapped) interpreted-original original)))

      :record original)))

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
                    [(request-member argument :seon.schema/projection)
                     (request-member
                      (request-member argument :seon.env/environment)
                      :seon.schema/projection)]))
            arguments)
      (let [projection ((mi/-f->original schema/handed-projection))]
        (when (request-member projection :seon.schema.projection/registry)
          projection))))

(defn- compiled-wrapper
  [projection function-symbol authored original caps]
  ((mi/-f->original schema/projection-cache-value)
   projection [::wrapper function-symbol authored original]
   (fn []
     (let [contract (get (:seon.schema.projection/function-contracts projection)
                         function-symbol authored)
           bound (bind-contract-predicates
                  projection
                  ((mi/-f->original schema/compilable-form)
                   contract
                   (get projection :seon.schema.projection/predicate-functions {})))
           report (throwing-report caps)]
       (m/-instrument
        {:schema bound :scope #{:input :output :guard}
         :report (fn [kind data]
                   (report kind (assoc data :fn-name function-symbol)))}
        original
        {:registry (mr/composite-registry
                    (:seon.schema.projection/registry projection)
                    (mr/var-registry))})))))

(defn- current-wrapper?
  [candidate authored current]
  (let [metadata (meta current)]
    (and (identical? candidate (::var metadata))
         (= authored (::authored metadata)))))

(defn- arm-var!
  [candidate authored bootstrap caps]
  (alter-var-root
   candidate
   (fn [current]
     (if (current-wrapper? candidate authored current)
       current
       (let [original (mi/-f->original current)
             function-symbol (var-symbol candidate)
             boot-wrapper (delay
                            (binding [*compiling-contract* true]
                              (compiled-wrapper bootstrap function-symbol
                                                authored original caps)))]
         (with-meta
           (fn [& arguments]
             (if *compiling-contract*
               (apply original arguments)
               (let [wrapped
                     (binding [*compiling-contract* true]
                       (if-let [projection (supplied-projection arguments)]
                         (compiled-wrapper projection function-symbol
                                           authored original caps)
                         @boot-wrapper))]
                 (apply wrapped arguments))))
           {::mi/original original ::var candidate ::authored authored}))))))

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
  "Arm loaded Vars whose wrapper does not enforce their current authored schema.
  Unchanged wrappers remain identical; metadata-only contract edits re-arm.

  Cluster :record requests cannot disable shared host contracts. Interpreted
  function policy remains local to wrap-interpreted. New host calls validate
  through their request projection or the existing evaluation carrier."
  {:malli/schema
   [:=> [:cat :seon.instrument/request]
    [:or :seon.instrument/applied :seon.error/value]]}
  [{mode :seon.config/on-core-error
    caps :seon.sci.admit/caps
    supplied-projection :seon.schema/projection}]
  (cond
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
        (let [contracts (collect-contracts! caps)
              pending (remove (fn [[candidate authored]]
                                (current-wrapper? candidate authored @candidate))
                              contracts)
              bootstrap (when (seq pending)
                          ((mi/-f->original schema/declaration-projection)
                           (schema.edn/packaged-forms)))]
          (doseq [[candidate authored] pending]
            (try
              (binding [*compiling-contract* true]
                (compiled-wrapper projection (var-symbol candidate)
                                  authored (mi/-f->original @candidate) caps))
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
            (arm-var! candidate authored bootstrap caps))
          {:seon.instrument/registered (count contracts)
           :seon.instrument/instrumented (count (instrumented))})))))

(defn remove!
  "Return the shared JVM wrapper count. Cluster teardown cannot remove host contracts."
  {:malli/schema [:=> [:cat] :seon.instrument/instrumented]}
  []
  (count (instrumented)))
