# PRD: Agent Observatory
## Status: COMPLETE — Observatory UI at /agents with per-agent logging and live message streaming

**Status:** Mostly Complete (Phase 2.3 Remaining)
**Priority:** Medium
**Branch:** feature/agent-isolation → merged to main
**Last Updated:** 2026-02-10
**Last Audit:** 2026-02-10 by agent 2564

---

## Current State

### ✅ Fully Working

**Phase 1: Per-agent logging** - COMPLETE
- Log files created at `logs/agents/{session-id}.log`
- Real-time `tail -f` works perfectly
- Format: ISO timestamp, pipe-delimited, structured events
- Handles: LAUNCH, MESSAGE, TOOL, RESULT, COMPLETE, ERROR
- SDK message parsing correctly extracts tool calls and results
- Tests pass: `test/seon/ai/agent/log_test.clj`

**Phase 2.1: Agent list view** - COMPLETE
- `/agents` route shows all running + recent completed agents
- SSE streaming with 2-second polling
- Sorting by most recent activity (uses XTDB message timestamps)
- "Show/Hide Completed" toggle working
- Status badges with "stuck" detection (>2 min no activity)
- Click row to drill into detail

**Phase 2.2: Agent detail view** - COMPLETE
- `/agents/:id` shows full message history from XTDB
- ID mapping works correctly: agent session-id (4-char hex) → AI session-id (ses-xxx)
- Initial context (task prompt, reference files, agent instructions) displayed
- Messages rendered with tool call/result pairing
- Tool-specific rendering (Edit shows diff, Bash shows command, REPL shows code)
- Markdown rendering for assistant text
- Auto-scroll with user-scroll-up detection (MutationObserver)
- Progress summary for running agents
- Context token usage bar

**SSE Infrastructure** - COMPLETE
- `patch-elements` supports `:mode` (append, prepend, etc.) and `:selector`
- `execute-script` for client-side JS execution
- Hash-based change detection prevents redundant updates
- Streaming brotli compression

**Session Isolation** - COMPLETE
- Each agent has isolated nREPL (unique port) and XTDB database
- Agents cannot use orchestrator REPL
- `seon.ai.agent/shutdown-all!` prevents core.async corruption

### 🔶 Minor Enhancements (Not Bugs)

1. **Uses polling, not live SSE streaming** - Detail view polls at 1 second intervals rather than using append-mode SSE streaming. Works well, but research for true streaming is in `streaming-research.md`.

2. **Context bar hardcoded to 200K** - Assumes Claude Opus 4.5 context window. Could be dynamic based on model.

### ❌ Not Started

**Phase 2.3: XTDB Entity Browser** - NOT STARTED
- `/db` route to browse XTDB entities
- Table list, entity detail view
- Low priority - not critical for agent observability

### Previously Reported Bugs (Now Fixed)

The previous PRD noted these bugs:
1. ~~**Log file not found** - Detail page uses wrong ID~~ **FIXED**: `find-ai-session-id` correctly maps agent session-id to AI session-id
2. ~~**Toggle may not work**~~ **FIXED**: Toggle works correctly, triggers `sse/refresh-all!`

### Files Created
- `src/seon/ai/agent/log.clj` - Per-agent logging (327 lines)
- `src/seon/ai/agent/views.clj` - Tool-specific renderers (1491 lines)
- `src/seon/web/agents.clj` - List and detail handlers (1274 lines)
- `test/seon/ai/agent/log_test.clj` - Tests (206 lines)

---

## Problem

Monitoring agents is awkward. Currently we:
- Poll via REPL (`agent/agents {}`, checking status atoms)
- Grep through log files
- Manually read PRD lab notebooks

We need real-time visibility into agent activity without burning orchestrator tokens.

---

## Goals

1. **Dedicated agent log** - Single file showing agent activity in real-time
2. **Web UI dashboard** - See all sessions, drill into details
3. **Live message stream** - Watch an agent work in real-time
4. **XTDB entity browser** - Inspect persisted data

---

## Phase 1: Structured Agent Log

Create a dedicated log file for agent activity that's easy to tail.

**Files:** `logs/agents/{agent-id}.log` - one file per agent

**Format:** One line per event, structured for both humans and parsing:

```
2026-01-20T13:23:20Z | LAUNCH  | seon.session-analytics | port=7892
2026-01-20T13:23:21Z | MESSAGE | assistant | "I'll start by exploring..."
2026-01-20T13:23:25Z | TOOL    | eval | (xt/q node "SELECT...")
2026-01-20T13:23:26Z | RESULT  | eval | [{:column-name "_id"}...]
2026-01-20T13:23:30Z | MESSAGE | assistant | "Found the schema..."
2026-01-20T13:24:00Z | HOOK    | Write | tests=pass | gemini=pending
2026-01-20T13:25:00Z | COMPLETE| cost=$0.45 | messages=84 | duration=100s
```

