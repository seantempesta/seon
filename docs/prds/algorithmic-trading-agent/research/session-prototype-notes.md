# Session Template System - Prototype Notes

**Status:** Prototype Complete
**Date:** 2025-12-21
**Author:** Claude (Session Designer)

---

## Overview

This document captures the design exploration for the session template system in `/Users/sean/src/seon/src/seon/trading/agent/session.clj`. The system handles:

1. Session namespace generation (unique, typeable IDs)
2. REPL capture and input/output pairing
3. Thinking token separation from executable code
4. Self-reinforcing template design

---

## 1. Namespace Generation

### Problem

Agents run in isolated namespaces like `seon.agent.xyz`. We need IDs that are:
- Short (quick to type)
- Unique (avoid collisions in parallel sessions)
- Easy for LLMs to reproduce without typos

### Approaches Explored

| Approach | Example | Pros | Cons |
|----------|---------|------|------|
| **Hex** | `a3f2` | 65k combinations, very short | Cryptic, 0/O and 1/l confusion |
| **Adjective-Noun** | `blue-fox` | Memorable, fun | Long (7-9 chars), requires word lists |
| **CVCV** | `bako` | Pronounceable, 9k combos, no confusing chars | May accidentally spell words |
| **Hybrid** | `fox-42` | Memorable prefix + numeric | Still has dash, longer |

### Decision: CVCV Pattern

**Winner: `gen-session-id-cvcv`** producing IDs like `bako`, `meli`, `toxa`.

Rationale:
- **4 characters** - Fast to type, short enough for namespaces
- **Pronounceable** - Easy to say ("bako"), aids memory
- **19 consonants * 5 vowels * 19 * 5 = 9,025 combinations** - Enough for parallel sessions
- **No confusing characters** - No 0/O, 1/l, no digits
- **LLM-friendly** - Simple patterns, less likely to make typos

### What Didn't Work

1. **Hex IDs** - LLMs occasionally confused `0` and `O`, `1` and `l`. Also harder to "say" which matters for human-AI collaboration.

2. **Word pairs** - While memorable, the extra length (`blue-fox` vs `bako`) adds friction. Also requires maintaining word lists.

3. **UUIDs** - Far too long. Nobody wants to type `seon.agent.550e8400-e29b-41d4`.

---

## 2. REPL Capture / Input-Output Pairing

### Problem

Every agent interaction becomes training data. We need to capture:
- The exact input expression
- The output value (truncated for context window, full for storage)
- Metadata: timing, function name, agent reasoning

### Design

```clojure
{:input "(iv-rank ctx {:ticker \"SPY\"})"
 :output "{:iv-rank/value 0.73 :iv-rank/label :elevated ...}"
 :val-id "v_a1b2c3d4"  ; Content hash for full value lookup
 :timestamp #inst "..."
 :fn-name "iv-rank"
 :duration-ms 42
 :thinking "I'll check if options are expensive..."
 :truncated? false
 :full-chars 156}
```

### Key Insights

1. **Two-level storage** - Truncated output for context window, full value stored by content hash. This was borrowed from `repl-recording.md` research.

2. **Content-addressed IDs** - Using SHA-256 hash prefix means same value always gets same ID, enabling deduplication.

3. **Capture input as string** - Not the evaluated form, but what the agent typed. Important for training data.

### Truncation Strategy

- **2000 character limit** - About 500 tokens, reasonable for one interaction
- **Use `*print-length*` and `*print-level*`** - Standard Clojure truncation
- **Add marker when truncated** - `;; ... (2500 chars, truncated)`

---

## 3. Thinking Token Separation

### Problem

LLM responses mix prose reasoning with executable code. We need to:
- Extract code fragments to execute
- Preserve thinking as context/training data
- Handle various response formats

### Approaches Explored

#### A. Delimiter-Based (Markdown)
```
Let me analyze this.

```clojure
(iv-rank ctx {:ticker "SPY"})
```
```

**Pros:** Standard, LLMs know this format
**Cons:** Extra typing in REPL context, verbose

#### B. Heuristic-Based (Balanced Forms)
Look for `(...)`, `{...}`, `[...]` patterns. Everything else is thinking.

**Pros:** Natural, no special syntax
**Cons:** Edge cases (strings with parens, partial forms)

#### C. First-Line Convention
First line determines mode:
- Starts with prose -> thinking mode
- Starts with `(` -> code mode

**Pros:** Simple
**Cons:** Doesn't handle mixed content well

### Decision: Hybrid B+C with Markdown Fallback

The `parse-agent-response` function uses a layered strategy:

1. **If markdown code blocks exist** - Extract those as code, rest is thinking
2. **Otherwise, find balanced top-level forms** - Those are code
3. **Everything between forms** - Thinking

### Implementation Challenges

1. **Balanced form detection** - Need a state machine to handle:
   - Nested parens `(foo (bar (baz)))`
   - String literals with parens `"(not code)"`
   - Escape sequences `\"escaped\"`

2. **Preserving order** - Thinking often leads into code. Must maintain interleaved sequence.

