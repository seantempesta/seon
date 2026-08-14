(ns seon.test.accretion
  "Green-to-install derivations shared by schema admission and runtime gating."
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [malli.registry :as mr]))

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
