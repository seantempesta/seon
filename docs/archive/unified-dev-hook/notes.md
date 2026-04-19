---
type: prd
status: completed
tags: [prd, archive]
---

# Implementation Notes: Unified Dev Hook

**Last Updated:** 2024-12-31

---

## Overview

Unified development feedback hook combining:
1. Syntax repair (from clojure-mcp-light)
2. Unit tests (from auto-test-hook)
3. Generative tests (new - Malli)
4. AI review (new - Gemini)

All state stored in XTDB.

---

## Key Learnings

### XTDB Namespaced Key Behavior (Phase 7b Research)

**Researched:** 2024-12-31

#### SQL Column Name Conversion

XTDB converts fully namespaced Clojure keywords to SQL column names using `$` as the separator for both namespace segments and the keyword name:

| Clojure Keyword | SQL Column Name |
|-----------------|-----------------|
| `:seon.dev.context/file` | `seon$dev$context$file` |
| `:seon.dev.verify/success` | `seon$dev$verify$success` |
| `:fn/namespace` | `fn$namespace` |
| `:xt/id` | `_id` (special case) |
| `:xt/valid-from` | `_valid_from` (special case) |

The pattern is:
- `.` (namespace separator) → `$`
- `/` (keyword name separator) → `$`
- `-` (hyphen in names) → `_`

#### Querying Namespaced Columns

**Writing data:**

```clojure
(node/execute-tx! node [[:put-docs :test-table
                         {:xt/id :test-1
                          :seon.dev.context/file "/tmp/test.clj"
                          :seon.dev.verify/success true}]])

```

**SELECT * returns original keywords:**

```clojure
(node/sql-query node "SELECT * FROM test_table")
;; => [{:xt/id :test-1,
;;      :seon.dev.context/file "/tmp/test.clj",
;;      :seon.dev.verify/success true}]

```

**Selecting specific columns (use $ syntax):**

```clojure
(node/sql-query node "SELECT seon$dev$context$file FROM test_table")
;; => [#:seon.dev.context{:file "/tmp/test.clj"}]

```

**Filtering on namespaced columns:**

```clojure
(node/sql-query node
  ["SELECT * FROM test_table WHERE seon$dev$context$file = ?" "/tmp/test.clj"])
;; Works! Returns rows with matching file path.

```

**Aliasing columns:**

```clojure
(node/sql-query node "SELECT seon$dev$context$file AS file_path FROM test_table")
;; => [{:file-path "/tmp/test.clj"}]
;; Note: SQL snake_case becomes Clojure kebab-case

```

**Finding column names via information_schema:**

```clojure
(node/sql-query node
  "SELECT column_name FROM information_schema.columns WHERE table_name = 'test_table'")
;; => [{:column-name _id}
;;     {:column-name seon$dev$context$file}
;;     {:column-name seon$dev$verify$success}]

```

#### Key Implications for Phase 7b

1. **Data integrity preserved**: When you write `:seon.dev.context/file`, you get `:seon.dev.context/file` back from `SELECT *`. No lossy conversion.

2. **SQL filtering works**: You can filter on any namespaced column using the `$` syntax (e.g., `WHERE seon$dev$verify$success = true`).

3. **Mixed key sources OK**: A single entity can have keys from multiple namespaces (e.g., `::context/*` and `::verify/*`), and both are preserved.

4. **No special escaping needed**: The `$` character works as a regular identifier in XTDB SQL - no quotes or escaping required.

---

### Dead Code Analysis: verify.clj

**Researched:** 2024-12-31

#### `format-results` - DEAD CODE

The `format-results` function in `src/seon/dev/verify.clj` (lines 343-368) is unused in production code.

**Evidence:**
- Only referenced in:
  - Its own docstring (examples)
  - Test file `verify_test.clj` (test coverage)
  - The PRD documentation
- NOT called by `hook.clj` or any other namespace
- `hook.clj` uses `format-unit-result` and `format-gen-result` directly instead

**Recommendation:** Remove. It's a convenience wrapper but nothing uses it.

#### `check-namespace` - DEAD CODE

The `check-namespace` function in `src/seon/dev/verify.clj` (lines 374-418) is unused in production code.

**Evidence:**
- Only referenced in:
  - Its own multi-arity definition (`(check-namespace ns)` calls `(check-namespace ns {})`)
  - The `comment` block for REPL exploration
  - Test file `verify_test.clj` (test coverage)
  - PRD documentation mentioning it as a planned feature
- NOT called by `hook.clj` or any other namespace
- `hook.clj` calls `run-unit-tests-for-source` and `run-gen-tests` separately

**Recommendation:** Remove. The combined runner was planned but never wired up. The hook handles the orchestration itself.

---

## Gotchas

### Malli Function Schemas Registration

Functions must be registered with `m/=>` for the hook to see them:

```clojure
;; This works:
(m/=> my-fn [:=> [:cat :int] :int])
(defn my-fn [x] (* x x))

;; This does NOT register (schema defined but not linked):
(def MySchema [:=> [:cat :int] :int])
(defn my-fn [x] (* x x))

```

### REPL Must Be Running

The hook queries `(m/function-schemas)` via nREPL. If REPL is down:
- Skip generative tests
- Fall back to unit tests only
- Log warning

---

## Code Patterns

