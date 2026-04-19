---
type: prd
status: completed
tags: [prd, archive]
---

# SSE Live Reload Investigation

## Status: COMPLETE — SSE live reload working via Datastar fragment merging

**Status:** In Progress
**Created:** 2026-01-29
**Branch:** feature/sse-live-reload (create from feature/namespace-ui)

## IMPORTANT: Agent Usage

**DO NOT use Seon's Clojure agents (`user/launch-agent!!`) for this investigation.**

The agents have a 100-turn limit that causes them to freeze mid-investigation. Use Claude Code's built-in Task tool with `subagent_type=Explore` or `subagent_type=Plan` instead.

---

## Problem Statement

Code changes to view renderers are not reflected in the browser without hard refresh. This has been an ongoing issue that multiple approaches have failed to solve.

**Observed behavior:**
- Edit a Clojure file (e.g., change "Agent Observatory" to "LIVE Observatory")
- Dev hook runs successfully, code reloads
- REPL confirms the new code is loaded (render functions return updated content)
- Browser does NOT show the change
- Hard refresh (Cmd+Shift+R) does NOT show the change
- Only server restart shows the change

---

## What Has Been Tried

### 1. Removing Handler Caches (This Session)

**Hypothesis:** Cached SSE handlers were holding stale closures.

**Changes made:**
- Removed `agent-detail-handlers` atom from `agents.clj`
- Removed `namespace-handlers` atom from `namespace.clj`
- Changed per-entity SSE handlers to create fresh handlers on each request

**Result:** Did not fix the issue. Fresh page loads still showed old content.

### 2. Manual Handler Recreation

**Test:** Manually called `alter-var-root` to recreate `agents-sse` handler in REPL.

**Result:** Did not fix the issue.

### 3. Var Reference Verification

**Test:** Verified that routes use var references (`#'agents/agents-sse`).

**Findings:**
- Routes DO use var references
- Server uses `#'routes/handler`
- The render function (`agents-sse-render`) uses var reference
- Calling render functions directly in REPL returns correct (updated) content

### 4. Hash Change Verification

**Test:** Previous agent (6302) tested if content hash changes after reload.

**Findings (from agent logs):**
- When testing directly: "Hash 1: 8be7c23d, Hash 2: 8be7c23d, Same hash? true"
- Later test: "Hash changed from 7ba80be9 to 4b3286c3"
- Inconsistent results suggest something intermittent

---

## Current Findings

### Browser JavaScript Errors

**CRITICAL:** The browser console shows repeated JavaScript syntax errors:

```
SyntaxError: Unexpected token ')' (line 1, position 55)
SyntaxError: Unexpected token '&' (line 6, position 52)

```

These errors repeat every ~6 seconds (matching SSE poll intervals).

**Possible cause:** The `on-load-js` string in `html.clj` contains `&` characters that get HTML-escaped to `&amp;` in attributes. When Datastar tries to evaluate this, it may be parsing the escaped version incorrectly.

The rendered HTML shows:

```html
data-init="@post(window.location.pathname + (window.location.search + &apos;&amp;u=&apos;).replace(/^&amp;/,&apos;?&apos;), {retryMaxCount: Infinity})"

```

**Unclear:** Whether these JS errors are the root cause or a symptom.

### SSE Request Flow

Verified via network inspector:
- GET /agents returns 200 (HTML page)
- POST /agents returns 200 (SSE stream)

The SSE request IS being made, but content may not be morphing correctly.

---

## Architecture Questions

### How Does Our SSE Implementation Work?

Location: `src/seon/web/sse.clj`

Key components:
1. `render-handler` - Creates an SSE handler that loops, calling render-fn
2. `do-render` - Renders content, hashes it, only sends if hash changed
3. `patch-elements` - Formats content as Datastar SSE event
4. Brotli compression over the SSE stream

### How Does Datastar Expect Content?

**NEEDS INVESTIGATION:** We need to understand:
1. What SSE event format does Datastar expect?
2. How does `datastar-patch-elements` event work?
3. What does `data-init` attribute do exactly?
4. Are we using the right Datastar version/API?

---

## Required Investigation: Datastar Deep Dive

### Tasks

1. **Check what Datastar code we have**

   ```bash
   # Look for datastar in reference-code
   ls -la reference-code/ | grep -i datastar

   # Check git submodules
   git submodule status

   ```

2. **Find Datastar's native Clojure implementation**
   - Search for official Datastar Clojure examples
   - Look at [starfederation/datastar](https://github.com/starfederation/datastar)
   - Find their recommended SSE patterns

3. **Compare our implementation to canonical**
   - How do they format SSE events?
   - How do they handle HTML escaping?
   - Do they use compression?
   - How do they handle reconnection?

4. **Test minimal reproduction**
   - Create simplest possible SSE + Datastar page
   - Verify if hot reload works in minimal case
   - Isolate whether issue is in our code or fundamental

---

## Files Changed (To Commit)

These changes were made during investigation but may or may not be correct:

1. `src/seon/web/agents.clj`
   - Removed handler cache
   - Changed header to "LIVE Observatory" (test change - revert or keep)
   - Added emoji to Read tool (test change - revert)

2. `src/seon/web/namespace.clj`
   - Removed handler cache
   - Removed `after-ns-reload` function

3. `src/seon/ai/agent/views.clj`
   - Added emoji to Read tool renderer (test change - revert)

---

## Open Questions

1. **Is the HTML escaping of `&` breaking Datastar's expression parser?**

2. **Are we using the correct Datastar SSE event format?**

3. **Does Datastar's morphing algorithm require specific HTML structure?**

4. **Is brotli compression interfering with SSE parsing?**

5. **Should we be using a different approach entirely?**
   - Server-side rendering without SSE?
   - Different SSE library?
   - htmx instead of Datastar?

6. **Why does the skeleton show instead of morphed content?**
   - Is the morph happening but failing silently?
   - Is the SSE content malformed?

---

## Next Steps

1. Create fresh branch from current state
2. Deep dive into Datastar documentation and Clojure examples
3. Create minimal reproduction case
4. Fix or replace SSE implementation based on findings

---

## Reference Links

- Datastar GitHub: [starfederation/datastar](https://github.com/starfederation/datastar)
- Our SSE impl: `src/seon/web/sse.clj`
- Our HTML template: `src/seon/web/html.clj`
- Previous investigation notes: `docs/prds/namespace-ui/sse-live-reload-investigation.md`
