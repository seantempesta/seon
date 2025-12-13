# Integrant REPL Workflow - Hot Reloading Guide

## Overview

This guide explains how hot-reloading works in our Clojure web application using Integrant and http-kit. Understanding this workflow is essential for productive REPL-driven development.

## Current Setup (As of 2025-12-02)

Our project uses:
- **Integrant 0.10.0** - Component lifecycle management
- **integrant/repl 0.4.0** - REPL workflow (uses `tools.namespace`)
- **http-kit 2.9.0-beta2** - HTTP server
- **clojure.tools.namespace 1.5.0** - Namespace reloading

### How It Works

The system is structured to enable hot-reloading:

1. **Var Indirection**: The http-kit server holds a reference to the handler **var** (`#'routes/handler`), not the function value
2. **Suspend/Resume**: Integrant's reset workflow suspends components, reloads code, then resumes
3. **Namespace Reloading**: `tools.namespace` tracks dependencies and reloads changed files

## The Var Indirection Pattern

### Why It Matters

In Clojure, when you pass a function to http-kit, it captures the **value** at that moment:

```clojure
;; WRONG - captures the function value at server start
(hk/run-server handler {:port 8080})

;; After redefining `handler`, the server still uses the old version!
```

Using a **var** instead captures a **reference** that always resolves to the current definition:

```clojure
;; CORRECT - uses the var, which always resolves to current definition
(hk/run-server #'handler {:port 8080})

;; After redefining `handler`, the server automatically uses the new version!
```

### Implementation in Our Code

See `src/ml_options/web/server.clj`:

```clojure
(defmethod ig/init-key ::http-server
  [_ {:keys [port bind handler node]}]
  ;; Use var wrapper so handler picks up namespace reloads
  (let [handler-fn (or handler #'routes/handler)  ; <-- The #' is critical!
        server (hk/run-server handler-fn {:port port :ip bind})]
    (log/info "HTTP server started" {:port port :bind bind})
    server))
```

The comment explicitly states the intent: "Use var wrapper so handler picks up namespace reloads".

## Integrant Reset Workflow

### The Standard Workflow

From your REPL (connected to port 7888):

```clojure
;; 1. Start the system
(go)

;; 2. Edit your code (handlers.clj, html.clj, routes.clj, etc.)

;; 3. Reload changed namespaces and restart components
(reset)

;; The system:
;; - Suspends all components (except nREPL - see below)
;; - Reloads changed namespaces using tools.namespace
;; - Resumes components with new code
```

### What Gets Reloaded

```clojure
;; Example reset output:
:reloading (ml-options.web.html
            ml-options.web.handlers
            ml-options.web.routes
            ...)
:resumed
```

The namespaces that changed are automatically detected and reloaded in dependency order.

### nREPL Special Handling

Our system keeps nREPL alive during reset - this is **critical** for REPL-driven development:

```clojure
;; From src/ml_options/system.clj:

;; Keep nREPL alive during (reset) - critical for REPL-driven development
(defmethod ig/suspend-key! :ml-options/nrepl-server [_ server] server)

(defmethod ig/resume-key :ml-options/nrepl-server
  [key opts old-opts old-server]
  (if (= opts old-opts)
    old-server  ; <-- Reuse existing server!
    (do (ig/halt-key! key old-server)
        (ig/init-key key opts))))
```

This means you can call `(reset)` without losing your REPL connection.

## Quick Reload Options

We provide several reload commands in `dev/user.clj`:

```clojure
(reset)       ; Full reset: suspend → reload changed → resume
(reset-all)   ; Full reset: suspend → reload ALL → resume (slower)
(refresh)     ; Just reload changed namespaces (no component restart)
(refresh-all) ; Just reload ALL namespaces (no component restart)
```

### When to Use What

| Command | Use Case | Speed | Risk |
|---------|----------|-------|------|
| `(refresh)` | Quick code change, no component state needed | Fast | Low |
| `(reset)` | Standard workflow, restarts components | Medium | Low |
| `(reset-all)` | Cleared stale state, thorough reload | Slow | Low |
| `(refresh-all)` | Debugging namespace issues | Slow | Medium |

**Best Practice**: Start with `(refresh)` for simple changes, use `(reset)` when you change component configuration or initialization logic.

## Troubleshooting

### Problem: Changes Don't Appear After `(reset)`

**Symptoms**: You edit a handler or HTML template, call `(reset)`, but the web server still returns old content.

**Diagnosis**:

1. Check if the namespace was reloaded:
   ```clojure
   (reset)
   ;; Look for your namespace in :reloading (...)
   ```

2. Verify var indirection is set up:
   ```clojure
   (require 'ml-options.web.server)
   ;; Should show #'routes/handler in the init-key implementation
   ```

3. Check for compilation errors:
   ```clojure
   (reset)
   ;; Look for :error-while-loading messages
   ```

**Solutions**:

- If namespace not reloaded: Save the file and try again
- If compilation error: Fix the error and call `(reset)` again
- If still not working: Try `(reset-all)` for a thorough reload

