# PRD: Phase 7b - Observability with Fully Namespaced Keys

**Status:** ✅ Complete (2024-12-31)
**Supersedes:** Phase 7b section of `prd.md`
**Branch:** `feature/unified-dev-hook`

---

## Context

This PRD completes Phase 7b (Observability and Historical Tracking) from the original unified-dev-hook PRD, with a critical architectural correction: **all XTDB keys must be fully namespaced Clojure keywords**.

The original implementation used pseudo-namespaced keys like `:edit/file` and `:review/gemini-prompt`. These are problematic because they don't map to real Clojure namespaces - you can't trace `:edit/file` back to any source code.

Read `prd.md` for full context on the unified dev hook system. This PRD focuses specifically on fixing the key namespacing and completing the observability wiring.

---

## Core Principle: Traceable Keys

**Every key written to XTDB must be a fully qualified keyword that maps to a real Clojure namespace.**

When you see a key like `:seon.dev.context/file`, you know:
1. Go to the `seon.dev.context` namespace
2. Look for `::file` - the schema registration and related functions
3. Everything about that key lives in one place

This enables:
- **Traceability** - Follow the namespace to find the code
- **Schema enforcement** - Validate all writes against registered schemas
- **No transformation** - Data flows through the system with its original keys intact

**Example - Before (wrong):**

```clojure
{:xt/id #uuid "..."
 :entity/type :edit-event           ; pseudo-namespace - where is this defined?
 :edit/file "/path/to/file.clj"     ; can't trace to source
 :edit/unit-test-result {:success true}}  ; plain keys, no schema reference

```

**Example - After (correct):**

```clojure
{:xt/id #uuid "..."
 :seon.dev.context/entity-type :edit-event
 :seon.dev.context/file "/path/to/file.clj"
 :seon.dev.context/unit-test-result
   {:seon.dev.verify/success true            ; verify's keys preserved!
    :seon.dev.verify/test-count 5}}

```

Notice that nested data (test results from verify.clj) keeps its original `::verify/*` keys. No transformation between namespaces - data is traceable all the way down.

---

## Goals

1. **Edit events store full observability data** - test results, decision (continue/block), feedback messages
2. **Review events store Gemini interaction** - prompt, response, token counts for cost tracking
3. **All keys are fully namespaced** - every key traces to its source namespace
4. **No key transformation** - verify results stored with `::verify/*` keys, not converted
5. **Separate dev database** - isolate dev hook data from main application
6. **Query helpers work** - analyze edit history, failure rates, token usage
7. **End-to-end verified** - tested with real edits, not just unit tests

---

## Current State Summary

Read these files to understand the current implementation:

| File | What It Does | Current Issue |
|------|--------------|---------------|
| `src/seon/dev/context.clj` | Records edit/review events to XTDB | Uses pseudo-namespaced keys (`:edit/*`, `:review/*`) |
| `src/seon/dev/hook.clj` | Main hook orchestrator | Discards test results - doesn't pass to record-edit! |
| `src/seon/dev/verify.clj` | Runs unit/gen tests | Correctly uses `::verify/*` keys |
| `src/seon/system.clj` | Integrant components | Pattern for adding new XTDB nodes |

The wiring gap is in `hook.clj` around line 404 - test results are computed but thrown away.

---

## Research Before Implementing

**Critical:** Before writing code, verify your understanding using these resources:

### 1. XTDB Source Code

The full XTDB v2 source is at `reference-code/xtdb/`. When you need to understand XTDB behavior (like how it handles namespaced keys in SQL), read the source - don't guess.

Key questions to answer:
- How does XTDB convert `:seon.dev.context/file` to a SQL column name?
- What's the exact syntax for querying namespaced columns?
- Are there any limitations with deeply nested namespaced maps?

### 2. Gemini Search

Use `(search "query")` in the REPL when you need current information:

```clojure
(search "XTDB v2 namespaced keywords SQL column names")
(search "Clojure fully qualified keywords in databases")

```

### 3. REPL Verification

Test ideas before implementing. The server should be running (`./bin/run`):

```bash
# Test XTDB behavior with namespaced keys
clj-nrepl-eval -p 7888 "
  (require '[seon.db.node :as node])
  (let [n (user/xtdb-node)]
    (node/execute-tx! n [[:put-docs :test-table
                          {:xt/id :test-1
                           :seon.dev.context/file \"/tmp/test.clj\"}]])
    (node/sql-query n \"SELECT * FROM test_table\"))"

```

See what column names XTDB actually uses. Don't assume.

---

## Implementation Approach

This is not a prescriptive step-by-step guide. You'll discover better patterns as you work. These are the goals for each phase:

### Phase 1: Understand and Verify

**Goal:** Confirm the PRD is accurate and fill in gaps.

- Read the current code and verify the issues described
- Test XTDB behavior with fully namespaced keys
- Document what you learn (SQL column names, any gotchas)
- Update this PRD with corrections

