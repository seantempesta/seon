(ns seon.render.default-page
  "Default page template for namespaces with *ctx* but no custom renderer.

   Renders a two-panel layout:
   - LEFT: Markdown narrative from :seon.render.default/narrative
   - RIGHT: Auto-rendered data keys from ctx
   - BOTTOM: Chat input stub
   - TOP: Namespace name + introspect link

   Scanner detects this as a page renderer via specs."
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [markdown.core :as md]
            [seon.render :as render]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! :seon.render.default/narrative
                  [:string {:description "Markdown narrative for the left panel"}])

(schema/register! :seon.ctx/messages
                  [:vector {:description "Chat message history"}
                   [:map
                    [:role [:enum :user :assistant]]
                    [:content :string]]])

(schema/register! :seon.ctx/uploads
                  [:vector {:description "Uploaded file references"}
                   [:map
                    [:name :string]
                    [:url {:optional true} :string]]])

(schema/register! :seon.ctx/user-input
                  [:string {:description "Current chat input text"}])

;;; ---------------------------------------------------------------------------
;;; Reserved Keys (not rendered in data panel)
;;; ---------------------------------------------------------------------------

(def ^:private reserved-keys
  "Keys handled specially, not rendered in the data panel."
  #{:seon.render.default/narrative
    :seon.ctx/messages
    :seon.ctx/uploads
    :seon.ctx/user-input})

;;; ---------------------------------------------------------------------------
;;; Data Panel Rendering
;;; ---------------------------------------------------------------------------

(defn- render-data-entry
  "Render a single data key-value pair for the right panel."
  [idx k v]
  (let [custom (render/try-render {k v} :html)
        label (render/humanize k)
        base-style (str "--i:" idx)]
    (if custom
      [:div {:class "mb-3" :style base-style}
       [:h3 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-1"}
        label]
       [:div {:class "bg-base-900 rounded p-2"} custom]]
      ;; Shape-specific rendering when no custom renderer
      (cond
        ;; Number → stat card with scale animation
        (number? v)
        [:div {:class "mb-3 bg-base-900 rounded p-3" :style base-style}
         [:div {:class "text-3xl font-bold text-signal font-mono animate-stat"} (str v)]
         [:div {:class "text-xs text-text-400 uppercase tracking-wider mt-1"} label]]

        ;; String → pre for multiline, p for single line
        (string? v)
        [:div {:class "mb-3" :style base-style}
         [:h3 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-1"}
          label]
         (if (str/includes? v "\n")
           [:pre {:class "bg-base-900 rounded p-2 text-xs text-text-200 font-mono whitespace-pre-wrap"} v]
           [:p {:class "bg-base-900 rounded p-2 text-sm text-text-200"} v])]

        ;; Everything else → for-html (handles schemas, maps, vectors)
        :else
        [:div {:class "mb-3" :style base-style}
         [:h3 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-1"}
          label]
         [:div {:class "bg-base-900 rounded p-2"}
          (render/for-html v)]]))))

(defn- render-data-panel
  "Render all non-reserved ctx keys in the right panel."
  [ctx-value]
  (let [data-keys (->> (keys ctx-value)
                       (remove reserved-keys)
                       (sort))]
    (if (seq data-keys)
      [:div
       (map-indexed (fn [idx k]
                      (render-data-entry idx k (get ctx-value k)))
                    data-keys)]
      [:div {:class "text-text-500 text-sm italic py-4"}
       "No data yet."])))

;;; ---------------------------------------------------------------------------
;;; Chat Panel
;;; ---------------------------------------------------------------------------

(defn- render-chat-messages
  "Render chat message history."
  [messages]
  (when (seq messages)
    [:div {:class "space-y-2 mb-3 max-h-48 overflow-y-auto"}
     (for [{:keys [role content]} messages]
       [:div {:class (str "text-xs p-2 rounded "
                          (if (= role :user)
                            "bg-base-800 text-text-200"
                            "bg-base-900 text-text-50 border border-base-700"))}
        [:span {:class "text-text-500 text-xs"} (name role) ": "]
        content])]))

(defn- render-chat-input
  "Render chat input stub."
  []
  [:div {:class "flex gap-2"}
   [:input {:field :seon.ctx/user-input
            :type "text"
            :placeholder "Type a message..."
            :class "flex-1 bg-base-800 border border-base-700 rounded px-3 py-2 text-sm text-text-50 font-mono placeholder-text-500"}]
   [:button {:on:click :send-message!
             :class "px-4 py-2 text-sm font-mono rounded bg-signal/20 text-signal border border-signal/30 hover:bg-signal/30 hover:border-signal/50"}
    "Send"]])

;;; ---------------------------------------------------------------------------
;;; Page Render Function
;;; ---------------------------------------------------------------------------

(defn render-default-page
  "Default page renderer for any namespace with *ctx*.

   Takes a map where one key ends in *ctx* containing the ctx value.
   Renders a two-panel layout with markdown narrative and auto-rendered data.

   The ctx value should contain:
   - :seon.render.default/narrative  - Markdown text for the left panel
   - :seon.ctx/messages              - Chat history (optional)
   - Other namespaced keys           - Auto-rendered in data panel"
  [input]
  (let [;; Find the *ctx* key in the input map
        ctx-entry (first (filter (fn [[k _]] (str/ends-with? (name k) "*ctx*")) input))
        [ctx-key ctx-value] ctx-entry
        ns-str (when ctx-key (namespace ctx-key))
        narrative (get ctx-value :seon.render.default/narrative)
        messages (get ctx-value :seon.ctx/messages [])
        narrative-html (when narrative (md/md-to-html-string narrative))]
    {:seon.render/html
     [:main#morph
      ;; Top bar
      [:div {:class "flex items-center justify-between mb-4"}
       [:h1 {:class "text-lg font-semibold tracking-tight font-mono"}
        (or ns-str "namespace")]
       [:a {:href (str "/ns/" ns-str "?view=introspect")
            :class "px-2 py-1 text-xs font-mono rounded border text-text-500 border-base-700 hover:border-base-600 hover:text-text-200"}
        "Introspect \u2192"]]

      ;; Two-panel layout
      [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-4 mb-4"}
       ;; LEFT: Narrative
       [:div {:class "bg-base-850 rounded p-4 panel-glow"
              :style "view-transition-name: step-content"}
        (if narrative-html
          [:div {:class "prose prose-sm prose-invert max-w-none"}
           (h/raw narrative-html)]
          [:div {:class "text-text-500 text-sm italic py-8 text-center"}
           "No narrative yet."])]

       ;; RIGHT: Data panel
       [:div {:class "bg-base-850 rounded p-4 panel-glow"
              :style "view-transition-name: data-panel"}
        (render-data-panel ctx-value)]]

      ;; BOTTOM: Chat
      [:section {:class "bg-base-850 rounded p-3"}
       (render-chat-messages messages)
       (render-chat-input)]]

     :seon.render/ai
     (str (or ns-str "namespace") " — "
          (if narrative
            (subs narrative 0 (min 200 (count narrative)))
            "no narrative")
          " | "
          (count messages) " messages")}))
