(ns seon.agent.message.pod
  "Bind Bun admission and clock services for portable messaging."
  (:require [seon.agent.message.leaf :as leaf]
            [seon.runtime.admission :as admission]
            [seon.warn :as warn]))

(defn services
  "Return Bun services for portable messaging."
  []
  {::leaf/available? admission/available?
   ::leaf/unavailable #(or (:seon/error (admission/unavailable))
                           {:seon.error/message "Runtime admission is unavailable."
                            :seon.error/kind :core-bug})
   ::leaf/now #(js/Date.)
   ::leaf/uuid #(str (random-uuid))
   ::leaf/hop-cap warn/hop-cap})
