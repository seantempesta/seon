# Research: Datafy/Nav and Multi-Format Rendering

**Date:** 2026-01-22
**Status:** Complete
**Questions Addressed:** Q4, Q5, Q6 from multi-format-rendering-prd.md

---

## Executive Summary

This research explored three key questions:

1. **Q5: Does datafy/nav help for schema-typed maps?** Yes, via metadata-based protocol extension
2. **Q4: How does multi-format dispatch work?** Multimethod on `[format, render-type]` or separate multimethods per format
3. **Q6: How to integrate with nREPL → MCP flow?** Wrapper function at MCP level or explicit `for-ai` calls

**Recommended Approach:** Separate multimethods per format (like Reveal), with metadata-based dispatch, and a `for-ai` wrapper function for nREPL integration.

---

## Q5: Datafy/Nav for Schema-Typed Maps

### Key Finding: Metadata-Based Protocol Extension

Both `Datafiable` and `Navigable` protocols have `:extend-via-metadata true`, meaning we can control datafy/nav behavior by attaching metadata to values.

```clojure
(require '[clojure.datafy :as d])
(require '[clojure.core.protocols :as p])

;; Custom datafy via metadata
(def custom-map
  (with-meta {:raw-data "lots of stuff" :count 1000}
    {`p/datafy (fn [this]
                 {:summary (str "Map with " (count this) " keys")
                  :keys (keys this)})}))

(d/datafy custom-map)
;; => {:summary "Map with 2 keys", :keys (:raw-data :count)}
```

### Metadata Flows Through Nav

When `nav` returns a value with metadata, that metadata controls subsequent datafy behavior:

```clojure
(def position
  (with-meta {:ticker "AAPL" :quantity 100 :price 150.0}
    {`p/nav (fn [coll k v]
              (case k
                :ticker (with-meta {:symbol v :name "Apple Inc."}
                          {`p/datafy (fn [this] (assoc this :enriched? true))})
                v))}))

(let [ticker (d/nav position :ticker "AAPL")]
  (d/datafy ticker))
