# PRD: Phase 8 - Convention Compliance for seon.dev

**Status:** ✅ COMPLETE
**Depends On:** Phase 7b (complete)
**Branch:** `feature/unified-dev-hook`

---

## Implementation Summary

All 6 `seon.dev.*` namespaces are now fully compliant with CONVENTIONS.md:

| Namespace | Public Fns | With Schema | With Map-In | Status |
|-----------|------------|-------------|-------------|--------|
| seon.dev.context | 13 | 13 | 13 | ✅ Compliant |
| seon.dev.verify | 5 | 5 | 5 | ✅ Compliant |
| seon.dev.codebase | 6 | 6 | 6 | ✅ Compliant |
| seon.dev.repair | 3 | 3 | 3 | ✅ Compliant |
| seon.dev.hook | 1 | 1 | 1 | ✅ Compliant |
| seon.dev.review | 4 | 4 | 4 | ✅ Compliant |
| **Total** | **32** | **32** | **32** | **100%** |

**Verification:**
- All 284 tests pass with 1362 assertions
- End-to-end hook test: processed event, ran gen tests, called Gemini, returned compliant response

### Phase 8f: Compliance Detection Tooling (COMPLETE)

Created `src/seon/dev/compliance.clj` with three main functions:

1. **`analyze-namespace`** - Analyzes a namespace for convention compliance
   - Input: `{::namespace 'seon.dev.context}`
   - Output: `{::compliant? false, ::violations [...], ::public-fns 13, ::with-schema 0, ::with-map-in 0}`
   - Uses reflection to examine all public vars

2. **`check-function`** - Checks a single function for compliance
   - Input: `{::var #'seon.dev.context/record-edit!}`
   - Output: `{::fn-name "record-edit!", ::has-schema? false, ::has-docstring? true, ::uses-map-in? false, ::violations [...]}`
   - Examines var metadata for `:malli/schema`, docstring, and arglist patterns

3. **`format-violations`** - Formats violations for display
   - Input: `{::violations [...], ::max-length 500}`
   - Output: `{::formatted "Convention violations:\n- foo: missing :malli/schema, not using map-in"}`

4. **`compliance-summary`** - Convenience function for logging
   - Input: `{::namespace 'seon.dev.context}`
   - Output: `{::summary "0/13 compliant (0 with schema, 0 with map-in)", ::compliant-count 0, ::total-count 13}`

All functions follow CONVENTIONS.md patterns (map-in/map-out, namespaced keys, :malli/schema metadata).

Tests: `test/seon/dev/compliance_test.clj` - 5 tests, 46 assertions, all passing.

---

## Context

The `seon.dev.*` namespaces implement the unified development hook system. While functionally complete, they don't follow the project's public API conventions defined in `CONVENTIONS.md`. This makes them inconsistent with the rest of the codebase and reduces the quality of training data we collect.

**Required Reading Before Implementation:**
1. `CONVENTIONS.md` - The authoritative source for all patterns
2. `docs/prds/unified-dev-hook/notes.md` - Learnings from Phase 7b
3. Any existing compliant namespace (e.g., `seon.ai.gemini`) as a reference

---

## Goals

1. **All public functions use map-in/map-out** - Single map argument, returns a map
2. **All public functions have `:malli/schema` metadata** - Contract enforcement
3. **All map keys are fully namespaced** - `::key` expands to `:seon.dev.namespace/key`
4. **Private functions remain unchanged** - Positional args are fine for internal use
5. **All tests updated** - Callers use new signatures
6. **Zero breaking changes to hook behavior** - Same functionality, better API

---

## Current State

### Functions Needing Conversion

| Namespace | Public Fns | Has Schema | Uses Map-In | Work |
|-----------|------------|------------|-------------|------|
| context.clj | 13 | 0 | 0 | Full conversion |
| verify.clj | 5 | 5 | 0 | Add map-in |
| codebase.clj | 6 | 6 | 0 | Add map-in |
| repair.clj | 3 | 3 | 0 | Add map-in |
| hook.clj | 1 | 1 | 0 | Add map-in |
| review.clj | 4 | 3 | 4 | Already done ✓ |
| **Total** | **32** | **18** | **4** | **28 to convert** |

