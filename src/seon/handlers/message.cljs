(ns seon.handlers.message
  "Renderers for `:seon.agent.message` entities — the AI-text form (`render-ai`)
   and the HTML form (`render-html`). Both are the conventional shape
   `(fn [{:seon.db/db :seon.agent/id :seon.render/entity}] ...)`.

   Stored on individual message entities via:
     :seon.render/ai   'seon.handlers.message/render-ai
     :seon.render/html 'seon.handlers.message/render-html

   The transcript section's html twin (`seon.agent.ctx/transcript-section-html`)
   resolves these per-message symbols (via `seon.render/render-entity-html`
   / `render-entity-ai`) to render the agent's messages as right-pane cards.

   Labels resolve by `:seon.agent.message/from` REF KIND (from/to migration,
   unit 1.5): the user entity → `user`, the viewing agent itself →
   `assistant`, any other agent → `agent-<id>`. The `:seon.agent.message/*`
   attr + kind schemas are owned by `seon.agent`.

   ## Ref resolution

   `d/pull '[*]` (how the transcript twin materializes message
   entities) returns refs as bare `{:db/id n}` maps — they carry NO
   `:seon.user/id`/`:seon.agent/id`, so `agent/message-label` on the
   raw pull said `unknown` for every message. `resolve-ref` re-pulls
   the identifying attrs through `:seon.db/db` before labeling.
   Pull-by-eid works on FilteredDB (lookup-ref `d/entity` does not —
   known datahike-cljs limitation)."
  (:require
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.db :as db]))

(defn- resolve-ref
  "Materialize a `{:db/id n}` ref into its identifying attrs. Returns
   the input unchanged when it already carries an identity attr (older
   write paths stored the full map) or when `db` is nil. Routes
   through guarded `seon.db/pull` (65dfc90): registered-but-never-
   installed attrs are filtered (→ `{}` for a fresh store, labeled
   `unknown`), typos throw legibly — the former bare try masked them."
  [db-val ref]
  (cond
    (or (:seon.user/id ref) (:seon.agent/id ref)) ref
    (and db-val (:db/id ref))
    (or (db/pull db-val '[:seon.user/id :seon.agent/id] (:db/id ref)) ref)
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
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:seon.db/keys [db] :seon.render/keys [node entity] :seon.agent/keys [id]}]
  (let [entity (or node entity)
        from (resolve-ref db (:seon.agent.message/from entity))
        body (or (:seon.agent.message/content entity) "")]
    (str "[" (agent/message-label from id) "] " body)))

(defn render-html
  "Chat-bubble-style hiccup card — Phosphor Terminal palette.

   - Direction line: `user → assistant` (from-label colored by kind,
     to-labels resolved + joined), `17:44:53` timestamp. The
     `:seon.agent.message/hops` counter stays in the DATA MODEL (loop
     prevention) but is NOT displayed — it confused users
     (user-requested, 2026-06-10).
   - Content rendered as MARKDOWN via the inspector's `[data-markdown]`
     + marked.js mechanism. XSS-safe end-to-end: the attr value is
     HTML-escaped by seon.ui.html at serialization, and the inspector's
     `seonMarkdownAll` re-escapes the raw text before `marked.parse`,
     so agent-authored inline HTML renders as visible text, never DOM."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render.live-tile/hiccup]]}
  [{:seon.db/keys [db] :seon.render/keys [node entity] :seon.agent/keys [id]}]
  (let [entity (or node entity)
        from   (resolve-ref db (:seon.agent.message/from entity))
        label  (agent/message-label from id)
        tos    (->> (:seon.agent.message/to entity)
                    (map #(agent/message-label (resolve-ref db %) id))
                    distinct
                    vec)
        body   (or (:seon.agent.message/content entity) "")
        at     (:seon.agent.message/at entity)
        user?      (= label "user")
        from-class (cond
                     user?                 "text-amber-400"
                     (= label "assistant") "text-text-100"
                     :else                 "text-amber-300/80")
        ;; Chat-first: user messages are right-aligned amber-tinted
        ;; bubbles; the agent (and other agents) answer from the left
        ;; in warm-black bubbles. Distinct at a glance from across a
        ;; demo room.
        bubble-class (cond
                       user?
                       "ml-auto bg-amber-950/40 border border-amber-800/40"
                       (= label "assistant")
                       "mr-auto bg-base-900 border border-base-800"
                       :else
                       "mr-auto bg-base-900 border border-amber-900/50")]
    [:div {:class "py-1 flex"}
     [:div {:class (str "max-w-[85%] min-w-40 rounded px-2.5 py-1.5 "
                        bubble-class)}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class (str "text-xs font-mono font-semibold " from-class)}
        label]
       (when (seq tos)
         [:span {:class "text-xs font-mono text-text-500"}
          (str "→ " (str/join ", " tos))])
       (when (instance? js/Date at)
         [:span {:class "text-xs text-text-500"} (hh-mm-ss at)])]
      [:div {:class "markdown mt-0.5"
             :data-markdown (str/trim body)}]]]))
