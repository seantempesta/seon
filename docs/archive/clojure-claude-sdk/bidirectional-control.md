# PRD: Bidirectional Agent Control

**Status:** Phase 1 Complete, Phase 2 Paused (superseded by AI Namespace Refactor)
**Priority:** High
**Branch:** feature/agent-isolation

---

## Vision

The orchestrator (you, Claude Code in interactive mode) should have **complete insight and control** over spawned agents:

- **Tail message logs** - See what any agent is doing in real-time ✅
- **Pause notifications** - Get alerted when an agent is waiting for approval
- **Interrupt capability** - Stop an agent mid-execution ✅
- **Tool interception** - Approve/deny/modify tool calls from Clojure
- **Post-action triggers** - Run tests, Gemini review after edits ✅
- **Whitelisting** - Auto-approve safe operations

This is NOT fire-and-forget. It's full orchestration with live visibility.

---

## Implementation Status (2026-01-19)

### What Was Built

| Feature | Status | Location |
|---------|--------|----------|
| Agent observatory (`agents`, `tail`, `interrupt!`) | ✅ Done | `src/seon/claude/sdk.clj` |
| Session isolation (REPL + XTDB per agent) | ✅ Done | `src/seon/orchestrator/session.clj` |
| Hook routing to agent sessions | ✅ Done | `bin/seon-hook` |
| Conversation persistence | ✅ Done (but needs refactor) | `src/seon/claude/conversation.clj` |
| Multi-turn sessions | ✅ Done | `src/seon/claude/exploration.clj` |

### Key Discovery

**No stdin control protocol.** The Claude CLI only accepts `user` type messages via stdin. Control operations like `interrupt`, `setModel`, `setPermissionMode` are NOT supported via stdin - must use process signals (SIGINT) or the TypeScript SDK's in-process callbacks.

### What's Blocked / Superseded

The conversation persistence schema is too Claude-specific. This PRD is **paused** in favor of the **AI Namespace Refactor PRD** (`docs/prds/ai-namespace-refactor/prd.md`) which:
1. Creates provider-agnostic `seon.ai` namespace
2. Moves Claude-specific code to `seon.ai.claude`
3. Wires auto-persistence into agent lifecycle

After the refactor, remaining items from this PRD can be addressed.

---

## Current State (Original)

We have:
- `seon.claude.sdk` - Spawns agents, reads output stream ✅
- `bin/seon-hook` - Shell hooks that call nREPL ✅
- `core.async` channels for message streaming ✅
- MCP server for agent REPL access ✅

We're missing:
- ~~Bidirectional control (sending commands back to CLI)~~ **Not possible via CLI**
- ~~Proper interrupt handling~~ ✅ Done via process destroy
- ~~Live visibility into agent state~~ ✅ Done via `agents`, `tail`
- In-process hook callbacks (not shell commands) - **Blocked on SDK limitations**

---

## Research Resources

### Local Code (investigate thoroughly)

| Path | What's There |
|------|--------------|
| `reference-code/claude-agent-sdk-typescript/` | TypeScript SDK source (if available) |
| `reference-code/claude-agent-sdk-demos/` | Working examples with hooks, MCP, multi-turn |
| `reference-code/claude-agent-sdk-demos/hello-world/` | Basic hooks example |
| `reference-code/claude-agent-sdk-demos/research-agent/` | Python SDK with hook tracking |
| `reference-code/claude-agent-sdk-demos/email-agent/` | Complex TypeScript integration |

### External Resources

| Resource | Purpose |
|----------|---------|
| https://github.com/hesreallyhim/awesome-claude-code | Community integrations, undocumented features |
| https://platform.claude.com/docs/en/agent-sdk/hooks | Official hooks documentation |
| https://platform.claude.com/docs/en/agent-sdk/typescript | TypeScript SDK reference |
| https://github.com/anthropics/claude-code/issues | Bug reports may reveal internals |

---

## Research Tasks

### Task 1: Understand the Bidirectional Protocol

**Goal:** Document exact message format for stdin/stdout communication

**Approach:**
1. Create a logging wrapper that captures ALL stdin/stdout
2. Run TypeScript SDK examples and capture the exchange
3. Identify control message types (permission requests, interrupts, etc.)

