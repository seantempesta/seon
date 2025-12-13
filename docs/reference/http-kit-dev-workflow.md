# http-kit Development Workflow - Hot Reloading Patterns

## Overview

This guide covers http-kit specific patterns for enabling hot-reloading in development. Understanding these patterns is essential for a productive development workflow where code changes appear immediately without restarting the server.

## The Core Problem

When you start an http-kit server, it captures a reference to your handler function:

```clojure
(require '[org.httpkit.server :as hk])

(defn handler [request]
  {:status 200
   :body "Version 1"})

(def server (hk/run-server handler {:port 8080}))

;; Server is running, returns "Version 1"
```

Now you update the handler:

```clojure
(defn handler [request]
  {:status 200
   :body "Version 2"})  ; <-- New code!
```

**Problem**: The server still returns "Version 1"! Why?

http-kit captured the **value** of `handler` when you called `run-server`. When you redefined `handler`, you created a new function value, but http-kit is still using the old one.

## The Solution: Var Indirection

Instead of passing the function **value**, pass the **var**:

```clojure
(def server (hk/run-server #'handler {:port 8080}))
;;                          ^^
;;                          This is the key!
```

Now when you redefine `handler`, http-kit will see the change immediately because it's looking up the var on every request.

### How Vars Work

In Clojure:

- `handler` - Returns the **value** bound to the var (the function object)
- `#'handler` - Returns the **var** itself (a reference that can be dereferenced)

When http-kit calls a var:

1. Request comes in
2. http-kit dereferences the var: `@#'handler`
3. Gets the current function value
4. Calls it with the request

This indirection means changes to `handler` are picked up automatically.

## Implementation in Our Project

### Server Component

See `src/ml_options/web/server.clj`:

```clojure
(defmethod ig/init-key ::http-server
  [_ {:keys [port bind handler node]}]
  ;; Initialize job manager with XTDB node
  (when node
    (jobs/init! node))

  ;; Use var wrapper so handler picks up namespace reloads
  (let [handler-fn (or handler #'routes/handler)  ; <-- Var indirection!
        server (hk/run-server handler-fn {:port port :ip bind})]
    (log/info "HTTP server started" {:port port :bind bind})
    server))
```

Key points:

1. **Default to var**: `#'routes/handler` enables hot-reloading by default
2. **Allow override**: Accept custom handler for testing (dependency injection)
3. **Document intent**: Comment explains WHY we use the var

### Handler Chain

Our handler chain:

```
#'routes/handler          (var - enables reload)
  ↓
routes/handler            (function defined in routes.clj)
  ↓
handlers/dashboard        (specific handler functions)
handlers/health
handlers/start-import
...
```

When you edit any handler function:

1. Call `(reset)` in the REPL
2. Namespace reloads
3. `routes/handler` function gets redefined
4. http-kit picks up the change via the var
5. Next request uses new code!

## Testing Patterns

### Unit Tests - Use Function Values

In tests, you usually want to test the function directly, not through vars:

```clojure
(require '[ml-options.web.handlers :as handlers])

(deftest health-endpoint-test
  (let [response (handlers/health {:request-method :get})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "ok"))))
```

No var needed - you're calling the function directly, not through http-kit.

### Integration Tests - Use Vars for Test Server

When you need an actual HTTP server in tests:

```clojure
(defn start-test-server! []
  (hk/run-server #'test-handler {:port 0}))  ; <-- Use var in tests too!

(defn test-handler [request]
  ;; Handler that you might redefine during test development
  ...)
```

This lets you iterate on test handlers without restarting the test server.

### Development vs Production

```clojure
;; Development - hot reloading enabled
(hk/run-server #'handler {:port 8080})

;; Production - slightly faster (skips var deref on each request)
(hk/run-server handler {:port 8080})
```

In practice, the performance difference is negligible. We use the var in production too for consistency.

## Common Patterns

### Pattern 1: Wrapper Handler

