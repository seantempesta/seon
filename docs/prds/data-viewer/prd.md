# PRD: Data Viewer - Expand/Collapse Interaction

**Status:** Phase 0 Ready
**Priority:** High (Phase 2 of namespace-ui)
**Branch:** feature/namespace-ui

---

## Goal

Click `{` to expand maps, `[` to expand vectors, `#{` to expand sets. A simple, consistent interaction model for exploring nested Clojure data structures in the browser.

---

## Current State

### Existing Infrastructure

The viewer system has two rendering paths that need to be unified:

**1. `seon.ui.viewer` (Phase 1 implementation)**
Location: `src/seon/ui/viewer.clj`

```clojure
;; Multimethod dispatch on type or ::viewer metadata (lines 16-28)
(defmulti render-value
  (fn [v _opts]
    (or (::viewer (meta v))
        (type v))))
```

Current collection rendering (lines 86-127):

- Maps, vectors, sets, seqs all render **fully expanded** by default
- Uses `border-l border-base-700` for visual nesting
- No interactive collapse - purely static HTML

**2. `seon.ns.view` (newer system)**
Location: `src/seon/ns/view.clj`

```clojure
;; Dispatch on [format view-type] (lines 106-119)
(defmulti render*
  (fn [value format] [format (extract-view-type value)]))
```

Default HTML renderer (lines 267-369):

- Also renders collections fully expanded
- Uses `pl-3 border-l border-base-700` for nesting
- Truncates after 20 items with "... N more" indicator

**Key observation:** Neither system has expand/collapse. Phase 2 adds this.

### Related Patterns in Codebase

**Agent log expand/collapse** (`src/seon/ai/agent/views.clj`):
Uses native `<details>` with `data-preserve-attr="open"` for SSE morph stability:

```clojure
;; Pattern from components.clj:153-159
[:details {:class "text-text-50 inline"
           :data-preserve-attr "open"}
 [:summary {:class "cursor-pointer list-none"}
  (subs content 0 preview-length)
  [:span {:class "text-info ml-1"} (str "+" (- (count content) preview-length) " more")]]
 [:div {:class "break-all mt-1 pl-2 border-l-2 border-base-700"}
  content]]
```

**Component library** (`src/seon/web/components.clj:133-163`):
Same pattern in `log-line` for long content.

### Datastar Patterns Available

From `docs/reference/datastar-quick-reference.md`:

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Client-side reactive state | `data-signals="{expanded: false}"` |
| `data-show` | Conditional display | `data-show="$expanded"` |
| `data-on:click` | Click handler | `data-on:click="$expanded = !$expanded"` |

The guide notes (line 47-49):
> **Attributes we DON'T use (yet):**
>
> - `data-signals` - Client-side reactive state (server-side only for now)

This is the opportunity - Phase 2 introduces client-side signals for expand/collapse.

---

## Design Decision: `<details>` vs Datastar Signals

### Option A: Native `<details>` (Recommended)

**Pros:**

- Already used successfully in log viewer
- Works without JavaScript
- SSE-stable with `data-preserve-attr="open"`
- Keyboard accessible (Enter/Space to toggle)
- No state management complexity

**Cons:**

- Limited styling control (browser default triangle)
- Can't coordinate multiple elements (e.g., "expand all")

### Option B: Datastar Signals

**Pros:**

- Full control over UI/animation
- Can build "expand all" / "collapse all"
- Matches Datastar philosophy

**Cons:**

- Adds client-side state
- More complex SSE morph handling
- Need unique signal names per element

### Recommendation

**Start with `<details>` (Option A)** because:

1. Already proven in the codebase
2. Simpler implementation
3. Matches design principle: "can we solve this with CSS? If yes, do that."

Consider Datastar signals later if we need:

- Expand all / collapse all buttons
- Synchronized expansion state
- Custom animations

---

## Implementation Plan

### Phase 0: Unified Collection Renderer

**Goal:** Create expand/collapse collection renderer in `seon.ui.viewer` using `<details>`.

