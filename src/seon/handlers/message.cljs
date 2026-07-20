(ns seon.handlers.message
  "Render agent messages for model and human context.

   Stored on individual message entities via:
     :seon.render/ai   'seon.handlers.message/render-ai
     :seon.render/html 'seon.handlers.message/render-html

   The transcript section's html twin (`seon.agent.ctx/transcript-block-html`)
   resolves these per-message symbols (via `seon.render/render-entity-html`
   / `render-entity-ai`) to render the agent's messages as right-pane cards.

   Labels resolve by `:seon.agent.message/from` REF KIND (from/to migration,
   unit 1.5): the user entity → `user`, the viewing agent itself →
   `assistant`, any other agent → `agent-<id>`. The `:seon.agent.message/*`
   attr + kind schemas are owned by `seon.agent`.

   ## Ref resolution is acquisition-side

   These renderers are SYNC render-plane fns — pure projections of the
   already-acquired node; they perform no database read (`seon.db` is
   async; a mid-render read would put a Promise into the label). The
   INVOKING acquisition must nest the identity attrs on the message's
   from/to refs — `{:seon.agent.message/from [:db/id :seon.user/id
   :seon.agent/id]}` — as `seon.agent.ctx.transcript/message-selector`
   and `seon.agent.message/recent-pull-pattern` already do. A ref pulled
   without them labels `unknown`; fix the acquiring pull pattern, never
   this renderer."
  (:require
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.render :as render]))

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
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [node entity]
    :seon.agent/keys [id]}]
  (let [entity (or node entity)
        from (:seon.agent.message/from entity)
        body (or (:seon.agent.message/content entity) "")]
    (str "[" (agent/message-label from id) "] " body)))

(defn render-html
  "Chat-bubble-style hiccup card — Phosphor Terminal palette.

   - Direction line: `user → assistant` (from-label colored by kind,
     to-labels resolved + joined), `17:44:53` timestamp. The
     `:seon.agent.message/hops` counter stays in the DATA MODEL (loop
     prevention) but is NOT displayed — it confused users
     (user-requested, 2026-06-10).
   - Content rendered as MARKDOWN server-side via the typed
     `seon.render/block` renderer (`{:seon.render/markdown body}` →
     `seon.ui.markdown/md->hiccup`). XSS-safe end-to-end: every text node
     is HTML-escaped by seon.ui.html at serialization, so agent-authored
     inline HTML renders as visible text, never DOM. No client-side
     `data-markdown`/marked.js pass — the agent-view shim loads only
     datastar.js, so the markdown must be hiccup by the time it ships."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.render/keys [node entity]
    :seon.agent/keys [id] :as input}]
  (let [configuration (:seon.config/configuration input)
        entity (or node entity)
        from   (:seon.agent.message/from entity)
        label  (agent/message-label from id)
        tos    (->> (:seon.agent.message/to entity)
                    (map #(agent/message-label % id))
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
     [:div {:class (str "seon-bubble max-w-[78%] min-w-0 rounded px-2.5 py-1.5 "
                        bubble-class)}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class (str "text-xs font-mono font-semibold " from-class)}
        label]
       (when (seq tos)
         [:span {:class "text-xs font-mono text-text-500"}
          (str "→ " (str/join ", " tos))])
       (when (instance? js/Date at)
         [:span {:class "text-xs text-text-500"} (hh-mm-ss at)])]
      [:div {:class "markdown mt-0.5 min-w-0"}
       (render/block :html configuration
                     {:seon.render/markdown (str/trim body)})]]]))
