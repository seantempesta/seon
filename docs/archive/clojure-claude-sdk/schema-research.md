---
type: prd
status: completed
tags: [prd, archive, schema]
---

# AI Conversation Schema Research

## Summary

After researching schema design patterns in Datomic, XTDB, and real-world LLM conversation systems, **the recommended approach is to use separate entities for sessions and messages** with references between them. This provides the best balance of queryability, append performance, and temporal semantics.

The key insight from Datomic's component entity pattern is that parent-child relationships work best when the child entities have no independent existence outside the parent. For AI conversations, messages have this characteristic - they belong to exactly one session and their lifecycle is tied to that session.

XTDB's document model differs from Datomic's datom model: XTDB treats documents as atomic units. This means embedding messages as vectors within sessions would require re-transacting the entire session document for each new message - inefficient for streaming scenarios. Separate message entities with session references align better with XTDB's strengths.

## Entity Design Options

### Option A: Single Conversation Entity (Embedded Messages)

Store the entire conversation as one document with messages as a vector attribute.

```clojure
{:xt/id "session-abc123"
 :seon.ai/type :session
 :seon.ai/started-at #inst "2025-01-19T10:00:00Z"
 :seon.ai/status :active
 :seon.ai/messages [{:role "user" :content "Hello" :timestamp #inst "..."}
                    {:role "assistant" :content "Hi there!" :timestamp #inst "..."}
                    {:role "user" :content "Help me code" :timestamp #inst "..."}]}

```

**Pros:**
- Simple conceptual model - one entity per conversation
- Single query fetches entire conversation
- Natural hierarchy matches the logical structure
- Ordering preserved automatically in XTDB vectors (unlike Datomic sets)

**Cons:**
- **Append performance is poor** - must re-transact entire document for each new message
- Growing document size as conversations extend (some can have hundreds of messages)
- Nested values are not indexed in XTDB - cannot query across messages efficiently
- XTDB's valid-time applies to the whole document, not individual messages
- Streaming inserts become increasingly expensive

### Option B: Separate Message Entities with Session Reference

Store sessions and messages as separate entities, with messages referencing their session.

```clojure
;; Session entity
{:xt/id "session-abc123"
 :seon.ai/type :session
 :seon.ai/started-at #inst "2025-01-19T10:00:00Z"
 :seon.ai/status :active
 :seon.ai/namespace 'seon.trading
 :seon.ai.claude/model "claude-opus-4-5-20251101"}

;; Message entities
{:xt/id "session-abc123/msg-001"
 :seon.ai/session-id "session-abc123"
 :seon.ai/type :message
 :seon.ai/role "user"
 :seon.ai/content "Hello"
 :seon.ai/timestamp #inst "2025-01-19T10:00:01Z"}

{:xt/id "session-abc123/msg-002"
 :seon.ai/session-id "session-abc123"
 :seon.ai/type :message
 :seon.ai/role "assistant"
 :seon.ai/content "Hi there!"
 :seon.ai/timestamp #inst "2025-01-19T10:00:05Z"
 :seon.ai/input-tokens 15
 :seon.ai/output-tokens 8}

```

**Pros:**
- **Optimal append performance** - new messages are independent inserts
- Each message has its own valid-time for precise temporal queries
- Messages are fully indexed and queryable
- Scales to conversations of any length
- Natural fit for streaming message persistence
- Can query messages without loading entire conversation

**Cons:**
- Requires join to fetch conversation with messages
- More entities to manage
- Message ordering relies on timestamp or sequence number

### Option C: Hybrid - Session with Summary, Separate Messages

Combine approaches: session entity holds metadata and summary, messages stored separately.

```clojure
;; Session with denormalized summary
{:xt/id "session-abc123"
 :seon.ai/type :session
 :seon.ai/started-at #inst "2025-01-19T10:00:00Z"
 :seon.ai/status :active
 :seon.ai/message-count 47
 :seon.ai/last-message-preview "Let me help you with that..."
 :seon.ai/total-tokens 12450
 :seon.ai/total-cost-usd 0.23}

;; Messages stored separately (same as Option B)

```

**Pros:**
- Fast session list rendering with previews
- No need to query messages just to show conversation list
- Combines queryability of Option B with UX of Option A

**Cons:**
- Requires update logic when messages arrive
- Potential consistency issues between summary and messages
- More complexity in write path

## Recommended Schema

**Recommendation: Option B (Separate Entities) with elements of Option C (session summaries)**

This provides the best tradeoffs for an AI conversation system:
1. Fast streaming inserts during active conversations
2. Full temporal queryability per message
3. Efficient querying across conversations
4. Clean mapping to XTDB's document model

### Generic Attributes (seon.ai/*)

