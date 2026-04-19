---
type: prd
status: completed
tags: [prd, archive]
---

# Implementation Notes: Auto-Test Hook

**Last Updated:** 2025-12-05

---

## Overview

*To be filled in during/after implementation.*

This feature adds automatic test running when Claude Code edits Clojure files, providing fast feedback without interrupting workflow.

---

## Key Discoveries During Research

### Discovery 1: Hook Architecture

**What we found:**
- Claude hooks receive JSON via stdin with `session_id`, `cwd`, `hook_event_name`, `tool_name`, `tool_input`
- Hooks can return exit code 0 (success), 2 (blocking error), or JSON responses
- Global and project hooks run **in parallel**, not sequentially

**File:** `~/.gitlibs/libs/.../clojure_mcp_light/hook.clj` (450 lines, good reference)

### Discovery 2: nREPL-Based Testing is Fast

**What we found:**

```bash
# Test via nREPL (instant, no JVM startup):
clj-nrepl-eval -p 7888 "(require 'ml-options.log-parsing-test) (clojure.test/run-tests 'ml-options.log-parsing-test)"
# Result: 3 tests, 26 assertions in <1 second

```

**Caveat:** Need to `require` the namespace first, and need to handle namespace reloading for changed code.

### Discovery 3: Test Mapping Convention

**What we found:**
The codebase follows a consistent pattern:

```
src/ml_options/foo.clj       → test/ml_options/foo_test.clj
src/ml_options/web/bar.clj   → test/ml_options/web/bar_test.clj

```

Simple path transformation should work for direct mapping.

---

## Gotchas

*To be filled in during implementation.*

### Gotcha 1: Namespace Reloading

**The problem:**
nREPL-based testing requires reloading the changed namespace before running tests, otherwise you test stale code.

**How to avoid:**
Use `clojure.tools.namespace.repl/refresh` or explicit `(require 'ns :reload)` before running tests.

**Why this happens:**
JVM keeps old bytecode loaded until explicitly reloaded.

---

## REPL Commands for Testing

```clojure
;; Quick test a specific namespace via nREPL
(require 'ml-options.log-parsing-test :reload)
(clojure.test/run-tests 'ml-options.log-parsing-test)

;; Run all tests
(require '[kaocha.runner :as k])
(k/run :unit)

;; Check what would run for a file
(-> "src/ml_options/web/handlers.clj"
    (clojure.string/replace #"^src/" "test/")
    (clojure.string/replace #"\.clj$" "_test.clj"))
;; => "test/ml_options/web/handlers_test.clj"

```

---

## Performance Characteristics

*To be measured during prototyping.*

| Approach | Startup | Test Time | Total |
|----------|---------|-----------|-------|
| Fresh JVM + Kaocha | ~5s | ~1s | ~6s |
| nREPL (cold) | 0s | ~2s (first require) | ~2s |
| nREPL (warm) | 0s | <1s | <1s |

---

## Future Improvements

1. **Transitive dependency testing** - Track which tests depend on changed code using tools.namespace
2. **Test coverage feedback** - Show which tests cover the changed code
3. **Parallel test execution** - Run independent tests in parallel for large suites
