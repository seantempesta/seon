# Dynamic Context Injection

**Status**: Research Phase (Channel Bug FIXED, Context Injection Still TODO)

## UPDATE 2025-01-30

**CRITICAL BUG FOUND AND FIXED**: The "100 message limit" was NOT Claude Code's turn limit - it was our channel buffer blocking!

```clojure
;; BUG (caused deadlock at 100 messages):
messages-ch (chan 100)

;; FIX (never blocks):
messages-ch (chan (async/sliding-buffer 1000))
```

See `src/seon/ai/claude.clj:685`. The fix is deployed. Agents can now run indefinitely.

**Remaining work**: Dynamic context injection via `type: "system"` messages still needs testing (Phase 0 below)
**Created**: 2025-01-30
**Related**: [VISION.md](/VISION.md) (Layer 5: Dynamic Context - The Cockpit)

## Problem Statement

Seon agents currently receive static context via AGENT.md at session start. As agents work, their context grows with message history until Claude Code compacts it (~200k tokens). This creates several issues:

1. **Stale context** - System state changes but the agent's view doesn't update
2. **Context bloat** - Tool results accumulate, pushing useful context out
3. **No live instrumentation** - Agents can't see real-time health, errors, or other agents
4. **Turn limit mishandling** - `error_max_turns` is treated as failure, not a continuation point

The vision describes agents having a "cockpit with instruments, not a growing scroll of text."

## Hypothesis

We believe Claude Code's `stream-json` input format supports:
1. **System message injection** (`type: "system"`) to update context per-turn
2. **User message injection** (`type: "user"`) to send continuations
3. **Context replacement** (not growth) when sending new system messages

**These are unverified assumptions.** Phase 0 research must validate or disprove them.

---

## Phase 0: Research & Validation (REQUIRED FIRST)

**Goal**: Determine what's actually possible with Claude Code's stdin API before designing anything.

### Research Questions

#### Q1: Does `type: "system"` message injection work?
- Can we send system messages mid-conversation?
- When does Claude process them - immediately or queued?
- Do they affect the next turn's context?

**Test approach**:
```clojure
;; Send a system message with unique identifier
(sdk/write-message! stdin {:type "system"
                           :content "SECRET_CODE_12345 is the magic number."
                           :session_id ""})
;; Then send user message asking Claude to report the secret
(sdk/write-message! stdin (sdk/make-user-message "What is the magic number?"))
;; If Claude knows SECRET_CODE_12345, injection works
```

#### Q2: Does system injection REPLACE or APPEND?
- If we send 10 system messages, does context grow by 10x?
- Or does each new system message replace the previous?
- How do we measure context size?

**Test approach**:
```clojure
;; Send system message with MARKER_A
;; Send user message, get response
;; Send system message with MARKER_B (no MARKER_A)
;; Ask Claude: "Do you see MARKER_A? Do you see MARKER_B?"
;; If only MARKER_B visible, it's replacement
;; If both visible, it's append (context growing)
```

#### Q3: What's the message schema?
- Verify the exact JSON structure Claude Code expects
- Check if `session_id` is required or can be empty
- Test error handling for malformed messages

**Test approach**:
```bash
# Run Claude Code with verbose logging
claude --verbose --input-format stream-json --output-format stream-json
# Send various message formats, observe behavior
```

#### Q4: How does `error_max_turns` actually work?
- Is it a hard stop or a pause waiting for input?
- Can we send a continuation message to resume?
- Does the context persist across the "error"?

**Test approach**:
```clojure
;; Launch agent with --max-turns 3
;; Let it hit the limit
;; Send: {:type "user" :message {...} :content "Continue"}
;; Observe: does it resume? Is context preserved?
```

#### Q5: Can we monitor context size?
- Does Claude Code expose token count in output?
- Can we infer it from `usage` fields in messages?
- What triggers compaction and can we detect it?

**Test approach**:
```clojure
;; Parse output messages for token usage
;; Track cumulative tokens over turns
;; Look for compaction events in verbose output
```

### Research Deliverables

1. **Findings document**: `docs/prds/dynamic-context/research-findings.md`
   - Answer each question with evidence
   - Include code/logs that prove the behavior
   - Note any version-specific behavior (Claude Code version)

2. **Prototype code**: `src/seon/experimental/context_injection.clj`
   - Working examples of each technique
   - Can be thrown away or promoted based on findings

3. **Updated PRD**: Revise this document based on findings
   - Remove invalid assumptions
   - Add confirmed capabilities
   - Adjust design accordingly

---

## Proposed Design (PENDING RESEARCH)

> **Note to implementing agent**: This design is a starting hypothesis. You are encouraged to:
> - Question every assumption
> - Propose better approaches based on your research
> - Recommend changes to this PRD
> - Stay close to Claude Code's documented API for stability
>
> The goal is a working system, not adherence to this spec.

### Concept: The Cockpit

Instead of static AGENT.md, agents receive a live "cockpit" view refreshed each turn:

```
┌─────────────────────────────────────────────────────────────┐
│                 AGENT COCKPIT (refreshed per turn)          │
├─────────────────────────────────────────────────────────────┤
│ STATIC (cached by Claude, ~tokens once)                     │
│   └─ How to use the REPL, Datalog primer, conventions       │
├─────────────────────────────────────────────────────────────┤
│ DYNAMIC (eval'd fresh, injected via system message)         │
│   ├─ Turn number / session duration                         │
│   ├─ System health (XTDB, nREPL, agents)                    │
│   ├─ Namespace source code (the code agent owns)            │
│   ├─ Recent errors (last 5)                                 │
│   ├─ Available schemas                                      │
│   └─ Other running agents                                   │
└─────────────────────────────────────────────────────────────┘
```

