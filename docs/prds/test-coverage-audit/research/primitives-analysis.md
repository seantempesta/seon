# DSL Primitives Analysis

**Date:** 2024-12-05
**File:** src/ml_options/dsl/primitives.clj

---

## Summary

This file contains **financial calculation primitives** for the trading agent. Several have **critical bugs** (hardcoded values, wrong calculations). All need tests.

---

## Critical Issues Found

### HIGH PRIORITY - Bugs to Fix

| Function | Line | Issue |
|----------|------|-------|
| `vanna` | 313 | Hardcoded `days-to-expiry = 30` |
| `term-structure-slope` | 165 | Divides by count, not time span |
| `implied-correlation` | 322-346 | Hardcoded volatilities (placeholder) |
| `iv-rank`/`iv-percentile` | 63-139 | `lookback` parameter ignored |

### MEDIUM PRIORITY - Edge Cases

| Function | Issue |
|----------|-------|
| `calculate-percentile` | No validation that p is in [0, 100] |
| `gamma-rent` | Defaults theta to -0.01 (hides data issues) |
| `skew-index` | Returns 0.0 for both "no data" and "skew is zero" |
| `put-call-ratio` | Invalid metric defaults to :volume silently |

---

## Function-by-Function Analysis

### calculate-percentile (lines 29-43)

**Purpose:** Calculate the p-th percentile of a sequence of values

**Inputs:**
- `values` - sequence of numbers (should be non-empty)
- `p` - percentile (should be 0-100)

**Output:** The value at the p-th percentile, or nil if empty

**Invariants:**
- Result should be within [min(values), max(values)]
- p=0 should return min, p=100 should return max
- Monotonic: higher p means >= result

**Edge cases:**
- Empty sequence → returns nil
- Single value → returns that value for any p
- p < 0 or p > 100 → undefined behavior (BUG)

**Bug risks:**
- `idx` calculation uses floor, no interpolation
- p=100 may be off-by-one

**Spec suggestion:**
```clojure
(m/=> calculate-percentile
  [:=> [:cat [:sequential :double] [:int {:min 0 :max 100}]]
       [:maybe :double]])
```

**Test cases:**
1. `[1 2 3 4 5]` at p=0 → 1
2. `[1 2 3 4 5]` at p=100 → 5
3. `[1 2 3 4 5]` at p=50 → 3
4. `[]` at any p → nil
5. `[42]` at any p → 42

---

### calculate-percentile-rank (lines 45-57)

**Purpose:** Calculate what percentile a value is within a distribution

**Inputs:**
- `values` - sequence of numbers
- `current-value` - the value to rank

**Output:** Percentile rank as decimal [0.0, 1.0]

**Invariants:**
- Result always in [0.0, 1.0]
- Minimum value → near 0.0
- Maximum value → 1.0
- Monotonic with current-value

**Edge cases:**
- Empty values → nil
- nil current-value → nil
- current-value below all values → low rank
- current-value above all values → 1.0

**Bug risks:**
- Uses `<=` not `<` (affects boundary behavior)
- Division could theoretically fail if count is 0 (guarded by seq check)

**Spec suggestion:**
```clojure
(m/=> calculate-percentile-rank
  [:=> [:cat [:sequential :double] [:maybe :double]]
       [:maybe [:double {:min 0.0 :max 1.0}]]])
```

**Test cases:**
1. `[1 2 3 4 5]` with value 3 → 0.6 (3 values <= 3)
2. `[1 2 3 4 5]` with value 1 → 0.2
3. `[1 2 3 4 5]` with value 5 → 1.0
4. `[1 2 3 4 5]` with value 0 → 0.0
5. `[]` with any value → nil

---

### iv-rank (lines 63-97)

