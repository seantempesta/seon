(ns seon.render.chat
  "The conversation surface — chat bubbles for the consumer agent view
   (`/agent/<id>`, canvas PRD §1 Surface 2).

   ## The stream is DERIVED — nothing stored per-view

   [[conversation]] builds on `seon.render.default/recent-messages`
   (from = me OR to ∋ me, labeled by DIRECTION — from-ref kind ×
   to-ref kinds) and classifies each row into a bubble kind:

   - `::human` — the human's messages (label `\"user\"`). Right-aligned,
     amber-tinted: the human's own words.
   - `::agent` — this agent's REPLIES to the human (label
     `\"assistant\"` — from = me AND to ∋ the user). Left-aligned,
     markdown-rendered: the agent speaking.
   - `::peer`  — peer traffic, both directions, INLINE in the same
     stream, dimmer and smaller, direction-labeled: incoming carries
     the sender's id (`\"agent-<id>\"`), outgoing carries the target
     (`\"→ agent-<id>\"`) — the human watches their agent confer with
     peers without leaving the conversation.

   - `::system` — turn-level failures (a turn died and the wake
     ended), rendered as a centered system-styled line so the human is
     never left staring at a silently idle agent. DERIVED from the turn
     log: the turn records `:seon.agent.turn/status :error`; the stream
     synthesizes the line from that status — no notification row is
     ever stored.

   No acknowledgement state, no read markers, no per-view storage:
   the stream re-derives from the message log at every render
   (reactive-context doctrine — nothing stored that needs clearing).

   ## Styling — Phosphor, consumer-tuned

   Warm blacks, cream text, amber accents — but RELAXED from the
   debug-view density: real rounded bubbles, `text-sm` body,
   `px-4 py-2.5` padding. Monospace stays for ids and timestamps;
   prose rides the sans stack.

   ## Markdown — rendered SERVER-SIDE (chat-surface task #29, a21)

   Human and agent bubble content renders markdown → hiccup at render
   time via `seon.ui.markdown/md->hiccup` (bold/italic/lists/inline
   code/fences/headings/links) — both directions, symmetric. The
   hiccup serializer escapes ALL text nodes, so agent-authored raw
   HTML (`<script>…`) degrades to visible text, never markup; link
   hrefs are scheme-guarded and carry rel=\"nofollow noopener\"
   target=_blank inside md->hiccup. Server-side means curl sees the
   `<strong>` — no JS pass required (the debug view's `data-markdown`
   marked.js pass still serves the transcript fragments, untouched)."
  (:require
    [seon.db :as db]
    [seon.render.default :as default]
    [seon.schema :as schema]
    [seon.ui.markdown :as md]))

;; ============================================================
;; Schemas — the bubble vocabulary.
;; ============================================================

(schema/register! ::kind [:enum ::human ::agent ::peer ::system])

;; The direction label exactly as `recent-messages` derives it:
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

(schema/register! ::limit [:int {:min 1 :max 500}])

;; `:seon.db/db` is registered (as `:any` — runtime handle) in
;; seon.render, which loads AFTER this ns can; the handle is specced
;; inline as `:any` here (same sanctioned third-party-boundary
;; exception as seon.render.canvas's request shapes).
(schema/register! ::conversation-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db    {:optional true} :any]
   [::limit        {:optional true} ::limit]])

(schema/register! ::conversation-response
  [:map [::messages [:vector ::message]]])

(schema/register! ::stream-request
  [:map [::messages [:vector ::message]]])

