---
type: reference
status: active
tags: [reference, web]
---
# Datastar/SSE Quick Reference

**Target Audience:** Claude Code agents needing to work on this codebase
**Last Updated:** 2026-02-26

---

## Mental Model

Server owns all state. Client is a reactive view. Two update patterns:

- **Pattern A (Direct Response):** User clicks button, handler returns HTML, Datastar morphs DOM. No SSE involved.
- **Pattern B (Background Push):** Data changes in background, `refresh-all!` notifies SSE clients, each re-renders if view hash changed.

**Rule of thumb: If a user clicked something, return HTML directly. If data changed in the background, use `refresh-all!`.**

See `CONVENTIONS.md` section "SSE: Direct Response vs Background Push" for the full spec.

**NOT like:** React/Vue/Angular (client-side state management)
**IS like:** Traditional server-side rendering + SSE, but simpler

---

## SSE Wire Format

```
event: datastar-patch-elements
id: 7f8a3c2d
data: elements <main id="morph"><h1>Dashboard</h1>
data: elements   <div>More HTML...</div>
data: elements </main>

```

**Key details:**

- `event:` - Always `datastar-patch-elements` for us
- `id:` - Hash of HTML (for idempotency, change detection)
- `data: elements` - HTML lines (multiline support via repeated `data:`)
- `\n\n\n` - Terminates event (SSE spec requires `\n\n`, extra `\n` for clarity)

---

## Data Attributes We Actually Use

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-init` | Auto-execute on page load | `data-init="@post('/')"` |
| `data-on:click` | Click handler (POST to server) | `data-on:click="@post('/api/stop')"` |
| `data-on:submit` | Form submit | `data-on:submit="@post('/api/start', {contentType: 'form'})"` |
| `data-on:online__window` | Reconnect when browser online | `data-on:online__window="@post('/')"` |
| `id="morph"` | Target for SSE updates | `<main id="morph">...</main>` |

**Attributes we DON'T use (yet):**

- `data-signals` - Client-side reactive state (server-side only for now)
- `data-bind` - Two-way data binding
- `data-show`/`data-text` - Reactive display
- `data-indicator` - Loading states

---

## Clojure Patterns (Copy-Paste Ready)

### Pattern A: Direct Response (user actions)

```clojure
;; Handler — process action, return rendered HTML
(defn my-action-handler [_request]
  (do-the-thing!)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (render-my-view)})

;; Button — @post returns HTML, Datastar morphs it into the DOM
[:button {:data-on:click "@post('/api/my-action')"} "Do Thing"]

```

**Flow:** User clicks -> POST -> Handler returns HTML -> Datastar morphs DOM. Instant feedback.

### Pattern B: Background Push (SSE)

```clojure
(ns your-namespace
  (:require [seon.web.sse :as sse]))

;; 1. Define an SSE handler with a render function
(def my-sse
  (sse/render-handler
    (fn [_request]
      ;; Render FULL view on every call
      (render-my-view @app-state))))

;; 2. After data changes, notify SSE clients
(d/transact! conn tx-data)
(sse/refresh-all!)

;; 3. Or use atom watch for automatic push
(add-watch app-state ::sse-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))

```

**Key insight:** `render-handler` calls your function on every refresh, compares hash, only sends if changed.

### View Transitions

View transitions are **disabled by default** in `render-handler`. Opt in only for page-level navigations:

```clojure
(sse/render-handler #'my-render-fn :use-view-transition? true)

```

### Rendering HTML

```clojure
(ns your-namespace
  (:require [dev.onionpancakes.chassis.core :as h]))

(defn dashboard-view [state]
  (h/html
    [:main#morph
     [:h1 "Dashboard"]
     [:div.stat-card
      [:div.stat-value (format-number (:total state))]
      [:div.stat-label "Total Records"]]]))

```

**Critical:** Always include `id="morph"` on root element for SSE targeting.

### Background Job with Progress

```clojure
(defn start-import! [symbols start-date end-date]
  (let [job-id (random-uuid)]
    ;; 1. Update state immediately
    (swap! job-state assoc :current
      {:id job-id
       :status :running
       :progress {:completed 0 :total 100}})

    ;; 2. Start background work
    (future
      (try
        (doseq [i (range 100)]
          ;; Update progress (triggers SSE via watch)
          (swap! job-state assoc-in [:current :progress :completed] i)
          (do-work i))

        ;; Mark complete
        (swap! job-state assoc-in [:current :status] :completed)

        (catch Exception e
          ;; Mark failed
          (swap! job-state assoc :current
            {:status :failed :error (.getMessage e)}))))

    {:ok job-id}))

```

**Pattern:** Every `swap!` triggers watch -> `refresh-all!` -> all clients see update

### Shim Page (Initial HTML)

```clojure
(defn shim-page []
  (h/html
    [:html
     [:head
      [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.6/bundles/datastar.js"
                :defer true :type "module"}]
      [:style "/* Inline CSS here */"]]
     [:body
      ;; Auto-POST on load to establish SSE
      [:div {:data-init "@post('/', {retryMaxCount: Infinity})"
             ;; Reconnect when browser online
             :data-on:online__window "@post('/', {retryMaxCount: Infinity})"}]
      [:noscript "JavaScript required"]
      [:main#morph
       [:h1 "Loading..."]]]]))

