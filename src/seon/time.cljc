(ns seon.time
  "Portable instant formatting shared by the pod and JVM execution host.")

(defn iso-string
  "UTC ISO-8601 text for one instant."
  {:malli/schema [:=> [:cat :inst] :string]}
  [instant]
  #?(:clj (str (.toInstant ^java.util.Date instant))
     :cljs (.toISOString ^js instant)))
