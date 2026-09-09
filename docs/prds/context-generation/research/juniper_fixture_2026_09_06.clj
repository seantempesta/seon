(ns juniper-fixture-2026-09-06
  (:require [my.plan]
            [seon.cluster]
            [seon.db]
            [seon.operator.runtime]
            [seon.schema]))

; Load this file through MCP JVM evaluation on the default development
; cluster. These are sample messages requested by Sean, not model-generated
; replies. The plan is one agent-owned component tree: the agent owns the
; plan through :seon.agent/plan. Its objective and four root steps share that
; component; :my.plan.item/position orders the steps, and
; :my.plan/current-step names the open step Juniper is working on.
(defn install!
  "Install the Juniper example in the explicitly selected running cluster."
  {:malli/schema [:=> [:cat :seon.cluster/name]
                  [:or :my.plan/component-view :seon.error/value]]}
  [cluster-name]
 (let [instance (get @seon.operator.runtime/running-instances cluster-name)
      cluster (:seon.turn.loop/cluster instance)
      connection (:seon.db/connection cluster)
      process (:seon.db.process/id cluster)]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state cluster)
   (fn []
     (let [created
           (seon.cluster/ensure-entity!
            connection process
            {:seon.agent/id "juniper"
             :seon.cluster/name cluster-name
             :seon.ns/name 'my.agents.juniper})]
       (if (:seon.error/kind created)
         created
         (let [written
               (seon.db/transact!
                connection
                {:tx-data
                 [[:db.fn/call
                   (fn [database]
                     (let [step-ref
                           (fn [id]
                             (or (:db/id (seon.db/pull database [:db/id]
                                                      [:my.plan.item/id id]))
                                 id))
                           plan-ref
                           (or (seon.db/q
                                '[:find ?plan . :where
                                  [?agent :seon.agent/id "juniper"]
                                  [?agent :seon.agent/plan ?plan]]
                                database)
                               "juniper-plan")
                           settings-ref
                           (or (seon.db/q
                                '[:find ?settings . :where
                                  [?agent :seon.agent/id "juniper"]
                                  [?agent :seon.agent/settings ?settings]]
                                database)
                               "juniper-settings")]
                 [{:db/id [:seon.agent/id "juniper"]
                   :seon.agent/settings
                   {:db/id settings-ref
                    :seon.config.eval/time-limit-ms 2500
                    :seon.config.ai/no-provider true
                    :seon.config.run/max-episode-runs 4}
                   :seon.agent/plan
                   {:db/id plan-ref
                    :my.plan/objective "Improve Juniper context inspection"
                    :my.plan/current-step (step-ref "juniper/render-plan")
                    :my.plan/steps
                      #{{:db/id (step-ref "juniper/inspect-identity-messages")
                         :my.plan.item/id "juniper/inspect-identity-messages"
                         :my.plan.item/position 0
                         :my.plan.item/title "Inspect identity and messages"
                         :my.plan.item/description
                         "Verify the fixture identity and the two sample messages in the paired AI and HTML blocks."
                         :my.plan.item/expected-result
                         "The identity unit and both root messages were read on the context inspection page."
                         :my.plan.item/completed-at #inst "2026-09-07T01:30:00Z"}
                        {:db/id (step-ref "juniper/render-plan")
                         :my.plan.item/id "juniper/render-plan"
                         :my.plan.item/position 1
                         :my.plan.item/title "Render this plan clearly"
                         :my.plan.item/description
                         "Replace raw entity numbers and dense prose with a readable current focus, progress summary, stable dependencies, and expandable step evidence."
                         :my.plan.item/expected-result
                         "The AI plan is concise and actionable; the HTML plan shows progress and stable step references without numeric entity ids."}
                        {:db/id (step-ref "juniper/compare-changed-results")
                         :my.plan.item/id "juniper/compare-changed-results"
                         :my.plan.item/position 2
                         :my.plan.item/title "Compare refreshed results"
                         :my.plan.item/description
                         "After context selections move from memory into durable agent-linked facts, change one relevant fact and compare the previous result with its automatically refreshed result."
                         :my.plan.item/expected-result
                         "The comparison shows the previous and refreshed results together, with the relevant changed input."
                         :my.plan.item/needs #{(step-ref "juniper/render-plan")}}
                        {:db/id (step-ref "juniper/try-live-turn")
                         :my.plan.item/id "juniper/try-live-turn"
                         :my.plan.item/position 3
                         :my.plan.item/title
                         "Try the assembled context in a live agent turn"
                         :my.plan.item/description
                         "After the visual and result checks pass, ask Juniper to find the same facts and update its own plan."
                         :my.plan.item/expected-result
                         "Juniper identifies the current step and records a truthful plan update from the assembled context."
                         :my.plan.item/needs #{(step-ref "juniper/compare-changed-results")}}}}}
                  {:seon.cluster.message/id "design-lab/root-to-juniper/1"
                   :seon.cluster.message/from [:seon.agent/id "root"]
                   :seon.cluster.message/to [:seon.agent/id "juniper"]
                   :seon.cluster.message/content
                   "Please make your current plan and the messages you receive easy to understand together. Start by inspecting the data connected to your agent entity."
                   :seon.cluster.message/at #inst "2026-09-06T19:35:00Z"}
                  {:seon.cluster.message/id "design-lab/root-to-juniper/2"
                   :seon.cluster.message/from [:seon.agent/id "root"]
                   :seon.cluster.message/to [:seon.agent/id "juniper"]
                   :seon.cluster.message/content
                   "Show Sean which function renders each block and an executable example of updating your plan. We will compare the assembled context before trying a live model turn."
                   :seon.cluster.message/at #inst "2026-09-06T19:36:00Z"}]))]]
                 :tx-meta
                 {:seon.db/user [:seon.agent/id "root"]
                  :seon.db/process [:seon.db.process/id process]}})]
           (if (:seon.error/kind written)
             written
             (my.plan/plan
              {:seon.db/db (seon.db/db connection)
               :seon.agent/id "juniper"})))))))))
