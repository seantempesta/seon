(ns turn-cut-read-evidence-probe-2026-09-08
  (:require [my.message :as message]
            [seon.db :as db]
            [seon.operator :as operator]))

(let [connection (operator/connection "default")
      captured (atom [])
      messages (binding [db/*read-evidence-sink* captured]
                 (message/inbox @connection "juniper"))]
  {:probe/recipient "juniper"
   :probe/message-count (count messages)
   :probe/plans (mapv :datahike.read/dependency-plan @captured)})