;; Bubble fns return the registered hiccup shape
;; `:seon.render.canvas/hiccup` — referenced DIRECTLY in their
;; `:malli/schema` metadata (resolved at instrument time, after the
;; whole bundle loads — same forward-metadata-reference pattern as
;; seon.render.default's `:seon.render/ai-response`). NOT re-aliased
;; via register! here: this ns now loads BEFORE seon.render.canvas
;; (canvas requires [[last-reply]] for its welcome compact block,
;; canvas U3), and register!'s compilability guard rejects forward
;; references at load time.

;; ============================================================
;; The derived bubble query.
;; ============================================================

(def ^:private default-limit
  "How many conversation rows a render shows by default."
  50)

(defn message-kind
  "Classify a `recent-messages` direction label into a bubble kind.

   `\"user\"` → `::human`, `\"assistant\"` (this agent → the human) →
   `::agent`, anything else (`\"agent-<id>\"` incoming peer,
   `\"→ agent-<id>\"` outgoing peer, `\"unknown\"`) → `::peer`."
  {:malli/schema [:=> [:catn [::label ::label]] ::kind]}
  [label]
  (cond
    (= label "user")      ::human
    (= label "assistant") ::agent
    :else                 ::peer))

(defn- provider-failure-rows
  "DERIVED `[at]` rows for this agent's turns that died:
   `:seon.agent.turn/status :error`. The turn log records the status;
   nothing else is stored per-view. The turn carries no error message
   of its own, so the human-facing line is synthesized downstream."
  [db id]
  (db/query
    {:seon.db/db db
     :seon.db/query
     '[:find ?at
       :in $ ?id
       :where
       [?me :seon.agent/id ?id]
       [?r :seon.agent.run/agent ?me]
       [?t :seon.agent.turn/run ?r]
       [?t :seon.agent.turn/status :error]
       [?t :seon.agent.turn/at ?at]]
     :seon.db/args [id]}))

(defn- system-messages
  "Failed turns as `::system` bubble messages — synthesized from the
   turn status alone (the turn stores no error text). Tells the human
   the one thing they need: the agent is not gone, the next message
   resumes it."
  [db id]
  (mapv (fn [[at]]
          {::at      at
           ::kind    ::system
           ::label   "system"
           ::content "agent's turn failed — it will resume on your next message"})
        (provider-failure-rows db id)))

(defn conversation
  "The agent's conversation as bubble messages, oldest-first.

   DERIVED from the message log via
   `seon.render.default/recent-messages` (from = me OR to ∋ me;
   nothing stored), merged with the turn log's provider-failure
   `::system` lines (see [[provider-failure-rows]]). Each message
   carries `::at` `::kind` `::label` `::content`. `::limit` bounds
   the tail (default 50)."
  {:malli/schema [:=> [:cat ::conversation-request] ::conversation-response]}
  [{:seon.agent/keys [id] :seon.db/keys [db] ::keys [limit]}]
  (let [db    (or db (some-> db/*conn* deref))
        n     (or limit default-limit)
        rows  (default/recent-messages db id n)
        msgs  (mapv (fn [[at label content]]
                      {::at      at
                       ::kind    (message-kind label)
                       ::label   label
                       ::content content})
                    rows)]
    {::messages
     (->> (into msgs (system-messages db id))
          (sort-by #(.getTime ^js (::at %)))
          (take-last n)
          vec)}))

(schema/register! ::last-reply-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db    {:optional true} :any]])

(schema/register! ::last-reply
  ::content)

(schema/register! ::last-reply-response
  [:map [::last-reply {:optional true} ::last-reply]])

(defn last-reply
  "The agent's most recent REPLY, as readable text.

   The newest `::agent` (label
   `\"assistant\"`: from = me AND to ∋ the user) message in
   [[conversation]]. Transcript self-narration and
   outgoing peer sends never count (direction-classified upstream).
   Returns `{}` when the agent has never replied (optional = absent).

   This is what the default root canvas shows
   (`seon.render.canvas/welcome`'s compact block: purpose + id +
   last reply) — DERIVED from the message log at render time, nothing
   stored (reactive-context doctrine)."
  {:malli/schema [:=> [:cat ::last-reply-request] ::last-reply-response]}
  [{:seon.agent/keys [id] :seon.db/keys [db]}]
  (let [{::keys [messages]} (conversation (cond-> {:seon.agent/id id}
                                            db (assoc :seon.db/db db)))]
    (if-some [m (peek (filterv #(= ::agent (::kind %)) messages))]
      {::last-reply (::content m)}
      {})))

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
