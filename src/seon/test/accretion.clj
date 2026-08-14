(ns seon.test.accretion
  "Green-to-install derivations shared by schema admission and runtime gating."
  (:require [clojure.edn :as edn]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.effect :as effect]
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
