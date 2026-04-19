---
type: research
status: completed
tags: [research, archive]
---

# Malli Function Schema Best Practices

Research findings on defining Malli function schemas for AI-assisted development with automatic verification.

## Executive Summary

**Recommended Approach:** Use `m/=>` macro with namespaced schema keywords from a global mutable registry.

This provides:
1. **Clear contracts** - AI agents can parse and understand function interfaces
2. **Automatic verification** - Works with `mi/check` and `mg/check` for generative testing
3. **Runtime instrumentation** - `malli.dev/start!` or `mi/instrument!` for runtime validation
4. **Maintainability** - Centralized schema definitions, easy refactoring

---

## Comparison of All Approaches

### 1. `m/=>` Macro (Recommended)

**Declaration:**

```clojure
(defn process-order [user-id cart]
  ;; implementation
  )
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])

```

**Pros:**
- Immediately registered in `(m/function-schemas)` - no extra step needed
- Works with `mi/check`, `mi/instrument!`, `malli.dev/start!`
- Schema is data - can be queried, transformed, serialized
- Keeps function definition clean
- Supports multi-arity via `:function` schema

**Cons:**
- Schema separated from function definition (can be mitigated by placing immediately after defn)
- Requires `[malli.core :as m]` dependency

**REPL Verification:**

```clojure
(get-in (m/function-schemas) ['my.ns 'process-order])
;; => {:schema #malli.core/Schema, :ns my.ns, :name process-order}

```

### 2. `:malli/schema` Metadata on defn

**Declaration:**

```clojure
(defn process-order
  "Processes an order"
  {:malli/schema [:=> [:cat :user/id :order/cart] :order/result]}
  [user-id cart]
  ;; implementation
  )

```

**Pros:**
- Schema co-located with function
- No dependency on Malli in the function file (can use string/keyword, resolve later)
- Visible in var metadata

**Cons:**
- **Requires `(mi/collect!)` to register** - not automatic
- Collection happens at specific point in time - new functions need re-collection
- Less discoverable (hidden in metadata)

**REPL Verification:**

```clojure
(:malli/schema (meta #'process-order))
;; => [:=> [:cat :user/id :order/cart] :order/result]

;; MUST call collect! first:
(mi/collect!)
(get-in (m/function-schemas) ['my.ns 'process-order])

```

### 3. `mx/defn` Inline Schema (malli.experimental)

**Declaration:**

```clojure
(require '[malli.experimental :as mx])

(mx/defn process-order :- :order/result
  "Processes an order"
  [user-id :- :user/id, cart :- :order/cart]
  ;; implementation
  )

```

**Pros:**
- Most readable - schema inline with parameters
- Immediately registered (like `m/=>`)
- Similar to TypeScript/Java annotations

**Cons:**
- Requires `malli.experimental` namespace (not in core)
- More verbose for complex schemas
- Harder for AI to parse (schema distributed across definition)
- Uses `:schema` key in metadata instead of `:malli/schema`

**REPL Verification:**

```clojure
(:schema (meta #'process-order))
;; => [:=> [:cat :user/id :order/cart] :order/result]

```

### 4. `:pre`/`:post` with Malli Validators

**Declaration:**

```clojure
(defn process-order [user-id cart]
  {:pre [(m/validate :user/id user-id)
         (m/validate :order/cart cart)]
   :post [(m/validate :order/result %)]}
  ;; implementation
  )

```

**Pros:**
- Built into Clojure
- Runtime validation always on
- Can add custom logic beyond schema validation

**Cons:**
- **Not discoverable** - no central registry
- **No generative testing** - can't use `mi/check` or `mg/check`
- **No instrumentation control** - always on, can't disable
- Throws `AssertionError` not informative Malli exceptions
- Schema duplicated (not DRY if you also want it elsewhere)

**Verdict:** Not recommended for AI-assisted development.

### 5. Per-Arity Metadata

**Declaration:**

```clojure
(defn process-order
  (^{:malli/schema [:=> [:cat :user/id] :order/result]}
   [user-id]
   ;; single-arity implementation
   )
  (^{:malli/schema [:=> [:cat :user/id :order/cart] :order/result]}
   [user-id cart]
   ;; two-arity implementation
   ))

```

**Pros:**
- Each arity has its own schema

**Cons:**
- Very verbose
- Must have schema on ALL arities or it fails
- Hard to read

**Verdict:** Avoid. Use `:function` multi-arity schema instead.

---

## Schema Declaration Location Comparison

### Option A: Immediately After defn (Recommended)

