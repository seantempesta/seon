# Handoff: Fix Gemini Review in Dev Hook

## Context

We refactored `seon.ai.gemini` to use a map-based API with namespaced keys:

```clojure
;; OLD (positional args):
(gemini/ask "prompt" {})

;; NEW (map-based with namespaced keys):
(gemini/ask {::gemini/prompt "prompt"})

```

The dev hook (`bin/seon-hook`) still uses the old API, so Gemini reviews are broken.

## New Structured Review API (In Progress)

Someone started adding structured code reviews to `gemini.clj`:

```clojure
;; New schemas added:
::code-review-request   ; Input: prompt, code, context
::code-review-response  ; Output: verdict, confidence, summary, issues

;; Verdicts:
:approve, :request-changes, :block

;; Issue structure:
{:severity :critical|:warning|:suggestion
 :location "line 42"
 :description "..."
 :suggestion "..."}

```

Uses Gemini's structured output (JSON mode) via `code-review-json-schema`.

**TODO**: Need to add a `review-code` public function that uses this schema.

## What's Broken

In `bin/seon-hook`, the `stage-gemini-review` function calls:

```clojure
(nrepl-eval
  (format "(seon.ai.gemini/ask \"%s\" {})"
          (str/replace prompt "\"" "\\\"")))

```

This needs to use the new structured review API (once it exists):

```clojure
(nrepl-eval
  (format "(seon.ai.gemini/review-code {:seon.ai.gemini/prompt \"...\" :seon.ai.gemini/code \"%s\"})"
          escaped-code))

```

## Additional Issues

1. **XTDB function tracking** - Was getting protocol errors. Server has been restarted but verify `record-function!` and `file-changed?` work in `seon.dev.feedback`.

2. **New function detection** - The hook should detect when a function is new (not seen before in XTDB) and trigger a Gemini review.

## Tasks

1. **Add `review-code` function to gemini.clj**
   - Schemas are already added (`::code-review-request`, `::code-review-response`)
   - JSON schema is defined (`code-review-json-schema`)
   - Need to add the public function that uses structured output
   - Should return parsed JSON as Clojure map with verdict, issues, etc.

2. **Update bin/seon-hook to use new API**
   - Replace `stage-gemini-review` to call `review-code`
   - Parse the structured response (verdict, issues)
   - Block on `:block` verdict, warn on `:request-changes`
   - Display issues with severity/location/suggestion

3. **Verify function tracking works**
   - In REPL: `(require '[seon.dev.feedback :as fb])`
   - Test: `(fb/file-changed? db "src/seon/ai/gemini.clj")` should return true/false
   - Test: `(fb/record-function! db 'seon.ai.gemini 'ask {...})` should not error

4. **Test end-to-end**
   - Edit a Clojure file to add a new function
   - Hook should: run tests, detect new function, request structured review
   - Review should show: verdict, confidence, issues with severity
   - `:block` verdict should block Claude, `:approve` should pass through

## Key Files

- `bin/seon-hook` - Babashka hook script (fix the API call here)
- `src/seon/ai/gemini.clj` - Gemini API (already refactored, reference for API)
- `src/seon/dev/feedback.clj` - Function tracking in XTDB
- `.claude/seon-hook.edn` - Hook configuration
- `.claude/seon-hook.log` - Hook debug logs

## Expected Behavior When Working

When you add a new function to a Clojure file:

```
[hook runs]
- Syntax repair
- Reload namespace
- Run unit tests (12 passed)
- Run generative tests (10 passed)
- Record functions
- Requesting code review for new function: foo
- Code Review: APPROVE (high confidence)
  Summary: Function follows conventions and handles edge cases.

```

When review finds issues:

```
[hook runs]
- Requesting code review for new function: bar
- Code Review: REQUEST-CHANGES (medium confidence)
  Summary: Function has potential issues.
  Issues:
    [warning] line 42: Missing nil check for input parameter
      Suggestion: Add (when input ...) guard
    [suggestion]: Consider using ::keys destructuring
- [WARN] Review requested changes - please address issues

```

When review blocks:

```
[hook runs]
- Code Review: BLOCK (high confidence)
  Summary: Critical security issue detected.
  Issues:
    [critical]: SQL injection vulnerability in query construction
      Suggestion: Use parameterized queries
- [BLOCKED] Code review blocked this change

```

## Verification Commands

```bash
# Check hook log
tail -f .claude/seon-hook.log

# Test Gemini API directly (in REPL)
(require '[seon.ai.gemini :as gemini])
(gemini/ask {::gemini/prompt "Say hello"})

# Test function tracking (in REPL)
(require '[seon.dev.feedback :as fb])
(fb/file-changed? (xtdb-node) "src/seon/ai/gemini.clj")

```

## Success Criteria

1. Hook logs show "Requesting Gemini review" when new functions are added
2. Gemini review text appears in Claude's output (info message)
3. No errors in `.claude/seon-hook.log` related to Gemini API calls
4. Function tracking via XTDB works without protocol errors

---

## Cleanup: Rename "pending" to "review queue"

The term "pending edit" is misleading - edits are already applied, they're just **queued for batch review**.

### Files to update:

**`src/seon/dev/feedback.clj`** (lines 418-511):

```
record-pending-edit!     → queue-for-review!
pending-edits            → review-queue
oldest-pending-edit-age  → oldest-queued-age
clear-pending-edits!     → clear-review-queue!
pending-edits-summary    → review-queue-summary
:pending-edit (entity)   → :queued-edit

```

**`bin/seon-hook`** (lines 403-690):

```
stage-record-pending-edit  → stage-queue-for-review
stage-get-pending-summary  → stage-get-review-queue
stage-clear-pending        → stage-clear-review-queue

```

**XTDB table**: `:pending-edit` → `:queued-edit`

**Log messages**: Change "pending" to "queued for review"

Note: `trading/ingest.clj` uses `:pending` for work items - that's different context, leave it.
