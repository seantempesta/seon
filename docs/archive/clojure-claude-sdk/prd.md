> **Status: ARCHIVED** — Complete — SDK is live

> **Status: ARCHIVED** — Complete — SDK is live

# PRD: Clojure Claude SDK

**Status:** Phase 3 Complete (Session Integration)
**Priority:** Medium
**Branch:** feature/agent-isolation

---

## Goals

1. **Native Clojure orchestration** - Spawn and control Claude Code agents from JVM
2. **Zero external dependencies** - Use clojure.java.process (1.12+) and existing libs
3. **Schema-first API** - Malli schemas for all public interfaces per CONVENTIONS.md
4. **Integration with Seon sessions** - Work alongside existing MCP-based agent isolation

---

## Problem Statement

Currently, Seon agents are invoked via Claude Code calling our MCP server. This works well but limits orchestration to the Claude Code process. A native Clojure SDK would enable:

- **JVM-initiated agents** - Run agents from Clojure code, tests, or scheduled jobs
- **Programmatic control** - Dynamic model/permission changes during execution
- **Custom hooks** - PreToolUse/PostToolUse callbacks in Clojure
- **Tighter integration** - Direct access to agent state without MCP indirection

**Impact:** Enables new orchestration patterns while maintaining backward compatibility with existing MCP approach.

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `docs/prds/agent-isolation/research/clojure-sdk-interface.md` | Protocol spec, verified tests |
| `docs/prds/agent-isolation/research/process-library-comparison.md` | clojure.java.process vs babashka.process |
| `/private/tmp/package/sdk.d.ts` | TypeScript SDK types for reference |
| `CONVENTIONS.md` | Malli schema patterns, API design |

---

## Solution Design

### Architecture

```
                    +-----------------+
                    |   User Code     |
                    +--------+--------+
                             |
                    +--------v--------+
                    | seon.claude.sdk |  <- Clojure SDK
                    +--------+--------+
                             | stdin/stdout (JSON-RPC)
                    +--------v--------+
                    |  Claude Code    |  <- Node.js CLI
                    |     cli.js      |
                    +--------+--------+
                             |
                    +--------v--------+
                    |  MCP Servers    |  <- seon, context7, etc.
                    +-----------------+
```

### Key Components

1. **Process Management** - Spawn/destroy Claude Code via clojure.java.process
2. **Message Protocol** - JSON-RPC over stdin/stdout
3. **Query API** - Simple interface for prompts with async message streaming
4. **Control Requests** - Model/permission changes, interrupts
5. **Session Integration** - Optional integration with seon.orchestrator.session

### API Design (Malli Schemas)

```clojure
(ns seon.claude.sdk
  (:require [seon.schema :as schema]))

;;; Schema Registration

(schema/register! ::model
  [:enum "claude-opus-4-5-20251101" "claude-sonnet-4-20250514"
   {:description "Claude model identifier. Prefer Opus for complex tasks."}])

(schema/register! ::permission-mode
  [:enum "default" "acceptEdits" "bypassPermissions" "plan" "dontAsk"])

(schema/register! ::query-options
  [:map
   [::model {:optional true} ::model]
   [::cwd {:optional true} :string]
   [::permission-mode {:optional true} ::permission-mode]
   [::allowed-tools {:optional true} [:vector :string]]
   [::disallowed-tools {:optional true} [:vector :string]]
   [::mcp-servers {:optional true} [:map-of :keyword :map]]
   [::max-turns {:optional true} :int]
   [::max-budget-usd {:optional true} :double]])

(schema/register! ::message-type
  [:enum "system" "assistant" "user" "result" "stream_event" "tool_progress"])

(schema/register! ::sdk-message
  [:map
   [:type ::message-type]
   [:session_id {:optional true} :string]
   [:uuid {:optional true} :string]])

(schema/register! ::result-message
  [:map
   [:type [:= "result"]]
   [:subtype [:enum "success" "error_during_execution" "error_max_turns" "error_max_budget_usd"]]
   [:result {:optional true} :string]
   [:num_turns :int]
   [:total_cost_usd :double]
   [:duration_ms :int]])

(schema/register! ::query-handle
  [:map
   [::messages-ch :any]  ; core.async channel
   [::result-ch :any]    ; core.async channel
   [::send! fn?]
   [::close! fn?]])
```

### Public API

```clojure
(defn query
  "Execute a Claude Code query. Returns a query handle for streaming messages.

   Request keys:
     ::prompt  - Required. The prompt text
     ::options - Optional. Query options map

   Response keys (query handle):
     ::messages-ch - Channel of SDK messages
     ::result-ch   - Channel receiving final result
     ::send!       - Function to send follow-up messages
     ::close!      - Function to terminate the query

   Example:
     (let [handle (query {::prompt \"What is 2+2?\"})]
       (go-loop []
         (when-let [msg (<! (::messages-ch handle))]
           (println (:type msg))
           (recur)))
       (<!! (::result-ch handle)))"
  {:malli/schema [:=> [:cat ::query-request] ::query-handle]}
  [{::keys [prompt options]}]
  ...)

(defn exec
  "Execute a query and block until completion. Returns the result message.

   Convenience wrapper around `query` for simple use cases.

   Example:
     (exec {::prompt \"List files in src/\"
            ::options {::model \"claude-opus-4-5-20251101\"
                       ::max-turns 5}})"
  {:malli/schema [:=> [:cat ::query-request] ::result-message]}
  [{::keys [prompt options]}]
  ...)
```

