---
type: research
status: completed
tags: [research, archive]
---

# Phase 6 Hook Refactor - Code Review

**Date:** 2025-12-30
**Reviewer:** Claude Opus 4.5 (with Gemini 3 Pro assistance)
**Status:** CRITICAL ISSUES FOUND

---

## Summary of Findings

| Severity | Count | Description |
|----------|-------|-------------|
| CRITICAL | 1 | Control flow bug - blocking never works |
| MAJOR | 3 | Missing revert, positional args, edamame exception handling |
| MINOR | 2 | Silent nREPL failures, race condition in rate limiting |
| NITPICK | 1 | Path traversal (mitigated by other checks) |

**Overall Assessment:** The Phase 6 refactor has a **critical control flow bug** that renders the blocking functionality completely non-functional. The code will always return success, even on compile errors, test failures, or unfixable syntax errors. This must be fixed before deployment.

---

## Critical Issues

### 1. CRITICAL: Block Response Never Returns (Control Flow Bug)

**Location:** `src/seon/dev/hook.clj` lines 363-412

**Problem:** The PostToolUse pipeline uses a `do` block where `block-response` calls are evaluated but their return values are discarded. The function always returns `(success-response @feedback)` regardless of failures.

**Code:**

```clojure
;; Full pipeline
(do
  ;; 1. Repair
  (let [repair-result (stage-repair file-path config)]
    (when (and repair-result (not (:success repair-result)))
      (block-response (:error repair-result))))  ;; <- DISCARDED!

  ;; 2. Reload namespace
  (let [reload-result (stage-reload ns-sym)]
    (when-not (:success reload-result)
      (block-response (:error reload-result))))  ;; <- DISCARDED!

  ;; ... more stages with same issue ...

  ;; Success
  (success-response @feedback))  ;; <- ALWAYS RETURNS THIS

```

**Impact:**
- Syntax errors that can't be repaired still pass
- Compile errors don't block the agent
- Failing unit tests don't block the agent
- Failing generative tests don't block the agent
- The hook is essentially non-functional for quality control

**Fix Required:** Use conditional early returns, e.g.:

```clojure
(or
  (when (and repair-result (not (:success repair-result)))
    (block-response (:error repair-result)))
  (when-not (:success reload-result)
    (block-response (:error reload-result)))
  ;; ... etc ...
  (success-response @feedback))

```

Or refactor to use a threading macro with early exit like `some->`.

---

## Major Issues

### 2. MAJOR: Missing "Revert on Failure" Functionality

**Location:** `src/seon/dev/hook.clj` and `src/seon/dev/repair.clj`

**Problem:** The PRD explicitly requires reverting files when syntax repair fails:
- PRD line 101: "Syntax Repair | ... | On Failure: Revert file, block"
- Design doc line 1177: "If unfixable: backup and restore, block"

