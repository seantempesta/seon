# PRD: Namespace UI

**Status:** Vision Document (phases split into focused PRDs)
**Priority:** High
**Branch:** feature/namespace-ui

---

## Phase Summary

| Phase | Goal | Status | Focused PRD |
|-------|------|--------|-------------|
| **0** | **Cleanup + UI Unification** | **✅ Done** | - |
| **0.5** | **Live Agent Widgets** | **✅ Done** | - |
| **1a** | **Render convention + view system** | **✅ Done** | - |
| **1b** | **Observatory UI improvements** | **🔄 In Progress** | [`observatory-polish`](../observatory-polish/prd.md) |
| **1c** | **Agent robustness** | **✅ Done** | [`stability-improvements`](../stability-improvements/prd.md) |
| 2 | Expand/collapse + styling | Pending | [`data-viewer`](../data-viewer/prd.md) |
| 3 | Malli schema viewer | Pending | [`schema-viewer`](../schema-viewer/prd.md) |
| 4 | XTDB entity browser | Pending | [`xtdb-browser`](../xtdb-browser/prd.md) |
| 5 | Live atom updates | Pending | [`live-updates`](../live-updates/prd.md) |
| 6 | Dashboard polish | Pending | [`dashboard-polish`](../dashboard-polish/prd.md) |
| 7 | Custom renderers | Pending | [`custom-renderers`](../custom-renderers/prd.md) |

**Note:** This document now serves as the vision and design philosophy reference. Implementation details have been moved to focused PRDs for better tracking.

All introspection is **runtime** - no hardcoded table names, schema keys, or function names.

---

## Vision

Every Clojure namespace in Seon becomes a viewable, introspectable "app". The system provides:

1. **Default renderer** - Automatically shows functions, vars, atoms, schemas, and DB entities via introspection
2. **Custom renderers** - Agents can override to build tailored UIs for specific domains
3. **Live tiles** - Windows Phone-style dashboard where each namespace is a configurable tile
4. **Session model** - View namespaces read-only, or launch sessions for live interaction

Think of Seon as an OS where namespaces are apps. Users can:
- Browse available namespaces from the dashboard
- Open namespace views (read-only introspection)
- Launch sessions for live REPL + DB access
- Have multiple instances (e.g., workout tracker for different users)
- Direct the orchestrator to create new namespaces and watch agents build them live

---

## Design Philosophy: The Terminal Soul

> "The screen is a window into a living computational process."

Seon's UI is rooted in McCarthy's vision of Lisp and the golden age of computing. This isn't "dark mode with code" - it's a philosophy:

### Core Principles

1. **Liveness** - The UI reflects the actual system state. Data flows, states change, indicators pulse. You're looking at a breathing organism, not a static page.

2. **Terminal Heritage** - Monospace typography everywhere. Information density. Fixed-width columns. The aesthetic of Symbolics Lisp Machines and Emacs, modernized.

3. **Warm Phosphor** - Not cold corporate blues. Seon uses amber/cream tones inspired by vintage CRT monitors - warm, humane, inviting long sessions.

4. **Every Pixel Earns Its Place** - No padding for "breathing room." No decorative cards. Tables over cards. Information density is a feature.

5. **Code as UI, UI as Code** - In a Lisp system, there's no distinction between data and interface. The namespace view IS the namespace.

### Design System

See [`design-system.md`](design-system.md) for:
- Color palette (Phosphor theme)
- Typography scale (JetBrains Mono, 11px primary)
- Spacing system (4px base unit)
- Component patterns (log viewer, status indicators, tables)
- Tailwind v4 configuration

---

## Skill Usage Guide

Different skills are relevant for different work. Use this matrix:

### When Building UI Components

```
/datastar-web-ui     → SSE patterns, data-on:click, merge-fragment
/frontend-design     → Visual design, avoiding generic aesthetics
/browser-automation  → Test the result visually, debug in browser
```

### When Working with Data/Queries

```
/xtdb-queries        → SQL patterns, temporal queries, multi-database
/clojure-testing     → Test patterns, generators, mocking
```

### When Designing New Features

```
/frontend-design     → Establish visual direction first
theme-factory        → Create cohesive color/font themes
skill-creator        → Codify domain-specific patterns as skills
```

### Skill Combinations by Task

| Task | Primary Skill | Supporting Skills |
|------|---------------|-------------------|
| Log viewer redesign | `/frontend-design` | `/datastar-web-ui`, `/browser-automation` |
| Agent status widget | `/datastar-web-ui` | `/xtdb-queries` |
| Namespace introspection | `/xtdb-queries` | `/datastar-web-ui` |
| Test failures | `/clojure-testing` | - |
| Theme polish | `/frontend-design` | `/browser-automation` |

### Invoking Skills

Skills can be invoked directly:
```
/datastar-web-ui - for SSE and Tailwind patterns
/frontend-design - for visual design guidance
/browser-automation - for testing in Chrome
```

Or via agent prompts:
```clojure
(claude/launch-agent!
  {::ai/prompt "Invoke /frontend-design and /datastar-web-ui skills.
                Redesign the log viewer following design-system.md."})
```

---

## Goals

1. **Every namespace viewable** - Any `seon.*` namespace can be inspected via web UI
2. **Zero-config default** - Useful view without any namespace modifications
3. **Agent-customizable** - Agents can override rendering for domain-specific UIs
4. **Session-aware** - Read-only introspection OR live session with ctx + DB
5. **Responsive tiles** - Namespace views adapt to tile/half/full view modes

---

## Problem Statement

Currently, understanding a namespace requires:
- Reading source code directly
- Using REPL to inspect vars/atoms
- Querying XTDB manually for related data

There's no unified view that shows "what's in this namespace and what's its current state?"

The agent observatory (in progress) solves this for agents specifically. This PRD generalizes the pattern to ALL namespaces.

**Impact:** Transforms Seon from a "headless" system into a visual, introspectable platform where both humans and agents can see and interact with any part of the system.

---

## URL Structure

```
/                              -> Orchestrator dashboard (namespace browser)
/seon.ai.claude                -> Namespace view (read-only introspection)
/seon.ai.claude?id=e45gf       -> Session view (live ctx + DB for that session)
```

The namespace IS the route. Session ID is optional query param.

**Rationale:**
- Minimal cruft in URLs
- Namespace names are already unique identifiers
- Query param for session keeps the base route clean
- `/` as dashboard is intuitive starting point

---

## Implementation Phases

Based on research (see `research/viewer-architecture.md`), here are actionable phases.

**Key Principle:** All introspection is RUNTIME. Nothing is hardcoded.
- Namespaces: `ns-publics`, `ns-interns`, var metadata
- Malli schemas: Query `malli.core/default-registry` at runtime
- XTDB tables: Discover via `information_schema` or `xt/q`
- Atoms: Detect via `(instance? clojure.lang.IAtom (var-get v))`

---

## Phase 0: Cleanup & New Dashboard

**Goal:** Remove trading-specific wiring from startup, replace dashboard with namespace UI, fix noisy logging.

**Duration:** 1 day

**Scope:** Keep `src/seon/trading/` code - just remove wiring. Everything is a namespace going forward.

### 0.1 Dashboard Replacement

Replace trading dashboard at `/` with namespace-focused UI:

**Remove from `seon.web.handlers`:**
- `start-import`, `stop-import`, `job-status` handlers
- Trading-specific dashboard-sse content

**Remove from `seon.web.routes`:**
- `/api/import/start`, `/api/import/stop`, `/api/import/status` routes

**Keep but simplify:**
- `seon.web.jobs` - archive, not wired to routes
- `seon.web.stats` - archive, not wired to routes

**New dashboard content in `seon.web.html`:**
```clojure
;; Simple namespace-focused dashboard
(defn dashboard-content []
  [:main#morph
   [:h1 "Seon"]
   [:p "Personal operating system for life"]

   ;; Running agents with link
   [:section
    [:h2 "Agents"]
    [:p (str (count (seon.ai.agent/agents {})) " running")]
    [:a {:href "/agents"} "View Observatory →"]]

   ;; Loaded seon.* namespaces
   [:section
    [:h2 "Namespaces"]
    (namespace-list (all-seon-namespaces))]])
```

### 0.2 Logging Cleanup

Fix `env/dev/resources/logback.xml`:

**Add Micrometer filter** (super noisy with gauge registration):
```xml
<logger name="io.micrometer" level="WARN" />
```

**Remove old reference:**
```xml
<!-- Remove: <logger name="ml-options" level="DEBUG" /> -->
```

**Change root level to INFO** (DEBUG is too noisy):
```xml
<root level="INFO">
```

