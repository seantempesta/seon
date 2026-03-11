# The Ctx-as-OS Pattern

**Core Insight:** The entire system is one data structure. UI is derived. Agent writes data. Specs constrain writes.

---

## The Game Engine Parallel

Games run at 60fps with complex interactions. They don't "think" per frame:

```
Planning Phase (slow, AI/designer):
  → Pre-compute possible futures
  → Register behaviors: "if X, do Y"
  → Queue assets to load

Execution Phase (fast, 60fps):
  → Check registered behaviors
  → Execute matching ones
  → Interpolate/render state

Re-planning (only when needed):
  → Unexpected input
  → State invalidated
  → Player does something novel

```

**The agent is in planning phase.** It doesn't generate responses in real-time - it sets up conditional logic ahead of time.

---

## Applied to Seon: The Ctx Atom

```clojure
(def ctx
  (atom
    {;; === PRIMER "APP" ===
     :primer/current-scene
       {:scene/id "forest-crossroads"
        :scene/template :narrative/choice
        :scene/params {...}}

     :primer/behaviors
       {;; Pre-arranged: agent computed these AHEAD of time
        :tap-left-path
          {:fn 'primer.transitions/goto-scene
           :args {:scene-id "dark-path"}
           :pre-computed? true}

        :tap-right-path
          {:fn 'primer.transitions/goto-scene
           :args {:scene-id "sunny-path"}
           :pre-computed? true}

        ;; Dynamic: requires AI reasoning
        :voice-input
          {:fn 'primer.ai/interpret-voice
           :args {:context :current-scene}
           :pre-computed? false}}

     :primer/assets
       {:dark-path-img {:status :ready :url "..."}
        :sunny-path-img {:status :loading}}

     :primer/child-profile
       {:interests #{:dinosaurs :stars}
        :struggles [:patience]
        :emotional-valence 0.7}

     ;; === OTHER "APPS" ===
     :trading/watchlist [...]
     :health/latest-hrv {...}

     ;; === UI ROUTING ===
     :ui/active-app :primer
     :ui/screens
       {:home [:primer/summary :health/summary :trading/alerts]
        :primer [:primer/current-scene]}}))

```

---

## The Render Function

Rendering is just **walking the data**:

```clojure
(defn render [ctx]
  (let [active-app (:ui/active-app ctx)
        screen-keys (get-in ctx [:ui/screens active-app])]
    [:div#morph
     (for [k screen-keys]
       (render-key ctx k))]))

(defmulti render-key (fn [ctx k] (namespace k)))

(defmethod render-key "primer" [ctx k]
  (case k
    :primer/current-scene (render-scene ctx (get ctx k))
    :primer/summary (render-primer-summary ctx)))

(defmethod render-key "health" [ctx k]
  (case k
    :health/summary (render-health-summary ctx (get ctx k))))

```

**Adding a new "app":**
1. Add namespaced keys to ctx
2. Add render-key method for that namespace
3. Done. It's just data.

---

## The Behavior System (Game Engine Style)

When user acts, we check pre-computed behaviors:

```clojure
(defn handle-action [ctx action-id payload]
  (let [behavior (get-in ctx [:primer/behaviors action-id])]
    (cond
      ;; Pre-computed path: instant execution
      (:pre-computed? behavior)
      (apply-transition ctx (:fn behavior) (:args behavior))

      ;; Dynamic: invoke AI
      (not (:pre-computed? behavior))
      (do
        (show-thinking-state ctx)
        (request-ai-decision ctx action-id payload))

      ;; Unknown action
      :else
      (log/warn "Unknown action" action-id))))

(defn apply-transition [ctx fn-sym args]
  (let [f (resolve fn-sym)]
    (swap! ctx-atom #(f % args))))

```

**The agent's job during "planning phase":**

```clojure
;; Agent output (structured, not HTML!)
{:action :update-behaviors
 :behaviors
   {:tap-owl
     {:fn 'primer.transitions/start-dialogue
      :args {:character :owl
             :greeting "Who goes there?"
             :tone :curious}
      :pre-computed? true
      :pre-rendered-html "<div class='dialogue'>..."}  ; Optional: pre-render too

    :ask-about-path
     {:fn 'primer.ai/dialogue-response
      :args {:topic :path-choice :character :owl}
      :pre-computed? false}}}

```

