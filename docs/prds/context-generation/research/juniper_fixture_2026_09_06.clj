(ns juniper-fixture-2026-09-06
  (:require [seon.cluster]
            [seon.db]
            [seon.operator.runtime]
            [seon.schema]))

; Load this file through MCP JVM evaluation on the selected scratch cluster.
; These are sample messages requested by Sean, not model-generated replies.
(defn install!
  "Install the Juniper example in the explicitly selected running cluster."
  [cluster-name]
 (let [instance (get @seon.operator.runtime/running-instances cluster-name)
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
         (let [written
               (seon.db/transact!
                connection
                {:tx-data
                 [{:my.plan.item/id "juniper/understand-context"
                   :my.plan.item/agent [:seon.cluster.agent/id "juniper"]
                   :my.plan.item/title "Improve Juniper context inspection"
                   :my.plan.item/description
                   "Make identity, messages, plans, changed results, and the eventual live agent context easy to inspect together."
                   :my.plan.item/expected-result
                   "A clear paired context view whose facts and rendered results can be checked without guessing."}
                  {:my.plan.item/id "juniper/inspect-identity-messages"
                   :my.plan.item/agent [:seon.cluster.agent/id "juniper"]
                   :my.plan.item/parent [:my.plan.item/id "juniper/understand-context"]
                   :my.plan.item/title "Inspect identity and messages"
                   :my.plan.item/description
                   "Verify the fixture identity and the two sample messages in the paired AI and HTML blocks."
                   :my.plan.item/expected-result
                   "Juniper identity and both root messages were visible in the context inspection page."
                   :my.plan.item/completed-at #inst "2026-09-07T01:30:00Z"}
                  {:my.plan.item/id "juniper/render-plan"
                   :my.plan.item/agent [:seon.cluster.agent/id "juniper"]
                   :my.plan.item/parent [:my.plan.item/id "juniper/understand-context"]
                   :my.plan.item/title "Render this plan clearly"
                   :my.plan.item/description
                   "Show current focus, ready work, dependencies, completed evidence, and exact item references."
                   :my.plan.item/expected-result
                   "The plan reads as a compact hierarchy in both AI text and HTML."}
                  {:my.plan.item/id "juniper/compare-changed-results"
                   :my.plan.item/agent [:seon.cluster.agent/id "juniper"]
                   :my.plan.item/parent [:my.plan.item/id "juniper/understand-context"]
                   :my.plan.item/needs [[:my.plan.item/id "juniper/render-plan"]]
                   :my.plan.item/title "Compare refreshed results"
                   :my.plan.item/description
                   "Change one relevant fact and compare the locked evaluation with the refreshed result."
                   :my.plan.item/expected-result
                   "The comparison names the changed result while preserving the locked baseline."}
                  {:my.plan.item/id "juniper/try-live-turn"
                   :my.plan.item/agent [:seon.cluster.agent/id "juniper"]
                   :my.plan.item/parent [:my.plan.item/id "juniper/understand-context"]
                   :my.plan.item/needs [[:my.plan.item/id "juniper/compare-changed-results"]]
                   :my.plan.item/title "Try the assembled context in a live agent turn"
                   :my.plan.item/description
                   "After the visual and result checks pass, ask Juniper to find the same facts and update its own plan."
                   :my.plan.item/expected-result
                   "Juniper identifies the current step and records a truthful plan update from the assembled context."}
                  {:db/id [:seon.cluster.agent/id "juniper"]
                   :my.plan/anchor
                   [:my.plan.item/id "juniper/render-plan"]}
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
              [:seon.cluster.agent/id "juniper"])))))))))
