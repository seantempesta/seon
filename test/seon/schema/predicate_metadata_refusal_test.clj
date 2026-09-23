(ns seon.schema.predicate-metadata-refusal-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.config :as config]
            [seon.error :as error]
            [seon.fn.schema-shape :as shape]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.schema.internal :as internal]
            [seon.test-support :as support]))

(deftest named-metadata-predicates-survive-loaded-refusal
  (support/with-database
   (fn [_]
     (let [projection (schema/handed-projection)
           predicates (schema/predicate-functions-in projection)
           operation 'seon.schema.predicate-metadata-refusal-test/probe
           authored (:malli/schema (meta #'internal/entity-entries))
           registry (:seon.schema.projection/registry projection)
           options {:registry (mr/composite-registry registry (mr/var-registry))}
           caps (config/result-caps config/defaults)
           policy {:seon.config/on-core-error :panic
                   :seon.config.error/max-evidence-bytes 8192}
           wrapped (#'instrument/compiled-wrapper projection operation authored
                    (fn [_] []) caps policy)
           refusal (support/refusal-data #(wrapped nil))
           explanations (:seon.instrument/explanations refusal)
           item (first (:seon.instrument.explanations/items explanations))
           actual (:seon.instrument.explanation/actual item)
           forms (:seon.schema.projection/forms projection)
           fingerprint (fn [form]
                         (:seon.schema.shape/fingerprint
                          (shape/normalized-form (m/schema form options)
                                                 forms predicates)))]
       (is (not (contains? (:seon.schema.projection/function-contracts projection)
                           operation)))
       (is (nil? (mr/schema registry operation)))
       (is (not-any? #(identical? m/schema? %) (vals predicates)))
       (is (= 'malli.core/schema? (get-in authored [1 1 1])))
       (is (= authored
              (schema/canonical-definition
               (m/form (m/schema (schema/compilable-form authored predicates)
                                 options)) predicates)))
       (is ((schema/projection-validator projection :seon.instrument/contract-error)
            refusal) (pr-str refusal))
       (is (= :input (:seon.instrument/check refusal)))
       (is (= operation (:seon.instrument/fn refusal)))
       (is (= operation (:seon.error/operation refusal)))
       (is (= 1 (:seon.instrument.explanations/count explanations)))
       (is (= 1 (count (:seon.instrument.explanations/items explanations))))
       (is (= (fingerprint [:cat [:fn 'malli.core/schema?]])
              (:seon.error/expected-shape refusal)))
       (is (= (fingerprint [:fn 'malli.core/schema?])
              (:seon.instrument.explanation/expected-shape item)))
       (is (= (error/project-observation
               (assoc caps :seon.config.eval.result/max-bytes
                      (:seon.error.projection/bound-bytes actual)) nil)
              actual))
       (is (= [] (wrapped (m/schema [:map]))))
       (let [anonymous (try
                         (schema/canonical-definition [:fn (fn [_] true)] predicates)
                         (catch clojure.lang.ExceptionInfo failure
                           (ex-data failure)))]
         (is (= :seon.schema/unnamed-callable
                (:seon.schema/noncanonical-definition anonymous))))))))
