(ns seon.agent.driver.pod
  "Pod external leaves for the portable claim driver."
  (:require [seon.agent.turn :as turn]
            [seon.ai.dispatch :as ai.dispatch]))

(defn ^:async execute-step!
  "Execute the one pod-capable phase selected by the portable driver."
  [{step :seon.agent.driver/step :as claim}]
  (case step
    :render
    (await (turn/render-phase! claim))

    :open-attempt
    (let [result
          (await
           (turn/llm-phase!
            (assoc claim :seon.agent/llm-fn (ai.dispatch/llm-fn))))]
      (or (:seon.retry/result result) result))

    :settle-attempt
    (let [result
          (await
           (turn/llm-phase!
            (assoc claim :seon.agent/llm-fn (ai.dispatch/llm-fn))))]
      (or (:seon.retry/result result) result))

    :publish
    (await (turn/publish-phase! claim))

    {:seon.error/message
     (str "The pod claimant cannot execute phase " (pr-str step) ".")
     :seon.error/kind :core-bug}))

(def leaf
  {:seon.agent.driver/capabilities
   #{:seon.agent.driver.capability/render
     :seon.agent.driver.capability/llm
     :seon.agent.driver.capability/publish}
   :seon.agent.driver/now #(js/Date.)
   :seon.agent.driver/execute-step! execute-step!})
