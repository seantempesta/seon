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

## Workflow

1. **Read the PRD first** - The orchestrator pointed you to it for a reason
2. **Invoke relevant skills** - Before searching, check if a skill covers your task
3. **Understand before coding** - Explore existing code, understand patterns
4. **Make incremental changes** - Small commits of working code
5. **Test as you go** - The dev hook runs tests automatically on file save
6. **Ask if stuck** - Use clear questions the orchestrator can answer

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

## If Something Breaks

```clojure
(user/reload)  ; Try reloading first
(user/reset)   ; Full restart if reload doesn't help
(user/status)  ; Check system health
```

If truly stuck, describe the problem clearly so the orchestrator can help.
