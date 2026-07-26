(ns seon.agent.ctx.driver
  "Derive prompt and agent-view renders inside the pod.

   Trusted composition stays in-process over one immutable database value.
   Callers inject the selected-function door so agent-authored renderers remain
   guarded without moving the trusted prompt or page projection."
  (:require
   [my.blob]
   [my.canvas]
   [my.kb]
   [my.plan]
   [my.skills]
   [my.ui]
   [seon.agent.ctx :as ctx]
   [seon.agent.ctx.canvas :as ctx-canvas]
   [seon.agent.message :as message]
   [seon.agent.home :as home]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.derive :as derive]
   [seon.error :as error]
   [seon.render :as render]
   [seon.render.canvas :as canvas]
   [seon.render.surface :as surface]
   [seon.schema :as schema]
   [seon.ui.agent-view]
   [seon.web.reactive.transform :as reactive-transform]))

(schema/register! ::render-agent-view-request
  [:map {:closed true}
   [:seon.agent/id :string]
   [:seon.db/db :seon.db/db]])

(schema/register! ::invoke-selected! 'fn?)

(schema/register! ::render-agent-view-error
  [:map
   [:seon.error/message :string]
   [:seon.error/kind {:optional true} :keyword]
   [:seon.error/data {:optional true} :map]])

(defn- selected-error-message
  [result]
  (or (get-in result [:seon.execution/error :seon.error/message])
      "selected function failed"))

(defn interactive-hiccup
  "Rewrite handlers only for agent-authored dynamic renders and literal canvas.

   A dynamic render's function namespace is its ordinary Clojure authoring
   namespace. Literal canvas hiccup has no function symbol, so the rendering
   agent's canonical home namespace supplies the same lexical meaning for a
   bare handler symbol. Core/context hiccup is not rewritten."
  [id block hiccup]
  (let [renderer (:seon.render/html block)
        authoring-ns
        (cond
          (and (symbol? renderer)
               (error/agent-authored-sym?
                renderer (schema/current-projection)))
          (symbol (namespace renderer))

          (and (vector? renderer)
               (= "canvas" (:seon.render.surface/selection block)))
          (home/home-ns id))]
    (if (and authoring-ns hiccup)
      (reactive-transform/transform-hiccup id authoring-ns hiccup)
      hiccup)))

(defn html-value
  "Normalize one selected HTML invocation result for an agent surface."
  [id block result]
  (if (:seon.execution/ok? result)
    (let [value (render/unwrap-response
                 :seon.render/html
                 (:seon.execution/value result))]
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
        (assoc (:seon.execution/error result)
               :seon.render.canvas/content (:seon.render/html block))))
      (canvas/error-card
       {:seon.error/message
        (selected-error-message result)}))))

(def agent-entity-read-profile
  "Datahike ceilings for complete prompt and agent-view entity pulls.

   W1 relocates this named execution-runtime policy into aero-backed database
   facts."
  {:datahike.resource/max-work 5000000
   :datahike.resource/max-results 65536
   :datahike.resource/max-result-weight (* 3 1024 1024)})

(defn acquired-member
  "Return one successful database acquisition member's value."
  [member]
  (when (true? (::protocol/success? member))
    (::protocol/result member)))

(defn prompt-acquisition-error
  "Return the flat core-bug value for a failed prompt acquisition."
  [acquired members]
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
     ::protocol/entity-id config/cluster-config-lookup-ref}
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

(defn- query-member-value [member]
  (when (true? (::protocol/success? member))
    (:datahike.query/result member)))

(defn- html-slot [value]
  (when (some? value)
    (db/decode-edn-value :seon.render/html value)))

(defn- html-call [id entity configuration database block renderer]
  {:seon.execution/function-symbol renderer
   :seon.execution/invoke-selected? true
   :seon.execution/arguments
   [(cond-> {:seon.agent/id id
             :seon.agent/entity entity
             :seon.config/configuration configuration
             ::db/db database
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
  "Acquire one page projection and resolve its surfaces inside the pod."
  {:malli/schema
   [:=> [:cat ::render-agent-view-request ::invoke-selected!]
    [:or :seon.ui.agent-view/projection ::render-agent-view-error]]}
  [{:seon.agent/keys [id] database ::db/db} invoke-selected!]
  (let [members (assoc-in agent-view-members [0 ::protocol/entity-id]
                          [:seon.agent/id id])
        acquired (if database
                   (await
                    (db/execute-many
                     {::db/db database
                      ::db/members members
                      ::db/max-result-weight 3670016}))
                   {:seon.error/message
                    "Agent-view rendering requires :seon.db/db."
                    :seon.error/kind :core-bug})
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
                                (fn [recent-message]
                                  (and
                                   (= id
                                      (get-in
                                       recent-message
                                       [:seon.agent.message/from
                                        :seon.agent/id]))
                                   (some
                                    :seon.user/id
                                    (:seon.agent.message/to recent-message)))))
                               last
                               :seon.agent.message/content))
                    blocks
                    (->> (ctx/selected-agent-blocks entity nil)
                         (keep
                          (fn [block]
                            (when-let [renderer
                                       (html-slot
                                        (:seon.render/html block))]
                              (assoc block :seon.render/html renderer))))
                         vec)
                    canvas-block
                    (cond->
                     {:seon.agent.ctx/name :canvas
                      :seon.render.surface/selection "canvas"
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
                                database
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
                    remote-read-evidence
                    (let [evidence (keep ::db/read-evidence results)]
                      (cond
                        (some #{:all} evidence) :all
                        (seq evidence)
                        (vec (distinct (mapcat identity evidence)))
                        :else nil))
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
                         vec)
                    state (if (seq entity) (page-state entity) :unknown)]
                (cond->
                 {:seon.agent/id id
                  :seon.ui.agent-view/state state
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
                   (if (= :running state) 1 0)}}
                  remote-read-evidence
                  (assoc ::db/read-evidence remote-read-evidence))))))))))
