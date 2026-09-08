(ns components-probe-2026-09-08
  (:require [my.agent]
            [my.plan]
            [seon.ai]
            [seon.cluster.agent]
            [seon.db]
            [seon.operator.runtime]
            [seon.render.hiccup]
            [seon.schema]))

; Load through MCP JVM evaluation on scratch cluster components. These are
; actual component functions and database facts, without a second renderer.
(let [instance (get @seon.operator.runtime/running-instances "components")
      cluster (:seon.cluster.loop/cluster instance)
      connection (:seon.db/connection cluster)
      target "/Users/sean/src/seon/docs/prds/context-generation/research/components-final-2026-09-08"]
  (seon.schema/call-with-projection-state
   (:seon.sci.eval/projection-state cluster)
   (fn []
     (let [database @connection
           identity (seon.db/pull database
                                  '[:seon.cluster.agent/id
                                    {:seon.cluster.agent/namespace
                                     [:seon.ns/name
                                      {:seon.ns/steward [:seon.cluster.agent/id]}]}]
                                  [:seon.cluster.agent/id "juniper"])
           plan (my.plan/plan {:seon.db/db database :seon.cluster.agent/id "juniper"})
           settings (my.agent/settings database "juniper")
           unit (fn [data] (assoc data :seon.db/db database
                                 :seon.cluster.agent/id "juniper"))
           sources {:seon.cluster.agent/agent (seon.cluster.agent/render-identity-ai (unit identity))
                    :seon.agent/plan (my.plan/render-plan-ai (unit plan))
                    :seon.agent/settings (my.agent/render-settings-ai (unit settings))}
           projections [(seon.cluster.agent/render-identity-html (unit identity))
                        (my.plan/render-plan-html (unit plan))
                        (my.agent/render-settings-html (unit settings))]
           html (seon.render.hiccup/->string
                 [:html
                  [:head [:meta {:charset "utf-8"}]
                   [:title "Juniper components — live function results"]
                   [:link {:rel "stylesheet" :href "components-evidence-2026-09-08.css"}]]
                  (into [:body {:class "seon-body" :style {:padding "2rem" :max-width "1100px" :margin "auto"}}
                         [:h1 "Juniper components"]]
                        projections)])]
       (spit (str target "-sources.edn") (str (pr-str sources) "\n"))
       (spit (str target ".html") html)
       {:seon.cluster.agent/id "juniper"
        :seon.agent/settings settings
        :seon.config/resolved-attributes (seon.ai/agent-setting-attributes database)
        ::sources sources
        ::html projections}))))
