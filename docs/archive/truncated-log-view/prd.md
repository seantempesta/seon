---
type: prd
status: completed
tags: [prd, archive]
---

> **Status: ARCHIVED** — Complete — implemented in namespace-ui

> **Status: ARCHIVED** — Complete — implemented in namespace-ui

# PRD: Truncated Log View

**Status:** Draft
**Priority:** High
**Branch:** feature/namespace-ui
**Related:** docs/prds/observatory-polish/prd.md

---

## Goals

1. **Density by default** - Log lines show ~4 lines of content, truncated with "..."
2. **Hover for full content** - Existing hover cards show complete output
3. **Future: Full-screen view** - Click to expand to full-screen modal

---

## Problem Statement

Current Observatory log view shows full content for every log line, making it:
- Hard to scan quickly (walls of text)
- Overwhelming for long tool outputs (file reads, grep results)
- Unlike Claude Code's terminal UI which truncates by default

**Desired behavior (like Claude Code):**

```
14:23 | TOOL   | Read | src/seon/web/agents.clj
       (ns seon.web.agents
         "Agent observatory...")
       ... 750 more lines

14:23 | RESULT | Read | ✓

```

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `src/seon/ai/agent/views.clj` | Current log line renderers with hover cards |
| `src/seon/web/agents.clj` | Agent detail content, log parsing |
| Claude Code terminal | Reference UX - 4-line truncation with expand |

---

## Solution Design

### Phase 1: Truncate inline content

Update log line renderers to show max 4 lines of content inline:

```clojure
(def max-inline-lines 4)

(defn- truncate-content [content]
  (let [lines (str/split-lines content)]
    (if (> (count lines) max-inline-lines)
      {:truncated? true
       :preview (str/join "\n" (take max-inline-lines lines))
       :total-lines (count lines)
       :hidden-lines (- (count lines) max-inline-lines)}
      {:truncated? false
       :preview content})))

```

Display with truncation indicator:

```clojure
[:div {:class "text-xs font-mono"}
 [:pre preview]
 (when truncated?
   [:span {:class "text-text-500"}
    (str "... " hidden-lines " more lines")])]

```

### Phase 2: Hover shows full content (already works)

Existing hover cards already show expanded content. Verify they work with truncated inline view.

### Phase 3: Full-screen modal (future)

Add click handler to open full-screen view:
- Modal overlay with dark background
- Full content with syntax highlighting
- Copy button
- Close on Escape or click outside

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/seon/ai/agent/views.clj` | Add truncation to content renderers |
| `src/seon/web/agents.clj` | May need adjustments to log line container |

---

## Constraints

- Must not break hover card functionality
- Truncation applies to content areas, not metadata (timestamps, tool names)
- Preserve syntax highlighting in truncated view
- SSE updates should work with truncated content

---

## Success Criteria

1. Log lines show max 4 lines of content by default
2. "... N more lines" indicator when truncated
3. Hover reveals full content
4. Logs remain scannable - can see many entries at once

---

## Deliverables

- [ ] Truncation helper function
- [ ] Updated log line renderers with truncation
- [ ] Truncation indicator styling
- [ ] Verify hover cards work with truncated view
