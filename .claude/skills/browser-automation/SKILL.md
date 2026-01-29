---
name: browser-automation
description: "Multi-agent browser coordination for Seon. Use when debugging web UI in browser, testing Datastar/SSE behavior, or verifying UI changes. Provides tab ownership patterns so agents don't step on each other's browser tabs."
---

# Browser Automation for Seon Agents

You have Chrome MCP tools (`mcp__claude-in-chrome__*`) for browser automation. This skill covers **multi-agent coordination** - how to share browser resources without conflicts.

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
