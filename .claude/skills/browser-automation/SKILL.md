---
name: browser-automation
description: "Browser automation with Claude in Chrome MCP. Use when debugging web UI, testing in browser, inspecting network requests, checking console errors, or verifying Datastar/SSE behavior. Use when you need to click buttons, fill forms, or take screenshots."
---

# Browser Automation Patterns

## Critical: Always Start Here

```
1. tabs_context_mcp        → Get tab IDs (REQUIRED first step)
2. navigate                → Go to URL
3. computer (screenshot)   → See current state
4. find / read_page        → Get element refs
5. computer (left_click)   → Interact
```

## Common Workflow: Debug Why Button Doesn't Work

```
1. tabs_context_mcp (createIfEmpty: true)     → Get tabId
2. navigate to page                            → Load the page
3. computer screenshot                         → See the UI
4. read_network_requests (sets up tracking)    → Start monitoring
5. find "the button"                           → Get ref
6. computer left_click ref                     → Click it
7. read_network_requests                       → Did POST fire?
8. read_console_messages                       → Any JS errors?
9. javascript_tool                             → Inspect DOM
```

## Key Gotchas

| Issue | Solution |
|-------|----------|
| "No tab ID" error | Call `tabs_context_mcp` first |
| Element ref not found | Refs go stale after navigation - re-find |
| No network requests captured | Call `read_network_requests` BEFORE the action |
| Can't screenshot chrome:// URLs | Navigate to a real page first |
| Need to inspect element attributes | Use `javascript_tool` with DOM queries |

## Tool Quick Reference

| Tool | When to Use |
|------|-------------|
| `tabs_context_mcp` | First! Get available tab IDs |
| `navigate` | Go to URL or back/forward |
| `computer` | screenshot, left_click, type, scroll |
| `find` | Find element by description ("login button") |
| `read_page` | Get accessibility tree (all elements) |
| `javascript_tool` | Run JS in page context, inspect DOM |
| `read_network_requests` | See XHR/fetch calls |
| `read_console_messages` | See console.log/error |
| `form_input` | Set form field values |

## Inspect Element Attributes

```javascript
// Get button's data attributes
document.querySelector('button').outerHTML

// Check all data-on:click elements
Array.from(document.querySelectorAll('[data-on\\:click]')).map(el => el.outerHTML)

// Check if Datastar loaded
typeof window.Datastar !== 'undefined' ? 'loaded' : 'NOT loaded'
```

## Debug Datastar SSE

1. Check button has `data-on:click` (colon, not hyphen!)
2. Check network for POST request on click
3. Check network for SSE connection (pending request to same URL)
4. Check console for JS errors

## Example: Verify Page Works

```
// 1. Setup
tabs_context_mcp (createIfEmpty: true) → tabId: 12345

// 2. Navigate
navigate url="http://localhost:8080/mypage" tabId=12345

// 3. Screenshot
computer action="screenshot" tabId=12345

// 4. Find and click
find query="Submit button" tabId=12345 → ref_1
computer action="left_click" ref="ref_1" tabId=12345

// 5. Verify
computer action="screenshot" tabId=12345  → See result
```

## When Debugging UI Issues

1. **Always screenshot first** - See what the user sees
2. **Check network tab** - Is the request firing?
3. **Check console** - Any JS errors?
4. **Inspect DOM** - Are attributes correct?
5. **For Datastar** - Invoke `datastar-web-ui` skill too!
