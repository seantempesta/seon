# Test Suite Fixes PRD

## Problem Statement

The test suite has 40 failing tests across two categories:

1. **Schema Generator Issue (20 failures)** - Malli generators produce wrong types
2. **Handler Test Failures (20 failures)** - Tests don't match updated handler implementations

These failures prevent CI from passing and block development workflow.

## Background

### Java Version Fixed
The original failure (`ClassNotFoundException: StructuredTaskScope$ShutdownOnFailure`) was caused by Java 25 incompatibility with XTDB 2.1.0-rc0. This was fixed by adding `.envrc` to enforce Java 21.

### Schema Generator Issue
In `ml-options.db.schema-test/custom-generators-produce-valid-data`:
- Generator produces `UUID` for `:xt/id` field
- Schema expects `:string` type
- Error: `{:path [:xt/id], :in [:xt/id], :schema :string, :value #uuid "..."}`

### Handler Test Failures
In `ml-options.web.handlers-test`:
- `start-import-handler-test` - Multiple assertion failures
- `handler-integration-test` - Job lifecycle assertions failing
- Common error: `"Cannot invoke \"java.lang.CharSequence.length()\" because \"this.text\" is null"`

The handler tests were written for an earlier API but the handlers were updated in prior feature work. The tests and implementation are now out of sync.

## Goals

1. All tests pass with `clj -M:test -m kaocha.runner`
2. No test workarounds (skipping, commenting out)
3. Tests accurately reflect current implementation behavior

## Non-Goals

- Adding new test coverage beyond fixing failures
- Refactoring test infrastructure
- Performance improvements

## Technical Analysis (Completed)

See `research/` directory for detailed findings. Summary:

### Schema Generator - Root Cause Found
- **Problem**: Generator produces UUID, schema expects string
- **Location**: `test/ml_options/generators.clj` - option-greeks generator
- **Schema is correct** - generator needs to match production ID format
- **Reference**: Check `src/ml_options/data/thetadata.clj` for how production IDs are created

### Handler Tests - Root Cause Found
- **Problem**: Handlers throw NPE instead of returning 400 errors
- **Root causes**:
  1. `slurp(nil)` when request body is nil
  2. `LocalDate/parse nil` when required fields missing
  3. No input validation before parsing
- **Location**: `src/ml_options/web/handlers.clj` - start-import handler

---

## Implementation Goals

### Goal 1: Fix Schema Generator
Make the option-greeks generator produce valid string IDs that match the production format.

**Success criteria:**
- Generator produces `:xt/id` values as strings (not UUIDs)
- IDs match the format used in production code
- All schema validation tests pass

### Goal 2: Fix Handler Input Validation
Add proper input validation to prevent NPEs when request body or fields are missing.

**Success criteria:**
- Handlers return 400 errors with clear messages for invalid input
- No NPEs thrown for missing body or fields
- Error messages are useful for debugging

### Goal 3: Verify All Tests Pass
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
clj -M:test -m kaocha.runner
```

Expected: 0 failures, 0 errors

## Resources to Study

| Resource | What's There |
|----------|--------------|
| `test/ml_options/generators.clj` | Schema generators - where the ID bug is |
| `src/ml_options/data/thetadata.clj` | Production ID format to match |
| `src/ml_options/web/handlers.clj` | Handler implementations to fix |
| `test/ml_options/web/handlers_test.clj` | Tests that are failing |
| `research/` | Prior investigation findings |

## Constraints

- **Don't change schemas** - The schema is correct, fix the generator
- **Don't skip tests** - Fix the actual bugs, don't comment out assertions
- **Fix implementation if broken** - If handlers are wrong, fix handlers (not just tests)
- **Document decisions** - Record why changes were made in `decisions.md`

## Success Criteria

All tests pass:
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
clj -M:test -m kaocha.runner
# Expected: 0 failures, 0 errors
```

---

## Implementation Summary

All tests now pass: **135 tests, 660 assertions, 0 failures**.

### Files Changed
| File | What Changed |
|------|--------------|
| `test/ml_options/generators.clj` | Fixed option-greeks generator to produce string IDs in production format (`"{OCC_SYMBOL}-{ISO_TIMESTAMP}"`) instead of UUIDs |
| `src/ml_options/web/handlers.clj` | Added input validation to `start-import` handler to check for nil body and blank required fields before parsing |
| `test/ml_options/web/handlers_test.clj` | Added `json-request` helper and updated all tests to include proper `content-type: application/json` header; added `jobs/get-node` mocks |
| `test/ml_options/web/stats_test.clj` | Updated test assertions to match current API (`:by-symbol` list format instead of `:records-by-symbol` map format; `:min-date/:max-date` instead of `:earliest/:latest`) |

### Key Decisions

**1. Generator ID Format**
- Used production format: `str occ "-" (.toString quote-instant)`
- Added `quote-instant` generator using existing `gen-historical-instant`
- This ensures test data matches production data format exactly

**2. Handler Input Validation**
- Added nil body check at top of handler (throws ex-info)
- Added blank field validation after parsing but before use
- Separated `DateTimeParseException` catch clause for clear error messages
- Validation order: body exists → JSON parses → fields present → dates parse

**3. Test Infrastructure**
- Created `json-request` helper to ensure proper content-type headers
- All JSON request tests now use this helper for consistency
- Added `jobs/get-node` mocks to all handler tests that call `start-import`

**4. Stats Test Updates**
- Tests updated to match current implementation (not vice versa)
- The stats refactor changed from map to list format - this is correct for HTML iteration
- Converted list to map in test for easier assertion

### Deviations from Analysis

**None** - The research findings were accurate. Both root causes were correct:
1. Generator produced UUID instead of string - fixed as prescribed
2. Handler threw NPE instead of 400 errors - fixed with input validation

The only additional discovery was that tests needed proper `content-type` headers to trigger JSON parsing, which wasn't mentioned in the research but was straightforward to fix.
