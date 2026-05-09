---
type: research
status: draft
tags: [prd, research]
---
# Current Spec and Validation Patterns

**Analyzed:** db/schema.clj, data/validation.clj, dev/user.clj

---

## Summary

The codebase has **extensive data schemas** but **zero function specs**. Validation happens at data ingestion boundaries only.

---

## Existing Malli Schemas (db/schema.clj)

### Domain Types Defined

| Schema | Purpose |
|--------|---------|
| `OptionQuote` | Full option quote with greeks |
| `Greeks` | Delta, gamma, theta, vega, rho |
| `IVSurface` | IV surface snapshot |
| `TradingSignal` | Agent-generated trading signals |
| `BulkProgress` | Import progress tracking |

### Custom Generators

The schema file includes **custom generators** for property-based testing:

```clojure
;; Example from schema.clj
(def gen-iv (gen/double* {:min 0.05 :max 2.0 :NaN? false :infinite? false}))
(def gen-delta (gen/double* {:min -1.0 :max 1.0 :NaN? false :infinite? false}))

```

These generators are used in `test/ml_options/db/schema_test.clj`.

### Registry Setup

```clojure
(def registry
  (merge
   (m/default-schemas)
   {:option-quote OptionQuote
    :greeks Greeks
    ...}))

```

---

## Current Validation Patterns (data/validation.clj)

### Where Validation Happens

```
External API → validation.clj → XTDB
                     ↓
              filter-valid-records
                     ↓
              (only valid data stored)

```

### Key Functions

| Function | Purpose |
|----------|---------|
| `validate-record` | Check single record against schema |
| `filter-valid-records` | Remove invalid records from batch |
| `explain-invalid` | Get human-readable error for invalid data |

### Validation Logic

```clojure
(defn validate-record [record]
  (m/validate OptionQuote record))

(defn filter-valid-records [records]
  (let [{valid true invalid false} (group-by validate-record records)]
    (when (seq invalid)
      (log/warn "Filtered" (count invalid) "invalid records"))
    valid))

```

---

## What's NOT Validated

### Function Inputs/Outputs

**No `m/=>` schemas exist anywhere:**

```bash
$ grep -r "m/=>" src/
(no results)

```

### DSL Primitives

Functions like `iv-rank`, `put-call-ratio`, `vanna` have:

- No input validation
- No output validation
- No pre/post conditions
- Only docstrings describing expected inputs

### API Request Bodies

HTTP handlers parse JSON but don't validate against schemas:

```clojure
;; Current pattern in handlers.clj
(let [body (parse-json request)]
  (start-import! body))  ;; No validation!

```

---

## Instrumentation Status

### Current State

```bash
$ grep -r "instrument!" src/
(no results)

$ grep -r "malli.dev" src/ dev/
(no results)

```

**No instrumentation is set up.**

### dev/user.clj

The user namespace has Integrant lifecycle but no malli.dev:

```clojure
(ns user
  (:require [integrant.repl :refer [go halt reset]]
            ...))
;; No malli.dev/start! anywhere

```

---

## Recommendations

### Option A: Tests-First (Simple)

Just write tests for `dsl/primitives.clj`. No schema changes needed.

**Pros:** Familiar, no new patterns
**Cons:** More verbose, doesn't catch REPL errors

### Option B: Specs-First with Dev Instrumentation (Recommended)

Add function specs and enable dev-time instrumentation.

**Implementation:**

1. Add to `db/schema.clj`:

```clojure
(def PercentileRank [:double {:min 0.0 :max 1.0}])
(def Percentile [:int {:min 0 :max 100}])

```

1. Add to `dsl/primitives.clj`:

```clojure
(m/=> iv-rank [:=> [:cat :some :string [:* :any]] [:maybe PercentileRank]])

```

1. Add to `env/dev/clj/user.clj`:

```clojure
(require '[malli.dev :as mdev])
(mdev/start!)

```

**Pros:**

- Catches errors in REPL
- Self-documenting
- Zero prod overhead

**Cons:**

- New pattern to learn
- Still need tests for correctness

### Option C: Hybrid (Best)

Add specs for type/range validation, write tests for calculation correctness.

**Priority:**

1. Specs for input validation (catches nil, wrong types)
2. Property tests for invariants (result in [0,1])
3. Unit tests for known values (percentile of [1,2,3,4,5] at 50 = 3)

---

## Checklist for Adding Specs

- [ ] Add custom schemas to `db/schema.clj` registry
- [ ] Add `m/=>` specs to target functions
- [ ] Add `malli.dev/start!` to user.clj go function
- [ ] Test in REPL that bad inputs throw
- [ ] Write tests for calculation correctness
- [ ] Document the pattern in CLAUDE.md
