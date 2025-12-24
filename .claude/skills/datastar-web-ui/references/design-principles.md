# Frontend Design Principles

Avoid generic "AI slop" aesthetics. Create distinctive, memorable interfaces.

## Current Project Aesthetic

This project uses a **data-focused professional dashboard** aesthetic:
- Clean, functional layouts prioritizing data readability
- Zinc-based neutral palette with purple accent for activity states
- Monospace fonts for data, clean sans-serif for UI
- Minimal decoration, generous whitespace

## Design Decisions Before Coding

1. **Purpose**: What data/action does this interface serve?
2. **Tone**: Professional/utilitarian, but not boring
3. **Differentiation**: What makes this memorable and useful?

## Typography

**Avoid**: Generic fonts (Arial, Inter, Roboto, system-ui)

**Current choices**:
- Data/code: SF Mono, Menlo, Monaco (monospace)
- Consider: JetBrains Mono, Fira Code for data tables

**For future enhancement**, consider distinctive fonts via Google Fonts:
```css
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&display=swap');
```

## Color & Theme

Use CSS variables (Tailwind v4 `@theme`) for consistency:

```css
@theme {
  /* Current palette - zinc-based with purple accent */
  --color-success: #22c55e;
  --color-error: #ef4444;
  --color-warning: #f59e0b;
  --color-running: #8b5cf6;
}
```

**Principles**:
- Dominant neutral (zinc) with sharp accent colors
- Status colors should be instantly recognizable
- Avoid purple gradients on white (overused AI aesthetic)

## Motion & Interactions

Prioritize high-impact moments over scattered micro-interactions:

```css
/* Page load staggered reveal */
.fade-in { animation: fadeIn 0.3s ease-out forwards; }
.fade-in-delay-1 { animation-delay: 0.1s; }
.fade-in-delay-2 { animation-delay: 0.2s; }

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
```

**SSE-specific**: Use transitions when fragments update:
```clojure
[:div {:class "transition-all duration-200"} content]
```

## Spatial Composition

**Data dashboards**:
- Card-based layouts with consistent spacing
- Generous padding inside cards (p-6, not p-2)
- Clear visual hierarchy: title → summary → details

**Tables**:
- Alternating row colors for scanability
- Sticky headers for long tables
- Right-align numbers, left-align text

## Backgrounds & Details

**Avoid**: Flat solid backgrounds everywhere

**Better**:
```clojure
;; Subtle gradient background
[:div {:class "min-h-screen bg-gradient-to-br from-zinc-50 to-zinc-100"}]

;; Card with subtle shadow and border
[:div {:class "bg-white rounded-lg shadow-sm border border-zinc-200 p-6"}]
```

## Component Patterns

### Status Badges
```clojure
(defn status-badge [status]
  (let [colors {:running "bg-violet-100 text-violet-700 ring-violet-200"
                :completed "bg-green-100 text-green-700 ring-green-200"
                :failed "bg-red-100 text-red-700 ring-red-200"}]
    [:span {:class (str "px-2.5 py-0.5 rounded-full text-xs font-medium ring-1 ring-inset "
                        (get colors status "bg-zinc-100 text-zinc-600"))}
     (name status)]))
```

### Data Cards
```clojure
(defn stat-card [label value]
  [:div {:class "bg-white rounded-lg shadow-sm border border-zinc-200 p-6"}
   [:dt {:class "text-sm font-medium text-zinc-500"} label]
   [:dd {:class "mt-1 text-3xl font-semibold text-zinc-900"} value]])
```

### Loading States
```clojure
;; Skeleton loader
[:div {:class "animate-pulse bg-zinc-200 rounded h-4 w-32"}]

;; Spinner
[:div {:class "animate-spin h-5 w-5 border-2 border-violet-500 border-t-transparent rounded-full"}]
```

## Anti-Patterns to Avoid

| Bad | Why | Better |
|-----|-----|--------|
| `bg-white` everywhere | Flat, lifeless | Subtle gradients, shadows |
| `text-gray-500` on everything | Low contrast | Intentional hierarchy |
| Purple gradient hero | AI cliche | Project-specific aesthetic |
| Rounded everything (`rounded-full`) | Generic feel | Mix: `rounded-lg` + sharp edges |
| Centered everything | Lazy layout | Intentional alignment |
