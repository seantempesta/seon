(ns seon.agent.driver.pod
  "Pod external leaves for the portable claim driver."
  (:require [seon.agent.driver :as driver]
            [seon.agent.turn :as turn]
            [seon.db :as db]))

(declare leaf)

(defonce ^:private !handles (atom {}))

(defn dispatch-run!
  "Admit at most one process-local driver fiber for a run.

   The database claim remains the authority. This retained Promise is only the
   R1 addressable handle that prevents two wake leaves in one claimant process
   from concurrently exercising the same held epoch."
  [request]
  (let [run-id (:seon.agent.run/id request)]
    (or (get @!handles run-id)
        (let [handle
              (-> (driver/call-with-leaf
                   leaf db/*leaf*
                   #(driver/drive-run! request))
                  (.finally
                   (fn []
                     (swap! !handles dissoc run-id))))]
          (swap! !handles
                 #(if (contains? % run-id) % (assoc % run-id handle)))
          (get @!handles run-id)))))

(defn ^:async execute-step!
  "Execute the one pod-capable phase selected by the portable driver."
  [{step :seon.agent.driver/step :as claim}]
  (case step
    :render
    (await (turn/render-phase! claim))

    :publish
    (await (turn/publish-phase! claim))

    {:seon.error/message
     (str "The pod claimant cannot execute phase " (pr-str step) ".")
     :seon.error/kind :core-bug}))

(def leaf
  {:seon.agent.driver/capabilities
   #{:seon.agent.driver.capability/render
     :seon.agent.driver.capability/publish}
   :seon.agent.driver/now #(js/Date.)
   :seon.agent.driver/dispatch-run! dispatch-run!
   :seon.agent.driver/execute-step! execute-step!})
