# Datastar Reference for ML Options Trading

This document covers Datastar integration patterns for our Clojure backend.

## Overview

Datastar is a lightweight (10KB) hypermedia framework that combines:
- **Frontend reactivity** (like Alpine.js) via HTML `data-*` attributes
- **Backend communication** via Server-Sent Events (SSE)
- **Signals** - reactive state containers that auto-update the UI

Key insight: All state lives server-side. The frontend is just a reactive view.

## Quick Start

### deps.edn

```clojure
;; Core SDK
dev.data-star.clojure/sdk {:mvn/version "1.0.0-RC4"}

;; http-kit adapter (IMPORTANT: need 2.9.0-beta2+)
dev.data-star.clojure/http-kit {:mvn/version "1.0.0-RC4"}
http-kit/http-kit {:mvn/version "2.9.0-beta2"}
```

### Frontend (HTML)

```html
<script src="https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.6/bundles/datastar.js"
        defer type="module"></script>

<!-- Signals (reactive state) -->
<div data-signals:count="0"></div>

<!-- Reactive text -->
<div data-text="count"></div>

<!-- Two-way binding -->
<input data-bind:username />

<!-- SSE actions -->
<button data-on:click="@post('/api/increment')">+1</button>
```

### Backend (Clojure)

```clojure
(require '[starfederation.datastar.clojure.api :as d*]
         '[starfederation.datastar.clojure.adapter.http-kit :as hk-gen])

(defn sse-handler [request]
  (hk-gen/->sse-response request
    {hk-gen/on-open
     (fn [sse-gen]
       ;; Patch HTML elements
       (d*/patch-elements! sse-gen "<div id='count'>42</div>")

       ;; Update signals
       (d*/patch-signals! sse-gen "{\"count\": 42}")

       ;; Close when done (or keep open for streaming)
       (d*/close-sse! sse-gen))}))
```

---

## Core Concepts

### Signals

Signals are reactive state on the frontend. Update them from the server:

```clojure
;; Add/update signals
(d*/patch-signals! sse-gen "{\"count\": 5, \"message\": \"hello\"}")

;; Remove signal (set to null)
(d*/patch-signals! sse-gen "{\"tempData\": null}")

;; Nested update (merge patch semantics)
(d*/patch-signals! sse-gen "{\"user\": {\"name\": \"Alice\"}}")
```

### Element Patching

Send HTML fragments to update the DOM:

```clojure
;; By element ID (auto-targeting)
(d*/patch-elements! sse-gen "<div id='message'>Updated!</div>")

;; By CSS selector
(d*/patch-elements! sse-gen "<span>New item</span>"
  {d*/selector "#container"
   d*/patch-mode d*/pm-append})

;; Remove elements
(d*/remove-element! sse-gen "#loading-spinner")
```

**Patch Modes:**
| Mode | Effect |
|------|--------|
| `pm-outer` | Morph into existing (default, preserves state) |
| `pm-inner` | Replace inner HTML |
| `pm-append` | Add at end |
| `pm-prepend` | Add at start |
| `pm-before` | Insert before |
| `pm-after` | Insert after |
| `pm-remove` | Delete element |

---

## Frontend Attributes

### Data Binding

```html
<!-- Text input -->
<input data-bind:username />

<!-- Number input (coerced) -->
<input type="number" data-bind.number:age />

<!-- Checkbox -->
<input type="checkbox" data-bind:isActive />
```

### Reactive Display

```html
<!-- Text content -->
<div data-text="greeting"></div>
<div data-text="`Count: ${count}`"></div>

<!-- Show/hide -->
<div data-show="isLoading">Loading...</div>

<!-- CSS classes -->
<div data-class:active="isActive"></div>
<div data-class="{ 'text-red': hasError }"></div>

<!-- Attributes -->
<button data-attr:disabled="isLoading">Submit</button>
```

### Events & Actions

```html
<!-- Click handlers -->
<button data-on:click="count++">Local increment</button>
<button data-on:click="@post('/api/increment')">Server increment</button>

<!-- With modifiers -->
<form data-on:submit.prevent="@post('/api/save')">
<input data-on:input.debounce="300ms:@post('/api/search')">
<div data-on:click.outside="modalOpen = false">

<!-- Indicators (loading state) -->
<button data-on:click="@post('/api/save')" data-indicator:isSaving>
  <span data-show="!isSaving">Save</span>
  <span data-show="isSaving">Saving...</span>
</button>
```

