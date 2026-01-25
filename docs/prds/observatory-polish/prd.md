# PRD: Observatory UI Polish

**Status:** In Progress (1b.1-1b.4 done, 1b.5-1b.6 pending, hover overflow bug)
**Priority:** High
**Branch:** feature/namespace-ui
**Parent PRD:** docs/prds/namespace-ui/prd.md (Phase 1b, lines 1055-1290)
**Updated:** 2026-01-25

---

## Goals

1. **Complete tool renderers** - All tools show useful one-liner summaries
2. **TOOL+RESULT pairing** - Group related lines for visual clarity
3. **Fix hover overflow bug** - Hover cards clipped by parent scroll container

---

## Current State

Phase 1b of the namespace-ui PRD brought significant improvements to Observatory:

### Done (1b.1-1b.4)

| Item | Status | Code Location |
|------|--------|---------------|
| RESULT tool names | Done | `log.clj:125-144` - `::tool-id->name` atom in logger, `log.clj:235-261` extract/merge |
| Local timestamps | Done | `views.clj:85-118` - `format-local-time` shows "14:23" (today), "Mon 14:23" (week) |
| Hover cards | Done | `views.clj:205-242` - CSS-only `hover-card` with `group-hover` pattern |
| Syntax highlighting | Done | `html.clj` - highlight.js CDN, `views.clj` - language classes on code blocks |

### Tool Renderers Implemented (9 of 14 common tools)

| Tool | Inline Summary | Hover | Code Location |
|------|----------------|-------|---------------|
| Edit | `file.clj (+3/-2)` + diff | path, stats, diff preview | `views.clj:423-487` |
| Read | `file.clj:100-200` | path, offset, limit | `views.clj:490-512` |
| Grep | `"pattern"` in path | pattern, path, glob, mode | `views.clj:515-546` |
| Bash | command or description | full command, timeout | `views.clj:549-585` |
| Glob | `"pattern"` in path | pattern, path | `views.clj:588-604` |
| Write | `file.clj (42 lines)` | path, content preview | `views.clj:607-634` |
| mcp__seon__eval | code preview + lines | session, timeout, code | `views.clj:637-674` |
| Task | agent type + description | type, prompt | `views.clj:677-698` |
| TodoWrite | `5 todos (2 active)` | status breakdown, list | `views.clj:701-736` |

### Not Started

| Item | Status |
|------|--------|
| TOOL+RESULT pairing (1b.6) | Pending |
| Hover overflow fix (1b.7) | Known bug |
| Additional tool renderers | 5 tools use fallback default |

---

## Remaining Work

### Phase 5: Complete Tool Renderers (1b.5)

**Goal:** Every tool type shows a useful inline summary with hover detail.

**Missing renderers** (currently use `:default` fallback at `views.clj:395-420`):

| Tool | Suggested Inline | Priority |
|------|------------------|----------|
| WebSearch | `"query"` | Medium - agents search docs |
| WebFetch | `url` + prompt preview | Medium |
| AskUserQuestion | `N questions` | Low - rarely in logs |
| Skill | skill name | Low |
| NotebookEdit | cell info | Low - rare in Seon |

**Implementation pattern** (follow existing renderers):

```clojure
;; In views.clj, add after TodoWrite renderer (~line 736)

(defmethod render-tool-html "WebSearch"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [query]} parsed-input]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "WebSearch"]
     [:span {:class "text-eval font-medium truncate"} (str "\"" query "\"")]]))

(defmethod render-tool-hover "WebSearch"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [query allowed_domains blocked_domains]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "query" query "text-eval")
      (when (seq allowed_domains)
        (hover-line "allowed" (str/join ", " allowed_domains)))
      (when (seq blocked_domains)
        (hover-line "blocked" (str/join ", " blocked_domains)))])))
```

**Files to modify:** `src/seon/ai/agent/views.clj`

**Test:** Each tool type renders readable one-liner (not raw JSON) in agent detail view.

### Phase 6: TOOL+RESULT Pairing (1b.6)

**Goal:** Display TOOL and its corresponding RESULT as a grouped visual unit.

**Problem:** Currently each log line renders independently:
```
14:23 | TOOL    | Read | src/foo.clj
14:23 | RESULT  | Read | (file contents...)
```

