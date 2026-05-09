---
type: research
status: abandoned
tags: [prd, research]
---
# Test Coverage Audit - Findings

**Auditor:** Claude (via test-coverage-audit task)
**Test Count:** ~183 tests across 13 test files

---

## Executive Summary

The test suite has **good coverage in critical areas** (validation, data ingestion, HTTP handlers) but has a **critical gap: the financial calculation primitives in `dsl/primitives.clj` have ZERO tests**. This is the highest priority fix.

### Overall Assessment

| Area | Coverage | Quality | Priority |
|------|----------|---------|----------|
| Data validation | Excellent | Property-based tests | - |
| Schema validation | Good | Property-based tests | - |
| HTTP handlers | Good | Integration-style | - |
| Database queries | Adequate | Temporal edge cases | P2 |
| **Financial calculations** | **NONE** | - | **P0** |
| DSL executor | NONE | - | P1 |
| Transactions | NONE | - | P1 |
| Agent analysis | NONE | - | P1 |

---

## P0 - Critical: Financial Calculations (dsl/primitives.clj)

**ZERO TESTS** for financial calculation primitives. This is a **financial data system** - these calculations MUST be tested.

### Untested Functions

| Function | Risk | What Could Go Wrong |
|----------|------|---------------------|
| `calculate-percentile` | HIGH | Off-by-one errors, empty sequence handling |
| `calculate-percentile-rank` | HIGH | Division by zero, boundary values |
| `iv-rank` | HIGH | Wrong percentile = bad trading signals |
| `iv-percentile` | MEDIUM | Same as above |
| `term-structure-slope` | HIGH | Division issues, wrong slope direction |
| `skew-index` | HIGH | Average calculation errors |
| `put-call-ratio` | MEDIUM | Division by zero if no calls |
| `gamma-rent` | MEDIUM | Division by zero, sign handling |
| `net-gamma` | LOW | Simple reduce |
| `vanna` | HIGH | Complex formula, edge cases |
| `implied-correlation` | HIGH | TODO markers, hardcoded values |

### Specific Concerns

1. **`calculate-percentile`** (line 29-43):

```clojure
(defn- calculate-percentile [values p]
  (when (seq values)
    (let [sorted (sort values)
          n (count sorted)
          idx (int (* (/ p 100.0) (dec n)))]  ;; Off-by-one risk!
      (nth sorted idx))))

```

- `idx` calculation may give wrong results for edge percentiles (0, 100)
- No interpolation between values (uses floor)
- Empty sequence returns nil - callers must handle

1. **`calculate-percentile-rank`** (line 45-57):

```clojure
(defn- calculate-percentile-rank [values current-value]
  (when (and (seq values) current-value)
    (let [below-count (count (filter #(<= % current-value) values))]
      (/ (double below-count) (count values)))))

```

- Uses `<=` not `<` - may affect edge cases
- `current-value` of nil returns nil - callers must handle

1. **`put-call-ratio`** (line 200-228):

```clojure
(if (pos? call-sum)
  (/ put-sum call-sum)
  0.0)  ;; Returns 0.0 when no calls - is this correct?

```

1. **`vanna`** (line 298-316):

```clojure
(let [days-to-expiry 30 ;; TODO: Calculate from expiry  ;; HARDCODED!

```

### Required Tests

```clojure
;; Percentile edge cases
(deftest calculate-percentile-test
  (testing "0th percentile returns minimum"
    (is (= 1 (calculate-percentile [1 2 3 4 5] 0))))
  (testing "100th percentile returns maximum"
    (is (= 5 (calculate-percentile [1 2 3 4 5] 100))))
  (testing "50th percentile with even count"
    (is (= 2 (calculate-percentile [1 2 3 4] 50))))  ;; What should this be?
  (testing "Empty sequence returns nil"
    (is (nil? (calculate-percentile [] 50))))
  (testing "Single value at any percentile"
    (is (= 42 (calculate-percentile [42] 0)))
    (is (= 42 (calculate-percentile [42] 50)))
    (is (= 42 (calculate-percentile [42] 100)))))

;; IV rank property tests
(defspec iv-rank-returns-valid-percentile 100
  (prop/for-all [values (gen/vector (gen/double* {:min 0.05 :max 2.0 :NaN? false :infinite? false}) 1 100)]
    (let [current (first values)
          rank (calculate-percentile-rank values current)]
      (and (>= rank 0.0) (<= rank 1.0)))))

```

