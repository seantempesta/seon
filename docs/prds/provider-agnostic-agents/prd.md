# PRD: Provider-Agnostic Agent Framework

**Status:** Draft
**Priority:** High
**Branch:** feature/provider-agnostic-agents

---

## Goals

1. **Provider Abstraction** - Add a new model provider (e.g., Gemini agents) without rewriting agent lifecycle code
2. **Shared Message Format** - All providers produce `::ai/message` entities that can be stored, queried, and analyzed uniformly
3. **Clean Namespace Separation** - SDK process management lives in its own namespace, not mixed with message schemas
4. **Future-Proof** - Architecture accommodates future providers (OpenAI, local models) with minimal changes

---

## Problem Statement

The current AI namespace architecture has several issues:

### Issue 1: `seon.ai.claude` is Too Big (867 lines)

This single file mixes:
- **Schemas** for Claude-specific message types (lines 50-250)
- **SDK process management** (lines 415-492) - spawning, args, env, stdin/stdout
- **Agent lifecycle** (lines 535-740) - launch, registry, monitoring
- **Observatory API** (lines 740-830) - agents, tail, interrupt, get-agent

This violates single-responsibility and makes it hard to extract reusable patterns.

### Issue 2: Message Types Are Claude-Specific

`::claude/sdk-message` and `::claude/message-entity` encode Claude SDK wire format, but the concepts are generic:
- Role (user/assistant/system)
- Content (text, tool calls, tool results)
- Tokens (input/output/cache)
- Timestamp

Gemini has equivalent concepts but different wire format. There's no shared abstraction.

### Issue 3: No Agent Loop Abstraction

The agent loop pattern in `launch-agent!` is:
1. Create session (XTDB, nREPL, ctx)
2. Spawn provider process
3. Send initial prompt
4. Read messages, persist to XTDB
5. Handle completion (result message)
6. Cleanup on close

This loop is provider-agnostic, but it's embedded in Claude-specific code.

### Issue 4: Duplicate Code Across Namespaces

`seon.claude.sdk` and `seon.ai.claude` have significant duplication:
- Both define `spawn-claude-code`
- Both have `build-args`, `build-env`, `write-message!`
- Both maintain agent registries

The deprecation notices indicate awareness of this, but the cleanup isn't complete.

**Impact:** Adding Gemini as an agent provider would require copying 500+ lines of boilerplate, leading to maintenance burden and inconsistency.

---

## Current Architecture Analysis

### What's in `seon.ai` (Generic Base - Good)

| Schema/Function | Purpose | Reusable? |
|-----------------|---------|-----------|
| `::type` | Entity type discriminator | Yes |
| `::role` | "user"/"assistant"/"system" | Yes |
| `::content` | String or structured content | Yes |
| `::timestamp` | Event timestamp | Yes |
| `::session-id` | Session reference | Yes |
| `::input-tokens`, `::output-tokens` | Token counts | Yes |
| `::cost-usd` | Cost tracking | Yes |
| `start-session!`, `end-session!` | Session lifecycle | Yes |
| `add-message!` | Persist messages | Yes |
| `get-session`, `get-messages`, `list-sessions` | Queries | Yes |

**Verdict:** `seon.ai` is well-designed as a provider-agnostic base. Keep it.

### What's in `seon.ai.claude` (Mixed Concerns)

| Component | Lines | Provider-Specific? | Should Move To |
|-----------|-------|-------------------|----------------|
| Claude model enum | 57-61 | Yes | Stay |
| Cache token schemas | 64-70 | Yes | Stay |
| Tool call/result schemas | 73-92 | Partially | Abstract to `seon.ai` |
| `::sdk-message` schema | 152-163 | Yes | `seon.ai.claude.sdk` |
| `::message-entity` | 166-182 | Yes | Stay (extends base) |
| `build-args`, `build-env` | 415-452 | Yes | `seon.ai.claude.sdk` |
| `spawn-claude-code` | 479-491 | Yes | `seon.ai.claude.sdk` |
| `launch-agent!` | 535-738 | Mostly generic | Extract loop to `seon.ai.agent` |
| `agents`, `tail`, `interrupt!` | 740-830 | Generic | `seon.ai.agent` |
| Agent registry | 522 | Generic | `seon.ai.agent` |

### What's in `seon.ai.gemini` (API Client Only)

| Component | Lines | Notes |
|-----------|-------|-------|
| `ask`, `search`, `calculate` | 414-489 | Request/response API, not agent loop |
| `review-code` | 491-574 | Used by dev hook |
| No agent lifecycle | - | Gemini doesn't have CLI SDK |

