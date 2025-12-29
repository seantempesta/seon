# Flaky Property-Based Test Investigation

## Executive Summary

The test `ml-options.db.schema-test/custom-generators-produce-valid-data` is flaky because **the `spread` generator can produce NaN values**, which then propagate to `:quote/ask` when calculating `(+ bid (* bid spread))`.

**Root Cause**: Missing `:NaN? false :infinite? false` flags in the `spread` generator at line 132 of `test/ml_options/generators.clj`.

**Failure Rate**: Approximately 3 out of 15 runs (~20% failure rate).

---

## Test Failure Details

### Error Symptoms
```
Quote should be valid: {..., :quote/ask ##NaN, ...}
:errors ({:path [:quote/ask], :in [:quote/ask], :schema [:double {:min 0.0, :max 1.0}], :value ##NaN})
```

The schema correctly rejects NaN values because:
1. The schema defines `:quote/ask [:double {:min 0.0 :max 1000.0}]`
2. NaN is not within any numeric range
3. Production code never produces NaN values

### Example Failing Values
From test run failures:
- `{:quote/bid 0.59375, :quote/ask ##NaN, ...}`
- `{:quote/bid 1.125, :quote/ask ##NaN, ...}`
- `{:quote/bid 0.625, :quote/ask ##NaN, ...}`

---

## Root Cause Analysis

### The Problem Code

**Location**: `test/ml_options/generators.clj` lines 132-143

```clojure
(def gen-valid-option-quote
  (gen/let [ticker gen-ticker
            occ gen-occ-symbol
            strike gen-strike-price
            opt-type gen-option-type
            expiry gen-expiry-instant
            quote-instant gen-historical-instant
            bid gen-option-price
            spread (gen/double* {:min 0.01 :max 0.5})  ; <-- BUG: Missing NaN/Inf flags
            iv gen-iv
            greeks gen-greeks
            volume (gen/choose 0 100000)]
    {:xt/id (str occ "-" (.toString quote-instant))
     :asset/ticker ticker
     :option/id occ
     :option/strike strike
     :option/type opt-type
     :option/expiry expiry
     :quote/bid bid
     :quote/ask (+ bid (* bid spread))  ; <-- NaN propagates here
     :quote/iv iv
     :greeks/delta (:delta greeks)
     :greeks/gamma (:gamma greeks)
     :greeks/vega (:vega greeks)
     :greeks/theta (:theta greeks)
     :market/volume volume}))
```

### Why It's Inconsistent (Flaky)

`gen/double*` without `:NaN? false` can occasionally generate:
- `##NaN` (Not a Number)
- `##Inf` (Positive Infinity)
- `##-Inf` (Negative Infinity)

The probability is low but non-zero. When any of these special values are used in arithmetic:
```clojure
(+ bid (* bid ##NaN))  ;=> ##NaN
(+ bid (* bid ##Inf))  ;=> ##Inf
```

### Why Other Generators Work

Compare to properly configured generators in the same file:

```clojure
;; Line 16 - CORRECT
(def gen-spot-price
  (gen/double* {:min 10.0 :max 1000.0 :NaN? false :infinite? false}))

;; Line 24 - CORRECT
(def gen-option-price
  (gen/double* {:min 0.01 :max 500.0 :NaN? false :infinite? false}))

;; Line 32 - CORRECT
(def gen-iv
  (gen/double* {:min 0.05 :max 2.0 :NaN? false :infinite? false}))

;; Line 132 - BROKEN (missing flags)
spread (gen/double* {:min 0.01 :max 0.5})
```

All the standalone generators properly exclude NaN/Inf, but the inline `spread` generator does not.

---

## Why This Wasn't Caught Earlier

1. **Low probability** - NaN/Inf generation is relatively rare in bounded double generators
2. **Isolation vs. Full Suite** - Running the test in isolation with only 20 samples might not hit the edge case
3. **Property-based testing by design** - The whole point is to find edge cases like this!
4. **Previous fix was incomplete** - Commit `17bcc4b` fixed the UUID→string issue but didn't audit all generators for NaN/Inf safety

---

## Reproduction Steps

Run the schema test suite multiple times:
```bash
for i in {1..15}; do
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  clj -M:test -m kaocha.runner --focus ml-options.db.schema-test
done
```

Expected: ~3 failures out of 15 runs showing `:quote/ask ##NaN`

---

## Fix Implementation

### Change Required

**File**: `test/ml_options/generators.clj`
**Line**: 132

**Before**:
```clojure
spread (gen/double* {:min 0.01 :max 0.5})
```

