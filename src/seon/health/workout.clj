(ns seon.health.workout
  "Workout tracking namespace.
   Provides a custom render function for /ns/seon.health.workout."
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [seon.web.components :as ui]))

;;; ---------------------------------------------------------------------------
;;; Sample Data
;;; ---------------------------------------------------------------------------

(def ^:private sample-workouts
  "Sample workout data for proof-of-life rendering."
  [{:exercise "Squat" :sets 5 :reps 5 :weight 100}
   {:exercise "Bench Press" :sets 5 :reps 5 :weight 80}
   {:exercise "Deadlift" :sets 1 :reps 5 :weight 120}
   {:exercise "Overhead Press" :sets 5 :reps 5 :weight 50}
   {:exercise "Barbell Row" :sets 5 :reps 5 :weight 70}])

;;; ---------------------------------------------------------------------------
;;; Rendering
;;; ---------------------------------------------------------------------------

(defn- render-workout-row
  "Render a single workout as a table row."
  [{:keys [exercise sets reps weight]}]
  [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
   [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} exercise]
   [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str sets)]
   [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str reps)]
   [:td {:class "py-2 px-3 text-text-200 text-sm text-right"} (str weight " kg")]])

(defn render
  "Render workout view. Called by ns/routes when visiting /ns/seon.health.workout.

   Arguments:
     opts - Map with :format (:html/:ai/:raw) and :id (optional instance id)

   Returns HTML string for :html format."
  [{:keys [format id]}]
  (case format
    :html
    (h/html
     [:main#morph
      [:div {:class "mb-4"}
       [:h1 {:class "text-lg font-semibold tracking-tight font-mono"} "seon.health.workout"]
       (when id
         [:p {:class "text-text-400 text-xs mt-0.5"}
          "Session: " [:code {:class "text-signal"} id]])
       [:p {:class "text-text-400 text-sm mt-2"}
        "Workout tracking. Proof-of-life for the render pipeline."]]

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
          (for [w sample-workouts]
            (render-workout-row w))]]]]])

    :ai
    (str "Workout: "
         (count sample-workouts) " exercises. "
         (str/join ", "
                   (map (fn [{:keys [exercise sets reps weight]}]
                          (str exercise " " sets "x" reps " @ " weight "kg"))
                        sample-workouts)))

    :raw
    {:workouts sample-workouts}

    ;; Default
    (str "Unknown format: " format)))
