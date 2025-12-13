# Test Suite Fixes - Notes

## Investigation Notes

### Test Run Summary (2024-12-05)

With Java 21:
- **Total tests:** 135
- **Assertions:** 660
- **Failures:** 40
- **Errors:** 0

### Failure Categories

#### 1. Schema Generator (ml-options.db.schema-test)
- `custom-generators-produce-valid-data` - 20 failures
- Issue: Generator produces UUID for `:xt/id`, schema expects string
- Example error:
  ```
  {:path [:xt/id], :in [:xt/id], :schema :string,
   :value #uuid "eee93375-e09c-4207-9419-eef61704bd09"}
  ```

#### 2. Handler Tests (ml-options.web.handlers-test)
- `start-import-handler-test` - Multiple failures
- `handler-integration-test` - Job lifecycle failures
- Common error: `"Cannot invoke \"java.lang.CharSequence.length()\" because \"this.text\" is null"`
- Tests expect responses that don't match current implementation

### Files to Investigate

1. `src/ml_options/db/schema.clj` - Schema definitions
2. `test/ml_options/generators.clj` - Custom generators
3. `src/ml_options/web/handlers.clj` - Handler implementations
4. `test/ml_options/web/handlers_test.clj` - Handler tests

## Gotchas & Learnings

(Add findings here as investigation progresses)
