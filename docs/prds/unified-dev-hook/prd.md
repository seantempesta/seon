# PRD: Unified Development Feedback Hook

**Status:** Complete
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
- `bin/seon-hook` - Unified Babashka hook with full pipeline
- `src/seon/dev/feedback.clj` - REPL-side generative testing + function tracking
- `src/seon/ai/gemini.clj` - Native Gemini client with search, calculate, review
- `.claude/seon-hook.edn` - Configuration file

**Hook Behavior:**
- Syntax repair via `clj-paren-repair-claude-hook` (blocks on unfixable)
- Unit tests with smart blocking (blocks on real failures, warns on infrastructure issues)
- Generative tests with Malli schemas (blocks on schema mismatches)
- Gemini review with 30s debounce (advisory, never blocks)
- All state stored in XTDB (functions, errors, pending edits)

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
| `bin/seon-hook` | Unified hook script (Babashka) |
| `src/seon/dev/feedback.clj` | REPL utilities, function tracking |
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
