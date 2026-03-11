# Parsing Approaches for Clojure Source Code

Research on extracting function definitions and Malli schemas from Clojure files.

## Options Evaluated

### 1. REPL Introspection (Recommended)

**Approach**: After namespace reload, query the running REPL for var metadata and Malli function schemas.

**How it works**:

```clojure
;; Get all functions with Malli schemas in a namespace
(require '[malli.core :as m])

;; Malli stores registered function schemas in an atom
(m/function-schemas)
;; => {:clj {seon.foo {bar {:schema [:=> [:cat :int] :string], :ns seon.foo, :name bar}, ...}}}

;; Or introspect var metadata directly
(defn functions-with-schemas [ns-sym]
  (->> (ns-interns ns-sym)
       (filter (fn [[_ v]] (-> v meta :malli/schema)))
       (into {})))

;; Get source via var metadata
(defn var-source [v]
  (let [{:keys [file line]} (meta v)]
    {:file file :line line}))

;; Example: get all schema-annotated functions
(for [[sym v] (functions-with-schemas 'seon.trading.core)]
  {:fn sym
   :schema (-> v meta :malli/schema)
   :file (-> v meta :file)
   :line (-> v meta :line)})

```

**Pros**:
- Malli already tracks function schemas via `m/function-schemas`
- Schemas are validated/parsed by Malli itself
- Works with both `m/=>` and `:malli/schema` metadata
- Fast - no file parsing needed
- Always up-to-date with loaded code

**Cons**:
- Requires REPL to be running with code loaded
- Cannot analyze files that fail to compile
- No access to comments or uneval'd code

### 2. rewrite-clj (Static Analysis)

**Approach**: Parse `.clj` files as zipper data structures, navigate to find `defn` and `m/=>` forms.

**How it works**:

```clojure
(require '[rewrite-clj.zip :as z])

;; Parse file into zipper
(def zloc (z/of-file "src/seon/foo.clj"))

;; Find all defn forms
(defn find-defns [zloc]
  (loop [loc (z/down zloc)
         defns []]
    (if (nil? loc)
      defns
      (let [form (z/sexpr loc)]
        (recur (z/right loc)
               (if (and (list? form) (= 'defn (first form)))
                 (conj defns {:name (second form)
                              :position (z/position loc)})
                 defns))))))

;; Find m/=> declarations
(defn find-schema-declarations [zloc]
  (loop [loc zloc
         schemas {}]
    (if (z/end? loc)
      schemas
      (let [node (z/node loc)]
        (recur (z/next loc)
               (if (and (z/list? loc)
                        (= 'm/=> (first (z/sexpr loc))))
                 (let [[_ fn-sym schema] (z/sexpr loc)]
                   (assoc schemas fn-sym schema))
                 schemas))))))

```

