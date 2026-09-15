(ns ^{:seon.ns/context-relevant? true} my.turn
  "Return explicit completion or waiting data for my session."
  (:require [seon.run :as run]))

(defn complete
  "Return my completion reply for its requester.

  Returns {:my.turn/disposition :completed :my.turn/result string}.
  Return this value as the form's result so the turn delivers the reply.

  Example:
  (my.turn/complete {:my.turn/result \"The requested verification passed.\"})"
  {:malli/schema [:=> [:cat [:map [:my.turn/result :my.turn/result]]] [:or :my.turn/completed :seon.error/value]]}
  [request]
  (run/complete (:my.turn/result request)))

(defn wait
  "Return the event or reply I need before continuing.

  Returns {:my.turn/disposition :wait :my.turn/note string}. Return it as
  the form's result to pause until a later outside wake.

  Example:
  (my.turn/wait {:my.turn/note \"Waiting for the missing source data.\"})"
  {:malli/schema [:=> [:cat [:map [:my.turn/note :my.turn/note]]] [:or :my.turn/wait :seon.error/value]]}
  [request]
  (run/wait (:my.turn/note request)))

(defn render-namespace-ai
  "Render the lifecycle namespace request."
  {:seon.fn/internal? true
   :malli/schema [:=> [:cat :my.turn/namespace-unit] :seon.render/ai]}
  [request]
  (run/render-namespace-ai request))

(defn usage-form
  "Render the lifecycle usage request."
  {:seon.fn/internal? true
   :malli/schema [:=> [:cat [:or :my.turn/namespace-unit :my.turn/usage-unit]] :seon.render/form]}
  [request]
  (run/usage-form request))
