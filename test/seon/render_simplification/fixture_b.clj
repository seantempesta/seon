(ns seon.render-simplification.fixture-b)

(defn namespace-ai
  "Identify the fixture B namespace through its own renderer."
  {:malli/schema [:=> [:cat :seon.ns/ns] :seon.render/ai]}
  [value]
  (str "B:" (:seon.ns/name value)))

(defn holes-html
  "Return former walker markers as ordinary inert Hiccup attributes."
  {:malli/schema [:=> [:cat :map] :seon.render/html]}
  [_value]
  [:section {:data-slot "inert"}
   [:span {:data-ref "[:db/id 7]"} "also inert"]])
