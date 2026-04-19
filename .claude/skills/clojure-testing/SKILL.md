---
name: clojure-testing
description: "Clojure test patterns for Seon. Use when writing tests, debugging test failures, working with generators or test-utils, or when tests fail with unexpected errors."
---

# Clojure Testing

> See also: `docs/seon/components/testing.md`

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

For tests that use `db/transact!` and `db/query` (the public API), bind `db/*direct-mode*` and `db/*conn-manager*` with a fake manager. The fake manager maps db-name keywords to connections.

`with-test-datalevin` provides a quick fixture mapping `:seon.ai` to a schemaless temp conn:

```clojure
(tu/with-test-datalevin
  (fn []
    ;; db/transact! and db/query work without the flow infrastructure
    (db/transact! :seon.ai [{:my/key "val"}])))
```

For custom db-names or schemas, wrap `with-temp-conn` yourself. This is the pattern used in `validation_test.clj`:

```clojure
(defn- with-my-conn [f]
  (tu/with-temp-conn my-datalevin-schema
    (fn [conn]
      (let [fake-mgr {::conn/port 0
                      ::conn/connections (atom {:my-db {::conn/connection conn}})}]
        (binding [db/*direct-mode* true
                  db/*conn-manager* fake-mgr]
          (f conn))))))

;; Then in tests:
(with-my-conn
  (fn [conn]
    (db/transact! :my-db [{::id 1 ::name "test"}])
    (is (= "test" (::name (d/pull @conn '[*] [::id 1]))))))
```

### Pipeline Roundtrip Testing

`assert-pipeline-roundtrip!` in `seon.db.pipeline-test` is the canonical generative test for schema development. Given a Malli `:map` schema, it:

1. Validates schema constraints (no `:any`, no `[:maybe X]`, namespaced keys)
2. Derives Datalevin schema via the bridge (`malli-map->datalevin-schema`)
3. Generates N entities from the Malli schema
4. Transacts each to a temp Datalevin DB
5. Pulls each back and coerces (vector->set for `:set` keys, strips `:db/id`)
6. Validates the pulled entity against Malli
7. Asserts value equality (sets for cardinality-many, direct for scalars)

Returns `{:pass-count N :fail-count M :failures [...]}`.

```clojure
(require '[seon.db.pipeline-test :refer [assert-pipeline-roundtrip!]])

(deftest my-entity-pipeline-test
  (testing "my entity schema survives the full pipeline"
    (let [schema [:map
                  [:my.entity/id {:db/unique :db.unique/identity} :string]
                  [:my.entity/name :string]
                  [:my.entity/status [:enum :active :inactive]]
                  [:my.entity/tags {:optional true} [:set :keyword]]]
          result (assert-pipeline-roundtrip! schema
                   {:identity-key :my.entity/id :num-samples 20})]
      (is (zero? (:fail-count result))
          (str "Failures: " (pr-str (:failures result))))
      (is (= 20 (:pass-count result))))))
```

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
| `test/seon/db/schema_test.clj` | Schema registration and derivation tests |
| `test/seon/db/consistency_test.clj` | Cross-entity consistency checks |
| `test/seon/db/datalevin/writer_test.clj` | Datalevin writer flow tests |
