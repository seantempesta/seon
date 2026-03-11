---
type: component
status: stable
tags: [component, agent]
---
# Agent System

> Provider-agnostic AI agent lifecycle — launch, observe, persist, and interrupt Claude agents running in isolated JVM processes.

## Purpose

The agent system manages the full lifecycle of AI agents: spawning isolated JVM processes with nREPL, routing prompts through the Claude Code CLI, persisting every conversation message to Datalevin, and providing real-time observability through the Observatory UI. It is designed to be provider-agnostic at the multimethod layer, with Claude as the primary (and currently only active) provider.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.ai` | `src/seon/ai.clj` | Base schemas (session, message, role, tokens, cost) and public API: `start-session!`, `end-session!`, `add-message!`, `get-session`, `list-sessions`, `session-stats` |
| `seon.ai.agent` | `src/seon/ai/agent.clj` | Provider-agnostic multimethods (`normalize-message`, `result-message?`, `parse-result`), agent registry atom, Observatory API (`agents`, `tail`, `interrupt!`, `get-agent`, `shutdown-all!`) |
| `seon.ai.claude` | `src/seon/ai/claude.clj` | Claude provider: `launch-agent!` / `launch-agent!!`, multimethod implementations, SDK message-to-entity conversion, file context reading, message processing loop |
| `seon.ai.claude.sdk` | `src/seon/ai/claude/sdk.clj` | Low-level CLI process management: `spawn-claude-code`, `build-args`, `build-env`, `write-message!`, `parse-line`, `make-user-message` |
| `seon.ai.gemini` | `src/seon/ai/gemini.clj` | Gemini provider: `ask`, `search`, `calculate`, `review-code` — synchronous HTTP API, not an agent provider (used for code review in dev hook and REPL queries) |
| `seon.ai.datalevin` | `src/seon/ai/datalevin.clj` | Datalevin storage: fire-and-forget writes for sessions/messages, Datalog queries for Observatory, entity conversion, session stats aggregation |
| `seon.ai.agent.log` | `src/seon/ai/agent/log.clj` | Per-agent structured logging to `logs/agents/{session-id}.log` — LAUNCH, MESSAGE, TOOL, RESULT, HOOK, COMPLETE, ERROR events |
| `seon.ai.agent.views` | `src/seon/ai/agent/views.clj` | Multimethod view renderers for agent data types: summary rows, detail pages, tool-specific log line rendering with 3-tier display (inline/hover/expanded) |
| `seon.orchestrator.session` | `src/seon/orchestrator/session.clj` | High-level session abstraction: `start-agent-session!` (ctx + pool JVM + nREPL), `stop-agent-session!`, activity tracking, session recovery on restart |
| `seon.agent.env` | `src/seon/agent/env.clj` | Agent environment toolkit: graph search wrappers, schema discovery (`related-schemas`, `who-produces`, `who-consumes`), context persistence (`ctx-save!`, `ctx-load`) |
| `seon.agent.helpers` | `src/seon/agent/helpers.clj` | Agent SQL helpers with `*ctx*` binding — currently all throw "not yet migrated to Datalevin" |

## Public API Surface

**Agent lifecycle** (`seon.ai.claude`):

- `launch-agent!` — spawn Claude agent with isolated session, nREPL, MCP, file context. Returns handle with channels and close function
- `launch-agent!!` — blocking variant, waits for completion and returns parsed result
- Options: `::ai/namespace`, `::ai/prompt`, `::model`, `::files`, `::sdk/permission-mode`, `::sdk/max-turns`, `::sdk/max-budget-usd`, `::sdk/allowed-tools`, `::sdk/disallowed-tools`, `::sdk/chrome`, `::ai/force?` (skip duplicate-namespace guard)

**Observatory** (`seon.ai.agent`):

- `agents` — list all running agents across providers (session-id, namespace, provider, status, port, cost)
- `get-agent` — get full agent handle by session ID
- `tail` — get core.async messages channel for real-time observation
- `interrupt!` — stop a running agent via its close function
- `shutdown-all!` — stop all agents (called before Integrant reset)

**Session management** (`seon.ai`):

- `start-session!` / `end-session!` — AI conversation session lifecycle in Datalevin
- `add-message!` — persist a message to a session
- `get-session` / `get-messages` / `list-sessions` / `session-stats` — query stored data

**Orchestrator sessions** (`seon.orchestrator.session`):

- `start-agent-session!` — creates persisted ctx atom, claims pool JVM, starts nREPL
- `stop-agent-session!` — flushes ctx, releases pool JVM
- `get-agent-session` / `list-agent-sessions` / `get-session-port`
- `record-eval-start!` / `record-eval-complete!` — activity tracking
- `recover-sessions!` — marks orphaned sessions as stopped after restart

**Gemini** (`seon.ai.gemini`):

- `ask` — simple text generation (model knowledge only)
- `search` — web-grounded answers with optional file context
- `calculate` — Python code execution via Gemini
- `review-code` — structured code review with conventions caching

## Dependencies

**Uses:**

- Claude Code CLI — spawned as child process via `clojure.java.process`, stream-json I/O
- core.async — message channels, mult for broadcasting, result channels
- Integrant — session initialization wired through system map
- [[components/database]] — session and message persistence via `seon.ai.datalevin`
- [[components/schema-system]] — schema registration for all entities
- [[components/context]] — persisted ctx atoms for agent state
- [[components/flow-topology]] — pool of pre-warmed JVM processes with nREPL
- `seon.runtime` — ID generation, instance registry
- `seon.ns.view` — multimethod rendering system for agent views
- Hato — HTTP client for Gemini API
- Cheshire — JSON parsing for SDK messages and Gemini responses

**Used by:**

- [[components/web-layer]] — Observatory pages (`seon.web.agents`), dashboard agent count
- Orchestrator — launches agents via `claude/launch-agent!`
- [[components/dev-tools]] — calls `gemini/review-code` for AI code review

## How Data Flows

### Agent Launch Sequence

1. **`claude/launch-agent!`** receives namespace + prompt
2. Calls `session/start-agent-session!` which:
   - Generates 6-char hex session ID
   - Creates persisted ctx atom via `seon.ctx/create!`
   - Claims a pool JVM via `seon.flow.pool/claim!` (gets nREPL port)
   - Registers in runtime registry
3. Starts AI session in Datalevin via `ai/start-session!`
4. Reads file context (if `::files` provided) and builds prompt with AGENT.md instructions
5. Builds MCP config pointing at the agent's nREPL port
6. Spawns Claude Code CLI process via `sdk/spawn-claude-code`
7. Sends initial user message via `sdk/write-message!`
8. Starts virtual thread to read stdout lines, parse JSON, and:
   - Normalize each message via `agent/normalize-message` multimethod
   - Persist to Datalevin via `ai/add-message!`
   - Log to `logs/agents/{id}.log` via `agent-log/log-sdk-message!`
   - Put on messages channel (for `tail`)
   - Check for result via `agent/result-message?`
9. Registers agent handle in `agent/agent-registry` atom
10. Returns handle with `::messages-ch`, `::result-ch`, `::status-atom`, `::close!`

### Message Processing

```
Claude CLI stdout -> parse-line (JSON) -> normalize-message (multimethod)
  -> add-message! (Datalevin)
  -> log-sdk-message! (file log)
  -> messages-ch (core.async)
  -> result-message? -> if true: parse-result, end-session!, put result-ch

