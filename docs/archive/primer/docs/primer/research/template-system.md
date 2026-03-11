# Primer Template System

**Core Idea:** The AI doesn't generate HTML - it selects and parameterizes templates.

Templates are like a **vocabulary** the AI speaks fluently. Each template is:
1. A visual/interactive pattern the AI knows how to use
2. Parameterized for infinite variation
3. Composable with other templates
4. Immediately renderable (no generation wait)

---

## Template Hierarchy

```
Base Templates (atomic)
└── Story Atom: single paragraph + illustration
└── Dialogue Bubble: character speech
└── Choice Button: tappable action
└── Object Hotspot: interactive element
└── Progress Bar: completion indicator

Composite Templates (molecule)
└── Story Page: atoms arranged on a page
└── Conversation: sequence of dialogue bubbles
└── Puzzle Grid: interactive object arrangement
└── Map View: explorable space

Scene Templates (organism)
└── Narrative Scene: story page + interactions
└── Dialogue Scene: conversation + responses
└── Puzzle Scene: challenge + success/fail states
└── Exploration Scene: map + discoverable objects
└── Reflection Scene: journal prompt + drawing canvas

```

---

## The 10 Core Templates

These cover 90% of Primer interactions:

### 1. Story Page (`narrative/page`)

Classic illustrated book page.

```clojure
{:template :narrative/page
 :params
   {:illustration {:prompt "A girl discovers a hidden garden"
                   :style :victorian
                   :characters [:nell]}
    :text "Behind the ivy-covered wall, Nell found..."
    :text-position :bottom   ; :top, :bottom, :overlay
    :interactions [{:type :tap-continue}
                   {:type :voice-question}]}}

```

**Renders:**
- Full-bleed illustration (or half-page)
- Text overlay with nice typography
- Subtle "tap to continue" affordance

### 2. Dialogue Exchange (`dialogue/exchange`)

Conversational back-and-forth with the Primer.

```clojure
{:template :dialogue/exchange
 :params
   {:speaker :primer   ; or :nell, :character-name
    :text "What do you think the owl was trying to say?"
    :tone :curious     ; affects voice synthesis
    :expect-response? true
    :response-type :voice   ; or :choice, :text-input
    :choices ["He wanted to help" "He was warning her" "I don't know"]}}

```

**Renders:**
- Speech bubble with character portrait
- Voice waveform if speaking
- Input affordance (mic icon, choice buttons, or text field)

### 3. Choice Moment (`narrative/choice`)

Branching decision point.

```clojure
{:template :narrative/choice
 :params
   {:situation-text "The path splits before you..."
    :illustration {:prompt "A fork in a forest path"}
    :choices
      [{:id :left-path
        :label "Take the shadowy path"
        :icon :moon
        :hint "It looks mysterious..."}
       {:id :right-path
        :label "Take the sunny path"
        :icon :sun
        :hint "It looks safer..."}
       {:id :ask
        :label "Ask the Primer for advice"
        :icon :book}]}}

```

**Renders:**
- Illustration with choice areas highlighted
- Choice buttons or tap targets
- Optional hint text on hover/hold

### 4. Puzzle Challenge (`puzzle/grid`)

Interactive manipulation puzzle.

```clojure
{:template :puzzle/grid
 :params
   {:type :matching         ; :sorting, :sequence, :jigsaw, :lock
    :instructions "Match each animal to its home"
    :items
      [{:id :bird :image "bird.png" :draggable? true}
       {:id :fish :image "fish.png" :draggable? true}
       {:id :nest :image "nest.png" :drop-target? true :accepts [:bird]}
       {:id :pond :image "pond.png" :drop-target? true :accepts [:fish]}]
    :success-message "Well done! Every creature has a home."
    :hint-after 30   ; seconds before offering hint
    :on-success :continue-story
    :on-give-up :skip-with-learning}}

```

