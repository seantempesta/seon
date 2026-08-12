(ns seon.instrument
  "Malli instrumentation, applied explicitly, measured before it was built.

  Every number below was measured on this machine and is reproduced in
  `research/error-handling-grounding-2026-07-27.md` §4. Nothing here is
  a preference dressed as a rule.

  THE SELECTION IS COMPUTED, and the computation is one sentence: every
  loaded public var carrying `:malli/schema`. There is no namespace
  prefix, no allow list, and no exclusion list — a name-based rule is
  the hand list the standing ruling bans. It excludes every hot inner
  function BY CONSTRUCTION, which is the part worth understanding:
  `admit`'s `project`/`project-node`/`project-map`/`take-node!` and
  `eval`'s `arm`/`diagnosis`/`failure-value` are all `defn-` with no
  schema, so \"public + schema\" already means \"a boundary\", and
  instrumentation lands on boundaries and nowhere else without anybody
  maintaining that fact.

  THE COST, measured: **+129 ns** on `(add2 1 2)` against
  `[:=> [:cat :int :int] :int]`, **+175 ns** on one closed four-key map
  argument holding a 32-element vector. Read it as a flat ~130-180 ns
  per instrumented call, dominated by `(vec args)` + `apply` + the
  validator walk — not a multiplier. On a per-turn or per-transaction
  boundary that is free. On a per-node walk it would be fatal, and the
  paragraph above is why it cannot land there.

  WHAT `:report` CANNOT DO, measured, because the docs read otherwise:
  malli's wrapper calls `report` FOR EFFECT and then runs the function
  anyway (`malli/core.cljc:3110-3131`). Probed: the reported call still
  executed and still threw its natural `ClassCastException`. So a
  non-throwing report mode is \"tell me, then let it break\" — never
  graceful degradation, and describing it as such would be a lie a
  reader would plan around.

  THE DIAL, therefore:

  - `:panic` (development) — INSTRUMENT, and the reporter THROWS. A
    contract violation is a bug in our own code and the first call is
    where it is cheapest to find. Fail loud is not fall down: the throw
    halts that CALL, not the tower. And when the caller is a flow proc,
    the throw is a Throwable escaping our code onto `::flow/error`,
    which is exactly the path `seon.error` already owns — so an
    instrumentation violation inside the run loop becomes a durable
    error fact with `:seon.error/kind ::contract-violated`, an
    explanation message, and a `problems` entry, through machinery
    nobody had to add here. That composition is the reason this
    namespace is small.
  - `:record` (production) — INSTRUMENT NOTHING, and remove whatever is
    instrumented. Judgment, flagged for the owner and reversible in one
    line: report mode cannot prevent the bad call (measured above), it
    taxes every public boundary ~150 ns forever, and the natural failure
    that follows a violation still becomes a fault through the wired
    error channel — so nothing is lost silently by staying out of the
    way. The alternative, if the owner wants contract violations named
    in production rather than diagnosed from their consequences, is to
    instrument with a reporter that commits an error fact instead of
    throwing.

  HOT RELOAD STRIPS INSTRUMENTATION SILENTLY, and this is the sharpest
  measured fact here. Re-evaluating a `defn` replaces the var's root,
  `alter-var-root`'s wrapper is gone, the `::original` meta is gone, and
  NOTHING warns: the var looks fine and is unprotected. The schema stays
  registered, so the registry now disagrees with reality. `malli.dev`'s
  watch does not save it either — the watch fires on
  `-register-function-schema!`, and a plain re-eval never touches that
  atom (probed: watch fired on re-collect, not on re-eval).

  THE DISCIPLINE, therefore, is one line: **re-run `apply!` after
  re-evaluating anything.** It is idempotent, it is fast, and it is the
  only reliable trigger. A future edit hook can call it for you; that is
  a note, not a thing this namespace does.

  `malli.dev/start!` is REJECTED as an entry point: it `alter-var-root`s
  `m/-fail!` globally, installs a pretty printer as the reporter, and
  writes clj-kondo config (`malli/dev.clj:13-23, 40-66`). We want a
  violation to become a durable fact, not a coloured box on stderr.

  `remove!` is EMERGENCY RECOVERY, not a dial and never the answer to a
  noisy report. A noisy report means the schema or the caller is wrong;
  the first one found this way was real (`loop.clj` passing a
  transaction argument map where the contract said vector — see
  `docs/seon/issues/archive/loop-open-transaction-violates-transact-schema.md`)."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.print :as print]
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
  "The vars carrying an instrumentation wrapper right now.
  Malli stamps the original fn under `::mi/original` when it wraps, so
  this is a fact about the running process rather than a count somebody
  remembered to keep. It is what makes `apply!` idempotence and the
  hot-reload strip both observable instead of assumed."
  {:malli/schema [:=> [:cat] [:set :any]]}
  []
  (into #{}
        (comp (mapcat ns-publics)
              (map val)
              (filter (fn [candidate]
                        (and (bound? candidate)
                             (some-> (deref candidate) meta ::mi/original)))))
        (all-ns)))