```

### Provider Dispatch

All multimethods dispatch on `:provider` key:

- `normalize-message` — converts SDK message format to `::ai/message-entity` with Claude-specific fields (cache tokens, tool calls, message type)
- `result-message?` — checks `{:type "result"}` in SDK message
- `parse-result` — extracts status, cost, tokens, turns, duration, result text from completion message

## Design Decisions

**Provider-agnostic multimethods**: The `seon.ai.agent` namespace defines the protocol; providers implement it. This means the Observatory, registry, and tail/interrupt work identically regardless of provider. Currently only Claude is implemented, but the architecture supports adding other providers.

**Isolated JVM processes**: Each agent runs in its own JVM from the pool (`seon.flow.pool`), with its own nREPL server. This provides complete isolation — agents cannot corrupt each other's state, and a crashed agent doesn't affect the orchestrator.

**Fire-and-forget persistence**: `seon.ai.datalevin` writes are best-effort — errors are logged but never propagated. This prevents a Datalevin hiccup from killing a running agent. Stats are tracked via `stats-atom` for monitoring.

**Dual ID system**: Each agent has both a 6-char Base62 "Seon session ID" (used for log files, MCP routing, display) and a longer "AI session ID" (ses-xxx, used for Datalevin entity references). The mapping is stored in the session entity.

**File context in prompt**: The `::files` option reads files at launch time and embeds their content directly in the agent's prompt. This is simpler than having agents discover files themselves and ensures they start with the right context.

**Structured per-agent logs**: Each agent gets `logs/agents/{id}.log` with pipe-delimited structured lines. These are both human-readable (`tail -f`) and machine-parseable (the Observatory parses them for the detail view). Tool ID-to-name tracking across messages ensures RESULT lines show actual tool names.

**Gemini as utility, not agent**: `seon.ai.gemini` provides synchronous HTTP calls for code review, web search, and calculations. It does not implement the agent multimethods — it's a tool, not an autonomous agent.

**Monotonic sequence numbers**: Messages within the same millisecond are ordered by a global `msg-sequence` atom, ensuring deterministic ordering in Datalevin queries.

## Refactoring Opportunities

- **`seon.agent.helpers`** — SQL functions all throw "not yet migrated to Datalevin". This is dead code that should either be migrated to Datalevin query helpers or removed entirely
- **`seon.ai.datalevin` dl-* functions** use positional args (internal pattern) but are de-facto public API consumed by `seon.web.agents` and `seon.ai.claude`. Migrating to map-in would break consumers but improve consistency
- **`seon.ai.datalevin` entity conversion** manually handles nil filtering and Instant-to-Date coercion — this could be unified with the schema bridge
- **`:any` in tool schemas** — `::tool-call` and `::tool-result` have `:any` for `:input` and `:content` fields. The wire protocol carries arbitrary data, but this violates the "no `:any`" rule
- **`seon.ai.claude`** is ~1370 lines — `launch-agent!` alone is 300+ lines. The prompt building, MCP config, and message processing loop could each be extracted
- **`seon.orchestrator.session`** uses `[:maybe ...]` in some schemas despite the project convention preferring `{:optional true}`
- **Gemini model list** hardcodes `"gemini-3-flash-preview"` and `"gemini-3-pro-preview"` as enums — these will need updating as models change
