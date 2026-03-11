# Malli Function Instrumentation Research

**Date:** 2024-12-05
**Sources:** Context7 (metosin/malli), Gemini Pro, malli docs

---

## Summary

Malli function instrumentation provides **dev-time runtime validation** for function inputs and outputs. It's complementary to tests, not a replacement.

---

## How `m/=>` Function Schemas Work

### Syntax

```clojure
(require '[malli.core :as m])

;; Define function normally
(defn pow [x n]
  (Math/pow x n))

;; Register schema separately
(m/=> pow [:=> [:cat :int :int] :double])
```

**Schema structure:**

- `[:=> input-schema output-schema]` - function schema
- `[:cat arg1-schema arg2-schema ...]` - sequential arguments
- Return type comes last

### Multi-arity Functions

```clojure
(m/=> my-fn
  [:function
   [:=> [:cat :int] :int]           ;; 1-arity
   [:=> [:cat :int :int] :int]])    ;; 2-arity
```

### Inline Schema (Alternative)

```clojure
(require '[malli.experimental :as mx])

(mx/defn times :- :int
  "x times y"
  [x :- :int, y :- :int]
  (* x y))
```

---

## How `mi/instrument!` Works

### Basic Usage

```clojure
(require '[malli.instrument :as mi])

(mi/instrument!)  ;; Instruments ALL functions with m/=> schemas
```

### What Happens Under the Hood

1. **Scanning** - finds all vars with registered schemas
2. **Wrapping** - uses `alter-var-root` to wrap functions
3. **Interception** - validates inputs before call, outputs after
4. **Exceptions** - throws `malli.core/invalid-input` or `malli.core/invalid-output`

### Selective Instrumentation

```clojure
;; Only specific namespaces
(mi/instrument! {:ns #{'ml-options.dsl.primitives}})

;; Unstrument when done
(mi/unstrument!)
```

---

## How `malli.dev/start!` Works (Auto-Instrumentation)

### Setup in dev/user.clj

```clojure
(ns user
  (:require [malli.dev :as dev]
            [malli.dev.pretty :as pretty]))

;; Start with pretty error reporting
(dev/start! {:report (pretty/reporter)})
```

### What It Does

1. **Watches for changes** - detects when functions are redefined
2. **Auto-reinstruments** - applies schemas to new definitions
3. **Pretty errors** - formats validation failures readably
4. **Perfect for Integrant** - reinstruments after `(reset)`

### Stop When Done

```clojure
(dev/stop!)
```

---

## Pattern for This Project

### 1. Define Custom Schemas (db/schema.clj)

```clojure
;; Add to existing registry
(def PercentileRank [:double {:min 0.0 :max 1.0}])
(def Percentile [:int {:min 0 :max 100}])
(def Ticker [:string {:min 1 :max 10}])
(def PositiveDouble [:double {:min 0.0}])
```

### 2. Add Function Specs (dsl/primitives.clj)

```clojure
(require '[malli.core :as m])

(defn- calculate-percentile [values p]
  ...)

(m/=> calculate-percentile
  [:=> [:cat [:sequential :double] Percentile]
       [:maybe :double]])

(defn iv-rank [node ticker & {:keys [as-of lookback]}]
  ...)

(m/=> iv-rank
  [:=> [:cat :some :string [:* :any]]  ;; node, ticker, opts
       [:maybe PercentileRank]])
```

### 3. Enable in Dev (env/dev/clj/user.clj)

```clojure
(defn go []
  (integrant.repl/go)
  ;; After system starts, enable instrumentation
  (require '[malli.dev :as mdev])
  (mdev/start!))
```

---

## Pros vs Cons

### Pros

| Benefit | Description |
|---------|-------------|
| Self-documenting | Schema IS the documentation |
| REPL safety | Catches errors during development |
| Reduced test boilerplate | Don't need tests for type/range violations |
| clj-kondo integration | Static linting for schemas |
| Generative testing | Can generate test data from schemas |

### Cons

| Drawback | Description |
|----------|-------------|
| Runtime only | Not compile-time (unlike typed Clojure) |
| Opaque types | Can't express "XTDB node" well (just `:some`) |
| Not logic tests | Can't catch "wrong calculation" bugs |
| Overhead | Validation has cost (dev-only is fine) |
| Maintenance | Schemas must stay in sync with code |

---

## Recommendation

**Use BOTH specs AND tests** - they're complementary:

| Concern | Tool |
|---------|------|
| Type/range violations | Malli specs (instrumented) |
| Edge cases (nil, empty) | Property-based tests |
| Calculation correctness | Unit tests with known values |
| Integration behavior | Integration tests with XTDB |

### Suggested Order

1. Add custom schemas to `db/schema.clj`
2. Add `m/=>` specs to `dsl/primitives.clj`
3. Enable `malli.dev/start!` in user.clj
4. Write tests for calculation correctness
5. Write property tests for invariants

---

## References

- [Malli Function Schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)
- [Malli Dev](https://github.com/metosin/malli#development-instrumentation)
