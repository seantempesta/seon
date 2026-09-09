(ns components-messages-probe-2026-09-08
  (:require [seon.cluster.message :as message]
            [seon.db :as db]
            [seon.operator.runtime]
            [seon.schema]))

(defn capture!
  "Record exact generated message source from the selected live cluster."
  [cluster-name target]
  (let [instance (get @seon.operator.runtime/running-instances cluster-name)
        cluster (:seon.cluster.loop/cluster instance)
        connection (:seon.db/connection cluster)]
    (seon.schema/call-with-projection-state
     (:seon.sci.eval/projection-state cluster)
     (fn []
       (let [messages (db/q '[:find [(pull ?message [*]) ...]
                              :where [?agent :seon.cluster.agent/id "juniper"]
                                     [?message :seon.cluster.message/to ?agent]]
                            @connection)
             source (message/render-inbox-ai messages)]
         (spit target source)
         {::messages (count messages) ::source source})))))
