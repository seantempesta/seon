---
type: research
status: completed
tags: [research, archive]
---

# Phase 6 Hook Refactor - Gemini Code Review

**Date:** 2025-12-30
**Model:** Gemini (via seon.ai.gemini/review-code)
**Input:** Full review package from data/phase6/review-package.md (173KB)

---

## Round 1: Full Package Review

The refactor significantly improves testability and structure but contains a critical logic error in the main orchestrator that breaks the "blocking" functionality. It also misses a key instrumentation step required for Malli generative tests to function.

### CRITICAL

-   **Broken Blocking Logic (`seon.dev.hook/process-hook-event!`):** The pipeline (Lines 381–424) uses a `do` block where intermediate results from `stage-repair`, `stage-reload`, and test failures are calculated but never returned. The function always falls through to the final `(success-response @feedback)`. Consequently, even if a syntax error is unfixable or tests fail, the hook will return `::continue true`, failing to block Claude from committing bad code.
-   **Missing Instrumentation (`seon.dev.verify/run-gen-tests`):** Malli's `(m/function-schemas)` only returns schemas that have been explicitly registered or collected. The old implementation (Old Line 552) called `(mi/collect! {:ns ns-sym})`. The new code (New Line 227) lacks this call. Generative tests will likely find zero functions to test unless they are manually collected elsewhere in the REPL session.

### MAJOR

-   **Loss of Change Detection:** The old implementation (Old Line 486) checked a file hash to skip processing if the content hadn't changed. The new implementation has removed this. Every "Edit" or "Write" tool use will now trigger a full test suite and potentially a Gemini review, even if the file content is identical (e.g., a "no-op" save), increasing latency and token costs.
-   **Namespace Derivation Fragility (`seon.dev.review/source->test-path`):** Line 139 hardcodes a regex replacement for `^src/`. If the project is in a subdirectory or uses a different structure (e.g., `src/clj/`), context building for reviews will fail to find associated test files.

### MINOR

-   **Hardcoded Port in BB Wrapper:** `bin/seon-hook` (Line 20) hardcodes `nrepl-port 7888`. This should ideally be read from `.nrepl-port` or the provided config to avoid connection failures in environments with dynamic ports.
-   **Truncation Marker Placement:** In `seon.dev.review/truncate` (Line 131), the `[truncated]` marker is appended to the end. If the string is highly structured (like code), this is fine, but for `format-output` (Line 310), it may cut off Gemini's most actionable feedback (usually at the end) without a clear visual break.

### Summary of Key Questions

1.  **Functionality lost?** Yes. File hashing (change detection) and explicit Malli instrumentation (`mi/collect!`) were lost.
2.  **Malli Compliance?** Yes. Namespaces, map-in/map-out, and namespaced keys are correctly implemented.
3.  **Edge cases/Error handling?** No. The orchestrator logic failure is a significant gap.
4.  **Integration correct?** Mostly, except for the failure to return "block" decisions.
5.  **SQL used?** Yes. `seon.dev.context` correctly uses SQL via `node/sql-query`.
6.  **BB Wrapper Graceful?** Yes. It uses `try/catch` and defaults to `continue` if the nREPL is down.

---

## Round 2: Critical Issue Verification

Summary: **Both issues are confirmed.** The pipeline logic in `process-hook-event!` discards results from blocking checks, and `run-gen-tests` will fail to find any schemas because Malli's instrument collection is never initialized.

### ISSUE 1: Discarded Return Values in `process-hook-event!`

In the "Full pipeline" section of `process-hook-event!`, you use a `do` block where several expressions call `block-response`, but their return values are ignored. The function always proceeds to the final `(success-response @feedback)`.

**Problematic Code:**

```clojure
;; seon.dev.hook: lines 268-306
(do
  ;; 1. Repair
  (let [repair-result (stage-repair file-path config)]
    (when (and repair-result (not (:success repair-result)))
      (block-response (:error repair-result)))) ;; Value discarded

  ;; 2. Reload namespace
  (let [reload-result (stage-reload ns-sym)]
    (when-not (:success reload-result)
      (block-response (:error reload-result)))) ;; Value discarded

  ;; ... Unit/Gen tests call block-response inside when blocks ...
  ;; All these values are discarded.

  ;; Success
  (success-response @feedback)) ;; This is ALWAYS the return value

```
*Fix:* Use `cond`, `some->`, or check the result of each stage and return early if a "block" decision is reached.