```clojure
(defn wrap-reload [handler]
  "Wrapper that enables reload for any handler."
  (fn [request]
    ;; Deref the var on each request
    (@handler request)))

;; Usage:
(hk/run-server (wrap-reload #'my-handler) {:port 8080})
```

We don't use this pattern - http-kit handles var dereferencing automatically. But you might see this in older Ring-based code.

### Pattern 2: Dev/Prod Conditional

```clojure
(defn start-server! [opts]
  (let [handler-fn (if (:dev? opts)
                     #'handler      ; Dev: hot reload
                     handler)]      ; Prod: direct
    (hk/run-server handler-fn opts)))
```

We don't use this either - the var overhead is negligible even in production.

### Pattern 3: Component Handler (Our Approach)

```clojure
(defmethod ig/init-key ::http-server
  [_ {:keys [handler]}]
  (let [handler-fn (or handler #'routes/handler)]
    (hk/run-server handler-fn {:port 8080})))
```

This is what we use:
- Default to var for hot-reloading
- Allow injection for testing
- Clear and explicit

## Debugging Reload Issues

### Issue: Changes Don't Appear

**Symptom**: You edit a handler, call `(reset)`, but the server still returns old content.

**Diagnosis**:

```clojure
;; 1. Check if server is using a var
(require 'ml-options.web.server)
;; Look at init-key implementation - should see #'routes/handler

;; 2. Test the handler directly
(require 'ml-options.web.routes :reload)
(ml-options.web.routes/handler {:request-method :get :uri "/api/health"})
;; Does this return new code?

;; 3. Check if namespace reloaded
(reset)
;; Look for ml-options.web.* in :reloading output
```

**Solutions**:

1. Verify `#'` is used in server initialization
2. Ensure namespace compiled without errors
3. Try `(reset-all)` for thorough reload

### Issue: "Var Not Found" Errors

**Symptom**: Server fails to start with "Unable to resolve var" errors.

**Cause**: Trying to use `#'` with a var that doesn't exist yet.

**Solution**:

```clojure
;; Make sure the namespace is loaded first
(require 'ml-options.web.routes)

;; Then start the server
(hk/run-server #'ml-options.web.routes/handler {:port 8080})
```

In our Integrant setup, namespaces are auto-loaded by `ig/load-namespaces`, so this is rare.

### Issue: Var Works in REPL, Fails in Production

**Symptom**: Development works fine, production fails with var-related errors.

**Possible causes**:

1. AOT compilation (we don't use AOT)
2. Namespace not loaded (check `ig/load-namespaces`)
3. Different classpath between dev/prod

**Diagnosis**:

```clojure
;; Check if var exists
(resolve 'ml-options.web.routes/handler)
;; Should return the var, not nil
```

## Performance Considerations

### Var Lookup Overhead

Each request triggers:
1. Var deref: `O(1)` lookup in var's root binding
2. Function call: Same as calling directly

**Measurement**:

```clojure
;; Direct function call
(time (dotimes [_ 1000000] (handler request)))
;; "Elapsed time: 100 msecs"

;; Var deref + call
(time (dotimes [_ 1000000] (@#'handler request)))
;; "Elapsed time: 102 msecs"
```

~2% overhead, negligible compared to actual handler work.

### When It Matters

Var overhead matters if:
- You're handling 100k+ RPS (not typical for this app)
- Handler does trivial work (just returns a constant)

For typical web handlers (database queries, business logic), the overhead is unmeasurable.

## SSE and Stateful Connections

### Special Case: Server-Sent Events

Our application uses SSE for real-time updates. SSE connections are long-lived, which interacts interestingly with hot reloading.

**What Happens During Reset**:

1. Call `(reset)`
2. Integrant suspends http-server component
3. http-kit stops the server (gracefully closes connections)
4. Namespaces reload
5. Integrant resumes http-server component
6. http-kit starts new server
7. Clients reconnect automatically (browser SSE auto-reconnects)

**In Practice**:

```clojure
;; Client code (browser)
data-on-load="@post('/')"  // Auto-connects on page load

// If connection drops during reset:
// - Browser automatically reconnects
// - Server sends fresh data
// - User sees updated UI
```

**Best Practice**: Design SSE endpoints to be reconnection-friendly:

```clojure
(defn sse-handler [request]
  (sse/create-sse-handler
   (fn [_request]
     ;; Send full state on connect
     ;; Don't assume client has seen previous updates
     (render-current-state))))
```

### Middleware and Wrappers

If you're using Ring middleware:

```clojure
(defn handler [request]
  ...)

(def app
  (-> #'handler              ; <-- Use var here!
      wrap-params
      wrap-keyword-params
      wrap-json-body))

(hk/run-server app {:port 8080})
```

The var should be at the **inner** level, before middleware wrapping. This lets you change the handler while keeping middleware stable.

Our project doesn't use Ring middleware (we use Datastar's approach), but this is important for Ring-based apps.