The old hook delegated to `clj-paren-repair-claude-hook` which handled backups. The new code blocks (or would, if bug #1 were fixed) but leaves the broken file on disk.

**Impact:** Agent writes broken code -> Repair fails -> File remains broken -> Next edit compounds the problem

**Fix Required:** Add backup/restore logic to `stage-repair` or `repair.clj`:

```clojure
(defn- stage-repair [file-path config]
  (when (and (get-in config [:repair :enabled]) ...)
    (let [backup (slurp file-path)]  ;; Save original
      (if repair-failed
        (do
          (spit file-path backup)  ;; Restore
          {:success false :error "..."})
        {:success true}))))

```

### 3. MAJOR: Convention Violation - Positional Args in Public Functions

**Location:** `src/seon/dev/context.clj` lines 104-147

**Problem:** Per CONVENTIONS.md, public functions should use "map in, map out" with namespaced keys. These functions use positional arguments:

```clojure
(defn record-edit!
  [xtdb-node file-path ns-sym]  ;; <- Positional args
  ...)

(defn record-review!
  [xtdb-node files]  ;; <- Positional args
  ...)

```

**Impact:** Inconsistent API style, prevents uniform Malli instrumentation

**Fix Required:** Refactor to use map-based API:

```clojure
(defn record-edit!
  [{::keys [xtdb-node file-path ns-sym]}]
  ...)

```

### 4. MAJOR: Incomplete Exception Handling in Repair

**Location:** `src/seon/dev/repair.clj` lines 52-66

**Problem:** Only catches `clojure.lang.ExceptionInfo`:

```clojure
(catch clojure.lang.ExceptionInfo ex
  (let [data (ex-data ex)]
    ...))

```

Edamame can throw standard `RuntimeException` for some parse errors (EOF, reader errors) that are not `ExceptionInfo`. These will escape and crash the hook.

**Fix Required:** Catch broader exception types:

```clojure
(catch Exception ex
  (if-let [data (ex-data ex)]
    ;; Handle ExceptionInfo with data
    ...
    ;; Handle other exceptions conservatively
    false))

```

---

## Minor Issues

### 5. MINOR: Silent nREPL Failure Masks Errors

**Location:** `bin/seon-hook` lines 77-81, 164-166

**Problem:** Connection failures return `nil`, which becomes `{:continue true}`:

```clojure
(catch java.net.ConnectException _
  ;; nREPL not running - return nil (will result in continue response)
  nil)

```

The agent receives no feedback that the hook failed.

**Impact:** Developers may not realize the hook isn't running

**Fix Suggestion:** Log a visible warning or include feedback:

```clojure
(catch java.net.ConnectException _
  {:seon.dev.hook/continue true
   :seon.dev.hook/feedback ["Warning: nREPL unavailable, hook skipped"]})

```

### 6. MINOR: Race Condition in Rate Limiting

**Location:** `src/seon/dev/context.clj` `should-review?` function

**Problem:** Multiple concurrent hook invocations (e.g., from "Save All") can all pass `should-review?` before any updates the DB, causing redundant Gemini API calls.

Timeline:
1. Hook A checks `should-review?` -> true (last review 2 min ago)
2. Hook B checks `should-review?` -> true (same stale timestamp)
3. Hook A calls Gemini...
4. Hook B calls Gemini... (redundant!)
5. Hook A records review
6. Hook B records review

**Impact:** Wasted API calls, possible duplicate review feedback

**Fix Suggestion:** Use optimistic locking or atomic "claim" on review:

```clojure
(defn claim-review-slot! [xtdb-node]
  "Atomically check and claim review slot. Returns true if claimed."
  ;; Use XTDB transaction with precondition
  ...)

```

---

## Nitpicks

### 7. NITPICK: No Path Validation in read-file-safe

**Location:** `src/seon/dev/review.clj` line 132-141

**Problem:** `read-file-safe` doesn't validate that paths are within the project.

**Mitigation:** The hook already filters for `src/seon/` files via `seon-source-file?`. This is defense-in-depth only.

---

## Gemini Review Session Log

### Round 1: Full Package Review

**Prompt:** Critical code review comparing old vs new, checking Malli compliance, edge cases, SQL usage, BB wrapper robustness.

**Response Summary:**
- Claimed argument mismatch in BB hook -> **VERIFIED FALSE** (Gemini confused design doc with actual code)
- Claimed truncated file -> **VERIFIED FALSE** (Gemini saw truncated review package)
- Claimed convention violations -> **VERIFIED TRUE** (positional args)
- Claimed missing revert -> **VERIFIED TRUE**

### Round 2: Logic Bugs Focus

**Prompt:** Review for control flow bugs, race conditions, silent failures, XTDB availability, namespace reloading.

**Response Summary:**
- Found control flow bug in `do` block -> **VERIFIED TRUE - CRITICAL**
- Found XTDB crash risk -> Valid concern
- Found edamame exception handling gap -> **VERIFIED TRUE**
- Found stale code execution risk -> Valid concern

### Round 3: Edge Cases Focus

**Prompt:** nREPL edge cases, SQL injection, path sanitization, Gemini API errors, rate limiting.

**Response Summary:**
- No SQL injection found -> Confirmed, uses parameterized queries
- Path traversal concern -> Valid but mitigated
- Silent nREPL failure -> **VERIFIED TRUE**
- Rate limiting race condition -> **VERIFIED TRUE**

---

## Recommended Fixes (Priority Order)

1. **[CRITICAL] Fix control flow bug** - Replace `do` block with conditional chaining
2. **[MAJOR] Add backup/restore** to repair stage
3. **[MAJOR] Refactor context.clj** to map-based API
4. **[MAJOR] Broaden exception handling** in repair.clj
5. **[MINOR] Add feedback on nREPL failure**
6. **[MINOR] Implement review slot claiming** to prevent race condition

---

## Verification Commands

After fixes, verify with:

```bash
# Test that blocking works
echo '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"/path/to/broken.clj"}}' | bb bin/seon-hook
# Should return: {"decision":"block","reason":"..."}

# Test syntax repair with revert
# 1. Write valid file
# 2. Write broken syntax
# 3. Verify file reverted to valid state

# Test nREPL unavailable feedback
# 1. Stop server
# 2. Run hook
# 3. Verify feedback message

```