### SSE Request Options

```javascript
@post('/api/endpoint', {
  headers: { 'X-Custom': 'value' },
  selector: '#target',           // Where to patch
  mode: 'append',                // How to patch
  contentType: 'form',           // 'json' (default) or 'form'
  retryMaxCount: 10              // Retry attempts
})
```

---

## SSE Protocol

### Event Format

```
event: datastar-patch-elements
id: event123
data: selector #container
data: mode append
data: elements <div class="item">New Item</div>

```

### SDK Functions

| Function | Purpose |
|----------|---------|
| `patch-elements!` | Update DOM elements |
| `patch-signals!` | Update reactive signals |
| `execute-script!` | Run JavaScript |
| `console-log!` | Log to browser console |
| `redirect!` | Navigate to URL |
| `close-sse!` | Close SSE connection |

---

## Patterns for ML Options

### 1. Long-Running Job Pattern

For bulk imports that take minutes/hours:

```clojure
(defn import-handler [request]
  (hk-gen/->sse-response request
    {hk-gen/on-open
     (fn [sse-gen]
       ;; Start import in background
       (future
         (doseq [day (date-range start end)]
           ;; Update progress
           (d*/patch-signals! sse-gen
             (json/write-str {:current-day (str day)
                              :progress (calc-progress day)}))
           ;; Update log
           (d*/patch-elements! sse-gen
             (hiccup/html [:div.log-entry (str "Processing " day "...")])
             {d*/selector "#log"
              d*/patch-mode d*/pm-append})

           ;; Do the actual work
           (process-day! day))

         ;; Done
         (d*/patch-signals! sse-gen "{\"status\": \"complete\"}")
         (d*/close-sse! sse-gen)))}))
```

### 2. Broadcast Pattern (Multiple Users)

```clojure
(def connections (atom #{}))

(defn subscribe-handler [request]
  (hk-gen/->sse-response request
    {hk-gen/on-open
     (fn [sse-gen]
       (swap! connections conj sse-gen))

     hk-gen/on-close
     (fn [sse-gen _status]
       (swap! connections disj sse-gen))}))

(defn broadcast! [html]
  (doseq [conn @connections]
    (d*/patch-elements! conn html)))
```

### 3. Hyperlith-Style Full Page Morph

For simpler mental model - always send full view:

```clojure
(defn render-page [state]
  (hiccup/html
    [:main#morph
     [:h1 "Import Status"]
     [:div.progress (:progress state) "%"]
     [:div.log
      (for [entry (:log state)]
        [:div.entry entry])]]))

(defn page-handler [request]
  (let [state-atom (atom {:progress 0 :log []})]
    (add-watch state-atom :sse
      (fn [_ _ _ new-state]
        ;; Re-render full page on any change
        (d*/patch-elements! @current-sse (render-page new-state))))

    (hk-gen/->sse-response request
      {hk-gen/on-open
       (fn [sse-gen]
         (reset! current-sse sse-gen)
         (d*/patch-elements! sse-gen (render-page @state-atom)))})))
```

---

## Compression

For long-lived SSE connections, use Brotli compression (100:1+ ratios):

```clojure
(require '[starfederation.datastar.clojure.adapter.http-kit :as hk-gen])

(defn handler [request]
  (hk-gen/->sse-response request
    {hk-gen/on-open (fn [sse-gen] ...)
     hk-gen/write-profile hk-gen/gzip-profile}))  ; or custom Brotli
```

---

## Key Insights from Hyperlith

1. **Compression beats diffing** - Send full views, let compression handle efficiency
2. **Virtual threads for SSE** - One virtual thread per connection scales to thousands
3. **Stateless connections** - All state in DB/atoms, not per-connection
4. **Throttle refreshes** - 100-200ms prevents render storms
5. **CQRS pattern** - Actions modify state, views react via SSE

---

## Resources

- [Datastar Official Site](https://data-star.dev/)
- [Clojure SDK GitHub](https://github.com/starfederation/datastar-clojure)
- [Hyperlith Framework](https://github.com/andersmurphy/hyperlith)
- [Anders Murphy Blog Post](https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html)

## Local Reference Code

- `/reference-code/datastar/` - Main Datastar framework
- `/reference-code/datastar-clojure/` - Official Clojure SDK
