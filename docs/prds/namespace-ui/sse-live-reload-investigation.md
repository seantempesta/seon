# SSE Live Reload - Solution Implemented

## Problem
SSE handlers use `def` to create handler objects. clj-reload doesn't re-evaluate `def` forms unless the code changes, causing stale handlers that don't pick up changes to render functions.

## Root Cause
```clojure
;; This closure is captured ONCE at def time
(def my-sse
  (sse/render-handler
   (fn [_request] (render-content))))  ; Changes won't propagate!
```

## Solution: Var References + after-ns-reload Hooks

clj-reload has built-in support for reload hooks (discovered in source at `reference-code/clj-reload/src/clj_reload/core.clj:15-19`):
- `:reload-hook` - function called AFTER reload (default: `after-ns-reload`)
- `:unload-hook` - function called BEFORE unload (default: `before-ns-unload`)

### Pattern Implemented

1. **Define render function separately** - enables var indirection
2. **Pass var reference** to `render-handler` - derefs to current binding each call
3. **Add `after-ns-reload` hook** - recreates handler objects after reload

```clojure
;; 1. Separate render function
(defn- my-sse-render [_request]
  (render-content))

;; 2. Var reference
(def my-sse
  (sse/render-handler #'my-sse-render :poll-ms 2000))

;; 3. Reload hook (called automatically by clj-reload)
(defn after-ns-reload []
  (alter-var-root #'my-sse
    (constantly (sse/render-handler #'my-sse-render :poll-ms 2000))))
```

## Files Updated

| File | Changes |
|------|---------|
| `src/seon/web/handlers.clj` | `dashboard-sse`, `log-viewer-sse` + `after-ns-reload` |
| `src/seon/web/agents.clj` | `agents-sse`, handler cache + `after-ns-reload` |
| `src/seon/web/namespace.clj` | Handler cache by ns-sym + `after-ns-reload` |
| `CONVENTIONS.md` | Added "SSE Handler Hot Reload Pattern" section |

## How It Works

1. **Var references are IFn** - `#'render-fn` derefs to current binding each call
2. **Dev hook triggers reload** - Edit/Write calls `user/reload` automatically
3. **clj-reload calls `after-ns-reload`** - defined in each SSE namespace
4. **Handler objects recreated** - new handlers capture updated var references
5. **SSE polling picks up changes** - browser sees new content within poll interval

## Testing

After server restart:
1. Edit any render function (e.g., change title text)
2. Dev hook reloads namespace + calls `after-ns-reload`
3. Browser shows updated content within 2s (poll interval)

No manual intervention needed after initial bootstrap.

## Key Insight

The handler OBJECT must be recreated to use the new var reference. The `after-ns-reload` hook handles this automatically. The var reference pattern alone isn't enough because the handler captures the var at creation time - we need to recreate the handler to capture the updated var binding.
