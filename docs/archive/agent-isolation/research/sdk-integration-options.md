# Claude Agent SDK Integration Research

**Date**: 2026-01-09
**Status**: Complete

## Executive Summary

The Anthropic TypeScript SDK is an HTTP API wrapper with convenience helpers for tool execution loops. It does not provide "agent control" capabilities beyond what the raw Messages API offers. The key value-adds are:

1. **ToolRunner** - Automatic agentic loop (request -> tool use -> execute -> respond)
2. **MCP integration** - Native support for MCP servers in beta API
3. **Type safety** - TypeScript types for all API structures
4. **Streaming helpers** - Event-based message streaming

For Seon's Clojure/Babashka toolchain, we have several integration paths available.

---

## 1. SDK Architecture Summary

### What the SDK Is

The SDK (`@anthropic-ai/sdk`) is a **thin HTTP wrapper** around the Claude Messages API with added conveniences:

```
┌─────────────────────────────────────────────────────────────────┐
│                     Anthropic TypeScript SDK                     │
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────────────────┐  │
│  │    HTTP Client      │  │     Higher-Level Helpers        │  │
│  │ (messages.create)   │  │                                 │  │
│  │                     │  │  - BetaToolRunner (agent loop)  │  │
│  │  POST /v1/messages  │  │  - MessageStream (SSE events)   │  │
│  │  Authentication     │  │  - betaZodTool (schema tools)   │  │
│  │  Retries/Timeouts   │  │  - betaTool (JSON schema)       │  │
│  └─────────────────────┘  └─────────────────────────────────┘  │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                 TypeScript Definitions                   │   │
│  │  Message, Tool, ContentBlock, ToolUseBlock, etc.        │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘

```

### What the SDK Is NOT

- **Not an agent framework** - No memory, planning, or multi-step reasoning
- **Not a CLI wrapper** - Calls HTTP API directly, not `claude` CLI
- **Not required for tool use** - Raw API has identical tool capabilities

### Key SDK Features

#### BetaToolRunner (Agent Loop)

The main SDK value-add is `toolRunner()` which handles the agentic loop:

```typescript
// SDK's tool loop (what we'd need to replicate)
const runner = anthropic.beta.messages.toolRunner({
  model: 'claude-opus-4',
  max_tokens: 4000,
  messages: [{ role: 'user', content: 'What is 2+2?' }],
  tools: [calculatorTool],
  max_iterations: 10,  // Limit tool rounds
});

// Iterates: Claude responds -> tool_use -> execute -> tool_result -> repeat
for await (const message of runner) {
  console.log(message);
}

```

This is ~360 lines of TypeScript (see `src/lib/tools/BetaToolRunner.ts`). The core loop:

1. Send message to Claude
2. Check if response contains `tool_use` blocks
3. Execute tools, collect results as `tool_result` blocks
4. Add tool results to messages, repeat from step 1
5. Exit when Claude responds without `tool_use` (or max_iterations reached)

#### MCP Integration

The SDK supports MCP servers natively via the beta API:

```typescript
anthropic.beta.messages.stream({
  model: 'claude-sonnet-4',
  mcp_servers: [{
    type: 'url',
    url: 'http://my-mcp-server/sse',
    name: 'my-tools',
    authorization_token: 'TOKEN',
  }],
  messages: [...],
}, {
  headers: { 'anthropic-beta': 'mcp-client-2025-04-04' }
});

```

This lets Claude directly call tools on MCP servers - the API handles the MCP protocol.

#### Streaming Helpers

```typescript
const stream = anthropic.messages.stream({...})
  .on('text', (delta) => console.log(delta))
  .on('message', (msg) => console.log('done', msg));

```

---

## 2. Babashka vs nbb vs ClojureScript

### Babashka

- **Runtime**: Native binary (GraalVM compiled)
- **Language**: Clojure (NOT ClojureScript)
- **npm interop**: None - cannot require npm packages
- **Strengths**: Fast startup (~5ms), access to JVM libs via pods

### nbb (Node Babashka)

- **Runtime**: Node.js (v14+)
- **Language**: ClojureScript (compiles to JS)
- **npm interop**: YES via `(:require ["package$default" :as pkg])`
- **Startup**: ~170ms (or ~470ms via npx)
- **Strengths**: Full npm ecosystem access

### ClojureScript (shadow-cljs)

- **Runtime**: Node.js or browser
- **Language**: ClojureScript
- **npm interop**: YES, full npm access
- **Build step**: Required (shadow-cljs compile)
- **Strengths**: Full optimization, source maps, advanced builds

### Comparison for SDK Integration

| Aspect | Babashka | nbb | ClojureScript |
|--------|----------|-----|---------------|
| Can require SDK | No | Yes | Yes |
| Startup time | ~5ms | ~170ms | Depends on build |
| Build step needed | No | No | Yes |
| Our existing code | `bin/mcp-server` | Could migrate | Overkill |

---

## 3. Integration Options

### Option A: nbb + SDK Direct

Use nbb to call the TypeScript SDK from ClojureScript.

