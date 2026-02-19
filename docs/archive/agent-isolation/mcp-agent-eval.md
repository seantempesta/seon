# MCP Agent Tools

**Status**: Complete
**Parent**: [Agent Isolation PRD](prd.md) - Phase 4b
**Created**: 2026-01-06
**Updated**: 2026-01-08

## Problem

`bin/agent-eval` and `clj-nrepl-eval` require ugly workarounds:

```bash
# This breaks - shell corrupts ! characters
./bin/agent-eval abc123 '(swap! *ctx* assoc :key 1)'

# This works but is ugly
cat << 'END' | ./bin/agent-eval abc123
(swap! *ctx* assoc :key 1)
END
```

The root cause: Claude Code's Bash tool always passes commands through a shell, which interprets special characters (`!`, `$`, backticks) before our script sees them.

**No amount of escaping fixes this** - the shell processes the string before any command runs.

## Solution

Created an MCP server (`bin/mcp-server`) that exposes an `agent_eval` tool. Claude calls it directly with JSON parameters - no shell involved.

```
Claude: agent_eval(session_id="abc123", code="(swap! *ctx* assoc :key 1)")
           |
    Parameters as JSON (no shell interpretation)
           |
    MCP server looks up session -> port via orchestrator nREPL
           |
    Evaluates on agent's nREPL
           |
    Returns result to Claude
```

## Implementation

### Technology Choice: Babashka

Chose **Babashka** over Clojure JVM for the MCP server because:

1. **Fast startup**: ~50ms vs ~3s for Clojure JVM
2. **Cheshire built-in**: JSON parsing available without adding dependencies
3. **bencode support**: Reused the nREPL communication pattern from `bin/agent-eval`
4. **Simple deployment**: Single executable script, no JVM deps.edn alias needed

### Architecture

```
bin/mcp-server (Babashka)
       |
       | JSON-RPC 2.0 over stdio
       |
       v
orchestrator nREPL (port 7888)
       |
       | Session lookup via eval
       |
       v
agent nREPL (port 7889, 7890, etc.)
       |
       | Clojure eval
       |
       v
Result returned to Claude
```

### Key Files

| File | Purpose |
|------|---------|
| `bin/mcp-server` | Babashka MCP server (~200 lines) |
| `.mcp.json` | Claude Code MCP configuration |
| `bin/agent-eval` | Still available for manual debugging |

### Tool Definitions

The MCP server provides four tools:

| Tool | Purpose |
|------|---------|
| `eval` | Evaluate code (orchestrator or agent session) |
| `create_session` | Create a new agent session |
| `stop_session` | Stop an agent session |
| `list_sessions` | List active sessions |

```json
{
  "name": "eval",
  "description": "Evaluate Clojure code. Use session_id='orchestrator' for the main REPL, or a 4-char hex session ID for an agent's isolated REPL. Returns result value and current namespace.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "session_id": {"type": "string", "description": "'orchestrator' for main REPL, or 4-char hex session ID (e.g., 'a1b2')"},
      "code": {"type": "string", "description": "Clojure code to evaluate"}
    },
    "required": ["session_id", "code"]
  }
}

{
  "name": "create_session",
  "description": "Create a new agent session with an isolated REPL and database. Returns the session_id to use with eval.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "namespace": {"type": "string", "description": "Clojure namespace for the agent (e.g., 'seon.trading')"}
    },
    "required": ["namespace"]
  }
}

{
  "name": "stop_session",
  "description": "Stop an agent session, flushing any pending state and cleaning up resources.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "session_id": {"type": "string", "description": "8-character hex session ID"}
    },
    "required": ["session_id"]
  }
}

{
  "name": "list_sessions",
  "description": "List all active agent sessions with their IDs, namespaces, and ports.",
  "inputSchema": {"type": "object", "properties": {}}
}
```

### MCP Protocol Implementation

The server handles these JSON-RPC 2.0 methods:

| Method | Purpose |
|--------|---------|
| `initialize` | Protocol handshake, return capabilities |
| `notifications/initialized` | Acknowledge initialization (no response) |
| `tools/list` | Return available tools |
| `tools/call` | Execute the `agent_eval` tool |

### Session Port Lookup

The MCP server queries the orchestrator nREPL to look up session -> port:

```clojure
;; Evaluate on orchestrator (port 7888)
(seon.orchestrator.session/get-session-port
  {:seon.orchestrator.session/node (user/xtdb-node)
   :seon.orchestrator.session/id "abc12345"})
;; => {:seon.orchestrator.session/nrepl-port 7889}
```

### Configuration

Added to `.mcp.json` in project root:

```json
{
  "mcpServers": {
    "seon-agent-eval": {
      "type": "stdio",
      "command": "/Users/sean/src/seon/bin/mcp-server",
      "args": [],
      "env": {}
    }
  }
}
```