```clojure
(defn process-order [user-id cart]
  (let [total (calculate-total cart)]
    {:order-id (random-uuid)
     :total total}))
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])

```

**Why this is best:**
- Schema is immediately visible after function
- Clear visual pairing
- Easy to update when function signature changes
- AI agents can parse: "schema follows function definition"

### Option B: Schema in Registry, Reference by Keyword

```clojure
;; In schema.clj
(def function-schemas
  {:order/process-order [:=> [:cat :user/id :order/cart] :order/result]})

;; In core.clj
(defn process-order [user-id cart] ...)
(m/=> process-order :order/process-order)  ;; DOES NOT WORK

```

**Verdict:** `m/=>` does not accept a keyword reference. You must pass the full schema form.

However, you CAN reference registered schemas WITHIN the schema:

```clojure
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])
;;                        ^^^^^^^^^^ ^^^^^^^^^^ ^^^^^^^^^^^^
;;                        These are looked up in the registry

```

### Option C: All Schemas in Schema Namespace

```clojure
;; seon.trading.schema
(ns seon.trading.schema
  (:require [malli.core :as m]))

(def schemas
  {:user/id :uuid
   :order/cart [:vector :order/item]
   :order/result [:map [:order-id :uuid] [:total :double]]})

;; seon.trading.core
(ns seon.trading.core
  (:require [malli.core :as m]
            [seon.trading.schema :as schema]))

(defn process-order [user-id cart] ...)
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])

```

**Pros:**
- Centralized schema definitions
- Schemas can be shared across namespaces
- Easy to generate documentation

**Cons:**
- Extra file to maintain
- Need to ensure registry is loaded before functions

---

## Namespaced Schemas for Clarity

### Setting Up a Global Mutable Registry

```clojure
;; seon.schema (loaded early in startup)
(ns seon.schema
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(defonce *schemas (atom {}))

(mr/set-default-registry!
  (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry *schemas)))

(defn register! [& schemas]
  (doseq [[k v] (partition 2 schemas)]
    (swap! *schemas assoc k v)))

```

### Domain Schema Registration

```clojure
;; seon.trading.schema
(ns seon.trading.schema
  (:require [seon.schema :as schema]))

(schema/register!
  :user/id :uuid
  :user/name [:string {:min 1 :max 200}]

  :order/item [:map
               [:product-id :uuid]
               [:quantity :pos-int]
               [:price :double]]
  :order/cart [:vector :order/item]
  :order/result [:map
                 [:order-id :uuid]
                 [:total :double]
                 [:status [:enum :pending :confirmed :shipped]]])

```

### Benefits for AI

```clojure
;; AI can understand this function's contract:
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])

;; By looking up each schema:
;;   :user/id -> :uuid (a UUID value)
;;   :order/cart -> [:vector :order/item] (vector of order items)
;;   :order/result -> [:map ...] (result map with order-id, total, status)

```

---

## Public vs Private Function Requirements

### Testing Results

Private functions (`defn-`) can be:
1. **Registered with `m/=>`** - Works exactly like public functions
2. **Instrumented** - `mi/instrument!` works on private functions
3. **Generatively tested** - `mi/check` works on private functions

### Recommendations

| Function Type | Schema Required? | Rationale |
|--------------|-----------------|-----------|
| Public API | Yes | Contract for external callers |
| Public internal | Yes | Used across namespaces |
| Private helper | Optional | If complex logic, schema helps |
| Private trivial | No | `(defn- inc-helper [x] (inc x))` needs no schema |

### Guidelines for Private Functions

1. **Schema if non-trivial:** If the private function does something complex, add a schema
2. **Schema if shared types:** If it takes/returns domain types, add a schema
3. **Skip for trivial helpers:** Simple wrappers don't need schemas

```clojure
;; No schema needed - trivial helper
(defn- inc-with-log [x]
  (println "incrementing" x)
  (inc x))

;; Schema needed - complex domain logic
(defn- calculate-order-total [cart discount-code]
  ;; complex calculation
  )
(m/=> calculate-order-total [:=> [:cat :order/cart :discount/code] :order/total])

```

---

## Integration with Generative Testing

### Best Approach: `mi/check`

```clojure
(require '[malli.instrument :as mi])

;; Check all functions in a namespace
(mi/check {:filters [(mi/-filter-ns 'seon.trading.core)]
           :num-tests 100})
;; => {#'seon.trading.core/process-order nil   ;; nil = passed
;;     #'seon.trading.core/broken-fn {...}}   ;; map = failed

```

### For Individual Functions: `mg/check`