**Purpose:** Calculate IV rank (current IV's percentile in historical distribution)

**Inputs:**
- `node` - XTDB node
- `ticker` - stock symbol
- `as-of` - optional temporal parameter
- `lookback` - optional lookback period (IGNORED - BUG)

**Output:** Percentile rank [0.0, 1.0] or nil

**Invariants:**
- Result in [0.0, 1.0]
- Higher current IV → higher rank
- Should use lookback period (currently doesn't)

**Edge cases:**
- No historical data → nil
- No current IV → nil
- Invalid ticker → nil or empty result

**Bug risks:**
- `lookback` parameter is accepted but not used
- Queries ALL history instead of lookback window

**Spec suggestion:**
```clojure
(m/=> iv-rank
  [:=> [:cat :some :string [:* :any]]
       [:maybe [:double {:min 0.0 :max 1.0}]]])
```

**Test cases:**
1. Known historical IVs [0.2, 0.3, 0.4], current 0.3 → 0.67
2. No data → nil
3. Current IV at historical max → 1.0
4. Current IV at historical min → low value

---

### term-structure-slope (lines 141-167)

**Purpose:** Calculate slope of IV term structure (near vs far expiry)

**Inputs:**
- `node` - XTDB node
- `ticker` - stock symbol
- `as-of` - optional temporal parameter

**Output:** Slope value (positive = contango, negative = backwardation)

**Invariants:**
- Positive when far IV > near IV
- Zero when equal
- Should be normalized by time

**Edge cases:**
- Less than 2 expirations → nil
- All same IV → 0.0
- Missing IV data for some expirations

**Bug risks:**
- **MAJOR BUG:** Divides by count of expirations, not time span
- Slope units are meaningless without time normalization

**Spec suggestion:**
```clojure
(m/=> term-structure-slope
  [:=> [:cat :some :string [:* :any]]
       [:maybe :double]])
```

**Test cases:**
1. Near IV 0.20, Far IV 0.30, 30 days apart → positive slope
2. Equal IVs → 0.0
3. Single expiration → nil

---

### put-call-ratio (lines 200-228)

**Purpose:** Calculate ratio of put to call activity

**Inputs:**
- `node` - XTDB node
- `ticker` - stock symbol
- `metric` - :volume or :open-interest (default :volume)
- `as-of` - optional temporal parameter

**Output:** Ratio (puts/calls), 0.0 if no calls

**Invariants:**
- Result >= 0.0
- Higher put activity → higher ratio
- 1.0 means equal put/call activity

**Edge cases:**
- No calls → returns 0.0 (questionable - should be infinity?)
- No puts → 0.0
- Invalid metric → defaults to :volume (silent - should error)

**Bug risks:**
- Returns 0.0 for "no calls" which could be confused with "equal activity"
- Invalid metric silently defaults instead of erroring

**Spec suggestion:**
```clojure
(m/=> put-call-ratio
  [:=> [:cat :some :string [:* :any]]
       [:double {:min 0.0}]])
```

**Test cases:**
1. 2000 puts, 1000 calls → 2.0
2. 0 puts, 1000 calls → 0.0
3. 1000 puts, 0 calls → 0.0 (or should error?)
4. Invalid metric → should throw

---

### vanna (lines 293-316)

**Purpose:** Calculate vanna (sensitivity of delta to IV changes)

**Inputs:**
- `node` - XTDB node
- `ticker` - stock symbol
- `as-of` - optional temporal parameter

**Output:** Vanna value

**Invariants:**
- Should vary with time to expiry
- Should use actual expiry dates

**Edge cases:**
- No options data → nil
- Missing delta/vega → calculation fails

**Bug risks:**
- **CRITICAL BUG:** Line 313 hardcodes `days-to-expiry = 30`
- Vanna calculation will be wrong for any option not exactly 30 DTE
- TODO comment acknowledges this needs fixing

**Spec suggestion:**
```clojure
(m/=> vanna
  [:=> [:cat :some :string [:* :any]]
       [:maybe :double]])
```

**Test cases:**
1. With actual expiry calculation (after fix)
2. Near expiry vs far expiry should differ
3. No data → nil

---

### implied-correlation (lines 322-346)

**Purpose:** Calculate implied correlation from index options

**Inputs:**
- `node` - XTDB node
- `ticker` - index symbol (SPX, etc.)
- `as-of` - optional temporal parameter

**Output:** Implied correlation value

**Bug risks:**
- **PLACEHOLDER CODE:** Lines 340-342 hardcode:
  - `index-vol = 0.20`
  - `component-vols = 0.25`
- This function is **unusable for real trading**

**Status:** Not testable until implemented properly

---

## Testing Strategy

### Phase 1: Unit Tests for Helpers

Test `calculate-percentile` and `calculate-percentile-rank` in isolation:
- Edge cases (empty, single value, boundaries)
- Property tests (monotonicity, range constraints)

### Phase 2: Integration Tests with Mock Data

Test primitives with known XTDB data:
- Insert known options data
- Verify calculations match expected values
- Test temporal (:as-of) behavior

### Phase 3: Fix Bugs First

Before comprehensive testing:
1. Fix `vanna` hardcoded days-to-expiry
2. Fix `term-structure-slope` to use time span
3. Decide on `lookback` parameter behavior
4. Make `implied-correlation` return nil or throw until implemented

---

## Recommended Test File Structure

```clojure
(ns ml-options.dsl.primitives-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [ml-options.dsl.primitives :as p]
            [ml-options.test-utils :as tu]))

;; ===== Helper Function Tests =====

(deftest calculate-percentile-test
  (testing "boundary percentiles"
    ...)
  (testing "empty input"
    ...)
  (testing "single value"
    ...))

(defspec percentile-monotonic 100
  (prop/for-all [values (gen/vector (gen/double* {:min 0 :max 100 :NaN? false}) 1 50)]
    ...))

;; ===== Integration Tests =====

(deftest iv-rank-integration-test
  (tu/with-test-db [node]
    (tu/insert-test-options! node [...])
    (testing "known distribution"
      (is (= 0.5 (p/iv-rank node "TEST"))))))
```