### 0.3 Agent System Understanding (for reference)

Current clean architecture:
- `seon.ai.claude/launch-agent!` - spawns agent with isolated resources
- `seon.orchestrator.session` - creates namespace-isolated XTDB + nREPL
- `seon.db.multi` - per-namespace database attachment
- `seon.ai.agent` - cross-provider registry and observatory API

Each agent gets:
- Own XTDB database: `data/namespaces/{namespace}/`
- Own nREPL server: ports 7889-7999
- Own persisted ctx atom
- Log file: `logs/agents/{session-id}.log`

### Test

```
1. Restart server
2. http://localhost:8080/ shows "Seon" dashboard with namespace list
3. No import form, no trading stats
4. Logs are cleaner (no Micrometer gauge spam)
5. /agents still works
```

### Deliverables

- [x] Remove import handlers from `handlers.clj`
- [x] Remove import routes from `routes.clj`
- [x] New dashboard content in `html.clj`
- [x] Silence Micrometer in `logback.xml` (set to ERROR level)
- [x] Root log level INFO
- [x] Observatory auto-refresh via SSE polling
- [x] Agent instructions (`.claude/AGENT.md`) auto-injected
- [x] Remove `ml-options` logger reference (not found in current config)
- [x] Fix multi-agent same-namespace support (nREPL keyed by session-id now)
- [x] **Logs tab: SSE polling** - replaced background thread with `:poll-ms 2000`
- [x] **Logs tab: CSS auto-scroll** - `flex-col-reverse`, removed toggle button
- [x] **Logs tab: status indicator** - "Last log: Xs ago" with live dot
- [x] **Agent detail: type filtering** - filter by LAUNCH/MESSAGE/TOOL/etc.
- [x] **Agent detail: dark terminal theme** - unified `bg-zinc-900` aesthetic
- [ ] **Agent reliability** - detect process death, cleanup orphaned resources

### Phosphor Theme Progress (2026-01-21)

**Iteration 1 - Agents 69c3 + 1d92:**

Accomplished:
- [x] Phosphor color palette in `custom-theme` (html.clj)
- [x] Body uses `bg-base-950 text-text-50 font-mono`
- [x] Agent Observatory table uses Phosphor colors
- [x] Status badges working (amber/blue/green)

Remaining after Iteration 1:
- [x] JetBrains Mono font import (Google Fonts CDN) ✓ (done, needed manual reload)
- [x] Dashboard cards `bg-base-850` ✓ (done, needed manual reload)
- [ ] Nav bar still light - need dark styling
- [ ] Table hover states

**Key Learnings:**
1. Agents can't use `/browser-automation` - Chrome MCP not available in subprocess
2. **CRITICAL BUG:** Dev hook reloads only in agent's nREPL (port 7889+), NOT main server (7888). Edits succeed but HTTP server keeps serving old code.

**Bug Fix Needed:** `bin/seon-hook` should reload on BOTH:
- Agent's session port (7889+) - so agent's REPL has fresh code for non-UI work
- Port 7888 (orchestrator) - so HTTP server serves updated pages

---

## Next Steps: Design Iteration 2

### Critical Issues to Address

**1. Wasted Space**
Current UI has too much padding and empty space. Seon is a terminal-first system - density is a feature. Need to think about:
- Split-screen usage (windows at 50% or 33% width)
- Minimum viable size for each component
- Progressive density (more info at larger sizes, essentials at small)

**2. Nav Bar Still Light**
The navigation bar uses old zinc/white colors. Needs Phosphor dark treatment.

**3. Hook Reload Bug**
Fix `bin/seon-hook` to reload on both agent port AND port 7888.

### Design Review Criteria

Before implementing more, need critical review:
- Is information density maximized?
- Does it work at 50% screen width?
- Are we following design-system.md spacing rules (4px base, py-0.5 for log lines)?
- No decorative elements that don't earn their pixels?

### Prompt for Design Critique Agent

Launch in a dedicated Claude Code session WITH Chrome MCP access:

```
Read docs/prds/namespace-ui/prd.md and docs/prds/namespace-ui/design-system.md

You are a CRITICAL design reviewer. Your job is to judge harshly.

Use /frontend-design skill for design principles.
Use browser automation to screenshot http://localhost:8080/ and http://localhost:8080/agents

Evaluate against these criteria:
1. DENSITY - Is space wasted? Could this work at 50% screen width?
2. TERMINAL SOUL - Does it feel like a Lisp machine or a generic web app?
3. TYPOGRAPHY - Is text size appropriate? Line height tight enough?
4. SPACING - Following 4px base unit? Or bloated padding?
5. COLOR - Warm phosphor feel or cold/generic?

For each issue found:
- Screenshot the problem area
- Explain what's wrong
- Propose specific fix (CSS classes, pixel values)

Update docs/prds/namespace-ui/prd.md with your findings under "Design Review: Iteration 2"

Be harsh. Be specific. No praise unless earned.
```

---

### Phase 0 Progress Notes (2026-01-20)

**Completed:**
- Dashboard shows "Seon" with agent count + namespace list
- Trading import UI removed from `/`
- Observatory auto-refreshes (2s polling for list, 1s for detail)
- Agent instructions auto-injected via `build-agent-prompt`
- CLAUDE.md updated with Clojure agent patterns
- Multi-agent same-namespace support (nREPL keyed by session-id)
- Duplicate agent warning in `launch-agent!` (throws unless `::ai/force? true`)
- CLAUDE.md updated with "check agents before launching" instruction

**Observatory UI Improvements (in progress):**
- Status badge added (running/completed/stuck/error) with time-since-last-activity
- Stuck detection (>2min no activity shows amber warning)
- Expandable log lines (WIP - click to expand not working yet)
- Log truncation increased (2000/1500/2000 chars for content/input/output)
- Routes now use var references (`#'handler`) for proper reload - TESTING

**Architecture:**
- Each agent: own nREPL (7889-7999), own XTDB, own log file
- SSE polling added via `:poll-ms` option in `render-handler`
- nREPL servers keyed by session-id, not namespace (allows multiple agents on same ns)

**Known Issues:**
- **Expand state lost on SSE update**: Clicking to expand a log line works, but the next SSE poll (1 second) collapses it again. Agent 0295 switched from Datastar signals to native HTML `<details>` element, which should preserve open state across SSE morphs via Idiomorph. Needs browser testing.

**Hot Reload Investigation (2026-01-20):**

Agent 0295 found that basic hot reload WORKS - `clj-reload` detects file changes, reloads namespaces, and var references pick up new code. However, we discovered a deeper issue:

**Problem:** When namespaces using core.async protocols (like Mult) get reloaded, existing protocol instances become incompatible with the reloaded protocol definitions. The SSE system creates a `mult` at server startup, and reloading SSE-related code causes:
```
No implementation of method: :tap* of protocol: #'clojure.core.async/Mult
```

**Resolution (Agent e84d, 2026-01-20):**

Tested extensively and confirmed that **hot reload is working correctly**:
1. `clj-reload` does NOT reload `clojure.core.async` because it's in Maven deps, not src dirs
2. The mult instance survives namespace reloads because it's stored in Integrant state
3. The `refresh-ch_` defonce atom also survives
4. Manual testing with `(reload/reload)` after touching `seon.web.sse` showed 8 namespaces reloading correctly with no protocol errors

The original error may have occurred during:
- A `(user/reset)` which stops and restarts everything (expected behavior)
- A manual `(require 'clojure.core.async :reload)` somewhere (bad practice)
- A transient issue that was already fixed

**Hot reload is working. No changes needed.**

**Completed by Agent e84d (2026-01-20):**
1. ✅ **Hot reload** - Confirmed working. clj-reload doesn't touch core.async protocols.
2. ✅ **Expandable log lines** - Using native `<details>` with `data-preserve-attr="open"` for SSE morph stability.
3. ✅ **Hook outputs in logs** - bin/seon-hook now writes full feedback to agent logs (no truncation).
4. ✅ **Log truncation removed** - Server-side limits set to 50000 chars. UI handles display with expand/collapse.

**Agent 8415 Progress + Incident (2026-01-20/21):**

Agent 8415 was implementing Phase 0.5 (Live Agent Widgets). Completed before getting stuck:
1. ✅ Added TodoWrite to `::tool-name` enum in `hook.clj`
2. ✅ Added `::tool-input` schema with `:todos` field
3. ✅ Created `todo_event` schema + `record-todos!` in `context.clj`
4. ✅ Added widget query functions: `latest-todos`, `latest-test-result`, `latest-review`
5. ⚠️ Disabled gen-test `block-on-fail` (workaround for `::xtdb-node` schema generating nil)

