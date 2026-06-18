(ns seon.agent.inspect
  "Agent self-introspection: 'what am I seeing right now?'

   Two verbs:
     - `ctx-preview` — the assembled AI-context the agent would receive
       on its next render: the exact prompt text, the per-section texts
       (left pane), and the per-section html twins (right pane). Both
       panes are derived from the ONE composer
       (`seon.ctx/assemble-context`), so they cannot diverge.
     - `handlers` — the live handler registry visible to the agent
       (core + per-agent).

   All map-in, map-out. Defaults `:seon.agent/id` to
   `(seon.db/current-agent-id)` so REPL calls from inside an agent
   scope work with no argument."
  (:require
    [seon.agent-view :as agent-view]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.handler :as handler]
    [seon.schema :as schema]))

(schema/register! :seon.agent.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

;; One rendered section of the assembled context — name + the exact
;; text that section contributed to the joined prompt. Consumed by the
;; inspector's left pane so static sections can collapse per-section
;; instead of re-showing the full static bulk on every view.
(schema/register! :seon.agent.inspect/section-text
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/text :string]])

(schema/register! :seon.agent.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/section-texts [:vector :seon.agent.inspect/section-text]]
   [:seon.render/section-html [:vector :seon.ctx/section-html]]
   [:seon.render/token-estimate :int]])

(schema/register! :seon.agent.inspect/handlers-response
  [:map [:seon.handler/list [:vector :map]]])

(defn- resolve-id
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               "seon.agent.inspect: no agent-id — pass :seon.agent/id or call inside (seon.db/with-agent id ...)."
               {:seon.agent.inspect/error :no-agent-id}))))

(defn ctx-preview
  "Return the assembled AI-context the agent would see on its next render
   — the EXACT bytes the LLM receives, via the ONE composer
   `seon.ctx/assemble-context` (the same fn the agent prompt path
   calls; divergence is impossible — the composer itself returns the
   per-section texts AND the per-section html twins, so the inspector's
   left pane (`:seon.render/section-texts`) and right pane
   (`:seon.render/section-html`) both mirror the same section set).
   Reads from the agent's filtered view so cross-agent tx are hidden."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        {:seon.render/keys [text token-estimate section-texts section-html]}
        (ctx/assemble-context {:seon.agent/id id :seon.db/db db})]
    {:seon.render/text            text
     :seon.render/section-texts   section-texts
     :seon.render/section-html    section-html
     :seon.render/token-estimate  token-estimate}))

(defn handlers
  "Return the live handler registry visible to the agent (core
   handlers + the agent's own, if any), sorted by priority desc."
  {:malli/schema [:=> [:cat :seon.agent.inspect/request] :seon.agent.inspect/handlers-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        hs (handler/query-handlers {:seon.agent/id id :seon.db/db db})]
    {:seon.handler/list hs}))
