---
name: clojure-testing
description: "Clojure test patterns for Seon. Use when writing tests, debugging test failures, working with generators or test-utils, or when tests fail with unexpected errors."
---

# Clojure Testing

**How to run tests is documented in CLAUDE.md "Testing".** This skill covers patterns, fixtures, and debugging.

## Test Fixtures

### Temporary Datalevin Connection

Most tests need an isolated database. Use `with-temp-conn` from `seon.test-utils`:

```clojure
(require '[seon.test-utils :as tu])
(require '[datalevin.core :as d])

;; Provide a Datalevin schema, get a temp connection
(tu/with-temp-conn my-schema
  (fn [conn]
    (d/transact! conn [{:my/name "test"}])
    (is (= 1 (count (d/q '[:find ?e :where [?e :my/name _]] @conn))))))
```

### Direct Mode (bypass flow infrastructure)

For tests that use `db/transact!` and `db/query` (the public API), bind `db/*direct-mode*`:

```clojure
(tu/with-test-datalevin
  (fn []
    ;; db/transact! and db/query work without the flow infrastructure
    (db/transact! :seon.ai [{:my/key "val"}])))
```

### Pipeline Roundtrip Testing

The `assert-pipeline-roundtrip!` utility in `seon.db.pipeline-test` generates N entities from a Malli schema, transacts them, pulls them back, and validates the roundtrip:

```
Generate → Malli validate → transact → pull → Malli validate
```

See `test/seon/db/pipeline_test.clj` for examples.

## Malli Generators

Seon uses Malli schemas for generative testing. Custom generators live on the schema itself:

```clojure
;; Schema with custom generator
(schema/register! ::my-type
  [:string {:gen/elements ["alpha" "beta" "gamma"]}])

;; Generate samples
(require '[malli.generator :as mg])
(mg/generate ::my-type)

;; Property-based test
(deftest my-property-test
  (let [schema [:map [:id :string] [:count :int]]]
    (doseq [sample (mg/sample schema 100)]
      (is (m/validate schema sample)))))
```

For generative function testing, use `(user/test-gen 'seon.ns)` — it generates inputs from `:malli/schema` metadata and checks outputs.

## Common Failure Patterns

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| "Unregistered attributes in transaction" | Missing `schema/register!` for an attr | Register the attr in the source namespace |
| "Malli validation failed for :attr" | Value doesn't match registered schema | Check what schema is registered, fix the value or the schema |
| Instrumentation error on function call | Function args don't match `:malli/schema` | Read the error — it shows expected schema and a generated example |
| LMDB assertion in tests | Bad type reaching Datalevin (e.g., String where Keyword expected) | Check validation gate is catching it; fix the schema type |
| "Direct buffer memory" OOM | Too many test connections | Use `tu/with-small-db-size` fixture |

## Mocking with-redefs

```clojure
(deftest my-test
  (with-redefs [seon.render/invalidate-render-cache! (constantly nil)]
    ;; Test code that would normally trigger UI refresh
    ))
```

## Key Test Files

| File | Purpose |
|------|---------|
| `test/seon/test_utils.clj` | Datalevin fixtures, time helpers |
| `test/seon/db/pipeline_test.clj` | Generative pipeline roundtrip tests |
| `test/seon/db/validation_test.clj` | Validation gate tests |
| `test/seon/db/schema_roundtrip_test.clj` | Bridge roundtrip contract |