```clojure
#!/usr/bin/env nbb

(ns agent.runner
  (:require ["@anthropic-ai/sdk$default" :as Anthropic]
            [promesa.core :as p]))

(def client (Anthropic. #js {:apiKey (js/process.env.ANTHROPIC_API_KEY)}))

(defn run-agent [prompt tools]
  (p/let [runner (.toolRunner (.-beta.messages client)
                              #js {:model "claude-opus-4"
                                   :max_tokens 4000
                                   :messages #js [#js {:role "user" :content prompt}]
                                   :tools (clj->js tools)})]
    ;; Consume the async iterator
    ...))

```

**Pros:**
- Full SDK access including ToolRunner, MCP, streaming
- Single process, no IPC overhead
- TypeScript types help via docstrings

**Cons:**
- nbb is ClojureScript, not Clojure (subtle differences)
- `$default` syntax for CommonJS modules
- No REPL connection to our running Seon server
- Would need to rewrite `bin/mcp-server` in nbb

**Verdict**: Possible but awkward. nbb's ClojureScript dialect differs from our Clojure codebase.

### Option B: Shell to Node Script

Babashka shells out to a Node.js script that uses the SDK.

```clojure
;; bin/agent-orchestrator (Babashka)
(defn run-agent-task [session-id prompt]
  (let [result (shell/sh "node" "scripts/agent-runner.js"
                         "--session" session-id
                         "--prompt" prompt)]
    (json/parse-string (:out result) true)))

```

```javascript
// scripts/agent-runner.js
import Anthropic from '@anthropic-ai/sdk';
import { tools } from './tools.js';

const client = new Anthropic();
const runner = client.beta.messages.toolRunner({
  // ...configure agent
  tools: tools.map(t => ({
    ...t,
    run: async (input) => {
      // Call our MCP server or HTTP endpoints
      return await fetch(`http://localhost:8080/tools/${t.name}`, {
        method: 'POST',
        body: JSON.stringify(input)
      });
    }
  }))
});
// ...

```

**Pros:**
- Clean separation: Babashka orchestrates, Node runs agents
- Full SDK features including ToolRunner
- Node script can call back to Seon via HTTP/MCP

**Cons:**
- Process startup overhead (~200-500ms per agent)
- Two languages in codebase (JS + Clojure)
- State sync complexity between processes
- Another dependency to maintain

**Verdict**: Workable but adds complexity. The overhead may not be worth it for what we gain.

### Option C: HTTP API Direct from Babashka/Clojure

Skip the SDK entirely and call the Claude HTTP API directly.

```clojure
;; Pure Clojure/Babashka - no SDK needed
(ns seon.agent.claude
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn messages-create [params]
  (-> (http/post "https://api.anthropic.com/v1/messages"
        {:headers {"x-api-key" (System/getenv "ANTHROPIC_API_KEY")
                   "anthropic-version" "2023-06-01"
                   "content-type" "application/json"}
         :body (json/generate-string params)})
      :body
      (json/parse-string true)))