**File:** `src/seon/ui/viewer.clj` - Update collection methods (lines 86-127)

#### Map Renderer

```clojure
(defmethod render-value clojure.lang.IPersistentMap [m opts]
  (if (empty? m)
    [:span {:class "text-text-400"} "{}"]
    [:details {:class "inline-block align-top"
               :data-preserve-attr "open"
               :open (< (count m) 5)}  ; auto-expand small maps
     ;; Clickable bracket as summary
     [:summary {:class "cursor-pointer list-none inline text-text-400 hover:text-signal"}
      [:span {:class "hover:bg-base-800 px-0.5 rounded"} "{"]
      [:span {:class "text-text-500 ml-1 text-xs"}
       (str (count m) (if (= 1 (count m)) " key" " keys"))]]
     ;; Expanded content
     [:div {:class "pl-4 border-l border-base-700 mt-0.5"}
      (for [[k v] m]
        [:div {:class "flex gap-2 py-0.5"}
         (render-value k opts)
         (render-value v opts)])
      [:span {:class "text-text-400"} "}"]]]))
```

Key design elements:

- **`<details>` wrapper** - native expand/collapse
- **Summary is the opening bracket** - click `{` to toggle
- **Count hint** - "3 keys" next to bracket when collapsed
- **Closing bracket inside** - appears only when expanded
- **Auto-expand small** - Collections with <5 items start expanded

#### Vector Renderer

```clojure
(defmethod render-value clojure.lang.IPersistentVector [v opts]
  (if (empty? v)
    [:span {:class "text-text-400"} "[]"]
    (let [limit 20
          total (count v)
          show-more? (> total limit)]
      [:details {:class "inline-block align-top"
                 :data-preserve-attr "open"
                 :open (< total 5)}
       [:summary {:class "cursor-pointer list-none inline text-text-400 hover:text-signal"}
        [:span {:class "hover:bg-base-800 px-0.5 rounded"} "["]
        [:span {:class "text-text-500 ml-1 text-xs"} (str total " items")]]
       [:div {:class "pl-4 border-l border-base-700 mt-0.5"}
        (for [item (take limit v)]
          [:div {:class "py-0.5"} (render-value item opts)])
        (when show-more?
          [:details {:class "text-info text-xs cursor-pointer" :data-preserve-attr "open"}
           [:summary {:class "list-none py-1"}
            (str "+" (- total limit) " more")]
           [:div
            (for [item (drop limit v)]
              [:div {:class "py-0.5"} (render-value item opts)])]])
        [:span {:class "text-text-400"} "]"]]])))
```

#### Set Renderer

```clojure
(defmethod render-value clojure.lang.IPersistentSet [s opts]
  (if (empty? s)
    [:span {:class "text-text-400"} "#{}"]
    [:details {:class "inline-block align-top"
               :data-preserve-attr "open"
               :open (< (count s) 5)}
     [:summary {:class "cursor-pointer list-none inline text-text-400 hover:text-signal"}
      [:span {:class "hover:bg-base-800 px-0.5 rounded"} "#{"]
      [:span {:class "text-text-500 ml-1 text-xs"} (str (count s) " items")]]
     [:div {:class "pl-4 border-l border-base-700 mt-0.5"}
      (for [item s]
        [:div {:class "py-0.5"} (render-value item opts)])
      [:span {:class "text-text-400"} "}"]]]))
```

### Phase 1: Update `seon.ns.view` Default Renderer

**File:** `src/seon/ns/view.clj` - Update default `:html` renderer (lines 293-325)

Apply the same `<details>` pattern to keep both renderers consistent.

### Phase 2: Depth Limiting

**Goal:** Prevent infinite nesting from breaking the UI.

Add depth tracking to opts:

```clojure
(let [depth (get opts :depth 0)
      max-depth 10]
  (if (>= depth max-depth)
    [:span {:class "text-text-500 italic"} "[max depth]"]
    ;; Normal rendering with (assoc opts :depth (inc depth))
    ))
```

