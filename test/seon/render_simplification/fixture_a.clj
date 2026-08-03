(ns seon.render-simplification.fixture-a)

(defn namespace-ai
  "Identify the fixture A namespace through its own renderer."
  {:malli/schema [:=> [:cat :seon.ns/ns] :string]}
  [value]
  (str "A:" (:seon.ns/name value)))
