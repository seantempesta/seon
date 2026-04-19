---
type: prd
status: draft
tags: [prd, web]
---
# PRD: Dashboard Polish

---

## Summary

Transform the dashboard from a sparse marketing-page aesthetic to an information-dense terminal-style interface that embodies the "Phosphor Terminal" design philosophy. The goal is to show the state of the running system at a glance - namespace tree, agent activity, liveness indicators.

---

## Current State

The dashboard (`/`) currently shows:

1. **Header** - "Seon" title with subtitle "Personal operating system for life"
2. **Agents card** - Agent count with link to Observatory
3. **Namespaces card** - Count of loaded `seon.*` namespaces with grouped list

**Code locations:**

- Dashboard page handler: `src/seon/web/handlers.clj:40-43`
- Dashboard SSE render: `src/seon/web/handlers.clj:52-60`
- Dashboard content: `src/seon/web/html.clj:264-297`
- Skeleton: `src/seon/web/html.clj:200-219`

---

## Problems (from Design Review)

The Design Review in namespace-ui PRD identified these issues:

### 1. Excessive Spacing

| Element | Current | Should Be |
|---------|---------|-----------|
| Cards | `p-6` | `p-3` |
| Header margin | `mb-8` | `mb-4` |
| Grid gap | `gap-6` | `gap-4` |
| Page padding | `p-4` | `px-4 py-3` |

**Evidence:** Cards are ~60% empty space. Agent count displayed in `text-4xl` (36px) is absurd for a terminal UI.

### 2. No Namespace Tree

Current: Flat list of namespaces grouped by second-level prefix.
Should be: Expandable tree structure like:

```
seon
├─ ai
│  ├─ agent
│  ├─ claude
│  └─ claude.sdk
├─ db
│  ├─ multi
│  └─ node
└─ web
   ├─ agents
   ├─ handlers
   └─ html

```

### 3. No Liveness Indicators

Current: Static agent count "0 agents" or "N running".
Should be:

- Pulsing dot when agents are running
- Most recent agent activity (e.g., "Last: seon.trading 2m ago")
- Quick status of active agents inline

### 4. No Agent Details on Dashboard

Observatory requires navigation away. Dashboard should show running agents inline with:

- Session ID
- Namespace
- Status dot
- Brief activity indicator

### 5. Typography Too Large

| Element | Current | Should Be |
|---------|---------|-----------|
| Title "Seon" | `text-4xl` (36px) | `text-lg` (14px) |
| Agent count | `text-lg` (36px) | `text-base` (13px) table row |
| Subtitle | `text-sm` | `text-xs` |

---

## Goals

1. **Information density** - Show maximum useful information without scrolling
2. **Namespace tree** - Expandable hierarchy of loaded namespaces
3. **Agent presence** - See running agents and their status without navigating away
4. **Liveness** - Visual indication that the system is running (pulses, recency indicators)
5. **Design system compliance** - Follow `design-system.md` spacing, typography, colors

---

## Implementation

### Phase 1: Spacing Fixes (30 min)

Fix Design Review P0/P1 issues.

**File:** `src/seon/web/html.clj`

```clojure
;; Line 272 - page title
;; BEFORE: [:h1 {:class "text-lg font-bold tracking-tight"} "Seon"]
;; AFTER:
[:h1 {:class "text-base font-semibold"} "seon"]

;; Line 274 - subtitle
;; BEFORE: [:p {:class "text-text-400 mt-1 text-sm"} "..."]
;; AFTER:
[:p {:class "text-text-400 text-xs mt-0.5"} "personal operating system"]

;; Line 277 - grid
;; BEFORE: [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-4"}
;; AFTER:
[:div {:class "grid grid-cols-1 lg:grid-cols-3 gap-3"}

;; Cards - reduce padding throughout
;; p-3 not p-4, mb-2 not mb-4

```

**Test:**

- [ ] Page title is 13-14px, not 36px
- [ ] Cards have 12px padding
- [ ] Grid gap is 12px
- [ ] No empty space > 24px anywhere

### Phase 2: Inline Agent Status (1 hour)

Replace the sparse "Agents" card with inline agent table.

**File:** `src/seon/web/html.clj`

Add function near line 264:

```clojure
(defn- running-agent-row
  "Render a single running agent as a compact row."
  [{:keys [::agent/session-id ::agent/namespace ::agent/agent-status]}]
  (let [status-dot (case agent-status
                     :running "bg-signal animate-pulse"
                     :completed "bg-success"
                     :stuck "bg-warning"
                     "bg-text-500")]
    [:div {:class "flex items-center gap-2 py-0.5"}
     [:span {:class (str "w-1.5 h-1.5 rounded-full shrink-0 " status-dot)}]
     [:span {:class "font-mono text-xs text-text-200 w-12"} session-id]
     [:span {:class "font-mono text-xs text-text-400 truncate"} namespace]]))

(defn- agents-section
  "Inline agents section for dashboard."
  [running-agents]
  (let [count (count running-agents)]
    [:div {:class "bg-base-850 rounded p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider"}
       "Agents"]
      [:a {:href "/agents" :class "text-xs text-signal hover:underline"} "→"]]
     (if (seq running-agents)
       [:div {:class "space-y-0.5"}
        (for [agent (take 5 running-agents)]
          (running-agent-row agent))
        (when (> count 5)
          [:div {:class "text-xs text-text-400 pt-1"}
           (str "+" (- count 5) " more")])]
       [:div {:class "text-xs text-text-500"} "no agents running"])]))

```

**Test:**

- [ ] Running agents appear inline on dashboard
- [ ] Status dots pulse for running agents
- [ ] Clicking arrow navigates to Observatory
- [ ] Max 5 agents shown with "+N more" overflow

### Phase 3: Namespace Tree (1-2 hours)

Replace flat list with expandable tree.

**File:** `src/seon/web/html.clj`

Add tree functions:

```clojure
(defn- build-namespace-tree
  "Build tree structure from flat namespace list.
   Input: [seon.ai.agent seon.ai.claude seon.db.node]
   Output: {:seon {:ai {:agent {} :claude {}} :db {:node {}}}}"
  [namespaces]
  (reduce
   (fn [tree ns-sym]
     (assoc-in tree (str/split (str ns-sym) #"\.") {}))
   {}
   namespaces))

(defn- render-tree-node
  "Render a tree node with expand/collapse.
   Uses native <details> for SSE morph stability."
  [path children depth]
  (let [node-name (last path)
        has-children? (seq children)
        indent (str "pl-" (* depth 3))]
    (if has-children?
      [:details {:class indent :open (< depth 2) :data-preserve-attr "open"}
       [:summary {:class "text-xs font-mono text-text-200 cursor-pointer hover:text-text-50 py-0.5"}
        node-name]
       [:div {:class "border-l border-base-700 ml-2"}
        (for [[child-name grandchildren] (sort-by first children)]
          (render-tree-node (conj path child-name) grandchildren (inc depth)))]]
      [:div {:class (str indent " text-xs font-mono text-text-400 py-0.5")} node-name])))

(defn- namespace-tree
  "Render namespace tree section."
  [namespaces]
  (let [tree (build-namespace-tree namespaces)
        seon-tree (get tree "seon" {})]
    [:div {:class "bg-base-850 rounded p-3"}
     [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}
      "Namespaces"]
     [:div {:class "max-h-64 overflow-y-auto"}
      [:div {:class "text-xs font-mono font-medium text-text-200"} "seon"]
      [:div {:class "border-l border-base-700 ml-2"}
       (for [[name children] (sort-by first seon-tree)]
         (render-tree-node ["seon" name] children 1))]]]))

```

**Test:**

- [ ] Namespaces render as tree with └─ ├─ style lines
- [ ] Clicking expands/collapses branches
- [ ] State survives SSE updates (data-preserve-attr)
- [ ] Top 2 levels expanded by default

### Phase 4: Activity Indicator (30 min)

Add recency indicator to header.

**File:** `src/seon/web/html.clj`

```clojure
(defn- latest-agent-activity
  "Get most recent agent log activity."
  [running-agents]
  (when (seq running-agents)
    (let [latest-time (->> running-agents
                           (keep (fn [a]
                                   (when-let [mtime (log-file-mtime (::agent/session-id a))]
                                     {:agent a :mtime mtime})))
                           (sort-by :mtime >)
                           first)]
      (when latest-time
        (let [age-ms (- (System/currentTimeMillis) (:mtime latest-time))
              age-s (quot age-ms 1000)]
          {:agent (:agent latest-time)
           :age-str (cond
                      (< age-s 60) (str age-s "s ago")
                      (< age-s 3600) (str (quot age-s 60) "m ago")
                      :else (str (quot age-s 3600) "h ago"))})))))

```

Add to header section:

```clojure
[:div {:class "flex items-center gap-3"}
 [:h1 {:class "text-base font-semibold"} "seon"]
 (when (seq running-agents)
   (let [activity (latest-agent-activity running-agents)]
     [:span {:class "inline-flex items-center gap-1.5 text-xs"}
      [:span {:class "w-1.5 h-1.5 rounded-full bg-signal animate-pulse"}]
      [:span {:class "text-text-400"}
       (str "active " (:age-str activity))]]))]

```

