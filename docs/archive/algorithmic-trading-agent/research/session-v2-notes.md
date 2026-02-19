# Session Template System V2 - Change Notes

**Date:** 2025-12-21
**Author:** Claude (Implementation)
**Status:** Complete

---

## Summary

Simplified the session template system to use a cleaner, more predictable parsing rule. The V2 system replaces complex balanced-form detection with a simple "last paragraph is code" rule.

---

## Key Changes

### 1. Raw Input Preservation

**Before:** Only stored the parsed/processed input.

**After:** Every REPL pair now has two fields:
- `input` - The individual code expression that was executed
- `raw-input` - The complete, original agent response before parsing

This is critical for training data. We want to see exactly what the agent wrote, not just the extracted code.

```clojure
;; Example REPL pair now includes:
{:input "(iv-rank ctx {:ticker \"SPY\"})"
 :raw-input "I'll check the IV rank for SPY.\n\n(iv-rank ctx {:ticker \"SPY\"})"
 :output "{:iv-rank/value 0.73 ...}"
 ...}
```

The session also tracks all raw inputs in `:raw-inputs` vector for complete history.

### 2. Simplified Parsing Rule

**Before:** Complex heuristic using:
- Balanced form detection with state machine
- Markdown code block extraction
- Interleaved thinking/code segments

**After:** Dead simple rule:

```
Split on the LAST `\n\n` (double newline).
- Everything BEFORE = thinking
- Everything AFTER = code
- Each non-empty line in code = one REPL input
```

Why this is better:
1. **Predictable** - No edge cases with string contents or partial forms
2. **Teachable** - Easy to explain to agents in the template
3. **Robust** - Works with any valid Clojure expression

### 3. All Expression Types Supported

**Before:** Only looked for `(...)`, `{...}`, `[...]` balanced forms.

**After:** Handles all valid Clojure REPL inputs:

| Expression | Description | Handled |
|------------|-------------|---------|
| `(foo bar)` | S-expression | Yes |
| `{:a 1}` | Map literal | Yes |
| `[1 2 3]` | Vector literal | Yes |
| `@ctx` | Deref | Yes (V2) |
| `ctx` | Symbol | Yes (V2) |
| `*1` | Last result | Yes (V2) |
| `42` | Number | Yes (V2) |
| `:keyword` | Keyword | Yes (V2) |
| `"string"` | String | Yes (V2) |

The key insight: if there's no `\n\n`, we use heuristics to determine if it's code or thinking:
- Starts with `( [ { @ * : " ' \` # ^` -> code
- Single word, no spaces, under 50 chars -> likely code (symbol/number)
- Otherwise -> thinking

### 4. Multiple Expressions Per Response

**Before:** Each code form was parsed individually from mixed content.

**After:** Each line in the code section executes separately:

```
Let me check a few things.

(iv-rank ctx {:ticker "SPY"})
(skew ctx {:ticker "SPY"})
@ctx
```

This becomes three separate REPL interactions, each with:
- Shared thinking text
- Shared raw-input (the full response)
- Individual execution and result capture

### 5. Updated Template

The template now explicitly teaches the new format with three examples:

1. **Single expression** - Thinking paragraph, blank line, one function call
2. **Multiple expressions** - Thinking paragraph, blank line, several lines of code
3. **Quick inspection** - Just `@ctx` with no thinking

The INSPECT section was added to show non-paren expressions:
```
INSPECT:
  @ctx      ; Dereference ctx to see current session state
  *1        ; Last REPL result
  ctx       ; The ctx atom itself
```

---

## What Was Removed

### Balanced Form Detection

Deleted the complex `find-balanced-form` function and related code:
- No more state machine for tracking depth, string state, escapes
- No more `extract-code-blocks` for markdown parsing
- No more `find-top-level-forms` scanning

This was ~100 lines of complex, edge-case-prone code replaced by ~50 lines of simple string manipulation.

### Alternative Session ID Generators

Kept only the CVCV generator. Removed:
- `gen-session-id-hex`
- `gen-session-id-words`
- `gen-session-id-hybrid`