**After**:
```clojure
spread (gen/double* {:min 0.01 :max 0.5 :NaN? false :infinite? false})
```

### Why This Fix Is Correct

1. **Matches production reality** - Real market data never has NaN spreads
2. **Matches other generators** - Consistent with `gen-spot-price`, `gen-option-price`, etc.
3. **Schema compatibility** - The schema correctly rejects NaN, so the generator should never produce it
4. **Minimal change** - Only adds missing safety flags, no logic changes

### Testing the Fix

After applying the fix, run:
```bash
# Should pass consistently
for i in {1..20}; do
  clj -M:test -m kaocha.runner --focus ml-options.db.schema-test/custom-generators-produce-valid-data
done
```

All 20 runs should pass with 0 failures.

---

## Related Audit

### Other Inline Generators to Check

Search for other inline `gen/double*` calls that might have the same issue:

```bash
grep -n "gen/double\*" test/ml_options/generators.clj
```

Found instances:
- Line 132: `spread` - **BROKEN** (needs fix)
- Line 160: `atm-delta` in `gen-straddle-candidate` - **BROKEN** (needs fix)
- Line 161: `low-iv` in `gen-straddle-candidate` - **BROKEN** (needs fix)
- Line 173: `high-gamma` in `gen-gamma-scalp-candidate` - **BROKEN** (needs fix)
- Line 174: `low-theta` in `gen-gamma-scalp-candidate` - **BROKEN** (needs fix)
- Line 183: `weights` in `gen-dispersion-components` - **BROKEN** (needs fix)

**All inline generators need the NaN/Inf safety flags.**

---

## Code References

| File | Line | Description |
|------|------|-------------|
| `test/ml_options/db/schema_test.clj` | 85-95 | Flaky test definition |
| `test/ml_options/generators.clj` | 132 | **BUG**: Missing NaN/Inf flags on `spread` |
| `test/ml_options/generators.clj` | 16-60 | Correct generator examples with safety flags |
| `src/ml_options/db/schema.clj` | 82 | Schema defining `:quote/ask` bounds (correctly rejects NaN) |

---

## Lessons Learned

### Property-Based Testing Best Practices

1. **Always specify `:NaN? false :infinite? false`** for `gen/double*` unless you explicitly want to test NaN/Inf handling
2. **Audit all generators** when adding NaN/Inf protection - check both standalone and inline generators
3. **Run property tests multiple times** during development to catch low-probability edge cases
4. **Generator failures are legitimate bugs** - if a generator produces invalid data, fix the generator, not the schema

### Why This Is NOT a False Positive

Some might argue "just make the schema accept NaN" - this is wrong because:
1. **Production never produces NaN** - Real OPRA feed data doesn't have NaN bid/ask prices
2. **Schemas document reality** - The schema correctly models the domain
3. **Tests should match production** - Generators should produce realistic test data
4. **Downstream bugs** - Allowing NaN in tests can mask real calculation errors

---

## Conclusion

This is a **generator bug, not a test isolation issue or schema bug**. The fix is straightforward: add `:NaN? false :infinite? false` to all inline `gen/double*` calls.

The flakiness demonstrates the value of property-based testing - it found a real edge case that could theoretically occur if calculation code ever produced NaN values. The fix makes the generators produce only valid, realistic financial data.

---

## Fix Verification

### Changes Applied

Fixed all 6 inline `gen/double*` generators in `test/ml_options/generators.clj`:

1. **Line 132**: `spread` in `gen-valid-option-quote` - ✅ FIXED
2. **Line 160**: `atm-delta` in `gen-straddle-candidate` - ✅ FIXED
3. **Line 161**: `low-iv` in `gen-straddle-candidate` - ✅ FIXED
4. **Line 173**: `high-gamma` in `gen-gamma-scalp-candidate` - ✅ FIXED
5. **Line 174**: `low-theta` in `gen-gamma-scalp-candidate` - ✅ FIXED
6. **Line 183**: `weights` in `gen-dispersion-components` - ✅ FIXED

Also created `test/ml_options/generators_test.clj` to:
- Satisfy auto-test hook requirements
- Provide basic sanity checks for generators
- Explicitly verify no NaN values are generated

### Test Results

**Before fix**: ~20% failure rate (3 failures out of 15 runs)
**After fix**: 0% failure rate

Ran multiple verification passes:
- 10 consecutive schema test runs: ✅ 0 failures
- 15 consecutive schema test runs: ✅ 0 failures
- Full test suite: ✅ 168 tests, 758 assertions, 0 failures

The test is **no longer flaky**.