### Problem: Manual `(require ... :reload)` Doesn't Update Server

**Symptoms**: You call `(require 'ml-options.web.handlers :reload)` but HTTP responses don't change.

**Cause**: Our routes map captures handler function **values** at compile time:

```clojure
(def routes
  {[:get "/api/health"] handlers/health  ; <-- VALUE, not var!
   ...})
```

When you reload just the handlers namespace, the routes map still has the old function values.

**Solution**: Use `(reset)` instead - it reloads BOTH handlers and routes in dependency order:

```clojure
;; WRONG - routes map has stale handlers
(require 'ml-options.web.handlers :reload)

;; RIGHT - reloads everything in correct order
(reset)
```

**Why this matters**: This is exactly why integrant-repl exists - to handle the complex dependency graph automatically.

### Problem: `(reset)` Fails with Errors

**Symptoms**: Reset throws exceptions, components don't restart properly.

**Common Causes**:

1. **Compilation errors**: Fix syntax errors before calling reset
2. **Component initialization failure**: Check component dependencies in `system.edn`
3. **Stale REPL state**: Try `(reset-all)` or restart the REPL

**Recovery**:

```clojure
;; 1. Stop everything
(halt)

;; 2. Clear state
(clear)

;; 3. Start fresh
(go)
```

### Problem: REPL Connection Lost During Reset

**This should NOT happen** - nREPL is configured to stay alive during reset.

If it does happen:

1. Check `src/ml_options/system.clj` for the `suspend-key!` and `resume-key` implementations
2. Verify you're using `integrant.repl/reset`, not a custom implementation
3. Check for exceptions in the console where `./bin/run` is running

## Upgrade Path: integrant/repl 0.5.0

The latest version of integrant/repl (0.5.0) replaces `tools.namespace` with `clj-reload`, which offers:

- **Faster reloading**: More efficient file watching
- **Better control**: Fine-grained reload options via `set-reload-options!`
- **Cleaner API**: Simpler configuration

### How to Upgrade

1. Update `deps.edn`:
   ```clojure
   :dev {:extra-deps {integrant/repl {:mvn/version "0.5.0"}
                      ;; Can remove tools.namespace if not used elsewhere
                      }}
   ```

2. Update `dev/user.clj` if you have custom reload configuration:
   ```clojure
   ;; Old (tools.namespace):
   (ns-repl/set-refresh-dirs "src")

   ;; New (clj-reload):
   (ig-repl/set-reload-options! {:dirs ["src"]
                                  :file-pattern #"\.clj"})
   ```

3. Test thoroughly - the behavior should be identical for standard use cases

### Migration Checklist

- [ ] Update integrant/repl to 0.5.0 in deps.edn
- [ ] Test `(reset)` workflow
- [ ] Test `(reset-all)` workflow
- [ ] Verify nREPL stays alive during reset
- [ ] Verify web changes hot-reload correctly
- [ ] Update team documentation

## Reference Code Examples

### Datastar Clojure Examples

The Datastar SDK examples show the var indirection pattern clearly:

```clojure
;; From reference-code/datastar-clojure/sdk-tests/src/main/starfederation/datastar/clojure/sdk_test/main.clj

(defn reboot-jetty-server! [handler & {:as opts}]
  (swap! !jetty-server
         (fn [server]
           (when server (stop! server))
           (start! handler opts))))

(comment
  ;; Development: use var for hot-reloading
  (reboot-jetty-server! #'c/handler)

  ;; Production: use function directly
  (start! c/handler))
```

### Integrant Official Examples

See `reference-code/integrant-repl/` for the canonical implementation patterns.

## Advanced: Manual Reload Techniques

Sometimes you need fine-grained control:

```clojure
;; Reload just one namespace
(require 'ml-options.web.handlers :reload)

;; Reload a namespace and its dependencies
(require 'ml-options.web.routes :reload-all)

;; Reload without restarting components (fast iteration)
(do
  (require 'ml-options.web.handlers :reload)
  (require 'ml-options.web.routes :reload))
```

**Warning**: Manual reloads skip dependency tracking. Use `(reset)` for production workflow.

## Best Practices

1. **Always use `#'var` syntax** when passing handlers to http-kit
2. **Use `(reset)` as your default** development workflow
3. **Keep nREPL alive** during reset (already configured in our system)
4. **Watch for compilation errors** - they prevent successful reload
5. **Don't manually restart the server** - let Integrant manage lifecycle
6. **Test changes immediately** after reset to catch issues early

## See Also

- [Stuart Sierra's Reloaded Workflow](https://cognitect.com/blog/2013/06/04/clojure-workflow-reloaded) - The canonical reference
- [Integrant Documentation](https://github.com/weavejester/integrant) - Component lifecycle
- [integrant-repl README](https://github.com/weavejester/integrant-repl) - Official workflow guide
- `docs/http-kit-dev-workflow.md` - http-kit specific patterns (to be created)
