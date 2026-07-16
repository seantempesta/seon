(ns seon.execution.runtime
  "Composition root and compiled prompt entrypoint for execution children."
  (:require
   [my.plan.internal]
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas]
   [seon.agent.ctx.menu]
   [seon.agent.ctx.namespaces]
   [seon.agent.ctx.subagents]
   [seon.agent.ctx.transcript]
   [seon.agent.ctx.typeahead-steps]
   [seon.agent.ctx.warnings]
   [seon.db :as db]
   [seon.execution :as execution]
   [seon.schema :as schema]))

(schema/register! ::render-prompt-request
  [:map {:closed true}
   [:seon.agent/id :string]
   [:seon.agent.ctx/profile
    {:optional true}
    :seon.agent.ctx/profile]])

(def ^:private prompt-pull-pattern
  '[:db/id
    :seon.agent/id
    :seon.agent.ctx/cache-breakpoint
    :seon.render/ai
    {:seon.agent/ctx [*]}])

(defn- database-error-entity
  [error]
  {:seon.agent/ctx
   [{:seon.agent.ctx/name :database
     :seon.agent.ctx/priority 0
     :seon.render/ai (pr-str (str ";; " (pr-str error)))}]})

(defn ^:async render-prompt!
  "Render one agent's acquired literal prompt data at the active coordinate."
  {:malli/schema [:=> [:cat ::render-prompt-request]
                  :seon.agent.ctx/rendered-context]}
  [{:seon.agent/keys [id] profile :seon.agent.ctx/profile}]
  (let [result (await (db/pull {::db/pull-pattern prompt-pull-pattern
                                ::db/ref [:seon.agent/id id]}))
        entity (cond
                 (and (map? result)
                      (string? (:seon.error/message result)))
                 (database-error-entity result)

                 (map? result) result
                 :else {})]
    (ctx/rendered-context-from-entity
     (cond-> {:seon.agent/entity entity}
       (seq profile) (assoc :seon.agent.ctx/profile (vec profile))))))

(defn -main
  "Start the execution child from the complete runtime composition root."
  []
  (execution/-main))
