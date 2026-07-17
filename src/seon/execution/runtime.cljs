(ns seon.execution.runtime
  "Composition root and compiled prompt entrypoint for execution children."
  (:require
   [my.plan.internal]
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas :as ctx-canvas]
   [seon.agent.ctx.menu]
   [seon.agent.ctx.namespaces]
   [seon.agent.ctx.render-fns :as render-fns]
   [seon.agent.ctx.subagents]
   [seon.agent.ctx.transcript]
   [seon.agent.ctx.typeahead-steps]
   [seon.agent.ctx.warnings]
   [seon.agent.message :as message]
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
   [seon.schema :as schema]))

(schema/register! ::render-prompt-request
  [:map {:closed true}
   [:seon.agent/id :string]
   [:seon.agent.ctx/profile
    {:optional true}
    :seon.agent.ctx/profile]])

(schema/register! ::prompt-error
  [:map
   [:seon.error/message :string]
   [:seon.error/kind :keyword]
   [:seon.error/data {:optional true} :map]])

(schema/register! ::eval-batch-request
  [:map {:closed true}
   [:seon.eval/parsed [:vector :map]]
   [:seon.eval/starting-ns :symbol]
   [:seon.agent.turn/id-of-turn :string]
   [:seon.agent.run/id-of-run {:optional true} :string]])

(schema/register! ::render-agent-view-request
  [:map {:closed true} [:seon.agent/id :string]])

(defn- block-error-text
  [block result]
  (str "[" (name (:seon.agent.ctx/name block)) "] render failed: "
       (or (get-in result [::execution/error :seon.error/message])
           "selected function failed")))

(defn- ai-value
  [value]
  (render/unwrap-response :seon.render/ai value))

(defn- html-value
  [block result]
  (if (::execution/ok? result)
    (let [value (render/unwrap-response
                 :seon.render/html
                 (::execution/value result))]
      (cond
        (or (vector? value) (nil? value))
        value

        :else
        (canvas/error-card
         {:seon.error/message
          (str "expected hiccup from " (:seon.render/html block))})))
    (canvas/error-card
     {:seon.error/message
      (or (get-in result [::execution/error :seon.error/message])
          "selected function failed")})))

(defn- block-call
  [id entity configuration block]
  {::execution/function-symbol (:seon.render/ai block)
   ::execution/invoke-selected? true
   ::execution/arguments
   [{:seon.agent/id id
     :seon.agent/entity entity
     :seon.config/configuration configuration
     :seon.render/node block}]})

