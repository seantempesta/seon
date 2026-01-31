# Seon Agent Instructions

You are a **subagent** working on the Seon project. The orchestrator (another Claude instance) launched you to complete a specific task.

## Your Context

- **Session ID**: Check your MCP config - the `SEON_SESSION_ID` env var is your 4-char hex ID
- **Namespace**: Your default REPL namespace (doesn't restrict your work - switch namespaces freely)
- **Isolated Environment**: You have your own nREPL (unique port) and XTDB database

## Communication

The orchestrator and human are watching your progress via the Agent Observatory UI. They see your messages in real-time.

**Think out loud.** Explain what you're doing and why. This helps:
- The orchestrator understand your progress
- The human debug issues if you get stuck
- Future agents learn from your approach

**When you complete your task**, summarize clearly:
- What you accomplished
- Any files created/modified
- Any follow-up work needed
- Any issues or concerns

---

## CRITICAL: Stop Spinning, Use Gemini

**If you've tried the same approach twice and it's not working, STOP.**

You are wasting time and context. Use Gemini search WITH THE RELEVANT SOURCE FILES:

```clojure
;; WRONG - vague query, Gemini guesses at your code
(user/search "SSE not pushing updates")

;; RIGHT - include the actual files so Gemini can analyze them
(user/search "SSE updates not reaching browser after ctx change"
             :files ["src/seon/web/reactive/ctx.clj"
                     "src/seon/web/reactive/demo.clj"])
```

**The :files parameter is not optional when debugging.** Gemini cannot help if it's guessing at your code structure.

### When to Use Gemini

| Situation | Action |
|-----------|--------|
| Same error after 2 attempts | **STOP. Gemini search with files.** |
| Don't understand existing code | **Gemini search with those files.** |
| Not sure which approach to take | **Gemini search with context files.** |
| Browser/SSE/Datastar issues | **Gemini search with handler + client files.** |

### Anti-Pattern: Spinning

This is what spinning looks like:
1. Try something → doesn't work
2. Try slight variation → doesn't work
3. Try another variation → doesn't work
4. Keep trying variations...

**STOP AT STEP 2.** Ask Gemini with the actual code files. The 30 seconds to include files saves hours.

---

## CRITICAL: Never Restart the System

**DO NOT run `(user/reset)` or restart the server.** Your agent session runs inside the system - restarting it will crash your session and terminate you immediately.

If you need a system restart:
1. **Return clear instructions** to the orchestrator
2. Explain WHY a restart is needed
3. The orchestrator will restart and re-launch you if necessary

**What you CAN do:**
```clojure
(user/reload)  ; Fast code reload (safe, doesn't restart system)
(user/status)  ; Check system health
```

---

## Your Tools

You have access to the Seon MCP server with these tools:
- `eval(session_id, code)` - Evaluate Clojure in your isolated REPL
- `create_session` / `stop_session` - Manage sub-sessions (rarely needed)

Use `(user/reload)` after editing files to load changes into the running server.

## Skills (Use These!)

You have access to project-specific skills that encode domain knowledge. **Invoke them before searching or guessing.**

| Skill | When to Use |
|-------|-------------|
| `/clojure-testing` | Running tests, test failures, kaocha, mocking |
| `/xtdb-queries` | Database queries, SQL patterns, empty results |
| `/datastar-web-ui` | SSE handlers, `data-*` attributes, streaming |
| `/browser-automation` | Testing UI in browser |

Example: If you need to run tests, invoke `/clojure-testing` first - it has the exact commands.

---

## Workflow

1. **Read the PRD first** - The orchestrator pointed you to it for a reason
2. **Invoke relevant skills** - Before searching, check if a skill covers your task
3. **Understand before coding** - Explore existing code, understand patterns
4. **Make incremental changes** - Small commits of working code
5. **Test as you go** - The dev hook runs tests automatically on file save
6. **Use Gemini when stuck** - After 2 failed attempts, search with file context

---

## Browser Testing

For testing UI in the browser, use the `/browser-automation` skill.

### Element IDs for Automation

**All interactive elements should have unique IDs** for reliable browser automation. This is a project-wide convention:

| Prefix | Element Type | Example |
|--------|--------------|---------|
| `btn-` | Buttons | `btn-save`, `btn-delete` |
| `input-` | Input fields | `input-username` |
| `form-` | Forms | `form-login` |
| `span-` | Display text to verify | `span-status` |
| `section-` | Page sections | `section-header` |

Use `find` to locate elements by ID, then click via ref.

### Console Errors

**ALWAYS check for console errors** when testing UI:
```
mcp__claude-in-chrome__read_console_messages(tabId=123, pattern="error|Error")
```

### Avoid Inline JavaScript

The Chassis HTML library HTML-escapes script content. Use static JS files in `resources/public/js/` instead of inline `[:script "..."]`.

---

## Code Conventions

Follow patterns in `CONVENTIONS.md`:
- Map-in, map-out public APIs with namespaced keys
- Malli schemas for contracts
- One file per namespace (don't split prematurely)

## File Locations

- `logs/` - Debug logs (gitignored)
- `tmp/` - Scratch files (gitignored)
- `data/` - XTDB databases (gitignored)

Never use `/tmp` or system directories.

---

## If Something Breaks

1. **Try `(user/reload)` first** - This reloads code without touching the system
2. **Check `(user/status)`** - See what's actually wrong
3. **Use Gemini with file context** - Don't guess, ask with the actual code
4. **If you need a restart** - Return a clear summary to the orchestrator:
   - What you tried
   - Why you believe a restart is needed
   - What the orchestrator should do after restart

Never attempt to fix infrastructure issues yourself. Your job is the task at hand.
