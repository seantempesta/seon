---
type: research
status: archived
tags: [prd, research, schema, web]
---
# Research: Malli Schema Properties for Render Definitions

**Questions Addressed:** Q1 (Where do render definitions live?), Q2 (Inheritance), Q3 (Local overrides)

---

## Executive Summary

Malli schema properties provide a solid foundation for attaching render definitions to schemas. Three viable approaches exist:

1. **Schema Properties** - Render functions stored directly in schema properties
2. **Separate Registry** - Render functions in a parallel registry keyed by schema keyword
3. **Hybrid** - Schema properties declare renderer *key*, separate registry holds functions

**Recommendation:** Start with **Approach 2 (Separate Registry)** for simplicity, with value metadata for type propagation. Migrate to Hybrid if tighter schema coupling is desired.

---

## Key Findings

### 1. Malli Schema Properties Work

Malli supports arbitrary properties on schemas:

```clojure
(def position-schema
  [:map {:seon.ui/render {:ai render-ai, :html render-html}
         :seon.ui/summary-keys [:ticker :quantity]}
   [:ticker :string]
   [:quantity :int]])

;; Access properties
(m/properties position-schema)
;; => {:seon.ui/render {...}, :seon.ui/summary-keys [...]}

```

**Caveat:** When schema is registered and accessed by keyword, you must dereference:

```clojure
(schema/register! :trading/position position-schema)

;; This returns nil for properties:
(m/properties (m/schema :trading/position))  ;; => nil

;; Must dereference first:
(m/properties (m/deref (m/schema :trading/position)))
;; => {:seon.ui/render {...}, ...}

```

### 2. Schema Inheritance via mu/merge

`malli.util/merge` combines schemas and **shallowly merges properties**:

```clojure
(require '[malli.util :as mu])

(def parent [:map {:seon.ui/render {:ai :parent-ai :html :parent-html}
                   :seon.ui/label "Position"}
             [:ticker :string]])

(def child [:map {:seon.ui/render {:ai :child-ai}}  ;; overrides :ai only
             [:analysis :string]])

(mu/merge parent child)
;; => [:map {:seon.ui/render {:ai :child-ai},      ;; LOST :html!
;;          :seon.ui/label "Position"}
;;     [:ticker :string] [:analysis :string]]

```

**Problem:** Child's `:seon.ui/render` completely replaces parent's—no deep merge.

**Solution:** Custom merge function:

```clojure
(defn merge-render-schemas [parent child]
  (let [parent-props (m/properties parent)
        child-props (m/properties child)
        merged-render (merge (:seon.ui/render parent-props)
                             (:seon.ui/render child-props))
        final-props (-> (merge parent-props child-props)
                        (assoc :seon.ui/render merged-render))]
    (mu/update-properties (mu/merge parent child) (constantly final-props))))

(merge-render-schemas parent child)
;; => [:map {:seon.ui/render {:ai :child-ai, :html :parent-html}, ...} ...]

```

### 3. Value Metadata for Type Propagation

Portal's approach: attach viewer hints as metadata on values. We can do the same for schema types:

```clojure
(defn typed-value [schema-key value]
  (with-meta value {:seon/schema schema-key}))

(defn value-schema [value]
  (:seon/schema (meta value)))

;; Usage
(def pos (typed-value :trading/position {:ticker "AAPL" :quantity 100}))
(value-schema pos) ;; => :trading/position

```

**Metadata preservation:** Most operations preserve metadata:

| Operation | Preserves? |
|-----------|------------|
| `assoc` | ✓ |
| `dissoc` | ✓ |
| `update` | ✓ |
| `merge` | ✓ |
| `select-keys` | ✓ |
| `into {}` | ✗ |
| `reduce-kv` | ✗ |

**Implication:** Functions that rebuild maps from scratch need to explicitly preserve metadata.

---

## Approach Comparison

### Approach 1: Schema Properties

Render functions stored directly in schema properties.

```clojure
(schema/register! :trading/position
  [:map {:seon.ui/render {:ai (fn [v] (str "Position: " (:ticker v)))
                          :html (fn [v] [:div.position (:ticker v)])}}
   [:ticker :string]
   [:quantity :int]])

(defn render [value format]
  (let [schema-key (value-schema value)
        render-fn (-> (m/schema schema-key)
                      m/deref
                      m/properties
                      :seon.ui/render
                      (get format))]
    (if render-fn (render-fn value) (pr-str value))))

```

**Pros:**

- Single source of truth
- Schema carries complete definition

**Cons:**

- Functions in schema registry (serialization issues)
- Need `m/deref` dance for registered schemas
- Inheritance requires custom merge logic

### Approach 2: Separate Registry

Render functions in parallel registry.