(defn ^:async ^:private resolve-blocks!
  [id entity configuration blocks invoke-selected!]
  (let [targets (->> blocks
                     (keep-indexed
                       (fn [index block]
                         (when (symbol? (:seon.render/ai block))
                           {:index index
                            :block block
                            :call (block-call id entity configuration block)})))
                     vec)]
    (if (empty? targets)
      {:seon.execution.runtime/blocks blocks
       :seon.execution.runtime/values []}
      (let [results (await (invoke-selected! (mapv :call targets)))]
        {:seon.execution.runtime/blocks
         (reduce
           (fn [resolved [{:keys [index block]} result]]
             (assoc-in resolved [index :seon.render/ai]
                       (if (::execution/ok? result)
                         (ai-value (::execution/value result))
                         (block-error-text block result))))
           blocks
           (map vector targets results))
         :seon.execution.runtime/values
         (mapv #(when (::execution/ok? %) (::execution/value %)) results)}))))

(defn- namespace-value [values]
  (some #(when (and (map? %)
                    (contains? % ::render-fns/fn-rows))
           %)
        values))

(defn- derived-blocks
  [stored values]
  (when-let [namespace-value (namespace-value values)]
    (let [stored-pins (into #{}
                            (comp (mapcat (juxt :seon.render/ai
                                                :seon.render/html))
                                  (filter symbol?))
                            stored)
          canvas-pins (into #{} (mapcat #(if (map? %)
                                           (or (::render-fns/pinned-syms %) #{})
                                           #{}))
                            values)]
      (render-fns/derived-blocks
        {::render-fns/current-ns (::render-fns/current-ns namespace-value)
         ::render-fns/fn-rows (::render-fns/fn-rows namespace-value)
         ::render-fns/pinned-syms (into stored-pins canvas-pins)}))))

(defn ^:async ^:private resolve-whole-prompt!
  [id entity configuration value invoke-selected!]
  (if-not (symbol? value)
    value
    (let [block {:seon.agent.ctx/name :prompt
                 :seon.agent.ctx/priority 0
                 :seon.render/ai value}
          result (first
                   (await (invoke-selected!
                           [(block-call id entity configuration block)])))]
      (if (::execution/ok? result)
        (ai-value (::execution/value result))
        (block-error-text block result)))))

(def ^:private prompt-pull-pattern
  (into
   '[:db/id
     :seon.agent.ctx/cache-breakpoint
     :seon.render/ai
     {:seon.agent/ctx [*]}]
   (ai/agent-config-pull-pattern)))

(def ^:private prompt-acquisition-members
  [{::protocol/operation protocol/pull-operation
    ::protocol/selector prompt-pull-pattern
    ::protocol/entity-id nil
    :datahike.resource/max-work 5000000
    :datahike.resource/max-results 65536
   :datahike.resource/max-result-weight 3145728}
   {::protocol/operation protocol/pull-operation
    ::protocol/selector '[*]
    ::protocol/entity-id [:seon.config/id config/cluster-config-id]
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 1
    :datahike.resource/max-result-weight 65536}
   {::protocol/operation protocol/pull-operation
    ::protocol/selector (ai/config-pull-pattern)
    ::protocol/entity-id [:seon.ai/id "config"]
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 1
    :datahike.resource/max-result-weight 65536}])

(defn- acquired-member [member]
  (when (true? (::protocol/success? member))
    (::protocol/result member)))

(defn- prompt-acquisition-error [acquired members]
  (let [error
        (or (when (and (map? acquired)
                       (string? (:seon.error/message acquired)))
              acquired)
            (some (fn [member]
                    (let [failure (::protocol/error member)]
                      (cond
                        (and (map? failure)
                             (string? (:seon.error/message failure)))
                        failure

                        (string? failure)
                        {:seon.error/message failure}

                        :else nil)))
                  members)
            {:seon.error/message "Prompt database acquisition failed."
             :seon.error/data {::db/results (::db/results acquired)}})]
    (cond-> error
      (not (keyword? (:seon.error/kind error)))
      (assoc :seon.error/kind :core-bug))))

(defn ^:async render-prompt!
  "Acquire and invoke selected prompt blocks at the active database value."
  {:malli/schema [:=> [:cat ::render-prompt-request :any]
                  [:or :seon.agent.ctx/rendered-context ::prompt-error]]}
  [{:seon.agent/keys [id] profile :seon.agent.ctx/profile} invoke-selected!]
  (let [members (assoc-in prompt-acquisition-members
                          [0 ::protocol/entity-id]
                          [:seon.agent/id id])
        acquired (await (db/execute-many {::db/members members
                                          ::db/max-result-weight 3670016}))
        [agent-member cluster-config-member ai-config-member]
        (::db/results acquired)
        member-failure?
        (not (every? #(true? (::protocol/success? %))
                     [agent-member cluster-config-member ai-config-member]))]
    (if member-failure?
      (prompt-acquisition-error acquired
                                [agent-member cluster-config-member
                                 ai-config-member])
      (let [entity (or (acquired-member agent-member) {})
            cluster-config-row
            (db/decode-edn-values
              (or (acquired-member cluster-config-member) {}))
            config-row (merge (or (acquired-member ai-config-member) {})
                              cluster-config-row)
            system-prompt (or (:seon.config/system-text cluster-config-row)
                              ctx/system-text)
            config-resolution (ai/resolved-config-from-rows config-row entity)
            whole-prompt (when-not (seq profile)
                           (some->> (:seon.render/ai entity)
                                    (db/decode-edn-value :seon.render/ai)))
            blocks (if (some? whole-prompt)
                     []
                     (ctx/selected-agent-blocks entity profile))
            stored-resolution
            (await (error/with-configuration
                     cluster-config-row
                     #(resolve-blocks! id entity cluster-config-row blocks
                                       invoke-selected!)))
            namespace-value
            (namespace-value (:seon.execution.runtime/values
                              stored-resolution))
            stored-blocks (:seon.execution.runtime/blocks stored-resolution)
            derived (when (and (not (seq profile)) (nil? whole-prompt))
                      (derived-blocks blocks
                                      (:seon.execution.runtime/values
                                        stored-resolution)))
            derived-resolution
            (await (error/with-configuration
                     cluster-config-row
                     #(resolve-blocks! id entity cluster-config-row
                                       (vec derived) invoke-selected!)))
            resolved-blocks
            (->> (concat stored-blocks
                         (:seon.execution.runtime/blocks derived-resolution))
                 (sort-by (juxt :seon.agent.ctx/priority
                                (comp str :seon.agent.ctx/name)))
                 vec)
            resolved-whole-prompt
            (when (some? whole-prompt)
              (await (error/with-configuration
                       cluster-config-row
                       #(resolve-whole-prompt! id entity cluster-config-row
                                               whole-prompt
                                               invoke-selected!))))]
        (error/with-configuration
          cluster-config-row
          #(assoc
             (ctx/rendered-context-from-entity
               (cond-> {:seon.agent/entity entity
                        :seon.config/configuration cluster-config-row
                        :seon.agent.ctx/selected-blocks resolved-blocks}
                 (seq profile) (assoc :seon.agent.ctx/profile (vec profile))
                 (some? whole-prompt)
                 (assoc :seon.agent.ctx/whole-prompt resolved-whole-prompt)))
             :seon.ai/system-prompt system-prompt
             :seon.ai/config-resolution config-resolution
             :seon.config/repl-mode
             (or (:seon.config/repl-mode cluster-config-row) :batch)
             :seon.eval/ns
             (or (::render-fns/current-ns namespace-value)
                 (keyword (str "my.agent." id)))))))))

