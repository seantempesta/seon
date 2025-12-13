# Datastar/Hyperlith Quick Reference

**Target Audience:** Claude Code agents needing to work on this codebase
**Last Updated:** 2025-12-02

---

## Mental Model

Server owns all state. Client is a reactive view. Communication is one-way (server → client) via SSE. User actions POST back, server updates state, state watchers trigger SSE refresh. The key insight: **compression beats diffing** - send full HTML views, streaming brotli makes it efficient (90-100x).

**NOT like:** React/Vue/Angular (client-side state management)
**IS like:** Traditional server-side rendering + WebSockets, but simpler

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
| `data-on:submit` | Form submit | `data-on-submit="@post('/api/start', {contentType: 'form'})"` |
| `data-on:online__window` | Reconnect when browser online | `data-on:online__window="@post('/')"`

 |
| `id="morph"` | Target for SSE updates | `<main id="morph">...</main>` |

**Attributes we DON'T use (yet):**
- `data-signals` - Client-side reactive state (server-side only for now)
- `data-bind` - Two-way data binding
- `data-show`/`data-text` - Reactive display
- `data-indicator` - Loading states

---

## Clojure Patterns (Copy-Paste Ready)

### Pattern 1: Rendering HTML

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

### Pattern 2: SSE Handler

```clojure
(ns your-namespace
  (:require [ml-options.web.sse :as sse]
            [ml-options.web.html :as html]))

(def dashboard-sse
  (sse/render-handler
    (fn [_request]
      ;; Render FULL view on every call
      (let [state @app-state]
        (html/dashboard-view state)))))
```

**Key insight:** render-handler calls your function on every refresh, compares hash, only sends if changed.

### Pattern 3: User Action (POST Handler)

```clojure
(defn stop-import [_request]
  (try
    ;; Modify state (don't send SSE manually!)
    (swap! job-state assoc-in [:current :status] :stopping)

    ;; Return JSON response
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-value-as-string {:ok true})}

    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))
```

**Flow:** User clicks → POST → Handler updates state → Watcher triggers SSE → All clients re-render

### Pattern 4: Auto-Refresh on State Change

```clojure
(ns your-namespace
  (:require [ml-options.web.sse :as sse]))

(defonce app-state (atom {:status :idle}))

;; THIS IS THE MAGIC - set once at startup
(add-watch app-state :sse-auto-refresh
  (fn [_key _ref old-state new-state]
    (when (not= old-state new-state)
      (sse/refresh-all!))))
```

**Never manually call `sse/refresh-all!`** - let watches handle it.

### Pattern 5: Background Job with Progress

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
          ;; Update progress (triggers SSE automatically)
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

**Pattern:** Every `swap!` triggers watch → `refresh-all!` → all clients see update

### Pattern 6: Shim Page (Initial HTML)

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
- `GET /` → Returns shim page
- `POST /` → SSE handler (streaming updates)

---

## Anti-Patterns (Things That Seem Right But Aren't)

### ❌ Don't: Send Partial Updates

```clojure
;; WRONG
(defn update-progress [percent]
  (sse/send-update! [:div#progress (str percent "%")]))
```

**Why not:** Complexity, missed events cause desyncs, compression is less effective.

**Do instead:** Update state, let watch trigger full re-render.

### ❌ Don't: Manual refresh-all! Calls

```clojure
;; WRONG
(defn update-data [new-data]
  (reset! app-state new-data)
  (sse/refresh-all!)  ; Unnecessary!
  )
```

**Why not:** Easy to forget, inconsistent.

**Do instead:** Set up watch once, never call refresh-all! manually.

### ❌ Don't: Track per-connection state

```clojure
;; WRONG
(defonce connection-state (atom {}))

(defn on-connect [req]
  (swap! connection-state assoc (:channel req) {:user-id ...}))
```

**Why not:** Lost on disconnect, hard to debug.

**Do instead:** Store in database/atom indexed by session ID.

### ❌ Don't: Use `(require 'ns :reload)`

```clojure
;; WRONG
(require 'ml-options.web.handlers :reload)
```

**Why not:** Route handlers captured at compile time, reload doesn't update them.

**Do instead:** Always use `(integrant.repl/reset)` from REPL.

### ❌ Don't: Forget IDs on morphed elements

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

## File Map (Our Codebase)

