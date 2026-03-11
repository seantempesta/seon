# Hook Output Visibility Research

**Date:** 2025-12-05
**Status:** Complete - Verified by testing

---

## The Problem

Our auto-test hook runs correctly but Claude never sees the output. Tests pass/fail silently from Claude's perspective.

**Current behavior:**
- Hook prints to stdout with exit code 0
- User sees output in verbose mode
- Claude does NOT see output

---

## Key Finding: Exit Code + Stream Determines Visibility

| Exit Code | Stream | User Sees | Claude Sees |
|-----------|--------|-----------|-------------|
| 0 | stdout | Yes (verbose) | **NO** |
| 0 | stderr | Yes (verbose) | **NO** |
| 1+ | any | Yes (error) | **NO** |
| 2 | stderr | Yes | **YES** (as `<system-reminder>`) |
| 0 + JSON `decision: "block"` | - | Yes | **YES** (sees `reason` field) |

**The counter-intuitive pattern:** To make Claude see hook output, you must either:
1. Exit code 2 + write to stderr (blocks execution)
2. JSON response with `decision: "block"` (also blocks execution)

---

## Implications for Auto-Test Hook

### Option A: Non-Blocking (Current)

- Passing tests: exit 0, stdout → User sees, Claude doesn't
- Failing tests: exit 0, stdout → User sees, Claude doesn't

**Problem:** Claude continues editing blindly when tests fail.

### Option B: Block on Failure

- Passing tests: exit 0, stdout → User sees, Claude doesn't ✓
- Failing tests: exit 2, stderr → Both see, execution blocked

**Tradeoff:** Blocks multi-file edits. If Claude is editing 5 files to fix a bug, first file's test failure stops everything.

### Option C: JSON Block on Failure (Recommended)

- Passing tests: exit 0, JSON `{"continue": true}` → User sees summary, Claude doesn't
- Failing tests: exit 0, JSON `{"decision": "block", "reason": "❌ 2 tests failed in ns"}` → Both see

**Behavior:** Claude sees failure message and can decide whether to continue or fix.

---

## Recommended Implementation

```bash
#!/usr/bin/env bb

;; ... run tests ...

(if (= status "passed")
  ;; Passing: don't bother Claude
  (do
    (println (format "✓ %d tests passed in %s (%dms)" ...))
    (System/exit 0))

  ;; Failing: make Claude see it via JSON block
  (do
    (println (json/generate-string
               {:decision "block"
                :reason (format "❌ %d tests failed in %s\n%s"
                               fail-count test-ns
                               (failure-summary))}))
    (System/exit 0)))

```

---

## Open Questions

1. **Should passing tests ever notify Claude?** Probably not - just noise. But maybe after a long streak of failures, a "finally passing" message could be useful.

2. **How verbose should failure messages be?** Just count? Or include failing test names? Or full stack traces?

3. **Should we provide a "skip tests and continue" escape hatch?** For cases where Claude knows the tests are temporarily broken.

---

## Verification Testing (2025-12-05)

Tested both `decision: "continue"` and `decision: "block"` in live Claude Code session.

### Test Setup

1. Made intentional breaking change to `date_utils.clj` (17 → 18 for hour)
2. Tests correctly failed with 2 failures
3. Tested both JSON decision values

### Results

| Decision Value | Claude Sees Output? | Behavior |
|----------------|---------------------|----------|
| `"continue"` | ❌ NO | Hook ran (verified in SQLite DB), but Claude saw nothing |
| `"block"` | ✅ YES | Claude saw `<system-reminder>` with failure message |

**Conclusion:** Only `decision: "block"` makes Claude see hook output. The `decision: "continue"` value does not work for informing without blocking - it's equivalent to silent.

### Important Bug Fixed

The hook must reload the SOURCE namespace before the test namespace. Original bug only reloaded test namespace, so tests ran against stale cached code in nREPL.

## Sources

- [Claude Code Hooks Reference](https://code.claude.com/docs/en/hooks)
- [GitHub Issue #3983](https://github.com/anthropics/claude-code/issues/3983) - PostToolUse JSON output behavior
- [GitHub Issue #11224](https://github.com/anthropics/claude-code/issues/11224) - Hook output visibility depends on exit code and stream
- Initial research in `initial-findings.md` (line 47-48: "Exit code 2 = blocking error, stderr shown to Claude")
