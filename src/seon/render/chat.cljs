(ns seon.render.chat
  "Render acquired messages as conversation bubbles and streams.

   Pure projections format eager message data as chat hiccup. Database
   acquisition, transcript selection, and feed publication remain upstream."
  (:require
    [seon.schema :as schema]
    [seon.ui.markdown :as md]))

;; ============================================================
;; Schemas — the bubble vocabulary.
;; ============================================================

(schema/register! ::kind [:enum ::human ::agent ::peer ::system])

;; The direction label supplied by the operation that acquired messages:
;; "user" / "assistant" / "agent-<id>" (incoming peer) /
;; "→ agent-<id>" (outgoing peer) / "unknown".
(schema/register! ::label :string)

(schema/register! ::content :string)

(schema/register! ::at :inst)

(schema/register! ::message
  [:map
   [::at      ::at]
   [::kind    ::kind]
   [::label   ::label]
   [::content ::content]])

(schema/register! ::stream-request
  [:map [::messages [:vector ::message]]])

;; Bubble fns return the registered hiccup shape
;; `:seon.render.canvas/hiccup` — referenced DIRECTLY in their
;; `:malli/schema` metadata (resolved at instrument time, after the
;; whole bundle loads. It is not re-aliased here because register!'s
;; compilability guard rejects forward references at load time.

;; ============================================================
;; Pure message rendering.
;; ============================================================

(defn message-kind
  "Classify an acquired message direction label into a bubble kind.

   `\"user\"` → `::human`, `\"assistant\"` (this agent → the human) →
   `::agent`, anything else (`\"agent-<id>\"` incoming peer,
   `\"→ agent-<id>\"` outgoing peer, `\"unknown\"`) → `::peer`."
  {:malli/schema [:=> [:catn [::label ::label]] ::kind]}
  [label]
  (cond
    (= label "user")      ::human
    (= label "assistant") ::agent
    :else                 ::peer))

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
  "Render ONE conversation message as a chat bubble.

   - `::human` — right-aligned, amber-tinted, markdown-rendered.
   - `::agent` — left-aligned, markdown-rendered.
   - `::peer`  — inline, dimmer, smaller, labeled with the peer's id
     (`agent-<id>`) — visually subordinate to the human↔agent stream.
   - `::system` — centered, amber-edged system line: a failed turn the
     human must see (the agent went idle mid-task; the next message
     resumes it).

   Human and agent content renders markdown → hiccup SERVER-SIDE
   (`seon.ui.markdown/md->hiccup` — symmetric, escaped-by-the-
   serializer; see the ns docstring)."
  {:malli/schema [:=> [:cat ::message] :seon.render.canvas/hiccup]}
  [{::keys [at kind label content]}]
  (let [time (hh-mm at)]
    (case kind
      ::human
      [:div {:class "flex justify-end"}
       [:div {:class (str "seon-bubble max-w-[80%] rounded-2xl rounded-br-md "
                          "bg-amber-900/30 border border-amber-800/40 "
                          "px-4 py-2.5")}
        (md/md->hiccup content {:wrap-class "markdown text-sm text-amber-50"})
        [:div {:class "text-[10px] font-mono text-text-500 mt-1 text-right"}
         time]]]

      ::agent
      [:div {:class "flex justify-start"}
       [:div {:class (str "seon-bubble max-w-[85%] rounded-2xl rounded-bl-md "
                          "bg-base-850 border border-base-800 px-4 py-2.5")}
        (md/md->hiccup content {:wrap-class "markdown text-sm text-text-100"})
        [:div {:class "text-[10px] font-mono text-text-500 mt-1"} time]]]

      ::system
      [:div {:class "flex justify-center"}
       [:div {:class (str "seon-bubble max-w-[85%] rounded-lg "
                          "border border-amber-900/50 bg-base-900/60 "
                          "px-3 py-1.5 text-center")}
        [:div {:class "text-[10px] font-mono text-amber-500/80 mb-0.5"} label]
        [:div {:class "text-xs text-amber-200/90 whitespace-pre-wrap"} content]
        [:div {:class "text-[10px] font-mono text-text-500 mt-0.5"} time]]]

      ;; ::peer — agent-to-agent, inline in the same stream.
      [:div {:class "flex justify-start pl-8"}
       [:div {:class (str "seon-bubble max-w-[70%] rounded-lg "
                          "border border-base-800/60 bg-base-900/40 "
                          "px-3 py-1.5")}
        [:div {:class "text-[10px] font-mono text-text-500 mb-0.5"} label]
        [:div {:class "text-xs text-text-400 whitespace-pre-wrap"} content]
        [:div {:class "text-[10px] font-mono text-text-500 mt-0.5"} time]]])))

(defn bubble-stream
  "Render the whole conversation as a bubble column.

   The left pane of
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