---

### ISSUE 2: Missing `collect!` in `run-gen-tests`

The `run-gen-tests` function relies on `(m/function-schemas)`. In Malli, function schemas defined via metadata (like the one on line 224: `{:malli/schema ...}`) are not automatically registered. You must call `malli.instrument/collect!` for them to be visible to `m/function-schemas`.

**Problematic Code:**

```clojure
;; seon.dev.verify: lines 177-180
(defn- get-function-schemas
  "Get all functions with Malli schemas in a namespace."
  [ns-sym]
  (get (m/function-schemas) ns-sym)) ;; Returns empty map unless collect! was called

```
*Fix:* Add `[malli.instrument :as mi]` to the requires and call `(mi/collect!)` (typically once at application startup or within `run-gen-tests` before checking). Without this, `failures` on line 227 will always be `[]` regardless of code state.

---

## Summary

### Confirmed Issues Requiring Fixes

| Severity | Issue | Location | Status |
|----------|-------|----------|--------|
| CRITICAL | Blocking logic broken - do block discards return values | hook.clj:268-306 | MUST FIX |
| CRITICAL | Missing mi/collect - gen tests find no schemas | verify.clj:177-180 | MUST FIX |
| MAJOR | Lost file hash change detection | hook.clj | Should fix |
| MAJOR | Hardcoded src/ path in test derivation | review.clj:139 | Should fix |
| MINOR | Hardcoded nREPL port 7888 | bin/seon-hook:20 | Nice to have |
| MINOR | Truncation marker placement | review.clj:131 | Nice to have |

---

## Round 3: Test Coverage Review

**Input:** All 6 test files (60KB)

The test suite covers filtering and utility logic well but lacks coverage for the core "hook" orchestration and failure handling.

### Summary of Concerns

The tests focus heavily on "happy paths" and early-exit filtering. They do not sufficiently exercise the blocking logic, the integration between stages (repair -> verify -> review), or the specific side effects required for generative testing.

### Specific Issues

1.  **Blocking Behavior Unverified**: There are no tests in `hook_test.clj` that simulate a failure (e.g., a failing unit test or a rejected review) to verify that `::hook/continue` becomes `false`. Every test currently asserts that the hook *continues*.
2.  **Instrumentation Gap**: `verify_test.clj` does not verify that `mi/collect!` (or any Malli instrumentation) is called before running generative tests. Since `mi/collect!` is a side effect, the tests should likely use a spy/mock to ensure it is triggered.
3.  **Orchestration Logic Missing**: `hook_test.clj` does not test the pipeline flow. For example:
    -   If `repair` fixes a file, does the `verify` stage receive the repaired content or the disk content?
    -   If `unit-tests` fail, is the `review` stage skipped or does it receive the failure logs?
4.  **Hardcoded Absolute Paths**: `review_test.clj` and `codebase_test.clj` contain hardcoded paths like `"/Users/sean/src/seon/..."`. These tests will fail in CI or on other developer machines. Use relative paths or temp files.
5.  **Weak Generative Test Coverage**: In `verify_test.clj`, the generative test success is verified by running against a namespace with *no schemas*. This doesn't test if the runner can actually find and exercise Malli schemas or handle generative failures correctly.
6.  **Context Integration is Mocked by Omission**: The `context-integration-test` in `hook_test.clj` catches all exceptions and returns `continue true`. This masks actual integration failures and doesn't verify that the edit was actually recorded in XTDB.
7.  **Format vs. Repair Conflict**: In `repair_test.clj`, `repair-and-format` returns success if code is already valid. It doesn't explicitly test the case where code is valid but *incorrectly formatted*, which is a primary reason to call the function.
8.  **Namespace Derivation Fragility**: `codebase_test.clj` relies on the physical presence of `src/seon/core.clj`. Tests for namespace derivation should ideally use `with-redefs` on file-io or use a dedicated test-resource folder to remain hermetic.
