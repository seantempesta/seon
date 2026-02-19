# Datastar Setup Comparison for Agent Observatory

## Summary

Our current setup is **sufficient for the agent observatory** with one minor extension needed. The append mode is already supported by the Datastar JavaScript library we're using, but our Clojure SSE helper only implements `outer` mode. Adding support for `mode append` requires a small extension to `seon.web.sse`.

## Question 1: What Datastar version are we using?

**Version: 1.0.0-RC.6** (via CDN)

From `src/seon/web/html.clj`:
```clojure
(def datastar-cdn
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.6/bundles/datastar.js")
```

The latest available is **1.0.0-RC.7** (per the official Clojure SDK). This is a minor version behind but functionally equivalent for our needs.

## Question 2: Does hyperlith support `mode append`?

**Yes.** Hyperlith has a dedicated `patch-append-body` function:

From `reference-code/hyperlith/src/hyperlith/impl/datastar.clj`:
```clojure
(defn patch-append-body [elements]
  (str "event: datastar-patch-elements"
    "\ndata: selector body"
    "\ndata: mode append"
    "\ndata: elements " (str/replace elements "\n" "\ndata: elements ")
    "\n\n\n"))
```

This is used in `action-handler` to append elements to the body for action responses.

## Question 3: Does our current setup support all patch modes?

**Partially.**

### Current Implementation (`seon.web.sse`)

Our `patch-elements` function only supports the default `outer` mode (morphing):

```clojure
(defn patch-elements
  [event-id elements]
  (str "event: datastar-patch-elements"
       "\nid: " event-id
       "\ndata: elements " (clojure.string/replace elements "\n" "\ndata: elements ")
       "\n\n\n"))
```

### Datastar Supports 8 Patch Modes

Per the official SDK (`reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/consts.clj`):

| Mode | Description |
|------|-------------|
| `outer` (default) | Morphs the element into the existing element |
| `inner` | Replaces the inner HTML of the existing element |
| `remove` | Removes the existing element |
| `replace` | Replaces the existing element with the new element |
| `prepend` | Prepends the element inside the existing element |
| `append` | Appends the element inside the existing element |
| `before` | Inserts the element before the existing element |
| `after` | Inserts the element after the existing element |

### What We Need to Add

For the agent observatory streaming messages, we need:
- **`mode append`** - Append new messages without replacing the container
- **`selector`** - Target a specific element (e.g., `#message-log`)

## Question 4: Is there an official Clojure SDK now?

**Yes.** The official Datastar Clojure SDK is at:
- Repository: https://github.com/starfederation/datastar-clojure
- Status: Active, well-maintained
- Features: Full SSE generation, Malli schemas, http-kit and Ring adapters, Brotli compression

### SDK Features

- `patch-elements!` with full mode support
- `patch-signals!` for reactive state updates
- `execute-script!` for running JS on client
- `remove-element!` convenience function
- Adapters for http-kit and Ring
- Optional Malli schema validation

## Question 5: Do we need to upgrade?

**Recommendation: Option A (Stay with current setup) + Minor Extension**

### Why Not Full SDK Adoption (Option B)?

1. **Our SSE architecture is simpler.** We use a "view = f(state)" model where the entire view re-renders on state change, then hash-based change detection prevents redundant sends. The official SDK is designed for more granular event-by-event control.

2. **Our Brotli streaming works well.** We already have streaming Brotli compression that matches hyperlith's approach.

3. **Minimal changes needed.** We only need to add `mode append` support for streaming messages.

### What We Should Do

Extend `seon.web.sse` with a flexible `patch-elements` that supports options:

```clojure
(defn patch-elements
  "Build a datastar SSE event for patching elements.

  Options:
  - :selector - CSS selector for target element (default: uses element ID)
  - :mode - Patch mode: :outer, :inner, :append, :prepend, :before, :after, :replace, :remove
  - :event-id - For idempotency/resumption"
  [{:keys [selector mode event-id]} elements]
  (let [lines (cond-> []
                selector (conj (str "data: selector " selector))
                (and mode (not= mode :outer)) (conj (str "data: mode " (name mode))))]
    (str "event: datastar-patch-elements"
         (when event-id (str "\nid: " event-id))
         (when (seq lines) (str "\n" (str/join "\n" lines)))
         "\ndata: elements " (str/replace elements "\n" "\ndata: elements ")
         "\n\n\n")))
```

## Question 6: `data-scroll-into-view`?

**This is a Datastar Pro (commercial) feature.** It's not included in the open-source bundle we're using.

### Alternatives for Scroll Management

1. **CSS `scroll-behavior: smooth` + `overflow-anchor: none`** - What hyperlith uses for virtual scroll
2. **JavaScript `scrollIntoView()`** - Via `data-init` or `data-on-click` attributes
3. **`data-effect` with scroll tracking** - Monitor scroll position in signals

For the agent observatory, we can use:

```clojure
;; After appending a message, execute script to scroll
(execute-script! "document.querySelector('#message-log').scrollTop = document.querySelector('#message-log').scrollHeight")
```

Or use CSS on the container:

```clojure
[:div#message-log {:style {:overflow-y "auto"
                           :flex-direction "column-reverse"}} ;; Auto-scroll to bottom
 messages]
```

## Files Examined

| File | Description |
|------|-------------|
| `src/seon/web/sse.clj` | Our SSE implementation |
| `src/seon/web/html.clj` | Datastar CDN version, page templates |
| `reference-code/hyperlith/src/hyperlith/impl/datastar.clj` | Hyperlith's Datastar helpers |
| `reference-code/datastar-clojure/libraries/sdk/` | Official Clojure SDK |
| `reference-code/datastar/sdk/datastar-sdk-config-v1.json` | Datastar protocol spec |

## Action Items

1. **Extend `seon.web.sse/patch-elements`** to accept options map with `:selector` and `:mode`
2. **Add `patch-signals` function** for updating client-side reactive state
3. **Consider bumping CDN to RC.7** (optional, no breaking changes)
4. **Use CSS/JS for scroll management** instead of Pro feature

## References

- Datastar SSE Events: https://data-star.dev/reference/sse_events
- Official Clojure SDK: https://github.com/starfederation/datastar-clojure
- Hyperlith: https://github.com/andersmurphy/hyperlith
