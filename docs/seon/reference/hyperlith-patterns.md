# Hyperlith Patterns: Deep Dive

**Date:** 2025-12-02
**Author:** Research based on Hyperlith framework by Anders Murphy
**References:**

- [GitHub: andersmurphy/hyperlith](https://github.com/andersmurphy/hyperlith)
- [Blog: Realtime collaborative web apps without ClojureScript](https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html)
- [Datastar Documentation](https://data-star.dev/)

---

## Executive Summary

Hyperlith is an opinionated Clojure framework that challenges conventional wisdom about web application architecture. Its core insight: **streaming brotli compression is so effective (90-100x reduction) that sending full HTML views on every state change is simpler AND more performant than fine-grained updates or diffing.**

This document captures Hyperlith's patterns for SSE + server-rendered HTML, with specific recommendations for the ml-options-trading codebase.

---

## The Hyperlith Philosophy

### Core Principle: `view = f(state)`

Hyperlith simplifies web applications to a single equation:

```clojure
view = f(state)
```

Instead of:

- Tracking which parts of the UI need updates
- Calculating deltas between view states
- Managing client-side state synchronization
- Handling missed events during disconnects

You just:

1. Maintain state on the server
2. Render the full view whenever state changes
3. Stream it to all connected clients

### Why This Works: The Compression Insight

**Counterintuitive truth:** Sending 100KB of HTML is more efficient than sending 1KB of partial updates when you factor in:

1. **Brotli streaming compression** - Maintains compression dictionary across the connection lifetime
2. **Repeated patterns** - HTML has massive redundancy across re-renders
3. **Effective compression ratios** - 100:1 to 230:1 compression over a series of re-renders
4. **Zero client complexity** - No state sync logic, no missed event handling

Anders Murphy's quote:
> "Intuitively you would think the diffing approach would be more performant so you wouldn't even consider this approach. The compression is so good that in my experience it's more network efficient and more performant than fine grained updates with diffing (without any of the additional complexity)."

---

## Architecture Patterns

### 1. CQRS: Commands and Queries

Hyperlith enforces strict separation:

#### Commands (Actions)

- Modify the database
- Return `204 No Content` (or `200` with signal patches)
- **Never** directly update the view
- Examples: POST endpoints that create/update/delete data

```clojure
(defaction handler-send-message
  [{:keys [db] {:keys [message]} :body}]
  (when-not (str/blank? message)
    (swap! db update :messages conj [(new-uid) message])
    ;; Return signal update OR 204
    (patch-signals {:message ""})))
```

#### Queries (Views)

- Read from database
- Render the full view
- Pushed via SSE when database changes
- Examples: Render functions that generate HTML

```clojure
(defview handler-home {:path "/"}
  [{:keys [db]}]
  (html [:main#morph ...render everything...]))
```

**Key constraint:** Actions should NOT update views via `patch-elements`. Changes flow through the database, which triggers automatic re-renders.

### 2. State-Driven Auto-Refresh

Use atom watches to automatically trigger SSE updates:

```clojure
;; From chat_atom example
(defn ctx-start []
  (let [db_ (atom {:messages []})]
    ;; ANY change to db_ triggers refresh-all!
    (add-watch db_ :refresh-on-change
      (fn [& _] (refresh-all!)))
    {:db db_}))
```

**Our implementation:**

```clojure
;; ml-options.web.jobs
(add-watch job-state :sse-auto-refresh
  (fn [_key _ref old-state new-state]
    (when (not= old-state new-state)
      (sse/refresh-all!))))
```

✅ **Status:** We already implement this pattern correctly!

### 3. Homogeneous Events

All SSE events are the same: "here's the current view state."

**Traditional approach (error-prone):**

- Event A: "User joined"
- Event B: "Message sent"
- Event C: "User left"
- Problem: Miss event B → UI desync

**Hyperlith approach (robust):**

- Every event: "Here's the current state of the chat room"
- Problem: Miss any event → next event fixes it
- Benefit: Can throttle without losing data

Quote from Hyperlith docs:
> "When your events are not homogeneous, you can't miss events, so you cannot throttle your events without losing data."

### 4. Throttling & Batching

Because events are homogeneous (full view state), you can safely throttle:

```clojure
(defn throttle [<in-ch msec]
  (let [<out-ch (a/chan)]
    (thread
      (while-some [event (a/<!! <in-ch)]
        (a/>!! <out-ch event)
        (Thread/sleep msec)))
    <out-ch))

;; Usage
{:max-refresh-ms 100}  ; Max 10 updates/second
```

Even at 200ms throttle (5 FPS), real-time collaboration feels smooth because:

- Idiomorph efficiently diffs DOM on client
- Brotli compression keeps bandwidth low
- Users don't perceive sub-200ms latency

**Our implementation:**

```clojure
;; ml-options.web.sse
(init-sse! :max-refresh-ms 200)  ; 5 updates/second
```

✅ **Status:** We implement throttling correctly!

---

## Technical Implementation

### 1. Streaming Brotli Compression

**The Key:** Maintain compressor state across writes to learn patterns.

```clojure
;; One-shot compression (BAD for SSE)
(defn compress [data opts]
  (Encoder/compress (encoder-params opts) data))

;; Streaming compression (GOOD for SSE)
(defn compress-stream [out br chunk]
  (doto br
    (.write (String/.getBytes chunk "UTF-8"))
    (.flush))
  (let [result (.toByteArray out)]
    (.reset out)  ; Clear buffer for next chunk
    result))

;; Usage in SSE handler
(with-open [out (byte-array-out-stream)
            br  (compress-out-stream out :window-size 18)]
  (loop []
    (when-let [html (render-view)]
      (send! ch (compress-stream out br html))
      (recur))))
```

**Window size tuning:**

- `window-size 18` = 262KB dictionary (good default)
- `window-size 24` = 16MB dictionary (maximum, better compression, more memory)
- Larger windows = better compression for highly repetitive data (like dashboard HTML)

**Our implementation:** ✅ We have `ml-options.web.brotli` ported from Hyperlith!

### 2. SSE Event Format

```clojure
(defn patch-elements [event-id elements]
  (str "event: datastar-patch-elements"
       "\nid: " event-id
       "\ndata: elements " (str/replace elements "\n" "\ndata: elements ")
       "\n\n\n"))
```

**Key details:**

- `event:` line specifies Datastar event type
- `id:` enables idempotency (browser can detect duplicates)
- `data:` lines contain the payload (multi-line supported)
- `\n\n\n` terminates the event (SSE spec requires `\n\n`, extra `\n` for clarity)

**Our implementation:** ✅ We match this exactly in `ml-options.web.sse`!

### 3. Hash-Based Change Detection

Only send updates when view actually changes:

```clojure
(loop [last-hash nil]
  (when-some [view (render-fn req)]
    (let [view-str  (html->str view)
          view-hash (Integer/toHexString (hash view-str))]
      ;; Only send if changed
      (when (not= last-hash view-hash)
        (send! ch (compress view-str)))
      (recur view-hash))))
```

**Why `Integer/toHexString(hash ...)`?**

- Fast: JVM hash is O(n) but very fast for strings
- Compact: Hex string is small for event IDs
- Good enough: Hash collisions are acceptable (worst case = extra send)

**Our implementation:** ✅ We implement this in `ml-options.web.sse`!

### 4. The Shim Page Pattern

**Traditional:** Server renders full HTML on first GET request.

**Hyperlith:** Server sends minimal shell, client POSTs to SSE endpoint:

```html
<html>
  <head>
    <script src="datastar.js"></script>
    <style>/* All CSS inline for single request */</style>
  </head>
  <body>
    <!-- Auto-POST on load -->
    <div data-init="@post('/')"></div>

    <!-- Reconnect when browser comes online -->
    <div data-on:online__window="@post('/')"></div>

    <!-- Fallback for no-JS -->
    <noscript>Your browser does not support JavaScript!</noscript>

    <!-- Will be populated via SSE -->
    <main id="morph"></main>
  </body>
</html>
```

**Benefits:**

1. **Bot filtering** - Bots don't execute JS, so they don't open SSE connections
2. **Pre-compression** - Shell is static, compress once with quality 11
3. **ETag caching** - Shell only re-downloaded if it changes
4. **Auto-reconnect** - Handles network changes gracefully

**Our implementation:** ✅ We implement this in `ml-options.web.html`!

### 5. HTTP/2 or HTTP/3 Required

SSE performs poorly over HTTP/1.1 because:

- HTTP/1.1: One request per connection
- SSE holds a connection open indefinitely
- HTTP/1.1 browsers limit to ~6 connections per domain
- Result: SSE blocks other requests

**Solution:** Use HTTP/2+ (multiplexing) via reverse proxy:

```bash
# Caddyfile
localhost:3030 {
    reverse_proxy localhost:8080
}
```

Caddy automatically provides:

- HTTP/2 support (multiplexing)
- TLS certificate (required for HTTP/2)
- Compression negotiation
- Static file serving

**Our status:** ⚠️ TODO: Add Caddyfile to project for development

---

## User Interaction Patterns

### 1. Form Submissions

```html
<form data-on-submit="@post('/api/import/start', {contentType: 'form'})">
  <input name="symbols" type="text" />
  <button type="submit">Start Import</button>
</form>
```

Datastar automatically:

- Prevents default form submission
- Serializes form data
- POSTs to action endpoint
- Processes SSE response (if any)

### 2. Button Clicks (Actions)

```html
<button data-on-click="@post('/api/import/stop')">
  Stop Import
</button>
```

### 3. Optimistic UI Updates (Signals)

```html
<input type="text" data-bind="message" />
<button data-on-click="@post('/send', {message: $message})">
  Send
</button>
```

The action can return signal updates for immediate feedback:

```clojure
(defaction handler-send
  [{:keys [db] {:keys [message]} :body}]
  (swap! db conj message)
  ;; Clear input immediately (optimistic)
  (patch-signals {:message ""}))
```

**Signal naming conventions:**

- `message` - Client-controlled, needs `__ifmissing` to survive re-renders
- `_internalState` - Server-only (leading underscore), not sent to client

### 4. Responsive Interactions

Use `pointerdown` instead of `click` for lower latency:

```html
<!-- 50-100ms faster perceived response -->
<button data-on:pointerdown="@post('/action')">
  Click Me
</button>
```

Why? Event order:

1. `pointerdown` (finger touches screen)
2. `pointerup` (finger lifts)
3. `click` (after both pointer events)

---

## Async Operations & Background Work

### The Pattern: Database-Driven State

For long-running operations (like bulk imports):

```clojure
(defn start-import! [params]
  ;; 1. Update database immediately
  (swap! job-state assoc :current
    {:status :running
     :progress {:percent 0}})

  ;; 2. Start background work
  (future
    (try
      ;; 3. Update database as work progresses
      (doseq [item items]
        (process item)
        (swap! job-state update-in
          [:current :progress :percent] inc))

      ;; 4. Mark complete in database
      (swap! job-state assoc-in
        [:current :status] :completed)

      (catch Exception e
        ;; 5. Mark failed in database
        (swap! job-state assoc-in
          [:current :status] :failed)))))
```

**Key points:**

1. Database is source of truth
2. Atom watch triggers SSE on every update
3. View function renders current state
4. No manual "send update to client" calls

**Our implementation:** ✅ We follow this pattern in `ml-options.web.jobs`!

### Progress Updates

```clojure
;; In background worker
(defn process-symbol [symbol day]
  ;; Do work...

  ;; Update state
  (swap! job-state update-in [:current :progress] merge
    {:current-symbol symbol
     :current-day    day
     :records-loaded count})

  ;; SSE update happens automatically via atom watch!
  )
```

### Cancellation

```clojure
;; Store future for cancellation
(let [f (future (long-running-task))]
  (swap! job-state assoc-in [:current :future] f))

;; Cancel button action
(defaction stop-job [{:keys [job-state]}]
  (when-let [f (get-in @job-state [:current :future])]
    (future-cancel f)
    (swap! job-state assoc-in [:current :status] :stopping))
  nil)  ; Return 204
```

---

## State Management Patterns

### 1. Single Atom Per Context

```clojure
(defn ctx-start []
  (let [db_ (atom {:users    {}
                   :messages []
                   :settings {}})]
    (add-watch db_ :refresh (fn [& _] (refresh-all!)))
    {:db db_}))
```

**Benefits:**

- Simple: One source of truth
- Automatic: Any change triggers refresh
- Transactional: `swap!` is atomic

**Trade-off:** Every change triggers re-render of entire page.

**Mitigation:** Throttling limits refresh rate, so rapid changes are batched.

### 2. Multiple Atoms (Advanced)

For independent sections that update at different rates:

```clojure
(defn ctx-start []
  (let [user-state_    (atom {})
        import-state_  (atom {})]

    ;; Separate refresh channels
    (add-watch user-state_   :refresh-users
      (fn [& _] (refresh-channel! :users)))
    (add-watch import-state_ :refresh-imports
      (fn [& _] (refresh-channel! :imports)))

    {:user-state   user-state_
     :import-state import-state_}))
```

**Use case:** When different SSE endpoints serve different views (rare).

**Our approach:** Single atom is sufficient for the dashboard.

### 3. Work Sharing (Multiplayer Optimization)

When N users view the same content, render once and broadcast:

```clojure
(defn render-handler [path render-fn]
  (let [;; Shared render atom
        cached-view_ (atom nil)]

    (add-watch db_ :update-cache
      (fn [& _]
        ;; Update cache when state changes
        (reset! cached-view_ (render-fn))))

    (fn handler [req]
      ;; All clients get same view
      @cached-view_)))
```

**Hyperlith's batching:** Renders once per throttle window, broadcasts to all connections.

**Our status:** Single dashboard user, so work sharing not needed yet.

---

## Performance Characteristics

### Bandwidth

**Typical dashboard:**

- Uncompressed HTML: 50-150KB
- First render (cold): ~5-10KB (brotli, cold dictionary)
- Subsequent renders: ~500 bytes to 2KB (hot dictionary)
- **Effective compression:** 50:1 to 100:1 after warmup

**Real-world example** from Hyperlith docs:

- Game of Life with 10,000 cells
- 200ms refresh rate (5 FPS)
- ~1KB per update after compression warmup
- 5 KB/sec bandwidth per client

### Latency

**Components:**

1. Database update: <1ms (in-memory atom)
2. Atom watch triggers: <1ms
3. Render function: 1-10ms (depends on complexity)
4. Brotli compression: 1-5ms
5. Network send: 10-50ms (varies by connection)

**Total: ~15-70ms** from state change to client UI update.

**User perception:** <100ms feels instant, so this is excellent.

### CPU Usage

**Per render cycle:**

- Hiccup → HTML: O(n) where n = DOM size
- Brotli compression: O(n) but highly optimized
- Hash calculation: O(n) but very fast

**Our dashboard:** ~50KB HTML → ~5-10ms render + compress.

**Virtual threads:** Hyperlith uses Java 21+ virtual threads for SSE handlers:

```clojure
;; Each SSE connection gets a virtual thread
(.start (Thread/ofVirtual)
  (fn []
    (with-open [out (byte-array-out-stream)
                br  (compress-out-stream out)]
      (loop []
        ;; Blocking OK in virtual threads
        (when-let [event (a/<!! refresh-ch)]
          (render-and-send)
          (recur))))))
```

**Scalability:** 10,000+ concurrent SSE connections per process (vs ~200 with platform threads).

### Memory Usage

**Per SSE connection:**

- Brotli encoder: ~(2^window-size) bytes dictionary
- Window size 18: 262KB per connection
- Virtual thread: ~1KB stack
- core.async channels: ~1KB

**Total: ~265KB per connection**

**10 concurrent dashboards:** ~2.6MB (negligible)

---

## Datastar Integration

### What is Datastar?

Datastar is the client-side library (11.4KB brotli) that:

1. Establishes SSE connection on `@post()` calls
2. Receives `datastar-patch-elements` events
3. Uses Idiomorph to efficiently merge HTML into DOM
4. Manages client-side signals (reactive state)
5. Handles disconnects/reconnects automatically

### Core Datastar Concepts

#### 1. Signals (Client State)

```html
<div data-signals:count="0">
  <p>Count: $count</p>
  <button data-on-click="$$count++">Increment</button>
</div>
```

Signals are reactive variables accessible via `$signalName`.

**Server can update signals:**

```clojure
(patch-signals {:count 42})
```

#### 2. Actions (Server Calls)

```html
<button data-on-click="@post('/api/action', {foo: 'bar'})">
  Submit
</button>
```

The `@` prefix means "call server endpoint."

#### 3. Expressions (Client Logic)

```html
<div data-show="$count > 10">
  Count is high!
</div>
```

Datastar evaluates expressions client-side for immediate feedback.

#### 4. SSE Events

Datastar handles multiple SSE event types:

- `datastar-patch-elements` - Update DOM (what we use)
- `datastar-patch-signals` - Update client state
- `datastar-execute-expr` - Run client JS expression
- `datastar-remove` - Remove elements
- `datastar-redirect` - Navigate to URL

**Hyperlith uses only `patch-elements`** for simplicity.

### Idiomorph: Smart DOM Merging

Datastar uses [Idiomorph](https://github.com/bigskysoftware/idiomorph) for DOM diffing:

1. Server sends full HTML fragment
2. Idiomorph compares with existing DOM
3. Only changed elements are updated
4. Element IDs are used for stable identity
5. Focus, scroll position, and form state preserved

**Why it's fast:**

- Operates on DOM directly (no VDOM overhead)
- Only touches changed nodes
- Preserves live state (input focus, scroll position)

**Example:** Dashboard with 1000 table rows:

- Only changed rows are updated in DOM
- Unchanged rows: Zero DOM manipulation
- Result: 60 FPS updates even with large tables

---

## Recommendations for ml-options-trading

### What We're Doing Right ✅

1. **Streaming brotli compression** - Implemented in `ml-options.web.brotli`
2. **SSE with hash-based change detection** - Implemented in `ml-options.web.sse`
3. **Shim page pattern** - Implemented in `ml-options.web.html`
4. **Auto-refresh via atom watch** - Implemented in `ml-options.web.jobs`
5. **Throttled updates** - 200ms refresh rate configured
6. **CQRS separation** - Actions POST, views render on state change

**Verdict:** We've successfully adopted Hyperlith patterns! 🎉

### What to Add ⚠️

#### 1. Caddy Reverse Proxy (Priority: High)

**Why:** HTTP/2 multiplexing required for SSE to not block other requests.

**Action:**

```bash
# Create Caddyfile
cat > Caddyfile <<EOF
localhost:3030 {
    reverse_proxy localhost:8080

    # Enable compression negotiation
    encode gzip zstd

    # Security headers
    header {
        Strict-Transport-Security "max-age=31536000"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
    }
}
EOF

# Start Caddy
caddy run

# Update CLAUDE.md
./bin/run            # Main app (port 8080)
./bin/thetadata      # ThetaData Terminal
caddy run            # Reverse proxy (port 3030)
```

**Update:** Access dashboard at `https://localhost:3030` instead of `http://localhost:8080`.

#### 2. Virtual Thread Configuration (Priority: Medium)

**Why:** Better scalability for concurrent SSE connections.

**Current:** We use `Thread/ofVirtual` in SSE handler (good!).

**Enhancement:** Configure global virtual thread executor:

```clojure
;; In ml-options.core or system.clj
(set-agent-send-executor!
  (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))

(set-agent-send-off-executor!
  (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
```

Requires Java 21+. Check project deps.edn.

#### 3. Brotli Window Size Tuning (Priority: Low)

**Current:** Default window size 18 (262KB dictionary)

**Experiment:** Try window size 22-24 for dashboard with repetitive HTML:

```clojure
(render-handler render-fn
  :br-window-size 22)  ; 4MB dictionary (16x more)
```

**Trade-off:** More memory per connection, better compression.

**Recommend:** Profile bandwidth vs memory before changing.

#### 4. Signal Management for Forms (Priority: Low)

**Current:** No client-side signals used yet.

**Future enhancement:** Optimistic UI updates for form submissions:

```html
<form data-on-submit="@post('/api/import/start', $formData)">
  <input data-bind="symbols" />
  <button type="submit" data-attr:disabled="$loading">
    Start Import
  </button>
</form>
```

```clojure
(defaction start-import [req]
  (let [result (start-import! params)]
    ;; Signal update for immediate feedback
    (patch-signals {:loading true})
    result))
```

**Use case:** Show loading state before SSE update arrives.

### What to Avoid ❌

1. **Don't use `starfederation.datastar.clojure` library** - We're better off with raw SSE control
2. **Don't do per-message gzip** - Streaming brotli is the key
3. **Don't manually call `sse/refresh-all!`** - Let atom watches handle it
4. **Don't send partial updates** - Always render full view
5. **Don't use WebSockets** - SSE + HTTP/2 is simpler and more efficient

---

## Testing Recommendations

### 1. Compression Effectiveness

Measure actual compression ratios in production:

```clojure
(defn send! [ch event]
  (let [uncompressed-size (count event)
        compressed (compress event)
        compressed-size (count compressed)
        ratio (/ uncompressed-size compressed-size)]
    (log/info "SSE compression"
              {:uncompressed uncompressed-size
               :compressed   compressed-size
               :ratio        ratio})
    (hk/send! ch compressed)))
```

**Expected:** 20:1 to 100:1 ratio after warmup.

### 2. Render Performance

Benchmark render function:

```clojure
(defn render-handler [render-fn]
  (fn [req]
    (let [start (System/nanoTime)
          html (render-fn req)
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (when (> elapsed-ms 10)
        (log/warn "Slow render" {:ms elapsed-ms}))
      html)))
```

**Target:** <10ms render time for dashboard.

### 3. Throttle Effectiveness

Monitor throttle drops:

```clojure
(defn throttle [<in-ch msec]
  (let [<out-ch (a/chan)
        drops_  (atom 0)]
    (thread
      (loop []
        (when (a/<!! <in-ch)
          ;; Count how many events queued
          (let [dropped (count (repeatedly #(a/poll! <in-ch)))]
            (swap! drops_ + dropped)
            (when (pos? dropped)
              (log/debug "Throttled events" {:dropped dropped})))
          (a/>!! <out-ch :refresh)
          (Thread/sleep msec)
          (recur))))
    {:channel <out-ch
     :stats   drops_}))
```

**Expected:** Some drops during rapid state changes (this is good - means throttling works).

### 4. Connection Stability

Monitor SSE connection lifecycle:

```clojure
(defonce connections_ (atom 0))

(defn render-handler [render-fn]
  (fn [req]
    {:on-open  (fn [_]
                 (swap! connections_ inc)
                 (log/info "SSE connected"
                           {:total @connections_}))
     :on-close (fn [_]
                 (swap! connections_ dec)
                 (log/info "SSE closed"
                           {:total @connections_}))}))
```

**Expected:** Stable connection count, reconnects after network changes.

---

## Common Patterns from Hyperlith Examples

### 1. Chat Application

```clojure
;; State
(def db_ (atom {:messages []}))
(add-watch db_ :refresh (fn [& _] (refresh-all!)))

;; Action
(defaction send-message [{:keys [db] {:keys [msg]} :body}]
  (swap! db update :messages conj msg)
  (patch-signals {:input ""}))  ; Clear input

;; View
(defview chat-view {:path "/"}
  [{:keys [db]}]
  (html
    [:main#morph
     [:input {:data-bind "input"}]
     [:button {:data-on:click (str "@post('" send-message "')")}
       "Send"]
     (for [msg (:messages @db)]
       [:p msg])]))
```

**Pattern:** State in atom → Auto-refresh → Full view render

### 2. Game of Life (High Frequency Updates)

```clojure
;; State updates 10 times/second
(def game-state_ (atom (init-game)))

;; Background loop
(future
  (while true
    (Thread/sleep 100)  ; 10 FPS
    (swap! game-state_ tick-game)))  ; Triggers auto-refresh

;; View renders full grid every time
(defview game-view {:path "/"}
  [{:keys [game-state]}]
  (html
    [:main#morph
     (for [cell (:cells @game-state)]
       [:div {:class (if (:alive cell) "alive" "dead")}])]))
```

**Pattern:** Background computation → State updates → View re-render

**Key insight:** 10 FPS with thousands of cells is fine because brotli compression is so effective.

### 3. Collaborative Drawing (Presence)

```clojure
;; State includes cursor positions for all users
(def users_ (atom {}))

(defaction update-cursor [{:keys [users sid] {:keys [x y]} :body}]
  (swap! users assoc-in [sid :cursor] {:x x :y y})
  nil)  ; 204 response

;; View shows all cursors
(defview canvas-view {:path "/"}
  [{:keys [users]}]
  (html
    [:main#morph
     ;; Canvas with mousemove tracking
     [:div {:data-on:mousemove (str "@post('" update-cursor "', {x: event.clientX, y: event.clientY})")}
      ;; Draw all user cursors
      (for [[uid user] @users]
        [:div.cursor {:style (str "left: " (-> user :cursor :x) "px; top: " (-> user :cursor :y) "px")}])]]))
```

**Pattern:** Frequent position updates → Throttled SSE → Smooth multiplayer

### 4. Billion Checkboxes (High Element Count)

```clojure
;; State: 1 billion checkboxes (sparse representation)
(def checked_ (atom #{}))  ; Set of checked IDs

;; View: Virtualized scrolling, only renders visible checkboxes
(defview checkboxes-view {:path "/"}
  [{:keys [checked] :as req}]
  (let [scroll-pos (get-in req [:params :scroll] 0)
        visible (range scroll-pos (+ scroll-pos 100))]  ; 100 visible
    (html
      [:main#morph
       (for [i visible]
         [:input {:type "checkbox"
                  :checked (contains? @checked i)
                  :data-on:change (str "@post('/toggle', {id: " i "})")}])])))

;; Action: Toggle checkbox
(defaction toggle-checkbox [{:keys [checked] {:keys [id]} :body}]
  (swap! checked (fn [s] (if (contains? s id) (disj s id) (conj s id))))
  nil)
```

**Pattern:** Sparse state + virtual scrolling + targeted updates = handles massive datasets

---

## Anti-Patterns to Avoid

### 1. ❌ Sending Deltas

```clojure
;; DON'T DO THIS
(defn update-message [id new-text]
  (sse/send-update!
    [:div#message-{id} new-text]))  ; Partial update
```

**Why not:** Complexity increases, compression decreases, missed events cause desyncs.

**Do this instead:**

```clojure
(defn update-message [id new-text]
  (swap! db assoc-in [:messages id :text] new-text))
  ;; Auto-refresh sends full view
```

### 2. ❌ Client-Side State Sync

```clojure
;; DON'T DO THIS
(defaction update-count [{:keys [body]}]
  ;; Client sends its current count
  (let [client-count (:count body)]
    ;; Try to reconcile with server...
    (when (not= client-count @server-count)
      (resolve-conflict))))
```

**Why not:** Distributed state is hard. Let server be source of truth.

**Do this instead:**

```clojure
(defaction increment-count [_]
  (swap! count inc))  ; Server state only
  ;; View shows server count
```

### 3. ❌ Manual Refresh Calls

```clojure
;; DON'T DO THIS
(defn update-data [new-data]
  (reset! db new-data)
  (sse/refresh-all!)  ; Manual call
  (log/info "Updated"))
```

**Why not:** Easy to forget, leads to inconsistent refresh behavior.

**Do this instead:**

```clojure
;; Set up once at startup
(add-watch db :auto-refresh (fn [& _] (sse/refresh-all!)))

;; Then just update state
(defn update-data [new-data]
  (reset! db new-data))  ; Refresh happens automatically
```

### 4. ❌ Stateful SSE Connections

```clojure
;; DON'T DO THIS
(defonce connection-state_ (atom {}))  ; Per-connection state

(defn on-connect [req]
  (swap! connection-state_ assoc (:id req) {...}))
```

**Why not:** Connection state is lost on disconnect. Hard to debug.

**Do this instead:**

```clojure
;; Store all state in database, indexed by session ID
(defn on-connect [req]
  (swap! db assoc-in [:sessions (:session-id req)] {...}))
  ;; View reads from db, not connection state
```

### 5. ❌ Complex Signal Management

```clojure
;; DON'T DO THIS - Too much client state
<div data-signals:formState="{errors: [], touched: [], pristine: true, ...}">
```

**Why not:** Client state complexity defeats the purpose of server-driven UI.

**Do this instead:**

```clojure
;; Keep client state minimal
<div data-signals:inputValue="">  ; Just the input value
  ;; Server validates and renders errors in next view
```

---

## Conclusion

Hyperlith represents a paradigm shift in web application architecture:

**Traditional approach:**

- Fine-grained updates (WebSockets or fetch)
- Client-side state management
- Complex synchronization logic
- Missed events are catastrophic

**Hyperlith approach:**

- Full view updates (SSE)
- Server-side state management
- Simple: view = f(state)
- Missed events are harmless (next update fixes it)

**The ml-options-trading codebase has successfully adopted these patterns.** Our implementation of:

- Streaming brotli compression
- SSE with hash-based change detection
- Auto-refresh via atom watches
- CQRS separation (actions vs views)

...is architecturally sound and follows Hyperlith best practices.

**Next steps:**

1. Add Caddy reverse proxy for HTTP/2
2. Consider virtual thread executor configuration
3. Profile compression ratios in production
4. Monitor render performance
5. Add metrics for throttle effectiveness

**The investment in learning Hyperlith patterns has paid off** - we have a robust, scalable, maintainable SSE architecture that will handle the dashboard requirements with ease.

---

## References

- [Hyperlith GitHub Repository](https://github.com/andersmurphy/hyperlith)
- [Building Realtime Collaborative Web Apps without ClojureScript](https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html)
- [Datastar Documentation](https://data-star.dev/)
- [Datastar Backend Requests & SSE Guide](https://data-star.dev/guide/backend_requests_sse_events)
- [Idiomorph DOM Morphing Library](https://github.com/bigskysoftware/idiomorph)
- Our existing docs: `docs/hyperlith-comparison.md`

**Local reference code:** `/Users/sean/src/ml-options-trading/reference-code/hyperlith/`

**Key files to study:**

- `src/hyperlith/core.clj` - defview/defaction macros
- `src/hyperlith/impl/datastar.clj` - SSE implementation
- `src/hyperlith/impl/brotli.clj` - Compression utilities
- `examples/chat_atom/src/app/main.clj` - Complete example
- `examples/game_of_life/src/app/main.clj` - High frequency updates
