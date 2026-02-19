# Error Hints Requirements Specification

## Purpose

The dev hook (`bin/seon-hook`) provides feedback to Claude Code agents via `additionalContext` in hook responses. These messages appear in the agent's context after file edits, guiding their next actions.

**Why actionable hints matter:**
- Agents don't have memory between tool calls - they need explicit guidance
- Vague messages waste context tokens without helping
- Agents need commands they can copy-paste or clear next steps
- "What happened" is less useful than "what to do about it"

## Current State

### All `add-feedback!` Calls

| Line | Context | Current Message |
|------|---------|-----------------|
| 640 | Unit tests pass | `"✓ %d tests passed (%s)"` (test-count, test-ns) |
| 648 | Unit tests timeout | `"⚠ Unit tests timed out - check for slow tests or infinite loops"` |
| 651 | Unit tests error (parse/etc) | `"⚠ Unit tests failed to run: %s"` (error) |
| 681 | Generative tests pass | `"✓ Generative tests passed (%s)"` (ns-sym) |
| 688 | Generative tests timeout | `"⚠ Generative tests timed out - check for slow generators"` |
| 691 | Generative tests error | `"⚠ Generative tests failed to run: %s"` (error) |
| 730 | Gemini review complete | `"Gemini: %s"` (truncated review-text) |
| 803 | nREPL unavailable | `"⚠ nREPL not available (port 7888) - skipping tests"` |

### Blocking Responses (via `block-response`)

| Line | Context | Current Message |
|------|---------|-----------------|
| 626 | Compile error | `"Compile error in %s: %s"` (ns-sym, error) |
| 657-666 | Unit tests fail | Multi-line with failure counts and `clj -M:test` command |
| 697-708 | Generative tests fail | Multi-line with function names and `fb/check-namespace` command |

## Analysis: Good vs Bad Messages

### Good Examples (Already Actionable)

**Unit test failures (lines 657-666):**
```
Unit tests failed: 2 failures, 0 errors in seon.foo-test

Fix the failing tests before continuing. Run tests with:
  clj -M:test -m kaocha.runner --focus seon.foo-test

Or update the test expectations if the behavior change is intentional.
```
- Specific counts
- Includes the test namespace
- Provides exact command to run
- Suggests alternative action if intentional

**Generative test failures (lines 697-708):**
```
Generative tests failed for: process-order, validate-input

The function schemas don't match the implementation. Fix by:
1. Check the :malli/schema metadata on the failing functions
2. Update the schema to match actual behavior, OR
3. Fix the function to match the declared schema

Run generative tests manually:
  (require '[seon.dev.feedback :as fb])
  (fb/check-namespace 'seon.trading {:num-tests 10})
```
- Lists specific failing functions
- Explains what the problem means
- Provides numbered steps
- Includes REPL command

### Bad Examples (Need Improvement)

**nREPL unavailable (line 803):**
```
BAD:  "⚠ nREPL not available (port 7888) - skipping tests"
```
- States the problem but not the fix
- "skipping tests" is passive
- Agent doesn't know how to resolve

**Unit tests error (line 651):**
```
BAD:  "⚠ Unit tests failed to run: %s"
```
- Just echoes the error
- No guidance on what to do
- Missing namespace context

**Generative tests error (line 691):**
```
BAD:  "⚠ Generative tests failed to run: %s"
```
- Same issue - just echoes error
- No actionable next step

**Unit tests timeout (line 648):**
```
MEDIOCRE: "⚠ Unit tests timed out - check for slow tests or infinite loops"
```
- Vague "check" instruction
- No specific command or location

**Generative tests timeout (line 688):**
```
MEDIOCRE: "⚠ Generative tests timed out - check for slow generators"
```
- Same vague guidance

## Proposed Changes

### 1. nREPL Unavailable

**Current:** `"⚠ nREPL not available (port 7888) - skipping tests"`

**Proposed:** `"⚠ nREPL unavailable - restart server: ./bin/run"`

**Why:** Provides the exact fix command. Agent knows what to do.

