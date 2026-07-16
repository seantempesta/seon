(ns seon.execution.runtime
  "Composition root and compiled prompt entrypoint for execution children."
  (:require
   [my.plan.internal]
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas]
   [seon.agent.ctx.menu]
   [seon.agent.ctx.namespaces]
   [seon.agent.ctx.render-fns :as render-fns]
   [seon.agent.ctx.subagents]
   [seon.agent.ctx.transcript]
   [seon.agent.ctx.typeahead-steps]
   [seon.agent.ctx.warnings]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.execution :as execution]
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

(defn- block-error-text
  [block result]
  (str "[" (name (:seon.agent.ctx/name block)) "] render failed: "
       (or (get-in result [::execution/error :seon.error/message])
           "selected function failed")))

(defn- ai-value
  [value]
  (if (and (map? value) (contains? value :seon.render/ai))
    (:seon.render/ai value)
    value))

(defn- block-call
  [id entity block]
  {::execution/function-symbol (:seon.render/ai block)
   ::execution/invoke-selected? true
   ::execution/arguments
   [{:seon.agent/id id
     :seon.agent/entity entity
     :seon.render/node block}]})

(defn ^:async ^:private resolve-blocks!
  [id entity blocks invoke-selected!]
  (let [targets (->> blocks
                     (keep-indexed
                       (fn [index block]
                         (when (symbol? (:seon.render/ai block))
                           {:index index
                            :block block
                            :call (block-call id entity block)})))
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

(defn- derived-blocks
  [stored values]
  (when-let [namespace-value
             (some #(when (and (map? %)
                               (contains? % ::render-fns/fn-rows))
                      %)
                   values)]
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
  [id entity value invoke-selected!]
  (if-not (symbol? value)
    value
    (let [block {:seon.agent.ctx/name :prompt
                 :seon.agent.ctx/priority 0
                 :seon.render/ai value}
          result (first
                   (await (invoke-selected! [(block-call id entity block)])))]
      (if (::execution/ok? result)
        (ai-value (::execution/value result))
        (block-error-text block result)))))

(def ^:private prompt-pull-pattern
  '[:db/id
    :seon.agent/id
    :seon.agent.ctx/cache-breakpoint
    :seon.render/ai
    {:seon.agent/ctx [*]}])

(def ^:private prompt-acquisition-members
  [{::protocol/operation protocol/pull-operation
    ::protocol/selector prompt-pull-pattern
    ::protocol/entity-id nil
    :datahike.resource/max-work 5000000
    :datahike.resource/max-results 65536
    :datahike.resource/max-result-weight 3145728}
   {::protocol/operation protocol/pull-operation
    ::protocol/selector [:seon.config/system-text]
    ::protocol/entity-id [:seon.config/id config/cluster-config-id]
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
  "Acquire and invoke selected prompt blocks at the active coordinate."
  {:malli/schema [:=> [:cat ::render-prompt-request :any]
                  [:or :seon.agent.ctx/rendered-context ::prompt-error]]}
  [{:seon.agent/keys [id] profile :seon.agent.ctx/profile} invoke-selected!]
  (let [members (assoc-in prompt-acquisition-members
                          [0 ::protocol/entity-id]
                          [:seon.agent/id id])
        acquired (await (db/execute-many {::db/members members
                                          ::db/max-result-weight 3670016}))
        [agent-member config-member] (::db/results acquired)
        member-failure? (not (and (true? (::protocol/success? agent-member))
                                  (true? (::protocol/success? config-member))))]
    (if member-failure?
      (prompt-acquisition-error acquired [agent-member config-member])
      (let [entity (or (acquired-member agent-member) {})
            system-prompt (or (:seon.config/system-text
                               (acquired-member config-member))
                              ctx/system-text)
            whole-prompt (when-not (seq profile)
                           (some->> (:seon.render/ai entity)
                                    (db/decode-edn-value :seon.render/ai)))
            blocks (if (some? whole-prompt)
                     []
                     (ctx/selected-agent-blocks entity profile))
            stored-resolution (await (resolve-blocks! id entity blocks
                                                      invoke-selected!))
            stored-blocks (:seon.execution.runtime/blocks stored-resolution)
            derived (when (and (not (seq profile)) (nil? whole-prompt))
                      (derived-blocks blocks
                                      (:seon.execution.runtime/values
                                        stored-resolution)))
            derived-resolution (await (resolve-blocks! id entity (vec derived)
                                                       invoke-selected!))
            resolved-blocks
            (->> (concat stored-blocks
                         (:seon.execution.runtime/blocks derived-resolution))
                 (sort-by (juxt :seon.agent.ctx/priority
                                (comp str :seon.agent.ctx/name)))
                 vec)
            resolved-whole-prompt
            (when (some? whole-prompt)
              (await (resolve-whole-prompt! id entity whole-prompt
                                            invoke-selected!)))]
        (assoc
         (ctx/rendered-context-from-entity
          (cond-> {:seon.agent/entity entity
                   :seon.agent.ctx/selected-blocks resolved-blocks}
            (seq profile) (assoc :seon.agent.ctx/profile (vec profile))
            (some? whole-prompt)
            (assoc :seon.agent.ctx/whole-prompt resolved-whole-prompt)))
         :seon.ai/system-prompt system-prompt)))))

(def compiled-functions
  "Trusted functions directly reachable through this exact execution artifact."
  {'seon.execution.runtime/render-prompt!
   (fn [arguments invoke-selected! _compile-state!]
     (apply render-prompt! (conj arguments invoke-selected!)))})

(defn -main
  "Start the execution child from the complete runtime composition root."
  []
  (execution/-main compiled-functions))
