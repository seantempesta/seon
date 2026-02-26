(ns seon.health.workout
  "Workout tracking namespace.
   Declares data schemas, sample data, and the item-level render function.
   Page rendering lives in seon.health.workout.render."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::exercise
                  [:string {:description "Exercise name"}])

(schema/register! ::sets
                  [:int {:min 1 :description "Number of sets"}])

(schema/register! ::reps
                  [:int {:min 1 :description "Number of reps per set"}])

(schema/register! ::weight
                  [number? {:min 0 :description "Weight in kg"}])

(schema/register! ::workout-set
                  [:map
                   [::exercise ::exercise]
                   [::sets ::sets]
                   [::reps ::reps]
                   [::weight ::weight]])

(schema/register! ::workouts
                  [:vector {:description "Collection of workout sets"}
                   ::workout-set])

;;; ---------------------------------------------------------------------------
;;; Dynamic State Spec
;;; ---------------------------------------------------------------------------

(schema/register! ::*ctx*
                  [:map
                   [::workouts ::workouts]
                   [::selected-exercise {:optional true} ::exercise]])

(schema/register! ::add-set-request
                  [:map
                   [:seon.reactive/ctx :any]
                   [::exercise ::exercise]
                   [::sets ::sets]
                   [::reps ::reps]
                   [::weight ::weight]])

(schema/register! ::add-set-response ::*ctx*)

;;; ---------------------------------------------------------------------------
;;; Spec-Driven Render Specs (for Datalevin resolution)
;;; ---------------------------------------------------------------------------

(schema/register! ::workout-set-render-request
                  [:map
                   [::exercise ::exercise]
                   [::sets ::sets]
                   [::reps ::reps]
                   [::weight ::weight]])

(schema/register! ::workout-set-render-response
                  [:map
                   [:seon.render/html :any]
                   [:seon.render/ai :string]])

;;; ---------------------------------------------------------------------------
;;; Sample Data
;;; ---------------------------------------------------------------------------

(def workouts
  "Sample workout data for proof-of-life rendering."
  [{::exercise "Squat" ::sets 5 ::reps 5 ::weight 100}
   {::exercise "Bench Press" ::sets 5 ::reps 5 ::weight 80}
   {::exercise "Deadlift" ::sets 1 ::reps 5 ::weight 120}
   {::exercise "Overhead Press" ::sets 5 ::reps 5 ::weight 50}
   {::exercise "Barbell Row" ::sets 5 ::reps 5 ::weight 70}])

;;; ---------------------------------------------------------------------------
;;; Initial State
;;; ---------------------------------------------------------------------------

(defn initial-state
  "Return the initial ctx state for the workout namespace.
   Used by the lifecycle system to seed a new ctx instance."
  []
  {::workouts workouts})

;;; ---------------------------------------------------------------------------
;;; Mutation Functions
;;; ---------------------------------------------------------------------------

(defn add-set!
  "Add a workout set to the ctx atom.
   Called via POST from Datastar @post() in the browser.

   Request keys:
     :seon.reactive/ctx - The ctx atom (injected by system)
     ::exercise         - Exercise name
     ::sets             - Number of sets
     ::reps             - Number of reps per set
     ::weight           - Weight in kg

   Response keys:
     ::workouts           - Updated workout list
     ::selected-exercise  - (optional) Selected exercise

   Example:
     (add-set! {:seon.reactive/ctx (atom {::workouts []})
                ::exercise \"Squat\" ::sets 5 ::reps 5 ::weight 100})"
  {:malli/schema [:=> [:cat ::add-set-request] ::add-set-response]}
  [{ctx-atom :seon.reactive/ctx
    ::keys [exercise sets reps weight]}]
  (swap! ctx-atom update ::workouts conj
         {::exercise exercise ::sets sets ::reps reps ::weight weight}))

;;; ---------------------------------------------------------------------------
;;; Render Function (discovered by scanner via naming convention)
;;; ---------------------------------------------------------------------------

(defn workout-set-render
  "Render a single workout set for all formats.
   Discovered by scanner via ::workout-set-render-request/-response specs.

   Returns map with :seon.render/html and :seon.render/ai keys."
  {:malli/schema [:=> [:cat ::workout-set-render-request] ::workout-set-render-response]}
  [{::keys [exercise sets reps weight]}]
  {:seon.render/ai (str exercise " — " sets "x" reps " @ " weight "kg")
   :seon.render/html [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
                      [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} exercise]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str sets)]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str reps)]
                      [:td {:class "py-2 px-3 text-text-200 text-sm text-right"} (str weight " kg")]]})
