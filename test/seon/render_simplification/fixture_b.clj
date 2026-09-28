(ns seon.render-simplification.fixture-b)

(defn namespace-ai
  "Identify fixture B's declared subject (seon.render-simplification-test/declare-subject!)."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [value]
  (str "B:" (:seon.ns/name value)))

(defn holes-html
  "Return former walker markers as ordinary inert Hiccup attributes."
  {:malli/schema [:=> [:cat :map] :seon.render/hiccup]}
  [_value]
  [:section {:data-slot "inert"}
   [:span {:data-ref "[:db/id 7]"} "also inert"]])
