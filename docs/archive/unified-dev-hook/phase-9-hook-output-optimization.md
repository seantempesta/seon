# PRD: Phase 9 - Hook Output Optimization

**Status:** Complete
**Depends On:** Phase 8 (complete)
**Branch:** `feature/unified-dev-hook`
**Completed:** 2026-01-02

---

## Context

The unified development hook (`bin/seon-hook`) provides feedback to Claude agents after file edits. The feedback needs optimization:

1. **Shallow compliance checks** - Only checks if `:malli/schema` metadata key exists, not if schemas are registered
2. **Non-actionable feedback** - "missing schema" doesn't show HOW to fix it
3. **No compliance checking in pipeline** - Agents aren't warned when they introduce convention violations

**Goal:** Make every token of feedback count. Brief success confirmation is fine, but failures/violations must be actionable with copy-pasteable fixes.

---

## Decisions Made

| Question | Decision | Rationale |
|----------|----------|-----------|
| Should compliance block edits? | **No** (but add config option for later) | Too aggressive now, want to enable later |
| Store compliance in XTDB? | **No** | Overkill - we just need real-time feedback |
| Function-level test targeting? | **Defer** | Tests run in <1s, complexity not worth it yet |
| Multi-arity public functions? | **No** | Map accretion handles extensibility naturally |
| Remove success feedback? | **No** | Brief confirmation is useful, just make it dense |

---

## Success Feedback Philosophy

**Keep it brief but useful.** Agents should know we're checking their work.

**Current (verbose):**

```
"5 tests passed (seon.dev.hook-test)"
"Generative tests passed (seon.dev.hook)"

```

**Better (dense):**

```
"✓ 5 tests, 3 gen-tests (0.2s)"

```

One line with all the signal: tests passed, gen-tests passed, it was fast.

---

## Failure Feedback Philosophy

**Show the fix, not just the problem.** Every violation message should be copy-pasteable.

**Current (not actionable):**

```
"- process-data: missing :malli/schema, not using map-in"

```

**Better (actionable):**

```
process-data needs:

Add to function:
  {:malli/schema [:=> [:cat ::process-data-request] ::process-data-response]}
  [{::keys [input opts]}]

Register schemas:
  (schema/register! ::process-data-request
    [:map [::input :any] [::opts {:optional true} :map]])
  (schema/register! ::process-data-response
    [:map [::result :any]])

```

Note: We show just the pieces needed, not the entire function definition.

---

## Docstring Requirements

When generating fix suggestions, docstrings should include:

1. **Brief description** - What the function does
2. **Request keys** - Each key with description
3. **Response keys** - Each key with description
4. **Example usage** - Common case with actual values
5. **Gotchas** - Important edge cases not obvious from the schema

**Example docstring template:**

```clojure
"Process input data with optional configuration.

 Request keys:
   ::input - Required. The data to process
   ::opts  - Optional. Processing options map

 Response keys:
   ::result  - The processed output
   ::elapsed - Processing time in ms

 Example:
   (process-data {::input {:name \"foo\"} ::opts {::verbose true}})
   ;; => {::result {...} ::elapsed 42}

 Note: Large inputs (>1MB) may timeout. Use ::opts {::async true} for streaming."

```

---

## Deep Compliance Checks

### What We Check Now (Shallow)

- ✅ Has `:malli/schema` metadata key
- ✅ Uses map-in pattern `[{::keys [...]}]`
- ✅ Has docstring

### What We Need to Add (Deep)

- ❌ Schema refs in metadata actually exist in registry
- ❌ Naming convention: `fn-name-request` / `fn-name-response`
- ❌ Generate skeleton fix from function signature

### Schema Verification

```clojure
;; Extract schema refs from :malli/schema
(:malli/schema (meta #'seon.ai.gemini/ask))
;; => [:=> [:cat :seon.ai.gemini/ask-request] :seon.ai.gemini/response]

;; Verify they exist
(schema/registered? :seon.ai.gemini/ask-request)  ;; => true or false

```

### Fix Generation

Parse the function's current signature to generate the fix:

```clojure
;; From: (defn process-data [input opts] ...)
;; Generate:

;; 1. Schema registrations (inferred from param names)
(schema/register! ::process-data-request
  [:map [::input :any] [::opts {:optional true} :map]])
(schema/register! ::process-data-response
  [:map [::result :any]])

;; 2. Metadata to add
{:malli/schema [:=> [:cat ::process-data-request] ::process-data-response]}

;; 3. New signature
[{::keys [input opts]}]

```

---

## Example Output

### All Passing (brief confirmation)

```json
{
  "continue": true,
  "feedback": ["✓ 5 tests, 3 gen-tests, compliant (0.2s)"]
}

```

### Test Failure (blocking)

```json
{
  "decision": "block",
  "reason": "Test failed: expected (= result 42), got 41\n  in calculate-total-test (line 23)"
}

```

### Compliance Violation (actionable fix)

```json
{
  "continue": true,
  "feedback": [
    "process-data needs:\n\nSchema registrations:\n  (schema/register! ::process-data-request\n    [:map [::input :any] [::opts {:optional true} :map]])\n  (schema/register! ::process-data-response\n    [:map [::result :any]])\n\nFunction metadata:\n  {:malli/schema [:=> [:cat ::process-data-request] ::process-data-response]}\n  [{::keys [input opts]}]"
  ]
}

```

### Unregistered Schema Refs

