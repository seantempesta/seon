(ns seon.render-simplification.fixture-a)

(defn namespace-ai
  "Identify the fixture A namespace through its own renderer."
  {:malli/schema [:=> [:cat :seon.ns/ns] :seon.render/ai]}
  [value]
  (str "A:" (:seon.ns/name value)))
