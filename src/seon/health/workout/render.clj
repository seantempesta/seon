(ns seon.health.workout.render
  "Render companion for seon.health.workout.
   Contains page-render (page renderer) and workout-set (item renderer).
   Uses parent namespace's specs directly — they're fully qualified."
  (:require [clojure.string :as str]
            [seon.health.workout :as workout]
            [seon.schema :as schema]
            [seon.web.components :as ui]))

;;; ---------------------------------------------------------------------------
;;; Item Renderer Specs
;;; ---------------------------------------------------------------------------

(schema/register! ::workout-set-request
                  [:map
                   [::workout/exercise ::workout/exercise]
                   [::workout/sets ::workout/sets]
                   [::workout/reps ::workout/reps]
                   [::workout/weight ::workout/weight]])

(schema/register! ::workout-set-response
                  [:map
                   [:seon.render/html :any]
                   [:seon.render/ai :string]])

;;; ---------------------------------------------------------------------------
;;; Page Renderer Specs
;;; ---------------------------------------------------------------------------

(schema/register! ::page-render-request
                  [:map
                   [::workout/*ctx* ::workout/*ctx*]])

(schema/register! ::page-render-response
                  [:map
                   [:seon.render/html :any]
                   [:seon.render/ai :string]])

;;; ---------------------------------------------------------------------------
;;; Render Functions
;;; ---------------------------------------------------------------------------

(defn workout-set
  "Renders a workout set for both HTML and AI."
  {:malli/schema [:=> [:cat ::workout-set-request] ::workout-set-response]}
  [{:seon.health.workout/keys [exercise sets reps weight]}]
  {:seon.render/html [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
                      [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} exercise]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str sets)]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str reps)]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-right"} (str weight " kg")]]
   :seon.render/ai (str exercise " — " sets "x" reps " @ " weight "kg")})

(defn page-render
  "Page renderer for seon.health.workout.
   Receives the ctx atom value (deref'd). Composes workout-set.

   Scanner detects this as a page renderer because its input spec
   includes :seon.health.workout/*ctx* — a key ending in *ctx*."
  {:malli/schema [:=> [:cat ::page-render-request] ::page-render-response]}
  [{workout-ctx ::workout/*ctx*}]
  (let [ws (::workout/workouts workout-ctx)]
    {:seon.render/html
     [:main#morph
      [:div {:class "mb-4"}
       [:h1 {:class "text-lg font-semibold tracking-tight font-mono"}
        "seon.health.workout"]
       [:p {:class "text-text-400 text-sm mt-2"}
        "Workout tracking. " (count ws) " exercises."]]
      [:section {:class "mb-6"}
       (ui/section-header "TODAY'S WORKOUT")
       [:div {:class "bg-base-850 rounded overflow-hidden"}
        [:table {:class "w-full"}
         [:thead
          [:tr {:class "border-b border-base-700"}
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Exercise"]
           [:th {:class "text-center py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "Sets"]
           [:th {:class "text-center py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "Reps"]
           [:th {:class "text-right py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-24"} "Weight"]]]
         [:tbody
          (map (comp :seon.render/html workout-set) ws)]]]]]
     :seon.render/ai
     (str "Workout: " (count ws) " exercises. "
          (str/join ", " (map #(:seon.render/ai (workout-set %)) ws)))}))
