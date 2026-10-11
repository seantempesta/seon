(ns ^{:seon.ns/context-relevant? true} my.turn
  "Return waiting data for my session: a reply is `my.message/send` then `my.turn/wait`."
  (:require [seon.run :as run]))

(defn wait
  "Return the event or reply I need before continuing.

  Returns {:my.turn/disposition :wait :my.turn/note string}. Return it as
  the form's result to pause until a later outside wake.

  Example:
  (my.turn/wait {:my.turn/note \"Waiting for the missing source data.\"})"
  {:malli/schema [:=> [:cat [:map [:my.turn/note :my.turn/note]]] [:or :my.turn/wait :my.turn/blank-note-error]]}
  [request]
  (run/wait (:my.turn/note request)))

(defn usage-form
  "Render the lifecycle usage request."
  {:seon.fn/internal? true
   :malli/schema [:=> [:cat :my.turn/usage-unit] :seon.render/read-form]}
  [request]
  (run/usage-form request))