**Renders:**
- Grid of draggable/tappable items
- Drop targets with visual feedback
- Progress indicator
- Hint button (appears after delay)

### 5. Exploration Map (`exploration/map`)

Spatial discovery interface.

```clojure
{:template :exploration/map
 :params
   {:background {:prompt "A cozy cottage interior, Victorian illustration"}
    :hotspots
      [{:id :bookshelf
        :bounds [100 200 150 300]   ; x, y, width, height
        :icon :magnifying-glass
        :on-tap :examine-books}
       {:id :window
        :bounds [300 100 200 200]
        :icon :eye
        :on-tap :look-outside}
       {:id :door
        :bounds [500 150 100 250]
        :icon :door
        :on-tap :exit-room}]
    :discovered []   ; tracks what child has found
    :required-discoveries [:bookshelf :window]}}

```

**Renders:**
- Background illustration
- Subtle hotspot indicators (glow, sparkle)
- Discovery counter
- "Continue" button when requirements met

### 6. Character Encounter (`dialogue/encounter`)

Meeting a new character.

```clojure
{:template :dialogue/encounter
 :params
   {:character
      {:name "Old Owl"
       :portrait {:prompt "A wise owl with spectacles, friendly expression"}
       :voice :deep-gentle}
    :entrance-text "From the shadows emerged a most unusual owl..."
    :greeting "Good evening, young traveler. What brings you to these woods?"
    :personality :wise-helpful
    :knowledge [:forest-lore :patience :observation]}}

```

**Renders:**
- Character reveal animation
- Entrance narration
- Character portrait with speech
- Response options

### 7. Reflection Prompt (`reflection/prompt`)

Journaling and self-expression.

```clojure
{:template :reflection/prompt
 :params
   {:prompt "What would you do if you found a secret door?"
    :mode :drawing       ; :writing, :drawing, :voice
    :time-limit nil      ; optional: seconds
    :share-with-primer? true   ; AI sees response
    :example nil         ; optional: show example response
    :on-complete :continue-story}}

```

**Renders:**
- Prompt text with pretty formatting
- Canvas (drawing) or text area (writing) or mic (voice)
- Timer if limited
- "I'm done" button

### 8. Progress Celebration (`narrative/celebration`)

Milestone acknowledgment.

```clojure
{:template :narrative/celebration
 :params
   {:achievement "Completed the Patience Fable"
    :illustration {:prompt "Fireworks and stars over a castle"}
    :message "You've learned something important today..."
    :badge {:icon :turtle :label "The Patient One"}
    :next-preview "Tomorrow, a new adventure awaits..."}}

```

**Renders:**
- Celebratory animation
- Achievement badge
- Encouraging message
- Preview of what's next

### 9. Mini-Game (`game/simple`)

Quick interactive game (not puzzle).

```clojure
{:template :game/simple
 :params
   {:type :catch          ; :dodge, :rhythm, :memory
    :theme "Catch the falling stars"
    :duration 30          ; seconds
    :difficulty :easy     ; adapts based on child
    :scoring {:star 10 :miss -5}
    :success-threshold 50
    :integration "The stars you caught light your path..."}}

```

**Renders:**
- Full-screen game canvas
- Simple game loop (JS/Three.js)
- Score display
- Transition to story continuation

### 10. Wait State (`narrative/wait`)

When async operation is happening.

```clojure
{:template :narrative/wait
 :params
   {:message "The Primer is dreaming up something special..."
    :illustration {:static true :id :dreaming-book}
    :estimated-seconds 5
    :activity :shimmer    ; animation type
    :interruptible? false}}

```

**Renders:**
- Calming animation
- Progress indicator (subtle)
- Engaging visual to hold attention

---

## How AI Uses Templates

The AI receives the template vocabulary and reasons about which to use:

```
System Prompt:
You are the Primer. You have these templates available:
- narrative/page: Show illustration with text, for story progression
- dialogue/exchange: Have a conversation, ask questions
- narrative/choice: Present meaningful decision
- puzzle/grid: Challenge with a puzzle
...

Given the current scene and child's state, output a JSON decision:
{
  "template": "narrative/choice",
  "params": { ... },
  "reasoning": "Child has been passive, time for agency"
}

```

**Benefits:**
1. AI output is small (template name + params)
2. Rendering is instant (template already exists)
3. Consistent quality (templates are polished)
4. Debuggable (see exactly what AI chose)

---

## Template Composition

Templates can embed other templates:

```clojure
;; A story page that includes dialogue
{:template :narrative/page
 :params
   {:illustration {...}
    :text "Nell approached the owl..."
    :embedded
      {:template :dialogue/exchange
       :params {:speaker :owl
                :text "Are you lost, little one?"}}}}

```

**Render order:**
1. Render outer template
2. Find `:embedded` slots
3. Recursively render embedded templates
4. Compose into final hiccup

---

## Template State Machine

Some templates have internal states:

```
puzzle/grid states:
  :presenting → (show puzzle)
  :attempting → (child is trying)
  :hint-offered → (hint button appeared)
  :hint-showing → (hint revealed)
  :success → (puzzle solved)
  :gave-up → (child skipped)

dialogue/exchange states:
  :speaking → (Primer is talking)
  :listening → (waiting for response)
  :processing → (AI thinking)
  :responded → (child answered)

```

State transitions happen via:
- Timers (auto-advance)
- User actions (tap, voice, drag)
- External events (AI response ready, image generated)

---

## Template Customization

Templates support theming and personalization:

```clojure
;; Child's preferences (stored in profile)
{:child/visual-style :watercolor   ; vs :victorian, :comic
 :child/font-size :large
 :child/color-scheme :warm
 :child/reading-speed :slow}

;; Template respects preferences
(defn render-story-page [{:keys [text] :as params} child-prefs]
  [:div.story-page {:class (style-class child-prefs)}
   [:p.story-text {:style (text-style child-prefs)}
    (paced-text text (:reading-speed child-prefs))]])

```

---

## Building New Templates

When the AI needs something that doesn't exist:

1. **First:** Compose from existing templates
2. **Second:** Request template extension (human review)
3. **Never:** Generate raw HTML

Template requests go to a queue for human review:

```clojure
{:template-request/id "req-123"
 :template-request/description "Need a template for playing music"
 :template-request/use-case "Child wants to play a simple melody"
 :template-request/similar-to [:game/simple :puzzle/grid]
 :template-request/requested-by :ai-session-456}

```

---

## Implementation Priority

**Phase 1 (MVP):**
1. `narrative/page` - Basic story display
2. `dialogue/exchange` - Voice/text conversation
3. `narrative/choice` - Simple branching
4. `narrative/wait` - Loading states

**Phase 2 (Engagement):**
5. `puzzle/grid` - Simple matching/sorting
6. `exploration/map` - Tap-to-discover
7. `narrative/celebration` - Rewards

**Phase 3 (Depth):**
8. `reflection/prompt` - Journaling
9. `dialogue/encounter` - Character meetings
10. `game/simple` - Mini-games

---

## File Structure

```
src/seon/domains/primer/templates/
├── core.clj              ; Template registry, composition engine
├── render.clj            ; Template → Hiccup
├── state.clj             ; Template state machines
├── narrative/
│   ├── page.clj
│   ├── choice.clj
│   ├── celebration.clj
│   └── wait.clj
├── dialogue/
│   ├── exchange.clj
│   └── encounter.clj
├── puzzle/
│   └── grid.clj
├── exploration/
│   └── map.clj
├── reflection/
│   └── prompt.clj
├── game/
│   └── simple.clj
└── components/           ; Shared atoms
    ├── illustration.clj
    ├── text.clj
    ├── button.clj
    └── audio.clj

```
