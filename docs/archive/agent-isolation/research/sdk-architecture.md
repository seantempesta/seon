# Claude Agent SDK Architecture Research

**Date**: 2026-01-09
**Status**: Complete

## Overview

The Claude Agent SDK (`@anthropic-ai/claude-agent-sdk`) provides programmatic control over Claude Code's agent execution. This research evaluates SDK integration options for Seon's agent orchestration.

## 1. SDK Architecture

### Core Functions

The SDK exports two main APIs:

#### V1 API: `query()`

```typescript
import { query } from '@anthropic-ai/claude-agent-sdk';

// Returns AsyncGenerator<SDKMessage, void>
const stream = query({
  prompt: "Your task here",
  options: {
    model: 'opus',
    cwd: '/path/to/workdir',
    agents: { ... },       // Custom subagent definitions
    mcpServers: { ... },   // MCP server configs
    allowedTools: [...],   // Tool whitelist
    systemPrompt: '...',   // Custom system prompt
    hooks: { ... },        // Lifecycle hooks
    // ... many more options
  }
});

for await (const message of stream) {
  // message: SDKMessage (assistant, user, result, system, etc.)
}

```

#### V2 API (Unstable): Sessions

```typescript
import { unstable_v2_createSession, unstable_v2_prompt } from '@anthropic-ai/claude-agent-sdk';

// One-shot
const result = await unstable_v2_prompt("Hello", { model: 'sonnet' });

// Multi-turn session
const session = unstable_v2_createSession({ model: 'opus' });
await session.send("First message");
for await (const msg of session.stream()) { ... }
await session.send("Follow-up");
for await (const msg of session.stream()) { ... }
session.close();

```

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Application (TypeScript)                          │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                       SDK API Layer                               │   │
│  │  query()              createSdkMcpServer()     tool()            │   │
│  │  unstable_v2_*        (in-process MCP)         (define tools)    │   │
│  └─────────────────────────────┬────────────────────────────────────┘   │
│                                │                                         │
│  ┌─────────────────────────────┴────────────────────────────────────┐   │
│  │                      Transport Layer                              │   │
│  │  - SpawnedProcess (stdin/stdout to Claude CLI)                   │   │
│  │  - Or custom spawnClaudeCodeProcess()                            │   │
│  └─────────────────────────────┬────────────────────────────────────┘   │
│                                │                                         │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │ JSON-RPC over stdio
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Claude Code CLI Process                          │
│                                                                          │
│  ┌────────────────┐  ┌─────────────────────────────────────────────┐   │
│  │  Agent Loop    │  │              MCP Client                      │   │
│  │  (prompting,   │  │  Connects to:                                │   │
│  │   tool calls)  │  │  - stdio servers (external process)         │   │
│  └────────────────┘  │  - SSE/HTTP servers (network)               │   │
│                      │  - SDK servers (in-process via transport)   │   │
│                      └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘

```

### Key Types

| Type | Purpose |
|------|---------|
| `AgentDefinition` | Subagent config: prompt, tools, model, mcpServers |
| `Options` | All query options: model, permissions, hooks, MCP, etc. |
| `SDKMessage` | Union: assistant, user, result, system, partial, etc. |
| `McpServerConfig` | Union: stdio, SSE, HTTP, or SDK (in-process) |
| `HookCallback` | Lifecycle hook: PreToolUse, PostToolUse, etc. |
| `PermissionMode` | default, acceptEdits, bypassPermissions, plan, dontAsk |

### Subagent Definition

The `agents` option in `query()` defines custom subagents:

```typescript
agents: {
  'seon-agent': {
    description: 'For Seon implementation tasks',  // Auto-delegation trigger
    prompt: 'You are implementing features...',   // GUARANTEED system prompt
    tools: ['Read', 'Edit', 'Bash', 'mcp__seon__eval'],
    disallowedTools: ['WebSearch'],               // Explicit deny
    model: 'inherit',                             // Use parent's model
    mcpServers: [{ command: './bin/mcp-server' }] // Additional MCP
  }
}