**How Agent 8415 Got Stuck:**

```
1. Agent edited hook.clj → broke syntax (2 missing close parens)
2. Agent tried to edit context.clj
3. Dev hook tried to call process-hook-event! via nREPL
4. nREPL tried to require seon.dev.hook → compile failed
5. Hook script caught error → returned {:continue true}
6. File was written to disk ✓
7. But hook response never properly reached Claude process
8. Agent waited forever for edit confirmation that never came
```

**Root Cause: Hook Self-Edit Vulnerability**

The syntax repair runs INSIDE `seon.dev.hook/process-hook-event!`. When an agent edits `hook.clj` itself and breaks its syntax, the nREPL eval to call the repair code fails because the broken code can't compile. This creates a chicken-and-egg problem:

- Broken hook.clj → can't load process-hook-event! → can't repair → stuck

**Potential Fixes (not implemented):**
1. Move syntax repair to `bin/seon-hook` (Babashka) so it runs before Clojure load
2. Special-case `hook.clj` edits with pre-edit syntax validation
3. Keep a backup of last-known-good `hook.clj` and restore on compile failure
4. Add a "hook health check" that validates hook.clj can compile before proceeding

**Fixes Applied by Orchestrator (2026-01-21):**

1. ✅ Fixed hook.clj syntax (added 2 missing close parens)
2. ✅ Removed arbitrary log truncation limits (was 50000, now unlimited - UI handles display)
3. ✅ Fixed agent list status bug - list now uses `effective-agent-status` to detect stuck agents via log file mtime
4. ✅ Added `:stuck` status badge to agent list view
5. ⚠️ Gen-test blocking still disabled (needs `::xtdb-node` generator fix first)

**For Next Agent - Resume From:**

**Skills to use:**
- `/datastar-web-ui` - For Tailwind styling, SSE patterns, and UI design advice

**Terminal Theme Research (see Research section above):**
- [ ] Research terminal themes (Dracula, Nord, Tokyo Night, Catppuccin, etc.)
- [ ] Prototype 2-3 options with real log output
- [ ] Define Seon theme palette
- [ ] Apply cohesive theme to logs + agent detail

**Phase 0.5 remaining work:**
- [ ] Widget UI components in `seon.web.widgets` namespace
- [ ] Wire widgets into agent detail page sidebar
- [ ] Edit diff capture (store `old_string`/`new_string` in `edit_event`)
- [ ] Last Edit Diff widget with syntax highlighting
- [ ] MCP eval auto-interrupt fix in `bin/mcp-server`
- [ ] Fix `::xtdb-node` schema generator (currently generates nil, breaks gen-tests)

**Agent Reliability Bug (HIGH PRIORITY):**
- [ ] Investigate why agent d35d died silently without detection
- [ ] Add process death detection to `src/seon/ai/claude.clj:619-696`
- [ ] Cleanup orphaned nREPL servers when process dies
- [ ] Add watchdog / health check for running agents

**Files modified recently:**
- `src/seon/dev/hook.clj` - TodoWrite handling added
- `src/seon/dev/context.clj` - todo_event schema + widget queries
- `src/seon/web/logs.clj` - simplified (removed watcher/refresher)
- `src/seon/web/handlers.clj` - poll-ms, removed toggle-scroll
- `src/seon/web/html.clj` - flex-col-reverse, status indicator
- `src/seon/web/agents.clj` - type filtering, dark theme
- `env/dev/resources/logback.xml` - io.micrometer set to ERROR

**UI Unification (Completed 2026-01-21):**

Transferred knowledge between logs tab and agents tab to unify patterns:

> ⚠️ **Note:** The dark terminal aesthetic is "skin deep" - both use `bg-zinc-900` but the overall theme isn't cohesive. See **Research: Terminal Theme** below for next steps.

**Logs Tab Improvements (from agents patterns):**
- ✅ Removed background thread + atom watcher pattern
- ✅ Now uses `:poll-ms 2000` on SSE handler (simpler)
- ✅ Added `flex-col-reverse` for CSS-based auto-scroll to bottom
- ✅ Removed auto-scroll toggle button (no longer needed)
- ✅ Added "Last log: Xs ago" status indicator with live dot

**Agent Detail View Improvements:**
- ✅ Added type filtering (LAUNCH/MESSAGE/TOOL/RESULT/HOOK/COMPLETE/ERROR)
- ✅ Filter bar with clickable type buttons
- ✅ Shows "X/Y lines" when filtered
- ✅ Applied dark terminal aesthetic (`bg-zinc-900`)
- ✅ Added `set-type-filter!` + `/api/agents/type-filter` endpoint

**Shared Patterns Now Unified:**
- Both use `:poll-ms` for SSE polling (no background threads)
- Both use `flex-col-reverse` for auto-scroll
- Both use dark terminal aesthetic (`bg-zinc-900`)
- Both support expand/collapse with `data-preserve-attr="open"`

**Files Modified:**
- `src/seon/web/logs.clj` - removed watcher/refresher
- `src/seon/web/handlers.clj` - poll-ms, removed toggle-scroll
- `src/seon/web/html.clj` - flex-col-reverse, status indicator
- `src/seon/web/agents.clj` - type filtering, dark theme
- `src/seon/web/routes.clj` - new type-filter endpoint
- `src/seon/web/server.clj` - removed logs init call

**Logging Fix:**
- ✅ Set `io.micrometer` to ERROR level in logback.xml (suppresses "Gauge already registered" noise)

---

---

## Research: Terminal Theme

**Status:** Not started
**Goal:** Find a cohesive, awesome terminal theme - not just "okay"

### Problem

Current state is "skin deep" unification:
- Both logs and agent detail use `bg-zinc-900`
- But colors, typography, spacing aren't cohesive
- No consistent syntax highlighting palette
- Different hover states, borders, etc.

### Research Tasks

1. **Study existing terminal themes** - Look at popular terminal emulator themes:
   - Dracula, Nord, Solarized Dark, Gruvbox, Tokyo Night, Catppuccin
   - What makes them feel cohesive? Color relationships, contrast ratios

2. **Study code editor themes** - VS Code, Sublime themes for inspiration:
   - How do they handle syntax highlighting for different token types?
   - What makes log output scannable?

3. **Prototype 2-3 options** - Apply to both logs and agent detail:
   - Test with real log output (TOOL, RESULT, MESSAGE, etc.)
   - Get feedback on scannability and aesthetics

4. **Define a Seon theme palette**:
   - Background tiers (bg, bg-elevated, bg-hover)
   - Text tiers (primary, secondary, muted)
   - Semantic colors (success, error, warning, info)
   - Log type colors (TOOL, RESULT, MESSAGE, HOOK, etc.)

### Resources

- **Claude UI Design skill** - Use `/datastar-web-ui` skill when implementing. It has patterns for Tailwind styling and can provide design advice.
- Consider using CSS custom properties for theme switching later

### Deliverables

- [ ] Research doc: `research/terminal-theme-research.md`
- [ ] Theme palette definition in code (CSS vars or Tailwind config)
- [ ] Apply to logs tab
- [ ] Apply to agent detail view
- [ ] Ensure both look cohesive side-by-side

---

**Agent Reliability Issue (Discovered 2026-01-21):**

Agent d35d died silently mid-task without the system detecting it:
- Claude process terminated but `agent-status` remained `:running`
- nREPL server orphaned (still listening on port)
- No COMPLETE marker in log file

**Root Cause (needs investigation):**
The reader loop in `src/seon/ai/claude.clj:619-696` should detect process death via `readLine` returning `nil`, then set status to `:terminated` in the `finally` block. This isn't happening.

**Potential fixes needed:**
1. Add process health check / watchdog
2. Monitor `exit-ref` from process
3. Timeout-based stuck detection
4. Cleanup orphaned nREPL servers

---

**Agent Detail View UX Improvements (Future):**

Current format is adequate but could be improved:
1. **Local time, short format** - "10:14:45" not full ISO timestamp
2. **Hide tool IDs** - "toolu_016..." adds no value
3. **Better type icons** - Use emoji or icons for TOOL/RESULT/MESSAGE
4. **Smart content formatting** - Syntax highlighting for code/JSON/EDN

---

## Phase 0.5: Live Agent Widgets (XTDB-Backed)

**Goal:** Per-agent widgets showing real-time status from XTDB queries instead of log parsing.

**Context:** The hook already writes structured data to XTDB (`edit_event`, `review_event` tables). We should query this directly for rich widgets rather than parsing log files.

### Data Sources

**Already in XTDB (via `seon.dev.context`):**
- `edit_event` - file, namespace, test results (pass/fail counts), decision, reason, feedback
- `review_event` - files reviewed, full Gemini interaction (prompt, response, tokens)

