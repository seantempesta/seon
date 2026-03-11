# Research: Strategy DSL Design

**Status:** Complete
**Researcher:** Claude (Sonnet 4.5)
**Date:** 2025-12-19

---

## Research Question

How should trading strategies be represented as data (not code)?

---

## Requirements

1. **Pure Data** - No code, just data structures
   - Safer (can't execute arbitrary code)
   - Serializable (store in XTDB)
   - Validatable (Malli schemas)

2. **Relative Metrics Only** - Conditions reference normalized values
   - Prevents future-peeking
   - Regime-independent

3. **Multi-Ticker Support** - Eventually support:
   - Pairs trading (SPY vs QQQ)
   - Sector rotation
   - Index vs constituents

4. **Configurable Lookbacks** - Per-condition lookback windows

---

## External Systems Research

### 1. QuantConnect / Lean

**Key Findings:**
- Uses an **Algorithm Framework** with modular components that separate concerns
- Strategies are built from composable modules: Universe Selection -> Alpha Model -> Portfolio Construction -> Risk Management -> Execution
- Trading signals are represented as **Insight objects** - discrete data objects with:
  - Direction (up/down/flat)
  - Magnitude
  - Confidence
  - Period (validity duration)
- Alpha models generate Insight objects that flow through the pipeline
- This separation means entry/exit logic is distributed across modules rather than in one place

**Relevant to Seon:**
- The Insight concept is similar to our signal approach - signals as data, not code
- Separation of signal generation from position sizing and risk management
- Validity periods on signals prevent stale signal execution

**Source:** [QuantConnect Algorithm Framework](https://www.quantconnect.com/docs/v2/writing-algorithms/algorithm-framework/overview)

### 2. Zipline / Quantopian Pipeline API

**Key Findings:**
- **Factor-based** approach - everything is a Factor (numerical output) or Filter (boolean output)
- Factors are composable: `SMA(20) > SMA(50)` creates a Filter
- Custom factors define `compute()` method with declared `inputs` and `window_length`
- Pipeline optimizes computations across the entire backtest period
- Factors can reference other factors, creating a dependency graph

**Example Factor Structure:**

```python
class Returns(CustomFactor):
    inputs = [USEquityPricing.close]
    window_length = 2

    def compute(self, today, assets, out, closes):
        out[:] = (closes[-1] - closes[0]) / closes[-1]

```

**Relevant to Seon:**
- Factor composition model - combine simple factors into complex ones
- Explicit declaration of inputs and lookback (window_length)
- Clear distinction between numerical factors and boolean filters
- Pipeline dependency resolution - factors reference other factors

**Source:** [Zipline Pipeline Factors](https://github.com/quantopian/zipline/blob/master/zipline/pipeline/factors/factor.py)

### 3. TradingView Pine Script

**Key Findings:**
- **Most intuitive** for non-programmers - reads like English
- Strategy structure:
  1. Version declaration
  2. Strategy setup (name, capital, commission)
  3. Input parameters (configurable)
  4. Logic/calculations (indicators)
  5. Order management (entry/exit)
- Entry/exit via `strategy.entry()`, `strategy.close()`, `strategy.exit()`
- Conditions combine naturally: `long_condition = ta.crossover(sma14, sma28)`
- Exit can specify stop-loss, profit target, trailing stop as parameters

**Pine Script Condition Example:**

```pine
long_condition = ta.crossover(ta.sma(close, 14), ta.sma(close, 28))
if (long_condition)
    strategy.entry("Long", strategy.long)

```

**Relevant to Seon:**
- Intuitive condition syntax - operator methods like `.crosses_above()`
- Exit conditions as parameters (stop-loss, profit target) rather than separate rules
- Input parameters make strategies configurable without code changes
- Clear separation of indicators vs signals vs orders

**Source:** [Pine Script Strategies](https://www.tradingview.com/pine-script-docs/concepts/strategies/)

### 4. Backtrader

**Key Findings:**
- **Signal-based** approach as alternative to full strategy classes
- Signals are Indicator subclasses that produce +1 (buy), -1 (sell), or 0 (hold)
- Five signal types in two groups (main signals and short signals)
- `SignalStrategy` class simplifies strategy definition via `signal_add()`
- Indicators have explicit `lines` and `params` declarations

**Signal Indicator Example:**

```python
class MySignal(bt.Indicator):
    lines = ('signal',)
    params = (('period', 30),)

    def __init__(self):
        self.lines.signal = self.data - bt.indicators.SMA(period=self.p.period)

```

**Combining Signals:**

```python
buy_sig = bt.And(close_over_sma, close_over_ema, sma_ema_diff > 0)

```

**Relevant to Seon:**
- `bt.And()` and `bt.Or()` for combining conditions - this is key!
- Encapsulating logic in Indicator when it gets complex
- Signal types (LONG, SHORT, LONGEXIT, SHORTEXIT) map to entry/exit
- Minimum period concept - strategy matures once sufficient data exists

**Source:** [Backtrader Signal Strategy](https://www.backtrader.com/docu/signal_strategy/signal_strategy/)

### 5. QuantJourney Strategy Definition Language (SDL)

**Key Findings:**
- **Declarative DSL** for Python - strategies read like plain English
- Reduced prototyping time by 50%
- Condition logic uses `&` for AND, supports OR
- Method chaining: `SMA(20).crosses_above(SMA(50))`
- Risk controls embedded: `with_stop_loss()`

**Example:**

```python
# AND operations
(condition_1) & (condition_2) & (condition_3)

# Entry with fluent API
SMA(20).crosses_above(SMA(50))

```

**Relevant to Seon:**
- Fluent method chaining is very readable
- `&` operator for AND is concise
- Embedding risk controls directly in strategy definition

**Source:** [QuantJourney SDL](https://quantjourney.substack.com/p/introducing-the-quantjourneys-strategy)

---

## Key Design Insights

### Common Patterns Across Systems

1. **Conditions are combinable** - All systems support AND/OR logic
2. **Signals are data** - Discrete objects with direction, magnitude, confidence
3. **Lookback is explicit** - Window/period is always declared upfront
4. **Entry and exit are separate** - Different rules for opening vs closing positions
5. **Risk limits are parameters** - Not logic, just configuration
6. **Factors compose** - Simple factors combine into complex ones

### Critical Design Decision: Condition Combinators

All systems handle condition combination differently:

| System | AND | OR | Nesting |
|--------|-----|-----|---------|
| Backtrader | `bt.And()` | `bt.Or()` | Supported |
| Pine Script | `and` keyword | `or` keyword | Natural |
| Zipline | `&` operator | `|` operator | Via precedence |
| SDL | `&` operator | Supported | Via parens |

**Recommendation:** Use nested prefix notation (like Clojure) for clarity:

```clojure
[:and condition-1 condition-2 [:or condition-3 condition-4]]

```

This is:
- Pure data (vectors and keywords)
- Infinitely nestable
- Easy to validate recursively
- Natural in Clojure

---

## Recommended Design

### Core Principle: Expressions as Data

Drawing inspiration from **Lisp/Clojure's homoiconicity**, represent conditions as nested vectors where the first element is the operator:

```clojure
;; Simple condition
[:metric/iv-rank :> 0.80]

;; Combined with AND (implicit when multiple in vector of conditions)
[[:metric/iv-rank :> 0.80]
 [:metric/skew-index :> 0.04]]

;; Explicit AND/OR
[:and
 [:metric/iv-rank :> 0.80]
 [:or
  [:metric/skew-index :> 0.04]
  [:metric/term-slope :< 0.0]]]

```

### Condition Structure

A condition is a 3-tuple: `[metric operator value]`

```clojure
;; Basic condition
[:metric/iv-rank :> 0.80]

;; With lookback override
{:metric :metric/iv-rank
 :op :>
 :value 0.80
 :lookback 126}  ; 6 months instead of default 252

```

**V1 simplification:** Use 3-tuple form only, lookback at strategy level.

### Available Metrics

Based on existing `seon.trading.signals` primitives:

| Metric | Type | Range | Description |
|--------|------|-------|-------------|
| `:metric/iv-rank` | percentile | [0, 1] | Current IV percentile vs history |
| `:metric/skew-index` | spread | [-0.2, 0.2] | 25-delta put-call IV spread |
| `:metric/term-slope` | slope | [-0.01, 0.01] | IV term structure slope |
| `:metric/put-call-ratio` | ratio | [0, 5] | Put/call volume ratio |
| `:metric/gamma-rent` | ratio | [0, 1] | Gamma/theta cost ratio |

### Operators

| Operator | Description | Value Type |
|----------|-------------|------------|
| `:>` | Greater than | number |
| `:<` | Less than | number |
| `:>=` | Greater than or equal | number |
| `:<=` | Less than or equal | number |
| `:=` | Equal | number |
| `:between` | In range (inclusive) | [low high] |
| `:crosses-above` | Crosses above threshold | number |
| `:crosses-below` | Crosses below threshold | number |

**Note on cross operators:** These require state (previous value), so V1 will only support simple comparison operators. Crosses require tracking previous values during backtesting.

---

## Malli Schema

```clojure
(ns seon.algorithmic-trading.schema
  (:require [malli.core :as m]))

;;; ---------------------------------------------------------------------------
;;; Primitive Types
;;; ---------------------------------------------------------------------------

(def Metric
  "Valid metric keywords"
  [:enum
   :metric/iv-rank
   :metric/skew-index
   :metric/term-slope
   :metric/put-call-ratio
   :metric/gamma-rent])

(def ComparisonOperator
  "Comparison operators for conditions"
  [:enum :> :< :>= :<= := :between])

(def CrossOperator
  "Cross operators (require state tracking)"
  [:enum :crosses-above :crosses-below])

(def Operator
  "All valid operators"
  [:or ComparisonOperator CrossOperator])

;;; ---------------------------------------------------------------------------
;;; Condition Types
;;; ---------------------------------------------------------------------------

(def SimpleCondition
  "A simple condition comparing a metric to a value.
   Format: [metric operator value]"
  [:tuple Metric ComparisonOperator [:or :double [:tuple :double :double]]])

(def ExtendedCondition
  "A condition with optional lookback override"
  [:map
   [:metric Metric]
   [:op Operator]
   [:value [:or :double [:tuple :double :double]]]
   [:lookback {:optional true} [:int {:min 1 :max 504}]]
   [:description {:optional true} :string]])

(def Condition
  "A condition can be simple or extended"
  [:or SimpleCondition ExtendedCondition])

;; Forward declaration for recursive schema
(def LogicalExpression)

(def AndExpression
  "AND combinator: all conditions must be true"
  [:cat [:= :and] [:+ [:or Condition [:ref ::LogicalExpression]]]])

(def OrExpression
  "OR combinator: any condition must be true"
  [:cat [:= :or] [:+ [:or Condition [:ref ::LogicalExpression]]]])

(def LogicalExpression
  "A logical combination of conditions"
  [:or AndExpression OrExpression])

(def ConditionSet
  "Entry or exit conditions - either a single condition,
   a vector of conditions (implicit AND), or a logical expression"
  [:or
   Condition                              ; Single condition
   [:vector Condition]                    ; Implicit AND (vector of conditions)
   LogicalExpression])                    ; Explicit AND/OR

;;; ---------------------------------------------------------------------------
;;; Position Sizing
;;; ---------------------------------------------------------------------------

(def PositionSizeType
  [:enum :fixed :percent :risk-based])

(def FixedSize
  [:map
   [:type [:= :fixed]]
   [:contracts [:int {:min 1}]]])

(def PercentSize
  [:map
   [:type [:= :percent]]
   [:value [:double {:min 0.01 :max 1.0}]]])  ; 1% to 100%

(def RiskBasedSize
  [:map
   [:type [:= :risk-based]]
   [:max-risk [:double {:min 0.001 :max 0.1}]]  ; Max 10% portfolio at risk
   [:stop-loss [:double {:min 0.01 :max 0.5}]]])  ; 1% to 50% stop

(def PositionSize
  [:or FixedSize PercentSize RiskBasedSize])

;;; ---------------------------------------------------------------------------
;;; Risk Limits
;;; ---------------------------------------------------------------------------

(def RiskLimits
  [:map
   [:max-position-size {:optional true} [:double {:min 0.01 :max 0.5}]]
   [:max-drawdown {:optional true} [:double {:min 0.01 :max 0.5}]]
   [:max-open-positions {:optional true} [:int {:min 1 :max 100}]]
   [:min-days-between-trades {:optional true} [:int {:min 0 :max 30}]]])

;;; ---------------------------------------------------------------------------
;;; Strategy Types
;;; ---------------------------------------------------------------------------

(def StrategyType
  [:enum :long-vol :short-vol :neutral :directional])

;;; ---------------------------------------------------------------------------
;;; Full Strategy Schema
;;; ---------------------------------------------------------------------------

(def Strategy
  "Complete strategy definition"
  [:map
   [:strategy/id :uuid]
   [:strategy/name :string]
   [:strategy/description {:optional true} :string]
   [:strategy/type StrategyType]
   [:strategy/ticker :string]  ; Single ticker for V1
   [:strategy/lookback {:optional true} [:int {:min 1 :max 504}]]  ; Default 252
   [:strategy/entry-conditions ConditionSet]
   [:strategy/exit-conditions ConditionSet]
   [:strategy/position-size PositionSize]
   [:strategy/risk-limits {:optional true} RiskLimits]
   [:strategy/created-at {:optional true} inst?]
   [:strategy/updated-at {:optional true} inst?]])

;;; ---------------------------------------------------------------------------
;;; V1 Simplified Strategy (recommended for initial implementation)
;;; ---------------------------------------------------------------------------

(def V1Strategy
  "Simplified strategy for V1 - single ticker, implicit AND, no crosses"
  [:map
   [:strategy/id :uuid]
   [:strategy/name [:string {:min 1 :max 100}]]
   [:strategy/description {:optional true} :string]
   [:strategy/type StrategyType]
   [:strategy/ticker [:string {:min 1 :max 10}]]
   [:strategy/lookback {:optional true} [:int {:min 1 :max 504}]]
   [:strategy/entry-conditions [:vector SimpleCondition]]  ; Implicit AND
   [:strategy/exit-conditions [:vector SimpleCondition]]   ; Implicit AND
   [:strategy/position-size PositionSize]
   [:strategy/risk-limits {:optional true} RiskLimits]])

```

---

## V1 Scope

### In Scope

1. **Single ticker** - One underlying per strategy
2. **Simple conditions** - 3-tuple format `[metric op value]`
3. **Implicit AND** - All entry conditions must be true
4. **Comparison operators only** - No cross operators (require state)
5. **Fixed lookback** - Strategy-level, not per-condition
6. **Basic position sizing** - Fixed, percent, or risk-based
7. **Basic risk limits** - Max position, max drawdown

### Out of Scope (V2+)

1. Multi-ticker strategies (pairs, sector rotation)
2. Cross operators (`:crosses-above`, `:crosses-below`)
3. Per-condition lookback overrides
4. Explicit AND/OR logical expressions
5. Time-based conditions (only trade Monday-Friday, etc.)
6. Correlation-based conditions
7. Option-specific conditions (strike selection, expiry)

### Validation Requirements

The validator must ensure:

1. **All metrics are relative** - Only metrics from approved list
2. **Values are bounded** - Within reasonable ranges for each metric
3. **At least one entry/exit condition** - Non-empty
4. **Position size is valid** - Positive, within limits
5. **Risk limits are reasonable** - Not contradictory

```clojure
(defn validate-strategy
  "Validate a strategy against the schema and semantic rules."
  [strategy]
  (let [schema-valid? (m/validate V1Strategy strategy)
        schema-errors (when-not schema-valid?
                        (m/explain V1Strategy strategy))]
    (if schema-valid?
      ;; Semantic validation
      (let [semantic-errors (validate-semantics strategy)]
        (if (empty? semantic-errors)
          {:valid? true}
          {:valid? false :errors semantic-errors}))
      {:valid? false
       :errors (format-malli-errors schema-errors)})))

(defn validate-semantics
  "Check semantic rules beyond schema validation."
  [strategy]
  (cond-> []
    ;; Check metric value ranges
    (some #(invalid-value-range? %)
          (:strategy/entry-conditions strategy))
    (conj {:path [:entry-conditions]
           :message "Condition value out of valid range for metric"})

    ;; Check position size not greater than max-position-size limit
    (position-exceeds-limit? strategy)
    (conj {:path [:position-size :risk-limits]
           :message "Position size exceeds risk limit"})))

```

---

## Example Strategies

### 1. High IV Mean Reversion (Short Vol)

```clojure
{:strategy/id #uuid "550e8400-e29b-41d4-a716-446655440001"
 :strategy/name "High IV Mean Reversion"
 :strategy/description "Sell premium when IV is elevated, exit when normalizes"
 :strategy/type :short-vol
 :strategy/ticker "SPY"
 :strategy/lookback 252
 :strategy/entry-conditions
 [[:metric/iv-rank :> 0.80]     ; IV rank above 80th percentile
  [:metric/skew-index :> 0.04]] ; Elevated put skew (fear)
 :strategy/exit-conditions
 [[:metric/iv-rank :< 0.50]]    ; IV normalized
 :strategy/position-size
 {:type :percent
  :value 0.05}  ; 5% of portfolio
 :strategy/risk-limits
 {:max-position-size 0.10
  :max-drawdown 0.15}}

```

### 2. Low IV Long Volatility

```clojure
{:strategy/id #uuid "550e8400-e29b-41d4-a716-446655440002"
 :strategy/name "Low IV Long Volatility"
 :strategy/description "Buy premium when IV is cheap, exit when elevated"
 :strategy/type :long-vol
 :strategy/ticker "QQQ"
 :strategy/lookback 252
 :strategy/entry-conditions
 [[:metric/iv-rank :< 0.20]         ; IV rank below 20th percentile
  [:metric/gamma-rent :> 0.05]]     ; Cheap gamma
 :strategy/exit-conditions
 [[:metric/iv-rank :> 0.60]]        ; IV elevated
 :strategy/position-size
 {:type :risk-based
  :max-risk 0.02
  :stop-loss 0.20}
 :strategy/risk-limits
 {:max-position-size 0.08
  :max-drawdown 0.10}}

```

### 3. Contango Calendar Spread

```clojure
{:strategy/id #uuid "550e8400-e29b-41d4-a716-446655440003"
 :strategy/name "Contango Calendar"
 :strategy/description "Trade calendar spreads in steep contango"
 :strategy/type :neutral
 :strategy/ticker "SPY"
 :strategy/lookback 126
 :strategy/entry-conditions
 [[:metric/term-slope :> 0.0005]    ; Steep contango
  [:metric/iv-rank :between [0.30 0.70]]]  ; Neutral IV
 :strategy/exit-conditions
 [[:metric/term-slope :< 0.0001]]   ; Contango flattens
 :strategy/position-size
 {:type :fixed
  :contracts 1}
 :strategy/risk-limits
 {:max-position-size 0.05
  :min-days-between-trades 5}}

```

### 4. Bearish Sentiment Fade

```clojure
{:strategy/id #uuid "550e8400-e29b-41d4-a716-446655440004"
 :strategy/name "Bearish Sentiment Fade"
 :strategy/description "Fade extreme bearish sentiment (contrarian)"
 :strategy/type :directional
 :strategy/ticker "IWM"
 :strategy/entry-conditions
 [[:metric/put-call-ratio :> 1.5]   ; Heavy put buying
  [:metric/skew-index :> 0.06]]     ; Very elevated skew
 :strategy/exit-conditions
 [[:metric/put-call-ratio :< 1.0]   ; Sentiment normalizes
  [:metric/iv-rank :< 0.40]]        ; IV contracts
 :strategy/position-size
 {:type :percent
  :value 0.03}
 :strategy/risk-limits
 {:max-drawdown 0.12}}

```

---

## Future Extensions (V2+)

### Multi-Ticker Strategy

```clojure
;; V2: Pairs trading
{:strategy/name "SPY-QQQ Mean Reversion"
 :strategy/type :pairs
 :strategy/tickers {:primary "SPY" :secondary "QQQ"}
 :strategy/entry-conditions
 [[:metric/iv-rank :primary :> 0.70]
  [:metric/iv-rank :secondary :< 0.40]
  [:metric/spread [:- [:iv-rank :primary] [:iv-rank :secondary]] :> 0.30]]
 ...}

```

### Cross Operators

```clojure
;; V2: With state tracking
{:strategy/entry-conditions
 [[:metric/iv-rank :crosses-above 0.80]]}  ; Enter on breakout, not on already high

```

### Explicit AND/OR

```clojure
;; V2: Complex logic
{:strategy/entry-conditions
 [:and
  [:metric/iv-rank :> 0.70]
  [:or
   [:metric/skew-index :> 0.05]
   [:metric/term-slope :< -0.0003]]]}

```

---

## Recommendation Summary

### Chosen Approach

**Nested data expressions** using Clojure vectors with keyword operators:

```clojure
[[:metric/iv-rank :> 0.80]
 [:metric/skew-index :> 0.04]]

```

### Rationale

1. **Pure data** - Vectors and keywords, no code execution needed
2. **Natural Clojure** - Idiomatic, uses standard data structures
3. **Easy validation** - Malli schemas can validate recursively
4. **Serializable** - EDN/JSON round-trips cleanly
5. **Extensible** - Add operators/metrics without schema changes
6. **LLM-friendly** - Clear structure an agent can construct
7. **Backtest-safe** - No way to peek at future data (only approved metrics)

### V1 Scope

- Single ticker
- Implicit AND (vector of conditions)
- Comparison operators only
- Strategy-level lookback
- Three position sizing modes
- Basic risk limits

This provides a minimal but useful foundation. Real trading strategies can be built and backtested with this specification.

---

## References

- [QuantConnect Algorithm Framework](https://www.quantconnect.com/docs/v2/writing-algorithms/algorithm-framework/overview)
- [Zipline Pipeline Factors](https://github.com/quantopian/zipline/blob/master/zipline/pipeline/factors/factor.py)
- [Pine Script Strategies](https://www.tradingview.com/pine-script-docs/concepts/strategies/)
- [Backtrader Signal Strategy](https://www.backtrader.com/docu/signal_strategy/signal_strategy/)
- [QuantJourney SDL](https://quantjourney.substack.com/p/introducing-the-quantjourneys-strategy)
- [Martin Fowler DSL Guide](https://martinfowler.com/dsl.html)
- [YAML as DSL](https://medium.com/@pavelpotapenkov/advocating-yaml-as-dsl-7f5fe695fba9)
