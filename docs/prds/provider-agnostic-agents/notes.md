# Implementation Notes

## Analysis Summary

### Current State (2026-01-19)

**Namespace Line Counts:**
- `seon.ai.clj` - 434 lines (well-designed base)
- `seon.ai.claude.clj` - 867 lines (too big, mixed concerns)
- `seon.ai.gemini.clj` - 615 lines (API client only)
- `seon.claude.sdk.clj` - 1013 lines (deprecated, duplicate)
- `seon.claude.conversation.clj` - 605 lines (deprecated, duplicate)
- `seon.claude.exploration.clj` - 838 lines (research tool, keep)

**Total deprecated code:** ~1618 lines to delete

### Key Patterns Discovered

**1. Agent Loop Pattern (from `seon.ai.claude/launch-agent!`):**
```clojure
;; Generic pattern that all providers share:
1. Create Seon session via session/start-agent-session!
2. Create AI session via ai/start-session!
3. Build provider-specific prompt with session context
4. Spawn provider process with MCP config
5. Start reader loop:
   - Parse provider wire format
   - Convert to normalized message
   - Persist via ai/add-message!
   - Detect result message
   - End AI session on completion
6. Build close! function for cleanup
7. Register in agent-registry
8. Send initial prompt
9. Return handle
```

**2. Message Normalization Pattern (from `sdk-message->entity`):**
```clojure
;; Input: Provider-specific wire format
{:type "assistant"
 :uuid "msg-123"
 :message {:role "assistant"
           :content [{:type "text" :text "Hello"}
                     {:type "tool_use" :id "tu-1" :name "Read" :input {...}}]}}

;; Output: Normalized ::ai/message entity
{:xt/id "msg-abc123"
 :seon.ai/type :message
 :seon.ai/role "assistant"
 :seon.ai/content "Hello"
 :seon.ai/timestamp #inst "2026-01-19T..."
 :seon.ai.claude/message-type "assistant"
 :seon.ai.claude/uuid "msg-123"
 :seon.ai.claude/tool-calls [{:id "tu-1" :name "Read" :input {...}}]}
```

**3. Provider Detection Pattern:**
- Claude SDK messages have `:type` field ("assistant", "user", "result", etc.)
- Result detection: `(= "result" (:type msg))`
- Gemini API uses different structure entirely

### Gotchas for Implementers

**1. Don't Break Existing Tests**
The existing `seon.ai.claude/launch-agent!` must continue to work exactly as before. The refactor should be internal.

**2. Registry Must Be Global**
Agent registry needs to be in `seon.ai.agent` (not provider namespace) so `agents`, `tail`, etc. work across all providers.

**3. Session ID Mapping**
Claude SDK generates its own session IDs that differ from Seon session IDs. The `session-map.edn` file maps between them. This logic should stay provider-specific.

**4. MCP Config is Claude-Specific**
The `build-agent-mcp-config` function creates Claude Code CLI-specific MCP configuration. Other providers may not have MCP support.

**5. Permission Modes**
Claude has specific permission modes ("bypassPermissions", "acceptEdits", etc.). These are provider-specific options, not part of the generic agent framework.

**6. Message Persistence Filtering**
Not all messages should be persisted:
```clojure
(defn- persistable-message-type? [msg-type]
  (#{"user" "assistant" "system" "result"} msg-type))
  ;; Skip: keep_alive, parse_error
```
This filter logic is provider-specific.

**7. Exploration Namespace is Valuable**
`seon.claude.exploration` is explicitly marked as research code, not deprecated. Keep it for protocol investigation.

### Open Questions

1. **How to handle provider-specific options?**
   - Option A: Single `::options` map passed through
   - Option B: Provider-specific schema registered with multimethod
   - Recommendation: Option A for simplicity

2. **Should normalize-message return a full entity or deltas?**
   - Currently returns full entity with `:xt/id` generated
   - Could return just the fields, let caller add ID
   - Recommendation: Return full entity (existing pattern)

3. **How to handle provider-specific observability?**
   - Claude has cost per message, Gemini has token counts
   - Both should normalize to `::ai/input-tokens`, `::ai/output-tokens`
   - Provider-specific fields go in provider namespace

### Dependencies

- `seon.orchestrator.session` - Session management (keep as-is)
- `seon.agent.ctx` - Persisted context atoms (keep as-is)
- `seon.db.multi` - Multi-database support (keep as-is)
- `seon.orchestrator.nrepl` - Per-session nREPL (keep as-is)

These are well-factored and don't need changes.

### Testing Strategy

1. **Unit tests for multimethods:**
   - Test `normalize-message` with various Claude message types
   - Test `result-message?` detection

2. **Integration test with mock provider:**
   - Create a `:mock` provider that returns canned messages
   - Verify full agent loop works

3. **Existing tests must pass:**
   - Don't change test behavior
   - May need to update imports

4. **Cannot spawn real processes in generative tests:**
   - Use `:gen/fmap` that throws to prevent generation
   - Already done for `::prompt`, `::node` schemas

## Research References

- `reference-code/claude-agent-sdk-typescript/` - TypeScript SDK patterns
- `docs/prds/clojure-claude-sdk/bidirectional-control.md` - Hook investigation
- `docs/prds/clojure-claude-sdk/schema-research.md` - Message type research
