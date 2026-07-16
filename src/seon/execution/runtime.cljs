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
   [seon.ai :as ai]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.derive :as derive]
   [seon.execution :as execution]
   [seon.eval :as eval]
   [seon.render.surface :as surface]
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
    ::protocol/selector (conj (ai/model-transport-pull-pattern)
                              :seon.config/system-text)
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
  "Acquire and invoke selected prompt blocks at the active coordinate."
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
            cluster-config-row (or (acquired-member cluster-config-member) {})
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
         :seon.ai/system-prompt system-prompt
         :seon.ai/config-resolution config-resolution)))))

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
   {::protocol/operation protocol/query-operation
    ::protocol/query-form '[:find (count ?e) . :where [?e ?a ?v]]
    ::protocol/arguments []
    :datahike.resource/max-work 5000000
    :datahike.resource/max-results 1
    :datahike.resource/max-result-weight 1024}])

(defn- query-member-value [member]
  (when (true? (::protocol/success? member))
    (:datahike.query/result member)))

(defn- html-slot [value]
  (when (some? value)
    (db/decode-edn-value :seon.render/html value)))

(defn- html-call [id entity block renderer]
  {::execution/function-symbol renderer
   ::execution/invoke-selected? true
   ::execution/arguments
   [{:seon.agent/id id
     :seon.agent/entity entity
     :seon.render/node block}]})

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
  (let [members (assoc-in agent-view-members [0 ::protocol/entity-id]
                          [:seon.agent/id id])
        acquired (await (db/execute-many {::db/members members
                                          ::db/max-result-weight 3670016}))
        [agent-member agent-count-member datom-count-member] (::db/results acquired)]
    (if-not (every? #(true? (::protocol/success? %)) (::db/results acquired))
      (prompt-acquisition-error acquired (::db/results acquired))
      (let [entity (or (acquired-member agent-member) {})
            blocks (->> (ctx/selected-agent-blocks entity nil)
                        (keep (fn [block]
                                (when-let [renderer (html-slot (:seon.render/html block))]
                                  (assoc block :seon.render/html renderer))))
                        vec)
            canvas-value (when-let [stored (:seon.render.canvas/content entity)]
                           (db/decode-edn-value
                            :seon.render.canvas/content stored))
            canvas-block {:seon.render.surface/selection "canvas"
                          :seon.render.surface/label "canvas"
                          :seon.render/html canvas-value}
            all-blocks (conj blocks canvas-block)
            targets (->> all-blocks
                         (keep-indexed (fn [index block]
                                         (when (symbol? (:seon.render/html block))
                                           {:index index
                                            :call (html-call id entity block
                                                             (:seon.render/html block))})))
                         vec)
            results (if (seq targets)
                      (await (invoke-selected! (mapv :call targets)))
                      [])
            result-by-index (into {} (map (juxt :index identity))
                                  (map (fn [target result]
                                         (assoc target :result result))
                                       targets results))
            surfaces
            (->> all-blocks
                 (keep-indexed
                  (fn [index block]
                    (let [renderer (:seon.render/html block)
                          hiccup (cond
                                   (vector? renderer) renderer
                                   (symbol? renderer)
                                   (surface/renderer-value
                                    block (:result (get result-by-index index)))
                                   (= "canvas" (:seon.render.surface/selection block))
                                   [:div {:class "p-2 text-text-500 text-xs"}
                                    "No canvas render yet."]
                                   :else nil)]
                      (surface/materialized block hiccup))))
                 vec)]
        {:seon.agent/id id
         :seon.ui.agent-view/state (if (seq entity) (page-state entity) :unknown)
         ::surface/surfaces surfaces
         :seon.ui.header/projection
         {:seon.ui.header/brand-name "seon"
          :seon.ui.header/agent-count (or (query-member-value agent-count-member) 0)
          :seon.ui.header/running-count (if (= :running (page-state entity)) 1 0)
          :seon.ui.header/datom-count (or (query-member-value datom-count-member) 0)}}))))

(defn ^:async eval-batch!
  "Evaluate one parsed batch in this agent's retained child compiler."
  {:malli/schema [:=> [:cat ::eval-batch-request :any] :map]}
  [{:seon.eval/keys [parsed starting-ns]
    turn-id :seon.agent.turn/id-of-turn
    run-id :seon.agent.run/id-of-run}
   prepare-program!]
  (let [{::execution/keys [compile-state program]}
        (await (prepare-program!))
        agent-id (db/current-agent-id)]
    (await
     (apply eval/eval-batch!
            [compile-state parsed starting-ns agent-id turn-id run-id
             (eval/authored-sources
              (::execution/namespace-rows program))]))))

(def compiled-functions
  "Trusted functions directly reachable through this exact execution artifact."
  {'seon.execution.runtime/render-prompt!
   {::execution/compiled-function
    (fn [arguments invoke-selected! _compile-state! _prepare-program!]
      (apply render-prompt! (conj arguments invoke-selected!)))
    ::execution/pin-coordinate? true}

   'seon.execution.runtime/eval-batch!
   {::execution/compiled-function
    (fn [arguments _invoke-selected! _compile-state! prepare-program!]
      (apply eval-batch! (conj arguments prepare-program!)))
    ::execution/pin-coordinate? false}

   'seon.execution.runtime/render-agent-view!
   {::execution/compiled-function
    (fn [arguments invoke-selected! _compile-state! _prepare-program!]
      (apply render-agent-view! (conj arguments invoke-selected!)))
    ::execution/pin-coordinate? true}})

(defn -main
  "Start the execution child from the complete runtime composition root."
  []
  (execution/-main compiled-functions))
