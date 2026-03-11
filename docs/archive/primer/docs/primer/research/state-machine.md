# The Primer Loop: State Machine & Checkpointing

**The Simple Loop:**

```
render(state) → wait(input) → transition(input) → checkpoint(state) → render(state)

```

That's it. Everything else is implementation detail.

---

## The State

A **Session State** contains everything needed to render the current moment:

```clojure
{;; Identity
 :session/id #uuid "..."
 :child/id #uuid "..."
 :device/id "..."

 ;; Current Scene
 :scene/current
   {:scene/id "forest-crossroads"
    :scene/type :narrative/choice
    :scene/template :narrative/choice
    :scene/params {...}
    :scene/entered-at #inst "..."}

 ;; Scene Stack (for nested interactions, dialogues, etc)
 :scene/stack []   ; push when entering sub-scene, pop on return

 ;; Story Context
 :story/current-arc :learning-patience
 :story/position [:chapter-1 :page-5]
 :story/facts-established #{:met-owl :has-lantern}
 :story/choices-made [{:scene "fork" :choice :left-path}]

 ;; Child State (AI-inferred)
 :child/emotional-valence 0.7      ; -1 to 1
 :child/engagement 0.85            ; 0 to 1
 :child/recent-struggles [:sharing :patience]
 :child/interests #{:dinosaurs :stars :purple}

 ;; Assets (pre-loaded references)
 :assets/pending #{:image-id-123}  ; being generated
 :assets/loaded {:nell-portrait "url..." :bg-forest "url..."}

 ;; Meta
 :session/started-at #inst "..."
 :session/last-activity-at #inst "..."
 :session/interaction-count 47}

```

---

## State Transitions

Every user action produces a **Transition**:

```clojure
{:transition/type :action           ; or :timeout, :asset-ready, :ai-response
 :transition/source :user           ; or :system
 :transition/action :choice-selected
 :transition/payload {:choice-id :left-path}
 :transition/timestamp #inst "..."}

```

The **Transition Function** produces next state:

```clojure
(defn transition [current-state transition]
  (let [{:keys [transition/type transition/action transition/payload]} transition]
    (case type
      :action (handle-user-action current-state action payload)
      :timeout (handle-timeout current-state)
      :asset-ready (handle-asset-ready current-state payload)
      :ai-response (handle-ai-response current-state payload))))

```

---

## The Three Transition Paths

### Path 1: Deterministic (No AI)

Simple, predetermined transitions. Fast.

```clojure
(defn handle-tap-continue [state]
  (let [next-scene-id (get-in state [:scene/current :scene/next])]
    (assoc state :scene/current (load-scene next-scene-id))))

```

**Examples:**
- Tap to continue
- Turn page
- Close dialogue
- Menu navigation

### Path 2: AI-Reasoned (Async)

AI decides what happens next. Requires wait state.

```clojure
(defn handle-choice [state {:keys [choice-id]}]
  ;; 1. Show wait state
  (let [wait-state (assoc state
                     :scene/current (wait-scene "Thinking..."))]
    ;; 2. Request AI decision (async)
    (request-ai-decision! state choice-id)
    ;; 3. Return wait state (AI response comes later)
    wait-state))

(defn handle-ai-response [state {:keys [decision]}]
  ;; AI has decided, apply it
  (let [next-scene (build-scene-from-decision decision)]
    (assoc state :scene/current next-scene)))

```

**Examples:**
- Story branching decisions
- Free-form dialogue responses
- Adaptive difficulty adjustments
- Emotional response to child's input

### Path 3: Hybrid (Optimistic + Correction)

Start with deterministic guess, correct if AI disagrees.

```clojure
(defn handle-voice-input [state {:keys [transcript]}]
  ;; 1. Optimistic: Show listening confirmation
  (let [optimistic-state (show-listening-indicator state)]
    ;; 2. Request AI interpretation (async)
    (request-ai-interpretation! state transcript)
    ;; 3. Return optimistic state
    optimistic-state))

```

**Examples:**
- Voice input (show "I heard you" immediately)
- Drawing interpretation (save drawing, analyze async)
- Complex puzzle solutions

---

## Scene Stack: Nested Interactions

Sometimes we need to "pause" a scene to do something else:

```
Story Page (main)
  └── Dialogue with Owl (pushed)
       └── Puzzle: Answer Riddle (pushed)

```

When riddle is solved, pop back to dialogue. When dialogue ends, pop back to story.

```clojure
(defn push-scene [state new-scene]
  (-> state
      (update :scene/stack conj (:scene/current state))
      (assoc :scene/current new-scene)))

(defn pop-scene [state]
  (let [stack (:scene/stack state)
        previous (peek stack)]
    (if previous
      (-> state
          (update :scene/stack pop)
          (assoc :scene/current previous))
      (assoc state :scene/current (load-scene :story-end)))))

```

---

## Checkpointing: XTDB Integration

Every state change is persisted to XTDB:

```clojure
(defn checkpoint! [state transition]
  (let [checkpoint-id (uuid/v4)
        checkpoint {:xt/id checkpoint-id
                    :checkpoint/session-id (:session/id state)
                    :checkpoint/child-id (:child/id state)
                    :checkpoint/state state
                    :checkpoint/transition transition
                    :checkpoint/sequence-num (next-seq-num state)}]
    (xt/submit-tx node [[:put-docs :primer/checkpoints checkpoint]])))

```

**Why checkpoint everything?**

1. **Crash recovery** - Resume exactly where child left off
2. **Debugging** - Replay any issue
3. **Analytics** - Understand learning patterns
4. **Rollback** - "That scared them, go back"
5. **Sharing** - "Show parent what happened"

---

## Checkpoint Queries

