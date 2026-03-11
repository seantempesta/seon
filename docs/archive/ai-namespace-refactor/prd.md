---
type: prd
status: completed
tags: [prd, archive, agent]
---

> **Status: ARCHIVED** — Complete — Phase 6 done

> **Status: ARCHIVED** — Complete — Phase 6 done

# PRD: AI Namespace Refactor

**Status:** Phase 6 Complete (Gemini aligned with base schemas)
**Priority:** High
**Branch:** feature/ai-namespace-refactor
**Supersedes:** Conversation persistence from `docs/prds/clojure-claude-sdk/bidirectional-control.md`

---

## Vision

Create a clean, provider-agnostic AI namespace hierarchy where:

1. **`seon.ai`** defines common schemas and entity functions (sessions, messages, roles)
2. **`seon.ai.claude`** extends with Claude-specific attributes and SDK integration
3. **`seon.ai.gemini`** (future) refactored to use common base

The key insight: in XTDB/Datomic, an entity is just a bag of namespaced attributes. A message entity can have both `:seon.ai/role` (generic) and `:seon.ai.claude/cache-tokens` (provider-specific) on the same entity.

---

## Namespace Hierarchy

```
seon.ai                 ; Base schemas + entity persistence
├── seon.ai.claude      ; Claude SDK integration
└── seon.ai.gemini      ; Gemini integration (future refactor)

```

### seon.ai (base namespace)

**Schemas:**
- `::type` - Entity type: `:session`, `:message`, `:tool-call`
- `::role` - Message role: `"user"`, `"assistant"`, `"system"`
- `::content` - Message content (string or structured)
- `::timestamp` - When the event occurred
- `::session-id` - Reference to parent session
- `::status` - Status: `:active`, `:completed`, `:failed`
- `::input-tokens`, `::output-tokens`, `::cost-usd` - Usage tracking
- `::namespace` - Clojure namespace context
- `::prompt` - Initial prompt that started a session

**Functions (AI domain operations, not generic CRUD):**
- `(start-session! {::db db ::namespace 'seon.trading ::prompt "..."})` - Create AI session
- `(end-session! {::db db ::session-id "..." ::status :completed})` - Close session with stats
- `(add-message! {::db db ::session-id "..." ::role "assistant" ::content "..."})` - Add message
- `(get-session {::db db ::session-id "..."})` - Get session with summary stats
- `(get-messages {::db db ::session-id "..."})` - Get all messages for session
- `(list-sessions {::db db ::limit 20})` - List recent sessions
- `(search-sessions {::db db ::namespace 'seon.trading ::since #inst "..."})` - Search/filter

### seon.ai.claude (provider extension)

**Schemas (extending base):**
- `::model` - Claude model identifier
- `::cache-creation-tokens`, `::cache-read-tokens` - Prompt caching
- `::tool-calls`, `::tool-results` - Tool use blocks
- `::message-type` - SDK message type ("assistant", "result", etc.)
- `::uuid` - SDK-assigned message UUID
- `::raw-message` - Full SDK message for debugging

**Functions:**
- `(launch-agent! {...})` - Spawn Claude agent with session
- `(agents {})` - List running agents
- `(interrupt! {::.../session-id "..."})` - Stop agent
- `(sdk-message->entity msg)` - Convert SDK message to entity

---

## XTDB Usage

### Mental Model: Entities, Not Tables

Think Datomic-style: everything is an entity with attributes. The "table name" in XTDB is just a type tag for SQL queries.

```clojure
;; This is an ENTITY, not a "row in a table"
{:xt/id "session-abc123"
 :seon.ai/type :session           ; Type tag (like Datomic entity type)
 :seon.ai/started-at #inst "..."
 :seon.ai/status :active
 :seon.ai.claude/model "opus"}    ; Provider-specific on same entity

```

### Use Existing seon.db.node

We already have `seon.db.node` with generic XTDB operations:
- `(db/put! node table entity)` - Insert entity
- `(db/entity node id)` - Get entity by ID
- `(db/query node sql-string)` - Execute SQL query
- `(db/delete! node table id)` - Delete entity

The `seon.ai` namespace uses these directly - no new abstractions needed:

```clojure
(ns seon.ai
  (:require [seon.db.node :as db]))

(defn start-session!
  [{::keys [node] :as request}]
  (let [session-id (generate-session-id)
        entity {:xt/id session-id
                ::type :session
                ::started-at (Instant/now)
                ;; ... other attrs from request
                }]
    (db/put! node :ai_sessions entity)
    {::session-id session-id}))

```

---

## Implementation Phases

### Phase 1: Create seon.ai Base Namespace [COMPLETED]

**Goal:** Establish the foundation with schemas and AI-domain functions.

