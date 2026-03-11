---
type: capability
status: partial
tags: [vision, schema]
---
# Test Data Isolation

Tests run against isolated database state so they cannot interfere with each other or with the live system. Direct-mode bypass and temp connection fixtures exist and are widely used. A unified fixture and generative testing adoption are designed but not yet built.

## What Exists

- `with-temp-conn` and `with-test-datalevin` fixtures
- 19 test files bind `db/*direct-mode*` for flow bypass
- 70 test files with approximately 819 deftest forms

## Gaps

- Unified `with-test-db` fixture not implemented (designed but not built)
- No `defspec` adoption for property-based tests
- Generative block-on-fail still disabled

## Related

- Components: [[components/testing]]
- PRDs: [[prds/test-infrastructure/prd]]