**Verdict:** Gemini is currently an API client, not an agent provider. If Google releases a Gemini CLI SDK, we'd need to add agent support.

### What's in `seon.claude.sdk` (Deprecated, Duplicated)

This is marked deprecated but still has 900+ lines. Most functionality is duplicated in `seon.ai.claude`. Should be deleted once migration is complete.

---

## Solution Design

### Proposed Namespace Structure

```
seon.ai                    ; Base schemas (existing - keep)
seon.ai.agent              ; Provider-agnostic agent loop (NEW)
seon.ai.claude             ; Claude provider: schemas + impl (refactor)
seon.ai.claude.sdk         ; Claude SDK process management (extract from claude.clj)
seon.ai.gemini             ; Gemini API client (existing - keep)
seon.ai.gemini.agent       ; Gemini agent provider (FUTURE - when SDK available)
```

### 1. Base Schemas to Add to `seon.ai`

```clojure
;; Tool calls are generic across providers
(schema/register! ::tool-call
  [:map
   [:id :string]
   [:name :string]
   [:input {:optional true} :any]])

(schema/register! ::tool-calls
  [:vector ::tool-call])

(schema/register! ::tool-result
  [:map
   [:tool-use-id :string]
   [:content {:optional true} :any]
   [:is-error {:optional true} :boolean]])

(schema/register! ::tool-results
  [:vector ::tool-result])

;; Provider identifier
(schema/register! ::provider
  [:enum :claude :gemini :openai :local])
```

### 2. Agent Protocol in `seon.ai.agent`

```clojure
(ns seon.ai.agent
  "Provider-agnostic agent lifecycle management.

   Defines the agent loop pattern and registry. Providers implement
   the AgentProvider protocol to integrate with this framework.")

;; Provider protocol (multimethod for extensibility)
(defmulti spawn-process
  "Spawn a provider-specific process. Returns process handle map.

   Dispatch on provider keyword (:claude, :gemini, etc.)

   Args: {:provider :claude
          :prompt \"...\"
          :options {...}}"
  :provider)

(defmulti normalize-message
  "Convert provider-specific message to ::ai/message entity.

   Dispatch on [provider message-type]."
  (fn [{:keys [provider message]}]
    [provider (:type message)]))

(defmulti result-message?
  "Check if message is the final result.

   Dispatch on provider keyword."
  :provider)

;; Generic agent registry
(defonce agent-registry (atom {}))

;; Generic agent loop (works with any provider)
(defn launch-agent!
  "Launch an agent using the specified provider.

   Request keys:
     ::ai/node      - XTDB node
     ::ai/namespace - Agent namespace
     ::ai/prompt    - Task description
     ::provider     - :claude, :gemini, etc.
     ::options      - Provider-specific options

   This function:
   1. Creates Seon session (XTDB, nREPL, ctx)
   2. Calls (spawn-process {:provider ...}) - provider-specific
   3. Starts reader loop that:
      - Calls (normalize-message ...) to convert to ::ai/message
      - Persists to XTDB via ai/add-message!
      - Detects completion via (result-message? ...)
   4. Returns agent handle with channels and close!"
  [{::ai/keys [node namespace prompt] ::keys [provider options]}]
  ...)

;; Generic observatory API
(defn agents [] ...)      ; List all agents
(defn tail [...] ...)     ; Stream messages
(defn interrupt! [...] ...) ; Stop agent
(defn get-agent [...] ...) ; Get handle by ID
```

### 3. Claude Provider in `seon.ai.claude`

After extraction, this file contains only Claude-specific concerns:

```clojure
(ns seon.ai.claude
  "Claude provider implementation.

   Extends seon.ai base schemas with Claude-specific attributes.
   Implements seon.ai.agent/AgentProvider protocol."
  (:require [seon.ai :as ai]
            [seon.ai.agent :as agent]
            [seon.ai.claude.sdk :as sdk]))

;; Claude-specific schemas (keep)
(schema/register! ::model ...)
(schema/register! ::cache-creation-tokens ...)
(schema/register! ::message-type ...)
(schema/register! ::message-entity ...) ; Extends ::ai/message-entity

;; Implement spawn-process
(defmethod agent/spawn-process :claude
  [{:keys [prompt options]}]
  (sdk/spawn-claude-code (merge options {:prompt prompt})))

;; Implement normalize-message
(defmethod agent/normalize-message [:claude "assistant"]
  [{:keys [message session-id]}]
  (sdk-message->entity {::sdk-message message
                        ::ai/session-id session-id}))

;; Implement result-message?
(defmethod agent/result-message? :claude
  [{:keys [message]}]
  (= "result" (:type message)))

;; Keep sdk-message->entity for conversion
(defn sdk-message->entity [...] ...)
```