### Example: Current vs Target

**Before (positional, non-compliant):**
```clojure
(defn record-edit!
  "Record an edit event."
  [xtdb-node file-path ns-sym opts]
  ...)

;; Usage
(record-edit! node "/path/to/file.clj" 'seon.foo {:decision :continue})
```

**After (map-in/map-out, compliant):**
```clojure
(schema/register! ::record-edit-request
  [:map
   [::xtdb-node :any]
   [::file-path ::file-path]
   [::namespace {:optional true} ::namespace]
   [::content-hash {:optional true} :string]
   [::unit-test-result {:optional true} ::test-result-summary]
   [::decision {:optional true} ::decision]])

(schema/register! ::record-edit-response
  [:map
   [::success :boolean]
   [::tx-id {:optional true} :int]])

(defn record-edit!
  "Record an edit event.

   Request keys:
     ::xtdb-node   - XTDB node
     ::file-path   - Path to edited file
     ::namespace   - Optional namespace symbol
     ...

   Response keys:
     ::success - Whether the transaction succeeded
     ::tx-id   - Transaction ID"
  {:malli/schema [:=> [:cat ::record-edit-request] ::record-edit-response]}
  [{::keys [xtdb-node file-path namespace content-hash unit-test-result decision]}]
  ...)

;; Usage
(record-edit! {::xtdb-node node
               ::file-path "/path/to/file.clj"
               ::namespace 'seon.foo
               ::decision :continue})
```

---

## Implementation Phases

### Phase 8a: context.clj (Highest Impact)

**13 functions to convert.** This namespace is called by hook.clj and has the most callers.

Functions:
- `record-edit!` - Record edit event
- `record-review!` - Record review event
- `get-last-review-time` - Query timing
- `get-last-edit-time` - Query timing
- `edits-since-last-review` - Query edits
- `edits-summary` - Aggregate edits
- `should-review?` - Rate limiting check
- `clear-all-events!` - Dev helper
- `edits-for-file` - Query helper
- `reviews-in-range` - Query helper
- `failure-rate` - Analytics
- `gemini-token-usage` - Analytics
- `recent-activity` - Analytics

**Callers to update:**
- `seon.dev.hook` - Main consumer
- `test/seon/dev/context_test.clj` - Tests
- `test/seon/dev/hook_test.clj` - Integration tests

### Phase 8b: verify.clj

**5 functions to convert.** Already has schemas, just needs map-in pattern.

Functions:
- `run-unit-tests` - Run unit tests
- `run-unit-tests-for-source` - Derive test ns and run
- `run-gen-tests` - Run generative tests
- `format-unit-result` - Format for display
- `format-gen-result` - Format for display

**Callers to update:**
- `seon.dev.hook` - Uses verify functions
- `test/seon/dev/verify_test.clj` - Tests

### Phase 8c: codebase.clj

**6 functions to convert.** File introspection utilities.

Functions:
- `clojure-file?` - Check file type
- `file->namespace` - Derive namespace from path
- `file->test-namespace` - Derive test namespace
- `read-source` - Read file safely
- `namespace->file` - Derive path from namespace
- `test-file-exists?` - Check for test file

**Callers to update:**
- `seon.dev.hook` - Uses for file handling
- `seon.dev.review` - Uses for context building
- `test/seon/dev/codebase_test.clj` - Tests

### Phase 8d: repair.clj

**3 functions to convert.** Delimiter repair utilities.

Functions:
- `delimiter-error?` - Check for syntax errors
- `repair` - Attempt to fix delimiters
- `repair-and-format` - Repair and optionally format

**Callers to update:**
- `seon.dev.hook` - Uses for repair stage
- `test/seon/dev/repair_test.clj` - Tests

### Phase 8e: hook.clj

**1 function to convert.** The main entry point.

Functions:
- `process-hook-event!` - Main orchestrator

**Note:** This function already uses namespaced keys internally. The conversion is mostly about the public signature.

