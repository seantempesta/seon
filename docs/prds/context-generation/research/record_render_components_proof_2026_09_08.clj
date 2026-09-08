(ns record-render-components-proof-2026-09-08
  "Read-only default component evidence; run in the MCP JVM REPL."
  (:require [my.agent :as agent]
            [seon.db :as db]
            [seon.operator :as operator]
            [seon.schema :as schema]))

(defn observe
  "Read the existing component probe agent without repeating timed-out writes."
  []
  (let [database @(operator/connection "default")
        projection (schema/projection-from-database database)
        agent-id "record-render-components"]
    (schema/call-with-projection
     projection
     (fn []
       (let [settings (agent/settings database agent-id)]
         {:record-render/record
          (db/pull database
                   '[:seon.cluster.agent/id
                     {:seon.agent/plan
                      [:my.plan/objective
                       {:my.plan/steps [:my.plan.item/id :my.plan.item/title
                                        :my.plan.item/position]}]}
                     {:seon.agent/settings [*]}]
                   [:seon.cluster.agent/id agent-id])
          :record-render/settings settings
          :record-render/settings-shapes
          (mapv :seon.schema/key (schema/matching-shapes-in projection settings))})))))
