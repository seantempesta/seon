(ns seon.render-simplification.fixture-ambiguous)

(defn first-ai
  "One deliberately overlapping renderer candidate."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [value]
  (str "first:" (:seon.ns/name value)))

(defn second-ai
  "The other deliberately overlapping renderer candidate."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [value]
  (str "second:" (:seon.ns/name value)))