## Integrant Integration

### Component Lifecycle

```clojure
;; init-key: Create server with var
(defmethod ig/init-key ::http-server [_ opts]
  (hk/run-server #'handler opts))

;; halt-key: Stop server gracefully
(defmethod ig/halt-key! ::http-server [_ server]
  (server :timeout 3000))  ; 3 second graceful shutdown

;; suspend-key: Not needed - default is fine
;; resume-key: Not needed - we recreate the server
```

http-kit servers don't need suspend/resume - they restart quickly and clients reconnect automatically.

### Testing Component Lifecycle

```clojure
(require '[integrant.core :as ig])

;; Test initialization
(def server (ig/init-key ::http-server {:port 0 :bind "127.0.0.1"}))

;; Test handler works
(require '[hato.client :as http])
(http/get "http://localhost:<port>/api/health")

;; Test shutdown
(ig/halt-key! ::http-server server)
```

## Advanced: Multiple Servers

If you run multiple http-kit servers (e.g., public API + admin API):

```clojure
;; system.edn
{:server/public {:port 8080 :handler #ig/ref :handler/public}
 :server/admin  {:port 9090 :handler #ig/ref :handler/admin}

 :handler/public {}
 :handler/admin {}}

;; handlers.clj
(defmethod ig/init-key :handler/public [_ _]
  #'public-handler)

(defmethod ig/init-key :handler/admin [_ _]
  #'admin-handler)

;; server.clj
(defmethod ig/init-key :server/public [_ {:keys [port handler]}]
  (hk/run-server handler {:port port}))  ; handler is already a var

(defmethod ig/init-key :server/admin [_ {:keys [port handler]}]
  (hk/run-server handler {:port port}))
```

Each server gets its own var, enabling independent hot-reloading.

## Checklist: Enabling Hot Reload

When setting up a new http-kit server:

- [ ] Use `#'handler` syntax when calling `hk/run-server`
- [ ] Document why var is used (helps future maintainers)
- [ ] Test reload workflow: edit → `(reset)` → verify change
- [ ] Ensure handler namespace compiles without errors
- [ ] Configure Integrant suspend/resume if needed (usually not for http-kit)
- [ ] Test SSE reconnection if using Server-Sent Events
- [ ] Add handler to namespace reload tracking (automatic with integrant/repl)

## Summary

**Key Takeaways**:

1. **Always use `#'handler` syntax** for http-kit in development
2. **Var indirection is cheap** - use it in production too
3. **http-kit handles var dereferencing** automatically
4. **Test by editing and calling `(reset)`** - changes should appear immediately
5. **SSE clients auto-reconnect** during server restarts

**One-Line Rule**: If you're passing a handler to http-kit, prefix it with `#'`.

## See Also

- [http-kit Server Documentation](https://http-kit.github.io/server.html)
- [Stuart Sierra's Reloaded Workflow](https://cognitect.com/blog/2013/06/04/clojure-workflow-reloaded)
- `docs/integrant-repl-workflow.md` - Full Integrant reset workflow
- [Ring SPEC](https://github.com/ring-clojure/ring/blob/master/SPEC) - Handler function contract