```clojure
;; Example: Logging wrapper to capture protocol
(defn logged-spawn [opts]
  (let [proc (spawn-claude-code opts)
        log-file (io/writer "logs/protocol-capture.jsonl")]
    ;; Wrap stdout to log all messages
    ;; Wrap stdin to log all commands we send
    ...))
```

**Questions to answer:**
- What message triggers `canUseTool` callback?
- How does `query.interrupt()` work?
- Is there a `can_use_tool` request/response?

### Task 2: Explore TypeScript SDK Internals

**Goal:** Understand how the SDK implements bidirectional control

**Approach:**
1. Read TypeScript SDK source in `reference-code/`
2. Find where `canUseTool` is invoked
3. Find where control messages are sent
4. Document the state machine

```bash
# Search for control protocol implementation
grep -r "can_use_tool\|canUseTool\|interrupt\|setPermissionMode" reference-code/
```

### Task 3: Test Control Messages from Clojure

**Goal:** Prove we can send control messages and get responses

**Approach:**
1. Start a Claude session with stream-json
2. Send a prompt, wait for tool_use
3. Try sending different message types on stdin
4. See what happens

```clojure
;; Experiment: Can we send a permission response?
(defn test-control-protocol []
  (let [proc (spawn-claude-code {::output-format "stream-json"
                                  ::input-format "stream-json"})
        stdin (process/stdin proc)
        stdout (process/stdout proc)]
    ;; Send initial prompt
    (write-message! stdin (make-user-message "Create a file test.txt"))

    ;; Read messages until we see tool_use
    ;; Then try sending a control message...
    ))
```

### Task 4: Improve core.async Architecture

**Goal:** Proper handling of interrupts, backpressure, multiple consumers

**Current issues:**
- Single consumer on message channel
- No interrupt mechanism
- No way to "peek" at agent state

**Approach:**
1. Use `mult` for multiple consumers (orchestrator + logging)
2. Add control channel for interrupt signals
3. Implement proper backpressure handling

```clojure
;; Better architecture sketch
(defn launch-agent-v2 [opts]
  (let [messages-ch (chan 100)
        messages-mult (async/mult messages-ch)  ; Multiple consumers
        control-ch (chan)                        ; For interrupts
        status-atom (atom {:state :running
                           :last-tool nil
                           :waiting-approval? false})]
    ;; Reader updates status-atom based on messages
    ;; Control-ch can send interrupt signal
    ...))
```

### Task 5: Investigate Community Integrations

**Goal:** Learn from others, find undocumented features

**Approach:**
1. Clone awesome-claude-code repo
2. Look for unusual integrations
3. Search for protocol discussions
4. Check GitHub issues for internals

```bash
cd reference-code
git clone https://github.com/hesreallyhim/awesome-claude-code
```

**Look for:**
- Custom SDKs in other languages
- Debugging/tracing tools
- Protocol documentation
- Undocumented CLI flags

### Task 6: Prototype Agent Observatory

**Goal:** Build a simple "agent dashboard" for the orchestrator

**Features:**
- List all running agents with status
- Tail any agent's message log
- See pending approval requests
- Interrupt an agent

```clojure
;; REPL helpers for orchestrator
(defn agents [] (sdk/list-agents {}))
(defn tail [session-id] (sdk/tail-messages session-id))
(defn pending [] (sdk/list-pending-approvals))
(defn interrupt! [session-id] (sdk/interrupt-agent! session-id))
```

---

## Technical Investigations

### Investigation A: What happens when canUseTool is set?

The TypeScript SDK docs say:
> "Permission callbacks are invoked via the control protocol when the CLI
> requests permission to use a tool."

Does the CLI send a special message? Or does the SDK intercept the tool_use message before it executes?

**Test:** Run same prompt with/without canUseTool, compare message streams.

### Investigation B: How does interrupt() work?

The Query interface has `interrupt(): Promise<void>`.

Does it:
1. Send a special message on stdin?
2. Kill the process with a signal?
3. Something else?

**Test:** Call interrupt during tool execution, observe behavior.

