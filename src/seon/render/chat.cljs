(ns seon.render.chat
  "The conversation surface — chat bubbles for the consumer agent view
   (`/agent/<id>`, live-tiles PRD §1 Surface 2).

   ## The stream is DERIVED — nothing stored per-view

   [[conversation]] builds on `seon.render.default/recent-messages`
   (from = me OR to ∋ me, labeled by from-ref kind) and classifies
   each row into a bubble kind:

   - `::human` — the human's messages (label `\"user\"`). Right-aligned,
     amber-tinted: the human's own words.
   - `::agent` — this agent's replies (label `\"assistant\"`).
     Left-aligned, markdown-rendered: the agent speaking.
   - `::peer`  — another agent's messages (label `\"agent-<id>\"`).
     INLINE in the same stream, dimmer and smaller, labeled with the
     peer's id — the human watches their agent confer with peers
     without leaving the conversation.

   No acknowledgement state, no read markers, no per-view storage:
   the stream re-derives from the message log at every render
   (reactive-context doctrine — nothing stored that needs clearing).

   ## Styling — Phosphor, consumer-tuned

   Warm blacks, cream text, amber accents — but RELAXED from the
   debug-view density: real rounded bubbles, `text-sm` body,
   `px-4 py-2.5` padding. Monospace stays for ids and timestamps;
   prose rides the sans stack. Agent bubbles carry their content in a
   `data-markdown` attribute (rendered client-side by the page's
   marked.js pass) WITH the raw text as a child, so the bubble
   degrades to plain text when JS is off."
  (:require
    [seon.db :as db]
    [seon.render.default :as default]
    [seon.render.live-tile :as live-tile]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — the bubble vocabulary.
;; ============================================================

(schema/register! ::kind [:enum ::human ::agent ::peer])

;; The from-kind label exactly as `recent-messages` derives it:
;; "user" / "assistant" / "agent-<id>" / "unknown".
(schema/register! ::label :string)

(schema/register! ::content :string)

(schema/register! ::at :inst)

(schema/register! ::message
  [:map
   [::at      ::at]
   [::kind    ::kind]
   [::label   ::label]
   [::content ::content]])

(schema/register! ::limit [:int {:min 1 :max 500}])

;; `:seon.db/db` is registered (as `:any` — runtime handle) in
;; seon.render, which loads AFTER this ns can; the handle is specced
;; inline as `:any` here (same sanctioned third-party-boundary
;; exception as seon.render.live-tile's request shapes).
(schema/register! ::conversation-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db    {:optional true} :any]
   [::limit        {:optional true} ::limit]])

(schema/register! ::conversation-response
  [:map [::messages [:vector ::message]]])

(schema/register! ::stream-request
  [:map [::messages [:vector ::message]]])

;; One bubble's hiccup. PLATFORM LAW (2026-06-11): registered schema
;; forms are PURE DATA — a fn object's form serializes as a
;; symbol/#object and dies on the next form round-trip (boot index,
;; second boot) without sci. Reference the registered data shape.
(schema/register! ::bubble :seon.render.live-tile/hiccup)

;; ============================================================
;; The derived bubble query.
;; ============================================================

(def ^:private default-limit
  "How many conversation rows a render shows by default."
  50)

(defn message-kind
  "Classify a `recent-messages` from-kind label into a bubble kind:
   `\"user\"` → `::human`, `\"assistant\"` (this agent) → `::agent`,
   anything else (`\"agent-<id>\"`, `\"unknown\"`) → `::peer`."
  {:malli/schema [:=> [:catn [::label ::label]] ::kind]}
  [label]
  (cond
    (= label "user")      ::human
    (= label "assistant") ::agent
    :else                 ::peer))

(defn conversation
  "The agent's conversation as bubble messages, oldest-first —
   DERIVED from the message log via
   `seon.render.default/recent-messages` (from = me OR to ∋ me;
   nothing stored). Each message carries `::at` `::kind` `::label`
   `::content`. `::limit` bounds the tail (default 50)."
  {:malli/schema [:=> [:cat ::conversation-request] ::conversation-response]}
  [{:seon.agent/keys [id] :seon.db/keys [db] ::keys [limit]}]
  (let [db   (or db (some-> db/*conn* deref))
        rows (default/recent-messages db id (or limit default-limit))]
    {::messages
     (mapv (fn [[at label content]]
             {::at      at
              ::kind    (message-kind label)
              ::label   label
              ::content content})
           rows)}))

;; ============================================================
;; The bubble hiccup — one fn per stream, one per message.
;; ============================================================

(defn- hh-mm
  [at]
  (if (instance? js/Date at)
    (let [pad #(if (< % 10) (str "0" %) (str %))]
      (str (pad (.getHours at)) ":" (pad (.getMinutes at))))
    ""))

(defn bubble
  "Render ONE conversation message as a chat bubble:

   - `::human` — right-aligned, amber-tinted, plain text.
   - `::agent` — left-aligned, markdown-rendered (`data-markdown`
     attribute + raw-text child for no-JS degradation).
   - `::peer`  — inline, dimmer, smaller, labeled with the peer's id
     (`agent-<id>`) — visually subordinate to the human↔agent stream."
  {:malli/schema [:=> [:cat ::message] ::bubble]}
  [{::keys [at kind label content]}]
  (let [time (hh-mm at)]
    (case kind
      ::human
      [:div {:class "flex justify-end"}
       [:div {:class (str "seon-bubble max-w-[80%] rounded-2xl rounded-br-md "
                          "bg-amber-900/30 border border-amber-800/40 "
                          "px-4 py-2.5")}
        [:div {:class "text-sm text-amber-50 whitespace-pre-wrap"} content]
        [:div {:class "text-[10px] font-mono text-text-500 mt-1 text-right"}
         time]]]

      ::agent
      [:div {:class "flex justify-start"}
       [:div {:class (str "seon-bubble max-w-[85%] rounded-2xl rounded-bl-md "
                          "bg-base-850 border border-base-800 px-4 py-2.5")}
        [:div {:class "markdown text-sm text-text-100"
               :data-markdown content}
         content]
        [:div {:class "text-[10px] font-mono text-text-500 mt-1"} time]]]

      ;; ::peer — agent-to-agent, inline in the same stream.
      [:div {:class "flex justify-start pl-8"}
       [:div {:class (str "seon-bubble max-w-[70%] rounded-lg "
                          "border border-base-800/60 bg-base-900/40 "
                          "px-3 py-1.5")}
        [:div {:class "text-[10px] font-mono text-text-500 mb-0.5"} label]
        [:div {:class "text-xs text-text-400 whitespace-pre-wrap"} content]
        [:div {:class "text-[10px] font-mono text-text-500 mt-0.5"} time]]])))

(defn bubble-stream
  "Render the whole conversation as a bubble column — the left pane of
   the consumer agent view. Returns the standard
   `:seon.render/html-response`. Empty conversation renders an
   invitation, not a blank."
  {:malli/schema [:=> [:cat ::stream-request] :seon.render/html-response]}
  [{::keys [messages]}]
  {:seon.render/hiccup
   (if (seq messages)
     (into [:div {:class "flex flex-col gap-3"}]
           (map bubble)
           messages)
     [:div {:class "text-sm text-text-500 italic p-4"}
      "no messages yet — say hello below"])})
