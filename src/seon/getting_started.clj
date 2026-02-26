(ns seon.getting-started
  "Getting started experience for Seon.

   A multi-step walkthrough demonstrating the living document UX.
   Each step provides a different narrative + data combination,
   showing how a namespace page evolves as an agent builds features."
  (:require [clojure.string :as str]
            [seon.render.default-page]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::current-step
                  [:int {:min 1 :max 4 :description "Current step index (1-4)"}])

(schema/register! ::exercise
                  [:string {:description "Exercise name"}])

(schema/register! ::sets
                  [:int {:min 1 :description "Number of sets"}])

(schema/register! ::reps
                  [:int {:min 1 :description "Number of reps"}])

(schema/register! ::weight
                  [number? {:min 0 :description "Weight in kg"}])

(schema/register! ::workout-entry
                  [:map
                   [::exercise ::exercise]
                   [::sets ::sets]
                   [::reps ::reps]
                   [::weight ::weight]])

(schema/register! ::workouts
                  [:vector {:description "Workout entries"} ::workout-entry])

(schema/register! ::total-volume
                  [number? {:min 0 :description "Total volume (sets * reps * weight)"}])

(schema/register! ::heaviest-lift
                  [number? {:min 0 :description "Heaviest single weight"}])

(schema/register! ::exercise-count
                  [:int {:min 0 :description "Number of unique exercises"}])

(schema/register! ::project-ideas
                  [:vector {:description "Example project ideas"}
                   [:map
                    [:title :string]
                    [:description :string]]])

(schema/register! ::proposed-schema
                  [:map {:description "Proposed data schema preview"}])

(schema/register! ::stats
                  [:map
                   [::total-volume ::total-volume]
                   [::heaviest-lift ::heaviest-lift]
                   [::exercise-count ::exercise-count]])

(schema/register! ::*ctx*
                  [:map
                   [::current-step ::current-step]
                   [:seon.render.default/narrative :seon.render.default/narrative]
                   [:seon.ctx/messages :seon.ctx/messages]
                   [::project-ideas {:optional true} ::project-ideas]
                   [::proposed-schema {:optional true} ::proposed-schema]
                   [::workouts {:optional true} ::workouts]
                   [::stats {:optional true} ::stats]
                   [:seon.ctx/user-input {:optional true} :seon.ctx/user-input]])

;;; ---------------------------------------------------------------------------
;;; Step Data
;;; ---------------------------------------------------------------------------

(defn step-1
  "Welcome step. Introduces Seon and asks what to build."
  []
  {::current-step 1
   :seon.render.default/narrative
   "# Welcome to Seon

You're looking at a **living document** — a page that evolves as you and your AI agent collaborate.

This isn't a static dashboard. It's a workspace where:

- **The left panel** shows narrative context — what's happening, what's planned, what to do next
- **The right panel** shows live data — schemas, tables, stats, whatever your namespace produces
- **The bottom** is where you talk to your agent

## What would you like to build today?

Try something like *\"a workout tracker\"* or *\"a reading list\"* or *\"a budget planner\"*.

Or just click **Next** to see a demo walkthrough."
   :seon.ctx/messages []
   ::project-ideas [{:title "Workout Tracker"
                     :description "Track exercises, sets, reps, and weight. See progress over time."}
                    {:title "Reading List"
                     :description "Organize books, articles, and papers. Track what you've read."}
                    {:title "Budget Planner"
                     :description "Categorize expenses, set budgets, see where money goes."}]})

(defn step-2
  "User said 'workout tracker'. Agent proposes structure."
  []
  {::current-step 2
   :seon.render.default/narrative
   "# Great choice — a Workout Tracker

Here's what I'm thinking for the data model:

Each workout entry tracks:
- **Exercise** name (e.g. Squat, Bench Press)
- **Sets** count
- **Reps** per set
- **Weight** in kg

The schema is shown on the right. This is a Malli spec — it means every piece of data is validated, and I can generate test data automatically.

```clojure
(schema/register! ::workout-entry
  [:map
   [::exercise [:string]]
   [::sets     [:int {:min 1}]]
   [::reps     [:int {:min 1}]]
   [::weight   [number? {:min 0}]]])
```

Once you're happy with the structure, I'll set up the namespace with:
- An `initial-state` function
- Action functions for adding/removing entries
- A custom renderer for the workout table

Click **Next** to see it built out."
   :seon.ctx/messages [{:role :user :content "I want to build a workout tracker"}
                       {:role :assistant :content "Great choice! Let me design a data model for tracking exercises, sets, reps, and weight."}]
   ::proposed-schema {:exercise "string — exercise name"
                      :sets "int >= 1 — number of sets"
                      :reps "int >= 1 — reps per set"
                      :weight "number >= 0 — weight in kg"}})

(defn step-3
  "Basics are built. Shows workout data with add form."
  []
  {::current-step 3
   :seon.render.default/narrative
   "# The basics are set up

I've created the workout namespace with:

- **Malli schemas** for every field (validated on every change)
- **Sample data** so you can see how it looks
- **An add form** so you can customize exercises

The table on the right is *live* — it updates when you add entries. Try adding an exercise using the form below the table.

Each row shows the exercise, sets, reps, and weight. The data is persisted to Datalevin, so it survives restarts.

## Next steps

I can add:
- Volume calculations (sets x reps x weight)
- Personal records tracking
- Workout history over time
- Charts and trends

Click **Next** to see analytics added."
   :seon.ctx/messages [{:role :user :content "I want to build a workout tracker"}
                       {:role :assistant :content "Great choice! Let me design a data model for tracking exercises, sets, reps, and weight."}
                       {:role :user :content "Looks good, build it"}
                       {:role :assistant :content "Done! I've set up the namespace with schemas, sample data, and an add form. Take a look."}]
   ::workouts [{::exercise "Squat" ::sets 5 ::reps 5 ::weight 100}
               {::exercise "Bench Press" ::sets 5 ::reps 5 ::weight 80}
               {::exercise "Deadlift" ::sets 1 ::reps 5 ::weight 120}
               {::exercise "Overhead Press" ::sets 5 ::reps 5 ::weight 50}
               {::exercise "Barbell Row" ::sets 5 ::reps 5 ::weight 70}]})

(defn step-4
  "Refined with analytics. Stats are computed in the renderer from workouts."
  []
  {::current-step 4
   :seon.render.default/narrative
   "# Looking good — analytics added

I've calculated some summary stats from your workout data:

- **Total volume** across all exercises (sets x reps x weight)
- **Heaviest lift** — your max weight in a single exercise
- **Exercise count** — unique movements in your program

These update automatically as you add or modify entries. Try adding an exercise — the stats recalculate instantly.

## What's next?

This is the basic flow of building with Seon. Your agent:
1. Listened to what you wanted
2. Proposed a data model
3. Built the namespace with schemas and UI
4. Added analytics on top

Every namespace in Seon works this way — a living document that grows with your needs.

You can navigate to any namespace using `/ns/seon.your-namespace` in the URL bar."
   :seon.ctx/messages [{:role :user :content "I want to build a workout tracker"}
                       {:role :assistant :content "Great choice! Let me design a data model."}
                       {:role :user :content "Looks good, build it"}
                       {:role :assistant :content "Done! Namespace set up with schemas and sample data."}
                       {:role :user :content "Can you add some analytics?"}
                       {:role :assistant :content "Added total volume, heaviest lift, and exercise count. Stats update automatically."}]
   ::workouts [{::exercise "Squat" ::sets 5 ::reps 5 ::weight 100}
               {::exercise "Bench Press" ::sets 5 ::reps 5 ::weight 80}
               {::exercise "Deadlift" ::sets 1 ::reps 5 ::weight 120}
               {::exercise "Overhead Press" ::sets 5 ::reps 5 ::weight 50}
               {::exercise "Barbell Row" ::sets 5 ::reps 5 ::weight 70}]})

;;; ---------------------------------------------------------------------------
;;; Step Navigation
;;; ---------------------------------------------------------------------------

(def ^:private steps [step-1 step-2 step-3 step-4])

(defn initial-state
  "Return Step 1 as the initial ctx state."
  []
  (step-1))

(defn advance!
  "Move to the next step, preserving user-added workouts.
   Step template data is merged under current ctx (current wins for ::workouts)."
  {:malli/schema [:=> [:cat [:map [:seon.reactive/ctx :any]]] :any]}
  [{ctx-atom :seon.reactive/ctx}]
  (swap! ctx-atom
         (fn [ctx]
           (let [current (::current-step ctx 1)
                 next-step (min 4 (inc current))
                 step-fn (nth steps (dec next-step))
                 step-defaults (step-fn)
                 user-workouts (::workouts ctx)]
             ;; Merge: step defaults first, then overlay user data
             (cond-> (merge step-defaults
                            (select-keys ctx [::workouts :seon.ctx/messages]))
               ;; If user had workouts, keep them (not the step defaults)
               user-workouts (assoc ::workouts user-workouts))))))

(defn go-back!
  "Move to the previous step, preserving user-added workouts."
  {:malli/schema [:=> [:cat [:map [:seon.reactive/ctx :any]]] :any]}
  [{ctx-atom :seon.reactive/ctx}]
  (swap! ctx-atom
         (fn [ctx]
           (let [current (::current-step ctx 1)
                 prev-step (max 1 (dec current))
                 step-fn (nth steps (dec prev-step))
                 step-defaults (step-fn)
                 user-workouts (::workouts ctx)]
             (cond-> (merge step-defaults
                            (select-keys ctx [::workouts :seon.ctx/messages]))
               user-workouts (assoc ::workouts user-workouts))))))

(defn add-workout!
  "Add a workout entry from form fields."
  {:malli/schema [:=> [:cat [:map
                              [:seon.reactive/ctx :any]
                              [::exercise {:optional true} :string]
                              [::sets {:optional true} :string]
                              [::reps {:optional true} :string]
                              [::weight {:optional true} :string]]] :any]}
  [{ctx-atom :seon.reactive/ctx
    :keys [seon.getting-started/exercise
           seon.getting-started/sets
           seon.getting-started/reps
           seon.getting-started/weight]}]
  (when (and exercise (not (str/blank? exercise)))
    (let [entry {::exercise exercise
                 ::sets (try (parse-long sets) (catch Exception _ 3))
                 ::reps (try (parse-long reps) (catch Exception _ 10))
                 ::weight (try (parse-double weight) (catch Exception _ 0))}]
      (swap! ctx-atom update ::workouts (fnil conj []) entry))))

(defn send-message!
  "Stub: append user message to chat history."
  {:malli/schema [:=> [:cat [:map [:seon.reactive/ctx :any]
                             [:seon.ctx/user-input {:optional true} :string]]] :any]}
  [{ctx-atom :seon.reactive/ctx :keys [seon.ctx/user-input]}]
  (when (and user-input (not (str/blank? user-input)))
    (swap! ctx-atom
           (fn [ctx]
             (-> ctx
                 (update :seon.ctx/messages conj {:role :user :content user-input})
                 (assoc :seon.ctx/user-input ""))))))
