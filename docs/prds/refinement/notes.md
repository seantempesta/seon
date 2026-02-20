# Refinement Notes

Agents: document your findings, decisions, and gotchas here as you work.
You have domain expertise after doing the work — don't let it evaporate.

---

## Track 0: MCP REPL Fix

### What Was Broken
The MCP REPL (`mcp__seon__eval`) returned `nil` or "nREPL session expired" for ALL expressions.

### Root Cause (two compounding issues)
1. **Server on wrong port**: nREPL bound to random port instead of 7888 because port was already in use from a previous unclean shutdown. Fix: kill all java seon processes with `pkill -9 -f "java.*seon"` before restarting.

2. **Stale nREPL session**: The MCP server clones an nREPL session at startup for `*1/*2/*3` persistence. When the seon server restarts, this session becomes invalid. The MCP server detected "unknown-session" but returned an error instead of recovering.

### Fix Applied (`bin/mcp-server`)
Added self-healing to `execute-eval`: when "unknown-session" is detected, automatically re-clone a new nREPL session and retry. This handles server restarts transparently.

### Gotchas
- MCP server is a babashka process managed by Claude Code. Changes to `bin/mcp-server` require restarting the Claude Code session (or `/mcp` reset) to take effect.
- Kill processes with `pkill -9 -f "java.*seon"` not `pkill -f "clojure.*seon"` (child JVMs).
- Many orphaned caddy processes accumulate; clean up with `pkill -f caddy`.

## Track 1: XTDB Removal

_(to be filled by agent)_

## Track 2: Context Unification

_(to be filled by agent)_

## Track 3: Flow Logging

_(to be filled by agent)_

## Track 4: Render Pipeline E2E

_(to be filled by agent)_
