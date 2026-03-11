---
type: prd
status: completed
tags: [prd, archive, agent]
---

# Agent Observatory Streaming Research

Research findings for building a smooth, real-time agent message viewer using Datastar.

## Executive Summary

Building a flicker-free, user-respecting real-time log viewer requires:
1. **Append mode** for new messages (not full re-render)
2. **Scroll position management** on the client side via signals
3. **Channel buffering** with backpressure handling
4. **Reconnection strategy** that preserves message continuity

## Datastar Patterns That Apply

### 1. Append Mode for Streaming Content

Datastar supports multiple patch modes. For a log viewer, **append mode** is ideal:

```
event: datastar-patch-elements
data: selector #message-log
data: mode append
data: elements <div class="message">New message here</div>

```

This appends new content to the container without touching existing DOM elements.

**Key insight from ADR.md:**
- `outer` (default) - Morph entire element, preserving state
- `inner` - Morph inner HTML only, preserving state
- `append` - Insert at end inside target (NO morph)
- `prepend` - Insert at beginning inside target (NO morph)

The `append` mode does NOT morph - it just inserts. This is perfect for log entries because:
- No flicker from re-rendering
- No scroll position interference
- Maintains DOM state (selection, focus)

### 2. Scroll Behavior Management

Datastar provides `data-scroll-into-view` for auto-scrolling:

```html
<!-- Auto-scroll new messages into view -->
<div data-scroll-into-view.smooth.vend>New message</div>

```

Modifiers available:
- `__smooth` - Animated scrolling
- `__instant` - Immediate jump
- `__vend` - Scroll to bottom of element
- `__vstart` - Scroll to top

**For user-respecting scroll:**

The trick is to ONLY add `data-scroll-into-view` when auto-scroll is enabled AND user hasn't scrolled up. This requires client-side signal tracking:

```html
<div data-signals="{autoScroll: true, userScrolled: false}">
  <div id="message-log"
       data-on-scroll="$userScrolled = (el.scrollTop < el.scrollHeight - el.clientHeight - 50)">
    <!-- messages here -->
  </div>
</div>

```

Then conditionally render the scroll attribute:

```clojure
;; Server-side: only include scroll-into-view if appropriate
[:div {:id (str "msg-" idx)
       :data-scroll-into-view (when auto-scroll "__smooth.__vend")}
  message-content]

```

### 3. Infinite Scroll / Intersection Observer

From `data-on-intersect`:

```html
<div data-on-intersect="@get('/more-messages')">
  Loading older messages...
</div>

```

Options:
- `__once` - Only trigger once
- `__half` - Trigger at 50% visibility
- `__threshold:25` - Trigger at 25% visibility

This is useful for loading historical messages when user scrolls up.

### 4. Ignore Morph for Preserved Content

If using full re-render approach, protect scroll position:

```html
<div id="message-log" data-ignore-morph>
  <!-- Content that shouldn't be morphed -->
</div>

```

But for our use case, append mode is cleaner.

## Channel to SSE Bridging Strategy

### Current Architecture

```
Agent Process
    |
    v
messages-ch (chan 100)  <-- claude/launch-agent! puts messages here
    |
    v
tail function returns channel  <-- observatory calls this

```

### Recommended Bridging Pattern

```clojure
(defn agent-sse-handler
  "SSE handler for streaming agent messages."
  [session-id]
  (fn [request]
    (let [messages-ch (agent/tail {::agent/session-id session-id})
          ;; Buffer channel for batching
          batch-ch (a/chan (a/sliding-buffer 50))]

      (hk/as-channel request
        {:on-open
         (fn [ch]
           ;; Start streaming thread
           (.start (Thread/ofVirtual)
             (fn []
               (loop []
                 (when-let [msg (a/<!! messages-ch)]
                   ;; Send as append event
                   (send-append! ch "#message-log" (render-message msg))
                   (recur))))))

         :on-close
         (fn [ch status]
           ;; Cleanup: close tap
           (a/close! batch-ch))}))))

```

### Buffering Considerations