XTDB's temporal nature gives us superpowers:

```clojure
;; Get state at any point in time
(defn state-at [session-id time]
  (xt/q node
    '{:find [(pull cp [:checkpoint/state])]
      :where [[cp :checkpoint/session-id sid]
              [cp :xt/valid-from t]
              [(<= t ?time)]]
      :order-by [[t :desc]]
      :limit 1
      :in [sid ?time]}
    [session-id time]))

;; Replay session transitions
(defn session-replay [session-id]
  (xt/q node
    '{:find [seq-num transition state]
      :where [[cp :checkpoint/session-id sid]
              [cp :checkpoint/sequence-num seq-num]
              [cp :checkpoint/transition transition]
              [cp :checkpoint/state state]]
      :order-by [[seq-num :asc]]
      :in [sid]}
    [session-id]))

;; Find scenes where child struggled
(defn struggle-points [child-id]
  (xt/q node
    '{:find [scene-id attempt-count time]
      :where [[cp :checkpoint/child-id cid]
              [cp :checkpoint/state state]
              [cp :xt/valid-from time]]
      ;; Custom logic to detect struggle patterns
      :in [cid]}
    [child-id]))

```

---

## Session Recovery

When child returns:

```clojure
(defn resume-session [child-id device-id]
  ;; Find most recent checkpoint
  (let [latest (latest-checkpoint child-id)]
    (if (and latest (< (age-minutes latest) 60))
      ;; Resume recent session
      {:action :resume
       :session-id (:checkpoint/session-id latest)
       :state (:checkpoint/state latest)}
      ;; Start fresh but remember context
      {:action :new-session
       :child-context (child-long-term-memory child-id)})))

```

---

## The Render Function

Given state, produce HTML:

```clojure
(defn render [state]
  (let [scene (:scene/current state)
        template-fn (get-template (:scene/template scene))
        params (:scene/params scene)]
    (template-fn params state)))

```

Render is **pure** - same state always produces same HTML.

---

## The Event Loop

Putting it all together:

```clojure
(defn session-loop [initial-state]
  (let [state (atom initial-state)]
    ;; Watch for state changes, push to SSE
    (add-watch state :sse
      (fn [_ _ old new]
        (when (not= old new)
          (sse/push! (render new)))))

    ;; Return handler for incoming actions
    (fn [action-event]
      (let [transition (parse-transition action-event)
            current @state
            next-state (transition current transition)]
        ;; Persist
        (checkpoint! next-state transition)
        ;; Update (triggers SSE via watch)
        (reset! state next-state)))))

```

**Flow:**
1. Action arrives (POST from Datastar)
2. Parse into transition
3. Apply transition function
4. Checkpoint to XTDB
5. Update atom
6. Watch triggers SSE push
7. Client receives new HTML
8. Datastar morphs DOM

---

## Handling Async (AI, Image Gen)

For async operations, we use a **pending operations** pattern:

```clojure
;; When starting async operation
(defn request-ai-decision! [state context]
  (let [request-id (uuid/v4)]
    ;; Track pending operation
    (swap! pending-ops assoc request-id
      {:type :ai-decision
       :session-id (:session/id state)
       :requested-at (now)})
    ;; Fire async request
    (go
      (let [result (<! (ai/decide context))]
        ;; When complete, inject transition
        (dispatch-transition!
          (:session/id state)
          {:transition/type :ai-response
           :transition/payload {:request-id request-id
                                :decision result}})))))

```

The session loop receives the `:ai-response` transition just like any other action.

---

## Error States

When things go wrong:

```clojure
(defn handle-error [state error]
  (let [fallback-scene (case (:error/type error)
                         :ai-timeout (gentle-retry-scene)
                         :asset-failed (text-only-fallback state)
                         :invalid-state (safe-restart-scene)
                         (generic-error-scene))]
    (-> state
        (assoc :scene/current fallback-scene)
        (update :session/errors conj error))))

```

**Error handling philosophy:**
- Never show scary errors to child
- Graceful degradation (text if image fails)
- Auto-retry with backoff
- Alert parents if persistent

---

## State Machine Visualization

For debugging, we can generate a state graph:

```clojure
(defn visualize-session [session-id]
  (let [checkpoints (session-replay session-id)]
    {:nodes (map #(hash-map
                    :id (:scene/id (:checkpoint/state %))
                    :type (:scene/type (:checkpoint/state %)))
                 checkpoints)
     :edges (map (fn [[a b]]
                   {:from (:scene/id (:checkpoint/state a))
                    :to (:scene/id (:checkpoint/state b))
                    :label (get-in b [:checkpoint/transition :transition/action])})
                 (partition 2 1 checkpoints))}))

```

Renders as:

```
[intro] --tap--> [forest-enter] --voice:"hello"--> [owl-greeting] --choice:help--> [owl-riddle]

```

---

## Implementation Checklist

1. [ ] Define `State` Malli schema
2. [ ] Define `Transition` Malli schema
3. [ ] Implement `transition` multimethod
4. [ ] Implement `checkpoint!` function
5. [ ] Implement `render` function (template dispatch)
6. [ ] Implement SSE integration (state watch)
7. [ ] Implement session recovery
8. [ ] Implement async operation handling
9. [ ] Implement error states
10. [ ] Add session visualization tools

---

## The Beauty of Simplicity

The entire system reduces to:

```clojure
(loop [state initial-state]
  (render state)           ; Show current state
  (checkpoint! state)      ; Save for history
  (let [input (wait-for-input)
        next (transition state input)]
    (recur next)))

```

Everything else - AI, images, voice, games, puzzles - is just details inside `transition` and `render`.

**This is the Primer loop.** Simple, composable, debuggable.