These attributes are provider-agnostic and apply to any AI system.

| Attribute | Type | Description |
|-----------|------|-------------|
| `:seon.ai/type` | keyword | Entity type: `:session`, `:message`, `:tool-call` |
| `:seon.ai/session-id` | string | Reference to parent session |
| `:seon.ai/role` | string | Message role: "user", "assistant", "system" |
| `:seon.ai/content` | string/any | Message content (text or structured) |
| `:seon.ai/timestamp` | inst | When the event occurred |
| `:seon.ai/input-tokens` | int | Input token count |
| `:seon.ai/output-tokens` | int | Output token count |
| `:seon.ai/total-tokens` | int | Running total for session |
| `:seon.ai/cost-usd` | double | Cost in USD |
| `:seon.ai/status` | keyword | Status: `:active`, `:completed`, `:failed` |
| `:seon.ai/namespace` | symbol | Clojure namespace context |
| `:seon.ai/prompt` | string | Initial prompt that started session |
| `:seon.ai/error` | map | Error details if failed |

### Provider-Specific Attributes (seon.ai.claude/*)

Claude-specific attributes that extend the generic schema.

| Attribute | Type | Description |
|-----------|------|-------------|
| `:seon.ai.claude/model` | string | Model identifier (opus, sonnet, etc.) |
| `:seon.ai.claude/session-id` | string | Claude Code internal session ID |
| `:seon.ai.claude/cache-creation-tokens` | int | Prompt cache creation tokens |
| `:seon.ai.claude/cache-read-tokens` | int | Prompt cache read tokens |
| `:seon.ai.claude/tool-calls` | vector | Tool use blocks from response |
| `:seon.ai.claude/tool-results` | vector | Tool result blocks |
| `:seon.ai.claude/message-type` | string | SDK message type: "assistant", "result", etc. |
| `:seon.ai.claude/uuid` | string | SDK-assigned message UUID |
| `:seon.ai.claude/raw-message` | map | Full raw SDK message for debugging/replay |

### Example Entities

**Session Entity:**

```clojure
{:xt/id "abc123"
 :seon.ai/type :session
 :seon.ai/started-at #inst "2025-01-19T10:00:00Z"
 :seon.ai/status :active
 :seon.ai/namespace 'seon.trading
 :seon.ai/prompt "Analyze AAPL options chain"

 ;; Provider-specific
 :seon.ai.claude/model "claude-opus-4-5-20251101"
 :seon.ai.claude/session-id "claude-internal-xyz"}

```

**Message Entity:**

```clojure
{:xt/id "abc123/msg-001"
 :seon.ai/session-id "abc123"
 :seon.ai/type :message
 :seon.ai/role "assistant"
 :seon.ai/content "I'll analyze the AAPL options chain for you..."
 :seon.ai/timestamp #inst "2025-01-19T10:00:05Z"
 :seon.ai/input-tokens 1250
 :seon.ai/output-tokens 450

 ;; Provider-specific
 :seon.ai.claude/message-type "assistant"
 :seon.ai.claude/uuid "msg-uuid-123"
 :seon.ai.claude/tool-calls [{:name "mcp__seon__eval"
                              :input {:code "(analyze-options \"AAPL\")"}}]}

```

**Tool Call Entity (optional, for detailed tool tracking):**

```clojure
{:xt/id "abc123/tool-001"
 :seon.ai/session-id "abc123"
 :seon.ai/type :tool-call
 :seon.ai/timestamp #inst "2025-01-19T10:00:06Z"
 :seon.ai.claude/tool-name "mcp__seon__eval"
 :seon.ai.claude/tool-input {:code "(analyze-options \"AAPL\")"}
 :seon.ai.claude/tool-output {:result {:ticker "AAPL" :iv 0.25}}
 :seon.ai.claude/latency-ms 1250
 :seon.ai.claude/status "success"}

```

### Query Examples

**Fetch a session with all messages:**

```sql
SELECT s.*, m.*
FROM ai_sessions s
LEFT JOIN ai_messages m ON s._id = m.seon$ai$session_id
WHERE s._id = 'abc123'
ORDER BY m.seon$ai$timestamp

```

Or with XTQL pull pattern:

```clojure
(-> (from :ai_sessions [{:xt/id session-id} *])
    (with {:messages (pull* (-> (from :ai_messages [{:seon.ai/session-id session-id} *])
                                (order-by timestamp)))}))

```

**Find sessions by namespace:**

```sql
SELECT * FROM ai_sessions
WHERE seon$ai$namespace = 'seon.trading'
ORDER BY seon$ai$started_at DESC
LIMIT 20

```

**Count tokens by model over time:**

