---
type: prd
status: draft
tags: [prd, web]
---
# Seon Design System: Phosphor Terminal

**Version:** 1.0
**Philosophy:** Industrial Lisp Machine meets warm phosphor CRT

---

## Design Philosophy

Seon is a **live Lisp system** - a personal operating system where code, data, and UI are one continuous fabric. The interface should embody:

1. **McCarthy's Vision** - The screen is a window into a running computational process. Data is not static; it flows. The UI doesn't represent the system - it IS the system.

2. **Terminal Soul** - Even with modern styling, the terminal aesthetic is the foundation. Monospace everywhere. Information density. No decoration for decoration's sake.

3. **Warm Phosphor** - Not the cold blue of corporate software. Seon uses amber/cream tones inspired by vintage CRT monitors - warm, humane, inviting long sessions.

4. **Liveness** - Status indicators pulse. Data streams. The interface breathes. When you look at Seon, you should feel the system running.

5. **Every Pixel Earns Its Place** - No padding for "breathing room." No empty cards. Information density is a feature, not a bug.

---

## Color Palette

### Backgrounds (Warm Blacks)

```css
--seon-base-950: #0d0d0c;  /* Deep void - main background */
--seon-base-900: #1a1918;  /* Elevated surface */
--seon-base-850: #252422;  /* Card/panel background */
--seon-base-800: #302e2b;  /* Hover state */
--seon-base-700: #3d3a36;  /* Active/selected, borders */
```

### Text (Cream, Not White)

```css
--seon-text-50:  #faf9f7;  /* Primary text */
--seon-text-200: #d4d0c8;  /* Secondary text */
--seon-text-400: #8c8578;  /* Muted (timestamps) */
--seon-text-500: #6b6459;  /* Disabled */
```

### Semantic Colors

```css
--seon-signal:  #f0b429;  /* Primary accent - amber/gold */
--seon-success: #34d399;  /* Completions, passing tests */
--seon-error:   #f87171;  /* Failures, exceptions */
--seon-warning: #fbbf24;  /* Stuck, timeouts */
--seon-info:    #60a5fa;  /* Messages, activity */
--seon-eval:    #c084fc;  /* REPL evaluation, Lisp forms */
```

### Log Type Colors

```css
--seon-log-launch:  #a78bfa;  /* LAUNCH - violet */
--seon-log-message: #60a5fa;  /* MESSAGE - blue */
--seon-log-tool:    #fbbf24;  /* TOOL - amber */
--seon-log-result:  #34d399;  /* RESULT - emerald */
--seon-log-hook:    #22d3ee;  /* HOOK - cyan */
--seon-log-done:    #4ade80;  /* COMPLETE - green */
--seon-log-error:   #f87171;  /* ERROR - red */
```

---

## Typography

### Font Stack

```css
--font-mono: 'JetBrains Mono', 'SF Mono', ui-monospace, Menlo, Monaco, 'Cascadia Mono', monospace;
```

**Why JetBrains Mono?**

- Excellent readability at small sizes (11-12px)
- Distinguishes similar characters (0/O, 1/l/I)
- Optional ligatures for Clojure (->>, ->, =>)
- Free and widely available
- Fallback to system monospace is graceful

### Type Scale

All sizes optimized for information density:

| Token | Size | Use |
|-------|------|-----|
| `text-2xs` | 10px | Timestamps, secondary meta |
| `text-xs` | 11px | Log lines, table cells (PRIMARY) |
| `text-sm` | 12px | Emphasis, labels |
| `text-base` | 13px | Section headers |
| `text-lg` | 14px | Page titles |
| `text-xl` | 16px | Dashboard hero (rare) |

### Line Height

```css
--leading-none:  1.0;    /* Single-line items */
--leading-tight: 1.25;   /* Log lines, lists (PRIMARY) */
--leading-snug:  1.375;  /* Paragraphs (rare) */
```

### Weight

```css
--font-normal:   400;  /* Body text */
--font-medium:   500;  /* Labels, emphasis */
--font-semibold: 600;  /* Headers, status */
```

---

## Spacing System

Base unit: **4px** (half a character cell)

| Token | Size | Use |
|-------|------|-----|
| `space-0.5` | 2px | Micro gaps |
| `space-1` | 4px | Tight inline |
| `space-1.5` | 6px | Status indicator gaps |
| `space-2` | 8px | Character-width gap |
| `space-3` | 12px | Component padding |
| `space-4` | 16px | Standard padding |
| `space-6` | 24px | Section spacing |

### Density Rules

```
Log lines:     py-0.5 (2px vertical)
Table rows:    py-1.5 (6px vertical)
Cards:         p-3 (12px all sides)
Sections:      gap-6 between major blocks
Page margins:  px-4 py-3 (minimal chrome)
```

### Borders

- **Width:** 1px only (never thicker)
- **Color:** base-700 (subtle, not prominent)
- **Radius:** `rounded` (4px) or `rounded-sm` (2px)
- **Never** `rounded-full` except status dots

---

## Component Patterns

### Log Viewer

```
┌───────────────────────────────────────────────────────────────┐
│ 14:23:45 TOOL   Read file.clj                                 │
│ 14:23:46 RESULT {:lines 42, :ns "seon.core"}                  │
│ 14:23:47 MSG    Analyzing namespace structure...              │
│ 14:23:48 HOOK   ✓ tests passed (12 assertions)                │
└───────────────────────────────────────────────────────────────┘
```