**Callers to update:**
- `bin/seon-hook` - Babashka script (calls via nREPL)
- `test/seon/dev/hook_test.clj` - Tests

### Phase 8f: Compliance Detection Tooling

**New namespace: `seon.dev.compliance`**

Build tooling that the hook can use to detect convention violations in real-time. This enables the hook to warn when edited code doesn't follow conventions.

**Core Functions to Implement:**

```clojure
(ns seon.dev.compliance
  "Convention compliance checking for Clojure namespaces.

   Analyzes namespaces for:
   - Missing :malli/schema metadata on public functions
   - Positional arguments instead of map-in pattern
   - Non-namespaced keys in schemas
   - Missing docstrings")

(defn analyze-namespace
  "Analyze a namespace for convention compliance.

   Request keys:
     ::namespace - Symbol of namespace to analyze

   Response keys:
     ::compliant?     - Boolean, true if fully compliant
     ::violations     - Vector of violation maps
     ::public-fns     - Count of public functions
     ::with-schema    - Count with :malli/schema
     ::with-map-in    - Count using map-in pattern"
  [{::keys [namespace]}]
  ...)

(defn check-function
  "Check a single function for convention compliance.

   Request keys:
     ::var - The var to check

   Response keys:
     ::fn-name        - Function name
     ::has-schema?    - Has :malli/schema metadata
     ::has-docstring? - Has docstring
     ::uses-map-in?   - Uses [{::keys [...]}] pattern
     ::violations     - Vector of specific violations"
  [{::keys [var]}]
  ...)

(defn format-violations
  "Format violations for hook feedback.

   Request keys:
     ::violations - Vector of violation maps
     ::max-length - Optional max output length

   Response keys:
     ::formatted - Formatted string for display"
  [{::keys [violations max-length]}]
  ...)
```

**Implementation Approach:**

Use Clojure's reflection capabilities:
```clojure
;; Get all public vars in a namespace
(ns-publics 'seon.dev.context)
;; => {record-edit! #'seon.dev.context/record-edit! ...}

;; Get metadata from a var
(meta #'seon.dev.context/record-edit!)
;; => {:arglists ([xtdb-node file-path ns-sym] [xtdb-node file-path ns-sym opts])
;;     :doc "Record an edit event..."
;;     :malli/schema [:=> ...]}

;; Check arglist for map destructuring
(defn uses-map-in? [arglists]
  (some (fn [arglist]
          (and (= 1 (count arglist))
               (map? (first arglist))))
        arglists))
```

**Hook Integration:**

After compliance tooling is built, update `hook.clj` to optionally check edited namespaces:

```clojure
;; In stage after reload, before tests
(when (get-in config [:compliance :check-enabled])
  (let [result (compliance/analyze-namespace {::compliance/namespace ns-sym})]
    (when-not (::compliance/compliant? result)
      (swap! feedback conj
             (compliance/format-violations
               {::compliance/violations (::compliance/violations result)})))))
```

**Callers:**
- `seon.dev.hook` - Optional compliance checking stage
- REPL - Manual compliance audits

---

## Conversion Pattern

For each function, follow this pattern:

### 1. Register Input Schema
```clojure
(schema/register! ::my-function-request
  [:map
   [::required-key SomeType]
   [::optional-key {:optional true} SomeType]])
```

### 2. Register Output Schema
```clojure
(schema/register! ::my-function-response
  [:map
   [::success :boolean]
   [::result-key SomeType]])
```

### 3. Update Function Signature
```clojure
(defn my-function
  "Docstring with Request keys: and Response keys: sections."
  {:malli/schema [:=> [:cat ::my-function-request] ::my-function-response]}
  [{::keys [required-key optional-key]}]
  ;; Implementation
  {::success true
   ::result-key value})
```

### 4. Update All Callers
Find all usages and convert from positional to map style.

### 5. Update Tests
Tests should use the new map signatures.

---

## Special Considerations

### Functions That Take XTDB Node

Many functions take `xtdb-node` as first argument. Options:

**Option A: Include in map (consistent)**
```clojure
(record-edit! {::xtdb-node node ::file-path "/path"})
```

**Option B: Keep as first positional arg (ergonomic)**
```clojure
(record-edit! node {::file-path "/path"})
```

**Recommendation:** Option A for full consistency. The slight verbosity is worth the uniformity.

### Functions That Return Simple Values

Some functions return simple values (boolean, string, number). Options:

**Option A: Wrap in map (consistent)**
```clojure
(clojure-file? {::file-path "/path"})
;; => {::clojure-file true}
```

**Option B: Return simple value (pragmatic)**
```clojure
(clojure-file? {::file-path "/path"})
;; => true
```

**Recommendation:** Option B for predicate functions (`*?`). Option A for functions that could fail or return multiple values.

### Multi-Arity Functions

Some functions have multiple arities for convenience:
```clojure
(defn should-review?
  ([xtdb-node] (should-review? xtdb-node {}))
  ([xtdb-node opts] ...))
```

**Recommendation:** Convert to single-arity with optional keys:
```clojure
(defn should-review?
  [{::keys [xtdb-node interval-seconds]}]
  (let [interval (or interval-seconds 60)]
    ...))
```

---

## Success Criteria

- [x] All 32 functions converted to map-in/map-out ✅
- [x] All public functions have `:malli/schema` metadata ✅
- [x] All keys are fully namespaced (`::key`) ✅
- [x] All callers updated (hook.clj, tests, bin/seon-hook) ✅
- [x] All tests pass (284 tests, 1362 assertions) ✅
- [x] Hook still works end-to-end ✅
- [x] No regressions in functionality ✅
- [x] `seon.dev.compliance` namespace created with analyze/check functions ✅
- [ ] Hook optionally runs compliance checks on edited namespaces (future enhancement)
- [ ] Compliance violations show in hook feedback (future enhancement)

---

## Files to Change

| File | Changes |
|------|---------|
| `src/seon/dev/context.clj` | Convert 13 functions, add schemas |
| `src/seon/dev/verify.clj` | Convert 5 functions to map-in |
| `src/seon/dev/codebase.clj` | Convert 6 functions to map-in |
| `src/seon/dev/repair.clj` | Convert 3 functions to map-in |
| `src/seon/dev/hook.clj` | Convert 1 function, update internal calls, add compliance stage |
| `src/seon/dev/compliance.clj` | **NEW** - Namespace analysis and compliance checking |
| `test/seon/dev/*_test.clj` | Update all test calls |
| `test/seon/dev/compliance_test.clj` | **NEW** - Tests for compliance checking |
| `bin/seon-hook` | Update nREPL call if needed |

---

## Estimated Effort

- **Phase 8a (context.clj):** Largest - 13 functions, most callers
- **Phase 8b (verify.clj):** Medium - already has schemas
- **Phase 8c (codebase.clj):** Medium - utility functions
- **Phase 8d (repair.clj):** Small - 3 functions
- **Phase 8e (hook.clj):** Small but careful - main entry point
- **Phase 8f (compliance.clj):** Medium - new namespace, reflection-based analysis

**Total:** ~3-4 hours of focused agent work, or split across multiple sessions.

**Recommended Order:**
1. **Phase 8f first** - Build compliance tooling so we can use it to verify our own conversions
2. **Phase 8a-8e** - Convert namespaces, using compliance tooling to verify each one

---

## Reference Documents

- `CONVENTIONS.md` - **Read this first** - All patterns defined here
- `src/seon/ai/gemini.clj` - Example of compliant namespace
- `src/seon/dev/review.clj` - Already uses map-in for most functions
- `docs/prds/unified-dev-hook/notes.md` - Phase 7b learnings

---

## Agent Instructions

1. **Do one phase at a time** - Complete and test before moving on
2. **Read CONVENTIONS.md first** - Understand the patterns
3. **Look at review.clj** - It's already mostly compliant, use as reference
4. **Run tests after each function** - Catch issues early
5. **Update callers immediately** - Don't leave broken references
6. **Test hook end-to-end** - Make real edits to verify functionality
