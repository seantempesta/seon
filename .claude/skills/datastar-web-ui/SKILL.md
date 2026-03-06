---
name: datastar-web-ui
description: "Patterns for Datastar SSE web UI with Tailwind CSS. Use when editing handlers, html.clj, sse.clj, or routes.clj. Use when working with data-signals, data-on-click, data-text, merge-fragment, or streaming-response. Use when building dashboards, forms, real-time updates, or improving UI design. Use when styling components or making the UI look better."
---

# Datastar Web UI Patterns

## Two SSE Patterns (IMPORTANT)

Seon has two patterns for updating the UI. **See `CONVENTIONS.md` section "SSE: Direct Response vs Background Push" for the full spec.** Summary below.

### Pattern A: Direct Response (user actions)

User clicks something. Handler returns HTML. Datastar morphs the DOM from the response. No SSE channel involved.

```clojure
;; Handler — mutate state, return rendered HTML
(defn toggle-completed-handler [_request]
  (toggle-show-completed!)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (render-my-view)})

;; Button — @post returns HTML, Datastar morphs it in
[:button {:data-on:click "@post('/api/my-action')"} "Do Thing"]
```

**Use for:** toggles, form submissions, any user-initiated mutation.

### Pattern B: Background Push (system events)

Data changed in the background. Call `refresh-all!` to notify SSE clients.

```clojure
(require '[seon.web.sse :as sse])

;; After a Datalevin transaction
(d/transact! conn tx-data)
(sse/refresh-all!)

;; Or via atom watch (ctx lifecycle does this automatically)
(add-watch my-atom ::sse-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))

;; SSE handler re-renders on refresh events
(def my-sse (sse/render-handler #'my-render-fn :poll-ms 10000))
```

**Use for:** agent progress, real-time data feeds, ctx mutations.

### Rule of thumb

**If a user clicked something, return HTML directly. If data changed in the background, use `refresh-all!`.**

## CRITICAL: Attribute Syntax

**Datastar uses COLONS, not hyphens** in event attributes:
- `data-on:click` (correct)
- `data-on-click` (WRONG - won't work!)

This applies to all event handlers: `data-on:click`, `data-on:submit`, `data-on:keydown`, etc.

**Known issue:** Some existing code in `html.clj` and `components.clj` incorrectly uses `data-on-click` (hyphen). Fix these when you encounter them — do not introduce new hyphen-style attributes.

## Key Datastar Attributes

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Declare reactive state | `{:count 0}` |
| `data-text` | Bind text content | `"$count"` |
| `data-on:click` | Handle clicks | `"$count++"` |
| `data-on:submit` | Form submission | `"@post('/api/submit')"` |
| `data-model` | Two-way binding | `"$inputValue"` |
| `data-show` | Conditional display | `"$isVisible"` |
| `data-init` | Run JS on element init | `"@get('/api/sse')"` |
| `data-preserve-attr` | Preserve attr across SSE morphs | `"open"` (on `<details>`) |

## Hiccup with Datastar

```clojure
(defn render-counter []
  [:div {:data-signals "{count: 0}"}
   [:span {:data-text "$count"}]
   [:button {:data-on:click "$count++"} "Increment"]])
```

## View Transitions

View transitions are **disabled by default** in seon's SSE system (`render-handler`). Opt in only when needed for page-level navigations:

```clojure
(sse/render-handler #'my-render-fn :use-view-transition? true)
```

## SSE Buffer Design

The broadcast channel uses a **sliding buffer of size 1**. Under load, only the most recent event is kept. Clients always converge to latest state because `render-handler` re-renders from scratch. This is why Pattern A matters for user actions.

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/web/sse.clj` | SSE core: `render-handler`, `refresh-all!` |
| `src/seon/web/html.clj` | Hiccup components, base layout |
| `src/seon/web/routes.clj` | Route definitions |
| `src/seon/web/agents.clj` | Example of both Pattern A and B |
| `src/seon/web/components.clj` | Reusable UI components |
| `src/seon/ns/routes.clj` | Namespace page handlers, SSE |
| `resources/public/css/input.css` | Tailwind source with theme |
| `resources/public/css/output.css` | Built CSS (don't edit directly) |
| `CONVENTIONS.md` | Ground truth for SSE patterns |

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

- **Full SSE pattern spec**: See `CONVENTIONS.md` section "SSE: Direct Response vs Background Push"
- **Design system**: See `docs/prds/namespace-ui/design-system.md` for Phosphor Terminal theme
- **Datastar attributes**: See `docs/reference/datastar-quick-reference.md`
- **Datastar deep dive**: See `docs/reference/datastar-deep-dive.md`