**Need to add:**
- `todo_event` - agent's current todo list (TodoWrite events reach the hook with full `:todos` in `tool_input`)

### Widget Design

Each widget shows the **most recent** data by default, but clicking any log entry shows the data **as of that entry's timestamp**.

**1. Test Status Widget**
```
┌─ Tests ─────────────────────────┐
│ ✓ 12 unit tests passed          │
│ ✓ 5 gen-tests passed            │
│ Last run: 2m ago                │
└─────────────────────────────────┘
```
- Query: `SELECT * FROM edit_event WHERE ... ORDER BY _valid_from DESC LIMIT 1`
- Show: pass/fail counts, timestamp
- Click log entry → show test results at that point

**2. Gemini Review Widget**
```
┌─ Last Review ───────────────────┐
│ "The changes look good. Minor   │
│ suggestion: consider adding..." │
│ [expand]                        │
│                                 │
│ Tokens: 1.2k prompt, 0.4k resp  │
│ (0.8k cached)                   │
│ 3m ago                          │
└─────────────────────────────────┘
```
- Query: `SELECT * FROM review_event ORDER BY _valid_from DESC LIMIT 1`
- Show: truncated review text, token usage (not cost), timestamp
- Click → expand full review

**3. Todo Status Widget**
```
┌─ Tasks ─────────────────────────┐
│ ● Implementing hot reload fix   │
│ ○ Add expand/collapse to logs   │
│ ○ Remove log truncation         │
│ ✓ Research Datastar patterns    │
└─────────────────────────────────┘
```
- Query: `SELECT * FROM todo_event WHERE session_id = ? ORDER BY _valid_from DESC LIMIT 1`
- Show: current todo list with status indicators (● in_progress, ○ pending, ✓ completed)

**4. Last Edit Diff Widget**
```
┌─ Last Edit ─────────────────────┐
│ agents.clj                      │
│ ┌───────────────────────────────┤
│ │- (defn old-fn []              │
│ │+ (defn new-fn []              │
│ │    (println "hello"))         │
│ └───────────────────────────────┤
│ 30s ago                         │
└─────────────────────────────────┘
```
- **Data capture:** Edit tool sends `{:file_path :old_string :new_string}` - store all three in `edit_event`
- **Display:** Syntax-highlighted unified diff (green additions, red deletions)
- **Click EDIT log entry** → show that specific diff in the widget
- **Diff library:** Use `clojure.data/diff` or simple line-by-line comparison
- **Syntax highlighting:** Detect language from file extension, use CSS classes for tokens

### Diff Capture Implementation

Edit hook receives:
```clojure
{:tool_input {:file_path "/path/to/file.clj"
              :old_string "(defn old-fn []..."
              :new_string "(defn new-fn []..."}}
```

Store in `edit_event`:
- `::old-string` - the replaced text
- `::new-string` - the replacement text

This enables:
1. Last Edit widget shows most recent diff
2. Click any EDIT/HOOK log entry → widget shows that edit's diff
3. Full edit history with diffs queryable from XTDB

### Implementation Plan

**0.5.1 TodoWrite Capture** ✅ Done (Agent 8415)
1. ✅ Add "TodoWrite" to `::tool-name` enum in `hook.clj`
2. ✅ Extract `:todos` from `tool_input`
3. ✅ Create `todo_event` table schema in `context.clj`
4. ✅ Store todo list to XTDB on each TodoWrite via `record-todos!`

**0.5.2 Widget Queries** ✅ Done (Agent 8415)
1. ✅ Add query functions to `seon.dev.context`:
   - `latest-test-result` - most recent edit_event with test data
   - `latest-review` - most recent review_event
   - `latest-todos` - most recent todo_event for session
2. [ ] Add `event-at-timestamp` variants for historical queries

**0.5.3 Widget UI Components** ❌ Not Started
1. [ ] Create `seon.web.widgets` namespace
2. [ ] Implement each widget as a component
3. [ ] Add to agent detail page sidebar

**0.5.4 Push Updates (Optional Enhancement)** ❌ Not Started
Instead of polling, push updates when events happen:
1. [ ] Track agent SSE connections in atom `{session-id -> sse-gen}`
2. [ ] On hook event, call `(d*/patch-elements! sse widget-html {:selector "#widget-id"})`
3. [ ] Fallback to polling for reliability

### MCP Eval Timeout Fix

**Problem:** Agent e84d got stuck on an `mcp__seon__eval` that hung indefinitely.

**Root cause:** The 30s timeout returns an error but doesn't kill the blocked eval thread.

**Fix:** Add auto-interrupt after timeout in `bin/mcp-server`:
```clojure
;; After timeout, also send interrupt
(when (= (:ex result) "timeout")
  (nrepl-interrupt port nrepl-session-id))
```

Also consider:
1. Agents should pass longer `timeout_ms` for slow operations
2. Add visible timeout countdown in UI
3. Auto-interrupt button in agent detail view

### Test Criteria

```
1. ✅ TodoWrite event → todo_event row in XTDB (via record-todos!)
2. [ ] Agent detail page shows 3 widgets: Tests, Review, Tasks
3. [ ] Click log entry → widgets update to show data at that timestamp
4. [ ] Token usage shows (no cost references)
5. [ ] Stuck eval → auto-interrupt after timeout
```

---

## Design Review: Iteration 2

**Date:** 2026-01-21
**Reviewer:** Critical Design Agent (Opus 4.5)
**Verdict:** FAILING - Multiple violations of design system

---

### Summary

The UI shows promise with the Phosphor color palette partially implemented, but fundamentally fails to achieve the "Terminal Soul" aesthetic. It currently looks like a **generic dark-mode web dashboard** rather than a **Lisp machine interface**. The main issues are:

1. **Excessive padding and wasted space** - Cards are 90% empty
2. **Nav bar uses web widget pattern** instead of terminal underline style
3. **Typography too large** - Hero text belongs on a marketing page, not a terminal
4. **Inconsistent component patterns** - Dashboard vs Agent Observatory feel like different apps

---

### 1. DENSITY - **FAIL**

**Evidence:** Dashboard AGENTS card (zoomed):
```
┌────────────────────────────────────────┐
│ AGENTS                                  │  ← Good: uppercase label
│                                         │
│ 0                                       │  ← BAD: text-4xl (36px) wastes space
│                                         │
│ No agents running                       │
│                                         │
│ View Observatory →                      │
│                                         │
│                                         │  ← BAD: 60% empty space
│                                         │
└────────────────────────────────────────┘
```

**Violations:**
| Element | Current | Design System | Line |
|---------|---------|---------------|------|
| Card padding | `p-6` (24px) | `p-3` (12px) | html.clj:275 |
| Header margin | `mb-8` (32px) | `mb-4` (16px) | html.clj:268 |
| Grid gap | `gap-6` (24px) | `gap-4` (16px) | html.clj:273 |
| Body padding | `p-4` (16px) | `px-4 py-3` | html.clj:162 |

**50% Width Test:** At narrow widths, cards stack vertically but remain sparse. The agent count "0" in 36px text is absurd for a terminal UI.

**Fix:**
```clojure
;; Dashboard cards - compress everything
[:div {:class "bg-base-850 rounded p-3"}  ; was p-6
 [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}  ; was mb-4
  "Agents"]
 [:div {:class "flex items-baseline gap-2 mb-2"}  ; was mb-4, was text-4xl
  [:span {:class "text-lg font-semibold font-mono"} agent-count]  ; text-lg not text-4xl
  [:span {:class "text-xs text-text-400"} "running"]]
 [:a {:href "/agents" :class "text-xs text-signal hover:underline"} "observatory →"]]
```

---

### 2. TERMINAL SOUL - **FAIL**

**Evidence:** Nav bar (zoomed):
```
┌─────────────────────────────────────┐
│ ┌───────────┐                       │
│ │ Dashboard │  Agents    Logs       │  ← BAD: Pill container with bg-base-900
│ └───────────┘                       │
└─────────────────────────────────────┘
```

**Current Code (html.clj:86-107):**
```clojure
[:nav {:class "flex gap-1 mb-6 bg-base-900 p-1 rounded w-fit"}  ; ← PILL WIDGET
 [:a {:class "px-4 py-2 rounded ... bg-base-800 ..."}  ; ← BACKGROUND FILL
```

**Design System Spec:**
> "Minimal tab bar, not sidebar"
> "Active: text-50 + 2px amber underline"
> "Inactive: text-400, no decoration"
> "No background pills"