### 4. SDK Process Management in `seon.ai.claude.sdk`

Pure process spawning, no agent lifecycle:

```clojure
(ns seon.ai.claude.sdk
  "Claude Code CLI process management.

   Low-level functions for spawning and communicating with
   the Claude Code CLI. Used by seon.ai.claude provider."
  (:require [clojure.java.process :as process]))

;; Schemas for CLI options
(schema/register! ::cli-command ...)
(schema/register! ::permission-mode ...)
(schema/register! ::mcp-servers ...)

;; Process spawning
(defn build-args [...] ...)
(defn build-env [...] ...)
(defn spawn-claude-code [...] ...)

;; Message I/O
(defn write-message! [...] ...)
(defn parse-line [...] ...)
```

### 5. Message Flow Diagram

```
Provider Process                 seon.ai.agent                    XTDB
     |                                |                             |
     |---(provider wire format)------>|                             |
     |                                |                             |
     |                    normalize-message()                       |
     |                    (converts to ::ai/message)                |
     |                                |                             |
     |                                |---ai/add-message!---------->|
     |                                |                             |
     |                    result-message?()?                        |
     |                    (checks if done)                          |
     |                                |                             |
     |                                |---ai/end-session!---------->|
```

---

## Design Philosophy

**Balance over perfection.** We're building solid bones, not a cathedral.

- **Extract before abstracting** - Move code first, then find patterns
- **One provider is not a pattern** - Don't over-engineer with just Claude
- **Test each phase independently** - No "trust me, it'll work later"
- **Keep Claude working** - Every commit should pass tests

**What we're doing:**
- Moving Claude-specific SDK code to its own namespace
- Creating extension points (multimethods) for future providers
- Establishing shared schemas for cross-provider queries

**What we're NOT doing:**
- Building elaborate provider abstractions we don't need yet
- Creating interfaces that only have one implementation
- Premature optimization for providers that don't exist

---

## Implementation Phases

### Phase 1: Add Generic Tool Schemas to `seon.ai`

**Goal:** Establish shared vocabulary for tool calls across providers.

**Files:** `src/seon/ai.clj`, `test/seon/ai_test.clj`

**Changes:**
1. Add `::tool-call` schema - `{:id :name :input}`
2. Add `::tool-calls` schema - vector of tool calls
3. Add `::tool-result` schema - `{:tool-use-id :content :is-error}`
4. Add `::tool-results` schema - vector of results
5. Add `::provider` enum - `:claude` (extensible later)
6. Update `::message-entity` to include optional `::tool-calls`, `::tool-results`

**Why first:** Pure additive change. Nothing breaks. Sets foundation.

**Tests:** Generative tests for new schemas.

**Commit:** `feat(ai): add generic tool call schemas`

---

### Phase 2: Extract Claude SDK to `seon.ai.claude.sdk`

**Goal:** Separate process management from agent lifecycle.

**Files:**
- `src/seon/ai/claude/sdk.clj` (new)
- `src/seon/ai/claude.clj` (update requires)

**Move these from `seon.ai.claude` → `seon.ai.claude.sdk`:**
- `build-args` - CLI argument construction
- `build-env` - Environment variables
- `spawn-claude-code` - Process spawning
- `write-message!` - Stdin writing
- `parse-line` - Stdout parsing
- `make-user-message` - Message formatting
- CLI-related schemas (`::cli-command`, `::permission-mode`, `::mcp-servers`, etc.)

**Keep in `seon.ai.claude`:**
- Message schemas (`::message-entity`, `::sdk-message`)
- `sdk-message->entity` conversion
- `launch-agent!` and observatory functions (for now)
- Agent registry (for now)

**Why second:** Pure refactor. No behavior change. Existing tests pass.

**Tests:** All existing `seon.ai.claude-test` tests must pass.

**Commit:** `refactor(ai.claude): extract SDK to seon.ai.claude.sdk`

---

### Phase 3: Create `seon.ai.agent` with Claude Implementation

**Goal:** Establish provider extension points with working Claude impl.

**Files:**
- `src/seon/ai/agent.clj` (new)
- `src/seon/ai/claude.clj` (implement multimethods)
- `test/seon/ai/agent_test.clj` (new)

**Create `seon.ai.agent` with:**