**Pros**:
- Works on any file (doesn't need to compile)
- Preserves source position (line, column)
- Can analyze partial/broken code
- Preserves whitespace and comments

**Cons**:
- Must parse schema syntax ourselves
- Need to resolve registry references separately
- More complex than REPL introspection
- Slower (file I/O + parsing)

### 3. tools.reader (Low-level)

**Approach**: Use `clojure.tools.reader` to read forms without evaluation.

**How it works**:

```clojure
(require '[clojure.tools.reader :as reader]
         '[clojure.tools.reader.reader-types :as rt])

(defn read-all-forms [file-path]
  (with-open [r (io/reader file-path)]
    (let [rdr (rt/indexing-push-back-reader (slurp r))]
      (loop [forms []]
        (let [form (reader/read {:eof ::eof} rdr)]
          (if (= form ::eof)
            forms
            (recur (conj forms form))))))))

;; Extract defns
(defn extract-defns [forms]
  (filter #(and (list? %) (= 'defn (first %))) forms))

;; Extract m/=> declarations
(defn extract-schemas [forms]
  (->> forms
       (filter #(and (list? %) (= 'm/=> (first %))))
       (map (fn [[_ sym schema]] [sym schema]))
       (into {})))

```

**Pros**:
- Returns plain Clojure data structures
- Lighter weight than rewrite-clj
- Good for simple extraction

**Cons**:
- No position tracking
- No zipper navigation
- Must handle reader conditionals manually
- Less suitable for code transformation

### 4. Simple Regex (Not Recommended)

**Approach**: Use regular expressions to find patterns.

```clojure
;; Find defn declarations
(re-seq #"\(defn\s+(\S+)" source)

;; Find m/=> declarations
(re-seq #"\(m/=>\s+(\S+)\s+(.+?)\)" source)

```

**Pros**:
- Simple, fast for basic cases
- Works on raw text

**Cons**:
- Fails on multi-line forms
- Doesn't handle nested parens
- Can't distinguish strings/comments from code
- **Not suitable for production use**

## Recommendation: REPL Introspection

For the unified dev hook, **REPL introspection is the best approach** because:

1. **Malli already tracks schemas** - The `m/function-schemas` atom contains all registered function schemas with their fully resolved schema data.

2. **Post-reload timing** - The hook runs after namespace reload, so we always get the latest state.

3. **Validated schemas** - Malli validates schemas on registration, so we know they're syntactically correct.

4. **Simple implementation** - No file parsing needed:

   ```clojure
   (defn get-function-schemas [ns-sym]
     (get-in (m/function-schemas) [:clj ns-sym]))

   ```

5. **Works with the existing hook flow** - The hook already reloads namespaces via nREPL, so introspection fits naturally.

### When to Use rewrite-clj

Use rewrite-clj only if we need:
- Source code position tracking for IDE integration
- Analysis of files that don't compile
- Preservation of comments or formatting

### Hybrid Approach

For the future, consider a hybrid:
1. **REPL introspection** for schema data (fast, accurate)
2. **rewrite-clj** for source position mapping (if needed for error reporting)

## REPL-Based Implementation Example

```clojure
(ns seon.dev.feedback
  "REPL-side feedback utilities."
  (:require [malli.core :as m]
            [malli.generator :as mg]))

(defn namespace-schemas
  "Get all function schemas for a namespace.
   Returns map of {fn-sym {:schema schema :ns ns :name name}}."
  [ns-sym]
  (get-in (m/function-schemas) [:clj ns-sym]))

(defn schema-fns
  "Get all functions in namespace that have schemas."
  [ns-sym]
  (->> (namespace-schemas ns-sym)
       (keys)
       (set)))

(defn new-functions
  "Find functions that weren't in the known set."
  [ns-sym known-fns]
  (let [current (schema-fns ns-sym)]
    (clojure.set/difference current known-fns)))

(defn check-function
  "Run generative tests on a single function."
  [ns-sym fn-sym opts]
  (let [schema-data (get (namespace-schemas ns-sym) fn-sym)
        var (ns-resolve ns-sym fn-sym)]
    (when (and schema-data var)
      (mg/check (:schema schema-data) @var opts))))

(defn check-namespace
  "Check all schema-annotated functions in namespace."
  [ns-sym opts]
  (let [schemas (namespace-schemas ns-sym)]
    (into {}
          (for [[fn-sym _] schemas]
            [fn-sym (check-function ns-sym fn-sym opts)]))))

```

## Code Examples to Test in REPL

```clojure
;; Test: Get all function schemas in a namespace
(require '[malli.core :as m])
(m/function-schemas)

;; Test: Check if a specific function has a schema
(require '[malli.instrument :as mi])
(mi/collect! {:ns 'seon.trading.core})
(m/function-schemas)

;; Test: Get var metadata
(meta #'seon.trading.core/some-fn)
;; => {:line 42, :file "src/seon/trading/core.clj", :malli/schema [...], ...}

;; Test: Run generative check
(require '[malli.generator :as mg])
(let [schema [:=> [:cat :int] :int]
      f (fn [x] (inc x))]
  (mg/check schema f {:num-tests 10}))

```

## Files to Read

| File | Purpose |
|------|---------|
| `reference-code/malli/src/malli/instrument.clj` | How Malli collects and instruments function schemas |
| `reference-code/malli/src/malli/dev.clj` | Development mode with auto-instrumentation |
| `reference-code/malli/src/malli/core.cljc` | Core schema functions, `function-schemas` atom |
