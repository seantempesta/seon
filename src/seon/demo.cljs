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
    [seon.agent :as agent]
    [seon.db :as db]
    [seon.render.default :as default]
    [seon.ui.components :as comp]))

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

(defn- chat-form [agent-id]
  ;; Datastar v1 attribute naming uses COLON between segments
  ;; (`data-on:submit__prevent`), not hyphen — the JVM seon
  ;; web layer is the known-good reference here (seon/src/seon/web/*.clj).
  ;; The `__prevent` modifier blocks the native form-submit so we don't
  ;; navigate away from the SSE stream.
  [:form {:class "flex gap-2 pt-2 border-t border-base-700"
          :data-on:submit__prevent
          (str "@post('/chat?agent=" agent-id "', {contentType:'form'})")}
   [:input {:type "text"
            :name "text"
            :required true
            :autocomplete "off"
            :placeholder (str "say hi to " agent-id "…")
            :class (str "flex-1 px-2 py-1 bg-base-800 text-text-100 "
                        "border border-base-700 rounded text-sm "
                        "focus:outline-none focus:border-signal")}]
   [:button {:type "submit"
             :class (str "px-3 py-1 text-sm font-medium rounded "
                         "bg-signal text-base-950 hover:bg-warning "
                         "transition-colors")}
    "send"]])

(defn welcome-view
  "Default `:seon.render/html` for the V0.5 demo. System fn → takes
   system input shape. Returns `{:seon.render/hiccup [...]}`."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent   (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        state (or (:seon.agent/state ent) :unknown)
        turns (or (:seon.agent/turn-count ent) 0)
        msgs  (#'default/recent-messages db id 10)
        errs  (#'default/recent-errors db id 5)]
    {:seon.render/hiccup
     [:div {:id (str "agent-" id)
            :class "h-full flex flex-col p-3 gap-2 bg-base-900 rounded"}
      [:header {:class "flex items-center gap-2"}
       (comp/status-dot state id)
       [:span {:class "text-xs text-text-400 ml-auto"} (str "turn " turns)]]
      (when (seq errs)
        [:section {:class (str "flex flex-col gap-1 border border-error/40 "
                               "bg-error/10 rounded p-2 text-xs")}
         (for [e errs]
           [:div {:class "flex items-start gap-2"}
            [:span {:class "text-error font-bold"} "⚠"]
            [:span {:class "flex-1 text-error font-mono break-all"}
             (str (:seon.log/message e))]])])
      [:section {:class "flex-1 overflow-auto text-xs font-mono space-y-1"}
       (if (seq msgs)
         (for [[_at role content] msgs]
           [:div {:class "py-0.5"}
            [:span {:class (case role
                             :user      "text-info"
                             :assistant "text-signal"
                             :system    "text-text-400"
                             "text-text-400")}
             (str (name role) ": ")]
            [:span {:class "text-text-100"} content]])
         [:div {:class "text-text-500 italic"} "no messages yet — say hi below"])]
      (chat-form id)]}))

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