---

## P1 - High Priority: Missing Critical Tests

### 1. db/transactions.clj (NO TESTS)

ID generation is deterministic for deduplication. Bugs here cause duplicate or lost data.

**Functions needing tests:**

- `make-option-quote-id` - deterministic ID from OCC + timestamp
- `make-iv-surface-id` - deterministic ID from ticker + timestamp
- `put-option-quote` - transaction builder
- `ingest-quotes!` - batch ingestion with validation

**Risk:** ID collision or non-determinism causes data corruption.

### 2. dsl/executor.clj (NO TESTS)

This executes LLM-generated code. Security-critical.

**Functions needing tests:**

- `safe-symbol?` - symbol whitelist
- `validate-expr` - expression validation (can bypass?)
- `execute` - expression execution
- `compile-rule` - rule compilation

**Risk:** Code injection if validation is bypassable.

### 3. agent/analysis.clj (NO TESTS)

Trading recommendations. Wrong categorization = bad advice.

**Functions needing tests:**

- `label-iv-rank` - threshold categorization
- `label-skew` - threshold categorization
- `determine-recommendation` - decision logic
- `analyze-ticker` - full pipeline

**Risk:** Incorrect signal labeling leads to wrong trading recommendations.

---

## P2 - Medium Priority: Coverage Gaps

### 1. db/queries.clj (9 tests, but missing coverage)

**Missing:**

- `options-by-delta` - delta range filtering
- `atm-options` - ATM option selection
- `iv-term-structure` - term structure calculation
- `option-by-occ` - single option lookup

**Current tests only cover:**

- `historical-ivs` - temporal queries (good)
- `options-chain` - basic + expiry filter

### 2. web/jobs.clj (NO TESTS)

Job lifecycle management. Currently tested indirectly via handlers.

**Functions needing tests:**

- `start-import!` - job creation
- `stop-job!` - job cancellation
- `update-job-progress!` - progress tracking
- Job state transitions

### 3. web/sse.clj (NO TESTS)

SSE streaming logic.

**Functions to test:**

- `streaming-response` - response creation
- `refresh-all!` - client refresh mechanism
- Client lifecycle (connect/disconnect)

---

## Skip - Low Value Tests

These don't need tests (config, entry points, trivial code):

| File | Reason |
|------|--------|
| config.clj | Pure configuration map |
| core.clj | Entry point, calls system/start! |
| runner.clj | Entry point |
| system.clj | Integrant config |
| web/routes.clj | Trivial routing table |
| web/server.clj | Server config |
| web/brotli.clj | Compression utility |
| web/logs.clj | Log parsing utility |
| web/html.clj | View rendering (tested via handlers) |

---

## Test Quality Assessment

### Excellent Quality

**validation_test.clj** (26 tests)

- Property-based tests for all Greek ranges
- Edge case coverage (deep ITM, positive theta)
- Regression tests for boundaries
- Real-world scenario tests

**schema_test.clj** (11 tests)

- Property-based tests for all schema types
- Custom generator validation
- Explanation error testing

### Good Quality

**handlers_test.clj** (7 tests)

- Integration-style lifecycle testing
- Error condition coverage
- Input validation tests

### Adequate Quality

**queries_test.clj** (9 tests)

- Temporal query coverage is good
- Missing: many query functions untested

**ingest_test.clj** (9 tests)

- Transformation pipeline covered
- Missing: execute-daily-work-item! (mocked only)

---

## Generator Assessment

### Strengths

The generators in `generators.clj` are well-designed:

```clojure
;; Explicit NaN/infinity prevention
(gen/double* {:min 10.0 :max 1000.0 :NaN? false :infinite? false})

;; Realistic financial ranges
(def gen-iv (gen/double* {:min 0.05 :max 2.0 ...}))  ;; 5-200% IV
(def gen-delta (gen/double* {:min -1.0 :max 1.0 ...}))

;; Composite generators maintain constraints
(def gen-valid-option-quote
  (gen/let [bid gen-option-price
            spread (gen/double* {:min 0.01 :max 0.5 ...})]
    {:quote/bid bid
     :quote/ask (+ bid (* bid spread))}))  ;; ask > bid always

```

### Weaknesses

1. **No extreme value testing** - generators avoid boundaries
2. **No negative test generators** - need invalid data generators for validation testing
3. **Schema generators in two places** - `generators.clj` and `db/schema.clj` both define generators

---

## Spec Coverage Assessment

