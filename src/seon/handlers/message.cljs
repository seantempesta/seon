(ns seon.handlers.message
  "Renderers for `:seon.message` entities — the AI-text form (`render-ai`)
   and the HTML form (`render-html`). Both are the conventional shape
   `(fn [{:seon.db/db :seon.agent/id :seon.render/entity}] ...)`.

   Stored on individual message entities via:
     :seon.render/ai   'seon.handlers.message/render-ai
     :seon.render/html 'seon.handlers.message/render-html

   `seon.render/visible-entities` walks every `:seon.render/ai` entity
   in the agent's filtered view and the inspector calls these via
   symbol-lookup."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

(defn render-ai
  "Format a single message as a single line of LLM-readable text.

   `[<from>] <content>`  — e.g. `[user] Define a fn that adds two numbers.`"
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [from (or (:seon.message/from entity)
                 (:seon.message/role entity)
                 :unknown)
        body (or (:seon.message/content entity) "")]
    {:seon.render/text (str "[" (name from) "] " body)}))

(defn render-html
  "Render a single message as a hiccup card. Uses the Phosphor Terminal
   palette — warm blacks, cream text, amber accents."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [from   (or (:seon.message/from entity)
                   (:seon.message/role entity)
                   :unknown)
        body   (or (:seon.message/content entity) "")
        at     (:seon.message/at entity)
        from-class (case from
                     :user      "text-amber-500"
                     :assistant "text-text-100"
                     :system    "text-text-400"
                     "text-text-300")]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2"}
       [:span {:class (str "text-xs font-mono font-semibold " from-class)}
        (name from)]
       (when at
         [:span {:class "text-xs text-text-500"}
          (try (str at) (catch :default _ ""))])]
      [:div {:class "text-xs text-text-100 font-mono whitespace-pre-wrap"}
       (str/trim body)]]}))

;; ============================================================
;; Schemas — register the message attrs `render-html` reads when not
;; already declared elsewhere. `:seon.message/from` is owned by
;; seon.handlers.wake (keyword form); we just consume it here.
;; ============================================================

(schema/register! :seon.message/role
  [:enum :user :assistant :system :tool])

(schema/register! :seon.message/at      :inst)
(schema/register! :seon.message/content :string)