### Investigation C: Are there undocumented message types?

The stream-json output includes types like:
- `system`, `assistant`, `user`, `result`

Are there others? Permission requests? Control acknowledgments?

**Test:** Capture ALL messages with verbose logging, look for unknowns.

### Investigation D: What CLI flags affect the protocol?

Known flags:
- `--output-format stream-json`
- `--input-format stream-json`
- `--verbose`
- `--debug hooks`

Are there hidden flags?

**Test:** `strings $(which claude) | grep -i flag\|option\|--`

---

## Success Criteria

1. **Document the protocol** - Full spec of stdin/stdout message types
2. **Prove bidirectional control** - Send a message, get a response
3. **Implement interrupt** - Stop an agent mid-execution from Clojure
4. **Implement tool interception** - Approve/deny from Clojure callback
5. **Build agent observatory** - REPL tools for live visibility

---

## Implementation Phases (After Research)

### Phase 1: Protocol Documentation
- Capture and document all message types
- Understand control flow for tool execution

### Phase 2: Core.async Refactor
- Mult for multiple consumers
- Control channel for interrupts
- Status tracking

### Phase 3: Clojure Hooks
- In-process callbacks via bidirectional protocol
- Or optimized shell hooks via nREPL

### Phase 4: Agent Observatory
- REPL helpers for orchestrator
- Live visibility into all agents

---

## Notes for Implementing Agent

When you work on this PRD:

1. **Use the REPL extensively** - `(user/reload)`, test everything interactively
2. **Write exploration code** - Create `src/seon/claude/exploration.clj` for experiments
3. **Capture everything** - Log all stdin/stdout to understand the protocol
4. **Check reference-code first** - The TypeScript SDK source may have answers
5. **Look for undocumented features** - Community repos often have discoveries
6. **Update this PRD** - Document findings as you go

---

## Findings (2026-01-19)

### Key Discovery: AsyncIterable Prompt for Bidirectional Control

The TypeScript SDK supports bidirectional communication by accepting an `AsyncIterable<SDKUserMessage>`
as the `prompt` parameter instead of a string. This enables:

1. **Multi-turn conversations** - Send additional messages after the initial prompt
2. **Live control** - Queue up user messages while Claude is processing
3. **Session management** - Resume sessions with context preservation

### Message Types (from SDK demos)

#### SDKUserMessage (stdin to CLI)
```typescript
{
  type: "user",
  message: {
    role: "user",
    content: string | ContentBlock[]
  }
}
```

#### SDKMessage (stdout from CLI) - Union type:
- **SDKSystemMessage** - Init message with session info, tools, skills
- **SDKAssistantMessage** - Claude's responses with text/tool_use blocks
- **SDKUserMessage** - Echoed user messages (when `--replay-user-messages`)
- **SDKResultMessage** - Final completion with cost, duration, status

### SDKSystemMessage (type: "system")
```typescript
{
  type: "system",
  subtype: "init",
  session_id: string,      // UUID for session resume
  tools: ToolInfo[],       // Available tools
  skills: SkillInfo[],     // Available skills
  mcp_servers: ServerInfo[] // Connected MCP servers
}
```

### SDKAssistantMessage (type: "assistant")
```typescript
{
  type: "assistant",
  message: {
    role: "assistant",
    content: ContentBlock[]  // text, tool_use, tool_result blocks
  },
  session_id: string,
  uuid: string
}
```

### SDKResultMessage (type: "result")
```typescript
{
  type: "result",
  subtype: "success" | "error_during_execution" | "error_max_turns" |
           "error_max_budget_usd" | "interrupted",
  result?: string,         // Final text output
  num_turns: number,
  total_cost_usd: number,
  duration_ms: number,
  session_id: string
}
```

### V2 Session API (from hello-world-v2)

The SDK provides higher-level session APIs:
```typescript
// Create session
await using session = unstable_v2_createSession({ model: 'sonnet' });

// Send message
await session.send('Hello!');

// Stream responses
for await (const msg of session.stream()) { ... }

// Resume session
await using session = unstable_v2_resumeSession(sessionId, options);
```

### Multi-Turn Pattern (from simple-chatapp)

