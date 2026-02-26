(ns seon.primer.debug
  "Debug utilities for Primer - EDN-first ctx inspection.

   Usage:
     GET /primer/ctx    -> Pretty-printed EDN (curl/LLM friendly)
     GET /primer/debug  -> Visual debug page (browser)

   EDN is the canonical format - no lossy JSON conversion."
  (:require [seon.primer.ctx :as ctx]
            [clojure.pprint :as pp]
            [dev.onionpancakes.chassis.core :as h]))

;;; === EDN Rendering ===

(defn ctx->edn
  "Render ctx as formatted EDN string."
  [ctx]
  (with-out-str (pp/pprint ctx)))

;;; === HTML Debug Components ===

(defn action-chip
  "Render an action as a clickable chip showing ID and label."
  [{:keys [action/id action/label]}]
  [:span.inline-flex.items-center.gap-1.px-2.py-1.text-xs.font-mono
   {:class "bg-blue-100 text-blue-800 rounded"}
   [:span.font-bold (name id)]
   [:span.text-blue-600 label]])

(defn scene-card
  "Debug view of current scene."
  [scene]
  [:div.border.rounded-lg.p-4.bg-white.shadow-sm
   [:h3.font-bold.text-sm.mb-2 "Current Scene"]
   [:dl.grid.grid-cols-2.gap-2.text-sm
    [:dt.text-gray-500 "ID"]
    [:dd.font-mono (:scene/id scene)]
    [:dt.text-gray-500 "Template"]
    [:dd.font-mono (str (:scene/template scene))]
    [:dt.text-gray-500 "Actions"]
    [:dd.flex.flex-wrap.gap-1
     (for [action (:scene/actions scene)]
       (action-chip action))]]])

(defn session-card
  "Debug view of session metadata."
  [ctx]
  [:div.border.rounded-lg.p-4.bg-white.shadow-sm
   [:h3.font-bold.text-sm.mb-2 "Session"]
   [:dl.grid.grid-cols-2.gap-2.text-sm
    [:dt.text-gray-500 "ID"]
    [:dd.font-mono (:session/id ctx)]
    [:dt.text-gray-500 "Created"]
    [:dd.font-mono (str (:session/created-at ctx))]
    [:dt.text-gray-500 "Checkpointed"]
    [:dd.font-mono (str (:session/checkpointed-at ctx))]]])

(defn raw-ctx-view
  "Collapsible raw EDN view of ctx."
  [ctx]
  [:details.border.rounded-lg.bg-gray-50
   [:summary.p-3.cursor-pointer.font-bold.text-sm "Raw CTX (EDN)"]
   [:pre.p-4.text-xs.font-mono.overflow-auto.max-h-96.bg-gray-900.text-green-400
    (ctx->edn ctx)]])

(defn debug-overlay
  "Full debug overlay panel - shows all ctx info visually."
  [session-id]
  (let [ctx (ctx/get session-id)]
    (if ctx
      [:div#debug-overlay.fixed.bottom-0.right-0.w-96.max-h-screen.overflow-auto
       {:class "bg-gray-100/95 border-l border-t shadow-lg p-4 space-y-4"
        :style "backdrop-filter: blur(4px);"}
       [:div.flex.justify-between.items-center
        [:h2.font-bold "Debug"]
        [:button.text-gray-500.hover:text-gray-700
         {:data-on:click "$debugOpen = false"
          :class "text-xl leading-none"}
         "×"]]
       (session-card ctx)
       (scene-card (:primer/current-scene ctx))
       (raw-ctx-view ctx)]
      [:div#debug-overlay.fixed.bottom-0.right-0.p-4.bg-red-100.text-red-800
       "No session found: " session-id])))

(defn debug-toggle-button
  "Floating button to toggle debug overlay."
  []
  [:button#debug-toggle.fixed.bottom-4.right-4.z-50
   {:class "bg-gray-800 text-white px-3 py-2 rounded-full shadow-lg
            hover:bg-gray-700 text-sm font-mono"
    :data-on:click "$debugOpen = !$debugOpen"}
   "DBG"])

;;; === Full Debug Page ===

(defn debug-page
  "Standalone debug page showing full ctx inspection."
  [session-id]
  (let [ctx (ctx/get session-id)]
    (h/html
     [:html
      [:head
       [:title "Primer Debug"]
       [:meta {:charset "UTF-8"}]
       [:script {:src "https://cdn.tailwindcss.com"}]]
      [:body.bg-gray-100.p-8
       [:div.max-w-4xl.mx-auto.space-y-6
        [:h1.text-2xl.font-bold "Primer Debug - " session-id]

        ;; Navigation links
        [:div.flex.gap-4.text-sm
         [:a.text-blue-600.hover:underline
          {:href (str "/primer/ctx?session-id=" session-id)}
          "Raw EDN"]
         [:a.text-blue-600.hover:underline
          {:href (str "/primer?session-id=" session-id)}
          "Back to Primer"]]

        (if ctx
          [:div.space-y-6
           (session-card ctx)
           (scene-card (:primer/current-scene ctx))
           (raw-ctx-view ctx)

           ;; History section (last 10 checkpoints)
           [:div.border.rounded-lg.p-4.bg-white.shadow-sm
            [:h3.font-bold.text-sm.mb-2 "Checkpoint History (recent)"]
            (if-let [history (seq (take 10 (ctx/history session-id)))]
              [:ul.text-sm.space-y-1
               (for [h history]
                 [:li.font-mono (str (:session/checkpointed-at h))])]
              [:p.text-gray-500.text-sm "No checkpoints yet"])]]
          [:div.bg-red-100.text-red-800.p-4.rounded
           "Session not found: " session-id])]]])))
