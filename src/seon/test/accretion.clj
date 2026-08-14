(ns seon.test.accretion
  "Green-to-install derivations shared by schema admission and runtime gating."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.effect :as effect]
            [seon.schema :as schema]
            [seon.sci.kernel :as kernel]))

(defn generatable?
  "True when Malli can construct a generator for `schema` with `options`."
  {:malli/schema
   [:function
    [:=> [:cat :any] :boolean]
    [:=> [:cat :any :map] :boolean]]}
  ([schema]
   (generatable? schema {}))
  ([schema options]
   (try
     (boolean (mg/generator schema options))
     (catch Throwable _ false))))

(defn schema-row
  "Accrete the derived generatability fact onto one canonical schema row."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [forms row]
  (let [registry (mr/composite-registry (m/default-schemas)
                                        (mr/fast-registry forms))]
    (assoc row :seon.schema/generatable?
           (try
             (generatable? (m/schema (:seon.schema/key row)
                                     {:registry registry}))
             (catch Throwable _ false)))))

(defn non-generatable-advisory
  "One teaching line for an admitted schema that cannot generate values."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [row]
  (when (and (:seon.schema/key row)
             (false? (:seon.schema/generatable? row)))
    (str "Schema " (:seon.schema/key row)
         " has no Malli generator; functions using it will skip auto-check.")))

(defn candidate-capabilities
  "Derive capabilities reached by a candidate's own indexed call edges."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.fn/fn]
    [:set :seon.fn/sym]]}
  [database row]
  (cond->
   (into #{}
         (mapcat
          (fn [call-ref]
            (effect/capabilities database
                                 (symbol (second call-ref)))))
         (:seon.fn/calls row))
    (:seon.effect/capability row)
    (conj (:seon.fn/sym row))))

(defn- observation
  [request output-schema arguments]
  (let [invocation
        (kernel/invoke
         {:seon.sci.eval/ctx (:seon.sci.eval/ctx request)
          :seon.db/db (:seon.db/db request)
          :seon.fn/sym (:seon.fn/sym request)
          :seon.sci.eval/args arguments
          :seon.sci.eval/time-limit-ms
          (:seon.sci.eval/time-limit-ms request)
          :seon.sci.admit/caps (:seon.sci.admit/caps request)
          :seon.config/on-core-error
          (:seon.config/on-core-error request)})
        returned (:seon.sci.admit/value invocation)
        actual (or (:seon.error/diagnostic-offending returned) returned)
        explanation (m/explain output-schema actual)]
    {:seon.test.accretion/arguments arguments
     :seon.test.accretion/actual actual
     :seon.test.accretion/expected (pr-str (m/form output-schema))
     :seon.test.accretion/explanation
     (when explanation (pr-str (me/humanize explanation)))
     :seon.test.accretion/pass? (nil? explanation)}))

(defn- arity-generators
  [function-schema options]
  (mapv
   (fn [index arity-schema]
     (let [{:keys [input output]} (m/-function-info arity-schema)]
       {:seon.test.accretion/index index
        :seon.test.accretion/output output
        :seon.test.accretion/generator
        (gen/fmap
         (fn [arguments]
           {:seon.test.accretion/index index
            :seon.test.accretion/arguments arguments})
         (mg/generator input options))}))
   (range)
   (m/-function-schema-arities function-schema)))