### Current State

- Malli schemas exist for all domain types (`db/schema.clj`)
- Validation enforced in data ingestion pipeline
- **NOT** enforced at:
  - API request boundaries (handlers parse JSON, no schema validation)
  - Function inputs (no pre/post conditions)
  - Database writes (schema exists but not used)

### Recommendations

1. **Add request validation middleware** using malli coercion
2. **Add function specs** for critical calculation functions
3. **Use schema at DB boundary** - validate before `ingest-quotes!`

---

## Recommendations Summary

### Immediate Actions (P0)

1. Write tests for `dsl/primitives.clj` - especially percentile calculations
2. Test edge cases: empty data, single values, boundary percentiles
3. Add property tests for IV rank (always 0.0-1.0)

### Next Sprint (P1)

1. Write tests for `db/transactions.clj` - ID determinism
2. Write tests for `dsl/executor.clj` - security validation
3. Write tests for `agent/analysis.clj` - signal categorization

### Backlog (P2)

1. Expand `db/queries.clj` test coverage
2. Add `web/jobs.clj` unit tests
3. Add `web/sse.clj` tests
4. Add boundary validation with malli

---

## Phase 2 Plan (2024-12-05)

### Decision: Hybrid Approach (Tests-First, Specs Later)

After researching malli instrumentation patterns, we decided on **tests-first** for Phase 2:

**Rationale:**

1. Several **bugs need fixing first** (vanna hardcoded 30 DTE, term-structure-slope wrong calc)
2. Tests catch **calculation correctness** issues that specs can't
3. Specs alone don't verify "is the percentile formula correct?"
4. We can add specs later for dev-time safety

**Deferred:**

- Malli function specs (`m/=>`) - add in Phase 3
- `malli.dev/start!` in user.clj - add in Phase 3

### Implementation Order

#### Step 1: Test Helper Functions (Private)

Create `test/ml_options/dsl/primitives_test.clj`:

```clojure
;; Test calculate-percentile and calculate-percentile-rank
;; These are private but we can test via #'ns/fn

(deftest calculate-percentile-test
  (testing "boundary percentiles"
    (is (= 1 (#'p/calculate-percentile [1 2 3 4 5] 0)))
    (is (= 5 (#'p/calculate-percentile [1 2 3 4 5] 100))))
  (testing "empty returns nil"
    (is (nil? (#'p/calculate-percentile [] 50))))
  (testing "single value"
    (is (= 42 (#'p/calculate-percentile [42] 50)))))

(deftest calculate-percentile-rank-test
  (testing "known distribution"
    (is (= 0.6 (#'p/calculate-percentile-rank [1 2 3 4 5] 3))))
  (testing "empty returns nil"
    (is (nil? (#'p/calculate-percentile-rank [] 5)))))

```

#### Step 2: Property Tests for Invariants

```clojure
(defspec percentile-rank-always-in-range 100
  (prop/for-all [values (gen/vector (gen/double* {:min 0 :max 100 :NaN? false :infinite? false}) 1 50)]
    (let [current (first values)
          rank (#'p/calculate-percentile-rank values current)]
      (and (>= rank 0.0) (<= rank 1.0)))))

```

#### Step 3: Integration Tests with Test XTDB

```clojure
(deftest iv-rank-integration-test
  (tu/with-test-node [node]
    (tu/insert-options! node test-data)
    (is (number? (p/iv-rank node "TEST")))
    (is (<= 0.0 (p/iv-rank node "TEST") 1.0))))

```

### Files to Create/Modify

| File | Action |
|------|--------|
| `test/ml_options/dsl/primitives_test.clj` | **CREATE** - Main test file |
| `test/ml_options/test_utils.clj` | **MODIFY** - Add `with-test-node` helper if needed |

### Success Criteria

1. [x] Helper functions have unit tests (calculate-percentile, calculate-percentile-rank)
2. [x] Property tests verify invariants (rank always 0.0-1.0)
3. [x] Integration tests for iv-rank with real XTDB node (no-data cases)
4. [x] All tests pass: `clj -M:test -m kaocha.runner` (178 tests, 0 failures)
5. [x] Bugs documented (vanna, term-structure-slope) in research/primitives-analysis.md

### Research Documents

See `research/` directory:

- `malli-instrumentation.md` - How m/=> and mi/instrument! work
- `current-spec-patterns.md` - Existing patterns in codebase
- `primitives-analysis.md` - Detailed function analysis with bugs