```clojure
;; Extension points - just 3 multimethods
(defmulti normalize-message
  "Convert provider message to ::ai/message entity.
   Dispatch: (fn [{:keys [provider]}] provider)"
  :provider)

(defmulti result-message?
  "Is this the final result message?
   Dispatch: (fn [{:keys [provider]}] provider)"
  :provider)

(defmulti parse-result
  "Extract final stats from result message.
   Returns {:status :completed/:failed, :cost-usd, :input-tokens, ...}
   Dispatch: (fn [{:keys [provider]}] provider)"
  :provider)
```

**Implement for Claude in `seon.ai.claude`:**

```clojure
(defmethod agent/normalize-message :claude [{:keys [message session-id]}]
  (sdk-message->entity {::sdk-message message ::ai/session-id session-id}))

(defmethod agent/result-message? :claude [{:keys [message]}]
  (= "result" (:type message)))

(defmethod agent/parse-result :claude [{:keys [message]}]
  {:status (if (= "success" (:subtype message)) :completed :failed)
   :cost-usd (:total_cost_usd message)
   ...})
```

**Keep `launch-agent!` in `seon.ai.claude` for now.** It calls the multimethods internally but the function stays where it is. We're not moving it to `seon.ai.agent` until we have a second provider that needs it.

**Why together:** Can't test multimethods without an implementation. Testing them together ensures they work.

**Tests:**
- Test `normalize-message` with various Claude message types
- Test `result-message?` detection
- Test `parse-result` extraction
- Existing `launch-agent!` tests still pass

**Commit:** `feat(ai.agent): add provider multimethods with Claude impl`

---

### Phase 4: Move Agent Registry to `seon.ai.agent`

**Goal:** Centralize agent tracking for cross-provider observability.

**Files:**
- `src/seon/ai/agent.clj` (add registry + observatory)
- `src/seon/ai/claude.clj` (use shared registry)

**Move to `seon.ai.agent`:**
- `agent-registry` atom
- `agents` function (list all)
- `get-agent` function (by ID)
- `tail` function (stream messages)
- `interrupt!` function (stop agent)

**Update `seon.ai.claude`:**
- Remove local `agent-registry`
- `launch-agent!` registers in `agent/agent-registry`
- Keep `launch-agent!` implementation in claude.clj

**Why now:** Registry is genuinely generic. Observatory functions work across providers.

**Tests:** Existing observatory tests pass with new locations.

**Commit:** `refactor(ai.agent): centralize agent registry and observatory`

---

### Phase 5: Delete Deprecated Namespaces

**Goal:** Remove dead code.

**Files to delete:**
- `src/seon/claude/sdk.clj` (~1000 lines)
- `src/seon/claude/conversation.clj` (~600 lines)
- `test/seon/claude/sdk_test.clj`

**Keep:**
- `src/seon/claude/exploration.clj` (research tool, explicitly not deprecated)

**Verify:** No remaining references to deleted namespaces.

**Commit:** `chore: delete deprecated seon.claude.sdk and conversation`

---

### Phase 6: Documentation

**Goal:** Update docs to reflect new structure.

**Files:**
- `CLAUDE.md` - Update AI namespace section
- `CONVENTIONS.md` - Add provider multimethod pattern
- Namespace docstrings

**Commit:** `docs: update AI namespace documentation`

---

## Constraints

- **Must not break existing functionality** - `seon.ai.claude/launch-agent!` must continue to work
- **Must be backwards compatible** - Existing message schemas must remain valid
- **Must follow CONVENTIONS.md** - Map-in/map-out, namespaced keys, Malli schemas
- **Must be REPL-friendly** - All functions work from REPL
- **Must not spawn processes in tests** - Use mocks for generative testing

---

## Success Criteria

1. **Clean separation achieved:**
   - SDK process code in `seon.ai.claude.sdk`
   - Message conversion in `seon.ai.claude`
   - Agent registry in `seon.ai.agent`
   - Each namespace has single responsibility

2. **Extension points established:**
   - 3 multimethods defined (`normalize-message`, `result-message?`, `parse-result`)
   - Claude implementation works via multimethods
   - Adding Gemini later means implementing these 3 methods

3. **Deprecated code deleted:**
   - `seon.claude.sdk` removed (~1000 lines)
   - `seon.claude.conversation` removed (~600 lines)
   - Net reduction of ~1600 lines

4. **All tests pass:**
   - Every phase leaves tests green
   - New schemas have generative tests
   - Existing functionality unchanged

---

## Deliverables

- [x] `seon.ai` - Add tool schemas (Phase 1)
- [x] `seon.ai.claude.sdk` - Extract SDK process management (Phase 2)
- [x] `seon.ai.agent` - Provider multimethods (Phase 3)
- [x] `seon.ai.claude` - Implement multimethods (Phase 3)
- [x] Agent registry in `seon.ai.agent` (Phase 4)
- [ ] Delete deprecated namespaces (Phase 5)
- [ ] Updated documentation (Phase 6)

