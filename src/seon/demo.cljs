(ns seon.example
  "V0.5 demo bootstrap. Sets up alice's initial render slots + provides
   the welcome view with a chat form. Per spec-05 §10.2 A-8 + §15.6.

   `setup!` runs at boot (called from seon.client/start-agent!). It
   transacts alice's `:seon.render/ai` (the rich ctx default) and
   `:seon.render/html` (this namespace's `welcome-view`), so a fresh
   page load sees alice's tile immediately rather than an empty shell.

   `welcome-view` is the initial tile — friendly hello, conversation
   log, and a chat form that posts to /chat via Datastar's
   `@post(...)`. Once the user types a message, seon.web.serve's
   POST /chat handler injects it as a `:seon.message/role :user`,
   the agent's kick listener fires, run-turn-once! flows the prompt
   through `:seon.render/ai` (the rich ctx from
   seon.render.default/ctx), the LLM responds, eval-batch! runs the
   forms, and broadcast morphs the tile."
  (:require
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.db :as db]
    [seon.render.default :as default]
    [seon.ui.components :as comp]
    [seon.ui.markdown :as md]))

;; ============================================================
;; Pointed-at-folder banner — surfaces SEON_FS_ROOT in the tile so
;; the demo's framing ("user points Seon at their work folder") is
;; visible at a glance, plus a couple of starter questions.
;; ============================================================

(defn- folder-banner []
  (when-let [root (default/fs-root)]
    [:section {:class (str "flex items-center gap-2 px-2 py-1 rounded "
                           "bg-base-800 border border-base-700 text-xs")}
     [:span {:class "text-warning"} "📁"]
     [:span {:class "text-text-400"} "Pointed at:"]
     [:span {:class "text-signal font-mono break-all"} root]
     (when (default/read-only?)
       [:span {:class "ml-auto text-text-500 italic"} "read-only"])]))

;; ============================================================
;; DB sidebar — live view of every entity Seon has stored.
;;
;; Re-renders on every transact via the broadcast tx-listener, so
;; it's effectively realtime: ask a question, watch a note appear.
;; ============================================================