### Proposed Implementation

#### 1. SDK Extension (sdk.clj)

```clojure
(defn make-system-message
  "Create a system message for context injection."
  [content]
  {:type "system"
   :content content
   :session_id ""})  ;; Verify this is correct

(defn make-continue-message
  "Create a user message to continue after turn limit."
  []
  (make-user-message "Continue with the task if work remains. Summarize if complete."))
```

#### 2. Cockpit Generator (new namespace)

```clojure
(ns seon.orchestrator.cockpit
  "Generate live context for agent turns.")

(defn cockpit
  "Generate cockpit view for an agent session."
  [{:keys [session-id namespace turn-number]}]
  (str
    (static-section)      ;; Cached instructions (only on turn 1?)
    (health-section)      ;; Live system health
    (namespace-section namespace)  ;; Source code agent owns
    (schemas-section namespace)    ;; Available schemas
    (agents-section)      ;; Other running agents
    (errors-section)))    ;; Recent errors
```

#### 3. Agent Loop Modification (claude.clj)

```clojure
;; In reader loop, after turn completion:
(when (and (turn-complete? msg) @inject-cockpit?)
  (let [cockpit-content (cockpit/cockpit {:session-id id
                                          :namespace namespace
                                          :turn-number (inc @turn-counter)})]
    (swap! turn-counter inc)
    (sdk/write-message! stdin (sdk/make-system-message cockpit-content))))

;; Handle turn limit as continuation, not error:
(when (= "error_max_turns" (:subtype msg))
  (if (< @continuation-count max-continuations)
    (do
      (log/info "Turn limit reached, continuing" {:turns (:num_turns msg)})
      (swap! continuation-count inc)
      (sdk/write-message! stdin (sdk/make-continue-message)))
    (do
      (log/warn "Max continuations reached, stopping" {:count @continuation-count})
      (reset! status-atom :completed))))
```

### Open Questions for Research

1. **Timing**: When exactly should we inject the system message?
   - After every `message` event?
   - Only after `result` events?
   - Before tool execution?

2. **Content size**: How much can we put in the cockpit?
   - Full namespace source might be too large
   - Need to measure token impact

3. **Caching**: Does Claude cache the static parts?
   - Or do we pay tokens for static content every turn?
   - Should static content be in AGENT.md and dynamic in system messages?

4. **API stability**: Is `type: "system"` documented or discovered?
   - If undocumented, may break in future versions
   - Should we have a fallback?

---

## Success Criteria

### Phase 0 (Research)
- [ ] All 5 research questions answered with evidence
- [ ] Findings document published
- [ ] Prototype demonstrating working injection (if possible)
- [ ] PRD updated with validated design

### Phase 1 (Turn Limit Fix)
- [ ] `error_max_turns` triggers continuation, not failure
- [ ] Agents can run >100 turns without manual intervention
- [ ] Continuation count tracked and limited (prevent infinite loops)

### Phase 2 (Cockpit Injection)
- [ ] System message injection working per-turn
- [ ] Context does NOT grow unboundedly (replacement confirmed)
- [ ] Agents can report live system state they wouldn't otherwise know

### Phase 3 (Orchestrator Namespace)
- [ ] Orchestrator is itself an agent with namespace `seon.orchestrator`
- [ ] Orchestrator cockpit shows all managed agents
- [ ] Meta-level: orchestrator can observe its own context

---

## References

### Seon Documentation
- [VISION.md](/VISION.md) - Layer 5: Dynamic Context (The Cockpit)
- [CLAUDE.md](/CLAUDE.md) - Agent instructions and MCP usage

### Research Findings (from 2025-01-30 investigation)

**Claude Code Storage**:
- Messages stored as JSONL at `~/.claude/projects/{project_id}/{session_id}.jsonl`
- SQLite (`__store.db`) used for indexing only
- Context compaction occurs at ~75-80% of 200k token window

**Stream JSON Input Format** (Gemini search):
- Supports `type: "user"` and reportedly `type: "system"` messages
- Messages are queued if sent mid-tool-execution
- Processed synchronously after current turn completes

**Turn Limit Behavior** (Gemini search):
- `error_max_turns` is a result subtype, not a hard error
- In interactive mode, Claude pauses and waits for input
- Continuation should be possible via stdin

**Relevant Code Locations**:
- `src/seon/ai/claude/sdk.clj` - Message creation, process spawning
- `src/seon/ai/claude.clj:762-783` - Result handling (where turn limit is processed)
- `.claude/AGENT.md` - Current static agent instructions

### External Resources
- Claude Code documentation: https://docs.anthropic.com/en/docs/claude-code
- MCP specification: https://modelcontextprotocol.io/

---

## Notes for Implementing Agent

1. **Research first**: Do not implement Phases 1-3 until Phase 0 is complete
2. **Question everything**: This PRD contains hypotheses, not facts
3. **Stay close to the API**: Prefer documented behavior over discovered hacks
4. **Report findings**: Update this PRD as you learn
5. **Recommend improvements**: If you find a better approach, propose it

The goal is reliable, maintainable infrastructure for agent context management. Cleverness that breaks on the next Claude Code release is not valuable.
