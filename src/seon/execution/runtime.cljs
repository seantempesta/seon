(ns seon.execution.runtime
  "Compose execution-child services and the compiled evaluation entrypoint.

   This runtime wires evaluation, rendering, database access, and request
   dispatch inside one child without taking over host process supervision."
  (:require
   [my.blob]
   [my.canvas]
   [my.data]
   [my.kb]
   [my.ns]
   [my.plan]
   [my.skills]
   [my.ui]
   [seon.agent]
   [seon.agent.home :as home]
   [seon.agent.ctx.menu]
   [seon.agent.ctx.namespaces]
   [seon.agent.ctx.render-fns :as render-fns]
   [seon.agent.ctx.subagents]
   [seon.agent.ctx.transcript]
   [seon.agent.ctx.typeahead-steps]
   [seon.agent.ctx.warnings]
   [seon.agent.fs]
   [seon.agent.lifecycle]
   [seon.agent.search]
   [seon.agent.shell]
   [seon.agent.web]
   [seon.ai :as ai]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.execution :as execution]
   [seon.error :as error]
   [seon.eval :as eval]
   [seon.render :as render]
   [seon.render.system]
   [seon.schema :as schema]
   [seon.web.reactive.transform :as reactive-transform]))


(def ^:private setup-home-requires-origin-query
  '[:find ?requires ?process-id
    :in $ ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.eval/home-requires ?requires ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id ?process-id]])

(defn- ^:async setup-fault-kind!
  "Classify namespace setup failure from its persisted input provenance."
  [database agent-id]
  (let [origin
        (when agent-id
          (await
           (db/query
            {::db/db database
             ::db/query setup-home-requires-origin-query
             ::db/args [agent-id]
             ::db/max-work 100000
             ::db/max-results 8
             ::db/max-result-weight 4096})))
        agent-override?
        (and (set? origin)
             (= 1 (count origin))
             (let [[requires process-id] (first origin)]
               (and (= :seon.db.process/repl process-id)
                    (not= home/home-ns-require-specs
                          (db/decode-edn-value
                           :seon.eval/home-requires requires)))))]
    (if agent-override? :agent :core)))

(defn ^:async eval-batch!
  "Evaluate one parsed batch in this agent's retained child compiler.

   Namespace setup targets the agent's OWN home namespace, never the derived
   `starting-ns` — an agent that `in-ns`'d into a host-bundled toolkit ns
   (e.g. `my.kb`) must not re-declare it with the home require vector (a
   self-require that rewires a namespace the agent does not own). The batch
   still evaluates in `starting-ns`; host-bundled nses need no declaration.
   A setup failure is recorded as a fault datom with the underlying eval
   error preserved and the batch proceeds — a broken home declaration never
   wedges the agent."
  {:malli/schema [:=> [:cat ::eval-batch-request :any] :map]}
  [{:seon.eval/keys [parsed starting-ns]
    turn-id :seon.agent.turn/id-of-turn
    run-id :seon.agent.run/id-of-run}
   prepare-program!]
  (let [database (::db/db (db/current-tx-context))
        {::execution/keys [compile-state program configuration]}
        (await (prepare-program!))
        agent-id (db/current-agent-id)
        setup-ns (if agent-id (home/home-ns agent-id) starting-ns)
        setup (await (eval/setup-agent-ns! configuration compile-state
                                           setup-ns agent-id))
        setup-fault
        (when (and (map? setup) (string? (:seon.error/message setup)))
          (await (setup-fault-kind! database agent-id)))
        _ (when (and (map? setup) (string? (:seon.error/message setup)))
            (error/with-configuration
             configuration
             #(error/record!
               {::error/raw (ex-info (:seon.error/message setup)
                                     (or (:seon.error/data setup) {}))
                ::error/fault setup-fault})))]
    (error/with-configuration
      configuration
      #(db/with-agent
         agent-id
         (fn []
           (db/with-tx-context
            {::db/db nil
             :seon.config/configuration configuration}
            (fn ^:async run-with-current-database! []
              (await
               (apply eval/eval-batch!
                      [compile-state parsed starting-ns agent-id turn-id run-id
                       {::eval/authored-sources
                        (eval/authored-sources
                         (::execution/namespace-rows program))
                        :seon.config/configuration configuration
                        ::db/db database}])))))))))

(def compiled-functions
  "Trusted functions directly reachable through this exact execution artifact."
  {'seon.execution.runtime/eval-batch!
   {::execution/compiled-function
    (fn [arguments _invoke-selected! _compile-state! prepare-program!]
      (apply eval-batch! (conj arguments prepare-program!)))
    ::execution/pin-database? true}})

(defn -main
  "Start the execution child from the complete runtime composition root."
  []
  (execution/-main compiled-functions))