(def ^:private agent-view-members
  [{::protocol/operation protocol/pull-operation
    ::protocol/selector
    '[:db/id :seon.agent/id :seon.agent/terminated-at
      :seon.render.canvas/content
      {:seon.agent/run [:seon.agent.run/status :seon.agent.run/paused-at]}
      {:seon.agent/ctx [*]}]
    ::protocol/entity-id nil
    :datahike.resource/max-work 5000000
    :datahike.resource/max-results 65536
    :datahike.resource/max-result-weight 3145728}
   {::protocol/operation protocol/query-operation
    ::protocol/query-form '[:find (count ?a) . :where [?a :seon.agent/id]]
    ::protocol/arguments []
    :datahike.resource/max-work 1000000
    :datahike.resource/max-results 1
    :datahike.resource/max-result-weight 1024}
   {::protocol/operation protocol/pull-operation
    ::protocol/selector '[*]
    ::protocol/entity-id [:seon.config/id config/cluster-config-id]
    :datahike.resource/max-work 100000
    :datahike.resource/max-results 1
    :datahike.resource/max-result-weight 65536}])

(def ^:private agent-view-fixed-dependencies
  #{:seon.agent/id
    :seon.agent/terminated-at
    :seon.agent/run
    :seon.agent/ctx
    :seon.agent.run/status
    :seon.agent.run/paused-at
    :seon.agent.ctx/name
    :seon.agent.ctx/priority
    :seon.render/html
    :seon.render.canvas/content
    :seon.render.surface/selection
    :seon.render.surface/label
    :seon.render.surface/touch
    :seon.render.surface/focus-touch
    :seon.fn/read-attrs})

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
                          :seon.render/entity
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
                                    (or (:seon.render/entity block) entity)
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
                                   (nth all-blocks index)
                                   result)])
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
                                        renderer

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

(defn ^:async eval-batch!
  "Evaluate one parsed batch in this agent's retained child compiler."
  {:malli/schema [:=> [:cat ::eval-batch-request :any] :map]}
  [{:seon.eval/keys [parsed starting-ns]
    turn-id :seon.agent.turn/id-of-turn
    run-id :seon.agent.run/id-of-run}
   prepare-program!]
  (let [database (::db/db (db/current-tx-context))
        {::execution/keys [compile-state program configuration]}
        (await (prepare-program!))
        agent-id (db/current-agent-id)]
    (error/with-configuration
      configuration
      #(db/with-tx-context
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
                     ::db/db database}])))))))

(def compiled-functions
  "Trusted functions directly reachable through this exact execution artifact."
  {'seon.execution.runtime/render-prompt!
   {::execution/compiled-function
    (fn [arguments invoke-selected! _compile-state! _prepare-program!]
      (apply render-prompt! (conj arguments invoke-selected!)))
    ::execution/pin-database? true}

   'seon.execution.runtime/eval-batch!
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