---

## Specs as Contract

**The agent can only write data that matches specs:**

```clojure
(def Behavior
  [:map
   [:fn qualified-symbol?]
   [:args :map]
   [:pre-computed? :boolean]
   [:pre-rendered-html {:optional true} :string]])

(def SceneParams
  [:map
   [:scene/id :string]
   [:scene/template [:enum :narrative/page :narrative/choice :dialogue/exchange ...]]
   [:scene/params :map]])

(def CtxUpdate
  [:map
   [:action [:enum :update-behaviors :update-scene :update-child-profile]]
   [:behaviors {:optional true} [:map-of :keyword Behavior]]
   [:scene {:optional true} SceneParams]
   ...])

```

**Validation on every agent write:**

```clojure
(defn agent-update! [ctx-atom update-data]
  (when-not (m/validate CtxUpdate update-data)
    (throw (ex-info "Invalid agent output"
                    {:errors (m/explain CtxUpdate update-data)})))
  (apply-update! ctx-atom update-data))

```

**Benefits:**
1. Agent can't write garbage
2. Agent learns the vocabulary (specs in prompt)
3. Structured output = predictable behavior
4. Debugging: inspect what agent wrote

---

## The Full Loop

```
┌─────────────────────────────────────────────────────────┐
│                    PLANNING PHASE                        │
│                   (Agent, async)                         │
│                                                          │
│  Agent receives: current ctx, child profile, story arc   │
│  Agent outputs: behaviors, pre-computed paths, assets    │
│  Validated against: Malli specs                          │
│  Written to: ctx atom                                    │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   EXECUTION PHASE                        │
│                 (Runtime, 60fps-capable)                 │
│                                                          │
│  User acts → Look up behavior in ctx                     │
│            → If pre-computed: execute instantly          │
│            → If dynamic: show wait, invoke AI            │
│  Ctx changes → Watch triggers render                     │
│             → SSE pushes to client                       │
│             → Datastar morphs DOM                        │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   RE-PLANNING TRIGGER                    │
│                                                          │
│  When:                                                   │
│    - Scene transition completed                          │
│    - Dynamic action invoked                              │
│    - Child state changed significantly                   │
│    - Timeout (proactive planning)                        │
│                                                          │
│  Agent plans next N interactions                         │
└─────────────────────────────────────────────────────────┘

```

---

## Namespaces as Apps

Each namespace is a self-contained "app":

| Namespace | Ctx Keys | Render Method | Capabilities |
|-----------|----------|---------------|--------------|
| `primer` | `:primer/*` | `render-key "primer"` | Interactive stories |
| `health` | `:health/*` | `render-key "health"` | HRV tracking |
| `trading` | `:trading/*` | `render-key "trading"` | Options analysis |
| `tasks` | `:tasks/*` | `render-key "tasks"` | Todo management |

**Home screen** is just composing keys from multiple namespaces:

```clojure
{:ui/screens
  {:home [:primer/summary      ; "Continue your story..."
          :health/today        ; "HRV: 45ms"
          :trading/alerts      ; "AAPL IV rank: 85%"
          :tasks/due-today]}}  ; "3 tasks due"

```

---

## Why This Matters

1. **Agent writes functions, not prose**
   - Output is structured data
   - Specs enforce contract
   - No prompt injection via HTML

2. **Instant interactions (pre-computed)**
   - Agent does heavy lifting ahead of time
   - Runtime just looks up and executes
   - 60fps possible

3. **Graceful degradation to dynamic**
   - Unknown input? Invoke AI
   - Show wait state
   - Re-plan

4. **Everything is checkpointable**
   - Ctx is EDN
   - Store in XTDB
   - Replay any moment

5. **Extensible by adding data**
   - New app = new namespace keys
   - New render method
   - No framework changes

---

## Next Step: Prototype

Build the minimal version:
1. One ctx atom
2. One scene with two pre-computed behaviors
3. One dynamic behavior (AI fallback)
4. Render → SSE → Datastar
5. Validate: does it feel instant?