**Desired:** Visual grouping shows tool call and its outcome together:
```
14:23 | TOOL    | Read | src/foo.clj ✓
```

**Implementation approach** (view-layer grouping, not log format change):

```clojure
;; In agents.clj or views.clj - group parsed lines at render time
(defn- pair-tool-results
  "Group consecutive TOOL/RESULT pairs by tool name.
   Returns: [{:type :tool-with-result :tool {...} :result {...}}
            {:type :message ...}
            ...]"
  [parsed-lines]
  (loop [lines parsed-lines
         result []
         pending-tool nil]
    (if-let [line (first lines)]
      (cond
        ;; TOOL line - save as pending
        (= "TOOL" (:type line))
        (recur (rest lines)
               (if pending-tool (conj result pending-tool) result)
               line)

        ;; RESULT line - pair with pending tool if names match
        (and (= "RESULT" (:type line)) pending-tool)
        (let [tool-name (second (str/split (:details pending-tool) #" \| " 2))
              result-name (first (str/split (:details line) #" \| " 2))]
          (if (= tool-name result-name)
            (recur (rest lines)
                   (conj result {:type :tool-with-result
                                 :tool pending-tool
                                 :result line})
                   nil)
            ;; Names don't match - emit both separately
            (recur (rest lines)
                   (conj result pending-tool line)
                   nil)))

        ;; Other line type
        :else
        (recur (rest lines)
               (if pending-tool
                 (conj result pending-tool line)
                 (conj result line))
               nil))
      ;; Done - emit any pending tool
      (if pending-tool (conj result pending-tool) result))))
```

**Rendering grouped lines:**

```clojure
(defn- log-line-component [item idx]
  (if (= :tool-with-result (:type item))
    ;; Grouped TOOL+RESULT - show tool with success indicator
    (let [{:keys [tool result]} item
          success? (not (str/includes? (or (:details result) "") "error"))]
      [:div {:class "font-mono text-xs ..."}
       ;; Render tool line with success/fail indicator
       (tool-line-content tool)
       [:span {:class (if success? "text-success ml-2" "text-error ml-2")}
        (if success? "✓" "✗")]])
    ;; Regular line
    (existing-log-line-component item idx)))
```

**Files to modify:**
- `src/seon/web/agents.clj:545-607` - `agent-detail-content` calls `pair-tool-results`
- `src/seon/ai/agent/views.clj` - or add pairing logic here

**Test:**
1. TOOL+RESULT pairs display as single line with ✓ or ✗ indicator
2. Orphan TOOLs (no result yet) display normally
3. Hover shows both tool input and result output

### Phase 7: Fix Hover Overflow Bug (1b.7)

**Problem:** Hover cards are clipped by parent `overflow-y-auto` container.

**Current structure** (`agents.clj:604`):
```html
<div class="p-3 max-h-[70vh] overflow-y-auto flex flex-col-reverse">
  <div>
    <!-- log lines with position:relative, hover cards with position:absolute -->
  </div>
</div>
```

The `overflow-y-auto` clips `position: absolute` children that extend beyond container bounds.

**Current hover-card** (`views.clj:205-215`):
```clojure
(defn- hover-card [content]
  [:div {:class (str "hover-card hidden group-hover:block absolute left-0 top-full z-20 "
                     "bg-base-850 border border-base-700 rounded shadow-lg "
                     "mt-1 p-2 max-w-xl min-w-64 text-xs font-mono")}
   content])
```

**Options:**

| Approach | Pros | Cons |
|----------|------|------|
| 1. Remove overflow | Simple | Page gets very long, loses scroll |
| 2. `position: fixed` + JS | Always visible | Needs JS positioning, more complex |
| 3. Render above when near bottom | CSS-only | Still clips at extreme top/bottom |
| 4. Tooltip library (tippy.js) | Battle-tested | New dependency |

**Recommended:** Option 2 - `position: fixed` with simple JS positioning.