| File | Purpose | Key Functions |
|------|---------|---------------|
| `src/ml_options/web/sse.clj` | SSE core | `render-handler`, `refresh-all!`, `init-sse!` |
| `src/ml_options/web/html.clj` | HTML rendering | `shim-page`, `dashboard-content` |
| `src/ml_options/web/handlers.clj` | HTTP handlers | `dashboard`, `dashboard-sse`, `start-import` |
| `src/ml_options/web/jobs.clj` | State management | `start-import!`, `stop-job!`, watch setup |
| `src/ml_options/web/stats.clj` | DB queries (cached) | `get-cached-stats`, `get-database-stats` |
| `src/ml_options/web/brotli.clj` | Streaming compression | `compress-stream`, `compress-out-stream` |
| `src/ml_options/web/routes.clj` | URL routing | Route map (path → handler) |
| `src/ml_options/web/server.clj` | System lifecycle | Integrant keys, startup/shutdown |

---

## Common Tasks

### Add a new button action

1. **HTML** (`html.clj`):
```clojure
[:button {"data-on-click" "@post('/api/my-action')"} "Do Thing"]
```

2. **Handler** (`handlers.clj`):
```clojure
(defn my-action [request]
  (swap! app-state assoc :thing :done)  ; Update state
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\": true}"})
```

3. **Route** (`routes.clj`):
```clojure
{:routes [["/api/my-action" {:post handlers/my-action}]]}
```

**No need to manually trigger SSE** - state watch handles it!

### Debug why SSE isn't updating

1. Check logs: `clj-nrepl-eval -p 7888 "(user/logs)"`
2. Verify state changed: `@ml-options.web.jobs/job-state`
3. Check watch is installed: Should see in `jobs.clj` line 21
4. Verify SSE connected: Browser DevTools → Network → POST `/` should be `Pending`
5. Manual trigger: `(ml-options.web.sse/refresh-all!)` from REPL

### Reload code changes

```bash
# ALWAYS use this (NOT require :reload)
clj-nrepl-eval -p 7888 "(integrant.repl/reset)"
```

---

## Performance Tips

1. **Throttle refreshes:** Already set to 100ms in `server.clj` (max 10 updates/sec)
2. **Cache expensive queries:** See `stats.clj` for 30-second cache pattern
3. **Use dropping buffers:** Already configured in SSE handler (slow clients don't block fast ones)
4. **Hash-based change detection:** Built into `render-handler` (only send if view changed)
5. **Virtual threads:** One per SSE connection, scales to thousands

---

## Key Insights (Not Obvious from Code)

1. **POST for SSE, not GET:** Datastar `@post('/')` opens SSE connection (GET returns shim)
2. **Idiomorph preserves state:** Focus, scroll, input values preserved during DOM updates
3. **Brotli learns patterns:** First render 5KB, subsequent ~50 bytes (dictionary builds up)
4. **Throttling is GOOD:** Users can't see >10 updates/sec anyway, saves bandwidth
5. **view = f(state):** Every render is from scratch, no deltas, compression handles efficiency

---

## Quick Debug Checklist

Problem: Dashboard not updating

- [ ] Is SSE connected? (Check browser DevTools Network tab for pending POST)
- [ ] Is state actually changing? (`@job-state` in REPL)
- [ ] Is watch installed? (Should be in `jobs.clj:21`)
- [ ] Is render function erroring? (Check logs with `(user/logs)`)
- [ ] Is hash changing? (Enable debug logging: `(taoensso.timbre/set-level! :debug)`)

Problem: Code changes not applying

- [ ] Did you use `(integrant.repl/reset)` not `require :reload`?
- [ ] Is the system actually running? (`(user/status)`)
- [ ] Did reset fail with errors? (Fix errors, reset again)

Problem: SSE disconnects frequently

- [ ] Are you on HTTP/1.1? (Need HTTP/2 via Caddy reverse proxy)
- [ ] Browser throttling hidden tabs? (Expected, reconnects automatically)
- [ ] Network issues? (Check `data-on:online__window` in shim-page)

---

## Resources

- **Hyperlith examples:** `/reference-code/hyperlith/examples/` (chat_atom, game_of_life, presence_cursors)
- **Our docs:** `docs/hyperlith-patterns.md`, `docs/current-sse-implementation.md`
- **Datastar docs:** https://data-star.dev/ (official site)
- **Blog post:** https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html