```clojure
(defonce *render-registry (atom {}))

(defn register-renderer! [schema-key render-map & {:keys [inherit]}]
  (let [parent-render (when inherit (get @*render-registry inherit))
        final-render (merge parent-render render-map)]
    (swap! *render-registry assoc schema-key final-render)))

(register-renderer! :trading/position
  {:ai (fn [v] (str "Position: " (:ticker v)))
   :html (fn [v] [:div.position (:ticker v)])})

;; Child inherits parent's renderers, overrides :ai
(register-renderer! :analysis/position
  {:ai (fn [v] (str "Analysis: " (:ticker v) " PnL: " (:pnl v)))}
  :inherit :trading/position)

(defn render [value format]
  (let [schema-key (value-schema value)
        render-fn (get-in @*render-registry [schema-key format])]
    (if render-fn (render-fn value) (pr-str value))))

```

**Pros:**

- Clean separation of concerns
- Easy explicit inheritance
- No serialization issues
- Simple implementation

**Cons:**

- Two places to maintain
- Schema and renderer can get out of sync

### Approach 3: Hybrid

Schema declares renderer *key*, registry holds functions.

```clojure
;; Schema declares what renderer to use
(schema/register! :trading/position
  [:map {:seon.ui/renderer :trading/position-renderer}
   [:ticker :string]
   [:quantity :int]])

;; Registry holds actual functions
(defonce *renderers (atom {}))

(defn register-renderers! [renderer-key render-map]
  (swap! *renderers assoc renderer-key render-map))

(register-renderers! :trading/position-renderer
  {:ai (fn [v] (str "Position: " (:ticker v)))
   :html (fn [v] [:div.position (:ticker v)])})

(defn render [value format]
  (let [schema-key (value-schema value)
        renderer-key (-> (m/schema schema-key) m/deref m/properties :seon.ui/renderer)
        render-fn (get-in @*renderers [renderer-key format])]
    (if render-fn (render-fn value) (pr-str value))))

```

**Pros:**

- Schemas remain serializable (just keyword reference)
- Can share renderers across schemas
- Schema declares rendering intent

**Cons:**

- Still two places to maintain
- Extra indirection

---

## Recommendation

**Start with Approach 2 (Separate Registry)** because:

1. **Simplest to implement** - Just an atom and a few functions
2. **Explicit inheritance** - Clear `inherit` parameter, no surprises
3. **No serialization issues** - Functions stay in code
4. **Easy REPL use** - Agents can `register-renderer!` without touching schemas
5. **Decoupled** - Can iterate on rendering without touching validation schemas

**Value typing via metadata** is essential regardless of approach. Data should carry `:seon/schema` metadata so rendering works across namespace boundaries.

### Migration Path

If tighter schema coupling is desired later:

1. Add `:seon.ui/renderer` property to schemas (Hybrid approach)
2. Keep registry for actual functions
3. Renderer lookup checks schema property first, falls back to schema-key in registry

---

## Implementation Sketch

```clojure
(ns seon.ui.render
  "Multi-format rendering based on schema type metadata."
  (:require [malli.core :as m]))

;;; Registry
(defonce *renderers (atom {}))

(defn register-renderer!
  "Register render functions for a schema key.
   opts:
     :inherit - parent schema key to inherit renderers from"
  [schema-key render-map & {:keys [inherit]}]
  (let [parent (when inherit (get @*renderers inherit))
        final (merge parent render-map)]
    (swap! *renderers assoc schema-key final)
    schema-key))

(defn get-renderer [schema-key format]
  (get-in @*renderers [schema-key format]))

;;; Value typing
(defn typed
  "Attach schema type as metadata."
  [schema-key value]
  (with-meta value {:seon/schema schema-key}))

(defn schema-of
  "Get schema type from metadata."
  [value]
  (:seon/schema (meta value)))

;;; Rendering
(defn render
  "Render value for format (:ai, :html, :raw).
   Uses metadata schema type for dispatch."
  ([value format] (render value format nil))
  ([value format default-schema]
   (let [schema-key (or (schema-of value) default-schema)
         render-fn (when schema-key (get-renderer schema-key format))]
     (cond
       render-fn (render-fn value)
       (= format :raw) value
       :else (pr-str value)))))

;;; Collection support
(defn render-seq
  "Render a sequence of typed values."
  [values format]
  (mapv #(render % format) values))

```

---

## Open Questions for Future Research

1. **Q4 (Multi-format):** Should `:ai` format use multimethod dispatch for composability?
2. **Q5 (datafy/nav):** Can datafy protocol extend schema-typed maps for richer navigation?
3. **Q6 (nREPL integration):** Where in the eval→result→string pipeline should rendering happen?
4. **Metadata preservation:** Should we provide `preserve-meta` wrappers for `into`/`reduce`?

---

## Code Artifacts

Working prototype tested at REPL. Key functions:

- `register-renderer!` - Register render functions with optional inheritance
- `typed` / `schema-of` - Attach/retrieve schema type metadata
- `render` - Multi-format rendering dispatch
- `merge-render-schemas` - Deep merge for schema property inheritance (if using Approach 1)

All code examples in this document are verified working.
