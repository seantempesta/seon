---
name: datastar-web-ui
description: "Patterns for Datastar SSE web UI with Tailwind CSS. Use when editing handlers.clj, html.clj, sse.clj, or routes.clj. Use when working with data-signals, data-on-click, data-text, merge-fragment, or streaming-response. Use when building dashboards, forms, real-time updates, or improving UI design. Use when styling components or making the UI look better."
---

# Datastar Web UI Patterns

## Core Architecture: View = f(State)

State changes trigger automatic UI refresh via SSE:

```clojure
(defonce app-state (atom {:data nil}))

(add-watch app-state :sse-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))
```

## SSE Response Pattern

```clojure
(require '[ml-options.web.sse :as sse])

(defn sse-handler [request]
  (sse/streaming-response request
    (fn [send!]
      (send! (sse/merge-fragment (render-view @app-state))))))
```

## CRITICAL: Attribute Syntax

**Datastar uses COLONS, not hyphens** in event attributes:
- ✅ `data-on:click` (correct)
- ❌ `data-on-click` (wrong - won't work!)

This applies to all event handlers: `data-on:click`, `data-on:submit`, `data-on:keydown`, etc.

## Key Datastar Attributes

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Declare reactive state | `{:count 0}` |
| `data-text` | Bind text content | `"$count"` |
| `data-on:click` | Handle clicks | `"$count++"` |
| `data-on:submit` | Form submission | `"@post('/api/submit')"` |
| `data-model` | Two-way binding | `"$inputValue"` |
| `data-show` | Conditional display | `"$isVisible"` |

## Hiccup with Datastar

```clojure
(defn render-counter []
  [:div {:data-signals "{count: 0}"}
   [:span {:data-text "$count"}]
   [:button {:data-on:click "$count++"} "Increment"]])
```

## Action Handler Pattern

```clojure
(defn action-handler [request]
  (let [result (process (:body request))]
    (swap! app-state assoc :result result)  ; triggers SSE refresh
    {:status 200 :body "ok"}))
```

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/web/sse.clj` | SSE handler, brotli streaming |
| `src/seon/web/html.clj` | Hiccup components, base layout |
| `src/seon/web/handlers.clj` | Route handlers, actions |
| `src/seon/web/routes.clj` | Route definitions |
| `src/seon/web/components.clj` | Reusable UI components |
| `resources/public/css/input.css` | Tailwind source with theme |
| `resources/public/css/output.css` | Built CSS (don't edit directly) |

## Tailwind Build (Local, NOT CDN)

We use local Tailwind with `@tailwindcss/typography` for prose/markdown styling.

```bash
# Build CSS once
npm run css:build

# Watch for changes during development
npm run css:watch
```

The theme is defined in `resources/public/css/input.css` using Tailwind v4 syntax:
- `@theme { }` block for custom colors
- `@plugin "@tailwindcss/typography"` for prose classes
- `@source` directive to scan Clojure files for classes

**After editing input.css, rebuild the CSS.**

## For More Details

- **Design system**: See `docs/prds/namespace-ui/design-system.md` for Phosphor Terminal theme
- **Datastar attributes**: See `docs/reference/datastar-quick-reference.md`
- **Design principles**: See [references/design-principles.md](references/design-principles.md)
- **Extended patterns**: See `docs/reference/datastar-extended-patterns.md`