These were exploratory alternatives that were documented but never used. CVCV is the clear winner.

---

## API Changes

### `parse-agent-response`

**Before:** Returned vector of segments:
```clojure
[{:type :thinking :content "..."}
 {:type :code :content "..."}]
```

**After:** Returns a single map:
```clojure
{:raw "..."       ; Original input preserved
 :thinking "..."  ; Thinking text (or nil)
 :code ["..." ...]}  ; Vector of code strings to execute
```

### `process-agent-response!`

**Before:** Returned vector of results from code execution.

**After:** Returns a map with complete context:
```clojure
{:raw "..."       ; Original response
 :thinking "..."  ; Extracted thinking
 :results [{:input "..." :output ... :error? false}
           ...]}
```

Also now:
- Records raw inputs in session `:raw-inputs` vector
- Passes `:raw-input` to each `record-interaction!` call

### `create-repl-pair`

**Added:** `:raw-input` option to preserve original response.

### `session->training-example`

**Added:** `:raw-inputs` in metadata for complete training data.

---

## Backward Compatibility

The following functions still work with the same signatures:
- `extract-executable-code` - Now wraps `parse-agent-response`
- `extract-thinking` - Now wraps `parse-agent-response`
- `gen-session-id` - Unchanged
- `create-session` - Unchanged
- `record-interaction!` - Added optional `:raw-input` in opts
- `exec!` macro - Unchanged

---

## Testing Notes

The comment block includes comprehensive examples for REPL testing:

```clojure
;; V2 Parsing examples
(parse-agent-response "I'll check.\n\n@ctx")
(parse-agent-response "@ctx")
(parse-agent-response "*1")
(parse-agent-response "42")
(parse-agent-response "This is just thinking.")
```

All examples show the expected output format.

---

## Design Rationale

### Why "Last Paragraph = Code"?

1. **Natural writing flow** - Agents naturally write thinking, then action
2. **No delimiter overhead** - No markdown blocks or special syntax
3. **Unambiguous** - Double newline is a clear separator
4. **Handles edge cases** - No need to parse Clojure syntax

### Why Per-Line Execution?

1. **Multiple actions** - Agent can do several things in one response
2. **Clear boundaries** - Each line is one complete expression
3. **Error isolation** - One failing line doesn't stop others
4. **Training clarity** - Each line gets its own input/output pair

### Why Preserve Raw Input?

1. **Training data fidelity** - See exactly what agent wrote
2. **Context reconstruction** - Understand why agent wrote what they did
3. **Format learning** - Train future agents on full thinking+code pattern
4. **Debugging** - Trace back from execution to original intent

---

## Future Considerations

### Multi-Line Expressions

Current limitation: Each line is one expression. This means multi-line expressions need to be on one line:

```clojure
;; This works:
{:key "value" :other "thing"}

;; This doesn't work (yet):
{:key "value"
 :other "thing"}
```

For V3, could detect incomplete expressions and join with following lines until balanced. But the simple rule is good enough for most cases.

### Markdown Code Blocks

Removed markdown support for simplicity. If agents commonly use markdown, could add it back as a fallback. But teaching the simpler format in the template should prevent this.

---

## Files Changed

- `/Users/sean/src/seon/src/seon/trading/agent/session.clj` - Complete rewrite of parsing logic
- `/Users/sean/src/seon/docs/prds/algorithmic-trading-agent/research/session-v2-notes.md` - This document

---

## Verification

To verify the changes work:

```clojure
(require '[seon.trading.agent.session :as sess])

;; Test parsing
(sess/parse-agent-response "Thinking here.\n\n@ctx")
;; => {:raw "Thinking here.\n\n@ctx" :thinking "Thinking here." :code ["@ctx"]}

;; Test multiple expressions
(sess/parse-agent-response "Check.\n\n(foo)\n(bar)\n@ctx")
;; => {:raw "..." :thinking "Check." :code ["(foo)" "(bar)" "@ctx"]}

;; Test non-paren expressions
(sess/parse-agent-response "*1")
;; => {:raw "*1" :thinking nil :code ["*1"]}
```
