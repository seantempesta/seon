---
type: research
status: completed
tags: [research, archive]
---

# Primer Architecture Vision

**The Core Insight:** A Primer session is a **server-controlled state machine** where:
- **Scene** = current state (data structure)
- **Templates** = render functions (scene → hiccup)
- **Transitions** = valid next states (AI-driven or user-triggered)
- **Checkpoint** = serialize scene to XTDB for replay/debugging

The AI doesn't "generate HTML" - it generates **state transitions**. The templates are pre-built. This gives us:
1. Instant rendering (no waiting for AI to generate markup)
2. Deterministic replay (same state = same view)
3. Composable complexity (templates call templates)
4. Debuggable (inspect state at any point)

---

## The Loop

```
┌─────────────────────────────────────────────────────────────┐
│                      SERVER (Seon)                          │
│                                                             │
│  ┌─────────┐    ┌──────────┐    ┌─────────┐               │
│  │  Scene  │───▶│ Template │───▶│   SSE   │──────────────┐│
│  │ (state) │    │ (render) │    │ (push)  │              ││
│  └────▲────┘    └──────────┘    └─────────┘              ││
│       │                                                   ││
│       │         ┌──────────┐                             ││
│       └─────────│ AI Core  │◀──────── (reasoning)        ││
│                 └────▲─────┘                             ││
│                      │                                    ││
│  ┌─────────────┐     │                                    ││
│  │ User Action │─────┘ (POST)                            ││
│  └─────────────┘                                          ││
│                                                           ││
│  ┌─────────────┐                                          ││
│  │   XTDB     │◀───── checkpoint(scene)                  ││
│  │ (history)  │                                           ││
│  └─────────────┘                                          ││
└───────────────────────────────────────────────────────────┘│
                                                             │
┌─────────────────────────────────────────────────────────────┐
│                      CLIENT (Browser)                       │
│                                                             │
│  ┌─────────┐    ┌──────────┐    ┌─────────┐               │
│  │ Datastar│◀───│   HTML   │◀───│   SSE   │◀──────────────┘
│  │ (morph) │    │  (view)  │    │ (recv)  │
│  └────┬────┘    └──────────┘    └─────────┘
│       │                                                     │
│       ▼                                                     │
│  ┌─────────┐    (optional: local interactivity)            │
│  │Three.js │    - animations, transitions                  │
│  │ Canvas  │    - audio playback                           │
│  │  etc.   │    - touch/gesture                            │
│  └─────────┘                                                │
└─────────────────────────────────────────────────────────────┘

```

---

## What is a Scene?

A scene is a **pure data structure** describing the current moment:

```clojure
{:scene/id "story-1-page-3"
 :scene/type :narrative        ; or :puzzle, :dialogue, :exploration

 ;; Visual layer
 :visual/background {:type :generated-image
                     :prompt "A young girl stands before a locked door..."
                     :style :victorian-engraving}
 :visual/characters [{:id :nell
                      :position [0.3 0.6]
                      :expression :curious}]
 :visual/objects [{:id :door
                   :interactive? true
                   :state :locked}]

 ;; Audio layer
 :audio/narration "Before her stood the curious door..."
 :audio/ambient :forest-night
 :audio/music {:track :mystery :intensity 0.3}

 ;; Interaction layer
 :interactions/available
   [{:trigger :tap-door
     :description "Examine the door"
     :transition :examine-door}
    {:trigger :voice
     :description "Ask the Primer a question"
     :transition :dialogue}
    {:trigger :swipe-left
     :description "Turn back"
     :transition :previous-page}]

 ;; Context for AI
 :context/story-arc :learning-patience
 :context/child-state {:emotional-valence 0.7  ; happy
                       :engagement 0.9
                       :recent-struggle :sharing}
 :context/session-history ["intro" "forest-enter" "meet-owl"]}

```

**Key properties:**
- Pure data (EDN) - serializable, inspectable
- Immutable - state changes create new scenes
- Composable - scenes reference other scenes
- Self-describing - AI can reason about structure

