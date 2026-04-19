(ns seon.getting-started
  "## Purpose

   Getting started experience for Seon. A 4-step interactive walkthrough
   demonstrating the living document UX. Each step shows a different
   narrative + data combination, evolving from 'What would you like to
   build?' to a working workout tracker with analytics.

   This namespace is both a demo and a test of the renderer resolution
   system: it provides step functions that populate *ctx*, and a sibling
   renderer (seon.getting-started.render/page-render) that renders the
   UI with step navigation buttons.

   ## Architecture Position
   - Child: seon.getting-started.render — page renderer for this namespace
   - Consumer: seon.ns.routes — routes to this namespace for /ns/seon.getting-started
   - Consumer: seon.render-test — uses ::exercise in humanize tests
   - Consumer: seon.web.reactive.transform — docs reference as example
   - Depends on: seon.render.default-page (namespace require, no direct usage)
   - Depends on: seon.schema (schema registration)

   ## Consumer Analysis
   seon.getting-started.render: Primary consumer. Reads ::*ctx* schema
   (line 19), references ::gs/current-step, ::gs/workouts, ::gs/exercise,
   ::gs/sets, ::gs/reps, ::gs/weight, ::gs/project-ideas, ::gs/proposed-schema,
   ::gs/total-volume, ::gs/heaviest-lift, ::gs/exercise-count. Also reads
   :seon.render.default-page/narrative and :seon.render.default-page/messages
   from ctx values. Clean usage, no workarounds.

   seon.ns.routes: References in docstrings as example for namespace routing.
   No direct function calls.

   seon.render-test: Uses ::exercise in humanize test (line 150). Tests that
   'seon.getting-started/exercise' humanizes to 'Exercise'. No coupling issues.

   ## Public API Assessment
   | Function      | Status    | Notes                                      |
   |---------------|-----------|-------------------------------------------|
   | initial-state | OK        | Returns step-1 data                       |
   | advance!      | OK        | Has :malli/schema, map-in pattern         |
   | go-back!      | OK        | Has :malli/schema, map-in pattern         |
   | add-workout!  | OK        | Has :malli/schema, map-in pattern         |
   | send-message! | OK        | Has :malli/schema, stub implementation    |
   | step-1        | NO_SCHEMA | Returns step data, no :malli/schema       |
   | step-2        | NO_SCHEMA | Returns step data, no :malli/schema       |
   | step-3        | NO_SCHEMA | Returns step data, no :malli/schema       |
   | step-4        | NO_SCHEMA | Returns step data, no :malli/schema       |

   ## Convention Compliance
   - Malli schemas: PARTIAL — ::*ctx* registered, action fns have schemas,
     step fns lack :malli/schema metadata
   - Map-in/map-out: PASS — action functions use single-map arguments
   - Namespaced keys: PASS — all keys properly namespaced (::exercise etc)
   - Docstrings: PARTIAL — action fns have docstrings, step fns minimal
   - Tests: PASS — 7 tests, 38 assertions

   ## Strategic Assessment
   This namespace serves a clear purpose: demo/onboarding. It should stay
   as a self-contained walkthrough. The step functions are internal helpers
   that could be made private (defn-). The action functions (advance!,
   go-back!, add-workout!, send-message!) are the true public API.

   The sibling namespace seon.getting-started.render handles all UI concerns.
   This separation is clean: data here, presentation there.

   ## Issues (Prioritized)
   - P2 - step-1 through step-4 lack :malli/schema metadata (blocks discoverability)
   - P3 - send-message! is a stub (appends to messages but no AI response)

   ## What's Good
   - Clean 4-step progression with clear narrative per step
   - Comprehensive Malli schemas for workout domain (::exercise, ::sets, etc)
   - Action functions follow map-in convention with :malli/schema
   - advance!/go-back! preserve user workout data across steps
   - Schema registrations grouped at top

   ## Recommendations
   1. Add :malli/schema to step-1 through step-4 (small)
   2. Create test file with example tests for initial-state, advance! (medium)
   3. Make step-1 through step-4 private (defn-) since they're internal (small)
   4. Wire send-message! to actual agent or document as stub (medium)

   ## Incoming Requests
   - 2026-02-26 from seon.render.default-page: Migrate from :seon.ctx/messages,
     :seon.ctx/user-input to :seon.render.default-page/* keys. Also migrate
     :seon.render.default/narrative to :seon.render.default-page/narrative.
     — DONE

   ## Requested Changes (for other namespace agents)
   - seon.getting-started.render: Migrate :seon.render.default/narrative (line 267)
     to :seon.render.default-page/narrative. Migrate :seon.ctx/messages (line 268)
     to :seon.render.default-page/messages. Migrate :seon.ctx/user-input (line 327)
     to :seon.render.default-page/user-input.

   ## Audit Metadata
   Audited: 2026-02-26
   Auditor: claude-opus-4-6
   Commit: 98fa52d
   Tests: 7 pass / 0 fail (38 assertions)"
  (:require [clojure.string :as str]
            [seon.render.default-page :as dp]
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
                   [::dp/narrative ::dp/narrative]
                   [::dp/messages ::dp/messages]
                   [::project-ideas {:optional true} ::project-ideas]
                   [::proposed-schema {:optional true} ::proposed-schema]
                   [::workouts {:optional true} ::workouts]
                   [::stats {:optional true} ::stats]
                   [::dp/user-input {:optional true} ::dp/user-input]])

;;; ---------------------------------------------------------------------------
;;; Step Data
;;; ---------------------------------------------------------------------------

(defn step-1
  "Welcome step. Introduces Seon and asks what to build."
  []
  {::current-step 1
   ::dp/narrative
   "# Welcome to Seon

You're looking at a **living document** — a page that evolves as you and your AI agent collaborate.

This isn't a static dashboard. It's a workspace where:

- **The left panel** shows narrative context — what's happening, what's planned, what to do next
- **The right panel** shows live data — schemas, tables, stats, whatever your namespace produces
- **The bottom** is where you talk to your agent

## What would you like to build today?

Try something like *\"a workout tracker\"* or *\"a reading list\"* or *\"a budget planner\"*.

Or just click **Next** to see a demo walkthrough."
   ::dp/messages []
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
   ::dp/narrative
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
   ::dp/messages [{:role :user :content "I want to build a workout tracker"}
                  {:role :assistant :content "Great choice! Let me design a data model for tracking exercises, sets, reps, and weight."}]
   ::proposed-schema {:exercise "string — exercise name"
                      :sets "int >= 1 — number of sets"
                      :reps "int >= 1 — reps per set"
                      :weight "number >= 0 — weight in kg"}})

(defn step-3
  "Basics are built. Shows workout data with add form."
  []
  {::current-step 3
   ::dp/narrative
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
   ::dp/messages [{:role :user :content "I want to build a workout tracker"}
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
   ::dp/narrative
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
   ::dp/messages [{:role :user :content "I want to build a workout tracker"}
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
                            (select-keys ctx [::workouts ::dp/messages]))
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
                            (select-keys ctx [::workouts ::dp/messages]))
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
                             [::dp/user-input {:optional true} :string]]] :any]}
  [{ctx-atom :seon.reactive/ctx user-input ::dp/user-input}]
  (when (and user-input (not (str/blank? user-input)))
    (swap! ctx-atom
           (fn [ctx]
             (-> ctx
                 (update ::dp/messages conj {:role :user :content user-input})
                 (assoc ::dp/user-input ""))))))