### Phase 3: Observability Wiring in hook.clj

**Implemented:** 2024-12-31

The hook now records full observability data with each edit event:

1. **`extract-unit-summary`** - Converts `::verify/*` keys to simple keys for storage:

   ```clojure
   (defn- extract-unit-summary [unit-result]
     (when unit-result
       {:success (::verify/success unit-result)
        :test-count (::verify/test-count unit-result)
        :pass-count (::verify/pass-count unit-result)
        :fail-count (::verify/fail-count unit-result)
        :error-count (::verify/error-count unit-result)}))

   ```

2. **`extract-gen-summary`** - Extracts gen test summary:

   ```clojure
   (defn- extract-gen-summary [gen-result]
     (when gen-result
       {:success (::verify/success gen-result)
        :error (::verify/error gen-result)}))

   ```

3. **Blocked edits are now recorded** - Before returning a block response, the edit event is recorded with:
   - `:decision :block`
   - `:reason "..."` - The blocking reason
   - `:unit-test-result` or `:gen-test-result` as appropriate

4. **Successful edits include all data** - The successful path records:
   - `:decision :continue`
   - `:unit-test-result` (if tests ran)
   - `:gen-test-result` (if tests ran)
   - `:feedback` - The feedback messages

Example recorded edit event:

```clojure
{:xt/id #uuid "..."
 :seon.dev.context/entity-type :edit-event
 :seon.dev.context/file "/path/to/file.clj"
 :seon.dev.context/namespace :seon.foo
 :seon.dev.context/decision :continue
 :seon.dev.context/unit-test-result {:success true :test-count 5 :pass-count 5}
 :seon.dev.context/gen-test-result {:success true}
 :seon.dev.context/feedback ["5 tests passed (seon.foo-test)" "Generative tests passed (seon.foo)"]}

```

---

## Testing Notes

### REPL Commands for Manual Testing

```clojure
;; Check what schemas are registered
(m/function-schemas)

;; Get schemas for a specific namespace
(get-in (m/function-schemas) [:clj 'seon.foo])

;; Run generative check on a function
(require '[malli.generator :as mg])
(mg/check [:=> [:cat :int] :int] (fn [x] (* x x)) {:num-tests 10})

;; Extract schema refs
(require '[seon.dev.feedback :as fb])
(fb/extract-schema-refs [:=> [:cat :user/id] :order/result])

```

---

## End-to-End Verification (Phase 7b Complete)

**Verified:** 2024-12-31

### Test Results

All success criteria verified:

1. **All XTDB keys are fully namespaced** ✅
   - Entity keys: `:seon.dev.context/file`, `:seon.dev.context/namespace`, `:seon.dev.context/decision`, etc.
   - SQL columns use `$` syntax: `seon$dev$context$file`

2. **Edit events contain test results** ✅

   ```clojure
   :seon.dev.context/unit-test-result {:success true, :test-count 5, :pass-count 5}
   :seon.dev.context/gen-test-result {:success true}

   ```

3. **Edit events contain decision** ✅
   - `:seon.dev.context/decision :continue` or `:block`

4. **Blocked edits are recorded** ✅
   - Blocked edits stored with `:decision :block` and `:reason`

5. **Query helpers work** ✅

   ```clojure
   (ctx/failure-rate node)
   ;; => {:total 2, :blocked 1, :rate 0.5}

   (ctx/recent-activity node)
   ;; => {:period-hours 1, :edit-count 2, :review-count 1, :blocked-count 1, ...}

   ```

6. **Separate dev database** ✅
   - `:seon.dev/xtdb-node` component added
   - Storage at `data/dev-hook`
   - `(user/dev-xtdb-node)` helper available

7. **Dead code removed** ✅
   - Removed `format-results` from verify.clj
   - Removed `check-namespace` from verify.clj

8. **All tests pass** ✅
   - 279 tests, 1288 assertions, 0 failures

---

### Malli Registry Sync Issue (2026-01-04)

**Problem:** Generative tests were failing with `:malli.core/register-function-schema` error when running via the hook.

**Root Cause:** After namespace reloads, Malli's default registry can get out of sync with our `seon.schema/*schemas` mutable atom. The `defonce` in `seon.schema` creates the composite registry at load time, but subsequent reloads can break the link between the mutable registry and the atom.

**Symptoms:**
- `mi/collect!` throws `:malli.core/register-function-schema` error
- Nested cause shows `:malli.core/invalid-schema` for registered schemas
- `(schema/registered? ::some-key)` returns true but `(m/schema ::some-key)` throws

**Solution:** Added `ensure-registry-sync!` function in `verify.clj` that refreshes the Malli registry before collecting function schemas:

```clojure
(defn- ensure-registry-sync!
  "Ensure Malli's default registry is in sync with our mutable schema atom."
  []
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry @#'schema/*schemas))))

```

Called in `run-gen-tests` before `mi/clj-collect!`.

**Also:** Use `mi/clj-collect!` (function) instead of `mi/collect!` (macro) for dynamic namespace symbols at runtime.

---

## References

- [Malli Function Schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)
- [clojure-mcp-light hook.clj](~/.gitlibs/.../clojure-mcp-light/src/clojure_mcp_light/hook.clj)
- Research: `docs/prds/unified-dev-hook/research/`
