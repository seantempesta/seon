(ns seon.inspect
  "Agent self-introspection: 'what am I seeing right now?'

   Three verbs:
     - `ctx-preview` — the assembled AI-context string an agent would
       receive on its next render.
     - `visible-entities` — the list of entity maps the agent sees, in
       render order. Identical set to what `ctx-preview` walked.
     - `handlers` — the live handler registry visible to the agent
       (substrate + per-agent).

   All map-in, map-out. Defaults `:seon.agent/id` to
   `(seon.db/current-agent-id)` so REPL calls from inside an agent
   scope work with no argument."
  (:require
    [seon.agent-view :as agent-view]
    [seon.db :as db]
    [seon.handler :as handler]
    [seon.render :as render]
    [seon.schema :as schema]))

(schema/register! :seon.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

(schema/register! :seon.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/entities [:vector :any]]
   [:seon.render/token-estimate :int]])

(schema/register! :seon.inspect/entities-response
  [:map [:seon.render/entities [:vector :any]]])

(schema/register! :seon.inspect/handlers-response
  [:map [:seon.handler/list [:vector :map]]])

(defn- resolve-id
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               "seon.inspect: no agent-id — pass :seon.agent/id or call inside (seon.db/with-agent id ...)."
               {:seon.inspect/error :no-agent-id}))))

(defn ctx-preview
  "Return the assembled AI-context string the agent would see on its
   next render. Reads from the agent's filtered view so cross-agent tx
   are hidden."
  {:malli/schema [:=> [:cat :seon.inspect/request] :seon.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})]
    (render/assemble-ai-context {:seon.agent/id id :seon.db/db db})))

(defn visible-entities
  "Return the entities the agent currently sees in its AI context, in
   render order. Subset of `ctx-preview` (same entities, no rendered
   strings)."
  {:malli/schema [:=> [:cat :seon.inspect/request] :seon.inspect/entities-response]}
  [{:seon.agent/keys [id]}]
  (let [{:seon.render/keys [entities]} (ctx-preview {:seon.agent/id (resolve-id id)})]
    {:seon.render/entities entities}))

(defn handlers
  "Return the live handler registry visible to the agent (substrate
   handlers + the agent's own, if any), sorted by priority desc."
  {:malli/schema [:=> [:cat :seon.inspect/request] :seon.inspect/handlers-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        hs (handler/query-handlers {:seon.agent/id id :seon.db/db db})]
    {:seon.handler/list hs}))