```clojure
(require '[malli.generator :as mg])

(let [schema (:schema (get-in (m/function-schemas) ['my.ns 'my-fn]))]
  (mg/check schema my-fn {:num-tests 100}))
;; => nil if passed, {:shrunk {:smallest [args]}} if failed

```

### Error Message Quality

`m/=>` schemas produce the best error messages:

```clojure
;; With instrumentation on:
(process-order "not-a-uuid" [])
;; => :malli.core/invalid-input
;;    {:input [:cat :user/id :order/cart]
;;     :args ["not-a-uuid" []]
;;     :schema [:=> [:cat :user/id :order/cart] :order/result]}

```

### Generative Test Failure Output

```clojure
(mi/check {:filters [(mi/-filter-ns 'my.ns)]})
;; For a failing function:
;; {#'my.ns/broken-fn
;;  {:schema [:=> [:cat :int :int] :int]
;;   :value #function[my.ns/broken-fn]
;;   :errors ({:path []
;;             :check {:smallest [(0 0)]    ;; <-- Shrunk counter-example
;;                     :pass? false}})}}

```

---

## Recommended Convention for This Project

### 1. Schema Location Rule

**Always place `m/=>` immediately after the function definition.**

```clojure
(defn calculate-total
  "Calculates the total for an order cart."
  [cart]
  (reduce + (map :price cart)))
(m/=> calculate-total [:=> [:cat :order/cart] :double])

```

### 2. Registry Setup

Create `src/seon/schema.clj` with global mutable registry. Load it early in system startup.

### 3. Namespaced Keywords

All domain types use namespaced keywords:
- `:user/id`, `:user/name`, `:user/entity`
- `:order/cart`, `:order/item`, `:order/result`
- `:trading/signal`, `:trading/position`

### 4. Schema Naming Convention

| Type | Naming |
|------|--------|
| Scalar types | `:domain/field` (e.g., `:user/id`) |
| Entity maps | `:domain/entity` (e.g., `:user/entity`) |
| Collections | Use descriptive suffix (e.g., `:order/cart` for vector of items) |
| Results | `:domain/result` for function outputs |

### 5. Required vs Optional Schemas

| Function Type | Schema |
|--------------|--------|
| Public functions | Required |
| Private with domain types | Required |
| Private trivial helpers | Optional |
| Test helpers | Not required |

### 6. Multi-arity Functions

Use `:function` with multiple `:=>` schemas:

```clojure
(defn add
  ([x] (inc x))
  ([x y] (+ x y))
  ([x y z] (+ x y z)))
(m/=> add [:function
           [:=> [:cat :int] :int]
           [:=> [:cat :int :int] :int]
           [:=> [:cat :int :int :int] :int]])

```

---

## CLAUDE.md Addition

Add this to the "Domain Design Guidelines" section:

```markdown
### Malli Function Schema Guidelines

1. **Use `m/=>` macro** - Place immediately after function definition
2. **Use namespaced keywords** - `:domain/type` pattern for all custom schemas
3. **Register in global registry** - Load `seon.schema` early in startup
4. **Schema all public functions** - Required for API boundaries
5. **Schema complex private functions** - If they handle domain types

#### Pattern

```clojure
(ns seon.trading.core
  (:require [malli.core :as m]))

(defn process-signal
  "Processes a trading signal and returns an action."
  [signal market-data]
  ;; implementation
  )
(m/=> process-signal [:=> [:cat :trading/signal :market/data] :trading/action])

