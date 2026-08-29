(ns seon.error-class-schema-test
  "Registry-derived W1 proofs for the declared error-class vocabulary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.cluster]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(def ^:private floor-producers
  #{'seon.render.value/render-ai
    'seon.render.value/render-html})

(defn- class-properties
  [form]
  (schema.form/namespaced-properties form))

(defn- error-class?
  [form]
  (true? (:seon.error/class (class-properties form))))

(defn- projection-fixture
  []
  (let [projection (schema/build-projection (schema/registered-schemas))
        forms (:seon.schema.projection/forms projection)
        rows (:seon.schema.projection/shape-rows projection)
        class-keys (into #{}
                         (keep (fn [[schema-key form]]
                                 (when (error-class? form) schema-key)))
                         forms)]
    {:projection projection
     :forms forms
     :rows rows
     :class-keys class-keys}))

(defn- evidence-attributes
  [row]
  (disj (:seon.schema/required-attrs row)
        :seon.error/message))

;; Error classes are flat, open evidence maps. Some need one identifying
;; attribute; others legitimately require a larger evidence packet. The
;; declared class schema owns that shape, not an exactly-one-marker taxonomy.
(defn- class-value-generator
  [projection schema-key]
  (let [registry (:seon.schema.projection/registry projection)
        compiled (m/schema schema-key {:registry registry})]
    (mg/generator compiled)))

(defn- generated-class-value
  [projection schema-key seed]
  (mg/generate (class-value-generator projection schema-key)
               {:seed seed :size 8}))

(defn- matching-class-keys
  [projection forms value]
  (into #{}
        (comp
         (filter (fn [{schema-key :seon.schema/key}]
                   (error-class? (get forms schema-key))))
         (map :seon.schema/key))
        (schema/matching-shapes-in projection value)))

(defn- render-request
  [database ctx value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/value value
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic})

(deftest every-declared-error-class-has-the-flat-open-contract
  (let [{:keys [projection forms rows class-keys]} (projection-fixture)
        canonical-keys
        (into #{}
              (comp (filter #(true? (:seon.error/class %)))
                    (map :seon.schema/key))
              (schema/canonical-schema-rows forms))]
    (is (seq class-keys))
    (is (= class-keys canonical-keys)
        "the queryable schema rows and declaration properties must agree")
    (doseq [[ordinal schema-key] (map-indexed vector (sort class-keys))]
      (let [form (get forms schema-key)
            row (get rows schema-key)
            properties (class-properties form)
            required-attrs (:seon.schema/required-attrs row)
            evidence-attrs (evidence-attributes row)
            compiled (m/schema schema-key
                               {:registry
                                (:seon.schema.projection/registry projection)})
            value (generated-class-value projection schema-key
                                         (+ 2026080600 ordinal))]
        (testing (str schema-key)
          (is (contains? required-attrs :seon.error/message))
          (is (seq evidence-attrs))
          (is (every? #(contains? forms %) required-attrs))
          (is (not (str/blank? (:error/message properties))))
          (is (qualified-symbol? (:seon.render/ai properties)))
          (is (qualified-symbol? (:seon.render/html properties)))
          (is (m/validate compiled value))
          (is (m/validate compiled
                          (assoc value :seon.error.test/accreted true))
              "error-class maps remain open to accretion"))))))

(deftest refusal-classes-share-one-rule-and-transition-shape
  (let [{:keys [projection forms]} (projection-fixture)
        refusal-classes
        (into {}
              (filter (fn [[_ form]]
                        (true? (:seon.error/refusal
                                (class-properties form)))))
              forms)]
    (is (= :map (first (:seon.error/refusal-value forms))))
    (is (seq refusal-classes))
    (doseq [[schema-key form] refusal-classes]
      (let [properties (class-properties form)
            compiled (m/schema schema-key
                               {:registry
                                (:seon.schema.projection/registry projection)})
            generated (mg/generate compiled {:seed 2026080603 :size 8})]
        (testing (str schema-key)
          (is (= :seon.error/refusal-value
                 (:seon.error/refusal-shape properties)))
          (is (some #{:seon.error/refusal-value} form))
          (is (m/validate compiled generated)))))))

(deftest generated-class-values-match-exactly-one-error-class
  (let [{:keys [projection forms class-keys]} (projection-fixture)]
    (doseq [[ordinal schema-key] (map-indexed vector (sort class-keys))]
      (let [check
            (tc/quick-check
             5
             (prop/for-all
              [value (class-value-generator projection schema-key)]
              (= #{schema-key}
                 (matching-class-keys projection forms value)))
             :seed (+ 2026080610 ordinal))]
        (test-support/assert-check!
         check
         (str "Error class must be structurally unambiguous: " schema-key))))))

(deftest no-error-class-reaches-the-generic-value-floor
  (let [{:keys [projection forms class-keys]} (projection-fixture)]
    (doseq [[ordinal schema-key] (map-indexed vector (sort class-keys))]
      (let [properties (class-properties (get forms schema-key))
            value (generated-class-value projection schema-key
                                         (+ 2026080700 ordinal))
            ai-producer (:seon.render/ai properties)
            html-producer (:seon.render/html properties)]
        (testing (str schema-key)
          (is (= #{schema-key}
                 (matching-class-keys projection forms value)))
          (is (not (contains? floor-producers ai-producer)))
          (is (not (contains? floor-producers html-producer)))
          (is (string? ((requiring-resolve ai-producer) value)))
          (is (vector? ((requiring-resolve html-producer) value))))))
    (test-support/with-database
     (fn [connection]
       (let [database @connection
             ctx (test-support/fork-cluster-ctx connection)
             representative-key (first (sort class-keys))
             value (generated-class-value projection representative-key
                                          2026080799)
             request (render-request database ctx value)
             floor-unit {:seon.render/value value
                         :seon.sci.admit/caps caps}]
         (is (not= (render.value/render-ai floor-unit)
                   (render/render-ai request)))
         (is (not= (render.value/render-html floor-unit)
                   (render/render-html request))))))))