```

**Routes:**

- `GET /` -> Returns shim page
- `POST /` -> SSE handler (streaming updates)

---

## Anti-Patterns (Things That Seem Right But Aren't)

### Don't: Send Partial Updates

```clojure
;; WRONG
(defn update-progress [percent]
  (sse/send-update! [:div#progress (str percent "%")]))

```

**Do instead:** Update state, let watch trigger full re-render.

### Don't: Return JSON from user actions

```clojure
;; WRONG — user won't see the change until next SSE poll
(defn toggle-handler [_request]
  (toggle!)
  {:status 200 :body "{\"ok\": true}"})

```

**Do instead:** Return rendered HTML so Datastar morphs it immediately (Pattern A).

### Don't: Track per-connection state

```clojure
;; WRONG
(defonce connection-state (atom {}))

```

**Do instead:** Store in database/atom indexed by session ID.

### Don't: Use `(require 'ns :reload)`

**Do instead:** Use `(user/reload)` or `(user/reset)` from REPL.

### Don't: Forget IDs on morphed elements

```html
<!-- WRONG -->
<div class="stat-card">
  <input type="text" />
</div>

<!-- RIGHT -->
<div id="stat-card-1" class="stat-card">
  <input id="input-symbol" type="text" />
</div>

```

**Why not:** Idiomorph can't track elements, lose focus/scroll state.

---

## File Map (Current Codebase)

| File | Purpose | Key Functions |
|------|---------|---------------|
| `src/seon/web/sse.clj` | SSE core | `render-handler`, `refresh-all!` |
| `src/seon/web/html.clj` | HTML rendering | `shim-page`, base layout |
| `src/seon/web/routes.clj` | URL routing | Route map (path -> handler) |
| `src/seon/web/agents.clj` | Agent observatory | Both Pattern A and B |
| `src/seon/web/components.clj` | Reusable UI | `card`, `status-dot`, `log-line` |
| `src/seon/ns/routes.clj` | Namespace pages | SSE + function call handlers |
| `CONVENTIONS.md` | SSE pattern ground truth | Pattern A/B spec |

---

## Common Tasks

### Add a new button action (Pattern A)

1. **HTML** (hiccup):

```clojure
[:button {:data-on:click "@post('/api/my-action')"} "Do Thing"]

```

1. **Handler**:

```clojure
(defn my-action [_request]
  (do-the-thing!)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (render-my-view)})

```

1. **Route**:

```clojure
["/api/my-action" {:post my-action}]

```

### Debug why SSE isn't updating

1. Check state changed: inspect atom/db in REPL
2. Check `refresh-all!` is being called (watch installed? manual call after transact?)
3. Verify SSE connected: Browser DevTools -> Network -> POST should be `Pending`
4. Manual trigger from REPL: `(seon.web.sse/refresh-all!)`
5. Check render function errors: `(user/logs)`

### Debug why button action doesn't update UI

1. Check handler returns `{"Content-Type" "text/html"}` (not JSON)
2. Check handler returns rendered HTML in `:body`
3. Check button uses `@post(...)` not `@get(...)`
4. Check response HTML has matching `id` attributes for morph targets

---

## Performance Notes

1. **Sliding buffer (size 1):** Under load, only most recent event kept. Clients always converge to latest state.
2. **Hash-based change detection:** Built into `render-handler` (only sends if view changed)
3. **Brotli disabled by default for SSE:** Small payloads get buffered by brotli, causing latency. Only enable for large views.
4. **poll-ms is a safety net:** Set to 10+ seconds. Reactive triggers (`refresh-all!`) handle timely updates.
5. **Virtual threads:** One per SSE connection, scales to thousands.

---

## Resources

- **Ground truth:** `CONVENTIONS.md` (SSE patterns section)
- **Datastar docs:** <https://data-star.dev/>
- **Deep dive:** `docs/reference/datastar-deep-dive.md`
- **Design system:** `docs/prds/namespace-ui/design-system.md`
- **Hyperlith examples:** `reference-code/hyperlith/examples/`