```javascript
// Add to html.clj inline script
document.addEventListener('mouseover', (e) => {
  const line = e.target.closest('.log-line');
  if (!line) return;
  const card = line.querySelector('.hover-card');
  if (!card) return;

  const rect = line.getBoundingClientRect();
  card.style.position = 'fixed';
  card.style.left = `${rect.left}px`;

  // Position below if room, else above
  const spaceBelow = window.innerHeight - rect.bottom;
  if (spaceBelow > 200) {
    card.style.top = `${rect.bottom + 4}px`;
  } else {
    card.style.top = 'auto';
    card.style.bottom = `${window.innerHeight - rect.top + 4}px`;
  }
});
```

**Updated hover-card:**
```clojure
(defn- hover-card [content]
  [:div {:class (str "hover-card hidden group-hover:block z-50 "
                     "bg-base-850 border border-base-700 rounded shadow-lg "
                     "p-2 max-w-xl min-w-64 text-xs font-mono")}
   ;; position set by JS
   content])
```

**Files to modify:**
- `src/seon/ai/agent/views.clj:205-215` - remove `absolute`, add data attr
- `src/seon/web/html.clj` - add positioning script

**Test:** Hover over log line near bottom of scroll container, card appears fully visible above viewport edge.

---

## Files Reference

| File | Line | Description |
|------|------|-------------|
| `src/seon/ai/agent/views.clj` | 383-387 | `render-tool-html` multimethod definition |
| `src/seon/ai/agent/views.clj` | 389-393 | `render-tool-hover` multimethod definition |
| `src/seon/ai/agent/views.clj` | 395-420 | `:default` fallback renderer |
| `src/seon/ai/agent/views.clj` | 205-215 | `hover-card` component (overflow bug) |
| `src/seon/ai/agent/views.clj` | 739-764 | `:html :agent.log/tool` renderer |
| `src/seon/ai/agent/log.clj` | 125-144 | `create-logger!` with `::tool-id->name` atom |
| `src/seon/ai/agent/log.clj` | 235-261 | `extract-tool-calls`, `extract-tool-results` |
| `src/seon/web/agents.clj` | 463-508 | `log-line-component` - parses and renders |
| `src/seon/web/agents.clj` | 545-607 | `agent-detail-content` - filtering, container |
| `src/seon/web/agents.clj` | 604 | `overflow-y-auto` container (hover bug source) |

---

## Design System Reference

Follow Phosphor Terminal patterns from `docs/prds/namespace-ui/design-system.md`:

- **Colors:** `text-log-tool` (amber), `text-log-result` (emerald), `text-success`/`text-error`
- **Spacing:** `py-0.5`, `gap-2`, dense rows
- **Typography:** `text-xs` (11px) primary, `font-mono` everywhere
- **Status:** Dot + word pattern (`● running`), not pill badges

---

## Constraints

1. **No log format changes** - Parse at read time, keep logs machine-readable
2. **CSS-first** - Prefer CSS over JS for visibility state
3. **Extend existing** - Use `render-tool-html` multimethod, don't create parallel system
4. **Minimal changes** - Each phase should be <100 lines of code

---

## Success Criteria

1. All common tools (WebSearch, WebFetch, AskUserQuestion, Skill) have dedicated renderers
2. TOOL+RESULT pairs display as visually grouped units with ✓/✗ indicator
3. Hover cards are fully visible regardless of scroll position
4. Tests pass: `clojure -M:test -m kaocha.runner --focus seon.ai.agent.views-test`

---

## Test Checklist

```
1b.5 [ ] WebSearch shows query, not raw JSON
1b.5 [ ] WebFetch shows URL and prompt preview
1b.5 [ ] AskUserQuestion shows question count
1b.5 [ ] Skill shows skill name
1b.6 [ ] TOOL+RESULT pairs display as single grouped line
1b.6 [ ] Orphan TOOLs (no result yet) display normally
1b.6 [ ] Grouped line shows ✓ for success, ✗ for error
1b.7 [ ] Hover card visible when hovering line at top of scroll container
1b.7 [ ] Hover card visible when hovering line at bottom of scroll container
```

---

## Deliverables

- [ ] 1b.5: Tool renderers for WebSearch, WebFetch, AskUserQuestion, Skill
- [ ] 1b.6: TOOL+RESULT pairing in view layer
- [ ] 1b.7: Hover overflow fix with fixed positioning
- [ ] Unit tests for new renderers in `seon.ai.agent.views-test`
