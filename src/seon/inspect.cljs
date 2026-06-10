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
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.agent-view :as agent-view]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.handler :as handler]
    [seon.render :as render]
    [seon.schema :as schema]))

(schema/register! :seon.inspect/request
  [:map [:seon.agent/id {:optional true} :string]])

;; One rendered section of the assembled context — name + the exact
;; text that section contributed to the joined prompt. Consumed by the
;; inspector's left pane so static sections can collapse per-section
;; instead of re-showing the full static bulk on every view.
(schema/register! :seon.inspect/section-text
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/text :string]])

(schema/register! :seon.inspect/ctx-response
  [:map
   [:seon.render/text :string]
   [:seon.render/entities [:vector :any]]
   [:seon.render/section-texts [:vector :seon.inspect/section-text]]
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

(defn- per-section-texts
  "Render the agent's context PER SECTION — same layout source (stored
   `:seon.agent/ctx` override or `seon.agent/substrate-default-ctx`) and
   same section fns as `seon.agent/assemble-context`, so each entry is
   the exact text that section contributed to `joined-text`.

   SAFETY GUARD against divergence from the one composer: byte-equality
   with `joined-text` is IMPOSSIBLE — `transcript-section` embeds the
   render-time timestamp (verified live 2026-06-09), so two renders ms
   apart always differ by a few bytes. Instead the per-section join's
   LENGTH must land within 64 chars of `joined-text`'s. Timestamp
   drift moves a handful of bytes; a structural divergence (dropped
   section, changed join separator, composer logic change) shifts far
   more. On guard failure, fall back to ONE pseudo-section `:context`
   carrying the whole joined text — the honest single blob instead of
   a wrong split. (assemble-context itself can't return per-section
   text today — seon.agent is owned by another in-flight lane; fold
   this in there when it frees up.)"
  [db id joined-text]
  (let [stored   (sort-by :seon.ctx/priority
                          (:seon.agent/ctx
                            (db/pull {:seon.db/db db
                                      :seon.db/pull-pattern
                                      '[{:seon.agent/ctx
                                         [:seon.ctx/name :seon.ctx/priority
                                          :seon.ctx/fn]}]
                                      :seon.db/ref [:seon.agent/id id]})))
        sections (if (seq stored) stored (agent/substrate-default-ctx))
        base-in  {:seon.db/db db :seon.agent/id id}
        rendered (->> sections
                      (map (fn [section]
                             (let [f (seval/lookup-value (:seon.ctx/fn section))
                                   in (assoc base-in :seon.agent/ctx-entity section)]
                               {:seon.ctx/name    (:seon.ctx/name section)
                                :seon.render/text (if f (f in) (agent/pretty-ai section))})))
                      (remove (comp str/blank? :seon.render/text))
                      vec)]
    (if (<= (js/Math.abs (- (count joined-text)
                            (count (str/join "\n\n" (map :seon.render/text rendered)))))
            64)
      rendered
      [{:seon.ctx/name :context :seon.render/text joined-text}])))

(defn ctx-preview
  "Return the assembled AI-context the agent would see on its next render
   — the EXACT bytes the LLM receives, via the ONE composer
   `seon.agent/assemble-context` (the same fn the agent prompt path
   calls; divergence is impossible). `:seon.render/entities` is the
   ordered set of entities BEHIND that context (for drill-in), via
   `seon.render/visible-entities`. Reads from the agent's filtered view
   so cross-agent tx are hidden."
  {:malli/schema [:=> [:cat :seon.inspect/request] :seon.inspect/ctx-response]}
  [{:seon.agent/keys [id]}]
  (let [id (resolve-id id)
        {:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id id})
        {:seon.render/keys [text token-estimate]}
        (agent/assemble-context {:seon.agent/id id :seon.db/db db})
        {:seon.render/keys [entities]}
        (render/visible-entities {:seon.agent/id id :seon.db/db db})]
    {:seon.render/text            text
     :seon.render/entities        entities
     :seon.render/section-texts   (per-section-texts db id text)
     :seon.render/token-estimate  token-estimate}))

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