### 2. Unit Tests Error

**Current:** `(format "⚠ Unit tests failed to run: %s" (:error result))`

**Proposed:** `(format "⚠ Unit tests error in %s: %s - check test file syntax" test-ns (:error result))`

**Why:** Adds namespace context and suggests what to check.

### 3. Generative Tests Error

**Current:** `(format "⚠ Generative tests failed to run: %s" (:error gen-result))`

**Proposed:** `(format "⚠ Gen tests error in %s: %s - check schema definitions" ns-sym (:error gen-result))`

**Why:** Adds namespace and points to likely cause (schemas).

### 4. Unit Tests Timeout

**Current:** `"⚠ Unit tests timed out - check for slow tests or infinite loops"`

**Proposed:** `(format "⚠ Unit tests timed out (%s) - reduce test scope or check for infinite loops. Run: clj -M:test --focus %s" test-ns test-ns)`

**Why:** Includes namespace and specific command.

### 5. Generative Tests Timeout

**Current:** `"⚠ Generative tests timed out - check for slow generators"`

**Proposed:** `(format "⚠ Gen tests timed out (%s) - reduce num-tests or simplify schemas. Run in REPL: (fb/check-namespace '%s {:num-tests 5})" ns-sym ns-sym)`

**Why:** Includes namespace and actionable REPL command.

### 6. Compile Error (Already Good, Minor Tweak)

**Current:** `(format "Compile error in %s: %s" ns-sym (:error reload-result))`

**Proposed:** `(format "Compile error in %s: %s\n\nFix the syntax error and save the file." ns-sym (:error reload-result))`

**Why:** Explicit "save the file" reminds agent to complete the edit.

## Design Principles

1. **Always include the fix action** - Don't just describe the problem
2. **Be specific** - Include namespaces, file paths, exact commands
3. **Keep it concise** - Agent context is limited (10k chars total for all feedback)
4. **Use consistent format** - `"⚠ [problem] - [action]"` for warnings
5. **Provide runnable commands** - Copy-pasteable shell or REPL commands
6. **Suggest alternatives** - When the "fix" might be intentional behavior change

## Message Format Guidelines

### Warning Messages (non-blocking)
```
Format: "⚠ [Problem description] ([context]) - [action to take]"

Examples:
- "⚠ nREPL unavailable - restart server: ./bin/run"
- "⚠ Unit tests timed out (seon.foo-test) - run: clj -M:test --focus seon.foo-test"
- "⚠ Gen tests error in seon.bar: Schema not found - check :malli/schema on functions"
```

### Success Messages
```
Format: "✓ [What passed] ([context])"

Examples:
- "✓ 5 tests passed (seon.foo-test)"
- "✓ Generative tests passed (seon.bar)"
```

### Blocking Messages
```
Format:
[Problem statement with specific counts/details]

[Blank line]
[Fix instructions - numbered if multiple steps]
[Blank line]
[Exact command to run]
```

## Implementation Checklist

- [ ] Update nREPL unavailable message (line 803)
- [ ] Update unit tests error message (line 651)
- [ ] Update generative tests error message (line 691)
- [ ] Update unit tests timeout message (line 648)
- [ ] Update generative tests timeout message (line 688)
- [ ] Optionally enhance compile error message (line 626)

## Related: Configurable Debounce

The `should-trigger-review?` function uses a hardcoded debounce. This should be made configurable:

1. `feedback.clj`: Update to accept `debounce-seconds` parameter
2. `bin/seon-hook`: Pass config value to nREPL call
3. `.claude/seon-hook.edn`: Add `:debounce-seconds 30` to `:gemini` section

## Testing

After implementing these changes:

1. **Verify nREPL message:**
   - Stop the server
   - Edit a Clojure file
   - Check additionalContext shows new message

2. **Verify test timeout messages:**
   - Create a test with `(Thread/sleep 35000)`
   - Edit the source file
   - Check timeout message includes namespace and command

3. **Verify blocking messages unchanged:**
   - Create a failing test
   - Verify block response still has full instructions
