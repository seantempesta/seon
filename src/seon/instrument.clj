(ns seon.instrument
  "JVM-owned host wrappers with contracts compiled in the caller's projection.

  A loaded Var has one wrapper for its contract and referenced declarations.
  Repeated arming preserves unchanged wrappers; teardown cannot remove them. Changed roots
  or contract declarations are armed on the next apply!.
  Compiled validators belong to the immutable projection that defines them.
  Host calls without cluster custody use the supplied program captured at
  arming; they never consult Malli's global registry."
  {:seon/tasks #{"f985bfc97116" "96ac997dcf2f"}}
  (:require [seon.error.refusal :as refusal]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [malli.util :as mu]
            [clojure.test.check.generators :as gen]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.batch]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.id]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as kernel]))

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
  ;; Reads each namespace's own mapping table (`Namespace.getMappings`); an
  ;; interned Var is one whose `.ns` is that namespace, which is the filter
  ;; `ns-interns` applies to a map it copies first
  ;; (`reference-code/clojure/src/clj/clojure/core.clj:4237`). A wrapper is
  ;; installed on the root, so the root is read, never a thread binding.
  ;; The copy cost 15 ms and 78 MB per call over 485k mappings (2026-09-24).
  (persistent!
   (reduce (fn [found ^clojure.lang.Namespace namespace-object]
             (reduce-kv (fn [found _ candidate]
                          (if (and (instance? clojure.lang.Var candidate)
                                   (identical? namespace-object (.ns ^clojure.lang.Var candidate))
                                   (.hasRoot ^clojure.lang.Var candidate)
                                   (identical? candidate
                                               (::var (meta (.getRawRoot ^clojure.lang.Var candidate)))))
                            (conj! found candidate)
                            found))
                        found (.getMappings namespace-object)))
           (transient #{}) (all-ns))))

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

(def ^:private preparing-wrapper-class-prefix
  ;; The prepared call is a closure `call-preparation/preparing` returns and
  ;; `compiled-wrapper` installs around the contract, so its frame sits
  ;; between every prepared call's caller and the refusal. Every refusal of a
  ;; contract declaring a supplied default named it (`call_preparation.clj:1033`,
  ;; measured on `seon.problems/open-faults` 2026-09-27). The class prefix is
  ;; derived from the Var, so a rename moves it; the rest of call-preparation
  ;; stays a possible caller.
  (let [{namespace-object :ns var-name :name} (meta #'call-preparation/preparing)]
    (str (clojure.lang.Compiler/munge (str (ns-name namespace-object)))
         "$" (clojure.lang.Compiler/munge (str var-name)) "$")))

(defn- caller-frame
  "The caller of the refused call: its function and source location.

  The first stack frame that is neither the contract machinery (this
  namespace, the prepared-call closure, Malli's wrapper) nor the host. Named
  as `ns/fn (file:line)`; an anonymous function or `eval` frame is named by
  its namespace alone. A frame in SCI's interpreter means the call came from
  interpreted code, whose location is SCI's own call stack, not a JVM frame:
  that is nil here, and the evaluation boundary reports SCI's stack."
  {:malli/schema [:=> [:cat] [:or :nil :string]]}
  []
  (let [compiler-named? (fn [^String frame-fn prefix]
                          ;; `fn--123` and `eval123`: names the compiler made up.
                          (and (.startsWith frame-fn prefix)
                               (< (count prefix) (count frame-fn))
                               (every? #(Character/isDigit ^char %)
                                       (subs frame-fn (count prefix)))))]
    ;; walked lazily from this frame outward, stopping at the first answer
    (refusal/first-frame-answer
     (fn [[class-name _ file line]]
       (let [class-name (str class-name)
             [^String frame-ns ^String frame-fn]
             (.split (clojure.lang.Compiler/demunge class-name) "/")]
         (cond
           (or (= frame-ns "seon.instrument")
               (.startsWith class-name ^String preparing-wrapper-class-prefix)
               (some #(.startsWith frame-ns ^String %)
                     non-caller-namespace-prefixes))
           nil

           (.startsWith frame-ns "sci.")
           false

           :else
           (str (if (and frame-fn
                         (not (compiler-named? frame-fn "fn--"))
                         (not (compiler-named? frame-fn "eval")))
                  (str frame-ns "/" frame-fn)
                  frame-ns)
                " (" file ":" line ")")))))))

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
  "The loaded Var owns host arglists; the program entity describes SCI-only code."
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

      ;; Only where a string id is expected is a keyword-headed pair read as
      ;; a lookup ref: `[:=> [:cat :int]]` returned where a Malli form was
      ;; expected was described as "a lookup-ref vector" (2026-09-27).
      (and lookup-ref? expects-string?)
      (assoc description
             :seon.error/actual-description "a lookup-ref vector"
             :seon.error/fix
             (str (if (string? (second value))
                    "Pass the string id (the second element of the lookup-ref vector) at "
                    "Pass a string id at ")
                  (pr-str (:seon.error/path description)) "."))

      :else description)))

(defn- schema-name
  "The registry name of the deepest named schema on `schema-path` inside
  `offended`, or nil.

  Malli's explanation dereferences a named schema before it reports the
  problem, so the problem's own `:schema` is the definition (a `[:fn ...]`
  predicate), never the name the contract was written with.
  `malli.util/get-in` (`reference-code/malli/src/malli/util.cljc:342`) walks
  the same child keys `m/explain` reports as `:path`, and a named child's form
  is its registry keyword."
  {:malli/schema [:=> [:cat :seon.instrument/compiled-schema
                       [:vector :seon.schema/value]]
                  [:or :nil :qualified-keyword]]}
  [offended schema-path]
  (some (fn [depth]
          (let [form (some-> (mu/get-in offended (subvec schema-path 0 depth)) m/form)]
            (when (qualified-keyword? form) form)))
        (range (count schema-path) -1 -1)))

(defn- attributed-problem
  "Name the expectation by the contract's own schema name and give the fix
  its subject: the caller for an argument, the function's definition for its
  return (`location`, `file:line`, or the function itself when it has none).

  Malli's declared `:error/message` is both the expectation and, for a
  predicate, the fix (`seon.error/explain-problem`), so the sentence read
  \"expected must be X ... Fix: must be X\"; a return value's fix read
  \"Supply a vector at []\", addressed to nobody."
  {:malli/schema [:=> [:cat :seon.error/problem-description
                       [:or :nil :qualified-keyword]
                       [:enum :input :output :guard]
                       :qualified-symbol [:or :nil :string] :boolean]
                  :seon.error/problem-description]}
  [description named arm function-symbol location keyed?]
  (let [{:seon.error/keys [expected-description fix argument path]} description
        expectation (if (and named (not keyed?))
                      (str named " (" expected-description ")")
                      expected-description)
        at (when (seq path) (str " at " (pr-str path)))]
    (cond-> (assoc description :seon.error/expected-description expectation)
      (and named (not keyed?)) (assoc :seon.error/expected named)
      (and (= :input arm) (= fix expected-description))
      (assoc :seon.error/fix (str "Pass " (or named expected-description)
                                  " as " argument at "."))
      ;; The sentence already states the expectation and the path; a located
      ;; fix names only where to change it, which keeps the headline inside
      ;; its token bound with the caller named (2026-09-27).
      (= :output arm)
      (assoc :seon.error/fix (if location
                               (str "Change " location ".")
                               (str "Change " function-symbol " to return "
                                    (or named expected-description) at "."))))))

(defn- definition-location
  "Where the loaded function is defined, as `file:line`, or nil."
  {:malli/schema [:=> [:cat :qualified-symbol] [:or :nil :string]]}
  [function-symbol]
  (when (find-ns (symbol (namespace function-symbol)))
    (let [{:keys [file line]} (some-> (find-var function-symbol) meta)]
      (when (and (string? file) line)
        (str file ":" line)))))

(defn- declared-keys
  "The keys a map branch declares, through `:and` and named references."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:set :any]]}
  [schema]
  (let [resolved (m/deref-all schema)]
    (case (m/type resolved)
      :map (set (m/explicit-keys resolved))
      :and (into #{} (mapcat declared-keys) (m/children resolved))
      #{})))

(defn- closest-alternatives
  "Each union's problems narrowed to its closest alternative, with how many
  other branches were dropped. Closest: the branch declaring the most of the
  value's own keys (a map carrying `:seon.render.profile/*` keys is refused as
  a profile, never as the error base it shares fewer keys with), then one the
  value's type matched (every problem lies deeper than the union's own value,
  e.g. one missing key of a map branch) before one it did not, then the
  fewest problems, then the first. Malli explains an `:or` with every
  branch's errors under the union's path plus the branch index; a refusal
  that led with the first branch said 'expected nil, got a map' for a map
  that failed a map branch by one key, and carried every branch's problems
  (365 for render-call)."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] :seon.schema/value [:sequential :map]]
                  [:tuple [:vector :map] nat-int?]]}
  [schema value problems]
  (reduce (fn [[problems others] {path :path at :in union :schema}]
            (if-not (= :or (m/type union))
              [problems others]
              (let [n (count path)
                    branch (fn [problem]
                             (let [p (vec (:path problem))]
                               (when (and (> (count p) n) (= path (subvec p 0 n)) (int? (nth p n)))
                                 (nth p n))))
                    {inside true outside false} (group-by #(some? (branch %)) problems)
                    branches (group-by branch inside)
                    held (get-in value at)
                    shared (fn [i] (if (map? held)
                                     (count (filter (declared-keys (nth (m/children union) i)) (keys held)))
                                     0))]
                (if (< (count branches) 2)
                  [problems others]
                  (let [mismatched? (fn [branch-problems]
                                      (if (some #(<= (count (:in %)) (count at)) branch-problems) 1 0))
                        [_ closest] (first (sort-by (fn [[i ps]] [(- (shared i)) (mismatched? ps) (count ps) i])
                                                    branches))]
                    [(into (vec outside) closest) (+ others (dec (count branches)))])))))
          [(vec problems) 0]
          (mu/subschemas schema)))

(defn- violation
  "Retain the actual offending values with every world in them referenced
  (`seon.error.refusal/references` under the arming projection): a database,
  connection, projection, environment or SCI context argument is its
  identity, never a copy. The error render pair owns presentation."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :map]  :seon.error/base]}
  [projection kind data]
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
           ;; an arity refusal has no explained schema (`offended` is nil) and no problems
           [explained-problems other-alternatives]
           (if-not explanation
             [[] 0]
           (closest-alternatives
            offended
            value
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
                         (:errors explanation))))))
           arm (case kind :malli.core/invalid-output :output
                          :malli.core/invalid-guard :guard :input)
           supplied-entries (when (= :input arm) (supplied-entry-problems function-symbol))
           ;; an output's fix names where the returning function is defined
           location (when (= :output arm) (definition-location function-symbol))
           problems
           (if arity?
             [{:seon.error/argument "argument count"
               :seon.error/path []
               :seon.error/expected arglists
               :seon.error/expected-description
               (str "a declared argument count ("
                    (->> (:arities data)
                         ;; One arity reports its bounds; several report
                         ;; their counts and `:varargs` (malli core.cljc:2301).
                         (map (fn [declared]
                                (if (map? declared)
                                  (let [{low :min high :max} declared]
                                    (cond (= low high) (str low)
                                          high (str low "-" high)
                                          :else (str low " or more")))
                                  (if (= :varargs declared) "variadic" (str declared)))))
                         sort
                         (interpose ", ")
                         (apply str))
                    ")")
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
                      ;; `_database` is the binding's unused-marker, not its name.
                      bound-name (when (symbol? binding)
                                   (let [written (name binding)
                                         unmarked (if (.startsWith written "_") (subs written 1) written)]
                                     (when-not (= "" unmarked) unmarked)))
                      argument (case arm
                                 :output "its result"
                                 :guard "arguments and return value"
                                 (str "argument " position
                                      (when-let [argument-name (or (some-> label name) bound-name)]
                                        (str " `" argument-name "`"))))
                      supplied? (and (= 2 (count (:in problem)))
                                     (contains? supplied-entries (vec (:in problem))))]
                  (cond-> (assoc
                   (attributed-problem
                    (actionable-problem
                     (error/explain-problem
                      {:seon.error/problem problem
                       :seon.error/path (problem-path kind problem)
                       :seon.error/parent
                       (when (and (= :input arm) (seq (:in problem)))
                         (get-in (vec arguments) (pop (vec (:in problem)))))
                       :seon.error/argument argument})
                     problem
                     supplied?)
                    (schema-name offended (vec (:path problem)))
                    arm function-symbol location
                    (or supplied? (= :malli.core/missing-key (:type problem))))
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
           caller (caller-frame)
           ;; A refused output that is itself an error keeps that error as the
           ;; cause: the contract mismatch is the defect to fix, the original
           ;; message is what went wrong (refusal path only).
           cause (when (and (= :output arm) (map? value) (string? (:seon.error/message value)))
                   (cond-> {:seon.error/throwable-class "seon.error/base"
                            :seon.error/message (:seon.error/message value)}
                     (string? (:seon.error/fix value))
                     (update :seon.error/message str " Fix: " (:seon.error/fix value))))]
       (seon.error.refusal/references projection
       (merge {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.instrument/invocation
         :seon.error/operation function-symbol
         :seon.error/message (str (error/problem-sentence
               function-symbol first-problem nil
               (error/scalar-text (:seon.error/offending first-problem)))
              (when (qualified-keyword? expected)
                (str " Contract: " expected "."))
              (when cause (str " Its result is itself an error, kept as the cause: "
                               (:seon.error/message cause)))
              (when caller (str " Called from " caller ".")))
         :seon.error/expected expected
         :seon.error/offending offending
         :seon.error/member (case arm :output :seon.fn.arity/output :guard :seon.fn.arity/guard
                                  (if arity? :seon.instrument/arity :seon.fn.arity/input))}
        (cond-> {::malli kind ::arm arm ::fn function-symbol
                  ::problem-count (count problems)
                  :seon.error/problems problems}
           arity? (assoc ::arity (:arity data))
           cause (assoc :seon.error/chain [cause])
           arglists (assoc ::arglists arglists)
           (pos? other-alternatives) (assoc ::other-alternatives other-alternatives)
           (seq paths) (assoc ::problem-paths paths)
           caller (assoc ::caller caller))
          (when arity?
            (select-keys lookup [:seon.instrument.lookup/status
                                 :seon.instrument.lookup/cause]))))))

;;; ---------------------------------------------------------------------------
;;; Interpreted function contracts
;;; ---------------------------------------------------------------------------

(def ^:private interpreted-original ::interpreted-original)

(defn- original-interpreted
  {:malli/schema [:=> [:cat :seon.instrument/callable] :seon.instrument/callable]}
  [f]
  (or (some-> f meta interpreted-original) f))

(declare compiled-wrapper registration-error ^:dynamic *compiling-contract*)

(defn wrap-interpreted
  "Apply one committed agent function contract.

  It uses the host boundary's per-arity enforcement: every refusal throws,
  and the evaluation boundary decides what it records."
  {:malli/schema
   [:function
    [:=> [:cat :qualified-symbol :string :map [:or :seon.sci.admit/caps :seon.config/error :seon.error/base] :seon.instrument/callable]
     :seon.instrument/callable]
    [:=> [:cat :qualified-symbol :string :map [:or :seon.sci.admit/caps :seon.config/error :seon.error/base] :seon.instrument/callable
          [:map [:seon.config.error/max-evidence-bytes {:optional true}
            :seon.config.error/max-evidence-bytes]
           [:seon.program/definition-digest {:optional true} :seon.program/definition-digest]
           [:seon.profile/context {:optional true} :seon.profile/context]
           [:seon.call-preparation/snapshot {:optional true} :seon.call-preparation/snapshot]]]
     :seon.instrument/callable]]}
  ([function-symbol spec-edn projection caps f]
   (wrap-interpreted function-symbol spec-edn projection caps f {}))
  ([function-symbol spec-edn projection caps f arm-request]
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
        digest (:seon.program/definition-digest arm-request)
        arm-request (cond-> (dissoc arm-request :seon.program/definition-digest :seon.profile/context)
                      ;; the interpreted definition's digest, which its refusals name
                      digest (assoc ::digest digest))]
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
        (throw (ex-info (:seon.error/message failure)
                        (assoc failure :seon.error/type :seon.instrument/registration-error)))))
      (let [wrapped (binding [*compiling-contract* true]
                      (compiled-wrapper projection function-symbol
                                        (edn/read-string spec-edn) original caps
                                        (assoc arm-request
                                               :seon.sci.admit/caps caps
                                               :seon.config.error/max-evidence-bytes
                                               (or (:seon.config.error/max-evidence-bytes arm-request)
                                                   (:seon.config.error/max-evidence-bytes
                                                    config/defaults)))))]
        ;; The compiled wrapper and its timing cell belong to this
        ;; installation alone; the projection never retains the callable.
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

(defn- problem-location
  "Where in the checked value the first problem lies, as the declared error
  location (`:seon.error/location`): one ordered segment per key of `path`,
  each key held as its scalar. The location enters the fault's signature, so
  two paths are two problems. A path holding a key that is not a scalar has
  no stored location (length 0)."
  {:malli/schema [:=> [:cat [:maybe :seon.render.data/path]] :seon.error.location/entity]}
  [path]
  (let [scalar? #(or (keyword? %) (symbol? %) (string? %) (int? %) (boolean? %))]
    (if (and (seq path) (every? scalar? path))
      {:seon.error.location/length (count path)
       :seon.error.location/segments
       (set (map-indexed (fn [ordinal k]
                           (let [printed (seon.id/printed k)]
                             {:seon.error.location.segment/ordinal ordinal
                              :seon.error.location.segment/key {:seon.error.key/projection printed
                                                                :seon.error.key/capped? false
                                                                :seon.error.key/bound-bytes 256
                                                                :seon.error.key/scalar k}}))
                         path))}
      {:seon.error.location/length 0})))

(defn- boundary-refusal
  "Construct boundary evidence independently of the body's output contract.

  `:seon.error/expected-shape` is the identity of the arm that refused as the
  producer declared it: this function, the ordinal of the arity its contract
  declares (`::arity-ordinal`, fixed when the wrapper was armed) and the
  check. It is never re-derived from the compiled schema the value failed: a
  compiled predicate is a callable, not a name."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :map [:vector :map]] :seon.instrument/refusal-result]}
  [projection kind data arities]
  (let [function (:fn-name data)
        ;; WHO PRODUCED THE VALUE, from this call's own stack (failure path
        ;; only): the compiled caller and each definition's digest from its
        ;; armed Var's profile cell, the version that ran. An interpreted
        ;; caller is SCI's to name (`seon.error.refusal/producer`).
        caller (refusal/calling-definition)
        caller-digest (some-> caller refusal/var-digest)
        digest (or (::digest data) (refusal/var-digest function))
        own? (#{:malli.core/invalid-output :malli.core/invalid-guard} kind)
        [producer producer-digest] (if own? [function digest] [caller caller-digest])
        base (cond-> (merge (violation projection kind (assoc data ::boundary? true))
                            {:seon.error/at (java.util.Date.)
                             :seon.error/layer :seon.instrument/invocation
                             :seon.error/operation function
                             :seon.instrument/fn function
                             :seon.instrument/arity (count (:args data))
                             :seon.render/operations [(list 'doc function)]})
               digest (assoc :seon.instrument/digest digest)
               caller (assoc :seon.error/caller caller)
               caller-digest (assoc :seon.error/caller-digest caller-digest)
               producer (assoc :seon.error/producer producer)
               producer-digest (assoc :seon.error/producer-digest producer-digest))]
    (if (= :malli.core/invalid-arity kind)
      (assoc base :seon.instrument/declared-arity-count (count arities)
             :seon.instrument/declared-arities
             (set (map-indexed
                   (fn [ordinal info]
                     (cond-> {:seon.instrument.arity/ordinal ordinal
                              :seon.instrument.arity/min (:min info)}
                       (:max info) (assoc :seon.instrument.arity/max (:max info)))) arities)))
      (let [check (case kind :malli.core/invalid-input :input
                            :malli.core/invalid-output :output :guard)]
        ;; The problems (path, expected form, offending value) are
        ;; `violation`'s `:seon.error/problems`, from its one `m/explain`.
        ;; A second explanation here copied them as pre-printed projection
        ;; strings nested seven levels deep: one missing key printed as
        ;; 34,518 bytes over MCP (2026-09-26). Values stay values; the
        ;; floor printer bounds them at the edge.
        (cond-> (assoc base :seon.instrument/check check
               :seon.error/expected-shape (seon.id/digest 64 [(:fn-name data) (::arity-ordinal data) check])
               :seon.error/location (problem-location (:seon.error/path (first (:seon.error/problems base)))))
          ;; the named schema the offending value failed, joinable to its entity
          (qualified-keyword? (:seon.error/expected (first (:seon.error/problems base))))
          (assoc :seon.error/expected-key (:seon.error/expected (first (:seon.error/problems base)))))))))

(defn calling-environment
  "The world of the evaluation calling now: its context's environment, naming
  that context for suppliers that act on it, or nil when none runs.

  An evaluation of the one REPL path binds its world (`seon.batch/*world*`):
  then the environment is that world's, scoped with its agent and SCI context, and nothing else is read. World
  members themselves are supplied from the world by
  `seon.call-preparation/supply`. Until every entrant runs through the one
  path, an evaluation without a bound world is answered as before, below
  (deleted with the last converted entrant).

  Its database is the custody in force at the call (`seon.db/db`: the
  evaluation's scoped read basis, else the custody connection's current
  value), so a call inside `seon.db/call-with-custody` — a fixture branch, a
  deliberate write elsewhere — is supplied that custody's database, never the
  value its world was scoped with. Without custody the world stands as it is.

  # Costs
  Unarmed, measured at default 2026-09-27: ~0.9 us without custody, ~1.0 us
  with an evaluation's read basis bound, ~2.3 us deriving a connection's
  current value (a host test body)."
  {:malli/schema [:=> [:cat] [:or :nil :seon.env/environment]]}
  []
  ;; Called on every prepared call, so it reads the arm and scopes unarmed.
  (if-let [{environment :seon.env/environment connection :seon.db/connection
            agent-id :seon.agent/id ctx :seon.sci.eval/ctx} seon.batch/*world*]
    (when environment
      ;; a world holds no database: the custody in force, else the world's
      ;; connection, read now
      (let [database (cond (or db/*read-database* db/*conn*) ((mi/-f->original db/db))
                           connection ((mi/-f->original db/db) connection))]
        ((mi/-f->original env/scope)
         environment
         (cond-> {}
           ctx (assoc :my.program/executing-ctx ctx)
           agent-id (assoc :seon.agent/id agent-id)
           ((mi/-f->original db/database-value?) database) (assoc :seon.db/db database)))))
  (when-let [ctx ((mi/-f->original kernel/world-ctx))]
    (when-let [environment ((mi/-f->original env/of) ctx)]
      (let [database (when (or db/*read-database* db/*conn*)
                       ((mi/-f->original db/db)))]
        ((mi/-f->original env/scope)
         environment
         (cond-> {:my.program/executing-ctx ctx}
           ((mi/-f->original db/database-value?) database) (assoc :seon.db/db database))))))))

(defn- compiled-wrapper
  {:seon/tasks #{"a57e8b27de67"}
   :malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :seon.schema/value :seon.instrument/callable :seon.sci.admit/caps [:? [:or :nil :map]]] :seon.instrument/callable]}
  [projection function-symbol authored original _caps & [policy]]
  (let [contract (get (:seon.schema.projection/function-contracts projection)
                      function-symbol authored)
        retained (mr/schema (:seon.schema.projection/registry projection) function-symbol)
        ;; The one armed compile (contracts-out §1): normalize, bind, compile in
        ;; the registry composed with Malli's var registry.
        options {:registry (mr/composite-registry
                            (:seon.schema.projection/registry projection)
                            (mr/var-registry))}
        compiled (or retained
                     (:seon.schema/compiled
                      ((mi/-f->original schema/contract-product)
                       projection {:seon.schema/form contract :seon.schema/mode :armed})))
        arity-schemas (vec (m/-function-schema-arities compiled))
        arities (mapv m/-function-info arity-schemas)
        ;; Malli reports the arity schema that refused (`:schema`), one of
        ;; these objects (malli core.cljc:2229-2240, :2305).
        arity-ordinal (fn [data]
                        (first (keep-indexed #(when (identical? %2 (:schema data)) %1)
                                             arity-schemas)))
        ;; Read on the refusal path only, so an absent refusal schema is a
        ;; minimal refusal at the call, not an arming failure.
        refusal? (delay ((mi/-f->original schema/projection-cache-value)
                         projection ::refusal-validator
                         #((mi/-f->original schema/projection-validator)
                           projection :seon.instrument/refusal-result)))
        ;; The base from Malli's report alone: valid by construction, a core
        ;; fault, the report key naming who is wrong, the failure in its chain.
        ;; Each report carries its arm's schema and checked values (arity:
        ;; the function schema and the count), so expected and offending
        ;; need no computation that could fail again.
        minimal (fn [kind data failure]
                  (let [arm (case kind
                              :malli.core/invalid-schema-at-call (:arm data)
                              :malli.core/invalid-arity :arity
                              :malli.core/invalid-output :output
                              :malli.core/invalid-guard :guard
                              :input)
                        offending (case arm
                                    :arity (find data :arity)
                                    :input (find data :args)
                                    :output (find data :value)
                                    (clojure.lang.MapEntry/create
                                     :guard [(:args data) (:value data)]))]
                    [:seon.error/base
                     (seon.error.refusal/diagnostic
                      (cond-> {:seon.error/at (java.util.Date.)
                               :seon.error/layer :seon.instrument/invocation
                               :seon.error/operation function-symbol
                               :seon.error/member kind
                               :seon.error/expected (m/form (if (= :arity arm) (:schema data)
                                                                (get data arm (:schema data))))
                               :seon.error/message (str function-symbol " " kind
                                                        ": the refusal could not be built: "
                                                        (ex-message failure))
                               :seon.error/throwable failure}
                        ;; NOT under the arming projection: this path runs because building
                        ;; the refusal against it failed, so its registry is the suspect (a
                        ;; masked refusal-result threw ::invalid-schema from identity-only
                        ;; descriptors, and the refusal had no members). `diagnostic`
                        ;; references the whole observation under the handed projection.
                        offending (assoc :seon.error/offending (val offending))))]))
        ;; A BOUND FIRING IS ITS OWN REPORT: explaining re-checks the value, so
        ;; an evaluation deadline can fire mid-refusal (or mid-validation,
        ;; which Malli reports as ::invalid-schema-at-call). SCI's interrupt
        ;; leaves unchanged so the bound reports what never arrived.
        reraise-bound! (fn [failure]
                         (when (kernel/interrupted? failure) (throw failure)))
        refusal (fn [kind data]
                  (if (= :malli.core/invalid-schema-at-call kind)
                    (do (reraise-bound! (:exception data))
                        (minimal kind data (:exception data)))
                    (try
                      (let [value (boundary-refusal projection kind
                                                    (cond-> (assoc data :fn-name function-symbol
                                                                   ::arity-ordinal (arity-ordinal data))
                                                      (::digest policy) (assoc ::digest (::digest policy)))
                                                    arities)]
                        (if (@refusal? value)
                          [(if (= :malli.core/invalid-arity kind)
                             :seon.instrument/arity-error :seon.instrument/contract-error) value]
                          (minimal kind data (ex-info "Instrumentation constructed an invalid refusal."
                                                 {:seon.instrument/problems
                                                  (mapv #(select-keys % [:path :in])
                                                        (:errors (m/explain :seon.instrument/refusal-result
                                                                            value options)))}))))
                      (catch Throwable failure
                        (reraise-bound! failure)
                        (minimal kind data failure)))))
        wrapped
        (m/-instrument
         {:schema compiled :scope #{:input :output :guard}
          :report (fn [kind data]
                    (binding [*compiling-contract* true]
                      (let [[declared-schema value] (refusal kind data)]
                        (throw (ex-info (:seon.error/message value)
                                        (assoc value :seon.error/type declared-schema))))))}
         original options)
        current (:seon.call-preparation/snapshot policy)
        plan (when current
               ((mi/-f->original call-preparation/arm-plan) current projection function-symbol))]
    ;; Malli's instrumented function IS the wrapper: every refusal throws, in
    ;; both core-error modes, so no value outside the arity's declared output
    ;; can escape. Who is responsible, and whether it is stored, is decided by
    ;; the boundary that owns the evaluation's world, never here.
    ;; A contract declaring a supplied default composes the preparation step
    ;; outside it; every other contract gets none (`arm-plan` is nil).
    (if plan
      (call-preparation/preparing current plan calling-environment wrapped)
      wrapped)))

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

(defn- arm-var!
  {:malli/schema
   [:function
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.instrument/callable] :seon.instrument/callable]
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.instrument/callable [:or :nil :map]] :seon.instrument/callable]
    [:=> [:cat :seon.instrument/loaded-var :seon.schema/value :seon.schema/projection :seon.instrument/callable [:or :nil :map] [:or :nil :seon.program/definition-digest]] :seon.instrument/callable]]}
  ;; `boot-wrapper` is the wrapper arming compiled once for this Var; the
  ;; installation calls that same object, so a call never compiles.
  ([candidate authored projection boot-wrapper]
   (arm-var! candidate authored projection boot-wrapper nil nil))
  ([candidate authored projection boot-wrapper policy]
   (arm-var! candidate authored projection boot-wrapper policy nil))
  ([candidate authored projection boot-wrapper policy digest]
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
             definitions (contract-definitions projection contract)]
         (profile/with-cell cell
          (with-meta
           ;; One wrapper per adoption: a call never picks another projection,
           ;; so it never compiles. Other branches' differing entities interpret
           ;; in SCI.
           (fn [& arguments]
             (if *compiling-contract*
               (apply original arguments)
               (profile/timed (apply boot-wrapper arguments))))
           {:malli.instrument/original original
            ::cell cell
            ::policy policy
            :seon.instrument/var candidate
            :seon.instrument/authored authored
            :seon.instrument/contract contract
            :seon.instrument/definitions definitions}))))))))

(defn- collect-contracts!
  "Read declarations from the program loaded into this JVM, without Malli's registry.

  With `digests`, the program is the published one they name: only its
  loaded Vars, so a namespace a JVM-mode client evaluated meanwhile (the
  prepl is open while the boot arms) is neither armed nor able to refuse the
  boot. Without them, every loaded Var."
  {:malli/schema [:=> [:cat [:or :nil :seon.profile/definition-digests]]
                  [:map-of :seon.instrument/loaded-var :seon.schema/value]]}
  [digests]
  (into {}
        (keep (fn [candidate]
                (when-let [authored (and candidate (mi/-schema candidate))]
                  (when (and (bound? candidate)
                             (not (primitive-fn? @candidate)))
                    [candidate authored]))))
        (if digests
          (map (fn [function-symbol]
                 (when (find-ns (symbol (namespace function-symbol)))
                   (find-var function-symbol)))
               (keys digests))
          (mapcat (comp vals ns-interns) (all-ns)))))

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
  A missing changed-identities member collects the complete loaded program:
  the published program `:seon.profile/definition-digests` names when handed
  (boot), else every loaded Var.
  An identity without a retained contract is disarmed on adoption.
  Unrelated contracts are neither inspected nor re-armed.

  Host wrappers capture the supplied admission caps and evidence bound at arm
  time. Calls validate through the wrapper compiled here, against this
  adoption's projection; a call's own projection never re-selects it. Every
  refusal throws; recording is the boundary's."
  {:malli/schema
   [:=> [:cat :seon.instrument/request]
    [:or :seon.instrument/applied :seon.instrument/registration-error]]}
  [{caps :seon.sci.admit/caps
    max-evidence-bytes :seon.config.error/max-evidence-bytes
    supplied-projection :seon.schema/projection
    digests :seon.profile/definition-digests
    database :seon.db/db
    :as request}]
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
            ;; The adoption's supplied-default entities, read once: each
            ;; wrapper's plan is decided from them at arming.
            current (when database (call-preparation/snapshot database projection))
            _ (when (db/read-refusal? current)
                (throw (ex-info (:seon.error/message current) current)))
            policy (cond-> {:seon.sci.admit/caps caps
                            :seon.config.error/max-evidence-bytes
                            (or max-evidence-bytes
                                (:seon.config.error/max-evidence-bytes defaults))}
                     current (assoc :seon.call-preparation/snapshot current))
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
                        (collect-contracts! digests))
            pending (if changed
                      contracts
                      (remove (fn [[candidate authored]]
                                (current-wrapper? candidate authored projection @candidate policy))
                              contracts))]
        (doseq [candidate candidates
                :when (and (bound? candidate) (not (find contracts candidate)))]
          (alter-var-root candidate mi/-f->original))
        ;; Each Var's wrapper is compiled once and that object installed. A
        ;; contract that cannot compile leaves its Var on its previous root
        ;; and becomes a registration error; every other Var still arms.
        (let [refused
              (into []
                    (keep
                     (fn [[candidate authored]]
                       (let [function-symbol (var-symbol candidate)
                             built (try
                                     (binding [*compiling-contract* true]
                                       (compiled-wrapper projection function-symbol authored
                                                         (mi/-f->original @candidate) caps policy))
                                     (catch Throwable failure failure))]
                         (if (instance? Throwable built)
                           (let [data (registration-cause-data built)]
                             (registration-error function-symbol
                              {:seon.error/message (str "The loaded function contract " function-symbol
                                                        " cannot compile: " (ex-message built))
                               :seon.error/layer :instrumentation
                               :seon.error/operation 'seon.instrument/apply!
                               :seon.error/expected authored
                               :seon.error/offending (or (:schema data) (get-in data [:data :ref])
                                                         (get-in data [:data :schema]))
                               :seon.error/member function-symbol
                               :seon.error/throwable built}))
                           (do (when changed (alter-var-root candidate mi/-f->original))
                               (arm-var! candidate authored projection built policy
                                         (get digests function-symbol))
                               nil)))))
                    pending)]
          (if-let [first-refusal (first refused)]
            (cond-> first-refusal
              (next refused)
              (update :seon.error/message str " Also refused: "
                      (mapv :seon.instrument/fn (rest refused)) "."))
            {:seon.instrument/registered (count contracts)
             :seon.instrument/instrumented (count (instrumented))}))))))

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
          (doseq [[candidate authored policy wrapper]
                  (mapv (fn [[candidate authored policy]]
                          [candidate authored policy
                           (binding [*compiling-contract* true]
                             (compiled-wrapper projection (var-symbol candidate) authored
                                               (mi/-f->original @candidate)
                                               (:seon.sci.admit/caps policy) policy))])
                        pending)]
            (arm-var! candidate authored projection wrapper
                      ;; A replaced definition's digest is unknown here.
                      policy nil)))))
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
  [entities]
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
        entities))

(def loaded-var-generator
  "A real Var from this owner for the loaded-Var contract."
  (gen/return #'apply!))
