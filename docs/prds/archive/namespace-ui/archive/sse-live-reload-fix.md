---
type: prd
status: archived
tags: [prd, web]
---
# SSE Live Reload Fix

## Problem Statement

Code changes to SSE-rendered views (like the Agent Observatory) weren't appearing in the browser without a full page refresh, even after `(user/reload)` was called.

## Investigation Summary

### Previous Agent (6302) Findings

Agent 6302 investigated and found:

1. **Var dereferencing works** - Clojure resolves function calls through vars, so redefining a function updates what callers see
2. **`h/html` picks up dynamic content** - The Chassis HTML macro evaluates content at render time
3. **Hash-based change detection is correct** - SSE only sends updates when `(not= last-view-hash new-view-hash)`

The agent tested hash equality and found "Same hash? true" - but this was testing two calls **without changing code**, so same hash is expected.

### Root Cause Identified

The issue was that `clj-reload` wasn't calling the `after-ns-reload` hooks defined in namespaces.

In `seon.web.agents`, there's an `after-ns-reload` function (lines 1011-1017) that recreates the `agents-sse` handler after namespace reload:

```clojure
(defn after-ns-reload
  "Called by clj-reload after namespace reload. Recreates list view SSE handler."
  []
  (log/debug "Recreating agents-sse handler after namespace reload")
  (alter-var-root #'agents-sse
                  (constantly (sse/render-handler #'agents-sse-render :poll-ms 2000))))

```

But `user.clj` initialized clj-reload **without** the `:reload-hook` option:

```clojure
;; Before (broken):
(reload/init {:dirs ["src" "env/dev/clj" "test"]
              :no-reload '#{user}})

```

## The Fix

Added `:reload-hook 'after-ns-reload` to the clj-reload initialization in `env/dev/clj/user.clj`:

```clojure
;; After (fixed):
(reload/init {:dirs ["src" "env/dev/clj" "test"]
              :no-reload '#{user}
              :reload-hook 'after-ns-reload})

```

This tells clj-reload to call any `after-ns-reload` function defined in a namespace after that namespace is reloaded.

## How SSE Live Reload Works

1. **SSE handler creation**: `agents-sse` is created with `(sse/render-handler #'agents-sse-render ...)`. The `#'agents-sse-render` is a **var reference**.

2. **Var dereferencing**: When the SSE loop calls `(render-fn req)`, Clojure dereferences the var to get the current function. This means code changes to `agents-sse-render` are picked up automatically.

3. **Hash-based change detection**: `do-render` in `sse.clj` computes a hash of the HTML output. Only when the hash differs from the previous render is an update sent to the browser.

4. **After reload hook**: When the namespace reloads, `after-ns-reload` recreates the handler to ensure any captured state is fresh.

## Why This Matters

For code changes to appear in the browser:

1. The render function must produce **different HTML** (otherwise hash is same)
2. The var reference must resolve to the **new function** (var deref handles this)
3. The reload hook ensures any **captured handler state** is refreshed

## Testing

Verified the fix works:

1. Made a visible code change (added "[LIVE]" to heading)
2. Called `(user/reload)`
3. Hash changed from `7ba80be9` to `4b3286c3`
4. New content `[LIVE]` was present in HTML output

## Files Changed

- `env/dev/clj/user.clj` - Added `:reload-hook 'after-ns-reload` to clj-reload init
