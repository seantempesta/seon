# Clerk Research for Namespace UI

**Date:** 2025-01-20
**Updated:** 2025-01-20 (deeper source analysis)
**Purpose:** Evaluate Clerk's viewer architecture for potential integration with Seon's namespace UI.

---

## Executive Summary

Clerk has a sophisticated viewer system with predicate-based dispatch and rich rendering. However, the architecture splits between JVM (dispatch + transforms) and browser (React/Reagent rendering via SCI). The JVM side is entangled with notebook machinery; the browser side depends on React state management.

**Three viable paths forward - decision deferred:**
1. Pull Clerk as dependency (heavy, but get everything)
2. Extract/adapt viewer.cljc (moderate effort, some deps)
3. Build our own using Clerk's patterns (clean, but more work)

This document captures findings to inform that decision.

---

## Key Finding: Architecture Split

Clerk's viewer system is split across two runtimes:

| Layer | Location | What It Does | Reusable? |
|-------|----------|--------------|-----------|
| **Dispatch** | `viewer.cljc` (JVM) | Predicate matching, viewer selection | Entangled with notebook machinery |
| **Transforms** | `viewer.cljc` (JVM) | Data transformation before render | Yes, but coupled to Clerk's wrapped-value format |
| **Render** | `render.cljs` (Browser) | React/Reagent components | No - requires SCI + React |

The `:render-fn` in viewer definitions is a **quoted form** sent to the browser and evaluated via SCI (Small Clojure Interpreter). This is the core architectural decision that makes direct reuse challenging for our SSE/Datastar approach.

---

## Architecture Overview

### Core Concept: Wrapped Values

Clerk wraps all values in maps with the key `:nextjournal/value`:

```clojure
;; Plain value becomes:
{:nextjournal/value 42}

;; With viewer specified:
{:nextjournal/value 42
 :nextjournal/viewer {:name `number-viewer
                      :render-fn 'nextjournal.clerk.render/render-number}}
