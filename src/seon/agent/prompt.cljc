(ns seon.agent.prompt
  "Compose one frozen prompt from already-acquired ordinary values."
  (:require
   [seon.agent.ctx :as ctx]
   [seon.agent.home :as home]
   [seon.ai.core :as ai]
   [seon.config.resolve :as config.resolve]
   [seon.db :as db]
   [seon.schema :as schema]))

(schema/register! ::attempt-timeout-ms [:int {:min 1}])
(schema/register!
 ::agent-entity
 [:map
  [:seon.agent/id [:string {:min 1}]]])
(schema/register!
 ::resolved-block
 [:map
  [:seon.agent.ctx/name :keyword]
  [:seon.agent.ctx/priority :int]
  [:seon.render/ai {:optional true} :string]])
(schema/register! ::resolved-blocks [:vector ::resolved-block])
(schema/register!
 ::render-request
 [:map {:closed true}
  [::db/db :seon.db/db]
  [:seon.agent/entity ::agent-entity]
  [:seon.config/configuration :map]
  [::attempt-timeout-ms ::attempt-timeout-ms]
  [:seon.agent.ctx/selected-blocks ::resolved-blocks]
  [:seon.agent.ctx/whole-prompt
   {:optional true}
   :string]
  [:seon.agent.ctx/profile
   {:optional true}
   :seon.agent.ctx/profile]
  [:seon.eval/ns {:optional true} :symbol]])
(schema/register!
 ::rendered-prompt
 [:map
  [:seon.render/text :string]
  [:seon.agent.ctx/rendered-blocks :seon.agent.ctx/rendered-blocks]
  [:seon.ai/system-prompt :string]
  [:seon.ai/config-resolution :map]
  [::db/db :seon.db/db]
  [:seon.eval/ns :symbol]
  [:seon.ai/wire-stream? :boolean]
  [:seon.ai/reply-evaluation [:enum :first-form :batch]]])

(defn render
  "Compose the prompt and LLM projections from one frozen acquisition.

   The caller acquires every input from one immutable database value and
   resolves selected block functions before calling. This function performs
   no database read and invokes no renderer: it delegates literal context
   formatting to `seon.agent.ctx` and the established model, retry, and reply
   projections to their pure owners."
  {:malli/schema [:=> [:cat ::render-request] ::rendered-prompt]}
  [{database ::db/db
    entity :seon.agent/entity
    configuration :seon.config/configuration
    attempt-timeout-ms ::attempt-timeout-ms
    selected-blocks :seon.agent.ctx/selected-blocks
    whole-prompt :seon.agent.ctx/whole-prompt
    profile :seon.agent.ctx/profile
    current-ns :seon.eval/ns
    :as request}]
  (let [context-request
        (cond-> {:seon.agent/entity entity
                 :seon.agent.ctx/selected-blocks selected-blocks}
          (contains? request :seon.agent.ctx/whole-prompt)
          (assoc :seon.agent.ctx/whole-prompt whole-prompt)

          (seq profile)
          (assoc :seon.agent.ctx/profile profile))
        rendered (ctx/rendered-context-from-entity context-request)
        shared-system-text
        (or (:seon.config/system-text configuration)
            ctx/system-text-shared)]
    (merge
     rendered
     {:seon.ai/system-prompt
      (ctx/render-system-text shared-system-text)
      :seon.ai/config-resolution
      (ai/resolved-config-from-rows
       ai/shipped-defaults configuration entity attempt-timeout-ms)
      ::db/db database
      :seon.eval/ns
      (or current-ns (home/home-ns (:seon.agent/id entity)))}
     (config.resolve/llm-retry-configuration configuration)
     (ai/reply-configuration-from-rows configuration entity)
     (select-keys
      configuration
      [:seon.config.model-stream/partial-publish-settle-ms]))))