The key insight is using an `AsyncIterable` as the prompt:

```typescript
class MessageQueue {
  // Push messages to send to Claude
  push(content: string) { ... }

  // Implement AsyncIterator for SDK consumption
  async *[Symbol.asyncIterator]() {
    while (!this.closed) {
      yield await this.nextMessage();
    }
  }
}

// Start query with queue as prompt
const query = query({
  prompt: messageQueue as any,  // AsyncIterable<SDKUserMessage>
  options: { ... }
});

// Send messages anytime
messageQueue.push("Follow-up question");
```

### CLI Flags Discovered

Key flags for programmatic control:
```
--output-format stream-json    # Required for message streaming
--input-format stream-json     # Required for sending messages
--verbose                      # REQUIRED for stream-json to work
--replay-user-messages         # Echo stdin messages to stdout
--resume <session-id>          # Resume previous session
--fork-session                 # Create new session from resume point
--session-id <uuid>            # Use specific session ID
--include-partial-messages     # Stream partial chunks
```

Complete flag list (from `claude --help`, v2.1.12):
```
--add-dir <dirs>               # Additional directories for tool access
--agent <agent>                # Agent for session (overrides setting)
--agents <json>                # JSON defining custom agents inline
--allow-dangerously-skip-permissions  # Enable bypass option
--allowedTools <tools>         # Tool whitelist (comma/space separated)
--append-system-prompt <prompt># Append to system prompt
--betas <betas>                # Beta headers for API (API key only)
--chrome                       # Enable Claude in Chrome integration
--dangerously-skip-permissions # Bypass ALL permission checks
--disable-slash-commands       # Disable all skills
--disallowedTools <tools>      # Tool denylist
--fallback-model <model>       # Fallback when overloaded (--print only)
--file <specs>                 # File resources to download
--fork-session                 # Create new session from resume point
--ide                          # Auto-connect to IDE
--include-partial-messages     # Stream partial chunks (--print only)
--input-format text|stream-json # Input format (--print only)
--json-schema <schema>         # JSON Schema for structured output
--max-budget-usd <amount>      # Cost limit (--print only)
--mcp-config <configs>         # Load MCP servers from JSON
--mcp-debug                    # (Deprecated) Use --debug instead
--model <model>                # Model alias or full name
--no-chrome                    # Disable Chrome integration
--no-session-persistence       # Don't save sessions (--print only)
--output-format text|json|stream-json  # Output format (--print only)
--permission-mode <mode>       # acceptEdits|bypassPermissions|default|delegate|dontAsk|plan
--plugin-dir <paths>           # Load plugins from directories
--replay-user-messages         # Echo stdin to stdout
--resume <session-id>          # Resume conversation
--session-id <uuid>            # Use specific session ID
--setting-sources <sources>    # user,project,local (comma-separated)
--settings <file-or-json>      # Load settings from file or JSON string
--strict-mcp-config            # Only use --mcp-config servers
--system-prompt <prompt>       # System prompt for session
--tools <tools>                # Available tools ("", "default", or list)
--verbose                      # Override verbose mode setting
```

### Session Resume Pattern

1. Capture `session_id` from system init message
2. Use `--resume <session_id>` to continue conversation
3. Context (memory, tool state) is preserved
4. Can use `--fork-session` to branch from a point

### Hook Integration via SDK

The SDK supports inline hooks (no shell commands):
```typescript
query({
  prompt: "...",
  options: {
    hooks: {
      PreToolUse: [{
        matcher: "Write|Edit",
        hooks: [async (input) => {
          // Return { continue: true } to allow
          // Return { decision: 'block', stopReason: '...' } to deny
        }]
      }]
    }
  }
});
```

### Control Methods (V2 API)

The Query object in streaming mode exposes:
- `query.interrupt()` - Stop current execution
- `query.setPermissionMode(mode)` - Change permissions mid-run
- `query.setModel(model)` - Switch models mid-run

These likely send control messages on stdin (needs verification).

### Actual Captured Protocol (from logs/protocol-capture-*.jsonl)

