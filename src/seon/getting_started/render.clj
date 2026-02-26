(ns seon.getting-started.render
  "Page renderer for seon.getting-started.
   Extends the default page layout with step navigation buttons.
   Each step gets custom right-panel rendering for a premium feel."
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [markdown.core :as md]
            [seon.getting-started :as gs]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.web.components :as ui]))

;;; ---------------------------------------------------------------------------
;;; Page Renderer Specs
;;; ---------------------------------------------------------------------------

(schema/register! ::page-render-request
                  [:map
                   [::gs/*ctx* ::gs/*ctx*]])

(schema/register! ::page-render-response
                  [:map
                   [:seon.render/html :any]
                   [:seon.render/ai :string]])

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- format-number
  "Format a number with comma separators."
  [n]
  (if (integer? n)
    (format "%,d" (long n))
    (format "%,.1f" (double n))))

;;; ---------------------------------------------------------------------------
;;; Step 1: Project Idea Cards
;;; ---------------------------------------------------------------------------

(defn- render-project-ideas
  "Render project ideas as attractive cards instead of a table."
  [ideas]
  [:div
   (ui/section-header "PROJECT IDEAS")
   [:div {:class "grid gap-3"}
    (map-indexed
     (fn [idx {:keys [title description]}]
       [:div {:class "bg-base-900 rounded p-3 border border-base-700/50 hover:border-signal/30 transition-colors cursor-default"
              :style (str "--i:" idx)}
        [:div {:class "flex items-start gap-3"}
         [:div {:class "w-8 h-8 rounded bg-signal/10 flex items-center justify-center shrink-0 mt-0.5"}
          [:span {:class "text-signal text-sm font-bold"} (subs title 0 1)]]
         [:div
          [:h3 {:class "text-sm font-semibold text-text-50 mb-1"} title]
          [:p {:class "text-xs text-text-400 leading-relaxed"} description]]]])
     ideas)]])

;;; ---------------------------------------------------------------------------
;;; Step 2: Schema Specification Table
;;; ---------------------------------------------------------------------------

(defn- render-proposed-schema
  "Render the proposed schema as a clean field specification table."
  [schema-map]
  [:div
   (ui/section-header "DATA MODEL")
   [:div {:class "bg-base-900 rounded overflow-hidden"}
    [:table {:class "w-full text-xs border-collapse"}
     [:thead
      [:tr {:class "border-b border-base-700 bg-base-900"}
       [:th {:class "py-2 px-3 text-left text-text-400 font-semibold uppercase tracking-wider"} "Field"]
       [:th {:class "py-2 px-3 text-left text-text-400 font-semibold uppercase tracking-wider"} "Type"]
       [:th {:class "py-2 px-3 text-left text-text-400 font-semibold uppercase tracking-wider"} "Constraints"]]]
     [:tbody
      (map-indexed
       (fn [idx [field-key description]]
         (let [label (render/humanize field-key)
               parts (str/split (str description) #"\s*—\s*" 2)
               type-str (first parts)
               constraint (second parts)]
           [:tr {:class "border-b border-base-800/50"
                 :style (str "--i:" idx)}
            [:td {:class "py-2 px-3 text-text-50 font-medium"} label]
            [:td {:class "py-2 px-3"}
             [:span {:class "inline-block px-1.5 py-0.5 rounded bg-base-800 text-text-200 text-xs font-mono"}
              type-str]]
            [:td {:class "py-2 px-3 text-text-400"} (or constraint "")]]))
       schema-map)]]
    [:div {:class "px-3 py-2 border-t border-base-800/50 text-xs text-text-500"}
     "All fields validated on every change via Malli"]]])

;;; ---------------------------------------------------------------------------
;;; Step 3 & 4: Workout Table (polished)
;;; ---------------------------------------------------------------------------

(defn- render-workout-table
  "Render a polished workout table with count badge."
  [workouts]
  [:div {:class "bg-base-900 rounded overflow-hidden"}
   [:table {:class "w-full"}
    [:thead
     [:tr {:class "border-b border-base-700"}
      [:th {:class "text-left py-2 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Exercise"]
      [:th {:class "text-center py-2 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-16"} "Sets"]
      [:th {:class "text-center py-2 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-16"} "Reps"]
      [:th {:class "text-right py-2 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-24"} "Weight"]]]
    [:tbody
     (map-indexed
      (fn [idx w]
        [:tr {:class "border-b border-base-800/50 hover:bg-base-800/30"
              :style (str "--i:" idx)}
         [:td {:class "py-2 px-3 text-text-50 font-mono text-sm font-medium"}
          (::gs/exercise w)]
         [:td {:class "py-2 px-3 text-text-200 text-sm text-center font-mono"}
          (str (::gs/sets w))]
         [:td {:class "py-2 px-3 text-text-200 text-sm text-center font-mono"}
          (str (::gs/reps w))]
         [:td {:class "py-2 px-3 text-text-200 text-sm text-right font-mono"}
          (str (::gs/weight w) " kg")]])
      workouts)]]])

(defn- render-workouts-with-form
  "Render workout table plus an add form for step 3."
  [workouts]
  [:div
   [:div {:class "flex items-center justify-between mb-2"}
    (ui/section-header "WORKOUTS")
    [:span {:class "text-xs text-text-500 font-mono"}
     (str (count workouts) " exercise" (when (not= 1 (count workouts)) "s"))]]
   (render-workout-table workouts)
   ;; Add form
   [:div {:class "mt-3 bg-base-900 rounded p-3 border border-base-800/50"}
    [:div {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"} "Add Exercise"]
    [:div {:class "flex gap-2 items-end"}
     [:div {:class "flex-1"}
      [:label {:class "text-xs text-text-500 block mb-1"} "Exercise"]
      [:input {:field ::gs/exercise
               :type "text"
               :placeholder "e.g. Pull-up"
               :class "w-full bg-base-800 border border-base-700 rounded px-2 py-1.5 text-sm text-text-50 font-mono placeholder-text-500 focus:border-signal/50 focus:outline-none"}]]
     [:div {:class "w-16"}
      [:label {:class "text-xs text-text-500 block mb-1"} "Sets"]
      [:input {:field ::gs/sets
               :type "number"
               :value "3"
               :class "w-full bg-base-800 border border-base-700 rounded px-2 py-1.5 text-sm text-text-50 font-mono text-center focus:border-signal/50 focus:outline-none"}]]
     [:div {:class "w-16"}
      [:label {:class "text-xs text-text-500 block mb-1"} "Reps"]
      [:input {:field ::gs/reps
               :type "number"
               :value "10"
               :class "w-full bg-base-800 border border-base-700 rounded px-2 py-1.5 text-sm text-text-50 font-mono text-center focus:border-signal/50 focus:outline-none"}]]
     [:div {:class "w-20"}
      [:label {:class "text-xs text-text-500 block mb-1"} "Weight"]
      [:input {:field ::gs/weight
               :type "number"
               :value "0"
               :class "w-full bg-base-800 border border-base-700 rounded px-2 py-1.5 text-sm text-text-50 font-mono text-center focus:border-signal/50 focus:outline-none"}]]
     [:div {:class "pb-0.5"}
      [:button {:on:click :add-workout!
                :class "px-3 py-1.5 text-sm font-mono rounded bg-signal/20 text-signal border border-signal/30 hover:bg-signal/30 hover:border-signal/50 transition-colors"}
       "+ Add"]]]]])

;;; ---------------------------------------------------------------------------
;;; Step 4: Stats Dashboard Cards
;;; ---------------------------------------------------------------------------

(defn- render-stat-card
  "Render a single KPI stat card."
  [idx value unit label]
  [:div {:class "bg-base-900 rounded p-3 animate-card"
         :style (str "--i:" idx)}
   [:div {:class "text-2xl font-bold text-signal font-mono animate-stat"}
    (str (format-number value))]
   [:div {:class "text-xs text-text-500 mt-0.5"} unit]
   [:div {:class "text-xs text-text-400 uppercase tracking-wider mt-1 font-semibold"} label]])

(defn- render-stats-dashboard
  "Render stats as KPI cards in a row."
  [stats workouts]
  (let [heaviest-exercise (when (seq workouts)
                            (::gs/exercise
                             (apply max-key ::gs/weight workouts)))]
    [:div
     (ui/section-header "ANALYTICS")
     [:div {:class "grid grid-cols-3 gap-3 mb-3"}
      (render-stat-card 0
                        (::gs/total-volume stats)
                        "kg total volume"
                        "Total Volume")
      (render-stat-card 1
                        (::gs/heaviest-lift stats)
                        (str "kg" (when heaviest-exercise (str " — " heaviest-exercise)))
                        "Heaviest Lift")
      (render-stat-card 2
                        (::gs/exercise-count stats)
                        (str "exercise" (when (not= 1 (::gs/exercise-count stats)) "s"))
                        "Exercise Count")]]))

;;; ---------------------------------------------------------------------------
;;; Step-Aware Data Panel
;;; ---------------------------------------------------------------------------

(defn- render-data-panel
  "Render the right panel with step-specific custom rendering."
  [ctx-value]
  (let [step (::gs/current-step ctx-value 1)]
    (case step
      ;; Step 1: Project idea cards
      1 (if-let [ideas (::gs/project-ideas ctx-value)]
          (render-project-ideas ideas)
          [:div {:class "text-text-500 text-sm italic py-4 text-center"} "No data yet."])

      ;; Step 2: Schema specification table
      2 (if-let [proposed (::gs/proposed-schema ctx-value)]
          (render-proposed-schema proposed)
          [:div {:class "text-text-500 text-sm italic py-4 text-center"} "No schema yet."])

      ;; Step 3: Workout table + add form
      3 (let [workouts (::gs/workouts ctx-value [])]
          (render-workouts-with-form workouts))

      ;; Step 4: Stats dashboard + workout table + add form
      4 (let [workouts (::gs/workouts ctx-value [])
              stats (when (seq workouts)
                      {::gs/total-volume (reduce (fn [acc w]
                                                   (+ acc (* (::gs/sets w) (::gs/reps w) (::gs/weight w))))
                                                 0 workouts)
                       ::gs/heaviest-lift (apply max (map ::gs/weight workouts))
                       ::gs/exercise-count (count (set (map ::gs/exercise workouts)))})]
          [:div
           (when stats
             (render-stats-dashboard stats workouts))
           (render-workouts-with-form workouts)])

      ;; Fallback
      [:div {:class "text-text-500 text-sm italic py-4 text-center"} "No data yet."])))

;;; ---------------------------------------------------------------------------
;;; Chat Messages
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

;;; ---------------------------------------------------------------------------
;;; Page Render Function
;;; ---------------------------------------------------------------------------

(defn page-render
  "Page renderer for seon.getting-started.
   Renders the multi-step getting started experience."
  {:malli/schema [:=> [:cat ::page-render-request] ::page-render-response]}
  [{gs-ctx ::gs/*ctx*}]
  (let [current-step (::gs/current-step gs-ctx 1)
        narrative (get gs-ctx :seon.render.default/narrative)
        messages (get gs-ctx :seon.ctx/messages [])
        narrative-html (when narrative (md/md-to-html-string narrative))
        max-steps 4]
    {:seon.render/html
     [:main#morph
      ;; Top bar
      [:div {:class "flex items-center justify-between mb-4"}
       [:h1 {:class "text-lg font-semibold tracking-tight font-mono"}
        "seon.getting-started"]
       [:div {:class "flex items-center gap-3"}
        ;; Step indicator dots
        [:div {:class "flex items-center gap-1.5"}
         (for [s (range 1 (inc max-steps))]
           [:div {:class (str "w-1.5 h-1.5 rounded-full transition-all duration-300 "
                              (if (= s current-step)
                                "bg-signal scale-125 shadow-[0_0_6px_rgba(240,180,41,0.4)]"
                                (if (<= s current-step)
                                  "bg-signal/50"
                                  "bg-base-700")))}])]
        [:span {:class "text-xs text-text-500 font-mono"}
         (str current-step) "/" (str max-steps)]
        [:a {:href "/ns/seon.getting-started?view=introspect"
             :class "px-2 py-1 text-xs font-mono rounded border text-text-500 border-base-700 hover:border-base-600 hover:text-text-200"}
         "Introspect \u2192"]]]

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

       ;; RIGHT: Step-aware data panel
       [:div {:class "bg-base-850 rounded p-4 panel-glow"
              :style "view-transition-name: data-panel"}
        (render-data-panel gs-ctx)]]

      ;; Step navigation
      [:div {:class "flex items-center justify-between mb-4"}
       (if (> current-step 1)
         [:button {:on:click :go-back!
                   :class "px-4 py-2 text-sm font-mono rounded border text-text-400 border-base-700 hover:border-base-600 hover:text-text-200"}
          "\u2190 Back"]
         [:div])
       (if (< current-step max-steps)
         [:button {:on:click :advance!
                   :class "px-4 py-2 text-sm font-mono rounded bg-signal/20 text-signal border border-signal/30 hover:bg-signal/30 hover:border-signal/50"}
          "Next \u2192"]
         [:span {:class "text-xs text-text-400 italic"} "End of walkthrough"])]

      ;; BOTTOM: Chat
      [:section {:class "bg-base-850 rounded p-3"}
       (ui/section-header "CHAT")
       (render-chat-messages messages)
       [:div {:class "flex gap-2"}
        [:input {:field :seon.ctx/user-input
                 :type "text"
                 :placeholder "Type a message..."
                 :class "flex-1 bg-base-800 border border-base-700 rounded px-3 py-2 text-sm text-text-50 font-mono placeholder-text-500"}]
        [:button {:on:click :send-message!
                  :class "px-4 py-2 text-sm font-mono rounded bg-signal/20 text-signal border border-signal/30 hover:bg-signal/30 hover:border-signal/50"}
         "Send"]]]]

     :seon.render/ai
     (str "Getting Started — Step " current-step " of " max-steps
          " | " (count messages) " messages")}))