**Structure:**

- Fixed-width columns: time (8ch), type (7ch), content (flex)
- Type column is the ONLY color per line
- Content is cream (#faf9f7), timestamps are muted (#8c8578)
- Hover highlights entire row with `bg-base-800`
- Expandable content uses native `<details>` (no JS state)
- `flex-col-reverse` for natural scroll-to-bottom

**CSS:**

```css
.log-line {
  font-size: 11px;
  line-height: 1.25;
  padding: 2px 0;
}
.log-line:hover {
  background: var(--seon-base-800);
}
```

### Agent Table

```
┌─────────────────────────────────────────────────────────────────┐
│ ID    NAMESPACE              STATUS      MSGS    COST          │
├─────────────────────────────────────────────────────────────────┤
│ a1b2  seon.web.handlers      ● running     47   $0.12          │
│ c3d4  seon.ai.claude         ✓ done       123   $0.45          │
│ e5f6  seon.trading           ⚠ stuck       89   $0.28          │
└─────────────────────────────────────────────────────────────────┘
```

**Structure:**

- Table, not cards (density over aesthetics)
- ID is monospace, prominent
- Right-align numeric columns
- Entire row is clickable
- No zebra striping (too noisy)

### Status Indicators

| State | Dot | Color | Animation |
|-------|-----|-------|-----------|
| running | ● | amber (#f0b429) | pulse |
| active | ● | blue (#60a5fa) | pulse |
| done | ✓ | green (#34d399) | none |
| failed | ✗ | red (#f87171) | none |
| stuck | ⚠ | amber (#fbbf24) | none |
| pending | ○ | gray (#6b6459) | none |

**Implementation:**

```html
<span class="status">
  <span class="status-dot status-running"></span>
  <span class="status-text">running</span>
</span>
```

```css
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.status-running .status-dot {
  background: var(--seon-signal);
  animation: pulse 2s infinite;
}
```

### Navigation

Minimal tab bar, not sidebar:

```
[dashboard]  [agents]  [logs]                    session: orch
```

- Active: text-50 + 2px amber underline
- Inactive: text-400, no decoration
- No background pills
- Session indicator on right

### Namespace Introspection

Tree structure with Lisp-style display:

```
seon.ai.claude
├─ functions ──────────────────────────────────────────────
│  launch-agent!  [{::ai/node ::ai/namespace ::ai/prompt}]
│  agents         [{} -> seq]
│  interrupt!     [{::ai/session-id}]
├─ atoms ──────────────────────────────────────────────────
│  *active-sessions*  {4 entries}
├─ schemas ────────────────────────────────────────────────
│  ::ai/node       [:fn {:min 1} xt/node?]
│  ::ai/namespace  [:qualified-symbol]
```

- Collapsible sections via `<details>`
- Function arglists inline
- Schemas as Malli forms
- Cross-references are links

---

## Tailwind v4 Configuration

```css
@theme {
  /* Fonts */
  --font-mono: 'JetBrains Mono', 'SF Mono', ui-monospace, Menlo, Monaco, monospace;

  /* Base colors (warm blacks) */
  --color-base-950: #0d0d0c;
  --color-base-900: #1a1918;
  --color-base-850: #252422;
  --color-base-800: #302e2b;
  --color-base-700: #3d3a36;

  /* Text colors (cream) */
  --color-text-50: #faf9f7;
  --color-text-200: #d4d0c8;
  --color-text-400: #8c8578;
  --color-text-500: #6b6459;

  /* Semantic */
  --color-signal: #f0b429;
  --color-success: #34d399;
  --color-error: #f87171;
  --color-warning: #fbbf24;
  --color-info: #60a5fa;
  --color-eval: #c084fc;

  /* Log types */
  --color-log-launch: #a78bfa;
  --color-log-message: #60a5fa;
  --color-log-tool: #fbbf24;
  --color-log-result: #34d399;
  --color-log-hook: #22d3ee;
  --color-log-done: #4ade80;
  --color-log-error: #f87171;
}
```

---

## Animation

### Pulse (for live states)

```css
@keyframes seon-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
.animate-seon-pulse {
  animation: seon-pulse 2s ease-in-out infinite;
}
```

### Skeleton Loading

```css
@keyframes seon-skeleton {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.7; }
}
.animate-skeleton {
  animation: seon-skeleton 1.5s ease-in-out infinite;
  background: var(--color-base-700);
}
```

### Page Transitions

SSE morphing handles content updates. For explicit transitions:

```css
.transition-colors {
  transition: color 150ms, background-color 150ms;
}
```

---

## Anti-Patterns

**Never do:**

- `bg-white` or any pure white background
- `text-gray-*` (use warm text-* scale)
- `rounded-full` on anything except dots
- Decorative shadows (`shadow-lg`, `shadow-xl`)
- Card-heavy layouts when tables would work
- Purple/blue gradients (AI cliche)
- `text-center` on data (left/right align by type)
- Excessive spacing for "breathing room"

---

## Implementation Checklist

- [ ] Add JetBrains Mono font import
- [ ] Update Tailwind @theme with Seon palette
- [ ] Apply bg-base-950 to body
- [ ] Refactor log viewer with new colors
- [ ] Refactor agent table with new patterns
- [ ] Update navigation to tab style
- [ ] Add status indicator components
- [ ] Test at 11px font size for readability
