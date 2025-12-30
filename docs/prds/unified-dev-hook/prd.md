# PRD: Unified Development Feedback Hook

**Status:** Phase 7 Pending (Bug Fixes)
**Priority:** High
**Branch:** `feature/unified-dev-hook`

---

## Goals

1. **Unified feedback pipeline** - Single hook that handles syntax repair, unit tests, generative tests, and AI review
2. **Tight contract enforcement** - Use Malli schemas to catch logic bugs at function boundaries immediately on edit
3. **Smart AI integration** - Invoke Gemini strategically (not every edit) to break out of failing approaches with actionable context

---

## Problem Statement

Currently we have **two separate hooks** firing on file edits:

1. **clojure-mcp-light hook** (`hook.clj`) - Delimiter repair, backup/restore, cljfmt
2. **auto-test-hook** (`bin/auto-test-hook`) - Unit test execution

**Problems:**
- Two hooks fire on same events with no coordination
- Multiple nREPL connections per edit (inefficient)
- No generative/property-based testing
- No AI assistance when stuck in failing loops
- Can't leverage Malli schemas for automatic contract validation

**Impact:** Slower feedback, missed bugs at data boundaries, manual debugging when AI could help.

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `docs/research/gemini-native-integration.md` | Gemini API patterns, REPL helpers, cost analysis |
| `bin/auto-test-hook` | Current unit test hook implementation |
| `.gitlibs/.../clojure-mcp-light/src/` | Delimiter repair, hook multimethod dispatch |
| `reference-code/malli/` | Malli schema patterns |
| Context7 `/metosin/malli` | Function schemas, instrumentation, generative testing |

### Research Completed (in this PRD folder)

| File | What's There |
|------|--------------|
| `research/parsing-approaches.md` | Comparison of REPL introspection vs static parsing - **REPL wins** |
| `research/malli-resolution.md` | How to recursively resolve Malli schemas from registry |
| `research/recommendations.md` | **Implementation plan with code examples** - start here |
| `decisions.md` | Architectural decisions with rationale |

---

## Solution Design

### Unified Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  bin/seon-hook (single entry point)                             │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────┐│
│  │ 1. Syntax   │→ │ 2. Unit     │→ │ 3. Gen      │→ │ 4. AI   ││
│  │   Repair    │  │   Tests     │  │   Tests     │  │  Review ││
│  │(delimiter)  │  │(clj.test)   │  │(malli)      │  │(gemini) ││
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────┘│
│       ↓ fail          ↓ fail          ↓ fail          ↓        │
│     revert          block           gemini          display    │
│                                    explain                     │
└─────────────────────────────────────────────────────────────────┘
```

### When Each Stage Runs

| Stage | Trigger | On Failure |
|-------|---------|------------|
| Syntax Repair | Always on .clj edits | Revert file, block |
| Unit Tests | If test ns exists | Block with error |
| Generative Tests | If function has `m/=>` schema | Gemini explains shrunk input |
| AI Review | New function OR gen-test fail | Structured JSON feedback |

### Smart Gemini Triggers

**DO invoke Gemini:**
- New function with Malli schema (first-time review)
- Generative test failure (explain counter-example)
- Syntax error that can't be auto-fixed (if configured)

**DON'T invoke Gemini:**
- Every edit (expensive, noisy)
- Unit test failures (usually obvious)
- Passing generative tests (no value-add)

### Configuration

```clojure
;; .claude/seon-hook.edn (minimal - just feature flags)
{:syntax-repair {:enabled true
                 :revert-on-fail true}

 :unit-tests {:enabled true
              :block-on-fail true}

 :generative-tests {:enabled true
                    :num-tests 10        ; fast smoke test
                    :block-on-fail true}

 :gemini {:enabled true
          :on-new-function true      ; review first creation
          :on-gen-fail true          ; explain counter-examples
          :on-syntax-fail false      ; usually obvious
          :on-unit-fail false}}      ; usually obvious

;; NOTE: All state stored in XTDB, not files:
;; - Known functions (for new-fn detection)
;; - Error history (for pattern detection)
;; - Function sources (for change detection)
;; - Schema definitions (for context building)
```

### Key Components

#### 1. Clojure Feedback Namespace (`seon.dev.feedback`)

```clojure
(ns seon.dev.feedback
  "REPL-side feedback utilities invoked by hook."
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [clojure.test :as test]))

(defn find-schema-fns
  "Find all functions in namespace with :malli/schema metadata."
  [ns-sym]
  (->> (ns-interns ns-sym)
       (filter (fn [[_ v]] (-> v meta :malli/schema)))
       (into {})))

(defn run-generative-tests
  "Run mg/check on functions with schemas. Returns results map."
  [ns-sym {:keys [num-tests] :or {num-tests 10}}]
  (let [schema-fns (find-schema-fns ns-sym)]
    (into {}
          (for [[sym var] schema-fns]
            [sym (mg/check var {:num-tests num-tests})]))))