(defn auto-check
  "Run the configured seeded contract cases through the candidate kernel.

  Effectful and non-generatable candidates are explicit skipped outcomes.
  A failure retains test.check's shrunk arguments and Malli's explanation."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/auto-check-request]
    :seon.test.accretion/auto-check-result]}
  [{database :seon.db/db
    row :seon.program/row
    projection :seon.schema/projection
    case-count :seon.config.test/auto-check-cases
    seed :seon.test.accretion/seed
    :as request}]
  (let [capabilities (candidate-capabilities database row)
        base-result
        {:seon.test.accretion/seed seed
         :seon.test.accretion/case-count case-count
         :seon.test.accretion/capabilities capabilities}]
    (if (seq capabilities)
      (assoc base-result
             :seon.test.accretion/status :skipped-effectful
             :seon.test.accretion/executed-count 0)
      (try
        (let [options
              {:registry (:seon.schema.projection/registry projection)}
              function-schema
              (m/function-schema (edn/read-string (:seon.fn/spec row))
                                 options)
              arities (arity-generators function-schema options)
              arity-by-index
              (into {} (map (juxt :seon.test.accretion/index identity)) arities)
              generated
              (gen/one-of
               (mapv :seon.test.accretion/generator arities))
              property
              (prop/for-all*
               [generated]
               (fn [{index :seon.test.accretion/index
                     arguments :seon.test.accretion/arguments}]
                 (:seon.test.accretion/pass?
                  (observation
                   (assoc request :seon.fn/sym (:seon.fn/sym row))
                   (:seon.test.accretion/output (arity-by-index index))
                   arguments))))
              checked (tc/quick-check case-count property :seed seed)
              completed
              (assoc base-result
                     :seon.test.accretion/executed-count
                     (:num-tests checked))]
          (if (true? (:result checked))
            (assoc completed :seon.test.accretion/status :passed)
            (let [{index :seon.test.accretion/index
                   arguments :seon.test.accretion/arguments}
                  (first (get-in checked [:shrunk :smallest]))]
              (assoc completed
                     :seon.test.accretion/status :failed
                     :seon.test.accretion/failure
                     (observation
                      (assoc request :seon.fn/sym (:seon.fn/sym row))
                      (:seon.test.accretion/output (arity-by-index index))
                      arguments)))))
        (catch Throwable failure
          (assoc base-result
                 :seon.test.accretion/status :skipped-non-generatable
                 :seon.test.accretion/skip-reason
                 (or (ex-message failure) (.getName (class failure)))
                 :seon.test.accretion/executed-count 0))))))

(defn seed-for
  "A stable positive test.check seed derived from one receipt identity."
  {:malli/schema [:=> [:cat :seon.cluster.eval/id]
                  :seon.test.accretion/seed]}
  [receipt-id]
  (Long/parseLong
   (subs (schema/sha-256 [(.getBytes receipt-id "UTF-8")]) 0 15)
   16))

(defn- failed-test?
  [result]
  (pos? (+ (:seon.test/fail-count result)
           (:seon.test/error-count result))))

(defn- test-failure
  [result]
  (let [shape
        (pr-str
         [:test
          (:seon.test/fail-count result)
          (:seon.test/error-count result)
          (:seon.test/failure-message result)])]
    (cond->
     {:seon.test.accretion/failure-source :test
      :seon.test.accretion/failure-shape shape
      :seon.test/sym (:seon.test/sym result)
      :seon.test.accretion/expected-actual
      (or (:seon.test/failure-message result)
          "The test failed without an assertion message.")}
      (seq (:seon.test/failing-assertions result))
      (assoc :seon.test/failing-assertions
             (:seon.test/failing-assertions result)))))

(defn- auto-check-failure
  [check]
  (when (= :failed (:seon.test.accretion/status check))
    (let [failure (:seon.test.accretion/failure check)]
      (assoc failure
             :seon.test.accretion/failure-source :auto-check
             :seon.test.accretion/failure-shape
             (pr-str [:auto-check
                      (:seon.test.accretion/expected failure)
                      (:seon.test.accretion/explanation failure)])
             :seon.test.accretion/seed
             (:seon.test.accretion/seed check)))))

(defn- advisories
  [function-symbol test-results check]
  (cond-> []
    (empty? test-results)
    (conj (str "No example test gates " function-symbol
               "; add one to teach intended behavior."))

    (= :skipped-non-generatable
       (:seon.test.accretion/status check))
    (conj (str "Auto-check skipped: "
               (:seon.test.accretion/skip-reason check)))

    (keyword? (get-in check
                      [:seon.test.accretion/failure
                       :seon.test.accretion/actual
                       :seon.error/kind]))
    (conj
     (str "Observed undeclared error class "
          (get-in check
                  [:seon.test.accretion/failure
                   :seon.test.accretion/actual
                   :seon.error/kind])
          "; declare the error branch in the output schema when intentional."))))

