---
type: reference
status: active
tags: [reference, web]
---
# Datastar Deep Dive

**Last Updated:** December 2, 2025
**Version Covered:** 1.0.0-RC.6

This document provides a comprehensive analysis of Datastar, a hypermedia framework that unifies frontend reactivity (like Alpine.js) with backend-driven updates (like htmx) using Server-Sent Events (SSE) as the primary communication mechanism.

---

## Table of Contents

1. [Core Concepts & Architecture](#core-concepts--architecture)
2. [SSE Event Format Specification](#sse-event-format-specification)
3. [Data Attributes Reference](#data-attributes-reference)
4. [Actions & Backend Communication](#actions--backend-communication)
5. [Signals & Reactivity](#signals--reactivity)
6. [DOM Morphing with Idiomorph](#dom-morphing-with-idiomorph)
7. [Async Patterns & Long-Running Operations](#async-patterns--long-running-operations)
8. [Progressive Enhancement & Forms](#progressive-enhancement--forms)
9. [Datastar vs htmx](#datastar-vs-htmx)
10. [Code Examples](#code-examples)
11. [References](#references)

---

## Core Concepts & Architecture

### What is Datastar?

Datastar is a lightweight (10.23 KiB) hypermedia framework for building everything from simple sites to real-time collaborative web applications. It combines:

- **Frontend reactivity** (like Alpine.js) via HTML `data-*` attributes
- **Backend communication** via Server-Sent Events (SSE)
- **Signals** - reactive state containers that auto-update the UI

**Key Philosophy:** "The backend drives state to the frontend and acts as the single source of truth."

### Installation

```html
<script type="module"
  src="https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.0-RC.6/bundles/datastar.js">
</script>
```

No npm packages, no build step required (though TypeScript is recommended for production).

### Architecture Principles

1. **Pragmatic Protocol Extension**: Extends the `text/event-stream` MIME type to support POST/PUT/PATCH/DELETE beyond SSE's native GET-only limitation
2. **Server-Driven Architecture**: The server pushes updates rather than the client polling
3. **Plugin-First Design**: Everything is a plugin, making the framework highly extensible
4. **View = f(state)**: Reactive signals automatically propagate changes throughout the DOM
5. **Debuggability**: Simple, transparent protocols that are easy to debug and maintain

### Communication Flow

```
User Action (click, input, etc.)
    ↓
Frontend sends request with all signals ($ prefixed state)
    ↓
Backend processes request (has full frontend state)
    ↓
Server responds with SSE events (text/event-stream)
    ↓
Multiple events patch DOM elements and/or signals
    ↓
Frontend reactively renders changes
```

**Key Insight:** Datastar sends ALL signals (except those prefixed with underscore `_`) with every backend request:

- **GET requests**: Signals sent as `datastar` query parameter
- **Other methods**: Signals sent as JSON body

---

## SSE Event Format Specification

Datastar uses Server-Sent Events (SSE) with the `text/event-stream` content type. The framework defines two primary event types.

### 1. `datastar-patch-elements`

Patches one or more HTML elements into the DOM.

**Basic Format:**

```
event: datastar-patch-elements
data: elements <div id="foo">Hello world!</div>

```

**Multi-line with Options:**

```
event: datastar-patch-elements
data: mode inner
data: selector #container
data: useViewTransition true
data: elements <div>
data: elements   <span>Multi-line HTML</span>
data: elements </div>

```

**Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `elements` | string | *required* | HTML content to patch |
| `selector` | string | auto (by ID) | CSS selector for targeting |
| `mode` | string | `outer` | Patch mode (see table below) |
| `useViewTransition` | boolean | `false` | Enable View Transitions API (disabled by default in seon's `render-handler` -- opt in with `:use-view-transition? true` only for page-level navigations) |

**Patch Modes:**

| Mode | Behavior |
|------|----------|
| `outer` | Morphs outer HTML (default) - matches by ID |
| `inner` | Morphs inner HTML only |
| `replace` | Replaces outer HTML completely |
| `prepend` | Prepends to target's children |
| `append` | Appends to target's children |
| `before` | Inserts as preceding sibling |
| `after` | Inserts as following sibling |
| `remove` | Deletes target from DOM |

**Remove Example:**

```
event: datastar-patch-elements
data: mode remove
data: selector #loading-spinner

```

### 2. `datastar-patch-signals`

Updates reactive signal values on the page.

**Basic Format:**

```
event: datastar-patch-signals
data: signals {"count": 42, "message": "Hello"}

```

**Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `signals` | JSON object | *required* | Signal key-value pairs |
| `onlyIfMissing` | boolean | `false` | Only add signals not already present |

**Nested Signals:**

```
event: datastar-patch-signals
data: signals {"user": {"name": "Alice", "email": "[email protected]"}}

```

**Removing Signals:**

```
event: datastar-patch-signals
data: signals {"tempData": null}

```

Setting a signal value to `null` removes it from the signal store.

### SSE Event Lifecycle

All HTTP actions (`@get`, `@post`, etc.) trigger `datastar-fetch` events:

| Event Type | When Fired |
|------------|------------|
| `started` | Request initiated |
| `finished` | Request completed successfully |
| `error` | Error encountered |
| `retrying` | Retry attempt in progress |
| `retries-failed` | All retries exhausted |

**Listening to Lifecycle:**

```html
<div data-on:datastar-fetch="
  console.log('Fetch event:', evt.detail.type)
"></div>
```

### Alternative Response Content Types

The server can respond with other content types, which Datastar auto-converts:

| Content Type | Behavior |
|--------------|----------|
| `text/event-stream` | Standard SSE with Datastar events |
| `text/html` | Patches elements (uses `datastar-selector`, `datastar-mode` headers) |
| `application/json` | Patches signals (uses `datastar-only-if-missing` header) |
| `text/javascript` | Executes code (uses `datastar-script-attributes` header) |

---

## Data Attributes Reference

Datastar uses HTML `data-*` attributes to add reactivity and interactivity. All attributes process **depth-first by DOM order**.

### Core State Management

#### `data-signals`

Defines reactive signals (state variables).

```html
<!-- Single signal -->
<div data-signals:count="0"></div>

<!-- Nested signals (dot-notation) -->
<div data-signals:form.email="''"></div>
<div data-signals:form.password="''"></div>

<!-- Multiple signals -->
<div data-signals="{foo: 1, bar: 'hello'}"></div>
```

**Key conversion:** Signals are converted to camelCase. `data-signals:my-signal` becomes `$mySignal`.

#### `data-bind`

Two-way data binding between signals and form elements.

```html
<input data-bind:username />
<input data-bind.number:age />
<input type="checkbox" data-bind:isActive />
```

**Type Modifiers:**

- `.number` - coerces to number
- `.trim` - trims whitespace

#### `data-computed`

Creates read-only derived signals that update automatically.

```html
<div data-computed:fullName="$firstName + ' ' + $lastName"></div>
<div data-computed:total="$price * $quantity"></div>

<!-- Object syntax -->
<div data-computed="{sum: () => $a + $b}"></div>
```

### Display & Visibility

#### `data-text`

Binds text content to expressions.

```html
<div data-text="$username"></div>
<div data-text="`Count: ${$count}`"></div>
<div data-text="$count > 10 ? 'High' : 'Low'"></div>
```

#### `data-show`

Conditionally shows/hides elements via `display` CSS property.

```html
<div data-show="$isLoading" style="display: none">Loading...</div>
<div data-show="!$isLoading">Content loaded</div>
```

**Best Practice:** Set initial `style="display: none"` to prevent flash of unwanted content (FOUC).

#### `data-class`

Conditionally adds/removes CSS classes.

```html
<!-- Single class -->
<div data-class:active="$isActive"></div>

<!-- Multiple classes -->
<div data-class="{
  'text-red': $hasError,
  'font-bold': $isImportant
}"></div>
```

#### `data-style`

Sets inline CSS styles reactively.

```html
<div data-style:background-color="$theme === 'dark' ? '#000' : '#fff'"></div>
<div data-style="{
  display: $hidden ? 'none' : 'block',
  opacity: $loading ? 0.5 : 1
}"></div>
```

#### `data-attr`

Sets HTML attribute values.

```html
<button data-attr:disabled="$isLoading">Submit</button>
<div data-attr:title="$tooltip"></div>

<!-- Multiple attributes -->
<div data-attr="{
  title: $tooltip,
  'aria-label': $label
}"></div>
```

### Event Handling

#### `data-on`

Attaches event listeners that execute expressions.

```html
<!-- Local state update -->
<button data-on:click="$count++">Increment</button>

<!-- Backend request -->
<button data-on:click="@post('/save')">Save</button>

<!-- Multiple statements -->
<button data-on:click="$saving = true; @post('/save')">Save</button>
```

**Event Modifiers:**

```html
<!-- Prevent default -->
<form data-on:submit.prevent="@post('/save')">

<!-- Debounce (wait 300ms after last event) -->
<input data-on:input.debounce.300ms="@post('/search')">

<!-- Throttle (max once per 300ms) -->
<input data-on:input.throttle.300ms="@post('/search')">

<!-- Once (fire only once) -->
<button data-on:click.once="@post('/init')">Initialize</button>

<!-- Stop propagation -->
<div data-on:click.stop="console.log('inner')">

<!-- Outside (fire when clicking outside element) -->
<div data-on:click.outside="$modalOpen = false">

<!-- Window event -->
<div data-on:resize__window="$width = window.innerWidth">

<!-- Delay -->
<button data-on:click__delay.500ms="$show = true">Show after 500ms</button>

<!-- View transition -->
<button data-on:click__viewtransition="@post('/next')">Next Page</button>
```

**Custom Events:**

```html
<div data-on:myevent="$data = evt.detail">
<button data-on:click="el.dispatchEvent(new CustomEvent('myevent', {detail: 'data'}))">
```

#### `data-on-intersect`

Triggers when element enters viewport (Intersection Observer).

```html
<!-- Fire when visible -->
<div data-on-intersect="$viewed = true">

<!-- Fire once when fully visible -->
<div data-on-intersect__once__full="@post('/track-view')">

<!-- Threshold modifier -->
<div data-on-intersect__threshold.0.5="$halfVisible = true">
```

#### `data-on-interval`

Executes expressions at regular intervals.

```html
<!-- Every second (default) -->
<div data-on-interval="$count++">

<!-- Custom duration -->
<div data-on-interval__duration.500ms="@get('/poll')">
```

#### `data-on-signal-patch`

Runs expressions whenever signals change.

```html
<div data-on-signal-patch="console.log('Signal changed')">

<!-- With filter -->
<div data-on-signal-patch="@post('/sync')"
     data-on-signal-patch-filter="{include: /^user/}">
```

### Loading States

#### `data-indicator`

Creates a boolean signal tracking fetch request status.

```html
<button data-on:click="@post('/save')"
        data-indicator:saving>
  <span data-show="!$saving">Save</span>
  <span data-show="$saving">Saving...</span>
</button>
```

**Critical:** Place `data-indicator` **before** the action in DOM order for proper processing.

### Lifecycle

#### `data-init`

Runs expressions when elements load or patch into DOM.

```html
<div data-init="$count = 1">

<!-- With delay -->
<div data-init__delay.500ms="$ready = true">

<!-- With view transition -->
<div data-init__viewtransition="@post('/')">
```

#### `data-effect`

Executes expressions on page load and whenever dependent signals change.

```html
<div data-effect="$total = $price * $quantity">
<div data-effect="console.log('Count changed:', $count)">
```

### DOM References

#### `data-ref`

Creates signals referencing DOM elements.

```html
<div data-ref:myDiv></div>
<button data-on:click="$myDiv.scrollIntoView()">Scroll to div</button>
```

### Advanced Control

#### `data-ignore`

Prevents Datastar from processing element and descendants.

```html
<div data-ignore data-show-thirdpartylib="">
  <!-- Third-party library attributes ignored by Datastar -->
</div>
```

#### `data-ignore-morph`

Skips element during DOM morphing while still processing other attributes.

```html
<div data-ignore-morph>
  <!-- Content preserved during updates -->
  <canvas id="chart"></canvas>
</div>
```

#### `data-preserve-attr`

Preserves attribute values during morphing.

```html
<details open data-preserve-attr="open">
  <!-- 'open' state preserved through morphs -->
</details>
```

### Debugging

#### `data-json-signals`

Displays reactive JSON representation of signals.

```html
<!-- All signals -->
<pre data-json-signals></pre>

<!-- Filtered -->
<pre data-json-signals="{include: /user/, exclude: /private/}"></pre>
```

---

## Actions & Backend Communication

Actions are functions called in expressions, prefixed with `@`. They're safe for use in expressions (sandboxed).

### HTTP Actions

All methods share the same signature: `@action(uri, options)`

```html
<button data-on:click="@get('/data')">Get</button>
<button data-on:click="@post('/save')">Save</button>
<button data-on:click="@put('/update')">Update</button>
<button data-on:click="@patch('/modify')">Patch</button>
<button data-on:click="@delete('/remove')">Delete</button>
```

### Action Options

```javascript
@post('/endpoint', {
  // Content type: 'json' or 'form'
  contentType: 'json',

  // Filter which signals to send
  filterSignals: {
    include: /.*/,           // Default: all signals
    exclude: /(^_|\._).*/    // Default: exclude _prefixed
  },

  // Custom headers
  headers: {
    'X-Custom': 'value'
  },

  // Target selector for patching
  selector: '#target',

  // Keep connection open when tab hidden
  openWhenHidden: false,

  // Retry configuration
  retryInterval: 1000,        // Initial delay (ms)
  retryScaler: 2,             // Backoff multiplier
  retryMaxWaitMs: 30000,      // Max retry delay
  retryMaxCount: 10,          // Max attempts

  // Request cancellation
  requestCancellation: 'auto' // 'auto', 'disabled', or AbortController
})
```

### Content Type: `json` vs `form`

**JSON Mode** (default):

```html
<button data-on:click="@post('/save')">
  <!-- Sends: {"username": "alice", "email": "alice@example.com"} -->
</button>
```

**Form Mode:**

```html
<form data-on:submit.prevent="@post('/save', {contentType: 'form'})">
  <input name="username" />
  <input name="email" />
  <button type="submit">Save</button>
</form>
<!-- Sends: FormData with validation -->
```

### Utility Actions

#### `@peek(signal)`

Accesses signal value without subscribing to changes.

```html
<button data-on:click="console.log(@peek($count))">
  <!-- Doesn't re-evaluate when $count changes -->
</button>
```

#### `@setAll(value, filter?)`

Sets all matching signals to a value.

```html
<button data-on:click="@setAll('', {include: /^form/})">
  Clear Form
</button>
```

#### `@toggleAll(filter?)`

Toggles all matching boolean signals.

```html
<button data-on:click="@toggleAll({include: /^is/})">
  Toggle All Flags
</button>
```

---

## Signals & Reactivity

### What are Signals?

Signals are reactive variables that automatically track and propagate changes. They're the foundation of Datastar's reactivity system.

**Syntax:** Signals are prefixed with `$` in expressions.

### Creating Signals

**1. Via `data-signals`:**

```html
<div data-signals:count="0"></div>
<div data-signals:user.name="'Alice'"></div>
<div data-signals="{a: 1, b: 2}"></div>
```

**2. Via `data-bind`:**

```html
<input data-bind:email />
<!-- Automatically creates $email signal -->
```

**3. Via `data-computed`:**

```html
<div data-computed:doubled="$count * 2"></div>
<!-- Creates read-only $doubled signal -->
```

**4. Via Backend:**

```
event: datastar-patch-signals
data: signals {"serverData": "value"}
```

### Signal Naming

- **Keys convert to camelCase:** `data-signals:my-signal` → `$mySignal`
- **Dots create nesting:** `data-signals:form.email` → `$form.email`
- **Underscore prefix excludes from requests:** `$_private` not sent to backend

### Nested Signals

```html
<div data-signals:user.name="'Alice'"></div>
<div data-signals:user.email="'alice@example.com'"></div>

<!-- Access in expressions -->
<div data-text="$user.name"></div>
<div data-text="$user.email"></div>
```

**Backend receives:**

```json
{
  "user": {
    "name": "Alice",
    "email": "alice@example.com"
  }
}
```

### Computed Signals

Read-only signals derived from other signals, updated automatically.

```html
<input data-bind.number:price />
<input data-bind.number:quantity />
<div data-computed:total="$price * $quantity"></div>
<div data-text="`Total: $${$total}`"></div>
```

### Signal Types

Signals preserve their types:

```html
<div data-signals:count="0"></div>           <!-- Number -->
<div data-signals:name="'Alice'"></div>      <!-- String -->
<div data-signals:isActive="true"></div>     <!-- Boolean -->
<div data-signals:items="[1,2,3]"></div>     <!-- Array -->
<div data-signals:user="{}"></div>           <!-- Object -->
```

**Type coercion in `data-bind`:**

```html
<input type="number" data-bind.number:age />
<input data-bind.trim:name />
```

### Reactivity Flow

```
Signal Change ($count = 5)
    ↓
All dependent expressions re-evaluate
    ↓
DOM updates automatically
```

**Example:**

```html
<input data-bind.number:count />
<div data-text="$count"></div>                  <!-- Updates -->
<div data-text="`Double: ${$count * 2}`"></div> <!-- Updates -->
<div data-show="$count > 10"></div>             <!-- Updates -->
<div data-class:high="$count > 10"></div>       <!-- Updates -->
```

### Special Variable: `el`

In all expressions, `el` refers to the current element:

```html
<div id="myDiv" data-text="el.id"></div>
<!-- Outputs: myDiv -->

<button data-on:click="el.disabled = true">
  Disable Self
</button>
```

---

## DOM Morphing with Idiomorph

### What is Morphing?

Morphing is the process of transforming one DOM tree into another while preserving:

- Element state (focus, scroll position)
- Event listeners
- CSS transitions/animations
- Reactive signal bindings

Datastar uses **Idiomorph**, a sophisticated DOM-merging algorithm.

### How It Works

**Default Behavior (mode: `outer`):**

1. Server sends HTML with IDs: `<div id="foo">New content</div>`
2. Datastar finds existing element with `id="foo"`
3. Idiomorph morphs (merges) old → new
4. Only changed parts update, state preserved

### Why Idiomorph?

Idiomorph creates **ID sets** - mappings of elements to all IDs within them. This enables better matching than simpler algorithms (morphdom, nanomorph) which only match direct IDs.

**Result:** More intelligent merging, especially for complex nested structures.

**Performance:** ~10% slower than morphdom for large morphs, equal or faster for small morphs.

### Morphing Best Practices

**1. Add IDs to elements you want to preserve:**

```html
<div id="container">
  <input id="email" type="email" />
  <div id="status"></div>
</div>
```

**2. Prevent morphing with `data-ignore-morph`:**

```html
<canvas id="chart" data-ignore-morph></canvas>
<!-- Canvas state preserved, not re-rendered -->
```

**3. Preserve specific attributes:**

```html
<details open data-preserve-attr="open">
  <!-- 'open' attribute preserved through morphs -->
</details>
```

**4. Use appropriate patch modes:**

```html
<!-- Append to list -->
<div data-on:click="@post('/add-item', {selector: '#list', mode: 'append'})">
```

### Fat Morph Pattern

Send large chunks (even entire `<html>` tag) and let compression + morphing handle efficiency:

```clojure
;; Server sends full page view
(defn render-full-page [state]
  (hiccup/html
    [:html
     [:head ...]
     [:body
      [:main#app
       (render-content state)]]]))
```

**Why this works:**

- Brotli compression: 90-100x reduction on repeated HTML
- Idiomorph: Efficiently morphs only changed parts
- Simpler than managing fine-grained updates

This is the **Hyperlith approach**: "Compression beats diffing."

---

## Async Patterns & Long-Running Operations

### Server-Sent Events for Streaming

SSE allows the backend to stream multiple events in a single response, perfect for:

- Long-running operations
- Progress updates
- Real-time dashboards
- Live collaboration

### Pattern 1: Progress Tracking

**Frontend:**

```html
<div data-signals:progress="0"></div>
<div data-signals:status="'idle'"></div>

<button data-on:click="@post('/long-operation')"
        data-indicator:running>
  <span data-show="!$running">Start</span>
  <span data-show="$running">Running...</span>
</button>

<div data-show="$running">
  <div>Progress: <span data-text="$progress"></span>%</div>
  <div data-text="$status"></div>
</div>
```

**Backend (Python example):**

```python
@app.post("/long-operation")
async def long_operation():
    async def stream():
        for i in range(0, 101, 10):
            # Update progress signal
            yield sse.PatchSignals({
                'progress': i,
                'status': f'Processing step {i//10}...'
            })

            # Simulate work
            await asyncio.sleep(0.5)

        # Final update
        yield sse.PatchSignals({
            'progress': 100,
            'status': 'Complete!'
        })

    return DatastarResponse(stream())
```

**Backend (Clojure example):**

```clojure
(defn long-operation-handler [request]
  (hk/as-channel request
    {:on-open
     (fn [ch]
       (future
         (doseq [i (range 0 101 10)]
           ;; Update progress
           (send-sse-event ch
             "datastar-patch-signals"
             {:signals (json/write-str {:progress i
                                        :status (str "Step " i "...")})})

           ;; Simulate work
           (Thread/sleep 500))

         ;; Done
         (send-sse-event ch
           "datastar-patch-signals"
           {:signals (json/write-str {:progress 100
                                      :status "Complete!"})})
         (hk/close ch)))}))
```

### Pattern 2: Infinite Stream

Keep connection open indefinitely, pushing updates as they occur:

```python
@app.get("/live-updates")
async def live_updates():
    async def stream():
        while True:
            # Get latest data from database/queue
            data = await get_latest_data()

            # Push update
            yield sse.PatchElements(
                f'<div id="updates">{render_data(data)}</div>'
            )

            # Wait for next update
            await asyncio.sleep(1)

    return DatastarResponse(stream())
```

**Frontend:**

```html
<!-- Auto-connects on load -->
<div data-init="@get('/live-updates')"
     data-on:online__window="@get('/live-updates')">
  <div id="updates">Loading...</div>
</div>
```

### Pattern 3: Broadcast to Multiple Clients

```clojure
(def connections (atom #{}))

(defn subscribe-handler [request]
  (hk/as-channel request
    {:on-open
     (fn [ch]
       (swap! connections conj ch)
       ;; Send initial state
       (send-sse-event ch "datastar-patch-elements"
         {:elements (render-current-state)}))

     :on-close
     (fn [ch _status]
       (swap! connections disj ch))}))

(defn broadcast! [html]
  (doseq [ch @connections]
    (send-sse-event ch "datastar-patch-elements"
      {:elements html})))

;; When data changes
(add-watch data-atom :broadcast
  (fn [_ _ _ new-state]
    (broadcast! (render-state new-state))))
```

### Browser Behavior with SSE

- **Tab hidden:** Browser automatically closes SSE connection
- **Tab visible:** Browser automatically reopens connection
- **Connection lost:** Browser automatically reconnects
- **Override:** Use `openWhenHidden: true` in action options

### Performance

SSE can handle **thousands of updates per second** with minimal server load (<2% CPU). Small, underpowered servers can push thousands of data points for interactive 3D models in browsers.

---

## Progressive Enhancement & Forms

### Progressive Enhancement Philosophy

Datastar follows a progressive enhancement approach:

1. Start with semantic HTML
2. Enhance with `data-*` attributes for reactivity
3. No JavaScript build step required (but TypeScript recommended)

### Forms vs Signals

**Key Difference from htmx:** Datastar discourages traditional forms because "they are ill-suited to nested reactive content."

Instead, Datastar sends **all reactive state (as JSON)** to the server on each request.

**Traditional Form (htmx):**

```html
<form hx-post="/save">
  <input name="email" />
  <button type="submit">Save</button>
</form>
```

**Datastar Approach (Signals):**

```html
<div data-signals:form.email="''"></div>
<input data-bind:form.email />
<button data-on:click="@post('/save')">Save</button>
```

**Server receives:**

```json
{
  "form": {
    "email": "user@example.com"
  }
}
```

### When to Use Form Mode

Use `contentType: 'form'` when you need:

- Built-in HTML5 validation
- File uploads
- Legacy backend expecting FormData

```html
<form data-on:submit.prevent="@post('/save', {contentType: 'form'})">
  <input name="username" required />
  <input name="email" type="email" required />
  <input type="file" name="avatar" />
  <button type="submit">Save</button>
</form>
```

**Key:** Signals are NOT sent with `contentType: 'form'` - only form data.

### Validation Pattern

**Client-side:**

```html
<input data-bind:email
       data-attr:class="$emailError ? 'error' : ''" />
<div data-show="$emailError"
     data-text="$emailError"
     class="error-message"></div>

<button data-on:click="
  $emailError = $email.includes('@') ? '' : 'Invalid email';
  if (!$emailError) @post('/save')
">Save</button>
```

**Server-side response:**

```
event: datastar-patch-signals
data: signals {"emailError": "Email already exists"}
```

### Progressive Load Example

```html
<!-- Shim page loads fast -->
<html>
<head>
  <script type="module" src="datastar.js"></script>
</head>
<body>
  <!-- Auto-fetch content on load -->
  <main id="content" data-init="@post('/')"></main>

  <!-- Reconnect when coming back online -->
  <div data-on:online__window="@post('/')"></div>

  <noscript>JavaScript required</noscript>
</body>
</html>
```

**Backend sends:**

```
event: datastar-patch-elements
data: selector #content
data: elements <div id="content">
data: elements   <h1>Welcome</h1>
data: elements   <p>Loaded via SSE</p>
data: elements </div>
```

**Benefits:**

- Fast initial load (shell is tiny, pre-compressed)
- Content only rendered for actual users (not bots)
- ETag caching for the shell

---

## Datastar vs htmx

### Key Differences

| Aspect | htmx | Datastar |
|--------|------|----------|
| **Size** | ~14 KB | ~10 KB |
| **Communication** | AJAX (XMLHttpRequest) | SSE (Server-Sent Events) |
| **Frontend Reactivity** | None (need Alpine.js) | Built-in (signals) |
| **Data Format** | FormData or custom | JSON (signals) |
| **Real-time Updates** | Polling or custom WebSockets | SSE push from server |
| **State Management** | None | Reactive signals |
| **Build Step** | None (pure JS) | Optional but recommended (TypeScript) |
| **Browser Support** | IE11+ | Modern browsers |
| **Plugin System** | Limited | Everything is a plugin |
| **Philosophy** | Push HTML spec forward | Adopt web-native features |

### When to Choose Datastar

- Need **real-time updates** (dashboards, collaboration)
- Want **unified framework** (no Alpine.js needed)
- Prefer **JSON over FormData** for complex nested data
- Value **TypeScript** and modern tooling
- Building **stateful SPAs** with backend control

### When to Choose htmx

- Need **IE11 support**
- Prefer **zero build step** (pure JS)
- Traditional **form-based workflows**
- Simpler **request-response patterns** (no streaming)
- Want to **extend HTML** as a philosophy

### Migration from htmx + Alpine.js

**Before (htmx + Alpine):**

```html
<div x-data="{count: 0}">
  <span x-text="count"></span>
  <button @click="count++" hx-post="/save" hx-vals='js:{count: count}'>
    Save
  </button>
</div>
```

**After (Datastar):**

```html
<div data-signals:count="0">
  <span data-text="$count"></span>
  <button data-on:click="$count++; @post('/save')">
    Save
  </button>
</div>
```

**Backend automatically receives:**

```json
{"count": 1}
```

---

## Code Examples

### Example 1: Click Counter

```html
<div data-signals:count="0">
  <div data-text="`Count: ${$count}`"></div>
  <button data-on:click="$count++">+</button>
  <button data-on:click="$count--">-</button>
  <button data-on:click="$count = 0">Reset</button>
</div>
```

### Example 2: Form with Validation

```html
<div data-signals:form.email="''"
     data-signals:form.password="''"
     data-signals:errors="{}">

  <input data-bind:form.email
         placeholder="Email"
         data-attr:class="$errors.email ? 'error' : ''" />
  <div data-show="$errors.email"
       data-text="$errors.email"
       class="error-msg"></div>

  <input type="password"
         data-bind:form.password
         placeholder="Password"
         data-attr:class="$errors.password ? 'error' : ''" />
  <div data-show="$errors.password"
       data-text="$errors.password"
       class="error-msg"></div>

  <button data-on:click="@post('/login')"
          data-indicator:logging-in
          data-attr:disabled="$loggingIn">
    <span data-show="!$loggingIn">Login</span>
    <span data-show="$loggingIn">Logging in...</span>
  </button>
</div>
```

**Server validation response:**

```
event: datastar-patch-signals
data: signals {
data: signals   "errors": {
data: signals     "email": "Invalid email format",
data: signals     "password": "Password too short"
data: signals   }
data: signals }
```

### Example 3: Search with Debounce

```html
<div data-signals:query="''"
     data-signals:results="[]">

  <input data-bind:query
         data-on:input.debounce.300ms="@post('/search')"
         placeholder="Search..." />

  <div data-show="$results.length > 0">
    <div data-text="`${$results.length} results`"></div>
    <ul>
      <!-- Results rendered by server -->
      <template id="results-template"></template>
    </ul>
  </div>
</div>
```

**Server response:**

```
event: datastar-patch-elements
data: selector #results-template
data: mode replace
data: elements <li>Result 1</li>
data: elements <li>Result 2</li>
data: elements <li>Result 3</li>
```

### Example 4: Live Dashboard

```html
<div data-init="@get('/dashboard-stream')">
  <div id="stats">Loading...</div>
</div>
```

**Server (Python):**

```python
@app.get("/dashboard-stream")
async def dashboard_stream():
    async def stream():
        while True:
            stats = await fetch_latest_stats()

            yield sse.PatchElements(
                f'''<div id="stats">
                  <div>Users: {stats.users}</div>
                  <div>Revenue: ${stats.revenue}</div>
                  <div>Updated: {stats.timestamp}</div>
                </div>'''
            )

            await asyncio.sleep(5)

    return DatastarResponse(stream())
```

### Example 5: Modal Dialog

```html
<div data-signals:modalOpen="false">
  <button data-on:click="$modalOpen = true">
    Open Modal
  </button>

  <div data-show="$modalOpen"
       class="modal-overlay"
       data-on:click.outside="$modalOpen = false">
    <div class="modal-content">
      <h2>Modal Title</h2>
      <p>Modal content here</p>
      <button data-on:click="$modalOpen = false">Close</button>
    </div>
  </div>
</div>
```

### Example 6: Bulk Import with Progress

**Frontend:**

```html
<div data-signals:import.status="'idle'"
     data-signals:import.progress="0"
     data-signals:import.log="[]">

  <button data-on:click="@post('/start-import')"
          data-indicator:importing
          data-attr:disabled="$importing">
    <span data-show="!$importing">Start Import</span>
    <span data-show="$importing">Importing...</span>
  </button>

  <div data-show="$importing">
    <div>Status: <span data-text="$import.status"></span></div>
    <div>Progress: <span data-text="$import.progress"></span>%</div>
    <div class="progress-bar">
      <div data-style:width="`${$import.progress}%`"></div>
    </div>

    <div id="log" class="log-container">
      <!-- Log entries appended by server -->
    </div>
  </div>
</div>
```

**Backend (Clojure):**

```clojure
(defn start-import-handler [request]
  (hk/as-channel request
    {:on-open
     (fn [ch]
       (future
         (doseq [[idx day] (map-indexed vector date-range)]
           (let [progress (int (* 100 (/ (inc idx) (count date-range))))]

             ;; Update signals
             (send-sse ch "datastar-patch-signals"
               {:signals (json/write-str
                          {:import {:status (str "Processing " day)
                                   :progress progress}})})

             ;; Append log entry
             (send-sse ch "datastar-patch-elements"
               {:selector "#log"
                :mode "append"
                :elements (hiccup/html
                          [:div.log-entry
                           [:span.timestamp (format-time (now))]
                           [:span.message (str "Imported " day)]])})

             ;; Do actual work
             (import-day! day)))

         ;; Complete
         (send-sse ch "datastar-patch-signals"
           {:signals (json/write-str
                     {:import {:status "Complete!"
                              :progress 100}})})
         (hk/close ch)))}))
```

---

## References

### Official Documentation

- [Datastar Website](https://data-star.dev/)
- [Getting Started Guide](https://data-star.dev/guide/getting_started)
- [Attributes Reference](https://data-star.dev/reference/attributes)
- [SSE Events Reference](https://data-star.dev/reference/sse_events)
- [Actions Reference](https://data-star.dev/reference/actions)
- [Backend Requests Guide](https://data-star.dev/guide/backend_requests_sse_events)

### Architecture & Philosophy

- [Why Another Framework?](https://data-star.dev/essays/why_another_framework)
- [Event Streams All the Way Down](https://data-star.dev/essays/event_streams_all_the_way_down)
- [Datastar vs htmx (htmx.org)](https://htmx.org/essays/alternatives/)

### Community Articles

- [Why I Switched from HTMX to Datastar](https://everydaysuperpowers.dev/articles/why-i-switched-from-htmx-to-datastar/)
- [Why I Chose Datastar Over Alpine.js/HTMX](https://dev.to/tinkerbaj/why-i-chose-datastar-over-alpinejshtmx-for-my-project-1j8b)
- [Using Datastar (Medium)](https://medium.com/@ianster/using-datastar-da1984a6cc77)

### Related Projects

- [GitHub - starfederation/datastar](https://github.com/starfederation/datastar)
- [Hyperlith Framework](https://github.com/andersmurphy/hyperlith) - Clojure framework using similar SSE+Brotli pattern
- [Idiomorph](https://github.com/bigskysoftware/idiomorph) - DOM morphing library used by Datastar

### SDK Repositories

- [Clojure SDK](https://github.com/starfederation/datastar-clojure)
- [Python SDK](https://github.com/starfederation/datastar-python)
- [Go SDK](https://github.com/starfederation/datastar-go)
- [Full SDK List](https://data-star.dev/reference/sdks)

### Video Resources

- [Datastar YouTube Channel](https://www.youtube.com/@data-star)

### Discord Community

- [Join Discord](https://discord.gg/bnRNgZjgPh)

---

## Summary

Datastar is a modern hypermedia framework that unifies frontend reactivity and backend-driven updates through Server-Sent Events. Its key strengths are:

1. **Lightweight** - 10 KB, no dependencies
2. **Real-time** - SSE enables push updates without polling
3. **Unified** - Single framework replaces htmx + Alpine.js
4. **Reactive** - Signals provide automatic UI updates
5. **Simple** - Declarative HTML attributes, no build step required
6. **Powerful** - Handles everything from simple sites to collaborative apps

The framework excels at building real-time dashboards, collaborative tools, and stateful SPAs while keeping most logic on the backend. Its SSE-based architecture enables streaming updates, progress tracking, and live collaboration with minimal client-side complexity.

For projects requiring modern real-time features with backend control, Datastar provides an elegant, lightweight solution that scales from simple enhancements to full SPA experiences.