---

## What are Templates?

Templates are **pure functions**: `(scene, context) → hiccup`

```clojure
(ns seon.primer.templates.narrative
  (:require [seon.primer.components.image :as image]
            [seon.primer.components.character :as char]
            [seon.primer.components.interaction :as interact]))

(defn render [{:keys [visual/background visual/characters
                      audio/narration interactions/available] :as scene}]
  [:div#morph.primer-page

   ;; Visual layer (z-index stacked)
   [:div.visual-layer
    (image/generated-background background)
    (for [char characters]
      (char/render char))]

   ;; Narration text overlay
   [:div.narration-layer
    [:p.narration-text narration]]

   ;; Interaction affordances
   [:div.interaction-layer
    (for [{:keys [trigger description transition]} available]
      (interact/hotspot trigger description transition))]])

```

**Template categories:**
- `narrative` - Story pages with illustration + text
- `dialogue` - Conversational exchange with Primer
- `puzzle` - Interactive challenge (lock, maze, riddle)
- `exploration` - Free-form investigation
- `reflection` - Journaling, drawing, expressing

---

## How Transitions Work

When user acts (tap, voice, gesture), we:

1. **Capture action** → POST to server
2. **Validate transition** → Is this action valid from current scene?
3. **AI reasoning** (if needed) → What's the next scene?
4. **State update** → New scene becomes current
5. **Checkpoint** → Store in XTDB
6. **Render** → Template produces HTML
7. **Push** → SSE sends to client

```clojure
(defn handle-action [{:keys [scene-id action payload]}]
  (let [current-scene (get-scene scene-id)
        valid-transitions (:interactions/available current-scene)]

    ;; Validate
    (when-not (valid-transition? action valid-transitions)
      (throw (ex-info "Invalid action" {:action action})))

    ;; AI decides next scene (or deterministic if simple)
    (let [next-scene (if (requires-ai? action)
                       (ai/reason-next-scene current-scene action payload)
                       (deterministic-next current-scene action))]

      ;; Checkpoint
      (checkpoint! next-scene)

      ;; Update state (triggers SSE refresh)
      (swap! session-state assoc :current-scene next-scene))))

```

---

## Checkpointing: The Superpower

Because scenes are data, we get debugging superpowers for free:

```clojure
;; Save scene to XTDB with temporal context
(defn checkpoint! [scene]
  (xt/submit-tx node
    [[:put-docs :primer/scenes
      (assoc scene
        :xt/id (:scene/id scene)
        :child-id (:child-id session)
        :session-id (:session-id session))]]))

;; Replay any moment
(defn restore-scene [scene-id as-of-time]
  (xt/q node
    '{:find [(pull scene [*])]
      :where [[scene :xt/id scene-id]]
      :at as-of-time}
    {:scene-id scene-id}))

;; Debug: what was happening when child got stuck?
(defn session-timeline [session-id]
  (xt/q node
    '{:find [scene-id timestamp]
      :where [[scene :session-id session-id]
              [scene :xt/valid-from timestamp]
              [scene :xt/id scene-id]]
      :order-by [[timestamp :asc]]}
    {:session-id session-id}))

```

**Use cases:**
- Reproduce bugs: "Show me exactly what they saw"
- A/B testing: "Compare story branches"
- Progress tracking: "How long did puzzle take?"
- Rollback: "They got frustrated, go back 3 scenes"

---

## The AI Layer

The AI doesn't generate HTML. It reasons about:
1. **What happens next?** (narrative)
2. **How should I respond?** (dialogue)
3. **What's the child's state?** (emotional, cognitive)
4. **What image should we generate?** (visual prompts)

```clojure
(defn ai-reason-next-scene [current-scene action child-context]
  (let [prompt (build-reasoning-prompt current-scene action child-context)]
    (-> (claude/complete prompt)
        (parse-scene-decision)
        (merge-with-defaults current-scene))))

```