#### System Init Message (stdout)
First message received after spawn. Contains all available tools, MCP servers, session ID:
```json
{
  "type": "system",
  "subtype": "init",
  "session_id": "3ef93fc6-c001-40ae-9d1b-15dee4d7d55b",
  "model": "claude-opus-4-5-20251101",
  "permissionMode": "default",
  "claude_code_version": "2.1.12",
  "cwd": "/Users/sean/src/seon",
  "tools": ["Task", "Bash", "Read", "Edit", "Write", ...],
  "mcp_servers": [
    {"name": "seon", "status": "connected"},
    {"name": "context7", "status": "connected"}
  ],
  "skills": ["browser-automation", "clojure-testing", ...],
  "agents": ["Bash", "general-purpose", "Explore", "Plan", ...],
  "apiKeySource": "none",
  "uuid": "f424bf18-1d96-4703-9b00-8015b3d0970a"
}
```

#### User Message (stdin)
Send prompts or follow-ups:
```json
{
  "type": "user",
  "session_id": "",
  "message": {
    "role": "user",
    "content": [{"type": "text", "text": "Your prompt here"}]
  },
  "parent_tool_use_id": null
}
```

#### Assistant Message (stdout)
Claude's responses, may include tool_use blocks:
```json
{
  "type": "assistant",
  "session_id": "3ef93fc6-c001-40ae-9d1b-15dee4d7d55b",
  "uuid": "af29765e-ce39-455f-af9a-5b244dd3912b",
  "parent_tool_use_id": null,
  "message": {
    "role": "assistant",
    "type": "message",
    "id": "msg_01S6qbLLXDhqtMoGeAYe3Tnk",
    "model": "claude-opus-4-5-20251101",
    "stop_reason": null,
    "stop_sequence": null,
    "content": [
      {"type": "text", "text": "Response text here..."},
      {"type": "tool_use", "id": "tool_123", "name": "Read", "input": {...}}
    ],
    "usage": {
      "input_tokens": 3,
      "output_tokens": 76,
      "cache_read_input_tokens": 20790,
      "cache_creation_input_tokens": 2012
    }
  }
}
```

#### Result Message (stdout)
Final completion with cost and stats:
```json
{
  "type": "result",
  "subtype": "success",
  "session_id": "3ef93fc6-c001-40ae-9d1b-15dee4d7d55b",
  "uuid": "b534508b-f99f-4eb0-bc23-0c3f8df08bb7",
  "result": "Final text response...",
  "num_turns": 1,
  "duration_ms": 4301,
  "duration_api_ms": 4282,
  "total_cost_usd": 0.024885,
  "is_error": false,
  "permission_denials": [],
  "usage": {
    "input_tokens": 3,
    "output_tokens": 76,
    "cache_read_input_tokens": 20790,
    "cache_creation_input_tokens": 2012,
    "server_tool_use": {"web_search_requests": 0, "web_fetch_requests": 0}
  },
  "modelUsage": {
    "claude-opus-4-5-20251101": {
      "inputTokens": 3,
      "outputTokens": 76,
      "cacheReadInputTokens": 20790,
      "costUSD": 0.024885,
      "contextWindow": 200000,
      "maxOutputTokens": 64000
    }
  }
}
```

### What We Still Need to Discover

1. ~~**Exact control message format** - What JSON goes to stdin for interrupt/setModel/etc?~~
   **RESOLVED**: The CLI only accepts `user` type messages on stdin. Control operations require:
   - Process signals (SIGINT) for interrupt
   - AbortController pattern in TypeScript SDK
   - No JSON control messages supported

2. **Permission request format** - How does CLI request tool approval in SDK mode?
3. **canUseTool callback mechanism** - Is it a stdin request or hook callback?
4. **Error handling** - What messages indicate failures?

### Key Discovery: No Stdin Control Protocol (2026-01-19)

**Critical Finding**: The Claude CLI does NOT support control messages over stdin.

When attempting to send messages with types like `interrupt` or `control`, the CLI returns:
```
Error: Expected message type 'user' or 'control', got 'interrupt'
Error: Expected message type 'user' or 'control', got 'control'
```

This contradictory error suggests the CLI may have partial control support but it's not functional.