---

## Phase 3 Implementation Notes

**Completed:** 2026-01-19

### Files Created/Modified

1. **`src/seon/ai/agent.clj`** (new)
   - Three multimethods: `normalize-message`, `result-message?`, `parse-result`
   - Each dispatches on `:provider` key
   - Default implementations throw helpful errors for unknown providers
   - `::parsed-result` schema for normalized result data

2. **`src/seon/ai/claude.clj`** (modified)
   - Added require for `seon.ai.agent`
   - Implemented all three multimethods for `:claude` provider
   - Fixed schema references: `::permission-mode` etc. now properly reference `::sdk/permission-mode`
   - Updated `launch-agent!` destructuring to use `::sdk/keys` for SDK options

3. **`test/seon/ai/agent_test.clj`** (new)
   - 20 tests covering all multimethods
   - Schema registration and generation tests
   - Claude implementation tests for various message types
   - Default implementation error tests

### Design Decisions

1. **Dispatch on `:provider` keyword** - Simple and extensible. Adding a new provider requires implementing three defmethods.

2. **Comments instead of docstrings for defmethod** - Clojure defmethod doesn't support docstrings directly, so documentation is in comments above each implementation.

3. **`::parsed-result` schema in agent.clj** - Normalized result structure that all providers return from `parse-result`, enabling provider-agnostic result handling.

4. **Keep `launch-agent!` in `seon.ai.claude`** - Per PRD guidance, the agent loop stays in provider namespace until we have multiple providers needing shared orchestration.

---

## Phase 4 Implementation Notes

**Completed:** 2026-01-19

### Files Modified

1. **`src/seon/ai/agent.clj`** (extended)
   - Added `agent-registry` atom for cross-provider agent tracking
   - Added observatory schemas: `::agent-status`, `::session-id`, `::namespace`, `::provider`, `::agent-summary`, `::interrupt-request`, `::interrupt-response`, `::agents-request`, `::agents-response`
   - Added `agents` function - list all running agents across providers
   - Added `get-agent` function - get handle by session ID
   - Added `tail` function - get messages channel for observing
   - Added `interrupt!` function - stop any agent via its close! function

2. **`src/seon/ai/claude.clj`** (modified)
   - Removed local `agent-registry` atom
   - Updated `launch-agent!` to register in `agent/agent-registry` with standardized handle structure
   - Handle now includes both `::agent/` prefixed keys (for shared registry) and legacy `::ai/` aliases (for backwards compatibility)
   - Updated `agents`, `tail`, `interrupt!`, `get-agent` to delegate to `seon.ai.agent` versions
   - Claude-specific functions filter for `:claude` provider and map response keys for backwards compatibility

3. **`test/seon/ai/agent_test.clj`** (extended)
   - Added `reset-registry-fixture` to clean registry between tests
   - Added schema registration and generation tests for observatory schemas
   - Added `agents-empty-test`, `agents-with-registered-agent-test`, `agents-multiple-providers-test`
   - Added `get-agent-not-found-test`, `get-agent-found-test`
   - Added `tail-not-found-test`, `tail-returns-channel-test`
   - Added `interrupt-not-found-test`, `interrupt-calls-close-test`, `interrupt-no-close-fn-test`, `interrupt-exception-test`

### Design Decisions

1. **Standardized handle structure** - Agent handles must include `::agent/session-id`, `::agent/namespace`, `::agent/provider`, `::agent/status-atom`, `::agent/close!`, and `::agent/messages-ch`. Optional fields: `::agent/nrepl-port`, `::agent/ai-session-id`.

2. **Backwards compatibility in seon.ai.claude** - Claude functions delegate to `seon.ai.agent` but map response keys to Claude-specific namespace. This allows existing code using `claude/agents` to continue working.

3. **`interrupt!` uses close! function** - Provider-agnostic interruption. Each provider's `launch-agent!` sets up its own close! function that handles provider-specific cleanup (process destruction, channel closing, session ending).

4. **Dual-key handles** - Claude handles include both `::agent/` and `::claude/`/`::ai/` keys. This allows both the shared registry functions and Claude-specific code to access handle fields without breaking changes.

---

## Future Work (Out of Scope)

- **Gemini Agent Provider** - When Google releases CLI SDK
- **OpenAI Agent Provider** - If needed
- **Local Model Provider** - For privacy-sensitive tasks
- **Multi-Agent Orchestration** - Agents collaborating across providers
- **Cost Analytics Dashboard** - Cross-provider cost visualization