```

**Key advantage over `.claude/agents/*.md`**: The `prompt` field is guaranteed to load as system prompt. Markdown agent bodies sometimes don't load (caching issue).

### MCP Server Types

1. **Stdio** (external process):

   ```typescript
   { command: './bin/mcp-server', args: [], env: {} }

   ```

2. **SSE/HTTP** (network):

   ```typescript
   { type: 'sse', url: 'http://localhost:8080/sse' }
   { type: 'http', url: 'http://localhost:8080/mcp' }

   ```

3. **SDK** (in-process, zero startup):

   ```typescript
   import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';

   const server = createSdkMcpServer({
     name: 'email',
     tools: [
       tool('search_inbox', 'Search emails', { query: z.string() },
         async (args) => ({ content: [{ type: 'text', text: '...' }] }))
     ]
   });

   // Use in query
   query({ prompt: '...', options: { mcpServers: { email: server } } });

   ```

### Lifecycle Hooks

Hooks intercept execution at various points:

```typescript
hooks: {
  PreToolUse: [{
    matcher: 'Edit|Write',  // Regex on tool name
    hooks: [async (input) => {
      // Validate file paths, etc.
      return { continue: true };  // or { decision: 'block', stopReason: '...' }
    }]
  }],
  SessionStart: [...],
  SubagentStart: [...],
  PostToolUse: [...]
}

```

**Available hooks**: PreToolUse, PostToolUse, PostToolUseFailure, Notification, UserPromptSubmit, SessionStart, SessionEnd, Stop, SubagentStart, SubagentStop, PreCompact, PermissionRequest

## 2. Integration Options for Seon

### Option A: Node.js Wrapper

**Architecture:**

```
┌─────────────────┐       ┌──────────────────┐       ┌─────────────────┐
│  Orchestrator   │  MCP  │  Node.js SDK     │ spawn │  Claude Code    │
│  (claude code)  │───────│  Wrapper         │───────│  CLI Subprocess │
│                 │       │  + seon-agent    │       │                 │
└─────────────────┘       └──────────────────┘       └─────────────────┘
                                    │
                                    │ nREPL
                                    ▼
                          ┌──────────────────┐
                          │  Seon JVM        │
                          │  (XTDB, nREPLs)  │
                          └──────────────────┘

```

**Implementation:**

```typescript
// bin/seon-orchestrator.ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import { nreplEval } from './nrepl-client';

const SEON_AGENT = {
  description: 'MUST BE USED for Seon implementation',
  prompt: await Bun.file('.claude/agents/seon-agent-prompt.md').text(),
  tools: ['Read', 'Edit', 'Bash', 'Grep', 'Glob', 'mcp__seon__eval'],
  model: 'inherit'
};

// Create in-process MCP server (zero startup overhead)
const seonServer = createSdkMcpServer({
  name: 'seon',
  tools: [
    tool('eval', 'Evaluate Clojure in agent session',
      { session_id: z.string(), code: z.string() },
      async ({ session_id, code }) => {
        const result = await nreplEval(session_id, code);
        return { content: [{ type: 'text', text: result }] };
      })
  ]
});

for await (const msg of query({
  prompt: process.argv[2],
  options: {
    agents: { 'seon-agent': SEON_AGENT },
    mcpServers: { seon: seonServer },
    settingSources: ['project']  // Load skills from project
  }
})) {
  console.log(msg);
}

```

**Pros:**
- Guaranteed system prompt loading
- Full control over subagent definition
- In-process MCP (zero startup per tool call)
- Type safety with TypeScript

**Cons:**
- Extra runtime layer (Node.js + bun)
- Need nREPL client in TypeScript (or shell out)
- More complex deployment

### Option B: Direct CLI Spawn (Current Approach)

**Architecture:**

```
┌─────────────────┐       ┌──────────────────┐
│  Orchestrator   │ Task  │  Claude Code     │
│  (claude code)  │───────│  Subagent        │
│                 │       │  (reads .md)     │
└─────────────────┘       └──────────────────┘
                                    │
                                    │ MCP (stdio)
                                    ▼
                          ┌──────────────────┐
                          │  bin/mcp-server  │
                          │  (Babashka)      │
                          │       │          │
                          │       │ nREPL    │
                          │       ▼          │
                          │  Seon JVM        │
                          └──────────────────┘

```

**Current implementation:**
- `.claude/agents/seon-agent.md` defines subagent
- `bin/mcp-server` (Babashka) provides MCP tools
- Works with Claude Code directly (no SDK needed)

**Pros:**
- Already working
- No additional runtime
- MCP server is fast (Babashka ~50ms startup)
- Markdown agent body NOW loads (as of recent session)

**Cons:**
- `.md` agent sometimes unreliable (caching issues observed)
- Less control than programmatic definition
- Can't define in-process MCP tools

### Option C: Pure HTTP (API Direct)

Skip Claude Code entirely, call Anthropic API directly.

**Not recommended because:**
- Lose all Claude Code tooling (file ops, git, etc.)
- Must reimplement agent loop, tool calling, permissions
- Significant engineering effort for marginal benefit

## 3. Recommendation

**Stick with Option B (current approach) for now.**

### Rationale

1. **Markdown agents work now**: Body loading issue appears resolved
2. **MCP is sufficient**: Our Babashka server handles all tool calls cleanly
3. **No shell escaping**: MCP protocol passes JSON directly
4. **Simpler stack**: No extra Node.js layer

### When to Reconsider SDK

Consider SDK migration if:

1. **Markdown agents regress**: If body loading becomes unreliable again
2. **Need tool restrictions**: SDK allows per-agent tool whitelists/denylists
3. **Need in-process MCP**: For zero-latency tool calls
4. **Need lifecycle hooks**: PreToolUse validation, PostToolUse logging
5. **Building external product**: SDK better for shipping to users

### SDK Quick Start (if needed later)

```bash
# Install
npm install @anthropic-ai/claude-agent-sdk

# Create wrapper
cat > bin/sdk-orchestrator.ts << 'EOF'
import { query, createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import * as fs from 'fs';

const prompt = fs.readFileSync('.claude/agents/seon-agent-prompt.md', 'utf8');

for await (const msg of query({
  prompt: Bun.argv[2],
  options: {
    agents: {
      'seon-agent': {
        description: 'For Seon implementation tasks',
        prompt,
        model: 'inherit'
      }
    },
    mcpServers: {
      seon: { command: './bin/mcp-server' }
    },
    settingSources: ['project']
  }
})) {
  if (msg.type === 'assistant') {
    const content = msg.message.content;
    if (typeof content === 'string') console.log(content);
  } else if (msg.type === 'result') {
    console.log('\n---\nCost: $' + msg.total_cost_usd.toFixed(4));
  }
}
EOF

# Run
bun bin/sdk-orchestrator.ts "Implement feature X"

```

## 4. File Organization

### Current State

Type definitions are in gitignored `/tmp/package/`:
- `sdk.d.ts` (53KB) - Full TypeScript definitions
- `sdk.mjs` (721KB) - SDK module

### Recommendation

**Do NOT vendor the types.** Instead:

1. **Document npm package**: Reference `@anthropic-ai/claude-agent-sdk` in docs
2. **Keep research notes**: This document captures key types and patterns
3. **Install on demand**: `npm install` when SDK is needed

**Why not vendor:**
- Types change with SDK updates
- 53KB of TypeScript in a Clojure project is awkward
- Easy to install when needed

### Key Type Summary (for reference)

```typescript
// Subagent definition
type AgentDefinition = {
  description: string;       // When to auto-delegate
  prompt: string;            // SYSTEM PROMPT - guaranteed to load
  tools?: string[];          // Allowed tools
  disallowedTools?: string[];
  model?: 'sonnet' | 'opus' | 'haiku' | 'inherit';
  mcpServers?: (string | McpServerConfig)[];
};

// Query options (partial list)
type Options = {
  model?: string;
  agents?: Record<string, AgentDefinition>;
  mcpServers?: Record<string, McpServerConfig>;
  allowedTools?: string[];
  disallowedTools?: string[];
  systemPrompt?: string | { type: 'preset', preset: 'claude_code', append?: string };
  hooks?: Record<HookEvent, HookCallbackMatcher[]>;
  permissionMode?: 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | 'dontAsk';
  // ... 40+ more options
};

// MCP server configs
type McpStdioServerConfig = { command: string; args?: string[]; env?: Record<string, string> };
type McpSSEServerConfig = { type: 'sse'; url: string; headers?: Record<string, string> };
type McpSdkServerConfig = { type: 'sdk'; name: string; instance: McpServer };

// Message types
type SDKMessage =
  | SDKAssistantMessage   // Claude's response
  | SDKUserMessage        // User input
  | SDKResultMessage      // Final result (success or error)
  | SDKSystemMessage      // Init message with config
  | SDKStatusMessage      // Compacting, etc.
  | SDKToolProgressMessage // Tool execution progress
  | ...;

```

## 5. Next Steps

1. **Continue with current approach**: Markdown agents + MCP server
2. **Monitor reliability**: Watch for agent body loading regressions
3. **Document SDK path**: Keep this research for future reference
4. **Consider SDK for**: New user-facing features, external products

## References

- SDK npm package: `@anthropic-ai/claude-agent-sdk`
- Example app: `reference-code/claude-agent-sdk-demos/email-agent/`
- Current MCP server: `bin/mcp-server`
- Agent definition: `.claude/agents/seon-agent.md`
- Previous research: `docs/prds/agent-isolation/research/custom-subagent-investigation.md`