## Usage

### Full Workflow

```
1. Orchestrator checks status (no session needed):
   eval(session_id="orchestrator", code="(user/status)")

2. Orchestrator creates agent session:
   create_session(namespace="seon.trading")
   → {:session_id "a1b2", :namespace "seon.trading", :nrepl_port 7889, :status "running"}

3. Agent evaluates code:
   eval(session_id="a1b2", code="(swap! *ctx* assoc :seon.trading/position 100)")
   → {:seon.trading/position 100}
   ;; ns: seon.trading

4. Agent uses helper functions:
   eval(session_id="a1b2", code="(user/reload)")
   eval(session_id="a1b2", code="(user/search \"XTDB temporal queries\")")

5. Orchestrator stops session:
   stop_session(session_id="a1b2")
   → {:session_id "a1b2", :status "stopped"}
```

**No heredocs. No shell escaping. No clj-nrepl-eval. Just works.**

### Verified Working

Tested the following scenarios:

1. Simple expressions: `(+ 1 2)` -> `3`
2. Special characters: `(def test! 42) test!` -> `42`
3. ctx operations: `(swap! *ctx* ...)` -> proper validation errors
4. Invalid session: Returns helpful error message

## What Changed

| Before | After |
|--------|-------|
| `bin/agent-eval` (250 lines Babashka) | `bin/mcp-server` (200 lines Babashka) |
| Heredoc workaround required | Direct JSON params |
| Shell corrupts `!`, `$`, backticks | All characters work |
| Two nREPL hops via bash | Direct MCP -> nREPL |

## What Stayed the Same

The MCP server is just a **cleaner interface** to existing infrastructure:

- **Multi-nREPL architecture** (Phase 2) - Each agent has isolated nREPL
- **Session API** (Phase 4) - Session ID -> port mapping
- **Persisted ctx** (Phase 3) - Automatic state persistence
- **Port allocation** - 7889, 7890, etc.

## Updated Agent Instructions Template

When launching an agent, orchestrator provides:

```
You have been assigned session ID: a1b2
Namespace: seon.trading

To evaluate Clojure code, use the eval tool:

  eval(session_id="a1b2", code="(your-code-here)")

Your context atom `*ctx*` is available. Use namespaced keys:

  eval(session_id="a1b2", code="(swap! *ctx* assoc :seon.trading/signals [...])")
  eval(session_id="a1b2", code="(:seon.trading/signals @*ctx*)")

Helper functions from user namespace (qualify with user/):

  eval(session_id="a1b2", code="(user/reload)")           ; Reload changed code
  eval(session_id="a1b2", code="(user/search \"query\")")  ; Web search via Gemini
  eval(session_id="a1b2", code="(user/status)")           ; System status

All state is automatically persisted. You don't need to save anything manually.
Each eval response includes the current namespace (;; ns: seon.trading).
```

## Decisions Log

### Why Babashka instead of Clojure JVM?

- MCP servers are spawned as subprocesses by Claude Code
- Clojure JVM takes ~3s to start, Babashka takes ~50ms
- Fast startup is critical for responsive tool calls
- No complex dependencies needed (cheshire and bencode built into bb)

### Why separate process instead of in-process?

- MCP servers are spawned by Claude Code as subprocesses
- Cannot run inside the Seon JVM as Claude controls the process lifecycle
- The orchestrator nREPL provides all needed session registry access

### Why keep bin/agent-eval?

- Useful for manual debugging without Claude
- Works from any terminal
- Heredoc workaround is acceptable for occasional human use

## Success Criteria - All Met

1. [x] `agent_eval` tool works with all special characters (`!`, `$`, backticks)
2. [x] No shell escaping issues
3. [x] Errors are properly surfaced
4. [x] Startup time < 1s (Babashka: ~50ms)
5. [x] Simple configuration (single command: `claude mcp add`)

## Research Notes

### MCP Protocol

- Uses JSON-RPC 2.0 over stdio (line-delimited JSON)
- Each JSON message is one line, newlines separate messages
- stdout is sacred: only JSON-RPC messages allowed
- stderr is for logging/debugging
- Must flush output after every message

### Reference Implementations

Added `reference-code/clojure-mcp` (git submodule) - Bruce Hauman's comprehensive Clojure MCP server. Uses the official Java MCP SDK. Could be useful for future enhancements but overkill for our simple `agent_eval` use case.

## Related

- [Agent Isolation PRD](prd.md) - Parent feature
- [notes.md](notes.md) - Shell escaping root cause analysis
- MCP Protocol: https://modelcontextprotocol.io/
- `reference-code/clojure-mcp/` - Reference Clojure MCP implementation
