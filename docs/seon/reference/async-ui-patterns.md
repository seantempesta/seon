# Async UI Patterns for SSE-Based Hypermedia Applications

**Author:** Research conducted 2025-12-02
**Context:** Clojure web application with slow database queries requiring responsive UI

---

## Table of Contents

1. [Problem Overview](#problem-overview)
2. [Pattern Catalog](#pattern-catalog)
3. [Implementation Strategies for SSE](#implementation-strategies-for-sse)
4. [Clojure-Specific Considerations](#clojure-specific-considerations)
5. [Recommended Approach for Slow DB Queries](#recommended-approach-for-slow-db-queries)
6. [Code Examples](#code-examples)
7. [References](#references)

---

## Problem Overview

### The Challenge

Modern web applications face a fundamental tension: backend operations (database queries, API calls, computations) can be slow, but users expect instant feedback. In traditional SPAs, this is solved with client-side state management, optimistic updates, and loading spinners. In hypermedia-driven applications using Server-Sent Events (SSE), we need different patterns that align with server-rendered HTML and streaming updates.

### Key Questions

1. **How do hypermedia frameworks handle slow backend operations?**
   - Progressive loading with skeleton screens
   - Background job queuing with SSE notifications
   - Streaming HTML as data becomes available

2. **What's the best UX for "data loading" states?**
   - Research shows skeleton screens reduce perceived wait time by 20-30%
   - Active waiting (showing progress) feels faster than passive waiting (spinners)
   - Users expect content to follow skeletons quickly (< 3 seconds ideal)

3. **How do you handle partial page updates?**
   - Render complete views (view = f(state)), not deltas
   - Use hash-based change detection to avoid redundant updates
   - Stream HTML fragments as they become available

4. **How do you prioritize what loads first?**
   - Critical content: Server-side render immediately
   - Non-critical content: Lazy load with HTMX `hx-trigger="load"` or `revealed`
   - Heavy computations: Background jobs with SSE progress updates

---

## Pattern Catalog

### 1. Skeleton Loading Pattern

**Description:** Display the structural layout of content before data arrives, using placeholder elements that mimic the final UI.

**Pros:**
- Reduces perceived load time by 20-30%
- Gives users a preview of what's coming
- No JavaScript required for basic implementation
- Works well with progressive enhancement

**Cons:**
- Can be frustrating if data takes too long (> 3 seconds)
- Requires maintaining UI structure in two places (skeleton + real content)
- Layout shifts can break trust if skeleton doesn't match final content

**When to Use:**
- Data loads predictably within 1-3 seconds
- UI structure is stable and known ahead of time
- You want to show progress without blocking

**Implementation Notes:**
```clojure
;; Initial render returns skeleton
(defn dashboard-skeleton []
  [:div.stat-card
   [:div.skeleton-label]  ; Gray box matching label size
   [:div.skeleton-value]  ; Gray box matching value size
   [:div.skeleton-meta]])

;; SSE update replaces with real data
(defn dashboard-content [data]
  [:div.stat-card
   [:div.stat-label "Total Records"]
   [:div.stat-value (format-number (:total data))]
   [:div.stat-meta "Records in database"]])
```

**Best Practices:**
- Use neutral colors (gray) to avoid distraction
- Animate L-to-R to follow natural eye movement (fast motion, not slow)
- Replace progressively as data arrives, not all at once
- Ensure no layout shift (skeleton must match final dimensions)
- Include primary structural elements only (skip labels, buttons, form fields)

**Sources:**
- [Skeleton loading screen design - LogRocket](https://blog.logrocket.com/ux-design/skeleton-loading-screen-design/)
- [How to Use Skeleton Screens to Improve Perceived Website Performance](https://www.freecodecamp.org/news/how-to-use-skeleton-screens-to-improve-perceived-website-performance/)
- [Effective Skeleton Screens - TimKadlec.com](https://timkadlec.com/remembers/2020-11-02-skeleton-screens/)

---

### 2. Progressive Loading Pattern

**Description:** Load content in stages, starting with critical elements and progressively adding details.

**Pros:**
- Users get immediate value from critical content
- Reduces initial page load time
- Allows prioritization of important data
- Works naturally with SSE streaming

**Cons:**
- More complex to implement
- Need to carefully design priority tiers
- Can cause layout shifts if not designed properly

**When to Use:**
- Clear hierarchy of content importance (critical vs. nice-to-have)
- Different data sources with varying load times
- Large pages with multiple independent sections

**Implementation Strategy:**
1. **Tier 1 - Critical Shell:** Server-render immediately (header, navigation, primary content structure)
2. **Tier 2 - Above-the-Fold:** Load via SSE within 1-2 seconds (main dashboard stats)
3. **Tier 3 - Below-the-Fold:** Lazy load when scrolled into view (history tables, detailed logs)
4. **Tier 4 - Optional:** Load on demand (admin panels, advanced features)

**HTMX Pattern:**
```html
<!-- Critical content: Server-rendered -->
<h1>Dashboard</h1>

<!-- High priority: Auto-load after short delay -->
<div hx-get="/api/stats" hx-trigger="load delay:500ms">
  <div class="skeleton">Loading stats...</div>
</div>

<!-- Low priority: Load when scrolled into view -->
<div hx-get="/api/history" hx-trigger="revealed">
  <div class="skeleton">History will load when visible...</div>
</div>
```

**Sources:**
- [Progressive Load Example - Data-Star](https://data-star.dev/examples/progressive_load)
- [More Htmx Patterns](https://hypermedia.systems/more-htmx-patterns/)
- [Progressive Loading - UX Patterns](https://uxpatterns.dev/glossary/progressive-loading)

---

### 3. Background Job Queue + SSE Notification Pattern

**Description:** Queue long-running operations as background jobs, notify clients via SSE when complete.

**Pros:**
- Handles truly long operations (minutes, hours)
- Decouples HTTP request timeout from job duration
- Scales well (workers independent of web servers)
- Jobs survive server restarts (if persisted)
- Real-time progress updates via SSE

**Cons:**
- More infrastructure complexity (queue, workers)
- Need to handle job failures, retries, cleanup
- Users must keep connection open or poll later
- State management complexity

**When to Use:**
- Operations take > 5 seconds
- Operations can fail and need retry logic
- Multiple users may request same expensive operation
- Need audit trail of operations

**Architecture:**

```
Client (Browser)              Web Server                Worker Process
     |                            |                            |
     |-- POST /api/start -------->|                            |
     |<-- 202 Accepted ----------|                            |
     |    job_id: abc123          |                            |
     |                            |--- Enqueue job ----------->|
     |                            |                            |
     |-- SSE connection --------->|                            |
     |                            |                            |
     |                            |<-- Progress update --------|
     |<-- event: progress --------|    (via queue/pubsub)      |
     |                            |                            |
     |                            |<-- Job complete -----------|
     |<-- event: complete --------|                            |
     |    (final data)            |                            |
```

**Implementation Components:**

1. **Job Queue:** Redis, PostgreSQL table, Kafka, BullMQ
2. **Pub/Sub for Updates:** Redis pub/sub, Kafka, core.async channels
3. **SSE Broadcast:** All web server instances subscribe to pub/sub
4. **Worker Process:** Independent of web server, processes jobs

**Scaling Considerations:**
- Multiple web server pods: Use shared pub/sub (Redis, Kafka)
- Each pod maintains its own SSE connections
- Workers publish updates to shared pub/sub
- All pods receive updates and forward to their clients

**Real-World Example:**
- Production system: 90,000 concurrent SSE connections
- 15 pods handling the load
- Redis pub/sub for coordination
- BullMQ for job persistence and retries

**Sources:**
- [How to Build Real-Time Notification Service Using SSE](https://dzone.com/articles/how-to-build-real-time-notification-service-using)
- [How We Used Server-Sent Events (SSE) to Deliver Real-Time Notifications - Trendyol Tech](https://medium.com/trendyol-tech/how-we-used-server-sent-events-sse-to-deliver-real-time-notifications-on-our-backend-ebae41d3b5cb)
- [Job Queues & CQRS - The pattern that you need to scale](https://softwareontheroad.com/job-queues-cqrs-nodejs-mongodb-agenda)
- [Web-Queue-Worker Architecture - Azure](https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/web-queue-worker)

---

### 4. Progressive Server-Side Rendering (Streaming SSR)

**Description:** Stream HTML chunks as data becomes available, rather than waiting for all data before rendering.

**Pros:**
- Improves Time to First Byte (TTFB)
- Users see content progressively, not all-or-nothing
- Reduces perceived latency
- Natural fit for SSE architecture

**Cons:**
- Requires framework support (React 18 Suspense, streaming templates)
- More complex error handling
- Browser buffering can delay chunks (typically 8KB before flush)
- Not all hosting environments support streaming

**When to Use:**
- Data comes from multiple sources with different latencies
- Page has independent sections that can render separately
- Initial render is slow but you can show something quickly

**How It Works:**

```
Server                          Browser
  |                               |
  |--- HTTP 200 + Headers ------->|
  |    (Content-Type: text/html)  |
  |                               |
  |--- Chunk 1: <html><head>...-->| Parse & render <head>
  |                               |
  |--- Chunk 2: <body><nav>... -->| Parse & render <nav>
  |                               |
  [Query DB for main content]     |
  |                               |
  |--- Chunk 3: <main>data...  -->| Parse & render <main>
  |                               |
  [Query DB for sidebar]          |
  |                               |
  |--- Chunk 4: <aside>...     -->| Parse & render <aside>
  |                               |
  |--- Chunk 5: </body></html> -->| Parse complete
  |                               |
```

**SSE Variation:**
- Initial request returns shell HTML with SSE connection opener
- Shell includes skeletons for pending sections
- Server sends SSE events with HTML fragments
- Client JavaScript patches fragments into place

**Sources:**
- [Enabling Progressive Server-Side Rendering - MDPI](https://www.mdpi.com/2674-113X/4/3/20)
- [Streaming Server-Side Rendering - Patterns.dev](https://www.patterns.dev/react/streaming-ssr/)
- [Developing Real-Time Web Applications with Server-Sent Events - Auth0](https://auth0.com/blog/developing-real-time-web-applications-with-server-sent-events/)

---

### 5. Optimistic UI Pattern

**Description:** Immediately update UI as if operation succeeded, then reconcile with server response.

**Pros:**
- Zero perceived latency for user actions
- Great for high-success-rate operations (99%+ success)
- Improves perceived performance dramatically
- Works well with offline-first apps

**Cons:**
- Complex rollback logic needed for failures
- Can mislead users about operation status
- Requires careful cache management
- Not suitable for critical operations (financial transactions)

**When to Use:**
- Operations have high success rates (likes, votes, simple updates)
- Fast rollback is possible
- Non-critical operations where temporary inconsistency is acceptable
- User is performing the action (not system-generated)

**When NOT to Use:**
- Financial transactions
- Operations requiring server validation
- High failure rates
- Multi-user conflicts likely

**Implementation Pattern:**

```clojure
;; 1. Update local state immediately
(swap! app-state update-in [:likes post-id] inc)

;; 2. Show optimistic UI
;; (Automatically happens via reactive rendering)

;; 3. Send request to server
(go
  (let [response (<! (http/post "/api/like" {:post-id post-id}))]
    (if (:success response)
      ;; 4a. Success - keep optimistic update
      (log/debug "Like confirmed" {:post-id post-id})

      ;; 4b. Failure - rollback
      (do
        (swap! app-state update-in [:likes post-id] dec)
        (show-error "Failed to like post")))))
```

**Cache Management:**
- Update cache immediately on action
- If server confirms: cache stays as-is
- If server rejects: revert cache to previous state
- Handle stale data from server that's newer than cache

**SSE Integration:**
- Optimistic update on client action
- Server broadcasts actual result via SSE
- All clients reconcile their state with server truth
- Conflicts resolved by last-write-wins or CRDT patterns

**Sources:**
- [Building an Optimistic UI with RxDB](https://rxdb.info/articles/optimistic-ui.html)
- [Optimistic UI - Apollo GraphQL Docs](https://www.apollographql.com/docs/react/v2/performance/optimistic-ui)
- [Optimistic UI Patterns for Improved Perceived Performance](https://simonhearne.com/2021/optimistic-ui-patterns/)
- [What is Optimistic UI?](https://plainenglish.io/blog/what-is-optimistic-ui)

---

### 6. Lazy Loading with Intersection Observer

**Description:** Defer loading non-critical content until it scrolls into view.

**Pros:**
- Reduces initial page load
- Saves bandwidth for content users don't see
- Native browser API (Intersection Observer)
- Works great with infinite scroll

**Cons:**
- Requires JavaScript
- Can cause layout shifts if not careful
- May delay content that user quickly scrolls to

**When to Use:**
- Long pages with lots of sections
- Content below the fold
- Image galleries, long lists
- Infinite scroll patterns

**HTMX Implementation:**

```html
<!-- Loads when scrolled into viewport -->
<div hx-get="/api/comments"
     hx-trigger="revealed"
     hx-swap="outerHTML">
  <div class="skeleton-comments">Loading comments...</div>
</div>

<!-- Infinite scroll pattern -->
<div hx-get="/api/items?page=2"
     hx-trigger="revealed"
     hx-swap="afterend">
  <!-- Last item in list -->
</div>
```

**Sources:**
- [htmx Examples - Lazy Loading](https://htmx.org/examples/lazy-load/)
- [Htmx Patterns - Click to Load & Infinite Scroll](https://hypermedia.systems/htmx-patterns/)

---

### 7. Error Handling & Retry Patterns

**Description:** Gracefully handle failures in async operations with automatic retries and user feedback.

**Pros:**
- Resilience against transient failures
- Better user experience during network issues
- Can handle token refresh, rate limits transparently
- Reduces support burden

**Cons:**
- Complexity in determining when to retry
- Risk of overwhelming failing servers
- Need to distinguish transient vs. permanent errors

**When to Use:**
- Network operations (always should have retry)
- Operations that may time out
- Token expiration scenarios
- Rate-limited APIs

**Retry Strategies:**

1. **Immediate Retry:** For transient network blips (max 1-2 retries)
2. **Linear Backoff:** Wait fixed time between retries (1s, 1s, 1s)
3. **Exponential Backoff:** Double wait time each retry (500ms, 1s, 2s, 4s, 8s)
4. **Jittered Backoff:** Add randomness to prevent thundering herd

**HTMX Error Handling:**

```javascript
// Global error handler
document.body.addEventListener('htmx:responseError', function(evt) {
  const status = evt.detail.xhr.status;

  if (status === 401) {
    // Token expired - refresh and retry
    refreshToken().then(() => {
      htmx.trigger(evt.detail.elt, evt.detail.requestConfig.triggeringEvent.type);
    });
  } else if (status >= 500) {
    // Server error - retry with backoff
    retryWithBackoff(evt.detail.elt, 3);
  } else if (status === 422) {
    // Validation error - swap error message
    evt.detail.shouldSwap = true;
    evt.detail.isError = false;
  }
});

// Exponential backoff
function retryWithBackoff(element, maxRetries) {
  const retryCount = parseInt(element.getAttribute('data-retry-count') || 0);
  if (retryCount < maxRetries) {
    const backoffTime = Math.pow(2, retryCount) * 500; // 500ms, 1s, 2s, 4s
    element.setAttribute('data-retry-count', retryCount + 1);
    setTimeout(() => {
      htmx.trigger(element, 'htmx:load');
    }, backoffTime);
  } else {
    showError('Operation failed after ' + maxRetries + ' retries');
  }
}
```

**Error States to Handle:**

- **Network timeout:** Retry with backoff
- **Server 5xx:** Retry with backoff
- **401 Unauthorized:** Refresh token, then retry
- **429 Rate Limit:** Respect Retry-After header
- **422 Validation:** Show errors, no retry
- **404 Not Found:** Show error, no retry

**Response-Targets Extension:**

```html
<!-- Different targets for different status codes -->
<button hx-post="/api/save"
        hx-ext="response-targets"
        hx-target="#success-div"
        hx-target-4*="#client-errors"
        hx-target-5*="#server-errors">
  Save
</button>
```

**Sources:**
- [handle errors with HTMX - Stack Overflow](https://stackoverflow.com/questions/69364278/handle-errors-with-htmx)
- [Retry on responseError? - HTMX Discussion](https://github.com/bigskysoftware/htmx/discussions/1746)
- [Handling AJAX timeouts and retries in HTMX](https://app.studyraid.com/en/read/14118/478177/handling-ajax-timeouts-and-retries-in-htmx)
- [Htmx global error handler - Wim Deblauwe](https://www.wimdeblauwe.com/blog/2023/12/14/htmx-global-error-handler/)

---

## Implementation Strategies for SSE

### Core SSE Principles

1. **Unidirectional:** Server → Client only (use POST/GET for Client → Server)
2. **Text-based:** Events are plain text (typically JSON or HTML)
3. **Event Types:** Named events allow routing to different handlers
4. **Auto-reconnect:** Browser automatically reconnects if connection drops
5. **Last-Event-ID:** Client sends last seen ID to resume from interruption

### SSE vs WebSockets

**Use SSE when:**
- You only need server → client updates
- You want simplicity (HTTP-based, firewall-friendly)
- You need automatic reconnection
- You want to leverage HTTP/2 multiplexing

**Use WebSockets when:**
- You need bidirectional streaming
- You need lower latency than SSE
- You're building real-time collaborative tools

### Compression for SSE

Streaming brotli compression is highly effective for SSE:

- **8.2x compression** on repeated HTML patterns
- **Stateful compression:** Dictionary builds over connection lifetime
- **Progressive:** Compresses each event separately but maintains state
- **LZ77 window:** Configure size based on pattern repetition (18 = 262KB window)

```clojure
;; From ml-options.web.sse
(with-open [out (br/byte-array-out-stream)
            br  (br/compress-out-stream out :window-size 18)]
  ;; Each event compressed individually
  (->> (patch-elements event-id html)
       (br/compress-stream out br)
       (send! channel)))
```

**Why it works:** Dashboard HTML has repetitive structure (same classes, same tags). Brotli learns these patterns and references them instead of repeating.

### View = f(State) Pattern

**Don't send deltas, render complete views:**

```clojure
;; ❌ Bad - sending deltas is complex and brittle
(defn send-update [old-state new-state]
  (let [diff (calculate-diff old-state new-state)]
    (send-sse-event (create-patch diff))))

;; ✅ Good - render complete view every time
(defn render-dashboard [state]
  (html/dashboard-content state))

;; SSE handler only sends if view changed (hash-based detection)
(let [new-view (render-dashboard @app-state)
      new-hash (hash new-view)]
  (when (not= last-hash new-hash)
    (send-sse-event new-view)))
```

**Benefits:**
- Simpler code (no delta logic)
- Can't get out of sync (always full state)
- Compression handles redundancy
- Easier to debug (inspect full HTML)

### Throttling to Prevent Overload

```clojure
;; Limit update frequency (e.g., max 1 update per 100ms)
(defn throttle [<in-ch msec]
  (let [<out-ch (a/chan)]
    (go-loop []
      (when-some [event (<! <in-ch)]
        (>! <out-ch event)
        (<! (a/timeout msec))
        (recur)))
    <out-ch))

;; Usage
(let [<refresh (throttle refresh-ch 100)]
  (a/mult <refresh))
```

**Why throttle:**
- Rapidly changing state (like progress updates) can overwhelm clients
- Browser rendering can't keep up with 60+ updates/sec
- Network bandwidth is wasted on imperceptible changes
- 10-20 updates/second is typically smooth enough

### Auto-Refresh on State Change

**CQRS Pattern:** Command (write) automatically triggers Query (read)

```clojure
;; Watch for state changes, auto-trigger SSE refresh
(add-watch job-state :sse-auto-refresh
  (fn [_key _ref old-state new-state]
    (when (not= old-state new-state)
      (sse/refresh-all!))))

;; Any state mutation automatically propagates to UI
(swap! job-state assoc-in [:current :progress] {...})
;; → SSE refresh triggered automatically
;; → All connected clients re-render
```

**Benefits:**
- No manual refresh calls scattered through code
- Guaranteed UI consistency with state
- Declarative (describe state, not updates)
- Easy to reason about (one source of truth)

**Current Implementation:** Already implemented in `ml-options.web.jobs` (line 21-25)

---

## Clojure-Specific Considerations

### Concurrency Primitives

**core.async Channels:**
```clojure
;; Broadcast to multiple clients
(defonce <refresh-ch (a/chan (a/dropping-buffer 1)))
(def refresh-mult (a/mult <refresh-ch))

;; Each SSE connection taps the mult
(let [<client-ch (a/tap refresh-mult (a/chan (a/dropping-buffer 1)))]
  ;; Read from <client-ch for this connection
  )
```

**Agents for Sequential Updates:**
```clojure
;; Good for serial operations (logging, notifications)
(def log-agent (agent []))

(send log-agent conj {:event "started" :timestamp (now)})
;; Updates happen serially, no race conditions
```

**Atoms for Shared State:**
```clojure
;; Good for coordinated state (current job, dashboard data)
(defonce job-state (atom {:current nil :history []}))

;; Watchers for side effects
(add-watch job-state :sse (fn [_ _ old new]
  (when (not= old new) (trigger-sse-update!))))
```

**Futures for Background Work:**
```clojure
;; Simple background tasks
(def job-future
  (future
    (try
      (long-running-operation)
      (catch InterruptedException _
        (cleanup))
      (finally
        (update-status!)))))

;; Cancel with
(future-cancel job-future)
```

**Virtual Threads (Java 21+):**
```clojure
;; Excellent for SSE handlers (one per connection)
(.start (Thread/ofVirtual)
  (fn []
    (loop []
      (when-some [event (<!! event-ch)]
        (send-to-client event)
        (recur)))))
```

**When to Use What:**

| Primitive | Use Case |
|-----------|----------|
| core.async | Coordination between components, fan-out broadcasts |
| Agents | Sequential logging, notification queue |
| Atoms | Shared application state |
| Futures | Simple one-off background tasks |
| Virtual Threads | Per-connection handlers (SSE, WebSocket) |

### HTTP Server Integration

**http-kit `as-channel` for SSE:**

```clojure
(require '[org.httpkit.server :as hk])

(defn sse-handler [req]
  (hk/as-channel req
    {:on-open (fn [ch]
                ;; Start sending events
                (start-event-loop ch))
     :on-close (fn [ch status]
                 ;; Clean up resources
                 (cleanup-connection ch))}))

;; Send events
(hk/send! ch {:status 200
              :headers {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-store"}
              :body "event: message\ndata: hello\n\n"}
          false) ; false = don't close channel
```

**Why http-kit:**
- Efficient for many concurrent connections
- Native async support
- Works well with SSE keep-alive

**Alternative: Ring async:**
```clojure
(defn sse-handler [req respond raise]
  ;; respond and raise are callbacks
  (respond {:status 200
            :headers {"Content-Type" "text/event-stream"}
            :body (sse-body-stream req)}))
```

### State Management Patterns

**Integrant Lifecycle:**

```clojure
(defmethod ig/init-key :app/sse-broadcast [_ opts]
  (let [<refresh-ch (a/chan (a/dropping-buffer 1))
        refresh-mult (a/mult <refresh-ch)]
    {:refresh-ch <refresh-ch
     :refresh-mult refresh-mult}))

(defmethod ig/halt-key! :app/sse-broadcast [_ {:keys [refresh-ch]}]
  (a/close! refresh-ch))
```

**Benefits:**
- Clean startup/shutdown
- Dependency injection (XTDB node, config)
- Hot-reload support with `suspend-key!` / `resume-key`

**Component Pattern:**
```clojure
(defrecord SSEBroadcast [refresh-mult config]
  component/Lifecycle

  (start [this]
    (let [<ch (a/chan (a/dropping-buffer 1))]
      (assoc this
        :refresh-ch <ch
        :refresh-mult (a/mult <ch))))

  (stop [this]
    (a/close! (:refresh-ch this))
    (dissoc this :refresh-ch :refresh-mult)))
```

---

## Recommended Approach for Slow DB Queries

### Decision Tree

```
Is query < 500ms?
├─ YES → Server-render inline, no special handling
└─ NO → Is query < 3 seconds?
    ├─ YES → Skeleton loading + SSE update
    └─ NO → Background job + SSE notifications
```

### Strategy 1: Skeleton + Fast SSE (< 3 seconds)

**Best for:** Dashboard stats, aggregations, moderately complex queries

**Implementation:**

```clojure
;; 1. Initial page load returns skeleton
(defn dashboard-page [req]
  (html/shim-page))  ; Returns minimal shell with SSE connection

;; 2. SSE handler renders complete view
(defn render-dashboard [req]
  (let [db-stats (db/get-stats (get-xtdb-node))  ; May take 1-2 seconds
        job-stats (jobs/get-status)]
    (html/dashboard-content job-stats db-stats)))

;; 3. SSE connection opens, triggers first render
(def sse-handler
  (sse/render-handler render-dashboard
                      :render-on-connect true
                      :br-window-size 18))

;; 4. State changes trigger automatic updates
(add-watch job-state :auto-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))
```

**Flow:**
1. Browser requests `/dashboard`
2. Server returns shell HTML instantly (< 50ms)
3. JavaScript opens SSE connection via POST
4. Server runs query, renders content, sends via SSE (1-2 seconds)
5. Datastar patches content into `#morph` element
6. Future state changes trigger re-renders automatically

**Advantages:**
- Fast initial response (shell loads instantly)
- Query runs in SSE handler, not blocking HTTP request
- Automatic reconnection if connection drops
- Compression makes HTML updates efficient
- Clean separation: shell vs. content

**Current Implementation:** This is exactly what `ml-options.web` does now!

### Strategy 2: Background Job + Progress Updates (> 3 seconds)

**Best for:** Bulk imports, report generation, complex analytics

**Implementation:**

```clojure
;; 1. Client initiates operation
POST /api/import/start
→ 202 Accepted, {job-id: "abc-123"}

;; 2. Server queues background job
(defn start-import! [node symbols start-date end-date]
  (let [job-id (uuid)]
    ;; Store job state
    (swap! job-state assoc :current {:id job-id :status :queued ...})

    ;; Start async work
    (future
      (try
        ;; Update progress periodically
        (bulk-load/load-data node symbols start-date end-date
          :on-progress (fn [progress]
                         (update-job-progress! job-id progress)))
        (mark-complete! job-id)
        (catch Exception e
          (mark-failed! job-id e))))

    {:ok job-id}))

;; 3. Progress updates trigger SSE broadcasts
(defn update-job-progress! [job-id progress]
  (swap! job-state assoc-in [:current :progress] progress)
  ;; Watcher automatically triggers SSE refresh
  )

;; 4. Client receives real-time updates via SSE
;; (Already connected via dashboard SSE handler)
```

**Flow:**
1. User clicks "Start Import" button
2. POST request queues job, returns immediately with job ID
3. Job runs in background, updates state periodically
4. State watcher triggers SSE refresh
5. All connected clients see live progress updates
6. On completion/failure, final state pushed via SSE

**Advantages:**
- Handles arbitrarily long operations
- Jobs survive server restarts (if persisted)
- Real-time progress feedback
- Multiple concurrent jobs possible
- Decouples HTTP timeout from job duration

**Enhancement: Progress Hooks in Bulk Loader:**

```clojure
;; In ml-options.data.bulk-load
(defn bulk-load-from-repl!
  [node symbols start-date end-date opts]
  (let [on-progress (:on-progress opts identity)]
    (doseq [symbol symbols]
      (doseq [date (date-range start-date end-date)]
        ;; Load data...
        (on-progress {:symbol symbol
                      :date date
                      :records-loaded (count records)})))))

;; In ml-options.web.jobs
(bulk-load/bulk-load-from-repl!
  node symbols start-date end-date
  {:on-progress (fn [progress]
                  (jobs/update-progress! progress))})
```

### Strategy 3: Hybrid (Critical + Lazy)

**Best for:** Complex dashboards with multiple sections at different priorities

**Implementation:**

```clojure
;; Server-render critical content immediately
(defn dashboard-initial [req]
  (let [quick-stats (db/get-quick-stats (get-xtdb-node))]  ; < 200ms
    (html/dashboard-shell quick-stats)))

;; SSE handler renders detailed sections
(defn render-dashboard-details [req]
  (let [detailed-stats (db/get-detailed-stats (get-xtdb-node))  ; 1-2 seconds
        history (db/get-job-history 50)]                       ; Another second
    (html/dashboard-details detailed-stats history)))

;; Route structure
GET  /dashboard        → Returns dashboard-initial (fast)
POST /dashboard/stream → SSE connection for details (slower)
```

**Multi-Section SSE:**

```clojure
;; Different render functions for different sections
(def stats-handler
  (sse/render-handler
    (fn [req] (html/stats-section (db/get-stats node)))))

(def history-handler
  (sse/render-handler
    (fn [req] (html/history-section (db/get-history node)))))

;; Client opens multiple SSE connections, one per section
;; Each can update independently
```

### Query Optimization Techniques

**Materialized Views / Cached Aggregates:**

```clojure
;; Cache expensive stats for 30 seconds
(def stats-cache
  (atom {:data nil :expires-at 0}))

(defn get-stats [node]
  (let [now (System/currentTimeMillis)
        {:keys [data expires-at]} @stats-cache]
    (if (< now expires-at)
      data
      (let [fresh-data (compute-stats node)]
        (reset! stats-cache {:data fresh-data
                             :expires-at (+ now 30000)})
        fresh-data))))
```

**Incremental Updates:**

```clojure
;; Track last-seen timestamp, only query new records
(defn get-new-records [node last-seen]
  (node/query node
    (xt/template
      (from :option-greeks
            [asset/ticker quote/timestamp]
            (where (> quote/timestamp ~last-seen))))))
```

**Parallel Queries:**

```clojure
;; Run independent queries concurrently
(defn get-dashboard-data [node]
  (let [stats-future (future (get-stats node))
        history-future (future (get-history node))
        symbols-future (future (get-symbols node))]
    {:stats @stats-future
     :history @history-future
     :symbols @symbols-future}))
```

---

## Code Examples

### Example 1: Complete SSE Setup (Current Implementation)

```clojure
;; ml-options.web.sse namespace
(ns ml-options.web.sse
  (:require [clojure.core.async :as a]
            [org.httpkit.server :as hk]
            [ml-options.web.brotli :as br]))

;; Initialize broadcast infrastructure
(defn init-sse! [& {:keys [max-refresh-ms]}]
  (let [<refresh-ch (a/chan (a/dropping-buffer 1))
        refresh-mult (-> (if max-refresh-ms
                          (throttle <refresh-ch max-refresh-ms)
                          <refresh-ch)
                        a/mult)]
    refresh-mult))

;; Create SSE handler
(defn render-handler [render-fn & opts]
  (fn [req]
    (let [<ch (a/tap (:sse/refresh-mult req)
                     (a/chan (a/dropping-buffer 1)))]
      (hk/as-channel req
        {:on-open (fn [ch]
                    (.start (Thread/ofVirtual)
                      (fn []
                        (with-open [out (br/byte-array-out-stream)
                                    br  (br/compress-out-stream out)]
                          (loop [last-hash nil]
                            (when-some [_ (a/<!! <ch)]
                              (let [html (render-fn req)
                                    hash (hash html)]
                                (when (not= last-hash hash)
                                  (->> (patch-elements hash html)
                                       (br/compress-stream out br)
                                       (send! ch)))
                                (recur hash))))))))
         :on-close (fn [_ch _status]
                     (a/untap (:sse/refresh-mult req) <ch))}))))

;; Trigger refresh for all clients
(defn refresh-all! []
  (a/>!! refresh-ch :refresh))
```

### Example 2: Dashboard with Auto-Refresh

```clojure
;; ml-options.web.jobs namespace
(defonce job-state (atom {:current nil :history []}))

;; Auto-refresh on state change
(add-watch job-state :sse-auto-refresh
  (fn [_key _ref old-state new-state]
    (when (not= old-state new-state)
      (sse/refresh-all!))))

;; Any mutation triggers UI update
(defn start-import! [node symbols start-date end-date opts]
  (let [job-id (uuid)
        job {:id job-id :status :running ...}]
    ;; This swap! triggers SSE refresh automatically
    (swap! job-state assoc :current job)

    (future
      (try
        (bulk-load node symbols start-date end-date)
        ;; This swap! also triggers SSE refresh
        (swap! job-state assoc-in [:current :status] :completed)
        (catch Exception e
          ;; And this one too
          (swap! job-state assoc-in [:current :status] :failed))))))
```

### Example 3: Skeleton Loading Pattern

```clojure
;; Initial skeleton (fast)
(defn dashboard-skeleton []
  [:div.card-grid
   [:div.stat-card
    [:div.skeleton-label {:style "width: 100px; height: 14px"}]
    [:div.skeleton-value {:style "width: 80px; height: 32px"}]
    [:div.skeleton-meta {:style "width: 120px; height: 12px"}]]
   ;; Repeat for other cards...
   ])

;; Real content (slower, arrives via SSE)
(defn dashboard-content [stats]
  [:div.card-grid
   [:div.stat-card
    [:div.stat-label "Total Records"]
    [:div.stat-value (format-number (:total stats))]
    [:div.stat-meta "Records in database"]]
   ;; Repeat for other cards...
   ])

;; CSS for skeleton animation
(def skeleton-css
  "
  .skeleton-label, .skeleton-value, .skeleton-meta {
    background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
    background-size: 200% 100%;
    animation: loading 1.5s ease-in-out infinite;
    border-radius: 4px;
  }

  @keyframes loading {
    0% { background-position: 200% 0; }
    100% { background-position: -200% 0; }
  }
  ")
```

### Example 4: Progressive Section Loading

```clojure
;; Tiered rendering based on priority
(defn render-dashboard [req]
  (let [tier (get-in req [:params :tier] "all")]
    (case tier
      "critical" (render-critical-only req)
      "secondary" (render-secondary-only req)
      "all" (render-complete-dashboard req))))

;; Critical content: Always server-rendered
(defn render-critical-only [req]
  (let [quick-stats (db/get-quick-stats node)]  ; Fast query
    (html/critical-section quick-stats)))

;; Secondary content: Loaded via SSE
(defn render-secondary-only [req]
  (let [detailed-stats (db/get-detailed-stats node)  ; Slower query
        history (db/get-history node 50)]            ; Another query
    (html/secondary-sections detailed-stats history)))

;; Client-side: Multiple SSE streams
[:div#critical
 ;; Server-rendered on page load
 ]

[:div#secondary
 ;; Initially empty or skeleton
 ;; Gets populated via SSE connection to /dashboard/stream?tier=secondary
 {:data-init "@post('/dashboard/stream?tier=secondary')"}]
```

### Example 5: Background Job with Progress

```clojure
;; Job state with progress tracking
(defn start-import! [node symbols start-date end-date opts]
  (let [job-id (uuid)
        total-days (days-between start-date end-date)
        job {:id job-id
             :status :running
             :progress {:completed 0 :total total-days}}]
    (swap! job-state assoc :current job)

    (future
      (doseq [[idx date] (map-indexed vector (date-range start-date end-date))]
        ;; Update progress after each day
        (swap! job-state assoc-in [:current :progress :completed] (inc idx))
        ;; Load data for this day
        (load-day-data node symbols date))

      ;; Mark complete
      (swap! job-state assoc-in [:current :status] :completed))))

;; Dashboard shows live progress
(defn render-progress [job]
  (let [progress (get job :progress {})
        pct (* 100.0 (/ (:completed progress) (:total progress)))]
    [:div.progress-container
     [:div.progress-bar
      [:div.progress-fill {:style (str "width: " pct "%")}]]
     [:div.progress-text
      (str (:completed progress) " of " (:total progress) " days completed")]]))
```

### Example 6: Error Handling with Retry

```clojure
;; HTMX client-side retry logic
(def retry-script
  "
  document.body.addEventListener('htmx:responseError', function(evt) {
    const element = evt.detail.elt;
    const status = evt.detail.xhr.status;
    const retryCount = parseInt(element.getAttribute('data-retry-count') || 0);
    const maxRetries = parseInt(element.getAttribute('data-max-retries') || 3);

    if (status >= 500 && retryCount < maxRetries) {
      // Server error - retry with exponential backoff
      const backoffMs = Math.pow(2, retryCount) * 1000;
      element.setAttribute('data-retry-count', retryCount + 1);

      console.log('Retrying after ' + backoffMs + 'ms (attempt ' + (retryCount + 1) + ')');

      setTimeout(() => {
        htmx.trigger(element, evt.detail.requestConfig.triggeringEvent.type);
      }, backoffMs);
    } else if (retryCount >= maxRetries) {
      // Max retries exceeded - show error
      element.innerHTML = '<div class=\"error\">Failed after ' + maxRetries + ' retries</div>';
    }
  });
  ")

;; Server-side: Return appropriate status codes
(defn api-handler [req]
  (try
    (let [result (expensive-operation)]
      {:status 200 :body result})
    (catch java.sql.SQLException e
      ;; Database error - retriable
      {:status 503 :body "Database temporarily unavailable"})
    (catch IllegalArgumentException e
      ;; Validation error - not retriable
      {:status 422 :body (validation-errors e)})))
```

---

## References

### Progressive Loading & Skeleton Screens
- [Progressive Load Example - Data-Star](https://data-star.dev/examples/progressive_load)
- [More Htmx Patterns](https://hypermedia.systems/more-htmx-patterns/)
- [Htmx Patterns](https://hypermedia.systems/htmx-patterns/)
- [Skeleton loading screen design - LogRocket](https://blog.logrocket.com/ux-design/skeleton-loading-screen-design/)
- [How to Use Skeleton Screens - freeCodeCamp](https://www.freecodecamp.org/news/how-to-use-skeleton-screens-to-improve-perceived-website-performance/)
- [Effective Skeleton Screens - TimKadlec.com](https://timkadlec.com/remembers/2020-11-02-skeleton-screens/)

### HTMX & Hypermedia Patterns
- [htmx ~ The loading-states Extension](https://v1.htmx.org/extensions/loading-states/)
- [htmx ~ Events](https://htmx.org/events/)
- [When to Load Data Right Away vs. When to Let HTMX Handle It Later](https://dev.to/sisproid/when-to-load-data-right-away-vs-when-to-let-htmx-handle-it-later-a-senior-devs-take-25nf)
- [htmx Examples - Lazy Loading](https://htmx.org/examples/lazy-load/)
- [Using Alpine.js In HTMX](https://www.bennadel.com/blog/4787-using-alpine-js-in-htmx.htm)

### Server-Sent Events (SSE)
- [HTMX - Server Sent Events(SSE)](https://www.tutorialspoint.com/htmx/htmx_server_sent_events.htm)
- [htmx ~ The htmx Server Sent Event (SSE) Extension](https://htmx.org/extensions/sse/)
- [Real-Time UI Updates with SSE: Simpler Than WebSockets](https://www.codingwithmuhib.com/blogs/real-time-ui-updates-with-sse-simpler-than-websockets)
- [Developing Real-Time Web Applications with Server-Sent Events - Auth0](https://auth0.com/blog/developing-real-time-web-applications-with-server-sent-events/)
- [How to Build Real-Time Notification Service Using SSE](https://dzone.com/articles/how-to-build-real-time-notification-service-using)

### Progressive Server-Side Rendering
- [Enabling Progressive Server-Side Rendering - MDPI](https://www.mdpi.com/2674-113X/4/3/20)
- [Streaming Server-Side Rendering - Patterns.dev](https://www.patterns.dev/react/streaming-ssr/)
- [Server-Side Rendering (SSR) with Progressive Hydration](https://www.metaltoad.com/blog/server-side-rendering-ssr-with-progressive-hydration)

### Optimistic UI
- [Building an Optimistic UI with RxDB](https://rxdb.info/articles/optimistic-ui.html)
- [Optimistic UI - Apollo GraphQL Docs](https://www.apollographql.com/docs/react/v2/performance/optimistic-ui)
- [Optimistic UI Patterns for Improved Perceived Performance](https://simonhearne.com/2021/optimistic-ui-patterns/)
- [What is Optimistic UI?](https://plainenglish.io/blog/what-is-optimistic-ui)

### Background Jobs & Queues
- [How to queue background tasks in ASP.NET Web API - Stack Overflow](https://stackoverflow.com/questions/14710822/how-to-queue-background-tasks-in-asp-net-web-api)
- [Job Queues & CQRS - The pattern that you need to scale](https://softwareontheroad.com/job-queues-cqrs-nodejs-mongodb-agenda)
- [Web-Queue-Worker Architecture - Azure](https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/web-queue-worker)
- [How We Used Server-Sent Events (SSE) - Trendyol Tech](https://medium.com/trendyol-tech/how-we-used-server-sent-events-sse-to-deliver-real-time-notifications-on-our-backend-ebae41d3b5cb)

### Error Handling & Retry Patterns
- [handle errors with HTMX - Stack Overflow](https://stackoverflow.com/questions/69364278/handle-errors-with-htmx)
- [Retry on responseError? - HTMX Discussion](https://github.com/bigskysoftware/htmx/discussions/1746)
- [Handling AJAX timeouts and retries in HTMX](https://app.studyraid.com/en/read/14118/478177/handling-ajax-timeouts-and-retries-in-htmx)
- [Htmx global error handler - Wim Deblauwe](https://www.wimdeblauwe.com/blog/2023/12/14/htmx-global-error-handler/)

---

## Summary & Recommendations

### For ml-options-trading Project

**Current State (Good!):**
- ✅ SSE with streaming brotli compression
- ✅ View = f(state) pattern with hash-based change detection
- ✅ Auto-refresh on state change (CQRS pattern)
- ✅ Background job support with status tracking

**Recommended Enhancements:**

1. **Add Skeleton Loading (Priority: Medium)**
   - Show skeleton while first SSE render completes
   - Improves perceived load time for dashboard
   - Simple CSS-only implementation possible

2. **Progress Hooks in Bulk Loader (Priority: High)**
   - Hook `bulk-load/bulk-load-from-repl!` to emit progress
   - Update job state after each symbol/day
   - Provides real-time feedback during long imports

3. **Error Handling & Retry (Priority: Medium)**
   - Add retry logic for transient API failures
   - Exponential backoff for ThetaData rate limits
   - Better error messages in UI

4. **Query Optimization (Priority: High)**
   - Cache expensive stats for 30 seconds
   - Parallel query execution for dashboard sections
   - Consider materialized views for common aggregates

5. **Progressive Section Loading (Priority: Low)**
   - Only if dashboard becomes very complex
   - Currently not needed (queries are reasonably fast)

### General Principles

1. **Start simple:** Skeleton + SSE covers 90% of use cases
2. **Measure first:** Don't optimize until you know it's slow
3. **Progressive enhancement:** Basic functionality without JS
4. **Fail gracefully:** Always have error states and retry logic
5. **View = f(state):** Render complete views, let compression handle efficiency

The current implementation in `ml-options.web.{sse,html,jobs}` is already well-architected and follows best practices. The main opportunities for enhancement are in the bulk loader progress reporting and query optimization.