---

## Visual Design

### Collapsed State

```
{3 keys}    ← Click bracket to expand
```

### Expanded State (small)

```
{                      ← Click bracket to collapse
│ :a 1
│ :b "hello"
│ :c [:x :y :z]        ← Nested vector also expandable
}
```

### Expanded State (large vector)

```
[100 items]
│ 0
│ 1
│ ...
│ 19
│ +80 more             ← Click to show rest
│   20
│   21
│   ...
]
```

### Color Scheme (per design-system.md)

| Element | Color | Class |
|---------|-------|-------|
| Brackets `{}[]#{}` | muted | `text-text-400` |
| Bracket hover | accent | `hover:text-signal` |
| Count hint | dimmer | `text-text-500` |
| Keys (keywords) | purple | `text-eval` |
| Values (strings) | amber | `text-warning` |
| Values (numbers) | green | `text-success` |
| Nesting line | border | `border-base-700` |

---

## Test Criteria

### Browser Tests

```
[ ] Navigate to /ns/seon.ui.viewer (or any namespace view with data)
[ ] See maps with <5 items expanded, larger ones collapsed
[ ] Click `{` bracket - map toggles expand/collapse
[ ] See count hint: "{3 keys}" or "[42 items]"
[ ] Nested maps/vectors are also expandable independently
[ ] SSE update doesn't collapse expanded elements (data-preserve-attr works)
[ ] Large vector (>20 items) shows "+N more" link
[ ] Clicking "+N more" reveals remaining items without collapsing parent
```

### REPL Tests

```clojure
;; Load the viewer
(require '[seon.ui.viewer :as viewer])
(require '[dev.onionpancakes.chassis.core :as h])

;; Basic rendering - should return Hiccup with <details>
(viewer/render-value {:a 1 :b {:nested true}} {})
;; => [:details {...} [:summary ...] [:div ...]]

;; Empty collections - no details wrapper
(viewer/render-value {} {})
;; => [:span {:class "text-text-400"} "{}"]

;; Large vector - nested details for overflow
(def big-vec (vec (range 50)))
(viewer/render-value big-vec {})
;; Should have [:details ...] with nested [:details ...] for "+30 more"

;; Generate HTML and inspect
(h/html (viewer/render-value {:x [1 2 3]} {}))
```

---

## Code References

| File | Line | Purpose |
|------|------|---------|
| `src/seon/ui/viewer.clj` | 86-127 | Collection render methods to update |
| `src/seon/ns/view.clj` | 293-325 | Default :html renderer to update |
| `src/seon/web/components.clj` | 153-159 | Reference `<details>` pattern |
| `docs/reference/datastar-quick-reference.md` | 47-49 | Datastar signals (future) |
| `docs/prds/namespace-ui/design-system.md` | - | Color palette, typography |
| `docs/prds/namespace-ui/prd.md` | 1456-1514 | Phase 2 original spec |

---

## Out of Scope

- Custom expand/collapse animations (CSS transitions)
- "Expand all" / "Collapse all" buttons (consider for Phase 3)
- Keyboard navigation beyond native `<details>` (Enter/Space)
- Remembering expand state across page loads
- Syntax highlighting for code strings

---

## Dependencies

- Phase 1a of namespace-ui (render convention) - **Done**
- Design system colors - **Done**
- `data-preserve-attr` pattern - **Proven in log viewer**

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| SSE morph instability | Use `data-preserve-attr="open"` (proven in log viewer) |
| Deep nesting performance | Limit max depth to 10 levels |
| Lazy sequence hang | Already handled with `(take 100 s)` in viewer.clj:122 |
| Browser default styling | CSS can hide default triangle via `list-none` |

---

## Future Enhancements

1. **Datastar signals version** - If we need "expand all" or coordinated state
2. **Path copying** - Right-click to copy path like `[:agent :messages 3 :content]`
3. **Value search** - Filter displayed content by pattern
4. **JSON export** - Copy value as JSON to clipboard