**Fix:**
```clojure
(defn nav-bar [active-page]
  [:nav {:class "flex gap-6 mb-4 border-b border-base-700"}
   (for [[page label] [[:dashboard "dashboard"] [:agents "agents"] [:logs "logs"]]]
     [:a {:href (case page :dashboard "/" :agents "/agents" :logs "/logs")
          :class (str "pb-2 text-sm font-medium transition-colors "
                      (if (= active-page page)
                        "text-text-50 border-b-2 border-signal -mb-px"
                        "text-text-400 hover:text-text-200"))}
      label])])
```

**Other Terminal Soul Issues:**
- Dashboard feels like a marketing page, not a running system
- No sense of "liveness" - static cards with static numbers
- Agent table is correct density, but Dashboard is wrong

---

### 3. TYPOGRAPHY - **PARTIAL FAIL**

**What Works:**
- JetBrains Mono correctly loaded ✓
- Log lines use text-xs (11px) ✓
- Uppercase labels on section headers ✓

**What Fails:**

| Element | Current | Design System Max |
|---------|---------|-------------------|
| Dashboard "Seon" | `text-4xl` (36px) | `text-xl` (16px) hero |
| Agent count | `text-4xl` (36px) | Should be table data |
| "Agent Observatory" | `text-3xl` (30px) | `text-lg` (14px) page title |

**Evidence from html.clj:269:**
```clojure
[:h1 {:class "text-4xl font-bold tracking-tight"} "Seon"]  ; ← 36px is absurd
```

**Fix:**
```clojure
;; Page titles should be text-lg (14px), not text-4xl
[:h1 {:class "text-lg font-semibold"} "Seon"]

;; Agent Observatory header
[:h1 {:class "text-base font-semibold tracking-tight"} "Agent Observatory"]
```

---

### 4. SPACING - **FAIL**

**4px Base Unit Violations:**

| Element | Current | Correct | Issue |
|---------|---------|---------|-------|
| Header bottom margin | `mb-8` (32px) | `mb-4` (16px) | 2x too much |
| Section gap | `gap-6` (24px) | `gap-4` (16px) | 1.5x too much |
| Card padding | `p-6` (24px) | `p-3` (12px) | 2x too much |
| Nav bottom margin | `mb-6` (24px) | `mb-4` (16px) | 1.5x too much |

**The spacing creates a "web app" feel** - generous whitespace for "breathing room" that terminals don't have.

---

### 5. COLOR - **MOSTLY CORRECT**