### Phase 2: Fix Key Namespacing in context.clj

**Goal:** All keys use `::` (which expands to `:seon.dev.context/*`).

- Update schema registrations
- Update `record-edit!` and `record-review!` to build entities with `::` keys
- Update SQL queries for new column names
- Update all query helper functions
- Test results from verify.clj should be stored as-is with their `::verify/*` keys

### Phase 3: Wire Observability in hook.clj [DONE]

**Goal:** Test results and decisions are recorded.

- [x] Pass `unit-result` and `gen-result` to `record-edit!`
- [x] Record blocked edits too (currently short-circuits without recording)
- [x] Include decision (`:continue` or `:block`) and feedback

**Implementation (2024-12-31):**
- Added `extract-unit-summary` and `extract-gen-summary` helpers to convert `::verify/*` keys to simple keys
- Updated `stage-record-edit` to accept optional `opts` parameter
- Modified pipeline to record before blocking on unit test failure
- Modified pipeline to record before blocking on gen test failure
- Successful edits now record full observability data

### Phase 4: Separate Dev Database [DONE]

**Goal:** Dev hook data is isolated from main application data.

- [x] Add `:seon.dev/xtdb-node` Integrant component
- [x] Follow the pattern from `:seon.primer/xtdb-node`
- [x] Add REPL helper to access dev node

**Implementation (2024-12-31):**
- Added `ig/init-key` and `ig/halt-key!` for `:seon.dev/xtdb-node` in `system.clj`
- Added config to `system.edn` with profile-specific storage: `data/dev-hook` for dev/prod, in-memory for test
- Added `dev-xtdb-node` helper in `user.clj`
- Verified: `(user/status)` shows 6 components including `:seon.dev/xtdb-node`
- Verified: Basic put/query operations work on the dev node

### Phase 5: Cleanup

**Goal:** Remove dead code, ensure tests pass.

- `format-results` and `check-namespace` in verify.clj are unused - remove them
- Clean up any legacy patterns discovered during implementation
- Run full test suite

### Phase 6: End-to-End Verification

**Goal:** Verify with real usage, not just tests.

- Make actual edits to seon files
- Query XTDB to see the stored events
- Verify all keys are properly namespaced
- Document what you observe

---

## Key Design Decisions

### 1. No Key Transformation

When `verify.clj` produces:

```clojure
{:seon.dev.verify/success true
 :seon.dev.verify/test-count 5}

```

Store it exactly like that in XTDB. Don't convert to `:success` or `:test-count`. The namespace tells you where the data came from.

### 2. Table Names

Currently using `:edit-event` and `:review-event` as XTDB table names. Consider whether these should also be namespaced (`:seon.dev.context/edit-event`). Research XTDB behavior and decide based on what works best.

### 3. Entity Type Field

The current code stores `:entity/type :edit-event`. Since the table name already indicates type, this may be redundant. Decide whether to keep it (for query convenience) or remove it (for simplicity).

### 4. Fresh Database

The dev database is new, so there's no migration needed. Old data in the main XTDB can be ignored - we're starting fresh.

---

## Success Criteria

- [x] All XTDB keys are fully namespaced (`:seon.dev.context/*`, `:seon.dev.verify/*`)
- [x] Edit events contain test results with original `::verify/*` keys
- [x] Edit events contain decision (`:continue` or `:block`)
- [x] Blocked edits are recorded (not just successful ones)
- [x] Query helpers return data with namespaced keys
- [x] Separate dev database mounted and used
- [x] Dead code removed
- [x] All tests pass (279 tests, 0 failures)
- [x] End-to-end verification documented in notes.md

---

## Files Likely to Change

| File | Why |
|------|-----|
| `src/seon/dev/context.clj` | Refactor keys, update SQL |
| `src/seon/dev/hook.clj` | Wire observability |
| `src/seon/dev/verify.clj` | Remove dead code |
| `src/seon/system.clj` | Add dev XTDB node |
| `resources/system.edn` | Add dev node config |
| `env/dev/clj/user.clj` | Add dev-xtdb-node helper |
| `test/seon/dev/*_test.clj` | Update for new keys |

---

## Reference Documents

- `prd.md` - Original unified dev hook PRD (read Phase 7b section)
- `decisions.md` - Architectural decisions made so far
- `notes.md` - Gotchas and learnings (update this as you work)
- `CONVENTIONS.md` - Malli schema patterns
- `docs/reference/xtdb-v2-reference.md` - XTDB patterns
- `reference-code/xtdb/` - Full XTDB source code

---

## Agent Instructions

1. **Research first** - Use `(search ...)`, read XTDB source, test in REPL before coding
2. **Update this PRD** - Add findings, correct errors, document decisions
3. **Use notes.md** - Record gotchas and surprises for future reference
4. **Test incrementally** - Verify each change works before moving on
5. **No legacy code** - If something is unused, delete it. No backwards compatibility shims.
6. **Ask when stuck** - If something doesn't make sense, ask rather than guess