(defn gate-report
  "Assemble one complete install decision from candidate test evidence."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/gate-report-request]
    :seon.test.accretion/gate-report]}
  [{function-symbol :seon.fn/sym
    test-results :seon.test.accretion/results
    check :seon.test.accretion/auto-check}]
  (let [test-failures (into [] (comp (filter failed-test?)
                                     (map test-failure)) test-results)
        check-failure (auto-check-failure check)
        failures (cond-> test-failures
                   check-failure (conj check-failure))
        grouped
        (->> failures
             (group-by :seon.test.accretion/failure-shape)
             (sort-by key)
             (mapv (fn [[shape members]]
                     {:seon.test.accretion/failure-shape shape
                      :seon.test.accretion/failures members})))
        refused? (seq failures)
        test-count (count test-results)
        failed-count (count test-failures)
        passed-count (- test-count failed-count)
        orientation
        (str "Gate: " test-count " tests · " passed-count
             " passed · " failed-count " failed · auto-check "
             (:seon.test.accretion/executed-count check) "/"
             (:seon.test.accretion/case-count check) " · install "
             (if refused? "refused" "ready"))]
    {:seon.fn/sym function-symbol
     :seon.test.accretion/orientation orientation
     :seon.test.accretion/test-count test-count
     :seon.test.accretion/test-pass-count passed-count
     :seon.test.accretion/test-fail-count failed-count
     :seon.test.accretion/failure-groups grouped
     :seon.test.accretion/advisories
     (advisories function-symbol test-results check)
     :seon.test.accretion/auto-check check
     :seon.test.accretion/install? (not refused?)}))

(defn install-refusal
  "The attribute-shaped error value for one refused function install."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/gate-report]
    :seon.test.accretion/install-refused-error]}
  [report]
  (assoc report
         :seon.test.accretion/install-refused true
         :seon.error/kind :seon.test.accretion/install-refused
         :seon.error/message (:seon.test.accretion/orientation report)))

(defn- render-failure
  [failure]
  (case (:seon.test.accretion/failure-source failure)
    :test
    (str "Test " (:seon.test/sym failure) "\n"
         (:seon.test.accretion/expected-actual failure))

    :auto-check
    (str "Auto-check seed " (:seon.test.accretion/seed failure)
         "\narguments: " (pr-str (:seon.test.accretion/arguments failure))
         "\nexpected: " (:seon.test.accretion/expected failure)
         "\nactual: " (pr-str (:seon.test.accretion/actual failure))
         (when-let [explanation (:seon.test.accretion/explanation failure)]
           (str "\nwhy: " explanation)))))

(defn render-ai
  "Render the grouped teaching face of an install refusal."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/install-refused-error]
    :seon.render/ai]}
  [unit]
  (when (:seon.test.accretion/install-refused unit)
    (str/join
     "\n\n"
     (concat
      [(:seon.test.accretion/orientation unit)]
      (mapcat
       (fn [group]
         (let [members (:seon.test.accretion/failures group)
               shown (take 3 members)]
           (concat
            [(str "Failure group (" (count members) ")")]
            (map render-failure shown)
            (when (< 3 (count members))
              [(str (- (count members) 3)
                    " more in the complete gate-report blob.")]))))
       (:seon.test.accretion/failure-groups unit))
      (when (seq (:seon.test.accretion/advisories unit))
        [(str "Advisories\n"
              (str/join "\n" (:seon.test.accretion/advisories unit)))])))))

(defn render-html
  "Render an install refusal as one HTML card."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/install-refused-error]
    :seon.render/hiccup]}
  [unit]
  (when-let [text (render-ai unit)]
    [:article {:class "seon-family-entry seon-test-gate-refusal"}
     [:pre text]]))
