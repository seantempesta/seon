# Spec-First Query Interface Design

**Date**: 2025-12-17
**Author**: Claude (Research Agent)
**Status**: Complete

---

## Executive Summary

This document designs a **spec-first query interface** for Seon where:

1. **All domain functions MUST have Malli schemas** - Enforced through convention and tooling
2. **Query results are automatically well-typed** - Simple function takes a query function, returns validated maps
3. **Living documentation** - Schemas ARE the documentation; agents read them to understand data
4. **Immediate REPL feedback** - Instrumentation catches errors at function boundaries in dev
5. **Low cognitive load** - Predictable patterns; agents focus on data processing, not plumbing

**Key Finding**: Malli provides ALL the pieces we need. We don't need to invent new mechanisms - we need to establish **patterns and conventions** that make spec-first development the path of least resistance.

---

## Part 1: Malli Mechanics Deep Dive

### How `m/=>` Works Internally

**Function schema registration** is a two-step process:

1. **Schema definition**: `m/=>` is a macro that calls `m/-register-function-schema!`
2. **Storage**: Schemas stored in `@#'m/-function-schemas*` atom indexed by `[:clj ns-sym fn-sym]`

```clojure
;; Registration (from malli.core.cljc:3090)
(defmacro => [given-sym value]
  (let [ns-str (str (or (not-empty (namespace given-sym)) *ns*))
        name-str (name given-sym)]
    `(do (-register-function-schema! '~(symbol ns-str) '~(symbol name-str)
                                      ~value ~(meta given-sym))
         '~(symbol ns-str name-str))))

;; Storage structure
@#'m/-function-schemas*
;; => {:clj {seon.trading.signals {iv-rank {:schema [:=> ...] :ns ... :name ...}}}}
```

**Schema structure** for function schemas:

```clojure
[:=> input-schema output-schema]
;; OR for multi-arity:
[:function
 [:=> [:cat :int] :int]           ;; 1-arity
 [:=> [:cat :int :int] :int]]     ;; 2-arity
```

### How Instrumentation Works

**Three instrumentation modes** available:

1. **`malli.instrument/instrument!`** - Manual instrumentation
2. **`malli.dev/start!`** - Auto-instrumentation with watchers
3. **`malli.experimental/defn`** - Compile-time instrumentation (with `:malli/always` metadata)

#### Mode 1: Manual Instrumentation

```clojure
(require '[malli.instrument :as mi])

;; Define function
(defn add [x y] (+ x y))

;; Register schema
(m/=> add [:=> [:cat :int :int] :int])

;; Instrument
(mi/instrument!)
;; => (#'user/add)

;; Now add is wrapped
(add "bad" 10)
;; => ExceptionInfo: :malli.core/invalid-input
```

**How wrapping works** (from malli.core.cljc:2203):

```clojure
(-instrument-f [schema {:keys [scope report gen] :as props} f _options]
  (let [{:keys [min max input output guard]} (-function-info schema)
        [validate-input validate-output] (-vmap -validator [input output])
        validate-guard (or (some-> guard -validator) any?)
        [wrap-input wrap-output wrap-guard] (-vmap #(contains? scope %)
                                                    [:input :output :guard])
        f (or (if gen (gen schema) f) (-fail! ::missing-function {:props props}))]
    (fn [& args]
      (let [args (vec args), arity (count args)]
        (when wrap-input
          (when-not (<= min arity (or max miu/+max-size+))
            (report ::invalid-arity {:arity arity ...}))
          (when-not (validate-input args)
            (report ::invalid-input {:input input, :args args ...})))
        (let [value (apply f args)]
          (when (and wrap-output (not (validate-output value)))
            (report ::invalid-output {:output output, :value value ...}))
          (when (and wrap-guard (not (validate-guard [args value])))
            (report ::invalid-guard {:guard guard ...}))
          value)))))
```

**Key insight**: The wrapper:
1. Validates arity (argument count)
2. Validates input arguments as a vector
3. Calls the original function
4. Validates the return value
5. Optionally validates guard conditions (constraints between input/output)

#### Mode 2: Dev Auto-Instrumentation

```clojure
(require '[malli.dev :as mdev]
         '[malli.dev.pretty :as pretty])

;; Start instrumentation with pretty errors
(mdev/start! {:report (pretty/reporter)})
;; => "dev-mode started"

;; Instrumentation auto-applies to:
;; 1. All loaded namespaces (via collect!)
;; 2. Any newly defined functions (via add-watch on -function-schemas*)

;; Define function after starting
(defn multiply [x y] (* x y))
(m/=> multiply [:=> [:cat :number :number] :number])

;; Already instrumented!
(multiply "bad" 10)
;; => ExceptionInfo with PRETTY error message

;; Stop instrumentation
(mdev/stop!)
```

**How it works** (from malli.dev.clj:39):

```clojure
(defn start!
  ([] (start! {:report (pretty/reporter)}))
  ([options]
   (with-out-str (stop!))
   (-capture-fail! options)                    ;; Wrap m/-fail! with reporter
   (mi/collect! {:ns (all-ns)})                ;; Scan all namespaces for schemas
   (let [watch (bound-fn [_ _ old new]         ;; Watch for schema changes
                 (->> (for [[n d] (:clj new)
                            :let [no (get-in old [:clj n])]
                            [s d] d
                            :when (not= d (get no s))]
                        [[n s] d])
                      (into {})
                      (reduce-kv assoc-in {})
                      (assoc options :data)
                      (mi/instrument!))         ;; Re-instrument changed functions
                 (clj-kondo/emit! options))]
     (add-watch @#'m/-function-schemas* ::watch watch))
   (mi/instrument! options)                    ;; Initial instrumentation
   (-log! "dev-mode started")))
```

**Key features**:
- **Auto-reinstrumentation**: Watches schema atom, re-instruments when schemas change
- **Namespace scanning**: `collect!` scans all loaded namespaces for `:malli/schema` metadata
- **Pretty errors**: Custom reporter formats validation failures
- **clj-kondo integration**: Emits type annotations for static analysis

#### Mode 3: Compile-Time Instrumentation

```clojure
(require '[malli.experimental :as mx])

;; Define with inline schemas
(mx/defn add-numbers :- :int
  "Add two integers"
  [x :- :int, y :- :int]
  (+ x y))

;; ALWAYS instrumented (no need for mdev/start!)
(add-numbers "bad" 10)
;; => ClassCastException (schema validation happens, but runtime still throws)

;; With :malli/always metadata, validation happens before execution
(mx/defn add-strict :- :int
  "Add two integers with strict validation"
  {:malli/always true}
  [x :- :int, y :- :int]
  (+ x y))

(add-strict "bad" 10)
;; => ExceptionInfo: :malli.core/invalid-input (caught BEFORE ClassCastException)
```

**How it works** (from malli.experimental.cljc:38):

```clojure
(c/defn -defn [schema args]
  (let [{:keys [name return doc arities] body-meta :meta :as parsed}
        (:values (m/parse schema args))
        validate? (or (:malli/always var-meta) (:malli/always body-meta))
        enriched-meta (assoc body-meta :raw-arglists ... :schema schema)]
    `(let [defn# ~(if validate?
                    `(def ~(with-meta name (merge var-meta enriched-meta ...))
                       ~@(some-> doc vector)
                       (m/-instrument {:schema ~schema}
                                      (fn ~(gensym (str name "-instrumented"))
                                        ~@bodies)))
                    `(c/defn ~name ~@(some-> doc vector) ~enriched-meta ~@bodies))]
       (m/=> ~name ~schema)  ;; Always register schema
       defn#)))
```

**Key insight**: `mx/defn`:
1. Always registers the schema via `m/=>`
2. If `:malli/always` is set, wraps function with `m/-instrument` at compile time
3. Otherwise, just attaches schema metadata (instrumented later via `mdev/start!`)

---

## Part 2: Enforcing Specs on All Functions

### Challenge: Can We REQUIRE Specs?

**No built-in mechanism** to fail at load-time if a function lacks a spec.

**Available options**:

#### Option 1: Convention + Linting

**Approach**: Use clj-kondo to lint for missing specs.

```clojure
;; .clj-kondo/config.edn
{:linters {:missing-malli-schema {:level :error}}}
```

**Pros**:
- Non-invasive
- Works in CI/CD
- Fast feedback in editor

**Cons**:
- Requires separate tool
- Can be bypassed
- Not runtime enforced

**Verdict**: ⚠️ Good for CI, but not foolproof

#### Option 2: Namespace-Level Validation

**Approach**: Add validation function that checks all public vars.

```clojure
(ns seon.db.spec-validator
  (:require [malli.core :as m]))

(defn validate-namespace-schemas!
  "Verify all public functions in namespace have schemas.
   Throws if any are missing."
  [ns-sym]
  (let [publics (vals (ns-publics ns-sym))
        missing (remove (fn [v]
                          (or (not (fn? @v))  ;; Skip non-functions
                              (some-> v meta :malli/schema)
                              (get-in (m/function-schemas) [:clj ns-sym (symbol (name v))])))
                        publics)]
    (when (seq missing)
      (throw (ex-info "Functions missing Malli schemas"
                      {:namespace ns-sym
                       :missing-functions (map (comp symbol name) missing)})))))

;; Usage at end of namespace
(ns seon.trading.signals
  (:require [seon.db.spec-validator :as spec]))

;; All function definitions...

;; Validate at load time
(spec/validate-namespace-schemas! *ns*)
```

**Pros**:
- Enforced at load time
- Clear error messages
- No external tooling required

**Cons**:
- Adds boilerplate to each namespace
- Runtime overhead at load time (negligible)
- Can be forgotten

**Verdict**: ✅ **Recommended** for critical domain namespaces

#### Option 3: Custom `defn` Macro

**Approach**: Define custom `defn` that requires a schema.

```clojure
(ns seon.core.spec-fn
  (:require [malli.core :as m]))

(defmacro defn
  "Like clojure.core/defn but requires a :schema key in metadata."
  [name & args]
  (let [m (if (map? (first args)) (first args) {})
        schema (:schema m)]
    (when-not schema
      (throw (ex-info "defn requires :schema metadata"
                      {:function name :namespace *ns*})))
    `(do
       (clojure.core/defn ~name ~@args)
       (m/=> ~name ~schema))))

;; Usage
(ns seon.trading.signals
  (:refer-clojure :exclude [defn])
  (:require [seon.core.spec-fn :refer [defn]]))

(defn iv-rank
  {:schema [:=> [:cat QueryFn :string :int] [:maybe PercentileRank]]}
  [query ticker lookback]
  ...)
```

**Pros**:
- Compile-time enforcement
- Clear at function definition
- Natural Clojure idiom (metadata)

**Cons**:
- Requires excluding `clojure.core/defn`
- Can't use `defn-` (would need separate macro)
- Less familiar to new developers

**Verdict**: ⚠️ Possible but adds complexity

#### Option 4: `malli.experimental/defn` with Linting

**Approach**: Use `mx/defn` everywhere, lint for `clojure.core/defn` usage.

```clojure
;; .clj-kondo/config.edn
{:linters {:deprecated-var {clojure.core/defn {:level :error
                                                :message "Use mx/defn instead"}}}}

;; All domain namespaces
(ns seon.trading.signals
  (:refer-clojure :exclude [defn])
  (:require [malli.experimental :as mx :refer [defn]]))

(defn iv-rank :- [:maybe PercentileRank]
  "Calculate IV percentile rank"
  [query :- QueryFn
   ticker :- :string
   lookback :- :int]
  ...)
```

**Pros**:
- Beautiful syntax (inline schemas)
- Enforced via linting
- Auto-registers schemas
- Works with `mdev/start!`

**Cons**:
- Requires linter configuration
- Can be bypassed
- Different syntax from standard Clojure

**Verdict**: ✅ **Recommended** - best developer experience

### Recommended Approach: Hybrid

**For domain namespaces** (seon.trading.*, seon.health.*, etc.):

1. **Use `mx/defn` everywhere**
2. **Add namespace validation** as backup
3. **Lint for `clojure.core/defn`** usage

```clojure
(ns seon.trading.signals
  "Trading signal primitives - ALL functions MUST have schemas"
  (:refer-clojure :exclude [defn])
  (:require [malli.experimental :as mx :refer [defn]]
            [seon.db.spec-validator :as spec]))

;; Define all functions with mx/defn
(defn iv-rank :- [:maybe PercentileRank]
  [query :- QueryFn, ticker :- :string, lookback :- :int]
  ...)

;; Validate at load time
(spec/validate-namespace-schemas! *ns*)
```

**For utility namespaces** (seon.db.node, seon.config, etc.):

- Standard `clojure.core/defn` is fine
- Not all utilities need schemas
- Focus validation on domain boundaries

---

## Part 3: Query Result Validation Pattern

### The Problem

We want query results to be "obviously correct" - if a query returns malformed data, we want to know immediately.

**Challenge**: SQL queries return arbitrary shapes:

```clojure
;; Full table
(query "SELECT * FROM option_greeks")
;; => [{:xt/id "..." :asset/ticker "AAPL" :quote/iv 0.25 ...}]

;; Projection
(query "SELECT \"asset$ticker\", \"quote$iv\" FROM option_greeks")
;; => [{:asset/ticker "AAPL" :quote/iv 0.25}]

;; Aggregation
(query "SELECT \"asset$ticker\", AVG(\"quote$iv\") as avg_iv FROM option_greeks GROUP BY \"asset$ticker\"")
;; => [{:asset/ticker "AAPL" :avg-iv 0.28}]

;; Join
(query "SELECT a.*, b.signal FROM option_greeks a JOIN trading_signals b ON a.\"asset$ticker\" = b.ticker")
;; => [{:xt/id "..." :asset/ticker "AAPL" :quote/iv 0.25 :signal :buy}]
```

**How do we validate these?**

### Option 1: Column Registry (Rejected)

**Idea**: Register schemas for known column names, validate presence automatically.

```clojure
(def column-schemas
  {:asset/ticker :string
   :quote/iv [:double {:min 0 :max 5}]
   :greeks/delta [:double {:min -1 :max 1}]
   ...})

(defn validate-query-result [result]
  (for [row result
        [k v] row]
    (when-let [schema (get column-schemas k)]
      (m/validate schema v))))
```

**Problems**:
1. Doesn't know which columns SHOULD be present
2. Can't handle computed columns (`:avg-iv`)
3. Brittle - breaks when columns change
4. Performance overhead on every query

**Verdict**: ❌ **Rejected** - too inflexible

### Option 2: Table Schema Registry (Rejected)

**Idea**: Register schema per table, validate full-table queries.

```clojure
(def table-schemas
  {:option-greeks schema/OptionQuote
   :trading-signals schema/TradingSignal})

(defn query-with-validation [node sql table-key]
  (let [result (node/sql-query node sql)
        schema (get table-schemas table-key)]
    (when schema
      (doseq [row result]
        (when-not (m/validate schema row)
          (throw (ex-info "Invalid query result" {:row row :schema schema})))))
    result))
```

**Problems**:
1. Only works for `SELECT *` queries
2. Breaks on projections (subset of columns)
3. Breaks on joins (combined schemas)
4. Requires passing table name to every query

**Verdict**: ❌ **Rejected** - only works for trivial queries

### Option 3: Function Boundary Validation (RECOMMENDED)

**Idea**: Validate at domain function boundaries, NOT at query level.

**Pattern**:

```clojure
;; Query helper - NO validation
(defn- get-atm-ivs
  "Get ATM IVs for a ticker. Returns raw query results."
  [query ticker]
  (query "SELECT \"quote$iv\", _valid_from
          FROM option_greeks
          WHERE \"asset$ticker\" = ?
          AND \"greeks$delta\" BETWEEN 0.4 AND 0.6
          ORDER BY _valid_from ASC"
         [ticker]))

;; Domain function - VALIDATED
(defn iv-rank :- [:maybe PercentileRank]
  "Calculate IV percentile rank.

  Args:
    query - Query function (locked to specific time)
    ticker - Underlying symbol
    lookback - Days of history

  Returns:
    Percentile rank [0.0, 1.0] or nil if no data"
  [query :- QueryFn
   ticker :- :string
   lookback :- :int]
  (let [results (get-atm-ivs query ticker)
        ivs (map :quote/iv results)]  ;; Extract IVs
    (when (seq ivs)
      (calculate-percentile-rank ivs (last ivs)))))
```

**With instrumentation enabled**:

```clojure
(mdev/start!)

;; Valid call
(iv-rank query "SPY" 126)
;; => 0.73

;; Invalid input
(iv-rank query 123 "bad")
;; => ExceptionInfo: :malli.core/invalid-input
;;    {:input [:cat QueryFn :string :int]
;;     :args [#<function> 123 "bad"]}

;; Invalid output (from implementation bug)
(defn buggy-iv-rank [query ticker lookback]
  "Returns :high instead of number"
  :high)

(buggy-iv-rank query "SPY" 126)
;; => ExceptionInfo: :malli.core/invalid-output
;;    {:output [:maybe PercentileRank]
;;     :value :high}
```

**Why this works**:

1. **Queries are implementation details** - can return any shape
2. **Function contracts are the boundary** - inputs/outputs validated
3. **Flexible** - queries can be refactored without changing schemas
4. **Performant** - validation only at meaningful boundaries
5. **Clear errors** - know exactly which function violated contract

### Recommended Query Pattern

```clojure
;; 1. Define custom schema types
(def QueryFn
  "A function (sql, params?) -> results"
  [:=> [:cat :string [:? [:sequential :any]]] [:sequential :map]])

(def PercentileRank
  [:double {:min 0.0 :max 1.0}])

;; 2. Internal query helpers - NO schema needed
(defn- get-recent-ivs [query ticker]
  (query "SELECT ..."))

;; 3. Public domain functions - MUST have schema
(defn iv-rank :- [:maybe PercentileRank]
  [query :- QueryFn
   ticker :- :string
   lookback :- :int]
  (let [ivs (get-recent-ivs query ticker)]
    (calculate-percentile-rank ivs)))

;; 4. Enable instrumentation in dev
(mdev/start!)
```

---

## Part 4: Living Documentation Pattern

### The Goal

**Schemas should be the single source of truth** for:

1. What data flows through the system
2. What functions expect and return
3. How domains communicate

**Agents should be able to**:

1. Query available function schemas
2. Understand data shapes from schemas alone
3. Generate valid test data from schemas

### Implementation

#### 1. Queryable Schema Registry

```clojure
(ns seon.db.spec-docs
  "Schema documentation utilities for AI agents"
  (:require [malli.core :as m]
            [clojure.pprint :as pp]))

(defn list-function-schemas
  "List all registered function schemas.

  Returns:
    Map of {ns-sym {fn-sym {:schema ... :doc ...}}}"
  ([]
   (list-function-schemas :clj))
  ([key]
   (m/function-schemas key)))

(defn function-schema
  "Get schema for a specific function.

  Args:
    ns-sym - Namespace symbol
    fn-sym - Function symbol

  Returns:
    Schema or nil"
  [ns-sym fn-sym]
  (get-in (m/function-schemas) [:clj ns-sym fn-sym :schema]))

(defn explain-schema
  "Human-readable explanation of a schema.

  Args:
    schema - Malli schema

  Returns:
    String description"
  [schema]
  (with-out-str (pp/pprint (m/form schema))))

(defn domain-schemas
  "Get all schemas for a domain namespace.

  Args:
    domain - Domain keyword (:trading, :health, etc.)

  Returns:
    Map of function schemas"
  [domain]
  (let [ns-prefix (str "seon." (name domain))]
    (into {}
          (filter (fn [[ns-sym _]]
                    (clojure.string/starts-with? (str ns-sym) ns-prefix))
                  (m/function-schemas :clj)))))

;; Example usage
(domain-schemas :trading)
;; => {seon.trading.signals
;;     {iv-rank {:schema [:=> [:cat QueryFn :string :int] [:maybe PercentileRank]]
;;               :ns seon.trading.signals
;;               :name iv-rank}
;;      term-structure-slope {:schema ...}}}
```

#### 2. Schema-Driven Test Generation

```clojure
(ns seon.testing.generators
  (:require [malli.generator :as mg]
            [malli.core :as m]
            [seon.db.spec-docs :as docs]))

(defn generate-function-args
  "Generate valid arguments for a function.

  Args:
    ns-sym - Namespace symbol
    fn-sym - Function symbol

  Returns:
    Vector of generated arguments"
  [ns-sym fn-sym]
  (when-let [schema (docs/function-schema ns-sym fn-sym)]
    (let [[_=> input _output] schema]
      (mg/generate input))))

;; Example
(generate-function-args 'seon.trading.signals 'iv-rank)
;; => [#<function> "AAPL" 252]
```

#### 3. Markdown Documentation Generation

```clojure
(ns seon.docs.schema-gen
  "Generate markdown documentation from schemas"
  (:require [malli.core :as m]
            [clojure.string :as str]
            [seon.db.spec-docs :as docs]))

(defn schema->markdown
  "Convert function schema to markdown documentation.

  Args:
    ns-sym - Namespace symbol
    fn-sym - Function symbol

  Returns:
    Markdown string"
  [ns-sym fn-sym]
  (when-let [{:keys [schema]} (get-in (m/function-schemas)
                                      [:clj ns-sym fn-sym])]
    (let [[_=> input output] schema
          [_cat & args] input
          var (ns-resolve ns-sym fn-sym)
          doc (:doc (meta var))]
      (str/join "\n"
        ["## " fn-sym
         ""
         doc
         ""
         "**Schema**: `" (pr-str schema) "`"
         ""
         "**Arguments**:"
         ""
         (str/join "\n" (map-indexed
                          (fn [i arg] (str "- arg" i ": `" (pr-str arg) "`"))
                          args))
         ""
         "**Returns**: `" (pr-str output) "`"]))))

;; Generate documentation
(spit "docs/api/trading-signals.md"
      (str/join "\n\n"
        (for [[fn-sym _] (get (m/function-schemas :clj) 'seon.trading.signals)]
          (schema->markdown 'seon.trading.signals fn-sym))))
```

#### 4. Agent Query Interface

```clojure
(ns seon.agent.schema-query
  "Interface for AI agents to query schemas"
  (:require [seon.db.spec-docs :as docs]
            [malli.core :as m]))

(defn what-does-this-return?
  "What does this function return?

  Usage: (what-does-this-return? 'seon.trading.signals 'iv-rank)
  Returns: [:maybe [:double {:min 0.0 :max 1.0}]]"
  [ns-sym fn-sym]
  (when-let [schema (docs/function-schema ns-sym fn-sym)]
    (let [[_=> _input output] schema]
      output)))

(defn what-does-this-take?
  "What arguments does this function take?

  Usage: (what-does-this-take? 'seon.trading.signals 'iv-rank)
  Returns: [QueryFn :string :int]"
  [ns-sym fn-sym]
  (when-let [schema (docs/function-schema ns-sym fn-sym)]
    (let [[_=> input _output] schema
          [_cat & args] input]
      args)))

(defn find-functions-returning
  "Find all functions that return a specific type.

  Usage: (find-functions-returning :double)
  Returns: list of [ns-sym fn-sym] pairs"
  [type-schema]
  (for [[ns-sym fns] (m/function-schemas :clj)
        [fn-sym {:keys [schema]}] fns
        :let [[_=> _input output] schema]
        :when (= type-schema (m/type output))]
    [ns-sym fn-sym]))
```

### Agent Usage Example

```clojure
;; Agent wants to know what IV rank returns
(require '[seon.agent.schema-query :as sq])

(sq/what-does-this-return? 'seon.trading.signals 'iv-rank)
;; => [:maybe [:double {:min 0.0 :max 1.0}]]

;; Agent wants to find all functions that return doubles
(sq/find-functions-returning :double)
;; => [[seon.trading.signals iv-rank]
;;     [seon.trading.signals gamma-rent]
;;     ...]

;; Agent wants to generate test data
(require '[seon.testing.generators :as gen])

(gen/generate-function-args 'seon.trading.signals 'iv-rank)
;; => [#<function> "MSFT" 126]
```

---

## Part 5: Trading Namespace Example

### Before: Current State

```clojure
(ns seon.trading.signals
  (:require [seon.db.node :as node]
            [xtdb.api :as xt]))

;; No schema
(defn iv-rank [db ticker lookback & [opts]]
  (let [ticker-str (name ticker)
        query-opts {:current-time (:as-of opts)}
        results (node/query db
                  (xt/template
                    (-> (from :option-greeks [asset/ticker quote/iv greeks/delta])
                        (where (= asset/ticker ~ticker-str)
                               (> greeks/delta 0.4)
                               (< greeks/delta 0.6))))
                  query-opts)
        ivs (map :quote/iv results)]
    (when (seq ivs)
      (calculate-percentile-rank ivs (last ivs)))))

;; Problems:
;; - No schema documentation
;; - Takes XTDB node (hard to mock)
;; - Takes :as-of in opts (temporal concern leaks)
;; - No validation - "bad" inputs cause runtime errors
;; - Uses XTQL (harder for LLMs than SQL)
```

### After: Spec-First Pattern

```clojure
(ns seon.trading.signals
  "Trading signal primitives for the reasoning agent.

  ALL functions have Malli schemas and are instrumented in dev."
  (:refer-clojure :exclude [defn])
  (:require [malli.experimental :as mx :refer [defn]]
            [seon.db.spec-validator :as spec]))

;;; ---------------------------------------------------------------------------
;;; Custom Schema Types
;;; ---------------------------------------------------------------------------

(def QueryFn
  "A function that executes SQL queries.

   Args: (sql, params?)
   Returns: vector of maps"
  [:=> [:cat :string [:? [:sequential :any]]] [:sequential :map]])

(def PercentileRank
  "Percentile rank value"
  [:double {:min 0.0 :max 1.0}])

;;; ---------------------------------------------------------------------------
;;; Internal Helpers (no schema required)
;;; ---------------------------------------------------------------------------

(defn- get-atm-ivs
  "Get ATM implied volatilities for a ticker."
  [query ticker]
  (query "SELECT \"quote$iv\", _valid_from
          FROM option_greeks
          WHERE \"asset$ticker\" = ?
          AND \"greeks$delta\" BETWEEN 0.4 AND 0.6
          ORDER BY _valid_from ASC"
         [ticker]))

;;; ---------------------------------------------------------------------------
;;; Public API (schemas required)
;;; ---------------------------------------------------------------------------

(defn iv-rank :- [:maybe PercentileRank]
  "Calculate the percentile rank of current IV vs historical.

  Queries ATM (delta 0.4-0.6) options to get representative IV,
  then calculates what percentile the current IV is vs all historical values.

  Used for: Volatility arbitrage signals

  Args:
    query - Query function (locked to specific time)
    ticker - Underlying symbol
    lookback - Lookback period in days (currently ignored)

  Returns:
    Percentile rank [0.0, 1.0] or nil if no data"
  [query :- QueryFn
   ticker :- :string
   lookback :- :int]
  (let [results (get-atm-ivs query ticker)
        ivs (map :quote/iv results)]
    (if (seq ivs)
      (calculate-percentile-rank ivs (last ivs))
      nil)))

(defn term-structure-slope :- [:maybe :double]
  "Calculate the slope of the IV term structure.

  Positive slope = contango (far > near)
  Negative slope = backwardation (near > far)

  Used for: Calendar spread signals

  Args:
    query - Query function
    ticker - Underlying symbol

  Returns:
    Slope value (far IV - near IV) / days between, or nil"
  [query :- QueryFn
   ticker :- :string]
  (let [results (query "SELECT \"quote$iv\", \"option$expiry\"
                        FROM option_greeks
                        WHERE \"asset$ticker\" = ?
                        AND \"greeks$delta\" BETWEEN 0.45 AND 0.55
                        ORDER BY \"option$expiry\" ASC
                        LIMIT 10"
                       [ticker])]
    (when (>= (count results) 2)
      (let [sorted (sort-by :option/expiry results)
            near (first sorted)
            far (last sorted)
            days-between (.between java.time.temporal.ChronoUnit/DAYS
                                   (:option/expiry near)
                                   (:option/expiry far))]
        (when (pos? days-between)
          (/ (- (:quote/iv far) (:quote/iv near)) days-between))))))

;; Validate all functions have schemas at load time
(spec/validate-namespace-schemas! *ns*)
```

### Benefits

1. **Self-documenting**: Schema IS the documentation
2. **Validated**: Invalid calls caught immediately in dev (via `mdev/start!`)
3. **Testable**: Easy to mock `query` function for tests
4. **Temporal-agnostic**: Domain code doesn't know about time
5. **LLM-friendly**: SQL queries, clear types
6. **Agent-readable**: Schemas queryable programmatically

---

## Part 6: Recommended Approach

### Development Workflow

#### 1. Enable Instrumentation in Dev

**Update `dev/user.clj`**:

```clojure
(ns user
  (:require [integrant.repl :as ig-repl]
            [malli.dev :as mdev]
            [malli.dev.pretty :as pretty]))

(defn go
  "Start the Integrant system with Malli instrumentation."
  []
  (ig-repl/go)
  ;; Enable Malli instrumentation after system starts
  (mdev/start! {:report (pretty/reporter)})
  (println "System started with Malli instrumentation enabled"))

(defn halt
  "Stop the system and disable instrumentation."
  []
  (mdev/stop!)
  (ig-repl/halt))
```

#### 2. Create Schema Validator Utility

**Create `src/seon/db/spec_validator.clj`**:

```clojure
(ns seon.db.spec-validator
  "Validation utilities for function schemas"
  (:require [malli.core :as m]))

(defn validate-namespace-schemas!
  "Verify all public functions in namespace have schemas.

   Call this at the end of domain namespaces to enforce schemas.

   Args:
     ns-sym - Namespace symbol (usually *ns*)

   Throws:
     ExceptionInfo if any public functions lack schemas"
  [ns-sym]
  (let [publics (vals (ns-publics ns-sym))
        fns (filter (fn [v] (fn? @v)) publics)
        missing (remove (fn [v]
                          (or (some-> v meta :malli/schema)
                              (get-in (m/function-schemas)
                                      [:clj ns-sym (symbol (name v))])))
                        fns)]
    (when (seq missing)
      (throw (ex-info "Functions missing Malli schemas"
                      {:namespace ns-sym
                       :missing-functions (mapv (comp symbol name) missing)
                       :hint "Add schemas using mx/defn or m/=>"})))))
```

#### 3. Create Domain Namespace Template

**File**: `docs/templates/domain-namespace.clj`

```clojure
(ns seon.domains.example
  "Example domain namespace showing spec-first pattern.

  ALL public functions MUST have Malli schemas."
  (:refer-clojure :exclude [defn])
  (:require [malli.experimental :as mx :refer [defn]]
            [seon.db.spec-validator :as spec]))

;;; ---------------------------------------------------------------------------
;;; Custom Schema Types
;;; ---------------------------------------------------------------------------

(def QueryFn
  "A function that executes SQL queries and returns results"
  [:=> [:cat :string [:? [:sequential :any]]] [:sequential :map]])

(def CustomType
  "Custom domain type"
  [:map
   [:field1 :string]
   [:field2 :int]])

;;; ---------------------------------------------------------------------------
;;; Internal Helpers (no schema required)
;;; ---------------------------------------------------------------------------

(defn- helper-fn
  "Internal helper - no schema needed"
  [arg1 arg2]
  ...)

;;; ---------------------------------------------------------------------------
;;; Public API (schemas REQUIRED)
;;; ---------------------------------------------------------------------------

(defn domain-function :- ReturnType
  "Public function documentation.

  Args:
    query - Query function (locked to specific time)
    arg1 - First argument

  Returns:
    Description of return value"
  [query :- QueryFn
   arg1 :- :string]
  (let [data (helper-fn query arg1)]
    ...))

;; Validate all public functions have schemas
(spec/validate-namespace-schemas! *ns*)
```

#### 4. Update Existing Trading Namespace

**Migration checklist**:

- [ ] Add `(:refer-clojure :exclude [defn])`
- [ ] Add `[malli.experimental :as mx :refer [defn]]`
- [ ] Add `[seon.db.spec-validator :as spec]`
- [ ] Define custom schema types (QueryFn, PercentileRank, etc.)
- [ ] Convert all `defn` to `mx/defn` with inline schemas
- [ ] Remove `:as-of` parameters (handled by query function)
- [ ] Convert XTQL queries to SQL where appropriate
- [ ] Add `(spec/validate-namespace-schemas! *ns*)` at end
- [ ] Test in REPL with `mdev/start!` enabled

#### 5. Configure Linting (Optional)

**.clj-kondo/config.edn**:

```clojure
{:linters
 {:deprecated-var
  {clojure.core/defn
   {:level :warning
    :message "Use mx/defn in domain namespaces for automatic schema registration"
    :namespaces [seon.trading.*
                 seon.health.*
                 seon.finance.*
                 seon.tasks.*]}}}
```

### Testing Pattern

```clojure
(ns seon.trading.signals-test
  (:require [clojure.test :refer [deftest testing is]]
            [seon.trading.signals :as sig]
            [malli.core :as m]
            [malli.generator :as mg]))

(deftest iv-rank-test
  (testing "valid inputs"
    (let [mock-query (fn [sql params]
                       [{:quote/iv 0.20}
                        {:quote/iv 0.25}
                        {:quote/iv 0.30}])
          result (sig/iv-rank mock-query "SPY" 126)]
      (is (number? result))
      (is (<= 0.0 result 1.0))))

  (testing "schema validation"
    ;; With instrumentation enabled, this throws
    (when (resolve 'malli.dev/start!)
      (is (thrown? clojure.lang.ExceptionInfo
            (sig/iv-rank "not-a-function" 123 "bad")))))

  (testing "property-based testing"
    ;; Generate valid arguments from schema
    (let [schema (get-in (m/function-schemas)
                         [:clj 'seon.trading.signals 'iv-rank :schema])
          [_=> input _output] schema
          mock-query (fn [_ _] [{:quote/iv 0.25}])]
      (dotimes [_ 100]
        (let [[_query ticker lookback] (mg/generate input)
              ;; Replace generated query fn with mock
              result (sig/iv-rank mock-query ticker lookback)]
          (is (or (nil? result) (<= 0.0 result 1.0))))))))
```

---

## Part 7: Column Registry for Query Validation (Bonus)

While we don't validate query results automatically, we CAN provide a column registry for **documentation and testing**.

```clojure
(ns seon.db.column-registry
  "Registry of known database columns and their schemas.

  Used for:
  - Documentation (what columns exist?)
  - Testing (generate valid data)
  - Query hints (suggest valid columns)")

(def columns
  "Map of column keyword -> Malli schema"
  {;; Asset columns
   :asset/ticker [:string {:min 1 :max 10}]

   ;; Quote columns
   :quote/bid [:double {:min 0}]
   :quote/ask [:double {:min 0}]
   :quote/iv [:double {:min 0.01 :max 5.0}]

   ;; Greeks columns
   :greeks/delta [:double {:min -1.0 :max 1.0}]
   :greeks/gamma [:double {:min 0.0 :max 1.0}]
   :greeks/vega [:double {:min 0.0 :max 100.0}]
   :greeks/theta [:double {:min -10.0 :max 10.0}]

   ;; Option columns
   :option/id :string
   :option/strike [:double {:min 0}]
   :option/type [:enum :call :put]
   :option/expiry inst?

   ;; Market columns
   :market/volume [:int {:min 0}]
   :market/aggressor [:enum :buy :sell]})

(defn validate-row
  "Validate a query result row against known column schemas.

  Only validates columns that exist in the registry - unknown columns pass through.

  Args:
    row - Map from query result

  Returns:
    Map of {:valid? bool :errors [...]} "
  [row]
  (let [errors (for [[k v] row
                     :let [schema (get columns k)]
                     :when schema
                     :when (not (m/validate schema v))]
                 {:column k
                  :value v
                  :expected schema
                  :error (m/explain schema v)})]
    {:valid? (empty? errors)
     :errors errors}))

(defn suggest-columns
  "Suggest column names for a table.

  Args:
    table-keyword - Table name keyword (e.g. :option-greeks)

  Returns:
    Vector of column keywords"
  [table-keyword]
  (case table-keyword
    :option-greeks [:xt/id :asset/ticker :option/id :option/strike :option/type
                    :option/expiry :quote/bid :quote/ask :quote/iv
                    :greeks/delta :greeks/gamma :greeks/vega :greeks/theta
                    :market/volume :market/aggressor]
    :trading-signals [:xt/id :signal/ticker :signal/action :signal/strategy
                      :signal/confidence :signal/timestamp]
    []))

(defn column-schema
  "Get schema for a specific column.

  Args:
    col-keyword - Column keyword

  Returns:
    Malli schema or nil"
  [col-keyword]
  (get columns col-keyword))
```

**Usage**:

```clojure
;; Validate query results in tests
(let [result (query "SELECT * FROM option_greeks LIMIT 1")
      validation (validate-row (first result))]
  (when-not (:valid? validation)
    (println "Invalid data:" (:errors validation))))

;; Suggest columns for agent
(suggest-columns :option-greeks)
;; => [:xt/id :asset/ticker :quote/iv :greeks/delta ...]

;; Get schema for specific column
(column-schema :quote/iv)
;; => [:double {:min 0.01 :max 5.0}]
```

---

## Part 8: Summary & Decision Points

### Key Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| **Function schema format** | `malli.experimental/defn` with inline schemas | Best DX, auto-registration, clear syntax |
| **Schema enforcement** | Namespace validation + linting | Catches missing schemas at load time |
| **Query validation** | Function boundary validation only | Flexible, performant, clear errors |
| **Instrumentation** | `malli.dev/start!` in dev, disabled in prod | Zero prod overhead, immediate REPL feedback |
| **Documentation** | Schemas + generated markdown | Living docs, agent-queryable |
| **Testing** | Property-based + manual mocks | Leverage schemas for test generation |

### Implementation Phases

**Phase 1: Foundation** (1-2 days)
- [ ] Add `malli.dev/start!` to `dev/user.clj`
- [ ] Create `seon.db.spec-validator` namespace
- [ ] Create domain namespace template
- [ ] Document patterns in CLAUDE.md

**Phase 2: Trading Migration** (2-3 days)
- [ ] Define custom schema types (QueryFn, PercentileRank, etc.)
- [ ] Convert `seon.trading.signals` to `mx/defn`
- [ ] Convert `seon.trading.analysis` to `mx/defn`
- [ ] Update tests to use mock query functions
- [ ] Verify all tests pass with instrumentation

**Phase 3: Tooling** (1-2 days)
- [ ] Create schema documentation utilities
- [ ] Create agent query interface
- [ ] Add column registry (optional)
- [ ] Configure clj-kondo linting

**Phase 4: Other Domains** (as needed)
- [ ] Apply pattern to health domain
- [ ] Apply pattern to finance domain
- [ ] Apply pattern to tasks domain

### Success Metrics

- **100% schema coverage** in domain namespaces
- **Zero schema violations** in dev REPL (via instrumentation)
- **Reduced debugging time** - invalid data caught at boundaries
- **Faster agent development** - schemas provide clear contracts
- **Living documentation** - schemas always up to date

---

## Appendix A: Complete Trading Signals Example

See next section for full before/after comparison of `seon.trading.signals` namespace.

---

## Appendix B: Reference Links

### Malli Documentation

- [Malli Function Schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)
- [Malli Development Mode](https://github.com/metosin/malli#development-instrumentation)
- [Malli Experimental](https://github.com/metosin/malli/blob/master/docs/experimental.md)

### Seon Documentation

- `/Users/sean/src/seon/docs/prds/sql-migration/research/malli-data-flow.md` - Prior Malli research
- `/Users/sean/src/seon/docs/prds/test-coverage-audit/research/malli-instrumentation.md` - Instrumentation patterns
- `/Users/sean/src/seon/src/seon/db/schema.clj` - Existing schema definitions

---

## Appendix C: Full Trading Signals Migration

### Before

```clojure
(ns seon.trading.signals
  (:require [seon.db.queries :as q]
            [seon.db.node :as node]
            [xtdb.api :as xt]))

(defn- calculate-percentile-rank
  [values current-value]
  (when (and (seq values) current-value)
    (let [below-count (count (filter #(<= % current-value) values))]
      (/ (double below-count) (count values)))))

(defn iv-rank [db ticker lookback & [opts]]
  (let [ticker-str (name ticker)
        query-opts {:current-time (:as-of opts)}
        results (node/query db
                  (xt/template
                    (-> (from :option-greeks
                              [asset/ticker quote/iv greeks/delta xt/valid-from])
                        (where (= asset/ticker ~ticker-str)
                               (> greeks/delta 0.4)
                               (< greeks/delta 0.6))))
                  query-opts)
        historical-ivs (map :quote/iv results)]
    (if (seq historical-ivs)
      (let [sorted-results (sort-by :xt/valid-from results)
            current-iv (:quote/iv (last sorted-results))]
        (or (calculate-percentile-rank historical-ivs current-iv) 0.5))
      0.5)))

(defn term-structure-slope [db ticker & [opts]]
  (let [query-opts {:current-time (:as-of opts)}
        term-struct (q/iv-term-structure db ticker query-opts)]
    (if (>= (count term-struct) 2)
      (let [sorted (sort-by :expiry term-struct)
            near (:expiry (first sorted))
            far (:expiry (last sorted))
            near-iv (:iv (first sorted))
            far-iv (:iv (last sorted))
            days-between (.between java.time.temporal.ChronoUnit/DAYS near far)]
        (if (pos? days-between)
          (/ (- far-iv near-iv) days-between)
          0.0))
      0.0)))

(defn skew-index [db ticker & [opts]]
  (let [query-opts {:current-time (:as-of opts)}
        puts (q/options-by-delta db ticker :put -0.30 -0.20 query-opts)
        calls (q/options-by-delta db ticker :call 0.20 0.30 query-opts)]
    (if (and (seq puts) (seq calls))
      (let [avg-put-iv (/ (reduce + (map :quote/iv puts)) (count puts))
            avg-call-iv (/ (reduce + (map :quote/iv calls)) (count calls))]
        (- avg-put-iv avg-call-iv))
      0.0)))
```

### After

```clojure
(ns seon.trading.signals
  "Trading signal primitives for the reasoning agent.

  Provides executable functions that the LLM can compose:
  - iv-rank: IV percentile rank
  - term-structure-slope: IV term structure slope
  - skew-index: 25-delta put/call IV spread

  Each primitive uses a query function locked to a specific time
  and returns a numeric result.

  ALL PUBLIC FUNCTIONS MUST HAVE MALLI SCHEMAS."
  (:refer-clojure :exclude [defn])
  (:require [malli.experimental :as mx :refer [defn]]
            [seon.db.spec-validator :as spec]))

;;; ---------------------------------------------------------------------------
;;; Custom Schema Types
;;; ---------------------------------------------------------------------------

(def QueryFn
  "A function that executes SQL queries and returns results.

   The query function is locked to a specific point in time for
   temporal isolation (agents can't see future data).

   Args:
     sql - SQL query string
     params - Optional vector of query parameters

   Returns:
     Vector of maps (query results)"
  [:=> [:cat :string [:? [:sequential :any]]] [:sequential :map]])

(def PercentileRank
  "Percentile rank value between 0.0 (lowest) and 1.0 (highest)"
  [:double {:min 0.0 :max 1.0}])

;;; ---------------------------------------------------------------------------
;;; Internal Helper Functions (no schema required)
;;; ---------------------------------------------------------------------------

(defn- calculate-percentile-rank
  "Calculate what percentile a value is in a sequence."
  [values current-value]
  (when (and (seq values) current-value)
    (let [below-count (count (filter #(<= % current-value) values))]
      (/ (double below-count) (count values)))))

(defn- get-atm-ivs
  "Get ATM implied volatilities for a ticker."
  [query ticker]
  (query "SELECT \"quote$iv\", _valid_from
          FROM option_greeks
          WHERE \"asset$ticker\" = ?
          AND \"greeks$delta\" BETWEEN 0.4 AND 0.6
          ORDER BY _valid_from ASC"
         [ticker]))

(defn- get-iv-term-structure
  "Get IV term structure (IV by expiry) for ATM options."
  [query ticker]
  (query "SELECT \"option$expiry\" as expiry, AVG(\"quote$iv\") as iv
          FROM option_greeks
          WHERE \"asset$ticker\" = ?
          AND \"greeks$delta\" BETWEEN 0.45 AND 0.55
          GROUP BY \"option$expiry\"
          ORDER BY \"option$expiry\" ASC"
         [ticker]))

(defn- get-options-by-delta
  "Get options filtered by delta range."
  [query ticker option-type min-delta max-delta]
  (query "SELECT \"quote$iv\"
          FROM option_greeks
          WHERE \"asset$ticker\" = ?
          AND \"option$type\" = ?
          AND \"greeks$delta\" BETWEEN ? AND ?"
         [ticker (name option-type) min-delta max-delta]))

;;; ---------------------------------------------------------------------------
;;; Public API (schemas REQUIRED)
;;; ---------------------------------------------------------------------------

(defn iv-rank :- [:maybe PercentileRank]
  "Calculate the percentile rank of current IV vs historical.

  Queries ATM (delta 0.4-0.6) options to get representative IV,
  then calculates what percentile the current IV is vs all historical values.

  Used for: Volatility arbitrage signals

  Args:
    query - Query function (locked to specific time)
    ticker - Underlying symbol
    lookback - Lookback period in days (currently ignored)

  Returns:
    Percentile rank [0.0, 1.0], or nil if no data

  Example:
    (iv-rank query \"SPY\" 126)
    ;; => 0.73  (IV is at 73rd percentile - relatively high)"
  [query :- QueryFn
   ticker :- :string
   lookback :- :int]
  (let [results (get-atm-ivs query ticker)
        ivs (map :quote/iv results)]
    (when (seq ivs)
      (let [sorted-results (sort-by :_valid-from results)
            current-iv (:quote/iv (last sorted-results))]
        (calculate-percentile-rank ivs current-iv)))))

(defn term-structure-slope :- [:maybe :double]
  "Calculate the slope of the IV term structure.

  Positive slope = contango (far > near)
  Negative slope = backwardation (near > far)

  Used for: Calendar spread signals

  Args:
    query - Query function (locked to specific time)
    ticker - Underlying symbol

  Returns:
    Slope value (far IV - near IV) / days between, or nil if insufficient data

  Example:
    (term-structure-slope query \"SPY\")
    ;; => 0.0002  (Positive slope - far months more expensive)"
  [query :- QueryFn
   ticker :- :string]
  (let [term-struct (get-iv-term-structure query ticker)]
    (when (>= (count term-struct) 2)
      (let [sorted (sort-by :expiry term-struct)
            near (first sorted)
            far (last sorted)
            days-between (.between java.time.temporal.ChronoUnit/DAYS
                                   (:expiry near)
                                   (:expiry far))]
        (when (pos? days-between)
          (/ (- (:iv far) (:iv near)) days-between))))))

(defn skew-index :- [:maybe :double]
  "Calculate the volatility skew (25-delta put IV - 25-delta call IV).

  High skew = expensive downside protection (fear in market)
  Low skew = cheap protection (complacency)

  Used for: Skew trading, risk reversals

  Args:
    query - Query function (locked to specific time)
    ticker - Underlying symbol

  Returns:
    Skew value (put IV - call IV), or nil if insufficient data

  Example:
    (skew-index query \"SPY\")
    ;; => 0.05  (Puts are 5% more expensive than calls - elevated fear)"
  [query :- QueryFn
   ticker :- :string]
  (let [puts (get-options-by-delta query ticker :put -0.30 -0.20)
        calls (get-options-by-delta query ticker :call 0.20 0.30)]
    (when (and (seq puts) (seq calls))
      (let [avg-put-iv (/ (reduce + (map :quote/iv puts)) (count puts))
            avg-call-iv (/ (reduce + (map :quote/iv calls)) (count calls))]
        (- avg-put-iv avg-call-iv)))))

;;; ---------------------------------------------------------------------------
;;; Schema Validation
;;; ---------------------------------------------------------------------------

;; Validate that all public functions have schemas
(spec/validate-namespace-schemas! *ns*)
```

### Key Improvements

1. **Schemas on all public functions** - `mx/defn` with inline type annotations
2. **No temporal leakage** - `query` function handles `:as-of`, domain code doesn't care
3. **SQL instead of XTQL** - More familiar to LLMs and humans
4. **Better documentation** - Schemas + docstrings + examples
5. **Testable** - Easy to mock `query` function
6. **Validated** - With `mdev/start!`, invalid calls caught immediately

---

## Conclusion

The spec-first query interface pattern provides:

1. **Enforcement** - Namespace validation ensures all functions have schemas
2. **Documentation** - Schemas are the source of truth
3. **Validation** - Instrumentation catches errors at boundaries
4. **Simplicity** - Clear patterns, low cognitive load
5. **Flexibility** - Query results can be any shape, validated at function boundaries

**Next steps**:
1. Implement foundation (spec-validator, update dev/user.clj)
2. Migrate trading namespace
3. Create domain namespace template
4. Document patterns in CLAUDE.md