3. **Edge cases** - What about `{:key "value"}` alone? It's valid Clojure but could be commentary. Current approach: if it parses as Clojure, treat as code.

### What Worked

The `find-balanced-form` function handles most cases:
```clojure
(defn- find-balanced-form [s pos]
  ;; State machine tracking depth, in-string?, escaped?
  ...)
```

Key insight: Track `in-string?` state to ignore parens inside strings.

### What Didn't Work

1. **Regex-based extraction** - Too brittle for nested structures
2. **Full Clojure reader** - Would catch syntax errors we want to preserve
3. **Always requiring delimiters** - Adds friction for agents

---

## 4. Self-Reinforcing Template Design

### Problem

The template should teach agents by example. The format of examples should match what we expect agents to produce.

### Design Principles

1. **Show real data** - Execute actual queries at template time, not static examples
2. **Format matches expectation** - If we show `(iv-rank ctx {:ticker "SPY"})` followed by result, agent learns to write that exact pattern
3. **Include thinking** - Show how thinking flows into code

### Template Structure

```
Session: seon.agent.bako
Started: 2025-12-21T10:00:00Z
Market Date: 2024-06-15T16:00:00Z

═══════════════════════════════════════════════════════════════════

SEON Trading Agent Session

[Instructions here...]

FORMAT: Write your thinking, then the code. Example:

I'll check the current IV rank for SPY to see if options are expensive.

(iv-rank ctx {:ticker "SPY"})

Results will appear after each command.
```

### Key Insight

The instructions include a FORMAT section showing:
1. Thinking as prose
2. Code on its own line
3. Results appear after

This teaches the agent the exact pattern we expect, which our parser can then extract.

---

## 5. Training Data Export

### Format Choice

Using OpenAI/Anthropic chat format for JSONL export:

```json
{
  "messages": [
    {"role": "system", "content": "You are a trading analyst..."},
    {"role": "user", "content": "Analyze SPY for opportunities"},
    {"role": "assistant", "content": "I'll check IV rank...\n```clojure\n(iv-rank ctx ...)\n```\nResult:..."}
  ],
  "metadata": {...}
}
```

### Why This Format

- Standard across OpenAI, Anthropic, HuggingFace
- Supports multi-turn conversations
- Metadata field for session info (frozen time, outcome, tags)

---

## 6. Open Questions / Future Work

### Unresolved

1. **Live execution in template** - Currently templates are static. Real implementation should execute queries to show current market data.

2. **Multi-turn parsing** - Current parser handles single responses. Need to handle conversation flow.

3. **Error handling** - What if code throws? How to record errors as training data?

4. **Annotation** - From `repl-recording.md`, we have `annotate!` and `tag!` for retroactive labeling. Not yet integrated into this session system.

### Ideas to Explore

1. **CVCV collision detection** - Could check if ID already in use and regenerate
2. **Pronounceable but longer** - CVCVCV (6 chars) for more uniqueness
3. **Semantic parsing** - Use tree-sitter for more robust form detection

---

## 7. Code Location

All prototype code is in:
`/Users/sean/src/seon/src/seon/trading/agent/session.clj`

Key functions:
- `gen-session-id` - CVCV namespace generator
- `parse-agent-response` - Thinking/code separation
- `create-session` - Session state initialization
- `record-interaction!` - REPL pair recording
- `exec!` - Macro for executing and recording
- `generate-template` - Template generation
- `session->training-example` - Export for training

---

## 8. Example Usage

```clojure
(require '[seon.trading.agent.session :as sess])

;; Create session
(def s (sess/create-session db {:goal "Analyze SPY" :ticker "SPY"}))
;; => #atom {:session/id "bako", :session/namespace "seon.agent.bako", ...}

;; Parse agent response
(sess/parse-agent-response
 "I'll check IV.

(iv-rank ctx {:ticker \"SPY\"})")
;; => [{:type :thinking :content "I'll check IV."}
;;     {:type :code :content "(iv-rank ctx {:ticker \"SPY\"})"}]

;; Record interaction
(sess/record-interaction! s "(iv-rank ctx {:ticker \"SPY\"})"
                          {:iv-rank/value 0.73}
                          {:thinking "Checking IV"})

;; Get history
(sess/session-history s)
;; => ";; Checking IV\n(iv-rank ctx {:ticker \"SPY\"})\n;; => {:iv-rank/value 0.73}"

;; Export for training
(sess/session->training-example s)
;; => {:messages [...], :metadata {...}}
```

---

## 9. Summary

| Component | Approach | Rationale |
|-----------|----------|-----------|
| Session ID | CVCV (`bako`) | Short, pronounceable, no confusing chars |
| REPL Capture | Two-level (truncated + full) | Context window limits |
| Value IDs | Content hash (`v_a1b2c3d4`) | Deduplication, stable references |
| Thinking Parse | Markdown fallback + balanced forms | Handles multiple formats |
| Template | Self-reinforcing examples | Format teaches expected format |
| Export | JSONL chat format | Industry standard |

The prototype is functional for experimentation. Next steps are integration with actual trading functions and live session testing.
