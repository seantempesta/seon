(ns seon.instrument
  "JVM-owned host wrappers with contracts compiled in the caller's projection.

  A loaded Var has one wrapper. Repeated cluster arming preserves its identity;
  teardown cannot remove it. A replaced Var root is armed on the next apply!.
  Compiled validators belong to the immutable projection that defines them.
  Host calls without cluster custody use the packaged JVM program captured at
  arming; they never consult Malli's global registry."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; What is instrumented, as a question anybody can ask
;;; ---------------------------------------------------------------------------

(defn instrumented
  "Loaded Vars bearing their own host wrapper, derived without a registry."
  {:malli/schema [:=> [:cat] [:set :any]]}
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
  {:malli/schema [:=> [:cat [:sequential :symbol]] [:set :any]]}
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

(defn- admitted-value
  [caps value]
  (:seon.sci.admit/value
   (admit/admit
    {:seon.sci.admit/value value
     :seon.sci.admit/interrupt-fn (constantly nil)
     :seon.sci.admit/caps caps
     ;; the reporter may not panic on the way to reporting a panic
     :seon.config/on-core-error :record})))

(def ^:private contract-evidence-caps
  ;; The admitted inline ceiling is 4,096 characters. These structural caps
  ;; leave room for the function, arm, expected shape, and problem count while
  ;; retaining the exact offending key/value pair. They narrow the caller's
  ;; caps only for contract evidence; the original value is never admitted or
  ;; retained wholesale past this bounded construction.
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 4
   :seon.config.eval.result/max-string 256
   :seon.config.eval.result/max-nodes 32})

(defn- evidence-caps
  [caps]
  (merge-with min caps contract-evidence-caps))

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

(defn- offending-leaf
  "One exact map key plus bounded value, or one bounded scalar value."
  [caps path value]
  (if (and (empty? path) (map? value))
    (if-let [[entry-key entry-value] (first value)]
      {(admitted-value caps entry-key) (admitted-value caps entry-value)}
      {})
    (admitted-value caps value)))

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

(def ^:private headline-problem-paths
  ;; The headline is a CONCISE DIAGNOSIS — `seon.instrument-test`'s bounded
  ;; headline regression measures it at under 64 estimated tokens — so the
  ;; paths it names are the first few a reader can act on. The complete list
  ;; is data on the value, never omitted.
  4)

(defn- problem-path
  "One Malli problem's path INTO THE ARGUMENT, as ordinary data.

  Input explanations begin with the positional argument index, which the
  surrounding args vector already represents. For a missing required key
  this path IS the key: naming it is the difference between \"missing
  required key\" and a refusal a reader can act on."
  [kind problem]
  (vec (cond-> (:in problem)
         (= :malli.core/invalid-input kind) next)))

(defn- offending-value
  "The exact Malli-reported key/value pair, nested only along its path."
  [caps kind problem]
  (let [path (:in problem)
        ;; Input explanations begin with the positional argument index. The
        ;; surrounding args vector already represents that position.
        path (if (= :malli.core/invalid-input kind) (next path) path)
        value (reduce (fn [child key] {key child})
                      (offending-leaf caps path (:value problem))
                      (reverse path))]
    (if (= :malli.core/invalid-input kind) [value] value)))

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

(defn- violation
  "One malli report as a flat, bounded, agent-readable value.
  `:args` can hold ANYTHING — a live Datahike connection is an ordinary
  argument at these boundaries — so it goes through the one codec, the
  same as every other error payload. With no caps to bound it, the args
  are OMITTED rather than printed: a description that can hang is worse
  than no description."
  ;; malli's report data names the OFFENDING SCHEMA and the OFFENDING
  ;; VALUE separately, and they are different keys per arm
  ;; (`core.cljc:2215,2218`): input is checked against the args vector,
  ;; output against the returned value. Explaining the whole `:=>`
  ;; schema instead — the first thing this reporter did — humanizes to
  ;; the useless "should be a valid function".
  [caps kind data]
  (let [fallback (minimal-violation kind data)]
    (try
      (if-let [inner (buried-error kind data)]
        inner
        (if (= :malli.core/invalid-arity kind)
          (let [function-symbol (:fn-name data)
                {status :seon.instrument.lookup/status
                 arglists ::arglists
                 cause :seon.instrument.lookup/cause}
                (diagnostic-arglists function-symbol)]
            (cond-> (error/diagnostic
                     {:seon.error/kind ::contract-violated
                      :seon.instrument/contract-violated (str function-symbol)
                      :seon.error/message (:seon.error/message fallback)
                      :seon.error/diagnostic-layer :instrumentation
                      :seon.error/diagnostic-operation function-symbol
                      :seon.error/diagnostic-member :arity
                      :seon.error/diagnostic-expected arglists
                      :seon.error/diagnostic-offending (:arity data)
                      :seon.error/diagnostic-cause (or cause kind)
                      :seon.error/diagnostic-evidence
                      (when arglists
                        {:seon.instrument.lookup/status status
                         ::arglists arglists})
                      :seon.error/data (:seon.error/data fallback)})
              arglists
              (-> (update :seon.error/message
                          str "; declared arglists: " (pr-str arglists))
                  (update :seon.error/data assoc ::arglists arglists))))
          (let [[offended value] (case kind
                                  :malli.core/invalid-output [(:output data) (:value data)]
                                  :malli.core/invalid-guard [(:guard data) [(:args data) (:value data)]]
                                  [(:input data) (:args data)])
          explanation (m/explain offended value)
          problems (:errors explanation)
          problem-count (count problems)
          bounded-caps (when caps (evidence-caps caps))
          first-problem (first problems)
          problem-message (or (some-> first-problem me/error-message)
                              "does not satisfy the declared schema")
          ;; EVERY PROBLEM, EACH WITH ITS PATH. Reporting one of N was the
          ;; absence-as-health shape one level up: a two-problem refusal
          ;; showed a single "missing required key" and an offending value
          ;; naming a DIFFERENT key than the one the reader had to supply.
          ;; The paths come first because for a missing required key the
          ;; path is the key.
          problem-paths
          (into [] (comp (map #(problem-path kind %)) (remove empty?))
                problems)
          problem-rows
          (mapv (fn [problem]
                  (cond-> {:seon.instrument.problem/message
                           (or (me/error-message problem)
                               "does not satisfy the declared schema")}
                    (seq (problem-path kind problem))
                    (assoc :seon.instrument.problem/path
                           (problem-path kind problem))))
                problems)
          representative-problem
          (when (and bounded-caps (seq problem-rows))
            (admitted-value bounded-caps problem-rows))
          schema-form (m/form offended)
          expected (if (and (= :malli.core/invalid-input kind)
                            (= :cat (first schema-form))
                            (= 2 (count schema-form)))
                     (second schema-form)
                     schema-form)
          expected-value (when bounded-caps
                           (admitted-value bounded-caps expected))
          offending (when (and bounded-caps first-problem)
                      (offending-value bounded-caps kind first-problem))
          function-symbol (:fn-name data)
          caller (caller-frame)
          arm (case kind :malli.core/invalid-output :output
                         :malli.core/invalid-guard :guard :input)]
      (error/diagnostic
       {:seon.error/kind ::contract-violated
        :seon.instrument/contract-violated (str function-symbol)
        ;; Store semantic evidence once. The terminal render path owns the
        ;; only presentation fit; embedding printed schemas, problem trees,
        ;; and arguments here made it print a print and repeat one payload.
        :seon.error/message
        (str function-symbol " violated its contract ("
             (name kind) "): " problem-message
             (when first-problem
               (str (when (= :malli.core/invalid-input kind)
                      (str "; argument " (first (:in first-problem)) " (0-based)"))
                    "; schema path " (pr-str (:path first-problem))
                    "; expected " (m/type (:schema first-problem))
                    ", got " (if (nil? (:value first-problem)) "nil"
                                 (.getSimpleName (class (:value first-problem))))))
             (when (seq problem-paths)
               ;; THE HEADLINE IS BOUNDED LIKE EVERY OTHER RENDERED
               ;; VALUE, and the omission is COUNTED rather than silent.
               ;; A 200-problem violation names the first few paths a
               ;; reader can act on; the complete list rides
               ;; `:seon.instrument/problem-paths` and the evidence.
               (let [shown (vec (take headline-problem-paths problem-paths))
                     remaining (- (count problem-paths) (count shown))]
                 (str " at " (pr-str shown)
                      (when (pos? remaining)
                        (str " and " remaining " more"))))))
        :seon.error/diagnostic-layer :instrumentation
        :seon.error/diagnostic-operation function-symbol
        :seon.error/diagnostic-member
        (if (= :malli.core/invalid-output kind) :return :arguments)
        :seon.error/diagnostic-expected expected-value
        :seon.error/diagnostic-offending offending
        :seon.error/diagnostic-cause kind
        :seon.error/diagnostic-evidence
        (when representative-problem
          (cond-> {:seon.instrument/problem-count problem-count
                   :seon.instrument/problems representative-problem}
            caller (assoc :seon.instrument/caller caller)))
        :seon.error/data
        (cond-> {::malli kind
                 ::arm arm
                 ::problem-count problem-count}
          (seq problem-paths) (assoc ::problem-paths problem-paths)
          caller (assoc ::caller caller)
          function-symbol (assoc ::fn (str function-symbol))
)}))))
      (catch Throwable _
        fallback))))

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
   projection [::wrapper function-symbol original]
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

(defn- arm-var!
  [candidate authored bootstrap caps]
  (alter-var-root
   candidate
   (fn [current]
     (if (identical? candidate (::var (meta current)))
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
           {::mi/original original ::var candidate}))))))

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
  "Arm previously unwrapped loaded Vars; existing wrappers remain identical.

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
    (let [bounded-caps (evidence-caps (or caps contract-evidence-caps))]
      (error/diagnostic
       {:seon.error/kind ::invalid-mode
        :seon.error/message
        "Instrumentation requires :panic or :record core-error mode."
        :seon.error/diagnostic-layer :instrumentation
        :seon.error/diagnostic-operation 'seon.instrument/apply!
        :seon.error/diagnostic-member :seon.config/on-core-error
        :seon.error/diagnostic-expected [:enum :panic :record]
        :seon.error/diagnostic-offending
        (if (nil? mode) ::nil (admitted-value bounded-caps mode))
        :seon.error/diagnostic-cause ::invalid-mode
        :seon.error/diagnostic-evidence
        {:seon.instrument/accepted-modes [:panic :record]}}))

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
              pending (remove (fn [[candidate _]]
                                (identical? candidate (::var (meta @candidate))))
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