**Implementation:**
- Each agent gets its own log file: `logs/agents/f602.log`
- Add logging in `seon.ai.claude/launch-agent!` reader loop
- Log: launches, messages (truncated), tool calls, hook feedback, completion
- Use timbre with dedicated appender per agent

**Usage:**
```bash
tail -f logs/agents/f602.log           # watch specific agent
ls -lt logs/agents/                     # see recent agents
tail -f logs/agents/*.log              # watch all agents
```

---

## Phase 2: Web Dashboard

Add agent observatory to existing web UI at `/agents`.

### 2.1 Agent List View

Shows all agents (running + recent completed):

```
┌─────────────────────────────────────────────────────────────┐
│ Agent Observatory                                    [refresh] │
├─────────────────────────────────────────────────────────────┤
│ ID   │ Namespace            │ Status    │ Messages │ Cost   │
│──────│──────────────────────│───────────│──────────│────────│
│ f602 │ seon.session-analytics│ ● running │ 84       │ $0.32  │
│ f2cb │ seon.hook-test       │ ✓ complete│ 13       │ $0.08  │
│ 3856 │ seon.e2e-test        │ ✓ complete│ 7        │ $0.07  │
└─────────────────────────────────────────────────────────────┘
```

Click row → drill into agent detail.

### 2.2 Agent Detail View

Live-updating view of single agent:

```
┌─────────────────────────────────────────────────────────────┐
│ Agent f602 - seon.session-analytics          ● running      │
│ Started: 13:23:20 | Port: 7892 | Messages: 84 | Cost: $0.32 │
├─────────────────────────────────────────────────────────────┤
│ [interrupt]                                                 │
├─────────────────────────────────────────────────────────────┤
│ 13:23:21 assistant                                          │
│   I'll start by exploring the database schema...            │
│                                                             │
│ 13:23:25 tool:eval                                          │
│   (xt/q node "SELECT column_name FROM...")                  │
│   → [{:column-name "_id"}, {:column-name "cost_usd"}...]    │
│                                                             │
│ 13:23:30 assistant                                          │
│   Found the schema. ai_sessions has: _id, cost_usd...       │
│                                                             │
│ 13:24:00 hook:Write                                         │
│   ✓ tests pass | ✓ gen tests | ⏳ gemini review             │
│                                                             │
│ ▼ [auto-scroll enabled]                                     │
└─────────────────────────────────────────────────────────────┘
```

**Implementation:**
- SSE endpoint streaming messages for an agent
- Datastar for reactive updates
- Uses existing `agent/tail` channel

### 2.3 XTDB Entity Browser

Browse persisted entities at `/db`:

```
┌─────────────────────────────────────────────────────────────┐
│ XTDB Browser                                                │
├──────────────┬──────────────────────────────────────────────┤
│ Tables       │ ai_sessions                                  │
│ ────────────│──────────────────────────────────────────────│
│ ai_sessions │ ID: ses-9d781e29-89be-468b-b6a0-3beddfdc5e70 │
│ ai_messages │ Status: completed                             │
│ edit_event  │ Namespace: seon.session-analytics             │
│ review_event│ Cost: $0.45                                   │
│             │ Started: 2026-01-20T13:23:20Z                 │
│             │ Ended: 2026-01-20T13:25:00Z                   │
│             │                                               │
│             │ [View Messages] [View Raw JSON]               │
└─────────────┴──────────────────────────────────────────────┘
```

---

## Phase 3: Orchestrator Integration

Make it easy for orchestrator (me) to monitor without REPL polling.

**Option A: Status command**
```clojure
;; Quick status check that returns summary
(agent/status)
;; => {:running [{:id "f602" :ns "seon.session-analytics" :messages 84}]
;;     :completed-today 5
;;     :total-cost-today 2.34}
```

**Option B: Notification on completion**
- When agent completes, log includes summary
- Could integrate with system notifications

---

## Implementation Notes

### Agent Instructions

When implementing the web UI, agents should use the `datastar-web-ui` skill to understand our Datastar/SSE patterns. Example:

```
/datastar-web-ui
```

This skill documents our Tailwind + Datastar + SSE patterns for building reactive UIs.

### Existing Infrastructure

We already have:
- `seon.ai.agent/agents` - list all agents
- `seon.ai.agent/tail` - returns message channel
- `seon.ai.agent/get-agent` - get handle by ID
- `seon.web.sse` - SSE infrastructure (needs extension for `mode append`)
- Datastar v1.0.0-RC.6 via CDN
- Datastar patterns in `seon.web.handlers`

### SSE Extension Needed