**AI outputs are structured:**

```clojure
{:decision/next-scene-type :dialogue
 :decision/story-beat :introduce-helper
 :decision/emotional-target :curious
 :decision/narration "The owl tilted its head..."
 :decision/image-prompt "A wise owl perched on a branch, Victorian engraving style"
 :decision/suggested-interactions [:ask-owl :continue :look-around]}

```

Templates then render this structured output. The AI never touches HTML.

---

## Interactive Elements: Client State

Some things DO need client-side state for smoothness:
- Animation playback
- Audio timing
- Touch gesture recognition
- Three.js scenes

These use **local state that syncs to server**:

```javascript
// Client signals (Datastar)
signals: {
  audioPlaying: false,
  animationFrame: 0,
  touchState: { x: 0, y: 0, gesture: null }
}

// On significant events, POST to server
if (gesture === 'swipe-left') {
  @post('/api/action', { action: 'swipe-left' })
}

```

The server remains source of truth. Client just handles real-time smoothness.

---

## Three.js / Canvas Integration

For richer scenes (puzzles, games, explorations):

```clojure
;; Scene defines a canvas element
{:scene/type :puzzle
 :visual/canvas
   {:type :threejs
    :config {:scene :lock-puzzle
             :camera [0 0 5]
             :objects [{:id :lock :model "lock.glb"}
                       {:id :key1 :model "key.glb" :position [-1 0 0]}
                       {:id :key2 :model "key.glb" :position [0 0 0]}
                       {:id :key3 :model "key.glb" :position [1 0 0]}]}}}

```

Template renders:

```clojure
[:div#threejs-container {:data-config (json/encode canvas-config)}]

```

Client-side JS initializes Three.js with server-provided config. Interactions POST back.

---

## Seon Domain Structure

```
src/seon/domains/primer/
├── core.clj           ; Public API, start-session!, get-current-scene
├── specs.clj          ; Malli schemas for scenes, actions, child-profile
├── scenes.clj         ; Scene construction, validation, transitions
├── templates/         ; Render functions by scene type
│   ├── narrative.clj
│   ├── dialogue.clj
│   ├── puzzle.clj
│   └── common.clj     ; Shared components
├── ai.clj             ; AI reasoning integration (Claude, Gemini)
├── audio.clj          ; Voice generation, audio asset management
├── images.clj         ; Image generation, caching, consistency
├── child.clj          ; Child profile, emotional tracking
└── tests.clj          ; Colocated tests, example scenes

```

---

## Why This is Better Than Next.js + Vercel AI SDK

| Aspect | Next.js + AI SDK | Seon + Datastar |
|--------|------------------|-----------------|
| State location | Split (client + server) | Server only |
| Checkpointing | Manual serialization | Free (XTDB) |
| Debugging | Complex | Inspect EDN |
| Latency | React hydration | HTML streaming |
| Complexity | React + RSC + SDK | Clojure + Hiccup |
| AI integration | Streaming text → UI | Structured → Template |
| Temporal queries | Build yourself | XTDB built-in |

**The killer feature:** Every session is automatically recorded with full fidelity. You can replay any child's experience, debug issues, and analyze learning patterns.

---

## Open Questions

1. **Voice input** - Gemini Live API vs local VAD + transcription?
2. **Image generation latency** - Pre-generate vs real-time? Caching strategy?
3. **Offline capability** - PWA with cached templates + local-first scenes?
4. **Multi-child** - How do siblings share a device?
5. **Parent dashboard** - What insights do parents see?
6. **Content moderation** - How do we ensure age-appropriate output?

---

## Next Steps

1. **Prototype the loop** - Single scene, one template, action → transition
2. **Scene schema** - Finalize Malli specs for scene structure
3. **Template library** - Build core template types
4. **AI integration** - Claude for reasoning, test prompts
5. **Voice proof-of-concept** - Gemini Live API or alternatives
6. **Image pipeline** - Generation, caching, consistency