1. **Sliding buffer** (50) - Drops oldest if overwhelmed
2. **Throttling** - Max 10 updates/second is fine for human viewing
3. **Batching** - Collect messages for 50-100ms, send as single append

```clojure
(defn batch-messages
  "Collect messages for batch-ms, then emit as vector."
  [in-ch batch-ms]
  (let [out-ch (a/chan)]
    (a/go-loop [batch []]
      (let [[v port] (a/alts! [in-ch (a/timeout batch-ms)])]
        (cond
          ;; Got message - add to batch
          (and (= port in-ch) v)
          (recur (conj batch v))

          ;; Timeout - emit batch if non-empty
          (= port (a/timeout batch-ms))
          (do
            (when (seq batch)
              (a/>! out-ch batch))
            (recur []))

          ;; Channel closed
          :else
          (do
            (when (seq batch)
              (a/>! out-ch batch))
            (a/close! out-ch)))))
    out-ch))

```

### Reconnection Strategy

**Problem:** SSE reconnects lose messages during disconnect.

**Solution 1: Message IDs + Last-Event-ID**

SSE spec supports `id:` field and `Last-Event-ID` header:

```clojure
(defn send-message! [ch msg-id content]
  (hk/send! ch
    {:body (str "event: datastar-patch-elements\n"
                "id: " msg-id "\n"
                "data: selector #message-log\n"
                "data: mode append\n"
                "data: elements " content "\n\n")}))

```

On reconnect, browser sends `Last-Event-ID` header. Server can replay from that point.

**Solution 2: Persistent Message Buffer**

Keep last N messages in memory or XTDB:

```clojure
(defn get-messages-since
  "Get messages from session since message-id."
  [node session-id since-id]
  (if since-id
    (ai/get-messages-since {::ai/node node
                            ::ai/session-id session-id
                            ::ai/since-id since-id})
    (ai/get-messages {::ai/node node
                      ::ai/session-id session-id
                      ::ai/limit 100})))

```

On connect, send backlog then switch to live stream.

## Scroll Behavior Recommendations

### Option A: Server-Controlled (Simpler)

Track auto-scroll state on server, send scroll directive with each batch:

```clojure
(defn render-message-batch [messages auto-scroll?]
  [:div
   (for [msg messages]
     [:div.message (:content msg)])
   (when auto-scroll?
     [:script "document.querySelector('#message-log').scrollTop =
               document.querySelector('#message-log').scrollHeight"])])

```

Pros: Simple, no client state
Cons: Fights user if they scroll during update

### Option B: Client-Controlled via Signals (Recommended)

Use Datastar signals for scroll state:

```html
<div data-signals="{autoScroll: true}">
  <div id="message-log"
       class="overflow-y-auto h-96"
       data-on-scroll="
         const atBottom = el.scrollTop >= el.scrollHeight - el.clientHeight - 50;
         $autoScroll = atBottom
       ">
  </div>
  <button data-on-click="$autoScroll = true;
                          document.querySelector('#message-log').scrollTop =
                          document.querySelector('#message-log').scrollHeight">
    Jump to bottom
  </button>
</div>

```

Server sends new messages with conditional scroll:

```clojure
;; Only scroll if client's autoScroll signal is true
[:div#new-msg
 {:data-scroll-into-view (when (:auto-scroll signals) "__smooth.__vend")}
 content]

```

### Option C: Pure Append (Simplest, Recommended for MVP)

Just append messages, let browser handle scroll naturally:

```
event: datastar-patch-elements
data: selector #message-log
data: mode append
data: elements <div class="message">Content here</div>

```

If user is at bottom, browser keeps them there.
If user scrolled up, they stay where they are.

This is the "it just works" approach for most cases.

## Implementation Recommendations

### Phase 1: Basic Streaming (MVP)

1. Create `/api/agent/:session-id/stream` SSE endpoint
2. Use `mode append` for all new messages
3. No scroll management (let browser handle)
4. Simple reconnection: reload last 50 messages on connect

