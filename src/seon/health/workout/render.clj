(ns seon.health.workout.render
  "Render companion for workout data.
   Demonstrates the .render namespace convention for spec-driven rendering."
  (:require [seon.health.workout :as workout]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
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
;;; Render Functions
;;; ---------------------------------------------------------------------------

(defn workout-set
  "Renders a workout set for both HTML and AI."
  [{:seon.health.workout/keys [exercise sets reps weight]}]
  {:seon.render/html [:tr [:td exercise] [:td (str sets "x" reps)] [:td (str weight "kg")]]
   :seon.render/ai   (str exercise " — " sets "x" reps " @ " weight "kg")})
