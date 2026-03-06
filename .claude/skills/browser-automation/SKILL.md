---
name: browser-automation
description: "Multi-agent browser coordination for Seon. Use when debugging web UI in browser, testing Datastar/SSE behavior, or verifying UI changes. Provides tab ownership patterns so agents don't step on each other's browser tabs."
---

# Browser Automation for Seon Agents

You have Chrome MCP tools (`mcp__claude-in-chrome__*`) for browser automation. This skill covers **multi-agent coordination** - how to share browser resources without conflicts.

**Tool names:** This skill uses shorthand names (e.g., `navigate`, `computer screenshot`). The actual MCP tool names are prefixed with `mcp__claude-in-chrome__` (e.g., `mcp__claude-in-chrome__navigate`).

## Tab Ownership Protocol

Agents share the browser. Follow these rules:

1. **Get context first:** `tabs_context_mcp` shows existing tabs
2. **Create your own tab:** Use `tabs_create_mcp`, don't reuse tabs you didn't create
3. **Track your tab ID:** Store it for the session, re-find if lost
4. **Leave tabs open:** Let orchestrator or user clean up (they may want to inspect)

```
# Session start
tabs_context_mcp → see what exists
tabs_create_mcp → get YOUR tab (remember this ID)
navigate to your URL
```

## Seon URLs

| Page | Path | Description |
|------|------|-------------|
| Dashboard | `/` | Main dashboard (root) |
| Agent Observatory | `/agents` | Agent list and status |
| Agent Detail | `/agents/{agent-id}` | Single agent view |
| Flow Monitor | `/flows` | core.async flow status |
| Log Viewer | `/logs` | Application logs |
| Namespace Page | `/ns/{namespace}` | Per-namespace view (e.g., `/ns/seon.trading`) |
| Function Call | `/ns/{namespace}/{function}` | POST to call, GET to read |
| Health Check | `/api/health` | JSON health endpoint |

**Base URLs:**
- HTTP: `http://localhost:8080`
- HTTPS (Caddy): `https://localhost:3030`

## Quick Workflow: Verify UI Change

After editing a handler or component:

```
1. tabs_create_mcp              → Your tab
2. navigate to page             → Load it
3. computer screenshot          → See current state
4. find "the element"           → Get ref
5. computer left_click ref      → Interact
6. read_network_requests        → Check POST fired
7. read_console_messages        → Check for errors
```

## Debugging Datastar SSE

For SSE issues, also invoke `/datastar-web-ui` for attribute patterns.

```
1. Check button has `data-on:click` (COLON not hyphen!)
2. read_network_requests → Look for POST on click
3. read_network_requests → Look for SSE connection (pending request)
4. read_console_messages pattern="error|Datastar"
```

## Inspect DOM

```javascript
// Via javascript_tool
document.querySelector('[data-on\\:click]').outerHTML
typeof window.Datastar !== 'undefined' ? 'loaded' : 'NOT loaded'
```

## Common Issues

| Problem | Solution |
|---------|----------|
| Tab ID invalid | Call `tabs_context_mcp` to get fresh IDs |
| Element ref stale | Refs invalidate on navigation - re-find |
| No network captured | Call `read_network_requests` BEFORE the action |
| Screenshot fails | Can't screenshot chrome:// URLs - navigate first |

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/web/routes.clj` | Route definitions |
| `src/seon/web/sse.clj` | SSE core |
| `src/seon/web/components.clj` | UI components |