**Deliverables:**
- `src/seon/ai.clj` with:
  - Schema registrations for all generic AI attributes (type, role, content, etc.)
  - `start-session!` - Create new AI session
  - `end-session!` - Close session with final stats
  - `add-message!` - Add message to session
  - `get-session` - Get session by ID
  - `get-messages` - Get messages for session
  - `list-sessions` - List recent sessions
  - Uses `seon.db.node` for XTDB operations (no reinventing)

**Tests:**
- Generate sample sessions/messages with `mg/generate`
- Round-trip: start-session -> add-message -> get-messages -> validate
- Query functions return valid entities matching schemas

**Implementation Notes:**
- Functions that take `::node` (XTDB node) do not have `:malli/schema` metadata because
  the node type cannot be generated for property testing. Schemas are documented in file.
- Namespace is stored as string (not symbol) for XTDB compatibility
- XTDB returns ZonedDateTime for timestamps (not Instant) - tests use `temporal?` helper

**Commit:** "feat: add seon.ai base namespace with session/message functions"

---

### Phase 2: Create seon.ai.claude Provider Namespace [COMPLETED]

**Goal:** Move Claude-specific code, referencing seon.ai base schemas.

**Deliverables:**
- `src/seon/ai/claude.clj` with:
  - Claude-specific schema registrations (extending base)
  - `sdk-message->entity` converter
  - `launch-agent!` (moved from sdk.clj, uses new schemas)
  - `agents`, `interrupt!`, `tail` (moved from sdk.clj)

**Key Pattern:** Claude schemas reference base schemas:

```clojure
(ns seon.ai.claude
  (:require [seon.ai :as ai]
            [seon.schema :as schema]))

;; Claude-specific, but session-id uses base schema
(schema/register! ::agent-handle
  [:map
   [::ai/session-id ::ai/session-id]  ; Reference base!
   [::model ::model]                   ; Claude-specific
   [::nrepl-port :int]])

```

**Tests:**
- Launch agent -> verify session entity created in XTDB
- SDK messages -> entities -> validate schemas
- Agent lifecycle (launch, monitor, terminate)

**Implementation Notes:**
- Functions that spawn processes or require XTDB nodes do not have `:malli/schema` metadata
  because those types cannot be generated for property testing. Schemas documented in docstrings.
- Added generator to `::ai/timestamp` schema to enable generation of message entities
- Cleared stale Malli function-schemas cache during development (was retaining old metadata)
- `sdk-message->entity` uses map-in pattern: `{::claude/sdk-message msg}` -> entity

**Commit:** "feat: add seon.ai.claude provider namespace"

---

### Phase 3: Wire Auto-Persistence into Agent Lifecycle [COMPLETED]

**Goal:** Automatically persist all messages during agent execution.

**Deliverables:**
- Modify `launch-agent!` to:
  - Call `(ai/start-session! {...})` when agent starts
  - Create message persister that calls `(ai/add-message! {...})` for each SDK message
  - Wire persister into the message reader loop
  - Call `(ai/end-session! {...})` on completion with final stats

**Tests:**
- Launch agent with simple task
- Query XTDB for session and messages
- Verify message count, token totals, cost tracking

**Implementation Notes:**
- `launch-agent!` creates AI session via `ai/start-session!` immediately after Seon session
- Reader loop uses `persist-message!` helper that converts SDK messages to entities
- `persistable-message-type?` filters out keep_alive and parse_error messages
- On "result" message, calls `ai/end-session!` with final status and cost
- On reader error or interrupt, session ends with :failed or :interrupted status
- Agent handle includes `::ai-session-id` for querying conversation history
- Tests cover full lifecycle without spawning real agents (unit-testable)

**Commit:** "feat: auto-persist agent conversations to XTDB"

---

### Phase 4: Deprecate Old Namespaces [COMPLETED]

**Goal:** Clean up and redirect old code.

**Deliverables:**
- Add deprecation notices to:
  - `seon.claude.sdk` -> use `seon.ai.claude`
  - `seon.claude.conversation` -> use `seon.ai`
  - `seon.claude.exploration` -> keep as research/dev tool
- Update CLAUDE.md with new namespace guidance
- Update any code that imports old namespaces

**Tests:**
- All existing tests still pass
- Deprecation warnings appear when using old namespaces

**Implementation Notes:**
- Deprecation notices added to ns docstrings with migration guides
- `seon.claude.exploration` marked as EXPERIMENTAL research/dev tool
- CLAUDE.md updated with AI namespace hierarchy documentation
- No external imports of deprecated namespaces found (only internal references)
- Test file `test/seon/claude/sdk_test.clj` marked as deprecated
- New comprehensive tests in `test/seon/ai/claude_test.clj`