**Phosphor Palette Implementation:** ✓
- `base-950` (#0d0d0c) background ✓
- `base-850` (#252422) card surfaces ✓
- `text-50` (#faf9f7) primary text ✓
- `signal` (#f0b429) amber accent ✓
- Log type colors all correct ✓

**Issue:** Status badges still use pill backgrounds (`bg-info/20`) instead of dot+text pattern.

**Current (html.clj:112-127):**
```clojure
[:span {:class "inline-block px-3 py-1 rounded text-xs font-semibold uppercase tracking-wide"
        :class "bg-info/20 text-info"}  ; ← PILL STYLE
```

**Design System Pattern:**
```
● running  ← 6px dot + text, no background
✓ done
⚠ stuck
```

**Fix already implemented in agents.clj:130-147** - use that pattern everywhere.

---

### 6. THEME COHESION - **PARTIAL FAIL**

**Split Personality:**
- **Dashboard:** Marketing page aesthetic (hero text, sparse cards, big numbers)
- **Agent Observatory:** Correct terminal aesthetic (dense table, small text, efficient)
- **Agent Detail:** Good terminal feel (log lines, filter bar)

These feel like different applications. The dashboard needs to match the Observatory's density.

**Component Duplication:**
- `html.clj` has `status-badge` with pill style
- `agents.clj` has `agent-status-badge` with dot+text style
- Should be one shared component using the correct pattern

---

### Priority Fixes

**P0 - Critical (do first):**
1. Nav bar: Remove pill container, add underline for active state
2. Dashboard cards: `p-6` → `p-3`, remove giant numbers
3. Page titles: `text-4xl` → `text-lg`

**P1 - Important:**
4. Unify status badges to dot+text pattern
5. Reduce all `mb-8`, `mb-6` to `mb-4`
6. Grid gap: `gap-6` → `gap-4`

**P2 - Polish:**
7. Add liveness indicators to dashboard (pulsing dot if agents running)
8. Extract shared components for status, log lines
9. Use `ch` units for fixed-width columns

---

### Implementation Checklist

```
[ ] Nav bar underline style (html.clj:86-107)
[ ] Dashboard card padding p-6 → p-3 (html.clj:275,289)
[ ] Dashboard title text-4xl → text-lg (html.clj:269)
[ ] Dashboard agent count text-4xl → text-lg (html.clj:278-279)
[ ] Header margins mb-8 → mb-4 (html.clj:268)
[ ] Grid gap-6 → gap-4 (html.clj:273)
[ ] Unify status-badge to use dot+text (html.clj:109-127)
[ ] Observatory title text-3xl → text-base (agents.clj:244)
[ ] Remove text-4xl from codebase entirely
```

---

## Phase 1a: Render Convention + View System ✅ COMPLETE

**Goal:** Namespaces can provide custom `render` functions that work with the view multimethod system.

**Implementation (January 2025):**

### Architecture

```
seon.ns.routes          - Checks for `render` fn in namespaces, calls with {:format :id}
seon.ns.view            - Multimethod (render value format) dispatching on [format view-type]
seon.ai.agent           - Provides `render` fn for /ns/seon.ai.agent
seon.ai.agent.views     - View methods for :seon.ai.agent/summary, :agent.log/* types
```

### Key Files

| File | Purpose |
|------|---------|
| `src/seon/ns/view.clj` | `render` multimethod, `typed` helper, default renderers for :html/:ai/:raw |
| `src/seon/ns/routes.clj` | `namespace-has-render?`, `call-namespace-render`, SSE handler |
| `src/seon/ai/agent.clj` | `render` fn, `init!` for XTDB, log parsing |
| `src/seon/ai/agent/views.clj` | View methods for agent summary and log line types |

### Render Convention

Namespaces can provide a `render` function:

```clojure
(defn render
  "Called by /ns/{namespace} route."
  [{:keys [format id]}]
  (if id
    (view/render (view/typed :my-ns/detail (get-detail id)) format)
    (view/render (view/typed :my-ns/list (get-list)) format)))
```

- `:format` - `:html`, `:ai`, `:human`, or `:raw`
- `:id` - Optional, from `?id=` query param

### Current Status

- ✅ `/ns/seon.ai.agent` shows agent list via view system
- ✅ Click rows → navigates to `/agents/{id}` detail page (old UI)
- ✅ SSE live updates work
- ✅ Log line parsing extracts type-specific fields
- ❌ Detail view uses OLD `/agents/{id}` page, not new view system
- ❌ No interactive elements (hover, expand/collapse)
- ❌ No tooltips or inline details

---

## Phase 1b: Observatory UI Improvements

**Goal:** Fix tool rendering issues with minimal changes - extend existing systems, use CSS for progressive disclosure.

**Status:** 🔄 In Progress (1b.1-1b.4 done, 1b.5-1b.6 pending, hover overflow bug)
**Date:** 2026-01-23

**Key Document:** `research/rendering-review.md` - Critical review that identified simpler approach

---

### Design Principles

After architectural review, we chose the **simpler path**:

1. **Extend existing `seon.ns.view/render*`** - Don't create parallel rendering system
2. **CSS for progressive disclosure** - Hover/expand are UI state, not data formats
3. **Parse at read time** - Keep log format stable, derive structure when reading
4. **Fix actual problems directly** - No new abstractions, just targeted fixes

> "When in doubt: can we solve this with CSS? If yes, do that." — rendering-review.md

---

### Problems to Fix

| Problem | Current | Solution |
|---------|---------|----------|
| Verbose tool IDs | `toolu_014BHfdp9mzEGzddcQx6nJq5` | Track `tool_use_id → tool_name` mapping |
| UTC timestamps | `2026-01-23T14:23:20Z` | Format locally in view layer |
| No hover preview | - | CSS `group-hover` cards |
| No expand | `<details>` exists but limited | Enhance existing pattern |
| No syntax highlighting | Plain text | Add highlight.js CDN |

---

### Implementation Phases

#### 1b.1 Fix RESULT Tool Names ✅ Testable

**Problem:** RESULT lines show `tool_use_id` instead of tool name.

**Root cause:** In `log.clj:251`, `extract-tool-results` uses `:tool_use_id` as `::tool-name`.

**Fix:** Track mapping from preceding TOOL calls.

**File:** `src/seon/ai/agent/log.clj`

```clojure
;; Add state to track tool_use_id -> tool_name
(defn- extract-tool-calls [content]
  ;; Returns [{::tool-name "Edit" ::tool-use-id "toolu_xxx" ::input {...}} ...]
  ...)

;; In log-sdk-message!, build mapping from assistant message tool_use blocks
;; Use mapping when logging RESULT to get actual tool name
```

**Test:**
```
;; Before: RESULT | toolu_014BHfdp9mzEGzddcQx6nJq5 | "success"
;; After:  RESULT | Edit | "success"
```

#### 1b.2 Local Timestamps ✅ Testable

**Problem:** UTC timestamps take space, require mental conversion.

**Fix:** Format in view layer (not log layer - keep logs machine-parseable).

**File:** `src/seon/ai/agent/views.clj`

```clojure
(defn- format-local-time [iso-timestamp]
  (let [inst (Instant/parse iso-timestamp)
        local (.atZone inst (ZoneId/systemDefault))
        now (LocalDateTime/now)]
    (cond
      ;; Today: just time
      (= (.toLocalDate local) (.toLocalDate now))
      (format "%02d:%02d" (.getHour local) (.getMinute local))

      ;; This week: day + time
      (< (Math/abs (.until (.toLocalDate local) (.toLocalDate now) ChronoUnit/DAYS)) 7)
      (format "%s %02d:%02d"
              (.getDisplayName (.getDayOfWeek local) TextStyle/SHORT Locale/ENGLISH)
              (.getHour local) (.getMinute local))

      ;; Older: date + time
      :else
      (format "%s %d %02d:%02d"
              (.getDisplayName (.getMonth local) TextStyle/SHORT Locale/ENGLISH)
              (.getDayOfMonth local)
              (.getHour local) (.getMinute local)))))
```

**Test:**
```clojure
(format-local-time "2026-01-23T14:23:20Z") ;; => "14:23" (if today)
(format-local-time "2026-01-20T14:23:20Z") ;; => "Mon 14:23" (if this week)
(format-local-time "2026-01-01T14:23:20Z") ;; => "Jan 1 14:23" (older)
```

#### 1b.3 Hover Cards via CSS ✅ Testable

**Problem:** No quick preview without clicking.

**Fix:** CSS-only hover cards using Tailwind's `group-hover`.

**File:** `src/seon/ai/agent/views.clj` (markup), `src/seon/web/html.clj` (styles)

```clojure
;; In log-line-component:
[:div {:class "log-line group relative"}
 ;; Always visible - inline summary
 [:span {:class "inline-content"} ...]

 ;; Hover card - hidden until group hover
 [:div {:class "hover-card hidden group-hover:block absolute left-0 top-full
                z-10 bg-base-850 border border-base-700 rounded p-3
                max-w-lg shadow-lg"}
  ;; More detailed content
  ...]]
```

**Test:** Browser - hover over log line, card appears below.

#### 1b.4 Syntax Highlighting ✅ Testable

**Problem:** Code blocks are plain text.

**Fix:** Add highlight.js via CDN.

**File:** `src/seon/web/html.clj`

```clojure
;; In base-page head:
[:link {:rel "stylesheet"
        :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css"}]

;; Before closing body:
[:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"}]
[:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/clojure.min.js"}]
[:script "hljs.highlightAll();"]
```

**File:** `src/seon/ai/agent/views.clj` - Add language classes:

```clojure
[:pre [:code {:class "language-clojure"} clojure-code]]
[:pre [:code {:class "language-bash"} bash-command]]
```

**Test:** Browser - code blocks have syntax colors.

#### 1b.5 Expand Tool Renderers ✅ Testable

**Problem:** Not all tools have good rendering.

**Fix:** Expand existing `render-tool-html` multimethod coverage.

**File:** `src/seon/ai/agent/views.clj`

Tools to ensure have good renderers:
- [x] Edit - show file, diff stats
- [x] Read - show file, line range
- [ ] Grep - show pattern, match count
- [ ] Bash - show command (use description if available)
- [ ] Glob - show pattern, file count
- [ ] mcp__seon__eval - show code snippet, result preview
- [ ] Task - show agent type, description
- [ ] TodoWrite - show item count, status summary

**Test:** Each tool type renders a useful one-liner.

#### 1b.6 TOOL+RESULT Pairing (View Layer) ✅ Testable

**Problem:** TOOL and RESULT are separate lines.

**Fix:** Group them in the view layer when rendering.

**File:** `src/seon/ai/agent/views.clj`

```clojure
(defn- pair-tool-results [parsed-lines]
  ;; Group consecutive TOOL/RESULT pairs
  ;; Returns: [{:type :tool-pair :tool {...} :result {...}}
  ;;          {:type :message ...} ...]
  )
```

**Test:** TOOL and RESULT display as single grouped line with success indicator.

---

### Files to Modify

| File | Changes |
|------|---------|
| `src/seon/ai/agent/log.clj` | Track tool_use_id → name mapping (1b.1) |
| `src/seon/ai/agent/views.clj` | Local timestamps (1b.2), hover cards (1b.3), tool renderers (1b.5), pairing (1b.6) |
| `src/seon/web/html.clj` | highlight.js CDN (1b.4), hover card CSS |

### Files NOT to Create

Per review recommendations:
- ~~`src/seon/render.clj`~~ - Use existing `seon.ns.view`
- ~~`src/seon/render/tools.clj`~~ - Extend `render-tool-html` instead
- ~~`src/seon/web/modals.clj`~~ - Use `<details>` for expand

---

### Test Checklist

```
1b.1 [x] RESULT lines show tool name, not tool_use_id
1b.2 [x] Timestamps show local time (14:23 not 2026-01-23T14:23:20Z)
1b.3 [~] Hover card appears on mouse hover (BUG: overflow clipped by parent)
1b.4 [x] Code blocks have syntax highlighting
1b.5 [ ] All tool types render useful one-liners
1b.6 [ ] TOOL+RESULT display as grouped unit
```

**Known Bug:** Hover cards are clipped by parent `overflow-y-auto` container. Fix options:
1. Remove overflow constraint from log container
2. Use `position: fixed` with JS positioning
3. Render hover cards in portal outside scroll container

---

### References

- `research/rendering-review.md` - Full architectural review with rationale
- `research/datafy-render-research.md` - Original research (superseded by review)
- `research/malli-render-research.md` - Schema approach research

---

## Phase 1c: Agent Robustness

**Goal:** Make agents resilient to errors so they don't exit unexpectedly.

**Status:** Pending
**Date:** 2026-01-23

**Background:** Investigation found agents getting `:interrupted` status (exiting without sending result message) when users did not interrupt them. While no single smoking gun was found, several defensive improvements are needed.

---

### Findings from Investigation

1. **Basic flow works** - Test agents complete successfully
2. **Incomplete agents have `:interrupted` status** - Not crashes, but unexpected exits
3. **`log-sdk-message!` lacks try/catch** - If logging fails, reader loop crashes
4. **Alias error is confusing** - `(require '[x :as alias])` throws if alias exists, returns as error
5. **TodoWrite hooks firing incorrectly** - Matcher `(Edit|Write)` matches `TodoWrite` via substring

---

### Implementation

#### 1c.1 Defensive Logging ✅ Testable

**Problem:** `log-sdk-message!` at claude.clj:668 is not wrapped in try/catch. If it throws, the entire agent reader loop fails.

**File:** `src/seon/ai/claude.clj`

```clojure
;; Before (line 668):
(agent-log/log-sdk-message! agent-logger msg)

;; After:
(try
  (agent-log/log-sdk-message! agent-logger msg)
  (catch Exception e
    (log/warn e "Failed to log SDK message" {:session-id id :msg-type msg-type})))
```

**Test:** Agent continues even if log file is unwritable.

#### 1c.2 Fix Hook Matcher ✅ Testable

**Problem:** Hook matcher `(Edit|Write)` matches `TodoWrite` via substring.

**File:** `.claude/settings.json`

```json
// Before:
"matcher": "(Edit|Write)"

// After - use anchors for exact match:
"matcher": "^(Edit|Write)$"
```

**Test:** TodoWrite no longer triggers seon-hook.

#### 1c.3 Graceful Alias Handling (Optional)

**Problem:** `(require '[x :as alias])` throws if alias exists. This returns as "Error" which may confuse agents.

**Options:**
1. Document this as expected behavior in AGENT.md
2. Add helper `(safe-require ...)` that checks first
3. Modify MCP eval to catch and return as warning

**Recommendation:** Start with documentation. If agents repeatedly struggle, add helper.

---

### Test Checklist

```
1c.1 [ ] log-sdk-message! wrapped in try/catch
1c.2 [ ] TodoWrite does not trigger seon-hook
1c.3 [ ] AGENT.md documents alias behavior (if needed)
```

---

## Phase 1 (Original): Viewer System + Basic Introspection

**Goal:** Render any Clojure value as styled Hiccup. Introspect namespaces generically.

**Duration:** 2-3 days

### 1.1 Value Viewer (Multimethod Dispatch)

```clojure
(ns seon.ui.viewer)

;; Dispatch on type or ::viewer metadata
(defmulti render-value (fn [v _opts] (or (::viewer (meta v)) (type v))))

(defmethod render-value :default [v _] [:code (pr-str v)])
(defmethod render-value nil [_ _] [:span.text-gray-400 "nil"])
(defmethod render-value Boolean [v _] [:span.text-blue-600 (str v)])
(defmethod render-value Number [v _] [:span.text-green-600 (str v)])
(defmethod render-value String [v _] [:span.text-amber-600 (pr-str v)])
(defmethod render-value clojure.lang.Keyword [v _] [:span.text-purple-600 (str v)])
(defmethod render-value clojure.lang.IPersistentMap [m opts] ...)
(defmethod render-value clojure.lang.IPersistentVector [v opts] ...)
```

### 1.2 Namespace Introspection (Generic, Runtime)

```clojure
(ns seon.ns.introspect)

(defn introspect
  "Introspect ANY loaded namespace at runtime."
  [ns-sym]
  (when-let [ns (find-ns ns-sym)]
    (let [publics (ns-publics ns)]
      {:ns-name ns-sym
       :doc (-> ns meta :doc)
       :functions (->> publics
                       (filter (fn [[_ v]] (fn? (var-get v))))
                       (map (fn [[k v]] {:name k
                                         :arglists (:arglists (meta v))
                                         :doc (:doc (meta v))})))
       :vars (->> publics
                  (filter (fn [[_ v]] (not (fn? (var-get v)))))
                  (filter (fn [[_ v]] (not (instance? clojure.lang.IAtom (var-get v)))))
                  (map (fn [[k v]] {:name k :value (var-get v)})))
       :atoms (->> publics
                   (filter (fn [[_ v]] (instance? clojure.lang.IAtom (var-get v))))
                   (map (fn [[k v]] {:name k :atom v})))
       :requires (ns-aliases ns)})))
```

### 1.3 Dynamic Route Handler

```clojure
;; Route: GET /{namespace}
(defn namespace-handler [{:keys [path-params query-params]}]
  (let [ns-sym (symbol (:namespace path-params))
        session-id (:id query-params)]
    (if-let [data (introspect ns-sym)]
      (render-namespace-view data session-id)
      {:status 404 :body "Namespace not found"})))
```

### Test

```clojure
;; In REPL:
(introspect 'seon.ai.claude)
;; => {:ns-name seon.ai.claude, :functions [...], :atoms [...], ...}

;; In browser:
;; GET /seon.ai.claude -> renders basic HTML
```

### Deliverables

- [ ] `seon.ui.viewer` namespace with multimethod dispatch
- [ ] `seon.ns.introspect/introspect` function (runtime, generic)
- [ ] Route handler at `/{namespace}`
- [ ] Basic unstyled HTML output

---

## Phase 2: Expand/Collapse + Styling

**Goal:** Collections expand/collapse, styled with Tailwind.

**Duration:** 2 days

### 2.1 Datastar Expand/Collapse

```clojure
;; Map viewer with expand/collapse
(defmethod render-value clojure.lang.IPersistentMap [m opts]
  (let [id (gensym "map")]
    [:div {:data-signals (str "{" id ": false}")}
     [:span.cursor-pointer
      {:data-on-click (str "$" id " = !$" id "}")}
      "{"]
     ;; Collapsed: show count
     [:span {:data-show (str "!$" id)}
      [:span.text-gray-400 (str (count m) " entries")]]
     ;; Expanded: show entries
     [:div.pl-4 {:data-show (str "$" id)}
      (for [[k v] m]
        [:div.flex.gap-2
         (render-value k opts)
         (render-value v opts)])]
     "}"]))
```

### 2.2 Truncation for Large Values

```clojure
(defn render-with-truncation [coll {:keys [limit] :or {limit 20}}]
  (let [total (count coll)
        visible (take limit coll)]
    [:div
     (for [item visible] (render-value item {}))
     (when (> total limit)
       [:button.text-blue-500
        {:data-on-click "..."}
        (str "+" (- total limit) " more")])]))
```

### Test

```clojure
;; In browser:
;; - Click on `{` to expand/collapse maps
;; - Large collections show "20 items" collapsed, expand on click
```

### Deliverables

- [ ] Expand/collapse for maps, vectors, sets
- [ ] Truncation with "show more" for large collections
- [ ] Tailwind styling (colors match Clerk/Portal conventions)
- [ ] Function cards with docstring expand

---

## Phase 3: Malli Schema Viewer (Runtime)

**Goal:** Query and render Malli schemas for any namespace.

**Duration:** 1-2 days

### 3.1 Schema Discovery (Runtime)

Malli schemas are in `malli.core/default-registry`. Query at runtime:

```clojure
(ns seon.ns.introspect)

(defn schemas-for-namespace
  "Find all Malli schemas whose keyword namespace matches ns-sym."
  [ns-sym]
  (let [ns-str (str ns-sym)
        registry (malli.registry/schemas malli.core/default-registry)]
    (->> registry
         (filter (fn [[k _]]
                   (and (keyword? k)
                        (= (namespace k) ns-str))))
         (map (fn [[k schema]]
                {:name k
                 :schema (malli.core/form schema)
                 :type (malli.core/type schema)})))))

;; Usage:
(schemas-for-namespace 'seon.ai)
;; => [{:name :seon.ai/message :schema [:map ...]} ...]
```

### 3.2 Schema Viewer with Clickable Refs

```clojure
(defmethod render-value ::schema [{:keys [name schema]} opts]
  [:div.border.rounded.p-2
   [:div.font-bold (str name)]
   [:pre.text-sm.mt-2
    (render-schema-form schema opts)]])

(defn render-schema-form [form opts]
  (cond
    ;; Keyword refs are clickable
    (keyword? form)
    [:a.text-purple-600.hover:underline
     {:href (str "/" (namespace form) "?schema=" (name form))}
     (str form)]

    ;; Recurse into vectors
    (vector? form)
    [:span "[" (interpose " " (map #(render-schema-form % opts) form)) "]"]

    :else (pr-str form)))
```

### Test

```clojure
;; GET /seon.ai.claude shows schemas section
;; Click on ::ai/node navigates to that schema
```

### Deliverables

- [ ] `schemas-for-namespace` function (runtime query)
- [ ] Schema viewer component
- [ ] Clickable cross-references between schemas

---

## Phase 4: XTDB Entity Browser (Generic)

**Goal:** Browse XTDB entities for any namespace/session.

**Duration:** 2-3 days

### 4.1 Table Discovery (Runtime)

XTDB v2 - discover tables dynamically, no hardcoding:

```clojure
(defn list-tables
  "List all tables in an XTDB node."
  [node]
  (db/q node "SELECT table_name FROM information_schema.tables"))

(defn table-columns
  "Get columns for a table."
  [node table-name]
  (db/q node
    "SELECT column_name FROM information_schema.columns WHERE table_name = ?"
    [table-name]))

(defn table-row-count
  "Count rows in a table."
  [node table-name]
  (-> (db/q node (str "SELECT COUNT(*) as cnt FROM " table-name))
      first :cnt))
```

### 4.2 Entity Viewer with Forward Refs

```clojure
(defn render-entity [node entity]
  [:div.border.rounded.p-2
   (for [[k v] entity]
     [:div.flex.gap-2
      [:span.text-purple-600 (str k)]
      (if (looks-like-entity-id? v)
        ;; Clickable link to referenced entity
        [:a.text-blue-500.hover:underline
         {:href (str "?entity=" v)}
         (str v)]
        (render-value v {}))])])

(defn looks-like-entity-id?
  "Heuristic: UUIDs, strings starting with known prefixes, etc."
  [v]
  (or (uuid? v)
      (and (string? v) (re-matches #"^[a-z]+-[a-f0-9]+" v))))
```

### 4.3 Bidirectional References

Find "what references this entity":

```clojure
(defn references-to
  "Find all entities that reference target-id in any column."
  [node target-id]
  (let [tables (list-tables node)]
    (->> tables
         (mapcat (fn [{:keys [table_name]}]
                   (let [cols (table-columns node table_name)]
                     (->> cols
                          (mapcat (fn [{:keys [column_name]}]
                                    (let [results (db/q node
                                                   (str "SELECT xt$id FROM " table_name
                                                        " WHERE " column_name " = ?")
                                                   [target-id])]
                                      (when (seq results)
                                        [{:table table_name
                                          :column column_name
                                          :count (count results)}]))))))))
         (into []))))
```

### Test

```clojure
;; GET /seon.ai.claude?id=e45gf shows:
;; - Tables in that session's DB with row counts
;; - Click entity -> see details with forward refs
;; - "Referenced by" section shows reverse refs
```

### Deliverables

- [ ] `list-tables`, `table-columns` (generic discovery)
- [ ] Entity viewer with clickable forward refs
- [ ] `references-to` for bidirectional navigation
- [ ] Integration with session-specific DBs

---

## Phase 5: Live Atom Updates

**Goal:** Atoms update in real-time via SSE.

**Duration:** 2-3 days

### 5.1 Watch Registry

```clojure
(ns seon.ui.live)

(defonce watch-registry (atom {}))

(defn watch-atom!
  "Watch an atom and push updates via SSE. Returns cleanup fn."
  [session-id atom-var selector]
  (let [watch-key (keyword "seon.ui.live" session-id)
        debounce-ms 100
        last-sent (atom nil)]

    ;; Initial render
    (sse/merge-fragment session-id selector
      (render-value @atom-var {}))

    ;; Watch with debounce
    (add-watch atom-var watch-key
      (fn [_ _ _ new-val]
        (future
          (Thread/sleep debounce-ms)
          (when (and (= @atom-var new-val)
                     (not= @last-sent new-val))
            (reset! last-sent new-val)
            (sse/merge-fragment session-id selector
              (render-value new-val {}))))))

    ;; Cleanup
    (fn [] (remove-watch atom-var watch-key))))
```

### 5.2 Atom Viewer Component

```clojure
(defn atom-viewer [atom-var]
  (let [id (str "atom-" (hash atom-var))]
    [:div.border.rounded.p-2
     [:div.flex.items-center.gap-2
      [:span.font-bold (str (:name (meta atom-var)))]
      [:span.text-xs.text-green-500 "● live"]]
     [:div {:id id}
      (render-value @atom-var {})]]))
```

### Test

```clojure
;; In browser: view namespace with atom
;; In REPL: (swap! some-atom assoc :new-key "value")
;; Browser updates within 100ms
```

### Deliverables

- [ ] `watch-atom!` with debounce
- [ ] SSE integration for pushing updates
- [ ] Visual "live" indicator
- [ ] Cleanup on session disconnect

---

## Phase 6: Orchestrator Dashboard

**Goal:** `/` shows namespace tree and active sessions.

**Duration:** 2 days

### 6.1 Namespace Discovery

```clojure
(defn all-seon-namespaces
  "Find all loaded seon.* namespaces."
  []
  (->> (all-ns)
       (filter #(str/starts-with? (str (ns-name %)) "seon."))
       (map ns-name)
       (sort)))

(defn namespace-tree
  "Group namespaces into hierarchical tree."
  [namespaces]
  ;; seon.ai.claude -> {:seon {:ai {:claude {:_ns 'seon.ai.claude}}}}
  ...)
```

### 6.2 Dashboard View

```clojure
(defn dashboard-handler [_request]
  (let [namespaces (all-seon-namespaces)
        tree (namespace-tree namespaces)
        sessions (session/list-agent-sessions)]
    (render-dashboard {:tree tree :sessions sessions})))
```

### Deliverables

- [ ] `/` route with dashboard
- [ ] Namespace tree (expandable)
- [ ] Active sessions panel
- [ ] Click to navigate

---

## Phase 7: Custom Renderers

**Goal:** Namespaces can override default rendering.

**Duration:** 2 days

### 7.1 Render Function Convention

If a namespace's ctx atom contains `:seon.ui/render-fn`, use it:

```clojure
;; In agent code for seon.trading namespace:
(swap! *ctx* assoc :seon.ui/render-fn 'seon.trading/render-dashboard)

;; The render function:
(defn render-dashboard
  "Custom renderer for trading namespace.

   Request:
     {:view-mode :tile | :half | :full
      :session-id \"e45gf\" | nil
      :ctx @*ctx*
      :db <xtdb-connection>}

   Returns: Hiccup HTML"
  [{:keys [view-mode session-id ctx db]}]
  (case view-mode
    :tile  (render-tile ctx)
    :half  (render-half-view ctx db)
    :full  (render-full-dashboard ctx db)))
```

### 7.2 View Modes

| Mode | Use Case | Typical Size |
|------|----------|--------------|
| `:tile` | Dashboard mini-view | 200x150 px |
| `:half` | Side-by-side comparison | 50% viewport |
| `:full` | Dedicated view | Full viewport |

### 7.3 Fallback Chain

```
1. Check ctx for :seon.ui/render-fn -> use custom renderer
2. Otherwise -> use default introspection renderer
```

### Deliverables

- [ ] View mode detection and passing
- [ ] Custom renderer lookup from ctx
- [ ] Fallback to default renderer
- [ ] Documentation for writing custom renderers

---

## Phase 8 (Future): Tile System / Window Management

**Goal:** Drag-and-drop tile management, persistent layouts.

**Lower priority** - the basic dashboard covers 80% of use cases.

- Tile size configuration
- Drag-and-drop reordering
- Layout persistence in XTDB
- Eventually: floating windows, minimize/maximize

---

## Technical Constraints

- **Datastar/SSE** - Use existing patterns from agent-observatory
- **Tailwind CSS** - Consistent with current styling
- **No external JS frameworks** - Keep it simple, Datastar handles reactivity
- **REPL-friendly** - All introspection functions usable from REPL
- **Session isolation** - Session views only see their own data

---

## Success Criteria

1. **Phase 1:** Navigate to `/seon.ai.claude` and see functions, vars, atoms listed
2. **Phase 2:** Click on `{` to expand/collapse maps in the viewer
3. **Phase 3:** Navigate to `/seon.ai.claude` and see Malli schemas for that namespace
4. **Phase 4:** Browse XTDB entities, click ID to navigate, see "Referenced by" section
5. **Phase 5:** View atom in browser, change in REPL, see browser update within 100ms
6. **Phase 6:** Navigate to `/` and see tree of all seon.* namespaces
7. **Phase 7:** Set `:seon.ui/render-fn` in ctx, see custom view render

---

## Dependencies

- **agent-observatory** - Phase 2-3 builds on XTDB browser and SSE patterns
- **seon.schema** - Schema introspection depends on registry
- **seon.orchestrator.session** - Session-aware views need session system

---

## Out of Scope (This PRD)

- Multi-user authentication
- Remote access / public URLs
- Mobile-optimized layouts
- Namespace creation UI (orchestrator handles this via REPL)

---

## Future Vision

Once this is working:

1. **Live coding** - Watch namespace update as agent writes code
2. **Entity relationships** - Visualize how namespaces connect via requires
3. **Time travel** - XTDB bitemporal queries to see past states
4. **Agent marketplace** - Share namespace "apps" with others
5. **Voice control** - "Show me the trading dashboard"

---

## Open Questions

1. **Namespace filtering** - Show all `seon.*` or also user namespaces?
2. **Launch session UX** - Button on read-only view? Or separate action?
3. **Tile persistence** - Store in orchestrator ctx or separate XTDB table?

---

## Resources to Study

| Resource | What's There |
|----------|--------------|
| `src/seon/web/handlers.clj` | Existing route patterns |
| `src/seon/orchestrator/session.clj` | Session lifecycle |
| `src/seon/schema.clj` | Schema registry |
| `docs/prds/agent-observatory/` | SSE patterns, Datastar usage |
| `reference-code/datastar-clojure/` | Datastar SDK |

---

## Research Documents (Completed)

| Document | Contents |
|----------|----------|
| `research/clerk-research.md` | Clerk viewer architecture, why we're building our own |
| `research/viewer-architecture.md` | Deep dive on Portal, Reveal, XTDB Inspector patterns |
| `research/chatgpt-research.md` | Surface-level overview (less useful) |

## Reference Code (Git Submodules)

| Repository | Location | What to Study |
|------------|----------|---------------|
| Portal | `reference-code/portal/` | Watch mechanism, datafy/nav, lazy loading |
| Reveal | `reference-code/reveal/` | Multimethod dispatch, annotation threading |
| Clerk | `reference-code/clerk/` | Viewer predicates, pagination |
| XTDB Inspector | `reference-code/xtdb-inspector/` | Reverse lookup queries, entity browser |
