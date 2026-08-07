(ns probe-predicate-function-cache
  "Surface: seon.schema/!predicate-functions — the process-global qualified-symbol
   to host-function cache that every `[:fn sym]` schema form binds through.

   Hypothesis: two isolated environments that declare the same predicate symbol
   with different behavior overwrite each other process-wide, so a schema form
   that is identical as DATA validates differently depending on which
   environment registered last."
  (:require [malli.core :as m]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(defn accept-a? [value] (= value :a))
(defn accept-b? [value] (= value :b))

(defn- validates? [form value]
  (let [projection
        (schema/build-projection
         (assoc ((requiring-resolve 'seon.schema.edn/packaged-forms))
                :probe/predicated form))]
    (m/validate
     (m/deref-recursive
      :probe/predicated
      {:registry (:seon.schema.projection/registry projection)})
     value)))

(defn run
  "Register one predicate symbol from two environments and compare behavior."
  [_options]
  (let [form [:fn 'probe-predicate-function-cache/accept?]
        _ (schema/register-core-predicate!
           'probe-predicate-function-cache/accept? accept-a?)
        a-before-b (validates? form :a)
        _ (schema/register-core-predicate!
           'probe-predicate-function-cache/accept? accept-b?)
        a-after-b (validates? form :a)
        b-after-b (validates? form :b)]
    {:probe/name 'probe-predicate-function-cache
     :probe/surface "seon.schema/!predicate-functions (process-global)"
     :probe/verdict (if (and a-before-b a-after-b (not b-after-b))
                      :pass
                      :fail)
     :probe/evidence
     {:probe/a-valid-under-first-registration a-before-b
      :probe/a-valid-after-second-registration a-after-b
      :probe/b-valid-after-second-registration b-after-b
      :probe/note
      (str "The projection is rebuilt from immutable form data both times. "
           "Any change in verdict comes only from the shared function cache.")}}))