;; => {:symbol "AAPL", :name "Apple Inc.", :enriched? true}
```

### Integration with Malli Schemas

We can attach Malli schemas to values via metadata, then extract render hints from schema properties:

```clojure
(require '[malli.core :as m])

(def Position
  [:map {:seon.ui/render :position
         :seon.ui/summary (fn [v] (str (:ticker v) " x" (:quantity v)))}
   [:ticker :string]
   [:quantity :int]
   [:price :double]])

(def pos (with-meta {:ticker "AAPL" :quantity 100 :price 150.0}
           {:malli/schema Position}))

;; Extract render hints
(let [schema (:malli/schema (meta pos))
      props (m/properties schema)]
  (:seon.ui/render props))
;; => :position
```

### Verdict on Datafy/Nav

**Complementary, not alternative.** Datafy/nav is useful for:

- Navigation into nested structures
- Transforming objects to data representations
- Carrying context through navigation

But for **rendering**, a simpler metadata-based multimethod dispatch is more appropriate. Datafy is about data transformation; rendering is about presentation.

---

## Q4: Multi-Format Dispatch

### Approach Comparison

#### Option A: Single Multimethod with Format Parameter

```clojure
(defmulti render-value
  (fn [v format _opts]
    (let [render-type (or (:seon.ui/render (meta v))
                          (when-let [schema (:malli/schema (meta v))]
                            (:seon.ui/render (m/properties schema)))
                          (type v))]
      [format render-type])))

(defmethod render-value [:ai :position] [v _ _]
  (str "Position: " (:ticker v) " x" (:quantity v)))

(defmethod render-value [:html :position] [v _ _]
  [:div.position-card [:span.ticker (:ticker v)]])
```

**Pros:** Single dispatch point, explicit format in call
**Cons:** Dispatch values are vectors, harder to extend

#### Option B: Separate Multimethods per Format (Recommended)

```clojure
(defn get-render-type [v]
  (or (:seon.ui/render (meta v))
      (when-let [schema (:malli/schema (meta v))]
        (:seon.ui/render (m/properties schema)))
      (type v)))

(defmulti render-ai get-render-type)
(defmulti render-html get-render-type)
(defmulti render-raw get-render-type)

(defmethod render-ai :position [v]
  (str "Position: " (:ticker v) " x" (:quantity v) " @ $" (:price v)))

(defmethod render-html :position [v]
  [:div.position-card
   [:span.ticker (:ticker v)]
   [:span.quantity "×" (:quantity v)]])

(defmethod render-raw :position [v] v)

;; Unified entry point
(defn render [v format & [opts]]
  (case format
    :ai (render-ai v)
    :html (render-html v)
    :raw (render-raw v)))
```

**Pros:**

- Simple single dispatch
- Easy to add new render types
- Follows Reveal's proven pattern
- Clear separation of concerns

**Cons:**

- Three defmethods per type

### REPL Registration Helper

For agents to register renderers without editing files:

```clojure
(defn register-renderer!
  "Register a renderer for a type.

   (register-renderer! :my/widget :ai (fn [w] (str \"Widget: \" (:name w))))
   (register-renderer! :my/widget :html (fn [w] [:div.widget (:name w)]))"
  [render-type format render-fn]
  (case format
    :ai (defmethod render-ai render-type [v] (render-fn v))
    :html (defmethod render-html render-type [v] (render-fn v))
    :raw (defmethod render-raw render-type [v] (render-fn v)))
  :registered)
```

### Collection Rendering

Collections need to recursively render their children:

```clojure
(defmethod render-ai clojure.lang.IPersistentVector [v]
  (str "[" (clojure.string/join ", " (map render-ai v)) "]"))
```

This allows:

```clojure
[Position: AAPL x100 @ $150.0, Position: GOOGL x50 @ $140.0]
```

Instead of verbose EDN.

---

## Q6: nREPL → MCP Integration

### Current Flow

```
Claude → MCP Server → nREPL (eval) → pr-str result → MCP Server → Claude
```

The result is always `pr-str` output, which can be verbose for large structures.

### Integration Options

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **1. nREPL middleware** | Custom middleware intercepts eval, wraps printing | Works for all clients | Complex, global effect |
| **2. MCP post-processing** | Parse EDN result, re-render | Simple | Double serialization, may fail |
| **3. Session-local printer** | Rebind print functions per session | Per-session control | print-method is a multimethod |
| **4. Explicit render call** | Agents call `(for-ai result)` | Explicit, no magic | Requires agent cooperation |
| **5. MCP code wrapping** | MCP server wraps code in render call | Transparent | Complexity in wrapper |

### Recommended: Option 4 with Helper Function

The simplest approach is a wrapper function that agents can use explicitly:

```clojure
(defn for-ai
  "Render any value for AI consumption."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) v
    (keyword? v) (str v)
    (number? v) (str v)
    ;; Has explicit render type
    (get-explicit-render-type v) (render-ai v)
    ;; Collections - recurse
    (sequential? v) (str "[" (clojure.string/join ", " (map for-ai v)) "]")
    (map? v) (str "{" (clojure.string/join ", "
                        (map (fn [[k val]] (str (pr-str k) " " (for-ai val))) v)) "}")
    :else (pr-str v)))
```

### Example Usage

```clojure
;; Agent evaluates:
(for-ai (analyze-positions data))

;; Instead of verbose EDN, returns:
"{:positions [Position: AAPL x100 @ $150.0], :total-value 15000.0}"
```

### Future Enhancement: Automatic Wrapping

The MCP server could optionally wrap code:

```clojure
;; In bin/mcp-server, if agent wants AI rendering:
(defn wrap-for-ai [code]
  (str "(seon.render/for-ai (do " code "))"))
```

This would be controlled by a parameter to the `eval` tool or a session setting.

---

## Schema Inheritance

Malli's `mu/merge` preserves and can override properties:

```clojure
(def BasePosition
  [:map {:seon.ui/render :position}
   [:ticker :string]
   [:quantity :int]])

(def AnalyzedPosition
  (mu/merge BasePosition
            [:map {:seon.ui/render :analyzed-position}
             [:delta :double]
             [:pnl :double]]))

(m/properties AnalyzedPosition)
;; => {:seon.ui/render :analyzed-position}
```

To inherit rendering while adding fields, omit the render property in the merge:

```clojure
(def ExtendedPosition
  (mu/merge BasePosition
            [:map  ;; No :seon.ui/render - inherits :position
             [:notes :string]]))
```

---

## Recommended Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     seon.render                              │
├─────────────────────────────────────────────────────────────┤
│  Dispatch Function                                           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ (get-render-type v)                                  │    │
│  │   1. (:seon.ui/render (meta v))                      │    │
│  │   2. (:seon.ui/render (m/properties schema))         │    │
│  │   3. (type v)                                        │    │
│  └─────────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────────┤
│  Format Multimethods                                         │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐         │
│  │  render-ai   │ │ render-html  │ │  render-raw  │         │
│  │  (concise)   │ │  (hiccup)    │ │   (data)     │         │
│  └──────────────┘ └──────────────┘ └──────────────┘         │
├─────────────────────────────────────────────────────────────┤
│  Entry Points                                                │
│  • (render v :ai)                                            │
│  • (render v :html)                                          │
│  • (for-ai v)          ; recursive, safe                     │
│  • (register-renderer! type format fn)  ; REPL use           │
└─────────────────────────────────────────────────────────────┘
```

---

## Next Steps

1. **Create `seon.render` namespace** with core multimethod infrastructure
2. **Define base renderers** for primitives, collections, common types
3. **Add render properties** to existing Malli schemas in `seon.schema`
4. **Update `seon.ui.viewer`** to use `render-html` multimethod
5. **Document for agents** how to use `for-ai` and `register-renderer!`
6. **Consider MCP enhancement** for automatic AI rendering (future)

---

## Code Reference

Working prototype code from this research session is available in the REPL history. Key functions:

- `get-render-type` - Extract render type from metadata or schema
- `render-ai`, `render-html`, `render-raw` - Format-specific multimethods
- `render` - Unified entry point
- `for-ai` - Safe recursive AI rendering
- `register-renderer!` - REPL-friendly registration