(def ^:private skip-attrs
  "Attributes we don't show in the sidebar (system noise)."
  #{:db/txInstant})

(defn- group-prefix
  "Group entities by their primary namespace prefix so the sidebar
   reads as: notes / messages / agent / evals / other."
  [ent]
  (cond
    (:seon.note/id    ent) :note
    (:seon.message/id ent) :message
    (:seon.agent/id   ent) :agent
    (:seon.eval/id    ent) :eval
    :else                  :other))

(defn- ent-pill [k]
  (let [text (str k)]
    [:span {:class "px-1.5 py-0.5 rounded bg-base-700 text-text-400 font-mono whitespace-nowrap"}
     (if (> (count text) 32)
       (str (subs text 0 32) "…")
       text)]))

(defn- format-val [v]
  (cond
    (string? v)  (if (> (count v) 60) (str (subs v 0 60) "…") v)
    (inst? v)    (str (.toISOString v))
    (vector? v)  (str "[" (count v) " items]")
    :else        (pr-str v)))

(defn- entity-card [ent kind]
  ;; Always-visible card (no <details>) — Datastar's SSE morph
  ;; closes any open <details> on every re-render, which makes
  ;; the sidebar feel broken. Cards stay readable at a glance.
  (let [headline
        (case kind
          :note    (str "📝 " (or (:seon.note/topic ent) "(note)"))
          :message (str (case (:seon.message/role ent)
                          :user      "💬 user"
                          :assistant "🤖 assistant"
                          :system    "ℹ️ system"
                          "message"))
          :agent   (str "🪪 " (:seon.agent/id ent))
          :eval    "⚙ eval"
          :other   "• entity")
        primary
        (case kind
          :note    (:seon.note/content ent)
          :message (:seon.message/content ent)
          :agent   (str "state " (:seon.agent/state ent)
                        " · turn " (:seon.agent/turn-count ent))
          :eval    (:seon.eval/source ent)
          :other   nil)
        border-cls
        (case kind
          :note    "border-warning/40 bg-warning/5"
          :message (case (:seon.message/role ent)
                     :user      "border-info/30 bg-info/5"
                     :assistant "border-signal/30 bg-signal/5"
                     :system    "border-warning/30 bg-warning/5"
                     "border-base-700")
          :agent   "border-signal/30 bg-signal/5"
          "border-base-700 bg-base-800/60")]
    [:div {:class (str "rounded border px-2 py-1.5 " border-cls)}
     [:div {:class "flex items-baseline gap-2 mb-0.5"}
      [:span {:class "text-xs text-text-100 truncate flex-1"} headline]
      [:span {:class "text-text-500 font-mono text-[10px]"} (str "#" (:db/id ent))]]
     (when primary
       [:div {:class "text-[11px] text-text-300 break-words line-clamp-2 leading-snug"}
        (format-val primary)])]))

(defn- db-sidebar [db]
  (let [entities (default/all-entities db)
        by-kind  (group-by group-prefix entities)
        counts   (into {} (map (fn [[k es]] [k (count es)]) by-kind))
        section
        (fn [kind label]
          (let [ents (get by-kind kind)]
            (when (seq ents)
              [:section {:class "flex flex-col gap-1"}
               [:div {:class "flex items-baseline justify-between text-xs uppercase tracking-wider text-text-500"}
                [:span {:class "font-bold"} label]
                [:span (str (count ents))]]
               (for [e ents] (entity-card e kind))])))]
    [:aside {:class (str "flex flex-col gap-3 p-3 "
                         "bg-base-950 border-l border-base-700 overflow-y-auto")
             :style "width: 50%; min-width: 320px; flex: 0 0 50%;"}
     [:header {:class "flex items-center gap-2 pb-1 border-b border-base-700"}
      [:span {:class "text-sm font-bold text-signal"} "🗄  Database"]
      [:span {:class "text-xs text-text-500 ml-auto"}
       (str (count entities) " entities")]]
     (section :note    "Notes (durable)")
     (section :agent   "Agent")
     (section :message "Messages")
     (section :eval    "Evals")
     (section :other   "Other")]))

(defn- empty-hero
  "Big centered welcome screen when there are no messages yet. Replaces
   the previous inline empty-state for a more chat-app feel."
  [agent-id]
  (let [root (default/fs-root)]
    [:div {:class "flex-1 flex flex-col items-center justify-center gap-6 px-8 py-12 text-center"}
     [:div {:class "text-5xl"} "✨"]
     [:div {:class "flex flex-col gap-1"}
      [:h1 {:class "text-2xl font-bold text-signal"} (display-name agent-id)]
      [:p {:class "text-sm text-text-300"}
       "Personal AI sidecar — reads your folder, remembers what it finds."]]
     (when root
       [:div {:class "flex items-center gap-2 px-3 py-1.5 rounded bg-base-800 border border-base-700 text-xs"}
        [:span {:class "text-warning"} "📁"]
        [:span {:class "text-text-400"} "Connected to"]
        [:span {:class "text-signal font-mono"} root]
        (when (default/read-only?)
          [:span {:class "text-text-500 italic"} "· read-only"])])
     [:div {:class "max-w-md flex flex-col gap-3 text-left"}
      [:div {:class "text-xs uppercase tracking-wider text-text-500 font-bold"}
       "How it works"]
      [:ol {:class "list-decimal pl-5 space-y-1 text-sm text-text-300"}
       [:li "Ask anything about the files Seon has access to."]
       [:li "She walks the directory, greps, and reads what's relevant."]
       [:li "Her thinking shows up as " [:code {:class "px-1 rounded bg-base-800 text-warning text-xs"} ";; comments"] " above the code."]
       [:li "Durable notes get saved to the database — clear the chat and ask again to see her recall them."]]
      [:div {:class "text-xs uppercase tracking-wider text-text-500 font-bold mt-2"}
       "Try asking"]
      [:ul {:class "flex flex-col gap-1.5"}
       (for [q ["what's open in recent-activity?"
                "what changed recently?"
                "what's the state of the build?"
                "find every mention of the release branch policy"]]
         [:li {:class (str "px-3 py-1.5 rounded bg-base-800/60 border border-base-700 "
                           "text-sm text-text-200 font-mono")}
          q])]]]))

;; ============================================================
;; Welcome view — the agent's tile on first page load.
;;
;; System fn (not in seon.agent.seon ns) → takes the system input
;; shape (:seon.db/db + :seon.agent/id) and pulls the entity itself.
;;
;; Renders:
;;   • status dot + agent id
;;   • turn count
;;   • recent message log (last 10)
;;   • inline error banner (recent-errors > 0)
;;   • chat form (POST /chat?agent=<id>)
;; ============================================================

(defn- display-name
  "Title-case the agent id for the tile header. 'seon' → 'Seon'."
  [agent-id]
  (str/capitalize agent-id))

(defn- chat-form [agent-id]
  ;; Datastar v1 attribute naming uses COLON between segments
  ;; (`data-on:submit__prevent`), not hyphen — the JVM seon
  ;; web layer is the known-good reference here (seon/src/seon/web/*.clj).
  ;; The `__prevent` modifier blocks the native form-submit so we don't
  ;; navigate away from the SSE stream. After `@post` we explicitly
  ;; `.reset()` the form (Datastar's __prevent suppresses the native
  ;; submit that would otherwise have cleared the field).
  [:form {:class "flex gap-2"
          :data-on:submit__prevent
          (str "@post('/chat?agent=" agent-id "', {contentType:'form'});"
               " evt.target.reset()")}
   [:input {:type "text"
            :name "text"
            :required true
            :autocomplete "off"
            :placeholder (str "ask " (display-name agent-id) " about your folder…")
            :class (str "flex-1 px-2 py-1 bg-base-800 text-text-100 "
                        "border border-base-700 rounded text-sm "
                        "focus:outline-none focus:border-signal")}]
   [:button {:type "submit"
             :class (str "px-3 py-1 text-sm font-medium rounded "
                         "bg-signal text-base-950 hover:bg-warning "
                         "transition-colors")}
    "send"]])

(defn- truncate
  "Cap a string for display, with an ellipsis marker."
  [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) " …(" (- (count s) n) " more chars)")
    (or s "")))

(defn- eval-block
  "Compact 'thinking' indicator for one `:seon.eval` entry. Visually
   distinct from message bubbles — left rule, dimmer text, single
   line of source code. Full code + result live in the sidebar's
   Evals section; this is just the live trail of what Seon is doing.

   row is `[at id src ok res err narr]` per recent-evals's shape."
  [[_at eid src ok _res err narr]]
  [:div {:class (str "flex gap-2 py-0.5 px-1 my-0.5 border-l "
                     (if ok "border-text-700" "border-error/50"))}
   [:span {:class "text-text-600 select-none"} "🤔"]
   [:div {:class "flex-1 min-w-0 flex flex-col gap-0.5"}
    (when-not (str/blank? narr)
      (md/md->hiccup narr
        {:wrap-class "text-xs text-text-400 italic leading-snug"}))
    [:code {:class "text-[11px] font-mono text-text-600 truncate block"}
     (truncate src 120)]
    (when (and (not ok) (not (str/blank? err)))
      [:code {:class "text-[11px] font-mono text-error truncate block"}
       (truncate err 200)])]])

;; Merge messages + evals into one chronological timeline so the user
;; sees the agent's reasoning interleaved with the conversation. Each
;; item is `{:kind :message|:eval :at <Date> :payload [...]}`.

(defn- recent-messages-with-id
  "Like default/recent-messages but pulls :seon.message/id too — needed
   so each rendered <div> can carry the message id, giving Idiomorph
   a stable key when SSE patches arrive (otherwise the diff treats
   appends as in-place replacements)."
  [db id n]
  (let [args  [[:seon.agent/id id]]
        query '[:find ?at ?mid ?role ?content
                :in $ ?aid
                :where
                [?m :seon.message/agent ?aid]
                [?m :seon.message/id ?mid]
                [?m :seon.message/at ?at]
                [?m :seon.message/role ?role]
                [?m :seon.message/content ?content]]
        rows  (if db
                (db/query {:seon.db/db db :seon.db/query query :seon.db/args args})
                (db/query {:seon.db/query query :seon.db/args args}))]
    (->> rows (sort-by first) (take-last n))))

(defn- timeline-items
  "Messages + evals interleaved by `:at`, oldest-first. Each item
   carries a stable `:id` so Idiomorph appends new items instead of
   swapping into existing slots. NO cap on either — both accumulate.
   Evals render as compact 'thinking' items (see `eval-block`), so
   the visual noise stays manageable even on long runs."
  [db id]
  (let [msgs (->> (recent-messages-with-id db id 500)
                  (map (fn [[at mid role content]]
                         {:kind :message :id (str "msg-" mid)
                          :at at :payload [role content]})))
        evs  (->> (#'default/recent-evals db id 500)
                  (map (fn [[eid at src ok res err narr]]
                         {:kind :eval :id (str "ev-" eid)
                          :at at :payload [at eid src ok res err narr]})))]
    (->> (concat msgs evs)
         (sort-by :at))))

(defn welcome-view
  "Default `:seon.render/html` for the V0.5 demo. System fn → takes
   system input shape. Returns `{:seon.render/hiccup [...]}`.

   The body shows:
     • header — capitalized agent name + status + turn count
     • optional error banner
     • timeline — messages interleaved with eval code blocks (the agent's
       actual code + result) so you can see the reasoning, not just the
       chat
     • chat form"
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent   (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        state (or (:seon.agent/state ent) :unknown)
        turns (or (:seon.agent/turn-count ent) 0)
        items (timeline-items db id)
        errs  (#'default/recent-errors db id 5)]
    {:seon.render/hiccup
     [:div {:id (str "agent-" id)
            :class "h-full flex bg-base-900 rounded overflow-hidden"}
      [:div {:class "flex flex-col p-3 gap-2 overflow-hidden"
             :style "width: 50%; flex: 0 0 50%; min-width: 0;"}
      [:header {:class "flex items-center gap-2"}
       (comp/status-dot state (display-name id))
       [:span {:class "text-xs text-text-400 ml-auto"} (str "turn " turns)]
       [:button {:type "button"
                 :id "agent-clear-btn"
                 :class (str "text-xs text-text-500 hover:text-warning "
                             "border border-base-700 hover:border-warning "
                             "rounded px-2 py-0.5 transition-colors")
                 :title "Clear messages — keeps the agent's durable notes"
                 :onclick
                 (str
                   "(function(){"
                   "  var b=this;"
                   "  console.log('[agent-clear] click registered');"
                   "  b.textContent='clearing…';"
                   "  b.style.background='#f59e0b';"
                   "  b.style.color='#000';"
                   "  fetch('/clear?agent=" id "',{method:'POST'})"
                   "    .then(function(r){"
                   "      console.log('[agent-clear] response',r.status);"
                   "      if(r.ok){location.reload();}"
                   "      else{b.textContent='clear failed: '+r.status;}"
                   "    })"
                   "    .catch(function(e){"
                   "      console.error('[agent-clear] fetch error',e);"
                   "      b.textContent='clear error';"
                   "      b.style.background='#dc2626';"
                   "    });"
                   "}).call(this)")}
        "clear chat"]]
      (when (seq items) (folder-banner))
      (when (seq errs)
        [:section {:class (str "flex flex-col gap-1 border border-error/40 "
                               "bg-error/10 rounded p-2 text-xs")}
         (for [e errs]
           [:div {:class "flex items-start gap-2"}
            [:span {:class "text-error font-bold"} "⚠"]
            [:span {:class "flex-1 text-error font-mono break-all"}
             (str (:seon.log/message e))]])])
      (if (seq items)
        [:section {:class "flex-1 overflow-auto text-sm space-y-2 px-1"}
         (for [{:keys [kind id payload]} items]
           (case kind
             :message
             (let [[role content] payload
                   role-cls (case role
                              :user      "text-info"
                              :assistant "text-signal"
                              :system    "text-warning"
                              "text-text-400")]
               [:div {:id id
                      :class (str "py-2 px-3 my-1 rounded "
                                  (case role
                                    :user      "bg-info/5 border-l-2 border-info"
                                    :assistant "bg-signal/5 border-l-2 border-signal"
                                    :system    "bg-warning/5 border-l-2 border-warning"
                                    "bg-base-800 border-l-2 border-base-700"))}
                [:div {:class (str "text-xs font-bold uppercase tracking-wider mb-1 "
                                   role-cls)}
                 (name role)]
                (md/md->hiccup content
                  {:wrap-class "text-sm text-text-100 leading-relaxed"})])

             :eval
             (assoc-in (eval-block payload) [1 :id] id)

             nil))]
        (empty-hero id))
      [:div {:class "pt-2 border-t border-base-700"}
       (chat-form id)]]
      (db-sidebar db)]}))

;; ============================================================
;; Bootstrap — runs at boot to transact the initial slot values.
;; ============================================================

(defn ^:async setup!
  "Initial demo bootstrap. Transacts alice's `:seon.render/ai` (the
   rich default ctx) and `:seon.render/html` (this namespace's
   welcome view) so the page tile renders immediately on first
   browser load."
  []
  (await (db/transact!
           {:seon.db/tx-data
            [{:seon.agent/id agent/default-id
              :seon.render/ai 'seon.render.default/ctx
              :seon.render/html 'seon.example/welcome-view}]})))
