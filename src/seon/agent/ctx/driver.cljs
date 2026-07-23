(ns seon.agent.ctx.driver
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
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas :as ctx-canvas]
   [seon.agent.home :as home]
   [seon.agent.ctx.render-fns :as render-fns]
   [seon.ai :as ai]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.execution :as execution]
   [seon.error :as error]
   [seon.render :as render]
   [seon.render.canvas :as canvas]
   [seon.schema :as schema]
   [seon.web.reactive.transform :as reactive-transform]))

(schema/register! ::render-prompt-request
  [:map {:closed true}
   [:seon.agent/id :string]
   [:seon.agent.run/id {:optional true} :seon.agent.run/id]
   [:seon.db/db :seon.db/db]
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

(defn- selected-error-message
  [result]
  (or (get-in result [::execution/error :seon.error/message])
      "selected function failed"))

(defn- block-error-text
  [block result]
  (str "[" (name (:seon.agent.ctx/name block)) "] render failed: "
       (selected-error-message result)))

(defn- ai-value
  [value]
  (render/unwrap-response :seon.render/ai value))

(defn- interactive-hiccup
  "Rewrite handlers only for agent-authored dynamic renders and literal canvas.

   A dynamic render's function namespace is its ordinary Clojure authoring
   namespace. Literal canvas hiccup has no function symbol, so the rendering
   agent's canonical home namespace supplies the same lexical meaning for a
   bare handler symbol. Core/context hiccup is not rewritten."
  [id block hiccup]
  (let [renderer (:seon.render/html block)
        authoring-ns
        (cond
          (and (symbol? renderer) (error/agent-authored-sym? renderer))
          (symbol (namespace renderer))

          (and (vector? renderer)
               (= "canvas" (:seon.render.surface/selection block)))
          (home/home-ns id))]
    (if (and authoring-ns hiccup)
      (reactive-transform/transform-hiccup id authoring-ns hiccup)
      hiccup)))

(defn- html-value
  [id block result]
  (if (::execution/ok? result)
    (let [value (render/unwrap-response
                 :seon.render/html
                 (::execution/value result))]
      (cond
        (or (vector? value) (nil? value))
        (interactive-hiccup id block value)

        :else
        (canvas/error-card
         {:seon.error/message
          (str "expected hiccup from " (:seon.render/html block))})))
    (if (= "canvas" (:seon.render.surface/selection block))
      (:seon.render/hiccup
       (canvas/error-response
        (assoc (::execution/error result)
               :seon.render.canvas/content (:seon.render/html block))))
      (canvas/error-card
       {:seon.error/message
        (selected-error-message result)}))))

(defn- block-call
  [id entity configuration database block run-id]
  {::execution/function-symbol (:seon.render/ai block)
   ::execution/invoke-selected? true
   ::execution/arguments
   [(cond-> {:seon.agent/id id
             :seon.agent/entity entity
             :seon.config/configuration configuration
             ::db/db database
             :seon.render/node block}
      run-id (assoc :seon.agent.run/id run-id))]})

(defn ^:async ^:private resolve-blocks!
  [id entity configuration database blocks run-id invoke-selected!]
  (let [targets (->> blocks
                     (keep-indexed
                       (fn [index block]
                         (when (symbol? (:seon.render/ai block))
                           {:index index
                            :block block
                            :call (block-call id entity configuration database block
                                              run-id)})))
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
  [id entity configuration database value run-id invoke-selected!]
  (if-not (symbol? value)
    value
    (let [block {:seon.agent.ctx/name :prompt
                 :seon.agent.ctx/priority 0
                 :seon.render/ai value}
          result (first
                   (await (invoke-selected!
                           [(block-call id entity configuration database block
                                        run-id)])))]
      (if (::execution/ok? result)
        (ai-value (::execution/value result))
        (block-error-text block result)))))

(def ^:private prompt-pull-pattern
  (into
   '[:db/id
     :seon.agent.ctx/cache-breakpoint
     :seon.config/repl-mode
     :seon.render/ai
     {:seon.agent/ctx [*]}]
   (ai/agent-config-pull-pattern)))

(def agent-entity-read-profile
  "Datahike ceilings for complete prompt and agent-view entity pulls.

   W1 relocates this named execution-runtime policy into aero-backed database
   facts."
  {:datahike.resource/max-work 5000000
   :datahike.resource/max-results 65536
   :datahike.resource/max-result-weight (* 3 1024 1024)})

(def ^:private prompt-acquisition-members
  [(merge
    {::protocol/operation protocol/pull-operation
     ::protocol/selector prompt-pull-pattern
     ::protocol/entity-id nil}
    agent-entity-read-profile)
   (merge
    {::protocol/operation protocol/pull-operation
     ::protocol/selector '[*]
     ::protocol/entity-id [:seon.config/id config/cluster-config-id]}
    config/configuration-read-profile)
   (merge
    {::protocol/operation protocol/pull-operation
     ::protocol/selector (ai/config-pull-pattern)
     ::protocol/entity-id [:seon.ai/id "config"]}
    ai/configuration-read-profile)
   ;; Presence query, not pull: the optional tier attribute may not be
   ;; installed yet in a database whose agents all remain on the child tier.
   {::protocol/operation protocol/query-operation
    ::protocol/query-form
    '[:find ?socket-path .
      :in $ ?agent-id
      :where
      [?agent :seon.agent/id ?agent-id]
      [?agent :seon.execution.host/eval-socket-path ?socket-path]]
    ::protocol/arguments [nil]
    :datahike.resource/max-work 10000
    :datahike.resource/max-results 8
    :datahike.resource/max-result-weight 1024}])

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
  [{:seon.agent/keys [id]
    run-id :seon.agent.run/id
    profile :seon.agent.ctx/profile
    database ::db/db} invoke-selected!]
  (let [members (-> prompt-acquisition-members
                    (assoc-in [0 ::protocol/entity-id] [:seon.agent/id id])
                    (assoc-in [3 ::protocol/arguments] [id]))
        acquired (if database
                   (await (db/execute-many {::db/db database
                                            ::db/members members
                                          ::db/max-result-weight 8388608}))
                   {:seon.error/message
                    "Prompt rendering requires :seon.db/db."
                    :seon.error/kind :core-bug})
        [agent-member cluster-config-member ai-config-member tier-member]
        (::db/results acquired)
        required-members (cond-> [agent-member cluster-config-member
                                  ai-config-member]
                           (some? tier-member) (conj tier-member))
        member-failure?
        (not (every? #(true? (::protocol/success? %)) required-members))]
    (if member-failure?
      (prompt-acquisition-error acquired required-members)
      (let [entity (or (acquired-member agent-member) {})
            cluster-config-row
            (db/decode-edn-values
              (or (acquired-member cluster-config-member) {}))
            config-row (merge (or (acquired-member ai-config-member) {})
                              cluster-config-row)
            shared-system-text
            (or (:seon.config/system-text cluster-config-row)
                ctx/system-text-shared)
            host-tier? (and (some? tier-member)
                            (string? (acquired-member tier-member)))
            ;; Three-member acquisition is retained only for older direct test
            ;; stubs. Real prompt acquisition always carries the tier member
            ;; and therefore always uses the one tier-aware renderer.
            system-prompt (if (some? tier-member)
                            (ctx/render-system-text host-tier?
                                                    shared-system-text)
                            (or (:seon.config/system-text cluster-config-row)
                                ctx/system-text))
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
                     #(resolve-blocks! id entity cluster-config-row database blocks
                                       run-id invoke-selected!)))
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
                     #(resolve-blocks! id entity cluster-config-row database
                                       (vec derived) run-id invoke-selected!)))
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
                       #(resolve-whole-prompt! id entity cluster-config-row database
                                               whole-prompt run-id
                                               invoke-selected!))))]
        (error/with-configuration
          cluster-config-row
          #(assoc
             (ctx/rendered-context-from-entity
               (cond-> {:seon.agent/entity entity
                        :seon.agent.ctx/selected-blocks resolved-blocks}
                 (seq profile) (assoc :seon.agent.ctx/profile (vec profile))
                 (some? whole-prompt)
                 (assoc :seon.agent.ctx/whole-prompt resolved-whole-prompt)))
             :seon.ai/system-prompt system-prompt
             :seon.ai/config-resolution config-resolution
             :seon.config/repl-mode
             (let [agent-mode (:seon.config/repl-mode entity)]
               (if (contains? #{:batch :stream} agent-mode)
                 agent-mode
                 (or (:seon.config/repl-mode cluster-config-row) :batch)))
             :seon.eval/ns
             (or (::render-fns/current-ns namespace-value)
                 (symbol (str "my.agent." id)))))))))

(def agent-view-members
  [(merge
    {::protocol/operation protocol/pull-operation
     ::protocol/selector
     '[:db/id :seon.agent/id :seon.agent/terminated-at
       :seon.render.canvas/content
       {:seon.agent/run
        [:seon.agent.run/id :seon.agent.run/status :seon.agent.run/paused-at
         {:seon.agent.run/cause [:db/id :seon.agent.message/id]}]}
       {:seon.agent/ctx [*]}]
     ::protocol/entity-id nil}
    agent-entity-read-profile)
   {::protocol/operation protocol/query-operation
    ::protocol/query-form '[:find (count ?a) . :where [?a :seon.agent/id]]
    ::protocol/arguments []
    :datahike.resource/max-work 1000000
    ;; The scalar output is one number; Datahike charges the matching relation
    ;; nodes it retains while computing that aggregate.
    :datahike.resource/max-results 65536
    :datahike.resource/max-result-weight 1024}
   (merge
    {::protocol/operation protocol/pull-operation
     ::protocol/selector '[*]
     ::protocol/entity-id [:seon.config/id config/cluster-config-id]}
    config/configuration-read-profile)])

(def agent-view-fixed-dependencies
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
