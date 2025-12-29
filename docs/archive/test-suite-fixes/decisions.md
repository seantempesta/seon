# Test Suite Fixes - Decisions

## Decision Log

### [2025-12-05] Fix Generator, Not Schema

**Context:** The option-greeks generator produced UUID for `:xt/id` but schema expected string.

**Options Considered:**
1. Fix generator to produce string IDs matching production format
2. Change schema to accept UUIDs
3. Add both UUID and string as valid types in schema

**Decision:** Fixed the generator to match production format

**Rationale:**
- Schema is correct - production always uses deterministic string IDs
- Format: `"{OCC_SYMBOL}-{ISO_TIMESTAMP}"` enables idempotent ingestion
- Transaction builder already has UUID fallback, but that's for error cases
- Tests should validate real production data format
- Would allow invalid production data in tests if we changed schema

**Consequences:** Test data now matches production data format exactly

---

### [2025-12-05] Add Input Validation to Handler

**Context:** Handler threw NPE on missing/nil fields instead of returning 400 errors.

**Options Considered:**
1. Add explicit validation checks before parsing
2. Let exceptions bubble up with generic error handler
3. Add middleware for validation

**Decision:** Added explicit validation checks in the handler

**Validation Order:**
1. Check request body is not nil
2. Parse JSON (catches malformed JSON)
3. Check required fields are not blank
4. Parse dates (catches invalid date format)

**Rationale:**
- Fail fast with clear error messages
- Each validation failure returns specific 400 error
- Separate catch for `DateTimeParseException` provides better error messages
- Follows standard HTTP API patterns
- NPE messages are cryptic ("this.text is null")

**Consequences:** API now returns actionable error messages for missing/invalid fields

---

### [2025-12-05] Update Tests, Not Implementation (Stats)

**Context:** Stats tests expected `:records-by-symbol` map but implementation returned `:by-symbol` list.

**Options Considered:**
1. Update tests to match current implementation
2. Change implementation to return both formats
3. Change implementation back to map format

**Decision:** Updated tests to match current implementation

**Rationale:**
- Implementation is correct - HTML rendering needs list format
- Prior refactor changed API but didn't update tests
- Tests should validate current behavior, not outdated expectations
- Converting list to map in tests is straightforward
- Adding both formats adds unnecessary complexity

**Consequences:** Tests now validate the actual API surface

---

### [2025-12-05] Create Test Helper for JSON Requests

**Context:** Many tests failed because they didn't include `content-type: application/json` header.

**Options Considered:**
1. Create `json-request` helper that sets both body and headers
2. Add headers to existing `mock-request` default
3. Add headers manually in each test

**Decision:** Created `json-request` helper function

**Rationale:**
- DRY principle - don't repeat header setup in every test
- Makes test intent clearer - "this is a JSON request"
- Prevents future bugs from forgetting the header
- Follows existing pattern (tests already had `json-body` and `mock-request` helpers)
- Some tests explicitly test non-JSON requests, so default headers would break those

**Consequences:** More maintainable and readable tests

---

## Lessons Learned

1. **Always check test mocks match handler signatures** - The handler called `jobs/get-node` but tests didn't mock it initially
2. **Content-type matters** - Handler behavior changes based on content-type header (JSON vs form-encoded)
3. **Test data should match production data** - Generators exist to catch real issues, not shortcuts
4. **Validation should provide actionable errors** - "symbols is required" is better than NPE stack traces
