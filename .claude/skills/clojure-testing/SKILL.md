---
name: clojure-testing
description: "Clojure test patterns for this project. Use when writing tests, debugging test failures, editing *_test.clj files, working with generators.clj or test-utils, or when tests fail with unexpected errors. Use when you see kaocha, malli generators, or mock patterns."
---

# Clojure Testing

## Run Tests

```bash
# All tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home clj -M:test -m kaocha.runner

# Watch mode
clj -M:test -m kaocha.runner --watch
```

## Common Failure Patterns

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| NPE in handler | Missing mock or nil input | Add mocks, validate inputs |
| Schema validation fails | Generator produces wrong type | Check generator matches schema |
| JSON not parsed | Missing content-type header | Add `"content-type" "application/json"` |
| "this.text is null" | `slurp nil` on missing body | Validate body exists first |

## Quick Fixes

### JSON Request Tests
```clojure
;; WRONG - body without content-type
{:body (io/input-stream (.getBytes json-str))}

;; RIGHT - include content-type header
{:body (io/input-stream (.getBytes json-str))
 :headers {"content-type" "application/json"}}
```

### Mocking System Components
```clojure
(with-redefs [jobs/get-node (constantly mock-node)
              jobs/get-state (constantly (atom {}))]
  (handler request))
```

### Generator ID Format
```clojure
;; WRONG - UUID for string schema
{:xt/id (java.util.UUID/randomUUID)}

;; RIGHT - deterministic string matching production
{:xt/id (str occ-symbol "-" (.toString timestamp))}
```

## Key Files

| File | Purpose |
|------|---------|
| `test/ml_options/generators.clj` | Domain data generators |
| `test/ml_options/test_utils.clj` | XTDB fixtures, time helpers |

## For More Details

- **Generator patterns**: See [references/generators.md](references/generators.md)
- **Mock patterns**: See [references/mocking.md](references/mocking.md)
- **Test utilities**: See [references/test-utils.md](references/test-utils.md)