```

This design allows carrying metadata alongside values through the transformation pipeline.

### Viewer Structure

A viewer is a map with these keys:

```clojure
{:name      `symbol-viewer        ; Unique identifier
 :pred      symbol?               ; Predicate to match values (JVM side)
 :transform-fn (fn [wrapped-value] ...) ; JVM transformation
 :render-fn    '(fn [x] [:span x])      ; Quoted form for browser (SCI)
 :page-size    20                       ; Pagination limit
 :opening-paren "["                     ; For collection display
 :closing-paren "]"
 :add-viewers   [...]                   ; Additional viewers for children
 }
```

**Key insight:** `:render-fn` is a *quoted form* sent to the browser and evaluated via SCI (Small Clojure Interpreter). This enables Clerk to run Clojure in the browser without full ClojureScript compilation.

### Viewer Dispatch (predicate-based)

```clojure
(defn viewer-for [viewers x]
  (or
    ;; 1. Check if value specifies its own viewer
    (when-let [selected-viewer (->viewer x)]
      (find-named-viewer viewers selected-viewer))

    ;; 2. Find first viewer whose :pred matches
    (find-viewer viewers
      (fn [{:keys [pred]}]
        (and (ifn? pred) (pred (->value x)))))))
```

The `default-viewers` vector is searched in order. First match wins.

### Default Viewers

Clerk's `default-viewers` includes ~35 viewers:

| Category | Viewers |
|----------|---------|
| Primitives | `char-viewer`, `string-viewer`, `number-viewer`, `symbol-viewer`, `keyword-viewer`, `nil-viewer`, `boolean-viewer` |
| Collections | `vector-viewer`, `set-viewer`, `sequential-viewer`, `map-viewer`, `map-entry-viewer` |
| Special | `var-viewer`, `throwable-viewer`, `ideref-viewer` (atoms, refs), `regex-viewer` |
| Rich Media | `image-viewer`, `html-viewer`, `markdown-viewer`, `code-viewer` |
| Charts | `plotly-viewer`, `vega-lite-viewer`, `katex-viewer`, `mathjax-viewer` |
| Tables | `table-viewer` (complex, with sub-viewers for rows, cells, etc.) |
| Notebook | `cell-viewer`, `result-viewer`, `notebook-viewer`, `header-viewer`, `toc-viewer` |

### Presentation Pipeline

```
Value -> ensure-wrapped -> apply-viewers -> process-wrapped-value -> Browser
```

1. **ensure-wrapped**: Wraps raw value in `{:nextjournal/value ...}`
2. **apply-viewers**: Finds matching viewer, runs `:transform-fn` (may loop if no `:render-fn`)
3. **process-wrapped-value**: Strips internal keys, prepares for serialization
4. **Browser**: SCI evaluates `:render-fn` with the transformed value

### Client-Server Split

- **JVM (Clojure):** Viewer dispatch, `:transform-fn` execution, pagination, value serialization
- **Browser (ClojureScript/SCI):** `:render-fn` execution, React/Reagent rendering, lazy loading via fetch

---

## What Clerk Provides

### Strengths

1. **Elegant predicate dispatch** - Simple, composable viewer selection
2. **Transform-before-render** - JVM transforms data before sending to browser
3. **Pagination built-in** - Large collections paginate automatically
4. **Extensible** - Easy to add custom viewers via `with-viewer`
5. **Hiccup-based rendering** - Familiar pattern

### Clerk-Specific Concerns

1. **SCI dependency** - Requires bundling SCI for browser evaluation
2. **WebSocket transport** - Assumes real-time bidirectional connection
3. **Static build system** - Heavy infrastructure for offline HTML generation
4. **Notebook-centric** - Viewers assume code cells, markdown blocks, file paths

---

## Render Function Analysis

We examined the actual render functions in `render.cljs` to understand what's reusable.

### Simple Viewers (Pure Hiccup)

These produce static Hiccup with no React state - **directly portable to JVM**:

```clojure
;; From viewer.cljc - inline render-fns
{:name `nil-viewer
 :pred nil?
 :render-fn '(fn [_] [:span.cmt-default.inspected-value "nil"])}

{:name `boolean-viewer
 :pred boolean?
 :render-fn '(fn [x] [:span.cmt-bool.inspected-value (str x)])}

{:name `keyword-viewer
 :pred keyword?
 :render-fn '(fn [x] [:span.cmt-atom.inspected-value (str x)])}

{:name `symbol-viewer
 :pred symbol?
 :render-fn '(fn [x] [:span.cmt-keyword.inspected-value (str x)])}

;; From render.cljs
(defn render-number [num]
  [:span.cmt-number.inspected-value
   (if (js/Number.isNaN num) "NaN" (str num))])  ;; js/ -> JVM equivalent needed
```

### Stateful Viewers (React/Reagent)

These use Reagent atoms for expand/collapse state - **NOT directly portable**:

```clojure
;; From render.cljs - uses !expanded-at Reagent atom
(defn render-coll [xs {:as opts :keys [path viewer !expanded-at] :or {path []}}]
  (let [expanded? (get @!expanded-at path)  ;; <-- Reagent atom deref
        {:keys [opening-paren closing-paren]} viewer]
    [:span.inspected-value.whitespace-nowrap
     {:class (when expanded? "inline-flex")}
     [:span
      (if (< 1 (count xs))
        [expand-button !expanded-at opening-paren path]  ;; <-- stateful component
        [:span opening-paren])
      ;; ...
```

```clojure
;; render-map, render-quoted-string also use !expanded-at
(defn render-map [xs {:as opts :keys [path viewer !expanded-at] :or {path []}}]
  (let [expanded? (get @!expanded-at path)
        ;; ...
```

### Viewer Complexity Breakdown

| Viewer | Render Location | State? | Portable? |
|--------|-----------------|--------|-----------|
| nil, boolean, keyword, symbol | Inline in viewer.cljc | No | Yes |
| number | render.cljs | No (just js/isNaN) | Yes, trivial port |
| char | Inline | No | Yes |
| var | Inline + transform | No | Yes |
| string | render.cljs | Yes (expand for multiline) | Needs adaptation |
| vector, set, seq | render.cljs | Yes (expand/collapse) | Needs adaptation |
| map | render.cljs | Yes (expand/collapse) | Needs adaptation |
| table | render/table.cljs | Yes (sorting, pagination) | Complex, needs rewrite |
| throwable | render.cljs | Yes (stack expand) | Needs adaptation |
| code | render/code.cljs | Yes (CodeMirror) | Complex |
| plotly, vega-lite | render.cljs | No (delegates to JS libs) | Would need JS libs |
| katex, mathjax | render.cljs | No (delegates to JS libs) | Would need JS libs |

### Datastar Alternative for State

For viewers that need expand/collapse, we could replace Reagent atoms with Datastar signals:

```clojure
;; Reagent approach (Clerk):
(let [expanded? (get @!expanded-at path)]
  [:span {:class (when expanded? "inline-flex")} ...])

;; Datastar approach (Seon):
[:span {:data-show "$expanded"
        :data-signals "{expanded: false}"
        :data-on-click "$expanded = !$expanded"} ...]
```

This would require rewriting the render functions but the logic is straightforward.

---

## Dependency Analysis

### viewer.cljc requires:

```clojure
(:require #?(:clj [babashka.http-client :as http])
          [clojure.datafy :as datafy]
          [clojure.pprint :as pprint]
          [clojure.string :as str]
          [flatland.ordered.map :as omap :refer [ordered-map]]
          #?@(:clj [[babashka.fs :as fs]
                    [clojure.repl :refer [demunge]]
                    [clojure.tools.reader :as tools.reader]
                    [nextjournal.clerk.config :as config]      ;; <-- notebook
                    [nextjournal.clerk.analyzer :as analyzer]]) ;; <-- notebook
          [nextjournal.clerk.parser :as parser]   ;; <-- notebook
          [nextjournal.clerk.walk :as w]
          [nextjournal.markdown :as md]           ;; <-- markdown parsing
          [nextjournal.markdown.utils :as md.utils])
```

**Entangled with notebook machinery:** config, analyzer, parser, markdown

**Potentially extractable:** ordered-map, walk, basic viewer definitions

### Full Clerk deps.edn:

```clojure
{:deps {babashka/fs {:mvn/version "0.5.28"}
        org.babashka/http-client {:mvn/version "0.4.23"}
        borkdude/edamame {:mvn/version "1.4.28"}
        weavejester/dependency {:mvn/version "0.2.1"}
        com.nextjournal/beholder {:mvn/version "1.0.3"}
        org.flatland/ordered {:mvn/version "1.15.12"}
        io.github.nextjournal/markdown {:mvn/version "0.7.222"}
        babashka/process {:mvn/version "0.4.16"}
        com.taoensso/nippy {:mvn/version "3.4.2"}
        mvxcvi/multiformats {:mvn/version "1.0.125"}
        com.pngencoder/pngencoder {:mvn/version "0.13.1"}
        http-kit/http-kit {:mvn/version "2.8.0"}
        hiccup/hiccup {:mvn/version "2.0.0-RC3"}
        rewrite-clj/rewrite-clj {:mvn/version "1.1.45"}
        juji/editscript {:mvn/version "0.6.4"}}}
```

Pulling Clerk as a dep would bring all of these.

---

## Integration Approaches

### Option A: Use Clerk as Dependency

**What we'd get:**
- All 35+ viewers
- Predicate dispatch system
- Transform pipeline
- Pagination built-in

**Challenges:**
- ~15 dependencies (see deps.edn above)
- Assumes notebook model (files, cells, caching)
- WebSocket-based live updates - conflicts with our SSE/Datastar model
- render.cljs requires SCI in browser - adds ~300KB
- Would need adapter layer to bridge architectures

**When this makes sense:**
- If we wanted full Clerk notebooks embedded in Seon
- If we needed Plotly/Vega/KaTeX charting immediately

### Option B: Extract/Adapt viewer.cljc

**What we'd get:**
- Viewer definitions with predicates
- Transform functions
- Wrapped-value pattern

**Challenges:**
- Entangled with nextjournal.clerk.{config, analyzer, parser, markdown}
- Would need to stub or extract those deps
- render-fns still assume browser-side SCI eval
- Would need to rewrite render-fns for server-side Hiccup

**When this makes sense:**
- If we want Clerk's specific viewer logic but not the notebook system
- If the effort to untangle is less than rewriting

### Option C: Build Our Own, Borrow Patterns

**What we'd get:**
- Clean Datastar/SSE integration from the start
- No extra dependencies
- Full control over rendering
- Purpose-built for namespace introspection

**Challenges:**
- Need to implement ~15-20 viewers ourselves
- Won't have Plotly/Vega/KaTeX initially
- Ongoing maintenance

**When this makes sense:**
- If our viewer needs are modest (namespace introspection, not notebooks)
- If clean architecture is more valuable than feature completeness

### Effort Estimates (Rough)

| Approach | Initial Effort | Ongoing Maintenance | Dependency Cost |
|----------|----------------|---------------------|-----------------|
| A: Full Clerk | Low (just wire up) | Low (Nextjournal maintains) | High (~15 deps, SCI) |
| B: Extract | Medium (untangle + adapt) | Medium (our fork) | Medium (some deps) |
| C: Build Own | Medium (implement viewers) | Medium (our code) | Low (none) |

**Decision deferred** - need to weigh these trade-offs against project priorities.

---

## Patterns Worth Considering

Regardless of which approach we take, these patterns from Clerk are worth adopting:

### 1. Wrapped Values

Carry metadata alongside values:

```clojure
;; Clerk style:
{:nextjournal/value 42
 :nextjournal/viewer {:name `number-viewer}}

;; Seon equivalent:
{:seon.ui/value 42
 :seon.ui/viewer :number}
```

### 2. Predicate-Based Dispatch

First-match-wins viewer selection:

```clojure
(def viewers
  [{:name :nil     :pred nil?     :render render-nil}
   {:name :number  :pred number?  :render render-number}
   {:name :string  :pred string?  :render render-string}
   {:name :fn      :pred fn?      :render render-fn}
   {:name :atom    :pred #(instance? IAtom %) :render render-atom}
   {:name :map     :pred map?     :render render-map}
   {:name :vector  :pred vector?  :render render-vector}
   {:name :fallback :pred (constantly true) :render render-fallback}])

(defn find-viewer [viewers value]
  (first (filter #((:pred %) value) viewers)))
```

This is ~10 lines of code and gives us Clerk's dispatch semantics.

### 3. Transform + Render Separation

JVM transforms data, then renders to Hiccup:

```clojure
(defn viewer->hiccup [{:keys [transform render]} value]
  (-> value
      (cond-> transform transform)
      render))
```

### 4. Pagination for Large Collections

Clerk's approach - truncate and offer expansion:

```clojure
;; Clerk uses :page-size on viewers
{:name `vector-viewer
 :pred vector?
 :render-fn 'nextjournal.clerk.render/render-coll
 :page-size 20}
```

For Datastar, pagination could use SSE fetch:

```clojure
(defn render-coll [xs {:keys [page-size] :or {page-size 20}}]
  (let [visible (take page-size xs)
        remaining (- (count xs) page-size)]
    [:div
     (map render-item visible)
     (when (pos? remaining)
       [:button {:data-on-click "$$get('/api/expand?path=...')"}
        (str remaining " more...")])]))
```

### 5. Value-Declared Viewers

Allow values to specify how they should be rendered:

```clojure
(defn with-viewer [viewer value]
  {:seon.ui/value value :seon.ui/viewer viewer})

;; Usage:
(with-viewer :code '(defn foo [x] (+ x 1)))
```

### 6. CSS Classes from Clerk

Clerk uses CodeMirror-style classes for syntax highlighting:

| Class | Purpose |
|-------|---------|
| `.cmt-number` | Numbers (green) |
| `.cmt-string` | Strings (amber) |
| `.cmt-keyword` | Keywords (purple) |
| `.cmt-atom` | Atoms/constants |
| `.cmt-bool` | Booleans (blue) |
| `.cmt-default` | Default/nil (gray) |
| `.inspected-value` | All inspected values |

We could adopt these or define our own Tailwind equivalents.

---

## Our Viewer Needs vs Clerk Coverage

| Our Need | Clerk Has? | Notes |
|----------|------------|-------|
| **Functions (arglists, docs)** | No | Core need - must build custom |
| **Malli schemas** | No | Core need - must build custom |
| **XTDB entities** | No | Core need - must build custom |
| **Vars** | Partial | Has `var-viewer` but notebook-focused |
| **Atoms (live values)** | Yes | `ideref-viewer` shows deref state |
| **Collections** | Yes | Full support with expand/collapse |
| **Primitives** | Yes | string, number, keyword, symbol, boolean, nil |
| **Exceptions** | Yes | `throwable-viewer` with stack traces |
| **Tables** | Yes | Full sorting/pagination |
| **Code** | Yes | Syntax highlighted via CodeMirror |
| **Charts (Plotly/Vega)** | Yes | If we need visualization |
| **Math (KaTeX)** | Yes | If we need formula rendering |

**Analysis:**
- Our three core needs (functions, Malli, XTDB) require custom viewers regardless of approach
- Clerk's strength is in primitives, collections, and rich media (charts, math)
- The overlap is mainly basic type rendering which is straightforward to implement

---

## What Option C Would Look Like

If we build our own viewer system, here's a sketch:

### Core Viewer System (~50 lines)

```clojure
(ns seon.ui.viewer)

(defn find-viewer [viewers value]
  (first (filter #((:pred %) value) viewers)))

(def default-viewers
  [{:name :nil       :pred nil?       :render (constantly [:span.text-gray-400 "nil"])}
   {:name :boolean   :pred boolean?   :render #([:span.text-blue-600 (str %)])}
   {:name :number    :pred number?    :render #([:span.text-green-600 (str %)])}
   {:name :string    :pred string?    :render #([:span.text-amber-600 (pr-str %)])}
   {:name :keyword   :pred keyword?   :render #([:span.text-purple-600 (str %)])}
   {:name :symbol    :pred symbol?    :render #([:span.text-pink-600 (str %)])}
   {:name :fn        :pred fn?        :render render-fn}
   {:name :var       :pred var?       :render render-var}
   {:name :atom      :pred atom?      :render render-atom}
   {:name :exception :pred #(instance? Throwable %) :render render-exception}
   {:name :map       :pred map?       :render render-map}
   {:name :vector    :pred vector?    :render render-vector}
   {:name :set       :pred set?       :render render-set}
   {:name :seq       :pred seq?       :render render-seq}
   {:name :fallback  :pred (constantly true) :render #([:code (pr-str %)])}])

(defn render [value]
  (let [{:keys [render]} (find-viewer default-viewers value)]
    (render value)))
```

### Namespace-Specific Viewers

```clojure
;; Additional viewers for seon.ns.introspect results
{:name :ns-functions :pred #(= :functions (:type %)) :render render-fn-table}
{:name :ns-vars      :pred #(= :vars (:type %))      :render render-var-table}
{:name :ns-atoms     :pred #(= :atoms (:type %))     :render render-atom-list}
```

### Malli Schema Viewer

```clojure
{:name :malli-schema
 :pred malli.core/schema?
 :render (fn [schema]
           [:div.font-mono.text-sm
            [:pre (with-out-str (pprint/pprint (malli.core/form schema)))]])}
```

### Collection Viewer with Datastar Expand/Collapse

```clojure
(defn render-vector [xs]
  (let [id (gensym "vec")]
    [:span.inspected-value
     {:data-signals (str "{" id "_expanded: false}")}
     [:span.cursor-pointer
      {:data-on-click (str "$" id "_expanded = !$" id "_expanded")}
      "["]
     [:span {:data-show (str "!$" id "_expanded")}
      (when (> (count xs) 3)
        [:span.text-gray-400 (str (count xs) " items")])]
     [:span {:data-show (str "$" id "_expanded")}
      (interpose " " (map render xs))]
     "]"]))
```

**Estimated effort:** 2-3 days for basic viewers, 1-2 more days for polish.

---

## Licensing

Clerk is ISC licensed (permissive, similar to MIT). We could copy code if desired:

> Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted...

However, copying code is unnecessary given our architectural differences.

---

## Dependencies to Avoid

If we wanted to use Clerk viewers, we'd need:

- `io.github.babashka/sci` - ClojureScript interpreter (~300KB)
- `juji/editscript` - Diff algorithm for updates
- `io.github.nextjournal/markdown` - Markdown parsing
- `rewrite-clj/rewrite-clj` - Clojure code manipulation
- Plus React, Reagent, framer-motion, CodeMirror, KaTeX...

For namespace introspection, this is massive overkill.

---

## Conclusion

Clerk provides excellent patterns for value rendering, but its architecture (SCI in browser, WebSocket updates, notebook-centric) doesn't align cleanly with our SSE/Datastar approach.

**What's clearly reusable:**
- Predicate-based viewer dispatch pattern (~10 lines to implement)
- Wrapped-value pattern for carrying metadata
- Simple inline render-fns (nil, boolean, keyword, symbol, number)
- CSS class conventions for syntax highlighting
- Pagination pattern for large collections

**What would need rewriting regardless:**
- Collection viewers (Reagent state → Datastar signals)
- Table viewer (sorting/pagination state)
- Expand/collapse interactions

**What we'd gain from pulling Clerk as dep:**
- 35+ viewers out of the box
- Plotly, Vega-Lite, KaTeX rendering (if we need charts/math)
- Maintained by Nextjournal

**What we'd lose:**
- Clean Datastar integration (would need adapter)
- Lightweight dependency tree
- Full control over rendering

---

## Open Questions

1. **Do we need charting?** If we need Plotly/Vega soon, Clerk becomes more attractive.

2. **How important is the expand/collapse UX?** If we're okay with flat rendering initially, the simple viewers are trivial to implement.

3. **Could we use Clerk for some viewers and custom for others?** Hybrid approach worth exploring.

4. **What about Portal/Reveal?** Should research these as alternatives - they may have lighter integration paths.

---

## Next Steps

Before deciding:

1. [ ] Research Portal's viewer architecture - may be simpler to integrate
2. [ ] Research Reveal's approach - may have different trade-offs
3. [ ] Prototype Option C (build own) with 5-6 basic viewers to gauge effort
4. [ ] Prototype Option A (Clerk dep) to understand integration friction
5. [ ] Decide based on actual effort vs. benefit

---

## References

- Clerk repo: `reference-code/clerk/`
- Key files examined:
  - `src/nextjournal/clerk/viewer.cljc` - JVM viewer definitions
  - `src/nextjournal/clerk/render.cljs` - Browser rendering
  - `deps.edn` - Dependency tree
- License: ISC (permissive, allows copying code)
