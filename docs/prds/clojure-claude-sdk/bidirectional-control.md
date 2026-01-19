# PRD: Bidirectional Agent Control

**Status:** Research Phase
**Priority:** High
**Branch:** feature/agent-isolation

---

## Vision

The orchestrator (you, Claude Code in interactive mode) should have **complete insight and control** over spawned agents:

- **Tail message logs** - See what any agent is doing in real-time
- **Pause notifications** - Get alerted when an agent is waiting for approval
- **Interrupt capability** - Stop an agent mid-execution
- **Tool interception** - Approve/deny/modify tool calls from Clojure
- **Post-action triggers** - Run tests, Gemini review after edits
- **Whitelisting** - Auto-approve safe operations

This is NOT fire-and-forget. It's full orchestration with live visibility.

---

## Current State

We have:
- `seon.claude.sdk` - Spawns agents, reads output stream
- `bin/seon-hook` - Shell hooks that call nREPL
- `core.async` channels for message streaming
- MCP server for agent REPL access

We're missing:
- Bidirectional control (sending commands back to CLI)
- Proper interrupt handling
- Live visibility into agent state
- In-process hook callbacks (not shell commands)

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
