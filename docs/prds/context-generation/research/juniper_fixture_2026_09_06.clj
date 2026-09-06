(ns juniper-fixture-2026-09-06
  (:require [my.plan]
            [seon.cluster]
            [seon.db]
            [seon.operator.runtime]
            [seon.schema]))

; Load this file through MCP JVM evaluation on the selected scratch cluster.
; Change the cluster name below when reproducing on a fresh source fork.
; These are sample messages requested by Sean, not model-generated replies.
(let [cluster-name "lab-run-inspection"
      instance (get @seon.operator.runtime/running-instances cluster-name)
      cluster (:seon.cluster.loop/cluster instance)
      connection (:seon.db/connection cluster)
      process (:seon.cluster.run/process cluster)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state cluster)
   (fn []
     (let [created
           (seon.cluster/ensure-entity!
            connection process
            {:seon.cluster.agent/id "juniper"
             :seon.cluster/name cluster-name
             :seon.ns/name 'my.agents.juniper})]
       (if (:seon.error/kind created)
         created
         (let [plan
               (my.plan/plan!
                [{:my.plan.item/id "juniper/understand-context"
                  :my.plan.item/title "Make my plan and messages useful context"
                  :my.plan.item/description
                  "Inspect the facts connected to my agent entity. Compare the AI and HTML renderings, then improve the functions with Sean."
                  :my.plan.item/expected-result
                  "Two clear blocks: the work I am doing and the new messages I should respond to."}
                 {:my.plan.item/id "juniper/try-live-turn"
                  :my.plan.item/title "Try the assembled context in a live agent turn"
                  :my.plan.item/description
                  "After reviewing the blocks, test whether the agent can find its data and update its plan."}]
                (seon.db/db connection) connection "juniper")]
           (if (:seon.error/kind plan)
             plan
             (let [written
                   (seon.db/transact!
                    connection
                    {:tx-data
                     [{:db/id [:seon.cluster.agent/id "juniper"]
                       :my.plan/anchor
                       [:my.plan.item/id "juniper/understand-context"]}
                      {:seon.cluster.message/id "design-lab/root-to-juniper/1"
                       :seon.cluster.message/from [:seon.cluster.agent/id "root"]
                       :seon.cluster.message/to [:seon.cluster.agent/id "juniper"]
                       :seon.cluster.message/content
                       "Please make your current plan and the messages you receive easy to understand together. Start by inspecting the data connected to your agent entity."
                       :seon.cluster.message/at #inst "2026-09-06T19:35:00Z"}
                      {:seon.cluster.message/id "design-lab/root-to-juniper/2"
                       :seon.cluster.message/from [:seon.cluster.agent/id "root"]
                       :seon.cluster.message/to [:seon.cluster.agent/id "juniper"]
                       :seon.cluster.message/content
                       "Show Sean which function renders each block and an executable example of updating your plan. We will compare the assembled context before trying a live model turn."
                       :seon.cluster.message/at #inst "2026-09-06T19:36:00Z"}]
                     :tx-meta
                     {:seon.db/user [:seon.cluster.agent/id "root"]
                      :seon.db/process [:seon.db.process/id process]}})]
               (if (:seon.error/kind written)
                 written
                 (seon.db/pull
                  (seon.db/db connection)
                  '[:db/id :seon.cluster.agent/id
                    {:seon.cluster.agent/namespace [:seon.ns/name]}
                    {:my.plan/anchor [:my.plan.item/id]}
                    {:my.plan.item/_agent [:my.plan.item/id]}
                    {:seon.cluster.message/_to [:seon.cluster.message/id]}]
                  [:seon.cluster.agent/id "juniper"]))))))))))
