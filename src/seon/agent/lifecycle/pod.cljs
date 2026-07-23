(ns seon.agent.lifecycle.pod
  "Bind Bun clock and admission services for portable lifecycle entries."
  (:require [seon.agent.lifecycle.leaf :as leaf]
            [seon.runtime.admission :as admission]))

(defn services
  "Return Bun services for portable lifecycle entries."
  []
  {::leaf/now #(js/Date.)
   ::leaf/available? admission/available?
   ::leaf/unavailable #(or (:seon/error (admission/unavailable))
                           {:seon.error/message "Runtime admission is unavailable."
                            :seon.error/kind :core-bug})
   ::leaf/uuid #(str (random-uuid))})
