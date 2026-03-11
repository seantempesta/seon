> **Status: ARCHIVED** — Used XTDB API, needs full rewrite

> **Status: ARCHIVED** — Used XTDB API, needs full rewrite

# PRD: Session Analytics

**Status:** In Progress
**Priority:** Medium
**Purpose:** Test agent capabilities with a real task

---

## Overview

Add a `session-stats` function to `seon.ai` that provides aggregate analytics on AI sessions, focusing on:
1. **Total cost** across all sessions
2. **Token caching behavior** - how effectively are we using cached tokens?

This is a **glass box test** - you are aware this task tests the agent system. Your observations help us improve it.

---

## Your Task

Implement `seon.ai/session-stats` that returns aggregate statistics.

### Requirements

```clojure
(ai/session-stats {::ai/node xtdb-node})
;; => {::ai/total-cost-usd 12.34
;;     ::ai/total-sessions 47
;;     ::ai/total-messages 892
;;     ::ai/tokens {:input 450000
;;                  :output 89000
;;                  :cache-read 120000
;;                  :cache-creation 35000}
;;     ::ai/cache-hit-rate 0.21}  ; cache-read / (cache-read + input)

```

### Approach

1. **Explore first** - Use REPL to understand the data before coding
2. **Log observations** - Update the Lab Notebook section below as you work
3. **Follow conventions** - Malli schemas, map-in/map-out, docstrings
4. **Write tests** - Unit tests that verify the aggregation logic
5. **Respond to feedback** - If Gemini or hooks flag issues, address them

---

## Lab Notebook

**Instructions:** Update this section as you work. Use these markers:
- `[x]` - Completed/verified
- `[?]` - Question or uncertainty
- `[!]` - Issue encountered
- `[>]` - Decision made

### Phase 1: REPL Exploration

[x] Connected to XTDB node via `integrant.repl.state/system`

**ai_sessions table columns:**
- `:xt/id` - Session ID (e.g., "ses-9dc87fd9-...")
- `:seon.ai/type` - Always `:session`
- `:seon.ai/status` - `:active`, `:completed`, etc.
- `:seon.ai/started-at` - ZonedDateTime
- `:seon.ai/ended-at` - ZonedDateTime (when completed)
- `:seon.ai/namespace` - String (e.g., "seon.e2e-test")
- `:seon.ai/prompt` - Initial prompt string
- `:seon.ai/cost-usd` - Double (e.g., 0.07837075)

[x] Sessions have cost but NOT token counts directly

**ai_messages table columns:**
- `:xt/id` - Message ID (e.g., "msg-8d101a1e-...")
- `:seon.ai/type` - Always `:message`
- `:seon.ai/session-id` - Reference to parent session
- `:seon.ai/role` - "user", "assistant", "system"
- `:seon.ai/content` - Message content
- `:seon.ai/timestamp` - ZonedDateTime
- `:seon.ai/input-tokens` - Int (when present)
- `:seon.ai/output-tokens` - Int (when present)
- `:seon.ai.claude/cache-read-tokens` - Int (when present)
- `:seon.ai.claude/cache-creation-tokens` - Int (when present)
- `:seon.ai.claude/message-type` - "assistant", "user", "system"
- `:seon.ai.claude/uuid` - Claude's message UUID

[x] Token data is on messages, not sessions
[x] Cache tokens are in Claude-specific namespace

**Current data (3 sessions, 74 messages):**
- Total cost: $0.17
- Input tokens: 14
- Output tokens: 1064
- Cache read: 126,181
- Cache creation: 13,332

[>] Decision: Aggregate tokens from ai_messages, cost from ai_sessions

### Phase 2: Implementation

[x] Added schemas to `seon.ai`:
- `::tokens` - Map with `:input`, `:output`, `:cache-read`, `:cache-creation`
- `::cache-hit-rate` - Double between 0.0 and 1.0
- `::session-stats-request` - Map with `::node`
- `::session-stats-response` - Full response schema

[x] Implemented `session-stats` function:
- Queries `ai_sessions` for cost/count aggregates
- Queries `ai_messages` for token aggregates (including Claude cache tokens)
- Calculates cache-hit-rate as `cache-read / (cache-read + input)`
- Handles division by zero (returns 0.0 when no tokens)

[!] Hook feedback on missing `:malli/schema` metadata:
- This is intentional per existing convention (see line 55-57 in ai.clj)
- The `::node` type cannot be generated for property testing
- All other functions in the file follow this same pattern

[x] Hook ran gen-tests successfully (1.5s)

### Phase 3: Verification

**REPL Verification:**

```clojure
(ai/session-stats {::ai/node node})
;; => #:seon.ai{:total-cost-usd 0.1730855,
;;              :total-sessions 3,
;;              :total-messages 100,
;;              :tokens {:input 14, :output 1064,
;;                       :cache-read 126181, :cache-creation 13332},
;;              :cache-hit-rate 0.9998890605808471}

```

[x] Results match raw SQL queries:
- Total cost: $0.17 (matches)
- Sessions: 3 (matches)
- Messages: 100+ (growing as agent runs)
- Cache hit rate: 99.99% (high because most tokens are cached context)

**Unit Tests:**
[x] `session-stats-empty-db-test` - Zeros for empty database
[x] `session-stats-with-data-test` - Aggregates cost and tokens correctly
[x] `session-stats-cache-hit-rate-test` - Rate is 0.0 when no cache tokens

```
22 tests, 211 assertions, 0 failures.

```

[>] Observation: The cache hit rate formula `cache-read / (cache-read + input)`
measures what fraction of context tokens came from cache vs fresh input.
High values (~99%) indicate effective prompt caching.

---

## Success Criteria

- [x] `session-stats` returns correct totals (verified in REPL against raw queries)
- [x] Malli schema defined and generators work
- [x] Unit tests pass (22 tests, 211 assertions)
- [x] Cache hit rate calculation is accurate
- [x] Lab notebook documents your process

---

## Notes for Observers

This task tests whether the agent can:
1. Use REPL effectively for discovery before coding
2. Handle hook feedback (test failures, Gemini reviews)
3. Self-report on confusion or issues encountered
4. Produce working code that follows project conventions

If the agent struggles, we improve the agent system - not the instructions.