```

#### Verification

```clojure
;; In REPL, verify schemas are registered:
(keys (get (m/function-schemas) 'seon.trading.core))

;; Run generative tests on a namespace:
(require '[malli.instrument :as mi])
(mi/check {:filters [(mi/-filter-ns 'seon.trading.core)]})

```

#### Don't Use
- `:pre`/`:post` with Malli - No registry integration
- `mx/defn` inline - Harder for AI to parse, experimental namespace
- Metadata without `mi/collect!` - Easy to forget the collection step

```

---

## Quick Reference Card

| Task | Code |
|------|------|
| Define function schema | `(m/=> fn-name [:=> [:cat arg-schemas...] return-schema])` |
| Multi-arity schema | `(m/=> fn-name [:function [:=> ...] [:=> ...]])` |
| Register domain schema | `(swap! *schemas assoc :domain/type schema)` |
| Query all schemas in ns | `(get (m/function-schemas) 'my.ns)` |
| Generative test namespace | `(mi/check {:filters [(mi/-filter-ns 'my.ns)]})` |
| Generative test function | `(mg/check schema fn {:num-tests 100})` |
| Enable instrumentation | `(require '[malli.dev :as dev]) (dev/start!)` |
| Extract schema refs | `(fb/extract-schema-refs schema)` |

---

## Implementation Learnings: seon.ai.gemini

The Gemini API integration namespace (`src/seon/ai/gemini.clj`) serves as a gold-standard example of the `:malli/schema` metadata pattern. Key learnings from implementation:

### Approach: Inline Schema Definitions with Var References

Instead of using a global mutable registry (which can cause conflicts), define schemas as `def` vars and reference them directly in function metadata:

```clojure
;; Define schemas as vars
(def GeminiApiKey [:string {:min 1}])
(def GeminiPrompt [:string {:min 1}])
(def GeminiModel [:enum "gemini-2.5-flash" "gemini-2.5-pro" ...])
(def GeminiOptions
  [:map
   [:model {:optional true} GeminiModel]
   [:timeout {:optional true} [:int {:min 1000 :max 600000}]]
   ...])
(def GeminiResponse
  [:map
   [:text :string]
   [:error {:optional true} GeminiError]
   ...])

;; Reference vars in function schema metadata
(defn generate
  "Generate content using Gemini API."
  {:malli/schema [:=> [:cat GeminiApiKey GeminiPrompt GeminiOptions]
                  GeminiResponse]}
  [api-key prompt opts]
  ...)

```

**Why this works well:**
1. **No registry conflicts** - Schemas are resolved at read time via var deref
2. **LLM-readable** - Schema vars are defined in same file, easy to understand
3. **Generative-capable** - `(mg/generate GeminiOptions)` works directly
4. **Testable** - `(m/validate GeminiResponse data)` works without registry options
5. **Collectable** - `(mi/collect!)` still works for function schema registration

### Gotcha: Registry-Based Keywords Don't Work Without Setup

If you use `:gemini/response` in function schemas, you MUST either:
1. Register them in the global mutable registry (can cause conflicts)
2. Pass `{:registry ...}` to every validation/generation call

The var-reference approach avoids this entirely.

### Gotcha: `mg/sample` Signature

`mg/sample` takes an options map, not a count:

```clojure
;; WRONG - causes ClassCastException
(mg/sample schema 10)

;; CORRECT
(mg/sample schema {:size 10})

```

### Gotcha: `[:maybe ...]` for Nullable Optional Fields

When a response field can be either present-with-value OR present-as-nil, use `[:maybe ...]`:

```clojure
(def GeminiResponse
  [:map
   [:text :string]
   [:grounding-metadata {:optional true} [:maybe GeminiGroundingMetadata]]])

```

This allows `{:text "hi" :grounding-metadata nil}` to validate.

### Test Pattern: Verify Schema Collection

Always verify that `mi/collect!` properly registers your functions:

```clojure
(deftest schema-collection-test
  (testing "mi/collect! registers function schemas"
    (mi/collect! {:ns 'seon.ai.gemini})
    (let [fn-schemas (get (m/function-schemas) 'seon.ai.gemini)]
      (is (some? fn-schemas))
      (is (contains? fn-schemas 'generate)))))

```

### Test Pattern: Generative Input Testing

Since functions with side effects (HTTP calls) can't be generatively tested, verify their input schemas are generatable:

```clojure
(deftest generative-schema-check-test
  (mi/collect! {:ns 'seon.ai.gemini})
  (let [fn-schemas (get (m/function-schemas) 'seon.ai.gemini)]
    (doseq [[fn-sym schema-data] fn-schemas]
      (let [input-schema (second (m/form (:schema schema-data)))]
        (is (some? (mg/generate input-schema)))))))

```

### Files Created

- `src/seon/ai/gemini.clj` - Implementation with `:malli/schema` metadata
- `test/seon/ai/gemini_test.clj` - Comprehensive tests (12 tests, 105 assertions)

---

## Summary

For AI-assisted development with automatic verification:

1. **`m/=>` wins** - Immediate registration, best tooling support, clear separation
2. **Namespaced keywords** - Self-documenting, enables schema lookup
3. **Global mutable registry** - One source of truth for all domain types
4. **Schema all public functions** - Enables generative testing and instrumentation
5. **`mi/check` for verification** - Runs property tests on all registered functions

**Alternative: Inline var references** (as demonstrated in `seon.ai.gemini`):
- Define schemas as `def` vars in the same namespace
- Reference vars directly in `:malli/schema` metadata
- Avoids registry setup and conflicts
- Works well for self-contained domains
