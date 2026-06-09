(ns seon.handlers.message
  "Renderers for `:seon.message` entities — the AI-text form (`render-ai`)
   and the HTML form (`render-html`). Both are the conventional shape
   `(fn [{:seon.db/db :seon.agent/id :seon.render/entity}] ...)`.

   Stored on individual message entities via:
     :seon.render/ai   'seon.handlers.message/render-ai
     :seon.render/html 'seon.handlers.message/render-html

   `seon.render/visible-entities` walks every `:seon.render/ai` entity
   in the agent's filtered view and the inspector calls these via
   symbol-lookup.

   Labels resolve by `:seon.message/from` REF KIND (from/to migration,
   unit 1.5): the user entity → `user`, the viewing agent itself →
   `assistant`, any other agent → `agent-<id>`. The `:seon.message/*`
   attr + kind schemas are owned by `seon.agent`.

   ## Ref resolution

   `d/pull '[*]` (how `seon.render/renderable-entities` materializes
   entities) returns refs as bare `{:db/id n}` maps — they carry NO
   `:seon.user/id`/`:seon.agent/id`, so `agent/message-label` on the
   raw pull said `unknown` for every message. `resolve-ref` re-pulls
   the identifying attrs through `:seon.db/db` before labeling.
   Pull-by-eid works on FilteredDB (lookup-ref `d/entity` does not —
   known datahike-cljs limitation)."
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent :as agent]))

(defn- resolve-ref
  "Materialize a `{:db/id n}` ref into its identifying attrs. Returns
   the input unchanged when it already carries an identity attr (older
   write paths stored the full map) or when `db` is nil. Never throws."
  [db ref]
  (cond
    (or (:seon.user/id ref) (:seon.agent/id ref)) ref
    (and db (:db/id ref))
    (try (d/pull db '[:seon.user/id :seon.agent/id] (:db/id ref))
         (catch :default _ ref))
    :else ref))

(defn- hh-mm-ss
  "`17:44:53` from a js/Date. Empty string when not a date."
  [at]
  (if (instance? js/Date at)
    (let [pad #(if (< % 10) (str "0" %) (str %))]
      (str (pad (.getHours at)) ":" (pad (.getMinutes at)) ":" (pad (.getSeconds at))))
    ""))

(defn render-ai
  "Format a single message as a single line of LLM-readable text.

   `[<from>] <content>`  — e.g. `[user] Define a fn that adds two numbers.`"
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity] :seon.agent/keys [id]}]
  (let [from (resolve-ref db (:seon.message/from entity))
        body (or (:seon.message/content entity) "")]
    {:seon.render/text
     (str "[" (agent/message-label from id) "] " body)}))

(defn render-html
  "Chat-bubble-style hiccup card — Phosphor Terminal palette.

   - Direction line: `user → assistant` (from-label colored by kind,
     to-labels resolved + joined), `17:44:53` timestamp, hops badge
     when > 0.
   - Content rendered as MARKDOWN via the inspector's `[data-markdown]`
     + marked.js mechanism. XSS-safe end-to-end: the attr value is
     HTML-escaped by seon.ui.html at serialization, and the inspector's
     `seonMarkdownAll` re-escapes the raw text before `marked.parse`,
     so agent-authored inline HTML renders as visible text, never DOM."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity] :seon.agent/keys [id]}]
  (let [from   (resolve-ref db (:seon.message/from entity))
        label  (agent/message-label from id)
        tos    (->> (:seon.message/to entity)
                    (map #(agent/message-label (resolve-ref db %) id))
                    distinct
                    vec)
        body   (or (:seon.message/content entity) "")
        at     (:seon.message/at entity)
        hops   (or (:seon.message/hops entity) 0)
        from-class (cond
                     (= label "user")      "text-amber-400"
                     (= label "assistant") "text-text-100"
                     :else                 "text-amber-300/80")]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class (str "text-xs font-mono font-semibold " from-class)}
        label]
       (when (seq tos)
         [:span {:class "text-xs font-mono text-text-500"}
          (str "→ " (str/join ", " tos))])
       (when (instance? js/Date at)
         [:span {:class "text-xs text-text-500"} (hh-mm-ss at)])
       (when (pos? hops)
         [:span {:class (str "text-xs font-mono text-amber-500/90 "
                             "border border-amber-700/50 rounded px-1")}
          (str "hops " hops)])]
      [:div {:class "markdown mt-0.5"
             :data-markdown (str/trim body)}]]}))
