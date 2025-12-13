# CSS Setup - Inline Approach

## Decision: Inline CSS

For this project, we've chosen **inline CSS in HTML templates** over external CSS frameworks like Tailwind.

## Rationale

### Why Inline CSS Works Here

1. **Simplicity** - No build step, no CLI watchers, no CDN dependencies
2. **Performance** - Single HTTP request for HTML+CSS, no additional stylesheets to load
3. **Colocated** - CSS lives with the HTML it styles, easy to understand and modify
4. **Scoped** - Page-specific styles don't conflict with each other
5. **No dependencies** - Works immediately without additional setup

### Approach

Each page (shim function) defines its own `<style>` block with CSS variables and component styles:

```clojure
(defn my-page-shim []
  (h/html
   [:html
    [:head
     [:style "
       :root {
         --color-bg: #fafafa;
         --color-text: #1a1a1a;
       }
       body { background: var(--color-bg); }
       .card { padding: 1rem; }
     "]]
    [:body
     [:div.card "Content"]]]))
```

### CSS Variable System

We use CSS custom properties (variables) for consistent theming:

**Light theme** (dashboard):
- Background: #fafafa
- Surface: #ffffff
- Text: #1a1a1a

**Dark theme** (log viewer):
- Background: #1a1a1a
- Surface: #2d2d2d
- Text: #e5e5e5

### Utility Classes

Common patterns are extracted as utility classes:

```css
.flex { display: flex; }
.items-center { align-items: center; }
.gap-1 { gap: 0.5rem; }
.mono { font-family: var(--font-mono); }
```

## Alternatives Considered

### Tailwind CLI

**Pros:**
- Utility-first approach, fast development
- Great autocomplete in editors
- Purge unused styles

**Cons:**
- Requires CLI watcher process
- Additional build step
- 3.5MB dependency
- Need to configure purge for Clojure files

**Verdict:** Too much overhead for our needs

### Tailwind CDN

**Pros:**
- No build step
- Easy to add to any page

**Cons:**
- Large initial download (300KB+)
- No purging of unused styles
- JIT compilation on every page load
- Slower than inline CSS

**Verdict:** Performance hit not worth the convenience

### Sail (Clojure Tailwind)

**Pros:**
- Clojure-native Tailwind
- Type-safe utilities

**Cons:**
- 2+ years old, may be outdated
- Adds complexity to build
- Another dependency to maintain

**Verdict:** Interesting but adds unnecessary abstraction

## When to Reconsider

Consider switching to Tailwind if:

1. **Multiple developers** need consistent styling
2. **Many pages** make inline CSS repetitive
3. **Complex responsive layouts** that benefit from Tailwind's media query utilities
4. **Design system** emerges that needs centralized management

For now, inline CSS is the right choice:
- 2 pages (dashboard + logs)
- Single developer
- Simple layouts
- Fast iteration

## File Structure

```
src/ml_options/web/
├── html.clj          # All HTML and inline CSS
├── handlers.clj      # Request handlers
├── routes.clj        # URL routing
└── sse.clj           # SSE infrastructure
```

CSS is defined in `html.clj` within each shim function's `<style>` block.

## Best Practices

1. **Use CSS variables** for colors, fonts, spacing
2. **Extract common utilities** for flex, grid, spacing
3. **Keep page styles isolated** - each shim has its own `<style>`
4. **Use semantic names** - `.log-entry` not `.text-sm-gray-500`
5. **Mobile-first** - Start with base styles, add media queries as needed

## Performance

Inline CSS delivers **best performance** for our use case:

- No additional HTTP requests
- No CSS parsing delay
- No unused CSS downloaded
- Gzipped HTML includes CSS (efficient)

Measured page sizes:
- Dashboard: ~12KB gzipped (HTML + CSS)
- Log viewer: ~10KB gzipped (HTML + CSS)

## Future Enhancements

If we add more pages, consider:

1. **Shared utility CSS** - Extract common utilities to a separate `<style>` include
2. **CSS file** - Move large styles to `/public/styles.css` if inline gets too large
3. **Component library** - Create reusable UI components in `html.clj`

But don't add Tailwind unless we have 10+ pages and multiple developers.

## Conclusion

**Inline CSS is the right choice for this project.**

It's simple, fast, and maintainable. We can always add Tailwind later if needed, but YAGNI (You Aren't Gonna Need It) applies here.
