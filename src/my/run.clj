(ns my.run
  "Every run ends by calling `complete` or `wait`, with one request map."
  (:require [seon.run :as run]))

(defn complete
  "Finish my turn with a reply for its requester."
  {:malli/schema [:=> [:cat [:map [:my.run/result :my.run/result]]] [:or :my.run/completed :seon.error/value]]}
  [request]
  (run/complete (:my.run/result request)))

(defn wait
  "Finish my turn with the event or reply I am waiting for."
  {:malli/schema [:=> [:cat [:map [:my.run/note :my.run/note]]] [:or :my.run/wait :seon.error/value]]}
  [request]
  (run/wait (:my.run/note request)))

(defn render-namespace-ai
  "Render the lifecycle namespace request."
  {:malli/schema [:=> [:cat :my.run/namespace-unit] :seon.render/ai]}
  [request]
  (run/render-namespace-ai request))

(defn usage-form
  "Render the lifecycle usage request."
  {:malli/schema [:=> [:cat [:or :my.run/namespace-unit :my.run/usage-unit]] :seon.render/form]}
  [request]
  (run/usage-form request))