---

## Constraints

- **Clojure 1.12+** - Uses clojure.java.process (already in deps.edn)
- **Node.js required** - Claude Code CLI is JavaScript
- **No new dependencies** - Use cheshire, core.async (already available)
- **REPL-friendly** - All functions work in REPL without restart
- **Backward compatible** - Existing MCP-based agents continue to work
- **Default to Opus** - Use `claude-opus-4-5-20251101` as default model for best results

---

## Success Criteria

1. **Query execution works** - Can send prompts and receive responses
2. **Multi-turn works** - Can send follow-up messages with context preserved
3. **Tool use works** - Tools execute and return results
4. **Malli schemas validate** - All inputs/outputs match schemas
5. **Tests pass** - Generative tests with schema generators
6. **REPL-friendly** - Can test interactively without restart

---

## Phases

### Phase 1: Core Query API (2-3 hours) - COMPLETE

- [x] Create `seon.claude.sdk` namespace
- [x] Implement `spawn-claude-code` using clojure.java.process
- [x] Implement `query` function with message streaming
- [x] Implement `exec` convenience function
- [x] Add Malli schemas per CONVENTIONS.md (18 schemas registered)
- [x] REPL-verified: query and exec work correctly
- [x] Made `spawn-claude-code` private (bug fix - see below)
- [x] Added `:malli/schema` metadata with non-generatable `::prompt` schema

**Bug Fix (2026-01-09)**: Discovered that exposing `spawn-claude-code` publicly led to
misuse where agents called it directly and used blocking IO (`slurp`) on the subprocess
streams. This blocked nREPL threads indefinitely. Fixed by making `spawn-claude-code`
private - the public API (`query`/`exec`) properly uses futures and core.async.

See `docs/prds/agent-isolation/prd.md` Phase 4c for full postmortem.

### Phase 2: Control Requests (2-3 hours)

**Research Completed**: 2026-01-19

See `bidirectional-control.md` for full protocol documentation including:
- Captured message formats (system init, user, assistant, result)
- Multi-turn conversation pattern using AsyncIterable
- Session resume with `--resume <session-id>` flag
- TypeScript SDK V2 API patterns

- [ ] Implement `set-model!` for dynamic model changes
- [ ] Implement `set-permission-mode!` for permission changes
- [ ] Implement `interrupt!` for stopping execution
- [ ] Add control request/response handling
- [ ] Test control flow

### Phase 3: Session Integration (3-4 hours) - COMPLETE

**Completed**: 2026-01-14

- [x] Integrate with existing `seon.orchestrator.session` API
- [x] Auto-create nREPL sessions for launched Claude Code agents
- [x] Pass session context (db, namespace, nREPL port) to agents via MCP
- [x] Map Claude Code sessions to Seon sessions bidirectionally
- [x] Handle session lifecycle (create, stop, list)
- [x] Ensure agents can use `mcp__seon__eval` with their assigned session

**Implementation**: Added to `src/seon/claude/sdk.clj`:
- `launch-agent!` - Creates Seon session, spawns Claude Code, configures MCP
- `terminate-agent!` - Cleans up Claude process and Seon session
- `list-agents` - Lists active agents with status
- Agent registry for tracking active agents
- Session instructions embedded in agent prompt

### Phase 4: Hooks & Events (3-4 hours)

- [ ] Implement `initialize` control request for hooks
- [ ] Add PreToolUse callback support
- [ ] Add PostToolUse callback support
- [ ] Add SessionStart/SessionEnd hooks
- [ ] Test hook invocation

### Phase 5: Production Hardening (2-3 hours)

- [ ] Comprehensive error handling
- [ ] Timeout management
- [ ] Resource cleanup (process termination, channel closing)
- [ ] Logging integration
- [ ] Documentation

---

## Deliverables

- [ ] `src/seon/claude/sdk.clj` - Core SDK implementation
- [ ] `test/seon/claude/sdk_test.clj` - Unit and generative tests
- [ ] Updated research docs with final implementation notes
- [ ] Integration with existing session API

---

## References

- [clojure.java.process API](https://clojure.github.io/clojure/clojure.java.process-api.html)
- [Claude Agent SDK Types](/private/tmp/package/sdk.d.ts)
- [Protocol Research](../agent-isolation/research/clojure-sdk-interface.md)
- [Process Library Comparison](../agent-isolation/research/process-library-comparison.md)