```clojure
(defn agent-stream-handler [request]
  (let [session-id (get-in request [:path-params :session-id])
        messages-ch (agent/tail {::agent/session-id session-id})]
    (sse/streaming-handler
      (fn [send!]
        ;; Send initial backlog
        (doseq [msg (get-recent-messages session-id 50)]
          (send! (append-event "#log" (render-message msg))))
        ;; Stream new messages
        (loop []
          (when-let [msg (a/<!! messages-ch)]
            (send! (append-event "#log" (render-message msg)))
            (recur)))))))

```

### Phase 2: Smart Scroll

Add client-side scroll detection:
- Track if user at bottom
- Only auto-scroll if at bottom
- "Jump to bottom" button when not at bottom

### Phase 3: History Loading

Add intersection observer for loading older messages:
- Sentinel element at top of log
- Load older messages on intersect
- Prepend mode for historical messages

## Gotchas and Concerns

### 1. Message Ordering

Agent messages arrive via channel - they're already ordered.
But batching can cause issues if messages have timestamps.
Solution: Sort batch by timestamp before rendering.

### 2. High Message Volume

Claude agents can produce many messages rapidly (especially tool calls).
- Tool use blocks can be 10+ messages in quick succession
- Batch these into single SSE event
- Consider collapsing tool call/result pairs

### 3. DOM Size

Long-running agents produce thousands of messages.
- Implement virtual scrolling for very long sessions
- Or: only keep last N messages in DOM, load more on scroll up
- Or: collapse old messages into "X more messages" summary

### 4. Brotli Compression

Our SSE uses streaming brotli. This works great for full re-renders
but may be less efficient for small appends.
- Test append mode with brotli
- May want to disable compression for append events
- Or batch more aggressively to make compression worthwhile

### 5. Channel Lifecycle

When agent completes, channel closes. Handle gracefully:
- Send "Agent completed" final message
- Close SSE connection cleanly
- Show completion status in UI

## References

- `/Users/sean/src/seon/reference-code/datastar/sdk/ADR.md` - SDK architecture decisions
- `/Users/sean/src/seon/reference-code/datastar/library/src/plugins/watchers/patchElements.ts` - Patch modes implementation
- `/Users/sean/src/seon/reference-code/datastar/library/src/plugins/attributes/onIntersect.ts` - Intersection observer
- `/Users/sean/src/seon/src/seon/web/sse.clj` - Current SSE implementation
- `/Users/sean/src/seon/src/seon/ai/agent.clj` - Agent registry and tail function
- `/Users/sean/src/seon/docs/reference/datastar-quick-reference.md` - Project Datastar patterns

## Code Snippets for Reference

### Append Event Helper

```clojure
(defn append-event
  "Build SSE event for appending content to a selector."
  [selector html-content]
  (str "event: datastar-patch-elements\n"
       "data: selector " selector "\n"
       "data: mode append\n"
       "data: elements " (str/replace html-content "\n" "\ndata: elements ") "\n\n"))

```

### Message Rendering

```clojure
(defn render-agent-message
  "Render a single agent message for the log."
  [{:keys [type message uuid] :as msg}]
  (h/html
    [:div {:id (str "msg-" (or uuid (hash msg)))
           :class "py-2 px-3 border-b border-zinc-800"}
     [:div.flex.items-center.gap-2
      [:span.text-xs.text-zinc-500 (format-timestamp)]
      [:span {:class (case type
                       "assistant" "text-blue-400"
                       "user" "text-green-400"
                       "result" "text-violet-400"
                       "text-zinc-400")}
       type]]
     [:div.text-sm.text-zinc-300.mt-1
      (extract-display-content msg)]]))

```

### Streaming Handler Pattern

```clojure
(defn streaming-response
  "Create an SSE streaming response with virtual thread."
  [render-fn]
  (fn [request]
    (hk/as-channel request
      {:on-open
       (fn [ch]
         (.start (Thread/ofVirtual)
           (fn []
             (try
               (render-fn ch request)
               (catch Exception e
                 (log/error e "Streaming error"))
               (finally
                 (hk/close ch))))))})))

```