**Implications**:
1. Interrupt must be done via process signals (SIGINT) or destruction
2. The TypeScript SDK uses `AbortController` which works at the OS level
3. We cannot change model/permissions mid-session via JSON
4. Multi-turn works fine - just send `user` type messages

### Implementation Plan for Clojure SDK

1. **Phase 1: AsyncIterable Equivalent** (core.async channel as prompt) - **COMPLETE**
   - Create message queue that the SDK consumes
   - Support multi-turn by pushing to channel
   - Tested: Context preserved across turns (2+2=4, then multiply by 3=12)

2. **Phase 2: Session Management** - **COMPLETE**
   - Capture session_id from init message
   - Support `--resume` for continuing sessions
   - Map Claude sessions to Seon sessions

3. **Phase 3: Control Protocol** - **PARTIALLY COMPLETE**
   - ~~Implement interrupt by sending control message~~ Not possible via JSON
   - Implemented interrupt via process destruction
   - setModel/setPermissionMode require new session (cannot change mid-session)
   - canUseTool requires shell hooks (not in-process)

4. **Phase 4: Hook Integration** - **DEFERRED**
   - In-process callbacks not supported by CLI
   - Shell hooks via bin/seon-hook working well
   - May revisit if Anthropic adds SDK support

---

## Implementation Status (2026-01-19)

### Completed

#### Agent Observatory (`src/seon/claude/sdk.clj`)

Added orchestrator visibility functions:

```clojure
;; List all running agents
(sdk/agents {})
;; => [{::sdk/session-id "a1b2"
;;      ::sdk/namespace 'seon.trading
;;      ::sdk/nrepl-port 7889
;;      ::sdk/status :running}]

;; Get messages channel for an agent
(sdk/tail {::sdk/session-id "a1b2"})
;; => <core.async channel>

;; Get full agent handle
(sdk/get-agent {::sdk/session-id "a1b2"})
;; => {::sdk/session-id "a1b2" ::sdk/messages-ch ... ::sdk/close! ...}

;; Interrupt an agent (via process destruction)
(sdk/interrupt! {::sdk/session-id "a1b2"})
;; => {::sdk/session-id "a1b2" ::sdk/interrupted? true ::sdk/method :destroy}

;; Get agent cost (if completed)
(sdk/agent-cost {::sdk/session-id "a1b2"})
;; => {::sdk/session-id "a1b2" ::sdk/cost-usd 0.05 ::sdk/turns 3}
```

#### Conversation Persistence (`src/seon/claude/conversation.clj`)

XTDB schemas and functions for storing conversations:

**Schemas:**
- `conversation/session` - Session metadata (start, namespace, status, cost)
- `conversation/message` - Individual messages with extracted text, tool calls
- `conversation/turn` - Complete turns (prompt + response)

**Functions:**
```clojure
;; Start session
(conv/start-session! {::conv/node node
                      ::conv/session-id "abc123"
                      ::conv/namespace 'seon.trading})

;; Persist messages as they arrive
(conv/persist-message! {::conv/node node
                        ::conv/session-id "abc123"
                        ::conv/message sdk-message})

;; Query conversations
(conv/get-session-messages {::conv/node node
                            ::conv/session-id "abc123"})

(conv/get-session-summary {::conv/node node
                           ::conv/session-id "abc123"})

(conv/list-recent-sessions {::conv/node node})

;; Create callback for automatic persistence
(let [persist! (conv/create-message-persister node session-id)]
  (go-loop []
    (when-let [msg (<! messages-ch)]
      (persist! msg)
      (recur))))
```

#### Exploration Code (`src/seon/claude/exploration.clj`)

REPL tools for protocol research:
- `start-multi-turn-session!` - Create interactive session
- `read-until-result!` - Consume messages until completion
- `capture-protocol!` - Full protocol capture to JSONL
- `test-control-message` - Experiment with stdin messages
- `view-capture` / `analyze-message-types` - Analyze captured data

### Not Implemented (Blocked)

1. **In-process control messages** - CLI doesn't support them
2. **canUseTool callbacks via JSON** - Requires shell hooks
3. **Mid-session model/permission changes** - Not supported by CLI
