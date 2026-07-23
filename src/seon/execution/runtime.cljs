(ns seon.execution.runtime
  "Compose execution-child services and compiled prompt entrypoints.

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
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas :as ctx-canvas]
   [seon.agent.home :as home]
   [seon.agent.ctx.menu]
   [seon.agent.ctx.namespaces]
   [seon.agent.ctx.render-fns :as render-fns]
   [seon.agent.ctx.subagents]
   [seon.agent.ctx.transcript]
   [seon.agent.ctx.typeahead-steps]
   [seon.agent.ctx.warnings]
   [seon.agent.message :as message]
   [seon.agent.fs]
   [seon.agent.lifecycle]
   [seon.agent.search]
   [seon.agent.shell]
   [seon.agent.web]
   [seon.ai :as ai]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.derive :as derive]
   [seon.execution :as execution]
   [seon.error :as error]
   [seon.eval :as eval]
   [seon.render :as render]
   [seon.render.canvas :as canvas]
   [seon.render.surface :as surface]
   [seon.render.system]
   [seon.schema :as schema]
   [seon.web.reactive.transform :as reactive-transform]))


(defn- query-member-value [member]
  (when (true? (::protocol/success? member))
    (:datahike.query/result member)))

(defn- html-slot [value]
  (when (some? value)
    (db/decode-edn-value :seon.render/html value)))

(defn- html-call [id entity configuration block renderer]
  {::execution/function-symbol renderer
   ::execution/invoke-selected? true
   ::execution/arguments
   [(cond-> {:seon.agent/id id
             :seon.agent/entity entity
             :seon.config/configuration configuration
             :seon.render/node block}
      (contains? block :seon.render.chat/last-reply)
      (assoc :seon.render.chat/last-reply
             (:seon.render.chat/last-reply block)))]})

(defn- page-state [entity]
  (let [run (:seon.agent/run entity)
        open? (= :open (:seon.agent.run/status run))]
    (derive/state-from-primitives
     (cond-> {:seon.agent.run/open? open?}
       (:seon.agent/terminated-at entity)
       (assoc :seon.agent/terminated-at (:seon.agent/terminated-at entity))
       (and open? (:seon.agent.run/paused-at run))
       (assoc :seon.agent.run/paused-at (:seon.agent.run/paused-at run))))))

(defn ^:async render-agent-view!
  "Acquire one page projection and resolve its surfaces inside the child."
  [{:seon.agent/keys [id]} invoke-selected!]
  (let [database (or (::db/db (db/current-tx-context))
                     (await (db/db)))
        members (assoc-in agent-view-members [0 ::protocol/entity-id]
                          [:seon.agent/id id])]
    (if (:seon.error/message database)
      database
      (let [acquired (await
                      (db/execute-many
                       {::db/db database
                        ::db/members members
                        ::db/max-result-weight 3670016}))
            [agent-member agent-count-member config-member]
            (::db/results acquired)]
        (cond
          (:seon.error/message acquired)
          acquired

          (not= 3 (count (::db/results acquired)))
          (prompt-acquisition-error acquired (::db/results acquired))

          (not-every? #(true? (::protocol/success? %))
                      (::db/results acquired))
          (prompt-acquisition-error acquired (::db/results acquired))

          :else
          (let [entity (or (acquired-member agent-member) {})
                configuration
                (db/decode-edn-values
                 (or (acquired-member config-member) {}))
                canvas-acquisition
                (await (ctx-canvas/acquire-canvas! id entity database))]
            (if (:seon.error/message canvas-acquisition)
              canvas-acquisition
              (let [canvas-wired
                    (:seon.render.canvas/wired canvas-acquisition)
                    canvas-value
                    (:seon.render.canvas/value canvas-wired)
                    recent-messages
                    (when (= canvas/welcome-sym canvas-value)
                      (await
                       (message/recent
                        {::db/db database
                         :seon.agent/id id
                         :seon.agent.message/recent-limit 50})))]
                (if (:seon.error/message recent-messages)
                  recent-messages
                  (let [last-reply
                        (when (vector? recent-messages)
                          (some->> recent-messages
                                   (filter
                                    (fn [message]
                                      (and
                                       (= id
                                          (get-in
                                           message
                                           [:seon.agent.message/from
                                            :seon.agent/id]))
                                       (some
                                        :seon.user/id
                                        (:seon.agent.message/to message)))))
                                   last
                                   :seon.agent.message/content))
                        blocks
                        (->> (ctx/selected-agent-blocks entity nil)
                             (keep
                              (fn [block]
                                (when-let [renderer
                                           (html-slot
                                            (:seon.render/html block))]
                                  (assoc block
                                         :seon.render/html renderer))))
                             vec)
                        canvas-block
                        (cond->
                        {:seon.render.surface/selection "canvas"
                         :seon.render.surface/label "canvas"
                         :seon.render/html canvas-value
                         :seon.fn/read-attrs
                         (:seon.fn/read-attrs canvas-acquisition)
                         :seon.agent/entity
                         (:seon.render/entity canvas-acquisition)}
                          (some? last-reply)
                          (assoc :seon.render.chat/last-reply last-reply))
                        all-blocks (conj blocks canvas-block)
                        targets
                        (->> all-blocks
                             (keep-indexed
                              (fn [index block]
                                (when (symbol? (:seon.render/html block))
                                  {:index index
                                   :call
                                   (html-call
                                    id
                                    (or (:seon.agent/entity block) entity)
                                    configuration
                                    block
                                    (:seon.render/html block))})))
                             vec)
                        results
                        (if (seq targets)
                          (await
                           (error/with-configuration
                            configuration
                            #(invoke-selected! (mapv :call targets))))
                          [])
                        hiccup-by-index
                        (into {}
                              (map
                               (fn [{:keys [index]} result]
                                 [index
                                  (html-value
                                   id (nth all-blocks index) result)])
                               targets
                               results))
                        surfaces
                        (->> all-blocks
                             (keep-indexed
                              (fn [index block]
                                (let [renderer (:seon.render/html block)
                                      hiccup
                                      (cond
                                        (vector? renderer)
                                        (interactive-hiccup id block renderer)

                                        (symbol? renderer)
                                        (get hiccup-by-index index)

                                        :else
                                        nil)]
                                  (surface/materialized block hiccup))))
                             vec)]
                    {:seon.agent/id id
                     :seon.ui.agent-view/state
                     (if (seq entity) (page-state entity) :unknown)
                     ::surface/surfaces surfaces
                     :seon.web.datastar/dependencies
                     (into agent-view-fixed-dependencies
                           (mapcat ::surface/read-attrs)
                           surfaces)
                     :seon.ui.header/projection
                     {:seon.ui.header/brand-name "seon"
                      :seon.ui.header/agent-count
                      (or (query-member-value agent-count-member) 0)
                      :seon.ui.header/running-count
                      (if (= :running (page-state entity)) 1 0)}}))))))))))

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
    ::execution/pin-database? true}

   'seon.execution.runtime/render-agent-view!
   {::execution/compiled-function
    (fn [arguments invoke-selected! _compile-state! _prepare-program!]
      (apply render-agent-view! (conj arguments invoke-selected!)))
    ::execution/pin-database? true}})

(defn -main
  "Start the execution child from the complete runtime composition root."
  []
  (execution/-main compiled-functions))