Our `patch-elements` only supports `outer` mode. Extend to support all modes:

```clojure
(defn patch-elements
  "Build a datastar SSE event for patching elements.
  Options:
  - :selector - CSS selector for target element
  - :mode - :outer, :inner, :append, :prepend, :before, :after, :replace, :remove"
  [{:keys [selector mode event-id]} elements]
  ...)
```

See `docs/prds/agent-observatory/datastar-comparison.md` for full implementation.

### Scroll Management

`data-scroll-into-view` is a **Datastar Pro (commercial) feature**. Use alternatives:

1. **CSS** - `flex-direction: column-reverse` on container (auto-scroll to bottom)
2. **JS** - `execute-script!` with `scrollIntoView()` or `scrollTop` assignment
3. **Signal tracking** - Detect if user scrolled up, only auto-scroll if at bottom

### Streaming Strategy

From `streaming-research.md`:
- Use `mode append` for new messages (no flicker, no scroll interference)
- Buffer messages with sliding-buffer(50)
- Batch for 50-100ms windows before sending
- Use message IDs for reconnection replay

### New Components Needed

1. `seon.ai.agent.log` - Structured per-agent logging to `logs/agents/{id}.log`
2. `seon.web.handlers/agents` - Dashboard handlers
3. `seon.web.handlers/db-browser` - Entity browser handlers
4. Routes at `/agents` and `/db`
5. Extension to `seon.web.sse/patch-elements` for `:mode` and `:selector`

---

## Success Criteria

- [x] `tail -f logs/agents/{id}.log` shows real-time agent activity (Phase 1)
- [x] Web UI at `/agents` shows all agents (Phase 2.1)
- [x] Can click into agent and see full message history (Phase 2.2)
- [x] Initial context displayed (task, files, instructions) (Phase 2.2)
- [x] Tool calls rendered with tool-specific formatting (Phase 2.2)
- [~] Live SSE streaming for agent detail view (works via polling, not true streaming)
- [ ] Can browse XTDB entities at `/db` (Phase 2.3 - not started)
- [x] Orchestrator can monitor via background `tail -f` + TaskOutput pattern

---

## Next Steps (For Future Agents)

### If Assigned to This Feature

1. **Phase 2.3: XTDB Browser** (optional)
   - Add `/db` route
   - List tables from XTDB
   - Show entities with pagination
   - Use existing SSE patterns from `/agents`

2. **True SSE Streaming** (optional optimization)
   - Research is complete in `streaming-research.md`
   - Use append mode instead of full re-render
   - Bridge `agent/tail` channel to SSE
   - Would reduce latency from 1s polling to near-instant

3. **Dynamic Context Window** (trivial)
   - Query model info to get context window size
   - Currently hardcoded to 200K

### Architecture Notes

The codebase is well-organized:
- `seon.web.agents` - HTTP handlers and HTML rendering
- `seon.ai.agent.views` - Tool-specific multimethod renderers
- `seon.ai.agent.log` - File logging (separate from XTDB persistence)
- `seon.web.sse` - SSE infrastructure with full mode support

All data comes from XTDB via `seon.ai` namespace functions. The UI is purely a view layer.

---

## Out of Scope (This PRD)

- Multi-user support
- Authentication
- Historical analytics/charts
- Agent replay/debugging

---

## Future Vision: Namespace Endpoints

Beyond this PRD, the broader vision is that each namespace gets its own endpoint that auto-wires when code is written:

```
/ns/seon.ai.claude     → renders namespace: code, vars, atoms, xtdb entities
/ns/seon.trading       → same pattern
/agents                → this PRD
/db                    → entity browser
```

Each namespace view would show:
- Source code (syntax highlighted)
- Public vars and their values
- Atoms and their current state
- Related XTDB entities for that namespace's database

This creates a live, introspectable system where you can see everything. But that's a larger undertaking - this PRD focuses on agent observability first.

---

## Open Questions (Resolved)

1. ~~How much message content to show in logs?~~ **Resolved:** Full content in log files, UI handles truncation with expand/collapse
2. ~~Should we persist agent metadata separately from AI sessions?~~ **Resolved:** No, registry provides running agents, XTDB provides completed sessions
3. ~~SSE or WebSocket for live updates?~~ **Resolved:** SSE with polling (true streaming researched but not implemented)

---

## Research Documents

- `exploration.md` - Current web UI state assessment
- `streaming-research.md` - Datastar patterns, channel bridging, scroll management
- `datastar-comparison.md` - Hyperlith vs official SDK comparison

## Reference Code

- `reference-code/datastar/` - Official Datastar repo (JS library + examples)
- `reference-code/datastar-clojure/` - Official Clojure SDK
- `reference-code/hyperlith/` - Hyperlith (Clojure Datastar patterns)