```json
{
  "continue": true,
  "feedback": [
    "process-data references unregistered schemas:\n  Missing: ::process-data-request, ::process-data-response\n\nRegister them:\n  (schema/register! ::process-data-request [:map ...])\n  (schema/register! ::process-data-response [:map ...])"
  ]
}

```

---

## Implementation Phases

### Phase 9a: Add Compliance Stage to Hook

**Goal:** Wire compliance checking into the hook pipeline.

Changes:
1. Add `stage-compliance` function to `hook.clj`
2. Add `:compliance {:enabled true}` to config
3. Call after reload, before tests
4. Add violations to feedback (non-blocking)

### Phase 9b: Deep Schema Verification

**Goal:** Verify schema refs exist, not just metadata presence.

Changes to `compliance.clj`:
1. `verify-schema-refs` - Extract refs from `:malli/schema`, check `schema/registered?`
2. `check-naming-convention` - Verify `fn-name-request`/`fn-name-response` pattern
3. Update `check-var` to include these checks

### Phase 9c: Actionable Fix Generation

**Goal:** Generate copy-pasteable fix code from function signature.

Changes to `compliance.clj`:
1. `extract-param-names` - Get params from function's arglists
2. `generate-schema-registration` - Create `schema/register!` form
3. `generate-fix-suggestion` - Full fix with schemas + metadata + signature
4. Update `format-violations` to use fix generation

### Phase 9d: Dense Success Feedback

**Goal:** Compress success feedback to one useful line.

Changes to `hook.clj`:
1. Collect all success metrics (test count, gen-test count, compliance, timing)
2. Format as single dense line: `"✓ 5 tests, 3 gen-tests, compliant (0.2s)"`
3. Only show if there's something to report

---

## Configuration

```clojure
;; .claude/seon-hook.edn
{:compliance {:enabled true        ;; Run compliance checks
              :block false}        ;; Future: block on violations
 :feedback {:dense true            ;; Use dense success format
            :max-length 1000}}     ;; Max chars per feedback item

```

---

## Files to Change

| File | Changes |
|------|---------|
| `src/seon/dev/hook.clj` | Add `stage-compliance`, dense success format |
| `src/seon/dev/compliance.clj` | Deep checks, fix generation |
| `.claude/seon-hook.edn` | Add compliance config |
| `test/seon/dev/compliance_test.clj` | Tests for deep checks |
| `test/seon/dev/hook_test.clj` | Tests for compliance integration |

---

## Success Criteria

- [x] Compliance stage runs after reload, before tests
- [x] Schema refs verified to exist in registry
- [x] Missing schemas generate `schema/register!` code
- [x] Wrong signatures show correct `[{::keys [...]}]` pattern
- [x] Fix suggestions are copy-pasteable
- [x] Success feedback is one dense line
- [x] Compliance violations don't block (but config option exists)
- [x] All existing tests pass (288 tests, 1377 assertions)
- [x] Hook performance <500ms typical

---

## Implementation Summary

### Phase 9a: Compliance Stage in Hook Pipeline

- Added `stage-compliance` function to `hook.clj` that runs after reload, before tests
- Added `:compliance {:enabled true :block false}` config section
- Added `:feedback {:dense true :max-length 1000}` config section
- Compliance violations are non-blocking by default (added to feedback)
- Config option exists to make blocking (`{:compliance {:block true}}`)

### Phase 9b: Deep Schema Verification

- Added `extract-schema-refs` - walks `:malli/schema` form to find qualified keywords
- Added `check-schema-refs-registered` - verifies all refs exist in `schema/registered?`
- Added `check-naming-convention` - checks fn-name-request/response pattern
- Naming convention is lenient: `clojure-file?` matches `::clojure-file-request`

### Phase 9c: Fix Code Generation

- Added `generate-request-schema` - generates `schema/register!` call with params
- Added `generate-response-schema` - generates response schema stub
- Added `generate-metadata-form` - generates `:malli/schema` metadata
- Added `generate-map-in-signature` - generates `[{::keys [...]}]` signature
- Added `generate-fix` public API - returns copy-pasteable fix code
- `format-violations` now supports `::with-fixes true` for detailed output

### Phase 9d: Dense Success Format

- Added `format-dense-success` - formats one-line success message with timing
- Example output: `"[checkmark] 5 tests, gen-tests, compliant (0.2s)"`
- Dense mode controlled by `{:feedback {:dense true}}`
- Verbose mode (dense: false) shows individual stage feedback

### Configuration Added

```clojure
;; .claude/seon-hook.edn
{:compliance {:enabled true :block false}
 :feedback {:dense true :max-length 1000}}

```

### Tests Added

- `deep-schema-verification-test` - tests schema ref extraction and naming
- `generate-fix-test` - tests fix code generation
- `compliance-stage-test` - tests config defaults
- `dense-feedback-test` - tests dense/verbose feedback modes

---

## Open Questions

1. **Test file compliance?** Test files don't follow map-in conventions. Should we skip them or have separate rules?

2. **Optional param detection?** How to detect which params should be `{:optional true}`? Heuristic: params named `opts`, `options`, `config` are optional?

---

## References

- [Phase 8 PRD](phase-8-convention-compliance.md) - Compliance tooling
- [CONVENTIONS.md](/CONVENTIONS.md) - The authoritative patterns
- `src/seon/dev/compliance.clj` - Existing compliance code
- `src/seon/schema.clj` - Schema registry with `registered?`