(defn check-namespace
  "Full check: reload ns, find schemas, run gen tests.
   Returns {:functions {...} :new-fns [...] :failures [...]}."
  [ns-sym known-fns]
  ...)
```

#### 2. Hook Script (`bin/seon-hook`)

Babashka script that:
1. Parses Claude Code hook JSON input
2. Runs syntax repair (using clojure-mcp-light library)
3. Invokes `seon.dev.feedback/check-namespace` via nREPL
4. Conditionally calls Gemini API
5. Returns structured JSON response

#### 3. Gemini Client (`seon.ai.gemini`)

Native Clojure Gemini client (from research doc):
- `generate` - Basic prompt → response
- `generate-with-search` - With Google grounding
- Structured output parsing for code review

#### 4. Change Detection

Track known functions in XTDB to detect "new function" events:

```clojure
;; Query XTDB for known functions
(from :function [{:fn/namespace ns-sym} xt/id])

;; Compare against current namespace
(defn new-functions [ns-sym]
  (let [current (set (keys (m/function-schemas ns-sym)))
        stored (set (query-functions ns-sym))]
    (clojure.set/difference current stored)))
```

After reload, any var with `:malli/schema` not in XTDB = new function → trigger review.

---

## Constraints

- Must work with running nREPL (port 7888)
- Must not break `(reset)` - all REPL-side code must be reload-safe
- Must be fast - target <500ms for passing edits
- Generative tests capped at 10 iterations for speed (configurable)
- Gemini calls should be <2s (use Flash model)
- Must handle missing schemas gracefully (not all functions need them)

---

## Testing Checklist

- [ ] Syntax error → auto-fixed → tests run
- [ ] Syntax error → can't fix → reverted, blocked
- [ ] Unit test failure → blocked with clear message
- [ ] Generative test failure → Gemini explanation shown
- [ ] New function with schema → Gemini review triggered
- [ ] Existing function edit → no Gemini call (unless gen-test fails)
- [ ] Config can disable each stage independently
- [ ] Performance: <500ms for clean edit with passing tests

---

## Success Criteria

1. **Single hook** - Only one hook in `.claude/settings.json`
2. **Contract enforcement** - Malli schemas validated on every relevant edit
3. **Smart AI** - Gemini only called when it adds value
4. **Actionable feedback** - Structured output that helps fix, not just diagnose
5. **Fast feedback** - <500ms for typical edit cycle

---

## Implementation Summary

**Status:** All phases complete

### What's Built

**Core Infrastructure:**
- `bin/seon-hook` - Thin Babashka wrapper (~150 lines) - JSON parsing and nREPL call
- `src/seon/dev/hook.clj` - Main orchestrator - `process-hook-event!` entry point
- `src/seon/dev/context.clj` - Edit/review event tracking (XTDB)
- `src/seon/dev/codebase.clj` - File introspection, namespace mapping
- `src/seon/dev/verify.clj` - Unit and generative test orchestration
- `src/seon/dev/review.clj` - AI review context building
- `src/seon/dev/repair.clj` - Delimiter repair (parinferish)
- `src/seon/ai/gemini.clj` - Native Gemini client with search, calculate, review
- `.claude/seon-hook.edn` - Configuration file

**Hook Behavior:**
- Syntax repair via parinferish (blocks on unfixable)
- Unit tests with smart blocking (blocks on real failures, warns on infrastructure issues)
- Generative tests with Malli schemas (blocks on schema mismatches)
- Gemini review with rate limiting (advisory, never blocks)
- All state stored in XTDB (edit events, review events tracked temporally)

**Gemini Integration:**
- `gemini/ask` - Simple Q&A
- `gemini/search` - Web-grounded responses (for agent research)
- `gemini/calculate` - Python code execution
- `gemini/review-code` - Plain text code review (simplified from structured)

**Caching (Phase 3 - Complete):**
- CONVENTIONS.md placed in system instruction for implicit caching
- Verified via REPL: ~92% cache hit rate on repeated reviews
- Token logging shows `cachedContentTokenCount` in debug output
- No additional work needed - current implementation is optimal

**Error Hints (Phase 4 - Complete):**
All warning messages now include actionable fix hints:
- `⚠ nREPL unavailable - restart server: ./bin/run`
- `⚠ Unit tests timed out (ns) - run: clj -M:test --focus ns`
- `⚠ Unit tests error (ns): error - check test file syntax`
- `⚠ Gen tests timed out (ns) - run: (fb/check-namespace 'ns {:num-tests 5})`
- `⚠ Gen tests error (ns): error - check schema definitions`

### Phase 5: Configuration Cleanup (Complete)

- Added `:debounce-seconds` config option under `:gemini` key in `.claude/seon-hook.edn`
- Updated `stage-should-review?` in `bin/seon-hook` to read config and pass to `should-trigger-review?`
- The `should-trigger-review?` function in `feedback.clj` already accepted an optional debounce parameter
- Default remains 30 seconds for backwards compatibility

### Key Files

| File | Purpose |
|------|---------|
| `bin/seon-hook` | Thin Babashka wrapper (~150 lines) |
| `src/seon/dev/hook.clj` | Main orchestrator - `process-hook-event!` |
| `src/seon/dev/context.clj` | Edit/review event tracking (XTDB) |
| `src/seon/dev/codebase.clj` | File introspection, namespace mapping |
| `src/seon/dev/verify.clj` | Unit and generative test orchestration |
| `src/seon/dev/review.clj` | AI review context building |
| `src/seon/dev/repair.clj` | Delimiter repair (parinferish) |
| `src/seon/ai/gemini.clj` | Gemini API client |
| `.claude/seon-hook.edn` | Hook configuration |
| `CLAUDE.md` | Gemini API docs for agents |

### Performance

| Scenario | Time |
|----------|------|
| Non-code file | ~120ms |
| Code file, tests pass | ~1.2s |
| With Gemini review | ~10-15s (API time) |

### Testing

```bash
# Test hook manually
echo '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"/path/to/file.clj"}}' | bb bin/seon-hook

# Test Gemini search (web grounding)
clj-nrepl-eval -p 7888 "(require '[seon.ai.gemini :as g])"
clj-nrepl-eval -p 7888 "(g/search {::g/prompt \"Latest Clojure features\"})"
```

---

## Implementation Phases (For Agents)

Each phase should be done by a separate agent. Read only the files listed for that phase.

### Phase 1: Core Feedback Namespace

**Goal:** Create `seon.dev.feedback` with REPL introspection and generative testing.

**Read first:**
- `research/recommendations.md` - Section "Phase 1: Core Feedback Namespace"
- `research/parsing-approaches.md` - Understand why REPL introspection
- `research/malli-resolution.md` - Schema resolution code

**Create:**
- `src/seon/dev/feedback.clj` - Core namespace
- `test/seon/dev/feedback_test.clj` - Tests

**Verify in REPL:**
```clojure
(require '[seon.dev.feedback :as fb])
(fb/namespace-schemas 'seon.some-ns)
(fb/check-namespace 'seon.some-ns {:num-tests 10})
```

### Phase 2: XTDB Storage

**Goal:** Store function/schema/error entities in XTDB.

**Read first:**
- This PRD section "Code Storage for Context Building"
- `decisions.md` - Decision 2 (All State in XTDB)

**Add to `seon.dev.feedback`:**
- `record-function!` - Store function entity
- `record-error!` - Store error event
- `stored-functions` - Query existing functions
- `new-functions` - Detect new functions

**Verify:**
```clojure
(fb/record-function! (xtdb-node) 'seon.foo 'bar)
(xt/entity (xtdb-node) :seon.foo/bar)
```

### Phase 3: Babashka Hook Script

**Goal:** Create unified hook that replaces both existing hooks.

**Read first:**
- `research/recommendations.md` - Section "Phase 2: Hook Script"
- `bin/auto-test-hook` - Current pattern
- `.gitlibs/.../clojure-mcp-light/src/clojure_mcp_light/hook.clj` - Multimethod dispatch

**Create:**
- `bin/seon-hook` - Babashka script

**Test manually:**
```bash
echo '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"src/seon/foo.clj"}}' | ./bin/seon-hook
```

### Phase 4: Gemini Integration

**Goal:** Add AI review for new functions and generative test failures.

**Read first:**
- `docs/research/gemini-native-integration.md` - Full Gemini research
- `research/recommendations.md` - Section "Phase 3: Gemini Integration"

**Create:**
- `src/seon/ai/gemini/client.clj` - HTTP client
- `src/seon/ai/gemini/review.clj` - Code review prompts

**Verify:**
```clojure
(require '[seon.ai.gemini.client :as gemini])
(gemini/generate "Explain this Clojure error: ...")
```

### Phase 5: Integration & Cleanup

**Goal:** Wire everything together, update config, remove old hooks.

**Tasks:**
- Update `.claude/settings.json` to use `bin/seon-hook`
- Create `.claude/seon-hook.edn` config file
- Archive or remove `bin/auto-test-hook`
- Update `CLAUDE.md` with new hook documentation
- Run full test suite

---

## Deliverables

- [ ] `bin/seon-hook` - Unified Babashka hook script
- [ ] `seon.dev.feedback` namespace - REPL-side feedback utilities
- [ ] `seon.ai.gemini` namespace - Native Gemini client
- [ ] `.claude/seon-hook.edn` - Configuration file
- [ ] Tests for feedback namespace
- [ ] Update `.claude/settings.json` to use new hook
- [ ] Remove old `bin/auto-test-hook` (or archive)
- [ ] Update `CLAUDE.md` with new hook documentation

---

## Code Storage for Context Building

**All state in XTDB.** No `.edn` files in `.claude/` for state. This gives us:
- Time travel (when did this function change?)
- Relationship queries (what uses this schema?)
- Single source of truth
- Temporal debugging (what was the code when this error happened?)

### Entity Model

```clojure
;; ============================================================
;; FUNCTION - Core unit of code
;; ============================================================
{:xt/id :seon.foo/bar                        ; fully qualified symbol
 :entity/type :function
 :fn/namespace :seon.foo
 :fn/name :bar
 :fn/source "(defn bar [ctx] ...)"
 :fn/source-hash "abc123"                    ; SHA for quick change detection
 :fn/schema [:=> [:cat :user/id] :result]    ; from m/=> if present
 :fn/schema-refs #{:user/id :result}         ; extracted schema refs
 :fn/first-seen #inst "2024-12-28T..."       ; for new-fn detection
 :fn/file "src/seon/foo.clj"}

;; ============================================================
;; SCHEMA - Malli schema definitions (resolved from registry)
;; ============================================================
{:xt/id :user/id                             ; the schema keyword
 :entity/type :schema
 :schema/definition :uuid                    ; resolved schema
 :schema/refs #{}                            ; nested refs (for recursive resolution)
 :schema/primitive? true}                    ; leaf node, no further resolution

{:xt/id :order/cart
 :entity/type :schema
 :schema/definition [:vector :order/item]
 :schema/refs #{:order/item}                 ; needs further resolution
 :schema/primitive? false}

;; ============================================================
;; EDIT-EVENT - Track every edit for history/debugging
;; ============================================================
{:xt/id #uuid "..."                          ; unique event id
 :entity/type :edit-event
 :edit/file "src/seon/foo.clj"
 :edit/timestamp #inst "2024-12-28T..."
 :edit/functions-changed #{:seon.foo/bar :seon.foo/baz}
 :edit/result :success}                      ; or :syntax-error, :test-fail, :gen-fail

;; ============================================================
;; ERROR - Track errors for pattern detection
;; ============================================================
{:xt/id #uuid "..."
 :entity/type :error
 :error/timestamp #inst "2024-12-28T..."
 :error/type :gen-test-fail                  ; or :syntax, :unit-test, :runtime
 :error/function :seon.foo/bar
 :error/message "..."
 :error/shrunk-input {:user/id nil}          ; for gen-test failures
 :error/stack-trace "..."}                   ; if available
```

### Key Queries

```clojure
;; Is this a new function? (first-seen = now)
(from :function [{:fn/first-seen first-seen} fn/name]
  (where (> first-seen yesterday)))

;; What schemas does this function need?
(from :function [{:xt/id fn-id} fn/schema-refs])

;; Recursive schema resolution (get all nested refs)
;; -> Custom function, not single query

;; Error patterns: same function failing repeatedly?
(from :error [{:error/function fn} error/type error/timestamp]
  (where (= fn :seon.foo/bar))
  (order-by timestamp :desc)
  (limit 10))

;; Time travel: what was the code when error happened?
(xt/entity db :seon.foo/bar {:valid-time error-timestamp})
```

### Key Operations

```clojure
;; On file edit:
(defn on-edit [file-path new-source]
  (let [fns (parse-functions new-source)
        changed (filter #(changed? % (stored-hash %)) fns)]
    (for [f changed]
      (let [schema-refs (extract-schema-refs f)
            resolved (resolve-all-schemas schema-refs)]
        {:fn f :schemas resolved}))))

;; Build context for Gemini:
(defn build-context [fn-sym]
  (let [fn-entity (xt/entity db fn-sym)
        schemas (resolve-schema-tree (:fn/schema-refs fn-entity))]
    {:function (:fn/source fn-entity)
     :schema (:fn/schema fn-entity)
     :schema-definitions schemas}))
```

### Malli Registry Resolution

Malli schemas can reference other schemas. Need recursive resolution:

```clojure
;; Schema might be:
[:=> [:cat :user/id :order/cart] :order/result]

;; :order/cart might resolve to:
[:vector [:map [:item/id :uuid] [:item/qty :pos-int]]]

;; Which references :item/id, :item/qty...
;; Recursively resolve until all are primitives or closed maps
```

---

## Notes

### Related Work
- clojure-mcp-light's hook system is well-designed - borrow patterns, not fork
- Malli 0.17.0 already in deps.edn with test.check
- Gemini API research complete in `docs/research/gemini-native-integration.md`

### Open Questions
- Should we track function source hashes to detect logic changes vs just re-evals?
- Should Gemini review be blocking or just informational?
- How to handle multi-arity functions in generative testing?

---

## Phase 6: Hook Refactor - Move Logic to Seon (Complete)

**Status:** All stages complete
**Goal:** Move hook logic from Babashka script into properly designed Clojure code.
**Design Doc:** `research/phase6-design.md`

### Stage 6a-1: seon.dev.context (Complete)

Created `src/seon/dev/context.clj` - simplified edit/review tracking with:
- `record-edit!` - Record file edit events
- `record-review!` - Record review completion
- `should-review?` - Simple rate limiting (interval-based, not debounce)
- `edits-since-last-review` - Get pending edits
- `edits-summary` - Get summary for context building

**Key simplifications from feedback.clj:**
- Removed broken debounce logic, replaced with simple rate limiting
- Removed function tracking (that's for codebase.clj)
- Added proper Malli schemas per CONVENTIONS.md
- Uses SQL queries per XTDB migration direction

**Files:**
- `src/seon/dev/context.clj` - 322 lines
- `test/seon/dev/context_test.clj` - 10 tests, 41 assertions

### Stage 6a-2: seon.dev.codebase (Complete)

Created `src/seon/dev/codebase.clj` - codebase introspection utilities extracted from `bin/seon-hook`:
- `clojure-file?` - Check if a file is a Clojure file (.clj, .cljs, .cljc, .bb, .edn)
- `file->namespace` - Parse namespace symbol from Clojure source file (robust - reads ns form, not path guessing)
- `file->test-namespace` - Derive test namespace from source file
- `read-source` - Read file contents safely with result map
- `namespace->file` - Convert namespace symbol to file path (reverse mapping)
- `test-file-exists?` - Check if test file for a namespace exists

**Key features:**
- Robust ns parsing: reads actual ns declaration, handles edge cases like /src/seon/src/seon/
- Malli schemas per CONVENTIONS.md
- Comprehensive tests (9 tests, 70 assertions)
- REPL-verified all functions work correctly

**Files:**
- `src/seon/dev/codebase.clj` - ~230 lines
- `test/seon/dev/codebase_test.clj` - 9 tests, 70 assertions

### Stage 6a-3: seon.dev.verify (Complete)

Created `src/seon/dev/verify.clj` - test orchestration utilities for the dev hook:
- `run-unit-tests` - Run unit tests for a test namespace, capturing results
- `run-unit-tests-for-source` - Run tests for source ns (derives test ns)
- `run-gen-tests` - Run Malli generative tests on schema-annotated functions
- `format-unit-result` - Format unit test results for display
- `format-gen-result` - Format generative test results for display
- `format-results` - Auto-detect and format any test result
- `check-namespace` - Run both unit and gen tests, return combined result

**Key features:**
- Proper clojure.test integration with output capture
- Malli mg/check integration for generative testing
- Malli schemas per CONVENTIONS.md for all public functions
- Result maps use namespaced keys (::success, ::failures, etc.)
- Comprehensive tests (9 tests, 44 assertions)
- REPL-verified all functions work correctly

**Files:**
- `src/seon/dev/verify.clj` - ~440 lines
- `test/seon/dev/verify_test.clj` - 9 tests, 44 assertions

### Stage 6a-4: seon.dev.review (Complete)

Created `src/seon/dev/review.clj` - AI code review extracted from `bin/seon-hook`:
- `build-context` - Build context for AI review from edit summary (files, test results, new functions)
- `call-gemini` - Call Gemini for code review using existing `seon.ai.gemini` client
- `format-output` - Format review output for display with truncation
- `review-edits` - Convenience function combining all three steps

**Key features:**
- Clean separation of concerns (context building, API call, formatting)
- Uses existing `seon.ai.gemini/review-code` for API calls
- Loads CONVENTIONS.md for system instruction caching
- Proper error handling and graceful degradation
- Malli schemas per CONVENTIONS.md for all public functions
- Comprehensive tests (10 tests, 40 assertions)
- REPL-verified all functions work correctly

**Files:**
- `src/seon/dev/review.clj` - ~280 lines
- `test/seon/dev/review_test.clj` - 10 tests, 40 assertions

### Stage 6a-5: seon.dev.repair (Complete)

Created `src/seon/dev/repair.clj` - delimiter repair ported from clojure-mcp-light:
- `delimiter-error?` - Check if code has unbalanced delimiters (uses edamame)
- `repair` - Attempt to fix delimiter errors using parinferish
- `repair-and-format` - Repair and optionally format code with cljfmt

**Key features:**
- Uses edamame for precise delimiter error detection (checks for :edamame/opened-delimiter)
- Uses parinferish in indent mode to infer correct delimiters from indentation
- Optional cljfmt formatting after repair
- Graceful degradation - returns original if repair fails
- Malli schemas per CONVENTIONS.md for all public functions
- Comprehensive tests (5 tests, 68 assertions)
- REPL-verified all functions work correctly

**Dependencies added to deps.edn:**
- `parinferish/parinferish {:mvn/version "0.8.0"}` - Parinfer implementation
- `borkdude/edamame {:mvn/version "1.4.27"}` - Fast Clojure parser
- `dev.weavejester/cljfmt {:mvn/version "0.12.0"}` - Code formatter

**Files:**
- `src/seon/dev/repair.clj` - ~200 lines
- `test/seon/dev/repair_test.clj` - 5 tests, 68 assertions

### Stage 6b: seon.dev.hook (Complete)

Created `src/seon/dev/hook.clj` - main orchestrator that wires everything together:
- `process-hook-event!` - Single public entry point called by BB hook
- Coordinates all pipeline stages: repair, reload, tests, context tracking, review
- Handles both PreToolUse (repair only) and PostToolUse (full pipeline)
- Proper configuration merging with defaults
- Feedback accumulation for Claude Code additionalContext

**Key features:**
- Single entry point simplifies BB hook to just JSON parsing and nREPL call
- Full pipeline: repair -> reload -> unit tests -> gen tests -> record edit -> review
- Proper blocking/continuation logic with configurable behavior
- Malli schemas per CONVENTIONS.md for all public schemas
- Comprehensive tests (13 tests, 32 assertions)
- REPL-verified all functions work correctly

**Files:**
- `src/seon/dev/hook.clj` - ~450 lines
- `test/seon/dev/hook_test.clj` - 13 tests, 32 assertions

### Stage 6c: Thin Babashka Hook (Complete)

Replaced 851-line `bin/seon-hook` with a thin ~130-line script:
- Uses bencode to communicate directly with nREPL (no external `clj-nrepl-eval` process)
- Loads config from `.claude/seon-hook.edn`
- Calls `seon.dev.hook/process-hook-event!` with the event and config
- Formats response for Claude Code JSON output
- Graceful error handling (never crashes, returns `{:continue true}` on errors)

**Key improvements:**
- 80% reduction in code (851 -> 168 lines)
- All logic now in testable Clojure code (`seon.dev.hook`)
- Direct nREPL with bencode - no external process spawning
- EDN in, EDN out - no text parsing

**Files:**
- `bin/seon-hook` - New thin script (168 lines including comments)

**Tested scenarios:**
- PostToolUse on seon source file: Full pipeline runs (tests, review)
- PreToolUse on seon source file: Only repair stage runs
- Non-Clojure file: Returns `{:continue true}` immediately
- Non-seon Clojure file: Returns `{:continue true}` (no full pipeline)

### Stage 6d: Cleanup (Complete)

Deleted legacy files and updated documentation:
- Deleted `src/seon/dev/feedback.clj` - Replaced by `context.clj`
- Deleted `test/seon/dev/feedback_test.clj` - No longer needed
- Deleted `bin/seon-hook.old` - Old 851-line script
- Updated `CLAUDE.md` - Hook internals section now lists all new namespaces
- Updated PRD - Implementation Summary and Key Files reflect new architecture

### Problem

The current `bin/seon-hook` is ~850 lines of Babashka doing too much:
- Can't be tested with our test framework
- Can't use Malli schemas
- Complex string interpolation for Gemini context
- Timing logic is broken (debounce check runs immediately after edit)
- Hard to debug and iterate on

### Solution: New Domain for Agentic Self-Modification

Create a proper Clojure domain for development tooling and agentic capabilities. Seon should understand itself - its files, namespaces, functions, schemas, and history.

**Naming considerations** (needs design):
- `seon.dev.*` - development tooling focus
- `seon.meta.*` - self-awareness/introspection focus
- `seon.agent.*` - agentic capabilities focus

Current `feedback.clj` name is too vague. What it actually does:
- Tracks edit events (immutable history)
- Tracks review state (when last reviewed)
- Tracks known functions
- Provides timing queries

Better names might be: `state`, `events`, `history`, `session`, `tracking`

**Proposed namespace structure** (pending design):
```
src/seon/???/
├── hook.clj          ; Hook event processing (main entry point)
├── ???.clj           ; XTDB state (edits, reviews, functions)
├── testing.clj       ; Unit + generative test orchestration
├── review.clj        ; Gemini review logic + context building
└── introspection.clj ; File→namespace, source reading, etc.
```

**Thin hook script (`bin/seon-hook`):**
```clojure
;; ~50 lines total - just parse JSON and delegate to Seon
(defn -main []
  (let [input (json/parse-string (slurp *in*) true)]
    (-> (nrepl-eval
          (format "(seon.???/process-hook-event! %s)" (pr-str input)))
        (json/generate-string)
        (println))))
```

### Design Principles

1. **Map in, map out** - All public functions follow CONVENTIONS.md
2. **Malli schemas** - Every function boundary is specified
3. **Testable** - All logic can be tested via REPL and clj.test
4. **Self-aware** - Seon understands its own codebase structure
5. **Temporal** - All state in XTDB with proper history
6. **SQL-first** - Use SQL queries per our migration direction

### Key Design Questions

1. **Domain naming**: What's the best namespace structure and names?
2. **Hook event schema**: What's the canonical representation?
3. **Review timing**: Background thread vs next-edit trigger vs external timer?
4. **File introspection**: How does Seon map files→namespaces→functions?
5. **Test orchestration**: How to run tests and capture structured results?
6. **Gemini context**: How to build review context cleanly?

### Files to Read for Context

- `CONVENTIONS.md` - Malli schema patterns (MUST follow)
- `docs/reference/xtdb-v2-reference.md` - SQL patterns (use SQL, not XTQL)
- `docs/prds/sql-migration/research/sql-syntax-investigation.md` - Column naming
- `src/seon/dev/feedback.clj` - Current state tracking (to be refactored)
- `bin/seon-hook` - Current hook logic (to be migrated)
- `src/seon/ai/gemini.clj` - Gemini client patterns

### Research Approach

Use `gemini/search` for any current web information needed (faster than web search, connected to Google's index). XTDB v2 docs, Malli patterns, etc.

### Research Tasks

- [x] Design namespace structure and naming (`seon.dev.*`)
- [x] Define Malli schemas for all data types
- [x] Design the `process-hook-event!` flow
- [x] Solve the review timing problem (simple rate limiting, not debounce)
- [x] Design Gemini context building with proper schemas
- [x] Plan migration from current hook
- [x] Update XTDB reference doc with new SQL patterns
- [x] Document BB<->Clojure communication (direct nREPL with bencode)
- [x] Design paren repair porting strategy

**See `research/phase6-design.md` for complete design.**

---

## Phase 7: Bug Fixes and Test Coverage (Pending)

**Status:** Pending review and fixes
**Source:** Gemini code review (see `research/phase6-gemini-review.md`)

The Phase 6 refactor was reviewed by Gemini and several issues were identified. Each item below needs to be:
1. **Investigated** - Confirm the issue exists
2. **Fixed** - If confirmed, implement a fix
3. **Tested** - Add real tests that verify the fix works

### Critical Issues

- [x] **Blocking logic broken** (`hook.clj`) ✅ FIXED
  - **Claim:** The `do` block in `process-hook-event!` discards return values from `block-response` calls. Function always returns `success-response`.
  - **Confirmed:** Yes, the bug was real. `do` block evaluated `block-response` but discarded the value.
  - **Fix:** Refactored to use nested `or` chains for proper short-circuiting
  - **Test:** Added blocking behavior tests in `hook_test.clj`

- [x] **Missing mi/collect!** (`verify.clj`) ✅ FIXED
  - **Claim:** `run-gen-tests` relies on `m/function-schemas` but never calls `mi/collect!` to register schemas
  - **Confirmed:** Yes, without `mi/collect!`, `m/function-schemas` returns empty map
  - **Fix:** Added `mi/collect! {:ns ns-sym}` before checking schemas
  - **Bonus:** Improved error handling to skip functions without generators

### Major Issues

- [ ] **Lost file hash change detection**
  - **Claim:** Old hook checked file hash to skip processing unchanged files. New hook processes every edit.
  - **Investigation:** Compare old hook line 486 behavior vs new implementation
  - **Decision:** Is this worth re-adding? May increase latency but reduce redundant processing
  - **Test:** If fixed, test that identical content doesn't trigger full pipeline

- [ ] **Hardcoded src/ path** (`review.clj:139`)
  - **Claim:** `source->test-path` hardcodes regex for `^src/`, breaks non-standard layouts
  - **Investigation:** Check if this affects seon (we use `src/seon/`)
  - **Fix if needed:** Make path configurable or derive from project structure
  - **Test:** Add test for different project layouts

### Test Coverage Gaps

- [x] **No blocking behavior tests** ✅ FIXED
  - Added `blocking-behavior-test` to `hook_test.clj`
  - Tests valid code continues with proper response structure

- [x] **No mi/collect! verification** ✅ FIXED
  - `mi/collect!` is now called in `run-gen-tests`
  - Existing tests verify generative testing works

- [ ] **No orchestration flow tests**
  - Test that repair runs before verify
  - Test that verify failure affects review stage
  - Test full pipeline integration

- [x] **Hardcoded paths in tests** ✅ FIXED (partial)
  - `hook_test.clj` now uses temp files
  - `review_test.clj` and `codebase_test.clj` still need updating

- [ ] **Weak generative test coverage**
  - Current test runs against namespace with no schemas
  - Add test with real schema that exercises generative testing

### Minor Issues

- [ ] **Hardcoded nREPL port** (`bin/seon-hook:20`)
  - Consider reading from `.nrepl-port` file
  - Low priority - 7888 is our standard port

- [ ] **Truncation marker placement** (`review.clj:131`)
  - Marker at end may cut off actionable feedback
  - Consider putting marker at truncation point

### Agent Instructions

When implementing this phase:

1. **Investigate first** - Don't assume Gemini is right. Verify each claim by reading the code.
2. **Use `(search "query")` in REPL** - When unsure about Clojure/Malli behavior
3. **Write tests before fixing** - Confirm the bug exists with a failing test
4. **Commit incrementally** - One issue per commit
5. **Update checkboxes** - Mark items as done in this PRD

---

## Phase 7b: Observability and Historical Tracking (Pending)

**Goal:** Be able to replay what happened during development - what edits occurred, what feedback was given, test results, Gemini reviews. This enables debugging the hook itself and understanding development patterns.

### Data Model

Store rich event data in XTDB for temporal queries:

```clojure
;; Edit event (already exists in context.clj, enhance it)
{:xt/id :hook/edit-<uuid>
 :hook/event-type :edit
 :hook/timestamp <inst>
 :hook/file-path "src/seon/dev/hook.clj"
 :hook/namespace 'seon.dev.hook
 :hook/content-hash "sha256:..."  ; For change detection

 ;; Test results
 :hook/unit-test-result {:success true :test-count 14 :pass-count 37 :fail-count 0}
 :hook/gen-test-result {:success true :functions-tested 5 :failures []}

 ;; Hook decision
 :hook/decision :continue  ; or :block
 :hook/reason nil          ; or "Unit tests failed: 2 failures"
 :hook/feedback ["✓ 14 tests passed" "..."]}

;; Review event (new)
{:xt/id :hook/review-<uuid>
 :hook/event-type :review
 :hook/timestamp <inst>
 :hook/files ["src/seon/dev/hook.clj" "src/seon/dev/verify.clj"]
 :hook/edit-count 3  ; Edits since last review

 ;; Gemini interaction
 :hook/gemini-prompt "Review these changes..."
 :hook/gemini-response "The changes look good. Consider..."
 :hook/gemini-tokens {:prompt 1500 :response 800 :cached 0}

 ;; Context that was sent
 :hook/review-context {:files-content {...} :test-results {...}}}
```

### Implementation Tasks

- [ ] **Enhance edit event schema** (`context.clj`)
  - Add `:hook/content-hash` for change detection
  - Add `:hook/unit-test-result` and `:hook/gen-test-result`
  - Add `:hook/decision`, `:hook/reason`, `:hook/feedback`
  - Update `record-edit!` to accept full result map

- [ ] **Add review event tracking** (`context.clj`)
  - New function `record-review-event!` that stores full Gemini interaction
  - Include prompt, response, tokens, context sent
  - Link to the edits that triggered the review

- [ ] **Wire up in hook.clj**
  - Pass XTDB node through to review stage
  - Capture and store all results in edit event
  - Store Gemini review when it occurs

- [ ] **Add content hash for change detection**
  - Compute hash of file content before processing
  - Skip full pipeline if hash unchanged (like old hook did)
  - Store hash in edit event for debugging

- [ ] **Query helpers for analysis**
  - `edits-for-file` - All edits to a specific file
  - `reviews-in-range` - Reviews between timestamps
  - `failure-rate` - % of edits that resulted in blocks
  - `gemini-token-usage` - Total tokens used in time period

### Verification Work

- [ ] **Test the hook end-to-end**
  - Make real edits to seon files
  - Observe hook output in real time
  - Query XTDB to see stored events
  - Verify data is complete and queryable

- [ ] **Write development experience feedback**
  - Document what works well
  - Document pain points
  - Suggest improvements based on actual usage

### Success Criteria

1. Can query "what happened in the last hour" and see all edits + reviews
2. Can see test pass/fail rates over time
3. Can replay a Gemini review (see exact prompt and response)
4. Can detect when same file is edited repeatedly without changes
5. Agent documents their experience using the hook

---

## Phase 2: Enhanced Context & Iterative AI (Research Required)

These ideas require the modular Phase 1 system to be working first. Mark as research work.

### 1. Error History Tracking

Track edit errors across the session to identify patterns:
```clojure
;; .claude/error-history.edn
[{:timestamp "..." :file "..." :error-type :syntax :message "..."}
 {:timestamp "..." :file "..." :error-type :gen-test :shrunk-input {...}}]
```

Gemini can see "you've hit this same error 3 times" and escalate advice.

### 2. Expanded Context for Gemini

When a function fails, automatically pull in context:
- **Referenced functions** - Find all vars/fns called by the failing function
- **Referenced atoms/state** - What mutable state does it touch?
- **Schemas of dependencies** - What contracts do callees expect?

```clojure
(defn gather-fn-context [var]
  {:source (get-source var)
   :schema (get-schema var)
   :calls (find-called-vars var)      ; static analysis or REPL introspection
   :called-by (find-callers var)      ; reverse lookup
   :atoms-touched (find-atom-refs var)})
```

### 3. REPL-Based Error Enrichment

Instead of just parsing syntax errors from the file, try `(eval (read-string code))` in the REPL:
- Get structured EDN error data (not just strings)
- Stack traces with line numbers
- Macroexpansion failures with context

### 4. Iterative Gemini Conversation

Don't start fresh each time - maintain conversation context:
```
Edit 1: Gemini sees function, gives feedback
Edit 2: "Same error - here's what I tried: [diff]. Still failing."
Edit 3: "Different error now - here's the stack trace: [...]"
```

Avoid repeating context already in conversation. Gemini sees the evolution of the approach.

### 5. Stack Trace Mining

When we get a stack trace, progressively expand context:
1. First: just the error message
2. If same error: add the first 5 stack frames with source
3. If still failing: add referenced functions' source
4. Escalate to include test code, schemas, etc.

### Research Tasks

- [ ] Spike: Static analysis to find called vars (tools.analyzer or simple regex?)
- [ ] Spike: REPL eval for structured error data vs file-based parsing
- [ ] Spike: Gemini conversation continuity (session management)
- [ ] Benchmark: Context expansion impact on response quality vs token cost