;;; ---------------------------------------------------------------------------
;;; The reporter
;;; ---------------------------------------------------------------------------

(defn- admitted-face
  [caps value]
  (let [admitted
        (admit/admit
         {:seon.sci.admit/value value
          :seon.sci.admit/interrupt-fn (constantly nil)
          :seon.sci.admit/caps caps
          ;; the reporter may not panic on the way to reporting a panic
          :seon.config/on-core-error :record})]
    {:seon.instrument/edn (:seon.cluster.eval/result-edn admitted)
     :seon.instrument/value (:seon.sci.admit/value admitted)
     :seon.instrument/value-edn
     (admit/canonical-edn (:seon.sci.admit/value admitted))
     :seon.instrument/text
     (print/emit-text
      (:seon.sci.admit/print-node admitted)
      {:seon.print/length
       (:seon.config.eval.result/max-collection caps)
       :seon.print/level (:seon.config.eval.result/max-depth caps)
       :seon.print/width 0
       :seon.print/table? false})}))

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

(defn- admitted-value
  [caps value]
  (:seon.instrument/value (admitted-face caps value)))

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
      (if arity? ::declared-arglists (or (:output data) (:input data)))
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
          (let [[offended value] (if (= :malli.core/invalid-output kind)
                             [(:output data) (:value data)]
                             [(:input data) (:args data)])
          explanation (m/explain offended value)
          problems (:errors explanation)
          problem-count (count problems)
          bounded-caps (when caps (evidence-caps caps))
          visible-explanation
          (when bounded-caps
            (assoc explanation
                   :errors
                   (into []
                         (take (:seon.config.eval.result/max-collection
                                bounded-caps))
                         problems)))
          all-problems
          (me/humanize
           (or visible-explanation explanation)
           {:unknown false
            :wrap #(select-keys % [:value :message])})
          problem-face (when bounded-caps
                         (admitted-face bounded-caps all-problems))
          first-problem (first problems)
          schema-form (m/form offended)
          expected (if (and (= :malli.core/invalid-input kind)
                            (= :cat (first schema-form))
                            (= 2 (count schema-form)))
                     (second schema-form)
                     schema-form)
          expected-face (when bounded-caps
                          (admitted-face bounded-caps expected))
          argument-face (when (and bounded-caps first-problem)
                          (admitted-face bounded-caps
                                         (offending-value bounded-caps
                                                          kind
                                                          first-problem)))]
      (cond->
       (error/diagnostic
        {:seon.error/kind ::contract-violated
         ;; A representative problem context goes through the ONE general
         ;; printer under the construction-time evidence caps. The complete
         ;; count remains the broken-system signal without retaining every
         ;; problem value.
         :seon.error/message
         (str (:fn-name data) " violated its contract ("
              (name kind) "): "
              (if caps
                (:seon.instrument/text problem-face)
                (pr-str all-problems)))
         :seon.error/diagnostic-layer :instrumentation
         :seon.error/diagnostic-operation (:fn-name data)
         :seon.error/diagnostic-member
         (if (= :malli.core/invalid-output kind) :return :arguments)
         :seon.error/diagnostic-expected
         (some-> expected-face :seon.instrument/value)
         :seon.error/diagnostic-offending
         (some-> argument-face :seon.instrument/value)
         :seon.error/diagnostic-cause kind
         :seon.error/diagnostic-evidence
         (when problem-face
           {:seon.instrument/problem-count problem-count
            :seon.instrument/problems
            (:seon.instrument/value problem-face)})
         :seon.error/data
         (cond-> {::malli kind
                  ::arm (if (= :malli.core/invalid-output kind) :output :input)
                  ::schema (if expected-face
                             (:seon.instrument/text expected-face)
                             (pr-str expected))
                  ::problem-count problem-count}
           (:fn-name data) (assoc ::fn (str (:fn-name data)))
           problem-face
           (assoc ::problems (:seon.instrument/edn problem-face)))})
        argument-face
       (update :seon.error/data assoc
               ::args (:seon.instrument/value-edn argument-face))))))
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
     (if (and (vector? value) (= :fn (first value)))
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
              :seon.schema/predicate predicate}))))
       value))
   contract))