**Test:**

- [ ] Pulsing dot appears when agents running
- [ ] Shows "active Xs ago" or "active Nm ago"
- [ ] Disappears when no agents

### Phase 5: Three-Column Layout (30 min)

Reorganize into 3-column grid for better density.

```clojure
[:div {:class "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3"}
 ;; Column 1: Agents (takes 1 col on lg, 1 col on md)
 (agents-section running-agents)
 ;; Column 2: Namespaces (takes 1 col on lg, 1 col on md)
 (namespace-tree namespaces)
 ;; Column 3: System/Meta (takes 1 col on lg, spans both on md)
 (system-status-section)]

```

Add system status section:

```clojure
(defn- system-status-section
  "System status indicators."
  []
  [:div {:class "bg-base-850 rounded p-3 md:col-span-2 lg:col-span-1"}
   [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}
    "System"]
   [:div {:class "space-y-1 text-xs font-mono"}
    [:div {:class "flex justify-between"}
     [:span {:class "text-text-400"} "xtdb"]
     [:span {:class "text-success"} "●"]]
    [:div {:class "flex justify-between"}
     [:span {:class "text-text-400"} "nrepl 7888"]
     [:span {:class "text-success"} "●"]]
    [:div {:class "flex justify-between"}
     [:span {:class "text-text-400"} "http 8080"]
     [:span {:class "text-success"} "●"]]]])

```

**Test:**

- [ ] 3 columns on large screens
- [ ] 2 columns on medium screens
- [ ] 1 column on mobile
- [ ] System status shows component health

---

## Design System Compliance Checklist

From `docs/prds/namespace-ui/design-system.md`:

### Typography

- [ ] Primary text: `text-xs` (11px)
- [ ] Page title: `text-base` (13px) max
- [ ] Section headers: `text-xs uppercase tracking-wider`
- [ ] Font: `font-mono` everywhere

### Spacing

- [ ] Card padding: `p-3` (12px)
- [ ] Grid gap: `gap-3` (12px)
- [ ] Page margins: `px-4 py-3`
- [ ] Section spacing: `mb-4` max

### Colors

- [ ] Background: `bg-base-950` (body)
- [ ] Card surface: `bg-base-850`
- [ ] Primary text: `text-text-50`
- [ ] Muted text: `text-text-400`
- [ ] Accent: `text-signal` (amber)

### Components

- [ ] Status dots: 6px, no pill backgrounds
- [ ] Links: `text-signal hover:underline`
- [ ] Tables over cards where appropriate
- [ ] `<details>` for expand/collapse

### Anti-patterns to Avoid

- [ ] No `text-4xl`, `text-3xl`, `text-2xl`
- [ ] No `p-6`, `gap-6`, `mb-8` (too much spacing)
- [ ] No `bg-white`, `text-gray-*`
- [ ] No decorative shadows
- [ ] No pill badges (use dot+text)

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/seon/web/html.clj:200-297` | Dashboard skeleton + content |
| `src/seon/web/handlers.clj:52-60` | Dashboard SSE render |
| `src/seon/web/components.clj` | Add tree component if needed |

## Files NOT to Create

- No new namespaces needed
- Extend existing `html.clj` and `components.clj`

---

## Test Criteria

### Visual Tests (Browser)

```
1. [ ] Navigate to http://localhost:8080/
2. [ ] Title is small (13-14px), not giant
3. [ ] Cards have minimal padding
4. [ ] Running agents show inline with status dots
5. [ ] Namespace tree is expandable
6. [ ] Active agents have pulsing dot in header
7. [ ] Works at 50% screen width (no broken layout)
8. [ ] Looks like terminal, not marketing page

```

### Functional Tests

```clojure
;; In REPL:
;; 1. Launch an agent
(claude/launch-agent! {...})

;; 2. Verify dashboard shows agent
;; - Status dot should pulse
;; - "active Xs ago" in header

;; 3. Kill agent, verify dashboard updates
;; - Status dot should disappear
;; - Count should decrease

```

---

## Success Criteria

1. **Density** - All content visible without scrolling on 1080p
2. **Liveness** - Can tell at a glance if system is active
3. **Navigation** - Namespace tree usable without extra clicks
4. **Consistency** - Matches Agent Observatory aesthetic
5. **50% Width** - Works in split-screen usage

---

## Related Documents

| Document | Purpose |
|----------|---------|
| `docs/prds/namespace-ui/prd.md` | Parent PRD, Phase 6 |
| `docs/prds/namespace-ui/design-system.md` | Design system spec |
| `src/seon/web/components.clj` | Shared UI components |
| `src/seon/web/agents.clj` | Reference for agent table patterns |