```sql
SELECT seon$ai$claude$model as model,
       SUM(seon$ai$input_tokens) as total_input,
       SUM(seon$ai$output_tokens) as total_output
FROM ai_messages
WHERE seon$ai$timestamp > TIMESTAMP '2025-01-01'
GROUP BY seon$ai$claude$model

```

**Find sessions containing specific tool calls:**

```sql
SELECT DISTINCT s.*
FROM ai_sessions s
JOIN ai_messages m ON s._id = m.seon$ai$session_id
WHERE m.seon$ai$claude$tool_calls IS NOT NULL
  AND m.seon$ai$claude$tool_calls LIKE '%mcp__seon__eval%'

```

**Get session at a point in time (temporal query):**

```sql
SELECT * FROM ai_sessions
FOR VALID_TIME AS OF TIMESTAMP '2025-01-15T12:00:00Z'
WHERE _id = 'abc123'

```

## Temporal Considerations

XTDB's bitemporality provides powerful capabilities for conversation storage:

### Valid Time for Messages

Each message naturally gets its own valid-time when inserted. This means:

1. **Reconstructing conversation state at any point:**

   ```sql
   SELECT * FROM ai_messages
   FOR VALID_TIME AS OF TIMESTAMP '2025-01-19T10:30:00Z'
   WHERE seon$ai$session_id = 'abc123'
   ORDER BY seon$ai$timestamp

   ```
   This shows exactly what messages existed at 10:30 AM.

2. **Correcting historical data:**
   If a message was stored incorrectly, you can update it for a specific time range without losing history.

### System Time for Audit

System time tracks when XTDB actually recorded the data:

1. **"What did the agent know at time T?"**

   ```sql
   SELECT * FROM ai_messages
   FOR SYSTEM_TIME AS OF TIMESTAMP '2025-01-19T10:05:00Z'
   WHERE seon$ai$session_id = 'abc123'

   ```
   Shows the conversation state as recorded at 10:05 AM, even if later corrections were made.

2. **Audit trail:**

   ```sql
   SELECT *, _system_from, _system_to
   FROM ai_messages
   FOR ALL SYSTEM_TIME
   WHERE _id = 'abc123/msg-001'

   ```
   Shows all versions of a message as they were recorded.

### Streaming Considerations

For streaming message persistence during active conversations:

1. **Batch writes within transactions:** Group multiple messages in single `execute-tx` for atomicity
2. **Use message UUIDs from SDK:** Ensures idempotent re-processing
3. **Handle disconnects gracefully:** Store partial results, update on completion

## References

### Primary Sources

- [XTDB Key Concepts](https://docs.xtdb.com/concepts/key-concepts) - Schemaless design, temporal columns
- [XTDB Time Documentation](https://docs.xtdb.com/about/time-in-xtdb) - Bitemporality explained
- [XTDB FAQ](https://v1-docs.xtdb.com/resources/faq/) - Document model comparison with Datomic

### Datomic Patterns (applicable concepts)

- [Datomic Data Modeling](https://docs.datomic.com/schema/schema-modeling.html) - Entity relationships
- [Datomic Component Entities](https://blog.datomic.com/2013/06/component-entities.html) - Parent-child lifecycle
- [Datomic Schema Reference](https://docs.datomic.com/schema/schema-reference.html) - Cardinality, refs

### LLM Conversation Design

- [Schema Design for Agent Memory and LLM History](https://medium.com/@pranavprakash4777/schema-design-for-agent-memory-and-llm-history-38f5cbc126fb) - Tables for conversations, messages, tool calls
- [AI SDK Chatbot Message Persistence](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence) - Streaming patterns
- [Building Stateful Conversations with Postgres and LLMs](https://medium.com/@levi_stringer/building-stateful-conversations-with-postgres-and-llms-e6bb2a5ff73e) - Practical schema design
- [Chat Application Schema Design](https://www.back4app.com/tutorials/how-to-design-a-database-schema-for-a-real-time-chat-and-messaging-app) - Relationship patterns

### Key Insights

1. **XTDB only indexes top-level attributes** - Nested values in maps/vectors are not indexed, so messages stored as vectors within sessions cannot be efficiently queried.

2. **Datomic stores each fact separately** regardless of cardinality - XTDB's document model differs, making separate entities more efficient for append-heavy workloads.

3. **Ordering in cardinality-many** - Datomic treats cardinality-many as sets (unordered); XTDB preserves vector ordering, but separate entities with timestamps are still preferred for queryability.

4. **Component entities** in Datomic gain lifecycle management (cascade delete) - XTDB doesn't have this built-in, so application code must handle cleanup.

5. **LLM systems typically use normalized schemas** with separate tables for sessions, messages, tool calls, and summaries - matching Option B's approach.