(defn wrap-interpreted
  "Apply one committed agent function contract under the core-error dial."
  {:malli/schema
   [:=>
    [:cat :symbol :string :map :seon.config/on-core-error
     :seon.sci.admit/caps [:fn clojure.core/ifn?]]
    [:fn clojure.core/ifn?]]}
  [function-symbol spec-edn projection mode caps f]
  (let [original (original-interpreted f)]
    (case mode
      :panic
      (let [contract (->> (edn/read-string spec-edn)
                          (bind-contract-predicates projection))
            report (throwing-report caps)
            wrapped
            (m/-instrument
             {:schema contract
              :scope #{:input :output}
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

(defn- collect-contracts!
  "Register the same public Vars Malli collects, one Var at a time.

  Malli's bulk `clj-collect!` reduces over these Vars but loses the current Var
  when compilation throws (`malli.instrument/-collect!`). Keeping that value
  beside its own registration operation makes the authored contract and the
  exact offending Var inseparable from the diagnostic."
  [caps]
  (let [bounded-caps (evidence-caps (or caps contract-evidence-caps))]
    (into #{}
          (keep
           (fn [candidate-var]
             (when-let [authored-schema (mi/-schema candidate-var)]
               (try
                 (mi/-collect! candidate-var)
                 (catch Throwable failure
                   (let [function-symbol (var-symbol candidate-var)
                         failure-data (ex-data failure)
                         root-data (or (error/refusal failure) failure-data)
                         nested-schema
                         (or (:schema root-data)
                             (get-in root-data [:data :schema])
                             (get-in root-data [:data :ref]))
                         diagnostic
                         (error/diagnostic
                          {:seon.error/kind ::registration-failed
                           :seon.error/message
                           (str "Malli could not register the contract for "
                                function-symbol ".")
                           :seon.error/diagnostic-layer :instrumentation
                           :seon.error/diagnostic-operation
                           'malli.instrument/-collect!
                           :seon.error/diagnostic-member function-symbol
                           :seon.error/diagnostic-expected
                           (admitted-value bounded-caps authored-schema)
                           :seon.error/diagnostic-offending
                           (when nested-schema
                             (admitted-value bounded-caps nested-schema))
                           :seon.error/diagnostic-cause
                           (or (:type root-data)
                               (some-> failure class .getName))
                           :seon.error/diagnostic-evidence
                           (when root-data
                             (admitted-value bounded-caps root-data))
                           :seon.error/data
                           {::fn (str function-symbol)}})]
                     (throw
                      (ex-info (:seon.error/message diagnostic)
                               diagnostic failure)))))))
           (->> (all-ns)
                (mapcat ns-publics)
                (map val)
                (sort-by (comp str var-symbol)))))))

(defn apply!
  "Collect function schemas and instrument per the dial. IDEMPOTENT.
  `(mi/clj-collect! {:ns (all-ns)})` — the FUNCTION, not the macro,
  because the namespace set is a runtime value — then `mi/instrument!`
  with `:scope #{:input :output}`. `:guard` is dropped: no schema in the
  tree declares one and including it costs a validate call per
  invocation for nothing.

  On `:panic` the reporter throws (see the namespace docstring for why,
  and for what happens when the throw escapes a proc). On `:record`
  this instruments nothing and REMOVES any wrapper already installed, so
  moving the dial to production actually takes effect rather than
  leaving yesterday's wrappers in place.

  Returns `{:seon.instrument/registered n :seon.instrument/instrumented m}`
  so \"is instrumentation on right now\" is answerable. In `:panic` a
  count of ZERO is a bug — there are hundreds of schema'd public vars in
  this tree — so it says so on stderr rather than passing quietly.

  Call it again after re-evaluating anything: a re-`defn` silently
  strips the wrapper and no watch fires. Malli collection compiles against
  the caller's cluster-bound schema projection; instrumentation never loads,
  publishes, or replaces schema declarations."
  {:malli/schema [:=> [:cat :seon.instrument/request] :seon.instrument/applied]}
  [{mode :seon.config/on-core-error caps :seon.sci.admit/caps}]
  (let [registered (count (collect-contracts! caps))]
    (case mode
      :panic
      (do (mi/instrument! {:scope #{:input :output}
                           :report (throwing-report caps)})
          (let [count-now (count (instrumented))]
            (when (zero? count-now)
              (binding [*out* *err*]
                (println "seon.instrument: :panic instrumented ZERO vars —"
                         "that is a bug, not a quiet success")
                (flush)))
            {:seon.instrument/registered registered
             :seon.instrument/instrumented count-now}))

      :record
      (do (mi/unstrument!)
          {:seon.instrument/registered registered
           :seon.instrument/instrumented (count (instrumented))}))))

(defn remove!
  "Strip every instrumentation wrapper. EMERGENCY RECOVERY ONLY.
  Not a dial, and never the answer to a noisy report — a noisy report
  means the schema or the caller is wrong, and both are fixable at the
  source. Returns how many wrappers survive, which is zero unless
  something re-instrumented underneath."
  {:malli/schema [:=> [:cat] :seon.instrument/instrumented]}
  []
  (mi/unstrument!)
  (count (instrumented)))