**Commit:** "chore: deprecate seon.claude.* in favor of seon.ai.*"

---

### Phase 5: Document and Update Conventions [COMPLETED]

**Goal:** Update CONVENTIONS.md with namespace composition patterns.

**Deliverables:**
- Add section on "Schema Inheritance Across Namespaces"
- Show pattern for provider namespaces extending base
- Document entity-centric thinking for XTDB

**Implementation Notes:**
- CONVENTIONS.md already had "Schema Composition Across Namespaces" from earlier phases
- Enhanced "Same Entity, Multiple Namespaces" to "Entity-Centric Thinking (XTDB/Datomic Pattern)"
- Added "Converter Functions (Map-In Pattern)" section showing `sdk-message->entity` pattern
- Documented why map-in pattern benefits even converter functions

**Commit:** "docs: add namespace composition patterns to CONVENTIONS.md"

---

### Phase 6: Refactor seon.ai.gemini [COMPLETED]

**Goal:** Bring Gemini into the common pattern.

**Deliverables:**
- Refactor `seon.ai.gemini` to use `seon.ai` base schemas where applicable
- Common schemas: `::prompt`, `::timeout`, `::tokens`
- Gemini-specific: `::thinking-level`, `::grounding-metadata`

**Implementation Notes:**
- Added `seon.ai` require to establish the provider relationship
- Kept `::gemini/prompt` as local schema (mirrors `::ai/prompt`) for API consistency
  - Callers use `(gemini/ask {::gemini/prompt "..."})` not `{::ai/prompt "..."}`
  - Both schemas are functionally identical (`[:string {:min 1}]`)
- Organized schema sections with clear comments:
  - Gemini-specific primitives (api-key, model, timeout, thinking-level)
  - Tool configuration (google_search, code_execution)
  - Request schemas (generate, ask, search, calculate, code-review)
  - Token tracking (uses Gemini API naming vs. normalized `::ai/input-tokens`)
  - Response components (text, error, grounding-metadata, code-results, usage)
- Documented relationship to base schemas in code comments
- Added provider pattern tests verifying:
  - Common prompts validate against both `::gemini/prompt` and `::ai/prompt`
  - Base schemas exist for token/cost tracking
  - Gemini-specific schemas (thinking-level, grounding, code execution) are distinct

**Commit:** "refactor: align seon.ai.gemini with common base schemas"

---

## Schema Reference

### Base Schemas (seon.ai)

| Schema | Type | Description |
|--------|------|-------------|
| `::type` | `[:enum :session :message :tool-call]` | Entity type |
| `::id` | `string` | Entity ID |
| `::session-id` | `string` | Parent session reference |
| `::role` | `[:enum "user" "assistant" "system"]` | Message role |
| `::content` | `[:or :string :map]` | Message content |
| `::timestamp` | `inst?` | Event timestamp |
| `::status` | `[:enum :active :completed :failed :interrupted]` | Status |
| `::input-tokens` | `int` | Input token count |
| `::output-tokens` | `int` | Output token count |
| `::cost-usd` | `double` | Cost in USD |
| `::namespace` | `symbol` | Clojure namespace context |
| `::prompt` | `string` | Initial prompt |
| `::error` | `map` | Error details |

### Claude Schemas (seon.ai.claude)

| Schema | Type | Description |
|--------|------|-------------|
| `::model` | `[:enum "opus" "sonnet" "haiku"]` | Model alias |
| `::cache-creation-tokens` | `int` | Prompt cache creation |
| `::cache-read-tokens` | `int` | Prompt cache reads |
| `::tool-calls` | `[:vector :map]` | Tool use blocks |
| `::tool-results` | `[:vector :map]` | Tool result blocks |
| `::message-type` | `string` | SDK message type |
| `::uuid` | `string` | SDK message UUID |
| `::raw-message` | `map` | Full SDK message |

---

## Success Criteria

1. **Clean hierarchy:** `seon.ai` contains only generic code, `seon.ai.claude` only Claude-specific
2. **Schema composition:** Claude schemas reference base schemas, not duplicate
3. **Auto-persistence:** Agent conversations automatically saved to XTDB
4. **Entity thinking:** Code uses domain functions (`start-session!`, `add-message!`), XTDB details hidden
5. **Testable:** Each phase has generative tests validating schemas

---

## Open Questions

1. Should `seon.ai` also define a protocol for providers to implement?
2. How to handle provider-specific query needs (e.g., Claude cache token analytics)?
3. Should we version the schema for future migrations?

---

## References

- `docs/prds/clojure-claude-sdk/schema-research.md` - Graph DB schema research
- `src/seon/ai/gemini.clj` - Existing Gemini integration (reference for patterns)
- `CONVENTIONS.md` - Schema and API patterns
