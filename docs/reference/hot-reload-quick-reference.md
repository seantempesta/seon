# Hot Reload Quick Reference

## TL;DR - The Workflow

```clojure
;; 1. Edit your code (handlers.clj, html.clj, etc.)

;; 2. In the REPL:
(reset)

;; 3. Test your changes
```

That's it! Changes appear immediately.

## Common Commands

| Command | What It Does | When to Use |
|---------|--------------|-------------|
| `(go)` | Start the system | Initial startup |
| `(halt)` | Stop the system | Shutdown |
| `(reset)` | Reload changed namespaces and restart | **Use this 99% of the time** |
| `(reset-all)` | Reload ALL namespaces | When things feel broken |
| `(refresh)` | Just reload, don't restart | Quick code changes |
| `(status)` | Show system info | Check what's running |

## Troubleshooting

### Changes don't appear after `(reset)`

**Quick checks**:

1. Did you save the file?
2. Was there a compilation error? (Check reset output)
3. Did the namespace actually reload? (Look for it in `:reloading (...)`)

**Quick fix**:

```clojure
;; Try the nuclear option
(reset-all)

;; Still broken? Full restart:
(halt)
(go)
```

### "Unable to resolve var" error

**Cause**: Namespace not loaded yet.

**Fix**:

```clojure
(require 'the.missing.namespace)
(reset)
```

### REPL connection lost

**This shouldn't happen** - nREPL stays alive during reset.

If it does, check the terminal where `./bin/run` is running for errors.

## How It Works (30 Second Version)

1. **Var indirection**: Server uses `#'handler` (the var) not `handler` (the value)
2. **On each request**: Server derefs the var to get current function
3. **When you reset**: Namespace reloads, var gets new function
4. **Next request**: Server sees the new function automatically

## Key Files

- `src/ml_options/web/server.clj` - Server uses `#'routes/handler`
- `src/ml_options/web/routes.clj` - Main handler
- `dev/user.clj` - REPL commands
- `resources/system.edn` - Component config

## Testing Your Setup

```clojure
;; 1. Add a test endpoint
(in-ns 'ml-options.web.handlers)

(defn test-handler [_]
  {:status 200
   :body "Version 1"})

;; 2. Add route (in routes.clj manually)
;; [:get "/test"] test-handler

;; 3. Reset
(reset)

;; 4. Test it
;; curl http://localhost:8080/test
;; => "Version 1"

;; 5. Change it
(defn test-handler [_]
  {:status 200
   :body "Version 2"})

;; 6. DON'T reset - just test directly
(test-handler {})
;; => {:status 200, :body "Version 2"}

;; 7. The var indirection means the server sees it too!
;; curl http://localhost:8080/test
;; => "Version 2"
```

## When You Need Full Documentation

- **Integrant workflow**: See `docs/integrant-repl-workflow.md`
- **http-kit patterns**: See `docs/http-kit-dev-workflow.md`
- **Troubleshooting**: Both docs have detailed troubleshooting sections

## The One Rule

**If you're passing a handler to http-kit, use `#'handler` not `handler`.**

That's the magic that makes hot-reloading work.
