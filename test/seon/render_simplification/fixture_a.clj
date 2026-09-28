(ns seon.render-simplification.fixture-a)

(defn namespace-ai
  "Identify fixture A's declared subject (seon.render-simplification-test/declare-subject!)."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [value]
  (str "A:" (:seon.ns/name value)))
