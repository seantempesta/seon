# Dynamic Context Injection: Research Findings

**Date**: 2026-01-30
**Researcher**: Agent (session 1c56)
**Claude Code Version**: 2.1.19

## Executive Summary

This research validates the feasibility of dynamic context management for Seon agents. Key findings:

1. **System message injection does NOT work** - Only `user` messages are accepted as input
2. **Context is APPEND, not REPLACE** - Conversation history accumulates
3. **Turn limit continuation WORKS** - Agents can resume after `error_max_turns` with full context preserved

The original "cockpit" vision of per-turn context injection is **not feasible** with the current Claude Code API. However, turn limit handling can be implemented immediately.

---

## Research Question 1: System Message Injection

### Question
Can we inject system messages via stdin to update agent context?

### Test Methodology
```clojure
;; Attempted to send system message via stdin
(sdk/write-message! stdin {:type "system"
                           :session_id ""
                           :content "The secret code is: MARKER_123"})
```

### Result: **DOES NOT WORK**

**Error received:**
```
Error: Expected message type 'user' or 'control', got 'system'
```

### Evidence
Claude Code's stream-json input parser explicitly rejects `type: "system"` messages. The only valid input types are:
- `type: "user"` - User messages (works)
- `type: "control"` - Control messages (accepted but purpose unclear)

### Conclusion
The hypothesis that we could inject system messages mid-conversation is **invalid**. The `type: "system"` in the protocol is OUTPUT-only (used by Claude Code to emit initialization data).

---

## Research Question 2: Alternative Message Types

### Question
Can `tool_result` or `control` messages be used for context injection?

### Test Methodology
```clojure
;; Attempted tool_result injection
(sdk/write-message! stdin {:type "tool_result"
                           :tool_use_id "fake_123"
                           :content "SECRET_CONTEXT"})

;; Attempted various control message formats
(sdk/write-message! stdin {:type "control" :action "inject_context"})
```

### Result: **DOES NOT WORK**

- `tool_result` is rejected with same error as `system`
- `control` messages are accepted but have no visible effect
- Only `user` messages produce a response from Claude

### Conclusion
There is no mechanism to inject arbitrary context outside of user messages.

---

## Research Question 3: User Message Context Injection

### Question
Can we embed context in user messages and have Claude recognize it?

### Test Methodology
```clojure
(sdk/write-message! stdin
  (sdk/make-user-message
    (str "<system-context>\n"
         "The secret code is: " marker "\n"
         "</system-context>\n\n"
         "What is the secret code?")))
```

### Result: **WORKS (but with caveats)**

Claude correctly parsed and responded to the embedded context:
```clojure
{:marker "SEON_MARKER_999111"
 :response "Ready"
 :acknowledged? true}
```

### Critical Caveat: Context is APPEND, not REPLACE

When testing replacement behavior:
- Sent message with MARKER_A
- Sent NEW message with MARKER_B only
- Asked Claude to list all markers visible

**Result:** Claude sees BOTH markers because the conversation history contains both.

```clojure
{:final-response "Codes found: ALPHA_555666, BETA_777888"
 :sees-both? true
 :context-behavior :append-history-preserved}
```

### Conclusion
User message context injection works, but:
1. Context accumulates in conversation history
2. Cannot "replace" previous context
3. The "cockpit" vision of fresh context per-turn would cause unbounded context growth

---

## Research Question 4: Turn Limit Handling

### Question
Can agents continue after hitting `error_max_turns`?

### Test Methodology
```clojure
;; Launch with very low turn limit
(sdk/spawn-claude-code {::sdk/max-turns 1})

;; Send task that requires multiple tool calls
(sdk/write-message! stdin
  (sdk/make-user-message "Read CLAUDE.md and CONVENTIONS.md"))

;; After error_max_turns, send continuation
(sdk/write-message! stdin
  (sdk/make-user-message "Continue."))
```

### Result: **WORKS PERFECTLY**

Phase 1: Hit turn limit
```clojure
{:subtype "error_max_turns" :num_turns 2}
```

Phase 2: Continuation succeeded
```clojure
{:subtype "success"
 :continuation-worked? true}
```

### Context Preservation Test

To verify context was preserved across the turn limit:
1. Established a unique marker before limit
2. Hit turn limit
3. Asked about the marker in continuation

**Result:** Claude correctly recalled the marker:
```clojure
{:marker "UNIQUE_MARKER_ABC123XYZ"
 :context-preserved? true
 :response "UNIQUE_MARKER_ABC123XYZ"}
```

### Conclusion
Turn limit handling is fully viable:
- `error_max_turns` is not a hard stop
- A simple user message continues the conversation
- Full context is preserved across the continuation
- The turn counter appears to reset for the new interaction

---

## Recommendations

### Immediate Implementation: Turn Limit Continuation

**This should be implemented now.** It's straightforward and high-value.

```clojure
;; In agent reader loop, when result type is error_max_turns:
(when (= "error_max_turns" (:subtype msg))
  (if (< @continuation-count max-continuations)
    (do
      (log/info "Turn limit reached, continuing...")
      (swap! continuation-count inc)
      (sdk/write-message! stdin
        (sdk/make-user-message "Continue with the task if work remains.")))
    (do
      (log/warn "Max continuations reached")
      (reset! status-atom :completed))))
```

### Deferred: Dynamic Context Injection

The "cockpit" vision needs redesign because:
1. We cannot inject system messages
2. User message context appends, doesn't replace
3. Per-turn context injection would cause context bloat

**Alternative approaches to explore:**
1. **MCP Tool-based Context** - Create an MCP tool that agents can call to get fresh context
2. **Initial Context Only** - Put all relevant context in AGENT.md, accept it's static
3. **Context Compaction** - Accept context grows, rely on Claude's built-in compaction
4. **Session Restart** - For long tasks, periodically restart with summarized context

### API Feature Request

If Anthropic added support for `type: "system"` messages as INPUT, the cockpit vision would be viable. This could be a feature request.

---

## Appendix: Test Code

The experimental code used for this research is in:
```
src/seon/experimental/context_injection.clj
```

This code is research-quality (not production conventions) and can be deleted after the findings are reviewed.

---

## Summary Table

| Research Question | Result | Implication |
|------------------|--------|-------------|
| System message injection | **NO** | Cannot inject context via `type: "system"` |
| Tool_result injection | **NO** | Cannot inject via `type: "tool_result"` |
| User message context | **YES (append)** | Works but context grows, doesn't replace |
| Turn limit continuation | **YES** | Can resume after `error_max_turns` |
| Context preservation | **YES** | Full context preserved across continuation |