(defn tool-loop
  "Run agent loop until no tool_use or max iterations."
  [params max-iterations]
  (loop [messages (:messages params)
         iteration 0]
    (if (>= iteration max-iterations)
      {:status :max-iterations :messages messages}
      (let [response (messages-create (assoc params :messages messages))
            tool-uses (filter #(= "tool_use" (:type %)) (:content response))]
        (if (empty? tool-uses)
          {:status :complete :response response :messages messages}
          ;; Execute tools and continue
          (let [tool-results (execute-tools tool-uses)
                new-messages (conj messages
                                   {:role "assistant" :content (:content response)}
                                   {:role "user" :content tool-results})]
            (recur new-messages (inc iteration))))))))

(defn execute-tools [tool-uses]
  ;; Route to our MCP server or internal handlers
  (for [{:keys [id name input]} tool-uses]
    {:type "tool_result"
     :tool_use_id id
     :content (dispatch-tool name input)}))

```

**Pros:**
- Pure Clojure - works in Babashka OR JVM
- No npm/Node.js dependencies
- Full control over the agent loop
- Integrates naturally with our existing codebase
- Can run in our existing nREPL sessions

**Cons:**
- Must implement ToolRunner logic ourselves (~200 lines)
- Must implement streaming SSE parsing for streaming responses
- No type hints from SDK (rely on docs)
- Must handle retries, errors ourselves

**Verdict**: Strongest option for Seon. The SDK's ToolRunner is simple enough to reimplement.

### Option D: Hybrid TypeScript Orchestrator

TypeScript orchestrator that calls our Clojure MCP server.

```
┌───────────────────────────────────────────────────────────────┐
│            TypeScript Agent Orchestrator (Node.js)            │
│                                                               │
│  Uses: @anthropic-ai/sdk                                      │
│  Manages: Agent loops, tool routing                           │
│                                                               │
│      ↓ MCP Protocol                                           │
├───────────────────────────────────────────────────────────────┤
│            Seon MCP Server (bin/mcp-server)                   │
│                                                               │
│  Language: Babashka (unchanged)                               │
│  Provides: eval, create_session, stop_session, list_sessions  │
│                                                               │
│      ↓ nREPL                                                  │
├───────────────────────────────────────────────────────────────┤
│                    Seon JVM (unchanged)                       │
└───────────────────────────────────────────────────────────────┘

```

**Pros:**
- Keep our existing MCP server as-is
- Full SDK features in the orchestrator layer
- TypeScript for agent logic, Clojure for domain logic

**Cons:**
- Additional Node.js process
- Coordination complexity
- TypeScript codebase to maintain
- Overkill for our current needs

**Verdict**: Too heavy. Makes sense for a multi-agent system, not for Seon's single-agent model.

---

## 4. What Does the SDK Give Us That Raw API Doesn't?

### Unique SDK Features

| Feature | SDK | Raw API | Effort to Implement |
|---------|-----|---------|---------------------|
| HTTP client | Yes | Use clj-http | 0 (trivial) |
| Automatic retries | Yes | Implement with retry lib | Low (~20 lines) |
| ToolRunner loop | Yes | Implement ourselves | Medium (~200 lines) |
| Streaming helpers | Yes | Parse SSE manually | Medium (~100 lines) |
| MCP integration | Yes (beta) | Call MCP ourselves | Low (we already do) |
| Token counting | Yes | Same API call | 0 |
| Message batches | Yes | Same API call | 0 |

### The Critical Question

**Q: Can we get the same agent/subagent control by calling the API directly?**

**A: YES.** The SDK's "agent control" is just the ToolRunner, which:
1. Sends messages
2. Checks for `tool_use` in response
3. Executes tools locally
4. Adds `tool_result` to messages
5. Loops until done

This is ~200 lines of straightforward Clojure. No magic involved.

### MCP Server Support

The SDK's MCP integration (`mcp_servers` parameter) is interesting but:
- Still in beta (`anthropic-beta: mcp-client-2025-04-04`)
- Only works with URL-based MCP servers (not stdio)
- We already have stdio MCP working with Claude Code

For our use case (Claude Code calling our MCP server), we don't need the SDK's MCP support.

---

## 5. Recommendation for Seon

### Primary Recommendation: Option C (HTTP API Direct)

**Implement a Clojure agent loop that calls the Claude API directly.**

Rationale:
1. **Minimal dependencies** - No npm, no Node.js, just HTTP
2. **Native Clojure** - Works in Babashka (fast scripts) and JVM (REPL integration)
3. **Full control** - Customize the agent loop for Seon's needs
4. **Existing patterns** - We already have `bin/mcp-server` doing similar work

### Implementation Plan

1. **Create `seon.agent.claude` namespace** (~200 lines):

   ```clojure
   (ns seon.agent.claude
     (:require [clj-http.client :as http]
               [cheshire.core :as json]))

   (defn messages-create [params] ...)
   (defn messages-stream [params on-event] ...)
   (defn tool-loop [params tools max-iterations] ...)

   ```

2. **Define Seon-specific tools** as Clojure data:

   ```clojure
   (def seon-tools
     [{:name "eval"
       :description "Evaluate Clojure in agent session"
       :input_schema {:type "object" :properties {...}}
       :handler (fn [{:keys [session_id code]}] ...)}
      ...])

   ```

3. **Integrate with orchestrator session API**:

   ```clojure
   (defn run-agent-task
     "Run an agent task in an isolated session."
     [{::keys [node namespace task]}]
     (let [{::session/keys [id]} (session/start-agent-session! {...})]
       (try
         (tool-loop {:model "claude-opus-4"
                     :messages [{:role "user" :content task}]
                     :tools seon-tools}
                    id
                    50)
         (finally
           (session/stop-agent-session! {...})))))

   ```

### What We Skip

- **SDK's TypeScript types** - Use Claude API docs instead
- **SDK's streaming helpers** - Parse SSE directly if needed (later)
- **SDK's MCP client** - We use MCP server-side, not client

### Future Considerations

If we later need:
- **Multi-agent coordination** - Consider Option D (TypeScript orchestrator)
- **Complex tool schemas** - Add Malli -> JSON Schema converter
- **Streaming UI** - Implement SSE parsing (~100 lines)

---

## Appendix: Key SDK Source Files

For implementing our own ToolRunner, reference these:

| File | Lines | Purpose |
|------|-------|---------|
| `src/lib/tools/BetaToolRunner.ts` | 474 | Agent loop, tool execution |
| `src/lib/tools/ToolRunner.ts` | 378 | Non-beta version |
| `src/resources/messages/messages.ts` | 3600 | Message types, API methods |
| `src/client.ts` | 1142 | HTTP client, auth, retries |

The core loop logic is in `BetaToolRunner.ts` lines 158-234 (async iterator).

---

## Sources

- [Anthropic TypeScript SDK](https://github.com/anthropics/anthropic-sdk-typescript)
- [nbb (Node Babashka)](https://github.com/babashka/nbb)
- [nbb on npm](https://www.npmjs.com/package/nbb)
- Claude Messages API documentation (https://docs.anthropic.com/claude/reference/)
